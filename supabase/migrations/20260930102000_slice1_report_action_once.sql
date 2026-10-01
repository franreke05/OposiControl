-- =============================================================================
-- FULL REMEDIATION -- a report resolution records its moderation action ONCE, atomically (MASTER6-API-001)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: one NEW NOT NULL column with a
-- constant default (catalog-only, no rewrite, no backfill) and one NEW function; no existing column, index, policy or earlier
-- migration is changed. Idempotent (add column if not exists, create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   Ktor AdminV1Gateway.resolverReporte commits the report transition first (compare-and-set PATCH), then grants the
--   optional reward (idempotent per report) and writes the moderation action LAST. A closed report whose action is
--   still missing is an incomplete resolution that the same moderator's retry resumes. The resume check ("no action
--   yet") was a separate GET followed by a plain POST, so two concurrent resumes could both insert an action row.
--   1. New column public.reportes_contenido.accion_pendiente boolean NOT NULL DEFAULT false: the EXPLICIT in-flight
--      marker of a resolution. Only the NEW Ktor compare-and-set PATCH sets it to true (in the same PATCH that closes
--      the report); only admin_record_report_action clears it, in the same transaction that inserts the action. Every
--      existing row reads false: every historical closure (MAIN data, old Ktor, staff) is NEVER resumable.
--   2. New function public.admin_record_report_action(p_reporte_id uuid, p_estado text, p_moderador_id uuid,
--      p_tipo_accion text, p_notas text) returns boolean. In ONE transaction it:
--        a. locks the report row (SELECT ... FOR UPDATE): concurrent calls for the same report are serialised;
--        b. compare-and-set on the report state: closed in p_estado ('resuelto' | 'descartado') by p_moderador_id
--           (`revisado_por IS NOT DISTINCT FROM`) AND accion_pendiente = true; otherwise returns false with no write;
--        c. inserts the action row (creado_en = the DB default now(): no application clock, never a future stamp),
--           sets accion_pendiente = false and returns true. A second call sees the marker cleared and returns false.
--   Ktor writes the audit row and the strategic event only when it gets true, and answers 409 otherwise. Its resume
--   pre-check reads the same marker (ReporteRow.accionPendiente; absent = false), so an earlier action taken while the
--   report was open, or an action by other staff, never blocks or completes a resolution: only the marker decides.
--
-- HISTORICAL DATA (verified facts, `git show main:backend/ktor/.../AdminV1Routes.kt` resolverReporte, and
-- baseline_main.sql)
--   The MAIN (old) Ktor resolverReporte had no state guard and wrote, in this order: (1) POST /rest/v1/acciones_moderacion
--   with moderador_id = the JWT actor (NULL for the static-token principal) and creado_en = the DB default now();
--   (2) the optional grant; (3) PATCH reportes_contenido with estado, revisado_en = the Ktor clock taken AFTER step 1,
--   revisado_por = the same actor, tickets_otorgados = the requested amount and recompensa_entregada = (amount > 0).
--   So with synchronised clocks every historical action has creado_en < revisado_en, a re-resolution of a closed
--   report added another action row, and no existing column (estado, revisado_por, revisado_en, tickets_otorgados,
--   recompensa_entregada, actualizado_en) distinguishes such a row from a new-flow closure whose action is missing.
--   Hence the explicit marker: any timestamp key (the round-2 `creado_en >= revisado_en`) misread every historical
--   resolution as incomplete and let the same moderator record it a second time.
--
-- NULL MODERATOR (p_moderador_id NULL)
--   Explicit: a NULL moderator matches only a report closed WITHOUT an actor (revisado_por NULL, the legacy static
--   token resolution, which never has a JWT subject) whose marker is set. A NULL moderator against a report closed by
--   a real moderator, or a real moderator against a report closed without an actor, returns false with no write.
--   Ktor never RESUMES without an actor (deny by default), so only the first static-token resolution reaches this path.
--
-- RESIDUALS (accepted, documented in SLICE_1_PRODUCTION_GATE)
--   - Writers that bypass the RPC: OLD Ktor (direct POST /rest/v1/acciones_moderacion, PATCH without the marker) and
--     authenticated staff through the policy acciones_moderacion_staff_all insert action rows without the report row
--     lock and never touch the marker. An old Ktor re-resolving a report that the NEW Ktor left in flight would add
--     its own action and leave the marker set: stop the old Ktor before migrating and never restart it.
--   - The marker is a column of reportes_contenido, which authenticated clients may INSERT (policy
--     reportes_contenido_insert_auth, reportado_por = auth.uid()). A forged pre-closed report with the marker set and
--     revisado_por = some moderator can only be "resumed" by that same moderator through Ktor, recording one action on
--     a report of the attacker's own; the same forged row was already resumable before this column existed.
--   Why not a UNIQUE index on acciones_moderacion(reporte_id): the invariant "one action per report" is NOT proven
--   by the schema. baseline_main.sql declares reporte_id NULLABLE with ON DELETE SET NULL, tipo_accion admits
--   'ocultar','restaurar','eliminar','advertir','bloquear_usuario','descartar' (several actions over a report's life
--   are representable, and the old Ktor wrote several on re-resolution) and the policy acciones_moderacion_staff_all
--   lets authenticated staff insert rows outside Ktor; the only index on the column is non-unique
--   (idx_acciones_moderacion_reporte_id). A unique index would fail on MAIN data or block a legitimate future flow.
--
-- PREFLIGHT
--   public.reportes_contenido(id uuid, estado text, revisado_por uuid, revisado_en timestamptz) and
--   public.acciones_moderacion(reporte_id uuid, moderador_id uuid, tipo_accion text, notas text, creado_en
--   timestamptz) must exist with those types, and an existing reportes_contenido.accion_pendiente must be boolean NOT
--   NULL; otherwise P0001 REPORT_ACTION_PREFLIGHT and nothing is created.
--
-- FORWARD PLAN
--   Apply BEFORE deploying the Ktor build that sends accion_pendiente and calls admin_record_report_action. With an
--   older DB that Ktor's compare-and-set PATCH is rejected by PostgREST (unknown column) BEFORE any write: the report
--   stays open and nothing is granted. MAIN only at the dedicated apply gate. PostgREST picks up the new column via
--   the schema reload notification at the end of this migration.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops the function and the marker column. Roll Ktor back FIRST (the new Ktor writes the
--   column and calls the function). Data effect: only the in-flight markers this migration introduced are dropped;
--   those incomplete resolutions then answer 409 like any historical closure (fail closed, never a second action).
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor inserts into acciones_moderacion directly and PATCHes reportes_contenido without the column: both still
--   work (the default fills the marker as false; see RESIDUALS).
--
-- LOCKING
--   ALTER TABLE ... ADD COLUMN with a constant default is catalog-only in PostgreSQL >= 11 (no table rewrite), but it
--   takes an ACCESS EXCLUSIVE lock on reportes_contenido for that instant; `set local lock_timeout = '5s'` makes the
--   migration fail instead of queueing behind long readers (retry it). Function DDL: catalog locks only. Runtime:
--   `SELECT ... FOR UPDATE` on ONE reportes_contenido row, held until the end of the caller's transaction (one indexed
--   lookup + one insert + one update): every call for the same report is serialised. It does NOT block plain inserts
--   into acciones_moderacion (old Ktor, staff policy): see RESIDUALS.
--
-- DATA LOSS
--   None on apply (existing rows read the default false). ROLLBACK drops only the marker column created here.
--
-- INDEXES
--   None (lookups use the primary key of reportes_contenido).
--
-- RLS AND GRANTS
--   No policy change; the column inherits the existing table grants. The function is SECURITY DEFINER with
--   search_path '' and row_security off (fail closed: a client can never call it), reads no auth schema. EXECUTE
--   revoked from public/anon/authenticated and granted to service_role only.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_record_report_action(uuid, text, uuid, text, text);
-- alter table public.reportes_contenido drop column if exists accion_pendiente;
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
--   and not (p.prosecdef and p.proconfig @> array['search_path=""'])
-- union all
-- select 'report_action_marker_column_missing',
--        (case when exists (select 1 from pg_attribute a
--                           left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
--                           where a.attrelid = to_regclass('public.reportes_contenido') and a.attname = 'accion_pendiente'
--                             and not a.attisdropped and a.atttypid = 'boolean'::regtype and a.attnotnull
--                             and pg_get_expr(d.adbin, d.adrelid) = 'false')
--              then 0 else 1 end)::bigint, null;
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
          or (a.attname = 'creado_en' and a.atttypid = 'timestamptz'::regtype))) <> 5
    or exists (select 1 from pg_attribute a
      where a.attrelid = to_regclass('public.reportes_contenido') and not a.attisdropped
        and a.attname = 'accion_pendiente' and not (a.atttypid = 'boolean'::regtype and a.attnotnull)) then
    raise exception 'REPORT_ACTION_PREFLIGHT'
      using detail = 'reportes_contenido(id uuid, estado text, revisado_por uuid, revisado_en timestamptz[, accion_pendiente boolean not null]) and acciones_moderacion(reporte_id uuid, moderador_id uuid, tipo_accion text, notas text, creado_en timestamptz) are required';
  end if;
end;
$$;

-- In-flight marker of a resolution (see header). The default fills every existing row with false: no historical
-- closure is ever resumable.
alter table public.reportes_contenido add column if not exists accion_pendiente boolean not null default false;

comment on column public.reportes_contenido.accion_pendiente is
  'true only between the Ktor compare-and-set that closes the report and the admin_record_report_action call that records its moderation action (20260930102000)';

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
  v_accion_pendiente boolean;
begin
  if p_estado is null or p_estado not in ('resuelto', 'descartado') then
    return false;
  end if;

  -- Serialises every call for this report; under READ COMMITTED the next statement sees what the previous holder
  -- committed (including the cleared marker).
  select r.estado, r.revisado_por, r.accion_pendiente
  into v_estado, v_revisado_por, v_accion_pendiente
  from public.reportes_contenido r
  where r.id = p_reporte_id
  for update;

  -- A NULL p_moderador_id only matches a report closed WITHOUT an actor (revisado_por NULL): see NULL MODERATOR.
  -- Only a resolution the new compare-and-set left in flight (marker true) is recorded: historical closures never.
  if not found or v_estado is distinct from p_estado or v_revisado_por is distinct from p_moderador_id
    or v_accion_pendiente is not true then
    return false;
  end if;

  -- creado_en: the DB default now() (no application clock, never in the future).
  insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion, notas)
  values (p_reporte_id, p_moderador_id, p_tipo_accion, p_notas);

  update public.reportes_contenido
  set accion_pendiente = false
  where id = p_reporte_id;

  return true;
end;
$$;

revoke all on function public.admin_record_report_action(uuid, text, uuid, text, text) from public, anon, authenticated;
grant execute on function public.admin_record_report_action(uuid, text, uuid, text, text) to service_role;

do $$
begin
  if to_regprocedure('public.admin_record_report_action(uuid,text,uuid,text,text)') is null
    or not exists (select 1 from pg_attribute a
                   where a.attrelid = to_regclass('public.reportes_contenido') and a.attname = 'accion_pendiente'
                     and not a.attisdropped) then
    raise exception 'REPORT_ACTION_NOT_INSTALLED';
  end if;
end;
$$;

notify pgrst, 'reload schema';

commit;
