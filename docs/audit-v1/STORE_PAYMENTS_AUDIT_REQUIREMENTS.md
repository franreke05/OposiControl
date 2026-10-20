# STORE_PAYMENTS_AUDIT_REQUIREMENTS — Google Play / App Store

Fecha: 2026-10-19. **Requisitos de auditoría de OposiControl**, no diseño impuesto. La implementación es de OposiBot (E1, `feature/v1-p3-store-payments`, base `1ec2b37`). OposiControl audita y retestea contra esta lista. Etiquetas: `CODE` (verificado en `86f0da9` salvo indicación), `DOC` (documentación oficial abierta), `NV` (no verificado).

## Bloqueantes previos (estado actual del código)

| ID | Requisito | Evidencia |
|---|---|---|
| PAY-R1 | El crédito no puede depender de un precio en EUR fijo contra la tienda. Hoy `app_credit_payment_order` solo acredita si importe, moneda y producto coinciden con el catálogo; si no, va a revisión. Google `purchases` no devuelve precio (solo la Orders API) y el `price` de Apple viene en milésimas de la moneda del storefront y Apple desaconseja usarlo para conciliar. **Decisión del propietario D1** | CODE `20261022100400:325-345`; DOC [purchases.products](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products), [orders](https://developers.google.com/android-publisher/api-ref/rest/v3/orders), [JWSTransactionDecodedPayload](https://developer.apple.com/documentation/appstoreserverapi/jwstransactiondecodedpayload) |
| PAY-R2 | El tope `MAX_RECEIPT_LENGTH = 4096` no admite un JWS de Apple con cadena `x5c` de 3 certificados | CODE `EconomyService.kt:403`; DOC [JWSDecodedHeader](https://developer.apple.com/documentation/appstoreserverapi/jwsdecodedheader); tamaño exacto NV |
| PAY-R3 | ECO-02: con reembolsos apagados no puede haber 503 sin registro. Apple reintenta 5 veces (1, 12, 24, 48 y 72 h) y solo en producción; Pub/Sub reentrega ante no-2xx con retención limitada. Toda notificación debe persistirse (bandeja idempotente) **antes** de responder 2xx | CODE `EconomyService.kt:342-344, 411-416`; DOC [Responding to ASSN](https://developer.apple.com/documentation/appstoreservernotifications/responding-to-app-store-server-notifications), [Pub/Sub push](https://cloud.google.com/pubsub/docs/push) |
| PAY-R4 | ECO-04: deduplicación `(proveedor, evento_id)` ligada a su pedido; si `eventos_pago.compra_id` difiere → revisión, no REPLAY silencioso | CODE `20261022100400:311-318` |
| PAY-R5 | ECO-11: `service_role` sin INSERT/UPDATE/DELETE/TRUNCATE directo sobre `compras_usuario` y `suscripciones` (solo vía RPC SECURITY DEFINER) | CODE baseline `main_shape_subset_20260929.sql:967`, `main_shape_subset_extra_plans.sql` |
| PAY-R6 | ECO-12: el borrado de cuenta no puede destruir ledger, pedidos ni `refund_case` mientras la tienda pueda notificar reembolsos o voids. **Decisión legal D6** | CODE `20261022100300:169-171, 303-305`; `20261022100400:163` |

## Google Play

- Verificación en servidor: `purchases.products.get` / `purchases.productsv2` (consumibles) y `purchases.subscriptionsv2.get` (suscripciones). Clave = `purchaseToken`, no `orderId` (hay compras sin orderId). DOC [security](https://developer.android.com/google/play/billing/security), [productsv2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2), [subscriptionsv2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).
- Conceder solo en `PURCHASED`; nunca `acknowledge`/`consume` de un `PENDING`. Acknowledge o consume **después** del crédito en base y dentro de 3 días (si no, reembolso y revocación automáticos). Rechazo explícito con `orders.refund(revoke=true)` o `subscriptionsv2.revoke`. `linkedPurchaseToken` revoca el anterior. DOC [integrate](https://developer.android.com/google/play/billing/integrate).
- `obfuscatedAccountId`: token opaco por usuario (≤ 64 caracteres, sin PII), comprobado en servidor antes de conceder. DOC [BillingFlowParams.Builder](https://developer.android.com/reference/com/android/billingclient/api/BillingFlowParams.Builder).
- RTDN por Pub/Sub push: OIDC Bearer con `aud` explícito, `iss` de Google, `email` = cuenta de servicio de push, `email_verified`; comprobar `packageName` y la suscripción; deduplicar por `messageId`; las RTDN no traen estado completo y hay que consultar la API; sin orden ni entrega garantizados. DOC [authenticate push](https://cloud.google.com/pubsub/docs/authenticate-push-subscriptions), [RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference).
- Voided Purchases API: sondeo diario de respaldo (ventana de 30 días, cuotas 6000/día y 30/30 s). DOC [voided purchases](https://developers.google.com/android-publisher/voided-purchases).
- Test vs producción: marcar `testPurchaseContext` / `purchaseType=0` / `testPurchase` como `entorno=sandbox`. DOC [test](https://developer.android.com/google/play/billing/test).

## App Store

- JWS (`signedTransactionInfo`, `signedRenewalInfo`, `signedPayload`) verificado hasta el root de Apple (cadena `x5c`: leaf → WWDR → root) antes de usar ningún campo; se prefiere la App Store Server Library (Java) a una verificación hecha a mano. DOC [JWSDecodedHeader](https://developer.apple.com/documentation/appstoreserverapi/jwsdecodedheader), [App Store Server Library](https://developer.apple.com/documentation/appstoreserverapi/simplifying-your-implementation-by-using-the-app-store-server-library).
- Entorno tomado del campo `environment` del payload firmado; no usar `verifyReceipt` (obsoleto). Hosts `api.storekit.apple.com` / `api.storekit-sandbox.apple.com`. DOC [Get Transaction Info](https://developer.apple.com/documentation/appstoreserverapi/get-transaction-info).
- `appAccountToken` = el mismo token opaco por usuario; comprobar `bundleId`. DOC [appAccountToken](https://developer.apple.com/documentation/storekit/product/purchaseoption/appaccounttoken(_:)).
- Notificaciones V2: deduplicar por `notificationUUID`; el orden no está garantizado, así que hay que rederivar el estado de la transacción firmada. Tipos mínimos: `ONE_TIME_CHARGE`, `REFUND`, `REFUND_REVERSED`, `REFUND_DECLINED`, `CONSUMPTION_REQUEST`, `SUBSCRIBED`, `DID_RENEW`, `DID_FAIL_TO_RENEW` (con y sin `GRACE_PERIOD`), `GRACE_PERIOD_EXPIRED`, `EXPIRED`, `DID_CHANGE_RENEWAL_STATUS`, `REVOKE`, `TEST`. Recuperación de perdidas con Notification History. DOC [notificationType](https://developer.apple.com/documentation/appstoreservernotifications/notificationtype), [responseBodyV2DecodedPayload](https://developer.apple.com/documentation/appstoreservernotifications/responsebodyv2decodedpayload).
- `CONSUMPTION_REQUEST`: `PUT /inApps/v2/transactions/consumption/{id}` en ≤ 12 h y **solo con consentimiento** del usuario. DOC [Send Consumption Information](https://developer.apple.com/documentation/appstoreserverapi/send-consumption-information).
- `REFUND_REVERSED` necesita un camino definido (hoy la máquina de estados solo permite `reembolsada → en_revision`, CODE `20261022102300:430-435`). **Decisión D3.**

## Comunes

- Sandbox en producción: rechazado o limitado a una lista de testers (App Review), marcado `entorno=sandbox` y excluido de ingresos (**D4**).
- Controles antifraude antes de tocar la base: package/bundle, SKU mapeado en servidor (el cliente nunca manda precio ni SKU libre), cantidad 1, estado PURCHASED, token de cuenta del usuario del JWT, token o transacción no ligados a otro usuario (UNIQUE en base), entorno permitido para el `APP_ENV`.
- Restauración: los consumibles no se "restauran" (manda el ledger); el cliente reenvía compras sin consumir o sin terminar al endpoint idempotente de verificación. Suscripciones: reverificar y ligar al usuario del JWT; si ya están ligadas a otro → 409 (**D5**).
- Suscripciones mapeadas a `suscripciones` con gracia, retención y cancelación (acceso hasta `fin_en`), expiración y revocación/reembolso (**D8**: suscripciones en V1 sí/no).
- Auditoría económica: `auditoria_log` dentro de cada RPC, `refund_case`, bandeja de eventos con hash de payload; ningún purchaseToken ni JWS en logs.
- Secretos (**D9**): JSON de la cuenta de servicio de Play, `.p8` de Apple, audiencia de Pub/Sub; solo en `/etc/oposibots/production`, nunca en el repo.

## Pruebas que OposiControl ejecutará en el retest

Cada caso negativo con su código de error y **cero filas escritas**:

- Firma o JWS inválido, cadena `x5c` ajena, OIDC con `aud`/`iss`/`email` incorrectos.
- Package o bundle erróneo, SKU desconocido, cantidad > 1, estado PENDING.
- Token de otro usuario, o ya ligado a otro.
- Reintento del mismo evento, y el mismo evento sobre otro pedido.
- Reembolso antes del pago, y reembolso con los reembolsos apagados (persistido, no perdido).
- Compra sandbox en producción.
- Fallo de consume seguido de reconciliación.
- `REFUND_REVERSED`.
- Gracia → retención → recuperación.
- Borrado de cuenta seguido de void.
- Invariantes: suma del ledger = saldo, nunca negativo, `app_wallet_reconcile` vacío.

## Decisiones del propietario

D1 precio/moneda (crédito por SKU o tolerancia) · D2 activar reembolsos en producción · D3 `REFUND_REVERSED` · D4 sandbox/testers en producción · D5 traspaso de suscripción entre cuentas · D6 ledger al borrar la cuenta (legal) · D7 consentimiento de datos de consumo de Apple · D8 suscripciones en V1 · D9 custodia de secretos · D10 equivalencia 100 OT = 1 € y comisiones de tienda.

NV pendientes: rutas exactas de Transaction History/Subscription Status/Refund History de Apple, audiencia por defecto de Pub/Sub, persistencia de `appAccountToken` en renovaciones, semántica de acceso en *account hold* de Google, si `purchases.products` v1 está obsoleto, URLs de notificación separadas por entorno en App Store Connect.
