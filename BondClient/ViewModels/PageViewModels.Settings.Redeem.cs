using System;
using System.Globalization;
using System.Text;
using System.Threading.Tasks;
using Bond.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Bond.ViewModels;

/// <summary>
/// 设置页「会员与兑换」分区：当前档位/到期时间 + 兑换码兑换。
/// <para>兑换码由管理后台批量生成（admin-web → <c>POST /api/admin/redeem-codes</c>，
/// 形如 <c>BOND-XXXXXXXXXXXXXXXX</c>），是一次性凭据；本站只做兑换
/// （<c>POST /api/redeem</c>），档位、到期时间、订阅、配额全部由服务端原子变更。</para>
/// <para>客户端只负责输入整形（去空白 + 大写），是否有效一律看后端：
/// 失败时展示 message 原文（如「兑换码无效、已使用或已过期」），不自造原因、不假装成功。</para>
/// </summary>
public partial class SettingsPageViewModel
{
    /// <summary>管理端生成的码是 21 位（BOND- + 16）；这里留足手工粘贴余量，超长基本是粘进了多余内容。</summary>
    private const int RedeemCodeMaxLength = 64;

    // ── 当前档位（GET /api/entitlements/me）──────────────────────
    [ObservableProperty]
    private bool _isPlanLoading;

    /// <summary>档位展示名（后端 planName，缺省回退 planCode）；读取失败时保留上一次的值。</summary>
    [ObservableProperty]
    private string _planNameText = "—";

    /// <summary>档位到期时间（本机时区）；免费/永久档显示「长期有效」。</summary>
    [ObservableProperty]
    private string _planExpiryText = "—";

    /// <summary>个人空间额度：已用 / 上限（来自同一份权益接口，与文件页配额同源）。</summary>
    [ObservableProperty]
    private string _planStorageText = "—";

    /// <summary>档位状态说明（宽限期内 / 读取失败原因）；一切正常时为空串。</summary>
    [ObservableProperty]
    private string _planStatusText = "";

    /// <summary>读数失败（与「正在读取」区分：只有失败才用红色提示并允许重试）。</summary>
    [ObservableProperty]
    private bool _planLoadFailed;

    public bool HasPlanStatus => !string.IsNullOrEmpty(PlanStatusText);

    /// <summary>档位读取失败（红色）：只有真的失败才是错误。</summary>
    public bool HasPlanError => PlanLoadFailed && HasPlanStatus;

    /// <summary>档位提示（橙色）：宽限期等需要提醒但不属于错误的情况。</summary>
    public bool HasPlanNotice => !PlanLoadFailed && HasPlanStatus;

    public string RefreshPlanButtonText => IsPlanLoading ? "读取中…" : "刷新";

    partial void OnPlanStatusTextChanged(string value) => RefreshPlanNoticeBindings();

    partial void OnPlanLoadFailedChanged(bool value) => RefreshPlanNoticeBindings();

    partial void OnIsPlanLoadingChanged(bool value) => OnPropertyChanged(nameof(RefreshPlanButtonText));

    private void RefreshPlanNoticeBindings()
    {
        OnPropertyChanged(nameof(HasPlanStatus));
        OnPropertyChanged(nameof(HasPlanError));
        OnPropertyChanged(nameof(HasPlanNotice));
    }

    /// <summary>
    /// 进入设置页 / 兑换成功后读取当前档位（<c>GET /api/entitlements/me</c>）。
    /// 失败时不清空已有数值，只把原因写进 <see cref="PlanStatusText"/>，避免界面闪成「—」。
    /// </summary>
    [RelayCommand]
    public async Task LoadRedeemSectionAsync()
    {
        if (IsPlanLoading) return;

        IsPlanLoading = true;
        try
        {
            var result = await _api
                .GetAsync<Bond.Services.Api.EntitlementsDto>("/api/entitlements/me")
                .ConfigureAwait(true);

            if (result?.IsOk != true || result.Data is null)
            {
                PlanLoadFailed = true;
                PlanStatusText = "读取当前档位失败：" + (_api.LastError ?? "服务端未返回权益信息");
                return;
            }

            var dto = result.Data;
            PlanLoadFailed = false;
            PlanNameText = FirstNotEmpty(dto.PlanName, dto.PlanCode, "—");
            PlanExpiryText = FormatIsoLocal(dto.ExpiresAt, "长期有效");
            PlanStorageText = DescribePlanStorage(dto);
            PlanStatusText = string.Equals(dto.Status, "grace", StringComparison.OrdinalIgnoreCase)
                ? "档位已到期，仍在宽限期内：额度暂时保留，续期后继续生效。"
                : "";
        }
        finally
        {
            IsPlanLoading = false;
        }
    }

    // ── 兑换码（POST /api/redeem）───────────────────────────────
    [ObservableProperty]
    private string _redeemCode = "";

    [ObservableProperty]
    private bool _isRedeeming;

    [ObservableProperty]
    private string _redeemHint = "";

    [ObservableProperty]
    private bool _redeemHintIsError;

    public bool HasRedeemHint => !string.IsNullOrEmpty(RedeemHint);

    /// <summary>成功提示走绿色；失败走红色。没有提示时两块都不显示。</summary>
    public bool RedeemHintIsSuccess => HasRedeemHint && !RedeemHintIsError;

    public string RedeemButtonText => IsRedeeming ? "兑换中…" : "立即兑换";

    public bool CanRedeem => !IsRedeeming && !string.IsNullOrWhiteSpace(RedeemCode);

    partial void OnRedeemCodeChanged(string value)
    {
        OnPropertyChanged(nameof(CanRedeem));
        RedeemCommand.NotifyCanExecuteChanged();
    }

    partial void OnIsRedeemingChanged(bool value)
    {
        OnPropertyChanged(nameof(RedeemButtonText));
        OnPropertyChanged(nameof(CanRedeem));
        RedeemCommand.NotifyCanExecuteChanged();
    }

    partial void OnRedeemHintChanged(string value) => RefreshRedeemHintBindings();

    partial void OnRedeemHintIsErrorChanged(bool value) => RefreshRedeemHintBindings();

    private void RefreshRedeemHintBindings()
    {
        OnPropertyChanged(nameof(HasRedeemHint));
        OnPropertyChanged(nameof(RedeemHintIsSuccess));
    }

    /// <summary>
    /// 兑换：<c>POST /api/redeem</c>。成功后清空输入、展示「档位 + 到期时间」、
    /// 重新读一次权益并让配额缓存重新取数；失败展示后端 message 原文。
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanRedeem))]
    private async Task RedeemAsync()
    {
        if (IsRedeeming) return;

        var code = NormalizeRedeemCode(RedeemCode);
        if (code.Length == 0)
        {
            SetRedeemHint("请先输入兑换码", isError: true);
            return;
        }

        if (code.Length > RedeemCodeMaxLength)
        {
            SetRedeemHint("兑换码长度异常，请检查是否粘贴了多余内容", isError: true);
            return;
        }

        IsRedeeming = true;
        SetRedeemHint(string.Empty, isError: false);
        try
        {
            var result = await _api.RedeemAsync(code).ConfigureAwait(true);

            if (result is null)
            {
                // 整段请求失败：区分「连不上服务器」与「服务端明确报错」，别一律说成没网。
                SetRedeemHint(
                    _api.LastFailure == Bond.Services.Api.ApiFailureKind.Network
                        ? "无法连接服务器，请检查网络后重试"
                        : _api.LastError ?? "兑换失败，请稍后重试",
                    isError: true);
                return;
            }

            if (!result.IsOk)
            {
                // 400 = 无效 / 已使用 / 已过期：后端 message 比客户端猜的准，原文展示。
                SetRedeemHint(DescribeAuthError(result, "兑换失败"), isError: true);
                return;
            }

            RedeemCode = string.Empty;
            var message = DescribeRedeemSuccess(result.Data);
            SetRedeemHint(message, isError: false);
            _notify.Notify(message, NoticeKind.Success);

            await LoadRedeemSectionAsync().ConfigureAwait(true);
            RefreshQuotaCaches();
        }
        catch (Exception ex)
        {
            SetRedeemHint("兑换失败：" + ex.Message, isError: true);
        }
        finally
        {
            IsRedeeming = false;
        }
    }

    private void SetRedeemHint(string text, bool isError)
    {
        RedeemHintIsError = isError;
        RedeemHint = text;
        RefreshRedeemHintBindings();
    }

    /// <summary>剪贴板读不出来（平台不支持 / 被其它程序占用）时给可读提示，不静默。</summary>
    public void ReportRedeemPasteFailed(string message)
        => SetRedeemHint("读取剪贴板失败：" + message + "，请手动粘贴", isError: true);

    private void RefreshQuotaCaches()
    {
        // 兑换改变了档位额度：让文件页 / 悬浮球重新取一次配额。纯附加动作，失败不影响兑换结果。
        try
        {
            if (App.Services?.GetService(typeof(Bond.Services.HttpBondStore)) is Bond.Services.HttpBondStore store)
            {
                _ = store.ReloadListAsync();
            }
        }
        catch
        {
            // 配额刷新失败不改变「兑换已成功」的事实
        }
    }

    /// <summary>兑换成功文案：档位 + 到期时间（服务端 ISO 时间转本机时区）。</summary>
    private static string DescribeRedeemSuccess(Bond.Services.Api.RedeemResultDto? dto)
    {
        var plan = string.IsNullOrWhiteSpace(dto?.PlanCode)
            ? string.Empty
            : dto!.PlanCode!.Trim().ToUpperInvariant();
        var expiry = FormatIsoLocal(dto?.ExpiresAt, string.Empty);

        if (plan.Length == 0)
        {
            return expiry.Length == 0 ? "兑换成功，档位已更新" : $"兑换成功，有效期至 {expiry}";
        }

        return expiry.Length == 0
            ? $"兑换成功：已开通 {plan}"
            : $"兑换成功：已开通 {plan}，有效期至 {expiry}";
    }

    /// <summary>已用 / 上限；上限为 0 表示不限（后端 limits.storageBytes=0 的约定）。</summary>
    private static string DescribePlanStorage(Bond.Services.Api.EntitlementsDto dto)
    {
        var limit = dto.Limits?.StorageBytes ?? 0;
        var used = dto.Usage?.StorageBytes ?? 0;

        if (limit <= 0) return used > 0 ? $"{FormatSize(used)} / 不限" : "不限";
        return $"{FormatSize(used)} / {FormatSize(limit)}";
    }

    /// <summary>
    /// 输入整形：去掉所有空白（粘贴时常带换行/全角空格）并转大写。
    /// 管理端生成的码只含大写字母、数字与中间连字符，大写化不改变语义，却能让手输小写也能兑换。
    /// </summary>
    internal static string NormalizeRedeemCode(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return string.Empty;

        var builder = new StringBuilder(raw.Length);
        foreach (var ch in raw)
        {
            if (char.IsWhiteSpace(ch)) continue;
            builder.Append(char.ToUpperInvariant(ch));
        }

        return builder.ToString();
    }

    /// <summary>
    /// 服务端时间统一是 ISO-8601（带时区偏移）。解析不了就回退 <paramref name="fallback"/>，
    /// 绝不把半截时间或异常文本塞进界面。
    /// </summary>
    private static string FormatIsoLocal(string? iso, string fallback)
    {
        if (string.IsNullOrWhiteSpace(iso)) return fallback;

        return DateTimeOffset.TryParse(iso.Trim(), CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed)
            ? TimeText.Local(parsed)
            : fallback;
    }

    private static string FirstNotEmpty(string? first, string? second, string fallback)
        => !string.IsNullOrWhiteSpace(first) ? first!.Trim()
            : !string.IsNullOrWhiteSpace(second) ? second!.Trim()
            : fallback;
}
