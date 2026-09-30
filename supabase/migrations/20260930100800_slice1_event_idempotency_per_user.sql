-- =============================================================================
-- SLICE 1 / AUDIT LOOP 1 -- KTOR_SEC: event idempotency scoped per user (MASTER-SEC-007)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only fix: no validated migration is edited.
-- public.record_event and ux_eventos_app_idempotency_key come from the MAIN baseline (product metrics candidate
-- 009_record_event_idempotency_rpc.sql); this file only narrows the idempotency scope. One transaction.
--
-- BASELINE PREREQUISITE (audit loop 2, MASTER2-DB-001; explicit, recorded edit of this branch-only file)
--   No migration in supabase/migrations creates public.eventos_app or public.record_event: both come from the MAIN
--   baseline (gate baseline_main.sql). This file therefore starts with a PREFLIGHT block that aborts the whole
--   transaction (nothing applied) with a clear message when:
--     * public.eventos_app is missing                                  -> PREFLIGHT_BASELINE_MISSING
--     * public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)
--       is missing                                                     -> PREFLIGHT_BASELINE_MISSING
--     * a single-column UNIQUE index on eventos_app(idempotency_key) exists under a name other than
--       ux_eventos_app_idempotency_key (MAIN drift: `drop index if exists` would silently keep the global scope)
--                                                                      -> PREFLIGHT_MAIN_DRIFT
--     * neither ux_eventos_app_idempotency_key (baseline) nor ux_eventos_app_idempotency_key_usuario (already
--       applied, re-run) exists                                        -> PREFLIGHT_MAIN_DRIFT
--   The preflight changes no object: with the MAIN baseline it passes and the resulting schema is identical to the
--   loop-1 version of this file. VERIFY `global_index_still_present` now counts ANY single-column unique index on
--   idempotency_key (not only the baseline name) and is mandatory (= 0) at the apply gate.
--
-- WHAT THIS MIGRATION DOES
--   1. New unique index ux_eventos_app_idempotency_key_usuario on public.eventos_app (idempotency_key, usuario_id).
--      Leading column idempotency_key so the RPC lookup (key + user) is an index probe. Default NULLS DISTINCT on
--      purpose: rows whose usuario_id became NULL through eventos_app_usuario_id_fkey ON DELETE SET NULL never
--      collide with each other, so deleting two users that reused a key can not fail.
--   2. Drops the GLOBAL unique index ux_eventos_app_idempotency_key (idempotency_key). With it, a key was unique
--      across ALL users: another user reusing a key got EVENT_IDEMPOTENCY_CONFLICT (a cross-user existence oracle)
--      and a legitimate event could be blocked by a foreign row.
--   3. public.record_event (same signature, same arguments, same result shape, same error codes) now looks the key
--      up for the SAME user only: `idempotency_key = btrim(key) and usuario_id is not distinct from p_usuario_id`.
--      An identical retry of the same user still returns the original event id; a different payload with the same
--      key of the same user is still EVENT_IDEMPOTENCY_CONFLICT; another user's key is ignored.
--   4. Re-asserts the ACL of record_event: EXECUTE for service_role only (MAIN shape).
--
-- FORWARD PLAN
--   Apply after 20260930100500 on local/branch; MAIN only at the dedicated apply gate. Ktor needs no change for
--   this file (it already sends p_usuario_id = JWT sub); the Ktor side of MASTER-SEC-007 (attempt ownership,
--   size caps) ships in AppV1Gateway.registrarEvento.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-creates the global unique index and the previous record_event body verbatim (baseline),
--   then drops the per-user index. Re-creating the global index FAILS (23505) if two users already share a key
--   after this migration: that is the intended stop signal (inspect with VERIFY `cross_user_shared_keys`, decide
--   per row; never delete events blindly).
--
-- COMPATIBILITY WITH OLD KTOR
--   Same function signature, same JSON shape (`event_id`), same error strings. Old Ktor keeps working.
--
-- LOCKING
--   CREATE UNIQUE INDEX (not CONCURRENTLY: we are inside a transaction) takes a SHARE lock on eventos_app for the
--   build (writes wait; reads continue). DROP INDEX takes ACCESS EXCLUSIVE briefly. lock_timeout 5s: fails fast,
--   retry off-peak. eventos_app is append-only analytics.
--
-- DATA LOSS
--   None. No row is touched.
--
-- INDEXES
--   + ux_eventos_app_idempotency_key_usuario (idempotency_key, usuario_id) UNIQUE
--   - ux_eventos_app_idempotency_key (idempotency_key) UNIQUE
--
-- RLS AND GRANTS
--   No policy change. record_event: revoke all from public/anon/authenticated, grant execute to service_role.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- set local lock_timeout = '5s';
-- create unique index if not exists ux_eventos_app_idempotency_key on public.eventos_app using btree (idempotency_key);
-- create or replace function public.record_event(
--   p_usuario_id uuid, p_nombre_evento text, p_pantalla text default null, p_entidad_tipo text default null,
--   p_entidad_id uuid default null, p_propiedades jsonb default '{}'::jsonb, p_event_version integer default 1,
--   p_occurred_en timestamptz default now(), p_especialidad_id uuid default null, p_intento_id uuid default null,
--   p_pregunta_id uuid default null, p_idempotency_key text default null
-- )
-- returns table (event_id uuid)
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_existing public.eventos_app%rowtype;
--   v_event_id uuid;
-- begin
--   if p_event_version is null or p_event_version <= 0 then
--     raise exception 'EVENT_VERSION_INVALID';
--   end if;
--   if p_idempotency_key is null or length(btrim(p_idempotency_key)) = 0 then
--     raise exception 'EVENT_IDEMPOTENCY_KEY_REQUIRED';
--   end if;
--   select e.* into v_existing
--   from public.eventos_app e
--   where e.idempotency_key = btrim(p_idempotency_key)
--   for update;
--   if found then
--     if v_existing.usuario_id is not distinct from p_usuario_id
--       and v_existing.nombre_evento is not distinct from p_nombre_evento
--       and v_existing.pantalla is not distinct from p_pantalla
--       and v_existing.entidad_tipo is not distinct from p_entidad_tipo
--       and v_existing.entidad_id is not distinct from p_entidad_id
--       and v_existing.propiedades is not distinct from coalesce(p_propiedades, '{}'::jsonb)
--       and v_existing.event_version is not distinct from p_event_version
--       and v_existing.especialidad_id is not distinct from p_especialidad_id
--       and v_existing.intento_id is not distinct from p_intento_id
--       and v_existing.pregunta_id is not distinct from p_pregunta_id then
--       return query select v_existing.id;
--       return;
--     end if;
--     raise exception 'EVENT_IDEMPOTENCY_CONFLICT';
--   end if;
--   insert into public.eventos_app (
--     usuario_id, nombre_evento, pantalla, entidad_tipo, entidad_id,
--     propiedades, event_version, occurred_en, received_en,
--     especialidad_id, intento_id, pregunta_id, idempotency_key
--   ) values (
--     p_usuario_id, p_nombre_evento, p_pantalla, p_entidad_tipo, p_entidad_id,
--     coalesce(p_propiedades, '{}'::jsonb), p_event_version, coalesce(p_occurred_en, now()), now(),
--     p_especialidad_id, p_intento_id, p_pregunta_id, btrim(p_idempotency_key)
--   ) returning id into v_event_id;
--   return query select v_event_id;
-- end;
-- $$;
-- revoke all on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text) from public, anon, authenticated;
-- grant execute on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text) to service_role;
-- drop index if exists public.ux_eventos_app_idempotency_key_usuario;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only; every check must report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'per_user_index_missing' as check_name,
--        (case when exists (
--            select 1 from pg_indexes
--            where schemaname = 'public' and tablename = 'eventos_app' and indexname = 'ux_eventos_app_idempotency_key_usuario'
--              and indexdef ilike '%unique%' and indexdef ilike '%(idempotency_key, usuario_id)%'
--        ) then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'global_index_still_present',
--        (select count(*) from pg_index x
--         join pg_attribute a on a.attrelid = x.indrelid and a.attnum = x.indkey[0]
--         where x.indrelid = 'public.eventos_app'::regclass and x.indisunique and x.indnkeyatts = 1
--           and a.attname = 'idempotency_key'),
--        null
-- union all
-- select 'record_event_lookup_not_per_user',
--        (case when pg_get_functiondef('public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure)
--                   ilike '%usuario_id is not distinct from p_usuario_id%for update%' then 0 else 1 end)::bigint,
--        null
-- union all
-- select 'record_event_execute_outside_service_role', count(*), null
-- from (values ('anon'), ('authenticated'), ('public')) r(rol)
-- where has_function_privilege(r.rol, 'public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure, 'execute')
-- union all
-- select 'cross_user_shared_keys (info: blocks rollback)', 0,
--        (select count(*)::text from (select idempotency_key from public.eventos_app group by idempotency_key having count(distinct usuario_id) > 1) k);
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (MASTER2-DB-001): fail loudly, before any change, when the MAIN baseline objects are missing or drifted.
do $preflight$
declare
  v_drift text;
begin
  if to_regclass('public.eventos_app') is null then
    raise exception 'PREFLIGHT_BASELINE_MISSING: public.eventos_app does not exist; this migration requires the MAIN baseline schema';
  end if;
  if to_regprocedure('public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)') is null then
    raise exception 'PREFLIGHT_BASELINE_MISSING: public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text) does not exist; this migration requires the MAIN baseline schema';
  end if;
  select string_agg(i.relname::text, ', ' order by i.relname) into v_drift
  from pg_index x
  join pg_class i on i.oid = x.indexrelid
  join pg_attribute a on a.attrelid = x.indrelid and a.attnum = x.indkey[0]
  where x.indrelid = 'public.eventos_app'::regclass
    and x.indisunique
    and x.indnkeyatts = 1
    and a.attname = 'idempotency_key'
    and i.relname <> 'ux_eventos_app_idempotency_key';
  if v_drift is not null then
    raise exception 'PREFLIGHT_MAIN_DRIFT: global unique index on eventos_app(idempotency_key) under an unexpected name (%); dropping ux_eventos_app_idempotency_key would not remove the global scope', v_drift;
  end if;
  if to_regclass('public.ux_eventos_app_idempotency_key') is null
     and to_regclass('public.ux_eventos_app_idempotency_key_usuario') is null then
    raise exception 'PREFLIGHT_MAIN_DRIFT: neither ux_eventos_app_idempotency_key (MAIN baseline) nor ux_eventos_app_idempotency_key_usuario exists on public.eventos_app';
  end if;
end
$preflight$;

create unique index if not exists ux_eventos_app_idempotency_key_usuario
    on public.eventos_app using btree (idempotency_key, usuario_id);

drop index if exists public.ux_eventos_app_idempotency_key;

create or replace function public.record_event(
  p_usuario_id uuid,
  p_nombre_evento text,
  p_pantalla text default null,
  p_entidad_tipo text default null,
  p_entidad_id uuid default null,
  p_propiedades jsonb default '{}'::jsonb,
  p_event_version integer default 1,
  p_occurred_en timestamptz default now(),
  p_especialidad_id uuid default null,
  p_intento_id uuid default null,
  p_pregunta_id uuid default null,
  p_idempotency_key text default null
)
returns table (event_id uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_existing public.eventos_app%rowtype;
  v_event_id uuid;
begin
  if p_event_version is null or p_event_version <= 0 then
    raise exception 'EVENT_VERSION_INVALID';
  end if;
  if p_idempotency_key is null or length(btrim(p_idempotency_key)) = 0 then
    raise exception 'EVENT_IDEMPOTENCY_KEY_REQUIRED';
  end if;

  -- MASTER-SEC-007: the key is scoped to the calling user; another user's row with the same key is ignored.
  select e.* into v_existing
  from public.eventos_app e
  where e.idempotency_key = btrim(p_idempotency_key)
    and e.usuario_id is not distinct from p_usuario_id
  for update;

  if found then
    if v_existing.nombre_evento is not distinct from p_nombre_evento
      and v_existing.pantalla is not distinct from p_pantalla
      and v_existing.entidad_tipo is not distinct from p_entidad_tipo
      and v_existing.entidad_id is not distinct from p_entidad_id
      and v_existing.propiedades is not distinct from coalesce(p_propiedades, '{}'::jsonb)
      and v_existing.event_version is not distinct from p_event_version
      and v_existing.especialidad_id is not distinct from p_especialidad_id
      and v_existing.intento_id is not distinct from p_intento_id
      and v_existing.pregunta_id is not distinct from p_pregunta_id then
      return query select v_existing.id;
      return;
    end if;
    raise exception 'EVENT_IDEMPOTENCY_CONFLICT';
  end if;

  insert into public.eventos_app (
    usuario_id, nombre_evento, pantalla, entidad_tipo, entidad_id,
    propiedades, event_version, occurred_en, received_en,
    especialidad_id, intento_id, pregunta_id, idempotency_key
  ) values (
    p_usuario_id, p_nombre_evento, p_pantalla, p_entidad_tipo, p_entidad_id,
    coalesce(p_propiedades, '{}'::jsonb), p_event_version, coalesce(p_occurred_en, now()), now(),
    p_especialidad_id, p_intento_id, p_pregunta_id, btrim(p_idempotency_key)
  ) returning id into v_event_id;

  return query select v_event_id;
end;
$$;

revoke all on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)
  from public, anon, authenticated;
grant execute on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)
  to service_role;

commit;
