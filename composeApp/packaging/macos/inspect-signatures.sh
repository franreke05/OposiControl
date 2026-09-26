#!/bin/bash
# Imprime la firma de cada binario Mach-O de una .app: identificador, Team ID, flags
# (0x10000 = Hardened Runtime) y autoridad. Sirve para detectar firmas mezcladas
# (p. ej. lanzador ad-hoc con Hardened Runtime + libjli.dylib firmada por el
# proveedor del JDK con otro Team ID). Sólo lee; no modifica nada.
#
# Uso: inspect-signatures.sh /ruta/OposiControl.app
set -uo pipefail
APP="${1:?Uso: $0 /ruta/App.app}"

sig() {
  local f="$1" info
  info="$(codesign -d -vvv "$f" 2>&1)"
  local team flags auth
  team="$(sed -n 's/^TeamIdentifier=//p' <<<"$info")"
  flags="$(sed -n 's/^CodeDirectory v=[0-9a-f]* size=[0-9]* flags=\(0x[0-9a-f]*([^)]*)\).*/\1/p' <<<"$info")"
  auth="$(sed -n 's/^Authority=//p' <<<"$info" | head -1)"
  [ -n "$auth" ] || auth="$(grep -q 'Signature=adhoc' <<<"$info" && echo adhoc || (grep -q 'not signed' <<<"$info" && echo UNSIGNED || echo '?'))"
  printf '%-10s team=%-12s flags=%-28s %s\n' "$auth" "${team:-none}" "${flags:-?}" "${f#"$APP"/}"
}

echo "== Bundle"
sig "$APP"
echo "== Mach-O embebidos"
find "$APP/Contents" -type f -print0 | while IFS= read -r -d '' f; do
  file -b "$f" | grep -q 'Mach-O' && sig "$f"
done
MAIN="$APP/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"
echo "== Entitlements del lanzador"
codesign -d --entitlements - --xml "$MAIN" 2>/dev/null | plutil -p - 2>/dev/null || echo "(sin entitlements)"
echo "== Entorno del equipo"
sw_vers 2>/dev/null | tr '\n' ' '; echo
csrutil status 2>/dev/null || true
