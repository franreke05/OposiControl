-- =============================================================================
-- SLICE 0 / S0-DB -- Idempotency-Key store (public.idempotency_keys)
-- =============================================================================
-- Backs the route-scoped Ktor Idempotency plugin. Uniqueness is
-- (actor_id = JWT sub, scope = '<METHOD> <route template>', idem_key = lowercase
-- UUID). request_fingerprint = sha256 hex of METHOD \n route template \n sorted
-- path params \n JCS body (computed in Ktor). Only 2xx results are stored and
-- replayed; the store is never the only guard (economic RPCs keep their own
-- in-transaction idempotency).
--
-- FUNCTIONS (SECURITY DEFINER, search_path = '', execute = service_role only)
--   idem_begin(actor, scope, key, fingerprint, ttl default 24h, lease default 30s)
--     One call, atomic. Deletes an expired row with the same key, sweeps at most
--     100 other expired rows (FOR UPDATE SKIP LOCKED, no workers), then
--     INSERT ... ON CONFLICT DO NOTHING and classifies:
--       NEW          row inserted (in_progress, locked_until = now()+lease) -> execute
--       REPLAY       completed, same fingerprint -> stored status/body/headers
--       MISMATCH     any state, different fingerprint -> 422 IDEMPOTENCY_KEY_REUSED
--       IN_PROGRESS  in_progress, same fp, lease alive -> 409 + Retry-After: 1
--       STALE        in_progress, same fp, lease expired -> lease renewed, claim_token
--                    rotated, execute
--     NEW and STALE return (record_id, claim_token). claim_token is a fencing
--     token: it rotates on every claim, so a slow owner whose lease was taken
--     over (STALE) can no longer complete or release the row.
--     Invalid arguments raise SQLSTATE 22023 (IDEM_INVALID_*); Ktor validates the
--     header first (400 IDEMPOTENCY_KEY_INVALID), so this is defence in depth.
--   idem_complete(id, claim_token, status 200..299, body jsonb, headers {Location, Content-Type})
--     in_progress -> completed; returns false if the row is gone, not in_progress
--     or claimed by someone else (claim_token rotated by a STALE takeover).
--   idem_release(id, claim_token)  deletes an in_progress row (non-2xx or
--     exception), so the client may retry with the same key; never deletes a
--     completed row nor a row whose claim_token has rotated.
--
-- FORWARD PLAN
--   SLICE 0: table + functions (this file) and the Ktor plugin wiring. The first
--   store-backed command is D2-03 (SLICE 3). TTL is 24h from created_at; a STALE
--   takeover does not extend it.
--
-- COMPATIBILITY PERIOD
--   New objects only; nothing existing is read or changed. No client impact
--   until a route declares the plugin.
--
-- LOCKING RISK
--   create table / index on a new, empty relation: no lock on existing objects.
--   Runtime: idem_begin row-locks one key (FOR UPDATE); concurrent begins on the
--   same key serialize on the unique index and converge on exactly one NEW.
--
-- DATA-LOSS RISK
--   Migration: none. Rows are ephemeral replay caches (24h); losing them only
--   disables replay, never business data (commands are naturally idempotent).
--   Rollback drops the table and every stored response.
--
-- INDEXES
--   unique (actor_id, scope, idem_key) -- lookup and uniqueness;
--   idx_idempotency_keys_expires_at (expires_at) -- expiry sweep.
--
-- RLS AND GRANTS
--   RLS enabled with NO policies; all privileges revoked from public, anon and
--   authenticated (also overrides Supabase default privileges on schema public);
--   table DML granted to service_role only. Functions: execute revoked from
--   public/anon/authenticated, granted to service_role only. actor_id has no FK
--   (neutral to DEC-008; purged by TTL).
--
-- IDEMPOTENCY OF THIS FILE
--   create table/index/column if not exists, drop function if exists + create
--   function (the fenced signatures replace the unfenced ones), revoke/grant.
--
-- ROLLBACK (manual, local/branch; executed verbatim by the dbtest suite)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.idem_release(uuid, uuid);
-- drop function if exists public.idem_complete(uuid, uuid, integer, jsonb, jsonb);
-- drop function if exists public.idem_release(uuid);
-- drop function if exists public.idem_complete(uuid, integer, jsonb, jsonb);
-- drop function if exists public.idem_begin(uuid, text, text, text, interval, interval);
-- drop table if exists public.idempotency_keys;
-- commit;
-- ROLLBACK-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

create table if not exists public.idempotency_keys (
    id uuid primary key default gen_random_uuid(),
    actor_id uuid not null,
    scope text not null check (char_length(scope) between 1 and 200),
    idem_key text not null
        check (idem_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    request_fingerprint text not null check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    state text not null check (state in ('in_progress', 'completed')),
    response_status smallint check (response_status between 200 and 299),
    response_body jsonb,
    response_headers jsonb,
    locked_until timestamptz,
    claim_token uuid not null default gen_random_uuid(),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    expires_at timestamptz not null,
    constraint idempotency_keys_actor_scope_key_uq unique (actor_id, scope, idem_key),
    constraint idempotency_keys_completed_chk
        check (state <> 'completed' or (response_status is not null and completed_at is not null))
);

-- A table created by an earlier revision of this file has no fencing token.
alter table public.idempotency_keys
    add column if not exists claim_token uuid not null default gen_random_uuid();

create index if not exists idx_idempotency_keys_expires_at
    on public.idempotency_keys (expires_at);

alter table public.idempotency_keys enable row level security;
revoke all on table public.idempotency_keys from public, anon, authenticated;
grant select, insert, update, delete on table public.idempotency_keys to service_role;

-- Unfenced signatures (earlier revision of this file) must not stay callable, and
-- idem_begin changes its result columns: drop, then create.
drop function if exists public.idem_release(uuid);
drop function if exists public.idem_complete(uuid, integer, jsonb, jsonb);
drop function if exists public.idem_begin(uuid, text, text, text, interval, interval);
drop function if exists public.idem_release(uuid, uuid);
drop function if exists public.idem_complete(uuid, uuid, integer, jsonb, jsonb);

create function public.idem_begin(
    p_actor_id uuid,
    p_scope text,
    p_idem_key text,
    p_request_fingerprint text,
    p_ttl interval default interval '24 hours',
    p_lease interval default interval '30 seconds'
)
returns table (
    outcome text,
    record_id uuid,
    stored_status smallint,
    stored_body jsonb,
    stored_headers jsonb,
    retry_after_seconds integer,
    claim_token uuid
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_key text := lower(btrim(p_idem_key));
    v_row public.idempotency_keys%rowtype;
    v_id uuid;
    v_token uuid;
    v_attempt integer := 0;
begin
    if p_actor_id is null then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_ACTOR';
    end if;
    if p_scope is null or char_length(p_scope) not between 1 and 200 then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_SCOPE';
    end if;
    if v_key is null or v_key !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_KEY';
    end if;
    if p_request_fingerprint is null or p_request_fingerprint !~ '^[0-9a-f]{64}$' then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_FINGERPRINT';
    end if;
    if p_ttl is null or p_ttl <= interval '0' or p_ttl > interval '7 days' then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_TTL';
    end if;
    if p_lease is null or p_lease <= interval '0' or p_lease > interval '15 minutes' or p_lease > p_ttl then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_LEASE';
    end if;

    -- Expired row with the same key: gone before we look.
    delete from public.idempotency_keys k
    where k.actor_id = p_actor_id and k.scope = p_scope and k.idem_key = v_key
      and k.expires_at <= now();

    -- Bounded sweep of other expired rows (no background worker).
    delete from public.idempotency_keys k
    where k.id in (
        select e.id from public.idempotency_keys e
        where e.expires_at <= now()
        order by e.expires_at
        limit 100
        for update skip locked
    );

    loop
        v_attempt := v_attempt + 1;
        v_id := null;

        insert into public.idempotency_keys as k (
            actor_id, scope, idem_key, request_fingerprint, state, locked_until, expires_at
        )
        values (
            p_actor_id, p_scope, v_key, p_request_fingerprint, 'in_progress',
            now() + p_lease, now() + p_ttl
        )
        on conflict (actor_id, scope, idem_key) do nothing
        returning k.id, k.claim_token into v_id, v_token;

        if v_id is not null then
            return query select 'NEW'::text, v_id, null::smallint, null::jsonb, null::jsonb, null::integer, v_token;
            return;
        end if;

        select * into v_row
        from public.idempotency_keys k
        where k.actor_id = p_actor_id and k.scope = p_scope and k.idem_key = v_key
        for update;

        exit when found;

        -- The conflicting row was released between our INSERT and SELECT.
        if v_attempt >= 3 then
            raise exception using errcode = '40001', message = 'IDEM_BEGIN_CONTENTION';
        end if;
    end loop;

    if v_row.request_fingerprint <> p_request_fingerprint then
        return query select 'MISMATCH'::text, null::uuid, null::smallint, null::jsonb, null::jsonb, null::integer, null::uuid;
        return;
    end if;

    if v_row.state = 'completed' then
        return query select 'REPLAY'::text, v_row.id, v_row.response_status, v_row.response_body,
                            v_row.response_headers, null::integer, null::uuid;
        return;
    end if;

    if v_row.locked_until is not null and v_row.locked_until > now() then
        return query select 'IN_PROGRESS'::text, null::uuid, null::smallint, null::jsonb, null::jsonb, 1, null::uuid;
        return;
    end if;

    -- Takeover: new lease AND new fencing token; the previous owner's token dies here.
    update public.idempotency_keys k
    set locked_until = now() + p_lease,
        claim_token = gen_random_uuid()
    where k.id = v_row.id
    returning k.claim_token into v_token;

    return query select 'STALE'::text, v_row.id, null::smallint, null::jsonb, null::jsonb, null::integer, v_token;
end;
$$;

create function public.idem_complete(
    p_record_id uuid,
    p_claim_token uuid,
    p_status integer,
    p_body jsonb,
    p_headers jsonb default '{}'::jsonb
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
    v_headers jsonb := coalesce(p_headers, '{}'::jsonb);
begin
    if p_status is null or p_status not between 200 and 299 then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_STATUS';
    end if;
    if jsonb_typeof(v_headers) <> 'object'
       or exists (
           select 1 from jsonb_each(v_headers) h
           where h.key not in ('Location', 'Content-Type') or jsonb_typeof(h.value) <> 'string'
       ) then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_HEADERS';
    end if;

    update public.idempotency_keys k
    set state = 'completed',
        response_status = p_status,
        response_body = p_body,
        response_headers = v_headers,
        completed_at = now(),
        locked_until = null
    where k.id = p_record_id and k.claim_token = p_claim_token and k.state = 'in_progress';

    return found;
end;
$$;

create function public.idem_release(p_record_id uuid, p_claim_token uuid)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
    delete from public.idempotency_keys k
    where k.id = p_record_id and k.claim_token = p_claim_token and k.state = 'in_progress';
    return found;
end;
$$;

revoke all on function public.idem_begin(uuid, text, text, text, interval, interval) from public, anon, authenticated;
revoke all on function public.idem_complete(uuid, uuid, integer, jsonb, jsonb) from public, anon, authenticated;
revoke all on function public.idem_release(uuid, uuid) from public, anon, authenticated;
grant execute on function public.idem_begin(uuid, text, text, text, interval, interval) to service_role;
grant execute on function public.idem_complete(uuid, uuid, integer, jsonb, jsonb) to service_role;
grant execute on function public.idem_release(uuid, uuid) to service_role;

commit;
