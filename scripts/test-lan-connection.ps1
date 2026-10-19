param(
    [Parameter(Mandatory)][string]$RemoteAddress,
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port
)
$ErrorActionPreference = 'Stop'
$result = Test-NetConnection -ComputerName $RemoteAddress -Port $Port -InformationLevel Detailed
$result | Select-Object ComputerName, RemoteAddress, RemotePort, SourceAddress, TcpTestSucceeded
if (!$result.TcpTestSucceeded) {
    Write-Error 'TCP failed: check IPv4, running Java listener, peer firewall and Wi-Fi client isolation.'
    exit 1
}
