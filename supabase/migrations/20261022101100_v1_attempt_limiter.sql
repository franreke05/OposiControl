-- V1 DAILY LIMITER L1 (client answers 2026-10-16; E1-V1-ACCESS-V2-DESIGN.md v2.1, E2 conditions C-L1, C-L2, C-L3) and the plan summary D7-31.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100900 and 20261022101000.
--
-- Unit (C-L1): questions REVEALED, counted over a rolling 24 h on the DB clock. A question counts the first time a page of a NON-exempt attempt serves it; serving it again inside the window is free (a resume is
-- not penalised); after the window it counts again. The limit is the plan benefit `limite_preguntas_24h` (bounded 1..config max; absent = unlimited; the Gratuito plan row carries the free value, 30 per the
-- client document). Exempt (C-L3, decided at start and read from intentos_examen.acceso_estado, never re-derived): GRANTED (purchase, gift, quota) and DEMO; an unknown state is NOT exempt (fail closed).
-- Race (C-L2): a per-user advisory lock 'limiter:<user>' taken ONLY when the plan has a limit (never the wallet lock); no write and no COUNT for unlimited plans.
-- public.app_reveal_attempt_questions(user, attempt, questions[]) is called by the page-serving route BEFORE it returns the page: outcome OK, DAILY_LIMIT_REACHED (+ retry_after_seconds = when enough of the
-- window frees), ATTEMPT_NOT_FOUND. A page is all or nothing.
-- public.app_plan_quota(user): the D7-31 payload (plan, quota, chosen exams, limiter meter).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_plan_quota(uuid);
-- drop function if exists public.app_reveal_attempt_questions(uuid, uuid, uuid[]);
-- drop function if exists private.plan_daily_limit(uuid, timestamptz);
-- drop table if exists public.preguntas_reveladas_usuario;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'limiter_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_reveal_attempt_questions(uuid,uuid,uuid[])'), ('public.app_plan_quota(uuid)')) f(n)
-- union all
-- select 'revealed_table_client_access',
--        (case when has_table_privilege('anon', 'public.preguntas_reveladas_usuario', 'select') or has_table_privilege('authenticated', 'public.preguntas_reveladas_usuario', 'select')
--                or has_table_privilege('service_role', 'public.preguntas_reveladas_usuario', 'insert')
--                or not (select c.relrowsecurity from pg_class c where c.oid = 'public.preguntas_reveladas_usuario'::regclass) then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.plan_benefit_int(uuid,text,integer,integer)') is null or to_regprocedure('private.exam_start_verdict(uuid,uuid,text,timestamptz)') is null then
    raise exception using errcode = 'P0001', message = 'V1_LIMITER_PREFLIGHT', hint = 'apply 20261022100900 and 20261022101000 first';
  end if;
end
$$;

insert into public.configuracion_v1 (clave, valor_integer) values ('limite_preguntas_24h_si_invalido', 30) on conflict (clave) do nothing;

create table if not exists public.preguntas_reveladas_usuario (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  revelada_en timestamptz not null,
  primary key (usuario_id, pregunta_id)
);
create index if not exists idx_preguntas_reveladas_usuario_ventana on public.preguntas_reveladas_usuario (usuario_id, revelada_en);
alter table public.preguntas_reveladas_usuario enable row level security;
revoke all on table public.preguntas_reveladas_usuario from public, anon, authenticated, service_role;

-- the daily limit of the user's plan at an instant: NULL = unlimited. Subscribed + resolved -> the plan benefit; otherwise the Gratuito plan (nivel 0) benefit.
create or replace function private.plan_daily_limit(p_usuario uuid, p_now timestamptz)
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  up record;
  v_plan uuid;
  v_lim integer;
begin
  select * into up from private.user_plan(p_usuario, p_now);
  if found and up.subscribed and up.resolved then
    v_plan := up.plan_id;
  else
    select pl.id into v_plan from public.planes_suscripcion pl where pl.nivel = 0 and pl.activo order by pl.posicion, pl.codigo limit 1;
  end if;
  if v_plan is null then
    return null;
  end if;
  v_lim := private.plan_benefit_int(v_plan, 'limite_preguntas_24h', 1, private.config_int('max_limite_preguntas_24h', 500));
  if v_lim is null and exists (select 1 from public.beneficios_plan b where b.plan_id = v_plan and b.codigo_beneficio = 'limite_preguntas_24h') then
    return private.config_int('limite_preguntas_24h_si_invalido', 30);  -- a MALFORMED limit never turns the limiter off (fail closed): the configured fallback applies
  end if;
  return v_lim;
end;
$$;
revoke all on function private.plan_daily_limit(uuid, timestamptz) from public, anon, authenticated;

create or replace function public.app_reveal_attempt_questions(p_usuario uuid, p_intento uuid, p_preguntas uuid[])
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_demo boolean;
  v_acceso text;
  v_now timestamptz;
  v_limit integer;
  v_nuevas uuid[];
  v_actual integer;
  v_exceso integer;
  v_libera timestamptz;
begin
  select ie.es_demo, ie.acceso_estado into v_demo, v_acceso
    from public.intentos_examen ie where ie.id = p_intento and ie.usuario_id = p_usuario and ie.creado_por_comando is not null;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_demo or v_acceso in ('GRANTED', 'DEMO') or coalesce(cardinality(p_preguntas), 0) = 0 then
    return jsonb_build_object('outcome', 'OK');
  end if;
  v_now := clock_timestamp();
  v_limit := private.plan_daily_limit(p_usuario, v_now);
  if v_limit is null then
    return jsonb_build_object('outcome', 'OK');  -- no limit: no lock, no write, no count
  end if;
  perform pg_advisory_xact_lock(hashtextextended('limiter:' || p_usuario::text, 0));
  v_now := clock_timestamp();  -- DB clock after the lock
  select coalesce(array_agg(x.q), '{}') into v_nuevas
    from unnest(p_preguntas) as x(q)
   where not exists (select 1 from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.pregunta_id = x.q and r.revelada_en > v_now - interval '24 hours');
  if cardinality(v_nuevas) = 0 then
    return jsonb_build_object('outcome', 'OK');
  end if;
  select count(*) into v_actual from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.revelada_en > v_now - interval '24 hours';
  if v_actual + cardinality(v_nuevas) > v_limit then
    v_exceso := v_actual + cardinality(v_nuevas) - v_limit;  -- how many of the oldest revealed questions must leave the window
    select r.revelada_en + interval '24 hours' into v_libera
      from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.revelada_en > v_now - interval '24 hours'
     order by r.revelada_en offset least(v_exceso, v_actual) - 1 limit 1;
    return jsonb_build_object('outcome', 'DAILY_LIMIT_REACHED', 'retry_after_seconds',
      greatest(1, ceil(extract(epoch from (coalesce(v_libera, v_now + interval '24 hours') - v_now)))::integer), 'limit', v_limit);
  end if;
  insert into public.preguntas_reveladas_usuario (usuario_id, pregunta_id, revelada_en)
  select p_usuario, q, v_now from unnest(v_nuevas) as q
  on conflict (usuario_id, pregunta_id) do update set revelada_en = excluded.revelada_en;
  return jsonb_build_object('outcome', 'OK');
end;
$$;
revoke all on function public.app_reveal_attempt_questions(uuid, uuid, uuid[]) from public, anon, authenticated;
grant execute on function public.app_reveal_attempt_questions(uuid, uuid, uuid[]) to service_role;

create or replace function public.app_plan_quota(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  up record;
  v_total integer := 0;
  v_used integer := 0;
  v_elegidos jsonb;
  v_limit integer;
  v_active boolean;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select * into up from private.user_plan(p_usuario, v_now);
  v_active := found and up.subscribed and up.resolved;
  if v_active then
    v_total := coalesce(private.plan_benefit_int(up.plan_id, 'inmersivos_incluidos', 0, private.config_int('max_inmersivos_incluidos', 4)), 0);
    v_used := private.plan_quota_used(p_usuario, up.inicio_en, v_now);
    select coalesce(jsonb_agg(jsonb_build_object('examenId', g.examen_id, 'titulo', e.titulo, 'desde', g.valido_desde, 'hasta', g.valido_hasta) order by g.valido_desde, g.id), '[]'::jsonb)
      into v_elegidos
      from public.contenido_desbloqueado_usuario g join public.examenes e on e.id = g.examen_id
     where g.usuario_id = p_usuario and g.motivo = 'plan_cupo' and g.cupo_suscripcion_inicio is not distinct from up.inicio_en and g.valido_desde <= v_now and g.valido_hasta > v_now;
  else
    v_elegidos := '[]'::jsonb;
  end if;
  v_limit := private.plan_daily_limit(p_usuario, v_now);
  return jsonb_build_object(
    'planCodigo', case when v_active then up.plan_codigo end,
    'nivel', case when v_active then up.nivel else 0 end,
    'cupoTotal', v_total, 'cupoUsado', v_used, 'cupoLibre', greatest(v_total - v_used, 0),
    'periodoHasta', case when v_active then up.fin_en end,
    'limitePreguntas24h', v_limit,
    'reveladas24h', (select count(*) from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.revelada_en > v_now - interval '24 hours'),
    'elegidos', v_elegidos);
end;
$$;
revoke all on function public.app_plan_quota(uuid) from public, anon, authenticated;
grant execute on function public.app_plan_quota(uuid) to service_role;

commit;
