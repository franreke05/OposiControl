# Decisiones de runtime de produccion

Estado: `PROPUESTO` (borrador versionado; nada desplegado). Evidencia con `archivo:linea` de `backend/ktor/src/main/kotlin/com/oposicontrol/backend/`
(el codigo manda sobre las auditorias previas en `.oposibots-gate/audit/prodarch/`). Topologia: Cloudflare -> Caddy ->
{ktor-bot-1, ktor-bot-2 (`SERVER_PROFILE=bot`), ktor-control (`SERVER_PROFILE=control`)} -> Supabase. Config en `ops/deploy/compose.production.yaml`.

## 1. Valkey: DECISION = KEEP

### Usos reales (inventario completo: `grep -i "valkey\|redis"` en `backend/`)

| # | Uso | Clasificacion | Evidencia |
|---|---|---|---|
| 1 | Contadores de ventana (INCR + PEXPIRE, script Lua atomico) del limitador de las operaciones de auth y de las rutas canonicas de perfil (REGISTER, RESEND, LOGIN, REFRESH, RECOVER, RESET, GOOGLE, APPLE, PROFILE_WRITE, LEGAL_LIST, ACCOUNT_DELETION...) | `DISTRIBUTED_RATE_LIMIT` y `SECURITY` (frena fuerza bruta de credenciales y abuso de correo/recuperacion) | `OposiBotsAuthRateLimiter.kt:20-` (operaciones y cupos), `:127-198` (implementacion), `:190-196` (script Lua), clave `oposibots:auth:<scope>:<op>:<hmac>` en `:159` |
| 2 | Seleccion de modo | `COORDINATION` de configuracion, no de datos | `OposiBotsAuthRateLimiter.kt:63-71` (factory `valkey`/`memory`) |
| 3 | Cierre del cliente al parar | ciclo de vida | `Application.kt:500-501`, `OposiBotsAuthConfiguration.kt:133` |

No hay cache, sesiones, colas, locks ni almacenamiento de datos de negocio en Valkey. No hay uso `LEGACY` ni `CORRECTNESS` de datos: perderlo no corrompe nada.

### Por que KEEP

* Fuera de desarrollo el modo `memory` esta prohibido por el arranque (`OposiBotsAuthConfiguration.kt:94-96`) y `VALKEY_URL` es obligatoria con
  `AUTH_RATE_LIMIT_MODE=valkey` (`:111-113`). Quitarlo exigiria cambiar codigo de seguridad cerrado.
* **Dos replicas bot SI lo necesitan.** Con contadores en memoria cada replica tendria su propio cupo: un atacante recibiria N veces el limite
  (login 8/300 s por subject pasaria a 16 con 2 replicas) y los cupos por usuario dependerian de a que replica llegue. Valkey comparte el contador.
  Requisito de despliegue: mismas `VALKEY_URL` y **mismo `AUTH_RATE_LIMIT_HMAC_SECRET`** en bot-1, bot-2 y control (las claves se hashean con el secreto;
  secretos distintos = cubos disjuntos = limites multiplicados).
* Tamano minimo justificado: una instancia Valkey de 64 MB, sin persistencia (`--save "" --appendonly no`), `volatile-ttl`, contrasena obligatoria, solo en la red interna.
  Sin volumen: perder los contadores solo reinicia las ventanas.

### Comportamiento si Valkey cae (segun codigo): FALLA CERRADO

* Conexion y comandos con tope corto: connect 1 s, comando 2 s (`OposiBotsAuthRateLimiter.kt:185-187`, `:132-139`). Cualquier `RedisException` (timeout o conexion)
  se traduce a `503 UPSTREAM_UNAVAILABLE` con `Retry-After: 5` (`:173-176`, KDoc `:122-126`: "FALLA CERRADO: sin limitador no se atiende la peticion").
* Como TODA ruta canonica y de auth pasa por el limitador, **con Valkey caido el login, el refresh, el registro y las escrituras de perfil devuelven 503**
  (las lecturas que no consultan el limitador y el catalogo anonimo `/v1/*` siguen). No hay degradacion a memoria.
* Arranque: `client.connect()` se hace en la construccion (`:141`), asi que sin Valkey alcanzable el runtime de auth no arranca (falla cerrado tambien en frio).
  Por eso compose declara `depends_on: valkey (service_healthy)` en los tres Ktor.
* Consecuencia operativa: Valkey es una dependencia **critica de disponibilidad de login**, aunque no de datos. Tiene `restart: unless-stopped` y healthcheck.
  Hueco: no hay metrica del limitador ni de la conexion (ver hallazgos); la unica senal es el ratio de 503 en `/v1/auth/*` (panel "Dependencies").

## 2. Worker / cola: DECISION = WORKER_REQUIRED = NO

### Cargas largas en la ruta de la peticion (hoy, todas en `control` salvo indicacion)

| Carga | Donde | Coste | Situacion |
|---|---|---|---|
| Importacion de preguntas desde PDF (PDFBox, sincrono, tope 20 MB) | `AdminV1Routes.kt:966-1010` (`/importaciones/preguntas/pdf`) | CPU + heap altos, sin tope de paginas | **FLUJO PROTEGIDO Y CERRADO: no se mueve a un worker.** |
| Publicar lote importado (un RPC transaccional) | `AdminV1Routes.kt` (`.../{loteId}/importar`) | una transaccion en Supabase, acotada por 20 s | idem, se queda en Ktor |
| Subida de recurso de pregunta con ffmpeg (video hasta 120 s, audio 30 s) y imagen (ImageIO, 50 MP) | `MediaCompression.kt:140` (`VIDEO_TIMEOUT_MS`), `:516`, `:694` (`ProcessBuilder("ffmpeg")`) | RAM fuera del heap + CPU en el mismo contenedor | flujo de subida protegido/cerrado; se queda en `control`, con memoria dimensionada para ello |
| Portada de examen (10 MB, ImageIO) y assets de noticias (`NewsGateway.kt:202`) | `AdminV1Routes.kt` / `NewsGateway.kt` | medio, acotado | se queda en `control` |
| Ejecucion del borrado de cuenta | `AccountDeletionService.kt:11-30` solo registra la solicitud (202, ejecucion diferida); el ejecutor no existe todavia | multi-paso (GoTrue admin + Storage + anonimizacion) | fuera de la ruta de la peticion por diseno; **sin ejecutor hoy** |

Ninguna de las cargas largas vive en el pool `bot`: no recibe multipart (el Caddyfile solo enruta las subidas a `control`).

### Razonamiento

1. Lo unico que justificaria un worker (importacion PDF y transcodificacion) es el flujo cerrado que no se debe tocar.
2. El resto es trafico de un unico equipo de administracion, sobre un contenedor `control` aislado del trafico de usuarios: un PDF pesado puede degradar a `control`, nunca al pool `bot`.
3. No hay cola ni infraestructura de worker en el codigo (`Observability.kt:118`: "no existe ningun worker"; `workerStatus = UNKNOWN`). Anadirlo ahora = Kafka/RabbitMQ/PGMQ
   + perfil nuevo + estados nuevos: complejidad sin carga que lo exija.

### Disparadores para introducirlo despues (cualquiera basta)

* Se implementa el ejecutor del borrado de cuenta (hoy solo hay solicitud): necesita reintentos e idempotencia por usuario.
* Una importacion/subida supera de forma recurrente el timeout de Caddy/Ktor (150 s / 20 s a Supabase), o `KtorJvmHeapPressure` salta por subidas de `control`.
* El flujo cerrado de importacion se reabre por decision del propietario.
* Cualquier tarea programada (recordatorios, limpieza) que hoy no existe.

Mientras tanto queda el marcador comentado `worker:` en `compose.production.yaml` (no ejecuta nada).

## 3. Sentry: como se distinguen `bot` y `control` hoy (solo verificacion)

* Inicializacion: `Application.kt:73-101` (condicionada a `SENTRY_DSN`). `environment` = `SENTRY_ENVIRONMENT` o, si falta, `AppEnvironment` (`APP_ENV`) (`:104-106`).
  `release` = `SENTRY_RELEASE` (`:87`). `traces_sample_rate` = `SENTRY_TRACES_SAMPLE_RATE`, 0 por defecto. PII desactivada, evento depurado (`scrubSentryEvent`, `:112-130`).
* Etiquetas por evento: `request_id` y `operation` (ruta) (`Application.kt:466-472`). **No hay etiqueta `profile` ni `server_name` explicito.**
* Distinguibilidad hoy: el SDK de Sentry rellena `server_name` con el hostname del contenedor; compose fija `hostname: ktor-bot-1|ktor-bot-2|ktor-control`, asi que cada evento
  ya se atribuye a su replica por `server_name`. El perfil no es una etiqueta filtrable de primera clase.
* Pendiente de decidir (opcional): usar `SENTRY_ENVIRONMENT=production` igual en los tres y anadir `scope.setTag("profile", serverProfile)` en `Sentry.init`/`withScope`
  (cambio de Kotlin: ver hallazgos; no se hace aqui). No se usan environments distintos por perfil: romperian la comparacion pre/post-release.
* `SENTRY_RELEASE` debe fijarse al tag/sha de la imagen (comprobable con la etiqueta OCI `org.opencontainers.image.revision`).

## 4. Otras decisiones de runtime

* **Un solo host, tres Ktor, sin Kubernetes ni broker**: ganancia = aislamiento y despliegue de bot sin corte, no capacidad (aviso en el compose).
* **Heap**: `MaxRAMPercentage` 60 % en bot (limite 768 MB) y 45 % en control (limite 2 GB, por ffmpeg y decodificacion fuera/dentro de heap) +
  `-XX:+ExitOnOutOfMemoryError` (el Dockerfile lo difirio; con `restart: unless-stopped` es seguro). Valores = marcadores a re-medir con carga real en el host elegido.
* **Readiness vs liveness**: contenedor usa `/health/live` (no depende de Supabase); Caddy usa `/health/live` desde el incidente del 2026-10-10 (antes `/health/ready`, que consulta Supabase: ver DEPLOYMENT_PLAN seccion 9; `/health/ready` queda para diagnostico y orquestadores, con resultado cacheado 30 s) (antes: incluye la sonda a Supabase, `Observability.kt:704-`) para sacar replicas.
  Una caida de Supabase deja a Caddy sin upstream sano: devuelve 503 en el borde en vez de 502/timeouts largos (efecto equivalente, mas rapido).
* **IP de cliente**: Caddy confia solo en rangos Cloudflare, usa `CF-Connecting-IP` y reescribe `X-Forwarded-For` con la IP real (`header_up X-Forwarded-For {client_ip}`);
  el backend toma el ULTIMO elemento de `X-Forwarded-For` si el peer esta en `TRUSTED_PROXY_HOSTS` (`OposiBotsAuthRoutes.kt:304-325`). Comprobado en local con Caddy 2.11.7: un `X-Forwarded-For` y un `X-Request-Id` falsos del cliente se sustituyen.

## 5. Hallazgos para el agente de Kotlin / backlog (no se tocaron fuentes)

1. **Perfiles `bot` y `control`** deben montar `/health/ready` y `/health/live` (el Caddyfile los usa en `health_uri`; `consumer` solo monta `/health/live`). El trabajo en curso de `ServerProfile.kt` / `Application.kt` ya los monta en todos los perfiles: confirmarlo con el humo de la imagen `bot`/`control`.
2. **Sentry**: anadir etiqueta `profile` (valor de `SERVER_PROFILE`) a los eventos; hoy solo `request_id`/`operation` + `server_name` por hostname.
3. **Metricas ausentes**: latencia/errores hacia Supabase (no hay `Timer`/`Counter` en `newUpstreamHttpClient` ni en las gateways), gauge de readiness, metricas del limitador/Valkey
   (denegaciones, errores de conexion), metricas de worker. Las alertas y paneles solo usan metricas existentes y declaran el hueco.
4. `PROMETHEUS_URL` por defecto = `http://oposicontrol-prometheus:9090` (`PrometheusMetrics.kt:92`); compose lo fija a `http://prometheus:9090` y ademas da el alias de red `oposicontrol-prometheus`.
5. Las consultas de `PrometheusMetrics.kt:417-421` agregan `ktor_http_server_requests_seconds_*` sin filtrar por perfil: con tres contenedores suman bot+control. Aceptable; documentarlo si el panel de estado se interpreta por separado.
6. `ops/monitoring/compose.yaml` (stack historico de un Ktor, red externa `oposicontrol-deploy_default`) y el stack nuevo **no deben ejecutarse a la vez** (dos Prometheus). El nuevo no incluye node-exporter/cAdvisor: si se quieren metricas de host, reutilizar esos servicios adaptando la red.
7. El filesystem raiz de los Ktor es `read_only` con `tmpfs /tmp`: si la JVM o Sentry necesitan otro directorio escribible, el arranque lo revelara en el preflight; no se pudo probar sin construir la imagen.

## 6. Sobrecarga: admision, plazo, borde, limitador, cache y GC (CERT-LOAD-1000, 2026-10-21)

Origen: `OposiBot/docs/ledger/2026/10/CERT-LOAD-1000-2026-10-21.md` (1000 usuarios simultaneos en local: unas colas sin tope
convertidas en 504 a los 25 s, Caddy apartando las dos replicas y escrituras "fantasma"). Rama `fix/v1-overload-admission-control`.
Todos los valores por defecto salen de la medicion LOCAL y hay que re-medirlos en el VPS (`NOT_VERIFIED` en produccion).

| Mando (perfil bot, `backend.bot.env`) | Defecto | Que hace |
|---|---|---|
| `BOT_ADMISSION_ENABLED` | `true` | Admision acotada por replica (`AdmissionControl.kt`). |
| `BOT_ADMISSION_MAX_IN_FLIGHT` | 32 | Peticiones ejecutandose a la vez. Codo local ~42 req/s por replica con ~0.3 s de servicio (~13-17 en vuelo); el pool IO (64) se lleno a partir de ahi. |
| `BOT_ADMISSION_MAX_QUEUED` | 64 | Peticiones esperando turno (FIFO). Mas alla: 503 `SERVER_BUSY` al instante. |
| `BOT_ADMISSION_QUEUE_TIMEOUT_MS` | 2000 | Espera maxima en cola; despues 503 `SERVER_BUSY`. |
| `BOT_ADMISSION_RETRY_AFTER_SECONDS` | 2 | `Retry-After` enviado: base..2*base (fluctuacion contra reintentos sincronizados). |
| `BOT_REQUEST_DEADLINE_MS` | 15000 | Plazo total por peticion (cola incluida), menor que `response_header_timeout 25s` del borde. |
| `PROFILE_IDENTITY_CACHE_TTL_MS` | 30000 (0 = off, max 120000) | Cache del estado de cuenta (`CachingCanonicalProfileDirectory`). |

* **Admision**: semaforo justo + cola acotada, en la fase `Plugins` (despues de la correlacion y de StatusPages). Exentas `/health*` y
  `/metrics`. Un 503 `SERVER_BUSY` de admision no ha ejecutado nada. Metricas: `admission_in_flight`, `admission_queued`,
  `admission_admitted_total`, `admission_rejected_total{reason=queue_full|queue_timeout}`, `admission_queue_wait_seconds`.
  Limitacion conocida: una subida multipart lenta ocupa su permiso mientras dura (en bot solo imagenes del foro).
* **Plazo cooperativo**: el transporte de Supabase no EMPIEZA una llamada con el plazo vencido y acorta el timeout de las LECTURAS al
  tiempo que queda; nunca corta una escritura en curso. **Punto sin retorno**: tras la primera escritura de negocio correcta
  (cualquier POST/PATCH/DELETE o RPC salvo `app_server_now` e `idem_begin`; tambien una subida/borrado de Storage) el plazo deja de
  aplicarse y la peticion termina. `idem_complete`/`idem_release` corren siempre. Asi un 503 `SERVER_BUSY` por plazo significa
  "no se aplico ninguna escritura de negocio" y la clave de idempotencia queda liberada (el reintento con la misma clave ejecuta una
  vez). Metrica `request_deadline_exceeded_total{stage=before_upstream|upstream_read}`. No interrumpe hilos bloqueados (ver
  `withUpstreamIoParallel`); el trabajo abandonado se acota porque no arranca trabajo nuevo.
* **Borde (Caddy, pool bot)**: sin salud pasiva (`max_fails`/`fail_duration`): Caddy cuenta como fallo pasivo tambien el
  `response_header_timeout` y no se puede limitar a fallos de conexion. Solo salud activa en `/health/live` cada 5 s (timeout 4 s).
  El 503 de Ktor es una respuesta y pasa tal cual (sin `lb_retry_match`, `handle_response` ni reescritura de `Retry-After`);
  `lb_retries 2` / `lb_try_duration 3s` solo reintentan fallos de CONEXION (`dial_timeout 1s`). **Coste aceptado**: una replica
  que muere sin drenar recibe trafico hasta la siguiente sonda (<= 5 s; con `least_conn` la replica caida, con 0 conexiones, es la
  preferida, asi que en esa ventana la mayoria de peticiones nuevas fallan con 502). Mitigacion pendiente: drenaje en el apagado
  (fallar `/health/live` antes de cerrar el puerto) o desplegar replica a replica esperando la sonda. Lo vigila `verify-ops-config.sh`.
* **Limitador Valkey**: API asincrona de Lettuce, sin hilo de `Dispatchers.IO` mientras espera; tope propio (`commandTimeout`, 2 s) y
  el de Lettuce. Sigue fallando CERRADO (503 `UPSTREAM_UNAVAILABLE`) si Valkey no contesta; `memory` solo en desarrollo.
* **Cache de estado de cuenta**: clave = `sub` verificado; solo cuentas `activa` + onboarding completo; cualquier otro estado se
  relee siempre. Revocacion acotada: un bloqueo hecho en `control` (otro proceso) o en la otra replica se ve como mucho en el TTL
  (30 s); las escrituras de perfil y la solicitud de borrado invalidan la cache de la replica que las atiende. Metrica
  `profile_identity_cache_requests_total{result=hit|miss|bypass}`. El login/refresh (puerta de cuenta del plano de auth) NO usa esta cache.
* **Lecturas en paralelo**: en D2-01/D2-02/D2-03 los hechos de acceso del usuario (suscripcion+plan, concesiones, capacidades,
  especialidad) se leen a la vez; D2-01 lee la especialidad una vez (antes dos); D2-02 lee portadas, nombres e intentos a la vez tras
  el veredicto. Mismas lecturas y misma politica (`decide`); si fallan varias se publica la primera que falle.
* **JVM**: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xlog:gc,gc+cpu:stdout:...` en bot y control (con 1 cpu la ergonomia elegia Serial).
  Las lineas de GC salen en texto plano en stdout junto al log de la aplicacion; Docker las rota (5 x 20 MB).
