# ADR-001: OposiControl independent admin operations

Status: `ACCEPTED_PRODUCT_DIRECTION` · Date: `2026-08-19`

Decision: OposiControl se trata como la aplicación administrativa/operativa independiente del producto, no como un alias de otro cliente.

Evidence: estructura y código del repositorio; auditoría `05_ARCHITECTURE.md`; cliente actual `ARTURO`.
Consequence: contracts, UI y ownership se documentan para OposiControl. No autoriza nuevas features ni cambios funcionales.
