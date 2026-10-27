param(
    [string]$MasterAddress = '127.0.0.1',
    [ValidateRange(1,65535)][int]$MasterPort = 5000,
    [ValidateRange(1,65535)][int]$HttpPort = 8080,
    [switch]$TestMatrix,
    [ValidateRange(2,100)][int]$MatrixSize = 2,
    [string]$JarPath = (Join-Path $PSScriptRoot '../target/distributed-task-scheduling-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'

function Test-TcpEndpoint([string]$Address, [int]$Port, [int]$TimeoutMs = 2500) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($Address, $Port, $null, $null)
        if (!$pending.AsyncWaitHandle.WaitOne($TimeoutMs)) { throw 'Connection timeout' }
        $client.EndConnect($pending)
        return [ordered]@{ address = $Address; port = $Port; connected = $true }
    } catch {
        return [ordered]@{ address = $Address; port = $Port; connected = $false; error = $_.Exception.Message }
    } finally { $client.Dispose() }
}

$report = [ordered]@{
    timestamp = (Get-Date).ToString('o')
    computer = [Environment]::MachineName
    localIPv4 = @()
    jarSha256 = $null
    masterTcp = (Test-TcpEndpoint $MasterAddress $MasterPort)
}
foreach ($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    if ($adapter.OperationalStatus -ne [Net.NetworkInformation.OperationalStatus]::Up) { continue }
    foreach ($address in $adapter.GetIPProperties().UnicastAddresses) {
        if ($address.Address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork) {
            $report.localIPv4 += [ordered]@{ adapter = $adapter.Name; address = $address.Address.ToString() }
        }
    }
}
if (Test-Path -LiteralPath $JarPath) { $report.jarSha256 = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash }
$base = 'http://{0}:{1}/api' -f $MasterAddress, $HttpPort
try {
    $snapshot = Invoke-RestMethod -Uri ($base + '/cluster') -TimeoutSec 5
    $report.status = $snapshot | Select-Object running,masterSession,protocolVersion,masterPort,policy,jarSha256,connectedWorkers,readyWorkers,offlineWorkers,effectiveParallelism
    $report.machines = $snapshot.machines
    $workers = $snapshot.workers
    $tcpDeadline = [DateTime]::UtcNow.AddSeconds(8)
    $report.workers = @($workers | ForEach-Object {
        [ordered]@{
            workerId = $_.workerId; host = $_.host; port = $_.port
            machineId = $_.machineId; machineName = $_.machineName; hostParallelism = $_.hostParallelism
            status = $_.status; paused = $_.paused; admissionReason = $_.admissionReason
            activeTasks = $_.activeTasks; capacity = $_.capacity; completedTasks = $_.completedTasks
            metrics = $_.metrics
            runtimeJarSha256 = $_.jarSha256
            jarMatchesMaster = $(if ($_.jarSha256 -and $report.status.jarSha256) { $_.jarSha256 -eq $report.status.jarSha256 } else { 'UNKNOWN' })
            tcpFromThisLaptop = $(
                if ($_.status -eq 'OFFLINE') { [ordered]@{ connected=$false; skipped=$true; reason='Registry marks this Worker OFFLINE' } }
                elseif ([DateTime]::UtcNow -ge $tcpDeadline) { [ordered]@{ connected=$null; skipped=$true; reason='Overall TCP probe time budget exceeded' } }
                else { Test-TcpEndpoint $_.host $_.port ([Math]::Max(1,[Math]::Min(2500,($tcpDeadline-[DateTime]::UtcNow).TotalMilliseconds))) }
            )
        }
    })
    if ($TestMatrix) {
        # A x I must equal A. Allow the exact size reported in a failing run.
        $request = @{
            operation = 'x'; rowsA = $MatrixSize; colsA = $MatrixSize; rowsB = $MatrixSize; colsB = $MatrixSize; taskCount = 0
        }
        if ($MatrixSize -eq 2) {
            $request.matrixA = @(@(1,2), @(3,4))
            $request.matrixB = @(@(1,0), @(0,1))
        } else {
            $request.genA = @{ generator = 'constant-v1'; val = 3 }
            $request.genB = @{ generator = 'identity-v1' }
        }
        $body = $request | ConvertTo-Json -Depth 5
        $job = Invoke-RestMethod -Uri ($base + '/compute/async') -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 10
        $report.testJobId = $job.jobId
        $deadline = (Get-Date).AddSeconds(15)
        do {
            $progress = Invoke-RestMethod -Uri ($base + '/jobs/progress?jobId=' + $job.jobId) -TimeoutSec 5
            if ($progress.status -ne 'RUNNING') { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        $report.matrixTest = $progress
        $result = $progress.resultSummary.result
        $correct = $progress.status -eq 'COMPLETED' -and $null -ne $result -and $result.Count -eq $MatrixSize
        if ($correct) {
            for ($row = 0; $row -lt $MatrixSize; $row++) {
                if ($result[$row].Count -ne $MatrixSize) { $correct = $false; break }
                for ($col = 0; $col -lt $MatrixSize; $col++) {
                    $expected = $(if ($MatrixSize -eq 2) { 2 * $row + $col + 1 } else { 3 })
                    if ($null -eq $result[$row][$col] -or [Math]::Abs([double]$result[$row][$col] - $expected) -gt 0.000001) { $correct = $false }
                }
            }
        }
        $report.matrixTestPassed = $correct
        $report.matrixSize = $MatrixSize
        if ($MatrixSize -eq 2) { $report.expectedResult = @(@(1,2), @(3,4)) }
        if ($MatrixSize -gt 8) {
            # Report every Worker and the verification outcome without dumping 10,000 cells.
            $report.matrixTest = [ordered]@{
                jobId = $progress.jobId; status = $progress.status; error = $progress.error
                totalTasks = $progress.totalTasks; completedTasks = $progress.completedTasks
                waitingReason = $progress.waitingReason; workerFleet = $progress.workerFleet
                workerIds = $progress.resultSummary.workerIds; durationMs = $progress.durationMs
                checkedCells = $(if ($correct) { $MatrixSize * $MatrixSize } else { 0 })
            }
        }
        if ($progress.status -eq 'RUNNING') {
            $report.note = 'Job is still waiting. Check waitingReason, workerFleet.admissionReason and Master/Worker logs.'
            # Cancel only the diagnostic job, leaving user jobs untouched.
            Invoke-RestMethod -Uri ($base + '/jobs/cancel') -Method Post -ContentType 'application/json' -Body (@{jobId=$job.jobId} | ConvertTo-Json) -TimeoutSec 5 | Out-Null
        }
    }
} catch { $report.apiError = $_.Exception.Message }
$report | ConvertTo-Json -Depth 12
