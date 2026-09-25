# ==============================================================================
# Script chay Demo tu dong toan dien: 1 Master (Port 50) + 3 Workers (Port 51, 52, 53)
# ==============================================================================

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "    CHAY TOAN BO KICH BAN TINH TOAN PHAN TAN & CAN BANG TAI       " -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

$CP = "target/classes;" + (mvn dependency:build-classpath -Dmdep.outputFile=.cp.tmp | Out-Null; Get-Content .cp.tmp; Remove-Item .cp.tmp)

java -cp $CP com.pbl4.app.ClusterLauncher
