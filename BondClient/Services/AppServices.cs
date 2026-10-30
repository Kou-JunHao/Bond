using System;
using Bond.Services;
using Bond.Services.Updates;
using Bond.ViewModels;
using Microsoft.Extensions.DependencyInjection;

namespace Bond.Services;

/// <summary>
/// 组合根：整个应用的唯一"知道一切"的地方。
/// <para>
/// 与旧版的区别：不再有 <c>static class ServiceProvider</c> 的可变静态字段，
/// 也没有"取不到就 new 一个"的兜底（那会导致两个 TransferService 抢同一端口）。
/// 所有对象都是构造注入，生命周期由容器管理。
/// </para>
/// </summary>
public static class AppServices
{
    public static ServiceProvider Build()
    {
        var services = new ServiceCollection();

        // ── 真实后端客户端（服务器地址可在设置页修改并重连）──
        services.AddSingleton(_ => new Bond.Services.Api.BondApiClient(LocalPrefs.Current.ServerUrl));

        // ── 数据 / 领域服务（Http 实现；离线时可换回 DemoBondStore）──
        services.AddSingleton<HttpBondStore>();
        services.AddSingleton<IFileService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<ITransferService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IDeviceService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IWorkspaceService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IChatService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IInboxService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<ISessionService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<ITrashService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IUploadService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IDocService>(sp => sp.GetRequiredService<HttpBondStore>());
        services.AddSingleton<IWebDavService>(sp => sp.GetRequiredService<HttpBondStore>());

        // task-7：系统公告（铃铛未读 + 公告面板 + 已读）
        services.AddSingleton<IAnnouncementService>(sp => sp.GetRequiredService<HttpBondStore>());

        services.AddSingleton<ISoftwareService, SoftwareService>();

        // ── task-141：更新内核 / 更新策略（频率、自动下载、忽略版本）──
        services.AddSingleton<IUpdateService, UpdateService>();
        services.AddSingleton<IUpdatePolicy, UpdatePolicy>();

        // ── task-160 A：局域网设备发现（UDP 组播；身份每次广播时实时读取）──
        services.AddSingleton(sp => new Bond.Services.Lan.LanDiscoveryService(() =>
        {
            var api = sp.GetRequiredService<Bond.Services.Api.BondApiClient>();
            if (!api.IsLoggedIn) return null;

            var store = sp.GetRequiredService<HttpBondStore>();
            var prefs = LocalPrefs.Current;
            var userId = Bond.Core.JwtUtil.GetUserId(api.AccessToken) ?? 0;

            // 同一台机器跑第二个实例做验收时，用 BOND_LAN_DEVICE_SUFFIX 区分设备身份（例如 "-b"），
            // 否则两边 deviceId / 设备名都相同，既会互相把对方当自己过滤掉，界面上也分不清谁是谁。
            var suffix = Environment.GetEnvironmentVariable("BOND_LAN_DEVICE_SUFFIX");
            var hasSuffix = !string.IsNullOrWhiteSpace(suffix);
            var deviceId = hasSuffix ? prefs.DeviceId + suffix : prefs.DeviceId;
            var deviceName = hasSuffix ? store.DeviceName + suffix : store.DeviceName;

            return new Bond.Services.Lan.LanIdentity(
                deviceId,
                deviceName,
                userId,
                store.AccountName,
                prefs.LanTransferPort);
        }));

        // ── 基础设施 ──
        services.AddSingleton<INotificationCenter, NotificationCenter>();
        services.AddSingleton<IWindowCoordinator, WindowCoordinator>();

        // ── 缩略图 / 预览 ──
        services.AddSingleton<PreviewService>();
        services.AddSingleton<IPreviewService>(sp => sp.GetRequiredService<PreviewService>());
        services.AddSingleton<IThumbnailService>(sp => new ThumbnailService(sp.GetRequiredService<PreviewService>()));
        services.AddSingleton<PreviewViewModel>();

        // ── 页面 ViewModel（常驻，切换页面时保留状态）──
        services.AddSingleton<FilesPageViewModel>();
        services.AddSingleton<WorkspacePageViewModel>();
        services.AddSingleton<TransferPageViewModel>();
        services.AddSingleton<DevicesPageViewModel>();
        services.AddSingleton<ChatPageViewModel>();
        services.AddSingleton<DocsPageViewModel>();
        services.AddSingleton<SettingsPageViewModel>();

        // ── 窗口 ViewModel ──
        services.AddSingleton<MainWindowViewModel>();
        services.AddSingleton<FloatingBallViewModel>();

        return services.BuildServiceProvider();
    }
}
