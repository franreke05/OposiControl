-- V1 NIGHT-PKCE: binding of the email deep-link code to the device that asked for the email (FA39). Contract: E1-V1-NIGHT-PKCE.md.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires nothing but the base schemas.
-- The `oposibots://auth?code=...` link (signup confirmation, password recovery) carries a one-time GoTrue token_hash. Any app registered for the same custom scheme could read it.
-- With this binding the client sends a PKCE-style S256 challenge when it asks for the email, and the verifier when it exchanges the code; Ktor consumes the stored challenge BEFORE it lets
-- GoTrue spend the token, so a stolen code is useless (and not even consumed) without the verifier. Only the challenge (a hash) and a keyed pseudonym of the email are stored.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.auth_code_binding_consume(text, text, text);
-- drop function if exists public.auth_code_binding_register(text, text, text, integer);
-- drop table if exists private.auth_code_bindings;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'binding_functions_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.auth_code_binding_register(text,text,text,integer)'), ('public.auth_code_binding_consume(text,text,text)')) f(n)
-- union all
-- select 'binding_table_missing_or_open',
--        (case when to_regclass('private.auth_code_bindings') is null or has_table_privilege('anon', 'private.auth_code_bindings', 'select') or has_table_privilege('authenticated', 'private.auth_code_bindings', 'select') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create schema if not exists private;

create table private.auth_code_bindings (
  id bigint generated always as identity primary key,
  purpose text not null check (purpose in ('signup', 'recovery')),
  email_ref text not null check (email_ref ~ '^[0-9a-f]{64}$'),   -- HMAC-SHA-256 (server secret) of the lower-cased email: never the email
  challenge text not null check (challenge ~ '^[A-Za-z0-9_-]{43}$'), -- base64url(SHA-256(code_verifier)), RFC 7636 S256
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null
);
create unique index ux_auth_code_bindings_challenge on private.auth_code_bindings (purpose, challenge);
create index ix_auth_code_bindings_email on private.auth_code_bindings (purpose, email_ref, id);
create index ix_auth_code_bindings_expiry on private.auth_code_bindings (expires_at);
alter table private.auth_code_bindings enable row level security;
revoke all on table private.auth_code_bindings from public, anon, authenticated;

-- one challenge per requested email; at most 5 live ones per (purpose, email) (a resend storm cannot grow the table), expired ones are swept on write
create or replace function public.auth_code_binding_register(p_purpose text, p_email_ref text, p_challenge text, p_ttl_seconds integer default 3600)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_purpose not in ('signup', 'recovery') then raise exception 'BINDING_PURPOSE_INVALID'; end if;
  delete from private.auth_code_bindings where id in (select id from private.auth_code_bindings where expires_at < clock_timestamp() order by expires_at limit 100);
  insert into private.auth_code_bindings (purpose, email_ref, challenge, expires_at)
    values (p_purpose, p_email_ref, p_challenge, clock_timestamp() + make_interval(secs => least(greatest(coalesce(p_ttl_seconds, 3600), 60), 86400)))
    on conflict (purpose, challenge) do nothing;
  delete from private.auth_code_bindings
   where id in (select id from private.auth_code_bindings where purpose = p_purpose and email_ref = p_email_ref order by id desc offset 5);
end;
$$;
revoke all on function public.auth_code_binding_register(text, text, text, integer) from public, anon, authenticated;
grant execute on function public.auth_code_binding_register(text, text, text, integer) to service_role;

-- consumes (single use) the live binding of this challenge and returns the email pseudonym it was requested for, or null (unknown / expired / already used / other email).
-- p_email_ref null = the caller did not say which email: any binding of that challenge matches and the caller compares the returned pseudonym with the session it gets.
create or replace function public.auth_code_binding_consume(p_purpose text, p_email_ref text, p_challenge text)
returns text
language sql
volatile
security definer
set search_path = ''
as $$
  delete from private.auth_code_bindings
   where purpose = p_purpose and challenge = p_challenge and expires_at > clock_timestamp()
     and (p_email_ref is null or email_ref = p_email_ref)
  returning email_ref
$$;
revoke all on function public.auth_code_binding_consume(text, text, text) from public, anon, authenticated;
grant execute on function public.auth_code_binding_consume(text, text, text) to service_role;

commit;
