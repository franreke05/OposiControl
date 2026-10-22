#!/usr/bin/env bash
# Genera el panel de escritorio de PRODUCCION a juego con el Ktor de control que corre en el servidor, lo comprueba y
# deja el instalador .msi en el Escritorio. Con --instalar, ademas actualiza la instalacion local
# (%LOCALAPPDATA%\Programs\OposiControl, la del acceso directo), como hacia publicar-oposicontrol.sh.
#
# Uso (Git Bash):
#   bash scripts/publicar-panel-produccion.sh                 # solo el .msi en el Escritorio
#   bash scripts/publicar-panel-produccion.sh --instalar      # y actualiza tu instalacion (cierra antes OposiControl)
#   PANEL_REF=origin/develop bash scripts/publicar-panel-produccion.sh   # otra rama (solo cuando control corra esa)
#
# Por defecto construye `origin/develop`: desde el lanzamiento V1 (2026-10-22) es lo que corre en el Ktor de control
# (oposicontrol-backend:0bb7efe02086). Para otra rama: PANEL_REF=<ref>.
# La URL y la clave PUBLICA de Supabase se leen del .env del repo y nunca se imprimen; el script falla si el panel
# empaquetado lleva una clave secreta o no apunta a api.oposibots.es.
set -euo pipefail

REF="${PANEL_REF:-origin/develop}"
INSTALAR="${1:-}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$(dirname "$REPO")/OposiControl-PanelBuild"
INSTALL="${LOCALAPPDATA:-/c/Users/$USERNAME/AppData/Local}/Programs/OposiControl"
INSTALL="$(cygpath -u "$INSTALL" 2>/dev/null || echo "$INSTALL")"
DESKTOP="$(cygpath -u "$(powershell.exe -NoProfile -Command "[Environment]::GetFolderPath('Desktop')" | tr -d '\r')")"

env_value() { grep -E "^$1=" "$REPO/.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r'; }

echo "== 1/5 comprobaciones previas"
[ -f "$REPO/.env" ] || { echo "FALLO: falta $REPO/.env (SUPABASE_URL y SUPABASE_ANON_KEY)."; exit 1; }
SUPABASE_URL="$(env_value SUPABASE_URL)"; SUPABASE_ANON_KEY="$(env_value SUPABASE_ANON_KEY)"
[ -n "$SUPABASE_URL" ] && [ -n "$SUPABASE_ANON_KEY" ] || { echo "FALLO: SUPABASE_URL o SUPABASE_ANON_KEY vacias en .env."; exit 1; }
export SUPABASE_URL SUPABASE_ANON_KEY
if [ "$INSTALAR" = "--instalar" ]; then
  RUNNING="$(tasklist //FI "IMAGENAME eq OposiControlAdmin.exe" 2>/dev/null | grep -c "OposiControlAdmin.exe" || true)"
  [ "$RUNNING" -eq 0 ] || { echo "FALLO: OposiControl esta abierto. Cierralo y repite."; exit 1; }
fi
git -C "$REPO" fetch -q origin
SHA="$(git -C "$REPO" rev-parse --short=12 "$REF")"
VERSION="1.$(date +%y).$((10#$(date +%j) * 100 + 10#$(date +%H)))"
echo "ok: $REF = $SHA, version $VERSION"

echo "== 2/5 preparar el arbol de construccion ($BUILD_DIR)"
if [ -d "$BUILD_DIR/.git" ] || [ -f "$BUILD_DIR/.git" ]; then
  git -C "$BUILD_DIR" checkout -q --detach "$SHA"
else
  git -C "$REPO" worktree add -q --detach "$BUILD_DIR" "$SHA"
fi
[ -z "$(git -C "$BUILD_DIR" status --porcelain --untracked-files=no)" ] || { echo "FALLO: $BUILD_DIR tiene cambios; no se publica desde un arbol sucio."; exit 1; }

echo "== 3/5 construir (unos minutos)"
cd "$BUILD_DIR"
./gradlew :composeApp:createDistributable :composeApp:packageMsi -PoposiProduction=true -PoposiVersion="$VERSION" \
  -PoposiRelease="control-panel-$VERSION-$SHA" -q --console=plain
APP="$BUILD_DIR/composeApp/build/compose/binaries/main/app/OposiControlAdmin"
MSI="$(ls "$BUILD_DIR"/composeApp/build/compose/binaries/main/msi/OposiControlAdmin-"$VERSION".msi)"

echo "== 4/5 comprobar el panel empaquetado"
CFG="$APP/app/OposiControlAdmin.cfg"
grep -q "oposi.BASE_URL=https://api.oposibots.es" "$CFG" || { echo "FALLO: el panel no apunta a api.oposibots.es."; exit 1; }
grep -q "oposi.APP_ENV=prod" "$CFG" || { echo "FALLO: el panel no es de produccion."; exit 1; }
SECRETAS="$(grep -cE "service_role|sb_secret" "$CFG" || true)"
[ "$SECRETAS" -eq 0 ] || { echo "FALLO: el panel lleva una clave secreta. No se publica."; exit 1; }
cp "$MSI" "$DESKTOP/"
echo "ok: $DESKTOP/$(basename "$MSI")"

echo "== 5/5 instalacion local"
if [ "$INSTALAR" = "--instalar" ]; then
  mkdir -p "$INSTALL"
  # Los ficheros instalados son de solo lectura: se borran antes de copiar, no se sobrescriben.
  rm -rf "$INSTALL/OposiControlAdmin.exe" "$INSTALL/OposiControlAdmin.ico" "$INSTALL/app" "$INSTALL/runtime"
  cp -r "$APP"/. "$INSTALL"/
  [ -f "$INSTALL/OposiControlAdmin.exe" ] || { echo "FALLO: el ejecutable no quedo instalado."; exit 1; }
  echo "ok: instalado en $INSTALL (abre OposiControl desde el acceso directo)"
else
  echo "sin --instalar: tu instalacion no se toca"
fi
echo "Hecho: panel $VERSION ($SHA) a juego con el Ktor de control."
