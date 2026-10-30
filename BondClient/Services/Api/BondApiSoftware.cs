using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using System.Threading.Tasks;

namespace Bond.Services.Api;

/// <summary>软件包（产品级）DTO，与后端 /api/software/packages 对齐。</summary>
public sealed class ApiSoftwarePackageDto
{
    [JsonPropertyName("code")] public string Code { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("platform")] public string? Platform { get; set; }

    /// <summary>服务端已算好的最高版本（含未对该平台发布时为 null）。</summary>
    [JsonPropertyName("latestVersion")] public string? LatestVersion { get; set; }

    /// <summary>已发布发行物数量。</summary>
    [JsonPropertyName("releaseCount")] public int ReleaseCount { get; set; }

    /// <summary>最近一次发布时间（ISO-8601 字符串，可能为 null）。</summary>
    [JsonPropertyName("updatedAt")] public DateTimeOffset? UpdatedAt { get; set; }
}

/// <summary>发行物 DTO，与后端 /api/software/packages/{code}/releases 对齐。</summary>
public sealed class ApiSoftwareReleaseDto
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("packageCode")] public string? PackageCode { get; set; }
    [JsonPropertyName("version")] public string? Version { get; set; }
    [JsonPropertyName("channel")] public string? Channel { get; set; }
    [JsonPropertyName("fileName")] public string? FileName { get; set; }
    [JsonPropertyName("fileSize")] public long FileSize { get; set; }
    [JsonPropertyName("sha256")] public string? Sha256 { get; set; }
    [JsonPropertyName("releaseNotes")] public string? ReleaseNotes { get; set; }

    /// <summary>
    /// 发布时间：后端返回 ISO-8601 字符串（如 "2026-10-23T19:09:32.948949Z"），
    /// 未发布时为 null —— 必须用 DateTimeOffset?，用 long 接字符串会抛 JsonException。
    /// </summary>
    [JsonPropertyName("publishedAt")] public DateTimeOffset? PublishedAt { get; set; }

    /// <summary>创建时间：同样是 ISO-8601 字符串，可能为 null。</summary>
    [JsonPropertyName("createdAt")] public DateTimeOffset? CreatedAt { get; set; }
}

public sealed partial class BondApiClient
{
    /// <summary>已发布软件包列表（生产公共接口）。</summary>
    public Task<ApiResult<List<ApiSoftwarePackageDto>>?> GetSoftwarePackagesAsync() =>
        GetAsync<List<ApiSoftwarePackageDto>>("/api/software/packages");

    /// <summary>指定产品已发布的发行物（按发布顺序由新到旧返回）。</summary>
    public Task<ApiResult<List<ApiSoftwareReleaseDto>>?> GetSoftwareReleasesAsync(string packageCode) =>
        GetAsync<List<ApiSoftwareReleaseDto>>(
            $"/api/software/packages/{System.Uri.EscapeDataString(packageCode)}/releases");
}
