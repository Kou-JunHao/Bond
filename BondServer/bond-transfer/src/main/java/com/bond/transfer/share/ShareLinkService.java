package com.bond.transfer.share;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bond.common.entity.ShareLink;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.acl.WorkspaceAclService;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.preview.RangeParser;
import com.bond.transfer.service.AuditService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.storage.ObjectStorage;
import com.bond.transfer.trash.FileTree;
import com.bond.transfer.share.ShareDtos.CreateShareRequest;
import com.bond.transfer.share.ShareDtos.PatchShareRequest;
import com.bond.transfer.share.ShareDtos.ShareLinkView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 分享链接服务（登录创建/管理 + 匿名访问）。
 *
 * <p>计费与次数的口径（Lead 定稿）：</p>
 * <ul>
 *   <li>计费 = {@code usage_counters.relay_bytes_down}，按**实际下行字节**累加，记在空间所有者头上；</li>
 *   <li>{@code download_count} 只用于「下载次数限制 / 审计」，Range 续传不重复计；
 *       计费与次数口径不同，勿混用；</li>
 *   <li>提取码只存 HMAC 摘要；失败 5 次锁 10 分钟（system_configs 可调）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareLinkService {

    /** 提取码 pepper 的环境变量（生产必须配置；开发降级并告警）。 */
    private static final String ENV_CODE_PEPPER = "SHARE_CODE_PEPPER";
    private static final String ENV_FALLBACK_PEPPER = "INTERNAL_GATEWAY_SECRET";
    private static final String DEV_PEPPER = "bond-dev-share-pepper";

    private static final String CFG_CODE_MAX_ATTEMPTS = "share.code_max_attempts";
    private static final String CFG_CODE_LOCK_MINUTES = "share.code_lock_minutes";
    private static final String CFG_RATE_LIMIT_PER_MINUTE = "share.rate_limit_per_minute";
    private static final String CFG_PUBLIC_BASE_URL = "share.public_base_url";

    private static final int MAX_TOKEN_INSERT_RETRIES = 3;
    private static final int RATE_WINDOW_MAX_ENTRIES = 10_000;
    /** task-102：永久链接缺省下载次数（之后仍按档位 share_max_downloads 收敛）。 */
    private static final int DEFAULT_PERMANENT_MAX_DOWNLOADS = 100;

    // ── task-183 文件夹分享（ZIP） ──
    /** 文件夹分享落地页最多展示的首层条目数（超出提示「下载 ZIP 查看全部」）。 */
    static final int MAX_LANDING_ITEMS = 200;
    /** 单次 ZIP 打包的最大条目数（含目录行）；超出返回 400，避免一次请求把整棵大树拉爆。 */
    static final int MAX_ZIP_ENTRIES = 20_000;
    /** 文件夹分享 ZIP 的下载类型。 */
    static final String ZIP_CONTENT_TYPE = "application/zip";

    private final ShareLinkMapper shareLinkMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final ObjectStorage objectStorage;
    private final UsageService usageService;
    private final EntitlementResolver entitlementResolver;
    private final ConfigService configService;
    private final AuditService auditService;
    private final UserMapper userMapper;
    /** task-183B：创建分享前校验 restricted 成员的可分享范围。 */
    private final WorkspaceAclService workspaceAclService;

    /** 匿名访问限流窗口：ip + token + 分钟 → 次数（进程内，足够单实例；多实例部署后续可换 Redis）。 */
    private final Map<String, Integer> rateWindow = new ConcurrentHashMap<>();
    private final AtomicBoolean pepperWarned = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // 登录接口：创建 / 列表 / 撤销
    // ------------------------------------------------------------------

    /** 创建分享链接。 */
    @Transactional
    public ShareLinkView create(Long userId, CreateShareRequest request) {
        if (request == null || request.fileId() == null) {
            throw new BusinessException(400, "fileId 不能为空");
        }
        WorkspaceFile file = workspaceFileMapper.selectById(request.fileId());
        if (file == null || file.getDeletedAt() != null) {
            // 回收站中的文件对外等同不存在；软删不是物理删，但要先恢复再分享
            throw new BusinessException(404, "文件不存在");
        }
        if (Boolean.TRUE.equals(file.getIsDirectory())) {
            // task-183：文件夹分享 —— share_links.file_id 直接指向目录行（不新增 is_directory 列，
            // 目标类型以 workspace_files.is_directory 为准；目录行 id 稳定、改名/移动不影响链接）。
            // 生命周期语义与单文件一致：目录进回收站/被删 → 落地页与下载 410。
            log.info("share link targets a folder: fileId={}, name={}", file.getId(), file.getFileName());
        }
        Long workspaceId = request.workspaceId() != null ? request.workspaceId() : file.getWorkspaceId();
        if (!workspaceId.equals(file.getWorkspaceId())) {
            throw new BusinessException(400, "文件不属于该空间");
        }
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(404, "空间不存在");
        }
        ensureReadable(workspace, userId);
        // task-183B：restricted 成员只能分享自己可读范围内的内容；分享目录要求目录授权覆盖整棵子树
        workspaceAclService.requireShareable(workspaceId, userId, file);

        Entitlements entitlements = entitlementResolver.resolve(userId);
        PlanLimits limits = entitlements.limits();
        if (limits.shareMaxLinks() > 0
                && shareLinkMapper.countActiveByCreator(userId) >= limits.shareMaxLinks()) {
            throw new BusinessException(403, "分享链接数量已达当前档位上限（" + limits.shareMaxLinks() + " 个）");
        }

        boolean requiresCode = Boolean.TRUE.equals(request.requiresCode());
        String code = request.code() == null ? null : request.code().trim();
        if (requiresCode) {
            if (code == null || code.isBlank()) {
                throw new BusinessException(400, "启用提取码时必须提供提取码");
            }
            if (!ShareCode.isWellFormedCode(code)) {
                throw new BusinessException(400, "提取码需为 4-8 位字母或数字");
            }
        } else {
            code = null;
        }

        OffsetDateTime expiresAt = resolveExpiresAt(request.expiresDays(), entitlements, limits);
        int maxDownloads = resolveMaxDownloads(request.maxDownloads(), expiresAt == null, limits);

        ShareLink link = new ShareLink();
        link.setToken(ShareCode.newToken());
        link.setFileId(file.getId());
        link.setWorkspaceId(workspaceId);
        link.setCreatorId(userId);
        link.setRequiresCode(requiresCode);
        link.setCodeHash(requiresCode ? ShareCode.hashCode(pepper(), link.getToken(), code) : null);
        link.setFailedAttempts(0);
        link.setExpiresAt(expiresAt);
        link.setMaxDownloads(maxDownloads);
        link.setDownloadCount(0);
        link.setStatus(0);
        OffsetDateTime now = OffsetDateTime.now();
        link.setCreatedAt(now);
        link.setUpdatedAt(now);

        insertWithTokenRetry(link, requiresCode ? code : null);
        log.info("share link created: linkId={}, fileId={}, directory={}, creator={}, requiresCode={}, permanent={}, maxDownloads={}",
                link.getId(), file.getId(), Boolean.TRUE.equals(file.getIsDirectory()), userId,
                requiresCode, expiresAt == null, maxDownloads);
        return toView(link, file);
    }

    /** 我创建的分享链接（按创建时间倒序）。 */
    public List<ShareLinkView> list(Long userId) {
        return list(userId, null);
    }

    /**
     * 我创建的分享链接（按创建时间倒序）；{@code workspaceId} 不为空时只返回该空间内的链接。
     *
     * <p>此前控制器签收了 {@code workspaceId} 却忽略它，属于"看起来支持实则不生效"的坏契约：
     * 这里既有 SQL 侧过滤，也在内存里做一次防御性过滤（每用户有效链接数有档位上限，量很小）。</p>
     */
    public List<ShareLinkView> list(Long userId, Long workspaceId) {
        List<ShareLink> links = shareLinkMapper.selectList(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getCreatorId, userId)
                .eq(workspaceId != null, ShareLink::getWorkspaceId, workspaceId)
                .orderByDesc(ShareLink::getCreatedAt));
        if (workspaceId != null) {
            links = links.stream()
                    .filter(link -> workspaceId.equals(link.getWorkspaceId()))
                    .toList();
        }
        if (links.isEmpty()) {
            return List.of();
        }
        Set<Long> fileIds = new HashSet<>();
        for (ShareLink link : links) {
            fileIds.add(link.getFileId());
        }
        List<WorkspaceFile> files = workspaceFileMapper.selectBatchIds(fileIds);
        Map<Long, WorkspaceFile> byId = new ConcurrentHashMap<>();
        for (WorkspaceFile file : files) {
            byId.put(file.getId(), file);
        }
        List<ShareLinkView> views = new ArrayList<>(links.size());
        for (ShareLink link : links) {
            WorkspaceFile file = byId.get(link.getFileId());
            views.add(toView(link, file));
        }
        return views;
    }

    /** 单个分享链接详情（创建者本人；或空间 owner）。 */
    public ShareLinkView get(Long userId, Long linkId) {
        ShareLink link = shareLinkMapper.selectById(linkId);
        if (link == null) {
            throw new BusinessException(404, "分享链接不存在");
        }
        if (!userId.equals(link.getCreatorId())) {
            Workspace workspace = workspaceMapper.selectById(link.getWorkspaceId());
            boolean owner = workspace != null && userId.equals(workspace.getOwnerId());
            if (!owner) {
                throw new BusinessException(403, "无权查看该分享链接");
            }
        }
        WorkspaceFile file = workspaceFileMapper.selectById(link.getFileId());
        return toView(link, file);
    }

    /** 撤销分享链接（创建者；或空间 owner/admin）。幂等。 */
    public void revoke(Long userId, Long linkId) {
        ShareLink link = shareLinkMapper.selectById(linkId);
        if (link == null) {
            throw new BusinessException(404, "分享链接不存在");
        }
        if (!userId.equals(link.getCreatorId())) {
            Workspace workspace = workspaceMapper.selectById(link.getWorkspaceId());
            boolean owner = workspace != null && userId.equals(workspace.getOwnerId());
            WorkspaceMember member = member(link.getWorkspaceId(), userId);
            boolean admin = member != null && member.getRole() != null && member.getRole() <= 1;
            if (!owner && !admin) {
                throw new BusinessException(403, "无权撤销该分享链接");
            }
        }
        shareLinkMapper.revokeById(linkId);
        log.info("share link revoked: linkId={}, by={}", linkId, userId);
    }

    /**
     * 修改分享链接（task-163，仅创建者本人）。
     *
     * <p>三态语义：字段缺失 = 不改；显式 {@code null} = 清空（取消提取码 / 永久 / 下载次数不限）；
     * 其他 = 设置。已撤销（status=1）不可修改；已过期是时间判定而非状态位，
     * 改一个未来的 {@code expiresAt} 即可重新生效（并把定时任务打的 status=2 复位为 0）。</p>
     */
    @Transactional
    public ShareLinkView patch(Long userId, Long linkId, PatchShareRequest request, String clientIp) {
        if (request == null || (request.extractCode() == null
                && request.expiresAtMillis() == null && request.maxDownloads() == null)) {
            throw new BusinessException(400, "至少提供一个需要修改的字段");
        }
        ShareLink link = shareLinkMapper.selectById(linkId);
        if (link == null) {
            throw new BusinessException(404, "分享链接不存在");
        }
        if (!userId.equals(link.getCreatorId())) {
            throw new BusinessException(403, "无权修改该分享链接");
        }
        if (link.getStatus() != null && link.getStatus() == 1) {
            throw new BusinessException(400, "已撤销的分享不能再修改");
        }

        Entitlements entitlements = entitlementResolver.resolve(userId);
        PlanLimits limits = entitlements.limits();
        OffsetDateTime now = OffsetDateTime.now();

        Boolean requiresCode = null;
        String codeHash = null;
        if (request.extractCode() != null) {
            String plain = request.extractCode().isNull() ? null : request.extractCode().asText("").trim();
            if (plain == null || plain.isEmpty()) {
                requiresCode = false;
                codeHash = null;
            } else {
                if (!ShareCode.isWellFormedCode(plain)) {
                    throw new BusinessException(400, "提取码需为 4-8 位字母或数字");
                }
                requiresCode = true;
                codeHash = ShareCode.hashCode(pepper(), link.getToken(), plain);
            }
        }

        boolean expiresChanged = request.expiresAtMillis() != null;
        OffsetDateTime expiresAt = link.getExpiresAt();
        if (expiresChanged) {
            JsonNode node = request.expiresAtMillis();
            if (node.isNull()) {
                if (!permanentShareAllowed(entitlements, limits)) {
                    throw new BusinessException(400, "当前档位不支持永久链接，请设置有效期");
                }
                expiresAt = null;
            } else if (node.canConvertToLong()) {
                OffsetDateTime requestedAt = OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(node.asLong()), ZoneOffset.UTC);
                if (!requestedAt.isAfter(now)) {
                    throw new BusinessException(400, "有效期必须晚于当前时间");
                }
                int planDays = limits.shareExpiresDays();
                if (planDays > 0 && requestedAt.isAfter(now.plusDays(planDays))) {
                    throw new BusinessException(400, "有效期不能超过当前档位上限（" + planDays + " 天）");
                }
                expiresAt = requestedAt;
            } else {
                throw new BusinessException(400, "expiresAtMillis 必须是时间戳或 null");
            }
        }

        boolean maxChanged = request.maxDownloads() != null;
        Integer maxDownloads = link.getMaxDownloads() == null ? 0 : link.getMaxDownloads();
        int downloaded = link.getDownloadCount() == null ? 0 : link.getDownloadCount();
        if (maxChanged) {
            JsonNode node = request.maxDownloads();
            if (node.isNull()) {
                maxDownloads = 0;
            } else if (node.canConvertToInt()) {
                int requested = node.asInt();
                if (requested < 0) {
                    throw new BusinessException(400, "下载次数上限不能为负数");
                }
                int cap = limits.shareMaxDownloads();
                if (requested > 0 && cap > 0 && requested > cap) {
                    throw new BusinessException(400, "下载次数上限不能超过当前档位上限（" + cap + " 次）");
                }
                if (requested > 0 && requested <= downloaded) {
                    throw new BusinessException(400, "已下载 " + downloaded + " 次，上限不能低于该值");
                }
                maxDownloads = requested;
            } else {
                throw new BusinessException(400, "maxDownloads 必须是整数或 null");
            }
        }

        UpdateWrapper<ShareLink> update = new UpdateWrapper<>();
        update.eq("id", linkId).set("updated_at", now).set("max_downloads", maxDownloads);
        if (requiresCode != null) {
            update.set("requires_code", requiresCode);
            if (requiresCode) {
                update.set("code_hash", codeHash);
            } else {
                update.setSql("code_hash = NULL");
            }
        }
        if (expiresChanged) {
            if (expiresAt == null) {
                update.setSql("expires_at = NULL");
            } else {
                update.set("expires_at", expiresAt);
            }
            update.set("status", 0);
            link.setStatus(0);
        }
        shareLinkMapper.update(null, update);

        if (requiresCode != null) {
            link.setRequiresCode(requiresCode);
            link.setCodeHash(codeHash);
        }
        if (expiresChanged) {
            link.setExpiresAt(expiresAt);
        }
        link.setMaxDownloads(maxDownloads);
        link.setUpdatedAt(now);

        String detail = "{\"extractCode\":\"" + (requiresCode == null ? "unchanged"
                        : (requiresCode ? "replaced" : "cancelled"))
                + "\",\"expiresAt\":\"" + (!expiresChanged ? "unchanged"
                        : (expiresAt == null ? "permanent" : "updated"))
                + "\",\"maxDownloads\":\"" + (!maxChanged ? "unchanged"
                        : (maxDownloads == 0 ? "unlimited" : String.valueOf(maxDownloads)))
                + "\"}";
        auditService.record(userId, actorName(userId), "share.update", "share", linkId, detail, clientIp);
        log.info("share link patched: linkId={}, by={}, detail={}", linkId, userId, detail);

        WorkspaceFile file = workspaceFileMapper.selectById(link.getFileId());
        return toView(link, file);
    }

    private boolean permanentShareAllowed(Entitlements entitlements, PlanLimits limits) {
        // 免费档位不允许永久链接（task-163）；付费档或「不限天数」档位允许
        if (limits.shareExpiresDays() == 0) {
            return true;
        }
        String planCode = entitlements.planCode();
        return planCode != null && !"free".equalsIgnoreCase(planCode);
    }

    private String actorName(Long userId) {
        try {
            User user = userMapper.selectById(userId);
            if (user != null) {
                return user.getNickname() != null && !user.getNickname().isBlank()
                        ? user.getNickname() : user.getUsername();
            }
        } catch (RuntimeException e) {
            log.debug("load actor name failed: userId={}, error={}", userId, e.toString());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 匿名接口：落地页 / 提取码校验 / 下载
    // ------------------------------------------------------------------

    /**
     * 匿名落地页 HTML（task-163：信息卡/提取码输入/大按钮/失效态/深色模式；不含隐私信息）。
     *
     * <p>task-183：目标为目录时展示「文件夹分享」样式 —— 首层子项列表（只读；目录以 / 结尾）、
     * 首层条目数，以及「下载全部（ZIP）」入口；ZIP 走同一 {@code /s/{token}/download}，
     * 提取码校验成功后的跳转逻辑与单文件完全一致。</p>
     */
    public String landingHtml(String token) {
        ShareLink link = loadValidLink(token);
        WorkspaceFile file = workspaceFileMapper.selectById(link.getFileId());
        if (file == null || file.getDeletedAt() != null) {
            // 回收站期间链接不删、但不再可下载/落地（410 可恢复；彻底删除时连带删行）
            throw new BusinessException(410, "分享的文件已不存在");
        }
        boolean directory = Boolean.TRUE.equals(file.getIsDirectory());
        String rawName = file.getFileName() == null
                ? (directory ? "未命名文件夹" : "未命名文件")
                : file.getFileName();
        String fileName = htmlEscape(rawName);
        String created = link.getCreatedAt() == null ? "—" : link.getCreatedAt().toLocalDate().toString();
        String expiry = link.getExpiresAt() == null ? "永久有效" : "有效期至 " + link.getExpiresAt().toLocalDate();
        String remain = link.getMaxDownloads() != null && link.getMaxDownloads() > 0
                ? "剩余下载次数 " + Math.max(0, link.getMaxDownloads()
                        - (link.getDownloadCount() == null ? 0 : link.getDownloadCount()))
                : "下载次数不限";
        boolean requiresCode = Boolean.TRUE.equals(link.getRequiresCode());
        if (directory) {
            FolderListing listing = folderListing(file);
            String itemCount = listing.total() >= MAX_LANDING_ITEMS
                    ? "首层 " + MAX_LANDING_ITEMS + "+ 项"
                    : "首层 " + listing.total() + " 项";
            String action = requiresCode ? codeForm(token) : bigDownloadButton(token, "下载全部（ZIP）");
            String body = """
                      <div class="card">
                        <div class="head">
                          <span class="badge">ZIP</span>
                          <div>
                            <h1>Bond 文件夹分享</h1>
                            <div class="name">%s/</div>
                          </div>
                        </div>
                        <div class="meta">
                          <span>%s</span><span>创建于 %s</span><span>%s</span><span>%s</span>
                        </div>
                        %s
                        %s
                        <div class="tip">ZIP 由服务端实时流式打包，不落盘；下载按 1 次计。链接由用户创建，遇到问题请让创建者重新分享。</div>
                      </div>
                    """.formatted(fileName, htmlEscape(itemCount), created, expiry, remain,
                    folderItemsHtml(listing), action);
            return page("Bond 文件夹分享 - " + fileName, body);
        }
        String size = formatSize(file.getFileSize() == null ? 0L : file.getFileSize());
        String action = requiresCode ? codeForm(token) : bigDownloadButton(token, "下载文件");
        String body = """
                  <div class="card">
                    <div class="head">
                      <span class="badge">%s</span>
                      <div>
                        <h1>Bond 文件分享</h1>
                        <div class="name">%s</div>
                      </div>
                    </div>
                    <div class="meta">
                      <span>%s</span><span>创建于 %s</span><span>%s</span><span>%s</span>
                    </div>
                    %s
                    <div class="tip">链接由用户创建；下载仅用于流量统计与次数限制。遇到问题请让创建者重新分享。</div>
                  </div>
                """.formatted(typeBadge(rawName), fileName, size, created, expiry, remain, action);
        return page("Bond 文件分享 - " + fileName, body);
    }

    /** 文件夹分享落地页的首层子项（只读、仅展示目录名/文件名/大小；不泄露 uploadedBy 等成员信息）。 */
    private record FolderListing(List<WorkspaceFile> items, int total) {
    }

    private FolderListing folderListing(WorkspaceFile dir) {
        String base = FileTree.childBase(dir);
        List<WorkspaceFile> rows = workspaceFileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, dir.getWorkspaceId())
                .eq(WorkspaceFile::getParentPath, base)
                .isNull(WorkspaceFile::getDeletedAt)
                .orderByDesc(WorkspaceFile::getIsDirectory)
                .orderByAsc(WorkspaceFile::getFileName));
        if (rows.size() > MAX_LANDING_ITEMS) {
            return new FolderListing(new ArrayList<>(rows.subList(0, MAX_LANDING_ITEMS)), MAX_LANDING_ITEMS);
        }
        return new FolderListing(rows, rows.size());
    }

    private static String folderItemsHtml(FolderListing listing) {
        if (listing.items().isEmpty()) {
            return "<p class=\"desc\">这个文件夹还是空的。</p>";
        }
        StringBuilder sb = new StringBuilder("<ul class=\"items\">");
        for (WorkspaceFile item : listing.items()) {
            boolean isDir = Boolean.TRUE.equals(item.getIsDirectory());
            String name = htmlEscape(item.getFileName() == null ? "" : item.getFileName());
            sb.append("<li><span class=\"ibadge").append(isDir ? " dir" : "").append("\">")
                    .append(isDir ? "DIR" : htmlEscape(typeBadge(item.getFileName() == null ? "" : item.getFileName())))
                    .append("</span><span class=\"iname\">").append(name).append(isDir ? "/" : "")
                    .append("</span><span class=\"isize\">")
                    .append(isDir ? "" : formatSize(item.getFileSize() == null ? 0L : item.getFileSize()))
                    .append("</span></li>");
        }
        if (listing.total() >= MAX_LANDING_ITEMS) {
            sb.append("<li class=\"more\">仅展示前 ").append(MAX_LANDING_ITEMS).append(" 项，下载 ZIP 查看全部</li>");
        }
        return sb.append("</ul>").toString();
    }

    /**
     * 失效落地页（task-163）：撤销/过期/次数用尽/不存在都给中文 HTML，
     * 且**不泄露**文件名、大小、创建者。
     */
    public String invalidLandingHtml(BusinessException error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        String title;
        String desc;
        if (error != null && error.getCode() == 404) {
            title = "分享链接不存在";
            desc = "链接可能已被删除，或地址不完整、被截断。";
        } else if (message.contains("撤销")) {
            title = "分享已被取消";
            desc = "创建者已取消这条分享链接，内容不再对外提供。";
        } else if (message.contains("过期")) {
            title = "分享已过期";
            desc = "这条分享已超过有效期，请联系创建者重新分享。";
        } else if (message.contains("次数")) {
            title = "下载次数已用完";
            desc = "这条分享已达到下载次数上限，不再提供下载。";
        } else if (message.contains("文件")) {
            title = "文件已不可用";
            desc = "分享的文件已被删除或移入回收站，暂时无法下载。";
        } else {
            title = "分享不可用";
            desc = "这条分享当前无法访问，请联系创建者确认。";
        }
        String body = """
                  <div class="card">
                    <div class="head">
                      <span class="badge warn">!</span>
                      <div><h1>%s</h1></div>
                    </div>
                    <p class="desc">%s</p>
                    <a class="btn ghost" href="/">返回首页</a>
                  </div>
                """.formatted(htmlEscape(title), htmlEscape(desc));
        return page("Bond - " + htmlEscape(title), body);
    }

    /** 失效落地页对应的 HTTP 状态：不存在 404，其余（撤销/过期/次数/文件删除）410。 */
    public int landingStatus(BusinessException error) {
        return error != null && error.getCode() == 404 ? 404 : 410;
    }

    /**
     * 提取码表单（含内联 JS：回车提交、错误红字 + 抖动，无外部依赖）。
     *
     * <p>容错（2026-10-29）：先看 HTTP 状态与 {@code content-type} 再决定怎么报错——
     * 只有 JSON 响应才取 {@code message}；网关/nginx 的空 body 403、502 或 HTML 错误页
     * 一律提示「服务暂时不可用（HTTP xxx）」，只有 fetch 本身失败（断网/被拦截）才说
     * 「网络不可达」。历史上无条件 {@code r.json()} 会把网关 CORS 403 空 body 误报成
     * 「网络异常」，把服务端问题伪装成用户网络问题。</p>
     */
    private static String codeForm(String token) {
        return "<form id=\"dl\" autocomplete=\"off\">"
                + "<input id=\"code\" type=\"text\" inputmode=\"latin\" maxlength=\"8\" "
                + "placeholder=\"请输入 4-8 位提取码\" aria-label=\"提取码\" />"
                + "<button type=\"submit\" class=\"btn\">下载</button>"
                + "</form><div class=\"err\" id=\"err\" role=\"alert\"></div>"
                + "<script>(function(){var f=document.getElementById('dl'),i=document.getElementById('code'),"
                + "e=document.getElementById('err');"
                + "function bad(m){e.textContent=m;e.style.display='block';"
                + "i.classList.remove('shake');void i.offsetWidth;i.classList.add('shake');i.focus();}"
                + "f.addEventListener('submit',function(ev){ev.preventDefault();var c=(i.value||'').trim();"
                + "if(!c){bad('请输入提取码');return;}"
                + "fetch('/s/" + token + "/verify',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({code:c})})"
                + ".then(function(r){var ct=(r.headers&&r.headers.get)?(r.headers.get('content-type')||''):'';"
                + "if(ct.indexOf('json')<0){return {ok:r.ok,status:r.status,j:null};}"
                + "return r.json().then(function(j){return {ok:r.ok,status:r.status,j:j};},"
                + "function(){return {ok:r.ok,status:r.status,j:null};});})"
                + ".then(function(r){if(r.ok){window.location='/s/" + token + "/download?code='"
                + "+encodeURIComponent(c);}else if(r.j&&r.j.message){bad(r.j.message);}"
                + "else{bad('服务暂时不可用（HTTP '+r.status+'），请稍后重试');}})"
                + ".catch(function(){bad('网络不可达，请检查网络后重试');});});i.focus();})();</script>";
    }

    private static String bigDownloadButton(String token, String label) {
        return "<a class=\"btn big\" href=\"/s/" + token + "/download\">" + htmlEscape(label) + "</a>";
    }

    private static String typeBadge(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "FILE";
        }
        String ext = fileName.substring(dot + 1).toUpperCase();
        return ext.length() > 4 ? ext.substring(0, 4) : ext;
    }

    /** 页面外壳：viewport + safe-area + 深色模式 + 系统字体栈，无外部 CDN。 */
    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
                  <meta name="color-scheme" content="light dark" />
                  <meta name="robots" content="noindex,nofollow" />
                  <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
                  <meta name="theme-color" content="#E57153" />
                  <title>{{TITLE}}</title>
                  <style>
                    :root{color-scheme:light dark;--bg:#f5f6f8;--card:#fff;--text:#1f2329;--muted:#8a8f99;
                      --line:#d0d3d9;--accent:#E57153;--accent-h:#D46A4C;--warn-bg:#fff4f0}
                    @media (prefers-color-scheme:dark){
                      :root{--bg:#14161a;--card:#1e2126;--text:#e8eaed;--muted:#9aa0a6;--line:#3a3f46;--warn-bg:#2a1e1b}
                    }
                    *{box-sizing:border-box}
                    body{margin:0;padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
                      font-family:system-ui,-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
                      background:var(--bg);color:var(--text);-webkit-text-size-adjust:100%}
                    .card{max-width:440px;margin:10vh auto;background:var(--card);border-radius:16px;padding:28px 24px;
                      box-shadow:0 6px 28px rgba(0,0,0,.12)}
                    .head{display:flex;gap:14px;align-items:flex-start;margin-bottom:16px}
                    .badge{flex:0 0 auto;min-width:52px;height:52px;border-radius:12px;background:var(--warn-bg);
                      color:var(--accent);font-size:13px;font-weight:700;display:flex;align-items:center;
                      justify-content:center;padding:0 8px}
                    .badge.warn{color:#fff;background:#e5484d;font-size:22px}
                    h1{font-size:17px;margin:0 0 6px}
                    .name{font-weight:600;word-break:break-all;line-height:1.4}
                    .meta{display:flex;flex-wrap:wrap;gap:6px 14px;color:var(--muted);font-size:13px;margin-bottom:20px}
                    form{display:flex;gap:10px;flex-wrap:wrap;margin:0}
                    input{flex:1;min-width:0;padding:12px 14px;border:1px solid var(--line);border-radius:10px;
                      font-size:16px;background:transparent;color:var(--text)}
                    input:focus{outline:2px solid var(--accent);border-color:transparent}
                    .btn{display:inline-block;padding:12px 20px;border:0;border-radius:10px;background:var(--accent);
                      color:#fff;font-size:15px;font-weight:600;cursor:pointer;text-decoration:none;text-align:center}
                    .btn:active,.btn:hover{background:var(--accent-h)}
                    .btn.big{display:block;width:100%;padding:15px;font-size:16px}
                    .btn.ghost{background:transparent;color:var(--accent);border:1px solid var(--accent)}
                    .err{display:none;color:#e5484d;font-size:13px;margin-top:10px}
                    .desc{color:var(--muted);font-size:14px;line-height:1.7;margin:0 0 20px}
                    .tip{margin-top:18px;color:var(--muted);font-size:12px;line-height:1.6}
                    /* task-183：文件夹分享的首层子项列表（只读） */
                    .items{list-style:none;margin:0 0 18px;padding:0;border:1px solid var(--line);border-radius:10px;
                      max-height:300px;overflow:auto}
                    .items li{display:flex;align-items:center;gap:10px;padding:9px 12px;border-bottom:1px solid var(--line)}
                    .items li:last-child{border-bottom:0}
                    .ibadge{flex:0 0 auto;min-width:40px;text-align:center;font-size:10px;font-weight:700;
                      color:var(--accent);background:var(--warn-bg);border-radius:6px;padding:3px 5px}
                    .ibadge.dir{color:var(--muted)}
                    .iname{flex:1;min-width:0;word-break:break-all;font-size:14px}
                    .isize{flex:0 0 auto;color:var(--muted);font-size:12px}
                    .items li.more{color:var(--muted);font-size:12px;justify-content:center}
                    .shake{animation:sh .4s}
                    @keyframes sh{0%,100%{transform:translateX(0)}20%{transform:translateX(-6px)}40%{transform:translateX(6px)}60%{transform:translateX(-4px)}80%{transform:translateX(4px)}}
                    @media (max-width:480px){
                      .card{margin:6vh 14px;padding:22px 18px;border-radius:14px}
                      h1{font-size:16px}
                    }
                  </style>
                </head>
                <body>
                {{BODY}}
                </body>
                </html>
                """.replace("{{TITLE}}", title).replace("{{BODY}}", body);
    }

    /** 提取码校验（匿名）。成功则清零失败计数；失败按阈值锁定。 */
    public void verify(String token, String code, String clientIp) {
        ShareLink link = loadValidLink(token);
        rateLimit(link.getToken(), clientIp);
        checkCode(link, token, code);
    }

    /**
     * 准备匿名下载：校验链接/提取码/次数，返回流。
     *
     * <p>计数语义：完整下载（无 Range 或 Range 从 0 开始）才 +1；纯续传不重复计。
     * 计费不在这里做——{@link UsageService#meterAndThrottleDownload} 按实际写出字节累加。</p>
     */
    public ShareDownload prepareDownload(String token, String code, String rangeHeader, String clientIp) {
        ShareLink link = loadValidLink(token);
        rateLimit(link.getToken(), clientIp);
        checkCode(link, token, code);

        WorkspaceFile file = workspaceFileMapper.selectById(link.getFileId());
        if (file == null || Boolean.TRUE.equals(file.getIsDirectory()) || file.getDeletedAt() != null) {
            throw new BusinessException(410, "文件已不存在");
        }
        long totalSize = file.getFileSize() == null ? 0L : file.getFileSize();
        RangeParser.Spec spec = RangeParser.parse(rangeHeader, totalSize);
        if (spec.unsatisfiable()) {
            throw new PreviewService.UnsatisfiableRangeException(totalSize);
        }
        boolean fullDownload = !spec.partial() || spec.offset() == 0L;
        if (fullDownload) {
            if (shareLinkMapper.incrementDownloadCount(link.getId()) == 0) {
                throw new BusinessException(410, "分享链接下载次数已用尽");
            }
        } else {
            // 纯 Range 续传不计数，但更新最近访问时间（task-163）
            shareLinkMapper.touchLastAccess(link.getId());
        }

        Workspace workspace = workspaceMapper.selectById(link.getWorkspaceId());
        Long billingUserId = workspace != null && workspace.getOwnerId() != null
                ? workspace.getOwnerId()
                : link.getCreatorId();

        String path = file.getMinioPath();
        InputStream raw = spec.partial()
                ? objectStorage.getObject(path, spec.offset(), spec.length())
                : objectStorage.getObject(path);
        InputStream stream = usageService.meterAndThrottleDownload(billingUserId, raw);
        return new ShareDownload(file.getFileName(), file.getContentType(),
                totalSize, spec.offset(), spec.length(), spec.partial(), stream);
    }

    // ------------------------------------------------------------------
    // task-183：文件夹分享（ZIP 流式下载）
    // ------------------------------------------------------------------

    /**
     * 下载入口的统一规划：**先判定链接目标是文件还是目录**，再分别走单文件流 / ZIP 流。
     *
     * <p>返回的 {@code file}/{@code zip} 恰有一个非 null。文件夹链接只多一次
     * {@code workspace_files} 查询（分享链接的目录占比极低），换取 controller 无需猜测类型。</p>
     */
    public ShareDownloadPlan planDownload(String token, String code, String rangeHeader, String clientIp) {
        ShareLink link = loadValidLink(token);
        WorkspaceFile target = workspaceFileMapper.selectById(link.getFileId());
        if (target != null && Boolean.TRUE.equals(target.getIsDirectory())) {
            return new ShareDownloadPlan(null, prepareZipDownload(token, code, clientIp));
        }
        return new ShareDownloadPlan(prepareDownload(token, code, rangeHeader, clientIp), null);
    }

    /**
     * 准备文件夹分享的 ZIP 下载：校验链接/提取码/次数，递归列出目录下全部未删除条目。
     *
     * <p>计数口径与单文件一致：**一次 ZIP 下载按 1 次计**（ZIP 不支持 Range，一律视为完整下载；
     * 次数在开始打包前原子 +1，用尽 → 410）。计费按**逐文件实际送出字节**在
     * {@link #writeZip} 内累加（zip 头/目录等打包开销不计流量，口径与「按内容字节计」一致）。</p>
     *
     * <p>目录进回收站/被删 → 410（与单文件同语义；链接行保留，恢复后自动重新可用）。</p>
     */
    public ShareZipDownload prepareZipDownload(String token, String code, String clientIp) {
        ShareLink link = loadValidLink(token);
        rateLimit(link.getToken(), clientIp);
        checkCode(link, token, code);

        WorkspaceFile dir = workspaceFileMapper.selectById(link.getFileId());
        if (dir == null || !Boolean.TRUE.equals(dir.getIsDirectory()) || dir.getDeletedAt() != null) {
            throw new BusinessException(410, "文件夹已不存在");
        }
        // 先收集条目（条目过多会 400），避免「打包计划失败却已消耗一次下载次数」
        List<ShareZipEntry> entries = collectZipEntries(dir);
        if (shareLinkMapper.incrementDownloadCount(link.getId()) == 0) {
            throw new BusinessException(410, "分享链接下载次数已用尽");
        }

        Workspace workspace = workspaceMapper.selectById(link.getWorkspaceId());
        Long billingUserId = workspace != null && workspace.getOwnerId() != null
                ? workspace.getOwnerId()
                : link.getCreatorId();

        return new ShareZipDownload(dir.getFileName(), entries, billingUserId);
    }

    /**
     * 递归收集目录下全部未删除条目，按「目录优先 + 路径字典序」排序，生成 ZIP 内相对路径。
     *
     * <p>路径口径：ZIP 内**不含外层目录名**（解压即得到目录内容），目录条目以 {@code /} 结尾，
     * 从而保留空目录；相对路径由 {@link FileTree#originalPath} 去掉目录基准前缀得到。
     * {@code likeRight} 不转义 {@code %}/{@code _}，因此再用 {@link FileTree#isUnder} 做逻辑路径二次过滤。</p>
     *
     * @throws BusinessException 400 条目数超过 {@link #MAX_ZIP_ENTRIES}
     */
    private List<ShareZipEntry> collectZipEntries(WorkspaceFile dir) {
        String base = FileTree.childBase(dir);
        List<WorkspaceFile> candidates = workspaceFileMapper.selectList(new LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, dir.getWorkspaceId())
                .isNull(WorkspaceFile::getDeletedAt)
                .and(w -> w.eq(WorkspaceFile::getParentPath, base)
                        .or().likeRight(WorkspaceFile::getParentPath, base + "/")));
        List<ShareZipEntry> entries = new ArrayList<>();
        for (WorkspaceFile row : candidates) {
            if (!FileTree.isUnder(row, base) || row.getId().equals(dir.getId())) {
                continue;
            }
            String logical = FileTree.originalPath(row);
            String relative = logical.substring(base.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            if (relative.isEmpty()) {
                continue;
            }
            if (Boolean.TRUE.equals(row.getIsDirectory())) {
                entries.add(new ShareZipEntry(relative + "/", 0L, null));
            } else if (row.getMinioPath() != null && !row.getMinioPath().isBlank()) {
                entries.add(new ShareZipEntry(relative,
                        row.getFileSize() == null ? 0L : row.getFileSize(), row.getMinioPath()));
            }
        }
        if (entries.size() > MAX_ZIP_ENTRIES) {
            throw new BusinessException(400, "文件夹条目过多（上限 " + MAX_ZIP_ENTRIES
                    + " 项），请改为分享子文件夹");
        }
        entries.sort(Comparator.comparing(ShareZipEntry::path));
        return entries;
    }

    /**
     * 把 ZIP 写入响应流（Spring {@code StreamingResponseBody} 调用；不落盘、无 Content-Length）。
     *
     * <p>每个文件单独包 {@link UsageService#meterAndThrottleDownload}（close 时按实际读出字节
     * 累加到空间所有者），因此计量 = 解压后内容字节之和，不含 zip 头尾开销。
     * 单个对象在打包期间被彻底删除（{@code getObject} 抛异常）只跳过该条并 WARN，不中断整包。</p>
     */
    public void writeZip(ShareZipDownload zip, OutputStream out) {
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out, 64 * 1024),
                StandardCharsets.UTF_8);
        try {
            for (ShareZipEntry entry : zip.entries()) {
                try {
                    if (entry.minioPath() == null) {
                        // 目录条目：以 / 结尾，保留空目录结构
                        zos.putNextEntry(new ZipEntry(entry.path()));
                        zos.closeEntry();
                        continue;
                    }
                    // 先取对象流再写 zip 头：对象已被彻底删除时不会留下空条目
                    try (InputStream raw = objectStorage.getObject(entry.minioPath());
                         InputStream metered = usageService.meterAndThrottleDownload(zip.billingUserId(), raw)) {
                        zos.putNextEntry(new ZipEntry(entry.path()));
                        metered.transferTo(zos);
                        zos.closeEntry();
                    }
                } catch (IOException e) {
                    // 客户端断开/网络错误：整包无法继续，直接抛出由容器记录
                    throw e;
                } catch (RuntimeException e) {
                    log.warn("share zip: skip unreadable entry {}: {}", entry.path(), e.toString());
                    try {
                        zos.closeEntry();
                    } catch (IOException ignored) {
                        // 没有打开的 entry 时 closeEntry 会抛，忽略即可
                    }
                }
            }
            zos.finish();
            zos.flush();
        } catch (IOException e) {
            log.warn("share zip stream aborted: folder={}, entries={}, error={}",
                    zip.folderName(), zip.entries().size(), e.toString());
        } finally {
            // 不 close 底层响应流（由 Servlet 容器负责）；只 flush 保证 central directory 已写出
            try {
                zos.flush();
            } catch (IOException ignored) {
                // 客户端已断开：无需再报错
            }
        }
    }

    /** 单文件下载计划。 */
    public record ShareDownloadPlan(ShareDownload file, ShareZipDownload zip) {
    }

    /** ZIP 内一个条目：{@code minioPath == null} 表示目录条目（path 以 / 结尾）。 */
    public record ShareZipEntry(String path, long size, String minioPath) {
    }

    /** 文件夹分享 ZIP 打包计划（controller 组装响应头，写流走 {@link #writeZip}）。 */
    public record ShareZipDownload(String folderName, List<ShareZipEntry> entries, Long billingUserId) {
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 链接有效性：404 未知 / 410 撤销、过期、次数耗尽。 */
    private ShareLink loadValidLink(String token) {
        if (!ShareCode.isWellFormedToken(token)) {
            throw new BusinessException(404, "分享链接不存在");
        }
        ShareLink link = shareLinkMapper.selectByToken(token);
        if (link == null) {
            throw new BusinessException(404, "分享链接不存在");
        }
        if (link.getStatus() != null && link.getStatus() == 1) {
            throw new BusinessException(410, "分享链接已撤销");
        }
        if (link.getStatus() != null && link.getStatus() == 2) {
            // 定时任务标记的「已过期」；status=2 也可通过 PATCH 改期复位（task-163）
            throw new BusinessException(410, "分享链接已过期");
        }
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessException(410, "分享链接已过期");
        }
        if (link.getMaxDownloads() != null && link.getMaxDownloads() > 0
                && link.getDownloadCount() != null && link.getDownloadCount() >= link.getMaxDownloads()) {
            throw new BusinessException(410, "分享链接下载次数已用尽");
        }
        return link;
    }

    private void ensureReadable(Workspace workspace, Long userId) {
        if ("personal".equals(workspace.getType())) {
            if (!userId.equals(workspace.getOwnerId())) {
                // 个人空间对他人不可见：统一按 404 处理，避免泄露存在性
                throw new BusinessException(404, "文件不存在");
            }
            return;
        }
        if (userId.equals(workspace.getOwnerId())) {
            return;
        }
        if (member(workspace.getId(), userId) == null) {
            throw new BusinessException(403, "无权分享该空间的文件");
        }
    }

    private WorkspaceMember member(Long workspaceId, Long userId) {
        return workspaceMemberMapper.selectOne(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
    }

    /**
     * 有效期（task-102）：**所有档位都允许永久链接**——`expiresDays=0` → `expiresAt=null`。
     * 未指定天数时按档位默认：`share_expires_days > 0` 用该值，档位为 0（不限）则永久。
     */
    private OffsetDateTime resolveExpiresAt(Integer requestedDays, Entitlements entitlements, PlanLimits limits) {
        int planDays = limits.shareExpiresDays();
        if (requestedDays == null) {
            // 档位默认：有上限就用上限天数；share_expires_days=0（不限）则默认永久
            return planDays > 0 ? OffsetDateTime.now().plusDays(planDays) : null;
        }
        if (requestedDays < 0) {
            throw new BusinessException(400, "有效期天数不能为负数");
        }
        if (requestedDays == 0) {
            // 永久：free 与付费档一致放开（用户已拍板）
            return null;
        }
        if (planDays > 0 && requestedDays > planDays) {
            throw new BusinessException(400, "有效期不能超过当前档位上限（" + planDays + " 天）");
        }
        return OffsetDateTime.now().plusDays(requestedDays);
    }

    /**
     * 下载次数上限（task-102）：永久链接缺省 100 次（不再报错）；
     * 请求值超过档位上限时按上限收敛；有有效期链接 0 = 不限。
     */
    private int resolveMaxDownloads(Integer requested, boolean permanent, PlanLimits limits) {
        int cap = limits.shareMaxDownloads();
        int maxDownloads = requested == null ? 0 : Math.max(0, requested);
        if (permanent && maxDownloads == 0) {
            maxDownloads = DEFAULT_PERMANENT_MAX_DOWNLOADS;
        }
        if (maxDownloads > 0 && cap > 0 && maxDownloads > cap) {
            maxDownloads = cap;
        }
        return maxDownloads;
    }

    /**
     * 插入链接；token 唯一索引冲突（理论上 2^-256 概率，但防御性处理）时换 token 重试。
     * 提取码摘要绑定 token，换 token 必须用明文 code 重算摘要。
     */
    private void insertWithTokenRetry(ShareLink link, String plainCode) {
        for (int attempt = 0; attempt < MAX_TOKEN_INSERT_RETRIES; attempt++) {
            try {
                shareLinkMapper.insert(link);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == MAX_TOKEN_INSERT_RETRIES - 1) {
                    throw e;
                }
                link.setToken(ShareCode.newToken());
                if (plainCode != null) {
                    link.setCodeHash(ShareCode.hashCode(pepper(), link.getToken(), plainCode));
                }
            }
        }
    }

    private void rateLimit(String token, String clientIp) {
        int limit = configService.getInt(CFG_RATE_LIMIT_PER_MINUTE, 30);
        if (limit <= 0) {
            return;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        String key = (clientIp == null ? "?" : clientIp) + "|" + token + "|" + minute;
        if (rateWindow.size() > RATE_WINDOW_MAX_ENTRIES) {
            rateWindow.clear();
        }
        int count = rateWindow.merge(key, 1, Integer::sum);
        if (count > limit) {
            throw new BusinessException(429, "访问过于频繁，请稍后再试");
        }
    }

    private void checkCode(ShareLink link, String token, String code) {
        if (!Boolean.TRUE.equals(link.getRequiresCode()) || link.getCodeHash() == null) {
            return;
        }
        OffsetDateTime lockedUntil = link.getCodeLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now())) {
            throw new BusinessException(429, "提取码已锁定，请稍后再试");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(403, "请输入提取码");
        }
        if (!ShareCode.verifyCode(pepper(), token, code.trim(), link.getCodeHash())) {
            int maxAttempts = Math.max(1, configService.getInt(CFG_CODE_MAX_ATTEMPTS, 5));
            int lockMinutes = Math.max(1, configService.getInt(CFG_CODE_LOCK_MINUTES, 10));
            shareLinkMapper.recordCodeFailure(link.getId(), maxAttempts, lockMinutes);
            int failed = (link.getFailedAttempts() == null ? 0 : link.getFailedAttempts()) + 1;
            if (failed >= maxAttempts) {
                throw new BusinessException(429, "提取码错误次数过多，已锁定 " + lockMinutes + " 分钟");
            }
            throw new BusinessException(403, "提取码错误");
        }
        shareLinkMapper.resetCodeFailures(link.getId());
    }

    private String pepper() {
        String pepper = System.getenv(ENV_CODE_PEPPER);
        if (pepper == null || pepper.isBlank()) {
            pepper = System.getenv(ENV_FALLBACK_PEPPER);
        }
        if (pepper == null || pepper.isBlank()) {
            if (pepperWarned.compareAndSet(false, true)) {
                log.warn("{} / {} 未配置，使用开发默认 pepper；生产必须配置环境变量", ENV_CODE_PEPPER, ENV_FALLBACK_PEPPER);
            }
            pepper = DEV_PEPPER;
        }
        return pepper;
    }

    /**
     * 视图组装。{@code file} 可能为 null（目标已被彻底删除，链接行仍在的历史数据）；
     * 目录分享的 {@code isDirectory=true}，客户端据此把「下载」按钮文案改成「下载全部（ZIP）」。
     */
    private ShareLinkView toView(ShareLink link, WorkspaceFile file) {
        boolean directory = file != null && Boolean.TRUE.equals(file.getIsDirectory());
        return new ShareLinkView(
                link.getId(),
                link.getToken(),
                publicUrl(link.getToken()),
                link.getFileId(),
                file == null ? null : file.getFileName(),
                file == null ? null : file.getFileSize(),
                Boolean.TRUE.equals(link.getRequiresCode()),
                link.getExpiresAt(),
                link.getMaxDownloads() == null ? 0 : link.getMaxDownloads(),
                link.getDownloadCount() == null ? 0 : link.getDownloadCount(),
                link.getStatus() == null ? 0 : link.getStatus(),
                link.getCreatedAt(),
                stateOf(link),
                link.getLastAccessAt() == null ? null : link.getLastAccessAt().toInstant().toEpochMilli(),
                link.getCreatedAt() == null ? null : link.getCreatedAt().toInstant().toEpochMilli(),
                link.getExpiresAt() == null ? null : link.getExpiresAt().toInstant().toEpochMilli(),
                file == null ? null : directory);
    }

    /** 派生状态（task-163）：revoked / expired / active；过期以时间判定为准。 */
    private static String stateOf(ShareLink link) {
        if (link.getStatus() != null && link.getStatus() == 1) {
            return "revoked";
        }
        if (link.getStatus() != null && link.getStatus() == 2) {
            return "expired";
        }
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return "expired";
        }
        return "active";
    }

    private String publicUrl(String token) {
        String base = configService.get(CFG_PUBLIC_BASE_URL, "https://bond.example.com");
        if (base == null || base.isBlank()) {
            base = "";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/s/" + token;
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    /** 匿名下载流与响应元信息（controller 组装 header）。 */
    public record ShareDownload(String fileName, String contentType, long totalSize,
                                long offset, long length, boolean partial, InputStream stream) {
    }
}
