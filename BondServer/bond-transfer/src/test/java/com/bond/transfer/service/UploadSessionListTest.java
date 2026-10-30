package com.bond.transfer.service;

import com.bond.common.dto.PageResult;
import com.bond.common.dto.UploadSessionDTO;
import com.bond.common.entity.FileUploadSession;
import com.bond.transfer.mapper.UploadSessionMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-54：上传会话列表（只读、分页、无副作用）。
 */
@ExtendWith(MockitoExtension.class)
class UploadSessionListTest {

    private static final long USER = 7L;

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

    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        service = new UploadSessionService(sessionMapper, objectStorage, workspaceService, personalSpaceService, configService);
    }

    @Test
    @DisplayName("列表：分页映射、uploadId 字符串化、已收片数按 etags 计算")
    void listMapsSessionsAndPageEnvelope() {
        when(sessionMapper.countActive(USER)).thenReturn(3L);
        when(sessionMapper.selectActivePage(USER, 0L, 2)).thenReturn(List.of(
                session(101L, "movie.mkv", 10L, 2, 5L, "[\"etag-a\",null]"),
                session(102L, "photo.jpg", 8L, 2, 4L, "{\"p\":[\"e1\",\"e2\"],\"h\":null,\"c\":[null,null]}")));

        PageResult<UploadSessionDTO> page = service.list(USER, 1, 2);

        assertEquals(3L, page.getTotal());
        assertEquals(1, page.getPage());
        assertEquals(2, page.getSize());
        assertEquals(2, page.getItems().size());
        UploadSessionDTO first = page.getItems().get(0);
        assertEquals("101", first.getUploadId());
        assertEquals("movie.mkv", first.getFileName());
        assertEquals(10L, first.getTotalBytes());
        assertEquals(5L, first.getReceivedBytes());
        assertEquals(2, first.getTotalChunks());
        assertEquals(1, first.getChunksReceived());
        UploadSessionDTO second = page.getItems().get(1);
        assertEquals(2, second.getChunksReceived());
    }

    @Test
    @DisplayName("列表：size 超上限夹到 200，page 小于 1 归 1")
    void listClampsPageAndSize() {
        when(sessionMapper.countActive(USER)).thenReturn(1L);
        when(sessionMapper.selectActivePage(USER, 0L, 200)).thenReturn(List.of(
                session(201L, "big.iso", 100L, 4, 0L, "[]")));

        PageResult<UploadSessionDTO> page = service.list(USER, 0, 500);

        assertEquals(1, page.getPage());
        assertEquals(200, page.getSize());
        assertEquals(1, page.getItems().size());
        assertEquals(0, page.getItems().get(0).getChunksReceived());
    }

    @Test
    @DisplayName("列表：未传分页参数时默认 50 条")
    void listUsesDefaultSize() {
        when(sessionMapper.countActive(USER)).thenReturn(1L);
        when(sessionMapper.selectActivePage(USER, 0L, 50)).thenReturn(List.of(
                session(301L, "a.bin", 5L, 1, 0L, "[]")));

        PageResult<UploadSessionDTO> page = service.list(USER, null, null);

        assertEquals(50, page.getSize());
        assertEquals(1, page.getItems().size());
    }

    @Test
    @DisplayName("列表：没有未完成会话时直接返回空页，不查明细")
    void listEmptyShortCircuits() {
        when(sessionMapper.countActive(USER)).thenReturn(0L);

        PageResult<UploadSessionDTO> page = service.list(USER, 1, 20);

        assertEquals(0L, page.getTotal());
        assertTrue(page.getItems().isEmpty());
        verify(sessionMapper, never()).selectActivePage(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("列表：DTO 时间字段透传，不返回存储凭证")
    void listDoesNotLeakStorageCredentials() {
        OffsetDateTime created = OffsetDateTime.parse("2026-10-23T08:00:00+08:00");
        OffsetDateTime expires = created.plusHours(24);
        FileUploadSession session = session(401L, "doc.pdf", 30L, 2, 10L, "[\"e\"]");
        session.setCreatedAt(created);
        session.setExpiresAt(expires);
        when(sessionMapper.countActive(USER)).thenReturn(1L);
        when(sessionMapper.selectActivePage(USER, 0L, 20)).thenReturn(List.of(session));

        UploadSessionDTO dto = service.list(USER, 1, 20).getItems().get(0);

        assertNotNull(dto.getCreatedAt());
        assertEquals(created, dto.getCreatedAt());
        assertEquals(expires, dto.getExpiresAt());
        // DTO 里没有 storageUploadId / storageKey / etags 字段（编译期即不可泄漏）
    }

    private static FileUploadSession session(Long id, String fileName, long fileSize, int totalChunks,
                                             long receivedBytes, String etags) {
        FileUploadSession session = new FileUploadSession();
        session.setId(id);
        session.setUserId(USER);
        session.setWorkspaceId(9L);
        session.setParentPath("/");
        session.setFileName(fileName);
        session.setContentType("application/octet-stream");
        session.setFileSize(fileSize);
        session.setChunkSize((int) Math.max(1, fileSize / Math.max(1, totalChunks)));
        session.setTotalChunks(totalChunks);
        session.setStorageUploadId("up-" + id);
        session.setStorageKey("key-" + id);
        session.setEtags(etags);
        session.setReceivedBytes(receivedBytes);
        session.setCreatedAt(OffsetDateTime.parse("2026-10-23T08:00:00+08:00"));
        session.setUpdatedAt(session.getCreatedAt());
        session.setExpiresAt(session.getCreatedAt().plusHours(24));
        return session;
    }
}
