package com.bond.transfer.service;

import com.bond.common.entity.TransferChunk;
import com.bond.common.entity.TransferTask;
import com.bond.common.enums.TransferStatus;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.FriendshipMapper;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import com.bond.transfer.mapper.DeviceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-25 B：分片上传校验 —— 索引越界、声明大小不符、实写超限、重复分片幂等、
 * 完成后总字节数不符拒绝入库并清理。
 */
@ExtendWith(MockitoExtension.class)
class ChunkUploadValidationTest {

    private static final long TASK_ID = 880001L;

    @Mock
    private TransferTaskMapper taskMapper;

    @Mock
    private TransferChunkMapper chunkMapper;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private ConfigService configService;

    @Mock
    private FriendshipMapper friendshipMapper;

    @Mock
    private WorkspaceMemberMapper workspaceMemberMapper;

    @Mock
    private EntitlementResolver entitlementResolver;

    @Mock
    private UsageService usageService;

    @Mock
    private DeviceMapper deviceMapper;

    @InjectMocks
    private TransferService transferService;

    private Path tempPath;

    @BeforeEach
    void setUp() throws Exception {
        tempPath = Path.of(System.getProperty("java.io.tmpdir"), "bond-transfer", TASK_ID + ".tmp");
        Files.createDirectories(tempPath.getParent());
        Files.deleteIfExists(tempPath);
        Files.createFile(tempPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempPath);
    }

    private TransferTask task(long fileSize, int chunkSize) {
        TransferTask task = new TransferTask();
        task.setId(TASK_ID);
        task.setSenderId(1L);
        task.setReceiverId(2L);
        task.setFileName("a.bin");
        task.setFileSize(fileSize);
        task.setChunkSize(chunkSize);
        task.setChunkCount((int) ((fileSize + chunkSize - 1) / chunkSize));
        task.setMinioPath("transfers/" + TASK_ID);
        task.setStatus(TransferStatus.PENDING.getValue());
        task.setUploadedChunks(0);
        return task;
    }

    private TransferChunk chunk(int index, long size, int status) {
        TransferChunk chunk = new TransferChunk();
        chunk.setTaskId(TASK_ID);
        chunk.setChunkIndex(index);
        chunk.setChunkSize(size);
        chunk.setStatus(status);
        return chunk;
    }

    private static InputStream bytes(int length) {
        return new ByteArrayInputStream(new byte[length]);
    }

    @Test
    @DisplayName("chunk_index 越界直接拒绝（不触碰临时文件）")
    void rejectsOutOfRangeChunkIndex() throws Exception {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(10, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.uploadChunk(TASK_ID, 2, null, bytes(5), 5, 1L));

        assertEquals(400, ex.getCode());
        assertEquals(0L, Files.size(tempPath));
        verify(chunkMapper, never()).updateById(any(TransferChunk.class));
    }

    @Test
    @DisplayName("HTTP 声明大小与分片期望不一致：拒绝且不落盘")
    void rejectsDeclaredSizeMismatch() throws Exception {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(10, 5));
        when(chunkMapper.selectOne(any())).thenReturn(chunk(0, 5, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.uploadChunk(TASK_ID, 0, null, bytes(3), 3, 1L));

        assertEquals(400, ex.getCode());
        assertEquals(0L, Files.size(tempPath));
    }

    @Test
    @DisplayName("实际请求体超过分片期望：立即拒绝（声明 1B 实写大内容被拦截）")
    void rejectsBodyLargerThanExpected() throws Exception {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(10, 5));
        when(chunkMapper.selectOne(any())).thenReturn(chunk(0, 5, 0));
        when(chunkMapper.sumUploadedBytes(TASK_ID)).thenReturn(0L);

        // size=0 绕过声明检查，验证流式读取时的硬上限
        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.uploadChunk(TASK_ID, 0, null, bytes(8), 0L, 1L));

        assertEquals(400, ex.getCode());
        assertTrue(Files.size(tempPath) <= 5L);
    }

    @Test
    @DisplayName("重复上传已完成分片：幂等成功且不重复写入")
    void duplicateCompletedChunkIsIdempotent() throws Exception {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(10, 5));
        when(chunkMapper.selectOne(any())).thenReturn(chunk(0, 5, 1));

        transferService.uploadChunk(TASK_ID, 0, null, bytes(5), 5L, 1L);

        assertEquals(0L, Files.size(tempPath));
        verify(chunkMapper, never()).updateById(any(TransferChunk.class));
        verify(taskMapper, never()).updateById(any(TransferTask.class));
    }

    @Test
    @DisplayName("完成后总字节数 != 声明大小：拒绝入库、清理临时文件、重置分片")
    void completeRejectsByteCountMismatchAndCleans() throws Exception {
        TransferTask task = task(10, 5);
        task.setUploadedChunks(2);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        TransferChunk c0 = chunk(0, 5, 1);
        TransferChunk c1 = chunk(1, 3, 1);
        when(chunkMapper.selectList(any())).thenReturn(List.of(c0, c1));
        Files.write(tempPath, new byte[8]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.completeTask(TASK_ID, 1L));

        assertEquals(400, ex.getCode());
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
        assertFalse(Files.exists(tempPath));
        // task-109：重置改为单条显式 UPDATE（status=0, etag=NULL）；MP updateById 清不掉 etag
        verify(chunkMapper).resetByTaskId(TASK_ID);
        verify(chunkMapper, org.mockito.Mockito.never()).updateById(any(TransferChunk.class));
        assertEquals(TransferStatus.PENDING.getValue(), task.getStatus());
        assertEquals(0, task.getUploadedChunks());
    }

    @Test
    @DisplayName("分片未全部成功：拒绝 complete（保留临时文件允许续传）")
    void completeRejectsMissingChunks() throws Exception {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(10, 5));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0, 5, 1), chunk(1, 5, 0)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.completeTask(TASK_ID, 1L));

        assertEquals(400, ex.getCode());
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("全部分片正确且总字节一致：上传对象并标记完成")
    void completeHappyPathUploadsObject() throws Exception {
        TransferTask task = task(10, 5);
        task.setUploadedChunks(2);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0, 5, 1), chunk(1, 5, 1)));
        Files.write(tempPath, new byte[10]);

        transferService.completeTask(TASK_ID, 1L);

        verify(objectStorage).putObject(eq("transfers/" + TASK_ID), any(InputStream.class), eq(10L),
                eq("application/octet-stream"));
        assertFalse(Files.exists(tempPath));
        assertEquals(TransferStatus.COMPLETED.getValue(), task.getStatus());
    }

    @Test
    @DisplayName("分片元数据数量与任务声明不一致：拒绝且不写入")
    void completeRejectsChunkMetadataMismatch() throws Exception {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(10, 5));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0, 5, 1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.completeTask(TASK_ID, 1L));

        assertEquals(400, ex.getCode());
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
    }
}
