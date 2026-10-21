-- MAIN shape of auth.users e-mail confirmation (GoTrue). The bootstrap image ships a minimal (pre-2021) auth.users owned by supabase_auth_admin,
-- where confirmed_at is a PLAIN column and email_confirmed_at/phone_confirmed_at do not exist. In GoTrue (MAIN) the e-mail instant is
-- email_confirmed_at and confirmed_at is GENERATED ALWAYS AS (LEAST(email_confirmed_at, phone_confirmed_at)) STORED
-- (GoTrue migrations 20210710035447_alter_users + 20210722035447_adds_confirmed_at). Run as supabase_admin. Idempotent.
-- Readers: referral trigger (email_confirmed_at, 20261022101800), foro (email_confirmed_at, 20261022101900),
-- daily challenge private.user_auth_facts (confirmed_at, 20261022101200). With a plain confirmed_at, a harness that sets
-- email_confirmed_at (as GoTrue does) left confirmed_at null and the claim answered 403 EMAIL_UNVERIFIED (E5 live run 2026-10-19, J17).
alter table auth.users add column if not exists email_confirmed_at timestamptz;
alter table auth.users add column if not exists phone_confirmed_at timestamptz;
do $$
begin
  if exists (select 1 from information_schema.columns
              where table_schema = 'auth' and table_name = 'users' and column_name = 'confirmed_at' and is_generated <> 'ALWAYS') then
    -- keep any confirmation recorded only in the old plain column; shape repair, not a business event: no triggers (referral) fire
    set local session_replication_role = replica;
    update auth.users set email_confirmed_at = confirmed_at where email_confirmed_at is null and confirmed_at is not null;
    set local session_replication_role = origin;
    alter table auth.users drop column confirmed_at;
  end if;
  if not exists (select 1 from information_schema.columns where table_schema = 'auth' and table_name = 'users' and column_name = 'confirmed_at') then
    alter table auth.users add column confirmed_at timestamptz generated always as (least(email_confirmed_at, phone_confirmed_at)) stored;
  end if;
end $$;
