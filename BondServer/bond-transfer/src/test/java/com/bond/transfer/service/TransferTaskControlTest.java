package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bond.common.dto.TransferTaskDTO;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 传输中心：取消 / 重试任务的状态机。
 */
@ExtendWith(MockitoExtension.class)
class TransferTaskControlTest {

    private static final long TASK_ID = 1L;
    private static final long SENDER_ID = 10L;
    private static final long RECEIVER_ID = 20L;

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

    @Test
    @DisplayName("接收方可取消进行中的任务，状态置 CANCELLED 且分片复位")
    void receiverCanCancelInProgressTask() {
        TransferTask task = task(TransferStatus.UPLOADING);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chunk(0), chunk(1)));

        TransferTaskDTO dto = transferService.cancelTask(TASK_ID, RECEIVER_ID);

        assertEquals(TransferStatus.CANCELLED.getValue(), dto.getStatus());
        assertEquals(TransferStatus.CANCELLED.getValue(), task.getStatus());
        assertEquals(0, task.getUploadedChunks().intValue());
        // resetIncompleteUpload 与设置 CANCELLED 各更新一次
        verify(taskMapper, org.mockito.Mockito.times(2)).updateById(task);
    }

    @Test
    @DisplayName("已完成任务不可取消 → 400")
    void completedTaskCannotBeCancelled() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TransferStatus.COMPLETED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.cancelTask(TASK_ID, SENDER_ID));
        assertEquals(400, ex.getCode());
        verify(taskMapper, never()).updateById(any(TransferTask.class));
    }

    @Test
    @DisplayName("无关用户不能取消任务 → 403")
    void outsiderCannotCancelTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TransferStatus.PENDING));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.cancelTask(TASK_ID, 99L));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("重复取消幂等：已 CANCELLED 直接返回，不重复清理")
    void cancellingAgainIsIdempotent() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TransferStatus.CANCELLED));

        TransferTaskDTO dto = transferService.cancelTask(TASK_ID, SENDER_ID);

        assertEquals(TransferStatus.CANCELLED.getValue(), dto.getStatus());
        verify(chunkMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("失败任务可重试：状态回到 PENDING，分片清空待重传")
    void failedTaskCanBeRetried() {
        TransferTask task = task(TransferStatus.FAILED);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chunk(0), chunk(1)));

        TransferTaskDTO dto = transferService.retryTask(TASK_ID, SENDER_ID);

        assertEquals(TransferStatus.PENDING.getValue(), dto.getStatus());
        assertEquals(TransferStatus.PENDING.getValue(), task.getStatus());
        assertEquals(0, task.getUploadedChunks().intValue());
        verify(taskMapper).updateById(task);
    }

    @Test
    @DisplayName("进行中的任务不可重试 → 400")
    void inProgressTaskCannotBeRetried() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TransferStatus.PENDING));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.retryTask(TASK_ID, SENDER_ID));
        assertEquals(400, ex.getCode());
    }

    private static TransferTask task(TransferStatus status) {
        TransferTask task = new TransferTask();
        task.setId(TASK_ID);
        task.setSenderId(SENDER_ID);
        task.setReceiverId(RECEIVER_ID);
        task.setFileName("a.bin");
        task.setFileSize(2048L);
        task.setChunkSize(1024);
        task.setChunkCount(2);
        task.setUploadedChunks(2);
        task.setMinioPath("transfer/" + TASK_ID);
        task.setStatus(status.getValue());
        return task;
    }

    private static TransferChunk chunk(int index) {
        TransferChunk chunk = new TransferChunk();
        chunk.setId((long) index + 1);
        chunk.setTaskId(TASK_ID);
        chunk.setChunkIndex(index);
        chunk.setChunkSize(1024L);
        chunk.setStatus(1);
        return chunk;
    }
}
