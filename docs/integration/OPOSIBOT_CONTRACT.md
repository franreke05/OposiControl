# OposiBot Contract

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

No OposiBot consumer repository, compatibility test or live handshake was verified. No consumer behavior is invented.

Consumer compatibility: `NOT_VERIFIED`.
