-- V1 DAILY CHALLENGE ALL-OR-NOTHING + PAID RETRY (owner decision 2026-11-15, workstream F31b, contract amendment FA57). Forward-only. Requires the V1 daily challenge
-- (20261022101200, 20261022103500), F20b (20261109100200: the ensure_challenge_row body replaced here), the F28 consumables (20261114400200: the answer and today bodies
-- replaced here, held doble/triple oportunidad) and the wallet ledger (20261022100300). Timestamp after the F31 calendar (20261115100000, independent: read only).
--
-- OWNER DECISION 2026-11-15:
--   1. The reto is SUPERADO only when ALL its questions are correct (necesarios = number of questions: 3 normally, fewer when the specialty has fewer eligible
--      questions). Otherwise FALLADO and the streak is lost unless a protector saves it (F28 protectors unchanged: protector_racha / dual keep the streak,
--      protector_tickets / dual keep the reward).
--   2. PAID RETRY before losing. A final WRONG answer of a caller that declares support (p_admite_reintento_pago = true; Ktor: body admiteReintentoPago true) and
--      has NO doble/triple oportunidad use left (consumables go FIRST, unchanged; declining a consumable that is still available is final) is HELD: nothing
--      stored, no correction, the answer says {"esCorrecta":false,"estado":"EN_CURSO","reintentoPago":{"disponible":true,"precioTickets":p,"saldoTickets":s}}.
--      Price p = floor((base reward + streak bonus that passing TODAY would pay) / 2), minimum 1 OT (private.reto_reintento_precio, the same plan and streak rules as
--      the completion), frozen in the held row. Then: the same option with p_pagar_reintento NULL = idempotent repeat of the offer; p_pagar_reintento = false =
--      decline (the held wrong answer becomes final); p_pagar_reintento = true with ANOTHER option = debit p through the wallet ledger under the wallet lock
--      (ledger tipo 'gasto' with metadatos.op 'reintento_reto', referencia reto, idempotency key reintento-reto:<user>:<reto>:<question>; the closed LedgerTipo
--      enum of the wallet contract and its CHECK are NOT changed) and answer that option; wrong again = final (one paid retry per question). Not enough OT =
--      P0001 INSUFFICIENT_BALANCE {"balance","price"} (Ktor 409 with details), nothing changes, the offer stays. Any other combination = ANSWER_LOCKED.
--      p_pagar_reintento = false on the first answer = never offer. Without p_admite_reintento_pago the decision is FALSE: never held, a held one is declined
--      (old images and old clients never get stuck).
--   3. GET today: preguntas[].reintentoPagoPendiente (true while a held answer waits for the decision) and reintentoPago {precioTickets, saldoTickets} while the reto
--      is DISPONIBLE / EN_CURSO (the frozen price of the held answer, otherwise what a wrong answer would cost now). necesarios = number of questions.
--
-- WHAT IT DOES
--   * public.reto_reintentos_pago: one row per user, reto and question (pendiente -> pagado | rechazado); no client access.
--   * private.reto_reintento_precio(user, specialty, now): the price.
--   * private.ensure_challenge_row: the F20b body with aciertos_necesarios = n (was ceil(2n/3)); rows of today and later get aciertos_necesarios = their number of
--     questions (a reto already completed keeps its frozen result).
--   * public.app_answer_daily_challenge(user, reto, question, option, p_usar_oportunidad DEFAULT NULL, p_admite_oportunidad DEFAULT false,
--     p_pagar_reintento DEFAULT NULL, p_admite_reintento_pago DEFAULT false): replaces the 6-argument function (dropped; ONE overload, so PostgREST never sees two
--     candidates and the named-argument calls of the running image keep working). Body = 20261114400200 plus the paid retry and the all-correct rule.
--   * public.app_get_daily_challenge: 20261114400200 body plus reintentoPagoPendiente / reintentoPago and necesarios 3 when NO_DISPONIBLE.
--   * NOTIFY pgrst at the end (the RPC signature changes).
-- DEPLOY ORDER: this migration, then the Ktor image that sends p_admite_reintento_pago / p_pagar_reintento (the running image sends neither: never held; its
-- 6 named arguments still resolve).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the previous bodies come back verbatim (answer 6 arguments and today 20261114400200, ensure_challenge_row
-- 20261109100200); rows of today and later get ceil(2n/3) back; held paid retries are dropped (the user answers again; a PAID retry stays in the ledger, the
-- answer it bought is already stored).
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean, boolean, boolean, boolean);
-- create or replace function public.app_answer_daily_challenge(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_opcion uuid, p_usar_oportunidad boolean default null,
--                                                               p_admite_oportunidad boolean default false)
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
--   v_pend public.reto_oportunidades_pendientes%rowtype;
--   v_hay_pend boolean;
--   v_content jsonb;
--   v_correcta uuid;
--   v_opcion uuid := p_opcion;
--   v_ok boolean;
--   v_progreso integer;
--   v_superado boolean;
--   v_reward jsonb;
--   st public.rachas_usuario%rowtype;
--   v_esp uuid;
--   v_plan uuid;
--   v_base integer := 0;
--   v_bonus integer := 0;
--   v_extra integer := 0;
--   v_mult numeric := 1;
--   v_tipo text;
--   v_paid boolean;
--   v_desde integer;
--   v_max integer;
--   v_hay_previa boolean;
--   v_alive boolean;
--   v_racha_hyp integer;
--   v_save_streak boolean := false;
--   v_save_tickets boolean := false;
--   v_dual uuid;
--   v_prot_racha uuid;
--   v_prot_tickets uuid;
--   v_protector uuid;
--   v_res_tickets uuid;
--   v_pot uuid;
--   v_op uuid;
--   v_usados text[] := '{}';
--   -- the caller's decision; a caller that does not declare support for held answers (old image, old client) = false: never held, a held one is declined
--   v_usar boolean := case when p_admite_oportunidad then p_usar_oportunidad else false end;
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
--       'recompensa', case when g.completado and (g.superado or g.recompensa_total_tickets > 0)
--                          then private.reto_recompensa_json(g) || jsonb_build_object('reclamada', g.recompensa_entregada) end,
--       'racha', private.reto_streak_json(p_usuario, v_today)));
--   end if;
--   if v_now < r.empieza_en or v_now >= r.termina_en or g.completado then
--     raise exception using errcode = 'P0001', message = 'RETO_OUT_OF_WINDOW';
--   end if;
--   if not exists (select 1 from jsonb_array_elements(v_content -> 'opciones') o where o ->> 'id' = p_opcion::text) then
--     raise exception using errcode = 'P0001', message = 'OPTION_NOT_IN_QUESTION';
--   end if;
--
--   -- ---- doble / triple oportunidad (F28): a wrong answer held while the user decides
--   select * into v_pend from public.reto_oportunidades_pendientes x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
--   v_hay_pend := found;
--   if v_hay_pend then
--     if v_usar is null then
--       if p_opcion <> v_pend.opcion_id then
--         raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- a different option needs the explicit choice to spend the retry
--       end if;
--       -- idempotent retry of the held wrong answer: the same offer again, nothing changes
--       return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
--         'oportunidad', jsonb_build_object('disponible', true, 'restantes', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now)),
--         'racha', private.reto_streak_json(p_usuario, v_today)));
--     elsif not v_usar then
--       v_opcion := v_pend.opcion_id;  -- declined: the held wrong answer becomes final
--       delete from public.reto_oportunidades_pendientes x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
--     else
--       if p_opcion = v_pend.opcion_id then
--         raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- the retry must be another option
--       end if;
--       v_op := private.consumible_usable(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now);
--       if v_op is null then
--         raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- nothing left to spend (expired meanwhile): decline to close it
--       end if;
--       perform private.consumible_gastar(v_op, p_reto, v_now);
--       v_usados := v_usados || (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_op);
--       delete from public.reto_oportunidades_pendientes x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
--     end if;
--   end if;
--   v_ok := (v_opcion = v_correcta);
--   -- a wrong answer with a retry available is HELD (not stored, no correction) unless the user already declined (v_usar = false)
--   if not v_ok and v_usar is distinct from false
--      and private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now) > 0 then
--     insert into public.reto_oportunidades_pendientes (usuario_id, reto_id, pregunta_id, opcion_id, creado_en) values (p_usuario, p_reto, p_pregunta, v_opcion, v_now);
--     return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
--       'oportunidad', jsonb_build_object('disponible', true, 'restantes', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now)),
--       'consumiblesUsados', case when cardinality(v_usados) > 0 then to_jsonb(v_usados) end, 'racha', private.reto_streak_json(p_usuario, v_today)));
--   end if;
--
--   insert into public.respuestas_reto_diario (usuario_id, reto_id, pregunta_id, opcion_id, es_correcta, respondida_en) values (p_usuario, p_reto, p_pregunta, v_opcion, v_ok, v_now);
--   v_progreso := g.progreso + 1;
--   update public.registros_reto_diario set progreso = v_progreso, aciertos = g.aciertos + (case when v_ok then 1 else 0 end), fallos = g.fallos + (case when v_ok then 0 else 1 end)
--    where usuario_id = p_usuario and reto_id = p_reto
--   returning * into g;
--   if v_progreso >= r.objetivo_preguntas then
--     v_superado := g.aciertos >= r.aciertos_necesarios;
--     -- tier of the user for the bar and the reward: a PAID plan (activa / cancelada with its period) earns its plan amount; trial and free users earn the Gratuito amount
--     v_paid := exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada') and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now));
--     v_tipo := case when v_paid then 'suscritos' else 'gratis' end;
--     insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values (p_usuario, 0, 0, null) on conflict do nothing;
--     select * into st from public.rachas_usuario x where x.usuario_id = p_usuario for update;
--     v_alive := st.ultima_actividad_fecha is not null and st.racha_actual_dias > 0
--                and (st.ultima_actividad_fecha = v_today or private.reto_streak_gap_free(st.ultima_actividad_fecha, v_today, r.especialidad_id));
--     v_racha_hyp := case when st.ultima_actividad_fecha = v_today then st.racha_actual_dias when v_alive then st.racha_actual_dias + 1 else 1 end;
--     -- reward amounts as if passed (frozen now); a failed reto pays them only through a tickets protector
--     select pf.especialidad_principal_id into v_esp from public.perfiles_usuario pf where pf.usuario_id = p_usuario;
--     if v_paid then
--       select up.plan_id into v_plan from private.user_plan(p_usuario, v_now) up;
--     end if;
--     if v_plan is null then
--       select pl.id into v_plan from public.planes_suscripcion pl where pl.nivel = 0 and pl.activo order by pl.posicion, pl.codigo limit 1;
--     end if;
--     v_base := coalesce(private.plan_benefit_int(v_plan, 'ot_reto_diario', 0, private.config_int('max_ot_reto_diario', 200)), 0);
--     v_desde := private.config_int('bonus_racha_desde_dia', 3);
--     v_max := private.config_int('bonus_racha_max_dias', 7);
--     if v_racha_hyp >= v_desde and v_racha_hyp < v_desde + v_max then
--       v_bonus := coalesce(private.plan_benefit_int(v_plan, 'ot_bonus_racha', 0, private.config_int('max_ot_bonus_racha', 100)), 0);
--     end if;
--     if not v_superado then
--       -- ---- protectors (F28): spent only when something would be lost. Streak: the user had a live streak. Tickets: the reto paid something.
--       v_dual := private.consumible_usable(p_usuario, array['protector_dual'], v_now);
--       v_prot_racha := private.consumible_usable(p_usuario, array['protector_racha'], v_now);
--       v_prot_tickets := private.consumible_usable(p_usuario, array['protector_tickets'], v_now);
--       -- C2: the protector that only keeps the TICKETS (v_res_tickets) pays at claim time, so it is reserved now and spent by the claim; a protector that
--       -- saves the streak acts now and is spent now.
--       if v_alive and v_base + v_bonus > 0 then
--         if v_dual is not null then
--           v_protector := v_dual; v_save_streak := true; v_save_tickets := true;
--         else
--           if v_prot_racha is not null then v_protector := v_prot_racha; v_save_streak := true; end if;
--           if v_prot_tickets is not null then
--             v_save_tickets := true; v_res_tickets := v_prot_tickets; v_protector := coalesce(v_protector, v_prot_tickets);
--           end if;
--         end if;
--       elsif v_alive then
--         v_protector := coalesce(v_prot_racha, v_dual); v_save_streak := v_protector is not null;
--       elsif v_base + v_bonus > 0 then
--         v_res_tickets := coalesce(v_prot_tickets, v_dual); v_protector := v_res_tickets; v_save_tickets := v_protector is not null;
--       end if;
--       if v_protector is not null and v_protector is distinct from v_res_tickets then
--         perform private.consumible_gastar(v_protector, p_reto, v_now);
--         v_usados := v_usados || (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_protector);
--       end if;
--       if v_res_tickets is not null then
--         perform private.consumible_reservar(p_usuario, p_reto, v_res_tickets, v_today + private.config_int('reto_reclamar_dias', 3));
--         v_usados := v_usados || (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_res_tickets);
--       end if;
--       if not v_save_tickets then
--         v_base := 0; v_bonus := 0;
--       end if;
--     end if;
--     if v_superado or v_save_streak then
--       -- streak: single writer
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
--     end if;
--     -- ---- potenciador (F28): multiplies the BASE reward of this reto (never the streak bonus); one per reto, not cumulative. C2: reserved now, spent by the claim.
--     if v_base > 0 then
--       v_pot := private.consumible_usable(p_usuario, array['potenciador'], v_now);
--       if v_pot is not null then
--         select greatest(coalesce((a.metadatos ->> 'multiplicador')::numeric, s.multiplicador, 1), 1) into v_mult
--           from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = v_pot;
--         v_extra := floor(v_base * v_mult)::integer - v_base;
--         perform private.consumible_reservar(p_usuario, p_reto, v_pot, v_today + private.config_int('reto_reclamar_dias', 3));
--         v_usados := v_usados || 'potenciador'::text;
--       end if;
--     end if;
--     update public.registros_reto_diario set completado = true, completado_en = v_now, superado = v_superado, recompensa_base_tickets = v_base, bonus_racha_tickets = v_bonus,
--            recompensa_total_tickets = v_base + v_extra + v_bonus, multiplicador_aplicado = v_mult, protector_usado_id = v_protector, potenciador_usado_id = v_pot
--      where usuario_id = p_usuario and reto_id = p_reto returning * into g;
--     insert into public.reto_contadores (reto_id, tipo_usuario, jugadores, superados) values (p_reto, v_tipo, 1, case when v_superado then 1 else 0 end)
--     on conflict (reto_id, tipo_usuario) do update set jugadores = public.reto_contadores.jugadores + 1, superados = public.reto_contadores.superados + (case when v_superado then 1 else 0 end);
--     if v_superado or g.recompensa_total_tickets > 0 then
--       v_reward := private.reto_recompensa_json(g) || jsonb_build_object('reclamada', false);
--     end if;
--   end if;
--   return jsonb_strip_nulls(jsonb_build_object('esCorrecta', v_ok, 'opcionCorrectaId', v_correcta,
--     'estado', case when g.completado and g.superado then 'SUPERADO' when g.completado then 'FALLADO' else 'EN_CURSO' end,
--     'aciertos', g.aciertos, 'respondidas', g.progreso, 'recompensa', v_reward,
--     'consumiblesUsados', case when cardinality(v_usados) > 0 then to_jsonb(v_usados) end,
--     'racha', private.reto_streak_json(p_usuario, v_today)));
-- end;
-- $$;
-- revoke all on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean, boolean) from public, anon, authenticated;
-- grant execute on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean, boolean) to service_role;
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
--                               'barraGlobal', jsonb_build_object('jugadores', 0, 'superados', 0), 'consumibles', private.consumibles_reto_json(p_usuario, v_now));
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
--                                                                                   'opcionCorrectaId', (private.app_revision_pregunta_solucion(pr.pregunta_id) ->> 'opcion_correcta_id')::uuid) end,
--            'oportunidadPendiente', case when op.pregunta_id is not null and a.pregunta_id is null then true end)
--          order by pr.posicion), '[]'::jsonb)
--     into v_preguntas
--     from public.preguntas_reto_diario pr
--     cross join lateral (select private.app_revision_pregunta_contenido(pr.pregunta_id) as contenido) c
--     left join public.respuestas_reto_diario a on a.usuario_id = p_usuario and a.reto_id = v_reto and a.pregunta_id = pr.pregunta_id
--     left join public.reto_oportunidades_pendientes op on op.usuario_id = p_usuario and op.reto_id = v_reto and op.pregunta_id = pr.pregunta_id
--    where pr.reto_id = v_reto;
--   if g.completado and (g.superado or g.recompensa_total_tickets > 0) then
--     v_recomp := private.reto_recompensa_json(g) || jsonb_build_object('reclamada', g.recompensa_entregada,
--                                 'pendiente', exists (select 1 from public.ot_ganancias_diferidas q where q.usuario_id = p_usuario and q.referencia_id = v_reto and q.estado = 'pendiente'));
--   end if;
--   v_tipo := case when exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada')
--                                  and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, v_now)) then 'suscritos' else 'gratis' end;
--   select jsonb_build_object('jugadores', coalesce(sum(k.jugadores), 0), 'superados', coalesce(sum(k.superados), 0)) into v_barra
--     from public.reto_contadores k where k.reto_id = v_reto and k.tipo_usuario = v_tipo;
--   return jsonb_strip_nulls(jsonb_build_object('estado', v_estado, 'retoId', r.id, 'empiezaEn', r.empieza_en, 'terminaEn', r.termina_en, 'necesarios', r.aciertos_necesarios,
--     'aciertos', g.aciertos, 'preguntas', v_preguntas, 'recompensa', v_recomp, 'racha', private.reto_streak_json(p_usuario, v_today), 'barraGlobal', v_barra,
--     'protectorUsado', (select s.tipo_servicio from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.id = g.protector_usado_id),
--     'consumibles', private.consumibles_reto_json(p_usuario, v_now)));
-- end;
-- $$;
-- revoke all on function public.app_get_daily_challenge(uuid) from public, anon, authenticated;
-- grant execute on function public.app_get_daily_challenge(uuid) to service_role;
-- create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
-- returns boolean
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_reto uuid;
--   v_q uuid;
--   v_ids uuid[] := '{}';
--   v_slots text[] := '{}';
--   v_n integer;
--   v_inicio timestamptz;
--   v_hay_regional boolean;
--   s record;
--   f record;
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
--   -- 1. one per type (fixed slot order; the pick is the same for every creator of this day and segment: no random())
--   for s in select x.slot, x.tipo_slot from (values (1, 'inmersiva', 'inmersiva_demo'), (2, 'razonada', 'razonada'), (3, 'rafaga', 'rafaga')) x(orden, slot, tipo_slot) order by x.orden loop
--     v_q := null;
--     select q into v_q from private.reto_pool(p_esp, p_com, s.slot) q
--      order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|' || s.slot || '|' || q::text), q
--      limit 1;
--     if v_q is not null then
--       v_ids := v_ids || v_q;
--       v_slots := v_slots || s.tipo_slot::text;
--     end if;
--   end loop;
--   v_n := coalesce(array_length(v_ids, 1), 0);
--   -- 2. F20b (owner 2026-11-11): always 3 when the segment has them; fill from the union of the pools, no repeated question, own salt
--   if v_n < 3 then
--     for f in select c.q, c.tipo_slot
--                from (select pq.q, x.tipo_slot
--                        from (values ('inmersiva', 'inmersiva_demo'), ('razonada', 'razonada'), ('rafaga', 'rafaga')) x(slot, tipo_slot)
--                        cross join lateral private.reto_pool(p_esp, p_com, x.slot) as pq(q)) c
--               where not (c.q = any (v_ids))
--               order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|fill|' || c.q::text), c.q
--               limit 3 - v_n loop
--       v_ids := v_ids || f.q;
--       v_slots := v_slots || f.tipo_slot::text;
--     end loop;
--     v_n := coalesce(array_length(v_ids, 1), 0);
--   end if;
--   if v_n = 0 then
--     return false;  -- no eligible question at all: no challenge for this segment today (never relax the regional rule)
--   end if;
--   -- the window ends at the NEXT LOCAL MIDNIGHT of the configured zone (25 h on the October DST day, 23 h in March), never inicio + 24 h
--   v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
--   -- pass rule: ceil(2n/3) correct of n (3 -> 2, 2 -> 2, 1 -> 1)
--   insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
--   values (p_fecha, 'Reto diario', null, v_n, (2 * v_n + 2) / 3, 0, true, v_inicio, private.reto_day_end(p_fecha), p_esp, p_com, 'todos')
--   on conflict do nothing
--   returning id into v_reto;
--   if v_reto is null then
--     return false;
--   end if;
--   insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot)
--   select v_reto, v_ids[g.i], g.i, v_slots[g.i] from generate_subscripts(v_ids, 1) as g(i);
--   return true;
-- end;
-- $$;
-- revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;
-- update public.retos_diarios d set aciertos_necesarios = (2 * q.n + 2) / 3
--   from (select pr.reto_id, count(*)::integer as n from public.preguntas_reto_diario pr group by pr.reto_id) q
--  where q.reto_id = d.id and d.fecha >= private.reto_today(clock_timestamp()) and d.aciertos_necesarios <> (2 * q.n + 2) / 3;
-- drop function if exists private.reto_reintento_cargar(uuid, uuid, uuid, integer, date);
-- drop function if exists private.reto_reintento_precio(uuid, uuid, timestamptz);
-- drop table if exists public.reto_reintentos_pago;
-- notify pgrst, 'reload schema';
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'answer_not_single_8_argument_service_role_function' as check_name,
--        (case when to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean,boolean,boolean)') is null
--                or (select count(*) from pg_proc p where p.proname = 'app_answer_daily_challenge' and p.pronamespace = 'public'::regnamespace) <> 1
--                or has_function_privilege('anon', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean,boolean,boolean)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean,boolean,boolean)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean,boolean,boolean)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'answer_without_all_correct_rule',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean,boolean,boolean)')) like '%g.aciertos >= greatest(v_n, 1)%' then 0 else 1 end)::bigint
-- union all
-- select 'today_without_paid_retry',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('public.app_get_daily_challenge(uuid)')) like '%reintentoPagoPendiente%' then 0 else 1 end)::bigint
-- union all
-- select 'new_rows_not_all_correct',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.ensure_challenge_row(date,uuid,uuid)')) like '%v_n, v_n, 0, true%' then 0 else 1 end)::bigint
-- union all
-- select 'open_rows_not_all_correct', count(*)::bigint
--   from public.retos_diarios d join (select pr.reto_id, count(*)::integer as n from public.preguntas_reto_diario pr group by pr.reto_id) q on q.reto_id = d.id
--  where d.fecha >= private.reto_today(clock_timestamp()) and d.aciertos_necesarios <> q.n
-- union all
-- select 'paid_retries_readable_by_clients',
--        (case when to_regclass('public.reto_reintentos_pago') is null
--                or has_table_privilege('anon', 'public.reto_reintentos_pago', 'select') or has_table_privilege('authenticated', 'public.reto_reintentos_pago', 'select')
--                or has_table_privilege('service_role', 'public.reto_reintentos_pago', 'select') then 1 else 0 end)::bigint
-- union all
-- select 'price_helper_missing_or_client_callable',
--        (case when to_regprocedure('private.reto_reintento_precio(uuid,uuid,timestamptz)') is null
--                or has_function_privilege('anon', 'private.reto_reintento_precio(uuid,uuid,timestamptz)', 'execute')
--                or has_function_privilege('authenticated', 'private.reto_reintento_precio(uuid,uuid,timestamptz)', 'execute')
--                or to_regprocedure('private.reto_reintento_cargar(uuid,uuid,uuid,integer,date)') is null
--                or has_function_privilege('anon', 'private.reto_reintento_cargar(uuid,uuid,uuid,integer,date)', 'execute')
--                or has_function_privilege('authenticated', 'private.reto_reintento_cargar(uuid,uuid,uuid,integer,date)', 'execute') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean)') is null or to_regclass('public.reto_oportunidades_pendientes') is null
     or to_regprocedure('private.consumible_restantes(uuid,text[],timestamptz)') is null or to_regprocedure('private.reto_streak_gap_free(date,date,uuid)') is null
     or to_regclass('public.carteras_usuario') is null
     or (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.ensure_challenge_row(date,uuid,uuid)')) not like '%(2 * v_n + 2) / 3%'
     or (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.ensure_challenge_row(date,uuid,uuid)')) not like '%|fill|%' then
    raise exception using errcode = 'P0001', message = 'V1_RETO_TODO_O_NADA_PREFLIGHT', hint = 'apply 20261109100200 and 20261114400200 first';
  end if;
end
$$;

-- ---- 1. the wrong answer HELD while the user decides whether to pay for one more try (never readable by clients)
create table if not exists public.reto_reintentos_pago (
  usuario_id uuid not null references auth.users(id) on delete cascade,
  reto_id uuid not null references public.retos_diarios(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  opcion_id uuid not null,
  precio_tickets integer not null check (precio_tickets >= 1),
  estado text not null default 'pendiente' check (estado in ('pendiente', 'pagado', 'rechazado')),
  transaccion_id uuid,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  primary key (usuario_id, reto_id, pregunta_id)
);
alter table public.reto_reintentos_pago enable row level security;
revoke all on table public.reto_reintentos_pago from public, anon, authenticated, service_role;

-- ---- 2. the price: half of what passing TODAY would pay (base of the user plan + the streak bonus of the day it would be), at least 1 OT.
--         Same plan and streak rules as the completion in app_answer_daily_challenge (paid plan = its benefits, trial/free = the Gratuito plan).
create or replace function private.reto_reintento_precio(p_usuario uuid, p_esp uuid, p_now timestamptz)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_today date := private.reto_today(p_now);
  st public.rachas_usuario%rowtype;
  v_hay boolean;
  v_alive boolean;
  v_hyp integer;
  v_plan uuid;
  v_base integer;
  v_bonus integer := 0;
  v_desde integer;
  v_max integer;
begin
  select * into st from public.rachas_usuario x where x.usuario_id = p_usuario;
  v_hay := found;
  v_alive := v_hay and st.ultima_actividad_fecha is not null and st.racha_actual_dias > 0
             and (st.ultima_actividad_fecha = v_today or private.reto_streak_gap_free(st.ultima_actividad_fecha, v_today, p_esp));
  v_hyp := case when v_hay and st.ultima_actividad_fecha = v_today then st.racha_actual_dias when v_alive then st.racha_actual_dias + 1 else 1 end;
  if exists (select 1 from public.estado_premium_usuario e where e.usuario_id = p_usuario and e.estado_suscripcion in ('activa', 'cancelada')
               and private.premium_active(e.es_premium, e.estado_suscripcion, e.fin_en, p_now)) then
    select up.plan_id into v_plan from private.user_plan(p_usuario, p_now) up;
  end if;
  if v_plan is null then
    select pl.id into v_plan from public.planes_suscripcion pl where pl.nivel = 0 and pl.activo order by pl.posicion, pl.codigo limit 1;
  end if;
  v_base := coalesce(private.plan_benefit_int(v_plan, 'ot_reto_diario', 0, private.config_int('max_ot_reto_diario', 200)), 0);
  v_desde := private.config_int('bonus_racha_desde_dia', 3);
  v_max := private.config_int('bonus_racha_max_dias', 7);
  if v_hyp >= v_desde and v_hyp < v_desde + v_max then
    v_bonus := coalesce(private.plan_benefit_int(v_plan, 'ot_bonus_racha', 0, private.config_int('max_ot_bonus_racha', 100)), 0);
  end if;
  return greatest((v_base + v_bonus) / 2, 1);
end;
$$;
revoke all on function private.reto_reintento_precio(uuid, uuid, timestamptz) from public, anon, authenticated;

-- ---- 2b. the debit of a paid retry: wallet lock (order reto -> wallet, like the claim), balance check, ONE ledger row (tipo gasto, op reintento_reto, key unique per
--          user, reto and question). Its own function so the free-earn writer rule (W-10: only private.free_ot_apply writes the free-earn ledger types) stays checkable.
create or replace function private.reto_reintento_cargar(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_precio integer, p_dia date)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_saldo integer;
  v_id uuid;
begin
  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  v_saldo := coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0);
  if v_saldo < p_precio then
    raise exception using errcode = 'P0001', message = 'INSUFFICIENT_BALANCE', detail = format('{"balance":%s,"price":%s}', v_saldo, p_precio);
  end if;
  insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
  values (p_usuario, 'gasto', -p_precio, 'Reintento del reto', 'reto', p_reto,
          'reintento-reto:' || p_usuario::text || ':' || p_reto::text || ':' || p_pregunta::text,
          jsonb_build_object('op', 'reintento_reto', 'reto_id', p_reto, 'pregunta_id', p_pregunta, 'dia', p_dia, 'precio_tickets', p_precio))
  returning id into v_id;
  return v_id;
end;
$$;
revoke all on function private.reto_reintento_cargar(uuid, uuid, uuid, integer, date) from public, anon, authenticated;

-- ---- 3. new rows: all questions to pass; open rows (today and later) aligned
create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_reto uuid;
  v_q uuid;
  v_ids uuid[] := '{}';
  v_slots text[] := '{}';
  v_n integer;
  v_inicio timestamptz;
  v_hay_regional boolean;
  s record;
  f record;
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
  -- 1. one per type (fixed slot order; the pick is the same for every creator of this day and segment: no random())
  for s in select x.slot, x.tipo_slot from (values (1, 'inmersiva', 'inmersiva_demo'), (2, 'razonada', 'razonada'), (3, 'rafaga', 'rafaga')) x(orden, slot, tipo_slot) order by x.orden loop
    v_q := null;
    select q into v_q from private.reto_pool(p_esp, p_com, s.slot) q
     order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|' || s.slot || '|' || q::text), q
     limit 1;
    if v_q is not null then
      v_ids := v_ids || v_q;
      v_slots := v_slots || s.tipo_slot::text;
    end if;
  end loop;
  v_n := coalesce(array_length(v_ids, 1), 0);
  -- 2. F20b (owner 2026-11-11): always 3 when the segment has them; fill from the union of the pools, no repeated question, own salt
  if v_n < 3 then
    for f in select c.q, c.tipo_slot
               from (select pq.q, x.tipo_slot
                       from (values ('inmersiva', 'inmersiva_demo'), ('razonada', 'razonada'), ('rafaga', 'rafaga')) x(slot, tipo_slot)
                       cross join lateral private.reto_pool(p_esp, p_com, x.slot) as pq(q)) c
              where not (c.q = any (v_ids))
              order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|fill|' || c.q::text), c.q
              limit 3 - v_n loop
      v_ids := v_ids || f.q;
      v_slots := v_slots || f.tipo_slot::text;
    end loop;
    v_n := coalesce(array_length(v_ids, 1), 0);
  end if;
  if v_n = 0 then
    return false;  -- no eligible question at all: no challenge for this segment today (never relax the regional rule)
  end if;
  -- the window ends at the NEXT LOCAL MIDNIGHT of the configured zone (25 h on the October DST day, 23 h in March), never inicio + 24 h
  v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
  -- FA57 (owner 2026-11-15): pass rule = ALL n questions correct
  insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
  values (p_fecha, 'Reto diario', null, v_n, v_n, 0, true, v_inicio, private.reto_day_end(p_fecha), p_esp, p_com, 'todos')
  on conflict do nothing
  returning id into v_reto;
  if v_reto is null then
    return false;
  end if;
  insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot)
  select v_reto, v_ids[g.i], g.i, v_slots[g.i] from generate_subscripts(v_ids, 1) as g(i);
  return true;
end;
$$;
revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;
update public.retos_diarios d set aciertos_necesarios = q.n
  from (select pr.reto_id, count(*)::integer as n from public.preguntas_reto_diario pr group by pr.reto_id) q
 where q.reto_id = d.id and d.fecha >= private.reto_today(clock_timestamp()) and d.aciertos_necesarios <> q.n;

-- ---- 4. answer: the 20261114400200 body plus the paid retry and the all-correct rule (see the header); replaced, never overloaded
drop function if exists public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean, boolean);
create or replace function public.app_answer_daily_challenge(p_usuario uuid, p_reto uuid, p_pregunta uuid, p_opcion uuid, p_usar_oportunidad boolean default null,
                                                              p_admite_oportunidad boolean default false, p_pagar_reintento boolean default null,
                                                              p_admite_reintento_pago boolean default false)
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
  -- the caller's decision; a caller that does not declare support for held answers (old image, old client) = false: never held, a held one is declined
  v_usar boolean := case when p_admite_oportunidad then p_usar_oportunidad else false end;
  -- FA57: the paid retry, same pattern: a caller that does not declare support (old image, old client) = false: never held, a held one is declined
  v_pagar boolean := case when p_admite_reintento_pago then p_pagar_reintento else false end;
  v_rp public.reto_reintentos_pago%rowtype;
  v_hay_rp boolean;
  v_pagado boolean := false;
  v_precio integer;
  v_saldo integer;
  v_ledger uuid;
  v_n integer;
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
    if v_usar is null then
      if p_opcion <> v_pend.opcion_id then
        raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- a different option needs the explicit choice to spend the retry
      end if;
      -- idempotent retry of the held wrong answer: the same offer again, nothing changes
      return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
        'oportunidad', jsonb_build_object('disponible', true, 'restantes', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now)),
        'racha', private.reto_streak_json(p_usuario, v_today)));
    elsif not v_usar then
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
  -- ---- FA57 paid retry: a wrong answer held while the user decides whether to pay for ONE more try of this question
  select * into v_rp from public.reto_reintentos_pago x where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
  v_hay_rp := found;
  if v_hay_rp and v_rp.estado = 'pendiente' then
    if v_pagar is null then
      if p_opcion <> v_rp.opcion_id then
        raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- a different option needs the explicit choice to pay
      end if;
      -- idempotent retry of the held wrong answer: the same offer again (frozen price), nothing changes
      return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
        'reintentoPago', jsonb_build_object('disponible', true, 'precioTickets', v_rp.precio_tickets,
                                            'saldoTickets', coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0)),
        'racha', private.reto_streak_json(p_usuario, v_today)));
    elsif not v_pagar then
      v_opcion := v_rp.opcion_id;  -- declined: the held wrong answer becomes final
      update public.reto_reintentos_pago x set estado = 'rechazado', actualizado_en = v_now
       where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
    else
      if p_opcion = v_rp.opcion_id then
        raise exception using errcode = 'P0001', message = 'ANSWER_LOCKED';  -- the paid retry must be another option
      end if;
      -- debit through the wallet ledger (private.reto_reintento_cargar: wallet lock, balance, one row); not enough OT = nothing changes, the offer stays
      v_ledger := private.reto_reintento_cargar(p_usuario, p_reto, p_pregunta, v_rp.precio_tickets, v_today);
      update public.reto_reintentos_pago x set estado = 'pagado', transaccion_id = v_ledger, actualizado_en = v_now
       where x.usuario_id = p_usuario and x.reto_id = p_reto and x.pregunta_id = p_pregunta;
      v_pagado := true;
    end if;
  elsif v_hay_rp and v_rp.estado = 'pagado' then
    v_pagado := true;  -- one paid retry per question: never offered again
  end if;

  v_ok := (v_opcion = v_correcta);
  -- a wrong answer with a retry available is HELD (not stored, no correction) unless the user already declined (v_usar = false); never after a paid retry
  if not v_ok and not v_pagado and v_usar is distinct from false
     and private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now) > 0 then
    insert into public.reto_oportunidades_pendientes (usuario_id, reto_id, pregunta_id, opcion_id, creado_en) values (p_usuario, p_reto, p_pregunta, v_opcion, v_now);
    return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
      'oportunidad', jsonb_build_object('disponible', true, 'restantes', private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now)),
      'consumiblesUsados', case when cardinality(v_usados) > 0 then to_jsonb(v_usados) end, 'racha', private.reto_streak_json(p_usuario, v_today)));
  end if;
  -- FA57: no retry use left (consumables go first; declining one, v_usar = false with uses left, is final): a wrong answer is HELD with a PAID retry offer,
  -- once per question, only for a caller that declares support and did not refuse it up front (v_pagar = false)
  if not v_ok and not v_pagado and not v_hay_rp and p_admite_reintento_pago and v_pagar is distinct from false
     and private.consumible_restantes(p_usuario, array['doble_oportunidad', 'triple_oportunidad'], v_now) = 0 then
    v_precio := private.reto_reintento_precio(p_usuario, r.especialidad_id, v_now);
    insert into public.reto_reintentos_pago (usuario_id, reto_id, pregunta_id, opcion_id, precio_tickets, estado, creado_en, actualizado_en)
    values (p_usuario, p_reto, p_pregunta, v_opcion, v_precio, 'pendiente', v_now, v_now);
    return jsonb_strip_nulls(jsonb_build_object('esCorrecta', false, 'estado', 'EN_CURSO', 'aciertos', g.aciertos, 'respondidas', g.progreso,
      'reintentoPago', jsonb_build_object('disponible', true, 'precioTickets', v_precio,
                                          'saldoTickets', coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0)),
      'consumiblesUsados', case when cardinality(v_usados) > 0 then to_jsonb(v_usados) end, 'racha', private.reto_streak_json(p_usuario, v_today)));
  end if;

  insert into public.respuestas_reto_diario (usuario_id, reto_id, pregunta_id, opcion_id, es_correcta, respondida_en) values (p_usuario, p_reto, p_pregunta, v_opcion, v_ok, v_now);
  v_progreso := g.progreso + 1;
  update public.registros_reto_diario set progreso = v_progreso, aciertos = g.aciertos + (case when v_ok then 1 else 0 end), fallos = g.fallos + (case when v_ok then 0 else 1 end)
   where usuario_id = p_usuario and reto_id = p_reto
  returning * into g;
  if v_progreso >= r.objetivo_preguntas then
    -- FA57 (owner 2026-11-15): passed only with EVERY question of the reto correct (necesarios = number of questions)
    select count(*)::integer into v_n from public.preguntas_reto_diario pr where pr.reto_id = p_reto;
    v_superado := g.aciertos >= greatest(v_n, 1);
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
revoke all on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean, boolean, boolean, boolean) from public, anon, authenticated;
grant execute on function public.app_answer_daily_challenge(uuid, uuid, uuid, uuid, boolean, boolean, boolean, boolean) to service_role;

-- ---- 5. today: the 20261114400200 body plus reintentoPagoPendiente / reintentoPago
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
    return jsonb_build_object('estado', 'NO_DISPONIBLE', 'necesarios', 3, 'aciertos', 0, 'preguntas', '[]'::jsonb, 'racha', private.reto_streak_json(p_usuario, v_today),
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
           'oportunidadPendiente', case when op.pregunta_id is not null and a.pregunta_id is null then true end,
           'reintentoPagoPendiente', case when rp.estado = 'pendiente' and a.pregunta_id is null then true end)
         order by pr.posicion), '[]'::jsonb)
    into v_preguntas
    from public.preguntas_reto_diario pr
    cross join lateral (select private.app_revision_pregunta_contenido(pr.pregunta_id) as contenido) c
    left join public.respuestas_reto_diario a on a.usuario_id = p_usuario and a.reto_id = v_reto and a.pregunta_id = pr.pregunta_id
    left join public.reto_oportunidades_pendientes op on op.usuario_id = p_usuario and op.reto_id = v_reto and op.pregunta_id = pr.pregunta_id
    left join public.reto_reintentos_pago rp on rp.usuario_id = p_usuario and rp.reto_id = v_reto and rp.pregunta_id = pr.pregunta_id
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
    'consumibles', private.consumibles_reto_json(p_usuario, v_now),
    -- FA57: price of the paid retry (the frozen price of a held answer, otherwise what a wrong answer would cost now) and the balance, while the reto is open
    'reintentoPago', case when v_estado in ('DISPONIBLE', 'EN_CURSO') then jsonb_build_object(
      'precioTickets', coalesce((select x.precio_tickets from public.reto_reintentos_pago x where x.usuario_id = p_usuario and x.reto_id = v_reto and x.estado = 'pendiente' order by x.creado_en limit 1),
                                private.reto_reintento_precio(p_usuario, r.especialidad_id, v_now)),
      'saldoTickets', coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0)) end));
end;
$$;
revoke all on function public.app_get_daily_challenge(uuid) from public, anon, authenticated;
grant execute on function public.app_get_daily_challenge(uuid) to service_role;

notify pgrst, 'reload schema';

commit;
