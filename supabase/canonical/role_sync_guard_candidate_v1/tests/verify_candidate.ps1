param(
    [string]$CandidateRoot = (Join-Path $PSScriptRoot '..')
)

# ===========================================================================
# VERIFICADOR DE PAQUETE -- NO ES UNA PRUEBA SEMANTICA
# ===========================================================================
#
# Este fichero comprobaba invariantes de roles leyendo el TEXTO del SQL. No
# podia hacerlo, y cinco revisiones independientes lo demostraron: cada ronda
# encontro otra forma sintactica de dejar la guarda muerta con todas las
# expresiones regulares en verde -- `raise notice` en vez de `raise exception`,
# un `case when false` de señuelo, `exception when others then null`, una
# condicion imposible añadida al `if`, una segunda definicion en MAYUSCULAS que
# pisaba a la guardada, un `/*` dentro de un `--` que hacia que el propio
# limpiador de comentarios se comiera el neutralizador.
#
# La leccion no era «añade otra regex». Era que la autoridad estaba en el sitio
# equivocado.
#
# PRUEBA (y solo esto):
#   - integridad del paquete: los ficheros esperados estan y no sobra ninguno;
#   - candidate y rollback identificados;
#   - el SQL es transaccional de principio a fin;
#   - los bloques de preflight y la postcondicion de instalacion estan presentes;
#   - el rollback existe y el README lo lista;
#   - el estado declarado sigue siendo APPLY_PENDING_APPROVAL;
#   - no se ha colado una migracion del candidate en supabase/migrations;
#   - el SQL ejecutable es ASCII;
#   - la suite semantica que SI tiene autoridad sigue existiendo.
#
# NO PRUEBA:
#   - las invariantes de roles (ultimo superadministrador, cero roles);
#   - la semantica de canonicalizacion en ejecucion;
#   - la concurrencia;
#   - la visibilidad bajo RLS;
#   - el comportamiento del rollback;
#   - que un `raise` sea realmente abortivo.
#
# Todo eso lo demuestra `backend/dbtest`, que aplica ESTE MISMO fichero a un
# PostgreSQL real y desechable y comprueba el estado de las tablas.
# ===========================================================================

$ErrorActionPreference = 'Stop'
$CandidateRoot = (Resolve-Path $CandidateRoot).Path
$RepoRoot = (Resolve-Path (Join-Path $CandidateRoot '..\..\..')).Path

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw "CONTRACT_TEST_FAILED: $Message" }
}
function Count-Of([string]$Text, [string]$Pattern) {
    return ([regex]::Matches($Text, $Pattern, 'IgnoreCase')).Count
}

# --- 1. Integridad del paquete ---------------------------------------------

$expected = @(
    '001_admin_sync_roles_guard.sql',
    '002_rollback_admin_sync_roles.sql',
    'README.md'
)
$actualTop = @(Get-ChildItem $CandidateRoot -File | Select-Object -ExpandProperty Name)
if (@(Compare-Object $expected $actualTop).Count -ne 0) {
    throw "CONTRACT_TEST_FAILED: candidate filename set differs from expected. Actual: $($actualTop -join ', ')"
}
if (-not (Test-Path (Join-Path $CandidateRoot 'tests'))) {
    throw "CONTRACT_TEST_FAILED: the tests/ directory is missing"
}

$sql = Get-Content -Raw (Join-Path $CandidateRoot '001_admin_sync_roles_guard.sql')
$rollback = Get-Content -Raw (Join-Path $CandidateRoot '002_rollback_admin_sync_roles.sql')
$readme = Get-Content -Raw (Join-Path $CandidateRoot 'README.md')

# --- 2. Forma transaccional -------------------------------------------------
#
# Si el script no abre y cierra transaccion, un fallo a mitad deja la base en un
# estado intermedio. Esto es forma, no semantica: que ABORTE de verdad ante un
# ACL hostil lo demuestra `RoleSyncInstallationTest` contra PostgreSQL real.

Assert-Contains $sql '(?m)^begin;' 'the candidate must open a transaction'
Assert-Contains $sql '(?m)^commit;' 'the candidate must close its transaction'

# --- 3. Piezas presentes (sanity, NO garantia) ------------------------------
#
# Cuenta insensible a mayusculas a proposito: la version anterior usaba
# [regex]::Matches sin IgnoreCase y una segunda definicion escrita en MAYUSCULAS
# contaba como cero. Aun asi, la autoridad sobre QUE funcion queda instalada es
# `pg_proc` en la suite semantica, no este recuento.

$defGuard = Count-Of $sql 'create\s+or\s+replace\s+function\s+public\.admin_sync_roles'
if ($defGuard -ne 1) { throw "CONTRACT_TEST_FAILED: expected exactly 1 definition of admin_sync_roles, found $defGuard" }
$defHelper = Count-Of $sql 'create\s+or\s+replace\s+function\s+public\.admin_role_code_canonical'
if ($defHelper -ne 1) { throw "CONTRACT_TEST_FAILED: expected exactly 1 definition of admin_role_code_canonical, found $defHelper" }

# --- 4. Preflight y postcondicion PRESENTES --------------------------------
#
# Que esten. Que funcionen lo prueba la suite semantica.

Assert-Contains $sql 'aclexplode' 'the ACL preflight block must be present'
Assert-Contains $sql 'ROLE_SYNC_GUARD_UNEXPECTED_ACL' 'the ACL preflight must have its named abort code'
Assert-Contains $sql 'ROLE_SYNC_GUARD_NOT_INSTALLED' 'the install postcondition must be present'
Assert-Contains $sql 'row_security' 'the fail-closed RLS setting must be present'

# --- 5. Rollback ------------------------------------------------------------

Assert-Contains $rollback 'create\s+or\s+replace\s+function\s+public\.admin_sync_roles' 'the rollback must redefine the function'
Assert-Contains $rollback 'reabre' 'the rollback must state that reverting reopens the vulnerability'
Assert-Contains $readme '002_rollback_admin_sync_roles\.sql' 'the README must list the rollback'

# --- 6. Estado declarado ----------------------------------------------------

Assert-Contains $sql 'APPLY_PENDING_APPROVAL' 'the SQL file must declare its pending-approval status'

# --- 7. Ninguna migracion del candidate colada en MAIN ----------------------

$migrations = Join-Path $RepoRoot 'supabase\migrations'
if (Test-Path $migrations) {
    $colada = @(Get-ChildItem $migrations -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'admin_sync_roles_guard|role_sync_guard' })
    if ($colada.Count -ne 0) {
        throw "CONTRACT_TEST_FAILED: the candidate appears under supabase/migrations: $($colada.Name -join ', ')"
    }
}

# --- 8. SQL ejecutable en ASCII --------------------------------------------
#
# Un espacio Unicode invisible dentro de la clase de caracteres ya corrompio
# este fichero una vez. Se mira solo el SQL ejecutable: los comentarios llevan
# acentos a proposito.

$sqlNoBlock = [regex]::Replace($sql, '/\*.*?\*/', '', 'Singleline')
$sqlCode = (($sqlNoBlock -split "`n") | ForEach-Object { ($_ -split '--')[0] }) -join "`n"
$noAscii = [regex]::Matches($sqlCode, '[^\x00-\x7F]')
if ($noAscii.Count -ne 0) {
    throw "CONTRACT_TEST_FAILED: executable SQL must stay ASCII; found $($noAscii.Count) non-ASCII characters"
}

# --- 9. La autoridad semantica sigue existiendo ----------------------------
#
# Sin esto, borrar la suite de PostgreSQL dejaria este verificador como unica
# comprobacion y nadie lo notaria: volveria el problema que cinco revisiones
# tardaron en encontrar.

$dbtest = Join-Path $RepoRoot 'backend\dbtest\src\test\kotlin\com\oposicontrol\dbtest'
foreach ($requerido in @('PostgresHarness.kt', 'RoleSyncSemanticTest.kt', 'RoleSyncMutationTest.kt')) {
    if (-not (Test-Path (Join-Path $dbtest $requerido))) {
        throw "CONTRACT_TEST_FAILED: the semantic PostgreSQL suite is missing $requerido -- this verifier does NOT prove the invariants by itself"
    }
}

Write-Output 'PACKAGE_TEST_PASSED: role_sync_guard_candidate_v1'
Write-Output 'PROVES: package integrity, transactional shape, preflight/postcondition present, rollback present, declared state, no MAIN migration, ASCII executable SQL, semantic suite present'
Write-Output 'DOES_NOT_PROVE: role invariants, canonicalization runtime semantics, concurrency, RLS visibility, rollback behaviour, raise being abortive -- see backend/dbtest'
