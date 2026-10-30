package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.AnnouncementDTO;
import com.bond.common.dto.AnnouncementUpsertRequest;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.Announcement;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.AnnouncementMapper;
import com.bond.transfer.mapper.AnnouncementReadCountRow;
import com.bond.transfer.mapper.AnnouncementRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统公告服务（任务单 #19，V15）。
 *
 * <p>可见性以数据库时间为准（now()），避免多实例服务器本地时钟不一致。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnnouncementService {

    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int USER_LIST_LIMIT = 50;
    private static final Set<String> LEVELS = Set.of("info", "warn", "critical");
    private static final String CFG_MAX_CONTENT_CHARS = "announcement.max_content_chars";
    private static final int DEFAULT_MAX_CONTENT_CHARS = 10_000;

    private final AnnouncementMapper announcementMapper;

    private final ConfigService configService;

    /** 当前用户可见公告（已读标记一并返回，不自动标已读）。 */
    public List<AnnouncementDTO> listForUser(Long userId) {
        List<AnnouncementRow> rows = announcementMapper.selectVisible(userId, USER_LIST_LIMIT);
        List<AnnouncementDTO> result = new ArrayList<>(rows.size());
        for (AnnouncementRow row : rows) {
            AnnouncementDTO dto = new AnnouncementDTO();
            dto.setId(row.getId());
            dto.setTitle(row.getTitle());
            dto.setContent(row.getContent());
            dto.setLevel(row.getLevel());
            dto.setPublishedAtMillis(toMillis(row.getPublishedAt()));
            dto.setExpiresAtMillis(toMillis(row.getExpiresAt()));
            dto.setRead(Boolean.TRUE.equals(row.getRead()));
            result.add(dto);
        }
        return result;
    }

    public long unreadCount(Long userId) {
        return announcementMapper.countUnread(userId);
    }

    /** 幂等：重复标记不报错；不存在/不可见的公告返回 404（不泄露草稿存在性）。 */
    public void markRead(Long userId, Long announcementId) {
        Announcement announcement = announcementMapper.selectById(announcementId);
        if (announcement == null || announcement.getPublishedAt() == null
                || announcement.getPublishedAt().isAfter(OffsetDateTime.now())
                || (announcement.getExpiresAt() != null && !announcement.getExpiresAt().isAfter(OffsetDateTime.now()))) {
            throw new BusinessException(404, "公告不存在");
        }
        announcementMapper.markRead(SnowflakeId.nextId(), announcementId, userId);
    }

    /** 管理台分页列表（含草稿/已过期），并带当前页的已读人数。 */
    public PageResult<AnnouncementDTO> listForAdmin(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>()
                .orderByDesc(Announcement::getCreatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);
        List<Announcement> rows = announcementMapper.selectList(wrapper);
        long total = announcementMapper.selectCount(null);

        List<Long> ids = rows.stream().map(Announcement::getId).toList();
        Map<Long, Long> readCounts = ids.isEmpty()
                ? Map.of()
                : announcementMapper.countReads(ids).stream()
                        .filter(r -> r.getAnnouncementId() != null && r.getCnt() != null)
                        .collect(Collectors.toMap(AnnouncementReadCountRow::getAnnouncementId,
                                AnnouncementReadCountRow::getCnt, (a, b) -> a));

        List<AnnouncementDTO> items = rows.stream().map(a -> toAdminDto(a, readCounts.getOrDefault(a.getId(), 0L))).toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    public AnnouncementDTO create(Long adminUserId, AnnouncementUpsertRequest request) {
        Announcement announcement = new Announcement();
        applyValidated(announcement, request);
        announcement.setCreatedBy(adminUserId);
        announcement.setCreatedAt(OffsetDateTime.now());
        announcement.setUpdatedAt(OffsetDateTime.now());
        announcementMapper.insert(announcement);
        log.info("announcement created: id={}, level={}, publishAt={}, by={}",
                announcement.getId(), announcement.getLevel(), announcement.getPublishedAt(), adminUserId);
        return toAdminDto(announcement, 0L);
    }

    public AnnouncementDTO update(Long adminUserId, Long announcementId, AnnouncementUpsertRequest request) {
        Announcement announcement = announcementMapper.selectById(announcementId);
        if (announcement == null) {
            throw new BusinessException(404, "公告不存在");
        }
        applyValidated(announcement, request);
        announcement.setUpdatedAt(OffsetDateTime.now());
        announcementMapper.updateById(announcement);
        // task-109：MP updateById 会跳过 null 字段；expiresAt=null 表示“取消过期时间”，需显式清空
        if (request.getExpiresAt() == null) {
            announcementMapper.clearExpiresAt(announcementId);
            announcement.setExpiresAt(null);
        }
        log.info("announcement updated: id={}, by={}", announcementId, adminUserId);
        long readCount = announcementMapper.countReads(List.of(announcementId)).stream()
                .filter(r -> r.getAnnouncementId() != null && r.getCnt() != null)
                .mapToLong(AnnouncementReadCountRow::getCnt)
                .findFirst()
                .orElse(0L);
        return toAdminDto(announcement, readCount);
    }

    public void delete(Long adminUserId, Long announcementId) {
        Announcement announcement = announcementMapper.selectById(announcementId);
        if (announcement == null) {
            throw new BusinessException(404, "公告不存在");
        }
        // announcement_reads 通过外键 ON DELETE CASCADE 一并清理。
        announcementMapper.deleteById(announcementId);
        log.info("announcement deleted: id={}, by={}", announcementId, adminUserId);
    }

    private void applyValidated(Announcement announcement, AnnouncementUpsertRequest request) {
        if (request == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        String title = request.getTitle() == null ? "" : request.getTitle().trim();
        if (title.isEmpty()) {
            throw new BusinessException(400, "公告标题不能为空");
        }
        if (title.length() > MAX_TITLE_CHARS) {
            throw new BusinessException(400, "公告标题不能超过 " + MAX_TITLE_CHARS + " 字符");
        }
        String content = request.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(400, "公告正文不能为空");
        }
        int maxContentChars = configService.getInt(CFG_MAX_CONTENT_CHARS, DEFAULT_MAX_CONTENT_CHARS);
        if (maxContentChars > 0 && content.length() > maxContentChars) {
            throw new BusinessException(400, "公告正文不能超过 " + maxContentChars + " 字符");
        }
        String level = request.getLevel() == null ? "info" : request.getLevel().trim().toLowerCase(Locale.ROOT);
        if (level.isEmpty()) {
            level = "info";
        }
        if (!LEVELS.contains(level)) {
            throw new BusinessException(400, "公告级别只能是 info/warn/critical");
        }
        OffsetDateTime publishedAt = request.getPublishedAt() == null ? OffsetDateTime.now() : request.getPublishedAt();
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(publishedAt)) {
            throw new BusinessException(400, "过期时间必须晚于发布时间");
        }
        announcement.setTitle(title);
        announcement.setContent(content);
        announcement.setLevel(level);
        announcement.setPublishedAt(publishedAt);
        announcement.setExpiresAt(request.getExpiresAt());
    }

    private AnnouncementDTO toAdminDto(Announcement announcement, long readCount) {
        AnnouncementDTO dto = new AnnouncementDTO();
        dto.setId(announcement.getId());
        dto.setTitle(announcement.getTitle());
        dto.setContent(announcement.getContent());
        dto.setLevel(announcement.getLevel());
        dto.setPublishedAtMillis(toMillis(announcement.getPublishedAt()));
        dto.setExpiresAtMillis(toMillis(announcement.getExpiresAt()));
        dto.setCreatedAtMillis(toMillis(announcement.getCreatedAt()));
        dto.setReadCount(readCount);
        return dto;
    }

    private static Long toMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }
}
