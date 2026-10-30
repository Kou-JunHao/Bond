using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;

namespace Bond.Core;

/// <summary>
/// 把「重新拉取到的整批数据」同步进已绑定的 <see cref="ObservableCollection{T}"/>，
/// 但**绝不触发 Reset**（即不调用 Clear）。
///
/// 为什么必须这样：Avalonia 12 的 ItemsControl/ItemsPresenter 在收到 Reset 通知后
/// 会残留旧容器，界面表现为「刷新一次多一条、再刷新再多一条」——设备页、
/// 回收站、会话列表都踩过。逐项 Replace/RemoveAt/Add 只发 Replace/Add/Remove，
/// 容器会被正确复用或回收。
///
/// 用法：<c>CollectionSync.Apply(Rows, fresh);</c>
/// 若列表项的相等语义比「引用相等」更宽（例如同 id 同内容就不必重建），
/// 传入 <paramref name="same"/> 让内容未变的行保持原实例，避免闪烁。
/// </summary>
public static class CollectionSync
{
    /// <summary>就地同步；集合只在 UI 线程上改（调用方保证）。</summary>
    public static void Apply<T>(
        ObservableCollection<T> target,
        IReadOnlyList<T> fresh,
        Func<T, T, bool>? same = null)
    {
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(fresh);

        var keep = Math.Min(target.Count, fresh.Count);

        for (var i = 0; i < keep; i++)
        {
            if (same is not null && same(target[i], fresh[i]))
            {
                continue;
            }

            // 逐项替换 → Replace 通知；不改变集合长度，界面容器被复用。
            target[i] = fresh[i];
        }

        // 尾部多出来的先删（Remove 通知），再追加新的（Add 通知）。
        while (target.Count > fresh.Count)
        {
            target.RemoveAt(target.Count - 1);
        }

        for (var i = keep; i < fresh.Count; i++)
        {
            target.Add(fresh[i]);
        }
    }
}
