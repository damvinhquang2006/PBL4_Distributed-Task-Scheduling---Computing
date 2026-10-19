# ==============================================================================
# Script khoi chay chi rieng cum Workers (Mac dinh: 50 Workers)
# ==============================================================================
param(
    [int]$Count = 50,
    [int]$StartPort = 51,
    [int]$MasterPort = 50,
    [string]$MasterHost = "127.0.0.1",
    [int]$Capacity = 2
)

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "     KHOI CHAY CUM $Count WORKERS KET NOI TOI MASTER $MasterHost`:$MasterPort" -ForegroundColor Cyan
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

$EndPort = $StartPort + $Count - 1
Write-Host ">>> Bat dau khoi chay $Count Workers tren cac Port $StartPort -> $EndPort..." -ForegroundColor Yellow

java -cp $JAR com.pbl4.app.WorkerClusterLauncher --count $Count --startPort $StartPort --masterHost $MasterHost --masterPort $MasterPort --capacity $Capacity
