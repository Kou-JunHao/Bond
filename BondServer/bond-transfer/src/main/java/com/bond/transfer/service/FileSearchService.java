package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.FileSearchItem;
import com.bond.common.dto.FileSearchResult;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * task-117：文件搜索（个人空间 + 我可访问的工作区）。
 *
 * <p>契约要点（客户端据此对接，见 {@code docs/API-CONTRACT.md} §4）：</p>
 * <ul>
 *   <li>{@code q} trim 后为空或超过 100 字符 → 400；大小写不敏感（DB 侧 ILIKE）；</li>
 *   <li>不传 workspaceId 时搜「个人空间 + 我是成员的工作区」；传了则校验成员（非成员 403）；</li>
 *   <li>只搜未删除项（{@code deleted_at IS NULL}，目录也返回）；</li>
 *   <li>排序：名称前缀命中优先 → {@code updated_at DESC} → {@code id DESC}；keyset 分页，不用 offset。</li>
 * </ul>
 *
 * <p>所有用户输入都通过 Mapper 的 {@code #{}} 参数绑定传给 PostgreSQL（含 ILIKE 模式串），
 * 不存在字符串拼 SQL。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSearchService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final int MAX_QUERY_LENGTH = 100;

    private final WorkspaceFileMapper fileMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final PersonalSpaceService personalSpaceService;
    private final WorkspaceService workspaceService;
    /** task-183B：restricted 成员的工作区没有路径级检索能力，直接排除（见设计文档剩余缺口）。 */
    private final com.bond.transfer.acl.WorkspaceAclService workspaceAclService;

    /**
     * @param userId      当前用户（网关注入，已验签）
     * @param q           关键词（必填，trim 后非空且 ≤100 字符）
     * @param workspaceId 可选：限定单个工作区；null = 个人空间 + 我是成员的全部工作区
     * @param limit       默认 50、上限 100（超出按 100 处理）
     * @param cursor      上一页返回的 nextCursor（非法 → 400）
     */
    public FileSearchResult search(Long userId, String q, Long workspaceId, Integer limit, String cursor) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            throw new BusinessException(400, "搜索关键词不能为空");
        }
        if (keyword.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(400, "搜索关键词过长（最多 " + MAX_QUERY_LENGTH + " 字符）");
        }
        int pageSize = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        List<Long> workspaceIds = resolveWorkspaceIds(userId, workspaceId);
        if (workspaceIds.isEmpty()) {
            return new FileSearchResult(List.of(), null, false, 0L);
        }

        String escaped = escapeLike(keyword);
        String pattern = "%" + escaped + "%";
        String prefixPattern = escaped + "%";

        CursorPosition position = decodeCursor(cursor);
        long total = fileMapper.countSearch(workspaceIds, pattern);
        List<WorkspaceFile> rows = fileMapper.search(workspaceIds, pattern, prefixPattern,
                position == null ? null : position.updatedAt(),
                position == null ? null : position.id(),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<WorkspaceFile> page = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : rows;
        List<FileSearchItem> items = page.stream().map(FileSearchService::toItem).toList();
        String nextCursor = hasMore && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : null;
        return new FileSearchResult(items, nextCursor, hasMore, total);
    }

    /** 空间范围：显式 workspaceId 需成员校验；否则个人空间 + 我是成员的工作区（去重）。 */
    private List<Long> resolveWorkspaceIds(Long userId, Long workspaceId) {
        if (workspaceId != null) {
            workspaceService.ensureMember(workspaceId, userId);
            // task-183B：受限成员在该工作区只能看被授权路径；SQL 级路径过滤不在本期范围内，
            // 直接不给结果（返回空集合，保证「不泄露未授权内容」；客户端可展示「该空间已受限」）。
            return workspaceAclService.isRestricted(workspaceId, userId)
                    ? List.of()
                    : List.of(workspaceId);
        }
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(personalSpaceService.resolveWorkspaceId(userId));
        List<WorkspaceMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<WorkspaceMember>().eq(WorkspaceMember::getUserId, userId));
        memberships.forEach(member -> ids.add(member.getWorkspaceId()));
        // 排除「我在其中是 restricted 成员」的工作区：避免搜索结果泄露未授权文件
        ids.removeAll(workspaceAclService.restrictedWorkspaceIds(userId, ids));
        return new ArrayList<>(ids);
    }

    private static FileSearchItem toItem(WorkspaceFile file) {
        return new FileSearchItem(
                file.getId(),
                file.getFileName(),
                joinPath(file.getParentPath(), file.getFileName()),
                Boolean.TRUE.equals(file.getIsDirectory()),
                file.getFileSize(),
                file.getContentType(),
                file.getUpdatedAt() == null ? null : file.getUpdatedAt().toInstant().toEpochMilli(),
                file.getWorkspaceId());
    }

    /** 由 parent_path + file_name 拼出 POSIX 风格绝对路径；根目录归一为 "/"。 */
    static String joinPath(String parentPath, String fileName) {
        String name = fileName == null ? "" : fileName;
        if (parentPath == null || parentPath.isBlank() || "/".equals(parentPath)) {
            return "/" + name;
        }
        String parent = parentPath.startsWith("/") ? parentPath : "/" + parentPath;
        while (parent.endsWith("/") && parent.length() > 1) {
            parent = parent.substring(0, parent.length() - 1);
        }
        return parent + "/" + name;
    }

    static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String encodeCursor(WorkspaceFile file) {
        String raw = file.getUpdatedAt().toString() + "|" + file.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf('|');
            if (split <= 0 || split == raw.length() - 1) {
                throw new IllegalArgumentException("missing separator");
            }
            OffsetDateTime updatedAt = OffsetDateTime.parse(raw.substring(0, split));
            Long id = Long.valueOf(raw.substring(split + 1));
            return new CursorPosition(updatedAt, id);
        } catch (RuntimeException ex) {
            throw new BusinessException(400, "cursor 非法");
        }
    }

    private record CursorPosition(OffsetDateTime updatedAt, Long id) {
    }
}
