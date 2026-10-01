#!/usr/bin/env bash
# Prueba de verify-no-direct-supabase.sh (GATE-DIRECT-SUPABASE): el repo real pasa y CADA violacion sembrada falla nombrando
# fichero:linea; los controles positivos (comentarios, pruebas, AuthApi con sus 3 llamadas) pasan.
set -uo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify-no-direct-supabase.sh >/dev/null || { echo "TEST FAIL: el repo real deberia pasar"; exit 1; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
AUTH=data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/AuthApi.kt
n=0
# Arbol minimo con SOLO AuthApi.kt y su lista de permitidos (AuthApi), sin las demas entradas del repo.
fresh() {
  n=$((n + 1)); w="$tmp/w$n"; mkdir -p "$w/$(dirname "$AUTH")" "$w/ops/inventory" "$w/composeApp/src/commonMain"
  cp "$AUTH" "$w/$AUTH"
  grep -E "^(#|HTTPCALL.${AUTH//\//.}|AUTH_V1.${AUTH//\//.}|APIKEY.${AUTH//\//.})" ops/inventory/direct-supabase-allowlist.tsv > "$w/ops/inventory/direct-supabase-allowlist.tsv"
}
run() { SCAN_ROOT="$w" bash scripts/verify-no-direct-supabase.sh >"$tmp/out" 2>&1; }
pass() { run || { echo "TEST FAIL: $1 deberia pasar"; cat "$tmp/out"; exit 1; }; }
expect_fail() { run && { echo "TEST FAIL: $1 paso"; exit 1; }; grep -q '^FAIL:' "$tmp/out" || { echo "TEST FAIL: $1 sin FAIL"; exit 1; }
  [ -z "${2:-}" ] || grep -q "$2" "$tmp/out" || { echo "TEST FAIL: $1 no nombra $2"; cat "$tmp/out"; exit 1; }; }
add() { printf '%s\n' "$2" >> "$w/$1"; }

# --- controles positivos
fresh; pass "arbol limpio con AuthApi (3 llamadas permitidas)"
fresh; add composeApp/src/commonMain/Ok.kt '// GET /rest/v1/examenes (solo un comentario)'; add composeApp/src/commonMain/Ok.kt '/* service_role y /storage/v1 en un bloque */ val ok = 1'; pass "menciones solo en comentarios"
fresh; mkdir -p "$w/data/src/jvmTest/kotlin"; add data/src/jvmTest/kotlin/MockTest.kt 'val u = "https://abc.supabase.co/rest/v1/x"'; pass "URL mock en src/jvmTest (las pruebas se excluyen)"
fresh; add composeApp/src/commonMain/Url.kt 'val u = "https://api.example.com//path" // /rest/v1'; pass "// dentro de una cadena y comentario real"

# --- controles negativos
fresh; add composeApp/src/commonMain/Foo.kt 'suspend fun f(client: C) = client.get("$url/rest/v1/users")'; expect_fail "/rest/v1" "Foo.kt:1"
fresh; add composeApp/src/commonMain/Foo.kt 'val p = "/storage/v1/object/x"'; expect_fail "/storage/v1" "Foo.kt:1"
fresh; add composeApp/src/commonMain/Foo.kt 'val p = "/functions/v1/x"'; expect_fail "/functions/v1" "Foo.kt:1"
fresh; add composeApp/src/commonMain/Foo.kt 'val k = "sb_secret_abc"'; expect_fail "sb_secret_ en Kotlin" "Foo.kt:1"
fresh; add composeApp/build.gradle.kts 'val k = "service_role"'; expect_fail "service_role en build.gradle.kts" "build.gradle.kts:1"
fresh; add "$AUTH" '    suspend fun x(c: C) = c.get("${requiredSupabaseUrl()}/auth/v1/admin/users")'; expect_fail "/auth/v1/admin/users extra en AuthApi" "AuthApi.kt"
fresh; add composeApp/src/commonMain/Other.kt 'val p = "/auth/v1/token?grant_type=password"'; expect_fail "/auth/v1/token en otro fichero" "Other.kt:1"
fresh; add composeApp/src/commonMain/Other.kt 'fun h(b: B) = b.header("apikey", k)'; expect_fail "header apikey en otro fichero" "Other.kt:1"
fresh; add composeApp/build.gradle.kts 'buildConfigField("String", "SUPABASE_SERVICE_ROLE_KEY", "\"x\"")'; expect_fail "buildConfigField SUPABASE_SERVICE" "build.gradle.kts:1"
fresh; add composeApp/gradle.properties 'url=https://abc.supabase.co/rest/v1'; expect_fail "host supabase.co en .properties" "gradle.properties:1"
fresh; add composeApp/src/commonMain/Foo.kt 'val t = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9"'; expect_fail "JWT incrustado" "Foo.kt:1"
fresh; add composeApp/src/commonMain/Foo.kt 'import io.github.jan.supabase.createSupabaseClient'; expect_fail "libreria cliente de Supabase" "Foo.kt:1"
fresh; add composeApp/src/commonMain/Foo.kt 'val b = "SUPABASE_BUCKET_X"'; expect_fail "SUPABASE_BUCKET fuera de la lista" "Foo.kt:1"
# la lista solo encoge: una llamada GoTrue de mas o de menos cambia el conteo fijado (3) y falla
fresh; add "$AUTH" '    suspend fun y(c: C) = c.post("${requiredSupabaseUrl()}/auth/v1/logout?scope=local")'; expect_fail "4a llamada GoTrue (conteo 3 fijado)" "AUTH_V1"
fresh; sed -i "/logout?scope=local/d" "$w/$AUTH"; expect_fail "una llamada GoTrue menos (conteo 3 fijado)" "fija 3"
fresh; printf 'SECRET\tcomposeApp/Gone.kt\t1\tmotivo\n' >> "$w/ops/inventory/direct-supabase-allowlist.tsv"; expect_fail "entrada permitida obsoleta" "obsoleta"
fresh; add composeApp/src/commonMain/Net.kt 'suspend fun g(client: C) = client.get("$base/api/v1/x")'; expect_fail "fichero HTTP nuevo fuera de la lista (HTTPCALL)" "HTTPCALL"
fresh; add composeApp/src/commonMain/Net.kt 'fun o(u: String) = URL(u).openStream()'; expect_fail "egreso no-Ktor nuevo (openStream)" "EGRESS_NONKTOR"
fresh; add composeApp/src/commonMain/Net.kt 'val c = HttpClient(CIO)'; expect_fail "segunda construccion de HttpClient" "EGRESS_CTOR"
fresh; add composeApp/src/commonMain/Net.kt 'val s = NSURLSession.sharedSession'; expect_fail "egreso nativo (NSURLSession)" "EGRESS_NONKTOR"
# un escaneo vacio no pasa
fresh; rm -rf "$w/data" "$w/composeApp"; expect_fail "escaneo de 0 ficheros" "ningun fichero"
echo "verify-no-direct-supabase.test OK ($n escenarios)"
