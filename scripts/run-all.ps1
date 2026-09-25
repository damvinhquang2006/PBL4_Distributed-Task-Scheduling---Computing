# ==============================================================================
# Script khoi chay toan bo cum: 1 Master (Port 50) va 3 Workers (Port 51, 52, 53)
# ==============================================================================

Write-Host ">>> Dang bien dich ma nguon Java..." -ForegroundColor Cyan
mvn clean compile
if ($LASTEXITCODE -ne 0) {
    Write-Host "Bien dich that bai!" -ForegroundColor Red
    exit 1
}

$CP = "target/classes;" + (mvn dependency:build-classpath -Dmdep.outputFile=.cp.tmp | Out-Null; Get-Content .cp.tmp; Remove-Item .cp.tmp)

Write-Host ">>> Khoi chay Master Server tren Port 50..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -cp '$CP' com.pbl4.app.MasterApp --port 50"

Start-Sleep -Seconds 1

Write-Host ">>> Khoi chay Worker 01 tren Port 51..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -cp '$CP' com.pbl4.app.WorkerApp --id client01 --port 51 --masterPort 50"

Write-Host ">>> Khoi chay Worker 02 tren Port 52..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -cp '$CP' com.pbl4.app.WorkerApp --id client02 --port 52 --masterPort 50"

Write-Host ">>> Khoi chay Worker 03 tren Port 53..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -cp '$CP' com.pbl4.app.WorkerApp --id client03 --port 53 --masterPort 50"

Write-Host "`n>>> DA KHOI CHAY TOAN BO CUM (1 MASTER & 3 WORKERS) THANH CONG!" -ForegroundColor Cyan
Write-Host "Cac cua so PowerShell rieng biet da duoc mo cho tung Node." -ForegroundColor Gray
