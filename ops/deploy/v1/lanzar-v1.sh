#!/usr/bin/env bash
# Lanzamiento V1 a produccion (bot-1, bot-2 y control) guiado por fases. Sigue docs/engineering/V1_PRODUCTION_DEPLOY_PLAN.md:
# este script NO sustituye al plan, lo ejecuta en orden con comprobaciones y una confirmacion tecleada en cada paso que escribe.
#
# Lo ejecuta el PROPIETARIO en Git Bash, desde un checkout LIMPIO del SHA aprobado (el que pasaron gate y ensayo, F0.3):
#   export V1_SHA=<sha12 aprobado>                       # obligatorio: el script se niega con otro arbol
#   export MAIN_DB_URL='postgresql://...session pooler...'   # solo para backup / ventana / relleno; nunca se imprime
#   llave SSH cargada en el agente de Windows (ssh-add)
#
# Fases (en este orden; cada una se puede repetir):
#   comprobar   F2.1-F2.3  capacidad del VPS, redeploy-rolling.sh, nombres de variables de entorno     (solo lectura)
#   caddy       F2.2b      Caddy del host sin expulsion pasiva (copia, diff, sudo, validate, reload)     (escribe, GO-2b)
#   imagen      F2.4-F2.5  construir la imagen del SHA, comprobarla y cargarla en el servidor SIN arrancar
#   backup      F3.2       volcado logico de MAIN a ~/.oposibots-gate/backups/ con SHA256SUMS          (lee MAIN)
#   ventana     F4         parar los 3 Ktor, volcado delta, migraciones una a una, relleno, VERIFY       (escribe MAIN, GO-4)
#   arrancar    F5         compose del SHA en el servidor, BACKEND_IMAGE, CONTROL_IMAGE vacia, 3 Ktor uno a uno
#   humo        F6.1       salud, borde y versiones                                                      (solo lectura)
#   imagenes-anteriores    R-0/R-1: vuelve a bot cfa2e14afc8e y control d922357e84d3 (SOLO si MAIN no tiene migraciones V1
#                          o ya se deshicieron: con migraciones V1 aplicadas la imagen vieja NO funciona; ver seccion 7 del plan)
set -euo pipefail

FASE="${1:-}"
HOST="${OPOSI_SSH_HOST:-opsadmin@49.12.232.217}"
API="${OPOSI_API_URL:-https://api.oposibots.es}"
SSH_BIN="${OPOSI_SSH_BIN:-/c/Windows/System32/OpenSSH/ssh.exe}"; [ -x "$SSH_BIN" ] || SSH_BIN=ssh
PG_IMAGE="public.ecr.aws/supabase/postgres:17.6.1.171"
ENV_DIR='~/oposibots-stack/env'
OLD_BOT="oposicontrol-backend:cfa2e14afc8e"
OLD_CONTROL="oposicontrol-backend:d922357e84d3"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO"

r()  { "$SSH_BIN" -o BatchMode=yes "$HOST" "$@"; }          # remoto, sin terminal
rt() { "$SSH_BIN" -t "$HOST" "$@"; }                         # remoto con terminal (sudo pide contrasena)
psql_main() { MSYS_NO_PATHCONV=1 docker run --rm -i -e MAIN_DB_URL "$PG_IMAGE" sh -c 'exec psql "$MAIN_DB_URL" -X -v ON_ERROR_STOP=1 "$@"' sh "$@"; }
titulo() { echo; echo "======== $* ========"; }
confirmar() { # <texto que hay que teclear> <que va a pasar>
  echo; echo ">> $2"; printf ">> Escribe %s para seguir (otra cosa cancela): " "$1"; local t; IFS= read -r t
  [ "$t" = "$1" ] || { echo "Cancelado: no se ha hecho nada en este paso."; exit 1; }
}
need_sha() {
  : "${V1_SHA:?define V1_SHA=<sha12 aprobado en F0.3>}"
  [ "$(git rev-parse --short=12 HEAD)" = "$V1_SHA" ] || { echo "ERROR: este arbol esta en $(git rev-parse --short=12 HEAD), no en V1_SHA=$V1_SHA."; exit 1; }
  [ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "ERROR: el arbol tiene cambios; se despliega solo desde un checkout limpio."; exit 1; }
  IMG="oposicontrol-backend:$V1_SHA"
}
need_db() { : "${MAIN_DB_URL:?define MAIN_DB_URL (session pooler de MAIN) en esta terminal}"; }
compose_cmd() { # imprime la orden compose del servidor a partir de las etiquetas del contenedor de control
  r 'c=oposibots-ktor-control-1; L() { docker inspect $c --format "{{index .Config.Labels \"$1\"}}"; }
     dir=$(L com.docker.compose.project.working_dir); files=$(L com.docker.compose.project.config_files)
     proj=$(L com.docker.compose.project); envf=$(L com.docker.compose.project.environment_file)
     a="-p $proj"; for f in $(echo "$files" | tr , " "); do a="$a -f $f"; done
     for e in $(echo "$envf" | tr , " "); do a="$a --env-file $e"; done; echo "cd $dir && docker compose $a"'
}
esperar_sano() { # <contenedor>
  local st=desconocido i; for i in $(seq 1 36); do
    st=$(r "docker inspect $1 --format '{{.State.Health.Status}}'" 2>/dev/null || echo ausente); echo "  $1: $st"
    [ "$st" = healthy ] && return 0; sleep 5; done; return 1
}

case "$FASE" in
comprobar)
  need_sha
  titulo "F2.1 capacidad del VPS (aborta si: nproc<4, available<1500 MB, libre<5 GB, algun contenedor unhealthy)"
  r 'nproc; free -m | awk "/Mem:/{print \"available MB:\", \$7}"; df -h / /var/lib/docker | tail -n +2; uptime; docker ps --format "{{.Names}} {{.Image}} {{.Status}}"'
  titulo "F2.2 redeploy-rolling.sh del servidor (pegalo a OposiControl si cambia)"
  r 'test -f ~/redeploy-rolling.sh && sha256sum ~/redeploy-rolling.sh || echo "no existe ~/redeploy-rolling.sh"'
  titulo "F2.3 variables de entorno: solo NOMBRES que faltan respecto a los .example de $V1_SHA"
  for p in common bot control; do
    falta=""
    for k in $(grep -oE '^[A-Z_][A-Z0-9_]*=' "ops/deploy/backend.$p.env.example" | tr -d =); do
      r "grep -qE '^$k=' $ENV_DIR/backend.$p.env" || falta="$falta $k"
    done
    echo "backend.$p.env -> faltan:${falta:- ninguna}"
  done
  echo "(Las opcionales pueden faltar. Obligatorias V1: bot AUTH_RATE_LIMIT_MODE=valkey, AUTH_PKCE_MODE=required;"
  echo " control ADMIN_RATE_LIMIT_MODE=valkey, ADMIN_RATE_LIMIT_VALKEY_URL, ADMIN_RATE_LIMIT_HMAC_SECRET >= 32 y distinto del de bot.)"
  titulo "Interruptores (valores no secretos) y PAYMENTS_SANDBOX_SECRET"
  r "cd $ENV_DIR && grep -hE '^(APP_ENV|AUTH_PKCE_MODE|AUTH_RATE_LIMIT_MODE|ADMIN_RATE_LIMIT_MODE|PAYMENTS_STORE_LIVE_CREDIT_ENABLED|PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED|PAYMENTS_STORE_GOOGLE_PLAY_ENABLED|PAYMENTS_STORE_APP_STORE_ENABLED|PAYMENTS_STORE_SANDBOX|PAYMENTS_REFUNDS_ENABLED)=' backend.*.env | sort; \
     if grep -qE '^PAYMENTS_SANDBOX_SECRET=.+' backend.*.env compose.env 2>/dev/null; then echo 'ERROR: PAYMENTS_SANDBOX_SECRET tiene valor (el proceso saldria con 78)'; else echo 'ok: PAYMENTS_SANDBOX_SECRET ausente o vacia'; fi"
  echo "Esperado (seccion 9): APP_ENV=production, AUTH_PKCE_MODE=required, *_RATE_LIMIT_MODE=valkey, PAYMENTS_*_ENABLED=false, PAYMENTS_STORE_SANDBOX=reject."
  ;;

caddy)
  need_sha
  titulo "F2.2b Caddy del host: pool bot sin max_fails/fail_duration (compatible con la imagen vieja)"
  "$SSH_BIN" -o BatchMode=yes "$HOST" 'cat > /tmp/Caddyfile.v1' < ops/caddy/Caddyfile.hostcaddy
  echo "Diferencias entre /etc/caddy/Caddyfile y el del SHA (revisalas: solo deberia cambiar la salud del pool bot y los topes de cuerpo):"
  rt 'sudo diff /etc/caddy/Caddyfile /tmp/Caddyfile.v1 || true'
  confirmar CADDY "copiar /etc/caddy/Caddyfile a .bak-<fecha>, instalar el del SHA, validar y recargar (si no valida, se restaura)"
  rt 'set -e; b=/etc/caddy/Caddyfile.bak-$(date +%Y%m%d%H%M); sudo cp -a /etc/caddy/Caddyfile $b
      sudo cp /tmp/Caddyfile.v1 /etc/caddy/Caddyfile
      if sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile; then sudo systemctl reload caddy; echo "ok: recargado (copia $b)";
      else sudo cp -a $b /etc/caddy/Caddyfile; echo "ERROR: no valida; restaurado $b"; exit 1; fi
      echo "max_fails/fail_duration restantes: $(sudo grep -c -E "max_fails|fail_duration" /etc/caddy/Caddyfile || true)"'
  curl -s -o /dev/null -w "health/live -> %{http_code}\n" "$API/health/live"
  ;;

imagen)
  need_sha
  titulo "F2.4 construir oposicontrol-backend:$V1_SHA con el script oficial"
  bash scripts/build-backend-image.sh | tail -2
  local_jar="$(sha256sum backend/ktor/build/install/ktor/lib/ktor.jar | cut -c1-64)"
  img_jar="$(docker run --rm --entrypoint sh "$IMG" -c 'sha256sum /app/lib/ktor.jar' | cut -c1-64)"
  [ "$local_jar" = "$img_jar" ] || { echo "ERROR: el jar de la imagen no es el recien construido."; exit 1; }
  v1="$(unzip -l backend/ktor/build/install/ktor/lib/ktor.jar | grep -c -E 'StorePaymentDecisions|ServerClock' || true)"
  [ "$v1" -gt 0 ] || { echo "ERROR: el jar no trae codigo V1."; exit 1; }
  docker run --rm --entrypoint sh "$IMG" -c 'test "$(id -un)" = app && ! touch /app/x 2>/dev/null && touch /tmp/x' || { echo "ERROR: usuario o permisos de la imagen."; exit 1; }
  echo "ok: jar $img_jar (anotalo en el registro, seccion 11)"
  titulo "F2.5 cargar la imagen en el servidor SIN arrancarla"
  if r "docker image inspect $IMG >/dev/null 2>&1"; then echo "ya estaba"; else docker save "$IMG" | gzip | "$SSH_BIN" -o BatchMode=yes "$HOST" 'gunzip | docker load' | tail -1; fi
  srv_jar="$(r "docker run --rm --entrypoint sh $IMG -c 'sha256sum /app/lib/ktor.jar'" | cut -c1-64)"
  [ "$srv_jar" = "$img_jar" ] || { echo "ERROR: el jar en el servidor no coincide."; exit 1; }
  echo "ok: $IMG en el servidor, sin arrancar. Imagenes viejas que NO se borran: $OLD_BOT y $OLD_CONTROL"
  ;;

backup)
  need_sha; need_db
  BK="$HOME/.oposibots-gate/backups/main-ndiooyyqtaeysnedywer-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$BK"
  titulo "F3.2 volcado logico de MAIN en $BK"
  echo "Si el CLI pide sesion: npx supabase@2.120.0 login (una vez) y link --project-ref ndiooyyqtaeysnedywer."
  ( cd "$BK" && npx --yes supabase@2.120.0 db dump --linked --role-only -f roles.sql \
      && npx --yes supabase@2.120.0 db dump --linked -f schema.sql \
      && npx --yes supabase@2.120.0 db dump --linked --data-only --use-copy -f data.sql )
  MSYS_NO_PATHCONV=1 docker run --rm -e MAIN_DB_URL -v "$BK:/bk" "$PG_IMAGE" sh -c 'pg_dump "$MAIN_DB_URL" -Fc --schema=supabase_migrations -f /bk/migrations_history.dump'
  psql_main -At < docs/engineering/v1-deploy/03_backup_auth_triggers_readonly.sql > "$BK/auth_triggers.sql"
  psql_main -A -F '|' < docs/engineering/v1-deploy/04_backup_storage_policies_readonly.sql > "$BK/storage_policies.txt"
  ( cd "$BK" && sha256sum * > SHA256SUMS && ls -l )
  echo "Hora UTC del volcado (RPO de R-4): $(date -u +%FT%TZ)  ruta: $BK"
  ;;

ventana)
  need_sha; need_db
  bash scripts/verify-migrations-manifest.sh >/dev/null || { echo "ERROR: el manifiesto no cuadra con este arbol."; exit 1; }
  titulo "F4 ventana de mantenimiento (tope 30 min desde F4.1; a los 15 min se decide seguir o R-2)"
  confirmar PARAR "F4.1 parar los TRES Ktor (Caddy dara 502 en toda la API) y copiar el entorno"
  r 'docker ps --format "{{.Names}} {{.Image}}" | tee ~/v1-deploy-ps-antes.txt; cp -a ~/oposibots-stack/env ~/oposibots-stack/env.bak-$(date +%Y%m%d%H%M)'
  r 'docker stop oposibots-ktor-bot-1-1 oposibots-ktor-bot-2-1 oposibots-ktor-control-1'
  echo "Inicio de la ventana (UTC): $(date -u +%FT%TZ)"; curl -s -o /dev/null -w "health/live -> %{http_code} (esperado 502)\n" "$API/health/live"
  titulo "F4.2 volcado delta OBLIGATORIO (con los Ktor parados)"
  bash "$REPO/ops/deploy/v1/lanzar-v1.sh" backup
  titulo "F4.3 migraciones una a una (el script pide teclear cada version; si una falla, se para todo)"
  # El manifiesto se lee por el descriptor 3: la entrada estandar queda para las confirmaciones tecleadas.
  while read -r version _blob _pred clase <&3; do
    case "$clase" in apply|apply-or-record) ;; *) continue ;; esac
    f="$(ls supabase/migrations/${version}_*.sql)"
    echo; echo "---- $f ($clase)"
    if [ "$clase" = apply-or-record ] && [ "$(echo "select count(*) from supabase_migrations.schema_migrations where version='$version';" | psql_main -At)" = 1 ]; then
      echo "ya registrada: se salta"; continue; fi
    bash ops/deploy/v1/apply-main-migration.sh "$f"
    if [ "$version" = 20261022103400 ]; then
      titulo "F4.3b relleno de la penalizacion racional (D-11 = SI)"
      psql_main < docs/engineering/v1-deploy/05_penalty_backfill_count_readonly.sql
      printf ">> candidatos (N) del recuento anterior: "; IFS= read -r N
      printf ">> UUID del admin propietario (actor de auditoria): "; IFS= read -r ADMIN
      [[ "$N" =~ ^[0-9]+$ ]] && [[ "$ADMIN" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "ERROR: N o UUID no validos; no se ha tocado nada."; exit 1; }
      confirmar RELLENO "actualizar exactamente $N examenes oficiales (si no son $N, se deshace todo)"
      sed -e "s/<ADMIN_UUID>/$ADMIN/g" -e "s/<N>/$N/g" docs/engineering/v1-deploy/06_penalty_backfill_apply.sql | psql_main
      psql_main < docs/engineering/v1-deploy/07_penalty_backfill_postcheck_readonly.sql
    fi
  done 3< <(grep -vE '^[[:space:]]*(#|$)' ops/deploy/v1/main-migrations.manifest)
  titulo "D-10: registrar sin ejecutar las 4 ya aplicadas a mano el 2026-10-22"
  for v in 20261023100000 20261023100300 20261023100400 20261023100500; do
    bash ops/deploy/v1/apply-main-migration.sh --record-only "$(ls supabase/migrations/${v}_*.sql)"
  done
  titulo "F4.4 VERIFY de todas y estado de la cartera"
  for f in $(ls supabase/migrations/2026102[0-3]*.sql | grep -v 20261023100100); do bash ops/deploy/v1/apply-main-migration.sh --verify-only "$f"; done 2>&1 | tee "$HOME/v1-verify-despues-$(date +%Y%m%d%H%M).txt" | grep -E "VERIFY"
  echo "select (select count(*) from public.app_wallet_reconcile(null)) as reconcile_debe_ser_0, (select count(*) from public.transacciones_cartera where referencia_tipo = 'legacy_opening') as aperturas_debe_ser_0;" | psql_main
  echo "Si alguna VERIFY no es 0 o el reconcile no es 0: NO-GO, seccion 7 del plan. Si todo esta bien: bash $0 arrancar"
  ;;

arrancar)
  need_sha
  titulo "F5 compose del SHA en el servidor e imagen V1 en los tres Ktor (control primero)"
  C="$(compose_cmd)"; dir="$(echo "$C" | sed -E 's/^cd ([^ ]+) .*/\1/')"
  echo "Servidor: $C"
  confirmar ARRANCAR "copiar compose.production.yaml y compose.hostcaddy.override.yaml del SHA a $dir/ops/deploy (con .bak), poner BACKEND_IMAGE=$IMG y CONTROL_IMAGE= en compose.env, y arrancar control, bot-1 y bot-2 uno a uno"
  ts=$(date +%Y%m%d%H%M)
  for f in compose.production.yaml compose.hostcaddy.override.yaml; do
    r "cp -a $dir/ops/deploy/$f $dir/ops/deploy/$f.bak-$ts"
    "$SSH_BIN" -o BatchMode=yes "$HOST" "cat > $dir/ops/deploy/$f" < "ops/deploy/$f"
  done
  r "cd $ENV_DIR && cp -a compose.env compose.env.bak-$ts && sed -i -e 's|^BACKEND_IMAGE=.*|BACKEND_IMAGE=$IMG|' compose.env && \
     { grep -q '^CONTROL_IMAGE=' compose.env && sed -i 's|^CONTROL_IMAGE=.*|CONTROL_IMAGE=|' compose.env || echo 'CONTROL_IMAGE=' >> compose.env; } && grep -E '^(BACKEND_IMAGE|CONTROL_IMAGE)=' compose.env"
  for svc in ktor-control ktor-bot-1 ktor-bot-2; do
    r "$C up -d --no-deps $svc" 2>&1 | tail -2
    esperar_sano "oposibots-$svc-1" || { echo "ERROR: $svc no queda sano (si sale con codigo 78: configuracion de pagos). NO-GO: seccion 7."; exit 1; }
  done
  titulo "F5.4 version real"
  r 'for c in $(docker ps --format "{{.Names}}" | grep ktor); do docker inspect $c --format "{{.Name}} {{.Config.Image}} {{index .Config.Labels \"org.opencontainers.image.revision\"}} {{.State.Health.Status}}"; done'
  ;;

humo)
  titulo "F6.1 salud y borde"
  for p in /health/live /health/ready; do printf '%-40s ' "$p"; curl -s -o /dev/null -w '%{http_code} (200)\n' "$API$p"; done
  for p in /metrics /admin/backend/restart /no-existe /api/v1/webhooks/store/google /api/v1/webhooks/store/apple; do printf '%-40s ' "$p"; curl -s -o /dev/null -w '%{http_code} (404)\n' "$API$p"; done
  for p in /api/v1/admin/etiquetas /api/v1/app/wallet; do printf '%-40s ' "$p"; curl -s -o /dev/null -w '%{http_code} (401 sin sesion)\n' "$API$p"; done
  r 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}" | grep ktor'
  echo "Despues: F6.3-F6.7 a mano (recorrido con cuenta sintetica, IDOR, panel de Control, pagos apagados). GO-6 = punto de no retorno."
  ;;

imagenes-anteriores)
  titulo "R-0/R-1: volver a $OLD_BOT (bots) y $OLD_CONTROL (control)"
  echo "ATENCION: solo vale si MAIN NO tiene migraciones V1 (o ya se deshicieron con sus ROLLBACK, seccion 7)."
  confirmar VOLVER "poner BACKEND_IMAGE=$OLD_BOT y CONTROL_IMAGE=$OLD_CONTROL en compose.env y arrancar los tres Ktor"
  C="$(compose_cmd)"
  r "cd $ENV_DIR && cp -a compose.env compose.env.bak-$(date +%Y%m%d%H%M) && sed -i -e 's|^BACKEND_IMAGE=.*|BACKEND_IMAGE=$OLD_BOT|' compose.env && \
     { grep -q '^CONTROL_IMAGE=' compose.env && sed -i 's|^CONTROL_IMAGE=.*|CONTROL_IMAGE=$OLD_CONTROL|' compose.env || echo 'CONTROL_IMAGE=$OLD_CONTROL' >> compose.env; }"
  for svc in ktor-control ktor-bot-1 ktor-bot-2; do r "$C up -d --no-deps $svc" 2>&1 | tail -1; esperar_sano "oposibots-$svc-1" || echo "ERROR: $svc no sano"; done
  ;;

*)
  sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
