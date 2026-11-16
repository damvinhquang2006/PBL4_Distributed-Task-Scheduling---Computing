param(
    [ValidateRange(1,65535)][int]$Port = 5000,
    [string]$BindAddress = '0.0.0.0',
    [ValidateSet('RESOURCE_AWARE','ROUND_ROBIN','LEAST_LOADED')][string]$Policy = 'RESOURCE_AWARE',
    [ValidateRange(1,86400)][int]$JobTimeoutSeconds = 300,
    [ValidateRange(5,3600)][int]$AttemptTimeoutSeconds = 60,
    [ValidateRange(5,86400)][int]$AttemptHardTimeoutSeconds = 180,
    [ValidateRange(1,300)][int]$QuarantineRestartSeconds = 10,
    [ValidateRange(0,100)][int]$RestartLimit = 5,
    [ValidateRange(1,300)][int]$MaxBackoffSeconds = 30,
    [string]$DataDirectory = (Join-Path $PSScriptRoot '../data/master-default'),
    [double]$CpuHigh = 85, [double]$CpuLow = 75,
    [double]$RamHigh = 90, [double]$RamLow = 80,
    [double]$HeapHigh = 85, [double]$HeapLow = 75,
    [string]$JarPath = (Join-Path $PSScriptRoot '../target/distributed-task-scheduling-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $JarPath)) { throw 'JAR missing. Run mvn package first, or supply -JarPath.' }
if ($DataDirectory.EndsWith('master-default')) { $DataDirectory = Join-Path (Split-Path -Parent $DataDirectory) ('master-' + $Port) }
$masterArgs = @('--port',$Port,'--bindHost',$BindAddress,'--policy',$Policy,'--jobTimeoutSeconds',$JobTimeoutSeconds,
    '--attemptTimeoutSeconds',$AttemptTimeoutSeconds,'--attemptHardTimeoutSeconds',$AttemptHardTimeoutSeconds,
    '--quarantineRestartSeconds',$QuarantineRestartSeconds,
    '--dataDir',$DataDirectory,'--cpuHigh',$CpuHigh,'--cpuLow',$CpuLow,
    '--ramHigh',$RamHigh,'--ramLow',$RamLow,'--heapHigh',$HeapHigh,'--heapLow',$HeapLow)
$supervisorArgs = @('-cp',$JarPath,'com.pbl4.app.ProcessSupervisor','--restartLimit',$RestartLimit,
    '--maxBackoffSeconds',$MaxBackoffSeconds,'--ownershipFile',(Join-Path $DataDirectory 'master-supervisor.lock'),
    '--','com.pbl4.app.MasterApp') + $masterArgs
& java @supervisorArgs
exit $LASTEXITCODE
