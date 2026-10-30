package com.bond.auth.service;

import com.bond.auth.mapper.DeviceMapper;
import com.bond.common.dto.DeviceDTO;
import com.bond.common.entity.Device;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：`/api/devices` 重复行。
 *
 * <p>根因：历史实现每次注册都 {@code INSERT}，devices 表没有稳定设备标识/唯一约束，
 * 同一台机器重复登录注册后就会出现多行。修复后按稳定 deviceUid 先查后写（V5 迁移
 * 另加唯一索引兜底并发），同一设备的重复注册只保留一行。</p>
 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    private static final Long USER_ID = 1001L;
    /** 真实 JWT（bond-auth 的 surefire 已注入测试用 JWT_SECRET）。 */
    private static final String TOKEN = JwtUtils.generateAccessToken(USER_ID, "tester");

    @Mock
    private DeviceMapper deviceMapper;

    @InjectMocks
    private DeviceService deviceService;

    private static Device existingDevice() {
        Device device = new Device();
        device.setId(9001L);
        device.setUserId(USER_ID);
        device.setDeviceUid("PC-01|windows");
        device.setDeviceName("PC-01");
        device.setDeviceType("windows");
        device.setPublicKey("old-pk");
        device.setIsOnline(true);
        device.setLastSeenAt(LocalDateTime.now().minusMinutes(5));
        return device;
    }

    @Test
    @DisplayName("同一设备重复注册：只 INSERT 一次，第二次走更新，行数不增加")
    void sameDeviceRegisteredTwiceLeavesOneRow() {
        Device existing = existingDevice();
        when(deviceMapper.selectOne(any())).thenReturn(null, existing);
        when(deviceMapper.insert(any(Device.class))).thenReturn(1);

        DeviceDTO first = deviceService.registerDevice(TOKEN, null, "PC-01", "windows", "pk-1");
        DeviceDTO second = deviceService.registerDevice(TOKEN, null, "PC-01", "windows", "pk-2");

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper, times(1)).insert(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals("PC-01|windows", captor.getValue().getDeviceUid());

        // 第二次注册命中同一行：只更新，不再插入
        verify(deviceMapper, times(1)).updateById(existing);
        assertNotNull(first.getId());
        assertEquals(existing.getId(), second.getId());
        assertEquals("pk-2", existing.getPublicKey());
    }

    @Test
    @DisplayName("客户端上报 deviceId 时优先作为稳定标识（不按名称合并不同机器）")
    void deviceIdTakesPrecedence() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(deviceMapper.insert(any(Device.class))).thenReturn(1);

        deviceService.registerDevice(TOKEN, "fp-abc-123", "PC-01", "windows", null);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper).insert(captor.capture());
        assertEquals("fp-abc-123", captor.getValue().getDeviceUid());
    }

    @Test
    @DisplayName("并发注册撞唯一索引（DuplicateKeyException）时回退为更新，不抛出")
    void concurrentInsertFallsBackToUpdate() {
        Device existing = existingDevice();
        when(deviceMapper.selectOne(any())).thenReturn(null, existing);
        doThrow(new DuplicateKeyException("ux_devices_user_uid"))
                .when(deviceMapper).insert(any(Device.class));

        DeviceDTO dto = deviceService.registerDevice(TOKEN, "fp-abc-123", "PC-01", "windows", "pk-3");

        assertEquals(existing.getId(), dto.getId());
        verify(deviceMapper, times(1)).updateById(existing);
    }

    @Test
    @DisplayName("deviceName 为空 → 400，不写库")
    void blankDeviceNameRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deviceService.registerDevice(TOKEN, null, "  ", "windows", null));
        assertEquals(400, ex.getCode());
    }
}
