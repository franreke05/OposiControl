param()

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

$envFile = Join-Path $projectRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            return
        }
        $name, $value = $line.Split("=", 2)
        $name = $name.Trim()
        $value = $value.Trim().Trim('"').Trim("'")
        if ($name.Length -gt 0) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

$localGradleHome = Join-Path $projectRoot ".gradle-test"
New-Item -ItemType Directory -Force -Path $localGradleHome | Out-Null
[Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", $localGradleHome, "Process")

& (Join-Path $projectRoot "gradlew.bat") --no-daemon :backend:ktor:run
