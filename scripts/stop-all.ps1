# ==============================================================================
# Script tat tat ca cac tien trinh Java cua Master va Workers
# ==============================================================================

Write-Host ">>> Dang tim va dung cac tien trinh Master va Worker..." -ForegroundColor Yellow

$procs = Get-CimInstance Win32_Process | Where-Object { 
    $_.CommandLine -like "*com.pbl4.app.MasterApp*" -or 
    $_.CommandLine -like "*com.pbl4.app.WorkerApp*" -or
    $_.CommandLine -like "*com.pbl4.app.ClusterLauncher*" -or
    $_.CommandLine -like "*com.pbl4.app.WorkerClusterLauncher*" -or
    $_.CommandLine -like "*vite*" -or
    $_.CommandLine -like "*distributed-task-scheduling*"
}

if ($procs) {
    foreach ($p in $procs) {
        Write-Host ("Dung tien trinh PID: " + $p.ProcessId) -ForegroundColor Red
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Write-Host ">>> Da dung toan bo cum an toan!" -ForegroundColor Green
} else {
    Write-Host "Khong tim thay tien trinh Master/Worker nao dang chay." -ForegroundColor Gray
}
