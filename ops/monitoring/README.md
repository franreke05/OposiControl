# Monitorización de OposiControl

Fuente reproducible del stack local de observabilidad de Hetzner: Prometheus,
Node Exporter y cAdvisor. Prometheus también consulta las métricas internas de
Ktor en `oposicontrol-ktor:8080/metrics`.

## Requisitos

- Docker Engine con Compose v2.
- El backend `oposicontrol-ktor` conectado a la red Docker externa
  `oposicontrol-deploy_default` y exponiendo `/metrics` dentro de esa red.
- Todos los comandos de este documento se ejecutan desde la RAIZ del repositorio
  (los comandos `docker compose` llevan `-f ops/monitoring/compose.yaml`; el nombre del
  proyecto y del volumen los fija `name:` en el compose, no el directorio).

Prometheus publica su interfaz únicamente en `127.0.0.1:9090`; no se debe abrir
ese puerto en el firewall ni publicar `/metrics` mediante el proxy público.

## Validar y levantar

```bash
docker compose -f ops/monitoring/compose.yaml config --quiet
docker run --rm \
  -v "$PWD/ops/monitoring/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  --entrypoint promtool \
  prom/prometheus:v3.14.0 \
  check config /etc/prometheus/prometheus.yml
docker compose -f ops/monitoring/compose.yaml up -d
```

Para comprobar los cuatro targets desde el propio servidor:

```bash
curl --fail --silent \
  'http://127.0.0.1:9090/api/v1/targets?state=active'
```

Los jobs esperados son `prometheus`, `node`, `cadvisor` y `ktor`, todos con
estado `UP`.

## Seguridad del endpoint de métricas

Ktor expone `/metrics` para Prometheus dentro de la red Docker, pero Caddy debe
bloquear esa ruta **antes** del `reverse_proxy` público y responder `404`. El
cambio de ingress se valida y despliega por separado; este stack no modifica
Caddy.

Tras desplegar, verificar ambos límites:

```bash
# Público: no debe revelar métricas.
test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
  https://api.oposibots.es/metrics)" = "404"

# Interno: debe ser accesible desde la red compartida. Con METRICS_SCRAPE_TOKEN
# fijado (obligatorio en produccion) Ktor exige el Bearer: sin el responde 401 y
# `--fail` haria fallar este paso en un despliegue correcto (MASTER4-CICD-004).
# Desde la raiz del repo. El token NO viaja en la linea de comandos (seria visible
# en `ps`, `docker inspect` y el historial/auditoria del shell, MASTER5-SEC-002): se
# monta el archivo de solo lectura (con el usuario que lo posee, para no relajar sus
# permisos) y la cabecera se construye DENTRO del contenedor.
docker run --rm --network oposicontrol-deploy_default \
  --user "$(id -u):$(id -g)" \
  -v "$PWD/ops/monitoring/secrets/metrics_token:/run/secrets/metrics_token:ro" \
  --entrypoint sh curlimages/curl:8.16.0 -c \
  'curl --fail --silent -H "Authorization: Bearer $(cat /run/secrets/metrics_token)" http://oposicontrol-ktor:8080/metrics >/dev/null'

# Interno SIN token: debe dar 401 (un 200 significa endpoint abierto = token vacio en el backend).
test "$(docker run --rm --network oposicontrol-deploy_default curlimages/curl:8.16.0 \
  --silent --output /dev/null --write-out '%{http_code}' \
  http://oposicontrol-ktor:8080/metrics)" = "401"
```

Si el backend corre con el token VACIO (solo tolerado fuera de produccion, ver
mas abajo) el segundo curl responde 200 y el bloque falla: es la senal de que el
token no esta fijado. El archivo `ops/monitoring/secrets/metrics_token` no debe
quedar vacio ni versionarse.

Permisos del token (MASTER6-CICD-004). `prom/prometheus` corre como `nobody` (65534) y lee `credentials_file` a traves del bind mount,
asi que un archivo `0600` del operador NO seria legible y el scrape fallaria (target `down` con 401/permission denied). Montaje
que funciona: directorio `ops/monitoring/secrets` `0700` (solo el operador atraviesa el directorio en el host; el daemon de Docker, root, hace
el bind) y archivo `metrics_token` `0644` (legible por `nobody` dentro del contenedor y por el usuario del operador en la comprobacion de
arriba). Alternativa: `chown 65534` y `0400`, pero entonces la comprobacion `--user "$(id -u):$(id -g)"` de arriba debe ejecutarse como ese usuario
o como root. Verificacion tras `docker compose up`: en Prometheus (`http://127.0.0.1:9090/targets`) el job de Ktor debe estar `UP`.

La regla de Caddy esta versionada en `ops/caddy/metrics-block.caddy`; se
importa ANTES del proxy general. No se deben abrir puertos adicionales.

### Token de scrape

Ktor exige `Authorization: Bearer <METRICS_SCRAPE_TOKEN>` en `/metrics` cuando
la variable no esta vacia (ver `.env.example`). Prometheus lo envia con
`credentials_file: /etc/prometheus/secrets/metrics_token`, montado desde
`ops/monitoring/secrets/metrics_token` (ignorado por git; crear antes de
`docker compose -f ops/monitoring/compose.yaml up`, mismo valor que el backend). Orden de despliegue:
1) fijar el token en el backend y crear el archivo, 2) levantar Prometheus con
esta config, 3) comprobar el target `ktor` en `UP`. Con el token vacio el
endpoint queda ABIERTO: el codigo aun no falla cerrado (B-22, se hara tras desplegar el scrape con token).
Mientras tanto, en produccion el token es obligatorio por procedimiento y la unica barrera es el bloqueo
publico de Caddy (`ops/caddy/metrics-block.caddy`), que se comprueba tras cada despliegue con
`curl -s -o /dev/null -w '%{http_code}' https://<host>/metrics` = 404.

## Actualizar

```bash
docker compose -f ops/monitoring/compose.yaml pull
docker compose -f ops/monitoring/compose.yaml up -d
docker compose -f ops/monitoring/compose.yaml ps
```

Las imágenes están fijadas a versiones concretas; actualizar una versión exige
modificar y validar este archivo antes de desplegarla.

## Persistencia, retención y red

- Las series se conservan en el volumen Docker nombrado
  `oposicontrol-monitoring_prometheus-data`.
- La retención está limitada simultáneamente a `15d` y `5GB`.
- La red interna del proyecto comunica Prometheus con los exporters.
- Solo Prometheus se conecta además a `oposicontrol-deploy_default`, lo que le
  permite resolver `oposicontrol-ktor` sin publicar nuevos puertos.

No ejecutar `docker compose down -v` ni eliminar manualmente el volumen: ambas
acciones destruyen el histórico de Prometheus.
