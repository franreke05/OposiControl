# OposiBot Contract

## Canonical V1 reconciliation — 2026-08-20

The consumer repository is now audited read-only. `RealAuthApi` consumes all twelve `/v1/auth/*` endpoints. No active client call was found for `/content/*`, `/v1/*` outside auth, `/api/v1/app/*` or `/api/v1/admin/*`. Full evidence is in [OPOSIBOT_CONSUMER_MATRIX](./OPOSIBOT_CONSUMER_MATRIX.md). No OposiBot file was changed.

## Ownership

OposiControl owns the server-side boundary observed in this repository:

- Ktor routes.
- Services.
- Gateways.
- Authorization.
- Service-role access.
- Database operations.
- Transactions.
- Server configuration.

OposiBot owns the consumer side:

- Consumer behavior.
- Client integration.
- Client state.
- Presentation.

OposiBot is a separate app/repo boundary. No consumer repository was cross-checked in this task.

## CURRENT_IMPLEMENTATION

The contract is derived from Ktor routes/DTOs and the audit catalog. Exact endpoint details belong in [ENDPOINT_CATALOG](./ENDPOINT_CATALOG.md) and [DTO_CATALOG](./DTO_CATALOG.md).

## NOT_VERIFIED

The OposiBot repository was inspected read-only at commit `e4bf669e3db8f9ecfbd1c07b0a4d459e1a318038`. HTTP compatibility and live handshake remain unverified; source consumption of the twelve auth routes is verified.

Consumer compatibility: `SOURCE_CONSUMED / HTTP_NOT_VERIFIED`.
