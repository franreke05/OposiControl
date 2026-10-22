#!/usr/bin/env bash
# Comprobaciones estaticas de la configuracion de ops (sin Docker, sin red).
# Falla si se pierde alguna proteccion versionada. Lo ejecuta el CI: `bash scripts/verify-ops-config.sh`
# (no depende del bit ejecutable). OPS_ROOT permite apuntarlo a otro arbol (lo usa verify-ops-config.test.sh).
set -uo pipefail
cd "${OPS_ROOT:-$(dirname "$0")/..}" || exit 2
fail=0
check() { if ( eval "$2" ) >/dev/null 2>&1; then echo "ok:   $1"; else echo "FAIL: $1"; fail=1; fi; }

# Bloque del job `ktor` de prometheus.yml: desde su `- job_name: ktor` hasta el siguiente `- job_name:`.
ktor_job() { awk '/^[[:space:]]*-[[:space:]]+job_name:[[:space:]]*ktor[[:space:]]*$/{f=1;next} f&&/^[[:space:]]*-[[:space:]]+job_name:/{f=0} f' ops/monitoring/prometheus/prometheus.yml; }

check "prometheus ktor job autentica con Bearer (lineas activas, no comentadas)" \
  "ktor_job | grep -Eq '^[[:space:]]+credentials_file:[[:space:]]*/etc/prometheus/secrets/metrics_token[[:space:]]*\$' && ktor_job | grep -Eq '^[[:space:]]+type:[[:space:]]*Bearer[[:space:]]*\$'"
check "compose monta el token de scrape" \
  "grep -Eq '^[[:space:]]*-[[:space:]]*.*secrets/metrics_token:/etc/prometheus/secrets/metrics_token:ro' ops/monitoring/compose.yaml"
check ".env.example documenta METRICS_SCRAPE_TOKEN" \
  "grep -q '^METRICS_SCRAPE_TOKEN=' .env.example"
check "Caddy bloquea /metrics con 404" \
  "grep -q '^@metrics path /metrics' ops/caddy/metrics-block.caddy && grep -q '^respond @metrics 404' ops/caddy/metrics-block.caddy"
check "Caddy limita el cuerpo no multipart (body-limit.caddy)"   "grep -Eq '^[[:space:]]*max_size[[:space:]]+[0-9]+MB' ops/caddy/body-limit.caddy && grep -q '^request_body @bounded' ops/caddy/body-limit.caddy"
check "secrets/ no se versiona" \
  "grep -q '^\*\$' ops/monitoring/secrets/.gitignore"
check "Dockerfile.backend con base fijada por digest" \
  "grep -Eq '^FROM eclipse-temurin:[^ ]+@sha256:[0-9a-f]{64}\$' ops/deploy/Dockerfile.backend"
check "ops/deploy solo contiene el Dockerfile construible con el .dockerignore raiz" \
  "test \"\$(ls ops/deploy | grep -c '^Dockerfile')\" = 1"
check ".dockerignore excluye todo salvo la distribucion" \
  "grep -q '^\*\$' .dockerignore && grep -q '^!backend/ktor/build/install/ktor' .dockerignore"
check ".gitattributes fija LF en migraciones" \
  "grep -q 'supabase/migrations/\*.sql text eol=lf' .gitattributes"
check ".gitattributes fija LF en gradlew" \
  "grep -q '^gradlew text eol=lf' .gitattributes"
check "script de build de imagen existe, con set -euo pipefail y llama a la guarda de arbol limpio" \
  "test -f scripts/build-backend-image.sh && grep -q '^set -euo pipefail' scripts/build-backend-image.sh && grep -q '^bash scripts/assert-clean-tree.sh' scripts/build-backend-image.sh"
check "guarda de arbol limpio: cambios, no rastreados y ignorados en arboles de codigo (MASTER3-SEC-002)" \
  "grep -q '^set -euo pipefail' scripts/assert-clean-tree.sh && grep -q 'git status --porcelain)' scripts/assert-clean-tree.sh && grep -q 'git status --porcelain --ignored' scripts/assert-clean-tree.sh && ! grep -q 'untracked-files=no' scripts/assert-clean-tree.sh"
check "ci.yml: cancel-in-progress solo en pull_request (MASTER4-CICD-003)" \
  "grep -Fq \"cancel-in-progress: \\\${{ github.event_name == 'pull_request' }}\" .github/workflows/ci.yml"
check "guarda de arbol limpio: rechaza ignorados bajo src/ y la raiz solo por argumento (MASTER4-SEC-002)" \
  "grep -q \"grep -E '^!! (.\\*/)?src/'\" scripts/assert-clean-tree.sh && ! grep -q 'CLEAN_TREE_ROOT' scripts/assert-clean-tree.sh"
check "README de monitoring: el curl interno de /metrics envia el Bearer (MASTER4-CICD-004)" \
  "grep -q 'Authorization: Bearer .(cat /run/secrets/metrics_token)' ops/monitoring/README.md && grep -q 'metrics_token:/run/secrets/metrics_token:ro' ops/monitoring/README.md"
check "README de monitoring: el token nunca se lee en el host para la linea de comandos y un unico directorio de trabajo (MASTER5-SEC-002)" \
  "! grep -q '(cat ops/monitoring/secrets/metrics_token)' ops/monitoring/README.md && grep -q 'RAIZ del repositorio' ops/monitoring/README.md && ! grep -q 'desde este directorio' ops/monitoring/README.md && ! grep -Eq '^docker compose (config|up|pull|ps)' ops/monitoring/README.md"
check "ci.yml: secret-scan con gitleaks-action fijada por SHA (MASTER7-CICD-001)"   "grep -Eq 'uses: gitleaks/gitleaks-action@[0-9a-f]{40}' .github/workflows/ci.yml && grep -q '^  secret-scan:' .github/workflows/ci.yml"
check "dependabot.yml: ecosistemas gradle y github-actions (MASTER7-CICD-001)"   "grep -Eq 'package-ecosystem: gradle' .github/dependabot.yml && grep -Eq 'package-ecosystem: github-actions' .github/dependabot.yml"
check "ci.yml construye y arranca la imagen del backend (MASTER7-CICD-002)"   "grep -q 'bash scripts/build-backend-image.sh' .github/workflows/ci.yml && grep -q 'bash scripts/smoke-backend-image.sh' .github/workflows/ci.yml && grep -q 'bash scripts/smoke-backend-image.test.sh' .github/workflows/ci.yml"
check "smoke-backend-image.sh: pipefail, uid 10001, /health/live y limpieza (MASTER7-CICD-002)"   "grep -q '^set -uo pipefail' scripts/smoke-backend-image.sh && grep -q 'id -u)\" = 10001' scripts/smoke-backend-image.sh && grep -q '/health/live' scripts/smoke-backend-image.sh && grep -q '^trap cleanup EXIT' scripts/smoke-backend-image.sh"
check "smoke-backend-image.sh: perfil consumer, salida temprana y etiqueta de revision (CI-BACKEND-IMAGE)"   "grep -q 'SERVER_PROFILE=consumer' scripts/smoke-backend-image.sh && grep -q '{{.State.Running}}' scripts/smoke-backend-image.sh && grep -q 'org.opencontainers.image.revision' scripts/smoke-backend-image.sh"
check "gradle/verification-metadata.xml existe y ci.yml no desactiva la verificacion (MASTER7-CICD-005)"   "grep -q '<verify-metadata>true</verify-metadata>' gradle/verification-metadata.xml && ! grep -Eq 'dependency-verification[= ]+(off|lenient)|--dependency-verification' .github/workflows/ci.yml scripts/build-backend-image.sh scripts/smoke-backend-image.sh"
check "ci.yml: deriva de verification-metadata.xml en Linux (CI-GRADLE-VERIF)"   "grep -Eq '^      - run: \./gradlew --no-configuration-cache --write-verification-metadata sha256 .*:backend:ktor:installDist .*:backend:ktor:test ' .github/workflows/ci.yml && grep -Fq 'run: git diff --exit-code -- gradle/verification-metadata.xml' .github/workflows/ci.yml"
check "ci.yml: control negativo de la deriva y guard de flavors Android (CI-GRADLE-VERIF, INV-NETWORK-COMPLETE)"   "grep -Fq 'run: bash scripts/verify-gradle-drift-negative-control.sh' .github/workflows/ci.yml && grep -Fq 'run: bash scripts/verify-android-flavor-config.sh' .github/workflows/ci.yml && test -s scripts/verify-gradle-drift-negative-control.sh && test -s scripts/verify-android-flavor-config.sh"
check "ci.yml: redocly desde tools/redocly con package-lock (npm ci, sin npx --yes) (MASTER7-CICD-005)"   "test -s tools/redocly/package-lock.json && grep -q 'npm ci --prefix tools/redocly' .github/workflows/ci.yml && ! grep -q 'npx --yes' .github/workflows/ci.yml"
# MASTER7-QUALITY-001: un CR suelto (no seguido de LF) vuelve el blob no-texto: git no lo normaliza ni lo diffea. Un CRLF puro de un
# checkout de Windows es inocuo y no cuenta.
has_lone_cr() { local cr; cr="$(printf '\r')"; sed "s/${cr}\$//" "$1" | grep -qU "$cr"; }
no_lone_cr_md() { local f; while IFS= read -r f; do has_lone_cr "$f" && return 1; done < <(find docs README.md -name '*.md'); return 0; }
check ".gitattributes fija LF en *.md y ningun .md de docs/README tiene CR suelto (MASTER7-QUALITY-001)" \
  "grep -Fxq '*.md text eol=lf' .gitattributes && no_lone_cr_md"
check "SECURITY.md no niega las metricas Prometheus que existen (MASTER7-QUALITY-001)"   "grep -q 'Prometheus .*/metrics.* does exist' docs/engineering/SECURITY.md && ! grep -Eq 'CallLogging., metrics/tracing' docs/engineering/SECURITY.md"
check "ci.yml ejecuta las puertas de inventario: sin acceso directo a Supabase (+ su prueba) y sin modo de regeneracion (GATE-DIRECT-SUPABASE, INV-NETWORK-COMPLETE)" \
  "grep -Fxq '      - run: bash scripts/verify-no-direct-supabase.sh' .github/workflows/ci.yml && grep -Fxq '      - run: bash scripts/verify-no-direct-supabase.test.sh' .github/workflows/ci.yml && ! grep -q INVENTORY_UPDATE .github/workflows/ci.yml && test -s ops/inventory/direct-supabase-allowlist.tsv && test -s ops/inventory/routes.combined.txt && test -s ops/inventory/routes.consumer.txt && grep -q '^# max: ' ops/inventory/undocumented-routes.tsv && test -s docs/integration/api-redesign/legacy-route-register.tsv"
# ---------------------------------------------------------------------------------------------------------------------
# Produccion (ops/deploy/compose.production.yaml, Caddyfile.production, Prometheus/Grafana de produccion). Estaticas: sin red ni
# secretos. `docker compose config -q` se usa solo si Docker esta disponible; si no, el resto de comprobaciones estructurales cubre.
CP=ops/deploy/compose.production.yaml
# Bloque de un servicio del compose: desde `  <nombre>:` hasta el siguiente servicio o seccion de primer nivel.
svc() { awk -v s="$1" '$0 ~ ("^  " s ":[[:space:]]*$"){f=1;next} f&&/^  [A-Za-z0-9_-]+:/{f=0} f&&/^[A-Za-z#x]/{f=0} f' "$CP"; }
# Plantilla `x-ktor` (ancla compartida por los tres Ktor).
xktor() { awk '/^x-ktor:/{f=1;next} f&&/^[A-Za-z#]/{f=0} f' "$CP"; }
compose_parses() {
  if [ -z "${OPS_SKIP_DOCKER:-}" ] && command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    local d; d="$(mktemp -d)"; : >"$d/backend.common.env"; : >"$d/backend.bot.env"; : >"$d/backend.control.env"
    BACKEND_COMMON_ENV_FILE="$d/backend.common.env" BACKEND_BOT_ENV_FILE="$d/backend.bot.env" BACKEND_CONTROL_ENV_FILE="$d/backend.control.env" \
      BACKEND_IMAGE=x:1 API_HOST=api.example.test VALKEY_PASSWORD=x GRAFANA_ADMIN_PASSWORD=x \
      docker compose -f "$CP" config -q; local rc=$?; rm -rf "$d"; return $rc
  fi
  grep -q '^services:' "$CP" && grep -q '^name: ' "$CP"
}
ktor_service_ok() { # <servicio> <perfil>
  local b; b="$(svc "$1")"
  echo "$b" | grep -Eq '<<:[[:space:]]*\*ktor' && echo "$b" | grep -Eq "^[[:space:]]+SERVER_PROFILE:[[:space:]]*$2[[:space:]]*\$" \
    \
    && echo "$b" | grep -Eq 'JAVA_TOOL_OPTIONS:.*MaxRAMPercentage='
}
all_limits() { local s; for s in caddy ktor-bot-1 ktor-bot-2 ktor-control valkey prometheus grafana alertmanager; do
  svc "$s" | grep -Eq '^[[:space:]]+cpus:' && svc "$s" | grep -Eq '^[[:space:]]+mem_limit:' || return 1; done; }
only_caddy_publishes() {
  local s n=0
  for s in $(grep -E '^  [A-Za-z0-9_-]+:[[:space:]]*$' "$CP" | tr -d ' :'); do
    if svc "$s" | grep -Eq '^[[:space:]]+ports:'; then [ "$s" = caddy ] || return 1; n=$((n + 1)); fi
  done
  [ "$n" = 1 ] && [ "$(svc caddy | grep -Ec '^[[:space:]]+-[[:space:]]*"[0-9]+:[0-9]+"')" = 2 ] \
    && svc caddy | grep -Fq '"80:80"' && svc caddy | grep -Fq '"443:443"'
}
# F-09b (gate Slice 3): un `env_file` comun a los tres Ktor daba a los bot los secretos de administracion. Listas de entorno
# por perfil (anclas `x-env-bot` / `x-env-control`), cada servicio con la suya y NINGUNA en la plantilla comun.
envlist() { awk -v s="$1" '$0 ~ ("^" s ":"){f=1;next} f&&/^[A-Za-z#]/{f=0} f' "$CP" | grep -E '^[[:space:]]+-[[:space:]]'; }
# El `env_file` efectivo de un servicio: su ancla resuelta (o lo que tenga escrito en linea).
svc_env() { local a; a="$(svc "$1" | sed -nE 's/^[[:space:]]+env_file:[[:space:]]*\*([a-z-]+)[[:space:]]*$/\1/p')"
  if [ -n "$a" ]; then envlist "x-$a"; else svc "$1" | awk '/^    env_file:/{f=1;next} f&&/^    [a-z_]+:/{f=0} f'; fi; }
env_files_outside_repo() { local l n=0
  while IFS= read -r l; do n=$((n + 1)); echo "$l" | grep -Eq '^[[:space:]]+-[[:space:]]+\$\{BACKEND_(COMMON|BOT|CONTROL)_ENV_FILE:-/etc/oposibots/production/backend\.(common|bot|control)\.env\}[[:space:]]*$' || return 1
  done < <(envlist x-env-bot; envlist x-env-control)
  [ "$n" -ge 4 ]; }
env_split_by_profile() { local s e
  ! xktor | grep -Eq '^[[:space:]]+env_file:' || return 1
  for s in ktor-bot-1 ktor-bot-2; do e="$(svc_env "$s")"
    echo "$e" | grep -Fq 'backend.common.env' && echo "$e" | grep -Fq 'backend.bot.env' && ! echo "$e" | grep -Eiq 'control' || return 1; done
  e="$(svc_env ktor-control)"
  echo "$e" | grep -Fq 'backend.common.env' && echo "$e" | grep -Fq 'backend.control.env' && ! echo "$e" | grep -Eiq 'backend\.bot|BOT_ENV' || return 1
  # Ningun secreto de un perfil llega por `environment:` al otro.
  ! svc ktor-bot-1 | grep -q 'OPOSICONTROL_BACKEND_TOKEN' && ! svc ktor-bot-2 | grep -q 'OPOSICONTROL_BACKEND_TOKEN' \
    && ! svc ktor-control | grep -Eq 'AUTH_RATE_LIMIT_HMAC_SECRET|VALKEY_URL|APPLE_CLIENT_SECRET'; }
env_names() { grep -oE '^[A-Z][A-Z0-9_]*=' "ops/deploy/backend.$1.env.example" | tr -d '='; }
env_examples_ok() { local p
  ! test -e ops/deploy/backend.env.example || return 1 # el fichero comun antiguo no vuelve
  for p in common bot control; do
    test -s "ops/deploy/backend.$p.env.example" && ! grep -Eq '^[A-Z][A-Z0-9_]*=.+' "ops/deploy/backend.$p.env.example" || return 1; done
  # Cada variable en UN solo fichero (si no, el reparto por perfil no dice nada).
  [ -z "$( (env_names common; env_names bot; env_names control) | sort | uniq -d)" ] || return 1
  env_names bot | grep -qx 'AUTH_RATE_LIMIT_HMAC_SECRET' && env_names bot | grep -qx 'VALKEY_URL' \
    && env_names control | grep -qx 'OPOSICONTROL_BACKEND_TOKEN' && env_names common | grep -qx 'SUPABASE_URL' || return 1
  # Los secretos de administracion no se ofrecen a bot ni al comun, ni los de auth de consumidor a control ni al comun.
  ! (env_names common; env_names bot) | grep -qx 'OPOSICONTROL_BACKEND_TOKEN' \
    && ! (env_names common; env_names control) | grep -Eqx 'AUTH_RATE_LIMIT_HMAC_SECRET|VALKEY_URL|APPLE_CLIENT_SECRET|APPLE_CLIENT_ID|SUPABASE_PUBLISHABLE_KEY|SUPABASE_ANON_KEY'; }
# F-07 (gate Slice 3): el pool bot solo expulsa una replica por salud ACTIVA (/health/live) o por fallos de conexion. Un
# `unhealthy_status` / `unhealthy_latency` expulsaba las DOS a la vez ante un 5xx de una dependencia compartida.
caddy_no_status_ejection() { local f=ops/caddy/Caddyfile.production
  ! grep -Ev '^[[:space:]]*#' "$f" | grep -Eq '^[[:space:]]*(unhealthy_status|unhealthy_latency)[[:space:]]' \
    && grep -Eq '^[[:space:]]*fail_duration[[:space:]]+[0-9]+s' "$f" && grep -Eq '^[[:space:]]*max_fails[[:space:]]+[0-9]+' "$f"; }
# Nombres de metrica que existen de verdad en el codigo (Micrometer/Ktor). Cualquier otro nombre en alertas o paneles es inventado.
metrics_known() {
  local m bad=0
  # Las lineas de comentario de las reglas pueden nombrar metricas con comodin (`jvm_gc_pause_seconds_*`): no cuentan.
  for m in $(cat ops/monitoring/grafana/dashboards/*.json ops/monitoring/prometheus/alert_rules.yml | grep -v '^[[:space:]]*#' \
      | grep -oE '(ktor_http_server_requests_seconds_[a-z]+|jvm_[a-z_]+|legacy_route_requests_total|upstream_response_wire_bytes_total|upstream_supabase_up|store_inbox_[a-z_]+|store_payments_[a-z_]+|process_[a-z_]+|system_[a-z_]+)' | sort -u); do
    case "$m" in
      ktor_http_server_requests_seconds_count|ktor_http_server_requests_seconds_sum|ktor_http_server_requests_seconds_bucket) ;;
      jvm_memory_used_bytes|jvm_memory_max_bytes|jvm_gc_pause_seconds_sum|jvm_gc_pause_seconds_count|jvm_threads_live_threads) ;;
      legacy_route_requests_total|upstream_response_wire_bytes_total|upstream_supabase_up) ;;
      store_inbox_backlog|store_inbox_oldest_seconds|store_inbox_history_incomplete|store_payments_enabled) ;;  # StoreInboxMetrics (StorePaymentRoutes.kt)
      *) echo "metrica desconocida: $m" >&2; bad=1 ;;
    esac
  done
  return $bad
}
no_secret_literals() {
  ! grep -rEn --exclude-dir=secrets --exclude-dir=inventory \
    -e '^[[:space:]]*"?[A-Z0-9_]*(SECRET|PASSWORD|TOKEN|KEY|DSN)[A-Z0-9_]*"?[:=][[:space:]]*["'"'"']?[A-Za-z0-9+/_.-]{12,}' \
    -e 'eyJ[A-Za-z0-9_-]{20,}' -e 'sb_secret_[A-Za-z0-9]' -e 'BEGIN [A-Z ]*PRIVATE KEY' -e 'https://[0-9a-f]{16,}@' ops
}
check "compose.production.yaml parsea (docker compose config -q si hay Docker; si no, estructura)" "compose_parses"
check "compose.production: ktor-bot-1 y ktor-bot-2 con SERVER_PROFILE=bot, plantilla x-ktor y heap explicito" \
  "ktor_service_ok ktor-bot-1 bot && ktor_service_ok ktor-bot-2 bot"
check "compose.production: ktor-control con SERVER_PROFILE=control, plantilla x-ktor y heap explicito" "ktor_service_ok ktor-control control"
check "compose.production: todos los servicios con limites cpus y mem_limit" "all_limits"
check "compose.production: healthcheck /health/live, restart, stop_grace_period y rotacion de logs en la plantilla x-ktor" \
  "xktor | grep -Fq 'http://127.0.0.1:8080/health/live' && xktor | grep -Eq '^  restart:[[:space:]]*unless-stopped' && xktor | grep -Eq '^  stop_grace_period:' && xktor | grep -Eq '^  logging:' && grep -Eq 'max-size:' \"\$CP\""
check "compose.production: unico puerto publicado es caddy 80/443" "only_caddy_publishes"
check "compose.production: env_file fuera del repo (/etc/oposibots/production)" \
  "env_files_outside_repo"
check "compose.production: entorno por perfil; un Ktor bot no carga el fichero de control ni control el de bot (F-09b)" \
  "env_split_by_profile"
check "backend.{common,bot,control}.env.example: solo nombres, ninguna variable con valor y cada una en UN solo fichero" \
  "env_examples_ok"
check "Caddyfile.production: host por variable, /metrics bloqueado, tope de cuerpo y 404 por defecto" \
  "grep -Fq '{\$API_HOST}' ops/caddy/Caddyfile.production && grep -Eq '^[[:space:]]*import /etc/caddy/metrics-block.caddy' ops/caddy/Caddyfile.production && grep -Eq '^[[:space:]]*import /etc/caddy/body-limit.caddy' ops/caddy/Caddyfile.production && grep -Eq '^[[:space:]]*handle \\{\$' ops/caddy/Caddyfile.production && grep -Eq '^[[:space:]]*respond 404\$' ops/caddy/Caddyfile.production"
check "Caddyfile.production: control por rutas explicitas (no /admin* en bloque) y dev-only /admin/backend, /admin/dev con 404" \
  "grep -Eq '^[[:space:]]*@control path .*/api/v1/admin/\\*' ops/caddy/Caddyfile.production && ! grep -Eq '^[[:space:]]*@control path .* /admin/?\\*?( |\$)' ops/caddy/Caddyfile.production && grep -Eq '^[[:space:]]*@devonly path .*/admin/backend.*/admin/dev' ops/caddy/Caddyfile.production && grep -Eq '^[[:space:]]*respond @devonly 404' ops/caddy/Caddyfile.production"
# Incidente 2026-10-10: la salud activa del balanceador usa /health/live. /health/ready consulta Supabase; sondeado por el
# balanceador agoto la cuota de trafico de salida y, al caer la dependencia COMPARTIDA, expulsaba todas las replicas a la vez.
check "Caddyfile.production: balanceo least_conn con salud activa en /health/live (nunca /health/ready), IP real de Cloudflare y reescritura de X-Forwarded-For" \
  "grep -Eq 'lb_policy least_conn' ops/caddy/Caddyfile.production && grep -Eq 'health_uri /health/live' ops/caddy/Caddyfile.production && ! grep -Ev '^[[:space:]]*#' ops/caddy/Caddyfile.production | grep -Eq 'health_uri /health/ready' && grep -Eq 'trusted_proxies static .*173\\.245\\.48\\.0/20' ops/caddy/Caddyfile.production && grep -Eq 'client_ip_headers CF-Connecting-IP' ops/caddy/Caddyfile.production && grep -Eq 'header_up X-Forwarded-For \\{client_ip\\}' ops/caddy/Caddyfile.production"
check "Caddyfile.production: el pool bot no expulsa replicas por codigo de estado ni latencia (solo salud activa y fallos de conexion) (F-07)" \
  "caddy_no_status_ejection"
check "Caddyfile.production: el log de acceso no registra Authorization ni cookies" \
  "grep -Eq 'request>headers>Authorization delete' ops/caddy/Caddyfile.production && grep -Eq 'request>headers>Cookie delete' ops/caddy/Caddyfile.production"
check "prometheus.production.yml: scrape de bot-1, bot-2 y control con Bearer y etiquetas service/profile/replica" \
  "grep -Eq '^[[:space:]]+credentials_file:[[:space:]]*/etc/prometheus/secrets/metrics_token' ops/monitoring/prometheus/prometheus.production.yml && test \"\$(grep -Ec '^[[:space:]]+profile:[[:space:]]*(bot|control)\$' ops/monitoring/prometheus/prometheus.production.yml)\" = 3 && test \"\$(grep -Ec '^[[:space:]]+replica:[[:space:]]' ops/monitoring/prometheus/prometheus.production.yml)\" = 3 && grep -q 'ktor-control:8080' ops/monitoring/prometheus/prometheus.production.yml && grep -q 'ktor-bot-2:8080' ops/monitoring/prometheus/prometheus.production.yml"
check "alertas y paneles solo usan metricas que existen en el codigo" "metrics_known"
check "no hay secretos en claro bajo ops/ (JWT, sb_secret_, claves privadas, asignaciones con valor)" "no_secret_literals"
check "artefactos de produccion presentes (Grafana, runbooks, decisiones)" \
  "test -s ops/deploy/compose.env.example && test -s ops/deploy/backend.common.env.example && test -s ops/monitoring/grafana/provisioning/datasources/prometheus.yml && test -s ops/monitoring/grafana/provisioning/dashboards/dashboards.yml && test -s ops/monitoring/grafana/dashboards/api-overview.json && test -s ops/monitoring/grafana/dashboards/jvm.json && test -s ops/monitoring/grafana/dashboards/dependencies.json && test -s docs/engineering/RUNTIME_DECISIONS.md && test -s docs/engineering/PRODUCTION_RUNBOOK.md && test -s docs/engineering/CLOUDFLARE_RUNBOOK.md"
# ---------------------------------------------------------------------------------------------------------------------
# Staging on the production host (ops/deploy/compose.staging.yaml, ops/deploy/staging, ops/caddy/staging, prometheus.staging.yml;
# runbook docs/engineering/STAGING_ON_PROD_HOST_RUNBOOK.md). Static: no network, no secrets.
CS=ops/deploy/compose.staging.yaml
ssvc() { awk -v s="$1" '$0 ~ ("^  " s ":[[:space:]]*$"){f=1;next} f&&/^  [A-Za-z0-9_-]+:/{f=0} f&&/^[A-Za-z#x]/{f=0} f' "$CS"; }
sxktor() { awk '/^x-ktor:/{f=1;next} f&&/^[A-Za-z#]/{f=0} f' "$CS"; }
sservices() { awk '/^services:/{f=1;next} f&&/^[A-Za-z#]/{f=0} f&&/^  [A-Za-z0-9_-]+:[[:space:]]*$/' "$CS" | tr -d ' :'; }
scode() { grep -Ev '^[[:space:]]*#' "$1"; }
staging_parses() {
  grep -Eq '^name:[[:space:]]*oposibots-staging[[:space:]]*$' "$CS" || return 1
  if [ -z "${OPS_SKIP_DOCKER:-}" ] && command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    local d; d="$(mktemp -d)"; : >"$d/c"; : >"$d/b"; : >"$d/k"; : >"$d/t"
    STAGING_COMMON_ENV_FILE="$d/c" STAGING_BOT_ENV_FILE="$d/b" STAGING_CONTROL_ENV_FILE="$d/k" STAGING_METRICS_TOKEN_FILE="$d/t" \
      BACKEND_IMAGE=x:1 STAGING_VALKEY_PASSWORD=x docker compose -p oposibots-staging -f "$CS" config -q; local rc=$?; rm -rf "$d"; return $rc
  fi
  grep -q '^services:' "$CS"
}
# Every service: cpus, mem_limit, memswap_limit, pids_limit, cpu_shares 256 (prod keeps 1024), oom_score_adj > 0, rotated logs.
staging_limits() { local s b n=0
  for s in $(sservices); do n=$((n + 1)); b="$(ssvc "$s")"; echo "$b" | grep -Eq '<<:[[:space:]]*\*ktor' && b="$b
$(sxktor)"
    for k in cpus mem_limit memswap_limit pids_limit logging; do echo "$b" | grep -Eq "^[[:space:]]+$k:" || return 1; done
    echo "$b" | grep -Eq '^[[:space:]]+cpu_shares:[[:space:]]*256[[:space:]]*$' && echo "$b" | grep -Eq '^[[:space:]]+oom_score_adj:[[:space:]]*[1-9][0-9]*[[:space:]]*$' || return 1
  done
  [ "$n" -ge 4 ] && grep -Eq 'max-size:' "$CS" && grep -Eq 'max-file:' "$CS"; }
# Only the two Ktor publish, only on 127.0.0.1, never production's 18081-18083; one own network, no external join, own subnet.
staging_network() { local s
  for s in $(sservices); do
    if ssvc "$s" | grep -Eq '^[[:space:]]+ports:'; then
      case "$s" in ktor-bot-1|ktor-control) ;; *) return 1 ;; esac
      ssvc "$s" | awk '/^    ports:/{f=1;next} f&&/^    [a-z_]+:/{f=0} f' | grep -E '^[[:space:]]+-' | grep -Ev '^[[:space:]]+-[[:space:]]*"127\.0\.0\.1:\$\{STAGING_[A-Z_]+_PORT:-1818[0-9]\}:8080"[[:space:]]*$' | grep -q . && return 1
    fi
    ssvc "$s" | grep -Eq '^[[:space:]]+network_mode:' && return 1
  done
  ! scode "$CS" | grep -Eq '^[[:space:]]+external:' && ! scode "$CS" | grep -Eq '1808[0-9]' \
    && test "$(awk '/^networks:/{f=1;next} f&&/^[A-Za-z#]/{f=0} f&&/^  [A-Za-z0-9_-]+:/' "$CS" | wc -l)" = 1 \
    && grep -Eq 'subnet: \$\{STAGING_SUBNET:-172\.30\.' "$CS" && ! scode "$CS" | grep -Eq '172\.28\.'; }
staging_env_paths() { local l n=0
  while IFS= read -r l; do n=$((n + 1)); echo "$l" | grep -Eq '^[[:space:]]+-[[:space:]]+\$\{STAGING_(COMMON|BOT|CONTROL)_ENV_FILE:-/etc/oposibots/staging/backend\.(common|bot|control)\.env\}[[:space:]]*$' || return 1
  done < <(awk '/^x-env-(bot|control):/{f=1;next} f&&/^[A-Za-z#]/{f=0} f' "$CS" | grep -E '^[[:space:]]+-')
  [ "$n" = 4 ] && ! scode "$CS" | grep -q '/etc/oposibots/production' && ! ssvc ktor-bot-1 | grep -q 'env-control' && ! ssvc ktor-control | grep -q 'env-bot'; }
# Payments sandbox only: every store/credit flag false in the bot `environment:`, PKCE required, APP_ENV staging, no sandbox secret.
staging_safety() { local b; b="$(ssvc ktor-bot-1)"
  for kv in 'PAYMENTS_STORE_GOOGLE_PLAY_ENABLED: "false"' 'PAYMENTS_STORE_APP_STORE_ENABLED: "false"' 'PAYMENTS_STORE_LIVE_CREDIT_ENABLED: "false"' \
            'PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED: "false"' 'PAYMENTS_STORE_SANDBOX: reject' 'AUTH_PKCE_MODE: required' 'AUTH_RATE_LIMIT_MODE: valkey'; do
    echo "$b" | grep -Fq "      $kv" || return 1; done
  ssvc ktor-control | grep -Fq '      ADMIN_RATE_LIMIT_MODE: valkey' && grep -Eq '^  APP_ENV: staging$' "$CS" \
    && ssvc ktor-bot-1 | grep -q '<<: \*safety-common' && ssvc ktor-control | grep -q '<<: \*safety-common' \
    && ! scode "$CS" | grep -q 'PAYMENTS_SANDBOX_SECRET' && ! grep -Eq '^PAYMENTS_SANDBOX_SECRET=' ops/deploy/staging/*.env.example; }
staging_examples() { local f
  for f in compose backend.common backend.bot backend.control; do test -s "ops/deploy/staging/$f.env.example" && ! grep -Eq '^[A-Z][A-Z0-9_]*=.+' "ops/deploy/staging/$f.env.example" || return 1; done; }
staging_prometheus() { local p=ops/monitoring/prometheus/prometheus.staging.yml
  grep -Eq '^[[:space:]]+env:[[:space:]]*staging$' "$p" && ! scode "$p" | grep -Eq '^[[:space:]]*(alerting|alertmanagers):' \
    && grep -Eq '^[[:space:]]+credentials_file:[[:space:]]*/etc/prometheus/secrets/metrics_token' "$p" && ! grep -q 'ktor-bot-2' "$p" \
    && test "$(grep -Ec '^[[:space:]]+env:[[:space:]]*staging$' "$p")" -ge 4 \
    && ! grep -q 'prometheus.staging' ops/deploy/compose.production.yaml ops/deploy/compose.hostcaddy.override.yaml \
    && ! scode ops/monitoring/prometheus/prometheus.production.yml | grep -Eq 'oposibots-staging|1818[0-9]|staging'; }
staging_caddy() { local f=ops/caddy/staging/Caddyfile.staging-hostcaddy
  grep -Eq '^staging-api\.oposibots\.es \{$' "$f" && grep -Eq '^staging-control\.oposibots\.es \{$' "$f" && ! grep -Eq '^api\.oposibots\.es' "$f" \
    && ! scode "$f" | grep -Eq '1808[0-9]' && ! scode "$f" | grep -Eq 'health_uri /health/ready' && test "$(grep -c 'health_uri /health/live' "$f")" = 3 \
    && test "$(grep -c '^	tls /etc/caddy/staging/origin.pem /etc/caddy/staging/origin.key$' "$f")" = 2 \
    && ! sed -n '/^staging-control/,$p' "$f" | grep -Eq '@bot |127\.0\.0\.1:18181' \
    && ! grep -q 'staging' ops/caddy/Caddyfile.hostcaddy ops/caddy/Caddyfile.production \
    && { ! command -v node >/dev/null 2>&1 || node ops/caddy/staging/gen-staging-hostcaddy.mjs --check; }; }
staging_no_main() { bash ops/deploy/staging/staging-secrets-check.sh --repo; }
staging_runbook() { local r=docs/engineering/STAGING_ON_PROD_HOST_RUNBOOK.md
  test -s "$r" && grep -q 'docker system prune' "$r" && grep -q 'stagingctl.sh' "$r" && grep -q 'Control acceptance criteria' "$r" \
    && ! grep -Eq '^[[:space:]]*(sudo[[:space:]]+)?docker[[:space:]]+compose[[:space:]]' "$r"; }
check "compose.staging.yaml: proyecto oposibots-staging y parsea (docker compose config -q si hay Docker)" "staging_parses"
check "compose.staging: cada servicio con cpus, mem_limit, memswap_limit, pids_limit, cpu_shares 256, oom_score_adj > 0 y logs rotados" "staging_limits"
check "compose.staging: solo los dos Ktor publican y en 127.0.0.1:1818x; una red propia, sin external ni subred de produccion" "staging_network"
check "compose.staging: env_file solo en /etc/oposibots/staging y por perfil" "staging_env_paths"
check "compose.staging: APP_ENV=staging, PKCE required, Valkey y pagos de tienda apagados (G1, D8, plataformas) fijados en compose" "staging_safety"
check "ops/deploy/staging/*.env.example: solo nombres" "staging_examples"
check "prometheus.staging.yml: env=staging, sin alerting/Alertmanager, con Bearer; produccion no la monta ni raspa staging" "staging_prometheus"
check "Caddyfile.staging-hostcaddy: hosts staging, upstreams 1818x, /health/live, Origin CA, control sin pool bot, generado y prod intacto" "staging_caddy"
check "ficheros de staging versionados sin la referencia del proyecto MAIN ni JWT de MAIN" "staging_no_main"
check "runbook de staging: prohibe prune, usa el wrapper (sin docker compose a pelo) y mapea los criterios de Control" "staging_runbook"
exit $fail
