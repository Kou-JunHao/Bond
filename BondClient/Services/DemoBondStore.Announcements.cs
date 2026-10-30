using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Bond.Models;

namespace Bond.Services;

/// <summary>
/// task-7：系统公告的离线演示实现（DemoBondStore 当前未注册，仅用于把接口契约补齐；
/// 切回演示数据时公告面板也能直接跑通三态）。
/// </summary>
public sealed partial class DemoBondStore : IAnnouncementService
{
    private readonly List<AnnouncementItem> _announcements =
    [
        new()
        {
            Id = "demo-announce-1",
            Title = "Bond 1.2 已发布：公告面板上线",
            Content = "桌面端新增公告铃铛：右上角铃铛可查看系统公告与维护通知。\n"
                    + "未读公告会在铃铛上显示红色数字徽标，点击公告即标记为已读。",
            Level = AnnouncementLevel.Info,
            PublishedAtMillis = DateTimeOffset.Now.AddHours(-3).ToUnixTimeMilliseconds()
        },
        new()
        {
            Id = "demo-announce-2",
            Title = "今晚 23:00 维护通知",
            Content = "为升级存储节点，今晚 23:00 - 23:30 将暂停上传与下载；\n"
                    + "已排队的传输任务会在维护结束后自动续传。",
            Level = AnnouncementLevel.Warn,
            PublishedAtMillis = DateTimeOffset.Now.AddMinutes(-25).ToUnixTimeMilliseconds()
        },
        new()
        {
            Id = "demo-announce-3",
            Title = "安全提醒：请勿在第三方站点输入提取码",
            Content = "近期发现仿冒 Bond 分享页面的钓鱼站点，请在官方域名（bond-api.sg.skkk.uno）内输入提取码。",
            Level = AnnouncementLevel.Critical,
            PublishedAtMillis = DateTimeOffset.Now.AddMinutes(-5).ToUnixTimeMilliseconds()
        }
    ];

    public Task<(IReadOnlyList<AnnouncementItem> Items, string? Error)> GetAsync(CancellationToken ct = default)
        => Task.FromResult<(IReadOnlyList<AnnouncementItem>, string?)>((_announcements.ToArray(), null));

    public Task<(int Unread, string? Error)> GetUnreadCountAsync(CancellationToken ct = default)
        => Task.FromResult<(int, string?)>((_announcements.Count(a => a.IsUnread), null));

    public Task<string?> MarkReadAsync(string id, CancellationToken ct = default)
    {
        var item = _announcements.FirstOrDefault(a => string.Equals(a.Id, id, StringComparison.Ordinal));
        if (item is null) return Task.FromResult<string?>("公告不存在");
        item.IsRead = true;
        return Task.FromResult<string?>(null);
    }
}
