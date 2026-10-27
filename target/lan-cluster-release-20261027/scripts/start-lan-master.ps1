param(
    [ValidateRange(1,65535)][int]$Port = 5000,
    [string]$BindAddress = '0.0.0.0',
    [ValidateSet('RESOURCE_AWARE','ROUND_ROBIN','LEAST_LOADED')][string]$Policy = 'RESOURCE_AWARE',
    [ValidateRange(1,86400)][int]$JobTimeoutSeconds = 300,
    [double]$CpuHigh = 85, [double]$CpuLow = 75,
    [double]$RamHigh = 90, [double]$RamLow = 80,
    [double]$HeapHigh = 85, [double]$HeapLow = 75,
    [string]$JarPath = (Join-Path $PSScriptRoot '../target/distributed-task-scheduling-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $JarPath)) { throw 'JAR missing. Run mvn package first, or supply -JarPath.' }
& java -cp $JarPath com.pbl4.app.MasterApp --port $Port --bindHost $BindAddress --policy $Policy --jobTimeoutSeconds $JobTimeoutSeconds --cpuHigh $CpuHigh --cpuLow $CpuLow --ramHigh $RamHigh --ramLow $RamLow --heapHigh $HeapHigh --heapLow $HeapLow
exit $LASTEXITCODE
