param(
    [string]$CandidateRoot = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$CandidateRoot = (Resolve-Path $CandidateRoot).Path

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw "CONTRACT_TEST_FAILED: $Message" }
}

$expected = @(
    '001_preflight.sql',
    '002_event_contract.sql',
    '003_first_response_integrity.sql',
    '004_commercial_catalog_contract.sql',
    '005_record_answer_rpc.sql',
    '006_backend_only_boundary.sql',
    '007_record_answer_runtime_fix.sql',
    '008_record_answer_returning_alias_fix.sql',
    '009_record_event_idempotency_rpc.sql',
    '010_admin_grant_tickets.sql',
    'PRODUCT_METRICS_EXECUTION_PLAN.md',
    'README.md'
)
$actual = @(Get-ChildItem $CandidateRoot -File | Select-Object -ExpandProperty Name)
if (@(Compare-Object $expected $actual).Count -ne 0) {
    throw "CONTRACT_TEST_FAILED: candidate filename set differs from expected. Actual: $($actual -join ', ')"
}

$preflight = Get-Content -Raw (Join-Path $CandidateRoot '001_preflight.sql')
$first = Get-Content -Raw (Join-Path $CandidateRoot '003_first_response_integrity.sql')
$commercial = Get-Content -Raw (Join-Path $CandidateRoot '004_commercial_catalog_contract.sql')
$rpc = Get-Content -Raw (Join-Path $CandidateRoot '005_record_answer_rpc.sql')
$corrective = Get-Content -Raw (Join-Path $CandidateRoot '007_record_answer_runtime_fix.sql')
$finalCorrective = Get-Content -Raw (Join-Path $CandidateRoot '008_record_answer_returning_alias_fix.sql')
$eventWriter = Get-Content -Raw (Join-Path $CandidateRoot '009_record_event_idempotency_rpc.sql')
$boundary = Get-Content -Raw (Join-Path $CandidateRoot '006_backend_only_boundary.sql')
$plan = Get-Content -Raw (Join-Path $CandidateRoot 'PRODUCT_METRICS_EXECUTION_PLAN.md')
$readme = Get-Content -Raw (Join-Path $CandidateRoot 'README.md')

Assert-Contains $preflight 'PREFLIGHT ONLY' '001 header'
Assert-Contains $preflight 'rollback;' '001 must rollback'
Assert-Contains $preflight 'RANKING_REQUIRES_FIRST_RESPONSE' 'preflight ranking invariant'
Assert-Contains $preflight 'PARTIAL_EVENT_CONTRACT' 'preflight partial event detection'
Assert-Contains $preflight 'PARTIAL_TARIFF_TABLE' 'preflight partial tariff detection'
Assert-Contains $preflight 'EVENT_ROWS_BLOCK_MANDATORY_IDEMPOTENCY' 'preflight blocks unbackfilled historical events'
Assert-Contains $preflight "'respuestas_intento', 'opcion_seleccionada_id', 'uuid', 'YES'" 'production response nullability baseline'
Assert-Contains $preflight "'transacciones_cartera', 'saldo_anterior', 'int4', 'YES'" 'production wallet nullability baseline'

Assert-Contains $first 'ux_respuestas_first_response' 'first-response unique index'
Assert-Contains $first 'where es_primer_intento_pregunta is true' 'first-response predicate'
Assert-Contains $first 'respuestas_intento_ranking_requires_first_response' 'ranking check'
if ($first -match 'where[\s\S]{0,240}cuenta_para_ranking') { throw 'CONTRACT_TEST_FAILED: first-response index depends on ranking flag' }

Assert-Contains $commercial 'create table if not exists public\.tarifas_producto_comercial' 'normalized tariff table'
Assert-Contains $commercial 'unique \(producto_id, periodo_facturacion, fase_precio\)' 'tariff cardinality'
Assert-Contains $commercial 'productos_store' 'provider mapping separation'
if ($commercial -match 'proveedor_product_id|proveedor_price_id') { throw 'CONTRACT_TEST_FAILED: provider IDs duplicated in commercial catalogue' }

Assert-Contains $rpc 'security definer' 'RPC security definer'
Assert-Contains $rpc 'set search_path = ''''' 'empty security-definer search path'
Assert-Contains $rpc 'pg_advisory_xact_lock' 'cross-attempt serialization'
Assert-Contains $rpc 'get stacked diagnostics' 'scoped unique violation diagnostics'
Assert-Contains $rpc 'ux_respuestas_first_response' 'only known unique race is handled'
Assert-Contains $rpc 'v_cuenta_para_ranking and v_first' 'ranking requires first response'
Assert-Contains $rpc 'grant execute on function public\.record_answer.*service_role' 'service-role-only RPC grant'

Assert-Contains $corrective 'v_response_id uuid' 'corrective RPC output variables'
Assert-Contains $corrective 'into v_response_id, v_response_correct' 'corrective RPC qualified RETURNING capture'
if ($corrective -match 'return query insert into public\.respuestas_intento') { throw 'CONTRACT_TEST_FAILED: corrective RPC retains ambiguous return-query insert' }
Assert-Contains $finalCorrective 'insert into public\.respuestas_intento as response_row' 'final corrective target alias'
Assert-Contains $finalCorrective 'returning response_row\.id' 'final corrective qualified returning'
Assert-Contains $eventWriter 'create or replace function public\.record_event' 'idempotent event writer'
Assert-Contains $eventWriter 'EVENT_IDEMPOTENCY_CONFLICT' 'event conflict guard'
Assert-Contains $eventWriter 'grant execute on function public\.record_event' 'service-role event writer grant'

$eventContract = Get-Content -Raw (Join-Path $CandidateRoot '002_event_contract.sql')
Assert-Contains $eventContract 'alter column idempotency_key set not null' 'event idempotency is mandatory'
Assert-Contains $eventContract 'create unique index ux_eventos_app_idempotency_key' 'event idempotency is globally unique'
if ($eventContract -match 'where idempotency_key is not null') { throw 'CONTRACT_TEST_FAILED: event idempotency index is still partial' }

Assert-Contains $boundary 'revoke all on table public\.eventos_app from public, anon, authenticated' 'raw event mutation boundary'
Assert-Contains $boundary 'drop policy if exists eventos_app_insert_auth' 'obsolete event write policy removed'
Assert-Contains $boundary 'grant select on table public\.respuestas_intento to authenticated' 'own response read grant retained'

Assert-Contains $plan '005_record_answer_rpc\.sql' 'execution order RPC'
Assert-Contains $plan '006_backend_only_boundary\.sql' 'execution order boundary'
if ($plan.IndexOf('005_record_answer_rpc.sql') -gt $plan.IndexOf('006_backend_only_boundary.sql')) { throw 'CONTRACT_TEST_FAILED: execution order reversed' }
Assert-Contains $plan '007_record_answer_runtime_fix\.sql' 'corrective migration order'
Assert-Contains $readme 'corrective migration' 'README corrective migration state'
if ($readme -match 'not ready for a disposable dry-run|Local-only candidate') { throw 'CONTRACT_TEST_FAILED: README contains stale local-only status' }

Write-Output 'PRODUCT_METRICS_CANDIDATE_STATIC_CONTRACT: PASS'
