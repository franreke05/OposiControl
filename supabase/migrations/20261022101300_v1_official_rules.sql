-- V1 OFFICIAL EXAMS: ANNULLED + RESERVE QUESTIONS (owner-approved model; E1-V1-OFFICIAL-RULES-DESIGN.md with E2 conditions C-O1..C-O6 accepted). Contract: E1-V1-OFFICIAL-RULES-CONTRACT.md (FA31).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101000 (demo), 20261022101100 (limiter) and 20261022101200 (challenge pool).
--
-- MODEL: preguntas_examen gets es_reserva (boolean) and orden_reserva; examenes gets regla_anuladas (REEMPLAZO_POR_RESERVA | NO_PUNTUA; NULL = DERIVED: an exam with reserves replaces, one without does not count the annulled = today) and the penalty as an exact
-- fraction penalizacion_num/den (default 0/1). At START the attempt freezes (preguntas_intento.rol PRINCIPAL | RESERVA_ACTIVA | RESERVA_NO_USADA, anulada_al_inicio, sustituye_a_posicion) and snapshots the rule on
-- intentos_examen; attempts that carry the snapshot never read the live annulment again (option 1 of the design); older attempts keep the M10 live rule. C-O1: an unused reserve is not answerable, not revealed and its
-- media is not served while the attempt is open (record answer / reveal here, page+grid+resource reads filter in the gateway query); it is visible only after finish. PUNTUA_ACIERTO was dropped (client answer 2026-10-16 (c)). C-O3: demo cross-check on every edit. C-O4: publish AND later-edit validation (OFFICIAL_RULES_INVALID). C-O5: nota computed ONCE in SQL (private.official_nota, integer arithmetic,
-- half-up) and stored on the attempt; a Kotlin twin is pinned by a parity matrix.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_estado text;
--   v_examen_id uuid;
--   v_iniciado timestamp with time zone;
--   v_correctas integer;
--   v_falladas integer;
--   v_sin_responder integer;
-- begin
--   -- ATTEMPT_CONTENT_REVISIONS
--   if p_usuario_id is null or p_intento_id is null then
--     return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
--   end if;
--
--   select ie.estado, ie.examen_id, ie.iniciado_en into v_estado, v_examen_id, v_iniciado
--   from public.intentos_examen ie
--   where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
--     and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
--   for update;
--   if not found then
--     return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
--   end if;
--   if v_estado = 'finalizado' then
--     return jsonb_build_object('outcome', 'OK', 'already', true);
--   end if;
--   if v_estado <> 'en_progreso' then
--     return jsonb_build_object('outcome', 'ATTEMPT_ABANDONED');
--   end if;
--
--   update public.preguntas_intento pi
--      set estado_final = s.estado_final
--   from (
--     select q.id,
--            case
--              when exists (select 1 from public.preguntas_examen pe
--                           where pe.examen_id = v_examen_id and pe.pregunta_id = q.pregunta_id and pe.anulada) then 'ANNULLED'
--              when ri.id is null then 'UNANSWERED'
--              when ri.opcion_seleccionada_id is null then 'SKIPPED'
--              when ri.es_correcta is true then 'CORRECT'
--              else 'WRONG'
--            end as estado_final
--     from public.preguntas_intento q
--     left join public.respuestas_intento ri on ri.intento_id = q.intento_id and ri.pregunta_id = q.pregunta_id
--     where q.intento_id = p_intento_id
--   ) s
--   where pi.id = s.id;
--
--   select count(*) filter (where pi.estado_final = 'CORRECT'),
--          count(*) filter (where pi.estado_final = 'WRONG'),
--          count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED'))
--     into v_correctas, v_falladas, v_sin_responder
--   from public.preguntas_intento pi
--   where pi.intento_id = p_intento_id;
--
--   insert into public.resumen_intento_etiqueta
--     (intento_id, etiqueta_id, total_preguntas, correctas, falladas, sin_responder,
--      tiempo_total_segundos, tiempo_medio_segundos, porcentaje_acierto)
--   select p_intento_id,
--          t.etiqueta_id,
--          count(*)::integer,
--          (count(*) filter (where pi.estado_final = 'CORRECT'))::integer,
--          (count(*) filter (where pi.estado_final = 'WRONG'))::integer,
--          (count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED')))::integer,
--          (coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000)::integer,
--          round(coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000.0 / count(*), 2),
--          case when count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')) > 0
--               then round(count(*) filter (where pi.estado_final = 'CORRECT') * 100.0
--                          / count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')), 2)
--               else 0 end
--   from public.preguntas_intento pi
--   join lateral (
--     -- Bound row: the revision's primary tag (if that tag still exists). Unbound legacy row: the live lookup.
--     select x.etiqueta_id
--     from (
--       select (r.contenido -> 'etiquetas' -> 0 ->> 'id')::uuid as etiqueta_id
--       from public.revisiones_pregunta r
--       where r.id = pi.revision_pregunta_id
--       union all
--       select l.etiqueta_id
--       from (select ep.etiqueta_id from public.etiquetas_pregunta ep
--             where ep.pregunta_id = pi.pregunta_id
--             order by ep.creado_en asc, ep.etiqueta_id asc
--             limit 1) l
--       where pi.revision_pregunta_id is null
--     ) x
--     join public.etiquetas e on e.id = x.etiqueta_id
--     limit 1
--   ) t on true
--   left join public.respuestas_intento ri on ri.intento_id = pi.intento_id and ri.pregunta_id = pi.pregunta_id
--   where pi.intento_id = p_intento_id and pi.estado_final <> 'ANNULLED'
--   group by t.etiqueta_id
--   on conflict (intento_id, etiqueta_id) do nothing;
--
--   update public.intentos_examen
--      set estado = 'finalizado',
--          motivo_fin = 'USER',
--          finalizado_en = now(),
--          duracion_segundos = greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
--          total_respondidas = v_correctas + v_falladas,
--          total_correctas = v_correctas,
--          total_falladas = v_falladas,
--          total_sin_responder = v_sin_responder,
--          porcentaje_acierto = case when v_correctas + v_falladas > 0
--                                    then round(v_correctas * 100.0 / (v_correctas + v_falladas), 2) else 0 end,
--          puntuacion = v_correctas,
--          calculado_en = now()
--    where id = p_intento_id;
--
--   return jsonb_build_object('outcome', 'OK', 'already', false);
-- end;
-- $$;
--
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
--   -- ATTEMPT_CONTENT_REVISIONS
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
--   where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id;
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
--   if exists (select 1 from public.preguntas_examen pe
--              where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id and pe.anulada) then
--     select count(*) into v_respondidas
--     from public.respuestas_intento ri
--     where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
--       and not exists (select 1 from public.preguntas_examen pe
--                       where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
--     return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
--                               'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
--   end if;
--
--   if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
--     select count(*) into v_respondidas
--     from public.respuestas_intento ri
--     where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
--       and not exists (select 1 from public.preguntas_examen pe
--                       where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
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
--   select count(*) into v_respondidas
--   from public.respuestas_intento ri
--   where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
--     and not exists (select 1 from public.preguntas_examen pe
--                     where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
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
--
-- create or replace function private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid, p_solo_demo boolean)
-- returns integer
-- language plpgsql
-- set search_path = ''
-- as $$
-- declare
--   v_n integer;
--   v_preguntas uuid[];
--   v_hashes text[];
--   v_contenidos jsonb[];
--   v_soluciones jsonb[];
--   v_bound integer;
-- begin
--   -- ATTEMPT_FREEZE_KEEPS_ANNULLED: every active question is frozen; the result is the number of EVALUABLE ones.
--   -- ATTEMPT_CONTENT_REVISIONS: each frozen question is bound to the immutable revision of its content right now.
--   if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
--     select array_agg(c.pregunta_id order by c.posicion, c.pregunta_id),
--            array_agg(encode(sha256(convert_to(c.contenido::text || '|' || coalesce(c.solucion::text, 'null'), 'UTF8')), 'hex')
--                      order by c.posicion, c.pregunta_id),
--            array_agg(c.contenido order by c.posicion, c.pregunta_id),
--            array_agg(c.solucion order by c.posicion, c.pregunta_id)
--       into v_preguntas, v_hashes, v_contenidos, v_soluciones
--     from (
--       select pe.pregunta_id, pe.posicion,
--              private.app_revision_pregunta_contenido(pe.pregunta_id) as contenido,
--              private.app_revision_pregunta_solucion(pe.pregunta_id) as solucion
--       from public.preguntas_examen pe
--       join public.preguntas p on p.id = pe.pregunta_id
--       where pe.examen_id = p_examen_id and p.activa and (not p_solo_demo or (pe.es_demo and not pe.anulada))
--     ) c;
--
--     if v_preguntas is not null then
--       -- Statement 1: reuse or create. A concurrent start inserting the same revision makes this wait and do nothing.
--       insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
--       select u.pregunta_id, u.hash, u.contenido, u.solucion
--       from unnest(v_preguntas, v_hashes, v_contenidos, v_soluciones) as u(pregunta_id, hash, contenido, solucion)
--       on conflict (pregunta_id, hash_contenido) do nothing;
--
--       -- Statement 2 (new snapshot: sees the revision a concurrent start committed).
--       insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id)
--       select p_intento_id, u.pregunta_id, u.ord::integer, r.id
--       from unnest(v_preguntas, v_hashes) with ordinality as u(pregunta_id, hash, ord)
--       join public.revisiones_pregunta r on r.pregunta_id = u.pregunta_id and r.hash_contenido = u.hash;
--       get diagnostics v_bound = row_count;
--       if v_bound <> cardinality(v_preguntas) then
--         raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001', detail = 'revision binding incomplete';
--       end if;
--     end if;
--   end if;
--   select count(*) into v_n
--   from public.preguntas_intento pi
--   where pi.intento_id = p_intento_id
--     and not exists (select 1 from public.preguntas_examen pe
--                     where pe.examen_id = p_examen_id and pe.pregunta_id = pi.pregunta_id and pe.anulada);
--   return v_n;
-- end;
-- $$;
--
-- create or replace function public.app_reveal_attempt_questions(p_usuario uuid, p_intento uuid, p_preguntas uuid[])
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_demo boolean;
--   v_acceso text;
--   v_now timestamptz;
--   v_limit integer;
--   v_nuevas uuid[];
--   v_actual integer;
--   v_exceso integer;
--   v_libera timestamptz;
-- begin
--   select ie.es_demo, ie.acceso_estado into v_demo, v_acceso
--     from public.intentos_examen ie where ie.id = p_intento and ie.usuario_id = p_usuario and ie.creado_por_comando is not null;
--   if not found then
--     return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
--   end if;
--   if v_demo or v_acceso in ('GRANTED', 'DEMO') or coalesce(cardinality(p_preguntas), 0) = 0 then
--     return jsonb_build_object('outcome', 'OK');
--   end if;
--   v_now := clock_timestamp();
--   v_limit := private.plan_daily_limit(p_usuario, v_now);
--   if v_limit is null then
--     return jsonb_build_object('outcome', 'OK');  -- no limit: no lock, no write, no count
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('limiter:' || p_usuario::text, 0));
--   v_now := clock_timestamp();  -- DB clock after the lock
--   select coalesce(array_agg(x.q), '{}') into v_nuevas
--     from unnest(p_preguntas) as x(q)
--    where not exists (select 1 from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.pregunta_id = x.q and r.revelada_en > v_now - interval '24 hours');
--   if cardinality(v_nuevas) = 0 then
--     return jsonb_build_object('outcome', 'OK');
--   end if;
--   select count(*) into v_actual from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.revelada_en > v_now - interval '24 hours';
--   if v_actual + cardinality(v_nuevas) > v_limit then
--     v_exceso := v_actual + cardinality(v_nuevas) - v_limit;  -- how many of the oldest revealed questions must leave the window
--     select r.revelada_en + interval '24 hours' into v_libera
--       from public.preguntas_reveladas_usuario r where r.usuario_id = p_usuario and r.revelada_en > v_now - interval '24 hours'
--      order by r.revelada_en offset least(v_exceso, v_actual) - 1 limit 1;
--     return jsonb_build_object('outcome', 'DAILY_LIMIT_REACHED', 'retry_after_seconds',
--       greatest(1, ceil(extract(epoch from (coalesce(v_libera, v_now + interval '24 hours') - v_now)))::integer), 'limit', v_limit);
--   end if;
--   insert into public.preguntas_reveladas_usuario (usuario_id, pregunta_id, revelada_en)
--   select p_usuario, q, v_now from unnest(v_nuevas) as q
--   on conflict (usuario_id, pregunta_id) do update set revelada_en = excluded.revelada_en;
--   return jsonb_build_object('outcome', 'OK');
-- end;
-- $$;
--
-- create or replace function private.reto_pool(p_esp uuid, p_com uuid, p_slot text)
-- returns setof uuid
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select distinct p.id
--     from public.preguntas p
--     join public.preguntas_examen pe on pe.pregunta_id = p.id and pe.anulada is not true and (p_slot <> 'inmersiva' or pe.es_demo)
--     join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= clock_timestamp() and e.especialidad_id = p_esp
--    where p.activa and p.tipo_pregunta = p_slot
--      and (not exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id where ep.pregunta_id = p.id)
--           or (p_com is not null and exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
--                                              where ep.pregunta_id = p.id and ec.comunidad_id = p_com)))
-- $$;
--
-- drop trigger if exists trg_examenes_official_rules on public.examenes;
-- drop trigger if exists trg_preguntas_examen_official_rules on public.preguntas_examen;
-- drop trigger if exists trg_preguntas_examen_demo_rules on public.preguntas_examen;
-- drop function if exists private.tg_official_rules_check();
-- drop function if exists private.tg_preguntas_examen_demo_rules();
-- drop function if exists public.admin_official_rules_check(uuid);
-- drop function if exists private.official_rules_warnings(uuid);
-- drop function if exists private.official_rules_problem(uuid);
-- drop function if exists private.official_rule_effective(uuid);
-- delete from public.configuracion_v1 where clave = 'max_reservas_oficiales';
-- drop function if exists private.intento_pregunta_anulada(uuid, uuid, uuid);
-- drop function if exists private.official_nota(integer, integer, integer, integer, integer);
-- alter table public.preguntas_intento drop constraint if exists preguntas_intento_estado_final_check;
-- alter table public.preguntas_intento add constraint preguntas_intento_estado_final_check check (((estado_final IS NULL) OR (estado_final = ANY (ARRAY['CORRECT'::text, 'WRONG'::text, 'SKIPPED'::text, 'UNANSWERED'::text, 'ANNULLED'::text]))));
-- alter table public.preguntas_intento drop column if exists sustituye_a_posicion, drop column if exists anulada_al_inicio, drop column if exists rol;
-- alter table public.intentos_examen drop column if exists anuladas_sin_reserva, drop column if exists reservas_usadas, drop column if exists total_anuladas, drop column if exists total_evaluables,
--   drop column if exists nota, drop column if exists penalizacion_den, drop column if exists penalizacion_num, drop column if exists regla_anuladas;
-- drop index if exists public.ux_preguntas_examen_orden_reserva;
-- alter table public.preguntas_examen drop constraint if exists preguntas_examen_reserva_chk;
-- alter table public.preguntas_examen drop column if exists orden_reserva, drop column if exists es_reserva;
-- alter table public.examenes drop constraint if exists examenes_penalizacion_chk;
-- alter table public.examenes drop column if exists penalizacion_den, drop column if exists penalizacion_num, drop column if exists regla_anuladas;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'official_functions_private_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('private.official_nota(integer,integer,integer,integer,integer)'), ('private.official_rules_problem(uuid)'), ('private.intento_pregunta_anulada(uuid,uuid,uuid)')) f(n)
-- union all
-- select 'official_triggers_missing',
--        (3 - (select count(*) from pg_trigger t where t.tgname in ('trg_examenes_official_rules', 'trg_preguntas_examen_official_rules', 'trg_preguntas_examen_demo_rules') and not t.tgisinternal))::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.config_int(text,integer)') is null
     or to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid,boolean)') is null
     or to_regprocedure('private.reto_pool(uuid,uuid,text)') is null
     or to_regprocedure('public.app_reveal_attempt_questions(uuid,uuid,uuid[])') is null
     or to_regprocedure('private.demo_set_ok(uuid)') is null then
    raise exception using errcode = 'P0001', message = 'V1_OFFICIAL_RULES_PREFLIGHT', hint = 'apply 20261022101000, 101100 and 101200 first';
  end if;
end
$$;

-- ---- 1. schema (defaults reproduce today exactly: PRINCIPAL, NO_PUNTUA, no penalty)
alter table public.examenes
  add column if not exists regla_anuladas text,
  add column if not exists penalizacion_num smallint not null default 0,
  add column if not exists penalizacion_den smallint not null default 1;
alter table public.examenes drop constraint if exists examenes_penalizacion_chk;
alter table public.examenes add constraint examenes_penalizacion_chk
  check ((regla_anuladas is null or regla_anuladas in ('REEMPLAZO_POR_RESERVA', 'NO_PUNTUA')) and penalizacion_den >= 1 and penalizacion_num >= 0 and penalizacion_num <= penalizacion_den);

alter table public.preguntas_examen
  add column if not exists es_reserva boolean not null default false,
  add column if not exists orden_reserva integer;
alter table public.preguntas_examen drop constraint if exists preguntas_examen_reserva_chk;
alter table public.preguntas_examen add constraint preguntas_examen_reserva_chk
  check ((not es_reserva and orden_reserva is null) or (es_reserva and orden_reserva is not null and orden_reserva >= 1));
create unique index if not exists ux_preguntas_examen_orden_reserva on public.preguntas_examen (examen_id, orden_reserva) where orden_reserva is not null;

alter table public.intentos_examen
  add column if not exists regla_anuladas text,
  add column if not exists penalizacion_num smallint,
  add column if not exists penalizacion_den smallint,
  add column if not exists nota numeric(4, 2),
  add column if not exists total_evaluables integer,
  add column if not exists total_anuladas integer,
  add column if not exists reservas_usadas integer,
  add column if not exists anuladas_sin_reserva integer;

alter table public.preguntas_intento
  add column if not exists rol text,
  add column if not exists anulada_al_inicio boolean,
  add column if not exists sustituye_a_posicion integer;
alter table public.preguntas_intento drop constraint if exists preguntas_intento_rol_chk;
alter table public.preguntas_intento add constraint preguntas_intento_rol_chk check (rol is null or rol in ('PRINCIPAL', 'RESERVA_ACTIVA', 'RESERVA_NO_USADA'));
alter table public.preguntas_intento drop constraint if exists preguntas_intento_estado_final_check;
alter table public.preguntas_intento add constraint preguntas_intento_estado_final_check
  check (estado_final is null or estado_final = any (array['CORRECT'::text, 'WRONG'::text, 'SKIPPED'::text, 'UNANSWERED'::text, 'ANNULLED'::text, 'RESERVA_NO_UTILIZADA'::text]));

-- ---- 2. scoring: ONE implementation (C-O5). Integer arithmetic, half-up, hundredths; Kotlin twin: OfficialScoring.nota (pinned by a parity matrix).
create or replace function private.official_nota(p_correctas integer, p_falladas integer, p_evaluables integer, p_num integer, p_den integer)
returns numeric
language sql
immutable
set search_path = ''
as $$
  select case when p_evaluables is null or p_evaluables <= 0 or p_den is null or p_den < 1 or p_num is null or p_num < 0 then null
         else (((greatest(0::bigint, p_correctas::bigint * p_den - p_num::bigint * p_falladas) * 2000 + p_den::bigint * p_evaluables)
                / (2 * p_den::bigint * p_evaluables))::numeric / 100)::numeric(4, 2) end
$$;
revoke all on function private.official_nota(integer, integer, integer, integer, integer) from public, anon, authenticated;

-- is the question annulled FOR THIS ATTEMPT? Attempts that carry the snapshot (rol not null) use the state frozen at start; older attempts keep the live rule (M10).
create or replace function private.intento_pregunta_anulada(p_intento uuid, p_examen uuid, p_pregunta uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    (select case when pi.rol is not null then coalesce(pi.anulada_al_inicio, false)
                 else exists (select 1 from public.preguntas_examen pe where pe.examen_id = p_examen and pe.pregunta_id = p_pregunta and pe.anulada) end
       from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = p_pregunta),
    exists (select 1 from public.preguntas_examen pe where pe.examen_id = p_examen and pe.pregunta_id = p_pregunta and pe.anulada))
$$;
revoke all on function private.intento_pregunta_anulada(uuid, uuid, uuid) from public, anon, authenticated;

-- the rule of an exam: explicit, or DERIVED (client answer 2026-10-16 (c)): an exam with reserves replaces annulled questions by reserves, an exam without reserves just does not count them
create or replace function private.official_rule_effective(p_examen uuid)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(e.regla_anuladas,
                  case when exists (select 1 from public.preguntas_examen pe where pe.examen_id = e.id and pe.es_reserva) then 'REEMPLAZO_POR_RESERVA' else 'NO_PUNTUA' end)
    from public.examenes e where e.id = p_examen
$$;
revoke all on function private.official_rule_effective(uuid) from public, anon, authenticated;

insert into public.configuracion_v1 (clave, valor_integer) values ('max_reservas_oficiales', 3) on conflict (clave) do nothing;

-- ---- 3. validation (C-O3, C-O4): the first violation of an exam, or null
create or replace function private.official_rules_problem(p_examen uuid)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_regla text;
  v_ann integer;
  v_res integer;
  v_res_act integer;
  v_min_ord integer;
  v_max_ord integer;
  v_main integer;
  v_main_pos integer;
  v_res_pos integer;
begin
  if not exists (select 1 from public.examenes e where e.id = p_examen) then
    return null;
  end if;
  v_regla := private.official_rule_effective(p_examen);
  select count(*) filter (where not pe.es_reserva and pe.anulada),
         count(*) filter (where pe.es_reserva),
         count(*) filter (where pe.es_reserva and not pe.anulada),
         min(pe.orden_reserva), max(pe.orden_reserva),
         count(*) filter (where not pe.es_reserva),
         max(pe.posicion) filter (where not pe.es_reserva),
         min(pe.posicion) filter (where pe.es_reserva)
    into v_ann, v_res, v_res_act, v_min_ord, v_max_ord, v_main, v_main_pos, v_res_pos
  from public.preguntas_examen pe where pe.examen_id = p_examen;
  if exists (select 1 from public.preguntas_examen pe where pe.examen_id = p_examen and pe.es_demo and (pe.anulada or pe.es_reserva)) then
    return 'DEMO_CONFLICT';
  end if;
  if v_res > 0 and v_main = 0 then
    return 'RESERVE_WITHOUT_MAIN';
  end if;
  if v_res > 0 and (v_min_ord <> 1 or v_max_ord <> v_res) then
    return 'RESERVE_ORDER_GAPS';
  end if;
  if v_res > 0 and v_res_pos <= v_main_pos then
    return 'RESERVE_BEFORE_MAIN';
  end if;
  if v_regla = 'REEMPLAZO_POR_RESERVA' and v_ann > v_res_act then
    return 'REEMPLAZO_NOT_ENOUGH_RESERVES';
  end if;
  return null;
end;
$$;
revoke all on function private.official_rules_problem(uuid) from public, anon, authenticated;

-- warnings do NOT block (client answer (c)): more reserves than the configured maximum (default 3); an annulled reserve is skipped by the replacement (the next one is taken)
create or replace function private.official_rules_warnings(p_examen uuid)
returns text[]
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(array_remove(array[
    case when (select count(*) from public.preguntas_examen pe where pe.examen_id = p_examen and pe.es_reserva) > private.config_int('max_reservas_oficiales', 3) then 'MORE_RESERVES_THAN_MAX' end,
    case when exists (select 1 from public.preguntas_examen pe where pe.examen_id = p_examen and pe.es_reserva and pe.anulada) then 'ANNULLED_RESERVE_SKIPPED' end], null), '{}')
$$;
revoke all on function private.official_rules_warnings(uuid) from public, anon, authenticated;

-- what Control reads before publishing: blocking problem (or null) + warnings + the rule in force
create or replace function public.admin_official_rules_check(p_examen uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object('problema', private.official_rules_problem(p_examen), 'avisos', to_jsonb(private.official_rules_warnings(p_examen)), 'regla', private.official_rule_effective(p_examen))
$$;
revoke all on function public.admin_official_rules_check(uuid) from public, anon, authenticated;
grant execute on function public.admin_official_rules_check(uuid) to service_role;

-- C-O3: the demo set is a fixed set of evaluable MAIN questions; checked on every edit, draft or published
create or replace function private.tg_preguntas_examen_demo_rules()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.es_demo and (new.anulada or new.es_reserva) then
    raise exception using errcode = '23514', message = 'DEMO_CONFLICT', hint = 'a demo question cannot be annulled or be a reserve';
  end if;
  if tg_op = 'UPDATE' and old.es_demo and new.es_demo and new.posicion is distinct from old.posicion then
    raise exception using errcode = '23514', message = 'DEMO_CONFLICT', hint = 'a demo question cannot be moved';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_preguntas_examen_demo_rules() from public, anon, authenticated;
drop trigger if exists trg_preguntas_examen_demo_rules on public.preguntas_examen;
create trigger trg_preguntas_examen_demo_rules before insert or update of es_demo, anulada, es_reserva, orden_reserva, posicion on public.preguntas_examen
  for each row execute function private.tg_preguntas_examen_demo_rules();

-- C-O4: a PUBLISHED exam is validated at the end of every transaction that edits it (so a block can be loaded in several statements)
create or replace function private.tg_official_rules_check()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_examen uuid;
  v_problem text;
begin
  v_examen := ((case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end) ->> (case when tg_table_name = 'examenes' then 'id' else 'examen_id' end))::uuid;
  if exists (select 1 from public.examenes e where e.id = v_examen and e.publicado_en is not null) then
    v_problem := private.official_rules_problem(v_examen);
    if v_problem is not null then
      raise exception using errcode = '23514', message = 'OFFICIAL_RULES_INVALID', hint = v_problem;
    end if;
  end if;
  return null;
end;
$$;
revoke all on function private.tg_official_rules_check() from public, anon, authenticated;
drop trigger if exists trg_preguntas_examen_official_rules on public.preguntas_examen;
create constraint trigger trg_preguntas_examen_official_rules after insert or update or delete on public.preguntas_examen
  deferrable initially deferred for each row execute function private.tg_official_rules_check();
drop trigger if exists trg_examenes_official_rules on public.examenes;
create constraint trigger trg_examenes_official_rules after update of publicado_en, regla_anuladas, penalizacion_num, penalizacion_den on public.examenes
  deferrable initially deferred for each row execute function private.tg_official_rules_check();

-- ---- 4. the attempt: freeze with roles (C-O1), answer, finish, reveal; the challenge pool never takes a reserve
create or replace function private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid, p_solo_demo boolean)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  v_n integer;
  v_preguntas uuid[];
  v_hashes text[];
  v_contenidos jsonb[];
  v_soluciones jsonb[];
  v_roles text[];
  v_anuladas boolean[];
  v_sustituye integer[];
  v_bound integer;
  v_regla text;
  v_num integer;
  v_den integer;
  v_a integer;
  v_r integer;
  v_k integer;
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED + ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES: every active question is frozen with its ROLE and its annulment state at start.
  -- A DEMO attempt ignores the official rules (rol null = the legacy live rule over exactly the flagged questions).
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    select private.official_rule_effective(p_examen_id), e.penalizacion_num, e.penalizacion_den into v_regla, v_num, v_den from public.examenes e where e.id = p_examen_id;
    select count(*) filter (where not pe.es_reserva and pe.anulada), count(*) filter (where pe.es_reserva and not pe.anulada)
      into v_a, v_r
    from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
    where pe.examen_id = p_examen_id and p.activa;
    v_k := case when not p_solo_demo and v_regla = 'REEMPLAZO_POR_RESERVA' then least(v_a, v_r) else 0 end;

    select array_agg(c.pregunta_id order by c.ord),
           array_agg(encode(sha256(convert_to(c.contenido::text || '|' || coalesce(c.solucion::text, 'null'), 'UTF8')), 'hex') order by c.ord),
           array_agg(c.contenido order by c.ord),
           array_agg(c.solucion order by c.ord),
           array_agg(c.rol order by c.ord),
           array_agg(c.anul order by c.ord),
           array_agg(c.sust order by c.ord)
      into v_preguntas, v_hashes, v_contenidos, v_soluciones, v_roles, v_anuladas, v_sustituye
    from (
      with q as (
        select pe.pregunta_id, pe.es_reserva, pe.anulada, pe.orden_reserva,
               row_number() over (order by (pe.es_reserva), pe.posicion, pe.pregunta_id) as ord
        from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
        where pe.examen_id = p_examen_id and p.activa and (not p_solo_demo or (pe.es_demo and not pe.anulada))
      ), ann as (
        select q.ord, row_number() over (order by q.ord) as rk from q where not q.es_reserva and q.anulada
      ), res as (
        select q.pregunta_id, row_number() over (order by q.orden_reserva) as rk from q where q.es_reserva and not q.anulada
      )
      select q.pregunta_id, q.ord,
             private.app_revision_pregunta_contenido(q.pregunta_id) as contenido,
             private.app_revision_pregunta_solucion(q.pregunta_id) as solucion,
             case when p_solo_demo then null
                  when not q.es_reserva then 'PRINCIPAL'
                  when exists (select 1 from res where res.pregunta_id = q.pregunta_id and res.rk <= v_k) then 'RESERVA_ACTIVA'
                  else 'RESERVA_NO_USADA' end as rol,
             case when p_solo_demo then null else q.anulada end as anul,
             case when not p_solo_demo and q.es_reserva
                       and exists (select 1 from res where res.pregunta_id = q.pregunta_id and res.rk <= v_k)
                  then (select a.ord from ann a where a.rk = (select r.rk from res r where r.pregunta_id = q.pregunta_id))::integer end as sust
      from q
    ) c;

    if v_preguntas is not null then
      -- Statement 1: reuse or create the immutable revisions (reserves included: their review must not drift).
      insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
      select u.pregunta_id, u.hash, u.contenido, u.solucion
      from unnest(v_preguntas, v_hashes, v_contenidos, v_soluciones) as u(pregunta_id, hash, contenido, solucion)
      on conflict (pregunta_id, hash_contenido) do nothing;

      -- Statement 2 (new snapshot: sees the revision a concurrent start committed).
      insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id, rol, anulada_al_inicio, sustituye_a_posicion)
      select p_intento_id, u.pregunta_id, u.ord::integer, r.id, u.rol, u.anul, u.sust
      from unnest(v_preguntas, v_hashes, v_roles, v_anuladas, v_sustituye) with ordinality as u(pregunta_id, hash, rol, anul, sust, ord)
      join public.revisiones_pregunta r on r.pregunta_id = u.pregunta_id and r.hash_contenido = u.hash;
      get diagnostics v_bound = row_count;
      if v_bound <> cardinality(v_preguntas) then
        raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001', detail = 'revision binding incomplete';
      end if;
      if not p_solo_demo then
        update public.intentos_examen
           set regla_anuladas = v_regla, penalizacion_num = v_num, penalizacion_den = v_den,
               reservas_usadas = v_k, total_anuladas = v_a,
               anuladas_sin_reserva = case when v_regla = 'REEMPLAZO_POR_RESERVA' then greatest(v_a - v_r, 0) else 0 end
         where id = p_intento_id;
      end if;
    end if;
  end if;
  -- The result is the number of EVALUABLE questions that can be answered: the non-annulled main questions plus the activated reserves.
  select count(*) into v_n
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id
    and ((pi.rol is null and not exists (select 1 from public.preguntas_examen pe
                                          where pe.examen_id = p_examen_id and pe.pregunta_id = pi.pregunta_id and pe.anulada))
         or (pi.rol in ('PRINCIPAL', 'RESERVA_ACTIVA') and not coalesce(pi.anulada_al_inicio, false)));
  return v_n;
end;
$$;

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
  v_visto_en_demo boolean;
begin
  -- ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES
  if p_usuario_id is null or p_intento_id is null or p_pregunta_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.estado, ie.examen_id, ie.cuenta_para_ranking, ie.iniciado_en, ie.es_demo
    into v_estado, v_examen_id, v_ranking, v_iniciado, v_es_demo
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

  -- DEMO (C-D4): a demo answer is never the first answer, and demo rows never consume it. A real first answer to a question whose correction the user already saw in a demo is stored
  -- as the first answer but does not count for ranking.
  v_first := p_opcion_id is not null and not v_es_demo and not exists (
    select 1 from public.respuestas_intento prev
    join public.intentos_examen pie on pie.id = prev.intento_id
    where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id
      and prev.opcion_seleccionada_id is not null and not pie.es_demo
      and (not v_exists or prev.id <> v_row.id));
  v_visto_en_demo := not v_es_demo and exists (
    select 1 from public.respuestas_intento prev
    join public.intentos_examen pie on pie.id = prev.intento_id
    where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id and prev.opcion_seleccionada_id is not null and pie.es_demo);

  if not v_exists then
    begin
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), coalesce(v_ranking, false) and v_first and not v_visto_en_demo, v_first,
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
             cuenta_para_ranking = coalesce(v_ranking, false) and v_first and not v_visto_en_demo,
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

create or replace function public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_iniciado timestamp with time zone;
  v_correctas integer;
  v_falladas integer;
  v_sin_responder integer;
  v_regla text;
  v_num integer;
  v_den integer;
  v_anuladas integer := 0;
  v_evaluables integer;
begin
  -- ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES
  if p_usuario_id is null or p_intento_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  select ie.estado, ie.examen_id, ie.iniciado_en, ie.regla_anuladas, ie.penalizacion_num, ie.penalizacion_den
    into v_estado, v_examen_id, v_iniciado, v_regla, v_num, v_den
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado = 'finalizado' then
    return jsonb_build_object('outcome', 'OK', 'already', true);
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_ABANDONED');
  end if;

  update public.preguntas_intento pi
     set estado_final = s.estado_final
  from (
    select q.id,
           case
             when q.rol = 'RESERVA_NO_USADA' then 'RESERVA_NO_UTILIZADA'
             when (q.rol is not null and q.anulada_al_inicio)
               or (q.rol is null and exists (select 1 from public.preguntas_examen pe
                                             where pe.examen_id = v_examen_id and pe.pregunta_id = q.pregunta_id and pe.anulada)) then 'ANNULLED'
             when ri.id is null then 'UNANSWERED'
             when ri.opcion_seleccionada_id is null then 'SKIPPED'
             when ri.es_correcta is true then 'CORRECT'
             else 'WRONG'
           end as estado_final
    from public.preguntas_intento q
    left join public.respuestas_intento ri on ri.intento_id = q.intento_id and ri.pregunta_id = q.pregunta_id
    where q.intento_id = p_intento_id
  ) s
  where pi.id = s.id;

  select count(*) filter (where pi.estado_final = 'CORRECT'),
         count(*) filter (where pi.estado_final = 'WRONG'),
         count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED')),
         count(*) filter (where pi.estado_final = 'ANNULLED' and pi.rol = 'PRINCIPAL')
    into v_correctas, v_falladas, v_sin_responder, v_anuladas
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id;
  v_evaluables := v_correctas + v_falladas + v_sin_responder;

  insert into public.resumen_intento_etiqueta
    (intento_id, etiqueta_id, total_preguntas, correctas, falladas, sin_responder,
     tiempo_total_segundos, tiempo_medio_segundos, porcentaje_acierto)
  select p_intento_id,
         t.etiqueta_id,
         count(*)::integer,
         (count(*) filter (where pi.estado_final = 'CORRECT'))::integer,
         (count(*) filter (where pi.estado_final = 'WRONG'))::integer,
         (count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED')))::integer,
         (coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000)::integer,
         round(coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000.0 / count(*), 2),
         case when count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')) > 0
              then round(count(*) filter (where pi.estado_final = 'CORRECT') * 100.0
                         / count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')), 2)
              else 0 end
  from public.preguntas_intento pi
  join lateral (
    -- Bound row: the revision's primary tag (if that tag still exists). Unbound legacy row: the live lookup.
    select x.etiqueta_id
    from (
      select (r.contenido -> 'etiquetas' -> 0 ->> 'id')::uuid as etiqueta_id
      from public.revisiones_pregunta r
      where r.id = pi.revision_pregunta_id
      union all
      select l.etiqueta_id
      from (select ep.etiqueta_id from public.etiquetas_pregunta ep
            where ep.pregunta_id = pi.pregunta_id
            order by ep.creado_en asc, ep.etiqueta_id asc
            limit 1) l
      where pi.revision_pregunta_id is null
    ) x
    join public.etiquetas e on e.id = x.etiqueta_id
    limit 1
  ) t on true
  left join public.respuestas_intento ri on ri.intento_id = pi.intento_id and ri.pregunta_id = pi.pregunta_id
  where pi.intento_id = p_intento_id and pi.estado_final not in ('ANNULLED', 'RESERVA_NO_UTILIZADA')
  group by t.etiqueta_id
  on conflict (intento_id, etiqueta_id) do nothing;

  update public.intentos_examen
     set estado = 'finalizado',
         motivo_fin = 'USER',
         finalizado_en = now(),
         duracion_segundos = greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
         total_respondidas = v_correctas + v_falladas,
         total_correctas = v_correctas,
         total_falladas = v_falladas,
         total_sin_responder = v_sin_responder,
         porcentaje_acierto = case when v_correctas + v_falladas > 0
                                   then round(v_correctas * 100.0 / (v_correctas + v_falladas), 2) else 0 end,
         puntuacion = v_correctas,
         total_evaluables = case when v_regla is not null then v_evaluables end,
         nota = case when v_regla is not null then private.official_nota(v_correctas, v_falladas, v_evaluables, v_num, v_den) end,
         calculado_en = now()
   where id = p_intento_id;

  return jsonb_build_object('outcome', 'OK', 'already', false);
end;
$$;

revoke all on function public.app_finish_exam_attempt(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_finish_exam_attempt(uuid, uuid) to service_role;
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
  if exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = any (p_preguntas) and pi.rol = 'RESERVA_NO_USADA') then
    return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');  -- OFFICIAL_RULES C-O1
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

create or replace function private.reto_pool(p_esp uuid, p_com uuid, p_slot text)
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select distinct p.id
    from public.preguntas p
    join public.preguntas_examen pe on pe.pregunta_id = p.id and pe.anulada is not true and not pe.es_reserva and (p_slot <> 'inmersiva' or pe.es_demo)
    join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= clock_timestamp() and e.especialidad_id = p_esp
   where p.activa and p.tipo_pregunta = p_slot
     and (not exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id where ep.pregunta_id = p.id)
          or (p_com is not null and exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
                                             where ep.pregunta_id = p.id and ec.comunidad_id = p_com)))
$$;


do $$
begin
  if (select count(*) from pg_proc p
       where p.oid in (to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid,boolean)'),
                       to_regprocedure('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
                       to_regprocedure('public.app_finish_exam_attempt(uuid,uuid)'))
         and p.prosrc ~ 'OFFICIAL_RULES') <> 3 then
    raise exception 'OFFICIAL_RULES_POSTCHECK' using detail = 'a command was not redefined';
  end if;
end;
$$;

commit;
