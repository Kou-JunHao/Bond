package com.bond.transfer.acl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.entity.WorkspaceAcl;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.enums.WorkspaceRole;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.WorkspaceAclMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.service.AuditService;
import com.bond.transfer.trash.FileTree;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import com.bond.transfer.acl.WorkspaceAclDtos.Grant;
import com.bond.transfer.acl.WorkspaceAclDtos.PutRequest;
import com.bond.transfer.acl.WorkspaceAclDtos.View;

/**
 * 工作区文件级 ACL（V34 / task-183B）：范围解析 + 强制点 + 授权管理。
 *
 * <p><b>语义（MVP，已写进 docs/workspace-acl-design.md）</b></p>
 * <ul>
 *   <li>成员级开关 {@code workspace_members.access_mode}：{@code all}（默认，行为与历史完全一致）
 *       或 {@code restricted}；<b>owner/admin 永远等价 all</b>，其 access_mode 列被忽略。</li>
 *   <li>{@code workspace_acl} 一行 = 一条授权：folder 授权覆盖该目录及其全部子路径（按路径段匹配，
 *       {@code /a} 不覆盖 {@code /ab}）；file 授权只覆盖该 fileId 对应文件。</li>
 *   <li>读拒绝 → 404「文件不存在」（不泄露存在性）；写拒绝 → 403「无权…」。</li>
 *   <li>创建分享链接时用 {@link #requireShareable}：restricted 成员不能把「自己也无权读的父目录」
 *       整包分享出去（否则等于越权导出）。</li>
 * </ul>
 *
 * <p><b>为什么强制点放在 controller/service 边界而不是 WorkspaceService 内部：</b>
 * 本轮主工作区有上一会话的未提交 WIP（WorkspaceService/TrashService 等），按任务约束不回改那些文件；
 * 所有 REST 入口（WorkspaceController / TrashController / FileSearchService / 分享创建）在此 service
 * 内统一收口。已知缺口：WebDAV、聊天附件、工作区文档暂未接入（见设计文档「剩余缺口」）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceAclService {

    public static final String MODE_ALL = "all";
    public static final String MODE_RESTRICTED = "restricted";
    public static final String SCOPE_FOLDER = "folder";
    public static final String SCOPE_FILE = "file";

    /** 单个成员最多授权条数（防止扫描式整树授权把每次请求放大成 N 次比对）。 */
    static final int MAX_GRANTS_PER_MEMBER = 100;
    static final int MAX_SCOPE_PATH_LENGTH = 1024;

    private final WorkspaceAclMapper aclMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceFileMapper fileMapper;
    private final AuditService auditService;

    // ------------------------------------------------------------------
    // 有效范围解析
    // ------------------------------------------------------------------

    /**
     * 解析成员在该工作区的有效访问范围。
     *
     * <p>非成员返回 {@code unrestricted}：成员校验（403）由调用方负责，这里不做越权判断的兜底，
     * 避免把「非成员」误表达成「受限成员」。owner/admin 返回 unrestricted。</p>
     */
    public WorkspaceAccess resolve(Long workspaceId, Long userId) {
        WorkspaceMember member = member(workspaceId, userId);
        if (member == null || isPrivileged(member)) {
            return WorkspaceAccess.unrestricted();
        }
        if (!MODE_RESTRICTED.equalsIgnoreCase(member.getAccessMode())) {
            return WorkspaceAccess.unrestricted();
        }
        List<WorkspaceAcl> grants = aclMapper.selectList(new LambdaQueryWrapper<WorkspaceAcl>()
                .eq(WorkspaceAcl::getWorkspaceId, workspaceId)
                .eq(WorkspaceAcl::getUserId, userId));
        return WorkspaceAccess.restricted(grants);
    }

    public boolean isRestricted(Long workspaceId, Long userId) {
        return resolve(workspaceId, userId).isRestricted();
    }

    /**
     * 批量找出「我在其中是 restricted 成员」的工作区（搜索用：受限工作区暂不做路径级检索，
     * 直接排除，保证不泄露未授权内容；见设计文档剩余缺口）。
     */
    public Set<Long> restrictedWorkspaceIds(Long userId, Collection<Long> workspaceIds) {
        if (userId == null || workspaceIds == null || workspaceIds.isEmpty()) {
            return Set.of();
        }
        List<WorkspaceMember> rows = memberMapper.selectList(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getUserId, userId)
                .in(WorkspaceMember::getWorkspaceId, workspaceIds)
                .eq(WorkspaceMember::getAccessMode, MODE_RESTRICTED));
        Set<Long> restricted = new LinkedHashSet<>();
        for (WorkspaceMember row : rows) {
            if (!isPrivileged(row)) {
                restricted.add(row.getWorkspaceId());
            }
        }
        return restricted;
    }

    // ------------------------------------------------------------------
    // 强制点（REST 入口调用）
    // ------------------------------------------------------------------

    /** 读取文件（下载/预览/缩略图/office-pdf 签发令牌前）：未授权按 404，不泄露存在性。 */
    public void requireReadable(Long workspaceId, Long userId, WorkspaceFile file) {
        WorkspaceAccess access = resolve(workspaceId, userId);
        if (access.isRestricted() && !access.canReadFile(file.getId(), logicalPath(file))) {
            throw new BusinessException(404, "文件不存在");
        }
    }

    /** 改写/删除/移动文件或目录：未授权按 403（明确拒绝；对象是否存在与拒绝无关）。 */
    public void requireWritable(Long workspaceId, Long userId, WorkspaceFile file) {
        WorkspaceAccess access = resolve(workspaceId, userId);
        if (!access.isRestricted()) {
            return;
        }
        boolean allowed = Boolean.TRUE.equals(file.getIsDirectory())
                ? access.canWriteDirectory(logicalPath(file))
                : access.canWriteFile(file.getId(), logicalPath(file));
        if (!allowed) {
            throw new BusinessException(403, "无权修改该文件或目录");
        }
    }

    /** 列出/读取目录：未授权按 404。 */
    public void requireReadableDirectory(Long workspaceId, Long userId, String dirPath) {
        WorkspaceAccess access = resolve(workspaceId, userId);
        if (access.isRestricted() && !access.canReadDirectory(dirPath)) {
            throw new BusinessException(404, "目录不存在");
        }
    }

    /** 上传/建目录到目标目录：未授权按 403。 */
    public void requireWritableDirectory(Long workspaceId, Long userId, String dirPath) {
        WorkspaceAccess access = resolve(workspaceId, userId);
        if (access.isRestricted() && !access.canWriteDirectory(dirPath)) {
            throw new BusinessException(403, "无权在该目录下写入");
        }
    }

    /**
     * 创建分享前的可分享性校验。
     *
     * <p>restricted 成员分享文件：必须对该文件有读权限（否则 404）；
     * 分享文件夹：必须是**目录授权（can_read）覆盖该目录**，仅靠「授权在目录之下」的遍历权限
     * 不够 —— 否则成员可以把父目录整包分享，等于越权导出未授权内容。</p>
     */
    public void requireShareable(Long workspaceId, Long userId, WorkspaceFile target) {
        WorkspaceAccess access = resolve(workspaceId, userId);
        if (!access.isRestricted()) {
            return;
        }
        if (Boolean.TRUE.equals(target.getIsDirectory())) {
            if (!access.isDirectoryCoveredByGrant(logicalPath(target))) {
                throw new BusinessException(403, "无权分享该目录");
            }
            return;
        }
        if (!access.canReadFile(target.getId(), logicalPath(target))) {
            throw new BusinessException(404, "文件不存在");
        }
    }

    // ------------------------------------------------------------------
    // 授权管理（owner/admin）
    // ------------------------------------------------------------------

    /** 读取某成员的 ACL（owner/admin）。 */
    public View memberAcl(Long operatorId, Long workspaceId, Long targetUserId) {
        requireAdmin(workspaceId, operatorId);
        WorkspaceMember target = member(workspaceId, targetUserId);
        if (target == null) {
            throw new BusinessException(404, "工作区成员不存在");
        }
        if (isPrivileged(target)) {
            return new View(workspaceId, targetUserId, MODE_ALL, List.of());
        }
        String mode = MODE_RESTRICTED.equalsIgnoreCase(target.getAccessMode()) ? MODE_RESTRICTED : MODE_ALL;
        List<Grant> grants = MODE_RESTRICTED.equals(mode) ? toGrants(grantsOf(workspaceId, targetUserId)) : List.of();
        return new View(workspaceId, targetUserId, mode, grants);
    }

    /** 本人有效范围（任意成员；owner/admin 返回 all + 空授权）。 */
    public View myAcl(Long userId, Long workspaceId) {
        WorkspaceMember member = member(workspaceId, userId);
        if (member == null) {
            throw new BusinessException(403, "无权访问该工作区");
        }
        if (isPrivileged(member)) {
            return new View(workspaceId, userId, MODE_ALL, List.of());
        }
        String mode = MODE_RESTRICTED.equalsIgnoreCase(member.getAccessMode()) ? MODE_RESTRICTED : MODE_ALL;
        List<Grant> grants = MODE_RESTRICTED.equals(mode) ? toGrants(grantsOf(workspaceId, userId)) : List.of();
        return new View(workspaceId, userId, mode, grants);
    }

    /**
     * 整包替换某成员的授权（owner/admin）。
     *
     * <p>事务内：更新 access_mode → 删除旧授权 → 插入新授权。{@code accessMode=all} 时只清授权。
     * 校验：目标必须是非 owner/admin 的成员；每条授权必须指向真实存在且属于该工作区的目录/文件；
     * can_write 强制同时 can_read；同路径重复授权按 OR 合并。</p>
     */
    @Transactional
    public View replaceAcl(Long operatorId, Long workspaceId, Long targetUserId,
                           PutRequest request, String clientIp) {
        requireAdmin(workspaceId, operatorId);
        WorkspaceMember target = member(workspaceId, targetUserId);
        if (target == null) {
            throw new BusinessException(404, "工作区成员不存在");
        }
        if (isPrivileged(target)) {
            throw new BusinessException(400, "空间所有者/管理员始终拥有全部权限，无需设置 ACL");
        }

        String mode = request == null || isBlank(request.accessMode())
                ? MODE_ALL : request.accessMode().trim().toLowerCase(Locale.ROOT);
        if (!MODE_ALL.equals(mode) && !MODE_RESTRICTED.equals(mode)) {
            throw new BusinessException(400, "accessMode 只能是 all 或 restricted");
        }

        List<WorkspaceAcl> rows;
        if (MODE_ALL.equals(mode)) {
            rows = List.of();
        } else {
            List<Grant> requested = request == null || request.grants() == null ? List.of() : request.grants();
            if (requested.size() > MAX_GRANTS_PER_MEMBER) {
                throw new BusinessException(400, "单成员授权条数上限 " + MAX_GRANTS_PER_MEMBER + " 条");
            }
            rows = normalizeGrants(workspaceId, targetUserId, requested);
        }

        target.setAccessMode(mode);
        memberMapper.updateById(target);
        aclMapper.delete(new LambdaQueryWrapper<WorkspaceAcl>()
                .eq(WorkspaceAcl::getWorkspaceId, workspaceId)
                .eq(WorkspaceAcl::getUserId, targetUserId));
        for (WorkspaceAcl row : rows) {
            row.setId(SnowflakeId.nextId());
            row.setCreatedAt(OffsetDateTime.now());
            row.setCreatedBy(operatorId);
            aclMapper.insert(row);
        }

        auditService.record(operatorId, null, "workspace.acl.update", "workspace", workspaceId,
                "{\"targetUserId\":" + targetUserId + ",\"accessMode\":\"" + mode
                        + "\",\"grants\":" + rows.size() + "}", clientIp);
        log.info("workspace acl replaced: workspace={}, target={}, mode={}, grants={}, by={}",
                workspaceId, targetUserId, mode, rows.size(), operatorId);
        return new View(workspaceId, targetUserId, mode, toGrants(rows));
    }

    /**
     * 回收站批量操作前的写权限校验（restore / purge）：按 id 批量取文件行（一次查询），
     * 任一命中行位于 restricted 成员未授权的范围 → 403（整批拒绝，不泄露具体哪一条越权）。
     *
     * <p>文档（documents）不在本期 ACL 范围：没有 workspace_files 行的 id 视为文档，跳过（见设计文档）。</p>
     */
    public void requireWritableTrashEntries(Long userId, List<Long> ids) {
        if (userId == null || ids == null || ids.isEmpty()) {
            return;
        }
        List<WorkspaceFile> rows = fileMapper.selectBatchIds(ids);
        for (WorkspaceFile row : rows) {
            WorkspaceAccess access = resolve(row.getWorkspaceId(), userId);
            if (!access.isRestricted()) {
                continue;
            }
            boolean allowed = Boolean.TRUE.equals(row.getIsDirectory())
                    ? access.canWriteDirectory(logicalPath(row))
                    : access.canWriteFile(row.getId(), logicalPath(row));
            if (!allowed) {
                throw new BusinessException(403, "无权操作该回收站条目");
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 校验并归一化请求授权：路径规范化、存在性校验、can_write ⇒ can_read、同键合并。 */
    private List<WorkspaceAcl> normalizeGrants(Long workspaceId, Long targetUserId, List<Grant> requested) {
        Map<String, WorkspaceAcl> merged = new LinkedHashMap<>();
        for (Grant grant : requested) {
            if (grant == null) {
                continue;
            }
            String type = isBlank(grant.scopeType()) ? "" : grant.scopeType().trim().toLowerCase(Locale.ROOT);
            boolean canWrite = grant.canWrite();
            boolean canRead = grant.canRead() || canWrite;
            if (!canRead) {
                throw new BusinessException(400, "每条授权至少需要 canRead 或 canWrite 之一");
            }
            String key;
            WorkspaceAcl row = new WorkspaceAcl();
            row.setWorkspaceId(workspaceId);
            row.setUserId(targetUserId);
            row.setScopeType(type);
            row.setCanRead(canRead);
            row.setCanWrite(canWrite);
            if (SCOPE_FOLDER.equals(type)) {
                if (grant.fileId() != null) {
                    throw new BusinessException(400, "folder 授权不能携带 fileId");
                }
                String path = normalizeScopePath(grant.scopePath());
                requireDirectoryExists(workspaceId, path);
                row.setScopePath(path);
                row.setFileId(null);
                key = SCOPE_FOLDER + ":" + path;
            } else if (SCOPE_FILE.equals(type)) {
                if (grant.fileId() == null) {
                    throw new BusinessException(400, "file 授权必须提供 fileId");
                }
                WorkspaceFile file = fileMapper.selectById(grant.fileId());
                if (file == null || file.getDeletedAt() != null
                        || !workspaceId.equals(file.getWorkspaceId())
                        || Boolean.TRUE.equals(file.getIsDirectory())) {
                    throw new BusinessException(400, "file 授权指向的文件不存在或不属于该工作区");
                }
                String path = logicalPath(file);
                row.setScopePath(path);
                row.setFileId(file.getId());
                key = SCOPE_FILE + ":" + file.getId();
            } else {
                throw new BusinessException(400, "scopeType 只能是 folder 或 file");
            }
            WorkspaceAcl existing = merged.get(key);
            if (existing == null) {
                merged.put(key, row);
            } else {
                // 同路径重复授权按 OR 合并，避免唯一索引冲突
                existing.setCanRead(Boolean.TRUE.equals(existing.getCanRead()) || canRead);
                existing.setCanWrite(Boolean.TRUE.equals(existing.getCanWrite()) || canWrite);
            }
        }
        for (WorkspaceAcl row : merged.values()) {
            if (Boolean.TRUE.equals(row.getCanWrite())) {
                row.setCanRead(true);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 规范化授权路径：/ 开头、无尾斜杠、无 . / .. / 空段、长度上限。 */
    static String normalizeScopePath(String raw) {
        if (isBlank(raw)) {
            throw new BusinessException(400, "授权路径不能为空");
        }
        String path = raw.trim();
        if (!path.startsWith("/")) {
            throw new BusinessException(400, "授权路径必须以 / 开头");
        }
        if (path.length() > MAX_SCOPE_PATH_LENGTH) {
            throw new BusinessException(400, "授权路径过长");
        }
        if (path.contains("//")) {
            throw new BusinessException(400, "授权路径包含空路径段");
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException(400, "授权路径包含非法片段");
            }
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 授权目录必须真实存在（根目录 "/" 隐式存在）；不存在按 400（授权意图明确，无需 404）。 */
    private void requireDirectoryExists(Long workspaceId, String dirPath) {
        if ("/".equals(dirPath)) {
            return;
        }
        int idx = dirPath.lastIndexOf('/');
        String parent = idx <= 0 ? "/" : dirPath.substring(0, idx);
        String name = dirPath.substring(idx + 1);
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, workspaceId)
                .eq(WorkspaceFile::getParentPath, parent)
                .eq(WorkspaceFile::getFileName, name)
                .eq(WorkspaceFile::getIsDirectory, true)
                .isNull(WorkspaceFile::getDeletedAt));
        if (count == null || count == 0) {
            throw new BusinessException(400, "授权目录不存在：" + dirPath);
        }
    }

    private void requireAdmin(Long workspaceId, Long operatorId) {
        WorkspaceMember member = member(workspaceId, operatorId);
        if (member == null) {
            throw new BusinessException(403, "无权访问该工作区");
        }
        if (!isPrivileged(member)) {
            throw new BusinessException(403, "只有空间所有者或管理员可以管理成员权限");
        }
    }

    private WorkspaceMember member(Long workspaceId, Long userId) {
        if (workspaceId == null || userId == null) {
            return null;
        }
        return memberMapper.selectOne(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
    }

    private List<WorkspaceAcl> grantsOf(Long workspaceId, Long userId) {
        return aclMapper.selectList(new LambdaQueryWrapper<WorkspaceAcl>()
                .eq(WorkspaceAcl::getWorkspaceId, workspaceId)
                .eq(WorkspaceAcl::getUserId, userId)
                .orderByAsc(WorkspaceAcl::getScopeType)
                .orderByAsc(WorkspaceAcl::getScopePath));
    }

    private static boolean isPrivileged(WorkspaceMember member) {
        Integer role = member.getRole();
        return role != null && role <= WorkspaceRole.ADMIN.getValue();
    }

    /** 行的逻辑路径（与 FileTree 一致：parent_path + "/" + file_name）。 */
    public static String logicalPath(WorkspaceFile file) {
        return FileTree.originalPath(file);
    }

    private static List<Grant> toGrants(List<WorkspaceAcl> rows) {
        List<Grant> grants = new ArrayList<>(rows.size());
        for (WorkspaceAcl row : rows) {
            grants.add(new Grant(row.getScopeType(), row.getScopePath(), row.getFileId(),
                    Boolean.TRUE.equals(row.getCanRead()), Boolean.TRUE.equals(row.getCanWrite())));
        }
        return grants;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
