package com.bond.transfer.service;

import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.FileUploadSession;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.UploadSessionMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * task-40：S3 Multipart 分片上传会话的单元测试（不连对象存储/DB）。
 */
@ExtendWith(MockitoExtension.class)
class UploadSessionServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long WS_ID = 9L;
    private static final int MB = 1024 * 1024;
    private static final int CHUNK = 8 * MB;

    @Mock
    private UploadSessionMapper sessionMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private ConfigService configService;

    @InjectMocks
    private UploadSessionService service;

    /** 配置默认值：未显式 stub 时返回调用方传入的默认值（chunk 8MB / maxChunks 10000 / ttl 24h）。 */
    @BeforeEach
    void baseStubs() {
        lenient().when(configService.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private FileUploadSession session(long id, long fileSize, int chunkSize, int totalChunks, String etags) {
        FileUploadSession s = new FileUploadSession();
        s.setId(id);
        s.setUserId(USER_ID);
        s.setWorkspaceId(WS_ID);
        s.setParentPath("/");
        s.setFileName("big.bin");
        s.setContentType("application/octet-stream");
        s.setFileSize(fileSize);
        s.setChunkSize(chunkSize);
        s.setTotalChunks(totalChunks);
        s.setStorageKey("workspaces/9/uuid/big.bin");
        s.setStorageUploadId("su-1");
        s.setEtags(etags);
        s.setReceivedBytes(0L);
        s.setExpiresAt(OffsetDateTime.now().plusHours(24));
        return s;
    }

    @Test
    void initCreatesMultipartSession() {
        when(sessionMapper.selectReusable(eq(USER_ID), eq(WS_ID), eq("/"), eq("big.bin"), eq(10L * MB)))
                .thenReturn(null);
        when(objectStorage.initMultipart(anyString(), any())).thenReturn("su-1");

        UploadSessionService.InitResult result =
                service.init(USER_ID, WS_ID, "/", "big.bin", 10L * MB, "application/octet-stream", null, null);

        assertEquals(CHUNK, result.chunkSize());
        assertEquals(2, result.totalChunks());
        assertTrue(result.uploadedChunks().isEmpty());
        ArgumentCaptor<FileUploadSession> captor = ArgumentCaptor.forClass(FileUploadSession.class);
        verify(sessionMapper).insertSession(captor.capture());
        assertEquals(10L * MB, captor.getValue().getFileSize());
        assertEquals("su-1", captor.getValue().getStorageUploadId());
        assertTrue(captor.getValue().getStorageKey().startsWith("workspaces/9/"));
        verify(workspaceService).checkUploadAllowed(WS_ID, USER_ID, 10L * MB);
    }

    @Test
    void initRejectsTooLargeFileForPlan() {
        doThrow(new BusinessException(400, "file exceeds plan max limit")).when(workspaceService)
                .checkUploadAllowed(WS_ID, USER_ID, 60L * MB);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.init(USER_ID, WS_ID, "/", "big.bin", 60L * MB, null, null, null));

        assertEquals(400, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void initReusesUnfinishedSession() {
        FileUploadSession reusable = session(100L, 10L * MB, CHUNK, 2, "[\"a\",null]");
        when(sessionMapper.selectReusable(eq(USER_ID), eq(WS_ID), eq("/"), eq("big.bin"), anyLong()))
                .thenReturn(reusable);

        UploadSessionService.InitResult result =
                service.init(USER_ID, WS_ID, "/", "big.bin", 10L * MB, null, null, null);

        assertEquals("100", result.uploadId());
        assertEquals(List.of(0), result.uploadedChunks());
        verify(objectStorage, never()).initMultipart(anyString(), any());
        verify(sessionMapper, never()).insertSession(any());
    }

    @Test
    void putPartRejectsIndexOutOfRange() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[]"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.putPart(USER_ID, "1", 5, new ByteArrayInputStream(new byte[0]), 0));

        assertEquals(400, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void putPartRejectsWrongSize() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[]"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.putPart(USER_ID, "1", 0, new ByteArrayInputStream(new byte[10]), 10));

        assertEquals(400, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void putPartRejectsForeignSession() {
        FileUploadSession foreign = session(1L, 10L * MB, CHUNK, 2, "[]");
        foreign.setUserId(8L);
        when(sessionMapper.selectSession(1L)).thenReturn(foreign);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.putPart(USER_ID, "1", 0, new ByteArrayInputStream(new byte[0]), 0));

        assertEquals(404, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void putPartRejectsExpiredSessionWith410() {
        FileUploadSession expired = session(1L, 10L * MB, CHUNK, 2, "[]");
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(sessionMapper.selectSession(1L)).thenReturn(expired);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.putPart(USER_ID, "1", 0, new ByteArrayInputStream(new byte[0]), 0));

        assertEquals(410, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void putPartDuplicateIndexDoesNotDoubleCount() {
        FileUploadSession first = session(1L, 10L * MB, CHUNK, 2, "[\"old\",null]");
        first.setReceivedBytes((long) CHUNK);
        FileUploadSession reloaded = session(1L, 10L * MB, CHUNK, 2, "[\"old\",null]");
        reloaded.setReceivedBytes((long) CHUNK);
        when(sessionMapper.selectSession(1L)).thenReturn(first, reloaded);
        when(objectStorage.uploadPart(anyString(), anyString(), eq(1), any(InputStream.class), eq((long) CHUNK)))
                .thenReturn("new");

        service.putPart(USER_ID, "1", 0, new ByteArrayInputStream(new byte[10]), CHUNK);

        ArgumentCaptor<String> etags = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateProgress(eq(1L), etags.capture(), eq((long) CHUNK));
        assertTrue(etags.getValue().contains("\"new\""));
        assertFalse(etags.getValue().contains("\"old\""));
    }

    @Test
    void completeRejectsMissingParts() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[\"e0\",null]"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.complete(USER_ID, "1"));

        assertEquals(400, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void completeRejectsQuotaShortageAndKeepsSession() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[\"e0\",\"e1\"]"));
        doThrow(new BusinessException(413, "空间不足")).when(workspaceService)
                .checkUploadAllowed(eq(WS_ID), eq(USER_ID), anyLong());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.complete(USER_ID, "1"));

        assertEquals(413, e.getCode());
        verify(objectStorage, never()).completeMultipart(anyString(), anyString(), anyList());
        verify(workspaceService, never()).reserveUploadQuota(anyLong(), anyLong());
        verify(sessionMapper, never()).deleteById(anyLong());
    }

    @Test
    void completeCreatesFileAndDeletesSession() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[\"e0\",\"e1\"]"));
        when(workspaceService.createUploadedFile(eq(WS_ID), eq(USER_ID), eq("/"), eq("big.bin"),
                eq(10L * MB), eq("workspaces/9/uuid/big.bin"), any(), eq(null)))
                .thenReturn(new WorkspaceFileDTO());
        doNothing().when(objectStorage).completeMultipart(anyString(), anyString(), anyList());

        WorkspaceFileDTO dto = service.complete(USER_ID, "1");

        assertNotNull(dto);
        verify(workspaceService).reserveUploadQuota(WS_ID, 10L * MB);
        ArgumentCaptor<List<ObjectStorage.PartETag>> parts = ArgumentCaptor.forClass(List.class);
        verify(objectStorage).completeMultipart(eq("workspaces/9/uuid/big.bin"), eq("su-1"), parts.capture());
        assertEquals(2, parts.getValue().size());
        assertEquals((long) CHUNK, parts.getValue().get(0).size());
        assertEquals(2L * MB, parts.getValue().get(1).size());
        verify(sessionMapper).deleteById(1L);
    }

    @Test
    void completeRollsBackQuotaWhenMultipartFails() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[\"e0\",\"e1\"]"));
        doThrow(new RuntimeException("s3 down")).when(objectStorage)
                .completeMultipart(anyString(), anyString(), anyList());

        assertThrows(RuntimeException.class, () -> service.complete(USER_ID, "1"));

        verify(workspaceService).releaseUploadQuota(WS_ID, 10L * MB);
        verify(sessionMapper, never()).deleteById(anyLong());
    }

    @Test
    void abortCallsMultipartAbortAndDeletesSession() {
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, "[]"));

        service.abort(USER_ID, "1");

        verify(objectStorage).abortMultipart("workspaces/9/uuid/big.bin", "su-1");
        verify(sessionMapper).deleteById(1L);
    }

    @Test
    void cleanupExpiredRespectsBatchLimit() {
        when(configService.getInt("upload.cleanup_batch", 200)).thenReturn(5);
        FileUploadSession a = session(1L, 10L * MB, CHUNK, 2, "[]");
        FileUploadSession b = session(2L, 10L * MB, CHUNK, 2, "[]");
        when(sessionMapper.selectExpired(5)).thenReturn(List.of(a, b));

        service.cleanupExpired(50);

        verify(sessionMapper).selectExpired(5);
        verify(objectStorage, times(2)).abortMultipart(anyString(), anyString());
        verify(sessionMapper).deleteById(1L);
        verify(sessionMapper).deleteById(2L);
    }

    @Test
    void cleanupDisabledWhenBatchIsZero() {
        when(configService.getInt("upload.cleanup_batch", 200)).thenReturn(0);

        service.cleanupExpired(50);

        verifyNoInteractions(sessionMapper);
        verifyNoInteractions(objectStorage);
    }

    @Test
    void statusReturnsUploadedChunksAndReceivedBytes() {
        FileUploadSession s = session(1L, 10L * MB, CHUNK, 2, "[\"e0\",null]");
        s.setReceivedBytes((long) CHUNK);
        when(sessionMapper.selectSession(1L)).thenReturn(s);

        UploadSessionService.StatusResult result = service.status(USER_ID, "1");

        assertEquals(List.of(0), result.uploadedChunks());
        assertEquals((long) CHUNK, result.receivedBytes());
        assertEquals(2, result.totalChunks());
    }

    // ── task-41：hash 落库 / 逐片校验 ──

    @Test
    void initRejectsMalformedFileHash() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.init(USER_ID, WS_ID, "/", "big.bin", 10L * MB, null, "not-a-hash", null));

        assertEquals(400, e.getCode());
        verifyNoInteractions(objectStorage);
        verifyNoInteractions(workspaceService);
    }

    @Test
    void initRejectsChunkHashesCountMismatch() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.init(USER_ID, WS_ID, "/", "big.bin", 10L * MB, null,
                        null, List.of("a".repeat(64))));

        assertEquals(400, e.getCode());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void completePersistsDeclaredFileHash() {
        String hash = "b".repeat(64);
        String etags = "{\"p\":[\"e0\",\"e1\"],\"h\":\"" + hash + "\"}";
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 10L * MB, CHUNK, 2, etags));
        when(workspaceService.createUploadedFile(eq(WS_ID), eq(USER_ID), eq("/"), eq("big.bin"),
                eq(10L * MB), eq("workspaces/9/uuid/big.bin"), any(), eq(hash)))
                .thenReturn(new WorkspaceFileDTO());
        doNothing().when(objectStorage).completeMultipart(anyString(), anyString(), anyList());

        service.complete(USER_ID, "1");

        verify(workspaceService).createUploadedFile(eq(WS_ID), eq(USER_ID), eq("/"), eq("big.bin"),
                eq(10L * MB), eq("workspaces/9/uuid/big.bin"), any(), eq(hash));
        verify(sessionMapper).deleteById(1L);
    }

    @Test
    void putPartRejectsChunkHashMismatch() {
        String wrongHash = "c".repeat(64);
        String etags = "{\"p\":[null],\"c\":[\"" + wrongHash + "\"]}";
        when(sessionMapper.selectSession(1L)).thenReturn(session(1L, 5L, CHUNK, 1, etags));
        when(objectStorage.uploadPart(anyString(), anyString(), eq(1), any(), eq(5L)))
                .thenReturn("etag0");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.putPart(USER_ID, "1", 0, new ByteArrayInputStream("hello".getBytes()), 5));

        assertEquals(400, e.getCode());
        verify(sessionMapper, never()).updateProgress(anyLong(), anyString(), anyLong());
    }

    @Test
    void putPartAcceptsMatchingChunkHash() throws Exception {
        byte[] content = "hello".getBytes();
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        String etags = "{\"p\":[null],\"c\":[\"" + hash + "\"]}";
        FileUploadSession s = session(1L, 5L, CHUNK, 1, etags);
        when(sessionMapper.selectSession(1L)).thenReturn(s);
        doAnswer(invocation -> {
            InputStream in = invocation.getArgument(3);
            in.readAllBytes();
            return "etag0";
        }).when(objectStorage).uploadPart(anyString(), anyString(), eq(1), any(InputStream.class), eq(5L));

        service.putPart(USER_ID, "1", 0, new ByteArrayInputStream(content), 5);

        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateProgress(eq(1L), captured.capture(), eq(5L));
        assertTrue(captured.getValue().contains("\"etag0\""));
    }
}
