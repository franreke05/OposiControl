-- =============================================================================
-- V1 -- STATS CORRECTNESS (F27, contract amendment FA54). Corrective; 20261022101600 is NOT edited. Forward-only.
-- Requires 20261022101600 (stats), 20261022101300 (official rules: frozen penalty, official_nota_racional) and 20261109100100 (F23 practice: intentos_examen.es_practica).
-- =============================================================================
-- OWNER RULES (2026-11-14, plan "arreglos de la 1.3" section 9):
--   a) a demo or a practice NEVER burns a question: a question seen or answered there still counts when the user later answers it for real (C-W3-3 retired).
--   b) aciertos / fallos and the per-tag stats count answers of real exams, practices and the daily challenge ("Si, suma, sin ranking"). A demo never counts.
--   c) Nota media, league and ranking count ONLY answers of attempts with cuenta_para_ranking = true (first real attempt of an exam): practice and reto never move them.
--   d) the Nota media uses Arturo's formula PD = A - E/(n-1) with the penalty FROZEN in each attempt (n = options of the question; penalty off = E weight 0).
--   e) "Sin contestar" uses the same criterion as aciertos / fallos.
--
-- DESIGN: two exactly-once ledgers, one row per (user, question), each the guard of its own counters (C-S1 unchanged: a replay, retry or double click never counts twice).
--   * public.primeras_respuestas_usuario (existing) = the COUNTED ledger: the user's first answer to the question among real exams, practices (origen 'practica') and the daily
--     challenge (origen 'reto', through the new AFTER INSERT trigger on respuestas_reto_diario, tiempo_ms null). It feeds estadisticas_usuario (aciertos / fallos), the per-tag
--     stats and, as before, the difficulty job. Demo answers never enter; a question answered first in a practice or the reto counts there and is not counted again.
--   * public.primeras_respuestas_ranking (NEW, private) = the RANKING ledger: the user's first answer to the question in an attempt that counts for ranking, with the 27720-scaled
--     weight of a wrong answer from the attempt's frozen rule (private.stats_penalizacion: rational 27720/(n-1), fixed fraction 27720 x num/den, off/demo/legacy 0). It feeds
--     estado_competitivo_usuario: preguntas_validas_ranking, league, window, Nota media, median time (ranking eligibility, C-W4-1) and Evolucion. A question answered first in a
--     demo, a practice or the reto still enters it on its first ranking answer.
--   * NOTA MEDIA (FA54): over the LAST W ranking answers (W = window of the user's league, unchanged), contribution +1 correct, -1/(n-1) wrong (frozen per attempt; 0 when the
--     attempt had no penalty), and Nota = max(0, A - sum of the wrong weights) / N x 10, HALF-UP to 2 decimals, computed by private.official_nota_racional, the SAME function as the
--     attempt grade. A skipped question is not an answer: it never enters the window (as before), so the Nota media equals the grade of an attempt made of those answers alone.
--     Example: 3 right + 1 wrong in a 4-option penalised exam = (3 - 1/3) / 4 x 10 = 6.67.
--   * SIN CONTESTAR (app_stats_summary totales.noContestadas): distinct questions left SKIPPED / UNANSWERED in finished real-exam or practice attempts that are NOT in the counted
--     ledger (answering one later moves it to aciertos / fallos), so aciertos + fallos + noContestadas = distinct questions met. Before: the sum of total_sin_responder of every
--     finished non-demo attempt (repeats included, practice excluded). The reto has no finish: an unanswered reto question is not counted.
--   * LATENT BUG FIXED: intentos_examen.cuenta_para_ranking was ALWAYS false (NOT NULL DEFAULT false defeats the coalesce of the BEFORE INSERT trigger preparar_intento_examen),
--     so rule c) would have emptied every Nota media. The trigger now sets it to "first real attempt of an exam" (the column comment) and the backfill sets it on past V1
--     first real attempts (section 5.0).
--   * SHAPE ALIGNMENT (found by the rehearsal on the MAIN capture 2026-10-14, section 1b): MAIN's estadisticas_usuario_etiqueta lacks respondidas / correctas / falladas
--     and estado_competitivo_usuario has NOT NULL nota columns, so on that shape the 20261022101600 hook fails on a user's first answer. Guarded alignment (no-op on the
--     V1 shape of the gate / dbtest); the ROLLBACK undoes it only on the MAIN shape.
--   * app_record_attempt_answer (body of 20261022101600 with minimal edits): v_visto_en_demo removed; respuestas_intento.cuenta_para_ranking = "this answer entered the ranking
--     ledger and is the first answer" (CHECK respuestas_intento_ranking_requires_first_response); calls private.app_count_answer (real exam or practice) and private.app_rank_answer (ranking attempt). private.app_apply_first_answer_stats keeps its signature as
--     a wrapper (gate assertions / repair scripts) and is no longer called by the answer function. JSON shapes of every endpoint: UNCHANGED.
--
-- BACKFILL (in this transaction; idempotent; design in docs/ledger/2026/11/2026-11-14-f27-stats-fix.md):
--   0. intentos_examen.cuenta_para_ranking = true on V1 first real attempts of an exam (es_primer_intento_examen, not demo, frozen rule), actualizado_en untouched.
--   1. counted ledger, ADDED rows only (existing rows and counters are never lowered, so pre-V1 counters survive): for each (user, question) not yet in the ledger, the earliest of
--      (i) a real V1 exam first answer that C-W3-3 skipped (es_primer_intento_pregunta, frozen rule regla_anuladas not null = stats era like step 2, and the user had answered the
--      question in a demo or practice; a legacy answer was already in the pre-V1 counters, so it is never added twice), (ii) a practice answer,
--      (iii) a daily challenge answer; the counters (estadisticas_usuario, estadisticas_usuario_etiqueta) get the same deltas the hook would have applied.
--   2. ranking ledger, BUILT from respuestas_intento: per (user, question) the earliest answer of an attempt with cuenta_para_ranking, not demo, origen examen and a frozen rule
--      (regla_anuladas not null = V1 attempt, i.e. the stats era; legacy attempts never entered the Nota media), not annulled; time = the counted ledger time when it exists.
--   3. estado_competitivo_usuario REBUILT for every user with a row or a ranking answer (private.stats_recompute_competitivo, today's configuration). Effects: answers burned by a
--      demo now count; answers of re-attempts (cuenta_para_ranking false) leave the Nota media / league count; the Nota media is penalised for penalised exams; a user may drop
--      to a lower league if their valid-question count falls. The ranking positions are recomputed by the ranking job as always.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the 20261022101600 bodies of app_record_attempt_answer, app_apply_first_answer_stats and app_stats_summary and the
-- 20261022101000 body of preparar_intento_examen come back verbatim (the cuenta_para_ranking flags set by 5.0 stay: they are what the column comment says); the trigger, the helpers and the ranking ledger are dropped; origen is dropped and a null tiempo_ms becomes 0. Counter values written meanwhile (including the
-- backfill) are data and stay; the old hook recomputes the Nota media from the counted ledger on the user's next first answer.
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_respuestas_reto_diario_counts on public.respuestas_reto_diario;
-- drop function if exists private.tg_reto_answer_counts();
-- create or replace function public.preparar_intento_examen()
-- returns trigger
-- language plpgsql
-- set search_path to 'public'
-- as $$
-- declare
--     v_tipo_examen text;
--     v_numero int;
-- begin
--     select tipo_examen into v_tipo_examen from public.examenes where id = new.examen_id;
--     new.tipo_examen_snapshot = coalesce(new.tipo_examen_snapshot, v_tipo_examen);
--     if new.es_demo then
--         -- DEMO: never the first attempt, never counts for ranking, does not shift the numbering of the real attempts
--         new.intento_numero_usuario = coalesce(new.intento_numero_usuario, 0);
--         new.es_primer_intento_examen = false;
--         new.cuenta_para_ranking = false;
--         return new;
--     end if;
--     select count(*) + 1 into v_numero
--     from public.intentos_examen ie
--     where ie.usuario_id = new.usuario_id and ie.examen_id = new.examen_id and not ie.es_demo
--       and ie.id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);
--     new.intento_numero_usuario = coalesce(new.intento_numero_usuario, v_numero);
--     new.es_primer_intento_examen = (new.intento_numero_usuario = 1);
--     -- Client rule: second attempts are kept for progress but not for ranking.
--     new.cuenta_para_ranking = coalesce(new.cuenta_para_ranking, new.es_primer_intento_examen);
--     return new;
-- end;
-- $$;
-- create or replace function public.app_record_attempt_answer(
--   p_usuario_id uuid,
--   p_intento_id uuid,
--   p_pregunta_id uuid,
--   p_opcion_id uuid,
--   p_tiempo_respuesta_ms integer,
--   p_base_revision integer,
--   p_nivel_confianza integer
-- )
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_estado text;
--   v_examen_id uuid;
--   v_ranking boolean;
--   v_iniciado timestamp with time zone;
--   v_posicion integer;
--   v_revision_id uuid;
--   v_contenido jsonb;
--   v_solucion jsonb;
--   v_correcta_id uuid;
--   v_row public.respuestas_intento%rowtype;
--   v_exists boolean;
--   v_first boolean;
--   v_last_event timestamp with time zone;
--   v_gap_ms bigint;
--   v_ms integer;
--   v_clamped boolean;
--   v_meta jsonb;
--   v_revision integer;
--   v_respondidas integer;
--   v_constraint text;
--   v_es_correcta boolean;
--   v_opcion_correcta uuid;
--   v_es_demo boolean;
--   v_visto_en_demo boolean;
-- begin
--   -- ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES + STATS
--   if p_usuario_id is null or p_intento_id is null or p_pregunta_id is null then
--     return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
--   end if;
--
--   perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));
--
--   select ie.estado, ie.examen_id, ie.cuenta_para_ranking, ie.iniciado_en, ie.es_demo
--     into v_estado, v_examen_id, v_ranking, v_iniciado, v_es_demo
--   from public.intentos_examen ie
--   where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
--     and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
--   for update;
--   if not found then
--     return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
--   end if;
--   if v_estado <> 'en_progreso' then
--     return jsonb_build_object('outcome', 'ATTEMPT_NOT_IN_PROGRESS', 'estado', v_estado);
--   end if;
--
--   select pi.posicion, pi.revision_pregunta_id, r.contenido, r.solucion
--     into v_posicion, v_revision_id, v_contenido, v_solucion
--   from public.preguntas_intento pi
--   left join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id
--   where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id
--     and pi.rol is distinct from 'RESERVA_NO_USADA';  -- OFFICIAL_RULES C-O1: an unused reserve is not part of the open attempt
--   if not found then
--     return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');
--   end if;
--
--   if p_opcion_id is not null and not (
--        case when v_revision_id is not null
--             then exists (select 1 from jsonb_array_elements(v_contenido -> 'opciones') o where o ->> 'id' = p_opcion_id::text)
--             else exists (select 1 from public.opciones_pregunta op where op.id = p_opcion_id and op.pregunta_id = p_pregunta_id)
--        end) then
--     return jsonb_build_object('outcome', 'OPTION_NOT_IN_QUESTION');
--   end if;
--
--   select ri.* into v_row
--   from public.respuestas_intento ri
--   where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id
--   limit 1;
--   v_exists := found;
--   v_revision := case when v_exists then v_row.revision else 0 end;
--
--   if private.intento_pregunta_anulada(p_intento_id, v_examen_id, p_pregunta_id) then
--     select count(*) into v_respondidas
--     from public.respuestas_intento ri
--     where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
--       and not private.intento_pregunta_anulada(p_intento_id, v_examen_id, ri.pregunta_id);
--     return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
--                               'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
--   end if;
--
--   if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
--     select count(*) into v_respondidas
--     from public.respuestas_intento ri
--     where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
--       and not private.intento_pregunta_anulada(p_intento_id, v_examen_id, ri.pregunta_id);
--     return jsonb_build_object(
--       'outcome', 'OK',
--       'estado', case when v_row.opcion_seleccionada_id is null then 'SKIPPED' else 'ANSWERED' end,
--       'revision', v_revision,
--       'es_primera_respuesta', v_row.es_primer_intento_pregunta,
--       'respondidas', v_respondidas,
--       'es_correcta', case when v_row.opcion_seleccionada_id is not null then v_row.es_correcta end,
--       'opcion_correcta_id', case when v_row.opcion_seleccionada_id is not null then v_row.opcion_correcta_id end,
--       'noop', true);
--   end if;
--
--   if v_exists and v_row.opcion_seleccionada_id is not null then
--     return jsonb_build_object('outcome', 'ANSWER_LOCKED', 'current_revision', v_revision);
--   end if;
--
--   if coalesce(p_base_revision, -1) <> v_revision then
--     return jsonb_build_object('outcome', 'REVISION_CONFLICT', 'current_revision', v_revision);
--   end if;
--
--   if p_opcion_id is not null then
--     if v_revision_id is not null then
--       v_correcta_id := (v_solucion ->> 'opcion_correcta_id')::uuid;
--     else
--       select sp.opcion_correcta_id into v_correcta_id
--       from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
--     end if;
--     if v_correcta_id is null then
--       raise exception 'APP_RECORD_ANSWER_MISSING_SOLUTION';
--     end if;
--   end if;
--
--   select greatest(v_iniciado, coalesce(max(greatest(ri.respondida_en, ri.actualizada_en, ri.creada_en)), v_iniciado))
--     into v_last_event
--   from public.respuestas_intento ri where ri.intento_id = p_intento_id;
--   v_gap_ms := greatest(0, floor(extract(epoch from (clock_timestamp() - v_last_event)) * 1000))::bigint;
--   v_ms := least(greatest(coalesce(p_tiempo_respuesta_ms, 0), 0)::bigint, v_gap_ms, 2147483647)::integer;
--   v_clamped := coalesce(p_tiempo_respuesta_ms, 0) <> v_ms;
--   v_meta := jsonb_strip_nulls(jsonb_build_object(
--     'nivelConfianza', p_nivel_confianza,
--     'tiempoRecortado', case when v_clamped then true end));
--
--   -- DEMO (C-D4): a demo answer is never the first answer, and demo rows never consume it. A real first answer to a question whose correction the user already saw in a demo is stored
--   -- as the first answer but does not count for ranking.
--   v_first := p_opcion_id is not null and not v_es_demo and not exists (
--     select 1 from public.respuestas_intento prev
--     join public.intentos_examen pie on pie.id = prev.intento_id
--     where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id
--       and prev.opcion_seleccionada_id is not null and not pie.es_demo
--       and (not v_exists or prev.id <> v_row.id));
--   v_visto_en_demo := not v_es_demo and exists (
--     select 1 from public.respuestas_intento prev
--     join public.intentos_examen pie on pie.id = prev.intento_id
--     where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id and prev.opcion_seleccionada_id is not null and pie.es_demo);
--
--   if not v_exists then
--     begin
--       insert into public.respuestas_intento as r (
--         intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
--         posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
--         primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
--         tiempo_respuesta_segundos, metadatos
--       ) values (
--         p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
--         v_posicion, now(), coalesce(v_ranking, false) and v_first and not v_visto_en_demo, v_first,
--         p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
--         (v_ms / 1000), v_meta
--       )
--       returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
--         into v_revision, v_first, v_es_correcta, v_opcion_correcta;
--     exception when unique_violation then
--       get stacked diagnostics v_constraint = constraint_name;
--       if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
--         raise;
--       end if;
--       insert into public.respuestas_intento as r (
--         intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
--         posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
--         primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
--         tiempo_respuesta_segundos, metadatos
--       ) values (
--         p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
--         v_posicion, now(), false, false,
--         p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
--         (v_ms / 1000), v_meta
--       )
--       returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
--         into v_revision, v_first, v_es_correcta, v_opcion_correcta;
--     end;
--   else
--     begin
--       update public.respuestas_intento r
--          set opcion_seleccionada_id = p_opcion_id,
--              es_correcta = (p_opcion_id = v_correcta_id),
--              opcion_correcta_id = v_correcta_id,
--              revision = r.revision + 1,
--              actualizada_en = now(),
--              cambio_respuesta = true,
--              es_primer_intento_pregunta = v_first,
--              cuenta_para_ranking = coalesce(v_ranking, false) and v_first and not v_visto_en_demo,
--              tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
--              tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
--              metadatos = r.metadatos || v_meta
--        where r.id = v_row.id
--       returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
--         into v_revision, v_first, v_es_correcta, v_opcion_correcta;
--     exception when unique_violation then
--       get stacked diagnostics v_constraint = constraint_name;
--       if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
--         raise;
--       end if;
--       update public.respuestas_intento r
--          set opcion_seleccionada_id = p_opcion_id,
--              es_correcta = (p_opcion_id = v_correcta_id),
--              opcion_correcta_id = v_correcta_id,
--              revision = r.revision + 1,
--              actualizada_en = now(),
--              cambio_respuesta = true,
--              es_primer_intento_pregunta = false,
--              cuenta_para_ranking = false,
--              tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
--              tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
--              metadatos = r.metadatos || v_meta
--        where r.id = v_row.id
--       returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
--         into v_revision, v_first, v_es_correcta, v_opcion_correcta;
--     end;
--   end if;
--
--   -- STATS (C-S1): only when THIS call wrote the first answer of the user to the question (the guard row inside decides; a replay, a retry or a double click never counts twice).
--   -- A failure here fails the whole answer: no half state.
--   -- C-W3-3 decision: a real first answer to a question whose correction the user already saw in a demo is stored but does NOT feed stats, Nota media, league or ranking (same rule as cuenta_para_ranking).
--   if v_first is true and p_opcion_id is not null and not v_visto_en_demo then
--     perform private.app_apply_first_answer_stats(p_usuario_id, p_pregunta_id, v_es_correcta, v_ms, p_intento_id);
--   end if;
--
--   select count(*) into v_respondidas
--   from public.respuestas_intento ri
--   where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
--     and not private.intento_pregunta_anulada(p_intento_id, v_examen_id, ri.pregunta_id);
--
--   update public.intentos_examen
--      set total_respondidas = v_respondidas,
--          ultima_pregunta_posicion = v_posicion
--    where id = p_intento_id;
--
--   return jsonb_build_object(
--     'outcome', 'OK',
--     'estado', case when p_opcion_id is null then 'SKIPPED' else 'ANSWERED' end,
--     'revision', v_revision,
--     'es_primera_respuesta', coalesce(v_first, false),
--     'respondidas', v_respondidas,
--     'es_correcta', case when p_opcion_id is not null then v_es_correcta end,
--     'opcion_correcta_id', case when p_opcion_id is not null then v_opcion_correcta end,
--     'noop', false);
-- end;
-- $$;
--
-- revoke all on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) from public, anon, authenticated;
-- grant execute on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) to service_role;
-- create or replace function private.app_apply_first_answer_stats(p_usuario uuid, p_pregunta uuid, p_correcta boolean, p_tiempo_ms integer, p_intento uuid)
-- returns void
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_seq bigint;
--   est public.estado_competitivo_usuario%rowtype;
--   lg record;
--   v_validas integer;
--   v_ventana integer;
--   v_nota numeric;
--   v_avg_ms integer;
--   v_desde integer;
--   v_cada integer := private.config_int('evolucion_cada_preguntas', 20);
--   v_ref numeric;
--   v_evol numeric;
--   v_rev uuid;
--   c integer := case when p_correcta then 1 else 0 end;
--   v_t integer := least(greatest(coalesce(p_tiempo_ms, 0), 0), private.config_int('tiempo_max_respuesta_seg', 300) * 1000);
-- begin
--   -- C-W3-1 kill switch (Control, audited by the configuracion_v1 trigger): 0 skips ONLY the statistics part; the answer itself is never blocked. Answers made while it is off are not counted (no backfill: the guard row is the count).
--   if private.config_int('stats_activo', 1) = 0 then
--     return;
--   end if;
--   -- C-W3-2 the time is clamped here (the caller already passes the server-measured gap since the last event, never the raw client value)
--   insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms)
--   values (p_usuario, p_pregunta, p_correcta, v_t)
--   on conflict on constraint ux_primeras_respuestas_usuario_pregunta do nothing
--   returning seq into v_seq;
--   if v_seq is null then
--     return;  -- already counted: a replay, a retry or a double click never counts twice
--   end if;
--
--   insert into public.estadisticas_usuario as e (usuario_id, preguntas_respondidas, respuestas_correctas, porcentaje_acierto)
--   values (p_usuario, 1, c, c * 100)
--   on conflict (usuario_id) do update
--      set preguntas_respondidas = e.preguntas_respondidas + 1,
--          respuestas_correctas = e.respuestas_correctas + c,
--          porcentaje_acierto = round((e.respuestas_correctas + c) * 100.0 / (e.preguntas_respondidas + 1), 2),
--          actualizado_en = now();
--
--   select pi.revision_pregunta_id into v_rev from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = p_pregunta;
--   insert into public.estadisticas_usuario_etiqueta as t (usuario_id, etiqueta_id, respondidas, correctas, falladas, porcentaje_acierto, ultima_practica_en)
--   select p_usuario, x.id, 1, c, 1 - c, c * 100, now()
--     from (select distinct e.id
--             from public.etiquetas e
--            where e.id in (select (j ->> 'id')::uuid from public.revisiones_pregunta r, jsonb_array_elements(r.contenido -> 'etiquetas') j where r.id = v_rev)
--               or (v_rev is null and e.id in (select ep.etiqueta_id from public.etiquetas_pregunta ep where ep.pregunta_id = p_pregunta))) x
--   on conflict (usuario_id, etiqueta_id) do update
--      set respondidas = t.respondidas + 1,
--          correctas = t.correctas + excluded.correctas,
--          falladas = t.falladas + excluded.falladas,
--          porcentaje_acierto = round((t.correctas + excluded.correctas) * 100.0 / (t.respondidas + 1), 2),
--          ultima_practica_en = now(),
--          actualizado_en = now();
--
--   -- C-W3-4/7: the per-question global counters are NOT written here (a hot row at exam peaks); app_job_dificultad derives them from the guard table.
--
--   insert into public.estado_competitivo_usuario (usuario_id) values (p_usuario) on conflict (usuario_id) do nothing;
--   select * into est from public.estado_competitivo_usuario x where x.usuario_id = p_usuario for update;
--   v_validas := est.preguntas_validas_ranking + 1;
--   select l.id, l.ventana_calculo into lg
--     from public.ligas_nivel l where l.activa and l.preguntas_minimas <= v_validas order by l.preguntas_minimas desc, l.posicion desc limit 1;
--   v_ventana := coalesce(lg.ventana_calculo, private.config_int('ventana_nota_defecto', 50));
--   select round(avg(case when w.correcta then 10.0 else 0.0 end), 2), round((percentile_cont(0.5) within group (order by w.tiempo_ms))::numeric)::integer into v_nota, v_avg_ms
--     from (select pr.correcta, pr.tiempo_ms from public.primeras_respuestas_usuario pr where pr.usuario_id = p_usuario order by pr.seq desc limit v_ventana) w;
--   v_desde := est.preguntas_desde_ultima_evolucion + 1;
--   v_ref := est.nota_referencia_evolucion;
--   v_evol := est.evolucion_nota;
--   if v_desde >= v_cada then
--     v_evol := case when est.nota_referencia_evolucion is null then null else v_nota - est.nota_referencia_evolucion end;
--     v_ref := v_nota;
--     v_desde := 0;
--   end if;
--   update public.estado_competitivo_usuario
--      set preguntas_validas_ranking = v_validas,
--          liga_nivel_id = coalesce(lg.id, est.liga_nivel_id),
--          ventana_calculo = v_ventana,
--          nota_media_actual = v_nota,
--          ventana_tiempo_mediano_ms = v_avg_ms,
--          nota_referencia_evolucion = v_ref,
--          evolucion_nota = v_evol,
--          preguntas_desde_ultima_evolucion = v_desde,
--          actualizado_en = now()
--    where usuario_id = p_usuario;
-- end;
-- $$;
-- revoke all on function private.app_apply_first_answer_stats(uuid, uuid, boolean, integer, uuid) from public, anon, authenticated;
-- create or replace function public.app_stats_summary(p_usuario uuid)
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   est public.estado_competitivo_usuario%rowtype;
--   eu public.estadisticas_usuario%rowtype;
--   v_validas integer;
--   v_ventana integer;
--   v_liga jsonb;
--   v_sig jsonb;
--   v_no integer;
-- begin
--   if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
--     raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
--   end if;
--   select * into est from public.estado_competitivo_usuario x where x.usuario_id = p_usuario;
--   select * into eu from public.estadisticas_usuario x where x.usuario_id = p_usuario;
--   v_validas := coalesce(est.preguntas_validas_ranking, 0);
--   v_ventana := coalesce(est.ventana_calculo, private.config_int('ventana_nota_defecto', 50));
--   select jsonb_build_object('codigo', l.codigo, 'nombre', l.nombre, 'preguntasMinimas', l.preguntas_minimas) into v_liga
--     from public.ligas_nivel l where l.id = est.liga_nivel_id;
--   select jsonb_build_object('codigo', l.codigo, 'nombre', l.nombre, 'preguntasMinimas', l.preguntas_minimas) into v_sig
--     from public.ligas_nivel l
--    where l.activa and l.preguntas_minimas > v_validas
--    order by l.preguntas_minimas, l.posicion limit 1;
--   select coalesce(sum(ie.total_sin_responder), 0)::integer into v_no
--     from public.intentos_examen ie where ie.usuario_id = p_usuario and ie.estado = 'finalizado' and not ie.es_demo and ie.origen_intento = 'examen';
--   return jsonb_build_object(
--     'notaMedia', jsonb_build_object('valor', est.nota_media_actual, 'ventana', v_ventana, 'respondidas', least(v_validas, v_ventana), 'provisional', v_validas < v_ventana),
--     'evolucion', jsonb_build_object('valor', est.evolucion_nota, 'referencia', est.nota_referencia_evolucion, 'cada', private.config_int('evolucion_cada_preguntas', 20),
--                                     'progreso', coalesce(est.preguntas_desde_ultima_evolucion, 0)),
--     'racha', private.reto_streak_json(p_usuario, private.reto_today(clock_timestamp())),
--     'liga', case when v_liga is null then null else v_liga || jsonb_build_object('siguiente', v_sig) end,
--     'preguntasValidas', v_validas,
--     'totales', jsonb_build_object('aciertos', coalesce(eu.respuestas_correctas, 0), 'fallos', coalesce(eu.preguntas_respondidas, 0) - coalesce(eu.respuestas_correctas, 0), 'noContestadas', v_no));
-- end;
-- $$;
-- revoke all on function public.app_stats_summary(uuid) from public, anon, authenticated;
-- grant execute on function public.app_stats_summary(uuid) to service_role;
-- drop function if exists private.stats_recompute_competitivo(uuid);
-- drop function if exists private.stats_nota_ventana(uuid, integer, integer);
-- drop function if exists private.app_rank_answer(uuid, uuid, boolean, integer, integer, uuid);
-- drop function if exists private.app_count_answer(uuid, uuid, boolean, integer, uuid, text);
-- drop function if exists private.stats_penalizacion(uuid, uuid);
-- drop table if exists public.primeras_respuestas_ranking;
-- update public.primeras_respuestas_usuario set tiempo_ms = 0 where tiempo_ms is null;
-- alter table public.primeras_respuestas_usuario alter column tiempo_ms set not null;
-- alter table public.primeras_respuestas_usuario drop constraint if exists primeras_respuestas_usuario_origen_chk;
-- alter table public.primeras_respuestas_usuario drop column if exists origen;
-- -- shape alignment back (only on the MAIN shape: a column with a DEFAULT was NOT NULL there; the added columns exist next to the legacy ones only there)
-- do $$
-- declare
--   c text;
-- begin
--   foreach c in array array['nota_media_actual', 'nota_referencia_evolucion', 'evolucion_nota', 'ventana_calculo'] loop
--     if (select column_default from information_schema.columns where table_schema = 'public' and table_name = 'estado_competitivo_usuario' and column_name = c) is not null then
--       execute format('update public.estado_competitivo_usuario set %1$I = %2$s where %1$I is null', c, case when c = 'ventana_calculo' then '50' else '0' end);
--       execute format('alter table public.estado_competitivo_usuario alter column %I set not null', c);
--     end if;
--   end loop;
--   if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'estadisticas_usuario_etiqueta' and column_name = 'respuestas_falladas') then
--     alter table public.estadisticas_usuario_etiqueta drop column if exists respondidas, drop column if exists correctas, drop column if exists falladas;
--   end if;
-- end
-- $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'private_helper_client_executable:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('private.stats_penalizacion(uuid,uuid)'), ('private.app_count_answer(uuid,uuid,boolean,integer,uuid,text)'), ('private.app_rank_answer(uuid,uuid,boolean,integer,integer,uuid)'),
--                ('private.stats_nota_ventana(uuid,integer,integer)'), ('private.stats_recompute_competitivo(uuid)'), ('private.app_apply_first_answer_stats(uuid,uuid,boolean,integer,uuid)'),
--                ('private.tg_reto_answer_counts()'), ('public.app_stats_summary(uuid)'), ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)')) f(n)
-- union all
-- select 'ranking_ledger_open_to_clients',
--        (case when has_table_privilege('anon', 'public.primeras_respuestas_ranking', 'select') or has_table_privilege('authenticated', 'public.primeras_respuestas_ranking', 'select')
--                or has_table_privilege('authenticated', 'public.primeras_respuestas_ranking', 'insert') or has_table_privilege('service_role', 'public.primeras_respuestas_ranking', 'select')
--                or not (select c.relrowsecurity from pg_class c where c.oid = 'public.primeras_respuestas_ranking'::regclass) then 1 else 0 end)::bigint
-- union all
-- select 'answer_function_still_burns_or_skips_the_ledgers',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'::regprocedure) not like '%v_visto_en_demo%'
--                and (select p.prosrc from pg_proc p where p.oid = 'public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'::regprocedure) like '%private.app_count_answer(%'
--                and (select p.prosrc from pg_proc p where p.oid = 'public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'::regprocedure) like '%private.app_rank_answer(%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.app_rank_answer(uuid,uuid,boolean,integer,integer,uuid)'::regprocedure) like '%official_nota_racional%' then 0 else 1 end)::bigint
-- union all
-- select 'daily_challenge_answers_not_counted',
--        (case when exists (select 1 from pg_trigger t where t.tgrelid = 'public.respuestas_reto_diario'::regclass and t.tgname = 'trg_respuestas_reto_diario_counts' and not t.tgisinternal and t.tgenabled <> 'D')
--              then 0 else 1 end)::bigint
-- union all
-- select 'summary_unanswered_not_by_question',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'public.app_stats_summary(uuid)'::regprocedure) like '%estado_final in (''SKIPPED'', ''UNANSWERED'')%' then 0 else 1 end)::bigint
-- union all
-- select 'ranking_ledger_row_from_a_non_ranking_attempt',
--        (select count(*) from public.primeras_respuestas_ranking r join public.intentos_examen ie on ie.id = r.intento_id where not ie.cuenta_para_ranking or ie.es_demo)::bigint
-- union all
-- select 'stats_tables_not_in_the_v1_shape',
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'estadisticas_usuario_etiqueta' and column_name = 'respondidas')
--                and not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'estado_competitivo_usuario'
--                                 and column_name in ('nota_media_actual', 'nota_referencia_evolucion', 'evolucion_nota', 'ventana_calculo') and is_nullable = 'NO') then 0 else 1 end)::bigint
-- union all
-- select 'first_attempt_flag_still_hidden_by_the_default',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'public.preparar_intento_examen()'::regprocedure) not like '%coalesce(new.cuenta_para_ranking%' then 0 else 1 end)::bigint
-- union all
-- select 'v1_first_real_attempt_not_counting_for_ranking',
--        (select count(*) from public.intentos_examen ie
--          where not ie.cuenta_para_ranking and ie.es_primer_intento_examen and not ie.es_demo and ie.examen_id is not null and ie.origen_intento = 'examen' and ie.regla_anuladas is not null)::bigint
-- union all
-- select 'competitive_state_differs_from_the_ranking_ledger',
--        (select count(*) from public.estado_competitivo_usuario e
--          where e.preguntas_validas_ranking <> (select count(*) from public.primeras_respuestas_ranking r where r.usuario_id = e.usuario_id))::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- C1 (review 2026-11-14, re-review): ALL the locks of this migration, taken HERE, once, in a fixed order, at their final strength (no later escalation =
-- no lock-upgrade deadlock). ACCESS EXCLUSIVE on the tables this migration ALTERs (primeras_respuestas_usuario, estadisticas_usuario_etiqueta,
-- estado_competitivo_usuario); SHARE ROW EXCLUSIVE on the ones that get a trigger change (respuestas_reto_diario: new trigger; intentos_examen: disable /
-- enable of trg_intentos_examen_actualizado_en) or that the backfill reads / writes (respuestas_intento, estadisticas_usuario): no answer can land
-- between the backfill reads and the hook swap (it would be counted twice or never). lock_timeout above still bounds the wait.
lock table public.primeras_respuestas_usuario, public.estadisticas_usuario_etiqueta, public.estado_competitivo_usuario in access exclusive mode;
lock table public.respuestas_intento, public.respuestas_reto_diario, public.intentos_examen, public.estadisticas_usuario in share row exclusive mode;

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regclass('public.primeras_respuestas_usuario') is null or to_regprocedure('private.app_apply_first_answer_stats(uuid,uuid,boolean,integer,uuid)') is null
     or to_regprocedure('private.official_nota_racional(integer,bigint,integer)') is null or to_regprocedure('private.intento_pregunta_anulada(uuid,uuid,uuid)') is null
     or to_regclass('public.respuestas_reto_diario') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'es_practica')
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'penalizacion_racional') then
    raise exception using errcode = 'P0001', message = 'V1_STATS_FIX_PREFLIGHT', hint = 'apply 20261022101600 and 20261109100100 (F23) first';
  end if;
end
$$;

-- ---- 1. schema
-- the counted ledger says where the counted answer came from; a daily challenge answer has no server-measured time
alter table public.primeras_respuestas_usuario add column if not exists origen text not null default 'examen';
alter table public.primeras_respuestas_usuario drop constraint if exists primeras_respuestas_usuario_origen_chk;
alter table public.primeras_respuestas_usuario add constraint primeras_respuestas_usuario_origen_chk check (origen in ('examen', 'practica', 'reto'));
alter table public.primeras_respuestas_usuario alter column tiempo_ms drop not null;

-- 1b. SHAPE ALIGNMENT (found by the F27 rehearsal on the MAIN capture of 2026-10-14): MAIN's estadisticas_usuario_etiqueta has preguntas_respondidas /
-- respuestas_correctas / respuestas_falladas, while every V1 stats body (hook, app_stats_weaknesses) uses respondidas / correctas / falladas (the local overlay
-- main_shape_subset_extra_engagement.sql); and MAIN's estado_competitivo_usuario has nota_media_actual / nota_referencia_evolucion / evolucion_nota / ventana_calculo
-- NOT NULL, while the hook writes null (no reference yet). On that shape the V1 hook fails on the first answer. Guarded, so a database that already has the V1
-- shape (gate, dbtest, overlay) is untouched: the three columns are added (filled from the legacy ones) and NOT NULL is dropped from the four columns.
do $$
begin
  if not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'estadisticas_usuario_etiqueta' and column_name = 'respondidas') then
    alter table public.estadisticas_usuario_etiqueta add column respondidas integer not null default 0, add column correctas integer not null default 0, add column falladas integer not null default 0;
    if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'estadisticas_usuario_etiqueta' and column_name = 'respuestas_falladas') then
      update public.estadisticas_usuario_etiqueta set respondidas = preguntas_respondidas, correctas = respuestas_correctas, falladas = respuestas_falladas;
    end if;
  end if;
end
$$;
alter table public.estado_competitivo_usuario alter column nota_media_actual drop not null, alter column nota_referencia_evolucion drop not null,
  alter column evolucion_nota drop not null, alter column ventana_calculo drop not null;

-- the ranking ledger: the exactly-once guard AND the window source of the Nota media, league count and median time (closed to every client role, like the counted ledger)
create table if not exists public.primeras_respuestas_ranking (
  seq bigint generated always as identity primary key,
  usuario_id uuid not null references auth.users(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  intento_id uuid references public.intentos_examen(id) on delete set null,
  correcta boolean not null,
  penalizacion integer not null default 0,  -- 27720-scaled weight of THIS wrong answer (27720 / (n - 1), 27720 x num / den, or 0); always 0 for a correct one
  tiempo_ms integer not null default 0,
  respondida_en timestamptz not null default clock_timestamp(),
  constraint ux_primeras_respuestas_ranking_pregunta unique (usuario_id, pregunta_id),
  constraint primeras_respuestas_ranking_penalizacion_chk check (penalizacion >= 0 and penalizacion <= 27720 and (not correcta or penalizacion = 0))
);
create index if not exists ix_primeras_respuestas_ranking_seq on public.primeras_respuestas_ranking (usuario_id, seq desc);
create index if not exists ix_primeras_respuestas_ranking_intento on public.primeras_respuestas_ranking (intento_id);
alter table public.primeras_respuestas_ranking enable row level security;
revoke all on table public.primeras_respuestas_ranking from public, anon, authenticated, service_role;

-- ---- 2. helpers and the two hooks
-- the 27720-scaled weight of a WRONG answer to p_pregunta in p_intento, from the rule FROZEN at the attempt start (20261022101300): rational = 27720 / (n - 1) with n = options of the
-- question's frozen revision (2..13; fewer than 2 = 0; more than 13 = 13), fixed fraction = 27720 x num / den, no rule (demo / legacy) or penalty off = 0. Same numbers as app_finish_exam_attempt.
create or replace function private.stats_penalizacion(p_intento uuid, p_pregunta uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((
    select case
             when ie.regla_anuladas is null then 0
             when coalesce(ie.penalizacion_racional, false) then
               coalesce((select case when k.n >= 2 then 27720 / (least(k.n, 13) - 1) else 0 end
                           from (select coalesce(jsonb_array_length(r.contenido -> 'opciones'),
                                                 (select count(*)::integer from public.opciones_pregunta o where o.pregunta_id = p_pregunta)) as n
                                   from public.preguntas_intento pi left join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id
                                  where pi.intento_id = p_intento and pi.pregunta_id = p_pregunta
                                  limit 1) k), 0)
             else least(27720, round(27720.0 * coalesce(ie.penalizacion_num, 0) / greatest(coalesce(ie.penalizacion_den, 1), 1)))::integer
           end
      from public.intentos_examen ie where ie.id = p_intento), 0)
$$;
revoke all on function private.stats_penalizacion(uuid, uuid) from public, anon, authenticated;

-- aciertos / fallos / tags (C-S1 exactly once): the user's FIRST counted answer to a question, from a real exam, a practice or the daily challenge (origen). Never a demo (callers decide).
create or replace function private.app_count_answer(p_usuario uuid, p_pregunta uuid, p_correcta boolean, p_tiempo_ms integer, p_revision uuid, p_origen text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_seq bigint;
  c integer := case when p_correcta then 1 else 0 end;
  v_t integer := case when p_tiempo_ms is null then null else least(greatest(p_tiempo_ms, 0), private.config_int('tiempo_max_respuesta_seg', 300) * 1000) end;
begin
  -- C-W3-1 kill switch: 0 skips ONLY the statistics part (no backfill: the guard row is the count)
  if private.config_int('stats_activo', 1) = 0 or p_usuario is null or p_pregunta is null or p_correcta is null then
    return false;
  end if;
  -- a daily challenge answer has no server-measured time: null (the median of the difficulty job ignores it)
  insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms, origen)
  values (p_usuario, p_pregunta, p_correcta, v_t, coalesce(p_origen, 'examen'))
  on conflict on constraint ux_primeras_respuestas_usuario_pregunta do nothing
  returning seq into v_seq;
  if v_seq is null then
    return false;  -- already counted: a replay, a retry, a double click or a later answer to the same question never counts twice
  end if;

  insert into public.estadisticas_usuario as e (usuario_id, preguntas_respondidas, respuestas_correctas, porcentaje_acierto)
  values (p_usuario, 1, c, c * 100)
  on conflict (usuario_id) do update
     set preguntas_respondidas = e.preguntas_respondidas + 1,
         respuestas_correctas = e.respuestas_correctas + c,
         porcentaje_acierto = round((e.respuestas_correctas + c) * 100.0 / (e.preguntas_respondidas + 1), 2),
         actualizado_en = now();

  insert into public.estadisticas_usuario_etiqueta as t (usuario_id, etiqueta_id, respondidas, correctas, falladas, porcentaje_acierto, ultima_practica_en)
  select p_usuario, x.id, 1, c, 1 - c, c * 100, now()
    from (select distinct e.id
            from public.etiquetas e
           where e.id in (select (j ->> 'id')::uuid from public.revisiones_pregunta r, jsonb_array_elements(r.contenido -> 'etiquetas') j where r.id = p_revision)
              or (p_revision is null and e.id in (select ep.etiqueta_id from public.etiquetas_pregunta ep where ep.pregunta_id = p_pregunta))) x
  on conflict (usuario_id, etiqueta_id) do update
     set respondidas = t.respondidas + 1,
         correctas = t.correctas + excluded.correctas,
         falladas = t.falladas + excluded.falladas,
         porcentaje_acierto = round((t.correctas + excluded.correctas) * 100.0 / (t.respondidas + 1), 2),
         ultima_practica_en = now(),
         actualizado_en = now();
  return true;
end;
$$;
revoke all on function private.app_count_answer(uuid, uuid, boolean, integer, uuid, text) from public, anon, authenticated;

-- Nota media, league, ranking (exactly once per user and question): only answers of attempts that count for ranking. The Nota media of a window is the SAME formula as the attempt
-- grade: official_nota_racional(A, sum of the frozen 27720-scaled weights of the wrong answers, answered questions of the window) = max(0, A - sum E/(n-1)) / N x 10, HALF-UP to 2 decimals.
create or replace function private.app_rank_answer(p_usuario uuid, p_pregunta uuid, p_correcta boolean, p_penalizacion integer, p_tiempo_ms integer, p_intento uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_seq bigint;
  est public.estado_competitivo_usuario%rowtype;
  lg record;
  v_validas integer;
  v_ventana integer;
  v_nota numeric;
  v_med_ms integer;
  v_desde integer;
  v_cada integer := private.config_int('evolucion_cada_preguntas', 20);
  v_ref numeric;
  v_evol numeric;
  v_t integer := least(greatest(coalesce(p_tiempo_ms, 0), 0), private.config_int('tiempo_max_respuesta_seg', 300) * 1000);
begin
  if private.config_int('stats_activo', 1) = 0 or p_usuario is null or p_pregunta is null or p_correcta is null then
    return false;
  end if;
  insert into public.primeras_respuestas_ranking (usuario_id, pregunta_id, intento_id, correcta, penalizacion, tiempo_ms)
  values (p_usuario, p_pregunta, p_intento, p_correcta, case when p_correcta then 0 else least(greatest(coalesce(p_penalizacion, 0), 0), 27720) end, v_t)
  on conflict on constraint ux_primeras_respuestas_ranking_pregunta do nothing
  returning seq into v_seq;
  if v_seq is null then
    return false;
  end if;

  insert into public.estado_competitivo_usuario (usuario_id) values (p_usuario) on conflict (usuario_id) do nothing;
  select * into est from public.estado_competitivo_usuario x where x.usuario_id = p_usuario for update;
  v_validas := est.preguntas_validas_ranking + 1;
  select l.id, l.ventana_calculo into lg
    from public.ligas_nivel l where l.activa and l.preguntas_minimas <= v_validas order by l.preguntas_minimas desc, l.posicion desc limit 1;
  v_ventana := coalesce(lg.ventana_calculo, private.config_int('ventana_nota_defecto', 50));
  select private.official_nota_racional((count(*) filter (where w.correcta))::integer, coalesce(sum(w.penalizacion), 0)::bigint, count(*)::integer),
         round((percentile_cont(0.5) within group (order by w.tiempo_ms))::numeric)::integer
    into v_nota, v_med_ms
    from (select x.correcta, x.penalizacion, x.tiempo_ms from public.primeras_respuestas_ranking x where x.usuario_id = p_usuario order by x.seq desc limit v_ventana) w;
  v_desde := est.preguntas_desde_ultima_evolucion + 1;
  v_ref := est.nota_referencia_evolucion;
  v_evol := est.evolucion_nota;
  if v_desde >= v_cada then
    v_evol := case when est.nota_referencia_evolucion is null then null else v_nota - est.nota_referencia_evolucion end;
    v_ref := v_nota;
    v_desde := 0;
  end if;
  update public.estado_competitivo_usuario
     set preguntas_validas_ranking = v_validas,
         liga_nivel_id = coalesce(lg.id, est.liga_nivel_id),
         ventana_calculo = v_ventana,
         nota_media_actual = v_nota,
         ventana_tiempo_mediano_ms = v_med_ms,
         nota_referencia_evolucion = v_ref,
         evolucion_nota = v_evol,
         preguntas_desde_ultima_evolucion = v_desde,
         actualizado_en = now()
   where usuario_id = p_usuario;
  return true;
end;
$$;
revoke all on function private.app_rank_answer(uuid, uuid, boolean, integer, integer, uuid) from public, anon, authenticated;

-- the Nota media of the window of p_ventana ranking answers ending at the p_hasta-th one (1-based, ledger order); same formula as app_rank_answer
create or replace function private.stats_nota_ventana(p_usuario uuid, p_hasta integer, p_ventana integer)
returns numeric
language sql
stable
security definer
set search_path = ''
as $$
  select private.official_nota_racional((count(*) filter (where w.correcta))::integer, coalesce(sum(w.penalizacion), 0)::bigint, count(*)::integer)
    from (select x.correcta, x.penalizacion, row_number() over (order by x.seq) as rn from public.primeras_respuestas_ranking x where x.usuario_id = p_usuario) w
   where w.rn > p_hasta - p_ventana and w.rn <= p_hasta
$$;
revoke all on function private.stats_nota_ventana(uuid, integer, integer) from public, anon, authenticated;

-- rebuilds estado_competitivo_usuario of ONE user from the ranking ledger: what app_rank_answer writes after the user's last answer, replayed with TODAY's configuration (league
-- windows, evolucion_cada_preguntas): validas = ledger rows, league and window of that count, Nota media and median time of the last window, reference = the Nota media at the last
-- multiple of cada, evolution = reference minus the Nota media at the previous multiple (null before the second one). Used by the backfill below; service-only, no endpoint.
create or replace function private.stats_recompute_competitivo(p_usuario uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_n integer;
  lg record;
  v_ventana integer;
  v_cada integer := greatest(private.config_int('evolucion_cada_preguntas', 20), 1);
  v_def integer := private.config_int('ventana_nota_defecto', 50);
  v_cp integer;
  v_nota numeric;
  v_med_ms integer;
  v_ref numeric;
  v_evol numeric;
begin
  select count(*)::integer into v_n from public.primeras_respuestas_ranking x where x.usuario_id = p_usuario;
  if v_n = 0 and not exists (select 1 from public.estado_competitivo_usuario e where e.usuario_id = p_usuario) then
    return;
  end if;
  select l.id, l.ventana_calculo into lg
    from public.ligas_nivel l where l.activa and l.preguntas_minimas <= v_n order by l.preguntas_minimas desc, l.posicion desc limit 1;
  v_ventana := coalesce(lg.ventana_calculo, v_def);
  if v_n > 0 then
    v_nota := private.stats_nota_ventana(p_usuario, v_n, v_ventana);
    select round((percentile_cont(0.5) within group (order by w.tiempo_ms))::numeric)::integer into v_med_ms
      from (select x.tiempo_ms from public.primeras_respuestas_ranking x where x.usuario_id = p_usuario order by x.seq desc limit v_ventana) w;
  end if;
  v_cp := (v_n / v_cada) * v_cada;
  if v_cp >= v_cada then
    v_ref := private.stats_nota_ventana(p_usuario, v_cp,
               coalesce((select l.ventana_calculo from public.ligas_nivel l where l.activa and l.preguntas_minimas <= v_cp order by l.preguntas_minimas desc, l.posicion desc limit 1), v_def));
  end if;
  if v_cp >= 2 * v_cada then
    v_evol := v_ref - private.stats_nota_ventana(p_usuario, v_cp - v_cada,
               coalesce((select l.ventana_calculo from public.ligas_nivel l where l.activa and l.preguntas_minimas <= v_cp - v_cada order by l.preguntas_minimas desc, l.posicion desc limit 1), v_def));
  end if;
  insert into public.estado_competitivo_usuario (usuario_id) values (p_usuario) on conflict (usuario_id) do nothing;
  update public.estado_competitivo_usuario
     set preguntas_validas_ranking = v_n,
         liga_nivel_id = coalesce(lg.id, liga_nivel_id),
         ventana_calculo = v_ventana,
         nota_media_actual = v_nota,
         ventana_tiempo_mediano_ms = v_med_ms,
         nota_referencia_evolucion = v_ref,
         evolucion_nota = v_evol,
         preguntas_desde_ultima_evolucion = v_n - v_cp,
         actualizado_en = now()
   where usuario_id = p_usuario;
end;
$$;
revoke all on function private.stats_recompute_competitivo(uuid) from public, anon, authenticated;

-- the 20261022101600 entry point keeps its signature (gate assertions and repair scripts call it): a REAL exam first answer = counted ledger + (when the attempt counts for ranking,
-- or no attempt is given) the ranking ledger with the attempt's frozen penalty. app_record_attempt_answer no longer calls it.
create or replace function private.app_apply_first_answer_stats(p_usuario uuid, p_pregunta uuid, p_correcta boolean, p_tiempo_ms integer, p_intento uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_rev uuid;
begin
  select pi.revision_pregunta_id into v_rev from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = p_pregunta;
  perform private.app_count_answer(p_usuario, p_pregunta, p_correcta, coalesce(p_tiempo_ms, 0), v_rev, 'examen');
  if p_intento is null or exists (select 1 from public.intentos_examen ie where ie.id = p_intento and ie.cuenta_para_ranking and not ie.es_demo) then
    perform private.app_rank_answer(p_usuario, p_pregunta, p_correcta, case when p_intento is null then 0 else private.stats_penalizacion(p_intento, p_pregunta) end, p_tiempo_ms, p_intento);
  end if;
end;
$$;
revoke all on function private.app_apply_first_answer_stats(uuid, uuid, boolean, integer, uuid) from public, anon, authenticated;

-- the daily challenge answer counts for aciertos / fallos / tags (owner: "Si, suma, sin ranking"); never for the Nota media, league or ranking
create or replace function private.tg_reto_answer_counts()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.app_count_answer(new.usuario_id, new.pregunta_id, new.es_correcta, null, null, 'reto');
  return null;
end;
$$;
revoke all on function private.tg_reto_answer_counts() from public, anon, authenticated;
drop trigger if exists trg_respuestas_reto_diario_counts on public.respuestas_reto_diario;
create trigger trg_respuestas_reto_diario_counts after insert on public.respuestas_reto_diario
  for each row execute function private.tg_reto_answer_counts();

-- ---- 2b. LATENT BUG (found by F27): intentos_examen.cuenta_para_ranking is NOT NULL DEFAULT false, so the default is already in NEW when this BEFORE INSERT trigger runs and
-- coalesce(new.cuenta_para_ranking, new.es_primer_intento_examen) always kept false: no attempt has ever counted for ranking (the old hook ignored the flag, so nothing showed it).
-- The column comment is the rule: true ONLY on the user's first valid attempt of the exam. No writer sets the flag explicitly (app_start_exam_attempt and the practice insert omit it).
create or replace function public.preparar_intento_examen()
returns trigger
language plpgsql
set search_path to 'public'
as $$
declare
    v_tipo_examen text;
    v_numero int;
begin
    select tipo_examen into v_tipo_examen from public.examenes where id = new.examen_id;
    new.tipo_examen_snapshot = coalesce(new.tipo_examen_snapshot, v_tipo_examen);
    if new.es_demo then
        -- DEMO: never the first attempt, never counts for ranking, does not shift the numbering of the real attempts
        new.intento_numero_usuario = coalesce(new.intento_numero_usuario, 0);
        new.es_primer_intento_examen = false;
        new.cuenta_para_ranking = false;
        return new;
    end if;
    select count(*) + 1 into v_numero
    from public.intentos_examen ie
    where ie.usuario_id = new.usuario_id and ie.examen_id = new.examen_id and not ie.es_demo
      and ie.id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);
    new.intento_numero_usuario = coalesce(new.intento_numero_usuario, v_numero);
    new.es_primer_intento_examen = (new.intento_numero_usuario = 1);
    -- Client rule: second attempts are kept for progress but not for ranking. F27: the first real attempt of an EXAM counts (the column default no longer hides it).
    new.cuenta_para_ranking = new.es_primer_intento_examen and new.examen_id is not null and coalesce(new.origen_intento, 'examen') = 'examen';
    return new;
end;
$$;

-- ---- 3. the answer function (20261022101600 body; edits marked F27)
create or replace function public.app_record_attempt_answer(
  p_usuario_id uuid,
  p_intento_id uuid,
  p_pregunta_id uuid,
  p_opcion_id uuid,
  p_tiempo_respuesta_ms integer,
  p_base_revision integer,
  p_nivel_confianza integer
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_ranking boolean;
  v_iniciado timestamp with time zone;
  v_posicion integer;
  v_revision_id uuid;
  v_contenido jsonb;
  v_solucion jsonb;
  v_correcta_id uuid;
  v_row public.respuestas_intento%rowtype;
  v_exists boolean;
  v_first boolean;
  v_last_event timestamp with time zone;
  v_gap_ms bigint;
  v_ms integer;
  v_clamped boolean;
  v_meta jsonb;
  v_revision integer;
  v_respondidas integer;
  v_constraint text;
  v_es_correcta boolean;
  v_opcion_correcta uuid;
  v_es_demo boolean;
  v_es_practica boolean;
  v_cuenta_rk boolean;
begin
  -- ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES + STATS + F27 (FA54)
  if p_usuario_id is null or p_intento_id is null or p_pregunta_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.estado, ie.examen_id, ie.cuenta_para_ranking, ie.iniciado_en, ie.es_demo, ie.es_practica
    into v_estado, v_examen_id, v_ranking, v_iniciado, v_es_demo, v_es_practica
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_IN_PROGRESS', 'estado', v_estado);
  end if;

  select pi.posicion, pi.revision_pregunta_id, r.contenido, r.solucion
    into v_posicion, v_revision_id, v_contenido, v_solucion
  from public.preguntas_intento pi
  left join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id
  where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id
    and pi.rol is distinct from 'RESERVA_NO_USADA';  -- OFFICIAL_RULES C-O1: an unused reserve is not part of the open attempt
  if not found then
    return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');
  end if;

  if p_opcion_id is not null and not (
       case when v_revision_id is not null
            then exists (select 1 from jsonb_array_elements(v_contenido -> 'opciones') o where o ->> 'id' = p_opcion_id::text)
            else exists (select 1 from public.opciones_pregunta op where op.id = p_opcion_id and op.pregunta_id = p_pregunta_id)
       end) then
    return jsonb_build_object('outcome', 'OPTION_NOT_IN_QUESTION');
  end if;

  select ri.* into v_row
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id
  limit 1;
  v_exists := found;
  v_revision := case when v_exists then v_row.revision else 0 end;

  if private.intento_pregunta_anulada(p_intento_id, v_examen_id, p_pregunta_id) then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not private.intento_pregunta_anulada(p_intento_id, v_examen_id, ri.pregunta_id);
    return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
                              'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not private.intento_pregunta_anulada(p_intento_id, v_examen_id, ri.pregunta_id);
    return jsonb_build_object(
      'outcome', 'OK',
      'estado', case when v_row.opcion_seleccionada_id is null then 'SKIPPED' else 'ANSWERED' end,
      'revision', v_revision,
      'es_primera_respuesta', v_row.es_primer_intento_pregunta,
      'respondidas', v_respondidas,
      'es_correcta', case when v_row.opcion_seleccionada_id is not null then v_row.es_correcta end,
      'opcion_correcta_id', case when v_row.opcion_seleccionada_id is not null then v_row.opcion_correcta_id end,
      'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not null then
    return jsonb_build_object('outcome', 'ANSWER_LOCKED', 'current_revision', v_revision);
  end if;

  if coalesce(p_base_revision, -1) <> v_revision then
    return jsonb_build_object('outcome', 'REVISION_CONFLICT', 'current_revision', v_revision);
  end if;

  if p_opcion_id is not null then
    if v_revision_id is not null then
      v_correcta_id := (v_solucion ->> 'opcion_correcta_id')::uuid;
    else
      select sp.opcion_correcta_id into v_correcta_id
      from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
    end if;
    if v_correcta_id is null then
      raise exception 'APP_RECORD_ANSWER_MISSING_SOLUTION';
    end if;
  end if;

  select greatest(v_iniciado, coalesce(max(greatest(ri.respondida_en, ri.actualizada_en, ri.creada_en)), v_iniciado))
    into v_last_event
  from public.respuestas_intento ri where ri.intento_id = p_intento_id;
  v_gap_ms := greatest(0, floor(extract(epoch from (clock_timestamp() - v_last_event)) * 1000))::bigint;
  v_ms := least(greatest(coalesce(p_tiempo_respuesta_ms, 0), 0)::bigint, v_gap_ms, 2147483647)::integer;
  v_clamped := coalesce(p_tiempo_respuesta_ms, 0) <> v_ms;
  v_meta := jsonb_strip_nulls(jsonb_build_object(
    'nivelConfianza', p_nivel_confianza,
    'tiempoRecortado', case when v_clamped then true end));

  -- DEMO (C-D4): a demo (or practice) answer is never the first answer, and demo rows never consume it.
  -- F27 (FA54, owner 2026-11-14): seeing a question in a demo or a practice no longer burns it (C-W3-3 retired). The answer counts for the Nota media, league and ranking when the
  -- ATTEMPT counts for ranking (cuenta_para_ranking: first real attempt of the exam) and the user has no ranking answer to the question yet (private ledger primeras_respuestas_ranking).
  v_first := p_opcion_id is not null and not v_es_demo and not exists (
    select 1 from public.respuestas_intento prev
    join public.intentos_examen pie on pie.id = prev.intento_id
    where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id
      and prev.opcion_seleccionada_id is not null and not pie.es_demo
      and (not v_exists or prev.id <> v_row.id));
  v_cuenta_rk := p_opcion_id is not null and coalesce(v_ranking, false) and not v_es_demo and private.config_int('stats_activo', 1) <> 0
                 and not exists (select 1 from public.primeras_respuestas_ranking x where x.usuario_id = p_usuario_id and x.pregunta_id = p_pregunta_id);

  if not v_exists then
    begin
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), v_cuenta_rk and v_first, v_first,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), false, false,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    end;
  else
    begin
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = v_first,
             cuenta_para_ranking = v_cuenta_rk and v_first,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = false,
             cuenta_para_ranking = false,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta, r.es_correcta, r.opcion_correcta_id
        into v_revision, v_first, v_es_correcta, v_opcion_correcta;
    end;
  end if;

  -- STATS (C-S1 + F27/FA54): two exactly-once ledgers, each guard row decides (a replay, a retry or a double click never counts twice). A failure here fails the whole answer: no half state.
  --   * aciertos / fallos / tags: the user's first counted answer to the question in a real exam OR a practice (the daily challenge counts through its own trigger); never a demo.
  --   * Nota media, league, ranking: only when the attempt counts for ranking, with the attempt's FROZEN penalty (PD = A - E/(n-1), or the fixed fraction, or 0 when off).
  if p_opcion_id is not null and (not v_es_demo or coalesce(v_es_practica, false)) then
    perform private.app_count_answer(p_usuario_id, p_pregunta_id, v_es_correcta, v_ms, v_revision_id, case when coalesce(v_es_practica, false) then 'practica' else 'examen' end);
  end if;
  if v_cuenta_rk then
    perform private.app_rank_answer(p_usuario_id, p_pregunta_id, v_es_correcta, private.stats_penalizacion(p_intento_id, p_pregunta_id), v_ms, p_intento_id);
  end if;

  select count(*) into v_respondidas
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
    and not private.intento_pregunta_anulada(p_intento_id, v_examen_id, ri.pregunta_id);

  update public.intentos_examen
     set total_respondidas = v_respondidas,
         ultima_pregunta_posicion = v_posicion
   where id = p_intento_id;

  return jsonb_build_object(
    'outcome', 'OK',
    'estado', case when p_opcion_id is null then 'SKIPPED' else 'ANSWERED' end,
    'revision', v_revision,
    'es_primera_respuesta', coalesce(v_first, false),
    'respondidas', v_respondidas,
    'es_correcta', case when p_opcion_id is not null then v_es_correcta end,
    'opcion_correcta_id', case when p_opcion_id is not null then v_opcion_correcta end,
    'noop', false);
end;
$$;

revoke all on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) from public, anon, authenticated;
grant execute on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) to service_role;

-- ---- 4. the summary: noContestadas by question (same criterion as aciertos / fallos); everything else unchanged
create or replace function public.app_stats_summary(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  est public.estado_competitivo_usuario%rowtype;
  eu public.estadisticas_usuario%rowtype;
  v_validas integer;
  v_ventana integer;
  v_liga jsonb;
  v_sig jsonb;
  v_no integer;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select * into est from public.estado_competitivo_usuario x where x.usuario_id = p_usuario;
  select * into eu from public.estadisticas_usuario x where x.usuario_id = p_usuario;
  v_validas := coalesce(est.preguntas_validas_ranking, 0);
  v_ventana := coalesce(est.ventana_calculo, private.config_int('ventana_nota_defecto', 50));
  select jsonb_build_object('codigo', l.codigo, 'nombre', l.nombre, 'preguntasMinimas', l.preguntas_minimas) into v_liga
    from public.ligas_nivel l where l.id = est.liga_nivel_id;
  select jsonb_build_object('codigo', l.codigo, 'nombre', l.nombre, 'preguntasMinimas', l.preguntas_minimas) into v_sig
    from public.ligas_nivel l
   where l.activa and l.preguntas_minimas > v_validas
   order by l.preguntas_minimas, l.posicion limit 1;
  -- F27 (FA54): same criterion as aciertos / fallos: distinct questions left blank in finished real-exam or practice attempts that the user never answered in a counted way
  select count(distinct pi.pregunta_id)::integer into v_no
    from public.intentos_examen ie
    join public.preguntas_intento pi on pi.intento_id = ie.id
   where ie.usuario_id = p_usuario and ie.estado = 'finalizado' and ie.origen_intento = 'examen' and (not ie.es_demo or ie.es_practica)
     and pi.estado_final in ('SKIPPED', 'UNANSWERED')
     and not exists (select 1 from public.primeras_respuestas_usuario x where x.usuario_id = p_usuario and x.pregunta_id = pi.pregunta_id);
  return jsonb_build_object(
    'notaMedia', jsonb_build_object('valor', est.nota_media_actual, 'ventana', v_ventana, 'respondidas', least(v_validas, v_ventana), 'provisional', v_validas < v_ventana),
    'evolucion', jsonb_build_object('valor', est.evolucion_nota, 'referencia', est.nota_referencia_evolucion, 'cada', private.config_int('evolucion_cada_preguntas', 20),
                                    'progreso', coalesce(est.preguntas_desde_ultima_evolucion, 0)),
    'racha', private.reto_streak_json(p_usuario, private.reto_today(clock_timestamp())),
    'liga', case when v_liga is null then null else v_liga || jsonb_build_object('siguiente', v_sig) end,
    'preguntasValidas', v_validas,
    'totales', jsonb_build_object('aciertos', coalesce(eu.respuestas_correctas, 0), 'fallos', coalesce(eu.preguntas_respondidas, 0) - coalesce(eu.respuestas_correctas, 0), 'noContestadas', v_no));
end;
$$;
revoke all on function public.app_stats_summary(uuid) from public, anon, authenticated;
grant execute on function public.app_stats_summary(uuid) to service_role;

-- ---- 5. BACKFILL (idempotent: every step skips what is already there)
-- 5.0 the attempt flag (latent bug, section 2b): V1 first real attempts of an exam (frozen rule = regla_anuladas not null) count for ranking. actualizado_en is not bumped.
-- The per-answer respuestas_intento.cuenta_para_ranking of past answers is not rewritten (no reader; new answers set it).
do $$
begin
  if exists (select 1 from pg_trigger t where t.tgrelid = 'public.intentos_examen'::regclass and t.tgname = 'trg_intentos_examen_actualizado_en') then
    alter table public.intentos_examen disable trigger trg_intentos_examen_actualizado_en;
  end if;
  update public.intentos_examen set cuenta_para_ranking = true
   where not cuenta_para_ranking and es_primer_intento_examen and not es_demo and examen_id is not null and origen_intento = 'examen' and regla_anuladas is not null;
  if exists (select 1 from pg_trigger t where t.tgrelid = 'public.intentos_examen'::regclass and t.tgname = 'trg_intentos_examen_actualizado_en') then
    alter table public.intentos_examen enable trigger trg_intentos_examen_actualizado_en;
  end if;
end
$$;

-- 5.1 counted ledger: ADD the first answers the old rules left out (C-W3-3 burned by a demo / practice; practice answers; daily challenge answers). Never lowers a counter.
create temp table f27_nuevas on commit drop as
select distinct on (c.usuario_id, c.pregunta_id) c.usuario_id, c.pregunta_id, c.correcta, c.tiempo_ms, c.en, c.rev, c.origen
  from (select ri.usuario_id, ri.pregunta_id, ri.es_correcta as correcta,
               least(greatest(coalesce(ri.tiempo_respuesta_ms, 0), 0), private.config_int('tiempo_max_respuesta_seg', 300) * 1000)::integer as tiempo_ms,
               coalesce(ri.actualizada_en, ri.respondida_en) as en, pi.revision_pregunta_id as rev,
               case when ie.es_practica then 'practica' else 'examen' end as origen
          from public.respuestas_intento ri
          join public.intentos_examen ie on ie.id = ri.intento_id and ie.origen_intento = 'examen'
          left join public.preguntas_intento pi on pi.intento_id = ri.intento_id and pi.pregunta_id = ri.pregunta_id
         where ri.opcion_seleccionada_id is not null and ri.es_correcta is not null
           and not private.intento_pregunta_anulada(ri.intento_id, ie.examen_id, ri.pregunta_id)
           and (ie.es_practica
                or (not ie.es_demo and ri.es_primer_intento_pregunta and ie.regla_anuladas is not null
                    and exists (select 1 from public.respuestas_intento d join public.intentos_examen de on de.id = d.intento_id and de.es_demo
                                 where d.usuario_id = ri.usuario_id and d.pregunta_id = ri.pregunta_id and d.opcion_seleccionada_id is not null)))
        union all
        select rr.usuario_id, rr.pregunta_id, rr.es_correcta, null::integer, rr.respondida_en, null::uuid, 'reto'
          from public.respuestas_reto_diario rr
         where rr.es_correcta is not null) c
 where not exists (select 1 from public.primeras_respuestas_usuario p where p.usuario_id = c.usuario_id and p.pregunta_id = c.pregunta_id)
   and exists (select 1 from public.preguntas q where q.id = c.pregunta_id)
   and exists (select 1 from auth.users u where u.id = c.usuario_id)
 order by c.usuario_id, c.pregunta_id, c.en, c.origen;

insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms, respondida_en, origen)
select n.usuario_id, n.pregunta_id, n.correcta, n.tiempo_ms, n.en, n.origen from f27_nuevas n order by n.en, n.usuario_id, n.pregunta_id;

insert into public.estadisticas_usuario as e (usuario_id, preguntas_respondidas, respuestas_correctas, porcentaje_acierto)
select n.usuario_id, count(*)::integer, (count(*) filter (where n.correcta))::integer, round((count(*) filter (where n.correcta)) * 100.0 / count(*), 2)
  from f27_nuevas n group by n.usuario_id
on conflict (usuario_id) do update
   set preguntas_respondidas = e.preguntas_respondidas + excluded.preguntas_respondidas,
       respuestas_correctas = e.respuestas_correctas + excluded.respuestas_correctas,
       porcentaje_acierto = round((e.respuestas_correctas + excluded.respuestas_correctas) * 100.0 / (e.preguntas_respondidas + excluded.preguntas_respondidas), 2),
       actualizado_en = now();

insert into public.estadisticas_usuario_etiqueta as t (usuario_id, etiqueta_id, respondidas, correctas, falladas, porcentaje_acierto, ultima_practica_en)
select k.usuario_id, k.etiqueta_id, count(*)::integer, (count(*) filter (where k.correcta))::integer, (count(*) filter (where not k.correcta))::integer,
       round((count(*) filter (where k.correcta)) * 100.0 / count(*), 2), now()
  from (select n.usuario_id, n.correcta, x.id as etiqueta_id
          from f27_nuevas n
          cross join lateral (select distinct e.id
                                from public.etiquetas e
                               where e.id in (select (j ->> 'id')::uuid from public.revisiones_pregunta r, jsonb_array_elements(r.contenido -> 'etiquetas') j where r.id = n.rev)
                                  or (n.rev is null and e.id in (select ep.etiqueta_id from public.etiquetas_pregunta ep where ep.pregunta_id = n.pregunta_id))) x) k
 group by k.usuario_id, k.etiqueta_id
on conflict (usuario_id, etiqueta_id) do update
   set respondidas = t.respondidas + excluded.respondidas,
       correctas = t.correctas + excluded.correctas,
       falladas = t.falladas + excluded.falladas,
       porcentaje_acierto = round((t.correctas + excluded.correctas) * 100.0 / (t.respondidas + excluded.respondidas), 2),
       ultima_practica_en = now(),
       actualizado_en = now();

-- 5.2 ranking ledger: BUILT from the answers of V1 ranking attempts (frozen rule = regla_anuladas not null), earliest per (user, question), in time order
insert into public.primeras_respuestas_ranking (usuario_id, pregunta_id, intento_id, correcta, penalizacion, tiempo_ms, respondida_en)
select b.usuario_id, b.pregunta_id, b.intento_id, b.correcta, b.pen, b.t, b.en
  from (select distinct on (ri.usuario_id, ri.pregunta_id) ri.usuario_id, ri.pregunta_id, ri.intento_id, ri.es_correcta as correcta,
               case when ri.es_correcta then 0 else least(private.stats_penalizacion(ri.intento_id, ri.pregunta_id), 27720) end as pen,
               coalesce((select p.tiempo_ms from public.primeras_respuestas_usuario p where p.usuario_id = ri.usuario_id and p.pregunta_id = ri.pregunta_id and p.origen = 'examen'),
                        least(greatest(coalesce(ri.tiempo_respuesta_ms, 0), 0), private.config_int('tiempo_max_respuesta_seg', 300) * 1000))::integer as t,
               coalesce(ri.actualizada_en, ri.respondida_en) as en
          from public.respuestas_intento ri
          join public.intentos_examen ie on ie.id = ri.intento_id
         where ri.opcion_seleccionada_id is not null and ri.es_correcta is not null
           and ie.origen_intento = 'examen' and ie.cuenta_para_ranking and not ie.es_demo and ie.regla_anuladas is not null
           and not private.intento_pregunta_anulada(ri.intento_id, ie.examen_id, ri.pregunta_id)
           and exists (select 1 from auth.users u where u.id = ri.usuario_id)
           and exists (select 1 from public.preguntas q where q.id = ri.pregunta_id)
         order by ri.usuario_id, ri.pregunta_id, coalesce(ri.actualizada_en, ri.respondida_en), ri.id) b
 where not exists (select 1 from public.primeras_respuestas_ranking x where x.usuario_id = b.usuario_id and x.pregunta_id = b.pregunta_id)
 order by b.en, b.usuario_id, b.pregunta_id;

-- 5.3 competitive state rebuilt from the ranking ledger for every user that has one or the other
do $$
declare
  u record;
begin
  for u in select x.usuario_id from public.estado_competitivo_usuario x union select r.usuario_id from public.primeras_respuestas_ranking r loop
    perform private.stats_recompute_competitivo(u.usuario_id);
  end loop;
end
$$;

commit;
