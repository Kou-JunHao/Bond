<#
  Capture a window to PNG for visual verification.

  Usage:
    pwsh -File tools/shot.ps1 -Out docs/screenshots/x.png [-ProcessName Bond] [-DelayMs 400]

  Notes:
    * Sets per-monitor-v2 DPI awareness BEFORE any drawing call, otherwise
      GetWindowRect/CopyFromScreen mix DIPs with pixels and the crop is wrong
      (that is why earlier captures came out 1707x1067 instead of 2560x1600).
    * Captures the exact window rect, so the result has the real pixel size
      for this 2560x1600 @150% display.
#>
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$ProcessName = 'Bond',
    [int]$DelayMs = 500,
    [switch]$Maximize
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$native = @'
using System;
using System.Runtime.InteropServices;
public static class WinCap
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
'@
if (-not ('WinCap' -as [type])) { Add-Type -TypeDefinition $native }

# -4 == DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
# The process-wide call fails when the host manifest already fixed an awareness
# context (powershell.exe does). The per-thread call always wins for this thread,
# which is what GetWindowRect/CopyFromScreen consult.
if (-not [WinCap]::SetProcessDpiAwarenessContext([IntPtr](-4))) {
    $prev = [WinCap]::SetThreadDpiAwarenessContext([IntPtr](-4))
    if ($prev -eq [IntPtr]::Zero) {
        Write-Warning 'Could not enable per-monitor DPI awareness; capture may be scaled.'
    }
}

$proc = Get-Process -Name $ProcessName -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
    Select-Object -First 1
if (-not $proc) { throw "No window found for process '$ProcessName'." }

$h = $proc.MainWindowHandle
if ([WinCap]::IsIconic($h)) { [void][WinCap]::ShowWindow($h, 9) }   # SW_RESTORE
if ($Maximize) { [void][WinCap]::ShowWindow($h, 3) }               # SW_MAXIMIZE
[void][WinCap]::SetForegroundWindow($h)
Start-Sleep -Milliseconds $DelayMs

$r = New-Object WinCap+RECT
if (-not [WinCap]::GetWindowRect($h, [ref]$r)) { throw 'GetWindowRect failed.' }

$w = $r.Right - $r.Left
$hh = $r.Bottom - $r.Top
if ($w -le 0 -or $hh -le 0) { throw "Bad window rect: $w x $hh" }

$bmp = New-Object System.Drawing.Bitmap $w, $hh
$g = [System.Drawing.Graphics]::FromImage($bmp)
try {
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size $w, $hh))
}
finally {
    $g.Dispose()
}

$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Output "$Out  ${w}x${hh}"
