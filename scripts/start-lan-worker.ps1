param(
    [Parameter(Mandatory)][string]$MasterAddress,
    [Parameter(Mandatory)][string]$WorkerAddress,
    [string]$WorkerId = 'laptop-b-worker',
    [ValidateRange(1,65535)][int]$MasterPort = 5000,
    [ValidateRange(1,65535)][int]$WorkerPort = 5001,
    [string]$BindAddress = '0.0.0.0',
    [ValidateRange(1,1024)][int]$Capacity = [Math]::Max(1, [Environment]::ProcessorCount - 1),
    [switch]$DemoDelay,
    [string]$JarPath = (Join-Path $PSScriptRoot '../target/distributed-task-scheduling-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $JarPath)) { throw 'JAR missing. Copy the same built JAR to this laptop or supply -JarPath.' }
$javaArgs = @('-cp', $JarPath, 'com.pbl4.app.WorkerApp', '--id', $WorkerId, '--host', $WorkerAddress, '--port', $WorkerPort, '--masterHost', $MasterAddress, '--masterPort', $MasterPort, '--bindHost', $BindAddress, '--capacity', $Capacity)
if ($DemoDelay) { $javaArgs += '--demoDelay' }
& java @javaArgs
exit $LASTEXITCODE
