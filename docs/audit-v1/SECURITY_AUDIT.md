# SECURITY_AUDIT — OposiBot/OposiControl V1

Fecha: 2026-10-19 · Auditor: sesión OposiControl (auditoría independiente de la de E2/OposiBot) · Estado: CICLO 1 (diagnóstico estático + verificación de catálogo en MAIN).

## Alcance y método

| Árbol | Rama | SHA | Papel |
|---|---|---|---|
| `OposiControl` | `develop` (local, no publicado) | `7fb9000` | Ktor canónico + E6 (JWT exp/aud, /metrics, rate limit admin) |
| `OposiControl-PhaseB-Server` | `feature/december-v1-server` | `86f0da9` | Servidor Bot V1 (economía, foro, retos, stats, ranking, referidos, auth FA39) |
| `OposiControl-E6-Panel` | `feature/december-v1-control-panel` | `29a019f` | Panel Control V1 + rutas `AdminControl*.kt` |
| `OposiBot/.claude/worktrees/OposiBot-V1-Integration` | `feature/night-client-hardening` | `0797b79` | Cliente (solo para OAuth/deep links) |

Método: lectura de código y SQL con evidencia `archivo:línea`; referencias OWASP ASVS 4/5 (V2 auth, V3 sesión, V4 control de acceso, V5 validación, V12 ficheros), API Security Top 10 2023 (API1 BOLA, API3 BOPLA, API4 consumo, API5 BFLA), MASVS-AUTH/NETWORK. Una única consulta de **catálogo** (sin datos, sin escritura) en MAIN `ndiooyyqtaeysnedywer` para SEC-V1-01. **No se ha ejecutado ninguna prueba adversarial en runtime todavía** (pendiente stack local, ver plan).

Etiquetas: `CONFIRMED_IN_CODE` (leído), `LIVE_VERIFIED` (comprobado en MAIN/runtime), `HYPOTHESIS` (requiere prueba), `INDEPENDENTLY_REVERIFIED` (re-leído por el coordinador además del agente).

## Superficie de ataque (resumen)

- Público sin auth: `/v1/especialidades`, `/v1/examenes*` (caché 30 s), `/health*`, `/metrics` (protegido por token en develop; abierto en v1-server), `/v1/auth/*` (registro, login, recover, email exchange, OAuth Google/Apple), webhook `/api/v1/webhooks/payments/{provider}` (HMAC).
- Usuario (JWT ES256 Supabase, perfil `bot`): `/api/v1/app/*` — intentos, catálogo, cartera, canjes, pedidos, plan/cupo, retos, stats, ranking, referidos, opositeca, OposiRed (17 rutas, subida de imágenes).
- Admin (perfil `control`, JWT + rol en BD con caché 90 s): `/api/v1/admin/**` (~240 rutas), `/admin/**` y `/content/**` legacy.
- **Fuera de Ktor**: PostgREST directo con la anon key pública + JWT de usuario → depende solo de GRANT/RLS (ver SEC-V1-01).
- Infra: Caddy → bot-1, bot-2, control; Valkey (rate limit); Prometheus/Grafana/Alertmanager; Sentry.

Inventario de rutas por perfil: `ops/inventory/route-profiles.tsv` en v1-server (332 combined / 100 bot / 240 control), resumido en `API_PERFORMANCE_AUDIT.md`.

## Hallazgos

Formato: `ID | SEVERIDAD | COMPONENTE | REPRODUCCIÓN | IMPACTO | CORRECCIÓN | RESPONSABLE | TEST | ESTADO | SHA`

### P0

Ninguno confirmado en este ciclo. Ver RELEASE_BLOCKERS: varios P1 son bloqueantes de release aunque no sean P0.

### P1

**SEC-V1-01 | P1 | RLS/GRANT contenido (MAIN) | ESTADO: FIX_READY_LOCAL (migración `20261023100000`, no aplicada a MAIN) | SHA: develop 7fb9000 + diff**

> Corrección (2026-10-19, ciclo 2): el «12 tablas» del ciclo 1 incluía `perfiles_usuario`, que no tiene `*_admin_all` sino `update_propio` con rama `OR es_admin()`. Alcance real: 11 tablas con `*_admin_all` verificadas en MAIN + `etiquetas_pregunta` y `lotes_importacion_preguntas` (misma forma en el baseline, no releídas en MAIN) + la rama admin de `perfiles_usuario_update_propio` + `es_admin()` sin `estado_cuenta`.
> Evidencia de la corrección: `ContentAdminWriteRevokeMigrationTest` 8/8 (PG16 embebido): control negativo (admin activo y bloqueado escriben en MAIN-shape), 42501 para admin activo/bloqueado/usuario/anon en INSERT/UPDATE/DELETE de las 13 tablas, admin activo conserva lectura de borradores, bloqueado no, perfiles solo propios, service_role intacto, rollback exacto, idempotencia y 6 preflights cerrados. PG17.6 imagen Supabase (gate en copia): aplica en orden, VERIFY = 0, rollback = huella del bootstrap, re-aplicación idéntica.
> Hallazgo lateral SEC-V1-23 (P2, preexistente): `anon` no tiene EXECUTE sobre `es_admin()` (baseline `:1072-1073`) y las políticas SELECT `... OR es_admin()` son `TO anon`; como RLS evalúa sus condiciones antes del `WHERE`, cualquier lectura anónima directa de `examenes` falla con 42501 si existe un borrador. Sin impacto en Ktor (service_role); la migración no lo cambia (test `lecturaAnonimaDeBorradorFallaIgualAntesYDespues`).
- Reproducción: con JWT de un admin y la anon key pública: `PATCH {SUPABASE_URL}/rest/v1/examenes?id=eq.<id>` cuerpo `{"precio_tickets":0,"tipo_acceso":"gratuito"}`. Igual en `preguntas`, `opciones_pregunta`, `soluciones_pregunta`, `preguntas_examen`, `recursos_pregunta`, `especialidades`, `comunidades_autonomas`, `etiquetas`, `noticia`, `tipo_noticia`.
- Evidencia: consulta de catálogo en MAIN (2026-10-19): `has_table_privilege('authenticated', t, INSERT|UPDATE|DELETE) = true` en las 12 tablas, cada una con política `*_admin_all` `FOR ALL`; `es_admin()` no menciona `estado_cuenta`. Baseline: `supabase/baseline/main_shape_subset.sql:2698-2737, 2894-2926, 2089-2101`. `auditoria_log` y `transacciones_cartera` sí están cerradas.
- Impacto: un admin (o un JWT admin robado, p. ej. del móvil personal del admin) modifica precios, acceso y contenido **sin pasar por Ktor**: sin `auditoria_log`, sin validaciones (`examenPatchFrom`, RichMarkup, máquina de estados de noticias), sin rate limit E6. Un admin **bloqueado** conserva escritura (Ktor lo bloquea, `es_admin()` no).
- Corrección: migración `revoke insert, update, delete on <12 tablas> from authenticated;` + eliminar políticas `*_admin_all FOR ALL` dejando solo SELECT; opcional `es_admin()` exige `estado_cuenta='activa'`. Verificar antes que ningún cliente (panel, OposiBot) escribe directo por PostgREST (`verify-no-direct-supabase` lo cubre para el panel).
- Responsable: Database (Control). Archivo nuevo de migración: sin conflicto con Bot. **Requiere GO del propietario para MAIN.**
- Test: bloque VERIFY PG17 que afirme `has_table_privilege(...)=false` por tabla + E2E local: PATCH PostgREST con JWT admin → 401/403.

**SEC-V1-02 | P1 | Rate limit de mutaciones admin inoperante en producción (DEBT-151 cerrado solo en papel) | ESTADO: FIXED_IN_WORKTREE (sin commit) + RUNTIME_VERIFIED local | SHA: develop 7fb9000 + diff**

> Corrección: `adminMutationRateLimiterFromEnvironment` (`AdminMutationRateLimit.kt`) con variables propias de control (`ADMIN_RATE_LIMIT_MODE`, `ADMIN_RATE_LIMIT_HMAC_SECRET`, `ADMIN_RATE_LIMIT_VALKEY_URL`; F-09b intacto) y cableado en `Application.kt` (`CombinedRoutingDeps.adminMutationLimiter`). Ktor real (`installDist`, `SERVER_PROFILE=control`, Valkey 8.1 local, Supabase falso, entorno limpio sin `.env`): 120 mutaciones → handler, la 121.ª → 429 `RATE_LIMITED` con `Retry-After`, lecturas no limitadas, contador en Valkey con clave HMAC. `APP_ENV=production`: sin modo, con `memory` o con Valkey caído el proceso termina con exit 1; con Valkey válido arranca y el token estático recibe 401.
> Hallazgo lateral RB-14 (P1, preexistente): `AdminAccessRequests.kt:1671-1677` exige `AUTH_RATE_LIMIT_HMAC_SECRET` fuera de desarrollo en TODOS los perfiles, pero la plantilla F-09b no se lo da a `control` → con las plantillas documentadas `ktor-control` no arranca en producción (comprobado en ejecución real).
- Reproducción: en `ktor-control` (`SERVER_PROFILE=control`, `ops/deploy/compose.production.yaml:143`), >120 POST/min a `/api/v1/admin/...` con JWT admin → ninguno recibe 429.
- Causa: `Application.kt:517-518` fija `authRuntime = null` en CONTROL; `Application.kt:776-777` pasa `limiter = deps.authRuntime?.rateLimiter` (null); `AdminMutationRateLimit.kt:38` sale si `limiter == null`. El test `AdminMutationRateLimitTest` inyecta un limitador, por eso pasa.
- Impacto: JWT admin robado = mutaciones ilimitadas (concesión de tickets, accesos, contenido).
- Corrección: construir un limitador Valkey propio para el perfil control (VALKEY_URL + HMAC secret) aunque no haya runtime de auth de consumidor; fallar el arranque fuera de desarrollo si el limitador falta.
- Responsable: Control (develop). Toca `Application.kt` (archivo caliente compartido con Bot) → parche pequeño, coordinar.
- Test: arranque con `configureServerProfile(CONTROL, …)` y cableado de producción → 429 en la mutación 121; arranque control no-dev sin limitador → falla.

**SEC-V1-03 | P1 | Regresión de seguridad al integrar: v1-server no contiene E6 | ESTADO: CONFIRMED_IN_CODE | SHA: 86f0da9 vs 7fb9000**
- Evidencia: `git merge-base --is-ancestor a301ea7 86f0da9` → no. v1-server `AppV1Routes.kt:313` `JWT.require(ECDSA256).withIssuer(issuer)` sin `withAudience`, sin rechazo de `exp` ausente; `PrometheusMetrics.kt:118-140` sirve `/metrics` sin token si `METRICS_SCRAPE_TOKEN` no está; `AdminMutationRateLimit.kt` no existe. La rama provisional `integration/bot-backend-on-develop` (cb50ee3) tampoco contiene E6.
- Impacto: desplegar v1-server o resolver conflictos con «theirs» reabre DEBT-095/156a.
- Corrección: integrar develop@7fb9000 en v1-server antes de cualquier candidato; guardas CI (grep `withAudience("authenticated")`, test de `/metrics` 404 sin token).
- Responsable: Bot (rebase de su rama) + Control (verificación).
- Test: conservar `SupabaseJwtVerifierJwksTest` (aud incorrecta / sin exp → 401) y `PrometheusMetricsTest` en el árbol fusionado.

**SEC-V1-04 | P1 | FA39 (vinculación de código de email) se salta omitiendo el verificador | ESTADO: CONFIRMED_IN_CODE (valor de producción NOT_VERIFIED) | SHA: 86f0da9**
- Evidencia: `EmailCodeBinding.kt:33` `AUTH_PKCE_MODE` ausente → `OPTIONAL`; `OposiBotsAuthGateway.kt:903-911` `consumeBinding` devuelve null si `verifier == null`; `OposiBotsAuthRoutes.kt:65-72` y reset `:296-297`. `AUTH_PKCE_MODE` no está en `ops/deploy/backend.bot.env.example` ni en compose.
- Reproducción: app maliciosa que reclama el esquema `oposibots://auth` (cliente `androidApp/AndroidManifest.xml:27`) intercepta `token_hash` → `POST /v1/auth/email/exchange {code}` sin `codeVerifier` → sesión; en `/reset-password` → toma de cuenta.
- Corrección: `AUTH_PKCE_MODE=required` en entorno bot y `requireDeployable()` rechazando otro valor fuera de desarrollo; migrar a App Links / Universal Links verificados.
- Responsable: Bot (archivos auth en edición activa).
- Test: arranque no-dev con PKCE ≠ required falla; exchange/reset sin verificador → 400.

**SEC-V1-05 | P1 | Pago: reembolso fuera de orden se consume y el pago posterior acredita | ESTADO: CONFIRMED_IN_CODE, INDEPENDENTLY_REVERIFIED | SHA: 86f0da9**
- Evidencia: `supabase/migrations/20261022100400_v1_payment_orders.sql:394-405` — el evento REFUNDED se inserta en `eventos_pago` y, si la orden no está `pagada`, devuelve `NOOP` (evento consumido para siempre). El camino PAID (`:309-338`) no consulta reembolsos previos y acredita.
- Reproducción: REFUNDED(E1) con orden `pendiente` → PAID(E2) → saldo acreditado; reintento de E1 → `REPLAY`.
- Impacto: el usuario conserva OT de un pago devuelto (Google RTDN y Apple permiten desorden).
- Corrección: si la orden no está `pagada`, no devolver NOOP: marcar `en_revision` + `refund_pending` que el crédito comprueba y rechaza, o error reintentable que haga rollback del evento.
- Responsable: Bot (migración en su rama; nueva migración correctiva, no editar la ya probada).
- Test: refund(E1) en pendiente, luego paid(E2): saldo sin cambios y orden en revisión/reembolsada.

**SEC-V1-06 | P1 | Reembolsos desactivados por defecto en producción → 503 y pérdida silenciosa | ESTADO: CONFIRMED_IN_CODE | SHA: 86f0da9**
- Evidencia: `EconomyService.kt:342-344, 411-416`. Con `APP_ENV=production` y sin `PAYMENTS_REFUNDS_ENABLED`, todo webhook REFUNDED → 503; el proveedor deja de reintentar a los días; nada queda registrado.
- Corrección: registrar siempre el evento de reembolso (p. ej. `refund_case` estado `pendiente_politica` con OT congelados) o exigir la variable al arrancar en producción.
- Responsable: Bot. Decisión de política de reembolsos: **propietario**.
- Test: variable ausente en producción → arranque falla, o evento persistido y visible en Control.

**SEC-V1-07 | P1 (bloqueante si V1 vende packs) | No existen proveedores de pago reales | ESTADO: CONFIRMED_IN_CODE | SHA: 86f0da9**
- Evidencia: `PaymentProviders.kt:21-24, 94-102` solo `SandboxPaymentProvider` (registrado únicamente con `APP_ENV` `local|test`; fuera, sale con código 78 — correcto). Sin App Store Server API/JWS, sin Google Play Developer API, sin ASSN v2 ni RTDN (Pub/Sub OIDC).
- Impacto: en producción `POST /payments/orders` → `PAYMENT_PROVIDER_UNAVAILABLE`. **Ninguna prueba con el proveedor local valida cobro real.**
- Corrección: adaptadores por proveedor (acknowledge Google < 3 días, REFUND/REVOKE/CONSUMPTION_REQUEST Apple, `appAccountToken`/`obfuscatedAccountId` ligado a `usuario_id`).
- Responsable: Bot + decisión del propietario (alcance de pagos en V1).
- Test: contrato con payloads firmados grabados; firma inválida, bundle/package erróneo, token de otro usuario.

### P2

| ID | Componente | Evidencia | Impacto | Corrección | Responsable | Estado |
|---|---|---|---|---|---|---|
| SEC-V1-08 | FA39 binding ligado al email, no al token | `20261022102200_v1_auth_code_binding.sql` (match `purpose+challenge[+email_ref]`, 5 vínculos vivos por email) | Atacante pide resend/recover con su challenge + interceptación del enlace → toma de cuenta incluso en modo required | id de vínculo en `redirect_to` exigido al consumir, o solo el vínculo más reciente | Bot | CONFIRMED_IN_CODE |
| SEC-V1-09 | Captcha solo validado en forma; login sin captcha | `OposiBotsAuthRoutes.kt:465-472`, `OposiBotsAuthGateway.kt:224` | Abuso de registro/resend limitado solo por rate limit | siteverify en Ktor o captcha GoTrue | Bot | HYPOTHESIS (config GoTrue no verificada) |
| SEC-V1-10 | Access tokens no revocados al logout; caché de rol admin 90 s | `AppV1Routes.kt:305-323, 1397` | Token robado vive hasta `exp`; admin degradado conserva acceso 90 s | deny-list Valkey en logout/cambio de contraseña o check de `session_id`; `jwt_expiry` 600-900 s | Bot/Control | CONFIRMED_IN_CODE |
| SEC-V1-11 | OAuth sin nonce | cliente `RealAuthApi.kt:563-571`; `OposiBotsAuthGateway.kt:389` | ID token robado usable una vez | nonce obligatorio | Bot (cliente+servidor) | CONFIRMED_IN_CODE |
| SEC-V1-12 | Identidad admin no separada, sin MFA | `AdminAuthorizationGuard.kt:97-118` | Dispositivo personal comprometido → panel | exigir `aal2` en control | Control + decisión propietario | CONFIRMED_IN_CODE |
| SEC-V1-13 | Usuarios anónimos Supabase no rechazados | verificador ignora `is_anonymous` | Solo si anonymous sign-ins activos | rechazar `is_anonymous=true` | Bot | HYPOTHESIS |
| SEC-V1-14 | Rutas app legacy sin gate de estado de cuenta si falta resolver | `AppV1Routes.kt:178-189` | Solo por mala configuración | fallar arranque bot sin resolver | Bot | CONFIRMED_IN_CODE |
| SEC-V1-15 | Auditoría admin incompleta y best-effort | `AdminV1Routes.kt:4396, 4266, 7509, 7537, 5300, 9105, 5730`; best-effort `:8797-8843`; `/content/*` legacy | Cambios de precio/especialidad/acceso sin rastro | `required=true` o triggers AFTER UPDATE | Control | CONFIRMED_IN_CODE |
| SEC-V1-16 | `auditoria_log` append-only solo en develop | migración `20261021100000` ausente en v1-server | Si se despliega v1-server sin develop, service_role puede borrar auditoría | integrar develop antes del gate PG17 | Bot (rebase) | CONFIRMED_IN_CODE |
| SEC-V1-17 | Cursor de foro forjado → 502 | `20261022101900` `app_foro_list` casts fuera del handler; `BackendErrors.kt:429-450` | Ruido 5xx/SLO | validar en `cur_dec` → 400 INVALID_CURSOR | Bot | CONFIRMED_IN_CODE |
| SEC-V1-18 | Carrera de subida de imagen entre instancias | `ForoImages.kt:162-215` (`inFlight` por proceso, `upsert=true`) | Objeto ≠ sha256 registrado (siempre re-codificado) | claim en BD `FOR UPDATE`, `upsert=false` | Bot | HYPOTHESIS |
| SEC-V1-19 | Subidas admin confían en MIME declarado; PDF sin magic bytes | `NewsRoutes.kt:42`, `MediaCompression.kt:165-171, 206-208`, `AdminV1Routes.kt:2503-2512, 982-998` | Políglota en bucket público `news-assets`; DoS parser | sniff de magic bytes, re-encode o rechazo, `%PDF-`, parse con timeout | Control | CONFIRMED_IN_CODE |
| SEC-V1-20 | Contenido gratuito legible por PostgREST/ruta pública sin gate de especialidad, incluidas reservas | baseline `:2703-2719`; `SupabaseV1Gateway.kt:132-145` no filtra `es_reserva` | Bypass de la decisión «especialidad es gate» y del limitador diario | filtrar `es_reserva`; decidir lectura anon | Bot + decisión propietario | CONFIRMED_IN_CODE |
| SEC-V1-21 | Foro: borrado de cuenta conserva texto | `foro_tombstone_user` | RGPD | redactar cuerpo | Decisión propietario | CONFIRMED_IN_CODE |
| SEC-V1-22 | Token estático y `X-Usuario-Id` | `Application.kt:1104, 1137-1149` | Solo dev + loopback literal; correcto. Riesgo latente SEC-003 (XForwardedHeaders) | mantener | — | VERIFIED_OK |

Hallazgos de economía P2 (ECO-04 … ECO-13) detallados en `RELEASE_BLOCKERS.md` y abajo, sección Economía.

## Economía (resumen; detalle de verificación)

Verificado OK en código (no por nombre de test): ledger append-only (trigger BEFORE UPDATE/DELETE, revoke a service_role, `wallet_ledger_hardening.sql:297-311, 438-439`); `saldo >= 0` por CHECK; lock asesor `wallet:<uuid>` en todas las RPC económicas; `UNIQUE(usuario_id, idempotency_key)` con huella y rechazo por discrepancia; precio y días leídos del servidor; débito + concesión en una transacción; reloj de BD; SECURITY DEFINER con `search_path=''` y EXECUTE revocado a public/anon/authenticated; sandbox con HMAC tiempo constante, ventana 300 s, cuerpo ≤ 64 KB.

| ID | Sev | Evidencia | Problema | Corrección | Responsable |
|---|---|---|---|---|---|
| ECO-04 | P2 | `payment_orders.sql:311-317, 394-400` | Dedup de evento no ligado a su orden: evento reutilizado para otra orden → REPLAY silencioso | comparar `compra_id`; si difiere → `en_revision` | Bot |
| ECO-05 | P2 (decisión) | `20261022100500_v1_exam_redeem.sql:244-248, 256-258` | Re-canje con otra clave cobra de nuevo y extiende 60 días | ALREADY_ACCESSIBLE o `extend:true` explícito | Propietario → Bot |
| ECO-06 | P2 | `exam_redeem.sql:230`; `EconomyService.kt:32, 243` | `expectedPrice` opcional: subida de precio entre vista y canje se cobra | obligatorio | Bot |
| ECO-07 | P2 | `20261022101200_v1_daily_challenge.sql:612-627` | Idempotencia de OT gratis ignora el payload | huella + IDEMPOTENCY_KEY_REUSED | Bot |
| ECO-08 | P2 | `wallet_ledger_hardening.sql:442-502`; `AdminV1Routes.kt:7117-7144` | `admin_grant_tickets` sin actor en BD; auditoría en request aparte; límites solo en Ktor | `p_actor` + `require_admin_actor` + auditoría y límites en SQL | Control (función) / Bot (migración) |
| ECO-09 | P2 | `20261022100700_v1_admin_grant.sql:72-102` | Regalo de examen con idempotencia heurística 30 s y actor no validado | clave de idempotencia + `require_admin_actor` | Bot |
| ECO-10 | P2 | `20261022102300_v1_control_rpcs.sql:461-533` | Sin ruta en v1-server para resolver órdenes `en_revision` (existe en panel `AdminControlCommerce.kt:379`) | integrar panel | Control |
| ECO-11 | P2 | `01_overlay_wallet_entitlement.sql:33` | service_role puede escribir `compras_usuario` y `contenido_desbloqueado_usuario`; GUC `app.order_resolve` fijable | revocar escrituras; gate solo-definer | Bot |
| ECO-12 | P2 (legal) | `wallet_ledger_hardening.sql:169-171, 304-307` | Borrar usuario borra en cascada ledger y pedidos (retención contable ~6 años) | pseudonimizar | Propietario/legal |
| ECO-13 | P2 | `exam_redeem.sql:227, 258` | Acceso «30 días» es configurable por examen sin CHECK | CHECK o decisión documentada | Propietario |

## Verificado OK (autenticación/autorización)

- Algoritmo fijado a ES256 (auth0 rechaza `alg` distinto; no hay HS256/none); `iss` fijado; `exp` y `aud` exigidos **solo en develop**.
- JWKS: `kid` ausente → 401; `kid` desconocido → un refresco con anti-tormenta; JWKS vacío/inalcanzable → 503 (fail closed). Riesgo residual: caché caducada se sirve mientras JWKS cae.
- Roles desde BD (`roles_usuario`), nunca de claims; Valkey no cachea roles (solo rate limit). Conceder/revocar admin exige superadmin con re-check bajo lock.
- Perfil `bot` no monta rutas admin; Caddy enruta admin solo a control.
- Limitador Valkey fail-closed (503); modo memoria solo en dev.
- Especialidad inmutable para **cualquier** escritor por trigger (`20261022100600`); el admin tampoco puede cambiarla → **decisión pendiente**: el requisito pide corrección administrativa auditada y hoy no existe.
- `comunidad_id NOT NULL` + FK RESTRICT en exámenes (`20261022100200`).
- BOLA/IDOR: todas las rutas V1 toman el uid de la identidad verificada; las 110 funciones nuevas revocan anon/authenticated y se re-acotan por `p_usuario`. **Pero** el barrido IDOR ejecutable de E2 (`e2-v1-night01-idor.mjs`) está marcado «not yet runnable»: no hay barrido cross-user ejecutado.
- 5xx genéricos con request id; tokens no registrados; respuestas auth `no-store`.

## Infraestructura, secretos y migraciones (INF-*)

| ID | Sev | Evidencia | Problema | Corrección | Responsable | Estado |
|---|---|---|---|---|---|---|
| INF-A1 | P1 | `.github/workflows/ci.yml:3-6` | CI solo en `pull_request` y push a `main`/`feature/**`: develop (rama de release) e `integration/**` nunca pasan CI en push; los merges E6/E7 fueron locales | añadir ramas y exigir checks | Control | CONFIRMED, reverificado |
| INF-A3 | P1 | v1-server borra `ops/deploy/release.sh` y su test en `ci.yml:24` | La integración puede perder el punto único de release | conservar develop | Bot/Control | CONFIRMED |
| INF-B1 | P1 | `.env` local (ignorado) | `SUPABASE_URL`=MAIN + service_role presente | ver RB-10 | Propietario | CONFIRMED (nombres de clave) |
| INF-C2 | P1 | `20261022100300:438-439` | revoke de escrituras de service_role en ledger/cartera: cambio CONTRACT, rollback de imagen imposible tras aplicar | despliegue conjunto | Release | CONFIRMED |
| INF-C3 | P1 | `20261022101600_v1_stats.sql:315-316` | `drop view ranking_ventanas_rapidas`, `drop column ventana_tiempo_medio_ms` | backup previo, CONTRACT | Release | CONFIRMED |
| INF-C6 | P2 | `20261022100600:58`, `101000:605`, `100700:56` | `drop function` (cambio de firma) → PGRST202 en imágenes antiguas | despliegue conjunto | Release | CONFIRMED |
| INF-C8 | P2 | `scripts/verify-migrations-manifest.sh:45` | migraciones `*_security_*` excluidas de todo manifiesto | manifiesto SECURITY | Control | CONFIRMED |
| INF-A4 | P2 | `compose.production.yaml:181-267` | prometheus/grafana/alertmanager sin `read_only`, `cap_drop`, `no-new-privileges` | mismo bloque que Ktor | Control | CONFIRMED |
| INF-A5 | P2 | `compose.production.yaml:189` | `--web.enable-lifecycle` sin auth en red `edge` compartida | quitado (ningún runbook usa `/-/reload` ni `/-/quit`); `verify-ops-config` exit 0 | Control | FIXED_IN_WORKTREE |
| INF-A6 | P2 | `compose.production.yaml` | una sola red `edge`: caddy alcanza valkey/grafana/prometheus | redes edge/data/mon | Control | CONFIRMED |
| INF-A7 | P2 | `compose.production.yaml:163` | contraseña Valkey en línea de comandos | fichero ACL montado | Control | CONFIRMED |
| INF-A8 | P2 | `ops/caddy/body-limit.caddy:12-17` | sin límite de multipart en el borde | `max_size` en @control | Control | CONFIRMED |
| INF-A11 | P2 | `ops/deploy/release.sh:96-127` | `ship` no repite preflight; `redeploy-rolling.sh` no versionado | preflight en ship; versionar | Control | CONFIRMED |
| INF-E1 | P1 (QA) | `PhaseB-Server/ops/local-exam/docker-compose.yaml:7-17` | BD QA sin volumen (causa de la pérdida QA) | volumen nombrado | Bot | CONFIRMED |

Verificado OK: solo caddy publica puertos; Ktor read-only, no-root (uid 10001), imagen fijada por digest, límites de recursos y healthchecks; Grafana sin acceso anónimo; `/metrics` 404 en el borde; Sentry sin PII; Prometheus con bearer desde fichero; gitleaks en CI (pero no corre en develop) con action fijada por SHA.

Pre-checks de datos en MAIN (solo lectura, 2026-10-19): `examenes` 3 filas, 0 sin `comunidad_id` → `20261022100200` no falla; tablas de foro vacías → la reconstrucción de CHECK de `20261022101900` no falla.

## Pruebas adversariales pendientes (entorno local autorizado)

1. Barrido BOLA cross-user sobre las ~100 rutas bot (dos usuarios, recurso del otro → 404/403).
2. PostgREST directo con JWT admin y JWT usuario sobre las 12 tablas (SEC-V1-01) — en BD local, no en MAIN.
3. Carreras económicas: N canjes concurrentes con misma/distinta clave; refund/paid desordenados (SEC-V1-05).
4. Tokens: aud ajena, sin exp, kid desconocido, JWKS caído, token tras logout.
5. FA39: exchange/reset sin verificador; challenge de atacante.
6. Rate limit control (SEC-V1-02) en contenedor con perfil control real.
