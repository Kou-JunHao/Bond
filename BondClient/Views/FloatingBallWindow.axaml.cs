using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Bond.Core.Morph;
using Bond.Services;
using Bond.ViewModels;

namespace Bond.Views;

/// <summary>
/// 悬浮球窗口。
///
/// 形变动画是这一版必须完整保留的资产，因此本文件的动画部分是对
/// BondClient/Views/FloatingBallWindow.axaml.cs 的逐行等价移植：
/// 弹簧参数、圆角混合、窗口中心插值、内容淡入时序、图标 morph、pill 吸附、
/// 拖拽阈值全部沿用同一套数值；纯数学部分抽到 SpringCurve / MorphMath，
/// 以便脱离 UI 做数值验证。
///
/// 与旧版的差别只在业务：面板从 14 个页面缩减为 1 个紧凑快捷面板，
/// 重型操作通过 IWindowCoordinator 跳转主窗口。
/// </summary>
public partial class FloatingBallWindow : Window, IBallHost
{
    // 唯一的一个面板（内部可滚动，见到更多内容）
    private const double PanelWidthDip = 344.0;
    private const double PanelHeightDip = 384.0;

    private double _dpi = 1.0;
    private double _ballSize, _ballPad;
    private int _ballWinW, _ballWinH;
    private double _edgeSnap, _dragThresh;
    private double _panelW, _panelH;

    private double _icoEndTx, _icoEndTy, _icoEndSc;
    private const double IcoStartTx = 0.0, IcoStartTy = 0.0, IcoStartSc = 1.0;

    private bool _ptrDown, _dragging;
    private PixelPoint _ptrStart, _winStart;
    private long _lastClickTick;

    private enum SnapDir { None, Left, Right, Top, Bottom }

    private SnapDir _snapDir = SnapDir.None;
    private bool _expanded;
    private bool _morphInProgress;

    // morph 状态
    private uint _mGen;
    private bool _mDir;
    private double _mT;
    private long _mTick;
    private double _fW, _fH, _fCR, _tW, _tH, _tCR;
    private int _cx, _cy;
    private int _scx, _scy, _tcx, _tcy;
    private double _iFx, _iFy, _iFs, _iTx, _iTy, _iTs;
    private bool _contentFadeIn;
    private bool _morphFromPill;
    private SnapDir _expandFromSnap;

    private ScaleTransform? _mScale;
    private TranslateTransform? _mTrans;
    private double _lastW = double.NaN, _lastH, _lastCR;
    private double _lastItx = double.NaN, _lastIty, _lastIts;

    // resize（面板尺寸自适应）
    private uint _resizeGen;
    private double _resizeT;
    private long _resizeTick;
    private double _resizeFromW, _resizeFromH, _resizeToW, _resizeToH;
    private int _resizeCx, _resizeCy;

    // hover
    private uint _hGen;
    private double _hT;
    private long _hTick;
    private double _hFrom, _hTo;

    // pill
    private bool _isPill;
    private bool _pillSquash;
    private uint _pillGen;
    private double _pillT;
    private long _pillTick;
    private double _pillFromW, _pillToW, _pillFromH, _pillToH, _pillFromCR, _pillToCR;

    private uint _slideGen;

    // toast
    private DispatcherTimer? _toastTimer;

    private readonly FloatingBallViewModel _vm;
    private readonly INotificationCenter _notify;
    private readonly IWindowCoordinator _shell;

    private static readonly string PosFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bond", "ball-position.txt");

    public FloatingBallWindow()
    {
        InitializeComponent();
        _vm = null!;
        _notify = new NotificationCenter();
        _shell = new WindowCoordinator();
    }

    public FloatingBallWindow(FloatingBallViewModel vm)
    {
        InitializeComponent();
        _vm = vm;
        DataContext = vm;

        _notify = App.Services.GetService(typeof(INotificationCenter)) as INotificationCenter
                  ?? new NotificationCenter();
        _shell = App.Services.GetService(typeof(IWindowCoordinator)) as IWindowCoordinator
                 ?? new WindowCoordinator();

        if (MorphIcon.RenderTransform is TransformGroup tg)
        {
            _mScale = tg.Children[0] as ScaleTransform;
            _mTrans = tg.Children[1] as TranslateTransform;
        }

        _notify.Notified += OnNotified;
        _vm.SendFilesRequested += OnSendFilesRequested;
        _vm.UploadFilesRequested += OnUploadFilesRequested;
    }

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);

        _dpi = RenderScaling;
        _ballSize = MorphSpec.BallSize;
        _ballPad = MorphSpec.BallPadding;
        _panelW = PanelWidthDip;
        _panelH = PanelHeightDip;
        _ballWinW = DipToPx(_ballSize + _ballPad * 2);
        _ballWinH = _ballWinW;
        _edgeSnap = MorphSpec.EdgeSnapThreshold;
        _dragThresh = MorphSpec.DragThreshold;
        _icoEndSc = MorphMath.IconTargetScale;

        var (tx, ty) = MorphMath.IconTargetOffset();
        _icoEndTx = tx;
        _icoEndTy = ty;

        // 悬浮球属于全局快捷层：展开后的面板不能被主窗口遮住
        Topmost = true;

        DragDrop.SetAllowDrop(this, true);
        DragDrop.AddDragEnterHandler(this, OnDragEnter);
        DragDrop.AddDragOverHandler(this, OnDragOver);
        DragDrop.AddDragLeaveHandler(this, OnDragLeave);
        DragDrop.AddDropHandler(this, OnDrop);

        // 注意顺序：先决定落点，再判断是否需要吸附。
        // 若先 clamp 再 auto-snap，初次启动（Position 默认 0,0）会被误判为"出屏"而压成 pill。
        var hadSavedPosition = File.Exists(PosFile);
        RestorePosition();

        if (!hadSavedPosition)
        {
            var screen = Screens.Primary;
            if (screen?.WorkingArea is { } wa)
            {
                Position = new PixelPoint(
                    wa.X + wa.Width - _ballWinW - DipToPx(40),
                    wa.Y + wa.Height / 2 - _ballWinH / 2);
            }
        }

        ClampToScreen();

        if (hadSavedPosition)
        {
            // 仅当位置来自历史记录（可能是换了显示器/分辨率）时才自动吸附回屏幕内
            AutoSnapIfOffScreen();
        }

        RefreshVisualState();
    }

    // ---------------------------------------------------------------
    //  IBallHost：供主窗口 / 托盘调用
    // ---------------------------------------------------------------

    public void RequestExpand()
    {
        if (_expanded || _morphInProgress) return;
        if (!IsVisible) Show();
        DoExpand();
    }

    public void RequestCollapse()
    {
        if (!_expanded || _morphInProgress) return;
        DoCollapse();
    }

    // ---------------------------------------------------------------
    //  展开 / 收起 / 反向
    // ---------------------------------------------------------------

    private void DoExpand()
    {
        // 每次展开刷新一次：设备 / 空间额度 / 待接收任务
        _ = _vm.RefreshPanelAsync();

        _expandFromSnap = _snapDir;
        _morphFromPill = _snapDir != SnapDir.None;

        _hGen++;
        Sc = 1;

        _cx = Position.X + DipToPx(_ballSize + _ballPad * 2) / 2;
        _cy = Position.Y + DipToPx(_ballSize + _ballPad * 2) / 2;
        _scx = _cx;
        _scy = _cy;
        _tcx = _cx;
        _tcy = _cy;

        // 面板必须整体落在工作区内。
        // 旧逻辑只在"从 pill 展开"时收敛，导致球体本身贴近屏幕边缘时展开会把面板怼出屏幕。
        // 这里改为无条件收敛：无论球在什么位置、是否吸附，都把面板中心夹到可行区间内。
        var wa = GetScreenBounds();
        if (wa != default)
        {
            var halfPanelW = DipToPx(_panelW) / 2;
            var halfPanelH = DipToPx(_panelH) / 2;
            var margin = DipToPx(24);

            var minCx = wa.X + halfPanelW + margin;
            var maxCx = wa.X + wa.Width - halfPanelW - margin;
            var minCy = wa.Y + halfPanelH + margin;
            var maxCy = wa.Y + wa.Height - halfPanelH - margin;

            // 面板比可用区域还大时（极小屏幕 / 高缩放），退化为居中，避免 Math.Clamp 抛异常
            _tcx = minCx <= maxCx ? Math.Clamp(_tcx, minCx, maxCx) : wa.X + wa.Width / 2;
            _tcy = minCy <= maxCy ? Math.Clamp(_tcy, minCy, maxCy) : wa.Y + wa.Height / 2;
        }

        _fW = BallBorder.Width;
        _fH = BallBorder.Height;
        _fCR = BallBorder.CornerRadius.TopLeft;
        _tCR = MorphSpec.PanelCornerRadius;
        _tW = _panelW;
        _tH = _panelH;

        _iFx = _mTrans?.X ?? 0;
        _iFy = _mTrans?.Y ?? 0;
        _iFs = _mScale?.ScaleX ?? 1;
        _iTx = _icoEndTx;
        _iTy = _icoEndTy;
        _iTs = _icoEndSc;

        if (_mScale is not null && _mScale.ScaleX < 0.9)
        {
            _iFx = 0;
            _iFy = 0;
            _iFs = _mScale.ScaleX;
        }

        DeviceNameDisplay.Text = _vm.DeviceName;
        DeviceNameDisplay.Opacity = 0;

        PanelContent.IsVisible = true;
        PanelContent.Opacity = 0;
        MorphIcon.IsVisible = true;
        _contentFadeIn = true;

        Badge.IsVisible = false;
        PendingGlow.IsVisible = false;
        _vm.SetPanelOpen(true);

        StartMorph(expand: true);
    }

    private void DoCollapse()
    {
        _morphFromPill = _expandFromSnap != SnapDir.None;
        _hGen++;
        Sc = 1;

        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;

        if (_expandFromSnap != SnapDir.None)
        {
            var isHoriz = _expandFromSnap is SnapDir.Top or SnapDir.Bottom;
            _fW = BallBorder.Width;
            _tW = isHoriz ? MorphSpec.PillHeight : MorphSpec.PillWidth;
            _fH = BallBorder.Height;
            _tH = isHoriz ? MorphSpec.PillWidth : MorphSpec.PillHeight;
            _fCR = BallBorder.CornerRadius.TopLeft;
            _tCR = MorphSpec.PillCornerRadius;

            var waScreen = GetScreenBounds();
            if (waScreen != default)
            {
                var pad = DipToPx(_ballPad);
                var pillWinW = DipToPx(_tW + _ballPad * 2);
                var pillWinH = DipToPx(_tH + _ballPad * 2);
                var pillWPx = DipToPx(_tW);
                var pillHPx = DipToPx(_tH);

                switch (_expandFromSnap)
                {
                    case SnapDir.Left:
                        _tcx = waScreen.X - pad + pillWinW / 2;
                        _tcy = _cy;
                        break;
                    case SnapDir.Right:
                        _tcx = waScreen.X + waScreen.Width - pad - pillWPx + pillWinW / 2;
                        _tcy = _cy;
                        break;
                    case SnapDir.Top:
                        _tcx = _cx;
                        _tcy = waScreen.Y - pad + pillWinH / 2;
                        break;
                    case SnapDir.Bottom:
                        _tcx = _cx;
                        _tcy = waScreen.Y + waScreen.Height - pad - pillHPx + pillWinH / 2;
                        break;
                }
            }
            else
            {
                _tcx = _cx;
                _tcy = _cy;
            }
        }
        else
        {
            _fW = BallBorder.Width;
            _tW = _ballSize;
            _fH = BallBorder.Height;
            _tH = _ballSize;
            _fCR = BallBorder.CornerRadius.TopLeft;
            _tCR = MorphSpec.BallCornerRadius;
            _tcx = _cx;
            _tcy = _cy;
        }

        _scx = _cx;
        _scy = _cy;

        _iFx = _mTrans?.X ?? 0;
        _iFy = _mTrans?.Y ?? 0;
        _iFs = _mScale?.ScaleX ?? 1;
        _iTx = IcoStartTx;
        _iTy = IcoStartTy;
        _iTs = IcoStartSc;

        _contentFadeIn = false;
        _vm.SetPanelOpen(false);

        StartMorph(expand: false);
    }

    private void StartMorph(bool expand)
    {
        _mGen++;
        _mDir = expand;
        _mT = 0;
        _mTick = 0;
        _lastW = double.NaN;
        _lastItx = double.NaN;
        _morphInProgress = true;
        RequestAnimationFrame(MorphTick);
    }

    private void ReverseMorph()
    {
        if (!_morphInProgress) return;

        _mGen++;
        _mDir = !_mDir;
        _mT = 0;
        _mTick = 0;
        _lastW = double.NaN;
        _lastItx = double.NaN;
        RecalcMorphTargets();
        RequestAnimationFrame(MorphTick);
    }

    private void RecalcMorphTargets()
    {
        (_fW, _tW) = (_tW, _fW);
        (_fH, _tH) = (_tH, _fH);
        (_fCR, _tCR) = (_tCR, _fCR);

        if (_mDir)
        {
            var tw = BallBorder.Width + _ballPad * 2;
            var th = BallBorder.Height + _ballPad * 2;
            _cx = Position.X + DipToPx(tw) / 2;
            _cy = Position.Y + DipToPx(th) / 2;
            _scx = _cx;
            _scy = _cy;
            _tcx = _cx;
            _tcy = _cy;
        }

        _iFx = _mTrans?.X ?? 0;
        _iFy = _mTrans?.Y ?? 0;
        _iFs = _mScale?.ScaleX ?? 1;

        if (_mDir)
        {
            _iTx = _icoEndTx;
            _iTy = _icoEndTy;
            _iTs = _icoEndSc;
            _contentFadeIn = true;
        }
        else
        {
            _iTx = IcoStartTx;
            _iTy = IcoStartTy;
            _iTs = IcoStartSc;
            _contentFadeIn = false;
        }

        _mT = 0;
        _mTick = 0;
    }

    /// <summary>
    /// 形变主循环 —— 全窗口最关键的一段。
    /// 数值与旧版完全一致：尺寸走 Border 弹簧，位置走独立 Position 弹簧（1.2 倍加速），
    /// 圆角用 MorphMath 的「圆 <-> 线性」混合，内容在 40% 之后淡入。
    /// </summary>
    private void MorphTick(TimeSpan now)
    {
        var gen = _mGen;
        if (!Adv(now, ref _mTick, ref _mT, MorphSpec.MorphDuration))
        {
            if (_mGen == gen) RequestAnimationFrame(MorphTick);
            return;
        }

        var raw = _mT > 1 ? 1 : _mT;

        if (raw >= 1)
        {
            BallBorder.Width = _tW;
            BallBorder.Height = _tH;
            BallBorder.CornerRadius = new CornerRadius(_tCR);
            if (BallBorder.Clip is RectangleGeometry clipFinal)
            {
                clipFinal.Rect = new Rect(0, 0, _tW, _tH);
                clipFinal.RadiusX = _tCR;
                clipFinal.RadiusY = _tCR;
            }

            if (!_dragging)
            {
                var ww = DipToPx(_tW + _ballPad * 2);
                var wh = DipToPx(_tH + _ballPad * 2);
                Position = new PixelPoint(_tcx - ww / 2, _tcy - wh / 2);
            }

            if (_mGen == gen) EndMorph();
            return;
        }

        double w, h, cr;

        if (_morphFromPill)
        {
            var wt = SpringCurve.Clamp01(SpringCurve.Normalized(
                raw, MorphSpec.PillWidthSpring.Zeta, MorphSpec.PillWidthSpring.Omega,
                MorphSpec.PillWidthSpring.EndValue));
            var ht = SpringCurve.Clamp01(SpringCurve.Normalized(
                raw, MorphSpec.PillHeightSpring.Zeta, MorphSpec.PillHeightSpring.Omega,
                MorphSpec.PillHeightSpring.EndValue));

            w = SpringCurve.Lerp(_fW, _tW, wt);
            h = SpringCurve.Lerp(_fH, _tH, ht);

            var posRaw = SpringCurve.Clamp01(raw * MorphSpec.PillPositionLeadFactor);
            var posT = SpringCurve.Clamp01(SpringCurve.Normalized(
                posRaw, MorphSpec.Position.Zeta, MorphSpec.Position.Omega, MorphSpec.Position.EndValue));

            var curCx = (int)SpringCurve.Lerp(_scx, _tcx, posT);
            var curCy = (int)SpringCurve.Lerp(_scy, _tcy, posT);

            cr = MorphMath.CornerRadius(w, h, _fCR, _tCR, wt, CornerBlendMode.FromPill, _ballSize);

            if (!_dragging)
            {
                var ww = DipToPx(w + _ballPad * 2);
                var wh = DipToPx(h + _ballPad * 2);
                Position = new PixelPoint(curCx - ww / 2, curCy - wh / 2);
            }
        }
        else
        {
            var bt = SpringCurve.Clamp01(SpringCurve.Normalized(
                raw, MorphSpec.Border.Zeta, MorphSpec.Border.Omega, MorphSpec.Border.EndValue));

            w = SpringCurve.Lerp(_fW, _tW, bt);
            h = SpringCurve.Lerp(_fH, _tH, bt);

            var posT = SpringCurve.EaseOutCubic(SpringCurve.Clamp01(raw * MorphSpec.PositionLeadFactor));
            var curCx = (int)SpringCurve.Lerp(_scx, _tcx, posT);
            var curCy = (int)SpringCurve.Lerp(_scy, _tcy, posT);

            cr = MorphMath.CornerRadius(w, h, _fCR, _tCR, bt, CornerBlendMode.FromBall, _ballSize);

            if (!_dragging)
            {
                var ww = DipToPx(w + _ballPad * 2);
                var wh = DipToPx(h + _ballPad * 2);
                Position = new PixelPoint(curCx - ww / 2, curCy - wh / 2);
            }
        }

        var sizeChanged = Math.Abs(w - _lastW) > 0.5 || Math.Abs(h - _lastH) > 0.5;
        var crChanged = Math.Abs(cr - _lastCR) > 0.3;

        if (sizeChanged)
        {
            BallBorder.Width = w;
            BallBorder.Height = h;
            _lastW = w;
            _lastH = h;
            if (BallBorder.Clip is RectangleGeometry clip) clip.Rect = new Rect(0, 0, w, h);
        }

        if (crChanged)
        {
            BallBorder.CornerRadius = new CornerRadius(cr);
            if (BallBorder.Clip is RectangleGeometry clip)
            {
                clip.RadiusX = cr;
                clip.RadiusY = cr;
            }
            _lastCR = cr;
        }

        var it = SpringCurve.Clamp01(SpringCurve.Normalized(
            raw, MorphSpec.Icon.Zeta, MorphSpec.Icon.Omega, MorphSpec.Icon.EndValue));

        SetMorphIcon(
            SpringCurve.Lerp(_iFx, _iTx, it),
            SpringCurve.Lerp(_iFy, _iTy, it),
            SpringCurve.Lerp(_iFs, _iTs, it));

        var contentOpacity = MorphMath.ContentOpacity(_contentFadeIn, raw, out var slideX);
        PanelContent.Opacity = contentOpacity;
        if (PanelContent.RenderTransform is TranslateTransform ct) ct.X = slideX;
        else PanelContent.RenderTransform = new TranslateTransform(slideX, 0);

        DeviceNameDisplay.Opacity = _contentFadeIn
            ? 0
            : raw < MorphSpec.ContentFadeOutEnd
                ? 1 - SpringCurve.EaseOutCubic(raw / MorphSpec.ContentFadeOutEnd)
                : 0;

        if (_mGen == gen) RequestAnimationFrame(MorphTick);
    }

    private void EndMorph()
    {
        _morphInProgress = false;

        if (PanelContent.RenderTransform is TranslateTransform ct) ct.X = 0;

        if (!_mDir)
        {
            _expanded = false;
            PanelContent.IsVisible = false;
            PanelContent.Opacity = 0;
            DeviceNameDisplay.Opacity = 1;
            MorphIcon.IsVisible = true;
            _vm.SetPanelOpen(false);

            if (_expandFromSnap != SnapDir.None)
            {
                _snapDir = _expandFromSnap;
                _isPill = true;
            }
            else
            {
                ClampToScreen();
                AutoSnapIfOffScreen();
            }

            _expandFromSnap = SnapDir.None;
            RefreshVisualState();
        }
        else
        {
            _expanded = true;
            PanelContent.Opacity = 1;
            DeviceNameDisplay.Opacity = 1;

            // 展开后面板顶部是账号头像，球体 logo 已完成「飞入」动画，收起（否则会压在头像/账号名上）
            MorphIcon.IsVisible = false;
        }
    }

    // ---------------------------------------------------------------
    //  球体状态的视觉：徽标 / 状态环 / 待处理光晕
    // ---------------------------------------------------------------

    private void RefreshVisualState()
    {
        if (_expanded || _morphInProgress) return;

        var hasPending = _vm.HasPending;
        Badge.IsVisible = hasPending;
        PendingGlow.IsVisible = hasPending;
        PendingGlow.Opacity = hasPending ? 0.65 : 0;
    }

    // ---------------------------------------------------------------
    //  面板尺寸自适应（保留旧版 ResizeTick 手感）
    // ---------------------------------------------------------------

    public void AnimatePanelSize(double width, double height)
    {
        if (!_expanded || _morphInProgress) return;
        if (Math.Abs(BallBorder.Width - width) < 0.5 && Math.Abs(BallBorder.Height - height) < 0.5)
            return;

        var outerW = BallBorder.Width + _ballPad * 2;
        var outerH = BallBorder.Height + _ballPad * 2;
        _resizeCx = Position.X + DipToPx(outerW) / 2;
        _resizeCy = Position.Y + DipToPx(outerH) / 2;
        _resizeFromW = BallBorder.Width;
        _resizeFromH = BallBorder.Height;
        _resizeToW = width;
        _resizeToH = height;
        _resizeT = 0;
        _resizeTick = 0;
        _resizeGen++;
        RequestAnimationFrame(ResizeTick);
    }

    private void ResizeTick(TimeSpan now)
    {
        var gen = _resizeGen;
        if (!Adv(now, ref _resizeTick, ref _resizeT, MorphSpec.ResizeDuration))
        {
            if (_resizeGen == gen) RequestAnimationFrame(ResizeTick);
            return;
        }

        var raw = Math.Min(_resizeT, 1);
        var t = SpringCurve.EaseOutCubic(raw);
        var w = SpringCurve.Lerp(_resizeFromW, _resizeToW, t);
        var h = SpringCurve.Lerp(_resizeFromH, _resizeToH, t);

        BallBorder.Width = w;
        BallBorder.Height = h;
        BallBorder.CornerRadius = new CornerRadius(MorphSpec.PanelCornerRadius);
        if (BallBorder.Clip is RectangleGeometry clip)
        {
            clip.Rect = new Rect(0, 0, w, h);
            clip.RadiusX = MorphSpec.PanelCornerRadius;
            clip.RadiusY = MorphSpec.PanelCornerRadius;
        }

        var ww = DipToPx(w + _ballPad * 2);
        var wh = DipToPx(h + _ballPad * 2);
        Position = new PixelPoint(_resizeCx - ww / 2, _resizeCy - wh / 2);

        if (raw < 1 && _resizeGen == gen) RequestAnimationFrame(ResizeTick);
    }

    // ---------------------------------------------------------------
    //  图标 morph
    // ---------------------------------------------------------------

    private void SetMorphIcon(double tx, double ty, double scale)
    {
        if (Math.Abs(tx - _lastItx) < 0.1
            && Math.Abs(ty - _lastIty) < 0.1
            && Math.Abs(scale - _lastIts) < 0.005) return;

        _lastItx = tx;
        _lastIty = ty;
        _lastIts = scale;

        if (_mTrans is not null)
        {
            _mTrans.X = tx;
            _mTrans.Y = ty;
        }

        if (_mScale is not null)
        {
            _mScale.ScaleX = scale;
            _mScale.ScaleY = scale;
        }
    }

    // ---------------------------------------------------------------
    //  Pill（贴边吸附态）
    // ---------------------------------------------------------------

    private void MorphToPill()
    {
        if (_isPill || _expanded || _morphInProgress) return;

        _isPill = true;
        var isHoriz = _snapDir is SnapDir.Top or SnapDir.Bottom;
        _pillFromW = BallBorder.Width;
        _pillToW = isHoriz ? MorphSpec.PillHeight : MorphSpec.PillWidth;
        _pillFromH = BallBorder.Height;
        _pillToH = isHoriz ? MorphSpec.PillWidth : MorphSpec.PillHeight;
        _pillFromCR = BallBorder.CornerRadius.TopLeft;
        _pillToCR = MorphSpec.PillCornerRadius;
        _pillGen++;
        _pillT = 0;
        _pillTick = 0;
        _pillSquash = true;
        RequestAnimationFrame(PillTick);
    }

    private void MorphToBall()
    {
        if (!_isPill || _expanded || _morphInProgress) return;

        _isPill = false;
        _pillFromW = BallBorder.Width;
        _pillToW = _ballSize;
        _pillFromH = BallBorder.Height;
        _pillToH = _ballSize;
        _pillFromCR = BallBorder.CornerRadius.TopLeft;
        _pillToCR = MorphSpec.BallCornerRadius;
        _pillGen++;
        _pillT = 0;
        _pillTick = 0;
        _pillSquash = false;
        RequestAnimationFrame(PillTick);
    }

    private void ReorientPill(SnapDir newDir)
    {
        var isHoriz = newDir is SnapDir.Top or SnapDir.Bottom;
        var newW = isHoriz ? MorphSpec.PillHeight : MorphSpec.PillWidth;
        var newH = isHoriz ? MorphSpec.PillWidth : MorphSpec.PillHeight;

        if (Math.Abs(BallBorder.Width - newW) < 0.5 && Math.Abs(BallBorder.Height - newH) < 0.5)
            return;

        _pillFromW = BallBorder.Width;
        _pillToW = newW;
        _pillFromH = BallBorder.Height;
        _pillToH = newH;
        _pillFromCR = BallBorder.CornerRadius.TopLeft;
        _pillToCR = MorphSpec.PillCornerRadius;
        _pillGen++;
        _pillT = 0;
        _pillTick = 0;
        _pillSquash = true;
        _isPill = true;
        _snapDir = newDir;
        RequestAnimationFrame(PillTick);
    }

    private void PillTick(TimeSpan now)
    {
        var gen = _pillGen;
        if (!Adv(now, ref _pillTick, ref _pillT, MorphSpec.PillDuration))
        {
            if (_pillGen == gen) RequestAnimationFrame(PillTick);
            return;
        }

        var spec = MorphSpec.PillSquash;
        var end = SpringCurve.EndValue(spec.Zeta, spec.Omega);
        var t = SpringCurve.Clamp01(SpringCurve.Normalized(_pillT, spec.Zeta, spec.Omega, end));

        var squashRaw = Math.Min(_pillT * 1.1, 1.0);
        var squash = SpringCurve.Clamp01(SpringCurve.Normalized(squashRaw, spec.Zeta, spec.Omega, end));

        var tw = _pillSquash ? squash : t;
        var th = _pillSquash ? t : squash;

        var w = SpringCurve.Lerp(_pillFromW, _pillToW, tw);
        var h = SpringCurve.Lerp(_pillFromH, _pillToH, th);
        var cr = SpringCurve.Lerp(_pillFromCR, _pillToCR, t);

        BallBorder.Width = w;
        BallBorder.Height = h;
        BallBorder.CornerRadius = new CornerRadius(cr);
        if (BallBorder.Clip is RectangleGeometry clip)
        {
            clip.Rect = new Rect(0, 0, w, h);
            clip.RadiusX = cr;
            clip.RadiusY = cr;
        }

        var iconScale = Math.Min(w, h) / _ballSize;
        if (_mScale is not null)
        {
            _mScale.ScaleX = iconScale;
            _mScale.ScaleY = iconScale;
        }

        if (_pillT >= 1 && _snapDir != SnapDir.None) SnapFlush();

        if (_pillT < 1 && _pillGen == gen) RequestAnimationFrame(PillTick);
    }

    private void SnapFlush()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;

        var pad = DipToPx(_ballPad);
        var pillPx = DipToPx(MorphSpec.PillWidth);

        Position = _snapDir switch
        {
            SnapDir.Left => new PixelPoint(wa.X - pad, Position.Y),
            SnapDir.Right => new PixelPoint(wa.X + wa.Width - pad - pillPx, Position.Y),
            SnapDir.Top => new PixelPoint(Position.X, wa.Y - pad),
            SnapDir.Bottom => new PixelPoint(Position.X, wa.Y + wa.Height - pad - pillPx),
            _ => Position
        };

    }

    private PixelRect GetScreenBounds()
    {
        var screen = Screens.ScreenFromPoint(Position) ?? Screens.Primary;
        return screen?.WorkingArea ?? default;
    }

    private void SnapAndSlide()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;

        var esPx = DipToPx(_edgeSnap);

        var distL = Math.Abs(Position.X - wa.X);
        var distR = Math.Abs(Position.X + _ballWinW - (wa.X + wa.Width));
        var distT = Math.Abs(Position.Y - wa.Y);
        var distB = Math.Abs(Position.Y + _ballWinH - (wa.Y + wa.Height));
        var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

        if (minDist > esPx) return;

        _snapDir = minDist == distL ? SnapDir.Left
            : minDist == distR ? SnapDir.Right
            : minDist == distT ? SnapDir.Top
            : SnapDir.Bottom;

        var pad = DipToPx(_ballPad);
        var pillPx = DipToPx(MorphSpec.PillWidth);

        var targetX = _snapDir switch
        {
            SnapDir.Left => wa.X - pad,
            SnapDir.Right => wa.X + wa.Width - pad - pillPx,
            _ => Position.X
        };

        var targetY = _snapDir switch
        {
            SnapDir.Top => wa.Y - pad,
            SnapDir.Bottom => wa.Y + wa.Height - pad - pillPx,
            _ => Position.Y
        };

        MorphToPill();

        var startX = Position.X;
        var startY = Position.Y;
        var gen = ++_slideGen;
        var sw = System.Diagnostics.Stopwatch.StartNew();

        void Tick()
        {
            if (_slideGen != gen) return;

            var t = Math.Min(sw.ElapsedMilliseconds / (double)MorphSpec.SnapSlideDurationMs, 1.0);
            var eased = SpringCurve.EaseOutCubic(t);

            Position = new PixelPoint(
                (int)(startX + (targetX - startX) * eased),
                (int)(startY + (targetY - startY) * eased));

            if (t < 1) Dispatcher.UIThread.Post(Tick, DispatcherPriority.Render);
            else SnapFlush();
        }

        Dispatcher.UIThread.Post(Tick, DispatcherPriority.Render);
    }

    private void AutoSnapIfOffScreen()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;

        var left = Position.X;
        var right = Position.X + _ballWinW;
        var top = Position.Y;
        var bottom = Position.Y + _ballWinH;

        if (left >= wa.X && right <= wa.X + wa.Width && top >= wa.Y && bottom <= wa.Y + wa.Height)
            return;

        var distL = left - wa.X;
        var distR = wa.X + wa.Width - right;
        var distT = top - wa.Y;
        var distB = wa.Y + wa.Height - bottom;
        var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

        _snapDir = minDist == distL ? SnapDir.Left
            : minDist == distR ? SnapDir.Right
            : minDist == distT ? SnapDir.Top
            : SnapDir.Bottom;

        Position = new PixelPoint(
            Math.Clamp(Position.X, wa.X, wa.X + wa.Width - _ballWinW),
            Math.Clamp(Position.Y, wa.Y, wa.Y + wa.Height - _ballWinH));

        MorphToPill();
        SnapFlush();
        SavePos();
    }

    // ---------------------------------------------------------------
    //  指针交互
    // ---------------------------------------------------------------

    private void OnPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsRightButtonPressed) return;

        _ptrDown = true;
        _dragging = false;
        _ptrStart = this.PointToScreen(e.GetPosition(this));
        _winStart = Position;

        if (sender is Control c) e.Pointer.Capture(c);
    }

    private void OnPointerMoved(object? sender, PointerEventArgs e)
    {
        if (!_ptrDown) return;

        var cur = this.PointToScreen(e.GetPosition(this));
        var dx = cur.X - _ptrStart.X;
        var dy = cur.Y - _ptrStart.Y;

        if (!_dragging && (Math.Abs(dx) > _dragThresh || Math.Abs(dy) > _dragThresh))
        {
            _dragging = true;
            if (_snapDir != SnapDir.None)
            {
                _ptrStart = cur;
                _winStart = Position;
            }
        }

        if (!_dragging) return;

        var x = _winStart.X + dx;
        var y = _winStart.Y + dy;
        var wa = GetScreenBounds();

        if (wa != default)
        {
            if (_expanded)
            {
                var halfW = _ballWinW / 2;
                var halfH = _ballWinH / 2;
                x = Math.Clamp(x, wa.X - halfW, wa.X + wa.Width - halfW);
                y = Math.Clamp(y, wa.Y, wa.Y + wa.Height - halfH);
            }
            else if (_snapDir != SnapDir.None)
            {
                var padPx = DipToPx(_ballPad);
                var ballPx = DipToPx(_ballSize);
                x = Math.Clamp(x, wa.X - padPx, wa.X + wa.Width - padPx - ballPx);
                y = Math.Clamp(y, wa.Y - padPx, wa.Y + wa.Height - padPx - ballPx);

                var cx = x + _ballWinW / 2;
                var cy = y + _ballWinH / 2;
                var distL = Math.Abs(cx - wa.X);
                var distR = Math.Abs(cx - (wa.X + wa.Width));
                var distT = Math.Abs(cy - wa.Y);
                var distB = Math.Abs(cy - (wa.Y + wa.Height));
                var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

                var newDir = minDist == distL ? SnapDir.Left
                    : minDist == distR ? SnapDir.Right
                    : minDist == distT ? SnapDir.Top
                    : SnapDir.Bottom;

                if (newDir != _snapDir) ReorientPill(newDir);
            }
            else
            {
                var padPx = DipToPx(_ballPad);
                var ballPx = DipToPx(_ballSize);
                x = Math.Clamp(x, wa.X - padPx, wa.X + wa.Width - padPx - ballPx);
                y = Math.Clamp(y, wa.Y - padPx, wa.Y + wa.Height - padPx - ballPx);
            }
        }

        Position = new PixelPoint(x, y);
    }

    private void OnPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (!_ptrDown) return;
        _ptrDown = false;

        if (_dragging)
        {
            _dragging = false;

            if (_snapDir != SnapDir.None)
            {
                var wa = GetScreenBounds();
                if (wa != default)
                {
                    var distL = Math.Abs(Position.X - wa.X);
                    var distR = Math.Abs(Position.X + _ballWinW - (wa.X + wa.Width));
                    var distT = Math.Abs(Position.Y - wa.Y);
                    var distB = Math.Abs(Position.Y + _ballWinH - (wa.Y + wa.Height));
                    var minDist = Math.Min(Math.Min(distL, distR), Math.Min(distT, distB));

                    if (minDist <= DipToPx(_edgeSnap))
                    {
                        _snapDir = minDist == distL ? SnapDir.Left
                            : minDist == distR ? SnapDir.Right
                            : minDist == distT ? SnapDir.Top
                            : SnapDir.Bottom;
                        MorphToPill();
                        SnapFlush();
                    }
                    else
                    {
                        _snapDir = SnapDir.None;
                        MorphToBall();
                    }
                }
            }
            else if (!_expanded)
            {
                SnapAndSlide();
            }

            SavePos();
        }
        else
        {
            if (_expanded)
            {
                // 展开态：双击收起（单击留给面板内的控件）
                var now = Environment.TickCount64;
                if (now - _lastClickTick < MorphSpec.DoubleClickMs)
                {
                    _lastClickTick = 0;
                    Click();
                }
                else
                {
                    _lastClickTick = now;
                }
            }
            else
            {
                Click();
            }
        }

        e.Pointer.Capture(null);
    }

    private void OnPointerEntered(object? sender, PointerEventArgs e)
    {
        if (!_expanded && !_morphInProgress) HoverIn();
    }

    private void OnPointerExited(object? sender, PointerEventArgs e)
    {
        if (!_expanded && !_morphInProgress) HoverOut();
    }

    private void Click()
    {
        if (_morphInProgress)
        {
            ReverseMorph();
            return;
        }

        if (_expanded) DoCollapse();
        else DoExpand();
    }

    // ---------------------------------------------------------------
    //  悬停弹性放大
    // ---------------------------------------------------------------

    private void HoverIn()
    {
        if (_expanded || _morphInProgress) return;

        var c = Sc;
        if (Math.Abs(c - 1.06) < 0.001) return;

        _hFrom = c;
        _hTo = 1.06;
        RunHover();
    }

    private void HoverOut()
    {
        var c = Sc;
        if (Math.Abs(c - 1.0) < 0.001) return;

        _hFrom = c;
        _hTo = 1.0;
        RunHover();
    }

    private void RunHover()
    {
        _hGen++;
        _hT = 0;
        _hTick = 0;
        RequestAnimationFrame(HoverTick);
    }

    private void HoverTick(TimeSpan now)
    {
        var gen = _hGen;
        if (!Adv(now, ref _hTick, ref _hT, MorphSpec.HoverDuration))
        {
            if (_hGen == gen) RequestAnimationFrame(HoverTick);
            return;
        }

        var spec = MorphSpec.Hover;
        var t = SpringCurve.Clamp01(
            SpringCurve.Normalized(_hT, spec.Zeta, spec.Omega, spec.EndValue));

        Sc = _hFrom + (_hTo - _hFrom) * t;

        if (_hT < 1 && _hGen == gen) RequestAnimationFrame(HoverTick);
    }

    private double Sc
    {
        get => BallBorder.RenderTransform is ScaleTransform st ? st.ScaleX : 1.0;
        set
        {
            if (BallBorder.RenderTransform is ScaleTransform st)
            {
                st.ScaleX = value;
                st.ScaleY = value;
            }
        }
    }

    // ---------------------------------------------------------------
    //  位置持久化
    // ---------------------------------------------------------------

    private void RestorePosition()
    {
        try
        {
            if (!File.Exists(PosFile)) return;

            var parts = File.ReadAllText(PosFile).Split(',');
            if (parts.Length == 2
                && int.TryParse(parts[0], out var x)
                && int.TryParse(parts[1], out var y))
            {
                Position = new PixelPoint(x, y);
            }
        }
        catch
        {
            // 位置文件损坏时静默回退到默认位置
        }
    }

    private void SavePos()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(PosFile)!);
            File.WriteAllText(PosFile, $"{Position.X},{Position.Y}");
        }
        catch
        {
            // 持久化失败不影响使用
        }
    }

    private void ClampToScreen()
    {
        var wa = GetScreenBounds();
        if (wa == default) return;

        // 球体状态必须完整可见：左右对称收敛。
        // 旧版把左边界放宽到 "wa.X - ballPad"（为贴边 pill 留的余量），
        // 结果普通球体可以被拖到/恢复到半出屏状态。
        var minX = wa.X;
        var maxX = wa.X + wa.Width - _ballWinW;
        var minY = wa.Y;
        var maxY = wa.Y + wa.Height - _ballWinH;

        Position = new PixelPoint(
            minX <= maxX ? Math.Clamp(Position.X, minX, maxX) : Position.X,
            minY <= maxY ? Math.Clamp(Position.Y, minY, maxY) : Position.Y);
    }

    // ---------------------------------------------------------------
    //  拖放投递
    // ---------------------------------------------------------------

    private void OnDragEnter(object? sender, DragEventArgs e)
    {
        if (e.DataTransfer.Contains(DataFormat.File)) SetDragOver(true);
    }

    private void OnDragLeave(object? sender, DragEventArgs e) => SetDragOver(false);

    private void SetDragOver(bool over)
    {
        _vm.IsDragOver = over;

        if (over)
        {
            BallBorder.BorderBrush = Brush.Parse("#E57153");
            BallBorder.BorderThickness = new Thickness(2);
            return;
        }

        if (this.TryFindResource("BondBorder", out var resource) && resource is IBrush brush)
        {
            BallBorder.BorderBrush = brush;
        }

        BallBorder.BorderThickness = new Thickness(1);
    }

    private void OnDragOver(object? sender, DragEventArgs e)
    {
        var isFile = e.DataTransfer.Contains(DataFormat.File);
        e.DragEffects = isFile ? DragDropEffects.Copy : DragDropEffects.None;
        SetDragOver(isFile);
    }

    private async void OnDrop(object? sender, DragEventArgs e)
    {
        var paths = new List<string>();
        foreach (var item in e.DataTransfer.GetItems(DataFormat.File))
        {
            var file = item.TryGetFile();
            if (file?.Path?.LocalPath is { } localPath) paths.Add(localPath);
        }

        SetDragOver(false);

        if (paths.Count == 0) return;

        // 拖到球上 = 直接上传到个人空间（面板展开显示真实进度）。
        if (!_expanded) DoExpand();
        await _vm.UploadPathsAsync(paths).ConfigureAwait(true);
    }

    /// <summary>快捷动作「上传」：弹系统文件选择器，选完直接上传到个人空间根目录。</summary>
    private async void OnUploadFilesRequested(object? sender, EventArgs e)
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "选择要上传到个人空间的文件",
            AllowMultiple = true,
            FileTypeFilter = [FilePickerFileTypes.All]
        });

        if (files.Count == 0) return;

        var paths = files
            .Select(f => f.TryGetLocalPath())
            .Where(p => !string.IsNullOrEmpty(p))
            .Select(p => p!)
            .ToList();

        if (paths.Count == 0)
        {
            _notify.Notify("这些文件不是本机文件，无法上传", NoticeKind.Warning);
            return;
        }

        await _vm.UploadPathsAsync(paths);
    }

    /// <summary>快捷动作「发文件」/ 设备行：弹系统文件选择器，然后发给目标设备。</summary>
    private async void OnSendFilesRequested(object? sender, BallDeviceItem? device)
    {
        if (device is { IsOnline: false })
        {
            _notify.Notify($"{device.Name} 当前离线，暂时无法发送", NoticeKind.Warning);
            return;
        }

        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "选择要发送的文件",
            AllowMultiple = true
        });

        var paths = files
            .Select(f => f.Path.LocalPath)
            .Where(File.Exists)
            .ToList();

        if (paths.Count == 0) return;

        await _vm.SendPathsToPeerAsync(device, paths).ConfigureAwait(true);
    }

    // ---------------------------------------------------------------
    //  Toast
    // ---------------------------------------------------------------

    private void OnNotified(object? sender, NoticeEventArgs e)
    {
        Dispatcher.UIThread.Post(() => ShowToast(e.Message));
    }

    private void ShowToast(string message)
    {
        if (!_expanded) return;

        ToastText.Text = message;
        ToastOverlay.IsVisible = true;
        ToastOverlay.Opacity = 1;

        _toastTimer?.Stop();
        _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.6) };
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer?.Stop();
            ToastOverlay.IsVisible = false;
        };
        _toastTimer.Start();
    }

    // ---------------------------------------------------------------
    //  菜单
    // ---------------------------------------------------------------

    private void OnOpenMainClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => _shell.ShowMain();

    private void OnCollapseClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => RequestCollapse();

    private void OnHideBallClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => Hide();

    private void OnExitClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
        => _shell.Quit();

    // ---------------------------------------------------------------
    //  工具
    // ---------------------------------------------------------------

    private static bool Adv(TimeSpan now, ref long prev, ref double progress, double duration)
    {
        if (prev == 0)
        {
            prev = now.Ticks;
            return false;
        }

        var dt = (now.Ticks - prev) / (double)TimeSpan.TicksPerSecond;
        prev = now.Ticks;
        progress += dt / duration;
        return true;
    }

    private int DipToPx(double dip) => MorphMath.DipToPx(dip, _dpi);

    protected override void OnClosed(EventArgs e)
    {
        _toastTimer?.Stop();
        _notify.Notified -= OnNotified;
        base.OnClosed(e);
    }
}
