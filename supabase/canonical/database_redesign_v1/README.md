# Database Redesign V1

Controlled post-Product-Metrics package for Supabase MAIN.

## Scope

- Remove one proven, unused duplicate index.
- Remove nominal anonymous mutation grants.
- Remove authenticated mutation grants only where no authenticated mutation
  policy exists.
- Remove mutation grants from public derived views while preserving SELECT.
- Preserve all existing policies, RPCs, catalog data, wallet logic, Storage,
  `public.usuario`, `/v1/auth/*` and OposiBot compatibility.

## MAIN safety

MAIN writes are allowed only through the three named migrations applied from this
package. The preflight is read-only and ends in `rollback`.

No destructive data operation, table drop, bucket deletion, object deletion,
OposiBot modification, commit or push is part of this package.

## Deferred work

The following remain explicitly deferred: entitlement target XOR semantics,
session and attempt idempotency contracts, multiple permissive policy
consolidation, product quotas/rewards, payment providers and unused Storage
bucket reconciliation.

Canonical vocabulary remains:

- Exams: `rafaga`, `razonado`, `inmersivo`.
- Questions: `rafaga`, `razonada`, `inmersiva`.

No `guiado`, `guiada` or `permite_guiados` is introduced.
