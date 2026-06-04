using System;
using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Platform;

namespace BondClient.Views;

public partial class FloatingBallWindow : Window
{
    private const double DragThresholdDip = 5.0;
    private const double EdgeSnapThresholdDip = 30.0;
    private const double BallSizeDip = 56.0;
    private const double BallPadDip = 20.0;
    private const double PanelWidthDip = 300.0;
    private const double PanelHeightDip = 400.0;
    private const double PanelCR = 12.0;
    private const double BallCR = 28.0;
    private const double IconSizeDip = 28.0;
    private const double IconTargetSizeDip = 18.0;
    private const double IconPadFromEdge = 16.0;

    private static readonly double Phi    = (1 + Math.Sqrt(5)) / 2;
    private static readonly double InvPhi = 1.0 / Phi;

    // Spring parameters (Apple CASpringAnimation feel)
    // ζω ≈ 4 → spring fills the full duration, no dead time
    // Border: ζ=0.85 (near-critical), ω=5 → no overshoot, smooth settle
    private const double BorderZeta = 0.85;
    private const double BorderOmega = 5.0;
    // Icon: ζ=0.65 (underdamped), ω=6 → ~7% overshoot at 70%, settles by 100%
    private const double IconZeta = 0.65;
    private const double IconOmega = 6.0;

    // DPI
    private double _dpi = 1.0;

    // Scaled values
    private double _ballSize, _ballPad, _panelW, _panelH;
    private int _ballWinW, _ballWinH;
    private double _edgeSnap, _dragThresh;

    // MorphIcon endpoint
    private double _icoEndTx, _icoEndTy;
    private const double IcoStartTx = 0.0;
    private const double IcoStartTy = 0.0;
    private const double IcoStartSc = 1.0;
    private double _icoEndSc;

    private bool _ptrDown, _dragging;
    private PixelPoint _ptrStart, _winStart;
    private bool _snapL, _snapR, _hoverSnap;
    private bool _expanded;
    private bool _morphInProgress;

    // ── Morph ──
    private uint _mGen;
    private bool _mDir;
    private double _mT;
    private long _mTick;
    private double _fW, _fH, _fCR;
    private double _tW, _tH, _tCR;
    private int _cx, _cy;

    private double _iFx, _iFy, _iFs;
    private double _iTx, _iTy, _iTs;

    // ── Hover ──
    private uint _hGen;
    private double _hT;
    private long _hTick;
    private double _hFrom, _hTo;

    // ── Cached refs ──
    private ScaleTransform? _mScale;
    private TranslateTransform? _mTrans;

    // ── Performance cache ──
    private double _lastW, _lastH, _lastCR;
    private double _lastItx = double.NaN, _lastIty, _lastIts;

    private static readonly string PosFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "BondClient", "position.txt");

    public FloatingBallWindow()
    {
        InitializeComponent();
        if (MorphIcon.RenderTransform is TransformGroup tg)
        {
            _mScale = tg.Children[0] as ScaleTransform;
            _mTrans = tg.Children[1] as TranslateTransform;
        }
    }

    protected override void OnOpened(EventArgs e)
    {
        base.OnOpened(e);

        _dpi = RenderScaling;

        _ballSize = BallSizeDip;
        _ballPad  = BallPadDip;
        _panelW   = PanelWidthDip;
        _panelH   = PanelHeightDip;
        _ballWinW = DipToPx(_ballSize + _ballPad * 2);
        _ballWinH = _ballWinW;
        _edgeSnap = EdgeSnapThresholdDip;
        _dragThresh = DragThresholdDip;
        _icoEndSc = IconTargetSizeDip / IconSizeDip;

        // Icon endpoint: padding from edge + half target icon size
        var halfTarget = IconTargetSizeDip / 2.0;
        var iconCenter = IconPadFromEdge + halfTarget;  // 16 + 9 = 25
        _icoEndTx = iconCenter - IconSizeDip / 2.0;     // 25 - 28 = -3
        _icoEndTy = _icoEndTx;

        RestorePosition();

        if (!File.Exists(PosFile))
        {
            var s = Screens.Primary;
            if (s?.WorkingArea is { } wa)
            {
                Position = new PixelPoint(wa.X + wa.Width - _ballWinW - DipToPx(40),
                                          wa.Y + wa.Height / 2 - _ballWinH / 2);
            }
        }
    }

    #region DPI

    private int DipToPx(double dip) => (int)Math.Round(dip * _dpi);

    #endregion

    #region Position

    private void RestorePosition()
    {
        try
        {
            if (!File.Exists(PosFile)) return;
            var p = File.ReadAllText(PosFile).Split(',');
            if (p.Length == 2 && int.TryParse(p[0], out var x) && int.TryParse(p[1], out var y))
            {
                Position = new PixelPoint(x, y);
                var s = Screens.ScreenFromPoint(Position);
                if (s?.WorkingArea is { } wa)
                {
                    if (x <= wa.X + DipToPx(_edgeSnap)) _snapL = true;
                    else if (x + _ballWinW >= wa.X + wa.Width - DipToPx(_edgeSnap)) _snapR = true;
                }
            }
        }
        catch { }
    }

    private void SavePos()
    {
        try
        {
            var dir = Path.GetDirectoryName(PosFile)!;
            if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
            File.WriteAllText(PosFile, $"{Position.X},{Position.Y}");
        }
        catch { }
    }

    #endregion

    #region Drag

    private void OnPointerPressed(object? s, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsRightButtonPressed) return;
        _ptrDown = true; _dragging = false;
        _ptrStart = this.PointToScreen(e.GetPosition(this));
        _winStart = Position;
        if (s is Control c) e.Pointer.Capture(c);
    }

    private void OnPointerMoved(object? s, PointerEventArgs e)
    {
        if (!_ptrDown) return;
        var cur = this.PointToScreen(e.GetPosition(this));
        var dx = cur.X - _ptrStart.X;
        var dy = cur.Y - _ptrStart.Y;
        if (!_dragging && (Math.Abs(dx) > _dragThresh || Math.Abs(dy) > _dragThresh))
        { _dragging = true; _snapL = false; _snapR = false; }
        if (_dragging) Position = new PixelPoint(_winStart.X + dx, _winStart.Y + dy);
    }

    private void OnPointerReleased(object? s, PointerReleasedEventArgs e)
    {
        if (!_ptrDown) return;
        _ptrDown = false;
        if (_dragging) { _dragging = false; Snap(); SavePos(); }
        else Click();
        e.Pointer.Capture(null);
    }

    #endregion

    #region Edge snap

    private void Snap()
    {
        var scr = Screens.ScreenFromPoint(Position);
        if (scr?.WorkingArea is not { } wa) return;
        var esPx = DipToPx(_edgeSnap);
        if (Position.X <= wa.X + esPx)
        { Position = new PixelPoint(wa.X - _ballWinW + DipToPx(16), Position.Y); _snapL = true; }
        else if (Position.X + _ballWinW >= wa.X + wa.Width - esPx)
        { Position = new PixelPoint(wa.X + wa.Width - DipToPx(16), Position.Y); _snapR = true; }
        SavePos();
    }

    private void OnPointerEntered(object? s, PointerEventArgs e)
    {
        if (!_expanded && !_morphInProgress && (_snapL || _snapR))
        { _hoverSnap = true; Slide(true); }
        if (!_expanded && !_morphInProgress) HoverIn();
    }

    private void OnPointerExited(object? s, PointerEventArgs e)
    {
        if (_hoverSnap) { _hoverSnap = false; Slide(false); }
        if (!_expanded && !_morphInProgress) HoverOut();
    }

    private void Slide(bool o)
    {
        var scr = Screens.ScreenFromPoint(Position);
        if (scr?.WorkingArea is not { } wa) return;
        if (o && _snapL)      Position = new PixelPoint(wa.X, Position.Y);
        else if (o && _snapR) Position = new PixelPoint(wa.X + wa.Width - _ballWinW, Position.Y);
        else if (!o && _snapL) Position = new PixelPoint(wa.X - _ballWinW + DipToPx(16), Position.Y);
        else if (!o && _snapR) Position = new PixelPoint(wa.X + wa.Width - DipToPx(16), Position.Y);
    }

    #endregion

    #region Hover (spring)

    private void HoverIn()
    {
        if (_expanded || _morphInProgress) return;
        var c = Sc; if (Math.Abs(c - 1.06) < 0.001) return;
        _hFrom = c; _hTo = 1.06; RunHover();
    }

    private void HoverOut()
    {
        var c = Sc; if (Math.Abs(c - 1.0) < 0.001) return;
        _hFrom = c; _hTo = 1.0; RunHover();
    }

    private void RunHover()
    {
        _hGen++; _hT = 0; _hTick = 0;
        RequestAnimationFrame(HoverTick);
    }

    private void HoverTick(TimeSpan now)
    {
        var g = _hGen;
        if (!Adv(now, ref _hTick, ref _hT, 0.28)) { if (_hGen == g) RequestAnimationFrame(HoverTick); return; }
        var t = Spring(_hT, 0.65, 10); // Apple-like spring, ~7% overshoot
        Sc = _hFrom + (_hTo - _hFrom) * t;
        if (_hT < 1 && _hGen == g) RequestAnimationFrame(HoverTick);
    }

    private double Sc
    {
        get => BallBorder.RenderTransform is ScaleTransform st ? st.ScaleX : 1;
        set { if (BallBorder.RenderTransform is ScaleTransform st) { st.ScaleX = value; st.ScaleY = value; } }
    }

    #endregion

    #region Morph

    private void Click()
    {
        if (_morphInProgress) { ReverseMorph(); return; }
        if (_expanded) DoCollapse(); else DoExpand();
    }

    private void DoExpand()
    {
        _hGen++; Sc = 1;
        _snapL = false; _snapR = false;

        var tw = (int)(_ballSize + _ballPad * 2);
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(tw) / 2;

        _fW = BallBorder.Width;  _tW = _panelW;
        _fH = BallBorder.Height; _tH = _panelH;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = PanelCR;

        _iFx = IcoStartTx; _iFy = IcoStartTy; _iFs = IcoStartSc;
        _iTx = _icoEndTx;  _iTy = _icoEndTy;  _iTs = _icoEndSc;

        SetMorphIcon(IcoStartTx, IcoStartTy, IcoStartSc);
        MorphIcon.Opacity = 1;
        BallContent.Opacity = 0;
        PanelContent.IsVisible = true;
        PanelContent.Opacity = 0;

        StartMorph(true);
    }

    private void DoCollapse()
    {
        _hGen++; Sc = 1;

        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;

        _fW = BallBorder.Width;  _tW = _ballSize;
        _fH = BallBorder.Height; _tH = _ballSize;
        _fCR = BallBorder.CornerRadius.TopLeft; _tCR = BallCR;

        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        _iTx = IcoStartTx; _iTy = IcoStartTy; _iTs = IcoStartSc;

        MorphIcon.Opacity = 1;
        PanelContent.Opacity = 0;
        BallContent.Opacity = 0;

        StartMorph(false);
    }

    private void ReverseMorph()
    {
        _fW = BallBorder.Width;
        _fH = BallBorder.Height;
        _fCR = BallBorder.CornerRadius.TopLeft;

        _mDir = !_mDir;
        if (_mDir) { _tW = _panelW; _tH = _panelH; _tCR = PanelCR; PanelContent.IsVisible = true; }
        else       { _tW = _ballSize; _tH = _ballSize; _tCR = BallCR; }

        var tw = BallBorder.Width + _ballPad * 2;
        var th = BallBorder.Height + _ballPad * 2;
        _cx = Position.X + DipToPx(tw) / 2;
        _cy = Position.Y + DipToPx(th) / 2;

        _iFx = _mTrans?.X ?? 0; _iFy = _mTrans?.Y ?? 0; _iFs = _mScale?.ScaleX ?? 1;
        if (_mDir) { _iTx = _icoEndTx; _iTy = _icoEndTy; _iTs = _icoEndSc; }
        else       { _iTx = IcoStartTx; _iTy = IcoStartTy; _iTs = IcoStartSc; }

        MorphIcon.Opacity = 1;
        BallContent.Opacity = 0;
        PanelContent.Opacity = 0;

        _mT = 0;
        _mTick = 0;
    }

    private void StartMorph(bool expand)
    {
        _mGen++;
        _mDir = expand;
        _mT = 0;
        _mTick = 0;
        _lastW = double.NaN;
        _lastItx = double.NaN; // invalidate icon cache too
        _morphInProgress = true;
        RequestAnimationFrame(MorphTick);
    }

    private void MorphTick(TimeSpan now)
    {
        var g = _mGen;
        if (!Adv(now, ref _mTick, ref _mT, 0.5)) // 0.5s — Apple-like relaxed duration
        {
            if (_mGen == g) RequestAnimationFrame(MorphTick);
            return;
        }

        var raw = _mT > 1 ? 1 : _mT;

        // ── Border: normalized spring, clamped to prevent overshoot ──
        var bt = Clamp01(SpringNorm(raw, BorderZeta, BorderOmega));
        var w  = L(_fW,  _tW,  bt);
        var h  = L(_fH,  _tH,  bt);

        // CornerRadius: stay circular when small
        var minDim = Math.Min(w, h);
        var circularCR = minDim / 2.0;
        var linearCR = L(_fCR, _tCR, bt);
        var blend = Clamp01((minDim / _ballSize - 1.0) / 3.0);
        var cr = circularCR + (linearCR - circularCR) * SmoothStep(blend);

        // Performance: skip if unchanged (threshold 0.05 DIP to avoid micro-jitter)
        var wChanged = Math.Abs(w - _lastW) > 0.05;
        var hChanged = Math.Abs(h - _lastH) > 0.05;
        var crChanged = Math.Abs(cr - _lastCR) > 0.05;

        if (wChanged || hChanged)
        {
            var ww = DipToPx(w + _ballPad * 2);
            var wh = DipToPx(h + _ballPad * 2);
            Position = new PixelPoint(_cx - ww / 2, _cy - wh / 2);
            BallBorder.Width = w;
            BallBorder.Height = h;
            _lastW = w; _lastH = h;
        }

        if (crChanged)
        {
            BallBorder.CornerRadius = new CornerRadius(cr);
            if (BallBorder.Clip is RectangleGeometry clip)
            {
                clip.Rect = new Rect(0, 0, w, h);
                clip.RadiusX = cr;
                clip.RadiusY = cr;
            }
            _lastCR = cr;
        }
        else if (wChanged || hChanged)
        {
            if (BallBorder.Clip is RectangleGeometry clip)
                clip.Rect = new Rect(0, 0, w, h);
        }

        // ── Icon: normalized spring, clamped — no overshoot past endpoint ──
        var it = Clamp01(SpringNorm(raw, IconZeta, IconOmega));
        SetMorphIcon(L(_iFx, _iTx, it), L(_iFy, _iTy, it), L(_iFs, _iTs, it));

        // ── Content fade: ease-out (no spring) ──
        if (_mDir)
        {
            var ft = raw < 0.3 ? 0 : EaseOut((raw - 0.3) / 0.7);
            PanelContent.Opacity = ft;
        }
        else
        {
            PanelContent.Opacity = raw < 0.25 ? 1 - raw / 0.25 : 0;
            // BallContent stays hidden — MorphIcon is the only icon during animation
            // Clean handoff happens in EndMorph, no overlap
        }

        if (raw >= 1)
        {
            if (_mGen == g) EndMorph();
            return;
        }
        if (_mGen == g) RequestAnimationFrame(MorphTick);
    }

    private void EndMorph()
    {
        _morphInProgress = false;
        if (!_mDir)
        {
            _expanded = false;
            MorphIcon.Opacity = 0;
            PanelContent.IsVisible = false;
            PanelContent.Opacity = 0;
            BallContent.Opacity = 1;
            SetMorphIcon(IcoStartTx, IcoStartTy, IcoStartSc);
            _lastItx = double.NaN; // reset cache for next expand
        }
        else
        {
            _expanded = true;
            BallContent.Opacity = 0;
            PanelContent.Opacity = 1;
        }
    }

    #endregion

    #region MorphIcon

    private void SetMorphIcon(double tx, double ty, double scale)
    {
        // Skip if change is negligible — prevents micro-jitter
        if (Math.Abs(tx - _lastItx) < 0.05 &&
            Math.Abs(ty - _lastIty) < 0.05 &&
            Math.Abs(scale - _lastIts) < 0.002) return;
        _lastItx = tx; _lastIty = ty; _lastIts = scale;
        if (_mTrans != null) { _mTrans.X = tx; _mTrans.Y = ty; }
        if (_mScale != null) { _mScale.ScaleX = scale; _mScale.ScaleY = scale; }
    }

    #endregion

    #region Easing

    /// <summary>
    /// Damped harmonic spring — mimics Apple CASpringAnimation.
    /// ζ (zeta): damping ratio, 0=undamped, 1=critically damped, &lt;1=underdamped (bouncy)
    /// ω (omega): natural frequency, higher = faster response
    /// </summary>
    private static double Spring(double t, double zeta, double omega)
    {
        if (t <= 0) return 0;
        if (t >= 1) return 1;

        if (zeta >= 1)
        {
            return 1 - (1 + omega * t) * Math.Exp(-omega * t);
        }

        var omegaD = omega * Math.Sqrt(1 - zeta * zeta);
        var envelope = Math.Exp(-zeta * omega * t);
        return 1 - envelope * (Math.Cos(omegaD * t) + (zeta * omega / omegaD) * Math.Sin(omegaD * t));
    }

    /// <summary>
    /// Normalized spring: evaluates spring at t and divides by spring(1.0)
    /// so the curve goes from exactly 0 to exactly 1 with the overshoot shape preserved.
    /// Eliminates endpoint jitter from residual spring oscillation.
    /// </summary>
    private static double SpringNorm(double t, double zeta, double omega)
    {
        if (t <= 0) return 0;
        if (t >= 1) return 1;

        double endVal;
        if (zeta >= 1)
        {
            endVal = 1 - (1 + omega) * Math.Exp(-omega);
        }
        else
        {
            var omegaD = omega * Math.Sqrt(1 - zeta * zeta);
            var endEnv = Math.Exp(-zeta * omega);
            endVal = 1 - endEnv * (Math.Cos(omegaD) + (zeta * omega / omegaD) * Math.Sin(omegaD));
        }

        if (Math.Abs(endVal) < 0.001) return t; // fallback

        var rawVal = Spring(t, zeta, omega);
        return rawVal / endVal;
    }

    private static double SmoothStep(double t) => t * t * t * (t * (6 * t - 15) + 10);

    /// <summary>Ease-out cubic — decelerating, for content fade.</summary>
    private static double EaseOut(double t) => 1 - (1 - t) * (1 - t) * (1 - t);

    private static double L(double a, double b, double t) => a + (b - a) * t;
    private static double Clamp01(double v) => v < 0 ? 0 : v > 1 ? 1 : v;

    #endregion

    #region Helpers

    private static bool Adv(TimeSpan now, ref long prev, ref double progress, double duration)
    {
        if (prev == 0) { prev = now.Ticks; return false; }
        var dt = (now.Ticks - prev) / (double)TimeSpan.TicksPerSecond;
        prev = now.Ticks;
        progress += dt / duration;
        return true;
    }

    #endregion

    private void OnExitClick(object? s, RoutedEventArgs e)
    {
        SavePos();
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime lt) lt.Shutdown();
    }

    public void ToggleVisibility()
    {
        if (IsVisible) Hide();
        else { Show(); Activate(); }
    }
}
