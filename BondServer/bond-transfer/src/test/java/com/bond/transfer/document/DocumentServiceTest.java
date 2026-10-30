package com.bond.transfer.document;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bond.common.dto.DocumentDTO;
import com.bond.common.dto.DocumentVersionDTO;
import com.bond.common.entity.Document;
import com.bond.common.entity.DocumentVersion;
import com.bond.common.entity.User;
import com.bond.common.entity.Workspace;
import com.bond.common.exception.BusinessException;
import com.bond.common.exception.ConflictException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.mapper.DocumentMapper;
import com.bond.transfer.mapper.DocumentVersionMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 在线文档服务：创建/列表/详情/保存（no-op、409、force）/删除/版本/恢复/导出/版本清理。 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final Long USER = 1L;
    private static final Long OTHER = 2L;
    private static final Long WS = 5L;

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
    private DocumentService documentService;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test-document-lambda");
        TableInfoHelper.initTableInfo(assistant, Document.class);
        TableInfoHelper.initTableInfo(assistant, DocumentVersion.class);
        TableInfoHelper.initTableInfo(assistant, Workspace.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static PlanLimits plan(long maxFileSize, int trashRetentionDays) {
        return new PlanLimits(10_737_418_240L, 5_368_709_120L, maxFileSize, 2, 1, 3,
                5_242_880L, 7, 3, 30, 5, trashRetentionDays);
    }

    private void stubAccess() {
        Workspace ws = new Workspace();
        ws.setId(WS);
        ws.setOwnerId(USER);
        ws.setType("team");
        lenient().when(workspaceMapper.selectById(WS)).thenReturn(ws);
    }

    private void stubPlan() {
        lenient().when(entitlementResolver.resolve(USER))
                .thenReturn(new Entitlements("free", plan(2L * 1024 * 1024 * 1024, 30), null));
    }

    private void stubContentCap(long cap) {
        lenient().when(configService.getLong(eq("document.max_content_bytes"), anyLong())).thenReturn(cap);
    }

    private static Document doc(String content) {
        OffsetDateTime now = OffsetDateTime.now();
        Document d = new Document();
        d.setId(9L);
        d.setWorkspaceId(WS);
        d.setOwnerId(USER);
        d.setTitle("a.md");
        d.setContentMd(content);
        d.setContentType("markdown");
        d.setVersion(3L);
        d.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        d.setParentPath("/");
        d.setLastEditorId(USER);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    private static User user(Long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setNickname(nickname);
        return u;
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("创建：写文档 + 版本 1 + 预留配额")
    void createPersistsDocumentAndFirstVersion() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        lenient().when(documentMapper.selectCount(any())).thenReturn(0L);
        stubAccess();
        lenient().when(documentMapper.insert(any(Document.class))).thenReturn(1);
        lenient().when(versionMapper.insert(any(DocumentVersion.class))).thenReturn(1);

        DocumentDTO dto = documentService.create(USER, WS, "/", "a.md", "hello 中文");

        assertEquals(1L, dto.getVersion());
        assertEquals((long) "hello 中文".getBytes(StandardCharsets.UTF_8).length, dto.getSizeBytes());
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        assertEquals(USER, captor.getValue().getOwnerId());
        assertEquals(1L, captor.getValue().getVersion());
        ArgumentCaptor<DocumentVersion> version = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versionMapper).insert(version.capture());
        assertEquals(1L, version.getValue().getVersion());
        verify(workspaceService).reserveUploadQuota(WS, dto.getSizeBytes());
    }

    @Test
    @DisplayName("创建：目录内撞名自动追加「(2)」")
    void createAppendsSuffixWhenTitleTaken() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        when(documentMapper.selectCount(any())).thenReturn(1L, 0L);
        lenient().when(documentMapper.insert(any(Document.class))).thenReturn(1);

        DocumentDTO dto = documentService.create(USER, WS, "/", "a.md", "");

        assertEquals("a.md (2)", dto.getTitle());
    }

    @Test
    @DisplayName("创建：正文超过 document.max_content_bytes → 413")
    void createRejectsOversizedContent() {
        stubAccess();
        stubPlan();
        stubContentCap(10L); // 10 字节上限
        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.create(USER, WS, "/", "a.md", "12345678901"));
        assertEquals(413, e.getCode());
        assertTrue(e.getMessage().contains("单篇文档正文上限"));
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    @DisplayName("创建：取档位 max_file_size 与配置的较小值")
    void createRespectsPlanMaxFileSize() {
        stubAccess();
        when(entitlementResolver.resolve(USER))
                .thenReturn(new Entitlements("free", plan(5, 30), null));
        stubContentCap(10L * 1024 * 1024);
        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.create(USER, WS, "/", "a.md", "123456"));
        assertEquals(413, e.getCode());
    }

    @Test
    @DisplayName("创建：他人个人空间 → 404（不泄露存在性）")
    void createPersonalSpaceOfOtherUserNotFound() {
        Workspace ws = new Workspace();
        ws.setId(WS);
        ws.setOwnerId(OTHER);
        ws.setType("personal");
        when(workspaceMapper.selectById(WS)).thenReturn(ws);

        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.create(USER, WS, "/", "a.md", "x"));
        assertEquals(404, e.getCode());
        verify(workspaceService, never()).ensureMember(anyLong(), anyLong());
    }

    // ── list / get ───────────────────────────────────────────────────────

    @Test
    @DisplayName("列表：不返回正文、带最后编辑人名")
    void listOmitsContentAndResolvesEditorName() {
        stubAccess();
        lenient().when(documentMapper.selectList(any())).thenReturn(List.of(doc("body")));
        lenient().when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(USER, "小明")));

        List<DocumentDTO> docs = documentService.list(USER, WS, "/");

        assertEquals(1, docs.size());
        assertNull(docs.get(0).getContentMd());
        assertEquals("小明", docs.get(0).getLastEditorName());
    }

    @Test
    @DisplayName("详情：已软删 / 不存在 → 404")
    void getDeletedDocumentNotFound() {
        Document deleted = doc("x");
        deleted.setDeletedAt(OffsetDateTime.now());
        when(documentMapper.selectById(9L)).thenReturn(deleted);

        BusinessException e = assertThrows(BusinessException.class, () -> documentService.get(USER, 9L));
        assertEquals(404, e.getCode());
    }

    // ── save ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("保存：正文与标题都没变 → 200 no-op（不写版本、不动配额）")
    void saveNoOpWhenUnchanged() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        Document current = doc("same");
        when(documentMapper.selectById(9L)).thenReturn(current);
        when(documentMapper.selectForUpdate(9L)).thenReturn(current);

        DocumentDTO dto = documentService.save(USER, 9L, null, "same", 3L, false);

        assertEquals(3L, dto.getVersion());
        verify(documentMapper, never()).update(any(), any());
        verify(versionMapper, never()).insert(any(DocumentVersion.class));
        verify(workspaceService, never()).reserveUploadQuota(anyLong(), anyLong());
    }

    @Test
    @DisplayName("保存：版本不匹配 → 409 带 serverVersion 与最后编辑人")
    void saveConflictCarriesPayload() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        Document current = doc("old");
        when(documentMapper.selectById(9L)).thenReturn(current, current);
        when(documentMapper.selectForUpdate(9L)).thenReturn(current);
        when(documentMapper.update(any(), any())).thenReturn(0);
        when(userMapper.selectById(USER)).thenReturn(user(USER, "小明"));

        ConflictException e = assertThrows(ConflictException.class,
                () -> documentService.save(USER, 9L, null, "new", 2L, false));

        assertEquals(409, e.getCode());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload = (java.util.Map<String, Object>) e.getData();
        assertEquals(3L, payload.get("serverVersion"));
        assertEquals("小明", payload.get("lastEditorName"));
    }

    @Test
    @DisplayName("保存：force=true 跳过版本校验，仍写新版本行")
    void saveForceWritesNewVersion() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        Document current = doc("old");
        when(documentMapper.selectById(9L)).thenReturn(current);
        when(documentMapper.selectForUpdate(9L)).thenReturn(current);
        when(documentMapper.update(any(), any())).thenReturn(1);

        DocumentDTO dto = documentService.save(USER, 9L, null, "new content", 1L, true);

        assertEquals(4L, dto.getVersion());
        verify(versionMapper).insert(any(DocumentVersion.class));
    }

    @Test
    @DisplayName("保存：变长预留配额、变短释放配额")
    void saveReservesAndReleasesDelta() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        Document grow = doc("a");
        when(documentMapper.selectById(9L)).thenReturn(grow);
        when(documentMapper.selectForUpdate(9L)).thenReturn(grow);
        when(documentMapper.update(any(), any())).thenReturn(1);

        documentService.save(USER, 9L, null, "abcdef", 3L, false);
        verify(workspaceService).reserveUploadQuota(WS, 5L);

        Document shrink = doc("abcdefghij");
        when(documentMapper.selectForUpdate(9L)).thenReturn(shrink);
        documentService.save(USER, 9L, null, "ab", 3L, false);
        verify(workspaceService).releaseUploadQuota(WS, 8L);
    }

    @Test
    @DisplayName("保存：改名撞名 → 409（不静默改名）")
    void saveRenameConflict409() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        Document current = doc("x");
        when(documentMapper.selectById(9L)).thenReturn(current);
        when(documentMapper.selectForUpdate(9L)).thenReturn(current);
        when(documentMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.save(USER, 9L, "other.md", "x", 3L, false));
        assertEquals(409, e.getCode());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("保存：缺 version 且非 force → 400")
    void saveMissingVersionBadRequest() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.save(USER, 9L, null, "x", null, false));
        assertEquals(400, e.getCode());
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("删除：软删进回收站；方案 B 下软删不释放配额（物理清理时才释放）")
    void deleteSoftDeletesWithoutReleasingQuota() {
        stubAccess();
        stubPlan();
        Document current = doc("12345");
        when(documentMapper.selectById(9L)).thenReturn(current);
        when(documentMapper.update(any(), any())).thenReturn(1);

        documentService.delete(USER, 9L);

        verify(documentMapper).update(any(), any());
        verify(workspaceService, org.mockito.Mockito.never())
                .releaseUploadQuota(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    // ── versions ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("版本列表：limit 最大 100、offset 最大 10000，均带编辑人名")
    void versionsClampAndResolveEditorNames() {
        stubAccess();
        Document current = doc("x");
        when(documentMapper.selectById(9L)).thenReturn(current);
        DocumentVersion v = new DocumentVersion();
        v.setId(1L);
        v.setDocumentId(9L);
        v.setVersion(3L);
        v.setSizeBytes(1L);
        v.setEditorId(USER);
        v.setCreatedAt(OffsetDateTime.now());
        when(versionMapper.selectPage(eq(9L), eq(100), eq(10_000))).thenReturn(List.of(v));
        lenient().when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(USER, "小明")));

        List<DocumentVersionDTO> rows = documentService.versions(USER, 9L, 999, 999_999);

        assertEquals(1, rows.size());
        assertEquals("小明", rows.get(0).getEditorName());
        verify(versionMapper).selectPage(9L, 100, 10_000);
    }

    @Test
    @DisplayName("版本详情：版本不存在 → 404")
    void versionNotFound() {
        stubAccess();
        when(documentMapper.selectById(9L)).thenReturn(doc("x"));
        when(versionMapper.selectByVersion(9L, 77L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.version(USER, 9L, 77L));
        assertEquals(404, e.getCode());
    }

    @Test
    @DisplayName("恢复旧版本：以 version+1 写入新版本行")
    void restoreVersionWritesNewVersion() {
        stubAccess();
        stubPlan();
        stubContentCap(10L * 1024 * 1024);
        Document current = doc("new");
        when(documentMapper.selectById(9L)).thenReturn(current);
        when(documentMapper.selectForUpdate(9L)).thenReturn(current);
        when(versionMapper.selectByVersion(9L, 2L)).thenReturn(version(2L, "old"));
        when(documentMapper.update(any(), any())).thenReturn(1);

        DocumentDTO dto = documentService.restoreVersion(USER, 9L, 2L);

        assertEquals(4L, dto.getVersion());
        assertEquals("old", dto.getContentMd());
        ArgumentCaptor<DocumentVersion> captor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(4L, captor.getValue().getVersion());
        assertEquals("old", captor.getValue().getContentMd());
    }

    private static DocumentVersion version(long number, String content) {
        DocumentVersion v = new DocumentVersion();
        v.setId(number);
        v.setDocumentId(9L);
        v.setVersion(number);
        v.setContentMd(content);
        v.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        v.setEditorId(USER);
        v.setCreatedAt(OffsetDateTime.now());
        return v;
    }

    // ── export ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("导出 md：text/markdown + 文件名")
    void exportMarkdown() {
        stubAccess();
        when(documentMapper.selectById(9L)).thenReturn(doc("# hi"));

        DocumentService.ExportFile file = documentService.export(USER, 9L, "md");

        assertEquals("text/markdown; charset=UTF-8", file.contentType());
        assertEquals("a.md", file.fileName());
        assertEquals("# hi", new String(file.content(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("导出 html：结构解析 + 文本节点转义")
    void exportHtmlEscapesTextNodes() {
        stubAccess();
        when(documentMapper.selectById(9L)).thenReturn(doc("# <b>x</b>\n\n> a > b"));

        DocumentService.ExportFile file = documentService.export(USER, 9L, "html");
        String html = new String(file.content(), StandardCharsets.UTF_8);

        assertEquals("text/html; charset=UTF-8", file.contentType());
        assertTrue(html.contains("<h1>&lt;b&gt;x&lt;/b&gt;</h1>"));
        assertTrue(html.contains("<blockquote>"));
        assertTrue(html.contains("a &gt; b"));
    }

    @Test
    @DisplayName("导出：非法 format → 400")
    void exportRejectsUnknownFormat() {
        stubAccess();
        when(documentMapper.selectById(9L)).thenReturn(doc("x"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> documentService.export(USER, 9L, "xyz"));
        assertEquals(400, e.getCode());
    }

    // ── version retention ────────────────────────────────────────────────

    @Test
    @DisplayName("版本保留：超出 50 个从最老删（删 10 个）")
    void cleanupKeepsNewestFifty() {
        lenient().when(configService.getInt(eq("document.version_keep"), anyInt())).thenReturn(50);
        lenient().when(configService.getLong(eq("document.version_total_bytes"), anyLong()))
                .thenReturn(20L * 1024 * 1024);
        List<DocumentVersion> rows = new java.util.ArrayList<>();
        for (int i = 60; i >= 1; i--) {
            DocumentVersion v = new DocumentVersion();
            v.setId((long) i);
            v.setVersion((long) i);
            v.setSizeBytes(1L);
            rows.add(v);
        }
        when(versionMapper.selectSizes(9L)).thenReturn(rows);

        documentService.cleanupVersions(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(versionMapper).deleteBatchIds(captor.capture());
        assertEquals(10, captor.getValue().size());
    }

    @Test
    @DisplayName("版本保留：总数不超限时不删")
    void cleanupNoopWhenUnderCap() {
        lenient().when(configService.getInt(eq("document.version_keep"), anyInt())).thenReturn(50);
        lenient().when(configService.getLong(eq("document.version_total_bytes"), anyLong()))
                .thenReturn(20L * 1024 * 1024);
        when(versionMapper.selectSizes(9L)).thenReturn(List.of(version(2L, "a"), version(1L, "b")));

        documentService.cleanupVersions(9L);

        verify(versionMapper, never()).deleteBatchIds(any());
    }

    @Test
    @DisplayName("cleanupVersions 的 query wrapper 类型可构造（防 API 回归）")
    void wrappersAreConstructible() {
        assertTrue(new LambdaQueryWrapper<Document>() != null);
        assertTrue(new LambdaUpdateWrapper<Document>() != null);
    }
}
