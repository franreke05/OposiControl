-- =============================================================================
-- FULL REMEDIATION R8 -- a client report carries server timestamps and bounded text (MASTERF2-DB-002 / RC-08)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). NEW forward-only migration: 20260930102000 (which tightened
-- the same policy) is pushed and NOT edited. One policy narrowed, two CHECK constraints added; no column, function,
-- index, grant or row changes. Idempotent, one transaction.
--
-- EVIDENCE
--   - 20260930102000 (item 3) left reportes_contenido_insert_auth checking only the status/server columns; MAIN
--     (supabase/baseline/main_shape_subset.sql :25) has motivo text NOT NULL and descripcion text without any length
--     bound, and creado_en/actualizado_en are client-settable on INSERT (defaults only). authenticated keeps INSERT
--     (:392, 102100 revoked only TRUNCATE/TRIGGER/REFERENCES).
--   - So any authenticated user can insert, through the Data API, a report backdated to any creado_en (GET /reportes
--     orders creado_en asc and the admin client reads one page: a backdated flood buries genuine reports) and
--     arbitrarily large motivo/descripcion text into the moderation table. dbtest
--     Slice1ReportInsertBoundsMigrationTest proves both under SET ROLE authenticated + claim sub BEFORE this migration.
--   - No required flow: Ktor never INSERTs reports (it GETs/PATCHes them and calls rpc/admin_record_report_action with
--     the service_role key, which bypasses RLS); OposiBot has no report-insert code. The legitimate client insert
--     (own, pending, every server column at its default) keeps passing (dbtest).
--
-- WHAT THIS MIGRATION DOES
--   1. reportes_contenido_insert_auth WITH CHECK = the 102000 expression AND creado_en = now() AND actualizado_en =
--      now(). now() is the transaction timestamp, the same value the column DEFAULT now() writes in that statement:
--      an insert that omits both columns passes; any explicit other value (backdated or future) is rejected by RLS
--      (42501). USING/command/roles unchanged.
--   2. CHECK reportes_contenido_motivo_longitud_check (char_length(motivo) <= 2000) and
--      reportes_contenido_descripcion_longitud_check (descripcion is null or char_length(descripcion) <= 2000), added
--      NOT VALID (no scan while adding) and then VALIDATEd in this transaction when no existing row exceeds them. If
--      historical rows exceed a bound, the constraint stays NOT VALID (still enforced for every new or updated row)
--      and a NOTICE gives the count only (never the text); VERIFY row report_text_bound_not_validated is
--      INFORMATIONAL (failures always 0, info = 'not validated: <constraint>') so the operator can review and VALIDATE
--      later. 2000 characters (code points) = FEEDBACK_DESCRIPTION_MAX of the Ktor API (AppV1Routes.kt), the only
--      audited free-text cap of the Slice 1 API (MASTER6-API-006); Ktor has no report-create route to mirror. A
--      tighter cap for motivo is a product decision (not taken here).
--   UNCHANGED: every grant, every other policy, the BEFORE UPDATE trigger, admin_record_report_action.
--
-- PREFLIGHT (fail closed, P0001 REPORT_INSERT_BOUNDS_PREFLIGHT, nothing changed)
--   - public.reportes_contenido has motivo text, descripcion text, creado_en timestamptz, actualizado_en timestamptz
--     and accion_pendiente boolean (20260930102000 applied);
--   - reportes_contenido_insert_auth is a PERMISSIVE INSERT policy TO authenticated whose WITH CHECK is EXACTLY the
--     102000 expression (first run) or EXACTLY the expression of this migration (rerun), compared on the server's own
--     deparse (the policy is set to each candidate inside this transaction and the original is compared; on mismatch
--     the exception rolls everything back). Any other expression could not be restored by the ROLLBACK;
--   - a pre-existing constraint with one of the two names must be the same CHECK (rerun), otherwise abort.
--
-- HISTORICAL DATA
--   Rows are only read (bound check before VALIDATE); none is written or deleted. Historical creado_en values are
--   unchanged (the policy applies to new client inserts only).
--
-- FORWARD PLAN
--   Order-independent from Ktor (no Ktor build inserts reports). Apply after 102000. MAIN only at the dedicated apply
--   gate. No PostgREST schema reload is needed (no column changes).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below restores the 102000 WITH CHECK verbatim (same deparse) and drops the two constraints.
--   Idempotent. Rolling back re-opens backdated and unbounded client inserts. No data is touched.
--
-- LOCKING
--   ALTER POLICY and ADD CONSTRAINT take an ACCESS EXCLUSIVE lock on reportes_contenido until COMMIT; VALIDATE scans
--   the table under that lock (reportes_contenido is the moderation queue: small; reads of it wait for the scan).
--   `set local lock_timeout = '5s'` makes the migration fail instead of queueing (retry it).
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None.
--
-- RLS AND GRANTS
--   Narrowing only: reportes_contenido_insert_auth WITH CHECK gains two predicates. No grant or other policy touched.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- alter policy reportes_contenido_insert_auth on public.reportes_contenido
--   with check (
--     reportado_por = (select auth.uid())
--     and estado = 'pendiente'
--     and revisado_por is null
--     and revisado_en is null
--     and tickets_otorgados = 0
--     and recompensa_entregada = false
--     and accion_pendiente = false
--   );
-- alter table public.reportes_contenido drop constraint if exists reportes_contenido_motivo_longitud_check;
-- alter table public.reportes_contenido drop constraint if exists reportes_contenido_descripcion_longitud_check;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'report_insert_policy_timestamps_missing' as check_name,
--        (case when exists (select 1 from pg_policy p
--                           where p.polrelid = to_regclass('public.reportes_contenido')
--                             and p.polname = 'reportes_contenido_insert_auth' and p.polcmd = 'a' and p.polpermissive
--                             and pg_get_expr(p.polwithcheck, p.polrelid) like '%(creado_en = now())%'
--                             and pg_get_expr(p.polwithcheck, p.polrelid) like '%(actualizado_en = now())%'
--                             and pg_get_expr(p.polwithcheck, p.polrelid) like '%accion_pendiente = false%')
--              then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'report_text_bound_missing', (2 - count(*))::bigint, string_agg(c.conname, ',')
-- from pg_constraint c
-- where c.conrelid = to_regclass('public.reportes_contenido') and c.contype = 'c'
--   and ((c.conname = 'reportes_contenido_motivo_longitud_check'
--         and pg_get_constraintdef(c.oid) like '%char_length(motivo) <= 2000%')
--     or (c.conname = 'reportes_contenido_descripcion_longitud_check'
--         and pg_get_constraintdef(c.oid) like '%char_length(descripcion) <= 2000%'))
-- union all
-- select 'report_text_bound_not_validated', 0::bigint,
--        coalesce('not validated: ' || string_agg(c.conname, ','), 'all validated')
-- from pg_constraint c
-- where c.conrelid = to_regclass('public.reportes_contenido') and c.contype = 'c' and not c.convalidated
--   and c.conname in ('reportes_contenido_motivo_longitud_check', 'reportes_contenido_descripcion_longitud_check');
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_rel oid := to_regclass('public.reportes_contenido');
  v_actual text;
  v_102000 text;
  v_102300 text;
begin
  if v_rel is null
     or (select string_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod), ',' order by a.attname)
         from pg_attribute a
         where a.attrelid = v_rel and not a.attisdropped
           and a.attname in ('motivo', 'descripcion', 'creado_en', 'actualizado_en', 'accion_pendiente'))
        is distinct from 'accion_pendiente:boolean,actualizado_en:timestamp with time zone,creado_en:timestamp with time zone,descripcion:text,motivo:text' then
    raise exception 'REPORT_INSERT_BOUNDS_PREFLIGHT'
      using detail = 'public.reportes_contenido(motivo text, descripcion text, creado_en timestamptz, actualizado_en timestamptz, accion_pendiente boolean) is required (20260930102000 first)';
  end if;

  if not exists (select 1 from pg_policy p
                 where p.polrelid = v_rel and p.polname = 'reportes_contenido_insert_auth' and p.polcmd = 'a'
                   and p.polpermissive and p.polroles = array['authenticated'::regrole::oid]) then
    raise exception 'REPORT_INSERT_BOUNDS_PREFLIGHT'
      using detail = 'reportes_contenido_insert_auth must be a permissive INSERT policy to authenticated';
  end if;

  select pg_get_expr(p.polwithcheck, p.polrelid) into v_actual
  from pg_policy p where p.polrelid = v_rel and p.polname = 'reportes_contenido_insert_auth';

  -- Server deparse of both candidates (set inside this transaction; every path below either sets the final
  -- expression or raises, which rolls these back).
  alter policy reportes_contenido_insert_auth on public.reportes_contenido
    with check (
      reportado_por = (select auth.uid())
      and estado = 'pendiente'
      and revisado_por is null
      and revisado_en is null
      and tickets_otorgados = 0
      and recompensa_entregada = false
      and accion_pendiente = false
    );
  select pg_get_expr(p.polwithcheck, p.polrelid) into v_102000
  from pg_policy p where p.polrelid = v_rel and p.polname = 'reportes_contenido_insert_auth';

  alter policy reportes_contenido_insert_auth on public.reportes_contenido
    with check (
      reportado_por = (select auth.uid())
      and estado = 'pendiente'
      and revisado_por is null
      and revisado_en is null
      and tickets_otorgados = 0
      and recompensa_entregada = false
      and accion_pendiente = false
      and creado_en = now()
      and actualizado_en = now()
    );
  select pg_get_expr(p.polwithcheck, p.polrelid) into v_102300
  from pg_policy p where p.polrelid = v_rel and p.polname = 'reportes_contenido_insert_auth';

  if v_actual is distinct from v_102000 and v_actual is distinct from v_102300 then
    raise exception 'REPORT_INSERT_BOUNDS_PREFLIGHT'
      using detail = 'reportes_contenido_insert_auth WITH CHECK is neither the 20260930102000 nor the 20260930102300 expression';
  end if;

  if exists (select 1 from pg_constraint c
             where c.conrelid = v_rel
               and c.conname in ('reportes_contenido_motivo_longitud_check', 'reportes_contenido_descripcion_longitud_check')
               and not (c.contype = 'c'
                        and ((c.conname = 'reportes_contenido_motivo_longitud_check'
                              and pg_get_constraintdef(c.oid) ~ '^CHECK \(\(char_length\(motivo\) <= 2000\)\)( NOT VALID)?$')
                          or (c.conname = 'reportes_contenido_descripcion_longitud_check'
                              and pg_get_constraintdef(c.oid) ~ '^CHECK \(\(\(descripcion IS NULL\) OR \(char_length\(descripcion\) <= 2000\)\)\)( NOT VALID)?$')))) then
    raise exception 'REPORT_INSERT_BOUNDS_PREFLIGHT'
      using detail = 'a constraint named reportes_contenido_*_longitud_check exists with another definition';
  end if;
end;
$$;
-- Item 1 is now in place (the last ALTER POLICY of the block above).

do $$
declare
  v_rel oid := 'public.reportes_contenido'::regclass;
  v_exceso bigint;
begin
  if not exists (select 1 from pg_constraint where conrelid = v_rel and conname = 'reportes_contenido_motivo_longitud_check') then
    alter table public.reportes_contenido
      add constraint reportes_contenido_motivo_longitud_check check (char_length(motivo) <= 2000) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conrelid = v_rel and conname = 'reportes_contenido_descripcion_longitud_check') then
    alter table public.reportes_contenido
      add constraint reportes_contenido_descripcion_longitud_check
      check (descripcion is null or char_length(descripcion) <= 2000) not valid;
  end if;

  if exists (select 1 from pg_constraint where conrelid = v_rel and conname = 'reportes_contenido_motivo_longitud_check' and not convalidated) then
    select count(*) into v_exceso from public.reportes_contenido where char_length(motivo) > 2000;
    if v_exceso = 0 then
      alter table public.reportes_contenido validate constraint reportes_contenido_motivo_longitud_check;
    else
      raise notice 'reportes_contenido_motivo_longitud_check left NOT VALID: % historical rows exceed 2000', v_exceso;
    end if;
  end if;
  if exists (select 1 from pg_constraint where conrelid = v_rel and conname = 'reportes_contenido_descripcion_longitud_check' and not convalidated) then
    select count(*) into v_exceso from public.reportes_contenido where char_length(descripcion) > 2000;
    if v_exceso = 0 then
      alter table public.reportes_contenido validate constraint reportes_contenido_descripcion_longitud_check;
    else
      raise notice 'reportes_contenido_descripcion_longitud_check left NOT VALID: % historical rows exceed 2000', v_exceso;
    end if;
  end if;
end;
$$;

commit;
