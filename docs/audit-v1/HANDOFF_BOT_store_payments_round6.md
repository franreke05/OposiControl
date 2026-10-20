# Para OposiBot: reauditoría de pagos de tienda, ronda 6 (`999a50b` + `c17a804` sobre `764e334`)

Fecha: 2026-10-20. **N5-1 = FIXED (con N6-1, P3, en el gauge). N5-2 = FIXED. N5-3 = FIXED. H-04 = FIXED en el contrato (409), pero el 409 solo se alcanza en teoría.** No hay P0/P1/P2 nuevos.

Método: los tres SHA dan `commit` con `git cat-file -t`. Se extrajo `git archive c17a804` en `scratchpad\store-reaudit6\tree`. PG17 con la imagen fijada por digest, en contenedores `reaudit6-*`, que se borraron al terminar (`docker ps -a | grep reaudit6` no devuelve nada). No se tocaron otros contenedores, worktrees ni MAIN. En el repositorio no se cambió nada salvo crear este archivo.

## Veredictos

| ID | Veredicto | Evidencia |
|---|---|---|
| N5-1 | FIXED | `StorePaymentRoutes.kt:230-262` y `103200:118-177`. Añadí 4 tests propios con un responder de History falso a `StoreServerSyncTest` (solo en el árbol del scratchpad) y pasan los 4. (a) **250 páginas en 3 vueltas**: los tokens `q101` y luego `q201` se guardan, el cursor no se mueve, todas las peticiones usan la misma ventana, al final cursor = fin de la ventana y se ingieren los 250 avisos en orden. (b) **Token rechazado (400)**: el token se descarta y el cursor queda igual. Durante 6 h no se hace ninguna llamada. Pasadas 7 h se relee desde `cursor-1h` sin token y se llega al final. (c) **5xx con el token guardado**: el token y el cursor se mantienen. PG17: `poll_page` deja el cursor (`7000\|tA`) y sobrescribe el token (`7000\|tB`). `advance` con un valor menor da `7000\|7000\|-`: se borra el token y el cursor no retrocede. En la primera fila, cursor = inicio (`4242\|tF\|4242`). Un token vacío o una ventana invertida dan `STORE_REQUEST_INVALID`. Un token a null con la ventana puesta incumple `tienda_sondeo_pagina_check`. Las funciones son `security definer`, con `search_path=""` y permiso solo para `service_role`. Alerta: `promtool check rules`: `SUCCESS: 20 rules found`. `promtool test rules` (propio): dispara a 6h05 con una réplica a 1 y otra a 0, no dispara a 5 h y un solo 0 reinicia el `for` |
| N5-2 | FIXED | En el catálogo de PG17 tras 31 migraciones: `app_store_inbox_pending(integer,text[])` es la única sobrecarga. Ningún `prosrc` la llama. `pending(1, null)` → `0`, ya sin `42725`. Grep en Kotlin, scripts y assertions: solo `(integer, text[])`; un test comprueba que no se envía `p_incluir_retenidos` |
| N5-3 | FIXED | `StorePayments.kt:929-951`: el cursor `orphanCursor` se conserva entre vueltas y vuelve a null al llegar al final. `orphan_step` (`103100:166-187`) convierte cualquier excepción en `ERROR` y devuelve la fila, así que el cursor siempre avanza. Test `orphanReconcileResumesTheNextRunFromTheCursorWhenARunHitsTheCap` en verde |
| H-04 | FIXED (contrato), solo alcanzable en teoría | `AttemptService.kt:533,550,567` → 409 (`BackendErrors.kt:1027`). El test `h04AnAttemptExpiredOutcomeFromTheDatabaseIsThe409OfTheContract` cubre answer, finish y abandon y pasa. **Ninguna función de la base emite `ATTEMPT_EXPIRED`**: el `prosrc like '%ATTEMPT_EXPIRED%'` en PG17 → `none`, y en las migraciones solo aparece en comentarios. La regla real del límite de tiempo sigue siendo una **decisión de producto pendiente** (sigue en verde `h04AnOpenAttemptLongPastTheExamDurationHasNoDeadlineAndIsStillOpen`) |

## Regresión

- `db-pg17-gate.sh` del árbol: `rc=0` en 532 s, con 31 pendientes, VERIFY = 0, assertions, rollback == bootstrap, carreras de tienda y F1..F5.
- `:backend:ktor:test`: **2229 tests, 0 fallos, 0 errores, 1 omitido** (los 2225 de Bot más mis 4).

| Mutante (los de la ronda 5, sobre `c17a804`) | Resultado: tiene que fallar el paso 6 |
|---|---|
| mutA | FAIL R1.x/R3.x `REVERSAL_ORPHAN_HELD` |
| mutA2 | FAIL R2.2/R4.2, `huerfanos con pedido: '2'` |
| mutC | FAIL R3.2, 40P01 en D1 y D2 |
| mutH | FAIL `deadlock detected`, D2.1 |
| mutS | FAIL `deadlock detected`, D2.1/D2.2 40P01 |
| mutP | FAIL `N4-2 interruptor apagado: '1'` |

## Hallazgos nuevos

| ID | Sev | Dónde | Escenario | Arreglo |
|---|---|---|---|---|
| N6-1 | P3 | `StorePaymentRoutes.kt:233-234, 251-256, 306-318` | El gauge refleja la última lectura de **la propia réplica**, no la base. (1) Con 2 réplicas, A deja el gauge a 1 y B termina la ventana. A sale antes de tiempo (`return 0`) durante 6 h, y también después si B gana el sondeo: sigue exportando 1 y `max()==1` dispara un **falso** `StoreAppleHistoryIncomplete` (test propio `reaudit6_gaugeStaysOneAfterAnotherReplicaFinishedTheWindow`). (2) Una réplica reiniciada empieza con el gauge a 0. Si cada vuelta falla con 5xx/401/429 sobre el token guardado, el token sigue atascado y el gauge sigue a 0, así que la alerta **calla** (`reaudit6_a5xxOnTheStoredTokenKeepsTokenAndCursor`). No se pierde dinero ni avisos (los webhooks siguen funcionando) | Justo después de `pollState`, poner `StoreInboxMetrics.historyIncomplete(state?.pendingPage != null)` (también en la salida temprana). Así el gauge sigue a la base en cada vuelta |
| N6-2 | Info | `103200:167`, `StorePaymentRoutes.kt:253` | Si Apple diera un token de más de 4096 caracteres, `savePollPage` lanzaría la excepción antes de guardarlo y antes de poner el gauge: se releerían las mismas 100 páginas cada vuelta y la alerta no saltaría. Es teórico (los tokens de Apple son cortos) | Poner el gauge antes, o registrar el fallo con una métrica |

Revisado sin hallazgos: el diff no toca el crédito, la firma, el sandbox ni G1/D8. No hay doble crédito ni crédito sin un proveedor verificado. Los logs nuevos no incluyen el token ni secretos. Si una vuelta falla a medias, lo ya ingerido se deduplica: no se pierde ni se salta nada. El 4xx de Apple llega como `STORE_PURCHASE_INVALID` (422 < 500) y 401/403/429/5xx como `UPSTREAM_UNAVAILABLE` (503): el token solo se descarta con 400/404.

## Sin verificar

- El comportamiento real de Apple: cuánto dura el token, si un token caducado devuelve 400 y el orden de History.
- `verify-ops-config.sh` y `db-pg17-gate.test.sh` (no se ejecutaron).
- N5-3 en PG17 con 50 filas `ERROR` reales: solo se cubrió con el test unitario y la lectura del código.

Artefactos: `scratchpad\store-reaudit6\` (`work\gate_*.log`, `work\ktor_*.log`, `work\extra_tests.kt`, `work\probe.sh`, `promtest\`, `mut*\`).
