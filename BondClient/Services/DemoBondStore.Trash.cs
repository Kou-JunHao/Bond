using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

/// <summary>离线演示用的回收站实现：永远空、操作恒成功（保证 demo 模式可编译可交互）。</summary>
public sealed partial class DemoBondStore : ITrashService
{
    public Task<(IReadOnlyList<TrashItem> Items, string? Error)> GetTrashAsync(CancellationToken ct = default)
        => Task.FromResult<(IReadOnlyList<TrashItem>, string?)>(([], null));

    public Task<TrashOpResult> RestoreAsync(IReadOnlyList<string> ids, CancellationToken ct = default)
        => Task.FromResult(new TrashOpResult { Succeeded = ids });

    public Task<TrashOpResult> PurgeAsync(IReadOnlyList<string> ids, CancellationToken ct = default)
        => Task.FromResult(new TrashOpResult { Succeeded = ids });

    public Task<(int Purged, string? Error)> EmptyAsync(CancellationToken ct = default)
        => Task.FromResult<(int, string?)>((0, null));
}
