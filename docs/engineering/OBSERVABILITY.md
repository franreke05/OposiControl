# Observability

OposiControl separa errores de aplicación, métricas operativas y salud:

- Sentry recibe excepciones inesperadas del backend y crashes del Desktop. Logback añade breadcrumbs desde `INFO` y eventos desde `ERROR`; el handler HTTP genérico captura manualmente la excepción y la registra como `WARN` para evitar duplicados.
- Prometheus conserva métricas de Ktor, JVM, host y contenedores. Node Exporter es la fuente de CPU/RAM del host y cAdvisor mantiene la visibilidad de contenedores.
- `/health/live`, `/health/ready` y `/health` certifican proceso, dependencia Supabase y compatibilidad legacy respectivamente.
- Desktop nunca consulta Prometheus directamente: consume el snapshot administrativo `GET /api/v1/admin/estado-sistema`.

## Métricas Ktor y seguridad

Ktor instala un único `PrometheusMeterRegistry`, `MicrometerMetrics` con histogramas y expone `GET /metrics`. Prometheus lo consulta por la red Docker en `oposicontrol-ktor:8080/metrics`. El ingress público debe responder `404` a la ruta exacta `/metrics` antes del `reverse_proxy`; Prometheus continúa ligado solo a `127.0.0.1:9090` y no se abre ningún puerto adicional.

La configuración reproducible vive en `ops/monitoring/`. Usa retención simultánea de 15 días y 5 GB y el volumen persistente `oposicontrol-monitoring_prometheus-data`, que no debe eliminarse durante actualizaciones.

## Snapshot de Estado del sistema

`GET /api/v1/admin/estado-sistema` conserva la autenticación administrativa y agrega:

- `cpuPercent`: `100 - idle%`, calculado desde `node_cpu_seconds_total` en una ventana de 5 minutos.
- `ramPercent`: `(1 - MemAvailable / MemTotal) * 100` desde Node Exporter.
- `apiLatencyMs`: p95 de duración HTTP Ktor en los últimos 5 minutos, excluyendo el propio scrape `/metrics`.
- `errors24h`: incremento observado de respuestas HTTP 5xx en la ventana móvil de 24 horas; no se publica hasta existir una ventana completa y un cero medido se conserva como cero.
- `updatedAt`: instante UTC real de composición del snapshot.

Cada consulta falla de forma independiente. Respuesta vacía, malformada, no finita o Prometheus inaccesible produce `null`, nunca cero inventado. Worker, crashes móviles y cola continúan no disponibles hasta disponer de fuentes reales.

## Configuración

- Backend Sentry: `SENTRY_DSN`, `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE`, `SENTRY_TRACES_SAMPLE_RATE`.
- Desktop Sentry: propiedades/variables `SENTRY_DESKTOP_*` resueltas por `JvmRuntimeConfigSource`.
- Prometheus backend: `PROMETHEUS_URL`, con valor de producción `http://oposicontrol-prometheus:9090`.

Los DSN, tokens y credenciales nunca se almacenan en el repositorio.
