#!/usr/bin/env bash
# GATE-DIRECT-SUPABASE: los clientes de OposiControl (composeApp, core, data, domain, features, iosApp + ficheros de build)
# NO acceden a Supabase para datos de negocio: solo el login/refresh/logout de GoTrue (identidad, no negocio) y SOLO desde
# data/.../remote/api/AuthApi.kt. Nada de /rest|/storage|/functions|/realtime|/graphql/v1, rpc, service_role/sb_secret_,
# JWT incrustados ni librerias cliente de Supabase. Tambien fija el EGRESO de los clientes: HttpClient( solo en ApiClientFactory,
# egreso no-Ktor (openConnection, NSURLSession, Sentry.init...) solo en la lista, y los ficheros que llaman a client.get/post/...
# (HTTPCALL, cuenta `*`) tambien: un fichero HTTP nuevo exige darlo de alta a proposito. El backend/ queda fuera (credenciales de servidor).
#
# Escaneo de CODIGO (comentarios y KDoc de .kt/.kts/.swift se eliminan; solo se excluyen los source sets de test exactos src/test y src/<x>Test, nunca paquetes como opositester o core/testing) con una lista
# de permitidos EXPLICITA y fijada por fichero y numero de lineas: ops/inventory/direct-supabase-allowlist.tsv
# (id<TAB>fichero<TAB>lineas<TAB>motivo). Una aparicion de mas, de menos o en otro fichero FALLA.
# SCAN_ROOT apunta a otro arbol (lo usa la prueba). Un escaneo de 0 ficheros FALLA (nunca vacuo). Nunca abre .env*.
set -uo pipefail
root="${SCAN_ROOT:-$(dirname "$0")/..}"
cd "$root" || exit 2
allow=ops/inventory/direct-supabase-allowlist.tsv
[ -f "$allow" ] || { echo "FAIL: falta $allow"; exit 1; }
dirs="${CLIENT_DIRS:-composeApp core data domain features iosApp build-logic}"
roots=""; for d in $dirs; do [ -d "$d" ] && roots="$roots $d"; done

files="$( { find $roots -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.swift' -o -name '*.properties' -o -name '*.xcconfig' \
    -o -name '*.plist' -o -name 'AndroidManifest.xml' -o -name '*.toml' -o -name '*.json' \) \
    -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.git/*' -not -path '*/node_modules/*' \
    -not -name '.env*' 2>/dev/null
  for f in build.gradle.kts settings.gradle.kts gradle.properties gradle/libs.versions.toml; do [ -f "$f" ] && echo "$f"; done; } | sort -u | grep -Ev '(^|/)src/(test|[A-Za-z0-9]*Test)/' )"
[ -n "$files" ] || { echo "FAIL: el escaneo no encontro ningun fichero (arbol vacio o CLIENT_DIRS erroneo)"; exit 1; }
nfiles="$(echo "$files" | wc -l | tr -d ' ')"

# id|ERE (awk). Se aplica sobre la linea SIN comentarios.
patterns='HOST|\.supabase\.(co|in|net)
DATA_API|/(rest|storage|functions|realtime|graphql)/v1
AUTH_ADMIN|/auth/v1/(admin|user|signup|otp|recover|verify|magiclink|resend|invite|sso|factors|reauthenticate)
AUTH_V1|/auth/v1
RPC|/rpc/
SECRET|service_role|SUPABASE_SERVICE|SUPABASE_SECRET|sb_secret_|sbp_
JWT|eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+
BUILDCFG|buildConfigField\(.*SUPABASE_(SERVICE|SECRET|DB|JWT)
BUCKET|SUPABASE_BUCKET
LIB|io\.github\.jan|supabase-kt|postgrest-kt|storage-kt|@supabase/supabase-js|createClient\(
APIKEY|["'"'"']apikey["'"'"']
EGRESS_CTOR|HttpClient\(
EGRESS_NONKTOR|openConnection\(|openStream\(|contentsOfURL|NSURLSession|HttpURLConnection|java\.net\.http|okhttp3|OkHttpClient|Sentry\.init|CredentialManager|ASAuthorizationController
HTTPCALL|(client|httpClient)\.(get|post|put|delete|patch|head|options|request|submitForm|submitFormWithBinaryData)\('

# Una pasada: quita comentarios (respetando cadenas) en codigo y emite "ID<TAB>fichero<TAB>linea<TAB>texto" por coincidencia.
hits="$(echo "$files" | tr '\n' '\0' | xargs -0 env PATS="$patterns" awk '
  BEGIN { np = split(ENVIRON["PATS"], P, "\n"); for (i = 1; i <= np; i++) { k = index(P[i], "|"); id[i] = substr(P[i], 1, k - 1); re[i] = substr(P[i], k + 1) } }
  FNR == 1 { inblk = 0; inraw = 0; code = (FILENAME ~ /\.(kt|kts|swift)$/) }
  {
    line = $0; out = ""; n = length(line); i = 1; instr = 0
    if (!code) { out = line; if (FILENAME ~ /\.properties$/ && line ~ /^[ \t]*[#!]/) out = "" }
    else while (i <= n) {
      c = substr(line, i, 1); c2 = substr(line, i, 2); c3 = substr(line, i, 3)
      if (inblk) { if (c2 == "*/") { inblk = 0; i += 2 } else i++; continue }
      if (inraw) { if (c3 == "\"\"\"") { inraw = 0; out = out c3; i += 3 } else { out = out c; i++ }; continue }
      if (instr) { if (c == "\\") { out = out c2; i += 2 } else { if (c == "\"") instr = 0; out = out c; i++ }; continue }
      if (c3 == "\"\"\"") { inraw = 1; out = out c3; i += 3; continue }
      if (c == "\"") { instr = 1; out = out c; i++; continue }
      if (c == "\047" && substr(line, i + 2, 1) == "\047") { out = out substr(line, i, 3); i += 3; continue }
      if (c2 == "//") break
      if (c2 == "/*") { inblk = 1; i += 2; continue }
      out = out c; i++
    }
    for (j = 1; j <= np; j++) if (out ~ re[j]) { gsub(/\t/, " ", out); print id[j] "\t" FILENAME "\t" FNR "\t" out }
  }')"

fail=0
# Cuenta real por (id, fichero) frente a la lista de permitidos.
actual="$(echo "$hits" | awk -F'\t' 'NF >= 3 { c[$1 "\t" $2]++ } END { for (k in c) print k "\t" c[k] }' | sort)"
allowed="$(grep -Ev '^[[:space:]]*(#|$)' "$allow" | awk -F'\t' '{ print $1 "\t" $2 "\t" $3 }' | sort)"
while IFS=$'\t' read -r id file cnt; do
  [ -n "$id" ] || continue
  want="$(echo "$allowed" | awk -F'\t' -v i="$id" -v f="$file" '$1 == i && $2 == f { print $3 }')"
  if [ -z "$want" ]; then
    echo "FAIL: $id fuera de la lista de permitidos en $file ($cnt linea(s)):"
    echo "$hits" | awk -F'\t' -v i="$id" -v f="$file" '$1 == i && $2 == f { print "  " $2 ":" $3 ": " $4 }'
    fail=1
  elif [ "$want" != "*" ] && [ "$want" != "$cnt" ]; then
    echo "FAIL: $id en $file: $cnt linea(s), la lista de permitidos fija $want:"
    echo "$hits" | awk -F'\t' -v i="$id" -v f="$file" '$1 == i && $2 == f { print "  " $2 ":" $3 ": " $4 }'
    fail=1
  fi
done <<< "$actual"
# Una entrada permitida que ya no aparece esta obsoleta (la lista solo puede encogerse).
while IFS=$'\t' read -r id file want; do
  [ -n "$id" ] || continue
  echo "$actual" | awk -F'\t' -v i="$id" -v f="$file" '$1 == i && $2 == f { found = 1 } END { exit !found }' \
    || { echo "FAIL: entrada permitida obsoleta (ya no aparece): $id $file"; fail=1; }
done <<< "$allowed"

# AuthApi.kt: SOLO los 3 literales de GoTrue permitidos; nada mas bajo /auth/v1 (p. ej. /auth/v1/admin/users).
authapi="$(echo "$files" | grep -E '/remote/api/AuthApi\.kt$' | head -1)"
if [ -n "$authapi" ]; then
  bad="$(echo "$hits" | awk -F'\t' -v f="$authapi" '$1 == "AUTH_V1" && $2 == f' \
    | grep -Ev '/auth/v1/token\?grant_type=(password|refresh_token)"|/auth/v1/logout\?scope=local"' || true)"
  [ -z "$bad" ] || { echo "FAIL: $authapi llama a /auth/v1 fuera de token(password|refresh_token) y logout(scope=local):"; echo "$bad"; fail=1; }
else
  echo "FAIL: no se encontro data/.../remote/api/AuthApi.kt (el unico fichero con acceso GoTrue)"; fail=1
fi

[ "$fail" = 0 ] && echo "ok:   sin acceso directo a Supabase en los clientes ($nfiles ficheros; solo GoTrue token/logout en AuthApi.kt)"
exit "$fail"
