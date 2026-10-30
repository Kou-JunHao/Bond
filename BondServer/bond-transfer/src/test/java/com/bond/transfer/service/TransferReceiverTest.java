package com.bond.transfer.service;

import com.bond.common.dto.TransferTaskDTO;
import com.bond.common.entity.Device;
import com.bond.common.entity.TransferTask;
import com.bond.common.entity.User;
import com.bond.common.enums.TransferStatus;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.DeviceMapper;
import com.bond.transfer.mapper.FriendshipMapper;
import com.bond.transfer.mapper.TransferChunkMapper;
import com.bond.transfer.mapper.TransferTaskMapper;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-94：传输任务接收方可见性与接受/拒绝语义。
 *
 * <p>覆盖：received 范围包含「账号接收」与「设备接收（receiverId 为空）」两类任务；
 * 发送方不能接受/拒绝；receiverId 为空时按接收设备归属补权限；重复 accept/reject 幂等；
 * 非 PENDING 状态 409；createTask 入库前用接收设备归属补 receiverId。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransferReceiverTest {

    private static final Long SENDER_ID = 1L;
    private static final Long RECEIVER_ID = 2L;
    private static final Long STRANGER_ID = 99L;
    private static final Long DEVICE_ID = 900L;
    private static final Long TASK_ID = 7000L;

    @Mock
    private TransferTaskMapper taskMapper;
    @Mock
    private TransferChunkMapper chunkMapper;
    @Mock
    private FriendshipMapper friendshipMapper;
    @Mock
    private WorkspaceMemberMapper workspaceMemberMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private ConfigService configService;
    @Mock
    private EntitlementResolver entitlementResolver;
    @Mock
    private UsageService usageService;

    @InjectMocks
    private TransferService transferService;

    @Test
    @DisplayName("received 范围：账号接收与设备接收的历史任务都可见")
    void receivedScopeIncludesAccountAndDeviceTasks() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, RECEIVER_ID)));
        TransferTask accountTask = task(TASK_ID, TransferStatus.COMPLETED, RECEIVER_ID, null);
        TransferTask deviceTask = task(TASK_ID + 1, TransferStatus.PENDING, null, DEVICE_ID);
        when(taskMapper.selectList(any())).thenReturn(List.of(accountTask, deviceTask));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(SENDER_ID, "sender")));

        List<TransferTaskDTO> tasks = transferService.listTasks(RECEIVER_ID, "received");

        assertEquals(2, tasks.size());
        assertEquals("sender", tasks.get(0).getPeerName());
        assertEquals("sender", tasks.get(1).getPeerName());
    }

    @Test
    @DisplayName("发送方不能接受任务 → 403")
    void senderCannotAcceptTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TASK_ID, TransferStatus.PENDING, RECEIVER_ID, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.acceptTask(TASK_ID, SENDER_ID));

        assertEquals(403, ex.getCode());
        verify(taskMapper, never()).updateById(any(TransferTask.class));
    }

    @Test
    @DisplayName("已 UPLOADING 时重复接受幂等：200 且不再写库")
    void acceptIsIdempotentWhenAlreadyUploading() {
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, TransferStatus.UPLOADING, RECEIVER_ID, null));

        TransferTaskDTO dto = transferService.acceptTask(TASK_ID, RECEIVER_ID);

        assertEquals(TransferStatus.UPLOADING.getValue(), dto.getStatus());
        verify(taskMapper, never()).updateById(any(TransferTask.class));
    }

    @Test
    @DisplayName("PENDING + 账号接收 → accept 后变 UPLOADING")
    void acceptMovesPendingToUploading() {
        TransferTask task = task(TASK_ID, TransferStatus.PENDING, RECEIVER_ID, null);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        TransferTaskDTO dto = transferService.acceptTask(TASK_ID, RECEIVER_ID);

        assertEquals(TransferStatus.UPLOADING.getValue(), dto.getStatus());
        verify(taskMapper).updateById(task);
    }

    @Test
    @DisplayName("PENDING + 设备接收（receiverId 为空）→ 设备归属用户可 accept")
    void deviceBasedReceiverCanAccept() {
        TransferTask task = task(TASK_ID, TransferStatus.PENDING, null, DEVICE_ID);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, RECEIVER_ID));

        TransferTaskDTO dto = transferService.acceptTask(TASK_ID, RECEIVER_ID);

        assertEquals(TransferStatus.UPLOADING.getValue(), dto.getStatus());
        verify(taskMapper).updateById(task);
    }

    @Test
    @DisplayName("PENDING + 拒绝 → REJECTED(6)")
    void rejectMovesPendingToRejected() {
        TransferTask task = task(TASK_ID, TransferStatus.PENDING, RECEIVER_ID, null);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        TransferTaskDTO dto = transferService.rejectTask(TASK_ID, RECEIVER_ID);

        assertEquals(TransferStatus.REJECTED.getValue(), dto.getStatus());
        assertEquals(6, TransferStatus.REJECTED.getValue());
        verify(taskMapper).updateById(task);
    }

    @Test
    @DisplayName("已完成任务拒绝 → 409")
    void rejectCompletedTaskConflicts() {
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(task(TASK_ID, TransferStatus.COMPLETED, RECEIVER_ID, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.rejectTask(TASK_ID, RECEIVER_ID));

        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("无关账号看任务详情 → 403")
    void unrelatedUserCannotReadTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TASK_ID, TransferStatus.PENDING, RECEIVER_ID, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transferService.getTask(TASK_ID, STRANGER_ID));

        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("createTask 只带 receiverDeviceId 时用设备归属补 receiverId")
    void createTaskFillsReceiverIdFromDevice() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(DEVICE_ID, RECEIVER_ID));
        when(entitlementResolver.resolve(SENDER_ID))
                .thenReturn(new Entitlements("free", PlanLimits.FREE_DEFAULTS, null));
        when(configService.getLong(eq("transfer.max_chunk_size"), anyLong())).thenReturn(64L * 1024 * 1024);
        when(configService.getLong(eq("transfer.max_chunks"), anyLong())).thenReturn(200_000L);
        when(configService.getBoolean("transfer.require_friend", true)).thenReturn(false);

        TransferTaskDTO dto = transferService.createTask(SENDER_ID, null, 11L, DEVICE_ID,
                "a.txt", 100L, 10, null, null);

        assertEquals(RECEIVER_ID, dto.getReceiverId());
        ArgumentCaptor<TransferTask> captor = ArgumentCaptor.forClass(TransferTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(RECEIVER_ID, captor.getValue().getReceiverId());
    }

    private static Device device(Long id, Long userId) {
        Device device = new Device();
        device.setId(id);
        device.setUserId(userId);
        device.setDeviceName("receiver-pc");
        return device;
    }

    private static TransferTask task(Long id, TransferStatus status, Long receiverId, Long receiverDeviceId) {
        TransferTask task = new TransferTask();
        task.setId(id);
        task.setSenderId(SENDER_ID);
        task.setReceiverId(receiverId);
        task.setReceiverDeviceId(receiverDeviceId);
        task.setFileName("a.bin");
        task.setFileSize(100L);
        task.setChunkSize(10);
        task.setChunkCount(10);
        task.setUploadedChunks(0);
        task.setStatus(status.getValue());
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
