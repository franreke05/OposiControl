# Monitorización de OposiControl

Fuente reproducible del stack local de observabilidad de Hetzner: Prometheus,
Node Exporter y cAdvisor. Prometheus también consulta las métricas internas de
Ktor en `oposicontrol-ktor:8080/metrics`.

## Requisitos

- Docker Engine con Compose v2.
- El backend `oposicontrol-ktor` conectado a la red Docker externa
  `oposicontrol-deploy_default` y exponiendo `/metrics` dentro de esa red.
- Ejecutar los comandos desde este directorio, o indicar siempre `-f`.

Prometheus publica su interfaz únicamente en `127.0.0.1:9090`; no se debe abrir
ese puerto en el firewall ni publicar `/metrics` mediante el proxy público.

## Validar y levantar

```bash
docker compose config --quiet
docker run --rm \
  -v "$PWD/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  --entrypoint promtool \
  prom/prometheus:v3.14.0 \
  check config /etc/prometheus/prometheus.yml
docker compose up -d
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

# Interno: debe ser accesible desde la red compartida.
docker run --rm --network oposicontrol-deploy_default curlimages/curl:8.16.0 \
  --fail --silent http://oposicontrol-ktor:8080/metrics >/dev/null
```

La regla de Caddy esta versionada en `ops/caddy/metrics-block.caddy`; se
importa ANTES del proxy general. No se deben abrir puertos adicionales.

### Token de scrape

Ktor exige `Authorization: Bearer <METRICS_SCRAPE_TOKEN>` en `/metrics` cuando
la variable no esta vacia (ver `.env.example`). Prometheus lo envia con
`credentials_file: /etc/prometheus/secrets/metrics_token`, montado desde
`ops/monitoring/secrets/metrics_token` (ignorado por git; crear antes de
`docker compose up`, mismo valor que el backend). Orden de despliegue:
1) fijar el token en el backend y crear el archivo, 2) levantar Prometheus con
esta config, 3) comprobar el target `ktor` en `UP`. Con el token vacio el
endpoint queda abierto (solo desarrollo); en produccion el token es obligatorio
y el bloqueo publico de Caddy es defensa en profundidad.

## Actualizar

```bash
docker compose pull
docker compose up -d
docker compose ps
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
