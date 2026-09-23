# Canonical V1 migration candidate

This directory contains the first executable migration package for Canonical V1. It is deliberately outside `supabase/migrations/`, so Supabase CLI history and production state are unchanged.

Execution order:

1. `001_preflight.sql`
2. `002_canonical_vocabulary.sql`
3. `003_identity_and_roles.sql`
4. `004_rls_and_grants.sql`
5. `005_security_definer_grants.sql`
6. `006_api_identity_support.sql`
7. `007_storage_and_preservation.sql`
8. `008_legacy_cleanup_final_gate.sql` — separate final phase only

Each file is transactional. `001` and `007` are assertion-only. `008` refuses to run without an explicit session human-approval setting and also refuses to drop `public.usuario` while any function still references it.

The package preserves Opositeca, OposiRed, PDF/import staging, Storage buckets and objects. It does not alter OposiBot or delete routes. `/v1/auth/*` remains a required compatibility surface.

Before live execution, review the companion `CANONICAL_MIGRATION_EXECUTION_PLAN.md`, run a fresh read-only snapshot, perform a dry run on a disposable clone, and obtain the human migration gate.
