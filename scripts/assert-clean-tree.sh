#!/usr/bin/env bash
# Guarda de arbol limpio de build-backend-image.sh (MASTER2-CICD / MASTER3-SEC-002 / MASTER4-SEC-002).
# 1) `git status --porcelain` no debe listar nada (cambios ni archivos sin rastrear).
# 2) `git status --porcelain --ignored` sobre los arboles de codigo tampoco debe listar archivos IGNORADOS
#    (.env, *.db, *.keystore...): installDist empaqueta src/main/resources y un archivo local ignorado acabaria
#    en una imagen etiquetada con un SHA "limpio". Se toleran solo directorios de SALIDA (build/, .gradle/, .kotlin/)
#    y nunca dentro de un directorio `src/` (src/main/resources/build/ tambien se empaquetaria: MASTER4-SEC-002).
# Uso: assert-clean-tree.sh [raiz]. La raiz solo se acepta como ARGUMENTO (lo usa la prueba); no hay variable de
# entorno que pueda redirigir la guarda del script real hacia otro arbol (MASTER4-SEC-002).
set -euo pipefail
cd "${1:-$(dirname "$0")/..}"

if [ -n "$(git status --porcelain)" ]; then
  echo "ERROR: arbol de trabajo con cambios o archivos sin rastrear (installDist compilaria codigo no versionado); la etiqueta SHA no identificaria la imagen." >&2
  exit 1
fi

trees=()
for d in backend core domain data build-logic; do [ -e "$d" ] && trees+=("$d"); done
if [ "${#trees[@]}" -gt 0 ]; then
  all_ignored="$(git status --porcelain --ignored -- "${trees[@]}" | grep '^!! ' || true)"
  # No tolerado: (a) todo lo que no sea un directorio de salida build/.gradle/.kotlin, o (b) cualquier ignorado bajo src/.
  ignored="$( { printf '%s\n' "$all_ignored" | grep -Ev '^!! (.*/)?(build|\.gradle|\.kotlin)/$' || true
                printf '%s\n' "$all_ignored" | grep -E '^!! (.*/)?src/' || true; } | grep -v '^$' | sort -u || true)"
  if [ -n "$ignored" ]; then
    echo "ERROR: archivos ignorados por git dentro de los arboles de codigo (se empaquetarian en la imagen):" >&2
    echo "$ignored" >&2
    exit 1
  fi
fi
