-- V1 WAVE 4: LEAGUES, RANKING, TOP 3, PLACE PROBABILITY. Contract: E1-V1-W4-CONTRACT.md (FA33). Design: E1-V1-ENGAGEMENT-DESIGN.md section 3 (C-R1..C-R3, C-S3, C-S4).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101600 (first-answer stats, league assignment, jobs infrastructure).
-- The league (tier by first answers) is already assigned by the W3 hook; this migration materialises, with a bounded throttled job, the position inside tier x specialty (ordered by Nota media), the place probability
-- (worse Nota media / comparable users, comparable = same specialty and same window, full windows only) and the top 3 of each cohort (opt-in public handle = the user-chosen username). Reads are own-data only and enforce
-- the minimum cohort (cohorte_minima_comparativa) inside the database. perfiles_usuario.ranking_publico (default false) is the opt-in.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.admin_recompute_stats(p_actor uuid, p_alcance text)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_res jsonb;
-- begin
--   if p_alcance is distinct from 'dificultad' then
--     raise exception using errcode = 'P0001', message = 'STATS_SCOPE_INVALID';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('recompute:dificultad', 0));
--   if exists (select 1 from public.lotes_actualizacion_ranking l where l.tipo = 'dificultad_admin' and l.iniciado_en > clock_timestamp() - make_interval(mins => private.config_int('recalculo_admin_cooldown_min', 10))) then
--     raise exception using errcode = 'P0001', message = 'STATS_RECOMPUTE_COOLDOWN';
--   end if;
--   insert into public.lotes_actualizacion_ranking (tipo, estado, finalizado_en, detalle) values ('dificultad_admin', 'ok', clock_timestamp(), jsonb_build_object('actor', p_actor, 'alcance', p_alcance));
--   insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
--   values (p_actor, 'estadisticas_recalculo', 'estadisticas_globales_pregunta', null, null, jsonb_build_object('alcance', p_alcance));
--   v_res := public.app_job_dificultad(true);
--   return v_res;
-- end;
-- $$;
-- revoke all on function public.admin_recompute_stats(uuid, text) from public, anon, authenticated;
-- grant execute on function public.admin_recompute_stats(uuid, text) to service_role;
-- drop function if exists public.app_set_ranking_visibility(uuid, boolean);
-- drop function if exists public.app_ranking_me(uuid);
-- drop function if exists public.app_job_ranking(boolean);
-- drop function if exists private.cohorte_tramo(integer);
-- drop table if exists public.ranking_top;
-- delete from public.configuracion_v1 where clave = 'ranking_job_minutos';
-- alter table public.estado_competitivo_usuario drop column if exists ranking_calculado_en, drop column if exists ranking_cohorte, drop column if exists probabilidad_cohorte, drop column if exists probabilidad_plaza;
-- alter table public.perfiles_usuario drop column if exists ranking_publico;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'ranking_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_ranking_me(uuid)'), ('public.app_set_ranking_visibility(uuid,boolean)'), ('public.app_job_ranking(boolean)'), ('public.admin_recompute_stats(uuid,text)')) f(n)
-- union all
-- select 'ranking_top_client_access',
--        (case when has_table_privilege('anon', 'public.ranking_top', 'select') or has_table_privilege('authenticated', 'public.ranking_top', 'select') or has_table_privilege('service_role', 'public.ranking_top', 'select')
--                or not (select c.relrowsecurity from pg_class c where c.oid = 'public.ranking_top'::regclass) then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_job_dificultad(boolean)') is null or to_regclass('public.estado_competitivo_usuario') is null or to_regclass('public.lotes_actualizacion_ranking') is null
     or to_regprocedure('private.config_v1_range(text)') is null then
    raise exception using errcode = 'P0001', message = 'V1_RANKING_PREFLIGHT', hint = 'apply 20261022101600 first';
  end if;
end
$$;

-- ---- 1. schema
alter table public.perfiles_usuario add column if not exists ranking_publico boolean not null default false;
alter table public.estado_competitivo_usuario
  add column if not exists probabilidad_plaza numeric(5, 2),
  add column if not exists probabilidad_cohorte integer,
  add column if not exists ranking_cohorte integer,
  add column if not exists ranking_calculado_en timestamptz;

-- the materialised top 3 of every cohort (specialty x league): server only, rewritten by the job
create table if not exists public.ranking_top (
  especialidad_id uuid not null,
  liga_nivel_id uuid not null references public.ligas_nivel(id) on delete cascade,
  posicion smallint not null check (posicion between 1 and 3),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  handle text not null,
  nota_media numeric(4, 2) not null,
  calculado_en timestamptz not null default clock_timestamp(),
  primary key (especialidad_id, liga_nivel_id, posicion)
);
alter table public.ranking_top enable row level security;
revoke all on table public.ranking_top from public, anon, authenticated, service_role;

insert into public.configuracion_v1 (clave, valor_integer) values ('ranking_job_minutos', 15) on conflict (clave) do nothing;

-- ---- 2. cohort bucket (never the exact size)
create or replace function private.cohorte_tramo(p_n integer)
returns integer
language sql
stable
set search_path = ''
as $$
  select case when p_n >= 5000 then 5000 when p_n >= 1000 then 1000 when p_n >= 500 then 500 when p_n >= 100 then 100 else private.config_int('cohorte_minima_comparativa', 50) end
$$;
revoke all on function private.cohorte_tramo(integer) from public, anon, authenticated;

-- ---- 3. the periodic job (bounded, throttled, advisory lock, statement timeout, watermark; only specialties with changed users are recomputed)
create or replace function public.app_job_ranking(p_forzar boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_hasta timestamptz;
  v_lote uuid;
  v_inicio timestamptz := clock_timestamp();
  v_min integer := private.config_int('cohorte_minima_comparativa', 50);
  v_lim integer := case when p_forzar then 2000 else 200 end;
  v_nuevo timestamptz;
  v_esp uuid[];
  v_filas integer := 0;
begin
  if not pg_try_advisory_xact_lock(hashtextextended('job:ranking', 0)) then
    return jsonb_build_object('estado', 'OCUPADO');
  end if;
  perform set_config('statement_timeout', '30000', true);
  if not p_forzar and exists (select 1 from public.lotes_actualizacion_ranking l where l.tipo = 'ranking' and l.estado = 'ok'
                               and l.iniciado_en > v_inicio - make_interval(mins => private.config_int('ranking_job_minutos', 15))) then
    return jsonb_build_object('estado', 'RECIENTE');
  end if;
  select coalesce((l.detalle ->> 'hasta')::timestamptz, '-infinity'::timestamptz) into v_hasta
    from public.lotes_actualizacion_ranking l where l.tipo = 'ranking' and l.estado = 'ok' order by l.iniciado_en desc limit 1;
  v_hasta := case when p_forzar then '-infinity'::timestamptz else coalesce(v_hasta, '-infinity'::timestamptz) end;
  insert into public.lotes_actualizacion_ranking (tipo) values ('ranking') returning id into v_lote;
  select array_agg(e.esp), max(e.m) into v_esp, v_nuevo
    from (select pf.especialidad_principal_id as esp, max(ec.actualizado_en) as m
            from public.estado_competitivo_usuario ec join public.perfiles_usuario pf on pf.usuario_id = ec.usuario_id
           where ec.liga_nivel_id is not null and pf.especialidad_principal_id is not null and ec.actualizado_en > v_hasta
           group by pf.especialidad_principal_id order by 2 limit v_lim) e;
  if v_esp is not null then
    drop table if exists pg_temp.rk;
    create temp table rk on commit drop as
    with base as (
      select ec.usuario_id, pf.especialidad_principal_id as esp, ec.liga_nivel_id as liga, ec.nota_media_actual as nota, ec.preguntas_validas_ranking as v, ec.ventana_calculo as w
        from public.estado_competitivo_usuario ec join public.perfiles_usuario pf on pf.usuario_id = ec.usuario_id
       where pf.especialidad_principal_id = any (v_esp) and ec.liga_nivel_id is not null and ec.nota_media_actual is not null
         and ec.preguntas_validas_ranking >= coalesce(ec.ventana_calculo, 1)  -- only FULL windows are comparable (a 1-question 10.0 must not top a league)
    ), pos as (
      select b.*, row_number() over (partition by b.esp, b.liga order by b.nota desc, b.v desc, b.usuario_id) as p, count(*) over (partition by b.esp, b.liga) as n from base b
    ), prob as (
      select f.usuario_id, rank() over (partition by f.esp, f.w order by f.nota) - 1 as peores, count(*) over (partition by f.esp, f.w) as n
        from base f where f.v >= f.w
    )
    select pos.usuario_id, pos.esp, pos.liga, pos.nota, pos.p, pos.n as n_liga, prob.peores, prob.n as n_prob from pos left join prob on prob.usuario_id = pos.usuario_id;
    update public.estado_competitivo_usuario ec set ranking_liga_posicion = null, ranking_cohorte = null, probabilidad_plaza = null, probabilidad_cohorte = null
     where ec.ranking_calculado_en is not null and not exists (select 1 from pg_temp.rk where rk.usuario_id = ec.usuario_id)
       and exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = ec.usuario_id and pf.especialidad_principal_id = any (v_esp));
    update public.estado_competitivo_usuario ec
       set ranking_liga_posicion = rk.p, ranking_cohorte = rk.n_liga,
           probabilidad_plaza = case when rk.n_prob > 1 then round(rk.peores * 100.0 / (rk.n_prob - 1), 2) end,
           probabilidad_cohorte = rk.n_prob, ranking_calculado_en = clock_timestamp()
      from rk where ec.usuario_id = rk.usuario_id;
    get diagnostics v_filas = row_count;
    delete from public.ranking_top t where t.especialidad_id = any (v_esp);
    insert into public.ranking_top (especialidad_id, liga_nivel_id, posicion, usuario_id, handle, nota_media)
    select rk.esp, rk.liga, rk.p, rk.usuario_id, pf.username, rk.nota
      from rk join public.perfiles_usuario pf on pf.usuario_id = rk.usuario_id
     where rk.p <= 3 and rk.n_liga >= v_min and pf.ranking_publico and pf.username is not null;
  end if;
  update public.lotes_actualizacion_ranking
     set estado = 'ok', finalizado_en = clock_timestamp(), filas = v_filas,
         detalle = jsonb_build_object('hasta', least(coalesce(v_nuevo, v_hasta), v_inicio - interval '2 minutes'), 'forzado', p_forzar)
   where id = v_lote;
  return jsonb_build_object('estado', 'OK', 'filas', v_filas);
end;
$$;
revoke all on function public.app_job_ranking(boolean) from public, anon, authenticated;
grant execute on function public.app_job_ranking(boolean) to service_role;

-- admin recompute: now two scopes (difficulty, ranking); same audit, lock and cooldown
create or replace function public.admin_recompute_stats(p_actor uuid, p_alcance text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_alcance is null or p_alcance not in ('dificultad', 'ranking') then
    raise exception using errcode = 'P0001', message = 'STATS_SCOPE_INVALID';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('recompute:' || p_alcance, 0));
  if exists (select 1 from public.lotes_actualizacion_ranking l where l.tipo = p_alcance || '_admin' and l.iniciado_en > clock_timestamp() - make_interval(mins => private.config_int('recalculo_admin_cooldown_min', 10))) then
    raise exception using errcode = 'P0001', message = 'STATS_RECOMPUTE_COOLDOWN';
  end if;
  insert into public.lotes_actualizacion_ranking (tipo, estado, finalizado_en, detalle) values (p_alcance || '_admin', 'ok', clock_timestamp(), jsonb_build_object('actor', p_actor, 'alcance', p_alcance));
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'estadisticas_recalculo', 'estadisticas_globales_pregunta', null, null, jsonb_build_object('alcance', p_alcance));
  return case p_alcance when 'ranking' then public.app_job_ranking(true) else public.app_job_dificultad(true) end;
end;
$$;
revoke all on function public.admin_recompute_stats(uuid, text) from public, anon, authenticated;
grant execute on function public.admin_recompute_stats(uuid, text) to service_role;


-- ---- 4. reads and the visibility choice (own data only)
create or replace function public.app_ranking_me(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  pf public.perfiles_usuario%rowtype;
  ec public.estado_competitivo_usuario%rowtype;
  lg public.ligas_nivel%rowtype;
  v_min integer := private.config_int('cohorte_minima_comparativa', 50);
  v_cohorte boolean;
  v_prob jsonb;
  v_top jsonb := '[]'::jsonb;
begin
  select * into pf from public.perfiles_usuario x where x.usuario_id = p_usuario;
  if not found then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select * into ec from public.estado_competitivo_usuario x where x.usuario_id = p_usuario;
  select * into lg from public.ligas_nivel x where x.id = ec.liga_nivel_id;
  v_cohorte := ec.ranking_cohorte is not null and ec.ranking_cohorte >= v_min;
  v_prob := case
    when ec.liga_nivel_id is null then jsonb_build_object('disponible', false, 'motivo', 'sin_liga')
    when ec.preguntas_validas_ranking < coalesce(ec.ventana_calculo, 1) then jsonb_build_object('disponible', false, 'motivo', 'ventana_incompleta')
    when ec.probabilidad_plaza is null or coalesce(ec.probabilidad_cohorte, 0) < v_min then jsonb_build_object('disponible', false, 'motivo', 'cohorte_insuficiente')
    else jsonb_build_object('disponible', true, 'porcentaje', ec.probabilidad_plaza, 'tramoCohorte', private.cohorte_tramo(ec.probabilidad_cohorte)) end;
  if v_cohorte and pf.especialidad_principal_id is not null then
    select coalesce(jsonb_agg(jsonb_build_object('posicion', t.posicion, 'handle', t.handle, 'notaMedia', t.nota_media) order by t.posicion), '[]'::jsonb) into v_top
      from public.ranking_top t where t.especialidad_id = pf.especialidad_principal_id and t.liga_nivel_id = ec.liga_nivel_id;
  end if;
  -- absent fields are absent (contract); only `liga` is an explicit null (the user has not reached the first tier)
  return jsonb_strip_nulls(jsonb_build_object(
    'liga', case when lg.id is not null then jsonb_build_object('codigo', lg.codigo, 'nombre', lg.nombre) end,
    'posicion', ec.ranking_liga_posicion,
    'notaMedia', ec.nota_media_actual,
    'cohorte', case when v_cohorte then jsonb_build_object('tramo', private.cohorte_tramo(ec.ranking_cohorte)) end,
    'top3', v_top,
    'probabilidadPlaza', v_prob,
    'visibilidad', jsonb_build_object('publico', pf.ranking_publico),
    'calculadoEn', ec.ranking_calculado_en)) || case when lg.id is null then '{"liga":null}'::jsonb else '{}'::jsonb end;
end;
$$;
revoke all on function public.app_ranking_me(uuid) from public, anon, authenticated;
grant execute on function public.app_ranking_me(uuid) to service_role;

create or replace function public.app_set_ranking_visibility(p_usuario uuid, p_publico boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user text;
begin
  select pf.username into v_user from public.perfiles_usuario pf where pf.usuario_id = p_usuario for update;
  if not found then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if p_publico is null then
    raise exception using errcode = 'P0001', message = 'RANKING_INVALID';
  end if;
  if p_publico and (v_user is null or btrim(v_user) = '') then
    raise exception using errcode = 'P0001', message = 'USERNAME_REQUIRED';
  end if;
  update public.perfiles_usuario set ranking_publico = p_publico where usuario_id = p_usuario and ranking_publico is distinct from p_publico;
  if not p_publico then
    delete from public.ranking_top where usuario_id = p_usuario;  -- leaving is immediate, not at the next job
  end if;
  return jsonb_build_object('publico', p_publico);
end;
$$;
revoke all on function public.app_set_ranking_visibility(uuid, boolean) from public, anon, authenticated;
grant execute on function public.app_set_ranking_visibility(uuid, boolean) to service_role;

commit;
