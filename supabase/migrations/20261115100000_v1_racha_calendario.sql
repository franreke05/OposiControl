-- V1 STREAK CALENDAR (owner request 2026-11-15 "Manten tu racha": the panel draws the Figma month calendar, calendar{racha activa=mes}, and the API had no day history;
-- workstream F31, contract amendment FA56, D3-26 GET /api/v1/app/racha/calendario). Forward-only, READ ONLY (no table, no write). Requires the V1 daily challenge
-- (20261022101200 registros_reto_diario.dia, 20261022103500 private.reto_streak_gap_free and private.reto_streak_json) and the F28 consumables (20261114400200:
-- registros_reto_diario.protector_usado_id is written by the answer; services and activations from 20261114400000 / 20261114400100).
--
-- New: public.app_racha_calendario(p_usuario uuid, p_mes date default null) -> jsonb, service_role only (pattern app_foro_* / app_ranking_me: Ktor passes the JWT `sub`).
-- The body lives in private.racha_calendario(p_usuario, p_mes, p_now) so the dbtest can prove the Madrid month boundary (DST) with a fixed clock; the public function
-- always passes clock_timestamp() (the DB clock, like the rest of the reto).
--   * Month: p_mes = any day of the month (Ktor sends the first day of ?mes=YYYY-MM); NULL = the current month in the configured zone (private.reto_tz, Europe/Madrid).
--     Allowed from the month of the user's FIRST reto (min registros_reto_diario.dia; no reto yet = only the current month) up to the current month; otherwise
--     P0001 RACHA_MES_INVALID (Ktor: 400 VALIDATION, issue mes OUT_OF_RANGE). Unknown user = WALLET_INVALID_USER (as the other reto reads).
--   * Body: {"mes":"YYYY-MM","hoy":"YYYY-MM-DD","rachaActual":int,"rachaMaxima":int,"rachaMes":int,"dias":[{"fecha":"YYYY-MM-DD","estado":...}]} with one entry per
--     day of the month from the 1st up to today (future days omitted; a past month = every day). rachaActual / rachaMaxima = private.reto_streak_json (the same
--     numbers as D3-04 GET /racha and D3-16).
--   * estado of a day, from the user's registros_reto_diario row of that Madrid day (dia):
--       SUPERADO      completed and passed.
--       PROTEGIDO     completed, failed, and the streak was SAVED by a protector: protector_usado_id is a protector_racha (the answer only takes it when there is a live
--                     streak), or a protector_dual while the streak was alive (the answer uses a dual for the tickets alone when there is no live streak: that day
--                     is FALLADO). "Alive" is recomputed with the answer's own rule: the last streak day before it (passed or saved) is gap-free to it
--                     (private.reto_streak_gap_free with the reto's specialty: yesterday, or only days without an active challenge in between).
--       FALLADO       completed and failed without a streak protector (a tickets-only protector keeps the reward, not the streak), or a PAST day whose reto was
--                     started (progreso > 0) and never finished (its window closed: D3-16 also said FALLADO).
--       PENDIENTE     today, not completed yet (the user has a specialty; without one there is no reto: SIN_ACTIVIDAD).
--       PERDIDO       (review Control 2026-11-15) a PAST day not played (no row, or a row without any final answer) that had an active reto of the user
--                     specialty while the streak was still alive: its last streak day is gap-free to that day (private.reto_streak_gap_free), so not playing it
--                     broke the streak. Only that day is PERDIDO; later unplayed days (streak already gone) are SIN_ACTIVIDAD.
--       SIN_ACTIVIDAD a past day not played with no reto for the user, or with no live streak to lose (also today without a specialty).
--   * rachaMes = the longest run of streak days (SUPERADO or PROTEGIDO) INSIDE the month, continued with the same gap-free rule; days of the previous month never count.
--   Only rows with dia (the V1 daily challenge) are read; legacy rows (dia NULL) and pre-V1 streak counters are not drawn.
-- Index: ux_registros_reto_usuario_dia (usuario_id, dia) where dia is not null (20261022101200). The user only ever sees their OWN rows (p_usuario = JWT sub).
-- DEPLOY ORDER: this migration BEFORE the Ktor image that serves D3-26 (an older image never calls it).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): drops the two functions; nothing else changed.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_racha_calendario(uuid, date);
-- drop function if exists private.racha_calendario(uuid, date, timestamptz);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'racha_calendario_not_service_role_only' as check_name,
--        (case when to_regprocedure('public.app_racha_calendario(uuid,date)') is null
--                or has_function_privilege('anon', 'public.app_racha_calendario(uuid,date)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_racha_calendario(uuid,date)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_racha_calendario(uuid,date)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'racha_calendario_helper_missing_or_client_callable',
--        (case when to_regprocedure('private.racha_calendario(uuid,date,timestamptz)') is null
--                or has_function_privilege('anon', 'private.racha_calendario(uuid,date,timestamptz)', 'execute')
--                or has_function_privilege('authenticated', 'private.racha_calendario(uuid,date,timestamptz)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'racha_calendario_not_security_definer',
--        (case when to_regprocedure('public.app_racha_calendario(uuid,date)') is null or to_regprocedure('private.racha_calendario(uuid,date,timestamptz)') is null
--                or exists (select 1 from pg_proc p where p.oid in (to_regprocedure('public.app_racha_calendario(uuid,date)'), to_regprocedure('private.racha_calendario(uuid,date,timestamptz)'))
--                             and (not p.prosecdef or not coalesce(p.proconfig @> array['search_path=""'], false))) then 1 else 0 end)::bigint
-- union all
-- select 'racha_calendario_streak_rule_not_shared',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.racha_calendario(uuid,date,timestamptz)')) like '%reto_streak_gap_free%'
--                   and (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.racha_calendario(uuid,date,timestamptz)')) like '%reto_streak_json%' then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.reto_today(timestamptz)') is null or to_regprocedure('private.reto_streak_gap_free(date,date,uuid)') is null
     or to_regprocedure('private.reto_streak_json(uuid,date)') is null or to_regclass('public.activaciones_servicio_usuario') is null
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'registros_reto_diario' and c.column_name = 'dia')
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'registros_reto_diario' and c.column_name = 'protector_usado_id')
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'servicios' and c.column_name = 'tipo_servicio') then
    raise exception using errcode = 'P0001', message = 'V1_RACHA_CALENDARIO_PREFLIGHT', hint = 'apply 20261022101200, 20261022103500 and 20261114400000..20261114400200 first';
  end if;
end
$$;

create or replace function private.racha_calendario(p_usuario uuid, p_mes date, p_now timestamptz)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_hoy date := private.reto_today(p_now);
  v_mes_actual date := date_trunc('month', private.reto_today(p_now))::date;
  v_mes date;
  v_fin date;
  v_primero date;
  v_esp uuid;
  v_ultimo date;         -- last streak day (passed or saved) before the row being read, over the whole history up to the month end
  v_prev_mes date;       -- last streak day inside the month
  v_run integer := 0;
  v_mejor integer := 0;
  v_cuenta boolean;
  v_estados jsonb := '{}'::jsonb;
  v_dias_racha date[] := '{}';  -- every streak day (passed or saved) up to the month end, for PERDIDO
  v_racha jsonb;
  v_dias jsonb;
  g record;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select pf.especialidad_principal_id into v_esp from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
  v_mes := coalesce(date_trunc('month', p_mes)::date, v_mes_actual);
  select min(r.dia) into v_primero from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia is not null;
  if v_mes > v_mes_actual or v_mes < date_trunc('month', least(coalesce(v_primero, v_hoy), v_hoy))::date then
    raise exception using errcode = 'P0001', message = 'RACHA_MES_INVALID';
  end if;
  v_fin := least((v_mes + interval '1 month')::date - 1, v_hoy);

  -- completed retos in day order: replays the streak rule of app_answer_daily_challenge to tell a dual that saved the streak from a dual used for the tickets alone
  for g in
    select r.dia, r.superado, s.tipo_servicio as protector, d.especialidad_id
      from public.registros_reto_diario r
      join public.retos_diarios d on d.id = r.reto_id
      left join public.activaciones_servicio_usuario a on a.id = r.protector_usado_id
      left join public.servicios s on s.id = a.servicio_id
     where r.usuario_id = p_usuario and r.dia is not null and r.dia <= v_fin and r.completado
     order by r.dia
  loop
    v_cuenta := g.superado
                or g.protector = 'protector_racha'
                or (g.protector = 'protector_dual' and v_ultimo is not null and private.reto_streak_gap_free(v_ultimo, g.dia, g.especialidad_id));
    if g.dia >= v_mes then
      v_estados := v_estados || jsonb_build_object(g.dia::text, case when g.superado then 'SUPERADO' when v_cuenta then 'PROTEGIDO' else 'FALLADO' end);
      if v_cuenta then
        v_run := case when v_prev_mes is not null and private.reto_streak_gap_free(v_prev_mes, g.dia, g.especialidad_id) then v_run + 1 else 1 end;
        v_prev_mes := g.dia;
        v_mejor := greatest(v_mejor, v_run);
      end if;
    end if;
    if v_cuenta then
      v_ultimo := g.dia;
      v_dias_racha := v_dias_racha || g.dia;
    end if;
  end loop;

  select coalesce(jsonb_agg(jsonb_build_object('fecha', to_char(x.d, 'YYYY-MM-DD'), 'estado',
           coalesce(v_estados ->> x.d::text,
                    case when x.d = v_hoy and v_esp is not null then 'PENDIENTE'
                         when x.d < v_hoy and r.progreso > 0 then 'FALLADO'
                         -- review Control 2026-11-15: a past day not played (no final answer) that had an active reto of the user specialty while the streak was
                         -- still alive (its last streak day is gap-free to that day, the answer's own rule) is the day the streak was LOST by not playing
                         when x.d < v_hoy and v_esp is not null and coalesce(r.progreso, 0) = 0
                              and exists (select 1 from public.retos_diarios rd where rd.fecha = x.d and rd.especialidad_id = v_esp and rd.activo)
                              and private.reto_streak_gap_free((select max(s) from unnest(v_dias_racha) s where s < x.d), x.d, v_esp) then 'PERDIDO'
                         else 'SIN_ACTIVIDAD' end)) order by x.d), '[]'::jsonb)
    into v_dias
    from generate_series(v_mes::timestamp, v_fin::timestamp, interval '1 day') as x0(t)  -- timestamp WITHOUT zone: calendar days, never a DST-shifted hour
    cross join lateral (select x0.t::date as d) x
    left join public.registros_reto_diario r on r.usuario_id = p_usuario and r.dia = x.d;

  v_racha := private.reto_streak_json(p_usuario, v_hoy);
  return jsonb_build_object(
    'mes', to_char(v_mes, 'YYYY-MM'),
    'hoy', to_char(v_hoy, 'YYYY-MM-DD'),
    'rachaActual', coalesce((v_racha ->> 'actual')::integer, 0),
    'rachaMaxima', coalesce((v_racha ->> 'maxima')::integer, 0),
    'rachaMes', v_mejor,
    'dias', v_dias);
end;
$$;
revoke all on function private.racha_calendario(uuid, date, timestamptz) from public, anon, authenticated;

create or replace function public.app_racha_calendario(p_usuario uuid, p_mes date default null)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$ select private.racha_calendario(p_usuario, p_mes, clock_timestamp()) $$;
revoke all on function public.app_racha_calendario(uuid, date) from public, anon, authenticated;
grant execute on function public.app_racha_calendario(uuid, date) to service_role;

notify pgrst, 'reload schema';

commit;
