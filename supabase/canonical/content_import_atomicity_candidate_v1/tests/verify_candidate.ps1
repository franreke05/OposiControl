param(
    [string]$CandidateRoot = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$CandidateRoot = (Resolve-Path $CandidateRoot).Path

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw "CONTRACT_TEST_FAILED: $Message" }
}

$expected = @(
    '001_admin_import_lote_preguntas.sql',
    'README.md'
)
$actualTop = @(Get-ChildItem $CandidateRoot -File | Select-Object -ExpandProperty Name)
if (@(Compare-Object $expected $actualTop).Count -ne 0) {
    throw "CONTRACT_TEST_FAILED: candidate filename set differs from expected. Actual: $($actualTop -join ', ')"
}

$sql = Get-Content -Raw (Join-Path $CandidateRoot '001_admin_import_lote_preguntas.sql')
$readme = Get-Content -Raw (Join-Path $CandidateRoot 'README.md')

# Never live-applied: no MAIN write markers, transaction-wrapped, service-role only.
Assert-Contains $sql 'APPLY_PENDING_APPROVAL' 'the SQL file must declare its pending-approval status'
Assert-Contains $sql '(?m)^begin;' 'the RPC must be wrapped in an explicit transaction'
Assert-Contains $sql '(?m)^commit;' 'the RPC must be wrapped in an explicit transaction'
Assert-Contains $sql 'security definer' 'the RPC must be security definer, matching house style (010_admin_grant_tickets.sql)'
Assert-Contains $sql "grant execute on function public\.admin_import_lote_preguntas\(uuid\)\s*\r?\n\s*to service_role;" 'execute must be granted to service_role only'
Assert-Contains $sql 'revoke execute on function public\.admin_import_lote_preguntas\(uuid\)' 'execute must be revoked from public/anon/authenticated'

# Atomicity: no per-row exception swallowing (the bug being fixed) inside the loop.
Assert-Contains $sql 'IMPORT_LOTE_NOT_FOUND' 'must raise a named exception for a missing lote (never silently no-op)'
Assert-Contains $sql "estado_final := case" 'must compute a single final estado for the whole lote'

# Duplicate handling contract: flagged for review, never auto-skip/auto-replace.
Assert-Contains $sql "duplicada_candidata" 'must flag duplicate candidates with a distinct status'
Assert-Contains $sql "TODO\(apply-review\)" 'the unresolved admin_create_question signature must stay marked as a pending TODO, not live SQL'

# README must be honest about not being applied anywhere.
Assert-Contains $readme 'APPLY_PENDING_APPROVAL' 'README must declare pending-approval status'
Assert-Contains $readme 'MAIN_WRITES: NONE' 'README must declare no MAIN writes were performed'

Write-Host 'CONTRACT_TEST_PASSED: content_import_atomicity_candidate_v1'
