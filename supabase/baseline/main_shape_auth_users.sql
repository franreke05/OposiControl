-- MAIN shape: GoTrue's e-mail confirmation instant (exists in MAIN). The bootstrap image ships a minimal auth.users owned by supabase_auth_admin, so this file is run as supabase_admin.
-- Used by the referral trigger (20261022101800). Idempotent.
alter table auth.users add column if not exists email_confirmed_at timestamptz;
