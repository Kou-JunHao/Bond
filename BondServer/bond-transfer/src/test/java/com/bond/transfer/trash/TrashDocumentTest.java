package com.bond.transfer.trash;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bond.common.entity.Document;
import com.bond.common.entity.WorkspaceFile;
import com.bond.transfer.mapper.DocumentMapper;
import com.bond.transfer.mapper.OpsMapper;
import com.bond.transfer.mapper.ShareLinkMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.ops.OpsJobRegistry;
import com.bond.transfer.preview.ThumbnailService;
import com.bond.transfer.service.ConfigService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import com.bond.transfer.storage.ObjectStorage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站合并文档（task-50）：列表 kind、恢复、彻底删除、清空/过期清理。
 *
 * <p>task-88 方案 B 后：软删/恢复都不动配额，物理清理时按 {@code size_bytes} 释放。</p>
 */
@ExtendWith(MockitoExtension.class)
class TrashDocumentTest {

    private static final Long USER = 1L;
    private static final Long WS = 5L;

    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private ConfigService configService;
    @Mock
    private ThumbnailService thumbnailService;
    @Mock
    private OpsMapper opsMapper;
    @Mock
    private OpsJobRegistry opsJobRegistry;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private TrashService trashService;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test-trash-doc-lambda");
        TableInfoHelper.initTableInfo(assistant, Document.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceFile.class);
    }

    private static Document deletedDocument() {
        Document doc = new Document();
        doc.setId(77L);
        doc.setWorkspaceId(WS);
        doc.setOwnerId(USER);
        doc.setTitle("a.md");
        doc.setParentPath("/docs");
        doc.setSizeBytes(5L);
        doc.setVersion(2L);
        doc.setDeletedAt(OffsetDateTime.now());
        doc.setTrashExpiresAt(OffsetDateTime.now().plusDays(30));
        return doc;
    }

    @Test
    @DisplayName("列表：文档 kind=document，标题当 fileName、字节数当 fileSize")
    void listIncludesDocuments() {
        when(workspaceService.getWorkspace(WS, USER)).thenReturn(null);
        when(fileMapper.selectList(any())).thenReturn(List.<WorkspaceFile>of());
        when(documentMapper.selectList(any())).thenReturn(List.of(deletedDocument()));

        List<TrashItem> items = trashService.list(USER, WS);

        assertEquals(1, items.size());
        assertEquals("document", items.get(0).kind());
        assertEquals("a.md", items.get(0).fileName());
        assertEquals(5L, items.get(0).fileSize());
        assertEquals("/docs/a.md", items.get(0).originalPath());
    }

    @Test
    @DisplayName("恢复文档：重新占用配额（2026-10 起取消方案 B）")
    void restoreDocumentReservesQuota() {
        when(fileMapper.selectById(77L)).thenReturn(null);
        when(documentMapper.selectById(77L)).thenReturn(deletedDocument());
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.update(any(), any())).thenReturn(1);

        TrashBatchResult result = trashService.restore(USER, List.of(77L));

        assertTrue(result.failed().isEmpty());
        verify(documentMapper).update(any(), any());
        verify(workspaceService).reserveSpace(WS, 5L);
    }

    @Test
    @DisplayName("恢复文档：同名已存在时标题加「(2)」")
    void restoreDocumentRenamesOnTitleConflict() {
        when(fileMapper.selectById(77L)).thenReturn(null);
        when(documentMapper.selectById(77L)).thenReturn(deletedDocument());
        when(documentMapper.selectCount(any())).thenReturn(1L, 0L);
        when(documentMapper.update(any(), any())).thenReturn(1);

        TrashBatchResult result = trashService.restore(USER, List.of(77L));

        assertTrue(result.failed().isEmpty());
        verify(documentMapper).update(any(), any());
        verify(workspaceService).reserveSpace(WS, 5L);
    }

    @Test
    @DisplayName("彻底删除文档：只删行（配额在删除时已释放）")
    void purgeDocumentDeletesRowOnly() {
        when(fileMapper.selectById(77L)).thenReturn(null);
        when(documentMapper.selectById(77L)).thenReturn(deletedDocument());
        when(documentMapper.deleteById(77L)).thenReturn(1);

        TrashBatchResult result = trashService.purge(USER, List.of(77L));

        assertTrue(result.failed().isEmpty());
        verify(documentMapper).deleteById(77L);
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
    }

    @Test
    @DisplayName("清空回收站：文件 + 文档都删（不再释放配额）")
    void emptyPurgesFilesAndDocuments() {
        when(fileMapper.selectList(any())).thenReturn(List.<WorkspaceFile>of());
        when(documentMapper.selectList(any())).thenReturn(List.of(deletedDocument()));
        when(documentMapper.deleteById(77L)).thenReturn(1);

        int purged = trashService.empty(USER, WS);

        assertEquals(1, purged);
        verify(documentMapper).deleteById(77L);
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
    }

    @Test
    @DisplayName("过期清理：文档过期行也被物理删除（不再释放配额）")
    void purgeExpiredIncludesDocuments() {
        when(configService.getInt(anyString(), anyInt())).thenReturn(200);
        when(fileMapper.selectList(any())).thenReturn(List.<WorkspaceFile>of());
        when(documentMapper.selectList(any())).thenReturn(List.of(deletedDocument()), List.of());
        when(documentMapper.deleteById(77L)).thenReturn(1);
        lenient().when(opsMapper.tryAdvisoryLock(anyLong())).thenReturn(true);
        lenient().when(opsJobRegistry.run(anyString(), any())).thenAnswer(inv ->
                ((java.util.function.Supplier<OpsJobRegistry.JobResult>) inv.getArgument(1)).get());

        trashService.purgeExpired();

        verify(documentMapper).deleteById(77L);
        verify(workspaceService, never()).reserveSpace(any(), anyLong());
    }

    @Test
    @DisplayName("文档不存在 → 彻底删除返回 404 失败明细")
    void missingDocument404() {
        when(fileMapper.selectById(77L)).thenReturn(null);
        when(documentMapper.selectById(77L)).thenReturn(null);

        TrashBatchResult result = trashService.purge(USER, List.of(77L));

        assertEquals(1, result.failed().size());
        assertTrue(result.failed().get(0).reason().contains("不存在"));
    }
}
