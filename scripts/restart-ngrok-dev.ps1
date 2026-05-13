param(
    [string]$Domain = "",
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

Get-Process ngrok -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1

$outLog = Join-Path $projectRoot "ngrok.out.log"
$errLog = Join-Path $projectRoot "ngrok.err.log"

$ngrokArgs = @("http")
if ($Domain.Trim().Length -gt 0) {
    $ngrokArgs += "--domain=$($Domain.Trim())"
}
$ngrokArgs += $Port.ToString()

Start-Process `
    -FilePath "ngrok" `
    -ArgumentList $ngrokArgs `
    -WindowStyle Hidden `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog
