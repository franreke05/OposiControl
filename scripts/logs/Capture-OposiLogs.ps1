[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Android", "Jvm", "Backend")]
    [string]$Source,
    [string]$Session = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [string[]]$Tags = @(
        "OC-UI",
        "OC-VM",
        "OC-REPO",
        "OC-NET",
        "OC-DIAG",
        "OC-UPLOAD",
        "OC-KTOR",
        "OC-SUPA"
    ),
    [string]$CorrelationId = "",
    [string]$RequestId = "",
    [ValidateRange(1, 100)]
    [int]$MaxFileSizeMb = 10,
    [ValidateRange(1, 20)]
    [int]$MaxFiles = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$allowedTags = @(
    "OC-UI",
    "OC-VM",
    "OC-REPO",
    "OC-NET",
    "OC-DIAG",
    "OC-UPLOAD",
    "OC-KTOR",
    "OC-SUPA"
)
$allowedKeys = @(
    "event",
    "layer",
    "screen",
    "operation",
    "correlationId",
    "requestId",
    "durationMs",
    "attempt",
    "count",
    "environment",
    "error_type",
    "http_method",
    "http_status",
    "mime_group",
    "result",
    "retryable",
    "route",
    "errorType"
)

if ($Session -notmatch "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$") {
    throw "Session must contain only 1-64 safe filename characters."
}
foreach ($identifier in @($CorrelationId, $RequestId)) {
    if ($identifier.Length -gt 0 -and $identifier -notmatch "^[A-Za-z0-9-]{1,64}$") {
        throw "CorrelationId and RequestId accept only 1-64 alphanumeric or hyphen characters."
    }
}
foreach ($tag in $Tags) {
    if ($tag -notin $allowedTags) {
        throw "Unsupported tag '$tag'. Allowed tags: $($allowedTags -join ', ')."
    }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$logRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot ".logs\dev"))
$sessionDirectory = [IO.Path]::GetFullPath((Join-Path $logRoot $Session))
$requiredPrefix = $logRoot.TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
) + [IO.Path]::DirectorySeparatorChar
if (-not $sessionDirectory.StartsWith($requiredPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Resolved session directory is outside .logs/dev."
}

New-Item -ItemType Directory -Force -Path $sessionDirectory | Out-Null
$sourceName = $Source.ToLowerInvariant()
$script:currentLogPath = Join-Path $sessionDirectory "$sourceName.log"
$script:maxBytes = $MaxFileSizeMb * 1MB
$script:includeFollowingStackFrames = $false
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Move-LogGeneration {
    $oldest = "$($script:currentLogPath).$MaxFiles"
    if (Test-Path -LiteralPath $oldest) {
        Remove-Item -LiteralPath $oldest -Force
    }
    for ($generation = $MaxFiles - 1; $generation -ge 1; $generation--) {
        $from = "$($script:currentLogPath).$generation"
        $to = "$($script:currentLogPath).$($generation + 1)"
        if (Test-Path -LiteralPath $from) {
            Move-Item -LiteralPath $from -Destination $to -Force
        }
    }
    if (Test-Path -LiteralPath $script:currentLogPath) {
        Move-Item -LiteralPath $script:currentLogPath -Destination "$($script:currentLogPath).1" -Force
    }
}

function Add-RotatingLine {
    param([Parameter(Mandatory = $true)][string]$Line)

    $content = $Line + [Environment]::NewLine
    $incomingBytes = [Text.Encoding]::UTF8.GetByteCount($content)
    if (Test-Path -LiteralPath $script:currentLogPath) {
        $currentBytes = (Get-Item -LiteralPath $script:currentLogPath).Length
        if ($currentBytes + $incomingBytes -gt $script:maxBytes) {
            Move-LogGeneration
        }
    }
    [IO.File]::AppendAllText($script:currentLogPath, $content, $utf8NoBom)
}

function Protect-LogValue {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $safe = $Value
    $safe = [Regex]::Replace($safe, "(?i)bearer\s+[a-z0-9._~+/-]+=*", "[REDACTED]")
    $safe = [Regex]::Replace(
        $safe,
        "eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+",
        "[REDACTED]"
    )
    $safe = [Regex]::Replace(
        $safe,
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}",
        "[REDACTED]"
    )
    $safe = [Regex]::Replace(
        $safe,
        "(?i)(password|token|secret|apikey|api_key|cookie|authorization)=([^\s]+)",
        '$1=[REDACTED]'
    )
    if ($Key -eq "route") {
        $safe = $safe.Split("?")[0]
        $safe = [Regex]::Replace(
            $safe,
            "/[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}(?=/|$)",
            "/{id}"
        )
        $safe = [Regex]::Replace($safe, "/[0-9]+(?=/|$)", "/{id}")
    }
    $safe = [Regex]::Replace($safe, "[^A-Za-z0-9_.:/{}\[\]-]", "_")
    if ($safe.Length -gt 160) {
        $safe = $safe.Substring(0, 160)
    }
    return $safe
}

function ConvertTo-SafeStructuredLine {
    param([Parameter(Mandatory = $true)][string]$Line)

    $tagPattern = "OC-(?:UI|VM|REPO|NET|DIAG|UPLOAD|KTOR|SUPA)"
    $match = [Regex]::Match($Line, "(?<tag>$tagPattern).*?(?<payload>event=[^\r\n]+)$")
    if (-not $match.Success) {
        return $null
    }

    $tag = $match.Groups["tag"].Value
    if ($tag -notin $Tags) {
        return $null
    }

    $payload = $match.Groups["payload"].Value
    $pairs = [Regex]::Matches(
        $payload,
        "(?:^|\s)(?<key>[A-Za-z][A-Za-z0-9_]*?)=(?<value>.*?)(?=\s+[A-Za-z][A-Za-z0-9_]*=|$)"
    )
    $safeParts = [Collections.Generic.List[string]]::new()
    foreach ($pair in $pairs) {
        $key = $pair.Groups["key"].Value
        if ($key -notin $allowedKeys) {
            continue
        }
        $value = Protect-LogValue -Key $key -Value $pair.Groups["value"].Value
        if ($key -eq "correlationId" -and $CorrelationId.Length -gt 0 -and $value -ne $CorrelationId) {
            return $null
        }
        if ($key -eq "requestId" -and $RequestId.Length -gt 0 -and $value -ne $RequestId) {
            return $null
        }
        $safeParts.Add("$key=$value")
    }

    if ($safeParts.Count -eq 0 -or -not $safeParts[0].StartsWith("event=")) {
        return $null
    }
    if ($CorrelationId.Length -gt 0 -and "correlationId=$CorrelationId" -notin $safeParts) {
        return $null
    }
    if ($RequestId.Length -gt 0 -and "requestId=$RequestId" -notin $safeParts) {
        return $null
    }

    return "$tag | $($safeParts -join ' ')"
}

function Add-SafeInputLine {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Line)

    $safeLine = ConvertTo-SafeStructuredLine -Line $Line
    if ($null -ne $safeLine) {
        Add-RotatingLine -Line $safeLine
        $script:includeFollowingStackFrames = $safeLine -match "\serrorType="
        return
    }

    if ($script:includeFollowingStackFrames) {
        $stackType = [Regex]::Match(
            $Line,
            "(?<kind>stacktrace|causedBy)=(?<type>[A-Za-z0-9_.$<>/-]+)"
        )
        if ($stackType.Success) {
            Add-RotatingLine -Line (
                "$($stackType.Groups['kind'].Value)=$($stackType.Groups['type'].Value)"
            )
            return
        }
        $frame = [Regex]::Match(
            $Line,
            '(?<frame>(?:at|Caused by:)\s+[A-Za-z0-9_.$:/<>()-]+)'
        )
        if ($frame.Success) {
            Add-RotatingLine -Line "stackFrame=$($frame.Groups['frame'].Value)"
            return
        }
    }
    $script:includeFollowingStackFrames = $false
}

$previousLocation = Get-Location
try {
    Set-Location $projectRoot
    switch ($Source) {
        "Android" {
            $adbArguments = @("logcat", "-v", "threadtime")
            foreach ($tag in $Tags) {
                $adbArguments += "${tag}:V"
            }
            $adbArguments += "*:S"
            & adb @adbArguments 2>&1 | ForEach-Object {
                Add-SafeInputLine -Line $_.ToString()
            }
        }
        "Jvm" {
            & (Join-Path $projectRoot "gradlew.bat") --no-daemon :composeApp:jvmRun 2>&1 |
                ForEach-Object { Add-SafeInputLine -Line $_.ToString() }
        }
        "Backend" {
            & (Join-Path $projectRoot "scripts\run-backend-dev.ps1") 2>&1 |
                ForEach-Object { Add-SafeInputLine -Line $_.ToString() }
        }
    }
}
finally {
    Set-Location $previousLocation
    Write-Host "Safe structured log capture: $script:currentLogPath"
}
