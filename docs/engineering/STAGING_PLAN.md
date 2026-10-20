# Staging aislado: plan técnico (2026-10-20)

Estado: **PLAN. No desplegado ni integrado.** Desplegar staging, crear recursos de pago (un proyecto Supabase o un servidor) e integrar ramas requieren un GO específico del propietario.

Objetivo: un entorno donde probar la integración (develop + panel + servidor V1 de Bot) con la topología de producción (`ops/deploy/compose.production.yaml`: Caddy, `ktor-bot-1/2`, `ktor-control`, Valkey, Prometheus, Grafana y Alertmanager) sin compartir nada con MAIN: ni datos, ni identidades, ni secretos, ni alertas.

## 1. Lo que ya está validado en local y lo que solo staging puede validar

| Aspecto | Local (`oposi-control-e2e`, hoy) | Necesita staging |
|---|---|---|
| Esquema PG17 + migraciones V1 + RPC de Control | Sí: 12 recorridos de datos, 8 de interfaz, latencias | Migraciones aplicadas con el rol real de despliegue (propietario de `storage.objects`, PREFLIGHT de RB-16) |
| Autenticación | Stub de GoTrue (ES256, JWKS local) | **GoTrue real**: confirmación de correo, refresh, logout, protección de contraseñas filtradas |
| Storage | Storage API v1.79 en contenedor, políticas reproducidas | Buckets y políticas reales; pruebas de escritura directa denegada (RB-16) |
| Borde | Sin Caddy ni TLS | Caddy con TLS, enrutado `@bot` y `@control` (incluidos los webhooks), deny-by-default |
| Límites | Limitador en memoria | **Valkey** (fail-closed fuera de dev), límite de mutaciones admin |
| Observabilidad | Logs de Ktor | Prometheus, reglas, Alertmanager y Sentry por entorno |
| Pagos | Sandbox HMAC local | Sandbox de Play y App Store con cuentas de prueba; G1 y D8 apagados por defecto |

## 2. Separación

1. **Base de datos.** Un proyecto Supabase PROPIO de staging (recomendado, porque reproduce GoTrue, Storage y PostgREST gestionados), en la misma región que MAIN y en un plan sin coste añadido si cabe. No vale una branch de MAIN: comparte organización y facturación y, sobre todo, la tentación de copiar datos.
   - Esquema: se construye **solo con migraciones**, en el mismo orden que MAIN (historial `MAIN_MIGRATION_HISTORY`) más las pendientes del candidato. Nunca con `pg_dump` de MAIN.
   - Datos: solo sintéticos. Los fixtures de `ops/local-exam/sql/9x_*` y `99z_control_e2e_fixture.sql` sirven de semilla. **Ningún dato personal de producción.**
2. **Autenticación.** El GoTrue de ese proyecto, con sus propias claves JWT y JWKS (Ktor verifica `iss = <staging>/auth/v1`; un token de MAIN no valida en staging, ni al revés). Cuentas de prueba propias por rol (superadmin, admin, admin bloqueado, moderador, usuario), con contraseñas en el gestor de secretos y nunca en el repositorio. Protección de contraseñas filtradas activada desde el primer día.
3. **Storage.** Buckets propios (`news-assets`, `news-images`, `recursos-pregunta`…) con las políticas que dejan **aplicadas** SEC-V1-01, RB-16 y RB-18. Staging es el ensayo general de esas tres migraciones antes del GO de MAIN: PREFLIGHT, aplicar, VERIFY = 0, ROLLBACK y volver a aplicar, todo registrado.
4. **Credenciales.** Juego completo e independiente: `SUPABASE_SERVICE_ROLE_KEY`, anon key, `ADMIN_RATE_LIMIT_HMAC_SECRET`, `AUTH_RATE_LIMIT_HMAC_SECRET`, `PAYMENTS_SANDBOX_SECRET`, `METRICS_SCRAPE_TOKEN`, la contraseña de Valkey y el DSN de Sentry de staging. Ninguno se reutiliza de producción. Viven en el gestor de secretos del host de staging (fichero `env` con permisos 600 o, mejor, su equivalente gestionado), nunca en el `.env` del repositorio. **La `.env` local actual apunta a MAIN con service_role (riesgo ya registrado): staging no debe heredarla.**
5. **Configuración.** Las mismas plantillas que producción (`backend.common/bot/control.env.example`) con `APP_ENV=staging`, para que se apliquen las reglas fail-closed de producción (Valkey obligatorio, sin limitador en memoria, `/metrics` con token). Hostnames propios (`staging-api.<dominio>`, `staging-control.<dominio>`), con el panel apuntando a ellos y certificados propios.
6. **Observabilidad.** Prometheus, Grafana y Alertmanager propios, o los de producción con la etiqueta `env=staging` y una **ruta de alertas que no avise a la guardia**. Sentry con `environment=staging`. Los logs siguen las reglas de producción (sin tokens, correos ni secretos), también verificadas en staging.
7. **Pagos.** Solo sandbox de las tiendas, con G1 y D8 apagados salvo para las cuentas tester. El dashboard de ingresos de Control debe marcar 0 € de producción (20261023100200).

## 3. Topología propuesta (un host)

Un único servidor pequeño (2–4 vCPU) con el `compose.production.yaml` sin cambios de imágenes, un `compose.env` de staging y el mismo `Dockerfile.backend`. Opción alternativa sin coste de nube: el stack `oposi-control-e2e` en una VM propia. Reproduce PG17 y PostgREST pero no GoTrue ni Storage gestionados, así que no sustituye los puntos 2 y 3.

## 4. Proceso

1. **Candidato:** develop actual + rama del panel + servidor V1 de Bot, integrado en una rama de candidato **solo con GO** (hoy no autorizado), resolviendo conflictos según `docs/audit-v1/INTEGRATION_CONFLICTS.md` (plan v2).
2. **Gates antes de desplegar en staging:** `:backend:ktor:test`, dbtest, gate PG17 del candidato, `verify-ops-config.sh`, `promtool`.
3. **Despliegue en staging** con `ops/deploy/release.sh` apuntando al host de staging (GO).
4. **Pruebas en staging:**
   - `ControlJourneysE2ETest` y `ControlPanelUiE2ETest` apuntados a staging por variables de entorno. La guarda de nombre de contenedor se sustituye por una lista blanca de hosts de staging, cambio que hay que hacer en las propias pruebas.
   - IDOR de Bot repetido con log completo.
   - Compras sandbox.
   - Ensayo de las migraciones de seguridad.
   - p50/p95 con la latencia real hasta Supabase.
5. **Criterio de salida:**
   - 12/12 recorridos de datos y 8/8 de interfaz en verde.
   - VERIFY = 0 en SEC-V1-01, RB-16 y RB-18, con su ROLLBACK probado.
   - Sin P0/P1 abiertos de Control.
   - IDOR certificado.
   - Alertas probadas sin avisar a la guardia.

## 5. Decisiones que necesita el propietario

- **Coste:** un proyecto Supabase de staging (recomendado) o una VM con stack autoalojado.
- **Dominio y certificados** de staging.
- **GO** para crear la rama candidata.
- **GO** para desplegar.
- **Quién guarda las credenciales** de staging.
