using System;
using System.Reflection;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Rendering;

namespace Bond.Views;

public partial class MainWindow
{
    private bool _renderTimerPatched;

    /// <summary>
    /// WinUI/DirectComposition 连接会把自己的 IRenderTimer 塞进 Compositor 的
    /// DefaultRenderLoop，覆盖平台层的 IRenderLoop 绑定。这里在窗口创建后直接替换
    /// 该 loop 的 _timer，让渲染节奏跟随显示器刷新率。
    /// </summary>
    private void ApplyHighRefreshRenderTimer()
    {
        if (_renderTimerPatched)
        {
            return;
        }

        try
        {
            var renderer = typeof(TopLevel)
                .GetProperty("Renderer", BindingFlags.Instance | BindingFlags.NonPublic)
                ?.GetValue(this);
            var compositor = renderer?.GetType()
                .GetProperty("Compositor", BindingFlags.Public | BindingFlags.Instance)
                ?.GetValue(renderer);
            var loop = compositor?.GetType()
                .GetProperty("Loop", BindingFlags.NonPublic | BindingFlags.Instance)
                ?.GetValue(compositor);
            if (loop is null)
            {
                return;
            }

            var timerField = loop.GetType().GetField("_timer", BindingFlags.NonPublic | BindingFlags.Instance);
            var tickField = loop.GetType().GetField("_tick", BindingFlags.NonPublic | BindingFlags.Instance);
            var oldTimer = timerField?.GetValue(loop);
            var tick = tickField?.GetValue(loop) as Action<TimeSpan>;
            if (oldTimer is null || tick is null)
            {
                return;
            }

            var oldFps = oldTimer.GetType()
                .GetProperty("FramesPerSecond", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)
                ?.GetValue(oldTimer);
            if (oldFps is int fps && fps >= 200)
            {
                _renderTimerPatched = true;
                return;
            }

            var baseAssembly = typeof(AvaloniaLocator).Assembly;
            var timerType = baseAssembly.GetType("Avalonia.Rendering.DefaultRenderTimer");
            if (timerType is null)
            {
                return;
            }

            var refreshRate = Bond.Program.GetDisplayRefreshRate();
            var newTimer = Activator.CreateInstance(timerType, refreshRate);
            if (newTimer is null)
            {
                return;
            }

            newTimer.GetType()
                .GetProperty("Tick", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)
                ?.SetValue(newTimer, tick);
            oldTimer.GetType()
                .GetProperty("Tick", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)
                ?.SetValue(oldTimer, null);
            timerField!.SetValue(loop, newTimer);
            _renderTimerPatched = true;
        }
        catch
        {
            // 渲染计时器替换失败时保持平台默认行为。
        }
    }
}
