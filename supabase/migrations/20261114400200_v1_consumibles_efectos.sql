-- =============================================================================
-- V1 -- consumables take effect in the daily challenge (F28 step 3, FA52). Forward-only.
-- =============================================================================
-- Requires 20261114400100 (activations bought with OT) and the V1 daily challenge (20261022101200, 20261022103500). Timestamp AFTER F20b
-- (20261109100200, ensure_challenge_row only) and F27 (20261114300000, stats by an AFTER INSERT trigger on respuestas_reto_diario): neither redefines the
-- three functions replaced here, so this body is the 20261022103500 / 20261022101200 one plus the consumables. A held wrong answer (below) is not
-- inserted into respuestas_reto_diario until it is final, so F27 counts only final answers.
--
-- OWNER DECISION 2026-11-14: consumables are bought with OposiTickets AND work in 1.3. Definitions from the client document "Arquitectura compra
-- OposiBots" section 8 (Servicios del oposimarket), the only place that defines them:
--   * protector_racha   "se contabiliza la racha pero pierdes los tickets"    -> a FAILED reto counts as a streak day; that day pays nothing.
--   * protector_tickets "sin el pierde 60 tickets" (40 base + 20 streak bonus) -> a FAILED reto still pays the reward it would have paid (base + bonus as
--                       if passed); the streak breaks. The document line "pierdes los tickets pero no se contabiliza la racha" is read with its price note
--                       (what it protects is the tickets); see the F28 ledger.
--   * protector_dual    "protege tu racha y los tickets"                       -> both.
--   * potenciador       "solo multiplicaran la recompensa base diaria ... El bonus de racha no se multiplicara" "un solo uso ... uno diario por reto. No
--                       acumulable" -> one potenciador per reto multiplies the BASE reward (never the bonus).
--   * doble/triple oportunidad "deshace la pregunta respondida, otorgandole una oportunidad adicional. Antes de que aparezcan los recursos ... pop-up
--                       preguntando si desea utilizarlo" -> after a wrong answer, BEFORE the correction, the user may answer that question again.
--   * modo_papel ("el usuario pagara por el numero de examenes que quiera pasar a PDF") and carta_ccaa_comodin ("no en MVP ... gamificacion distinta"):
--     no behaviour defined -> NOT purchasable in 1.3 (20261114400100, review C4); nothing here.
--
-- WHAT IT DOES
--   * public.reto_oportunidades_pendientes: the wrong answer HELD while the user decides (one row per user, reto, question; no client access).
--   * private.consumible_usable / consumible_restantes / consumible_gastar / consumibles_reto_json / reto_recompensa_json: the shared rules. A usable
--     activation: estado 'activa', not expired (activa_hasta), uses left. The pick: soonest expiry, then the highest multiplier, then the oldest; locked FOR UPDATE
--     under the per-user reto lock. Spending a use writes usado_en and usado_en_reto_id; the last use sets estado 'usada' (D6-01 consumibles and D6-39 disponibles follow).
--   * public.app_answer_daily_challenge(user, reto, question, option, p_usar_oportunidad boolean DEFAULT NULL) replaces the 4-argument function (same named
--     arguments + one optional: the old Ktor call keeps working; no overload, so PostgREST never sees two candidates):
--       - doble/triple: a WRONG answer while the user has retry uses is HELD: nothing is stored, no correction, the answer says
--         {"esCorrecta":false,"estado":"EN_CURSO","oportunidad":{"disponible":true,"restantes":n}}. Then: the same option with p_usar_oportunidad NULL = idempotent
--         repeat of that offer; p_usar_oportunidad = false = decline (the held answer becomes final, normal flow); p_usar_oportunidad = true with ANOTHER option = spend
--         one use and answer that option (wrong again with uses left = held again). Any other combination = ANSWER_LOCKED (nothing changes). Sending
--         p_usar_oportunidad = false on the first answer = never offer.
--       - at completion, when the reto is FAILED: protectors as defined above, spent only when something would be lost (streak: the user had a live streak;
--         tickets: the reto would have paid > 0). Both needed: a dual if there is one, otherwise a racha and a tickets protector (each its own). Only the
--         streak: racha, else dual. Only the tickets: tickets, else dual. registros_reto_diario.protector_usado_id = the protector that decided it.
--         The reto stays FALLADO (superado false, reto_contadores unchanged); a saved streak advances exactly like a passed day.
--       - potenciador: when the reward base > 0 (passed, or failed with tickets protected) ONE potenciador multiplies the base: extra = floor(base x m) - base,
--         recompensa_total_tickets = base + extra + bonus, multiplicador_aplicado = m (frozen at purchase), potenciador_usado_id.
--       - C2 (review 2026-11-14): what only pays at CLAIM time is RESERVED at answer time and SPENT at claim time: the potenciador, and the protector that only
--         keeps the tickets (protector_tickets, or a dual used for the tickets alone). A reservation (public.reto_consumibles_reservados) holds one use until
--         the last claim day of that reto (completion day + reto_reclamar_dias); usable / remaining counts subtract live reservations, so one use is never
--         promised twice. A claim that fails (RETO_CLAIM_EXPIRED, EMAIL_UNVERIFIED, RETO_CLAIM_NOT_ELIGIBLE, OT_EARN_CAP_REACHED) rolls back and spends
--         nothing; an unclaimed reservation frees itself after the claim window. What acts at answer time is still spent at answer time (protector_racha, a
--         dual that saves the streak, doble/triple oportunidad).
--       - the answer adds consumiblesUsados (types spent by this answer) and recompensa.multiplicador / recompensa.extraPotenciador when a potenciador applied.
--   * public.app_get_daily_challenge: reward also shown for a FAILED reto that pays (tickets protector), potenciador part, preguntas[].oportunidadPendiente
--     (held answer waiting for the decision), protectorUsado (type) and consumibles {protectorRacha, protectorTickets, protectorDual, potenciador, oportunidades}.
--   * public.app_claim_daily_challenge: claimable when completed AND (passed OR total > 0); the potenciador extra is credited as ONE 'potenciador' ledger
--     movement (key potenciador:<user>:<dia>, outside the free-earn caps: it was bought with OT) in the same all-or-nothing transaction as base and bonus;
--     the reserved uses of that reto are spent in that same transaction (C2). OWNER DECISION D1 pending: c_potenciador_en_topes (one constant in the claim)
--     routes the extra through public.app_credit_free_ot ('reto_diario', same key) so it counts in the daily/weekly free-OT caps; today false = outside.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the previous bodies come back verbatim (answer 20261022103500 with 4 arguments; today and claim
-- 20261022101200). Activations already spent stay spent; held answers are dropped (the user answers again); reservations are dropped (those uses were never
-- spent: the user keeps them).
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean);
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
--       elsif private.reto_streak_gap_free(st.ultima_actividad_fecha, v_today, r.especialidad_id) then
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
--
-- create or replace function public.app_get_daily_challenge(p_usuario uuid)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_now timestamptz := clock_timestamp();
--   v_today date := private.reto_today(v_now);
--   v_reto uuid;
--   r public.retos_diarios%rowtype;
--   g public.registros_reto_diario%rowtype;
--   v_estado text;
--   v_preguntas jsonb;
--   v_recomp jsonb;
--   v_tipo text;
--   v_barra jsonb;
-- begin
--   if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--   v_reto := private.user_challenge_reto(p_usuario, v_now);
--   if v_reto is null then
--     return jsonb_build_object('estado', 'NO_DISPONIBLE', 'necesarios', 2, 'aciertos', 0, 'preguntas', '[]'::jsonb, 'racha', private.reto_streak_json(p_usuario, v_today),
--                               'barraGlobal', jsonb_build_object('jugadores', 0, 'superados', 0));
--   end if;
--   select * into r from public.retos_diarios x where x.id = v_reto;
--   select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = v_reto;
--   v_estado := case
--     when v_now < r.empieza_en then 'NO_DISPONIBLE'
--     when g.completado and g.superado then 'SUPERADO'
--     when g.completado then 'FALLADO'
--     when v_now >= r.termina_en then 'FALLADO'
--     when g.progreso > 0 then 'EN_CURSO'
--     else 'DISPONIBLE' end;
--   select coalesce(jsonb_agg(jsonb_build_object(
--            'posicion', pr.posicion,
--            'tipoSlot', case pr.tipo_slot when 'inmersiva_demo' then 'INMERSIVA' when 'razonada' then 'RAZONADA' else 'RAFAGA' end,
--            'preguntaId', pr.pregunta_id,
--            'titulo', c.contenido ->> 'titulo',
--            '_recursos', coalesce(c.contenido -> 'recursos', '[]'::jsonb),
--            'opciones', coalesce((select jsonb_agg(jsonb_build_object('id', o ->> 'id', 'letra', o ->> 'letra', 'texto', o ->> 'texto') order by (o ->> 'posicion')::int, o ->> 'id')
--                                    from jsonb_array_elements(c.contenido -> 'opciones') o), '[]'::jsonb),
--            'respuesta', case when a.pregunta_id is not null then jsonb_build_object('opcionId', a.opcion_id, 'esCorrecta', a.es_correcta,
--                                                                                   'opcionCorrectaId', (private.app_revision_pregunta_solucion(pr.pregunta_id) ->> 'opcion_correcta_id')::uuid) end)
--          order by pr.posicion), '[]'::jsonb)
--     into v_preguntas
--     from public.preguntas_reto_diario pr
--     cross join lateral (select private.app_revision_pregunta_contenido(pr.pregunta_id) as contenido) c
--     left join public.respuestas_reto_diario a on a.usuario_id = p_usuario and a.reto_id = v_reto and a.pregunta_id = pr.pregunta_id
--    where pr.reto_id = v_reto;
--   if g.completado and g.superado then
--     v_recomp := jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', g.recompensa_entregada,
--                                 'pendiente', exists (select 1 from public.ot_ganancias_diferidas q where q.usuario_id = p_usuario and q.referencia_id = v_reto and q.estado = 'pendiente'));
--   end if;
--   v_tipo := case when exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada')
--                                  and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now)) then 'suscritos' else 'gratis' end;
--   select jsonb_build_object('jugadores', coalesce(sum(k.jugadores), 0), 'superados', coalesce(sum(k.superados), 0)) into v_barra
--     from public.reto_contadores k where k.reto_id = v_reto and k.tipo_usuario = v_tipo;
--   return jsonb_strip_nulls(jsonb_build_object('estado', v_estado, 'retoId', r.id, 'empiezaEn', r.empieza_en, 'terminaEn', r.termina_en, 'necesarios', r.aciertos_necesarios,
--     'aciertos', g.aciertos, 'preguntas', v_preguntas, 'recompensa', v_recomp, 'racha', private.reto_streak_json(p_usuario, v_today), 'barraGlobal', v_barra));
-- end;
-- $$;
-- revoke all on function public.app_get_daily_challenge(uuid) from public, anon, authenticated;
-- grant execute on function public.app_get_daily_challenge(uuid) to service_role;
--
-- create or replace function public.app_claim_daily_challenge(p_usuario uuid, p_reto uuid)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   g public.registros_reto_diario%rowtype;
--   v_now timestamptz;
--   v_today date;
--   v_tz text := private.reto_tz();
--   v_key_base text;
--   v_key_racha text;
--   a record;
--   b record;
--   v_started boolean;
--   v_pend boolean;
--   v_confirmado timestamptz;
--   v_creado timestamptz;
--   v_n integer;
--   v_saldo integer;
-- begin
--   if p_usuario is null or p_reto is null then
--     raise exception using errcode = 'P0001', message = 'NOT_FOUND';
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
--   v_now := clock_timestamp();
--   v_today := private.reto_today(v_now);
--   select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.dia is not null;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'NOT_FOUND';
--   end if;
--   if not (g.completado and g.superado) then
--     raise exception using errcode = 'P0001', message = 'RETO_NOT_PASSED';
--   end if;
--   select coalesce(w.saldo_tickets, 0) into v_saldo from public.carteras_usuario w where w.usuario_id = p_usuario;
--   if g.recompensa_entregada then
--     return jsonb_build_object('recompensa', jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', true, 'pendiente', false), 'saldo', coalesce(v_saldo, 0));
--   end if;
--   v_key_base := 'reto:' || p_usuario::text || ':' || g.dia::text || ':base';
--   v_key_racha := 'racha:' || p_usuario::text || ':' || g.dia::text;
--   v_started := exists (select 1 from public.ot_ganancias_diferidas q where q.usuario_id = p_usuario and q.clave in (v_key_base, v_key_racha))
--             or exists (select 1 from public.transacciones_cartera t where t.usuario_id = p_usuario and t.idempotency_key in (v_key_base, v_key_racha));
--   if not v_started then
--     -- first claim attempt only: window and farming gates (W-9, W-6); once the earn started, replays never re-gate
--     if (v_today - (g.completado_en at time zone v_tz)::date) > private.config_int('reto_reclamar_dias', 3) then
--       raise exception using errcode = 'P0001', message = 'RETO_CLAIM_EXPIRED';
--     end if;
--     select f.confirmado_en, f.creado_en into v_confirmado, v_creado from private.user_auth_facts(p_usuario) f;
--     if private.config_int('reto_exige_email_verificado', 1) = 1 and v_confirmado is null then
--       raise exception using errcode = 'P0001', message = 'EMAIL_UNVERIFIED';
--     end if;
--     if private.config_int('reto_edad_minima_horas', 0) > 0 and (v_creado is null or v_now - v_creado < make_interval(hours => private.config_int('reto_edad_minima_horas', 0))) then
--       raise exception using errcode = 'P0001', message = 'RETO_CLAIM_NOT_ELIGIBLE';
--     end if;
--     if private.config_int('reto_min_respuestas_cobrar', 0) > 0 then
--       select count(*)::integer into v_n from public.respuestas_intento ri join public.intentos_examen ie on ie.id = ri.intento_id
--        where ie.usuario_id = p_usuario and ri.es_primer_intento_pregunta and not coalesce(ie.es_demo, false);
--       if v_n < private.config_int('reto_min_respuestas_cobrar', 0) then
--         raise exception using errcode = 'P0001', message = 'RETO_CLAIM_NOT_ELIGIBLE';
--       end if;
--     end if;
--   end if;
--   select * into a from public.app_credit_free_ot(p_usuario, 'reto_diario', g.recompensa_base_tickets, 'reto', p_reto, v_key_base, jsonb_build_object('dia', g.dia));
--   select * into b from public.app_credit_free_ot(p_usuario, 'bonus_racha', g.bonus_racha_tickets, 'reto', p_reto, v_key_racha, jsonb_build_object('dia', g.dia));
--   if a.estado = 'REJECTED_CAP' or b.estado = 'REJECTED_CAP' then
--     raise exception using errcode = 'P0001', message = 'OT_EARN_CAP_REACHED';  -- rolls back a part that was already credited: all or nothing
--   end if;
--   v_pend := a.estado = 'DEFERRED' or b.estado = 'DEFERRED';
--   if not v_pend then
--     update public.registros_reto_diario set recompensa_entregada = true where usuario_id = p_usuario and reto_id = p_reto;
--   end if;
--   select coalesce(w.saldo_tickets, 0) into v_saldo from public.carteras_usuario w where w.usuario_id = p_usuario;
--   return jsonb_build_object('recompensa', jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets, 'reclamada', not v_pend, 'pendiente', v_pend), 'saldo', v_saldo);
-- end;
-- $$;
-- revoke all on function public.app_claim_daily_challenge(uuid, uuid) from public, anon, authenticated;
-- grant execute on function public.app_claim_daily_challenge(uuid, uuid) to service_role;
--
-- drop function if exists private.reto_potenciador_abonar(uuid, uuid, text, integer, date, numeric, uuid);
-- drop function if exists private.reto_recompensa_json(public.registros_reto_diario);
-- drop function if exists private.consumibles_reto_json(uuid, timestamptz);
-- drop function if exists private.consumible_gastar(uuid, uuid, timestamptz);
-- drop function if exists private.consumible_restantes(uuid, text[], timestamptz);
-- drop function if exists private.consumible_usable(uuid, text[], timestamptz);
-- drop function if exists private.consumible_reservar(uuid, uuid, uuid, date);
-- drop function if exists private.consumible_reservados(uuid, timestamptz);
-- drop table if exists public.reto_consumibles_reservados;
-- drop table if exists public.reto_oportunidades_pendientes;
-- notify pgrst, 'reload schema';
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'answer_without_oportunidad_argument',
--        (case when to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)') is not null
--                and to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)') is null then 0 else 1 end)::bigint
-- union all
-- select 'answer_not_service_role_only',
--        (case when to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)') is null then 1
--              when has_function_privilege('anon', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'answer_does_not_spend_consumables',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)')) like '%protector_usado_id = v_protector%'
--                and (select p.prosrc from pg_proc p where p.oid = to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean)')) like '%multiplicador_aplicado = v_mult%' then 0 else 1 end)::bigint
-- union all
-- select 'claim_does_not_credit_the_potenciador',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('public.app_claim_daily_challenge(uuid,uuid)')) like '%v_key_pot%' then 0 else 1 end)::bigint
-- union all
-- select 'held_answers_readable_by_clients',
--        (case when to_regclass('public.reto_oportunidades_pendientes') is null
--                or has_table_privilege('anon', 'public.reto_oportunidades_pendientes', 'select') or has_table_privilege('authenticated', 'public.reto_oportunidades_pendientes', 'select')
--                or has_table_privilege('service_role', 'public.reto_oportunidades_pendientes', 'select') then 1 else 0 end)::bigint
-- union all
-- select 'reservations_readable_by_clients',
--        (case when to_regclass('public.reto_consumibles_reservados') is null
--                or has_table_privilege('anon', 'public.reto_consumibles_reservados', 'select') or has_table_privilege('authenticated', 'public.reto_consumibles_reservados', 'select')
--                or has_table_privilege('service_role', 'public.reto_consumibles_reservados', 'select') then 1 else 0 end)::bigint
-- union all
-- select 'claim_does_not_spend_the_reservations',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('public.app_claim_daily_challenge(uuid,uuid)')) like '%reto_consumibles_reservados%' then 0 else 1 end)::bigint
-- union all
-- select 'consumable_helpers_executable_by_clients',
--        (case when to_regprocedure('private.consumible_usable(uuid,text[],timestamptz)') is null or to_regprocedure('private.consumible_gastar(uuid,uuid,timestamptz)') is null
--                   or to_regprocedure('private.consumible_restantes(uuid,text[],timestamptz)') is null then 1
--              when has_function_privilege('authenticated', 'private.consumible_usable(uuid,text[],timestamptz)', 'execute')
--                or has_function_privilege('authenticated', 'private.consumible_gastar(uuid,uuid,timestamptz)', 'execute')
--                or has_function_privilege('anon', 'private.consumible_restantes(uuid,text[],timestamptz)', 'execute') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.servicio_disponibles(uuid,uuid,timestamptz)') is null or to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)') is null
     or to_regprocedure('private.reto_streak_gap_free(date,date,uuid)') is null or to_regclass('public.respuestas_reto_diario') is null then
    raise exception using errcode = 'P0001', message = 'F28_EFECTOS_PREFLIGHT', hint = 'apply 20261114400100 and the V1 daily challenge (20261022101200, 20261022103500) first';
  end if;
end
$$;

-- ---- 1. a wrong answer held while the user decides whether to spend a doble/triple oportunidad (never readable by clients)
create table if not exists public.reto_oportunidades_pendientes (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  opcion_id uuid not null,
  creado_en timestamptz not null default now(),
  primary key (usuario_id, reto_id, pregunta_id)
);
alter table public.reto_oportunidades_pendientes enable row level security;
revoke all on table public.reto_oportunidades_pendientes from public, anon, authenticated, service_role;

-- ---- 1b. C2: one use of an activation RESERVED by a completed reto until its claim (spent by the claim; frees itself after the last claim day)
create table if not exists public.reto_consumibles_reservados (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  activacion_id uuid not null references public.activaciones_servicio_usuario(id) on delete cascade,
  reclamable_hasta date not null,
  creado_en timestamptz not null default now(),
  primary key (usuario_id, reto_id, activacion_id)
);
create index if not exists idx_reto_consumibles_reservados_activacion on public.reto_consumibles_reservados (activacion_id, reclamable_hasta);
alter table public.reto_consumibles_reservados enable row level security;
revoke all on table public.reto_consumibles_reservados from public, anon, authenticated, service_role;

-- live reservations of one activation (claim window still open on the Madrid day of p_now)
create or replace function private.consumible_reservados(p_activacion uuid, p_now timestamptz)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select count(*)::integer from public.reto_consumibles_reservados x where x.activacion_id = p_activacion and x.reclamable_hasta >= private.reto_today(p_now)
$$;
revoke all on function private.consumible_reservados(uuid, timestamptz) from public, anon, authenticated;

create or replace function private.consumible_reservar(p_usuario uuid, p_reto uuid, p_activacion uuid, p_hasta date)
returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.reto_consumibles_reservados (usuario_id, reto_id, activacion_id, reclamable_hasta) values (p_usuario, p_reto, p_activacion, p_hasta)
  on conflict do nothing
$$;
revoke all on function private.consumible_reservar(uuid, uuid, uuid, date) from public, anon, authenticated;

-- ---- 2. the user's usable activation of some types: soonest expiry first, then the highest multiplier, then the oldest (locked FOR UPDATE); live reservations
--         (C2) take their use out first
create or replace function private.consumible_usable(p_usuario uuid, p_tipos text[], p_now timestamptz)
returns uuid
language sql
security definer
set search_path = ''
as $$
  select a.id
    from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id
   where a.usuario_id = p_usuario and s.tipo_servicio = any (p_tipos) and a.estado = 'activa'
     and (a.activa_hasta is null or a.activa_hasta > p_now) and a.usos_consumidos + private.consumible_reservados(a.id, p_now) < a.usos_disponibles
   order by a.activa_hasta nulls last, coalesce((a.metadatos ->> 'multiplicador')::numeric, s.multiplicador, 1) desc, a.activa_desde, a.id
   limit 1
   for update of a
$$;
revoke all on function private.consumible_usable(uuid, text[], timestamptz) from public, anon, authenticated;

-- remaining uses of the user's usable activations of some types (the panel counters)
create or replace function private.consumible_restantes(p_usuario uuid, p_tipos text[], p_now timestamptz)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(sum(greatest(a.usos_disponibles - a.usos_consumidos - private.consumible_reservados(a.id, p_now), 0)), 0)::integer
    from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id
   where a.usuario_id = p_usuario and s.tipo_servicio = any (p_tipos) and a.estado = 'activa'
     and (a.activa_hasta is null or a.activa_hasta > p_now) and a.usos_consumidos < a.usos_disponibles
$$;
revoke all on function private.consumible_restantes(uuid, text[], timestamptz) from public, anon, authenticated;

-- spends ONE use of an activation in a daily challenge; the last use closes it ('usada')
create or replace function private.consumible_gastar(p_activacion uuid, p_reto uuid, p_now timestamptz)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.activaciones_servicio_usuario
     set usos_consumidos = usos_consumidos + 1,
         estado = case when usos_consumidos + 1 >= usos_disponibles then 'usada' else estado end,
         usado_en = p_now, usado_en_reto_id = p_reto
   where id = p_activacion
$$;
revoke all on function private.consumible_gastar(uuid, uuid, timestamptz) from public, anon, authenticated;

create or replace function private.consumibles_reto_json(p_usuario uuid, p_now timestamptz)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'protectorRacha', private.consumible_restantes(p_usuario, array['protector_racha'], p_now),
    'protectorTickets', private.consumible_restantes(p_usuario, array['protector_tickets'], p_now),
    'protectorDual', private.consumible_restantes(p_usuario, array['protector_dual'], p_now),
    'potenciador', private.consumible_restantes(p_usuario, array['potenciador'], p_now),
    'oportunidades', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], p_now))
$$;
revoke all on function private.consumibles_reto_json(uuid, timestamptz) from public, anon, authenticated;

-- the frozen reward as the app reads it (D3-16, D3-17, D3-18): base, bonusRacha, total, plus the potenciador part when one was applied
create or replace function private.reto_recompensa_json(g public.registros_reto_diario)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object('base', g.recompensa_base_tickets, 'bonusRacha', g.bonus_racha_tickets, 'total', g.recompensa_total_tickets)
      || case when g.multiplicador_aplicado > 1 then
           jsonb_build_object('multiplicador', g.multiplicador_aplicado::double precision,
                              'extraPotenciador', g.recompensa_total_tickets - g.recompensa_base_tickets - g.bonus_racha_tickets)
         else '{}'::jsonb end
$$;
revoke all on function private.reto_recompensa_json(public.registros_reto_diario) from public, anon, authenticated;

-- ---- 3. answer: the 20261022103500 body plus the consumables (see the header). p_usar_oportunidad is new and defaults to NULL: the old 4-argument
--         call (named arguments, Ktor before F28) keeps working, so the function is replaced (dropped and created with 5 arguments) instead of overloaded.
drop function if exists public.app_answer_daily_challenge(uuid, uuid, uuid, uuid);
create or replace function public.app_answer_daily_challenge(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_opcion uuid, p_usar_oportunidad boolean default null)
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
  v_pend public.reto_oportunidades_pendientes%rowtype;
  v_hay_pend boolean;
  v_content jsonb;
  v_correcta uuid;
  v_opcion uuid := p_opcion;
  v_ok boolean;
  v_progreso integer;
  v_superado boolean;
  v_reward jsonb;
  st public.rachas_usuario%rowtype;
  v_esp uuid;
  v_plan uuid;
  v_base integer := 0;
  v_bonus integer := 0;
  v_extra integer := 0;
  v_mult numeric := 1;
  v_tipo text;
  v_paid boolean;
  v_desde integer;
  v_max integer;
  v_hay_previa boolean;
  v_alive boolean;
  v_racha_hyp integer;
  v_save_streak boolean := false;
  v_save_tickets boolean := false;
  v_dual uuid;
  v_prot_racha uuid;
  v_prot_tickets uuid;
  v_protector uuid;
  v_res_tickets uuid;
  v_pot uuid;
  v_op uuid;
  v_usados text[] := '{}';
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
      'recompensa', case when g.completado and (g.superado or g.recompensa_total_tickets > 0)
                         then private.reto_recompensa_json(g) || jsonb_build_object('reclamada', g.recompensa_entregada) end,
      'racha', private.reto_streak_json(p_usuario, v_today)));
  end if;
  if v_now < r.empieza_en or v_now >= r.termina_en or g.completado then
    raise exception using errcode = 'P0001', message = 'RETO_OUT_OF_WINDOW';
  end if;
  if not exists (select 1 from jsonb_array_elements(v_content -> 'opciones') o where o ->> 'id' = p_opcion::text) then
    raise exception using errcode = 'P0001', message = 'OPTION_NOT_IN_QUESTION';
  end if;

  -- ---- doble / triple oportunidad (F28): a wrong answer held while the user decides
  select * into v_pend from public.reto_oportunidades_pendientes x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
  v_hay_pend := found;
  if v_hay_pend then
    if p_usar_oportunidad is null then
      if p_opcion <> v_pend.opcion_id then
        raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- a different option needs the explicit choice to spend the retry
      end if;
      -- idempotent retry of the held wrong answer: the same offer again, nothing changes
      return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
        'oportunidad', jsonb_build_object('disponible', true, 'restantes', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now)),
        'racha', private.reto_streak_json(p_usuario, v_today)));
    elsif not p_usar_oportunidad then
      v_opcion := v_pend.opcion_id;  -- declined: the held wrong answer becomes final
      delete from public.reto_oportunidades_pendientes x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
    else
      if p_opcion = v_pend.opcion_id then
        raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- the retry must be another option
      end if;
      v_op := private.consumible_usable(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now);
      if v_op is null then
        raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- nothing left to spend (expired meanwhile): decline to close it
      end if;
      perform private.consumible_gastar(v_op, p_reto, v_now);
      v_usados := v_usados || (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_op);
      delete from public.reto_oportunidades_pendientes x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
    end if;
  end if;
  v_ok := (v_opcion = v_correcta);
  -- a wrong answer with a retry available is HELD (not stored, no correction) unless the user already declined (p_usar_oportunidad = false)
  if not v_ok and p_usar_oportunidad is distinct from false
     and private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now) > 0 then
    insert into public.reto_oportunidades_pendientes (usuario_id, reto_id, pregunta_id, opcion_id, creado_en) values (p_usuario, p_reto, p_pregunta, v_opcion, v_now);
    return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
      'oportunidad', jsonb_build_object('disponible', true, 'restantes', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now)),
      'consumiblesUsados', case when cardinality(v_usados) > 0 then to_jsonb(v_usados) end, 'racha', private.reto_streak_json(p_usuario, v_today)));
  end if;

  insert into public.respuestas_reto_diario (usuario_id, reto_id, pregunta_id, opcion_id, es_correcta, respondida_en) values (p_usuario, p_reto, p_pregunta, v_opcion, v_ok, v_now);
  v_progreso := g.progreso + 1;
  update public.registros_reto_diario set progreso = v_progreso, aciertos = g.aciertos + (case when v_ok then 1 else 0 end), fallos = g.fallos + (case when v_ok then 0 else 1 end)
   where usuario_id = p_usuario and reto_id = p_reto
  returning * into g;
  if v_progreso >= r.objetivo_preguntas then
    v_superado := g.aciertos >= r.aciertos_necesarios;
    -- tier of the user for the bar and the reward: a PAID plan (activa / cancelada with its period) earns its plan amount; trial and free users earn the Gratuito amount
    v_paid := exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada') and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now));
    v_tipo := case when v_paid then 'suscritos' else 'gratis' end;
    insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values (p_usuario, 0, 0, null) on conflict do nothing;
    select * into st from public.rachas_usuario x where x.usuario_id = p_usuario for update;
    v_alive := st.ultima_actividad_fecha is not null and st.racha_actual_dias > 0
               and (st.ultima_actividad_fecha = v_today or private.reto_streak_gap_free(st.ultima_actividad_fecha, v_today, r.especialidad_id));
    v_racha_hyp := case when st.ultima_actividad_fecha = v_today then st.racha_actual_dias when v_alive then st.racha_actual_dias + 1 else 1 end;
    -- reward amounts as if passed (frozen now); a failed reto pays them only through a tickets protector
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
    if v_racha_hyp >= v_desde and v_racha_hyp < v_desde + v_max then
      v_bonus := coalesce(private.plan_benefit_int(v_plan, 'ot_bonus_racha', 0, private.config_int('max_ot_bonus_racha', 100)), 0);
    end if;
    if not v_superado then
      -- ---- protectors (F28): spent only when something would be lost. Streak: the user had a live streak. Tickets: the reto paid something.
      v_dual := private.consumible_usable(p_usuario, array['protector_dual'], v_now);
      v_prot_racha := private.consumible_usable(p_usuario, array['protector_racha'], v_now);
      v_prot_tickets := private.consumible_usable(p_usuario, array['protector_tickets'], v_now);
      -- C2: the protector that only keeps the TICKETS (v_res_tickets) pays at claim time, so it is reserved now and spent by the claim; a protector that
      -- saves the streak acts now and is spent now.
      if v_alive and v_base + v_bonus > 0 then
        if v_dual is not null then
          v_protector := v_dual; v_save_streak := true; v_save_tickets := true;
        else
          if v_prot_racha is not null then v_protector := v_prot_racha; v_save_streak := true; end if;
          if v_prot_tickets is not null then
            v_save_tickets := true; v_res_tickets := v_prot_tickets; v_protector := coalesce(v_protector, v_prot_tickets);
          end if;
        end if;
      elsif v_alive then
        v_protector := coalesce(v_prot_racha, v_dual); v_save_streak := v_protector is not null;
      elsif v_base + v_bonus > 0 then
        v_res_tickets := coalesce(v_prot_tickets, v_dual); v_protector := v_res_tickets; v_save_tickets := v_protector is not null;
      end if;
      if v_protector is not null and v_protector is distinct from v_res_tickets then
        perform private.consumible_gastar(v_protector, p_reto, v_now);
        v_usados := v_usados || (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_protector);
      end if;
      if v_res_tickets is not null then
        perform private.consumible_reservar(p_usuario, p_reto, v_res_tickets, v_today + private.config_int('reto_reclamar_dias', 3));
        v_usados := v_usados || (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_res_tickets);
      end if;
      if not v_save_tickets then
        v_base := 0; v_bonus := 0;
      end if;
    end if;
    if v_superado or v_save_streak then
      -- streak: single writer
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
    end if;
    -- ---- potenciador (F28): multiplies the BASE reward of this reto (never the streak bonus); one per reto, not cumulative. C2: reserved now, spent by the claim.
    if v_base > 0 then
      v_pot := private.consumible_usable(p_usuario, array['potenciador'], v_now);
      if v_pot is not null then
        select greatest(coalesce((a.metadatos ->> 'multiplicador')::numeric, s.multiplicador, 1), 1) into v_mult
          from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_pot;
        v_extra := floor(v_base * v_mult)::integer - v_base;
        perform private.consumible_reservar(p_usuario, p_reto, v_pot, v_today + private.config_int('reto_reclamar_dias', 3));
        v_usados := v_usados || 'potenciador'::text;
      end if;
    end if;
    update public.registros_reto_diario set completado = true, completado_en = v_now, superado = v_superado, recompensa_base_tickets = v_base, bonus_racha_tickets = v_bonus,
           recompensa_total_tickets = v_base + v_extra + v_bonus, multiplicador_aplicado = v_mult, protector_usado_id = v_protector, potenciador_usado_id = v_pot
     where usuario_id = p_usuario and reto_id = p_reto returning * into g;
    insert into public.reto_contadores (reto_id, tipo_usuario, jugadores, superados) values (p_reto, v_tipo, 1, case when v_superado then 1 else 0 end)
    on conflict (reto_id, tipo_usuario) do update set jugadores = public.reto_contadores.jugadores + 1, superados = public.reto_contadores.superados + (case when v_superado then 1 else 0 end);
    if v_superado or g.recompensa_total_tickets > 0 then
      v_reward := private.reto_recompensa_json(g) || jsonb_build_object('reclamada', false);
    end if;
  end if;
  return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_ok, 'opcionCorrectaId', v_correcta,
    'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
    'aciertos', g.aciertos, 'respondidas', g.progreso, 'recompensa', v_reward,
    'consumiblesUsados', case when cardinality(v_usados) > 0 then to_jsonb(v_usados) end,
    'racha', private.reto_streak_json(p_usuario, v_today)));
end;
$$;
revoke all on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean) from public, anon, authenticated;
grant execute on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean) to service_role;

-- ---- 4. today: the 20261022101200 body; the reward also shows for a failed reto saved by a tickets protector, the potenciador part, the held retry and the counters
create or replace function public.app_get_daily_challenge(p_usuario uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_today date := private.reto_today(v_now);
  v_reto uuid;
  r public.retos_diarios%rowtype;
  g public.registros_reto_diario%rowtype;
  v_estado text;
  v_preguntas jsonb;
  v_recomp jsonb;
  v_tipo text;
  v_barra jsonb;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  v_reto := private.user_challenge_reto(p_usuario, v_now);
  if v_reto is null then
    return jsonb_build_object('estado', 'NO_DISPONIBLE', 'necesarios', 2, 'aciertos', 0, 'preguntas', '[]'::jsonb, 'racha', private.reto_streak_json(p_usuario, v_today),
                              'barraGlobal', jsonb_build_object('jugadores', 0, 'superados', 0), 'consumibles', private.consumibles_reto_json(p_usuario, v_now));
  end if;
  select * into r from public.retos_diarios x where x.id = v_reto;
  select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = v_reto;
  v_estado := case
    when v_now < r.empieza_en then 'NO_DISPONIBLE'
    when g.completado and g.superado then 'SUPERADO'
    when g.completado then 'FALLADO'
    when v_now >= r.termina_en then 'FALLADO'
    when g.progreso > 0 then 'EN_CURSO'
    else 'DISPONIBLE' end;
  select coalesce(jsonb_agg(jsonb_build_object(
           'posicion', pr.posicion,
           'tipoSlot', case pr.tipo_slot when 'inmersiva_demo' then 'INMERSIVA' when 'razonada' then 'RAZONADA' else 'RAFAGA' end,
           'preguntaId', pr.pregunta_id,
           'titulo', c.contenido ->> 'titulo',
           '_recursos', coalesce(c.contenido -> 'recursos', '[]'::jsonb),
           'opciones', coalesce((select jsonb_agg(jsonb_build_object('id', o ->> 'id', 'letra', o ->> 'letra', 'texto', o ->> 'texto') order by (o ->> 'posicion')::int, o ->> 'id')
                                   from jsonb_array_elements(c.contenido -> 'opciones') o), '[]'::jsonb),
           'respuesta', case when a.pregunta_id is not null then jsonb_build_object('opcionId', a.opcion_id, 'esCorrecta', a.es_correcta,
                                                                                  'opcionCorrectaId', (private.app_revision_pregunta_solucion(pr.pregunta_id) ->> 'opcion_correcta_id')::uuid) end,
           'oportunidadPendiente', case when op.pregunta_id is not null and a.pregunta_id is null then true end)
         order by pr.posicion), '[]'::jsonb)
    into v_preguntas
    from public.preguntas_reto_diario pr
    cross join lateral (select private.app_revision_pregunta_contenido(pr.pregunta_id) as contenido) c
    left join public.respuestas_reto_diario a on a.usuario_id = p_usuario and a.reto_id = v_reto and a.pregunta_id = pr.pregunta_id
    left join public.reto_oportunidades_pendientes op on op.usuario_id = p_usuario and op.reto_id = v_reto and op.pregunta_id = pr.pregunta_id
   where pr.reto_id = v_reto;
  if g.completado and (g.superado or g.recompensa_total_tickets > 0) then
    v_recomp := private.reto_recompensa_json(g) || jsonb_build_object('reclamada', g.recompensa_entregada,
                                'pendiente', exists (select 1 from public.ot_ganancias_diferidas q where q.usuario_id = p_usuario and q.referencia_id = v_reto and q.estado = 'pendiente'));
  end if;
  v_tipo := case when exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada')
                                 and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now)) then 'suscritos' else 'gratis' end;
  select jsonb_build_object('jugadores', coalesce(sum(k.jugadores), 0), 'superados', coalesce(sum(k.superados), 0)) into v_barra
    from public.reto_contadores k where k.reto_id = v_reto and k.tipo_usuario = v_tipo;
  return jsonb_strip_nulls(jsonb_build_object('estado', v_estado, 'retoId', r.id, 'empiezaEn', r.empieza_en, 'terminaEn', r.termina_en, 'necesarios', r.aciertos_necesarios,
    'aciertos', g.aciertos, 'preguntas', v_preguntas, 'recompensa', v_recomp, 'racha', private.reto_streak_json(p_usuario, v_today), 'barraGlobal', v_barra,
    'protectorUsado', (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = g.protector_usado_id),
    'consumibles', private.consumibles_reto_json(p_usuario, v_now)));
end;
$$;
revoke all on function public.app_get_daily_challenge(uuid) from public, anon, authenticated;
grant execute on function public.app_get_daily_challenge(uuid) to service_role;

-- the potenciador part of a claimed reward: ONE 'potenciador' movement per user and Madrid day (idempotent by its key). Its own function so that
-- no function other than private.free_ot_apply writes the free-earn ledger types (gate W-10); the caller holds the wallet lock.
create or replace function private.reto_potenciador_abonar(p_usuario uuid, p_reto uuid, p_clave text, p_cantidad integer, p_dia date, p_mult numeric, p_activacion uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_cantidad > 0 and not exists (select 1 from public.transacciones_cartera t where t.usuario_id = p_usuario and t.idempotency_key = p_clave) then
    insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
    values (p_usuario, 'potenciador', p_cantidad, 'Potenciador', 'reto', p_reto, p_clave,
            jsonb_build_object('op', 'potenciador_reto', 'dia', p_dia, 'multiplicador', p_mult, 'activacion_id', p_activacion));
  end if;
end;
$$;
revoke all on function private.reto_potenciador_abonar(uuid, uuid, text, integer, date, numeric, uuid) from public, anon, authenticated;

-- ---- 5. claim: the 20261022101200 body; a failed reto saved by a tickets protector is claimable, and the potenciador part is credited as a 'potenciador'
--         ledger movement (bought with OT, so outside the free-earn caps), idempotent by its key, in the same all-or-nothing transaction
create or replace function public.app_claim_daily_challenge(p_usuario uuid, p_reto uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  g public.registros_reto_diario%rowtype;
  v_now timestamptz;
  v_today date;
  v_tz text := private.reto_tz();
  v_key_base text;
  v_key_racha text;
  v_key_pot text;
  v_extra integer;
  -- OWNER DECISION D1 (pending): true = the potenciador extra goes through public.app_credit_free_ot and counts in the daily/weekly free-OT caps.
  c_potenciador_en_topes constant boolean := false;
  a record;
  b record;
  c record;
  v_res record;
  v_started boolean;
  v_pend boolean;
  v_confirmado timestamptz;
  v_creado timestamptz;
  v_n integer;
  v_saldo integer;
begin
  if p_usuario is null or p_reto is null then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  v_now := clock_timestamp();
  v_today := private.reto_today(v_now);
  select * into g from public.registros_reto_diario x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.dia is not null;
  if not found then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  if not (g.completado and (g.superado or g.recompensa_total_tickets > 0)) then
    raise exception using errcode = 'P0001', message = 'RETO_NOT_PASSED';
  end if;
  select coalesce(w.saldo_tickets, 0) into v_saldo from public.carteras_usuario w where w.usuario_id = p_usuario;
  if g.recompensa_entregada then
    return jsonb_build_object('recompensa', private.reto_recompensa_json(g) || jsonb_build_object('reclamada', true, 'pendiente', false), 'saldo', coalesce(v_saldo, 0));
  end if;
  v_key_base := 'reto:' || p_usuario::text || ':' || g.dia::text || ':base';
  v_key_racha := 'racha:' || p_usuario::text || ':' || g.dia::text;
  v_key_pot := 'potenciador:' || p_usuario::text || ':' || g.dia::text;
  v_started := exists (select 1 from public.ot_ganancias_diferidas q where q.usuario_id = p_usuario and q.clave in (v_key_base, v_key_racha, v_key_pot))
            or exists (select 1 from public.transacciones_cartera t where t.usuario_id = p_usuario and t.idempotency_key in (v_key_base, v_key_racha, v_key_pot));
  if not v_started then
    -- first claim attempt only: window and farming gates (W-9, W-6); once the earn started, replays never re-gate
    if (v_today - (g.completado_en at time zone v_tz)::date) > private.config_int('reto_reclamar_dias', 3) then
      raise exception using errcode = 'P0001', message = 'RETO_CLAIM_EXPIRED';
    end if;
    select f.confirmado_en, f.creado_en into v_confirmado, v_creado from private.user_auth_facts(p_usuario) f;
    if private.config_int('reto_exige_email_verificado', 1) = 1 and v_confirmado is null then
      raise exception using errcode = 'P0001', message = 'EMAIL_UNVERIFIED';
    end if;
    if private.config_int('reto_edad_minima_horas', 0) > 0 and (v_creado is null or v_now - v_creado < make_interval(hours => private.config_int('reto_edad_minima_horas', 0))) then
      raise exception using errcode = 'P0001', message = 'RETO_CLAIM_NOT_ELIGIBLE';
    end if;
    if private.config_int('reto_min_respuestas_cobrar', 0) > 0 then
      select count(*)::integer into v_n from public.respuestas_intento ri join public.intentos_examen ie on ie.id = ri.intento_id
       where ie.usuario_id = p_usuario and ri.es_primer_intento_pregunta and not coalesce(ie.es_demo, false);
      if v_n < private.config_int('reto_min_respuestas_cobrar', 0) then
        raise exception using errcode = 'P0001', message = 'RETO_CLAIM_NOT_ELIGIBLE';
      end if;
    end if;
  end if;
  select * into a from public.app_credit_free_ot(p_usuario, 'reto_diario', g.recompensa_base_tickets, 'reto', p_reto, v_key_base, jsonb_build_object('dia', g.dia));
  select * into b from public.app_credit_free_ot(p_usuario, 'bonus_racha', g.bonus_racha_tickets, 'reto', p_reto, v_key_racha, jsonb_build_object('dia', g.dia));
  if a.estado = 'REJECTED_CAP' or b.estado = 'REJECTED_CAP' then
    raise exception using errcode = 'P0001', message = 'OT_EARN_CAP_REACHED';  -- rolls back a part that was already credited: all or nothing
  end if;
  v_extra := g.recompensa_total_tickets - g.recompensa_base_tickets - g.bonus_racha_tickets;
  v_pend := a.estado = 'DEFERRED' or b.estado = 'DEFERRED';
  if v_extra > 0 then
    if c_potenciador_en_topes then
      select * into c from public.app_credit_free_ot(p_usuario, 'reto_diario', v_extra, 'reto', p_reto, v_key_pot,
                                                    jsonb_build_object('dia', g.dia, 'op', 'potenciador_reto', 'multiplicador', g.multiplicador_aplicado, 'activacion_id', g.potenciador_usado_id));
      if c.estado = 'REJECTED_CAP' then
        raise exception using errcode = 'P0001', message = 'OT_EARN_CAP_REACHED';
      end if;
      v_pend := v_pend or c.estado = 'DEFERRED';
    else
      perform private.reto_potenciador_abonar(p_usuario, p_reto, v_key_pot, v_extra, g.dia, g.multiplicador_aplicado, g.potenciador_usado_id);
    end if;
  end if;
  -- C2: the claim went through (credited or deferred): spend the uses this reto reserved (potenciador, tickets protector), once
  for v_res in select r.activacion_id from public.reto_consumibles_reservados r where r.usuario_id = p_usuario and r.reto_id = p_reto order by r.activacion_id loop
    perform private.consumible_gastar(v_res.activacion_id, p_reto, v_now);
  end loop;
  delete from public.reto_consumibles_reservados r where r.usuario_id = p_usuario and r.reto_id = p_reto;
  if not v_pend then
    update public.registros_reto_diario set recompensa_entregada = true where usuario_id = p_usuario and reto_id = p_reto;
  end if;
  select coalesce(w.saldo_tickets, 0) into v_saldo from public.carteras_usuario w where w.usuario_id = p_usuario;
  return jsonb_build_object('recompensa', private.reto_recompensa_json(g) || jsonb_build_object('reclamada', not v_pend, 'pendiente', v_pend), 'saldo', v_saldo);
end;
$$;
revoke all on function public.app_claim_daily_challenge(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_claim_daily_challenge(uuid, uuid) to service_role;

-- PostgREST: the 4-argument app_answer_daily_challenge was dropped and the 5-argument one created; reload the schema cache at commit so the API never
-- keeps resolving the old signature (review 2026-11-14).
notify pgrst, 'reload schema';

commit;
