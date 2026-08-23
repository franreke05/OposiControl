# Canonical V1 candidate

**This directory is candidate-only. It is not applied live and is not part of `supabase/migrations` history.**

The corrected pre-OposiBot package is `migrations_candidate_v2/`. The earlier `migrations_candidate/` package is superseded for execution because it created a duplicate `is_*` authorization family, allowed direct authenticated business writes, included unnecessary `current_auth_user_id()`, and bundled deferred legacy cleanup. Use V2 for future disposable-clone review only.

Deferred cleanup is isolated in `POST_OPOSIBOT_LEGACY_CLEANUP/` and must not run before OposiBot adaptation. The guarded `permite_guiados -> permite_razonados` rename is owned by `migrations_candidate_v2/`.

The files describe the target and migration boundaries without copying the live schema blindly. Before execution, each statement needs a reviewed diff against a fresh read-only snapshot, an ADR/ledger entry, advisor review and a human migration gate.

## Target domains

| File | Target |
|---|---|
| `identity.sql` | `auth.users` UUID identity, profile ownership, legacy removal preconditions |
| `roles.sql` | explicit role codes and UUID role assignments |
| `content.sql` | UUID specialties, communities, tags, questions, options, solutions, resources |
| `exams.sql` | `rafaga`, `razonado`, `inmersivo` and access vocabulary |
| `opositeca.sql` | Opositeca physical tables retained as canonical content capability |
| `oposired.sql` | physical posts/comments retained; domain aliases Thread/Reply |
| `market.sql` | UUID products, plans, subscriptions, wallet and purchase replacement boundary |
| `support.sql` | UUID support conversations/messages and ownership policy boundary |
| `analytics.sql` | derived metrics/events/audit actor UUID boundary |
| `rls.sql` | policy plan only; no live policy changes |
| `functions.sql` | function rewrite/removal matrix only; no live function changes |
| `storage.sql` | bucket/path consolidation gates; no object movement or bucket deletion |

The `.sql` files are intentionally non-executable design manifests. Concrete DDL belongs in a later reviewed migration chain after the live snapshot, consumer matrix and RLS/function decisions are approved.
