package com.bond.transfer.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.entity.ChatConversation;
import com.bond.common.entity.ChatMessage;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.ChatConversationMapper;
import com.bond.transfer.mapper.ChatMemberMapper;
import com.bond.transfer.mapper.ChatMessageMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.preview.OfficePreviewService;
import com.bond.transfer.preview.PreviewService;
import com.bond.transfer.preview.PreviewTokenService;
import com.bond.transfer.preview.RangeParser;
import com.bond.transfer.preview.ThumbnailRateLimiter;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Objects;

/**
 * 聊天文件跨账号访问（task-84 方案 C）。
 *
 * <p>令牌根绑 {@code messageId}（不含 fileId），每次取流都按三步复校验：
 * 消息未删除 → 请求者在会话参与者内 → 文件仍存在（缺一即 404）。</p>
 *
 * <p>因此撤销语义干净：撤回消息、退出/被移出会话、删除文件后，旧令牌立刻失效。
 * 下载按<b>接收方</b>计量（不计发送者）；缩略图与 Office→PDF 预览不计流量。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatFileAccessService {

    static final String TYPE_DIRECT = "direct";
    static final String TYPE_WORKSPACE = "workspace";
    static final String KIND_FILE = "file";
    /** task-139 修复：图片消息与文件消息一样需要跨账号预览（本人走个人空间，对端走聊天令牌）。 */
    static final String KIND_IMAGE = "image";

    /** 只有携带 workspace_files 引用的消息才可签发/校验聊天文件令牌。 */
    static boolean isAttachableKind(String kind) {
        return KIND_FILE.equals(kind) || KIND_IMAGE.equals(kind);
    }

    private final ChatMessageMapper messageMapper;
    private final ChatConversationMapper conversationMapper;
    private final ChatMemberMapper chatMemberMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final PreviewTokenService tokens;
    private final WorkspaceService workspaceService;
    private final ThumbnailService thumbnails;
    private final ThumbnailRateLimiter thumbnailRateLimiter;
    private final UsageService usageService;
    private final OfficePreviewService officePreview;
    private final PersonalSpaceService personalSpaceService;

    /** 一次签发的短 URL 集合（均带 10 分钟令牌，客户端不应持久化）。 */
    public record Tokens(String metaUrl, String contentUrl, String thumbnailUrl, String officePdfUrl,
                         long expiresIn, String fileName, long fileSize, String contentType, String sha256) {
    }

    /** meta 端点响应：文件元信息（不含对象存储键等内部字段）。 */
    public record FileMeta(String fileName, long fileSize, String contentType, String kind, String sha256) {
    }

    private record Access(ChatMessage message, WorkspaceFile file, Long userId) {
    }

    // ------------------------------------------------------------------ 签发

    public Tokens issue(Long messageId, Long userId) {
        Access access = requireAccessible(messageId, userId);
        WorkspaceFile file = access.file();
        String meta = tokens.issueForMessage(messageId, userId, PreviewTokenService.SCOPE_CHAT_META);
        String content = tokens.issueForMessage(messageId, userId, PreviewTokenService.SCOPE_CHAT_CONTENT);
        String thumb = tokens.issueForMessage(messageId, userId, PreviewTokenService.SCOPE_CHAT_THUMB);
        String office = null;
        if (officePreview.isEnabled() && officePreview.supports(file)) {
            String officeToken = tokens.issueForMessage(messageId, userId, PreviewTokenService.SCOPE_CHAT_OFFICE);
            office = "/api/chat/files/office-pdf?token=" + officeToken;
        }
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        return new Tokens(
                "/api/chat/files/meta?token=" + meta,
                "/api/chat/files/content?token=" + content,
                "/api/chat/files/thumbnail?w=" + ThumbnailService.DEFAULT_WIDTH + "&token=" + thumb,
                office,
                tokens.getTtlSeconds(),
                file.getFileName(),
                size,
                PreviewService.contentTypeOf(file),
                file.getFileHash());
    }

    // ------------------------------------------------------------------ 读取

    public FileMeta meta(String token) {
        Access access = resolveAndAuthorize(token, PreviewTokenService.SCOPE_CHAT_META);
        WorkspaceFile file = access.file();
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        return new FileMeta(file.getFileName(), size, PreviewService.contentTypeOf(file),
                access.message().getKind(), file.getFileHash());
    }

    public PreviewService.Content openContent(String token, String rangeHeader) {
        Access access = resolveAndAuthorize(token, PreviewTokenService.SCOPE_CHAT_CONTENT);
        WorkspaceFile file = access.file();
        long total = file.getFileSize() == null ? 0L : file.getFileSize();
        RangeParser.Spec spec = RangeParser.parse(rangeHeader, total);
        if (spec.unsatisfiable()) {
            throw new PreviewService.UnsatisfiableRangeException(total);
        }
        InputStream raw;
        try {
            raw = workspaceService.openFileContentStream(file, spec.offset(), spec.length());
        } catch (Exception e) {
            log.error("open chat file stream failed: messageId={} fileId={}: {}",
                    access.message().getId(), file.getId(), e.getMessage());
            throw new BusinessException(503, "文件暂时不可用，请稍后重试");
        }
        // 只计下行：计量按接收方（令牌绑定的参与者），不计发送者
        InputStream stream = usageService.meterAndThrottleDownload(access.userId(), raw);
        return new PreviewService.Content(stream, total, spec.offset(), spec.length(), spec.partial(),
                PreviewService.contentTypeOf(file), file.getFileName());
    }

    public PreviewService.Thumbnail thumbnail(String token, String width) {
        Access access = resolveAndAuthorize(token, PreviewTokenService.SCOPE_CHAT_THUMB);
        // 缩略图不计流量但可被刷：按接收方软限流，超限 429（客户端降级图标）
        long retryAfter = thumbnailRateLimiter.acquire(access.userId());
        if (retryAfter > 0L) {
            throw new PreviewService.ThumbnailRateLimitedException(retryAfter);
        }
        ThumbnailService.Thumb thumb = thumbnails.getOrGenerate(access.file(),
                ThumbnailService.resolveWidth(width));
        return thumb == null ? null : new PreviewService.Thumbnail(thumb.jpeg(), thumb.etag());
    }

    /** 复用 Office→PDF 转换缓存（task-59）；不计量、支持 Range。 */
    public PreviewService.Content openOfficePdf(String token, String rangeHeader) {
        Access access = resolveAndAuthorize(token, PreviewTokenService.SCOPE_CHAT_OFFICE);
        WorkspaceFile file = access.file();
        OfficePreviewService.CachedPdf pdf = officePreview.ensure(file);
        RangeParser.Spec spec = RangeParser.parse(rangeHeader, pdf.size());
        if (spec.unsatisfiable()) {
            throw new PreviewService.UnsatisfiableRangeException(pdf.size());
        }
        InputStream stream = officePreview.openRange(pdf.key(), pdf.size(), spec.offset(), spec.length());
        return new PreviewService.Content(stream, pdf.size(), spec.offset(), spec.length(), spec.partial(),
                OfficePreviewService.CONTENT_TYPE_PDF, PreviewService.officePdfFileName(file));
    }

    // ------------------------------------------------------------------ 鉴权

    private Access resolveAndAuthorize(String token, String scope) {
        PreviewTokenService.MessageTicket ticket = tokens.resolveForMessage(token, scope)
                .orElseThrow(PreviewService.InvalidPreviewTokenException::new);
        return requireAccessible(ticket.messageId(), ticket.userId());
    }

    /**
     * 权限复校验：消息未删 &gt; 会话存在 &gt; 请求者是参与者（否则 403）&gt; 文件仍存在 &gt;
     * 文件属于该会话可访问空间（否则 404，不泄露文件是否存在于其它空间）。
     *
     * <p>消息不存在 / 已删除 / 非 file|image / 文件已删 / 跨空间：统一 404，不泄露具体原因。
     * 会话存在但请求者不是成员：403（与 REST 会话接口语义一致）。</p>
     */
    Access requireAccessible(Long messageId, Long userId) {
        if (messageId == null || userId == null) {
            throw notFound();
        }
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null || message.getDeletedAt() != null || !isAttachableKind(message.getKind())
                || message.getFileId() == null) {
            throw notFound();
        }
        ChatConversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            throw notFound();
        }
        if (!isParticipant(conversation, userId)) {
            throw new BusinessException(403, "无权访问该会话");
        }
        WorkspaceFile file = workspaceFileMapper.selectById(message.getFileId());
        if (file == null || file.getDeletedAt() != null) {
            throw notFound();
        }
        if (!Objects.equals(allowedWorkspace(conversation, message.getSenderId()), file.getWorkspaceId())) {
            throw notFound();
        }
        return new Access(message, file, userId);
    }

    /**
     * 会话可访问空间：工作区频道 = 频道所属工作区；单聊 = 发送者个人空间。
     *
     * <p>发送者此时一定存在（消息行有 sender_id），个人空间缺失属于脏数据，按 404 处理。</p>
     */
    private Long allowedWorkspace(ChatConversation conversation, Long senderId) {
        if (TYPE_WORKSPACE.equals(conversation.getType())) {
            return conversation.getWorkspaceId();
        }
        if (senderId == null) {
            return null;
        }
        try {
            return personalSpaceService.resolveWorkspaceId(senderId);
        } catch (RuntimeException e) {
            log.warn("resolve chat file personal space failed: senderId={}: {}", senderId, e.getMessage());
            return null;
        }
    }

    private boolean isParticipant(ChatConversation conversation, Long userId) {
        if (TYPE_DIRECT.equals(conversation.getType())) {
            return chatMemberMapper.select(conversation.getId(), userId) != null;
        }
        Long workspaceId = conversation.getWorkspaceId();
        if (workspaceId == null) {
            return false;
        }
        Long count = workspaceMemberMapper.selectCount(new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId));
        return count != null && count > 0L;
    }

    private static BusinessException notFound() {
        return new BusinessException(404, "文件不存在或已失效");
    }
}
