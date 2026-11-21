# ==============================================================================
# Script khoi chay Web UI Frontend (ReactJS + Vite)
# ==============================================================================
param(
    [ValidateRange(1,65535)][int]$Port = 5173,
    [string]$HostAddress = '0.0.0.0'
)

$FrontendDir = Join-Path $PSScriptRoot "..\frontend"
if (-not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
    Write-Host ">>> Dang cai dat dependencies cho Frontend..." -ForegroundColor Cyan
    Push-Location $FrontendDir
    npm install
    Pop-Location
}

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "   Giao dien Web UI dang lang nghe tren $HostAddress`:$Port" -ForegroundColor Green
Write-Host '   Tren may B, mo http://<IP-Radmin-cua-may-A>:5173' -ForegroundColor Green
Write-Host "==================================================================" -ForegroundColor Cyan

Push-Location $FrontendDir
npm run dev -- --host $HostAddress --port $Port --open
Pop-Location
