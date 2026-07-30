[CmdletBinding()]
param(
    [string]$Session = "",
    [string[]]$Tags = @(),
    [string]$CorrelationId = "",
    [string]$RequestId = "",
    [int]$MaxFileSizeMb = 10,
    [int]$MaxFiles = 5
)

$parameters = @{
    Source = "Jvm"
    MaxFileSizeMb = $MaxFileSizeMb
    MaxFiles = $MaxFiles
}
if ($Session.Length -gt 0) { $parameters.Session = $Session }
if ($Tags.Count -gt 0) { $parameters.Tags = $Tags }
if ($CorrelationId.Length -gt 0) { $parameters.CorrelationId = $CorrelationId }
if ($RequestId.Length -gt 0) { $parameters.RequestId = $RequestId }

& (Join-Path $PSScriptRoot "Capture-OposiLogs.ps1") @parameters
