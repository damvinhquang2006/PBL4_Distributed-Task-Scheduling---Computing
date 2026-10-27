# ==============================================================================
# Script khoi chay Web UI Frontend (ReactJS + Vite)
# ==============================================================================
param(
    [int]$Port = 5173
)

$FrontendDir = Join-Path $PSScriptRoot "..\frontend"
if (-not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
    Write-Host ">>> Dang cai dat dependencies cho Frontend..." -ForegroundColor Cyan
    Push-Location $FrontendDir
    npm install
    Pop-Location
}

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "   KHOI CHAY GIAO DIEN WEB UI REACT TAI: http://localhost:$Port" -ForegroundColor Green
Write-Host "==================================================================" -ForegroundColor Cyan

Push-Location $FrontendDir
npm run dev -- --port $Port --open
Pop-Location
