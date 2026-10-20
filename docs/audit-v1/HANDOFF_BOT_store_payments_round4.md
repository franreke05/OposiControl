# Para OposiBot — reauditoría de pagos de tienda, ronda 4 (`6987c1b` + `ae2028a` + `e9e9b93`)

Fecha: 2026-10-20. **N3-1 a N3-5 = FIXED, N3-6 y N3-7 = PARTIAL (las dos quedan como decisión del propietario). Sin P0/P1 nuevos. Hay dos P2 nuevos.**

Método: copia con `git archive` de `6987c1b` (el gate) y de `e9e9b93` (Kotlin) en `scratchpad\store-reaudit4\`. PG17 con la imagen fijada por digest, en contenedores propios, todos eliminados al terminar. No se tocaron el worktree de Bot, los stacks `oposi-*` ni MAIN.

## Verificación

| ID | Veredicto | Evidencia |
|---|---|---|
| N3-1 | FIXED | Gate verde, D1 en las dos vueltas. Además lancé mi propia carrera con 3 sesiones psql, 3 iteraciones en cada orden. D1a (reintento de crédito con la cartera ocupada y después reconcile) da `a=REPLAY b=0 row=REVERSAL_HELD`. D1b (primero reconcile) da `REVERSAL_HELD` o `REVERSAL_RECORDED`. Las esperas observadas son `Lock:advisory`, sin errores. `pg_stat_database.deadlocks=0`, libro = saldo y `app_wallet_reconcile` = 0 |
| N3-2 | FIXED | Con la reversión huérfana ya religada por el crédito (`REVERSAL_HELD`), un `mark('procesado','REVERSAL_ORPHAN_HELD')` obsoleto devuelve `true` y la fila sigue en `REVERSAL_HELD\|procesado`. `mark(...,'REPLAY')` sobre una fila HELD también la deja en `REVERSAL_HELD` |
| N3-3 | FIXED | Mutantes en la tabla de abajo: los cuatro hacen fallar el paso 6 |
| N3-4 | FIXED | `mark` con un reclamo null lanza `STORE_REQUEST_INVALID`. Sobre una fila liberada con un reclamo ajeno devuelve `false` |
| N3-5 | FIXED (con un residuo P3) | Con 300k filas, reconcile (con `true`, con `false` y con plan genérico `force_generic_plan`) usa `Index Scan using ix_tienda_bandeja_huerfano`: 0,5–1,1 ms. La rama de huérfanos de `inbox_stats` también usa el índice (0,55 ms) |
| N3-6 | PARTIAL | Existen `store_payments_enabled` y la regla `StoreInboxMetricsAbsent`. El N del dead-letter sigue a 0 por defecto (decisión del propietario, documentada). `promtool` no se ejecutó en esta ronda |
| N3-7 | PARTIAL / aceptado | `purge(30)` borró 4 de 8 filas: se quedan `REFUND_HELD`, `REVERSAL_HELD`, `REFUND_ORPHAN_HELD` y `REVERSAL_ORPHAN_HELD`; caen `REFUNDED`, `REPLAY`, NULL y `SANDBOX_IGNORED`. Es lo contrario de lo que proponía la ronda 3 (purgar al salir de revisión): es seguro, pero esas filas crecen sin límite. Decisión registrada en DECISIONS_PENDING |

Regresión:

- `db-pg17-gate.sh` del árbol archivado: `db-pg17-gate OK`, rc=0 (10 min 31 s).
- `:backend:ktor:test` en `e9e9b93`: 2209 tests, 0 fallos, 0 errores, 1 omitido.

## Mutantes (N3-3)

| Mutante | Resultado del gate |
|---|---|
| mutA: cerrojo de la reversión bajado justo encima del cerrojo de la cartera | FAIL: `race R1.1: 'REVERSAL_ORPHAN_HELD' (esperado 'REVERSAL_RECORDED')` y R3.x |
| mutA2: cerrojo del reembolso bajado justo encima de `return query` | FAIL: `R2.1: 'REFUND_ORPHAN_HELD\|pagada\|1\|0'`, R4.x `pagada\|1\|0`, `huerfanos con pedido: '4'` |
| mutC: cerrojo del crédito movido detrás de los bucles de huérfanos | FAIL: R3.x y R4.x, más D1 `WARNING ... (40P01)` |
| mutH: reconcile con el cuerpo de 102900 (fila antes que clave; el texto `pg_try_advisory_xact_lock` se conserva en un comentario y el VERIFY sigue verde) | FAIL: `race b: ERROR: deadlock detected` |

Una nota sobre los mutantes. Mi primera variante ponía el cerrojo justo después del `select`. Fallaba en `assertions.sql`, no en las carreras, porque `perform` reescribe `FOUND`. Por eso esa variante no prueba la carrera y la descarté; las válidas son las de la tabla.

## Hallazgos nuevos

| ID | Sev | Dónde | Escenario | Arreglo propuesto |
|---|---|---|---|---|
| N4-1 | P2 | `StorePayments.kt:517-518` | Con G1 apagado (`LIVE_CREDIT=false`, el defecto) y una tienda activada, `products()` sigue listando los packs consumibles de producción. El usuario paga y `verify` responde 503 para siempre. Play lo reembolsa a los 3 días; Apple no: el cobro queda sin entrega hasta que se encienda G1. No hay doble crédito, pero es un cobro sin producto | Filtrar también `CONSUMABLE` cuando `!liveCredit`, salvo para sandbox o la allowlist |
| N4-2 | P2 | `StorePayments.kt:700` | Los aplazamientos `LIVE_CREDIT_DISABLED` y `SUBSCRIPTIONS_DISABLED` quedan en `pendiente` y cuentan como intento. Con el N=20 recomendado para el dead-letter, esos avisos pasan a `muerto` en unos 4 días y no se aplican al encender G1/D8, lo que contradice DECISIONS_PENDING. Además, `StoreInboxPendingStuck` queda disparada mientras G1 esté apagado | Tratarlos como `retenido` (sin contar intento) y volver a pedirlos cuando su interruptor esté encendido |
| N4-3 | P3 | `StoreAppStore.kt:173`, `StorePaymentRoutes.kt:238` | Get Notification History se corta en 100 páginas y aun así avanza el cursor hasta `now`: se pierden los avisos de las páginas siguientes | Avanzar el cursor solo si `nextToken == null`, o guardar el token |
| N4-4 | P3 | `StoreAppStore.kt:165` frente a `StorePayments.kt:678` | La huella de History es `sha256(signedPayload)` y la del webhook es `sha256(rawBody)`. El mismo aviso da `misma_huella=false`, y el log «different body» sale por error | Hashear el mismo contenido en los dos sitios |
| N4-5 | P3 | 102900 `app_store_inbox_stats`, primera rama | `estado in (pendiente, retenido, muerto)` hace un Parallel Seq Scan: 92 ms con 300k filas | Índice parcial `(estado) where estado in (...)` |
| N4-6 | Info | 103000:294-326 | Reconcile retiene hasta 200 cerrojos de clave y de cartera hasta que termina su transacción; los créditos en vivo de esos usuarios esperan. No hay ciclo de cerrojos | Limitar el lote o hacer commit por fila desde el job |

Revisado sin hallazgos:

- G1 está apagado por defecto. El único constructor en producción usa `settings.decisions`, y `gate()` es el único camino hacia `app_store_credit_purchase` y `app_store_upsert_subscription`.
- El sandbox solo se acredita con la allowlist o con `accept`.
- Las respuestas de la App Store Server API se verifican con `SignedDataVerifier`, y se exige el mismo `purchaseKey`, producto y tipo.
- Los logs no contienen secretos (solo nombres de variables, motivos y nombres de clase).
- La suscripción con el mismo cerrojo por clave y REPLAY: S3 en verde.

## Sin verificar

- `promtool` y `verify-ops-config.sh` en esta ronda.
- `db-pg17-gate.test.sh` completo (yo construí mutantes equivalentes).
- El comportamiento real de Apple y Google (todo con pruebas y payloads simulados).

Artefactos: `C:\Users\franr\AppData\Local\Temp\claude\C--Users-franr-AndroidStudioProjects-OposiControl\1d986d0b-f4d8-458b-85bf-c26607484af6\scratchpad\store-reaudit4\` (`work\gate_*.log`, `work\d1\`, `sql\`, `mut*\`).
