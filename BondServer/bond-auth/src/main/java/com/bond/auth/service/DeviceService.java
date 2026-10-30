package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.auth.mapper.DeviceMapper;
import com.bond.common.dto.DeviceDTO;
import com.bond.common.entity.Device;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.JwtUtils;
import com.bond.common.utils.SnowflakeId;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceMapper deviceMapper;

    /**
     * 注册设备（幂等 UPSERT）。
     *
     * <p>历史实现每次调用都 INSERT 新行，devices 表又没有稳定设备标识/唯一约束，
     * 导致同一台机器多次登录或注册后 {@code /api/devices} 返回重复行。
     * 现在以 deviceUid 为稳定标识先查后写；并发注册时唯一索引
     * {@code ux_devices_user_uid} 会拒绝后到者，这里捕获 {@link DuplicateKeyException}
     * 回退为更新，保证同一用户同一设备最终只有一行。</p>
     */
    public DeviceDTO registerDevice(String token, String deviceId, String deviceName,
                                    String deviceType, String publicKey) {
        Long userId = JwtUtils.getUserId(token);
        if (deviceName == null || deviceName.isBlank()) {
            throw new BusinessException(400, "设备名称不能为空");
        }
        String deviceUid = deriveDeviceUid(deviceId, deviceName, deviceType);
        Device existing = findDevice(userId, deviceUid);
        if (existing == null) {
            Device device = new Device();
            device.setId(SnowflakeId.nextId());
            device.setUserId(userId);
            device.setDeviceUid(deviceUid);
            device.setDeviceName(deviceName);
            device.setDeviceType(deviceType);
            device.setPublicKey(publicKey);
            device.setIsOnline(true);
            device.setLastSeenAt(LocalDateTime.now());
            try {
                deviceMapper.insert(device);
                return toDTO(device);
            } catch (DuplicateKeyException e) {
                // 并发注册同一设备：另一请求已插入，转为更新已存在的行
                existing = findDevice(userId, deviceUid);
                if (existing == null) {
                    throw e;
                }
            }
        }
        if (deviceType != null) existing.setDeviceType(deviceType);
        existing.setDeviceName(deviceName);
        if (publicKey != null) existing.setPublicKey(publicKey);
        existing.setIsOnline(true);
        existing.setLastSeenAt(LocalDateTime.now());
        deviceMapper.updateById(existing);
        return toDTO(existing);
    }

    private Device findDevice(Long userId, String deviceUid) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getUserId, userId)
                .eq(Device::getDeviceUid, deviceUid));
    }

    /**
     * 稳定设备标识推导：客户端上报的 deviceId（设备指纹）优先；缺省回退为
     * {@code deviceName + "|" + deviceType}。
     *
     * <p>回退规则必须与 {@code V5__devices_dedupe.sql} 的回填 SQL 完全一致
     * （同样 btrim），否则上线后首次重复注册会被当成新设备。</p>
     */
    static String deriveDeviceUid(String deviceId, String deviceName, String deviceType) {
        if (deviceId != null && !deviceId.isBlank()) {
            return deviceId.trim();
        }
        String name = deviceName == null ? "" : deviceName.trim();
        String type = deviceType == null ? "" : deviceType.trim();
        return name + "|" + type;
    }

    public List<DeviceDTO> listDevices(String token) {
        Long userId = JwtUtils.getUserId(token);
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getUserId, userId)
                        .orderByDesc(Device::getLastSeenAt));
        return devices.stream().map(this::toDTO).collect(Collectors.toList());
    }

    public DeviceDTO updateDevice(String token, Long deviceId, String deviceName, String publicKey) {
        Long userId = JwtUtils.getUserId(token);
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || !device.getUserId().equals(userId)) {
            throw new BusinessException(404, "设备不存在");
        }
        if (deviceName != null) device.setDeviceName(deviceName);
        if (publicKey != null) device.setPublicKey(publicKey);
        deviceMapper.updateById(device);
        return toDTO(device);
    }

    public void deleteDevice(String token, Long deviceId) {
        Long userId = JwtUtils.getUserId(token);
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || !device.getUserId().equals(userId)) {
            throw new BusinessException(404, "设备不存在");
        }
        deviceMapper.deleteById(deviceId);
    }

    public void heartbeat(String token, Long deviceId) {
        Long userId = JwtUtils.getUserId(token);
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || !device.getUserId().equals(userId)) {
            throw new BusinessException(404, "设备不存在");
        }
        device.setIsOnline(true);
        device.setLastSeenAt(LocalDateTime.now());
        deviceMapper.updateById(device);
    }

    public String getPublicKey(Long deviceId) {
        Device device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(404, "设备不存在");
        }
        return device.getPublicKey();
    }

    @Scheduled(fixedRate = 30000)
    public void markOfflineDevices() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(2);
        List<Device> onlineDevices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>().eq(Device::getIsOnline, true));
        for (Device d : onlineDevices) {
            if (d.getLastSeenAt() != null && d.getLastSeenAt().isBefore(threshold)) {
                d.setIsOnline(false);
                deviceMapper.updateById(d);
            }
        }
    }

    private DeviceDTO toDTO(Device device) {
        DeviceDTO dto = new DeviceDTO();
        dto.setId(device.getId());
        dto.setUserId(device.getUserId());
        dto.setDeviceName(device.getDeviceName());
        dto.setDeviceType(device.getDeviceType());
        dto.setPublicKey(device.getPublicKey());
        dto.setIsOnline(device.getIsOnline());
        dto.setLastSeenAt(device.getLastSeenAt() != null ? device.getLastSeenAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return dto;
    }
}
