# ==============================================================================
# Script khoi chay toan bo cum: 1 Master (Port 50) va N Workers (Mac dinh: 50 Workers)
# ==============================================================================
param(
    [int]$WorkerCount = 50,
    [int]$MasterPort = 50,
    [int]$StartWorkerPort = 51,
    [switch]$WithUI
)

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "     KHOI CHAY CUM PHAN TAN: 1 MASTER & $WorkerCount WORKERS" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

$JAR = "target/distributed-task-scheduling-1.0.0.jar"

# Kiem tra va bien dich neu chua co JAR hoac ma nguon thay doi
if (-not (Test-Path $JAR)) {
    Write-Host ">>> Dang bien dich va dong goi du an bang Maven..." -ForegroundColor Cyan
    mvn clean package -DskipTests=true
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Bien dich that bai!" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host ">>> Da tim thay file package: $JAR" -ForegroundColor Green
}

# 1. Khoi chay Master Server tren Port 50 trong mot cua so PowerShell rieng
Write-Host ">>> Khoi chay Master Server tren Port $MasterPort..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar '$JAR' --port $MasterPort"

# Doi 2 giay de Master Server san sang lang nghe
Start-Sleep -Seconds 2

# 2. Khoi chay cum Workers dong thoi
$EndWorkerPort = $StartWorkerPort + $WorkerCount - 1
Write-Host ">>> Khoi chay $WorkerCount Workers tren cac Port $StartWorkerPort -> $EndWorkerPort..." -ForegroundColor Yellow

if ($WorkerCount -le 3) {
    # Neu so luong nho (<= 3), mo tung cua so rieng biet de quan sat
    for ($i = 0; $i -lt $WorkerCount; $i++) {
        $port = $StartWorkerPort + $i
        $id = [string]::Format("client{0:D2}", $i + 1)
        Write-Host "  |-- Khoi chay $id tren Port $port..." -ForegroundColor Yellow
        Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -cp '$JAR' com.pbl4.app.WorkerApp --id $id --port $port --masterPort $MasterPort"
    }
} else {
    # Neu so luong lon (VD: 50 Workers), khoi chay dong thoi tat ca trong 1 cua so Cluster chuyen dung
    # tranh viec lam tran man hinh Windows voi 50 cua so popup
    Write-Host "  |-- Khoi chay dong thoi $WorkerCount Workers trong cua so quan ly cum..." -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -cp '$JAR' com.pbl4.app.WorkerClusterLauncher --count $WorkerCount --startPort $StartWorkerPort --masterPort $MasterPort"
}

if ($WithUI) {
    Write-Host ">>> Khoi chay Web UI Frontend (ReactJS) tai http://localhost:5173..." -ForegroundColor Cyan
    $FrontendDir = Join-Path $PSScriptRoot "..\frontend"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$FrontendDir'; npm run dev -- --port 5173 --open"
}

Write-Host "`n>>> DA KHOI CHAY TOAN BO CUM THANH CONG!" -ForegroundColor Cyan
Write-Host "  - Master Server: 127.0.0.1:$MasterPort (Web API: http://localhost:8080/api)" -ForegroundColor Gray
Write-Host "  - Tong so Workers: $WorkerCount (Ports $StartWorkerPort -> $EndWorkerPort)" -ForegroundColor Gray
if ($WithUI) {
    Write-Host "  - Web UI Dashboard: http://localhost:5173" -ForegroundColor Green
} else {
    Write-Host "  - Muon mo Web UI ReactJS? Chay: .\scripts\start-ui.ps1 hoac .\scripts\run-all.ps1 -WithUI" -ForegroundColor Cyan
}
Write-Host "  - De tat toan bo cum Master, Workers & UI, hay chay: .\scripts\stop-all.ps1" -ForegroundColor Yellow
