#!/usr/bin/env bash
# Prueba de assert-clean-tree.sh (MASTER3-SEC-002) sobre un repo git temporal.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" || exit 1
git init -q . && git config user.email t@t && git config user.name t
mkdir -p backend/ktor/src/main/resources scripts
printf '.env\n*.db\nbuild/\n.gradle/\n' > .gitignore
echo x > backend/ktor/src/main/resources/application.conf
cp "$here/assert-clean-tree.sh" scripts/
git add -A && git commit -qm init
guard() { CLEAN_TREE_ROOT="$tmp" bash scripts/assert-clean-tree.sh 2>&1; }
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
echo "assert-clean-tree.test OK"
