# Re-auditoría ronda 7 — pagos de tienda de OposiBot (`be9e0bd`)

- Fecha: 2026-10-21
- Auditor: OposiControl (revisión independiente, solo lectura)
- Commit revisado: `be9e0bd` (padre `c17a804`), rama local `feature/v1-p3-store-payments`, checkout `OposiControl-P3-store`. Sin push. Pagos siguen apagados.
- Alcance: cierre de N6-1 y N6-2 de la ronda 6 ([HANDOFF_BOT_store_payments_round6.md](./HANDOFF_BOT_store_payments_round6.md)) y regresiones del propio commit.
- Método: `git show`/`git archive` a una carpeta temporal; ningún repositorio editado, sin Docker, sin Supabase.

## Veredicto

**Ronda cerrada.** Sin P0/P1/P2.

| ID | Veredicto | Evidencia |
|---|---|---|
| N6-1 | FIXED | `StorePaymentRoutes.kt:233-234`: `historyIncomplete(state?.pendingPage != null)` justo tras `pollState`, antes del return temprano (`:237`), de la llamada a Apple y de la rama 4xx/5xx. El gauge se recalcula desde la base en cada pasada; entre réplicas solo puede ir retrasado un intervalo del trabajo (300 s por defecto). |
| N6-2 | FIXED en la práctica (residuo P3: N7-1) | `StorePaymentRoutes.kt:259-261`: gauge a 1 y log de alerta antes de `savePollPage`. La migración `20261022103300` sube el límite del token a 65536. |

### Migración `20261022103300`

Correcta: PREFLIGHT que falla en cerrado, ROLLBACK y VERIFY, `lock_timeout 5s`, una transacción. La restricción y la función solo cambian 4096 → 65536; `security definer` y `search_path=''` se mantienen y la ACL (solo `service_role`) se conserva y se verifica. El ROLLBACK no puede fallar: anula `pagina_token` de las filas con más de 4096 caracteres en vez de truncarlo, y la ventana se vuelve a leer (sin saltos; la relectura puede esperar hasta 6 h).

### Pruebas

- Los 3 tests nuevos FALLAN sobre `c17a804` (comprobado copiando el test nuevo sobre el código anterior: 17 tests, 3 fallos) y pasan sobre `be9e0bd` (17/0).
- `:backend:ktor:test` sobre `be9e0bd`: 2228 tests, 0 fallos, 0 errores, 1 omitido (coincide con lo declarado por Bot).

## Hallazgos nuevos

| ID | Sev | Descripción | Propuesta |
|---|---|---|---|
| N7-1 | P3 | Si la base rechaza el guardado de forma persistente, cada pasada empieza con el gauge en 0 y lo sube a 1 al terminar de leer; un scrape intermedio ve 0 y reinicia el `for: 6h` de la alerta (`alert_rules.yml:182-184`), que casi nunca dispara. Además se releen 100 páginas cada 5 min por réplica (riesgo de 429). Bot ya lo reconoce en `docs/payments/ROUND4_FIXES.md:116-117`; queda el log `ALERT` en cada pasada. | Contador `store_apple_history_save_failed_total` con alerta `increase(...) > 0`, o no bajar el gauge a 0 en la ruta que va a leer. No bloquea: deuda técnica. |
| N7-2 | Info | La segunda mitad del test de N6-2 («el token de 5000 se guarda») corre contra un fake con `maxToken = 65536` por defecto: habría pasado antes. La migración solo la cubre el check `N6-2 token largo` del gate (`db-pg17-gate.sh:223-224`). | Ninguna. |
| N7-3 | Info | El ROLLBACK se ejecuta en el paso 5 del gate sin tokens largos (la fila de 5000 se inserta en el paso 6): la rama `length(pagina_token) > 4096` del rollback no se ejercita con datos. | Sembrar un token de más de 4096 caracteres antes del paso 5. |
| N7-4 | Info | En el VERIFY, los nombres `poll_page_token_limit_4096` y `poll_page_check_limit_4096` comprueban 65536; `prosrc like '%65536%'` es frágil; el ROLLBACK no fija `lock_timeout`. | Cosmético. |

Secretos: ningún log nuevo ni existente de `pollAppleHistory`, `notificationHistory` o `StoreInboxMetrics` incluye el token. El diff no toca crédito, firma, sandbox, G1 ni D8.

## Sin verificar

- Gate PG17 de Bot (rc=0, 32 pendientes según Bot), `db-pg17-gate.test.sh`, `verify-ops-config.sh` y `promtool`: no ejecutados.
- ROLLBACK de 103300 con filas de más de 4096 caracteres: solo por lectura.
- Tamaño real de los tokens de Apple y duración real de leer 100 páginas (determinan el peso de N7-1).
