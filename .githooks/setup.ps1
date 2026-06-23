# Run this script once to enable the pre-commit secret detection hook
git config core.hooksPath (Join-Path (Get-Location) '.githooks')
Write-Host "Git hooks configured. Pre-commit secret scanning is now active." -ForegroundColor Green
