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
--     3. returns false with no write if THIS resolution already has its action row (resolution complete). The
--        idempotency key of a resolution is (reporte_id, moderador_id = revisado_por, creado_en >= revisado_en): an
--        action taken while the report was still open (e.g. an earlier 'ocultar', by anyone) or an action by other
--        staff belongs to a different decision and never makes the resolution a no-op. With revisado_en NULL (a
--        legacy close) any action by the resolver counts (fail closed: never a second record);
--     4. otherwise inserts the action row with creado_en = greatest(now(), revisado_en) and returns true. revisado_en
--        is written by Ktor (application clock); stamping no earlier than it keeps the key true for the row just
--        written whatever the clock skew, so a resume can never record the same resolution twice.
--   Ktor writes the audit row and the strategic event only when it gets true, and answers 409 otherwise. Its resume
--   pre-check (AdminV1Gateway.hasResolutionAction) uses the SAME key, so a report with only earlier actions resumes.
--
-- NULL MODERATOR (p_moderador_id NULL)
--   Explicit: a NULL moderator matches only a report closed WITHOUT an actor (revisado_por NULL, the legacy static
--   token resolution, which never has a JWT subject); it is recorded once, under the same key (actions with a NULL
--   moderador_id at or after revisado_en). A NULL moderator against a report closed by a real moderator, or a real
--   moderator against a report closed without an actor, returns false with no write. Ktor never RESUMES without an
--   actor (deny by default), so only the first static-token resolution reaches this path.
--
-- RESIDUALS (accepted, documented in SLICE_1_PRODUCTION_GATE)
--   - Writers that bypass the RPC: OLD Ktor (direct POST /rest/v1/acciones_moderacion) and authenticated staff
--     through the policy acciones_moderacion_staff_all insert rows without taking the report row lock. The one-row
--     guarantee holds only once every resolution writer is the new Ktor: deploy the new Ktor (old instances drained)
--     before relying on it. A direct insert by the resolver at or after revisado_en is read as the resolution action.
--   - Clock skew: if the Ktor clock is BEHIND the DB clock, an action the resolver took while the report was open
--     within that skew window is read as the resolution action (fail closed: 409, no duplicate). An OLD-Ktor closure
--     whose direct action row predates its revisado_en (Ktor clock AHEAD of the DB clock by more than one round trip)
--     is read as incomplete and the SAME moderator could record it once more through the new Ktor.
--   Why not a UNIQUE index on acciones_moderacion(reporte_id): the invariant "one action per report" is NOT proven
--   by the schema. baseline_main.sql declares reporte_id NULLABLE with ON DELETE SET NULL, tipo_accion admits
--   'ocultar','restaurar','eliminar','advertir','bloquear_usuario','descartar' (several actions over a report's life
--   are representable) and the policy acciones_moderacion_staff_all lets authenticated staff insert rows outside
--   Ktor; the only index on the column is non-unique (idx_acciones_moderacion_reporte_id). A unique index could
--   fail on MAIN data or block a legitimate future flow, so the guarantee lives in this RPC instead.
--
-- PREFLIGHT
--   public.reportes_contenido(id uuid, estado text, revisado_por uuid, revisado_en timestamptz) and
--   public.acciones_moderacion(reporte_id uuid, moderador_id uuid, tipo_accion text, notas text, creado_en
--   timestamptz) must exist with those types; otherwise P0001 REPORT_ACTION_PREFLIGHT and nothing is created.
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
--   Old Ktor inserts into acciones_moderacion directly: nothing it uses changes (it bypasses this RPC: see RESIDUALS).
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: `SELECT ... FOR UPDATE` on ONE reportes_contenido row, held until
--   the end of the caller's transaction (one indexed lookup + one insert): every call for the same report is
--   serialised. It does NOT block plain inserts into acciones_moderacion (old Ktor, staff policy): see RESIDUALS. `set local lock_timeout = '5s'` applies to the DDL of THIS migration only.
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
          or (a.attname = 'revisado_por' and a.atttypid = 'uuid'::regtype)
          or (a.attname = 'revisado_en' and a.atttypid = 'timestamptz'::regtype))) <> 4
    or (select count(*) from pg_attribute a
      where a.attrelid = to_regclass('public.acciones_moderacion') and not a.attisdropped
        and ((a.attname = 'reporte_id' and a.atttypid = 'uuid'::regtype)
          or (a.attname = 'moderador_id' and a.atttypid = 'uuid'::regtype)
          or (a.attname = 'tipo_accion' and a.atttypid = 'text'::regtype)
          or (a.attname = 'notas' and a.atttypid = 'text'::regtype)
          or (a.attname = 'creado_en' and a.atttypid = 'timestamptz'::regtype))) <> 5 then
    raise exception 'REPORT_ACTION_PREFLIGHT'
      using detail = 'reportes_contenido(id uuid, estado text, revisado_por uuid, revisado_en timestamptz) and acciones_moderacion(reporte_id uuid, moderador_id uuid, tipo_accion text, notas text, creado_en timestamptz) are required';
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
  v_revisado_en timestamptz;
begin
  if p_estado is null or p_estado not in ('resuelto', 'descartado') then
    return false;
  end if;

  -- Serialises every call for this report; under READ COMMITTED the next statement sees what the previous holder
  -- committed (including its action row).
  select r.estado, r.revisado_por, r.revisado_en
  into v_estado, v_revisado_por, v_revisado_en
  from public.reportes_contenido r
  where r.id = p_reporte_id
  for update;

  -- A NULL p_moderador_id only matches a report closed WITHOUT an actor (revisado_por NULL): see NULL MODERATOR.
  if not found or v_estado is distinct from p_estado or v_revisado_por is distinct from p_moderador_id then
    return false;
  end if;

  -- Idempotency key of the resolution: an action row of THIS resolution = same report, written by the resolver
  -- (revisado_por) at or after revisado_en. Earlier actions (taken while the report was open) and actions by other
  -- staff never complete it. With revisado_en NULL (legacy close) any action by the resolver counts (fail closed).
  if exists (
    select 1
    from public.acciones_moderacion a
    where a.reporte_id = p_reporte_id
      and a.moderador_id is not distinct from v_revisado_por
      and a.creado_en >= coalesce(v_revisado_en, '-infinity'::timestamptz)
  ) then
    return false;
  end if;

  -- creado_en is never earlier than revisado_en (revisado_en comes from the Ktor clock): the row written here always
  -- satisfies the key above, so a later call can never record the resolution a second time.
  insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion, notas, creado_en)
  values (p_reporte_id, p_moderador_id, p_tipo_accion, p_notas, greatest(now(), v_revisado_en));
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
