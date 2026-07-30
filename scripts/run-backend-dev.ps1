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

# Compatibility for existing local files while deployments migrate to the
# split publishable/secret key names. The value remains process-local.
if (
    [string]::IsNullOrWhiteSpace($env:SUPABASE_SECRET_KEY) -and
    -not [string]::IsNullOrWhiteSpace($env:SUPABASE_SERVICE_ROLE_KEY)
) {
    $env:SUPABASE_SECRET_KEY = $env:SUPABASE_SERVICE_ROLE_KEY
}
if ([string]::IsNullOrWhiteSpace($env:APP_ENV)) {
    $env:APP_ENV = "dev"
}

$localGradleHome = Join-Path $projectRoot ".gradle-test"
New-Item -ItemType Directory -Force -Path $localGradleHome | Out-Null
[Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", $localGradleHome, "Process")

& (Join-Path $projectRoot "gradlew.bat") --no-daemon :backend:ktor:run
