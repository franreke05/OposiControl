-- =============================================================================
-- V1 -- RANKING JOB BATCH TABLE SHAPE (window 1.3, found by the S3 review of the MAIN capture of 2026-10-14). Corrective; 20261022101600 / 20261022101700
-- are NOT edited. Forward-only, guarded: a no-op on a database that already has the V1 shape (PG17 gate, backend/dbtest, local overlay).
-- Requires 20261022101600 (app_job_dificultad, admin_recompute_stats) and 20261022101700 (app_job_ranking).
-- =============================================================================
-- WHY: every V1 batch writer uses public.lotes_actualizacion_ranking with the shape of the local overlay (supabase/baseline/main_shape_subset_extra_engagement.sql):
--   tipo text, estado 'en_curso' -> 'ok', iniciado_en default now(), filas integer, detalle jsonb, error text.
-- The MAIN capture of 2026-10-14 (baseline_main.sql, sha256 07acead5...) has the PRE-V1 shape instead:
--   (id, liga_id, estado default 'pendiente' CHECK in (pendiente, ejecutando, completado, fallido), iniciado_en NULL without default, finalizado_en,
--    total_usuarios_procesados, mensaje_error, creado_en)
-- and no V1 migration alters the table. On that shape app_job_dificultad / app_job_ranking (run every tick by the Ktor DailyChallengeJob) fail on their first
-- statement that names tipo (column does not exist), and admin_recompute_stats fails the same way; even with the columns, estado = 'ok' violates the MAIN CHECK
-- and a NULL iniciado_en would break the cooldown and the watermark ("order by iniciado_en desc"). Result: no difficulty refresh and no ranking positions.
-- The plpgsql bodies were created without error in the 2026-10-22 window because plpgsql does not resolve columns at CREATE time.
--
-- WHAT (only when the MAIN marker column total_usuarios_procesados exists; each step guarded):
--   * add tipo text (nullable: legacy rows have none; every V1 writer sets it), filas integer, detalle jsonb NOT NULL DEFAULT '{}', error text;
--   * estado DEFAULT 'en_curso' (V1 writers insert (tipo) and rely on it) and iniciado_en DEFAULT now() (cooldown / watermark);
--   * the estado CHECK keeps the four legacy values and admits the V1 ones (en_curso, ok, error).
-- Legacy columns, constraints, index, policy and grants are left as they are (the V1 bodies never use them). No data is rewritten.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate, where it is a no-op): only on the MAIN shape, the rows written by the V1 batch writers (tipo is not null:
-- job bookkeeping that cannot exist in the pre-V1 shape) are deleted, the four columns are dropped and the legacy CHECK and defaults come back. After it the V1
-- jobs fail again exactly as before this migration.
-- ROLLBACK-BEGIN
-- begin;
-- set local lock_timeout = '5s';
-- do $$
-- begin
--   if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name = 'total_usuarios_procesados') then
--     if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name = 'tipo') then
--       delete from public.lotes_actualizacion_ranking where tipo is not null;
--     end if;
--     alter table public.lotes_actualizacion_ranking drop constraint if exists lotes_actualizacion_ranking_estado_check;
--     alter table public.lotes_actualizacion_ranking add constraint lotes_actualizacion_ranking_estado_check
--       check (estado = any (array['pendiente'::text, 'ejecutando'::text, 'completado'::text, 'fallido'::text]));
--     alter table public.lotes_actualizacion_ranking alter column estado set default 'pendiente'::text, alter column iniciado_en drop default;
--     alter table public.lotes_actualizacion_ranking drop column if exists tipo, drop column if exists filas, drop column if exists detalle, drop column if exists error;
--   end if;
-- end
-- $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'ranking_batches_missing_v1_column:' || c.n as check_name,
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name = c.n)
--              then 0 else 1 end)::bigint as failures
--   from (values ('tipo'), ('estado'), ('iniciado_en'), ('finalizado_en'), ('filas'), ('detalle'), ('error')) c(n)
-- union all
-- select 'ranking_batches_insert_defaults_not_v1',
--        (case when (select column_default from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name = 'estado') like '''en_curso''%'
--                and (select column_default from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name = 'iniciado_en') is not null
--              then 0 else 1 end)::bigint
-- union all
-- select 'ranking_batches_estado_check_rejects_v1_values',
--        (select count(*) from pg_constraint k where k.conrelid = 'public.lotes_actualizacion_ranking'::regclass and k.contype = 'c' and pg_get_constraintdef(k.oid) like '%estado%'
--            and not (pg_get_constraintdef(k.oid) like '%''en_curso''%' and pg_get_constraintdef(k.oid) like '%''ok''%'))::bigint
-- union all
-- select 'ranking_batches_open_to_clients',
--        (case when has_table_privilege('anon', 'public.lotes_actualizacion_ranking', 'select') or has_table_privilege('authenticated', 'public.lotes_actualizacion_ranking', 'select')
--              then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regclass('public.lotes_actualizacion_ranking') is null or to_regprocedure('public.app_job_dificultad(boolean)') is null
     or to_regprocedure('public.app_job_ranking(boolean)') is null then
    raise exception using errcode = 'P0001', message = 'V1_RANKING_LOTES_SHAPE_PREFLIGHT', hint = 'apply 20261022101600 and 20261022101700 first';
  end if;
end
$$;

do $$
begin
  if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking' and column_name = 'total_usuarios_procesados') then
    alter table public.lotes_actualizacion_ranking add column if not exists tipo text, add column if not exists filas integer,
      add column if not exists detalle jsonb not null default '{}'::jsonb, add column if not exists error text;
    alter table public.lotes_actualizacion_ranking alter column estado set default 'en_curso'::text, alter column iniciado_en set default now();
    if exists (select 1 from pg_constraint k where k.conrelid = 'public.lotes_actualizacion_ranking'::regclass and k.conname = 'lotes_actualizacion_ranking_estado_check'
                 and not (pg_get_constraintdef(k.oid) like '%''en_curso''%' and pg_get_constraintdef(k.oid) like '%''ok''%')) then
      alter table public.lotes_actualizacion_ranking drop constraint lotes_actualizacion_ranking_estado_check;
      alter table public.lotes_actualizacion_ranking add constraint lotes_actualizacion_ranking_estado_check
        check (estado = any (array['pendiente'::text, 'ejecutando'::text, 'completado'::text, 'fallido'::text, 'en_curso'::text, 'ok'::text, 'error'::text]));
    end if;
  end if;
end
$$;

commit;
