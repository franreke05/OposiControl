# Para OposiBot: reauditoría de pagos de tienda, ronda 5 (`c30292c` + `764e334` sobre `cf64d78`)

Fecha: 2026-10-20. **N4-1, N4-2, N4-4, N4-5 y N4-6 = FIXED. N4-3 = PARTIAL (P3). No hay P0/P1/P2 nuevos.**

Método: `git archive 764e334` en `scratchpad\store-reaudit5\tree` (los tres SHA existen como `commit`). PG17 con la imagen fijada por digest, en contenedores propios `reaudit5-*`; todos se eliminaron al terminar (`docker ps -a | grep reaudit5` no devuelve nada). No se tocaron `oposi-exam-local-*`, `oposi-control-e2e-*`, ningún worktree ni MAIN.

## Veredictos

| ID | Veredicto | Evidencia |
|---|---|---|
| N4-1 | FIXED | `StorePayments.kt:536-541`: `deliverable = liveCredit \|\| sandbox.allows(SANDBOX, userId)`, y la ruta pasa el `userId` del JWT (`StorePaymentRoutes.kt:109-111`, el único que llama). El modo `accept` fuera de local/test no arranca (`:294`). `gate()` (`:666-674`) sigue siendo el único camino al crédito. `StoreRound4RegressionTest`: 9/9 en verde |
| N4-2 | FIXED | En PG17, después de 25 reentregas con `mark(retenido, LIVE_CREDIT_DISABLED, N=20)` la fila queda `retenido\|0`. El control (`pendiente`, N=20) acaba `muerto\|20`. Stats: `retenido_decision:2`, sin `pendiente` añadido. Con el interruptor apagado (`{REFUNDS,SUBS}` o `null::text[]`) se reclama 0 veces. Con G1 encendido se reclama 1 vez, y una segunda llamada inmediata da 0. Una reentrega mientras está reclamado da `reclamo=null`. Después: `CREDITED`, `procesado\|CREDITED`, 0 reclamos más, un segundo crédito da `REPLAY`, 1 fila de libro y libro = saldo |
| N4-3 | PARTIAL (P3) | Si la lectura está truncada, el cursor no avanza y se escribe el log ALERT (`StorePaymentRoutes.kt:238-244`). La limitación residual se describe abajo (N5-1) |
| N4-4 | FIXED | `StoreAppStore.kt:95-96`: la huella es `sha256(signedPayload)` en el webhook. History usa `event.fingerprint`. El test `StoreServerSyncTest` está en verde |
| N4-5 | FIXED | Con 300.020 filas, `Index Scan using ix_tienda_bandeja_atasco`, 0,14 ms (antes 92 ms con Seq Scan). Dentro de la función (auto_explain anidado), las dos ramas usan índice (`atasco` y `huerfano`) |
| N4-6 | FIXED | Mi carrera D1 con `app_store_reconcile_orphan_step`, 3+3 iteraciones: D1a `a=REPLAY b=BUSY row=REVERSAL_HELD` y D1b `REVERSAL_RECORDED`. Las esperas son `Lock:advisory`. `deadlocks=0`, libro = saldo, `app_wallet_reconcile=0`. Dos pasos a la vez sobre el mismo huérfano, 3 veces: uno `REVERSAL_RECORDED` y el otro `BUSY`, con 1 sola fila en `eventos_pago` |

## Regresión

- `db-pg17-gate.sh` del árbol archivado: `db-pg17-gate OK`, rc=0 (609 s; 30 pendientes; R/S/D1/D2/N4-x/F1..F5).
- `:backend:ktor:test`: 2223 tests, 1 fallo y 1 omitido. El fallo fue `AdminCatalogReadRoutesTest` por `UncompletedCoroutinesError` (timeout de 1 min, con 3 gates PG17 en paralelo). Al repetir la clase sola: 15/15 en verde. Lo atribuyo a la carga, no al cambio. Las 10 clases Store*: 0 fallos.

## Mutantes (todos tienen que fallar el paso 6)

| Mutante | Resultado |
|---|---|
| mutA (cerrojo de la reversión bajado) | FAIL R1.x/R3.x `REVERSAL_ORPHAN_HELD` |
| mutA2 (cerrojo del reembolso bajado) | FAIL R2.x/R4.x, `huerfanos con pedido: '4'` |
| mutC (cerrojo del crédito detrás de los bucles) | FAIL R3/R4, `deadlock detected`, 40P01 en D1 **y en D2** |
| mutH (reconcile con fila antes que clave) | FAIL `race b: deadlock detected` |
| NUEVO mutS (paso sin try-lock de clave; el texto sigue en un comentario y el VERIFY pasa) | FAIL D2.1/D2.2 `orphan_step ... (40P01)` |
| NUEVO mutP (`pending` reclama cualquier retenido si el array no está vacío) | FAIL `N4-2 interruptor apagado: '1'` |

## Hallazgos nuevos

| ID | Sev | Dónde | Escenario | Arreglo |
|---|---|---|---|---|
| N5-1 | P3 | `StorePaymentRoutes.kt:236-245` | Si hay más de 100 páginas desde el cursor, este no se mueve y la ventana solo crece (`end=now`). Cada 6 h se releen las mismas 100 primeras páginas y nunca se llega a las siguientes; el inicio solo avanza con el tope de 180 días. Es un atasco permanente de la recuperación de huecos, no un aviso repetido. El ALERT es solo una línea de log, sin métrica ni regla. Los webhooks siguen funcionando, así que hace falta que fallen las entregas y que haya más de 100 páginas a la vez | Si la lectura está truncada, avanzar el cursor hasta la fecha del último aviso leído (History va en orden), o guardar el `paginationToken`. Publicar un gauge con su alerta |
| N5-2 | Info | `102900:498` | Queda la sobrecarga antigua `app_store_inbox_pending(integer, boolean)`. Con `true` reclama también los retenidos de G1/D8; no acredita, porque se vuelven a retener. Una llamada con `null` sin tipo da `42725 not unique`. PostgREST resuelve por nombre, así que hoy no afecta | Hacer `drop` en la próxima migración |
| N5-3 | Info | `StorePayments.kt:905` | Cada vuelta empieza con el cursor a null: 50 huérfanos con `ERROR` persistente en cabeza dejarían sin procesar a los siguientes. Ya pasaba igual con el lote anterior y es poco probable | Rotar el cursor entre vueltas o empujar al final los `ERROR` |

Revisado sin hallazgos: G1 y D8 siguen apagados por defecto. El sandbox solo se acredita con la allowlist o con `accept`. No hay secretos en los logs nuevos (solo cuentas y nombres de clase). Las funciones nuevas son `security definer`, con `search_path=''` y permiso solo para `service_role` (VERIFY = 0). Ningún retenido se pierde: el rollback los devuelve a `pendiente`.

## H-04 (informativo)

Lo confirmo en el código:

- `AttemptService.kt:498-539` y `:545-549` no tienen rama para `ATTEMPT_EXPIRED`. Ese caso cae en `else`, que da `UPSTREAM_CONTRACT_ERROR`, y ese error es 502 (`BackendErrors.kt:1015`), no el 409 de `:1027`.
- Ninguna migración emite `ATTEMPT_EXPIRED`.
- `assertions.sql:4246` acepta respuestas fuera de plazo.
- El test `h04TheRpcsNeverEmitAttemptExpired...` deja escrito ese comportamiento. No se arregló nada.

## Sin verificar

- `promtool` y `verify-ops-config.sh`.
- `db-pg17-gate.test.sh`; en su lugar construí mutantes equivalentes.
- El comportamiento real de Apple y Google: que History devuelva el mismo `signedPayload` y en orden cronológico, que es lo que supone N5-1.

Artefactos: `scratchpad\store-reaudit5\` (`work\gate_*.log`, `work\d2\`, `work\ktor_*.log`, `sql\`, `mut*\`).
