<#
.SYNOPSIS
    构建 Bond 客户端 Windows 安装包（自包含发布 + Inno Setup 打包）。

.DESCRIPTION
    产物：publish\installer\Bond-<Version>-win-x64-setup.exe
    依赖：.NET SDK 10、Inno Setup 6（ISCC.exe）
      Inno Setup 下载：https://jrsoftware.org/isdl.php
      也可设置环境变量 BOND_ISCC 指向 ISCC.exe

.PARAMETER Version
    安装包版本，默认从 BondClient\Bond.csproj 的 <Version> 读取。

.PARAMETER SkipPublish
    跳过 dotnet publish（复用 publish\BondClient-win-x64 现有产物）。

.PARAMETER Upload
    构建后打印上传到软件分发接口所需的命令（不自动执行）。

.EXAMPLE
    pwsh scripts\build-installer.ps1
    pwsh scripts\build-installer.ps1 -Version 2.0.1 -SkipPublish
#>
[CmdletBinding()]
param(
    [string]$Version,
    [switch]$SkipPublish,
    [switch]$Upload
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$csproj = Join-Path $repo 'BondClient\Bond.csproj'
$iss = Join-Path $repo 'installer\BondClient-installer.iss'
$publishDir = Join-Path $repo 'publish\BondClient-win-x64'
$outDir = Join-Path $repo 'publish\installer'

if (-not $Version) {
    $m = Select-String -Path $csproj -Pattern '<Version>([^<]+)</Version>' | Select-Object -First 1
    if (-not $m) { throw "无法从 $csproj 读取 <Version>" }
    $Version = $m.Matches[0].Groups[1].Value.Trim()
}
Write-Host "Bond 安装包构建：version=$Version"

# 1. 结束正在运行的客户端（否则 exe 被占用，publish 会失败）
$running = Get-Process -Name Bond -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "检测到 Bond.exe 正在运行，先结束：$($running.Id -join ',')"
    $running | Stop-Process -Force
    Start-Sleep -Milliseconds 800
}

# 2. 自包含发布（内含 .NET 运行时 + libvlc）
if (-not $SkipPublish) {
    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
    Write-Host "dotnet publish -> $publishDir"
    & dotnet publish $csproj -c Release -r win-x64 --self-contained true `
        -p:DebugType=none -p:DebugSymbols=false -o $publishDir | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败（exit=$LASTEXITCODE）" }
} else {
    if (-not (Test-Path (Join-Path $publishDir 'Bond.exe'))) { throw "publish 目录不存在或不完整：$publishDir" }
    Write-Host "跳过 publish，复用 $publishDir"
}

# 3. 定位 ISCC
$isccCandidates = @(@(
    $env:BOND_ISCC,
    'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    'C:\Program Files\Inno Setup 6\ISCC.exe'
) | Where-Object { $_ -and (Test-Path $_) })
if ($isccCandidates.Count -eq 0) {
    throw "找不到 ISCC.exe：请安装 Inno Setup 6（https://jrsoftware.org/isdl.php）或设置 BOND_ISCC"
}
$iscc = $isccCandidates[0]

# 4. 同步 iss 里的版本号后编译
$issText = Get-Content -Raw -Path $iss
$issText = [regex]::Replace($issText, '(?m)^#define MyAppVersion "[^"]*"', "#define MyAppVersion `"$Version`"")
[IO.File]::WriteAllText($iss, $issText, (New-Object Text.UTF8Encoding($true)))

Write-Host "ISCC: $iscc"
& $iscc '/Qp' $iss
if ($LASTEXITCODE -ne 0) { throw "Inno Setup 编译失败（exit=$LASTEXITCODE）" }

# 5. 汇总
$setup = Join-Path $outDir "Bond-$Version-win-x64-setup.exe"
if (-not (Test-Path $setup)) { throw "未生成安装包：$setup" }
$file = Get-Item $setup
$sha = (Get-FileHash $setup -Algorithm SHA256).Hash.ToLower()

Write-Host ""
Write-Host "安装包：$($file.FullName)"
Write-Host ("大小  ：{0:N1} MB ({1:N0} 字节)" -f ($file.Length / 1MB), $file.Length)
Write-Host "SHA256：$sha"

if ($Upload) {
    Write-Host ""
    Write-Host "上传到软件分发（管理端需要管理员 JWT）："
    Write-Host "  curl -X POST `$API/api/admin/software/releases ^"
    Write-Host "    -H `"Authorization: Bearer `$TOKEN`" ^"
    Write-Host "    -F packageCode=bond-windows -F version=$Version -F channel=stable ^"
    Write-Host "    -F sha256=$sha -F releaseNotes=`"...`" ^"
    Write-Host "    -F `"file=@$setup`""
    Write-Host "  # 再 POST `$API/api/admin/software/releases/{id}/publish"
}
