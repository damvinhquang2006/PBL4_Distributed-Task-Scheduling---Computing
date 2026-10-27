param(
    [string]$MasterAddress = '127.0.0.1',
    [ValidateRange(1,65535)][int]$MasterPort = 5000,
    [ValidateRange(1,65535)][int]$HttpPort = 8080,
    [switch]$TestMatrix,
    [string]$JarPath = (Join-Path $PSScriptRoot '../target/distributed-task-scheduling-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'

function Test-TcpEndpoint([string]$Address, [int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($Address, $Port, $null, $null)
        if (!$pending.AsyncWaitHandle.WaitOne(2500)) { throw 'Connection timeout' }
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
    $report.status = Invoke-RestMethod -Uri ($base + '/status') -TimeoutSec 5
    $workers = Invoke-RestMethod -Uri ($base + '/workers') -TimeoutSec 5
    $report.workers = @($workers | ForEach-Object {
        [ordered]@{
            workerId = $_.workerId; host = $_.host; port = $_.port
            status = $_.status; paused = $_.paused; admissionReason = $_.admissionReason
            activeTasks = $_.activeTasks; capacity = $_.capacity; completedTasks = $_.completedTasks
            metrics = $_.metrics
            tcpFromThisLaptop = (Test-TcpEndpoint $_.host $_.port)
        }
    })
    if ($TestMatrix) {
        # A x I must equal A. This submits a real two-row matrix job to the current cluster.
        $body = @{
            operation = 'x'; rowsA = 2; colsA = 2; rowsB = 2; colsB = 2
            matrixA = @(@(1,2), @(3,4)); matrixB = @(@(1,0), @(0,1)); taskCount = 2
        } | ConvertTo-Json -Depth 5
        $job = Invoke-RestMethod -Uri ($base + '/compute/async') -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 10
        $report.testJobId = $job.jobId
        $deadline = (Get-Date).AddSeconds(15)
        do {
            $progress = Invoke-RestMethod -Uri ($base + '/jobs/progress?jobId=' + $job.jobId) -TimeoutSec 5
            if ($progress.status -ne 'RUNNING') { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        $report.matrixTest = $progress
        $report.expectedResult = @(@(1,2), @(3,4))
        if ($progress.status -eq 'RUNNING') {
            $report.note = 'Job is still waiting. Check waitingReason, workerFleet.admissionReason and Master/Worker logs.'
            # Cancel only the diagnostic job, leaving user jobs untouched.
            Invoke-RestMethod -Uri ($base + '/jobs/cancel') -Method Post -ContentType 'application/json' -Body (@{jobId=$job.jobId} | ConvertTo-Json) -TimeoutSec 5 | Out-Null
        }
    }
} catch { $report.apiError = $_.Exception.Message }
$report | ConvertTo-Json -Depth 12
