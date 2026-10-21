-- =============================================================================
-- V1 -- daily challenge: DST-safe window, one streak rule for read and write, segment-aware legacy read (CERT-STREAKS-QUESTIONS-2026-10-21).
-- Corrective; 20261022101200 is NOT edited.
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only. Requires 20261022101200 (daily challenge) and 20261022101500 (config bounds).
--
-- WHAT IT DOES
--   * P1 DST: ensure_challenge_row wrote termina_en = empieza_en + 24 h. On the October change (2026-10-25 in Europe/Madrid, a 25 h day) the window
--     closed at 23:00 local: answers between 23:00 and 24:00 got RETO_OUT_OF_WINDOW, GET read FALLADO and the streak was lost; on the March change
--     (2027-03-28, a 23 h day) the window ran 1 h into the next day. The window now ends at the next LOCAL midnight of the configured zone
--     (private.reto_day_end), which is also where private.reto_today switches day (the answer gate keys the user's row by that day).
--     Rows of today and later that still carry the old end are corrected (empieza_en is never touched).
--   * Streak rule: the write side keeps its rule (a day WITHOUT any active challenge for the specialty never breaks the streak; a real missed day
--     resets it to 1) and now reads it from ONE helper, private.reto_streak_gap_free; the read side (reto_streak_json, used by GET /retos/hoy,
--     GET /racha, the answer payload and the stats summary) uses the same helper, so GET no longer shows 0 for a streak the next pass continues.
--     Owner decision pending (CERT-STREAKS-QUESTIONS-2026-10-21, decision 5): whether a no-challenge day should keep the streak at all.
--   * Legacy GET /api/v1/app/reto-diario (D2-05, superseded by D3-16) and /home.retoDiario read retos_diarios with the JVM date and no segment.
--     public.app_legacy_daily_challenge(p_usuario) is a READ-ONLY lookup of the user's row of today in the configured zone: the frozen row if the
--     user opened the challenge today, otherwise the most specific eligible row (same order as private.user_challenge_reto). It creates nothing.
--   * P0 load (CERT-LOAD-1000-2026-10-21): every user's first open of the day took ONE global advisory lock ('reto-job') in user_challenge_reto, so
--     a spike of first opens serialized (0 of 1000 completed). The first open now takes NO global lock: an existing row is a lock-free read; a
--     missing row is created with INSERT ... ON CONFLICT DO NOTHING on the unique index ux_retos_diarios_segmento (fecha, especialidad, comunidad),
--     so concurrent first opens of the same segment wait only for that one row and then read the winner's row and questions (exactly one row per
--     day and segment, the same questions for everyone). Segments are created in a fixed order (national, then communities by id) by both the
--     first open and the job, so concurrent inserters cannot deadlock. The job keeps its own leader lock (one generator at a time).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the 20261022101200 bodies come back verbatim; rows corrected by this migration (or created
-- after it) get the old end back (empieza_en + 24 h); the new functions are dropped.
-- ROLLBACK-BEGIN
-- begin;
-- update public.retos_diarios d set termina_en = d.empieza_en + interval '24 hours'
--  where d.termina_en = private.reto_day_end(d.fecha) and d.termina_en <> d.empieza_en + interval '24 hours';
-- create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
-- returns boolean
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_reto uuid;
--   v_inm uuid;
--   v_raz uuid;
--   v_raf uuid;
--   v_inicio timestamptz;
--   v_hay_regional boolean;
-- begin
--   if exists (select 1 from public.retos_diarios r where r.fecha = p_fecha and r.especialidad_id = p_esp and r.comunidad_id is not distinct from p_com) then
--     return false;
--   end if;
--   -- a regional row only exists when some regional-tagged question can enter it (otherwise it would equal the national one)
--   if p_com is not null then
--     select exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
--                     where ec.comunidad_id = p_com
--                       and ep.pregunta_id in (select private.reto_pool(p_esp, p_com, 'rafaga') union select private.reto_pool(p_esp, p_com, 'razonada') union select private.reto_pool(p_esp, p_com, 'inmersiva')))
--       into v_hay_regional;
--     if not v_hay_regional then
--       return false;
--     end if;
--   end if;
--   select q into v_inm from private.reto_pool(p_esp, p_com, 'inmersiva') q order by random() limit 1;
--   select q into v_raz from private.reto_pool(p_esp, p_com, 'razonada') q order by random() limit 1;
--   select q into v_raf from private.reto_pool(p_esp, p_com, 'rafaga') q order by random() limit 1;
--   if v_inm is null or v_raz is null or v_raf is null then
--     return false;  -- a slot without pool: no challenge for this segment today (never relax the regional rule)
--   end if;
--   v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
--   insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
--   values (p_fecha, 'Reto diario', null, 3, 2, 0, true, v_inicio, v_inicio + interval '24 hours', p_esp, p_com, 'todos')
--   on conflict do nothing
--   returning id into v_reto;
--   if v_reto is null then
--     return false;
--   end if;
--   insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot) values
--     (v_reto, v_inm, 1, 'inmersiva_demo'), (v_reto, v_raz, 2, 'razonada'), (v_reto, v_raf, 3, 'rafaga');
--   return true;
-- end;
-- $$;
-- revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;
-- create or replace function public.app_generate_daily_challenges(p_fecha date default null)
-- returns integer
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_fecha date;
--   v_n integer := 0;
--   s record;
--   c record;
-- begin
--   perform pg_advisory_xact_lock(hashtextextended('reto-job', 0));  -- one generator at a time (single leader)
--   v_fecha := coalesce(p_fecha, private.reto_today(clock_timestamp()));
--   for s in select e.id from public.especialidades e where e.activa loop
--     if private.ensure_challenge_row(v_fecha, s.id, null) then v_n := v_n + 1; end if;
--     for c in select distinct ec.comunidad_id from public.etiquetas_comunidad ec loop
--       if private.ensure_challenge_row(v_fecha, s.id, c.comunidad_id) then v_n := v_n + 1; end if;
--     end loop;
--   end loop;
--   return v_n;
-- end;
-- $$;
-- revoke all on function public.app_generate_daily_challenges(date) from public, anon, authenticated;
-- grant execute on function public.app_generate_daily_challenges(date) to service_role;
-- create or replace function private.user_challenge_reto(p_usuario uuid, p_now timestamptz)
-- returns uuid
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_today date := private.reto_today(p_now);
--   v_reto uuid;
--   v_esp uuid;
--   v_principal uuid;
--   c record;
-- begin
--   select r.reto_id into v_reto from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia = v_today;
--   if found then
--     return v_reto;
--   end if;
--   select pf.especialidad_principal_id, pf.comunidad_principal_id into v_esp, v_principal from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
--   if v_esp is null then
--     return null;
--   end if;
--   -- lazy creation of the segments this user can see (the job normally did it already)
--   perform pg_advisory_xact_lock(hashtextextended('reto-job', 0));
--   perform private.ensure_challenge_row(v_today, v_esp, null);
--   for c in select x.comunidad_id from (select v_principal as comunidad_id union select o.comunidad_id from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario) x where x.comunidad_id is not null loop
--     perform private.ensure_challenge_row(v_today, v_esp, c.comunidad_id);
--   end loop;
--   -- most specific eligible row: the principal community first, then the target communities by priority, then the national row
--   select r.id into v_reto
--     from public.retos_diarios r
--     left join public.comunidades_objetivo_usuario o on o.usuario_id = p_usuario and o.comunidad_id = r.comunidad_id
--    where r.fecha = v_today and r.activo and r.especialidad_id = v_esp
--      and (r.comunidad_id is null or r.comunidad_id = v_principal or o.comunidad_id is not null)
--    order by (r.comunidad_id = v_principal) desc nulls last, (r.comunidad_id is not null) desc, o.prioridad nulls last, r.id
--    limit 1;
--   if v_reto is null then
--     return null;
--   end if;
--   insert into public.registros_reto_diario (usuario_id, reto_id, dia) values (p_usuario, v_reto, v_today) on conflict do nothing;
--   select r.reto_id into v_reto from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia = v_today;
--   return v_reto;
-- end;
-- $$;
-- revoke all on function private.user_challenge_reto(uuid, timestamptz) from public, anon, authenticated;
-- create or replace function private.reto_streak_json(p_usuario uuid, p_today date)
-- returns jsonb
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select jsonb_build_object(
--     'actual', case when r.ultima_actividad_fecha is not null and r.ultima_actividad_fecha >= p_today - 1 then r.racha_actual_dias else 0 end,
--     'maxima', coalesce(r.mejor_racha_dias, 0),
--     'ultimoDiaSuperado', r.ultima_actividad_fecha)
--   from (select 1) x left join public.rachas_usuario r on r.usuario_id = p_usuario
-- $$;
-- revoke all on function private.reto_streak_json(uuid, date) from public, anon, authenticated;
-- create or replace function public.app_answer_daily_challenge(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_opcion uuid)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_now timestamptz;
--   v_today date;
--   r public.retos_diarios%rowtype;
--   g public.registros_reto_diario%rowtype;
--   v_prev public.respuestas_reto_diario%rowtype;
--   v_content jsonb;
--   v_correcta uuid;
--   v_ok boolean;
--   v_progreso integer;
--   v_superado boolean;
--   v_reward jsonb;
--   st public.rachas_usuario%rowtype;
--   v_esp uuid;
--   v_plan uuid;
--   v_base integer := 0;
--   v_bonus integer := 0;
--   v_tipo text;
--   v_paid boolean;
--   v_desde integer;
--   v_max integer;
--   v_hay_previa boolean;
-- begin
--   if p_usuario is null or p_reto is null or p_pregunta is null or p_opcion is null then
--     raise exception using errcode = 'P0001', message = 'NOT_FOUND';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('reto:' || p_usuario::text, 0));
--   v_now := clock_timestamp();
--   v_today := private.reto_today(v_now);
--   -- the reto must be the row stored for THIS user today (no oracle for other rows / segments)
--   select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.dia = v_today;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'NOT_FOUND';
--   end if;
--   select * into r from public.retos_diarios x where x.id = p_reto;
--   if not exists (select 1 from public.preguntas_reto_diario pr where pr.reto_id = p_reto and pr.pregunta_id = p_pregunta) then
--     raise exception using errcode = 'P0001', message = 'NOT_FOUND';
--   end if;
--   select * into v_prev from public.respuestas_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
--   v_hay_previa := found;
--   v_content := private.app_revision_pregunta_contenido(p_pregunta);
--   v_correcta := (private.app_revision_pregunta_solucion(p_pregunta) ->> 'opcion_correcta_id')::uuid;
--   if v_hay_previa then
--     if v_prev.opcion_id <> p_opcion then
--       raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';
--     end if;
--     -- idempotent retry: the stored result, nothing changes
--     return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_prev.es_correcta, 'opcionCorrectaId', v_correcta,
--       'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
--       'aciertos', g.aciertos, 'respondidas', g.progreso,
--       'recompensa', case when g.completado and g.superado then jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', g.recompensa_entregada) end,
--       'racha', private.reto_streak_json(p_usuario, v_today)));
--   end if;
--   if v_now < r.empieza_en or v_now >= r.termina_en or g.completado then
--     raise exception using errcode = 'P0001', message = 'RETO_OUT_OF_WINDOW';
--   end if;
--   if not exists (select 1 from jsonb_array_elements(v_content -> 'opciones') o where o ->> 'id' = p_opcion::text) then
--     raise exception using errcode = 'P0001', message = 'OPTION_NOT_IN_QUESTION';
--   end if;
--   v_ok := (p_opcion = v_correcta);
--   insert into public.respuestas_reto_diario (usuario_id, reto_id, pregunta_id, opcion_id, es_correcta, respondida_en) values (p_usuario, p_reto, p_pregunta, p_opcion, v_ok, v_now);
--   v_progreso := g.progreso + 1;
--   update public.registros_reto_diario set progreso = v_progreso, aciertos = g.aciertos + (case when v_ok then 1 else 0 end), fallos = g.fallos + (case when v_ok then 0 else 1 end)
--    where usuario_id = p_usuario and reto_id = p_reto
--   returning * into g;
--   if v_progreso >= r.objetivo_preguntas then
--     v_superado := g.aciertos >= r.aciertos_necesarios;
--     -- tier of the user for the bar and the reward: a PAID plan (activa / cancelada with its period) earns its plan amount; trial and free users earn the Gratuito amount
--     v_paid := exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada') and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now));
--     v_tipo := case when v_paid then 'suscritos' else 'gratis' end;
--     if v_superado then
--       -- streak: single writer
--       insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values (p_usuario, 0, 0, null) on conflict do nothing;
--       select * into st from public.rachas_usuario x where x.usuario_id = p_usuario for update;
--       if st.ultima_actividad_fecha = v_today then
--         null;  -- already counted today
--       elsif st.ultima_actividad_fecha = v_today - 1
--             or (st.ultima_actividad_fecha is not null and st.ultima_actividad_fecha < v_today - 1
--                 and not exists (select 1 from public.retos_diarios d where d.especialidad_id = r.especialidad_id and d.fecha > st.ultima_actividad_fecha and d.fecha < v_today and d.activo)) then
--         st.racha_actual_dias := st.racha_actual_dias + 1;  -- consecutive, or only days WITHOUT an available challenge in between (they never break the streak)
--         st.ultima_actividad_fecha := v_today;
--       else
--         st.racha_actual_dias := 1;
--         st.ultima_actividad_fecha := v_today;
--       end if;
--       update public.rachas_usuario set racha_actual_dias = st.racha_actual_dias, mejor_racha_dias = greatest(st.mejor_racha_dias, st.racha_actual_dias),
--              ultima_actividad_fecha = st.ultima_actividad_fecha, actualizado_en = now() where usuario_id = p_usuario;
--       -- reward amounts, frozen now
--       select pf.especialidad_principal_id into v_esp from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
--       if v_paid then
--         select up.plan_id into v_plan from private.user_plan(p_usuario, v_now) up;
--       end if;
--       if v_plan is null then
--         select pl.id into v_plan from public.planes_suscripcion pl where pl.nivel = 0 and pl.activo order by pl.posicion, pl.codigo limit 1;
--       end if;
--       v_base := coalesce(private.plan_benefit_int(v_plan, 'ot_reto_diario', 0, private.config_int('max_ot_reto_diario', 200)), 0);
--       v_desde := private.config_int('bonus_racha_desde_dia', 3);
--       v_max := private.config_int('bonus_racha_max_dias', 7);
--       if st.racha_actual_dias >= v_desde and st.racha_actual_dias < v_desde + v_max then
--         v_bonus := coalesce(private.plan_benefit_int(v_plan, 'ot_bonus_racha', 0, private.config_int('max_ot_bonus_racha', 100)), 0);
--       end if;
--     end if;
--     update public.registros_reto_diario set completado = true, completado_en = v_now, superado = v_superado, recompensa_base_tickets = v_base, bonus_racha_tickets = v_bonus,
--            recompensa_total_tickets = v_base + v_bonus where usuario_id = p_usuario and reto_id = p_reto returning * into g;
--     insert into public.reto_contadores (reto_id, tipo_usuario, jugadores, superados) values (p_reto, v_tipo, 1, case when v_superado then 1 else 0 end)
--     on conflict (reto_id, tipo_usuario) do update set jugadores = public.reto_contadores.jugadores + 1, superados = public.reto_contadores.superados + (case when v_superado then 1 else 0 end);
--     if v_superado then
--       v_reward := jsonb_build_object('base', v_base, 'bonusRacha', v_bonus, 'total', v_base + v_bonus, 'reclamada', false);
--     end if;
--   end if;
--   return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_ok, 'opcionCorrectaId', v_correcta,
--     'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
--     'aciertos', g.aciertos, 'respondidas', g.progreso, 'recompensa', v_reward, 'racha', private.reto_streak_json(p_usuario, v_today)));
-- end;
-- $$;
-- revoke all on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid) from public, anon, authenticated;
-- grant execute on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid) to service_role;
-- drop function if exists public.app_legacy_daily_challenge(uuid);
-- drop function if exists private.reto_streak_gap_free(date, date, uuid);
-- drop function if exists private.reto_day_end(date);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'function_not_service_role_only:public.app_legacy_daily_challenge(uuid)' as check_name,
--        (case when has_function_privilege('anon', 'public.app_legacy_daily_challenge(uuid)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_legacy_daily_challenge(uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_legacy_daily_challenge(uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'private_helper_client_executable:' || f.n,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('private.reto_day_end(date)'), ('private.reto_streak_gap_free(date,date,uuid)')) f(n)
-- union all
-- select 'window_still_inicio_plus_24h', (case when (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) like '%reto_day_end(p_fecha)%'
--                                              and (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) not like '%interval ''24 hours''%' then 0 else 1 end)::bigint
-- union all
-- select 'streak_rule_not_shared', (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)'::regprocedure) like '%reto_streak_gap_free%'
--                                         and (select p.prosrc from pg_proc p where p.oid = 'private.reto_streak_json(uuid,date)'::regprocedure) like '%reto_streak_gap_free%' then 0 else 1 end)::bigint
-- union all
-- select 'first_open_takes_a_global_lock', (case when (select p.prosrc from pg_proc p where p.oid = 'private.user_challenge_reto(uuid,timestamptz)'::regprocedure) not like '%pg_advisory%' then 0 else 1 end)::bigint
-- union all
-- select 'day_end_not_next_local_midnight:' || d::text,
--        (case when private.reto_today(private.reto_day_end(d) - interval '1 microsecond') = d and private.reto_today(private.reto_day_end(d)) = d + 1 then 0 else 1 end)::bigint
--   from unnest(array['2026-10-25'::date, '2027-03-28'::date, '2026-10-21'::date]) d
-- union all
-- select 'open_rows_with_a_24h_window', count(*)::bigint from public.retos_diarios d
--  where d.fecha >= private.reto_today(clock_timestamp()) and d.termina_en = d.empieza_en + interval '24 hours' and d.termina_en <> private.reto_day_end(d.fecha);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('private.ensure_challenge_row(date,uuid,uuid)') is null or to_regprocedure('private.reto_streak_json(uuid,date)') is null
     or to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)') is null or to_regprocedure('private.reto_today(timestamptz)') is null
     or to_regprocedure('private.reto_tz()') is null or to_regprocedure('private.user_challenge_reto(uuid,timestamptz)') is null then
    raise exception using errcode = 'P0001', message = 'V1_DAILY_CHALLENGE_DST_PREFLIGHT', hint = 'apply 20261022101200 first';
  end if;
end
$$;

-- ---- 1. day boundary: the next local midnight of the configured zone (where private.reto_today switches day)
create or replace function private.reto_day_end(p_fecha date)
returns timestamptz
language sql
stable
security definer
set search_path = ''
as $$ select ((p_fecha + 1)::timestamp at time zone private.reto_tz()) $$;
revoke all on function private.reto_day_end(date) from public, anon, authenticated;

-- ---- 2. the ONE streak-continuation rule (write: app_answer_daily_challenge; read: reto_streak_json)
-- true when the streak whose last passed day is p_last continues on p_today: p_last is yesterday, or every day strictly between had NO active
-- challenge for the specialty (owner decision 5 pending: such days never break the streak). A real missed day (a challenge existed) = false.
create or replace function private.reto_streak_gap_free(p_last date, p_today date, p_esp uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select p_last is not null and p_last < p_today
     and (p_last = p_today - 1
          or (p_esp is not null
              and not exists (select 1 from public.retos_diarios d where d.especialidad_id = p_esp and d.fecha > p_last and d.fecha < p_today and d.activo)))
$$;
revoke all on function private.reto_streak_gap_free(date, date, uuid) from public, anon, authenticated;

-- ---- 3. window of new rows
create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_reto uuid;
  v_inm uuid;
  v_raz uuid;
  v_raf uuid;
  v_inicio timestamptz;
  v_hay_regional boolean;
begin
  if exists (select 1 from public.retos_diarios r where r.fecha = p_fecha and r.especialidad_id = p_esp and r.comunidad_id is not distinct from p_com) then
    return false;
  end if;
  -- a regional row only exists when some regional-tagged question can enter it (otherwise it would equal the national one)
  if p_com is not null then
    select exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
                    where ec.comunidad_id = p_com
                      and ep.pregunta_id in (select private.reto_pool(p_esp, p_com, 'rafaga') union select private.reto_pool(p_esp, p_com, 'razonada') union select private.reto_pool(p_esp, p_com, 'inmersiva')))
      into v_hay_regional;
    if not v_hay_regional then
      return false;
    end if;
  end if;
  select q into v_inm from private.reto_pool(p_esp, p_com, 'inmersiva') q order by random() limit 1;
  select q into v_raz from private.reto_pool(p_esp, p_com, 'razonada') q order by random() limit 1;
  select q into v_raf from private.reto_pool(p_esp, p_com, 'rafaga') q order by random() limit 1;
  if v_inm is null or v_raz is null or v_raf is null then
    return false;  -- a slot without pool: no challenge for this segment today (never relax the regional rule)
  end if;
  -- the window ends at the NEXT LOCAL MIDNIGHT of the configured zone (25 h on the October DST day, 23 h in March), never inicio + 24 h
  v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
  insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
  values (p_fecha, 'Reto diario', null, 3, 2, 0, true, v_inicio, private.reto_day_end(p_fecha), p_esp, p_com, 'todos')
  on conflict do nothing
  returning id into v_reto;
  if v_reto is null then
    return false;
  end if;
  insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot) values
    (v_reto, v_inm, 1, 'inmersiva_demo'), (v_reto, v_raz, 2, 'razonada'), (v_reto, v_raf, 3, 'rafaga');
  return true;
end;
$$;
revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;

-- rows of today and later created with the old 24 h end (empieza_en unchanged; earlier days are history and are not rewritten)
update public.retos_diarios d set termina_en = private.reto_day_end(d.fecha)
 where d.fecha >= private.reto_today(clock_timestamp()) and d.termina_en = d.empieza_en + interval '24 hours' and d.termina_en <> private.reto_day_end(d.fecha);

-- ---- 3b. first open WITHOUT the global lock (CERT-LOAD-1000 P0); the job keeps its own leader lock and uses the same segment order
create or replace function public.app_generate_daily_challenges(p_fecha date default null)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_fecha date;
  v_n integer := 0;
  s record;
  c record;
begin
  perform pg_advisory_xact_lock(hashtextextended('reto-job', 0));  -- one generator at a time (single leader)
  v_fecha := coalesce(p_fecha, private.reto_today(clock_timestamp()));
  for s in select e.id from public.especialidades e where e.activa order by e.id loop
    if private.ensure_challenge_row(v_fecha, s.id, null) then v_n := v_n + 1; end if;
    for c in select distinct ec.comunidad_id from public.etiquetas_comunidad ec order by ec.comunidad_id loop  -- same segment order as a first open: no lock-order deadlock
      if private.ensure_challenge_row(v_fecha, s.id, c.comunidad_id) then v_n := v_n + 1; end if;
    end loop;
  end loop;
  return v_n;
end;
$$;
revoke all on function public.app_generate_daily_challenges(date) from public, anon, authenticated;
grant execute on function public.app_generate_daily_challenges(date) to service_role;

create or replace function private.user_challenge_reto(p_usuario uuid, p_now timestamptz)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_today date := private.reto_today(p_now);
  v_reto uuid;
  v_esp uuid;
  v_principal uuid;
  c record;
begin
  select r.reto_id into v_reto from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia = v_today;
  if found then
    return v_reto;
  end if;
  select pf.especialidad_principal_id, pf.comunidad_principal_id into v_esp, v_principal from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
  if v_esp is null then
    return null;
  end if;
  -- lazy creation of the segments this user can see (the job normally did it already). NO global lock (CERT-LOAD-1000 P0): an existing row is a
  -- lock-free read inside ensure_challenge_row; a missing row is inserted with ON CONFLICT DO NOTHING on ux_retos_diarios_segmento, so concurrent first
  -- opens of the SAME segment wait only for that row (the loser inserts nothing and re-reads the winner's row and questions); other segments never wait.
  -- Segment order is fixed (national, then communities by id; the job uses the same order), so two inserters can never deadlock.
  perform private.ensure_challenge_row(v_today, v_esp, null);
  for c in select x.comunidad_id from (select v_principal as comunidad_id union select o.comunidad_id from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario) x where x.comunidad_id is not null order by x.comunidad_id loop
    perform private.ensure_challenge_row(v_today, v_esp, c.comunidad_id);
  end loop;
  -- most specific eligible row: the principal community first, then the target communities by priority, then the national row
  select r.id into v_reto
    from public.retos_diarios r
    left join public.comunidades_objetivo_usuario o on o.usuario_id = p_usuario and o.comunidad_id = r.comunidad_id
   where r.fecha = v_today and r.activo and r.especialidad_id = v_esp
     and (r.comunidad_id is null or r.comunidad_id = v_principal or o.comunidad_id is not null)
   order by (r.comunidad_id = v_principal) desc nulls last, (r.comunidad_id is not null) desc, o.prioridad nulls last, r.id
   limit 1;
  if v_reto is null then
    return null;
  end if;
  insert into public.registros_reto_diario (usuario_id, reto_id, dia) values (p_usuario, v_reto, v_today) on conflict do nothing;
  select r.reto_id into v_reto from public.registros_reto_diario r where r.usuario_id = p_usuario and r.dia = v_today;
  return v_reto;
end;
$$;
revoke all on function private.user_challenge_reto(uuid, timestamptz) from public, anon, authenticated;

-- ---- 4. streak read = the write rule
create or replace function private.reto_streak_json(p_usuario uuid, p_today date)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  -- the read follows the write rule (private.reto_streak_gap_free): a streak is still alive when its last passed day is today, yesterday, or
  -- every day in between had NO active challenge for the user's specialty (those days never break the streak)
  select jsonb_build_object(
    'actual', case when r.ultima_actividad_fecha is not null
                    and (r.ultima_actividad_fecha >= p_today or private.reto_streak_gap_free(r.ultima_actividad_fecha, p_today, pf.especialidad_principal_id))
                   then r.racha_actual_dias else 0 end,
    'maxima', coalesce(r.mejor_racha_dias, 0),
    'ultimoDiaSuperado', r.ultima_actividad_fecha)
  from (select 1) x
  left join public.rachas_usuario r on r.usuario_id = p_usuario
  left join public.perfiles_usuario pf on pf.usuario_id = p_usuario
$$;
revoke all on function private.reto_streak_json(uuid, date) from public, anon, authenticated;

-- ---- 5. streak write: same body, the continuation condition reads the shared helper
create or replace function public.app_answer_daily_challenge(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_opcion uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_today date;
  r public.retos_diarios%rowtype;
  g public.registros_reto_diario%rowtype;
  v_prev public.respuestas_reto_diario%rowtype;
  v_content jsonb;
  v_correcta uuid;
  v_ok boolean;
  v_progreso integer;
  v_superado boolean;
  v_reward jsonb;
  st public.rachas_usuario%rowtype;
  v_esp uuid;
  v_plan uuid;
  v_base integer := 0;
  v_bonus integer := 0;
  v_tipo text;
  v_paid boolean;
  v_desde integer;
  v_max integer;
  v_hay_previa boolean;
begin
  if p_usuario is null or p_reto is null or p_pregunta is null or p_opcion is null then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('reto:' || p_usuario::text, 0));
  v_now := clock_timestamp();
  v_today := private.reto_today(v_now);
  -- the reto must be the row stored for THIS user today (no oracle for other rows / segments)
  select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.dia = v_today;
  if not found then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  select * into r from public.retos_diarios x where x.id = p_reto;
  if not exists (select 1 from public.preguntas_reto_diario pr where pr.reto_id = p_reto and pr.pregunta_id = p_pregunta) then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  select * into v_prev from public.respuestas_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
  v_hay_previa := found;
  v_content := private.app_revision_pregunta_contenido(p_pregunta);
  v_correcta := (private.app_revision_pregunta_solucion(p_pregunta) ->> 'opcion_correcta_id')::uuid;
  if v_hay_previa then
    if v_prev.opcion_id <> p_opcion then
      raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';
    end if;
    -- idempotent retry: the stored result, nothing changes
    return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_prev.es_correcta, 'opcionCorrectaId', v_correcta,
      'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
      'aciertos', g.aciertos, 'respondidas', g.progreso,
      'recompensa', case when g.completado and g.superado then jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', g.recompensa_entregada) end,
      'racha', private.reto_streak_json(p_usuario, v_today)));
  end if;
  if v_now < r.empieza_en or v_now >= r.termina_en or g.completado then
    raise exception using errcode = 'P0001', message = 'RETO_OUT_OF_WINDOW';
  end if;
  if not exists (select 1 from jsonb_array_elements(v_content -> 'opciones') o where o ->> 'id' = p_opcion::text) then
    raise exception using errcode = 'P0001', message = 'OPTION_NOT_IN_QUESTION';
  end if;
  v_ok := (p_opcion = v_correcta);
  insert into public.respuestas_reto_diario (usuario_id, reto_id, pregunta_id, opcion_id, es_correcta, respondida_en) values (p_usuario, p_reto, p_pregunta, p_opcion, v_ok, v_now);
  v_progreso := g.progreso + 1;
  update public.registros_reto_diario set progreso = v_progreso, aciertos = g.aciertos + (case when v_ok then 1 else 0 end), fallos = g.fallos + (case when v_ok then 0 else 1 end)
   where usuario_id = p_usuario and reto_id = p_reto
  returning * into g;
  if v_progreso >= r.objetivo_preguntas then
    v_superado := g.aciertos >= r.aciertos_necesarios;
    -- tier of the user for the bar and the reward: a PAID plan (activa / cancelada with its period) earns its plan amount; trial and free users earn the Gratuito amount
    v_paid := exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada') and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now));
    v_tipo := case when v_paid then 'suscritos' else 'gratis' end;
    if v_superado then
      -- streak: single writer
      insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values (p_usuario, 0, 0, null) on conflict do nothing;
      select * into st from public.rachas_usuario x where x.usuario_id = p_usuario for update;
      if st.ultima_actividad_fecha = v_today then
        null;  -- already counted today
      elsif private.reto_streak_gap_free(st.ultima_actividad_fecha, v_today, r.especialidad_id) then
        st.racha_actual_dias := st.racha_actual_dias + 1;  -- consecutive, or only days WITHOUT an available challenge in between (they never break the streak)
        st.ultima_actividad_fecha := v_today;
      else
        st.racha_actual_dias := 1;
        st.ultima_actividad_fecha := v_today;
      end if;
      update public.rachas_usuario set racha_actual_dias = st.racha_actual_dias, mejor_racha_dias = greatest(st.mejor_racha_dias, st.racha_actual_dias),
             ultima_actividad_fecha = st.ultima_actividad_fecha, actualizado_en = now() where usuario_id = p_usuario;
      -- reward amounts, frozen now
      select pf.especialidad_principal_id into v_esp from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
      if v_paid then
        select up.plan_id into v_plan from private.user_plan(p_usuario, v_now) up;
      end if;
      if v_plan is null then
        select pl.id into v_plan from public.planes_suscripcion pl where pl.nivel = 0 and pl.activo order by pl.posicion, pl.codigo limit 1;
      end if;
      v_base := coalesce(private.plan_benefit_int(v_plan, 'ot_reto_diario', 0, private.config_int('max_ot_reto_diario', 200)), 0);
      v_desde := private.config_int('bonus_racha_desde_dia', 3);
      v_max := private.config_int('bonus_racha_max_dias', 7);
      if st.racha_actual_dias >= v_desde and st.racha_actual_dias < v_desde + v_max then
        v_bonus := coalesce(private.plan_benefit_int(v_plan, 'ot_bonus_racha', 0, private.config_int('max_ot_bonus_racha', 100)), 0);
      end if;
    end if;
    update public.registros_reto_diario set completado = true, completado_en = v_now, superado = v_superado, recompensa_base_tickets = v_base, bonus_racha_tickets = v_bonus,
           recompensa_total_tickets = v_base + v_bonus where usuario_id = p_usuario and reto_id = p_reto returning * into g;
    insert into public.reto_contadores (reto_id, tipo_usuario, jugadores, superados) values (p_reto, v_tipo, 1, case when v_superado then 1 else 0 end)
    on conflict (reto_id, tipo_usuario) do update set jugadores = public.reto_contadores.jugadores + 1, superados = public.reto_contadores.superados + (case when v_superado then 1 else 0 end);
    if v_superado then
      v_reward := jsonb_build_object('base', v_base, 'bonusRacha', v_bonus, 'total', v_base + v_bonus, 'reclamada', false);
    end if;
  end if;
  return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_ok, 'opcionCorrectaId', v_correcta,
    'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
    'aciertos', g.aciertos, 'respondidas', g.progreso, 'recompensa', v_reward, 'racha', private.reto_streak_json(p_usuario, v_today)));
end;
$$;
revoke all on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid) to service_role;

-- ---- 6. legacy read (GET /api/v1/app/reto-diario, /home.retoDiario): the user's row of today, read only
create or replace function public.app_legacy_daily_challenge(p_usuario uuid)
returns table (id uuid, fecha date, titulo text, descripcion text, objetivo_preguntas integer, recompensa_puntos integer)
language sql
stable
security definer
set search_path = ''
as $$
  with dia as (select private.reto_today(clock_timestamp()) as hoy),
  frozen as (
    select rg.reto_id from public.registros_reto_diario rg, dia where rg.usuario_id = p_usuario and rg.dia = dia.hoy
  ),
  perfil as (
    select pf.especialidad_principal_id as esp, pf.comunidad_principal_id as principal from public.perfiles_usuario pf where pf.usuario_id = p_usuario
  ),
  eligible as (  -- same order as private.user_challenge_reto, without creating anything
    select r.id
      from public.retos_diarios r
      cross join dia
      cross join perfil
      left join public.comunidades_objetivo_usuario o on o.usuario_id = p_usuario and o.comunidad_id = r.comunidad_id
     where r.fecha = dia.hoy and r.activo and r.especialidad_id = perfil.esp
       and (r.comunidad_id is null or r.comunidad_id = perfil.principal or o.comunidad_id is not null)
     order by (r.comunidad_id = perfil.principal) desc nulls last, (r.comunidad_id is not null) desc, o.prioridad nulls last, r.id
     limit 1
  )
  select r.id, r.fecha, r.titulo::text, r.descripcion::text, r.objetivo_preguntas, r.recompensa_puntos
    from public.retos_diarios r
   where p_usuario is not null
     and r.id = coalesce((select f.reto_id from frozen f), (select e.id from eligible e))
$$;
revoke all on function public.app_legacy_daily_challenge(uuid) from public, anon, authenticated;
grant execute on function public.app_legacy_daily_challenge(uuid) to service_role;

commit;
