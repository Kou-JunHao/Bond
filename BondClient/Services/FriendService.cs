using BondClient.Models;
using System.Text.Json.Serialization.Metadata;

namespace BondClient.Services;

public class FriendService
{
    private readonly ApiClient _api;
    private static readonly BondJsonContext Ctx = BondJsonContext.Default;

    public event Action? FriendsUpdated;
    public event Action? RequestsUpdated;

    public FriendService(ApiClient api)
    {
        _api = api;
    }

    public async Task<List<FriendInfo>?> GetFriendsAsync()
    {
        var result = await _api.GetJsonAsync("/api/friends", Ctx.ApiResultListFriendInfo);
        if (result?.IsSuccess == true) return result.Data;
        return null;
    }

    public async Task<List<FriendRequestInfo>?> GetPendingRequestsAsync()
    {
        var result = await _api.GetJsonAsync("/api/friends/requests", Ctx.ApiResultListFriendRequestInfo);
        if (result?.IsSuccess == true) return result.Data;
        return null;
    }

    public async Task<bool> SendRequestAsync(long toUserId, string? message = null)
    {
        var json = message != null
            ? $$"""{"toUserId":{{toUserId}},"message":"{{message}}"}"""
            : $$"""{"toUserId":{{toUserId}}}""";
        var result = await _api.PostRawJsonAsync("/api/friends/request", json);
        if (result?.IsSuccess == true) { RequestsUpdated?.Invoke(); return true; }
        return false;
    }

    public async Task<bool> AcceptRequestAsync(long requestId)
    {
        var result = await _api.PostRawJsonAsync($"/api/friends/requests/{requestId}/accept", "{}");
        if (result?.IsSuccess == true) { RequestsUpdated?.Invoke(); FriendsUpdated?.Invoke(); return true; }
        return false;
    }

    public async Task<bool> RejectRequestAsync(long requestId)
    {
        var result = await _api.PostRawJsonAsync($"/api/friends/requests/{requestId}/reject", "{}");
        if (result?.IsSuccess == true) { RequestsUpdated?.Invoke(); return true; }
        return false;
    }

    public async Task<bool> RemoveFriendAsync(long friendUserId)
    {
        var result = await _api.DeleteAsync($"/api/friends/{friendUserId}");
        if (result?.IsSuccess == true) { FriendsUpdated?.Invoke(); return true; }
        return false;
    }

    public async Task<List<UserSearchResult>?> SearchUsersAsync(string query)
    {
        var result = await _api.GetJsonAsync($"/api/friends/search?q={Uri.EscapeDataString(query)}", Ctx.ApiResultListUserSearchResult);
        if (result?.IsSuccess == true) return result.Data;
        return null;
    }
}
