#!/usr/bin/env bash
# Prueba de assert-clean-tree.sh (MASTER3-SEC-002) sobre un repo git temporal.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" || exit 1
git init -q . && git config user.email t@t && git config user.name t
mkdir -p backend/ktor/src/main/resources scripts
printf '.env\n*.db\nbuild/\n.gradle/\n.kotlin/\n' > .gitignore
echo x > backend/ktor/src/main/resources/application.conf
cp "$here/assert-clean-tree.sh" scripts/
git add -A && git commit -qm init
guard() { bash scripts/assert-clean-tree.sh "$tmp" 2>&1; }
guard >/dev/null || { echo "TEST FAIL: arbol limpio debe pasar"; exit 1; }
mkdir -p backend/ktor/build/install backend/.gradle
echo x > backend/ktor/build/install/a.jar; echo x > backend/.gradle/c
guard >/dev/null || { echo "TEST FAIL: build/ y .gradle/ ignorados deben tolerarse"; exit 1; }
echo secret > backend/ktor/src/main/resources/.env
out="$(guard)" && { echo "TEST FAIL: .env ignorado dentro de resources paso"; exit 1; }
echo "$out" | grep -q 'resources/.env' || { echo "TEST FAIL: el mensaje no nombra el archivo: $out"; exit 1; }
rm backend/ktor/src/main/resources/.env
echo x > backend/ktor/src/main/resources/data.db
guard >/dev/null && { echo "TEST FAIL: *.db ignorado paso"; exit 1; }
rm backend/ktor/src/main/resources/data.db
echo x > backend/untracked.txt
guard >/dev/null && { echo "TEST FAIL: archivo sin rastrear paso"; exit 1; }
rm backend/untracked.txt
echo y >> backend/ktor/src/main/resources/application.conf
guard >/dev/null && { echo "TEST FAIL: cambio sin commitear paso"; exit 1; }
sed -i '$d' backend/ktor/src/main/resources/application.conf   # deshace el cambio sin commitear de arriba
guard >/dev/null || { echo "TEST FAIL: tras deshacer el cambio debe pasar"; exit 1; }
# MASTER4-SEC-002: un build/ .gradle/ o .kotlin/ IGNORADO dentro de src/ se empaquetaria (installDist) y no se tolera.
for d in build .gradle .kotlin; do
  mkdir -p "backend/ktor/src/main/resources/$d"; echo x > "backend/ktor/src/main/resources/$d/leak.txt"
  out="$(guard)" && { echo "TEST FAIL: $d/ ignorado dentro de src/main/resources paso"; exit 1; }
  echo "$out" | grep -q "src/main/resources/$d/" || { echo "TEST FAIL: el mensaje no nombra src/main/resources/$d/: $out"; exit 1; }
  rm -rf "backend/ktor/src/main/resources/$d"
done
mkdir -p backend/ktor/src/test/build; echo x > backend/ktor/src/test/build/a
guard >/dev/null && { echo "TEST FAIL: build/ ignorado bajo src/test paso"; exit 1; }
rm -rf backend/ktor/src/test/build
guard >/dev/null || { echo "TEST FAIL: tras limpiar debe volver a pasar"; exit 1; }
# MASTER4-SEC-002: CLEAN_TREE_ROOT ya no redirige la guarda (solo el argumento): un arbol sucio ajeno no cambia el resultado.
otro="$(mktemp -d)"; trap 'rm -rf "$tmp" "$otro"' EXIT
( cd "$otro" && git init -q . && echo x > sucio.txt )
CLEAN_TREE_ROOT="$otro" bash scripts/assert-clean-tree.sh >/dev/null 2>&1 || { echo "TEST FAIL: CLEAN_TREE_ROOT redirigio la guarda"; exit 1; }
bash scripts/assert-clean-tree.sh "$otro" >/dev/null 2>&1 && { echo "TEST FAIL: el argumento no apunto al arbol sucio"; exit 1; }
echo "assert-clean-tree.test OK"
