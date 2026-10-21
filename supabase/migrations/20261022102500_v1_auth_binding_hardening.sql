-- V1 P2 (E2): SEC-V1-08 hardening of the FA39 email-code binding (20261022102200_v1_auth_code_binding.sql, not edited).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Problem: a binding matched purpose + challenge (+ email) and up to 5 live ones per email were valid, so an attacker who asked a
-- recovery/signup mail for the victim's address with HIS challenge could pair that binding with the victim's intercepted code
-- (account takeover even with AUTH_PKCE_MODE=required).
-- Fix: a binding belongs to ONE email request.
--   1. auth_code_binding_begin stores it PENDING (activated_at null): it cannot be consumed.
--   2. Ktor calls auth_code_binding_activate only when GoTrue answered 2xx to that email request (it issued, and replaced, the
--      token). Activation drops every other activated binding of the same (purpose, email): GoTrue keeps one live token per
--      (user, type), and now one valid binding per (purpose, email) too, both from the same (last sent) request.
--   3. auth_code_binding_consume only spends an ACTIVATED binding that is the newest activated one of its (purpose, email).
--   A throttled/refused/failed send (429, 4xx, 5xx, transport) leaves the binding pending forever (swept at expiry).
-- Rolling-deploy compatible: the legacy auth_code_binding_register keeps its signature and inserts activated rows (column default),
-- so an older Ktor keeps working (and already gets the "newest only" rule at consume).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.auth_code_binding_activate(bigint);
-- drop function if exists public.auth_code_binding_begin(text, text, text, integer);
-- create or replace function public.auth_code_binding_consume(p_purpose text, p_email_ref text, p_challenge text)
-- returns text
-- language sql
-- volatile
-- security definer
-- set search_path = ''
-- as $$
--   delete from private.auth_code_bindings
--    where purpose = p_purpose and challenge = p_challenge and expires_at > clock_timestamp()
--      and (p_email_ref is null or email_ref = p_email_ref)
--   returning email_ref
-- $$;
-- drop index if exists private.ix_auth_code_bindings_active;
-- alter table private.auth_code_bindings drop column if exists activated_at;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'binding_functions_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.auth_code_binding_begin(text,text,text,integer)'), ('public.auth_code_binding_activate(bigint)'), ('public.auth_code_binding_consume(text,text,text)'), ('public.auth_code_binding_register(text,text,text,integer)')) f(n)
-- union all
-- select 'binding_activated_at_column_missing',
--        (case when exists (select 1 from information_schema.columns where table_schema = 'private' and table_name = 'auth_code_bindings' and column_name = 'activated_at') then 0 else 1 end)::bigint
-- union all
-- select 'binding_table_open_to_clients',
--        (case when has_table_privilege('anon', 'private.auth_code_bindings', 'select') or has_table_privilege('authenticated', 'private.auth_code_bindings', 'select') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- existing (legacy) rows were valid: the volatile default stamps each of them, and the legacy register keeps inserting valid rows
alter table private.auth_code_bindings add column activated_at timestamptz default clock_timestamp();
create index ix_auth_code_bindings_active on private.auth_code_bindings (purpose, email_ref, activated_at desc, id desc) where activated_at is not null;

-- pending binding of one email request; null = the challenge is already bound to another email (the first registration wins)
create function public.auth_code_binding_begin(p_purpose text, p_email_ref text, p_challenge text, p_ttl_seconds integer default 3600)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_id bigint;
  v_email text;
  v_expires timestamptz := clock_timestamp() + make_interval(secs => least(greatest(coalesce(p_ttl_seconds, 3600), 60), 86400));
begin
  if p_purpose not in ('signup', 'recovery') then raise exception 'BINDING_PURPOSE_INVALID'; end if;
  perform pg_advisory_xact_lock(hashtextextended('auth_code_binding:' || p_purpose || ':' || coalesce(p_email_ref, ''), 0));
  delete from private.auth_code_bindings where id in (select id from private.auth_code_bindings where expires_at < clock_timestamp() order by expires_at limit 100);
  delete from private.auth_code_bindings where purpose = p_purpose and challenge = p_challenge and expires_at <= clock_timestamp();
  insert into private.auth_code_bindings (purpose, email_ref, challenge, expires_at, activated_at)
    values (p_purpose, p_email_ref, p_challenge, v_expires, null)
    on conflict (purpose, challenge) do nothing
    returning id into v_id;
  if v_id is null then
    select b.id, b.email_ref into v_id, v_email from private.auth_code_bindings b where b.purpose = p_purpose and b.challenge = p_challenge for update;
    if v_id is null or v_email is distinct from p_email_ref then return null; end if;
    update private.auth_code_bindings set expires_at = greatest(expires_at, v_expires) where id = v_id;
    return v_id;
  end if;
  -- a request storm cannot grow the table: at most 5 PENDING per (purpose, email); the valid (activated) one is never displaced here
  delete from private.auth_code_bindings
   where id in (select id from private.auth_code_bindings where purpose = p_purpose and email_ref = p_email_ref and activated_at is null order by id desc offset 5);
  return v_id;
end;
$$;
revoke all on function public.auth_code_binding_begin(text, text, text, integer) from public, anon, authenticated;
grant execute on function public.auth_code_binding_begin(text, text, text, integer) to service_role;

-- GoTrue sent the email of binding p_id: it becomes the ONLY valid binding of its (purpose, email). false = unknown or expired.
create function public.auth_code_binding_activate(p_id bigint)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_purpose text;
  v_email text;
begin
  select b.purpose, b.email_ref into v_purpose, v_email from private.auth_code_bindings b where b.id = p_id;
  if v_purpose is null then return false; end if;
  perform pg_advisory_xact_lock(hashtextextended('auth_code_binding:' || v_purpose || ':' || v_email, 0));
  update private.auth_code_bindings set activated_at = clock_timestamp() where id = p_id and expires_at > clock_timestamp();
  if not found then return false; end if;
  delete from private.auth_code_bindings where purpose = v_purpose and email_ref = v_email and id <> p_id and activated_at is not null;
  return true;
end;
$$;
revoke all on function public.auth_code_binding_activate(bigint) from public, anon, authenticated;
grant execute on function public.auth_code_binding_activate(bigint) to service_role;

-- same contract as before (single use, returns the email pseudonym or null) but only an ACTIVATED binding that is the newest
-- activated one of its (purpose, email) can be spent: a pending, superseded, expired or other-email binding returns null and is kept
create or replace function public.auth_code_binding_consume(p_purpose text, p_email_ref text, p_challenge text)
returns text
language sql
volatile
security definer
set search_path = ''
as $$
  delete from private.auth_code_bindings b
   where b.purpose = p_purpose and b.challenge = p_challenge and b.expires_at > clock_timestamp()
     and b.activated_at is not null
     and (p_email_ref is null or b.email_ref = p_email_ref)
     and not exists (
       select 1 from private.auth_code_bindings n
        where n.purpose = b.purpose and n.email_ref = b.email_ref and n.id <> b.id
          and n.activated_at is not null and n.expires_at > clock_timestamp()
          and (n.activated_at, n.id) > (b.activated_at, b.id))
  returning b.email_ref
$$;
revoke all on function public.auth_code_binding_consume(text, text, text) from public, anon, authenticated;
grant execute on function public.auth_code_binding_consume(text, text, text) to service_role;

commit;
