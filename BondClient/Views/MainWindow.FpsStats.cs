using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Reflection;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Rendering;
using Avalonia.Threading;

namespace Bond.Views;

public partial class MainWindow
{
    private readonly List<double> _frameTimes = new(2048);
    private DispatcherTimer? _fpsStatsTimer;
    private long _lastFrameTimestamp;
    private bool _fpsStatsRunning;
    private object? _renderer;
    private EventInfo? _sceneInvalidatedEvent;
    private EventHandler<SceneInvalidatedEventArgs>? _sceneInvalidatedHandler;

    /// <summary>
    /// 临时诊断浮层：Ctrl+Shift+F9 或 BOND_FPS_STATS=1 时显示平均帧率、1% Low、
    /// 帧时间分位和帧率分布。数据来自渲染器 SceneInvalidated（真实呈现帧）。
    /// </summary>
    private void StartFpsStats(bool force = false)
    {
        if (_fpsStatsRunning)
        {
            return;
        }

        if (!force && Environment.GetEnvironmentVariable("BOND_FPS_STATS") != "1")
        {
            return;
        }

        if (!TryHookRenderer())
        {
            return;
        }

        FpsStatsPanel.IsVisible = true;
        _fpsStatsRunning = true;
        _lastFrameTimestamp = Stopwatch.GetTimestamp();

        _fpsStatsTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _fpsStatsTimer.Tick += (_, _) => UpdateFpsStats();
        _fpsStatsTimer.Start();

        Closed += (_, _) => StopFpsStats();
    }

    private bool TryHookRenderer()
    {
        try
        {
            var rendererProperty = typeof(TopLevel).GetProperty(
                "Renderer",
                BindingFlags.Instance | BindingFlags.NonPublic);
            _renderer = rendererProperty?.GetValue(this);
            if (_renderer is null)
            {
                return false;
            }

            _sceneInvalidatedEvent = _renderer.GetType().GetEvent(
                "SceneInvalidated",
                BindingFlags.Public | BindingFlags.Instance);
            if (_sceneInvalidatedEvent is null)
            {
                return false;
            }

            _sceneInvalidatedHandler = OnSceneInvalidated;
            _sceneInvalidatedEvent.AddEventHandler(_renderer, _sceneInvalidatedHandler);

            return true;
        }
        catch
        {
            return false;
        }
    }

    private void OnSceneInvalidated(object? sender, SceneInvalidatedEventArgs e)
    {
        if (!_fpsStatsRunning)
        {
            return;
        }

        var now = Stopwatch.GetTimestamp();
        var deltaMs = (now - _lastFrameTimestamp) * 1000.0 / Stopwatch.Frequency;
        _lastFrameTimestamp = now;

        // 超过 100ms 的间隔视为应用空闲/无渲染，不计入帧率统计。
        if (deltaMs > 0 && deltaMs <= 100)
        {
            _frameTimes.Add(deltaMs);
            if (_frameTimes.Count > 1200)
            {
                _frameTimes.RemoveRange(0, _frameTimes.Count - 1200);
            }
        }
    }

    private void ToggleFpsStats()
    {
        if (_fpsStatsRunning)
        {
            StopFpsStats();
        }
        else
        {
            StartFpsStats(force: true);
        }
    }

    private void StopFpsStats()
    {
        _fpsStatsRunning = false;
        _fpsStatsTimer?.Stop();
        _fpsStatsTimer = null;

        if (_renderer is not null && _sceneInvalidatedEvent is not null && _sceneInvalidatedHandler is not null)
        {
            try
            {
                _sceneInvalidatedEvent.RemoveEventHandler(_renderer, _sceneInvalidatedHandler);
            }
            catch
            {
                // 窗口关闭阶段忽略。
            }
        }

        _renderer = null;
        _sceneInvalidatedEvent = null;
        _sceneInvalidatedHandler = null;

        if (FpsStatsPanel is not null)
        {
            FpsStatsPanel.IsVisible = false;
        }
    }

    private void UpdateFpsStats()
    {
        if (!_fpsStatsRunning || FpsStatsText is null)
        {
            return;
        }

        if (_frameTimes.Count < 2)
        {
            FpsStatsText.Text = "FPS 统计：等待帧…";
            return;
        }

        var sorted = _frameTimes.OrderBy(x => x).ToArray();
        var avgMs = _frameTimes.Average();
        var p50 = Percentile(sorted, 0.50);
        var p95 = Percentile(sorted, 0.95);
        var p99 = Percentile(sorted, 0.99);
        var avgFps = 1000.0 / avgMs;
        var lowFps = 1000.0 / p99;

        var buckets = new int[5];
        foreach (var ms in _frameTimes)
        {
            if (ms <= 1000.0 / 240) buckets[0]++;
            else if (ms <= 1000.0 / 120) buckets[1]++;
            else if (ms <= 1000.0 / 60) buckets[2]++;
            else if (ms <= 1000.0 / 30) buckets[3]++;
            else buckets[4]++;
        }

        var total = (double)_frameTimes.Count;
        FpsStatsText.Text =
            $"平均 {avgFps:F1} FPS | 1% Low {lowFps:F1} FPS\n" +
            $"帧时间 p50 {p50:F1}ms  p95 {p95:F1}ms  p99 {p99:F1}ms\n" +
            $"≥240: {buckets[0] / total:P0}  120-239: {buckets[1] / total:P0}  60-119: {buckets[2] / total:P0}  30-59: {buckets[3] / total:P0}  <30: {buckets[4] / total:P0}";
    }

    private static double Percentile(double[] sorted, double p)
    {
        if (sorted.Length == 0)
        {
            return 0;
        }

        var index = (int)Math.Ceiling(p * sorted.Length) - 1;
        return sorted[Math.Clamp(index, 0, sorted.Length - 1)];
    }
}
