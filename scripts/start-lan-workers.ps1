param(
    [Parameter(Mandatory)][string]$MasterAddress,
    [Parameter(Mandatory)][string]$WorkerAddress,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9._-]{1,64}$')][string]$MachineId,
    [string]$MachineName = [Environment]::MachineName,
    [ValidateRange(1,1024)][int]$Count = 50,
    [ValidateRange(1,65535)][int]$StartPort = 5001,
    [ValidateRange(1,65535)][int]$MasterPort = 5000,
    [ValidateRange(1,1024)][int]$HostParallelism = [Math]::Max(1, [Environment]::ProcessorCount - 1),
    [ValidateRange(1,1024)][int]$Capacity = 1,
    [ValidateRange(1,50)][int]$Groups = 1,
    [ValidateRange(0,100)][int]$RestartLimit = 5,
    [ValidateRange(1,300)][int]$MaxBackoffSeconds = 30,
    [string]$BindAddress = '0.0.0.0',
    [switch]$StartPaused,
    [string]$JarPath = (Join-Path $PSScriptRoot '../target/distributed-task-scheduling-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'
if ($StartPort + $Count - 1 -gt 65535) { throw 'Worker port range exceeds 65535.' }
if (!(Test-Path -LiteralPath $JarPath)) { throw 'JAR missing. Copy the same newly built JAR to both laptops.' }
$localAddresses = @([Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | ForEach-Object {
    if ($_.OperationalStatus -eq [Net.NetworkInformation.OperationalStatus]::Up) {
        $_.GetIPProperties().UnicastAddresses | ForEach-Object { $_.Address.ToString() }
    }
})
if ($WorkerAddress -notin $localAddresses) { throw 'WorkerAddress is not an active IP of this laptop. Use an active address from ipconfig, including the Radmin VPN adapter address for a Radmin cluster.' }
if ($Groups -gt $Count -or $Groups -gt $HostParallelism) { throw 'Groups cannot exceed Worker count or laptop compute limit.' }
Write-Host ('Laptop: {0} ({1}) | Ports: {2}-{3} | Compute limit for entire laptop: {4}' -f $MachineId,$MachineName,$StartPort,($StartPort+$Count-1),$HostParallelism)
Write-Host ('JAR SHA256: ' + (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash)
Write-Host 'Wait for REGISTERED X/Y; opened ports alone do not confirm connection to Master.'
$javaArgs = @('-cp',$JarPath,'com.pbl4.app.WorkerProcessSupervisor',
    '--count',$Count,'--groups',$Groups,'--startPort',$StartPort,'--host',$WorkerAddress,'--bindHost',$BindAddress,
    '--masterHost',$MasterAddress,'--masterPort',$MasterPort,'--machineId',$MachineId,'--machineName',$MachineName,
    '--hostParallelism',$HostParallelism,'--capacity',$Capacity,'--restartLimit',$RestartLimit,'--maxBackoffSeconds',$MaxBackoffSeconds)
if ($StartPaused) {
    $javaArgs += '--startPaused'
    Write-Host 'Warm Standby enabled (-StartPaused): workers will register in PAUSED state until resumed from the Web UI.'
}
& java @javaArgs
exit $LASTEXITCODE
