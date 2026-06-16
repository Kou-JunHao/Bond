package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.auth.mapper.DeviceMapper;
import com.bond.common.dto.DeviceDTO;
import com.bond.common.entity.Device;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.JwtUtils;
import com.bond.common.utils.SnowflakeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceMapper deviceMapper;

    public DeviceDTO registerDevice(String token, String deviceName, String deviceType, String publicKey) {
        Long userId = JwtUtils.getUserId(token);
        Device device = new Device();
        device.setId(SnowflakeId.nextId());
        device.setUserId(userId);
        device.setDeviceName(deviceName);
        device.setDeviceType(deviceType);
        device.setPublicKey(publicKey);
        device.setIsOnline(true);
        device.setLastSeenAt(LocalDateTime.now());
        deviceMapper.insert(device);
        return toDTO(device);
    }

    public List<DeviceDTO> listDevices(String token) {
        Long userId = JwtUtils.getUserId(token);
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>().eq(Device::getUserId, userId));
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
