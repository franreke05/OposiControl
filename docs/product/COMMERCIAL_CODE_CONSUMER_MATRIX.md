# Canonical Commercial Code Consumer Matrix

Living compatibility source for commercial identifiers. The live codes remain unchanged:
`free`, `rafagas`, `razonados`, `inmersivo`.

Classification: `CANONICAL_REFERENCE`. Update this matrix when a commercial code or known consumer changes; it is not an active radar entry.

| CODE | CALLER | ROUTE | REPOSITORY/GATEWAY | PURPOSE | CAN_CHANGE | COMPATIBILITY_REQUIRED |
|---|---|---|---|---|---|---|
| `free` | Supabase live catalogue | `productos_comerciales.codigo` | Supabase catalogue | Existing free plan/product identity | NO | YES |
| `free` | OposiControl Ktor | Generic `producto_codigo` fields in admin/subscription contracts; no literal product-price writer found | `AppV1Gateway` / admin gateway | Pass-through subscription/product identity | NO | YES |
| `free` | OposiBot archived backend | `plan_codigo` default and legacy plan response | Archived SQL/repositories only | Historical compatibility | NO | YES, legacy only |
| `rafagas` | Supabase live catalogue | `productos_comerciales.codigo` / `planes_suscripcion.codigo` | Supabase catalogue | Existing plan/product identity | NO | YES |
| `rafagas` | OposiControl domain | Canonical entitlement tests/models | `CanonicalEntitlements` | Domain vocabulary assertion, not price source | NO | YES |
| `rafagas` | OposiBot UI | `SubscriptionPlan.RAPID` selection/paywall | `SubscriptionPlanScreen` | Displayed plan and UI price copy | NO | YES; OposiBot untouched |
| `razonados` | Supabase live catalogue | `productos_comerciales.codigo` / `planes_suscripcion.codigo` | Supabase catalogue | Existing plan/product identity | NO | YES |
| `razonados` | OposiControl domain | Canonical entitlement tests/models | `CanonicalEntitlements` | Domain vocabulary assertion, not price source | NO | YES |
| `razonados` | OposiBot UI | `SubscriptionPlan.REASONED` selection/paywall | `SubscriptionPlanScreen` | Displayed plan and UI price copy | NO | YES; OposiBot untouched |
| `inmersivo` | Supabase live catalogue | `productos_comerciales.codigo` / `planes_suscripcion.codigo` | Supabase catalogue | Existing plan/product identity | NO | YES |
| `inmersivo` | OposiControl content model | `ExamType.INMERSIVO` and content gates | Domain model | Content type; not proof of unlimited entitlement | NO | YES |
| `inmersivo` | OposiBot UI | `SubscriptionPlan.IMMERSIVE` selection/paywall | `SubscriptionPlanScreen` | Displayed plan and UI price copy | NO | YES; OposiBot untouched |

## Pricing representation

`productos_comerciales` remains the stable internal product catalogue. Candidate `004`
adds only the optional `plan_id` product identity link. Multiple tariffs are stored in
the child table `tarifas_producto_comercial`, with
`producto_id + periodo_facturacion + fase_precio` unique. This preserves the existing
unique product codes while giving each product the required tariff cardinality.

Tariff periods are `mensual`, `anual`, `unitario`; phases are `first_year`, `renewal`,
`standard`.

The confirmed prices to configure later are, in EUR cents:

| CODE | MONTHLY FIRST YEAR | ANNUAL FIRST YEAR | MONTHLY RENEWAL | ANNUAL RENEWAL |
|---|---:|---:|---:|---:|
| `rafagas` | 299 | 2999 | 499 | 4499 |
| `razonados` | 699 | 6000 | 999 | 9999 |
| `inmersivo` | 999 | 9999 | 1799 | 17900 |

No prices are added to Kotlin and no new SKU is seeded by this candidate. Existing
OposiBot UI price literals are a compatibility finding and require a separate,
authorized OposiBot change before they can be replaced by catalogue reads.

`productos_store` is the only provider mapping table. Provider product/price/offer
identifiers must be added there only after a provider contract is confirmed; the
candidate does not duplicate them in `productos_comerciales`.
