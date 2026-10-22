# (2026-10-22) APLICADAS EN MAIN con GO del propietario: SEC-V1-01, RB-16, RB-17 (parte Control), RB-18 y 20261023100400; VERIFY 0. Detalle en docs/brain/CURRENT_STATE.md.

# RELEASE_BLOCKERS — V1 (diciembre 2026)

Fecha: 2026-10-19 · Estado de salida: **NO APTO PARA PRODUCCIÓN**.

Un bloqueante solo se cierra con evidencia ejecutada (test, gate o comprobación en entorno autorizado), registrada aquí con SHA. Detalle técnico de cada ID en `SECURITY_AUDIT.md`.

Formato: `ID | SEV | COMPONENTE | REPRODUCCIÓN | IMPACTO | CORRECCIÓN | RESPONSABLE | TEST | ESTADO | SHA`

| ID | SEV | Componente | Criterio verificable de cierre | Responsable | Estado | SHA |
|---|---|---|---|---|---|---|
| RB-01 (SEC-V1-01) | P1 | RLS/GRANT contenido en MAIN | VERIFY de `20261023100000` = 0 en MAIN tras GO; antes, VERIFY de solo lectura en MAIN para confirmar la forma de `etiquetas_pregunta`/`lotes_importacion_preguntas` | Control (Database) + GO propietario | FIX_READY_LOCAL (PG16 8/8, PG17 apply/rollback OK). VERIFY de solo lectura en MAIN (GO 2026-10-19, migración NO aplicada): `content_client_write_privilege`=39 (solo `authenticated`, 13 tablas × I/U/D; `anon` 0), `content_client_write_policy`=13 (las 13 `*_admin_all`, incluidas `etiquetas_pregunta` y `lotes_importacion_preguntas`), `content_service_role_write_missing`=0, `es_admin_ignores_account_state`=1, `perfiles_update_admin_branch`=1 → idéntico al control negativo del test | develop 2cab55b. GO-1 (2026-10-19): preflight MAIN 70/70 true, 2 admins activos, 0 Edge Functions; checkpoint en `docs/audit-v1/evidence/SEC-V1-01/`. Pendiente GO-2 (aplicar) |
| RB-02 (SEC-V1-02) | P1 | Rate limit admin en perfil control | Commit del fix + variables `ADMIN_RATE_LIMIT_*` en `/etc/oposibots/production/backend.control.env` + 429 observado en staging | Control + propietario (secretos) | FIXED_IN_WORKTREE · RUNTIME_VERIFIED local | develop 7fb9000 + diff |
| RB-14 | P1 | `ktor-control` no arranca en producción con las plantillas: `AdminAccessRequests.kt:1671-1677` exige `AUTH_RATE_LIMIT_HMAC_SECRET` en todo perfil no-dev, la plantilla F-09b no lo da a control | Decisión: clave propia de control para el seudónimo (p. ej. reutilizar `ADMIN_RATE_LIMIT_HMAC_SECRET`) o excepción documentada a F-09b; arranque control-producción con plantillas en verde | Control + propietario | FIXED_IN_WORKTREE (sin commit): `AdminAccessRequests.kt` usa `ADMIN_RATE_LIMIT_HMAC_SECRET` si falta el del consumidor (F-09b intacto). Test `controlInProductionUsesItsOwnAdminSecretForTheEmailPseudonym` + 31/31 del gateway; Ktor real `control` `APP_ENV=production` solo con variables de las plantillas common+control: arranca (antes exit 1), `/api/v1/admin/*` sin token 401, token estático 401. Efecto documentado: el seudónimo de correo difiere entre logs de bot y control (correlación por `requestRowId`) | develop 7fb9000 + diff |
| RB-15 | P1 | Gate PG17 de `develop` falla en `assertions.sql` («frozen set = active and not annulled, in exam order»), además del manifiesto (DEBT-156b) | Gate PG17 completo en verde sobre el candidato | Control (Database) | PARCIAL. Causa: `assertions.sql` no se actualizó con e5a66bf (`20261010100000`, decisión 2026-10-10: el intento congela también las anuladas). Corregido sin commit: 5 aserciones del bloque Slice 3 (conjunto P1–P4+P6, posiciones 1..5, P6 ANNULLED sin guardar, estados finales con P6, recuento 5 en el endurecimiento) + mutación (f) de `db-pg17-gate.test.sh` sustituida (la antigua quedó invertida). Gate PG17.6 completo en verde sobre copia (25 pendientes de develop y 26 con SEC-V1-01): VERIFY=0, assertions OK, rollback = bootstrap. PENDIENTE: manifiesto de develop (DEBT-156b, IC-05): 6 `security_*` + `20261023100000` fuera de `PENDING_MIGRATIONS.txt`, 4 sin bloques ROLLBACK/VERIFY; fichero en disputa con Bot → decisión de propietario | develop 7fb9000 + diff |
| RB-03 (SEC-V1-03) | P1 | E6 ausente en v1-server e integración | Árbol candidato contiene a301ea7; tests JWT aud/exp y `/metrics` en verde sobre el candidato | Bot (rebase) + Control (verifica) | COMPAT_VERIFIED_BY_BOT (merge --no-commit de cb50ee3 con 2cab55b en worktree desechable: ktor 2308/0; aud ajena/sin aud/sin exp → 401; /metrics 404; 429 en la 121.ª). Se cierra al integrar (plan v2 en INTEGRATION_CONFLICTS) | 2cab55b + cb50ee3 |
| RB-04 (SEC-V1-04) | P1 | FA39 PKCE opcional | `AUTH_PKCE_MODE=required` en env bot + arranque no-dev falla con otro valor; exchange/reset sin verificador → 400 | Bot | RETEST_PASS OposiControl (Ktor real de aa5e3ab: production/dev/development sin `required` → exit 1; local+required: exchange/reset sin verificador → 400 sin llamar a GoTrue; 111/111 tests auth). SEC-V1-08 MITIGATED: B1–B3, B5 OK; residual B4 (si la última petición es la del atacante, su challenge vale) → App Links/Universal Links (Bot E4) o aceptación del riesgo | aa5e3ab (local, sin push) |
| RB-05 (SEC-V1-05) | P1 | Reembolso fuera de orden (ORDER_REFUND_PENDING → 409 en 1a203b9: retest PASS, BackendErrorsTest 36/36) | Test PG17: refund(E1) en pendiente → paid(E2) no acredita | Bot | RETEST_PASS OposiControl (gate PG17 + escenarios R1–R5 propios: retenido, reintentos, segundo reembolso, resolve acreditar/confirmar → ORDER_REFUND_PENDING, pedido expirado + pago tardío, reconcile limpio, camino feliz P2). Pendiente: `ORDER_REFUND_PENDING` en BackendErrors (E1) | 1ec2b37 (local, sin push) |
| RB-06 (SEC-V1-06) | P1 | Reembolsos off → 503 silencioso | Evento de reembolso persistido siempre, o arranque falla sin la variable | Bot + decisión propietario | OPEN | 86f0da9 |
| RB-07 (SEC-V1-07) | P1 condicional | Sin proveedor de pago real | Si V1 vende packs: adaptadores Apple/Google con tests de firma. Si no: decisión escrita «V1 sin compra con dinero» | Propietario → Bot | OPEN · DECISIÓN | 86f0da9 |
| RB-08 (INF-A1) | P1 | CI no corre en push a `develop` ni `integration/**` | `ci.yml` incluye esas ramas y el HEAD candidato tiene CI verde | Control (Release) | FIX_IN_WORKTREE, **NO publicar** sin decisión de coste: repo PRIVATE, 8 jobs ubuntu-latest, último run verde ≈ 39 min facturables (tests 25 m), push no cancela runs previos; plan/cuota de Actions NO_VERIFIED (requiere scope `user` en gh). Disparadores propuestos (sin commit): `pull_request` (cualquiera; cancela runs previos del PR) + `push` a `main`, `develop`, `feature/**`, `integration/**` (no cancela). Con el ritmo actual de ramas `feature/**` de Bot (cada push = 1 run completo ≈ 39-43 min facturables), el coste lo dominan los push de `feature/**`, que YA disparan CI hoy; el cambio solo añade `develop` e `integration/**` (estimación: +1 run ≈ 40 min por push a esas ramas) | develop 7fb9000 + diff |
| RB-09 (INF-C2/C3/C6) | P1 | Migraciones CONTRACT (revoke a service_role del ledger, drop column/view, drop function) | Plan de despliegue conjunto imagen+migraciones con backup y orden fijado; ensayo en copia local | Control (Release) + Bot | CLOSED 2026-10-22: plan V1_PRODUCTION_DEPLOY_PLAN ejecutado en MAIN. Backup `main-ndiooyyqtaeysnedywer-20261022T183321Z` (RPO 18:34:10Z) con restauracion de prueba OK; recaptura F3.6 sin deriva (ensayo OK, huella = referencia salvo orden de ACL); F4 40/40 VERIFY = 0 al aplicarse + F4.3b + D-10; F5 bot-1, bot-2 y control en `0bb7efe02086`, healthy | 86f0da9 |
| RB-10 (INF-B1) | P1 | `.env` de desarrollo con service_role de MAIN | El `.env` local de desarrollo apunta a stack local/rama; clave de MAIN fuera del equipo de desarrollo o rotada | Propietario | CLOSED 2026-10-22 (rotada): el propietario borro en Supabase la clave secreta `default`; el backend de produccion corre con `v1_servidor` (ready 200, especialidades 200, comprobado por OposiControl). Una copia local de la clave borrada ya no sirve | — |
| RB-11 | P1 | Integración Bot+Control sin candidato válido | Rama nueva develop@7fb9000 + v1-server + panel@29a019f, inventarios regenerados, gates backend/data/features/PG17 en verde | Control (prepara) + propietario (autoriza merge) | OPEN | — |
| RB-12 | P1 | Verificación runtime | E2E local sobre el candidato (journeys `ops/local-exam` + panel), Android físico, iOS runtime | Bot (QA) + Control (panel) | OPEN · NOT_VERIFIED | — |
| RB-13 | P1 | Barrido BOLA ejecutado | `e2-v1-night01-idor.mjs` (o equivalente propio) ejecutado con 0 accesos cruzados | Bot E2 + Control reverifica | PARCIAL: barrido de Bot `eaa546b` válido (control positivo Z0) con 0 accesos cruzados, pero solo cubre de verdad intentos/{id} (7 subrutas), opositeca y mass-assignment de perfil. I4 usa un id aleatorio. Pendiente segunda pasada: pedidos/verify, canjes, OposiRed, retos, recursos, store, BFLA admin | eaa546b (revisado en solo lectura; no re-ejecutado para no escribir en QA) |

## No bloqueantes con plazo (antes de diciembre)

ECO-04…ECO-13, SEC-V1-08…21, PERF-H1…H10, INF-A4…A12. Ver documentos respectivos.

## Descartados en este ciclo (con evidencia)

| Hipótesis | Evidencia | Resultado |
|---|---|---|
| `20261022100200` falla en MAIN por exámenes sin comunidad | MAIN: 3 exámenes, 0 con `comunidad_id` null (consulta de solo lectura 2026-10-19) | No bloquea |
| `20261022101900` falla por estados de foro no previstos | MAIN: `publicaciones_oposired` y `comentarios_oposired` sin filas | No bloquea |
| Algoritmo JWT degradable (HS256/none) | Solo `Algorithm.ECDSA256`; auth0 rechaza `alg` distinto | OK |
| Sandbox de pago activable en producción | `PaymentProviders`: solo `APP_ENV` local/test, si no exit 78; CHECK de `proveedor` sin `sandbox` en producción | OK |

## Hallazgos nuevos del checkpoint de MAIN (GO-1)

| ID | SEV | Componente | Criterio de cierre | Responsable | Estado |
|---|---|---|---|---|---|
| RB-16 (SEC-V1-01c) | P1 | 15 políticas de `storage.objects` con `es_admin()` para escritura en `exam-resources`, `news-assets` (público), `question-resources` y `recursos_pregunta`: un JWT de admin sube o borra objetos directamente, sin Ktor | `20261023100100_security_storage_admin_write_revoke` aplicada en MAIN con GO y VERIFY = 0 | Control (seguridad) + GO | FIX_READY_LOCAL (ampliada 2026-10-20 a **14** políticas: + `p_storage_news_images_update/_delete` del bucket EXISTENTE `news-images` vía `can_manage_content()` = `es_admin()`; la detección también busca `can_manage_content(`). PG16 `StorageAdminWriteRevokeMigrationTest` 5/5 tras la ampliación. PG17 con imagen Supabase y `postgres` no superusuario: sin pertenencia al dueño → PREFLIGHT, nada cambia; con pertenencia aplica, VERIFY 0, admin denegado en los 4 buckets, lectura admin y service_role intactos, rerun no-op, rollback idéntico. NOT_VERIFIED: si el rol que aplica migraciones en MAIN pertenece a `supabase_storage_admin` (lo detecta el PREFLIGHT) |
| RB-17 (SEC-V1-01b) | P1 | 5 tablas admin más con `*_admin_all` y 10 tablas de usuario con `propio OR es_admin()`; `authenticated` tiene I/U/D en todas (LIVE_VERIFIED, GO 2026-10-19). Lo más grave: `tokens_dispositivo` e `invitaciones_referido` | Migración de seguimiento (contenido admin → solo Ktor; tablas de usuario → quitar la rama admin), coordinada con Bot para los datos de usuario | Control (admin) + Bot (datos de usuario) + GO | **Parte de Control APLICADA EN MAIN 2026-10-22 (VERIFY 0)**; tablas de usuario pendientes de 102700 (V1). Cobertura local (2026-10-21), todo pendiente del GO de MAIN:** (1) tablas de usuario (`perfiles_usuario`, `tokens_dispositivo`, `sesiones_estudio`, `configuraciones_usuario`, `preguntas_favoritas_usuario`, `cola_repaso_usuario`, `recomendaciones_usuario`, `abandonos_examen`, `uso_recursos_pregunta`) → `20261022102700` de Bot; (2) `registros_reto_diario` y `lotes_actualizacion_ranking` → `20261022101200` (+101700); (3) **las 4 tablas admin restantes** (`opositeca_items`, `etiquetas_opositeca`, `invitaciones_referido`, `preguntas_parseadas_staging`) → `20261023100500_security_admin_tables_write_revoke` (Control): `*_admin_all` fuera y sin escritura de clientes, salvo la invitación PROPIA del usuario (`invitaciones_referido_insert_propias`), que se conserva. `AdminTablesWriteRevokeMigrationTest` 6/6 (control negativo de MAIN, 42501 para admin/usuario/anon, invitación propia sí y ajena o con recompensa no, service_role escribe, rollback exacto, PREFLIGHT cerrado ante 6 estados desconocidos). VERIFY esperado en MAIN antes: 11 privilegios y 4 políticas; después: 0. |
| RB-18 (SEC-V1-24) | P1 | `es_moderador_o_admin()` ignora `estado_cuenta`: un moderador/admin BLOQUEADO conserva por PostgREST la cola de reportes, `acciones_moderacion` (ALL) y soporte/oposired (15 políticas; `authenticated` con S/I/U/D en las 9 tablas). LIVE_VERIFIED por catálogo 2026-10-20 | `20261023100300_security_moderator_requires_active_account` aplicada en MAIN con GO y VERIFY = 0; la superficie de escritura directa sigue en RB-17 | Control (seguridad) + GO; aviso a Bot (oposired/soporte) | FIX_READY_LOCAL: `ModeratorActiveAccountMigrationTest` 4/4 (control negativo MAIN, bloqueado = usuario, activo sigue, rollback exacto, PREFLIGHT cerrado). Expediente propio, separado de SEC-V1-01 y RB-16 |

## Pagos de tienda (Bot `feature/v1-p3-store-payments` @ `8859ee4`, auditoría independiente 2026-10-19)

Método: copia con `git archive`, 70 tests de tienda (8 adversariales Kotlin de OposiControl) y gate PG17 más 8 escenarios SQL propios (S1–S8), todo en verde. Requisitos: `STORE_PAYMENTS_AUDIT_REQUIREMENTS.md`.

| ID | SEV | Hallazgo | Responsable | Estado |
|---|---|---|---|---|
| PAY-F1 | P1 | Caddy no enrutaba `/api/v1/webhooks/{payments,store}/*`: con deny-by-default respondía 404 | Control (infra) | FIXED_IN_WORKTREE (`Caddyfile.production:102`, `Caddyfile.hostcaddy:63`; `caddy adapt` 2.11.7 OK, verify-ops-config OK) |
| PAY-F2 | P1 | Un REFUND_REVERSED que llega antes que su REFUND, o con el REFUND diferido, queda en NOOP y después el REFUND cobra al usuario; nada pasa a revisión | Bot | FIXED en `9e4ecd0`, reauditado (S1, N1, mutante) |
| PAY-F3 | P1 | Con `allowlist`, una compra sandbox suma en ingresos (`admin_dashboard_economy_totals` no filtra `entorno`) | Control | FIX_READY_LOCAL: `20261023100200_security_revenue_excludes_sandbox` (PREFLIGHT exige `entorno` y la función). Gate PG17 completo sobre copia de `9e4ecd0`: producción suma, sandbox no; rollback = bootstrap. Baseline de Bot con la función en `3e630db`: gate PG17 completo en verde con mi migración y su baseline sin retoques. `AdminV1Routes.kt:8009` NO se cambia: es la comprobación de producto en uso antes de borrarlo y debe contar también las compras sandbox (verificado en código). Pendiente: `AdminAnalytics.kt:1014` (Control, en la integración) |
| PAY-F4 | P1 | Con los reembolsos apagados, el diferido agota 200 intentos (~16 h) y desaparece de la cola: pérdida silenciosa | Bot | FIXED en `9e4ecd0`, reauditado (S5 reforzado, N2, mutante) |
| PAY-F5 | P2 | La revisión tras REFUND_REVERSED no puede restaurar los OT (D3); `admin_payment_order_resolve` es de Control | Control tras D3 | OPEN · CONFIRMED (S2) |
| PAY-F6…F12 | P2 | Error reintentable de Apple → 422; suscripción de Pub/Sub opcional; raíz de Apple sin fijar; sin App Store Server API ni Notification History, y Voided limitado; token de cuenta null aceptado en base; revocación de suscripción diferida; REFUND_REVERSED huérfano sin registro | Bot | OPEN |
| PAY-R6 | P2 legal | `tienda_cuentas` y pedidos se borran en cascada con la cuenta (D6) | Propietario | DECISIÓN |

## Retests de Bot (todos en copias, sin push)

| SHA | Corrección | Resultado OposiControl |
|---|---|---|
| `1ec2b37` | SEC-V1-05 reembolso fuera de orden | PASS (R1–R5) |
| `aa5e3ab` | SEC-V1-04 PKCE obligatorio / SEC-V1-08 binding | PASS / MITIGATED con residual B4 (requiere App Links) |
| `1a203b9` | ORDER_REFUND_PENDING → 409 | PASS (BackendErrorsTest 36/36; camino panel → `mapSupabaseDomainError` revisado) |
| `13bbdf6` | 102700: escritura cliente revocada en 9 tablas de usuario | PASS (T1–T4) y compatible con `20261023100000`: gate PG17 completo en verde con las dos |

| `11bae54` | grant de prueba de assertions.sql:3703 revocado | PASS (T1–T4 al final de la transacción; gate PG17 completo con 1023) |

## Reauditoría de pagos de tienda (`94a867c` + `9e4ecd0`)

F2, F4, F6 (con fallo OCSP real), F7, F8, F10 y F12: FIXED, con tests invertidos verificados por mutantes. F11: sin cambios (decisión D11). Regresión: ktor 2181/0, gate PG17 OK.

| ID | SEV | Hallazgo nuevo | Responsable | Estado |
|---|---|---|---|---|
| PAY-NEW-1 | P1 | Carrera: un REFUND_REVERSED de una compra aún no vista hace commit durante la transacción de crédito. Queda huérfano para siempre y el reembolso posterior cobra al usuario | Bot | FIXED en `1cabc65`, reauditado con dos sesiones (R1, R3b) |
| PAY-NEW-2 | P1 | La misma carrera con un REFUND huérfano: el pedido queda pagado aunque la tienda devolvió el dinero, sin alerta | Bot | FIXED en `1cabc65`, reauditado (R2, R3) |
| PAY-NEW-3..7 | P2 | Claims de la bandeja, dead-letter y retención, alertas, REVERSAL_EARLY, raíz de Apple en PEM | Bot | FIXED en `ad88bc0`, reauditado (promtool OK, verify-ops-config.test.sh exit 0) |

## Reauditoría ronda 3 (`1cabc65` + `ad88bc0`): sin P0/P1

| ID | SEV | Hallazgo | Responsable | Estado |
|---|---|---|---|---|
| PAY-N3-3 | P2 | El lock por compra solo se comprueba como texto: si se mueve debajo de la búsqueda del huérfano, el gate sigue en verde y la carrera NEW-1 vuelve (mutA/mutA2). Falta un test de concurrencia en el gate o en CI | Bot | OPEN · CONFIRMED |
| PAY-N3-1 | P2 | Deadlock 40P01 entre reconcile y un reintento de crédito con contención en la cartera; se autocorrige y el dinero queda consistente | Bot | OPEN · CONFIRMED |
| PAY-N3-2,4,5,7 | P2/Info | Marca HELD obsoleta, mark con reclamo null, seq scan de la bandeja, filas HELD sin purgar | Bot | OPEN |
| PAY-N3-6 | P2/Ops | Dead-letter «nunca» por defecto: alerta de atasco disparada para siempre y sin regla absent() | Bot + decisión del propietario (N) | OPEN |
