#!/usr/bin/env bash
# Prueba de ops/deploy/release.sh con repos git falsos en un directorio temporal, sin red ni servidor.
# ssh/docker/scp/curl son stubs en el PATH que ANOTAN la llamada y fallan: cualquier invocacion indebida rompe la prueba.
set -uo pipefail
cd "$(dirname "$0")/.."
REL="$(pwd)/ops/deploy/release.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
for c in ssh docker scp curl; do
  printf '#!/usr/bin/env bash\necho "%s $*" >> "$STUB_LOG"\nexit 99\n' "$c" > "$tmp/bin/$c"
  chmod +x "$tmp/bin/$c"
done
export STUB_LOG="$tmp/stub.log"; : > "$STUB_LOG"
fail() { echo "TEST FAIL: $*"; exit 1; }

G() { git -c user.name=t -c user.email=t@t "$@"; }
# mkrepo <dir>: origin bare + clon en develop con migraciones y verificadores simulados que pasan.
mkrepo() {
  local d="$1"
  git init -q --bare "$d.git"
  git init -q -b develop "$d"
  git -C "$d" config core.autocrlf false
  mkdir -p "$d/scripts" "$d/supabase/migrations"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$d/scripts/verify-migrations-manifest.sh"
  cp "$d/scripts/verify-migrations-manifest.sh" "$d/scripts/verify-ops-config.sh"
  for v in 20261001100000_a 20261002100000_b 20261003100000_c; do echo "select 1;" > "$d/supabase/migrations/$v.sql"; done
  G -C "$d" add -A && G -C "$d" commit -q -m init
  G -C "$d" remote add origin "$d.git"
  G -C "$d" push -q origin develop
}
R="$tmp/repo"; mkrepo "$R"
pre() { RELEASE_ROOT="$R" PATH="$tmp/bin:$PATH" bash "$REL" preflight "$@" 2>&1; }

out="$(pre)" || fail "preflight deberia pasar en repo limpio: $out"
echo "$out" | grep -q "PREFLIGHT OK" || fail "sin PREFLIGHT OK: $out"
echo "$out" | grep -q "AVISO: sin --applied-list" || fail "deberia avisar sin lista"

# Pendientes: aplicada 001 y 003 -> pendiente solo 002 (anterior a la ultima aplicada, marcada).
printf '20261001100000\r\n20261003100000 | nombre\n' > "$tmp/applied.txt"
out="$(pre --applied-list "$tmp/applied.txt")" || fail "preflight con lista: $out"
echo "$out" | grep -q "20261002100000_b.sql   <-- ANTERIOR" || fail "no lista 002 como pendiente anterior: $out"
echo "$out" | grep -q "Total pendientes: 1" || fail "total pendiente incorrecto: $out"
echo "$out" | grep -q "20261001100000_a.sql" && fail "lista una ya aplicada"
# Aplicadas hasta 002 -> pendiente 003 sin marca.
printf '20261001100000\n20261002100000\n' > "$tmp/applied2.txt"
out="$(pre --applied-list "$tmp/applied2.txt")" || fail "preflight lista 2: $out"
echo "$out" | grep -q "^  20261003100000_c.sql$" || fail "003 deberia ser pendiente sin marca: $out"
# Todas aplicadas.
printf '20261001100000\n20261002100000\n20261003100000\n' > "$tmp/applied3.txt"
pre --applied-list "$tmp/applied3.txt" | grep -q "(ninguna)" || fail "deberia decir ninguna pendiente"
# Lista inexistente / sin versiones.
pre --applied-list "$tmp/no-existe" >/dev/null && fail "lista inexistente deberia fallar"
echo "basura" > "$tmp/bad.txt"; pre --applied-list "$tmp/bad.txt" >/dev/null && fail "lista sin versiones deberia fallar"

# Arbol sucio.
echo x > "$R/sucio.txt"
out="$(pre)" && fail "arbol sucio deberia fallar"
echo "$out" | grep -q "arbol de trabajo sucio" || fail "mensaje de arbol sucio: $out"
rm "$R/sucio.txt"

# .env rastreado.
echo K=v > "$R/.env"; G -C "$R" add .env; G -C "$R" commit -q -m env; G -C "$R" push -q origin develop
out="$(pre)" && fail ".env rastreado deberia fallar"
echo "$out" | grep -q ".env rastreado" || fail "mensaje .env: $out"
G -C "$R" rm -q .env; G -C "$R" commit -q -m noenv; G -C "$R" push -q origin develop

# HEAD adelantado respecto a origin.
echo y > "$R/extra.txt"; G -C "$R" add extra.txt; G -C "$R" commit -q -m local
out="$(pre)" && fail "HEAD != origin/develop deberia fallar"
echo "$out" | grep -q "no coincide" || fail "mensaje origin: $out"
G -C "$R" push -q origin develop

# Verificador roto.
printf '#!/usr/bin/env bash\nexit 1\n' > "$R/scripts/verify-ops-config.sh"; G -C "$R" add -A; G -C "$R" commit -q -m v; G -C "$R" push -q origin develop
out="$(pre)" && fail "verificador roto deberia fallar"
echo "$out" | grep -q "verify-ops-config.sh fallo" || fail "mensaje verificador: $out"

# Fuera de develop.
G -C "$R" checkout -q -b otra
out="$(pre)" && fail "fuera de develop deberia fallar"
echo "$out" | grep -q "se publica desde 'develop'" || fail "mensaje rama: $out"
G -C "$R" checkout -q develop
SHA="$(git -C "$R" rev-parse --short=12 HEAD)"

ship() { RELEASE_ROOT="$R" OPOSI_SSH_HOST="u@h.invalid" PATH="$tmp/bin:$PATH" bash "$REL" ship "$@" 2>&1; }
# Sin host: falla.
out="$(OPOSI_SSH_HOST= RELEASE_ROOT="$R" PATH="$tmp/bin:$PATH" bash "$REL" ship </dev/null 2>&1)" && fail "ship sin OPOSI_SSH_HOST deberia fallar"
# Sin la bandera: dry-run, exit 0, imprime el plan.
out="$(ship </dev/null)" || fail "dry-run deberia salir 0: $out"
echo "$out" | grep -q "DRY-RUN" || fail "sin DRY-RUN: $out"
echo "$out" | grep -q "redeploy-rolling.sh oposicontrol-backend:$SHA" || fail "plan sin imagen: $out"
# Con la bandera pero SHA incorrecto / vacio: rechaza.
out="$(echo deadbeef | ship --i-have-the-go)" && fail "SHA incorrecto deberia rechazar"
echo "$out" | grep -q "no coincide" || fail "mensaje SHA: $out"
ship --i-have-the-go </dev/null >/dev/null && fail "sin respuesta interactiva deberia rechazar"
ship --otra >/dev/null && fail "argumento desconocido deberia fallar"
# Nada de lo anterior pudo invocar ssh/docker/scp/curl.
[ ! -s "$STUB_LOG" ] || fail "se invoco un comando remoto/docker: $(cat "$STUB_LOG")"

# Con bandera y SHA correcto SI llega a ssh (el stub falla -> abortado antes de docker save).
out="$(echo "$SHA" | ship --i-have-the-go)" && fail "con stub ssh roto deberia fallar"
grep -q "^ssh -o BatchMode=yes" "$STUB_LOG" || fail "con confirmacion correcta deberia intentar ssh"
grep -q "^docker" "$STUB_LOG" && fail "docker no debe ejecutarse si ssh falla"

# verify exige URL; con URL llama a curl (stub devuelve vacio -> fallo).
RELEASE_ROOT="$R" PATH="$tmp/bin:$PATH" bash "$REL" verify >/dev/null 2>&1 && fail "verify sin URL deberia fallar"
OPOSI_API_URL=https://x.invalid RELEASE_ROOT="$R" PATH="$tmp/bin:$PATH" bash "$REL" verify >/dev/null 2>&1 && fail "verify con curl roto deberia fallar"
bash "$REL" >/dev/null 2>&1 && fail "sin subcomando deberia salir != 0"

echo "release.test.sh OK"
