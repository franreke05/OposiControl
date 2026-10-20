# Para OposiBot — reauditoría de pagos de tienda, ronda 3 (`1cabc65` + `ad88bc0`)

Fecha: 2026-10-19. No se pudo entregar por mensaje porque la sesión coordinadora de OposiBot ya no estaba activa. **NEW-1 a NEW-7 = FIXED. Sin P0/P1 nuevos.**

Método: copia con `git archive`, PG17 en un contenedor propio (ya eliminado) y dos sesiones psql reales. No se ejecutó `store-concurrency-probe.mjs` contra QA ni se tocó el worktree de Bot.

## Verificado

- Carreras R1, R2, R3 y R3b: la segunda sesión espera en el lock por clave y luego se liga correctamente; reconcile en 0.
- Sin deadlock en D2a/D2b (confirmar frente a reversión, en los dos órdenes) ni en D1c (reembolso frente a reintento de crédito).
- NEW-3 a NEW-7 verificados en vivo: claims, dead-letter, purga ≥30 días sin borrar huérfanas, `promtool` «SUCCESS: 18 rules», raíz de Apple en PEM y en DER.
- Mutantes K1, K2, K3 y mutB: fallan sus tests, como deben.
- Regresión: ktor 2189/0 (1 omitido), gate PG17 OK, `verify-ops-config.sh` exit 0. `verify-ops-config.test.sh` también exit 0 («OK (90 mutaciones)», unos 70 min); ya no está sin confirmar.

## Hallazgos nuevos (P2/Info)

| ID | Sev | Dónde | Problema | Arreglo propuesto |
|---|---|---|---|---|
| N3-3 | P2 (importante) | VERIFY de 102900:434-437 (texto) | El lock por clave se comprueba solo como texto. Si se mueve por debajo de la búsqueda del huérfano (mutA en la reversión, mutA2 en el reembolso), el gate sigue en verde y vuelve la carrera NEW-1 (R1 devuelve `REVERSAL_ORPHAN_HELD` contra un pedido pagado). Hoy la única cobertura es el probe contra QA, que no está ni en el gate ni en CI | Una carrera con dos conexiones dentro de `db-pg17-gate.sh` (dblink o un psql en segundo plano), o un job de CI con PG17 efímero |
| N3-1 | P2 | mig:953 → 956/958 frente a 649 → 691/703 | Deadlock 40P01: reconcile bloquea la fila de la bandeja y pide el lock por clave; un reintento de crédito tiene el lock por clave y actualiza la fila (con contención en la cartera). Se autocorrige y el dinero queda consistente | En reconcile, tomar primero el lock por clave y volver a seleccionar la fila `FOR UPDATE SKIP LOCKED`, una por subtransacción |
| N3-2 | P2 | mig:554; `StorePayments.kt:648-660` | `mark` escribe el `*_ORPHAN_HELD` obsoleto encima del `*_HELD` religado por el crédito: huérfanos falsos y después `REPLAY`, con pérdida de la etiqueta HELD | No reemplazar un `*_HELD` puesto por la base, o pasar a `mark` el resultado final de la RPC |
| N3-4 | P2/Info | mig:562 | `mark` con `p_reclamo` null puede marcar filas liberadas (Kotlin nunca envía null) | Rechazar null |
| N3-5 | P2 | mig:944-953, 609-617 | Seq scan de `tienda_bandeja` por `resultado` (unos 90 ms a 300k filas, crece sin retención) | Índice parcial `(recibido_en) where resultado in ('REFUND_ORPHAN_HELD','REVERSAL_ORPHAN_HELD')` |
| N3-6 | P2/Ops | `StorePayments.kt:282-284`; `alert_rules.yml` | Dead-letter «nunca» por defecto: `StoreInboxPendingStuck` queda disparada para siempre y `StoreInboxDeadLetter` no salta nunca. Sin métricas cuando el job está apagado, y no hay regla `absent()` | Un N recomendado para producción (decisión del propietario) y `absent(store_inbox_backlog)` |
| N3-7 | Info | mig:596 | Las filas `*_HELD` de pedidos ya resueltos no se purgan nunca | Purgarlas cuando el pedido salga de revisión |

## Artefactos (scratchpad temporal de OposiControl, copiarlos pronto)

`C:\Users\franr\AppData\Local\Temp\claude\C--Users-franr-AndroidStudioProjects-OposiControl\1d986d0b-f4d8-458b-85bf-c26607484af6\scratchpad\store-reaudit3\`: `copy\` (incluye `Auditor3StoreTest`), `mutA\`, `mutA2\`, `mutB\`, `mutK\` y `work\` (logs y SQL de las sesiones).
