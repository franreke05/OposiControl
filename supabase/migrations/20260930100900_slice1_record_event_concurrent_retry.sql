-- =============================================================================
-- SLICE 1 / AUDIT LOOP 2 -- DB: record_event safe under concurrent identical retries (MASTER2-IDEM-002)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only fix: NEW file; 20260930100800 is not edited for this.
-- Requires 20260930100800 (per-user unique index ux_eventos_app_idempotency_key_usuario + per-user lookup).
-- One transaction.
--
-- DEFECT
--   record_event (20260930100800) does `select ... for update` and INSERTs on a miss. On a miss FOR UPDATE locks
--   nothing, so two concurrent FIRST calls with the same (user, key) both miss; the loser's INSERT waits on the
--   winner's uncommitted index entry and then raises 23505 (unique_violation), which Ktor maps to 409
--   UNIQUE_VIOLATION instead of returning the original event id (identical retry) or EVENT_IDEMPOTENCY_CONFLICT
--   (different payload).
--
-- WHAT THIS MIGRATION DOES
--   public.record_event (same signature, same arguments, same result shape, same error codes, same ACL) takes a
--   transaction-scoped advisory lock on (user, trimmed key) BEFORE the lookup:
--     pg_advisory_xact_lock(hashtext('public.record_event'), hashtext(coalesce(p_usuario_id::text, '') || '|' || btrim(key)))
--   Concurrent calls for the same (user, key) serialize; the second one runs its lookup after the first commits
--   (READ COMMITTED: each statement takes a new snapshot; PostgREST/Ktor use READ COMMITTED), finds the row and
--   returns the original event id or EVENT_IDEMPOTENCY_CONFLICT. Different users / different keys use different
--   lock ids and do not wait for each other (a 32-bit hash collision only serializes two unrelated calls; it never
--   changes a result). The lock is released at commit/rollback; nothing persists.
--
-- FORWARD PLAN
--   Apply after 20260930100800 on local/branch; MAIN only at the dedicated apply gate. No Ktor change.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below restores the 20260930100800 body verbatim (no advisory lock) and the same ACL.
--
-- COMPATIBILITY WITH OLD KTOR
--   Same function signature, same JSON shape (`event_id`), same error strings. Old Ktor keeps working.
--
-- LOCKING
--   Function replacement only (brief lock on pg_proc entry). At runtime: one advisory xact lock per call,
--   scoped to (user, key). The `set local lock_timeout = '5s'` applies to the DDL only (it ends at commit and the
--   function carries no setting): the runtime advisory lock waits up to statement_timeout (MASTER3-DB-003).
--
-- DATA LOSS
--   None. No row, table or index is touched.
--
-- INDEXES
--   None changed.
--
-- RLS AND GRANTS
--   No policy change. record_event: revoke all from public/anon/authenticated, grant execute to service_role.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- set local lock_timeout = '5s';
-- create or replace function public.record_event(
--   p_usuario_id uuid,
--   p_nombre_evento text,
--   p_pantalla text default null,
--   p_entidad_tipo text default null,
--   p_entidad_id uuid default null,
--   p_propiedades jsonb default '{}'::jsonb,
--   p_event_version integer default 1,
--   p_occurred_en timestamptz default now(),
--   p_especialidad_id uuid default null,
--   p_intento_id uuid default null,
--   p_pregunta_id uuid default null,
--   p_idempotency_key text default null
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
--
--   -- MASTER-SEC-007: the key is scoped to the calling user; another user's row with the same key is ignored.
--   select e.* into v_existing
--   from public.eventos_app e
--   where e.idempotency_key = btrim(p_idempotency_key)
--     and e.usuario_id is not distinct from p_usuario_id
--   for update;
--
--   if found then
--     if v_existing.nombre_evento is not distinct from p_nombre_evento
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
--
--   insert into public.eventos_app (
--     usuario_id, nombre_evento, pantalla, entidad_tipo, entidad_id,
--     propiedades, event_version, occurred_en, received_en,
--     especialidad_id, intento_id, pregunta_id, idempotency_key
--   ) values (
--     p_usuario_id, p_nombre_evento, p_pantalla, p_entidad_tipo, p_entidad_id,
--     coalesce(p_propiedades, '{}'::jsonb), p_event_version, coalesce(p_occurred_en, now()), now(),
--     p_especialidad_id, p_intento_id, p_pregunta_id, btrim(p_idempotency_key)
--   ) returning id into v_event_id;
--
--   return query select v_event_id;
-- end;
-- $$;
-- revoke all on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text) from public, anon, authenticated;
-- grant execute on function public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only; every check must report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'record_event_retry_lock_missing' as check_name,
--        (case when pg_get_functiondef('public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure)
--                   ilike '%pg_advisory_xact_lock(%usuario_id is not distinct from p_usuario_id%for update%' then 0 else 1 end)::bigint as failures
-- union all
-- select 'record_event_not_definer_or_search_path',
--        (case when p.prosecdef and p.proconfig @> array['search_path=""'] then 0 else 1 end)::bigint
-- from pg_proc p
-- where p.oid = 'public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure
-- union all
-- select 'record_event_execute_outside_service_role', count(*)
-- from (values ('anon'), ('authenticated'), ('public')) r(rol)
-- where has_function_privilege(r.rol, 'public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure, 'execute')
-- union all
-- select 'record_event_service_role_cannot_execute',
--        (case when has_function_privilege('service_role', 'public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure, 'execute') then 0 else 1 end)::bigint
-- union all
-- select 'per_user_index_missing',
--        (case when to_regclass('public.ux_eventos_app_idempotency_key_usuario') is null then 1 else 0 end)::bigint;
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

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

  -- MASTER2-IDEM-002: serialize concurrent calls for the same (user, key) so a racing identical retry sees the
  -- committed row instead of failing on the unique index with 23505.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtext('public.record_event'),
    pg_catalog.hashtext(coalesce(p_usuario_id::text, '') || '|' || btrim(p_idempotency_key))
  );

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
