using System.Collections.Generic;
using Avalonia;
using System;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Bond.Services;
using Bond.Services.Api;

namespace Bond.Views;

/// <summary>
/// 登录 / 注册 / 找回密码窗口。
/// 注册为 1-2-3 分步向导（与找回密码共用 stepper 视觉）；
/// 接口：POST /api/auth/login、POST /api/auth/register、POST /api/auth/email/code、
///       POST /api/auth/password/reset。
/// </summary>
public partial class LoginWindow : Window
{
    private readonly BondApiClient _api;
    private bool _isRegister;
    private bool _captchaNeeded;
    private bool _regCaptchaNeeded;
    private int _resetStep = 1;
    private int _regStep = 1;

    private string? _captchaId;
    private string? _regCaptchaId;

    /// <summary>忘记密码流程当前使用的邮箱（发码与重置必须一致）。</summary>
    private string _resetEmail = "";

    /// <summary>注册第一步暂存的账号信息（后续步骤沿用，避免回退丢失）。</summary>
    private string _regUsername = "";
    private string _regEmail = "";
    private string _regEmailCode = "";

    public event EventHandler? Authenticated;

    public LoginWindow()
    {
        InitializeComponent();
        _api = new BondApiClient();
    }

    public LoginWindow(BondApiClient api)
    {
        InitializeComponent();
        _api = api;
        ServerText.Text = "服务器：" + api.BaseUrl;

        UsernameBox.KeyDown += OnFieldKeyDown;
        PasswordBox.KeyDown += OnFieldKeyDown;
        CaptchaBox.KeyDown += OnFieldKeyDown;

        RegEmailBox.KeyDown += OnRegStep1KeyDown;
        RegEmailCodeBox.KeyDown += OnRegStep1KeyDown;
        RegUsernameBox.KeyDown += OnRegStep1KeyDown;
        RegPasswordBox.KeyDown += OnRegStep2KeyDown;
        RegPassword2Box.KeyDown += OnRegStep2KeyDown;
        RegNicknameBox.KeyDown += OnRegStep3KeyDown;
        RegCaptchaBox.KeyDown += OnRegStep3KeyDown;

        ResetEmailBox.KeyDown += OnResetFieldKeyDown;
        ResetCodeBox.KeyDown += OnResetFieldKeyDown;
        ResetPasswordBox.KeyDown += OnResetFieldKeyDown;
        ResetPassword2Box.KeyDown += OnResetFieldKeyDown;

        KeyDown += OnWindowKeyDown;

        Opened += (_, _) =>
        {
            UsernameBox.Focus();
            _ = EnsureCaptchaAsync();
        };
    }

    // ============================ 通用 ============================

    private void OnRootPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
        {
            try { BeginMoveDrag(e); } catch { /* 忽略拖拽失败 */ }
        }
    }

    private void OnWindowKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Escape) return;

        if (_isRegister && _regStep > 1)
        {
            _regStep--;
            ApplyRegStep();
            PlayPageTransition();
            FocusRegStep();
            e.Handled = true;
            return;
        }

        if (ResetPanel.IsVisible && _resetStep > 1)
        {
            _resetStep--;
            ApplyResetStep();
            PlayPageTransition();
            e.Handled = true;
        }
    }

    private void OnSelectLogin(object? sender, RoutedEventArgs e) => SetMode(false);

    private void OnSelectRegister(object? sender, RoutedEventArgs e) => SetMode(true);

    private void SetMode(bool register)
    {
        if (_isRegister == register && !ResetPanel.IsVisible) return;

        _isRegister = register;
        LoginTab.Classes.Set("active", !register);
        RegisterTab.Classes.Set("active", register);

        ResetPanel.IsVisible = false;
        _resetStep = 1;

        FormBody.IsVisible = !register;
        RegisterPanel.IsVisible = register;

        if (register)
        {
            _regStep = 1;
            ApplyRegStep();
            RegErrorBar.IsVisible = false;
            RegErrorBar2.IsVisible = false;
            RegErrorBar3.IsVisible = false;
            Dispatcher.UIThread.Post(() => RegUsernameBox.Focus());
        }
        else
        {
            ErrorBar.IsVisible = false;
            Dispatcher.UIThread.Post(() => UsernameBox.Focus());
        }

        PlayPageTransition();
    }

    /// <summary>模式切换：卡片淡入 + 12px 位移（220ms，cubic-out）。</summary>
    private void PlayPageTransition()
    {
        PageHost.Opacity = 0;
        PageHost.RenderTransform = new Avalonia.Media.TranslateTransform(12, 0);
        Dispatcher.UIThread.Post(async () =>
        {
            await Task.Delay(16);
            PageHost.Opacity = 1;
            PageHost.RenderTransform = new Avalonia.Media.TranslateTransform(0, 0);
        });
    }

    private static bool IsEmail(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 254) return false;
        try
        {
            var address = new System.Net.Mail.MailAddress(value.Trim());
            return string.Equals(address.Address, value.Trim(), StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>把底层真实失败原因带出来，而不是永远一句“无法连接服务器”。</summary>
    private string DescribeConnError()
        => string.IsNullOrWhiteSpace(_api.LastError)
            ? "无法连接服务器，请检查网络或服务器地址"
            : "无法连接服务器：" + _api.LastError;

    private static string Describe(string message)
    {
        var text = string.IsNullOrWhiteSpace(message) ? "操作失败，请重试" : message;
        return text.Contains("验证码", StringComparison.Ordinal)
            ? text + "（连续失败会触发验证码，请按提示填写）"
            : text;
    }

    /// <summary>后端 message 优先展示（不发信失败、冷却、冷却秒数都以服务端为准）。</summary>
    private static string MapEmailError(ApiResult res, bool registering)
    {
        var message = (res.Message ?? "").Trim();
        if (message.Length > 0) return message;

        return res.Code switch
        {
            400 => registering ? "邮箱或验证码不正确" : "验证码无效或已过期",
            401 => registering ? "验证码不正确" : "当前密码不正确",
            404 => registering ? "接口不存在" : "该邮箱尚未注册",
            409 => registering ? "用户名或邮箱已被占用" : "该邮箱已被占用，请更换",
            429 => "操作过于频繁，请稍后再试",
            503 => "邮件服务暂时不可用，请稍后再试",
            _ => $"请求失败（HTTP {res.Code}）"
        };
    }

    private void BeginCountdown(Button button, int seconds)
    {
        var remaining = seconds > 0 ? seconds : 60;
        button.IsEnabled = false;
        button.Content = $"重新发送({remaining}s)";

        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        timer.Tick += (_, _) =>
        {
            remaining--;
            if (remaining <= 0)
            {
                timer.Stop();
                button.Content = "发送验证码";
                button.IsEnabled = true;
            }
            else
            {
                button.Content = $"重新发送({remaining}s)";
            }
        };
        timer.Start();
    }

    // ============================ 图形验证码 ============================

    /// <summary>服务端在同一 IP 连续失败后会要求验证码；这里先问一次是否需要。</summary>
    private async Task EnsureCaptchaAsync()
    {
        var check = await _api.GetAsync<Dictionary<string, bool>>("/api/auth/captcha/check");
        if (check?.IsOk == true && check.Data is { } map
            && map.TryGetValue("needsCaptcha", out var needs) && needs)
        {
            ShowLoginCaptchaHost(true);
            await LoadCaptchaAsync();
        }
    }

    private void ShowLoginCaptchaHost(bool show)
    {
        _captchaNeeded = show;
        // 高度随内容自适应（IsVisible），不再写死高度 + ClipToBounds，避免「看不清？换一张」被裁。
        CaptchaGroup.IsVisible = show;
    }

    private async Task LoadCaptchaAsync()
    {
        CaptchaSkeleton.IsVisible = true;
        CaptchaRetry.IsVisible = false;
        CaptchaImage.IsVisible = false;

        var res = await _api.GetAsync<Dictionary<string, string>>("/api/auth/captcha");
        if (res?.IsOk != true || res.Data is null)
        {
            CaptchaSkeleton.IsVisible = false;
            CaptchaRetry.IsVisible = true;
            return;
        }

        if (!res.Data.TryGetValue("id", out var id)) id = "";
        if (!res.Data.TryGetValue("image", out var dataUri) || string.IsNullOrEmpty(dataUri))
        {
            CaptchaSkeleton.IsVisible = false;
            CaptchaRetry.IsVisible = true;
            return;
        }

        if (!TryDecodeImage(dataUri, out var bitmap))
        {
            CaptchaSkeleton.IsVisible = false;
            CaptchaRetry.IsVisible = true;
            return;
        }

        _captchaId = id;
        CaptchaImage.Source = bitmap;
        CaptchaImage.IsVisible = true;
        CaptchaSkeleton.IsVisible = false;
        CaptchaRetry.IsVisible = false;
        ShowLoginCaptchaHost(true);
        CaptchaBox.Text = "";
    }

    private void OnCaptchaBorderClick(object? sender, PointerPressedEventArgs e) => _ = LoadCaptchaAsync();

    private void OnRefreshCaptchaClick(object? sender, RoutedEventArgs e) => _ = LoadCaptchaAsync();

    /// <summary>注册第三步的图形验证码：进入该步时按 needsCaptcha 决定是否展示。</summary>
    private async Task EnsureRegCaptchaAsync()
    {
        var check = await _api.GetAsync<Dictionary<string, bool>>("/api/auth/captcha/check");
        if (check?.IsOk == true && check.Data is { } map
            && map.TryGetValue("needsCaptcha", out var needs) && needs)
        {
            ShowRegCaptchaHost(true);
            await LoadRegCaptchaAsync();
        }
    }

    private void ShowRegCaptchaHost(bool show)
    {
        _regCaptchaNeeded = show;
        // 同登录页：内容自适应高度，避免裁切。
        RegCaptchaGroup.IsVisible = show;
    }

    private async Task LoadRegCaptchaAsync()
    {
        RegCaptchaSkeleton.IsVisible = true;
        RegCaptchaRetry.IsVisible = false;
        RegCaptchaImage.IsVisible = false;

        var res = await _api.GetAsync<Dictionary<string, string>>("/api/auth/captcha");
        if (res?.IsOk != true || res.Data is null)
        {
            RegCaptchaSkeleton.IsVisible = false;
            RegCaptchaRetry.IsVisible = true;
            return;
        }

        if (!res.Data.TryGetValue("id", out var id)) id = "";
        if (!res.Data.TryGetValue("image", out var dataUri) || string.IsNullOrEmpty(dataUri)
            || !TryDecodeImage(dataUri, out var bitmap))
        {
            RegCaptchaSkeleton.IsVisible = false;
            RegCaptchaRetry.IsVisible = true;
            return;
        }

        _regCaptchaId = id;
        RegCaptchaImage.Source = bitmap;
        RegCaptchaImage.IsVisible = true;
        RegCaptchaSkeleton.IsVisible = false;
        RegCaptchaRetry.IsVisible = false;
        ShowRegCaptchaHost(true);
    }

    private void OnRegCaptchaBorderClick(object? sender, PointerPressedEventArgs e) => _ = LoadRegCaptchaAsync();

    private void OnRefreshRegCaptchaClick(object? sender, RoutedEventArgs e) => _ = LoadRegCaptchaAsync();

    private static bool TryDecodeImage(string dataUri, out Avalonia.Media.Imaging.Bitmap? bitmap)
    {
        bitmap = null;
        var comma = dataUri.IndexOf(',');
        if (comma < 0) return false;
        try
        {
            var bytes = Convert.FromBase64String(dataUri[(comma + 1)..]);
            using var ms = new System.IO.MemoryStream(bytes);
            bitmap = new Avalonia.Media.Imaging.Bitmap(ms);
            return true;
        }
        catch
        {
            return false;
        }
    }

    // ============================ 登录 ============================

    private void OnFieldKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) OnSubmit(sender, new RoutedEventArgs());
    }

    private async void OnSubmit(object? sender, RoutedEventArgs e)
    {
        if (SubmitBusy.IsVisible) return;

        ErrorBar.IsVisible = false;

        var username = UsernameBox.Text?.Trim() ?? "";
        var password = PasswordBox.Text ?? "";
        if (string.IsNullOrWhiteSpace(username) || string.IsNullOrWhiteSpace(password))
        {
            ShowError("请输入用户名和密码");
            return;
        }

        SetLoginBusy(true);
        try
        {
            var payload = new Dictionary<string, object>
            {
                ["username"] = username,
                ["password"] = password
            };

            if (CaptchaGroup.IsVisible && !string.IsNullOrWhiteSpace(CaptchaBox.Text))
            {
                payload["captchaId"] = _captchaId ?? "";
                payload["captchaCode"] = CaptchaBox.Text.Trim();
            }

            var login = await _api.PostAsync<UserDto>("/api/auth/login", payload);
            if (login is null)
            {
                ShowError(DescribeConnError());
                return;
            }

            if (!login.IsOk || login.Data is null)
            {
                ShowError(Describe(login.Message));

                if (login.Message.Contains("验证码", StringComparison.Ordinal))
                {
                    ShowLoginCaptchaHost(true);
                    await LoadCaptchaAsync();
                }
                return;
            }

            if (string.IsNullOrEmpty(login.Data.AccessToken))
            {
                ShowError("服务器未返回令牌，请稍后重试");
                return;
            }

            _api.SetTokens(login.Data.AccessToken!, login.Data.RefreshToken);
            Authenticated?.Invoke(this, EventArgs.Empty);
            Close();
        }
        catch (Exception ex)
        {
            ShowError("请求失败：" + ex.Message);
        }
        finally
        {
            SetLoginBusy(false);
        }
    }

    private void SetLoginBusy(bool busy)
    {
        SubmitBusy.IsVisible = busy;
        SubmitText.IsVisible = !busy;
        SubmitButton.IsEnabled = !busy;
        UsernameBox.IsEnabled = !busy;
        PasswordBox.IsEnabled = !busy;
        CaptchaBox.IsEnabled = !busy;
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorBar.IsVisible = true;
    }

    // ============================ 注册（1-2-3） ============================

    private void ApplyRegStep()
    {
        RegStep1.IsVisible = _regStep == 1;
        RegStep2.IsVisible = _regStep == 2;
        RegStep3.IsVisible = _regStep == 3;

        RegSubtitle.Text = _regStep switch
        {
            1 => "第一步：填写账号与邮箱",
            2 => "第二步：设置登录密码",
            _ => "第三步：确认并完成注册"
        };

        RegDot1.Classes.Set("active", _regStep == 1);
        RegDot1.Classes.Set("done", _regStep > 1);
        RegDot2.Classes.Set("active", _regStep == 2);
        RegDot2.Classes.Set("done", _regStep > 2);
        RegDot3.Classes.Set("active", _regStep == 3);
        RegLine1.Background = _regStep > 1
            ? (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E57153")
            : (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E3E0D8");
        RegLine2.Background = _regStep > 2
            ? (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E57153")
            : (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E3E0D8");

        if (_regStep == 3)
        {
            RegSummaryUser.Text = "用户名：" + (_regUsername.Length > 0 ? _regUsername : "—");
            RegSummaryEmail.Text = "邮箱：" + (_regEmail.Length > 0 ? _regEmail : "—");
            Dispatcher.UIThread.Post(() =>
            {
                RegNicknameBox.Focus();
                _ = EnsureRegCaptchaAsync();
            });
        }
    }

    private void FocusRegStep()
    {
        switch (_regStep)
        {
            case 1: Dispatcher.UIThread.Post(() => RegUsernameBox.Focus()); break;
            case 2: Dispatcher.UIThread.Post(() => RegPasswordBox.Focus()); break;
            default: Dispatcher.UIThread.Post(() => RegNicknameBox.Focus()); break;
        }
    }

    private void OnRegBack1(object? sender, RoutedEventArgs e)
    {
        _regStep = 1;
        ApplyRegStep();
        PlayPageTransition();
        FocusRegStep();
    }

    private void OnRegBack2(object? sender, RoutedEventArgs e)
    {
        _regStep = 2;
        ApplyRegStep();
        PlayPageTransition();
        FocusRegStep();
    }

    private void OnRegNext1(object? sender, RoutedEventArgs e)
    {
        RegErrorBar.IsVisible = false;
        var username = (RegUsernameBox.Text ?? "").Trim();
        var email = (RegEmailBox.Text ?? "").Trim();
        var code = (RegEmailCodeBox.Text ?? "").Trim();

        if (username.Length < 3 || username.Length > 32)
        {
            ShowRegError(1, "用户名需 3-32 位");
            return;
        }
        if (!IsEmail(email))
        {
            ShowRegError(1, "请输入正确的邮箱地址");
            return;
        }
        if (code.Length != 6 || !code.All(char.IsDigit))
        {
            ShowRegError(1, "请输入 6 位数字验证码，或点击「发送验证码」");
            return;
        }

        _regUsername = username;
        _regEmail = email;
        _regEmailCode = code;
        _regStep = 2;
        ApplyRegStep();
        PlayPageTransition();
        FocusRegStep();
    }

    private void OnRegNext2(object? sender, RoutedEventArgs e)
    {
        RegErrorBar2.IsVisible = false;
        var password = RegPasswordBox.Text ?? "";
        var password2 = RegPassword2Box.Text ?? "";

        if (password.Length < 6)
        {
            ShowRegError(2, "密码至少 6 位");
            return;
        }
        if (!string.Equals(password, password2, StringComparison.Ordinal))
        {
            ShowRegError(2, "两次输入的密码不一致");
            return;
        }

        _regStep = 3;
        ApplyRegStep();
        PlayPageTransition();
    }

    private void OnRegStep1KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) OnRegNext1(sender, new RoutedEventArgs());
    }

    private void OnRegStep2KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) OnRegNext2(sender, new RoutedEventArgs());
    }

    private void OnRegStep3KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) OnSubmitRegister(sender, new RoutedEventArgs());
    }

    private async void OnSendRegisterCode(object? sender, RoutedEventArgs e)
    {
        RegErrorBar.IsVisible = false;
        var email = (RegEmailBox.Text ?? "").Trim();
        if (!IsEmail(email))
        {
            ShowRegError(1, "请输入正确的邮箱地址");
            return;
        }

        RegSendCodeButton.IsEnabled = false;
        RegSendCodeButton.Content = "发送中…";

        var res = await _api.PostAsync<EmailCodeResult>(
            "/api/auth/email/code", new { email, purpose = "register" });

        if (res is null)
        {
            RegSendCodeButton.Content = "发送验证码";
            RegSendCodeButton.IsEnabled = true;
            ShowRegError(1, DescribeConnError());
            return;
        }

        if (!res.IsOk)
        {
            RegSendCodeButton.Content = "发送验证码";
            RegSendCodeButton.IsEnabled = true;
            ShowRegError(1, MapEmailError(res, registering: true));
            return;
        }

        RegCodeHint.Text = "验证码已发送，如未收到请检查垃圾邮件（10 分钟内有效）。";
        BeginCountdown(RegSendCodeButton,
            res.Data?.CooldownSeconds is > 0 ? res.Data!.CooldownSeconds : 60);
    }

    private async void OnSubmitRegister(object? sender, RoutedEventArgs e)
    {
        RegErrorBar3.IsVisible = false;

        // 步骤 1/2 的内容可能被修改过，这里再校验一次
        var username = (RegUsernameBox.Text ?? "").Trim();
        var email = (RegEmailBox.Text ?? "").Trim();
        var code = (RegEmailCodeBox.Text ?? "").Trim();
        var password = RegPasswordBox.Text ?? "";
        var password2 = RegPassword2Box.Text ?? "";
        var nickname = (RegNicknameBox.Text ?? "").Trim();

        if (username.Length < 3 || username.Length > 32 || !IsEmail(email)
            || code.Length != 6 || !code.All(char.IsDigit))
        {
            _regStep = 1;
            ApplyRegStep();
            PlayPageTransition();
            ShowRegError(1, "账号信息有误，请检查后重试");
            return;
        }
        if (password.Length < 6 || !string.Equals(password, password2, StringComparison.Ordinal))
        {
            _regStep = 2;
            ApplyRegStep();
            PlayPageTransition();
            ShowRegError(2, "密码有误，请检查后重试");
            return;
        }
        if (RegCaptchaGroup.IsVisible && string.IsNullOrWhiteSpace(RegCaptchaBox.Text))
        {
            ShowRegError(3, "请输入图形验证码");
            return;
        }

        SetRegBusy(true);
        try
        {
            var payload = new Dictionary<string, object>
            {
                ["username"] = username,
                ["password"] = password,
                ["email"] = email,
                ["code"] = code,
                ["nickname"] = string.IsNullOrWhiteSpace(nickname) ? username : nickname
            };
            if (RegCaptchaGroup.IsVisible)
            {
                payload["captchaId"] = _regCaptchaId ?? "";
                payload["captchaCode"] = RegCaptchaBox.Text?.Trim() ?? "";
            }

            var reg = await _api.PostAsync<UserDto>("/api/auth/register", payload);
            if (reg is null)
            {
                ShowRegError(3, DescribeConnError());
                return;
            }
            if (!reg.IsOk)
            {
                ShowRegError(3, MapEmailError(reg, registering: true));
                return;
            }

            if (!string.IsNullOrEmpty(reg.Data?.AccessToken))
            {
                _api.SetTokens(reg.Data!.AccessToken!, reg.Data.RefreshToken);
                Authenticated?.Invoke(this, EventArgs.Empty);
                Close();
                return;
            }

            // 注册成功但未带 token：用同一账号密码登录一次
            var login = await _api.PostAsync<UserDto>("/api/auth/login",
                new { username, password });
            if (login?.IsOk == true && !string.IsNullOrEmpty(login.Data?.AccessToken))
            {
                _api.SetTokens(login.Data!.AccessToken!, login.Data.RefreshToken);
                Authenticated?.Invoke(this, EventArgs.Empty);
                Close();
                return;
            }

            _isRegister = false;
            RegisterPanel.IsVisible = false;
            FormBody.IsVisible = true;
            LoginTab.Classes.Set("active", true);
            RegisterTab.Classes.Set("active", false);
            UsernameBox.Text = username;
            PasswordBox.Text = "";
            ErrorText.Text = "注册成功，请用新密码登录。";
            ErrorBar.IsVisible = true;
            UsernameBox.Focus();
        }
        catch (Exception ex)
        {
            ShowRegError(3, "请求失败：" + ex.Message);
        }
        finally
        {
            SetRegBusy(false);
        }
    }

    private void SetRegBusy(bool busy)
    {
        RegSubmitBusy.IsVisible = busy;
        RegSubmitText.IsVisible = !busy;
        RegSubmitButton.IsEnabled = !busy;
        RegSendCodeButton.IsEnabled = !busy && RegSendCodeButton.Content is "发送验证码";
    }

    private void ShowRegError(int step, string message)
    {
        switch (step)
        {
            case 1:
                RegErrorText.Text = message;
                RegErrorBar.IsVisible = true;
                break;
            case 2:
                RegErrorText2.Text = message;
                RegErrorBar2.IsVisible = true;
                break;
            default:
                RegErrorText3.Text = message;
                RegErrorBar3.IsVisible = true;
                break;
        }
    }

    // ============================ 找回密码 ============================

    private void OnForgotClick(object? sender, RoutedEventArgs e)
    {
        _resetStep = 1;
        FormBody.IsVisible = false;
        RegisterPanel.IsVisible = false;
        ResetPanel.IsVisible = true;
        ResetErrorBar.IsVisible = false;
        ResetEmailHint.Text = "";
        ResetCodeBox.Text = "";
        ResetPasswordBox.Text = "";
        ResetPassword2Box.Text = "";
        _resetEmail = "";
        ResetEmailBox.Text = EmailBox_previous();
        ApplyResetStep();
        PlayPageTransition();
        ResetEmailBox.Focus();
    }

    /// <summary>登录页没有邮箱输入框；这里保留上次记录的邮箱用于找回密码。</summary>
    private string EmailBox_previous() => _lastLoginEmail;

    private string _lastLoginEmail = "";

    private void ApplyResetStep()
    {
        ResetStep1.IsVisible = _resetStep == 1;
        ResetStep2.IsVisible = _resetStep == 2;
        ResetStep3.IsVisible = _resetStep == 3;
        ResetSubtitle.Text = _resetStep switch
        {
            1 => "第一步：填写注册邮箱",
            2 => "第二步：输入邮箱验证码",
            _ => "第三步：设置新密码"
        };

        StepDot1.Classes.Set("active", _resetStep == 1);
        StepDot1.Classes.Set("done", _resetStep > 1);
        StepDot2.Classes.Set("active", _resetStep == 2);
        StepDot2.Classes.Set("done", _resetStep > 2);
        StepDot3.Classes.Set("active", _resetStep == 3);
        StepLine1.Background = _resetStep > 1
            ? (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E57153")
            : (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E3E0D8");
        StepLine2.Background = _resetStep > 2
            ? (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E57153")
            : (Avalonia.Media.IBrush?)Avalonia.Media.Brush.Parse("#E3E0D8");
    }

    private void OnResetBackToStep1(object? sender, RoutedEventArgs e)
    {
        _resetStep = 1;
        ApplyResetStep();
        PlayPageTransition();
        ResetEmailBox.Focus();
    }

    private void OnResetBackToStep2(object? sender, RoutedEventArgs e)
    {
        _resetStep = 2;
        ApplyResetStep();
        PlayPageTransition();
        ResetCodeBox.Focus();
    }

    private void OnResetNextStep(object? sender, RoutedEventArgs e)
    {
        var code = (ResetCodeBox.Text ?? "").Trim();
        if (code.Length != 6 || !code.All(char.IsDigit))
        {
            ShowResetError("请输入 6 位数字验证码");
            return;
        }
        ResetErrorBar.IsVisible = false;
        _resetStep = 3;
        ApplyResetStep();
        PlayPageTransition();
        ResetPasswordBox.Focus();
    }

    private void OnBackToLogin(object? sender, RoutedEventArgs e)
    {
        ResetPanel.IsVisible = false;
        FormBody.IsVisible = true;
        RegisterPanel.IsVisible = false;
        ErrorBar.IsVisible = false;
        PlayPageTransition();
        UsernameBox.Focus();
    }

    private void OnResetFieldKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        switch (_resetStep)
        {
            case 1: _ = SendResetCodeAsync(); break;
            case 2: OnResetNextStep(sender, new RoutedEventArgs()); break;
            default: OnSubmitReset(sender, new RoutedEventArgs()); break;
        }
    }

    private async void OnSendResetCode(object? sender, RoutedEventArgs e) => await SendResetCodeAsync();

    private async Task SendResetCodeAsync()
    {
        var email = (ResetEmailBox.Text ?? "").Trim();
        if (!IsEmail(email))
        {
            ShowResetError("请输入正确的邮箱地址");
            return;
        }

        ResetSendButton.IsEnabled = false;
        ResetSendButton.Content = "发送中…";

        var res = await _api.PostAsync<EmailCodeResult>(
            "/api/auth/email/code", new { email, purpose = "reset_password" });

        if (res is null)
        {
            ResetSendButton.Content = "发送验证码";
            ResetSendButton.IsEnabled = true;
            ShowResetError(DescribeConnError());
            return;
        }

        if (!res.IsOk)
        {
            ResetSendButton.Content = "发送验证码";
            ResetSendButton.IsEnabled = true;
            ShowResetError(MapEmailError(res, registering: false));
            return;
        }

        _resetEmail = email;
        ResetEmailHint.Text = "如果该邮箱已注册，验证码已发送（10 分钟内有效）。";
        ResetEmailEcho.Text = "重置账号：" + email;
        ResetErrorBar.IsVisible = false;
        BeginCountdown(ResetSendButton, res.Data?.CooldownSeconds is > 0 ? res.Data!.CooldownSeconds : 60);
        _resetStep = 2;
        ApplyResetStep();
        PlayPageTransition();
        ResetCodeBox.Focus();
    }

    private async void OnSubmitReset(object? sender, RoutedEventArgs e)
    {
        var code = (ResetCodeBox.Text ?? "").Trim();
        var password = ResetPasswordBox.Text ?? "";
        var password2 = ResetPassword2Box.Text ?? "";

        if (_resetEmail.Length == 0)
        {
            ShowResetError("请先输入邮箱并获取验证码");
            return;
        }
        if (code.Length != 6 || !code.All(char.IsDigit))
        {
            ShowResetError("请输入 6 位数字验证码");
            return;
        }
        if (password.Length < 6)
        {
            ShowResetError("新密码至少 6 位");
            return;
        }
        if (!string.Equals(password, password2, StringComparison.Ordinal))
        {
            ShowResetError("两次输入的密码不一致");
            return;
        }

        ResetSubmitButton.IsEnabled = false;
        ResetSubmitButton.Content = "重置中…";
        try
        {
            var res = await _api.PostAsync<Dictionary<string, object>>(
                "/api/auth/password/reset",
                new { email = _resetEmail, code, newPassword = password });

            if (res is null)
            {
                ShowResetError(DescribeConnError());
                return;
            }
            if (!res.IsOk)
            {
                ShowResetError(MapEmailError(res, registering: false));
                return;
            }

            ResetPanel.IsVisible = false;
            FormBody.IsVisible = true;
            _lastLoginEmail = _resetEmail;
            PasswordBox.Text = "";
            ResetPasswordBox.Text = "";
            ResetPassword2Box.Text = "";
            ErrorText.Text = "密码已重置，请用新密码登录。";
            ErrorBar.IsVisible = true;
            PasswordBox.Focus();
        }
        finally
        {
            ResetSubmitButton.IsEnabled = true;
            ResetSubmitButton.Content = "重置密码";
        }
    }

    private void ShowResetError(string message)
    {
        ResetErrorText.Text = message;
        ResetErrorBar.IsVisible = true;
    }

    private void OnCloseClick(object? sender, RoutedEventArgs e)
    {
        // 首次登录直接关窗 = 退出应用
        if (Application.Current?.ApplicationLifetime
            is Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.Shutdown();
        }
        else
        {
            Close();
        }
    }
}
