using BondClient.Models;

namespace BondClient.Services;

public class DeviceService
{
    private readonly ApiClient _api;

    public DeviceService(ApiClient api)
    {
        _api = api;
    }

    public async Task<DeviceModel?> RegisterDeviceAsync(string deviceName, string deviceType, string publicKey)
    {
        var json = $$"""{"deviceName":"{{deviceName}}","deviceType":"{{deviceType}}","publicKey":"{{publicKey}}"}""";
        var result = await _api.PostRawJsonAsync("/api/devices", json, BondJsonContext.Default.ApiResultDeviceModel);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<List<DeviceModel>?> ListDevicesAsync()
    {
        var result = await _api.GetJsonAsync(
            "/api/devices",
            BondJsonContext.Default.ApiResultListDeviceModel);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<DeviceModel?> UpdateDeviceAsync(long deviceId, string? deviceName, string? publicKey)
    {
        var dn = deviceName ?? "";
        var pk = publicKey ?? "";
        var json = $$"""{"deviceName":"{{dn}}","publicKey":"{{pk}}"}""";
        var result = await _api.PutRawJsonAsync($"/api/devices/{deviceId}", json, BondJsonContext.Default.ApiResultDeviceModel);
        return result?.IsSuccess == true ? result.Data : null;
    }

    public async Task<bool> DeleteDeviceAsync(long deviceId)
    {
        var result = await _api.DeleteAsync($"/api/devices/{deviceId}");
        return result?.IsSuccess == true;
    }

    public async Task<bool> HeartbeatAsync(long deviceId)
    {
        var result = await _api.PostRawJsonAsync($"/api/devices/{deviceId}/heartbeat", "{}");
        return result?.IsSuccess == true;
    }

    public async Task<string?> GetPublicKeyAsync(long deviceId)
    {
        var result = await _api.GetJsonAsync(
            $"/api/devices/{deviceId}/public-key",
            BondJsonContext.Default.ApiResultString);
        return result?.IsSuccess == true ? result.Data : null;
    }
}
