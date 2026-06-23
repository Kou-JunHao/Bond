using System.Text.Json.Serialization;
using BondClient.Models;

namespace BondClient.Services;

[JsonSerializable(typeof(DeviceInfo))]
[JsonSerializable(typeof(TransferRequest))]
[JsonSerializable(typeof(PasswordManager.ConfigData))]
[JsonSerializable(typeof(FileEntry))]
[JsonSerializable(typeof(User))]
[JsonSerializable(typeof(DeviceModel))]
[JsonSerializable(typeof(TransferTask))]
[JsonSerializable(typeof(TransferChunkInfo))]
[JsonSerializable(typeof(Workspace))]
[JsonSerializable(typeof(WorkspaceFile))]
[JsonSerializable(typeof(ApiResult<User>))]
[JsonSerializable(typeof(ApiResult<List<User>>))]
[JsonSerializable(typeof(ApiResult<DeviceModel>))]
[JsonSerializable(typeof(ApiResult<List<DeviceModel>>))]
[JsonSerializable(typeof(ApiResult<TransferTask>))]
[JsonSerializable(typeof(ApiResult<List<TransferTask>>))]
[JsonSerializable(typeof(ApiResult<List<TransferChunkInfo>>))]
[JsonSerializable(typeof(ApiResult<TransferChunkInfo>))]
[JsonSerializable(typeof(ApiResult<Workspace>))]
[JsonSerializable(typeof(ApiResult<List<Workspace>>))]
[JsonSerializable(typeof(ApiResult<WorkspaceFile>))]
[JsonSerializable(typeof(ApiResult<List<WorkspaceFile>>))]
[JsonSerializable(typeof(ApiResult<string>))]
[JsonSerializable(typeof(ApiResult<Dictionary<string, string>>))]
[JsonSerializable(typeof(ApiResult))]
[JsonSerializable(typeof(AuthToken))]
[JsonSerializable(typeof(ResumeState))]
[JsonSerializable(typeof(object))]
internal partial class BondJsonContext : JsonSerializerContext;
