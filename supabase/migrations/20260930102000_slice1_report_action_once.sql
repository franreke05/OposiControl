-- =============================================================================
-- FULL REMEDIATION -- a report resolution records its moderation action ONCE, atomically (MASTER6-API-001)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: no table, index, policy or
-- earlier migration is changed. Idempotent (create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   Ktor AdminV1Gateway.resolverReporte commits the report transition first (compare-and-set PATCH), then grants the
--   optional reward (idempotent per report) and writes the moderation action LAST: a closed report without an action
--   row is an incomplete resolution that the same moderator's retry resumes. The resume check ("no action yet") was
--   a separate GET followed by a plain POST, so two concurrent resumes could both insert an action row.
--   New function public.admin_record_report_action(p_reporte_id uuid, p_estado text, p_moderador_id uuid,
--   p_tipo_accion text, p_notas text) returns boolean. In ONE transaction it:
--     1. locks the report row (SELECT ... FOR UPDATE): concurrent calls for the same report are serialised;
--     2. compare-and-set on the report state: the report must be closed in p_estado ('resuelto' | 'descartado') by
--        p_moderador_id (`revisado_por IS NOT DISTINCT FROM`), otherwise returns false with no write;
--     3. returns false with no write if the report already has an action row (resolution complete);
--     4. otherwise inserts the action row and returns true.
--   Ktor writes the audit row and the strategic event only when it gets true, and answers 409 otherwise.
--   Why not a UNIQUE index on acciones_moderacion(reporte_id): the invariant "one action per report" is NOT proven
--   by the schema. baseline_main.sql declares reporte_id NULLABLE with ON DELETE SET NULL, tipo_accion admits
--   'ocultar','restaurar','eliminar','advertir','bloquear_usuario','descartar' (several actions over a report's life
--   are representable) and the policy acciones_moderacion_staff_all lets authenticated staff insert rows outside
--   Ktor; the only index on the column is non-unique (idx_acciones_moderacion_reporte_id). A unique index could
--   fail on MAIN data or block a legitimate future flow, so the guarantee lives in this RPC instead.
--
-- PREFLIGHT
--   public.reportes_contenido(id uuid, estado text, revisado_por uuid) and public.acciones_moderacion(reporte_id
--   uuid, moderador_id uuid, tipo_accion text, notas text) must exist with those types; otherwise
--   P0001 REPORT_ACTION_PREFLIGHT and nothing is created.
--
-- FORWARD PLAN
--   Apply BEFORE deploying the Ktor build that calls admin_record_report_action. With an older DB that Ktor gets
--   PGRST202 on the last step: the report stays closed without action (incomplete resolution) and the same
--   moderator's retry resumes it once the migration is applied. MAIN only at the dedicated apply gate.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops only the function. Roll Ktor back FIRST (the new Ktor calls it). No data is touched.
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor inserts into acciones_moderacion directly: nothing it uses changes.
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: a row lock on ONE reportes_contenido row for the duration of the
--   call (one indexed lookup + one insert). `set local lock_timeout = '5s'` applies to the DDL of THIS migration only.
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None (lookups use the primary key of reportes_contenido and the existing idx_acciones_moderacion_reporte_id).
--
-- RLS AND GRANTS
--   No policy change. SECURITY DEFINER with search_path '' and row_security off (fail closed: a client can never call
--   it). EXECUTE revoked from public/anon/authenticated and granted to service_role only.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_record_report_action(uuid, text, uuid, text, text);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'report_action_function_missing' as check_name,
--        (case when to_regprocedure('public.admin_record_report_action(uuid,text,uuid,text,text)') is not null then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'report_action_function_executable_by_clients', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_record_report_action(uuid,text,uuid,text,text)')
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))
-- union all
-- select 'report_action_function_not_definer', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_record_report_action(uuid,text,uuid,text,text)')
--   and not (p.prosecdef and p.proconfig @> array['search_path=""']);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if (select count(*) from pg_attribute a
      where a.attrelid = to_regclass('public.reportes_contenido') and not a.attisdropped
        and ((a.attname = 'id' and a.atttypid = 'uuid'::regtype)
          or (a.attname = 'estado' and a.atttypid = 'text'::regtype)
          or (a.attname = 'revisado_por' and a.atttypid = 'uuid'::regtype))) <> 3
    or (select count(*) from pg_attribute a
      where a.attrelid = to_regclass('public.acciones_moderacion') and not a.attisdropped
        and ((a.attname = 'reporte_id' and a.atttypid = 'uuid'::regtype)
          or (a.attname = 'moderador_id' and a.atttypid = 'uuid'::regtype)
          or (a.attname = 'tipo_accion' and a.atttypid = 'text'::regtype)
          or (a.attname = 'notas' and a.atttypid = 'text'::regtype))) <> 4 then
    raise exception 'REPORT_ACTION_PREFLIGHT'
      using detail = 'reportes_contenido(id uuid, estado text, revisado_por uuid) and acciones_moderacion(reporte_id uuid, moderador_id uuid, tipo_accion text, notas text) are required';
  end if;
end;
$$;

create or replace function public.admin_record_report_action(
  p_reporte_id uuid,
  p_estado text,
  p_moderador_id uuid,
  p_tipo_accion text,
  p_notas text
)
returns boolean
language plpgsql
security definer
volatile
set search_path = ''
set row_security = off
as $$
declare
  v_estado text;
  v_revisado_por uuid;
begin
  if p_estado is null or p_estado not in ('resuelto', 'descartado') then
    return false;
  end if;

  -- Serialises every call for this report; under READ COMMITTED the next statement sees what the previous holder
  -- committed (including its action row).
  select r.estado, r.revisado_por
  into v_estado, v_revisado_por
  from public.reportes_contenido r
  where r.id = p_reporte_id
  for update;

  if not found or v_estado is distinct from p_estado or v_revisado_por is distinct from p_moderador_id then
    return false;
  end if;

  if exists (select 1 from public.acciones_moderacion a where a.reporte_id = p_reporte_id) then
    return false;
  end if;

  insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion, notas)
  values (p_reporte_id, p_moderador_id, p_tipo_accion, p_notas);
  return true;
end;
$$;

revoke all on function public.admin_record_report_action(uuid, text, uuid, text, text) from public, anon, authenticated;
grant execute on function public.admin_record_report_action(uuid, text, uuid, text, text) to service_role;

do $$
begin
  if to_regprocedure('public.admin_record_report_action(uuid,text,uuid,text,text)') is null then
    raise exception 'REPORT_ACTION_NOT_INSTALLED';
  end if;
end;
$$;

commit;
