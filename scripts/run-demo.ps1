# ==============================================================================
# Script chay Demo tu dong toan dien: 1 Master (Port 50) + N Workers (Mac dinh: 3 Workers)
# ==============================================================================
param(
    [int]$Workers = 3
)

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "    CHAY TOAN BO KICH BAN TINH TOAN PHAN TAN & CAN BANG TAI       " -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

$JAR = "target/distributed-task-scheduling-1.0.0.jar"

if (-not (Test-Path $JAR)) {
    Write-Host ">>> Dang bien dich va dong goi du an bang Maven..." -ForegroundColor Cyan
    mvn clean package -DskipTests=true
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Bien dich that bai!" -ForegroundColor Red
        exit 1
    }
}

java -cp $JAR com.pbl4.app.ClusterLauncher --workers $Workers
