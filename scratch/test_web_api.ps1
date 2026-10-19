$JAR = "target/distributed-task-scheduling-1.0.0.jar"
$masterPort = 5550
$httpPort = 8550
$workerStartPort = 5551

Write-Host ">>> 1. Khoi chay Master Server voi Web API tai port $httpPort..." -ForegroundColor Cyan
$masterProc = Start-Process java -ArgumentList "-jar", $JAR, "--port", $masterPort, "--httpPort", $httpPort -PassThru
Start-Sleep -Seconds 3

Write-Host ">>> 2. Khoi chay 3 Workers..." -ForegroundColor Cyan
$workersProc = Start-Process java -ArgumentList "-cp", $JAR, "com.pbl4.app.WorkerClusterLauncher", "--count", "3", "--startPort", $workerStartPort, "--masterPort", $masterPort -PassThru
Start-Sleep -Seconds 3

try {
    Write-Host ">>> 3. Kiem tra GET /api/status..." -ForegroundColor Yellow
    $statusResp = Invoke-RestMethod -Uri "http://localhost:$httpPort/api/status" -Method Get
    Write-Host ("Status: running=" + $statusResp.running + ", totalWorkers=" + $statusResp.totalWorkers + ", availableWorkers=" + $statusResp.availableWorkers)

    Write-Host ">>> 4. Kiem tra GET /api/workers..." -ForegroundColor Yellow
    $workersResp = Invoke-RestMethod -Uri "http://localhost:$httpPort/api/workers" -Method Get
    Write-Host ("So workers nhan duoc: " + $workersResp.Count)
    foreach ($w in $workersResp) {
        Write-Host ("  - Worker: " + $w.workerId + " at port " + $w.port + " [Status: " + $w.status + "]")
    }

    Write-Host ">>> 5. Kiem tra POST /api/compute (Nhan ma tran A x B)..." -ForegroundColor Yellow
    $body = @{
        operation = "x"
        matrixA = @(@(1, 2), @(3, 4))
        matrixB = @(@(2, 0), @(1, 2))
        compareSequential = $true
    } | ConvertTo-Json

    $computeResp = Invoke-RestMethod -Uri "http://localhost:$httpPort/api/compute" -Method Post -ContentType "application/json" -Body $body
    Write-Host ("Compute Success: " + $computeResp.success)
    Write-Host ("Result Matrix: " + ($computeResp.result | ConvertTo-Json -Compress))
    Write-Host ("Distributed Time: " + $computeResp.distributedTimeMs + " ms")
    Write-Host ("Sequential Time: " + $computeResp.sequentialTimeMs + " ms")

    # Expected: [[4, 4], [10, 8]]
    if ($computeResp.result[0][0] -eq 4 -and $computeResp.result[0][1] -eq 4 -and $computeResp.result[1][0] -eq 10 -and $computeResp.result[1][1] -eq 8) {
        Write-Host ">>> TEST WEB API: THANH CONG 100%!" -ForegroundColor Green
    } else {
        Write-Host ">>> TEST THAT BAI: Ket qua sai!" -ForegroundColor Red
    }

} finally {
    Write-Host ">>> Don dep tien trinh test..." -ForegroundColor Gray
    Stop-Process -Id $masterProc.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $workersProc.Id -Force -ErrorAction SilentlyContinue
}
