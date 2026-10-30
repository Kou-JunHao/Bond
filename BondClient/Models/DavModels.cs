using System;
using System.Windows.Input;
using CommunityToolkit.Mvvm.ComponentModel;

namespace Bond.Models;

/// <summary>WebDAV 专用密码（列表行）。明文只在创建成功时出现一次，模型里永远不存。</summary>
public sealed class WebDavPasswordItem : ObservableObject
{
    public string Id { get; init; } = string.Empty;

    public string Name { get; init; } = string.Empty;

    public DateTimeOffset? CreatedAt { get; init; }

    public DateTimeOffset? LastUsedAt { get; init; }

    private bool _isRevoked;

    /// <summary>已吊销的行保留在列表里置灰展示（后端列表同时返回历史吊销记录）。</summary>
    public bool IsRevoked
    {
        get => _isRevoked;
        set => SetProperty(ref _isRevoked, value);
    }

    /// <summary>吊销命令（由页面 ViewModel 注入）。</summary>
    public ICommand? RevokeCommand { get; set; }

    public string CreatedAtText => CreatedAt is null ? "-" : CreatedAt.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm");

    /// <summary>“最后使用”文案：从未使用与已吊销区分开。</summary>
    public string LastUsedText => LastUsedAt is null ? "从未使用" : LastUsedAt.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm");
}

/// <summary>挂载地址行：个人空间一行 + 每个工作区一行。</summary>
public sealed class WebDavAddressItem
{
    public string Title { get; init; } = string.Empty;

    public string Url { get; init; } = string.Empty;

    public bool IsPersonal { get; init; }

    /// <summary>复制本行地址（由页面 ViewModel 注入）。</summary>
    public ICommand? CopyCommand { get; set; }

    /// <summary>该地址对应的角色说明（个人空间 / 工作区名）。</summary>
    public string KindText => IsPersonal ? "个人空间" : "工作区";
}
