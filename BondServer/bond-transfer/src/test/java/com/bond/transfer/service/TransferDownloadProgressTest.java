package com.bond.transfer.service;

import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.entity.TransferChunk;
import com.bond.common.entity.TransferTask;
import com.bond.common.entity.User;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.FriendshipMapper;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.DeviceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-55：接收方下载进度打点（V13）。
 *
 * <p>语义：接收方首次成功取走一个分片 → transfer_chunks.downloaded_at 由 NULL 迁移为 now()
 * 并返回 1 → 才把 transfer_tasks.downloaded_chunks 原子 +1；重复下载/发送方下载都不计数。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransferDownloadProgressTest {

    private static final long TASK = 1L;
    private static final long SENDER = 10L;
    private static final long RECEIVER = 20L;
    private static final long CHUNK = 100L;
    private static final String KEY = "users/10/a.bin";

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
    private UserMapper userMapper;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private UsageService usageService;
    @Mock
    private DeviceMapper deviceMapper;

    @InjectMocks
    private TransferService transferService;

    private TransferTask task;
    private TransferChunk chunk;

    @BeforeEach
    void setUp() {
        task = new TransferTask();
        task.setId(TASK);
        task.setSenderId(SENDER);
        task.setReceiverId(RECEIVER);
        task.setFileName("a.bin");
        task.setFileSize(30L);
        task.setChunkSize(10);
        task.setChunkCount(3);
        task.setMinioPath(KEY);
        task.setStatus(2);
        task.setUploadedChunks(3);
        task.setDownloadedChunks(0);

        chunk = new TransferChunk();
        chunk.setId(CHUNK);
        chunk.setTaskId(TASK);
        chunk.setChunkIndex(0);
        chunk.setChunkSize(10L);
        chunk.setStatus(1);
    }

    @Test
    @DisplayName("接收方首次下载分片：幂等标记成功一次 + 任务计数原子 +1")
    void receiverFirstDownloadIncrementsOnce() throws Exception {
        stubDownload(RECEIVER);
        when(chunkMapper.markDownloaded(CHUNK)).thenReturn(1);
        when(taskMapper.incrementDownloadedChunks(TASK)).thenReturn(1);

        InputStream stream = transferService.downloadChunk(TASK, RECEIVER, 0);

        assertNotNull(stream);
        verify(chunkMapper, times(1)).markDownloaded(CHUNK);
        verify(taskMapper, times(1)).incrementDownloadedChunks(TASK);
    }

    @Test
    @DisplayName("重复下载同一分片：markDownloaded 返回 0，不重复计数（幂等）")
    void repeatedDownloadDoesNotIncrementAgain() throws Exception {
        stubDownload(RECEIVER);
        when(chunkMapper.markDownloaded(CHUNK)).thenReturn(0);

        transferService.downloadChunk(TASK, RECEIVER, 0);

        verify(chunkMapper, times(1)).markDownloaded(CHUNK);
        verify(taskMapper, never()).incrementDownloadedChunks(anyLong());
    }

    @Test
    @DisplayName("发送方下载：下载照常放行，但不改接收方进度")
    void senderDownloadDoesNotTouchReceiverProgress() throws Exception {
        stubDownload(SENDER);

        InputStream stream = transferService.downloadChunk(TASK, SENDER, 0);

        assertNotNull(stream);
        verify(chunkMapper, never()).markDownloaded(anyLong());
        verify(taskMapper, never()).incrementDownloadedChunks(anyLong());
    }

    @Test
    @DisplayName("打点失败不阻断下载（进度是旁路）")
    void markingFailureDoesNotBreakDownload() throws Exception {
        stubDownload(RECEIVER);
        when(chunkMapper.markDownloaded(CHUNK)).thenThrow(new RuntimeException("db down"));

        InputStream stream = transferService.downloadChunk(TASK, RECEIVER, 0);

        assertNotNull(stream);
    }

    @Test
    @DisplayName("详情 DTO 暴露 downloadedChunks（列表/详情共用 toDTO）")
    void taskDetailExposesDownloadedChunks() {
        task.setDownloadedChunks(2);
        when(taskMapper.selectById(TASK)).thenReturn(task);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(SENDER, "sender")));

        TransferTaskDTO dto = transferService.getTask(TASK, RECEIVER);

        assertEquals(2, dto.getDownloadedChunks());
        assertEquals(3, dto.getChunkCount());
    }

    private void stubDownload(long viewer) throws Exception {
        when(taskMapper.selectById(TASK)).thenReturn(task);
        when(chunkMapper.selectOne(any())).thenReturn(chunk);
        when(objectStorage.getObject(eq(KEY), eq(0L), eq(10L)))
                .thenReturn(new ByteArrayInputStream(new byte[10]));
        when(usageService.meterAndThrottleDownload(eq(viewer), any(InputStream.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private static User user(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setUsername(nickname);
        return user;
    }
}
