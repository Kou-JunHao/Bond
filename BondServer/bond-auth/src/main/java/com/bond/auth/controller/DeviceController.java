package com.bond.auth.controller;

import com.bond.common.dto.DeviceDTO;
import com.bond.common.dto.Result;
import com.bond.auth.service.DeviceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public Result<DeviceDTO> registerDevice(@RequestHeader("Authorization") String auth,
                                            @RequestBody RegisterDeviceRequest req) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return Result.ok(deviceService.registerDevice(token, req.getDeviceName(), req.getDeviceType(), req.getPublicKey()));
    }

    @GetMapping
    public Result<List<DeviceDTO>> listDevices(@RequestHeader("Authorization") String auth) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return Result.ok(deviceService.listDevices(token));
    }

    @PutMapping("/{id}")
    public Result<DeviceDTO> updateDevice(@RequestHeader("Authorization") String auth,
                                          @PathVariable Long id,
                                          @RequestBody UpdateDeviceRequest req) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        return Result.ok(deviceService.updateDevice(token, id, req.getDeviceName(), req.getPublicKey()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteDevice(@RequestHeader("Authorization") String auth,
                                     @PathVariable Long id) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        deviceService.deleteDevice(token, id);
        return Result.ok();
    }

    @PostMapping("/{id}/heartbeat")
    public Result<Void> heartbeat(@RequestHeader("Authorization") String auth,
                                  @PathVariable Long id) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        deviceService.heartbeat(token, id);
        return Result.ok();
    }

    @GetMapping("/{id}/public-key")
    public Result<String> getPublicKey(@PathVariable Long id) {
        return Result.ok(deviceService.getPublicKey(id));
    }

    @Data
    static class RegisterDeviceRequest {
        private String deviceName;
        private String deviceType;
        private String publicKey;
    }

    @Data
    static class UpdateDeviceRequest {
        private String deviceName;
        private String publicKey;
    }
}
