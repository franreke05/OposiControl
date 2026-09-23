param(
    [string]$PackageRoot = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$PackageRoot = (Resolve-Path $PackageRoot).Path

$expected = @(
    '011_preflight.sql',
    '012_drop_redundant_attempt_index.sql',
    '013_least_privilege_grants.sql',
    '014_revoke_view_mutation_grants.sql',
    'DATABASE_REDESIGN_EXECUTION_PLAN.md',
    'README.md'
)

$actual = @(Get-ChildItem -LiteralPath $PackageRoot -File | Select-Object -ExpandProperty Name)
if (@(Compare-Object $expected $actual).Count -ne 0) {
    throw "DATABASE_REDESIGN_PACKAGE_FAILED: unexpected file set: $($actual -join ', ')"
}

$preflight = Get-Content -Raw (Join-Path $PackageRoot '011_preflight.sql')
$index = Get-Content -Raw (Join-Path $PackageRoot '012_drop_redundant_attempt_index.sql')
$grants = Get-Content -Raw (Join-Path $PackageRoot '013_least_privilege_grants.sql')
$views = Get-Content -Raw (Join-Path $PackageRoot '014_revoke_view_mutation_grants.sql')
$plan = Get-Content -Raw (Join-Path $PackageRoot 'DATABASE_REDESIGN_EXECUTION_PLAN.md')
$readme = Get-Content -Raw (Join-Path $PackageRoot 'README.md')

if ($preflight -notmatch 'PREFLIGHT ONLY' -or $preflight -notmatch 'rollback;') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: preflight contract' }
if ($index -notmatch 'idx_intentos_examen_usuario' -or $index -notmatch 'idx_intentos_usuario') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: index pair contract' }
if ($index -notmatch 'DATABASE_REDESIGN_INDEX_PRECONDITION_FAILED') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: index precondition' }
if ($grants -notmatch 'revoke insert, update, delete, truncate, references, trigger') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: grant revocation' }
if ($grants -notmatch 'roles && array\[''anon''\]::name\[\]') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: anonymous policy guard' }
if ($grants -notmatch 'roles && array\[''authenticated''\]::name\[\]') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: authenticated policy guard' }
if ($views -notmatch 'pg_views' -or $views -notmatch 'PUBLIC DERIVED VIEWS') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: view grant boundary' }
if ($plan -notmatch 'DEFER_RUNTIME_EVIDENCE' -or $plan -notmatch 'LEGACY_COMPAT_KEEP') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: classification matrix' }
if ($readme -match 'DROP TABLE|delete bucket|delete object') { throw 'DATABASE_REDESIGN_PACKAGE_FAILED: destructive scope' }

Write-Output 'DATABASE_REDESIGN_V1_STATIC_CONTRACT: PASS'
