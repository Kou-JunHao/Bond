package com.bond.transfer.chat;

import com.bond.common.entity.ChatConversation;
import com.bond.common.entity.ChatMember;
import com.bond.common.entity.ChatMessage;
import com.bond.common.entity.WorkspaceFile;
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
import com.bond.transfer.preview.ThumbnailRateLimiter;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatFileAccessServiceTest {

    private static final Long USER = 9L;
    private static final Long CONVERSATION = 3L;
    private static final Long WORKSPACE = 5L;
    private static final Long MESSAGE = 7L;
    private static final Long FILE = 11L;

    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private ChatConversationMapper conversationMapper;
    @Mock
    private ChatMemberMapper chatMemberMapper;
    @Mock
    private WorkspaceMemberMapper workspaceMemberMapper;
    @Mock
    private WorkspaceFileMapper workspaceFileMapper;
    @Mock
    private PreviewTokenService tokens;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private ThumbnailService thumbnails;
    @Mock
    private ThumbnailRateLimiter thumbnailRateLimiter;
    @Mock
    private UsageService usageService;
    @Mock
    private OfficePreviewService officePreview;
    @Mock
    private PersonalSpaceService personalSpaceService;

    private ChatFileAccessService service;

    @BeforeEach
    void setUp() {
        service = new ChatFileAccessService(messageMapper, conversationMapper, chatMemberMapper,
                workspaceMemberMapper, workspaceFileMapper, tokens, workspaceService, thumbnails,
                thumbnailRateLimiter, usageService, officePreview, personalSpaceService);
    }

    // ------------------------------------------------------------------ issue

    @Test
    @DisplayName("签发：direct 会话成员可拿到 meta/content/thumbnail URL 与元数据")
    void issueReturnsUrlsForDirectMember() {
        givenMessageAndDirectConversation();
        when(tokens.issueForMessage(MESSAGE, USER, PreviewTokenService.SCOPE_CHAT_META)).thenReturn("m");
        when(tokens.issueForMessage(MESSAGE, USER, PreviewTokenService.SCOPE_CHAT_CONTENT)).thenReturn("c");
        when(tokens.issueForMessage(MESSAGE, USER, PreviewTokenService.SCOPE_CHAT_THUMB)).thenReturn("t");
        when(tokens.getTtlSeconds()).thenReturn(600L);

        ChatFileAccessService.Tokens out = service.issue(MESSAGE, USER);

        assertEquals("/api/chat/files/meta?token=m", out.metaUrl());
        assertEquals("/api/chat/files/content?token=c", out.contentUrl());
        assertEquals("/api/chat/files/thumbnail?w=320&token=t", out.thumbnailUrl());
        assertNull(out.officePdfUrl());
        assertEquals(600L, out.expiresIn());
        assertEquals("报告.docx", out.fileName());
        assertEquals(100L, out.fileSize());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                out.contentType());
        assertEquals("sha-abc", out.sha256());
    }

    @Test
    @DisplayName("签发：Office 文件且在开启渲染时附 office-pdf URL")
    void issueAddsOfficePdfUrlWhenSupported() {
        givenMessageAndDirectConversation();
        WorkspaceFile file = workspaceFileMapper.selectById(FILE);
        when(officePreview.isEnabled()).thenReturn(true);
        when(officePreview.supports(file)).thenReturn(true);
        when(tokens.issueForMessage(eq(MESSAGE), eq(USER), any())).thenReturn("x");
        when(tokens.getTtlSeconds()).thenReturn(600L);

        ChatFileAccessService.Tokens out = service.issue(MESSAGE, USER);

        assertNotNull(out.officePdfUrl());
        assertEquals("/api/chat/files/office-pdf?token=x", out.officePdfUrl());
    }

    @Test
    @DisplayName("签发：非会话成员 → 403（会话存在但无权访问）")
    void issueRejectsOutsider() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(fileMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(workspaceConversation());
        when(workspaceMemberMapper.selectCount(any())).thenReturn(0L);

        assertForbidden(() -> service.issue(MESSAGE, USER));
    }

    @Test
    @DisplayName("签发：direct 会话成员表里没有该用户 → 403")
    void issueRejectsDirectNonMember() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(fileMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(directConversation());
        when(chatMemberMapper.select(CONVERSATION, USER)).thenReturn(null);

        assertForbidden(() -> service.issue(MESSAGE, USER));
    }

    @Test
    @DisplayName("签发：消息已撤回 / 非 file 消息 / 文件已删除 → 404")
    void issueRejectsInvalidMessageOrFile() {
        ChatMessage deleted = fileMessage();
        deleted.setDeletedAt(OffsetDateTime.now());
        when(messageMapper.selectById(MESSAGE)).thenReturn(deleted);
        assertNotFound(() -> service.issue(MESSAGE, USER));

        ChatMessage text = fileMessage();
        text.setKind("text");
        when(messageMapper.selectById(MESSAGE)).thenReturn(text);
        assertNotFound(() -> service.issue(MESSAGE, USER));

        when(messageMapper.selectById(MESSAGE)).thenReturn(fileMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(directConversation());
        when(chatMemberMapper.select(CONVERSATION, USER)).thenReturn(new ChatMember());
        when(workspaceFileMapper.selectById(FILE)).thenReturn(null);
        assertNotFound(() -> service.issue(MESSAGE, USER));
    }

    @Test
    @DisplayName("签发：image 消息（对端图片预览 404 修复）同样签发 meta/content/thumbnail 令牌")
    void issueReturnsUrlsForImageMessage() {
        when(personalSpaceService.resolveWorkspaceId(USER)).thenReturn(WORKSPACE);
        when(messageMapper.selectById(MESSAGE)).thenReturn(imageMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(directConversation());
        when(chatMemberMapper.select(CONVERSATION, USER)).thenReturn(new ChatMember());
        when(workspaceFileMapper.selectById(FILE)).thenReturn(imageFile());
        when(tokens.issueForMessage(eq(MESSAGE), eq(USER), any())).thenReturn("x");
        when(tokens.getTtlSeconds()).thenReturn(600L);

        ChatFileAccessService.Tokens out = service.issue(MESSAGE, USER);

        assertEquals("/api/chat/files/meta?token=x", out.metaUrl());
        assertEquals("/api/chat/files/content?token=x", out.contentUrl());
        assertEquals("/api/chat/files/thumbnail?w=320&token=x", out.thumbnailUrl());
        assertEquals("image/png", out.contentType());
        assertEquals("截图.png", out.fileName());
    }

    @Test
    @DisplayName("签发：文件不在该会话可访问空间（跨空间脏数据）→ 404")
    void issueRejectsCrossSpaceFile() {
        when(messageMapper.selectById(MESSAGE)).thenReturn(fileMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(directConversation());
        when(chatMemberMapper.select(CONVERSATION, USER)).thenReturn(new ChatMember());
        WorkspaceFile otherSpace = workspaceFile();
        otherSpace.setWorkspaceId(99L);
        when(workspaceFileMapper.selectById(FILE)).thenReturn(otherSpace);
        when(personalSpaceService.resolveWorkspaceId(USER)).thenReturn(WORKSPACE);

        assertNotFound(() -> service.issue(MESSAGE, USER));
    }

    // ------------------------------------------------------------------ meta

    @Test
    @DisplayName("meta：有效令牌返回文件元信息")
    void metaReturnsFileInfo() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_META);
        givenMessageAndDirectConversation();

        ChatFileAccessService.FileMeta meta = service.meta("tok");

        assertEquals("报告.docx", meta.fileName());
        assertEquals(100L, meta.fileSize());
        assertEquals("file", meta.kind());
        assertEquals("sha-abc", meta.sha256());
    }

    @Test
    @DisplayName("meta：image 消息的 kind 返回 image（客户端据此决定图片/文件渲染）")
    void metaReturnsImageKind() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_META);
        when(personalSpaceService.resolveWorkspaceId(USER)).thenReturn(WORKSPACE);
        when(messageMapper.selectById(MESSAGE)).thenReturn(imageMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(directConversation());
        when(chatMemberMapper.select(CONVERSATION, USER)).thenReturn(new ChatMember());
        when(workspaceFileMapper.selectById(FILE)).thenReturn(imageFile());

        assertEquals("image", service.meta("tok").kind());
    }

    @Test
    @DisplayName("meta：无效令牌 → InvalidPreviewTokenException")
    void metaRejectsInvalidToken() {
        when(tokens.resolveForMessage("bad", PreviewTokenService.SCOPE_CHAT_META)).thenReturn(Optional.empty());
        assertThrows(PreviewService.InvalidPreviewTokenException.class, () -> service.meta("bad"));
    }

    @Test
    @DisplayName("每次取流都复校验：令牌有效但消息已撤回 → 404（撤销立刻生效）")
    void revalidatesStateOnEveryAccess() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_CONTENT);
        ChatMessage deleted = fileMessage();
        deleted.setDeletedAt(OffsetDateTime.now());
        when(messageMapper.selectById(MESSAGE)).thenReturn(deleted);

        assertNotFound(() -> service.openContent("tok", null));
    }

    // ------------------------------------------------------------------ content

    @Test
    @DisplayName("content：全量读取按接收方计量，返回 Content 元信息")
    void contentFullStreamsAndMetersReceiver() throws Exception {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_CONTENT);
        givenMessageAndDirectConversation();
        InputStream raw = new ByteArrayInputStream(new byte[100]);
        when(workspaceService.openFileContentStream(any(WorkspaceFile.class), eq(0L), eq(100L)))
                .thenReturn(raw);
        when(usageService.meterAndThrottleDownload(eq(USER), any(InputStream.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        PreviewService.Content content = service.openContent("tok", null);

        assertSame(raw, content.stream());
        assertEquals(100L, content.totalSize());
        assertEquals(0L, content.offset());
        assertEquals(100L, content.length());
        assertFalse(content.partial());
        verify(usageService).meterAndThrottleDownload(eq(USER), any(InputStream.class));
        verify(workspaceService).openFileContentStream(any(WorkspaceFile.class), eq(0L), eq(100L));
    }

    @Test
    @DisplayName("content：Range 请求解析为部分内容")
    void contentRange() throws Exception {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_CONTENT);
        givenMessageAndDirectConversation();
        when(workspaceService.openFileContentStream(any(WorkspaceFile.class), eq(10L), eq(20L)))
                .thenReturn(new ByteArrayInputStream(new byte[20]));
        when(usageService.meterAndThrottleDownload(eq(USER), any(InputStream.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        PreviewService.Content content = service.openContent("tok", "bytes=10-29");

        assertEquals(10L, content.offset());
        assertEquals(20L, content.length());
        assertEquals(100L, content.totalSize());
        assertEquals(true, content.partial());
    }

    @Test
    @DisplayName("content：Range 越界 → UnsatisfiableRangeException(total)")
    void contentUnsatisfiableRange() throws Exception {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_CONTENT);
        givenMessageAndDirectConversation();

        PreviewService.UnsatisfiableRangeException ex = assertThrows(
                PreviewService.UnsatisfiableRangeException.class,
                () -> service.openContent("tok", "bytes=1000-"));
        assertEquals(100L, ex.getTotalSize());
        verify(workspaceService, never())
                .openFileContentStream(any(WorkspaceFile.class), anyLong(), anyLong());
    }

    @Test
    @DisplayName("content：存储读取失败 → 503（不让 500 冒到客户端）")
    void contentStorageFailureBecomes503() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_CONTENT);
        givenMessageAndDirectConversation();
        try {
            when(workspaceService.openFileContentStream(any(WorkspaceFile.class), eq(0L), eq(100L)))
                    .thenThrow(new IOException("storage down"));
        } catch (Exception ignored) {
            // 桩声明是 throws Exception，不会走到这里
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.openContent("tok", null));
        assertEquals(503, ex.getCode());
    }

    // ------------------------------------------------------------------ thumbnail

    @Test
    @DisplayName("thumbnail：限流命中 → 429 异常（带 Retry-After）")
    void thumbnailRateLimited() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_THUMB);
        givenMessageAndDirectConversation();
        when(thumbnailRateLimiter.acquire(USER)).thenReturn(30L);

        PreviewService.ThumbnailRateLimitedException ex = assertThrows(
                PreviewService.ThumbnailRateLimitedException.class, () -> service.thumbnail("tok", null));
        assertEquals(30L, ex.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("thumbnail：生成成功返回 jpeg + etag；不支持时 null")
    void thumbnailSuccessAndUnsupported() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_THUMB);
        givenMessageAndDirectConversation();
        when(thumbnailRateLimiter.acquire(USER)).thenReturn(0L);
        when(thumbnails.getOrGenerate(any(WorkspaceFile.class), eq(320)))
                .thenReturn(new ThumbnailService.Thumb(new byte[]{1, 2}, "\"e1\""));
        PreviewService.Thumbnail thumb = service.thumbnail("tok", null);
        assertNotNull(thumb);
        assertEquals("\"e1\"", thumb.etag());

        when(thumbnails.getOrGenerate(any(WorkspaceFile.class), eq(64))).thenReturn(null);
        assertNull(service.thumbnail("tok", "64"));
    }

    // ------------------------------------------------------------------ office

    @Test
    @DisplayName("office-pdf：复用转换缓存并支持 Range（不计量）")
    void officePdfOpenRange() {
        givenValidToken(PreviewTokenService.SCOPE_CHAT_OFFICE);
        givenMessageAndDirectConversation();
        when(officePreview.ensure(any(WorkspaceFile.class)))
                .thenReturn(new OfficePreviewService.CachedPdf("previews/x.pdf", 200L));
        when(officePreview.openRange("previews/x.pdf", 200L, 0L, 50L))
                .thenReturn(new ByteArrayInputStream(new byte[50]));

        PreviewService.Content content = service.openOfficePdf("tok", "bytes=0-49");

        assertEquals(200L, content.totalSize());
        assertEquals(50L, content.length());
        assertEquals(true, content.partial());
        assertEquals(OfficePreviewService.CONTENT_TYPE_PDF, content.contentType());
        verify(usageService, never()).meterAndThrottleDownload(any(), any());
    }

    // ------------------------------------------------------------------ helpers

    private void givenValidToken(String scope) {
        when(tokens.resolveForMessage("tok", scope))
                .thenReturn(Optional.of(new PreviewTokenService.MessageTicket(MESSAGE, USER, scope,
                        System.currentTimeMillis() + 600_000L)));
    }

    private void givenMessageAndDirectConversation() {
        lenient().when(personalSpaceService.resolveWorkspaceId(USER)).thenReturn(WORKSPACE);
        when(messageMapper.selectById(MESSAGE)).thenReturn(fileMessage());
        when(conversationMapper.selectById(CONVERSATION)).thenReturn(directConversation());
        when(chatMemberMapper.select(CONVERSATION, USER)).thenReturn(new ChatMember());
        when(workspaceFileMapper.selectById(FILE)).thenReturn(workspaceFile());
    }

    private static ChatMessage fileMessage() {
        ChatMessage message = new ChatMessage();
        message.setId(MESSAGE);
        message.setConversationId(CONVERSATION);
        message.setSenderId(USER);
        message.setKind("file");
        message.setFileId(FILE);
        message.setCreatedAt(OffsetDateTime.now());
        return message;
    }

    private static ChatConversation directConversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONVERSATION);
        conversation.setType("direct");
        return conversation;
    }

    private static ChatConversation workspaceConversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(CONVERSATION);
        conversation.setType("workspace");
        conversation.setWorkspaceId(WORKSPACE);
        return conversation;
    }

    private static WorkspaceFile workspaceFile() {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(FILE);
        file.setWorkspaceId(WORKSPACE);
        file.setFileName("报告.docx");
        file.setFileSize(100L);
        file.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        file.setMinioPath("workspaces/5/x/报告.docx");
        file.setFileHash("sha-abc");
        return file;
    }

    private static ChatMessage imageMessage() {
        ChatMessage message = fileMessage();
        message.setKind("image");
        return message;
    }

    private static WorkspaceFile imageFile() {
        WorkspaceFile file = workspaceFile();
        file.setFileName("截图.png");
        file.setContentType("image/png");
        file.setMinioPath("workspaces/5/x/截图.png");
        return file;
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(403, ex.getCode());
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(404, ex.getCode());
    }
}
