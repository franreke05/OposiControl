# Contrato Control ↔ Bot: pedidos, reembolsos, accesos y estados de compra (propuesta, 2026-10-20)

Estado: **PROPUESTA de OposiControl; OposiBot respondió Q1-Q3 el 2026-10-20 (abajo). Falta el visto bueno del propietario en la política P2.** Reparto (decisión del propietario): Bot implementa la compra del usuario (pedido, Apple/Google/sandbox, webhooks, acreditación automática, reembolso del proveedor). Control implementa la decisión administrativa y su auditoría. Ninguno duplica lo del otro.

Fuente: código de E7 `cb50ee3` (migraciones 20261022100400 / 102300, `AdminControlCommerce.kt`) y comportamiento **observado** en el stack aislado `oposi-control-e2e` con `ControlJourneysE2ETest` (J08 y J09, 2026-10-20). Cada punto lleva entre corchetes su origen: [E2E] = observado, [código] = solo leído.

## 1. Estados del pedido (`compras_usuario.estado`)

`pendiente → pagada | fallida | cancelada | expirada | en_revision`; `expirada → pagada (tardía, solo proveedor verificado) | en_revision`; `pagada → reembolsada | en_revision`; `fallida | cancelada | reembolsada → en_revision`; `en_revision →` solo la función admin [código: trigger `trg_compras_usuario_order_machine`].

| Quién | Qué escribe | Cómo |
|---|---|---|
| Bot (app) | crea `pendiente` | `POST /api/v1/app/payments/orders` [E2E] |
| Bot (webhook) | `pagada` + crédito al libro; `en_revision` si el importe o el estado no cuadran; `reembolsada` + reversión de los OT sin gastar | `POST /api/v1/webhooks/payments/{proveedor}`, firmado [E2E] |
| Control | sale de `en_revision` | `POST /api/v1/admin/pedidos/{id}/resolver` → `admin_payment_order_resolve` [E2E] |

## 2. Decisiones de Control (solo desde `en_revision`)

| Decisión | Precondición | Efecto | Resultado | Error si no |
|---|---|---|---|---|
| `acreditar` | nunca acreditado | crédito ledger-first de la cantidad CONGELADA del pedido con la misma clave de idempotencia que el webhook (no puede acreditarse dos veces), `pagada` | `CREDITED` [E2E: +50 OT una vez] | `ORDER_ALREADY_CREDITED` |
| `confirmar` | ya acreditado | vuelve a `pagada`, sin mover OT | `CONFIRMED` | `ORDER_NOT_CREDITED` [E2E] |
| `rechazar` | nunca acreditado | `fallida`, sin mover OT | `REJECTED` | `ORDER_ALREADY_CREDITED` |
| `reembolsar` | acreditado y `PAYMENTS_REFUNDS_ENABLED` | política P2 (`app_refund_payment_order`): revierte solo la parte sin gastar, acorta el acceso comprado, cierra `refund_case` | `REFUNDED` / `REFUNDED_P2` | `ORDER_NOT_CREDITED` [E2E], `REFUNDS_DISABLED` |
| cualquiera | pedido fuera de revisión | — | — | `ORDER_NOT_IN_REVIEW` [E2E] |
| la misma ya aplicada | — | nada | `REPLAY` [E2E: acreditar y reembolsar] | — |

Cada decisión escribe `auditoria_log` `pedido_resuelto` (actor = JWT, nota, decisión, resultado) en la misma transacción [E2E]. La nota interna escribe `pedido_nota_interna` [E2E]. El usuario no puede resolver: 403 [E2E].

## 3. Reembolsos: qué hace cada lado

- **Reembolso del proveedor con todos los OT sin gastar** (`payment.refunded` sobre un pedido `pagada`): lo resuelve Bot **solo**. El pedido pasa a `reembolsada`, se revierten los OT y **no** pasa por revisión ni crea `refund_case` [E2E: 50 OT revertidos, `refund_case` = 0]. Control lo ve en el detalle (`estado = reembolsada`) [E2E], y reembolsarlo otra vez es `REPLAY` sin segunda reversión [E2E].
- **Casos que piden decisión** (OT ya gastados, importe distinto, pedido cancelado y luego pagado, reversión con huérfano): Bot los manda a `en_revision` y el admin decide con la tabla del §2. Hoy el E2E solo cubre «importe distinto». **NOT_VERIFIED_E2E**: `reembolsar` P2 con OT gastados; Bot tiene las pruebas SQL (rondas 1 a 3 de la auditoría de pagos).
- **Q1, respondida por Bot el 2026-10-20 [código 100400 `app_refund_payment_order`]:** ningún evento lleva a `en_revision` un pedido `pagada` con OT gastados. El reembolso del proveedor revierte solo los OT sin gastar (fila `devolucion`), acorta en proporción el acceso pagado con los gastados, deja el pedido `reembolsada`, registra un `refund_case` ya resuelto con política `P2` y devuelve `REFUNDED_P2`, sin revisión. En el lado Bot solo entran en `en_revision` el reembolso retenido antes del pago (102400) y los casos de 102800/102900. **La política (proporcional o total, con revisión manual o sin ella) sigue siendo decisión del propietario (SEC-V1-06 / P2).** Consecuencia para Control: hoy la decisión `reembolsar` del panel solo se alcanza desde esas entradas a revisión.

## 4. Accesos concedidos y retirados

- Fuente única: `contenido_desbloqueado_usuario` (vigente = `valido_hasta` nulo o futuro). Control concede (`POST /api/v1/admin/accesos`) y retira (`POST /accesos/{id}/revocar`) con auditoría `acceso_examen_concedido` / `acceso_examen_retirado` [E2E]. Solo sobre exámenes **publicados** (`NotFound: El examen no existe o no esta publicado`) [E2E]. Un usuario no puede concederse acceso: 403 [E2E].
- La concesión por compra o canje la escribe Bot. **Q2, ACORDADO con Bot el 2026-10-20:** cuando Control retira un acceso no toca ni el pedido ni el libro; la retirada queda auditada.
- El RPC `admin_grant` (20261022100700) existe y el panel no lo usa (usa la ruta Ktor de arriba). **Q3 (Bot, 2026-10-20):** Bot no encuentra ningún llamador del `admin_grant` de acceso (100700) fuera del Ktor del panel, ni ninguno desde rutas de cliente. Queda una sola vía: la de Ktor.

## 5. Ingresos

El dashboard de Control cuenta solo pedidos `pagada` de **producción** (`entorno` nulo o `produccion`). El sandbox nunca es ingreso (20261023100200, local). Bot debe escribir siempre `entorno` en pedidos nuevos (lo hace desde 102600).

## 6. Lo que Control NO hace

No crea pedidos, no acredita por su cuenta fuera de `acreditar` en revisión, no habla con Apple ni con Google, no procesa webhooks, no cambia precios de tienda de proveedor (`productos_store`). Los packs que vende la app son `productos_comerciales` de tipo `pack_tickets` (Control los edita, J07). La tabla de Market `paquetes_opositickets` no la lee la app: PRODUCT_DECISIONS_PENDING §15.1.

## 7. Moderador en Control (aviso para Bot, 2026-10-20)

Decisión del propietario: el rol `moderador` entra en el panel **solo para moderar el foro**. Implementado en local en la rama del panel, sin push: lista cerrada de rutas en el guard de Ktor (`/sesion`, `/foro/cola`, `/foro/moderar`, `GET /foro/restricciones`), con rol `moderador` y cuenta activa. No se ha tocado ninguna migración ni ninguna ruta `/api/v1/app`. `admin_foro_moderate` (101900) no comprueba el rol en SQL y solo Ktor llega a ella; su auditoría registra al moderador como actor (verificado en el E2E). Levantar restricciones sigue siendo de admin (`private.require_admin_actor`). No se pudo entregar por mensaje porque la sesión de Bot ya no estaba activa. Si OposiRed (app) supone que «moderación = solo admin», hay que decirlo.
