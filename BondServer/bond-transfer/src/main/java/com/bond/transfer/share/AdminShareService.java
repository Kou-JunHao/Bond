package com.bond.transfer.share;

import com.bond.common.dto.AdminShareDTO;
import com.bond.common.dto.PageResult;
import com.bond.common.entity.ShareLink;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.AdminShareRow;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.service.AuditService;
import com.bond.transfer.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理端分享链接治理（task-89）：分页列表 + 强制撤销。
 *
 * <p>安全边界：只读元数据，不返回文件内容、不返回任何签名 URL；
 * 公网地址由 {@code share.public_base_url} 拼出（与匿名落地页同源）。
 * 撤销复用 {@link ShareLinkMapper#revokeById} 的原子 SQL（status=0 才生效），
 * 已撤销/不存在统一 404，与落地页失效语义一致。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminShareService {

    private static final String CFG_PUBLIC_BASE_URL = "share.public_base_url";
    private static final String DEFAULT_PUBLIC_BASE_URL = "https://bond.example.com";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int TOKEN_PREFIX_IN_AUDIT = 8;
    private static final String ACTION_REVOKE = "share.revoke";
    private static final String TARGET_TYPE = "share_link";

    private final ShareLinkMapper shareLinkMapper;
    private final UserMapper userMapper;
    private final ConfigService configService;
    private final AuditService auditService;

    public PageResult<AdminShareDTO> list(int page, int size, Integer status,
                                          String ownerKeyword, String tokenPrefix) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String keyword = blankToNull(ownerKeyword);
        String prefix = blankToNull(tokenPrefix);
        long total = shareLinkMapper.countAdmin(status, keyword, prefix);
        List<AdminShareDTO> items = total == 0
                ? List.of()
                : shareLinkMapper.selectAdminPage(status, keyword, prefix, safeSize,
                        (long) (safePage - 1) * safeSize)
                        .stream().map(this::toDto).toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    /**
     * 强制撤销。
     *
     * @param id      分享链接 id
     * @param actorId 管理员 id（来自网关签名的 X-User-Id）
     * @param ip      客户端 IP（审计用）
     */
    public void revoke(Long id, Long actorId, String ip) {
        ShareLink link = shareLinkMapper.selectById(id);
        if (link == null || (link.getStatus() != null && link.getStatus() == 1)) {
            throw new BusinessException(404, "分享链接不存在或已撤销");
        }
        shareLinkMapper.revokeById(id);
        String actorName = displayName(actorId);
        String ownerName = displayName(link.getCreatorId());
        String tokenPrefix = tokenPrefix(link.getToken());
        String detail = "{\"tokenPrefix\":\"" + tokenPrefix + "\""
                + ",\"ownerId\":" + link.getCreatorId()
                + ",\"ownerUsername\":\"" + nullSafe(ownerName) + "\"}";
        auditService.record(actorId, actorName, ACTION_REVOKE, TARGET_TYPE, id, detail, ip);
    }

    private AdminShareDTO toDto(AdminShareRow row) {
        AdminShareDTO dto = new AdminShareDTO();
        dto.setId(row.getId());
        dto.setToken(row.getToken());
        dto.setUrl(publicUrl(row.getToken()));
        dto.setFileId(row.getFileId());
        dto.setFileName(row.getFileName());
        dto.setOwnerId(row.getOwnerId());
        dto.setOwnerUsername(row.getOwnerUsername());
        dto.setStatus(row.getStatus() == null ? 0 : row.getStatus());
        dto.setExpiresAt(toMillis(row.getExpiresAt()));
        dto.setDownloadCount(row.getDownloadCount() == null ? 0 : row.getDownloadCount());
        dto.setCreatedAt(toMillis(row.getCreatedAt()));
        // 无独立 last_access_at 列：updated_at 会在下载计数/撤销等变更时刷新，作为最近活动时间近似。
        dto.setLastAccessAt(toMillis(row.getUpdatedAt()));
        return dto;
    }

    private String publicUrl(String token) {
        String base = configService.get(CFG_PUBLIC_BASE_URL, DEFAULT_PUBLIC_BASE_URL);
        if (base == null || base.isBlank()) {
            base = "";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/s/" + token;
    }

    private String displayName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    private static String tokenPrefix(String token) {
        if (token == null) {
            return "";
        }
        return token.length() <= TOKEN_PREFIX_IN_AUDIT
                ? token
                : token.substring(0, TOKEN_PREFIX_IN_AUDIT);
    }

    private static Long toMillis(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toEpochMilli();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }
}
