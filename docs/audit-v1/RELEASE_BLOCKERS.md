# RELEASE_BLOCKERS — V1 (diciembre 2026)

Fecha: 2026-10-19 · Estado de salida: **NO APTO PARA PRODUCCIÓN**.

Un bloqueante solo se cierra con evidencia ejecutada (test, gate o comprobación en entorno autorizado), registrada aquí con SHA. Detalle técnico de cada ID en `SECURITY_AUDIT.md`.

Formato: `ID | SEV | COMPONENTE | REPRODUCCIÓN | IMPACTO | CORRECCIÓN | RESPONSABLE | TEST | ESTADO | SHA`

| ID | SEV | Componente | Criterio verificable de cierre | Responsable | Estado | SHA |
|---|---|---|---|---|---|---|
| RB-01 (SEC-V1-01) | P1 | RLS/GRANT contenido en MAIN | VERIFY de `20261023100000` = 0 en MAIN tras GO; antes, VERIFY de solo lectura en MAIN para confirmar la forma de `etiquetas_pregunta`/`lotes_importacion_preguntas` | Control (Database) + GO propietario | FIX_READY_LOCAL (PG16 8/8, PG17 apply/rollback OK). VERIFY de solo lectura en MAIN (GO 2026-10-19, migración NO aplicada): `content_client_write_privilege`=39 (solo `authenticated`, 13 tablas × I/U/D; `anon` 0), `content_client_write_policy`=13 (las 13 `*_admin_all`, incluidas `etiquetas_pregunta` y `lotes_importacion_preguntas`), `content_service_role_write_missing`=0, `es_admin_ignores_account_state`=1, `perfiles_update_admin_branch`=1 → idéntico al control negativo del test | develop 7fb9000 + diff |
| RB-02 (SEC-V1-02) | P1 | Rate limit admin en perfil control | Commit del fix + variables `ADMIN_RATE_LIMIT_*` en `/etc/oposibots/production/backend.control.env` + 429 observado en staging | Control + propietario (secretos) | FIXED_IN_WORKTREE · RUNTIME_VERIFIED local | develop 7fb9000 + diff |
| RB-14 | P1 | `ktor-control` no arranca en producción con las plantillas: `AdminAccessRequests.kt:1671-1677` exige `AUTH_RATE_LIMIT_HMAC_SECRET` en todo perfil no-dev, la plantilla F-09b no lo da a control | Decisión: clave propia de control para el seudónimo (p. ej. reutilizar `ADMIN_RATE_LIMIT_HMAC_SECRET`) o excepción documentada a F-09b; arranque control-producción con plantillas en verde | Control + propietario | FIXED_IN_WORKTREE (sin commit): `AdminAccessRequests.kt` usa `ADMIN_RATE_LIMIT_HMAC_SECRET` si falta el del consumidor (F-09b intacto). Test `controlInProductionUsesItsOwnAdminSecretForTheEmailPseudonym` + 31/31 del gateway; Ktor real `control` `APP_ENV=production` solo con variables de las plantillas common+control: arranca (antes exit 1), `/api/v1/admin/*` sin token 401, token estático 401. Efecto documentado: el seudónimo de correo difiere entre logs de bot y control (correlación por `requestRowId`) | develop 7fb9000 + diff |
| RB-15 | P1 | Gate PG17 de `develop` falla en `assertions.sql` («frozen set = active and not annulled, in exam order»), además del manifiesto (DEBT-156b) | Gate PG17 completo en verde sobre el candidato | Control (Database) | PARCIAL. Causa: `assertions.sql` no se actualizó con e5a66bf (`20261010100000`, decisión 2026-10-10: el intento congela también las anuladas). Corregido sin commit: 5 aserciones del bloque Slice 3 (conjunto P1–P4+P6, posiciones 1..5, P6 ANNULLED sin guardar, estados finales con P6, recuento 5 en el endurecimiento) + mutación (f) de `db-pg17-gate.test.sh` sustituida (la antigua quedó invertida). Gate PG17.6 completo en verde sobre copia (25 pendientes de develop y 26 con SEC-V1-01): VERIFY=0, assertions OK, rollback = bootstrap. PENDIENTE: manifiesto de develop (DEBT-156b, IC-05): 6 `security_*` + `20261023100000` fuera de `PENDING_MIGRATIONS.txt`, 4 sin bloques ROLLBACK/VERIFY; fichero en disputa con Bot → decisión de propietario | develop 7fb9000 + diff |
| RB-03 (SEC-V1-03) | P1 | E6 ausente en v1-server e integración | Árbol candidato contiene a301ea7; tests JWT aud/exp y `/metrics` en verde sobre el candidato | Bot (rebase) + Control (verifica) | OPEN | 86f0da9 / cb50ee3 |
| RB-04 (SEC-V1-04) | P1 | FA39 PKCE opcional | `AUTH_PKCE_MODE=required` en env bot + arranque no-dev falla con otro valor; exchange/reset sin verificador → 400 | Bot | OPEN | 86f0da9 |
| RB-05 (SEC-V1-05) | P1 | Reembolso fuera de orden | Test PG17: refund(E1) en pendiente → paid(E2) no acredita | Bot | OPEN · reverificado | 86f0da9 |
| RB-06 (SEC-V1-06) | P1 | Reembolsos off → 503 silencioso | Evento de reembolso persistido siempre, o arranque falla sin la variable | Bot + decisión propietario | OPEN | 86f0da9 |
| RB-07 (SEC-V1-07) | P1 condicional | Sin proveedor de pago real | Si V1 vende packs: adaptadores Apple/Google con tests de firma. Si no: decisión escrita «V1 sin compra con dinero» | Propietario → Bot | OPEN · DECISIÓN | 86f0da9 |
| RB-08 (INF-A1) | P1 | CI no corre en push a `develop` ni `integration/**` | `ci.yml` incluye esas ramas y el HEAD candidato tiene CI verde | Control (Release) | FIX_IN_WORKTREE, **NO publicar** sin decisión de coste: repo PRIVATE, 8 jobs ubuntu-latest, último run verde ≈ 39 min facturables (tests 25 m), push no cancela runs previos; plan/cuota de Actions NO_VERIFIED (requiere scope `user` en gh) | develop 7fb9000 + diff |
| RB-09 (INF-C2/C3/C6) | P1 | Migraciones CONTRACT (revoke a service_role del ledger, drop column/view, drop function) | Plan de despliegue conjunto imagen+migraciones con backup y orden fijado; ensayo en copia local | Control (Release) + Bot | OPEN | 86f0da9 |
| RB-10 (INF-B1) | P1 | `.env` de desarrollo con service_role de MAIN | El `.env` local de desarrollo apunta a stack local/rama; clave de MAIN fuera del equipo de desarrollo o rotada | Propietario | OPEN · CONFIRMED (solo nombres de clave) | — |
| RB-11 | P1 | Integración Bot+Control sin candidato válido | Rama nueva develop@7fb9000 + v1-server + panel@29a019f, inventarios regenerados, gates backend/data/features/PG17 en verde | Control (prepara) + propietario (autoriza merge) | OPEN | — |
| RB-12 | P1 | Verificación runtime | E2E local sobre el candidato (journeys `ops/local-exam` + panel), Android físico, iOS runtime | Bot (QA) + Control (panel) | OPEN · NOT_VERIFIED | — |
| RB-13 | P1 | Barrido BOLA ejecutado | `e2-v1-night01-idor.mjs` (o equivalente propio) ejecutado con 0 accesos cruzados | Bot E2 + Control reverifica | OPEN · script «not yet runnable» | 88d0baf |

## No bloqueantes con plazo (antes de diciembre)

ECO-04…ECO-13, SEC-V1-08…21, PERF-H1…H10, INF-A4…A12. Ver documentos respectivos.

## Descartados en este ciclo (con evidencia)

| Hipótesis | Evidencia | Resultado |
|---|---|---|
| `20261022100200` falla en MAIN por exámenes sin comunidad | MAIN: 3 exámenes, 0 con `comunidad_id` null (consulta de solo lectura 2026-10-19) | No bloquea |
| `20261022101900` falla por estados de foro no previstos | MAIN: `publicaciones_oposired` y `comentarios_oposired` sin filas | No bloquea |
| Algoritmo JWT degradable (HS256/none) | Solo `Algorithm.ECDSA256`; auth0 rechaza `alg` distinto | OK |
| Sandbox de pago activable en producción | `PaymentProviders`: solo `APP_ENV` local/test, si no exit 78; CHECK de `proveedor` sin `sandbox` en producción | OK |
