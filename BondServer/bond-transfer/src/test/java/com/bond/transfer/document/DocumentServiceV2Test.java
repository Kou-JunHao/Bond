package com.bond.transfer.document;

import com.bond.common.dto.DocumentDTO;
import com.bond.common.entity.Document;
import com.bond.common.entity.DocumentVersion;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.mapper.DocumentMapper;
import com.bond.transfer.mapper.DocumentVersionMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 文档 v2（task-137）服务层测试：HTML 白名单落库、列表增强、409 data 结构、
 * PATCH 自动保存 60 秒合并、版本恢复 HTML、PDF 导出与 503。
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceV2Test {

    private static final Long USER_ID = 7L;
    private static final Long WORKSPACE_ID = 10L;
    private static final Long DOC_ID = 20L;

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentVersionMapper versionMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private ConfigService configService;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private UserMapper userMapper;
    @Mock
    private DocumentPdfExporter pdfExporter;

    @InjectMocks
    private DocumentService service;

    @BeforeEach
    void setUp() {
        lenient().when(entitlementResolver.resolve(USER_ID)).thenReturn(Entitlements.free());
        lenient().when(configService.getLong(anyString(), anyLong())).thenReturn(10L * 1024 * 1024);
        lenient().when(configService.getInt(anyString(), anyInt())).thenReturn(50);

        Workspace personal = new Workspace();
        personal.setId(WORKSPACE_ID);
        personal.setType("personal");
        personal.setOwnerId(USER_ID);
        lenient().when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(personal);

        User user = new User();
        user.setId(USER_ID);
        user.setNickname("小明");
        user.setUsername("ming");
        lenient().when(userMapper.selectById(any())).thenReturn(user);
        lenient().when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));
    }

    // ── 创建 HTML ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("创建 html 文档：正文白名单净化后落 content_html，content_md 置空")
    void createHtmlSanitizesAndStores() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        DocumentDTO dto = service.create(USER_ID, WORKSPACE_ID, "/", "富文本",
                null, "<p onclick=\"x()\">a<script>alert(1)</script><img src=\"x\" onerror=\"y()\"></p>", "html");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        Document saved = captor.getValue();
        assertEquals("html", saved.getContentType());
        assertEquals("", saved.getContentMd());
        assertEquals("<p>a<img src=\"x\"></p>", saved.getContentHtml());
        assertEquals(saved.getContentHtml().getBytes(StandardCharsets.UTF_8).length,
                saved.getSizeBytes().intValue());
        verify(workspaceService).reserveUploadQuota(eq(WORKSPACE_ID), eq(saved.getSizeBytes()));
        verify(versionMapper).insert(any(DocumentVersion.class));

        assertEquals("html", dto.getContentType());
        assertEquals("<p>a<img src=\"x\"></p>", dto.getContentHtml());
        assertEquals("<p>a<img src=\"x\"></p>", dto.getContent());
        assertNotNull(dto.getUpdatedAtMillis());
        assertEquals("小明", dto.getLastEditorNickname());
    }

    @Test
    @DisplayName("创建 markdown 文档仍走原路径（html 为 null）")
    void createMarkdownKeepsMarkdown() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.create(USER_ID, WORKSPACE_ID, "/", "md", "# 标题", null, null);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        assertEquals("markdown", captor.getValue().getContentType());
        assertEquals("# 标题", captor.getValue().getContentMd());
        assertNull(captor.getValue().getContentHtml());
    }

    // ── 列表增强 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("列表 q 超长 400；正常 q 走参数绑定模糊查询")
    void listValidatesQuery() {
        assertThrows(BusinessException.class,
                () -> service.list(USER_ID, WORKSPACE_ID, "/", "x".repeat(101), 20));

        when(documentMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.list(USER_ID, WORKSPACE_ID, "/", "会议", 20).size());
    }

    // ── 保存冲突 409 结构 ────────────────────────────────────────────────

    @Test
    @DisplayName("保存冲突：409 data 含 version/content/contentHtml/updatedAtMillis 与旧字段")
    void saveConflictCarriesStructuredData() {
        Document current = htmlDoc(3L, "<p>服务端</p>");
        when(documentMapper.selectById(DOC_ID)).thenReturn(current);
        when(documentMapper.selectForUpdate(DOC_ID)).thenReturn(current);
        when(documentMapper.update(any(), any())).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.save(USER_ID, DOC_ID, null, null, "<p>我的</p>", "html", 2L, false));

        assertEquals(409, ex.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ex.getData();
        assertEquals(3L, data.get("version"));
        assertEquals(3L, data.get("serverVersion"));
        assertEquals("<p>服务端</p>", data.get("contentHtml"));
        assertEquals("<p>服务端</p>", data.get("content"));
        assertNotNull(data.get("updatedAtMillis"));
    }

    // ── PATCH 自动保存 ────────────────────────────────────────────────────

    @Test
    @DisplayName("自动保存：同一人 60 秒内合并到同一版本号（不新增版本行）")
    void autosaveMergesWithin60Seconds() {
        Document doc = htmlDoc(3L, "<p>旧</p>");
        DocumentVersion latest = versionRow(3L, USER_ID, OffsetDateTime.now().minusSeconds(10));
        latest.setId(99L);
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc);
        when(documentMapper.selectForUpdate(DOC_ID)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(versionMapper.selectLatest(DOC_ID)).thenReturn(latest);
        when(versionMapper.selectSizes(DOC_ID)).thenReturn(List.of());

        DocumentDTO dto = service.autosave(USER_ID, DOC_ID, null, "<p>新</p>");

        assertEquals(3L, dto.getVersion());
        assertEquals("<p>新</p>", dto.getContentHtml());
        verify(versionMapper).updateContent(eq(99L), eq(""), eq("<p>新</p>"), anyLong());
        verify(versionMapper, never()).insert(any(DocumentVersion.class));
    }

    @Test
    @DisplayName("自动保存：超过 60 秒或不同人则升版本并新增版本行")
    void autosaveBumpsVersionWhenStale() {
        Document doc = htmlDoc(3L, "<p>旧</p>");
        DocumentVersion latest = versionRow(3L, USER_ID, OffsetDateTime.now().minusSeconds(120));
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc);
        when(documentMapper.selectForUpdate(DOC_ID)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(versionMapper.selectLatest(DOC_ID)).thenReturn(latest);
        when(versionMapper.insert(any(DocumentVersion.class))).thenReturn(1);
        when(versionMapper.selectSizes(DOC_ID)).thenReturn(List.of());

        DocumentDTO dto = service.autosave(USER_ID, DOC_ID, null, "<p>新</p>");

        assertEquals(4L, dto.getVersion());
        verify(versionMapper).insert(any(DocumentVersion.class));
        verify(versionMapper, never()).updateContent(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("自动保存：内容未变化为 no-op（不写库、不升版本）")
    void autosaveSameContentIsNoop() {
        Document doc = htmlDoc(3L, "<p>同</p>");
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc);
        when(documentMapper.selectForUpdate(DOC_ID)).thenReturn(doc);

        DocumentDTO dto = service.autosave(USER_ID, DOC_ID, null, "<p>同</p>");

        assertEquals(3L, dto.getVersion());
        verify(documentMapper, never()).update(any(), any());
        verify(versionMapper, never()).updateContent(anyLong(), anyString(), anyString(), anyLong());
    }

    // ── 版本恢复 / 导出 ───────────────────────────────────────────────────

    @Test
    @DisplayName("恢复历史版本：HTML 一并恢复")
    void restoreVersionRestoresHtml() {
        Document doc = htmlDoc(4L, "<p>现在</p>");
        DocumentVersion old = versionRow(2L, USER_ID, OffsetDateTime.now().minusDays(1));
        old.setContentHtml("<p>旧版</p>");
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc);
        when(documentMapper.selectForUpdate(DOC_ID)).thenReturn(doc);
        when(versionMapper.selectByVersion(DOC_ID, 2L)).thenReturn(old);
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(versionMapper.insert(any(DocumentVersion.class))).thenReturn(1);
        when(versionMapper.selectSizes(DOC_ID)).thenReturn(List.of());

        DocumentDTO dto = service.restoreVersion(USER_ID, DOC_ID, 2L);
        assertEquals("<p>旧版</p>", dto.getContentHtml());
        assertEquals(5L, dto.getVersion());
    }

    @Test
    @DisplayName("导出 PDF：走 DocumentPdfExporter，返回 application/pdf")
    void exportPdfUsesExporter() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(htmlDoc(1L, "<p>a</p>"));
        byte[] pdf = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);
        when(pdfExporter.toPdf(anyString())).thenReturn(pdf);

        DocumentService.ExportFile file = service.export(USER_ID, DOC_ID, "pdf");
        assertArrayEquals(pdf, file.content());
        assertEquals("application/pdf", file.contentType());
        assertTrue(file.fileName().endsWith(".pdf"));
        verify(pdfExporter).toPdf(anyString());
    }

    @Test
    @DisplayName("Gotenberg 不可用时导出 PDF 抛 503（明确 message）")
    void exportPdfSurfaces503() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(htmlDoc(1L, "<p>a</p>"));
        when(pdfExporter.toPdf(anyString()))
                .thenThrow(new BusinessException(503, "文档转换服务暂时不可用，请稍后重试"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.export(USER_ID, DOC_ID, "pdf"));
        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("文档转换服务"));
    }

    @Test
    @DisplayName("docx 导出返回 501 明确不支持")
    void exportDocxIs501() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(htmlDoc(1L, "<p>a</p>"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.export(USER_ID, DOC_ID, "docx"));
        assertEquals(501, ex.getCode());
        assertTrue(ex.getMessage().contains("Word"));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Document htmlDoc(long version, String html) {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setWorkspaceId(WORKSPACE_ID);
        doc.setOwnerId(USER_ID);
        doc.setTitle("doc");
        doc.setParentPath("/");
        doc.setContentType("html");
        doc.setContentMd("");
        doc.setContentHtml(html);
        doc.setSizeBytes((long) html.getBytes(StandardCharsets.UTF_8).length);
        doc.setVersion(version);
        doc.setLastEditorId(USER_ID);
        doc.setCreatedAt(OffsetDateTime.now().minusDays(2));
        doc.setUpdatedAt(OffsetDateTime.now().minusMinutes(5));
        return doc;
    }

    private static DocumentVersion versionRow(long version, Long editorId, OffsetDateTime createdAt) {
        DocumentVersion row = new DocumentVersion();
        row.setId(50L + version);
        row.setDocumentId(DOC_ID);
        row.setVersion(version);
        row.setContentMd("");
        row.setContentHtml("<p>v</p>");
        row.setSizeBytes(6L);
        row.setEditorId(editorId);
        row.setCreatedAt(createdAt);
        return row;
    }
}

