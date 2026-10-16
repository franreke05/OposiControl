-- V1 WAVE 3: STATS (Nota media, Evolucion, Debilidades by tag, summaries, question difficulty). Contract: E1-V1-W3-CONTRACT.md (FA32). Design: E1-V1-ENGAGEMENT-DESIGN.md section 2 (C-S1..C-S4).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101200 (streak, jobs infrastructure), 20261022101300 (answer function) and 20261022101500 (config bounds); the MAIN stats tables
-- (estadisticas_*, estado_competitivo_usuario, ligas_nivel, lotes_actualizacion_ranking) come from the local overlay (main_shape_subset_extra_engagement.sql) with MAIN's shape.
-- primeras_respuestas_usuario is the exactly-once guard AND the window source: one row per (user, question) written by the hook; the hook runs only when that row was inserted. The hook does per-user upserts only
-- (user totals, per-tag, global question counters, competitive state with the Nota media over the last N first answers and the Evolucion step). Difficulty and the league window are NOT computed on request.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
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
--   -- ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES
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
-- drop function if exists public.admin_recompute_stats(uuid, text);
-- drop function if exists public.app_job_dificultad(boolean);
-- drop function if exists public.app_stats_difficulty(uuid, uuid);
-- drop function if exists public.app_stats_weaknesses(uuid, uuid, integer);
-- drop function if exists public.app_stats_summary(uuid);
-- drop function if exists private.app_apply_first_answer_stats(uuid, uuid, boolean, integer, uuid);
-- alter table public.estado_competitivo_usuario drop column if exists ventana_tiempo_medio_ms;
-- drop table if exists public.primeras_respuestas_usuario;
-- delete from public.configuracion_v1 where clave in ('evolucion_cada_preguntas', 'debilidad_min_respuestas', 'dificultad_min_respuestas', 'dificultad_muestra', 'dominio_medio_pct', 'dominio_alto_pct', 'ventana_nota_defecto', 'cohorte_minima_comparativa', 'dificultad_job_minutos', 'recalculo_admin_cooldown_min');
-- create policy estadisticas_globales_pregunta_select on public.estadisticas_globales_pregunta for select to authenticated using (true);
-- grant select on public.estadisticas_globales_pregunta to anon;
-- grant select on public.estadisticas_globales_pregunta to authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'stats_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or not has_function_privilege('service_role', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('public.app_stats_summary(uuid)'), ('public.app_stats_weaknesses(uuid,uuid,integer)'), ('public.app_stats_difficulty(uuid,uuid)'), ('public.app_job_dificultad(boolean)'), ('public.admin_recompute_stats(uuid,text)')) f(n)
-- union all
-- select 'stats_tables_client_access:' || t.n,
--        (case when has_table_privilege('anon', t.n, 'select') or has_table_privilege('authenticated', t.n, 'select') or has_table_privilege('authenticated', t.n, 'insert')
--                or has_table_privilege('authenticated', t.n, 'update') or has_table_privilege('authenticated', t.n, 'delete') or has_table_privilege('service_role', t.n, 'select')
--                or not (select c.relrowsecurity from pg_class c where c.oid = t.n::regclass) then 1 else 0 end)::bigint
--   from (values ('public.primeras_respuestas_usuario')) t(n)
-- union all
-- select 'global_question_stats_readable_by_clients',
--        (case when has_table_privilege('anon', 'public.estadisticas_globales_pregunta', 'select') or has_table_privilege('authenticated', 'public.estadisticas_globales_pregunta', 'select') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.estado_competitivo_usuario') is null or to_regclass('public.estadisticas_usuario_etiqueta') is null or to_regclass('public.estadisticas_globales_pregunta') is null
     or to_regclass('public.ligas_nivel') is null or to_regclass('public.lotes_actualizacion_ranking') is null
     or to_regprocedure('private.reto_streak_json(uuid,date)') is null or to_regprocedure('private.config_v1_range(text)') is null then
    raise exception using errcode = 'P0001', message = 'V1_STATS_PREFLIGHT', hint = 'apply 20261022101200 and 20261022101500 first and load the MAIN stats tables (main_shape_subset_extra_engagement.sql)';
  end if;
end
$$;

-- ---- 1. configuration (Control editable, bounded by 20261022101500)
insert into public.configuracion_v1 (clave, valor_integer) values
  ('evolucion_cada_preguntas', 20), ('debilidad_min_respuestas', 10), ('dificultad_min_respuestas', 20), ('dificultad_muestra', 100),
  ('dominio_medio_pct', 50), ('dominio_alto_pct', 75), ('ventana_nota_defecto', 50), ('cohorte_minima_comparativa', 50),
  ('dificultad_job_minutos', 15), ('recalculo_admin_cooldown_min', 10), ('stats_activo', 1), ('tiempo_max_respuesta_seg', 300)
on conflict (clave) do nothing;

-- C-W4-1: the average first-answer time of the window (the ranking eligibility reads it; the hook keeps it current)
alter table public.estado_competitivo_usuario add column if not exists ventana_tiempo_medio_ms integer;

-- ---- 2. the ledger of first answers: the exactly-once guard of the stats hook (C-S1) and the window source of the Nota media
create table if not exists public.primeras_respuestas_usuario (
  seq bigint generated always as identity primary key,
  usuario_id uuid not null references auth.users(id) on delete cascade,
  pregunta_id uuid not null references public.preguntas(id) on delete cascade,
  correcta boolean not null,
  tiempo_ms integer not null default 0,
  respondida_en timestamptz not null default clock_timestamp(),
  constraint ux_primeras_respuestas_usuario_pregunta unique (usuario_id, pregunta_id)
);
create index if not exists ix_primeras_respuestas_usuario_seq on public.primeras_respuestas_usuario (usuario_id, seq desc);
create index if not exists ix_primeras_respuestas_pregunta_seq on public.primeras_respuestas_usuario (pregunta_id, seq desc);
create index if not exists ix_primeras_respuestas_respondida on public.primeras_respuestas_usuario (respondida_en);
alter table public.primeras_respuestas_usuario enable row level security;
revoke all on table public.primeras_respuestas_usuario from public, anon, authenticated, service_role;

-- ---- 3. client grants (W-11): the global question statistics carry the difficulty, which is served only for answered questions (D3-22); no client reads them directly
drop policy if exists estadisticas_globales_pregunta_select on public.estadisticas_globales_pregunta;
revoke select on table public.estadisticas_globales_pregunta from anon, authenticated;
do $$
begin
  if to_regclass('public.estadisticas_globales_etiqueta') is not null then
    revoke select on table public.estadisticas_globales_etiqueta from anon, authenticated;
  end if;
end
$$;

-- ---- 4. the hook: runs ONLY when the first-answer row was actually written (the guard row decides), in the answer transaction
create or replace function private.app_apply_first_answer_stats(p_usuario uuid, p_pregunta uuid, p_correcta boolean, p_tiempo_ms integer, p_intento uuid)
returns void
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
  v_avg_ms integer;
  v_desde integer;
  v_cada integer := private.config_int('evolucion_cada_preguntas', 20);
  v_ref numeric;
  v_evol numeric;
  v_rev uuid;
  c integer := case when p_correcta then 1 else 0 end;
  v_t integer := least(greatest(coalesce(p_tiempo_ms, 0), 0), private.config_int('tiempo_max_respuesta_seg', 300) * 1000);
begin
  -- C-W3-1 kill switch (Control, audited by the configuracion_v1 trigger): 0 skips ONLY the statistics part; the answer itself is never blocked. Answers made while it is off are not counted (no backfill: the guard row is the count).
  if private.config_int('stats_activo', 1) = 0 then
    return;
  end if;
  -- C-W3-2 the time is clamped here (the caller already passes the server-measured gap since the last event, never the raw client value)
  insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms)
  values (p_usuario, p_pregunta, p_correcta, v_t)
  on conflict on constraint ux_primeras_respuestas_usuario_pregunta do nothing
  returning seq into v_seq;
  if v_seq is null then
    return;  -- already counted: a replay, a retry or a double click never counts twice
  end if;

  insert into public.estadisticas_usuario as e (usuario_id, preguntas_respondidas, respuestas_correctas, porcentaje_acierto)
  values (p_usuario, 1, c, c * 100)
  on conflict (usuario_id) do update
     set preguntas_respondidas = e.preguntas_respondidas + 1,
         respuestas_correctas = e.respuestas_correctas + c,
         porcentaje_acierto = round((e.respuestas_correctas + c) * 100.0 / (e.preguntas_respondidas + 1), 2),
         actualizado_en = now();

  select pi.revision_pregunta_id into v_rev from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = p_pregunta;
  insert into public.estadisticas_usuario_etiqueta as t (usuario_id, etiqueta_id, respondidas, correctas, falladas, porcentaje_acierto, ultima_practica_en)
  select p_usuario, x.id, 1, c, 1 - c, c * 100, now()
    from (select distinct e.id
            from public.etiquetas e
           where e.id in (select (j ->> 'id')::uuid from public.revisiones_pregunta r, jsonb_array_elements(r.contenido -> 'etiquetas') j where r.id = v_rev)
              or (v_rev is null and e.id in (select ep.etiqueta_id from public.etiquetas_pregunta ep where ep.pregunta_id = p_pregunta))) x
  on conflict (usuario_id, etiqueta_id) do update
     set respondidas = t.respondidas + 1,
         correctas = t.correctas + excluded.correctas,
         falladas = t.falladas + excluded.falladas,
         porcentaje_acierto = round((t.correctas + excluded.correctas) * 100.0 / (t.respondidas + 1), 2),
         ultima_practica_en = now(),
         actualizado_en = now();

  -- C-W3-4/7: the per-question global counters are NOT written here (a hot row at exam peaks); app_job_dificultad derives them from the guard table.

  insert into public.estado_competitivo_usuario (usuario_id) values (p_usuario) on conflict (usuario_id) do nothing;
  select * into est from public.estado_competitivo_usuario x where x.usuario_id = p_usuario for update;
  v_validas := est.preguntas_validas_ranking + 1;
  select l.id, l.ventana_calculo into lg
    from public.ligas_nivel l where l.activa and l.preguntas_minimas <= v_validas order by l.preguntas_minimas desc, l.posicion desc limit 1;
  v_ventana := coalesce(lg.ventana_calculo, private.config_int('ventana_nota_defecto', 50));
  select round(avg(case when w.correcta then 10.0 else 0.0 end), 2), round(avg(w.tiempo_ms))::integer into v_nota, v_avg_ms
    from (select pr.correcta, pr.tiempo_ms from public.primeras_respuestas_usuario pr where pr.usuario_id = p_usuario order by pr.seq desc limit v_ventana) w;
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
         ventana_tiempo_medio_ms = v_avg_ms,
         nota_referencia_evolucion = v_ref,
         evolucion_nota = v_evol,
         preguntas_desde_ultima_evolucion = v_desde,
         actualizado_en = now()
   where usuario_id = p_usuario;
end;
$$;
revoke all on function private.app_apply_first_answer_stats(uuid, uuid, boolean, integer, uuid) from public, anon, authenticated;

-- ---- 5. reads (own data only; the route passes the JWT subject)
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
  select coalesce(sum(ie.total_sin_responder), 0)::integer into v_no
    from public.intentos_examen ie where ie.usuario_id = p_usuario and ie.estado = 'finalizado' and not ie.es_demo and ie.origen_intento = 'examen';
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

create or replace function public.app_stats_weaknesses(p_usuario uuid, p_examen uuid, p_limite integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_lim integer := least(greatest(coalesce(p_limite, 10), 1), 50);
  v_min integer := private.config_int('debilidad_min_respuestas', 10);
  v_medio integer := private.config_int('dominio_medio_pct', 50);
  v_alto integer := private.config_int('dominio_alto_pct', 75);
  v_items jsonb;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if p_examen is null then
    select coalesce(jsonb_agg(jsonb_build_object('etiquetaId', t.etiqueta_id, 'nombre', e.nombre, 'respondidas', t.respondidas, 'correctas', t.correctas, 'falladas', t.falladas,
             'porcentajeAcierto', t.porcentaje_acierto,
             'nivelDominio', case when t.porcentaje_acierto < v_medio then 'bajo' when t.porcentaje_acierto < v_alto then 'medio' else 'alto' end)
             order by t.porcentaje_acierto, t.respondidas desc, e.nombre), '[]'::jsonb) into v_items
      from (select * from public.estadisticas_usuario_etiqueta s where s.usuario_id = p_usuario and s.respondidas >= v_min
             order by s.porcentaje_acierto, s.respondidas desc limit v_lim) t
      join public.etiquetas e on e.id = t.etiqueta_id;
    return jsonb_build_object('alcance', 'TOTAL', 'minimoRespuestas', v_min, 'items', v_items);
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('etiquetaId', a.etiqueta_id, 'nombre', e.nombre, 'respondidas', a.correctas + a.falladas, 'correctas', a.correctas, 'falladas', a.falladas,
           'sinResponder', a.sin_responder, 'porcentajeAcierto', a.pct,
           'nivelDominio', case when a.pct < v_medio then 'bajo' when a.pct < v_alto then 'medio' else 'alto' end)
           order by a.pct, a.correctas + a.falladas desc, e.nombre), '[]'::jsonb) into v_items
    from (select r.etiqueta_id, sum(r.correctas)::integer as correctas, sum(r.falladas)::integer as falladas, sum(r.sin_responder)::integer as sin_responder,
                 case when sum(r.correctas + r.falladas) > 0 then round(sum(r.correctas) * 100.0 / sum(r.correctas + r.falladas), 2) else 0 end as pct
            from public.resumen_intento_etiqueta r join public.intentos_examen ie on ie.id = r.intento_id
           where ie.usuario_id = p_usuario and ie.examen_id = p_examen and ie.estado = 'finalizado' and not ie.es_demo
           group by r.etiqueta_id order by 5, 2 desc limit v_lim) a
    join public.etiquetas e on e.id = a.etiqueta_id;
  return jsonb_build_object('alcance', 'EXAMEN', 'minimoRespuestas', 0, 'items', v_items);
end;
$$;
revoke all on function public.app_stats_weaknesses(uuid, uuid, integer) from public, anon, authenticated;
grant execute on function public.app_stats_weaknesses(uuid, uuid, integer) to service_role;

create or replace function public.app_stats_difficulty(p_usuario uuid, p_pregunta uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_dif integer;
begin
  -- comparative data: only for a question the caller has answered (no probing of the question bank)
  if p_usuario is null or p_pregunta is null or not exists (select 1 from public.primeras_respuestas_usuario p where p.usuario_id = p_usuario and p.pregunta_id = p_pregunta) then
    raise exception using errcode = 'P0001', message = 'NOT_FOUND';
  end if;
  select g.dificultad_calculada_1_5 into v_dif from public.estadisticas_globales_pregunta g where g.pregunta_id = p_pregunta;
  return jsonb_build_object('preguntaId', p_pregunta, 'dificultad', v_dif);
end;
$$;
revoke all on function public.app_stats_difficulty(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_stats_difficulty(uuid, uuid) to service_role;

-- ---- 6. the periodic job (C-S4): one instance at a time (advisory lock), bounded, statement timeout, throttled by Control (dificultad_job_minutos), watermarked
create or replace function public.app_job_dificultad(p_forzar boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_hasta bigint;
  v_lote uuid;
  v_min integer := private.config_int('dificultad_min_respuestas', 20);
  v_m integer := private.config_int('dificultad_muestra', 100);
  v_lim integer := 2000;  -- C-W3-6: forced or not, ONE page per call; a forced run restarts the watermark and the periodic job finishes the rest
  v_max bigint;
  v_nuevo bigint;
  v_filas integer := 0;
  v_pagina integer := 0;
begin
  if not pg_try_advisory_xact_lock(hashtextextended('job:dificultad', 0)) then
    return jsonb_build_object('estado', 'OCUPADO');
  end if;
  perform set_config('statement_timeout', '30000', true);
  if not p_forzar and exists (select 1 from public.lotes_actualizacion_ranking l where l.tipo = 'dificultad' and l.estado = 'ok'
                               and l.iniciado_en > clock_timestamp() - make_interval(mins => private.config_int('dificultad_job_minutos', 15))) then
    return jsonb_build_object('estado', 'RECIENTE');
  end if;
  -- C-W3-5: the watermark is the guard-table seq (monotonic), minus a safety margin for transactions that commit out of order
  select coalesce((l.detalle ->> 'hasta_seq')::bigint, 0) into v_hasta
    from public.lotes_actualizacion_ranking l where l.tipo = 'dificultad' and l.estado = 'ok' order by l.iniciado_en desc limit 1;
  v_hasta := case when p_forzar then 0 else coalesce(v_hasta, 0) end;
  select coalesce(max(p.seq), 0) into v_max from public.primeras_respuestas_usuario p;
  insert into public.lotes_actualizacion_ranking (tipo) values ('dificultad') returning id into v_lote;
  drop table if exists pg_temp.dj_sucias;
  create temp table dj_sucias on commit drop as
    select p.pregunta_id, min(p.seq) as m from public.primeras_respuestas_usuario p where p.seq > v_hasta group by p.pregunta_id order by m limit v_lim;
  select count(*)::integer, max(m) into v_pagina, v_nuevo from dj_sucias;
  -- (C-W3-4/7) the counters and the difficulty live in rows only THIS job writes (the answer hook never touches them), in one bounded page
  with agg_all as (
    select p.pregunta_id, count(*)::integer as total, count(*) filter (where p.correcta)::integer as aciertos
      from public.primeras_respuestas_usuario p join dj_sucias s on s.pregunta_id = p.pregunta_id group by p.pregunta_id
  ), muestra as (
    select p.pregunta_id, p.correcta, p.tiempo_ms, row_number() over (partition by p.pregunta_id order by p.seq desc) as rn
      from public.primeras_respuestas_usuario p join dj_sucias s on s.pregunta_id = p.pregunta_id
  ), agg as (
    select pregunta_id, count(*)::integer as n, count(*) filter (where not correcta)::integer as fallos,
           (percentile_cont(0.5) within group (order by tiempo_ms)) / 1000.0 as med
      from muestra where rn <= v_m group by pregunta_id
  ), upd as (
    insert into public.estadisticas_globales_pregunta as g (pregunta_id, total_respuestas, total_aciertos, total_fallos, total_respuestas_ranking, total_aciertos_ranking, total_fallos_ranking,
                                                            porcentaje_acierto, porcentaje_fallo, muestra_ultimos_usuarios, mediana_tiempo_respuesta_segundos, puntos_fallo_1_5, actualizado_en)
    select a.pregunta_id, a.total, a.aciertos, a.total - a.aciertos, a.total, a.aciertos, a.total - a.aciertos,
           round(a.aciertos * 100.0 / a.total, 2), round((a.total - a.aciertos) * 100.0 / a.total, 2), agg.n, round(agg.med::numeric, 2),
           case when agg.n >= v_min then least(5, 1 + floor(agg.fallos * 100.0 / agg.n / 20))::integer end, clock_timestamp()
      from agg_all a join agg on agg.pregunta_id = a.pregunta_id
    on conflict (pregunta_id) do update
       set total_respuestas = excluded.total_respuestas, total_aciertos = excluded.total_aciertos, total_fallos = excluded.total_fallos,
           total_respuestas_ranking = excluded.total_respuestas_ranking, total_aciertos_ranking = excluded.total_aciertos_ranking, total_fallos_ranking = excluded.total_fallos_ranking,
           porcentaje_acierto = excluded.porcentaje_acierto, porcentaje_fallo = excluded.porcentaje_fallo,
           muestra_ultimos_usuarios = excluded.muestra_ultimos_usuarios, mediana_tiempo_respuesta_segundos = excluded.mediana_tiempo_respuesta_segundos,
           puntos_fallo_1_5 = excluded.puntos_fallo_1_5, actualizado_en = clock_timestamp()
    returning g.pregunta_id
  )
  select count(*)::integer into v_filas from upd;
  -- five equal groups by median time over every question with enough sample, combined with the failure band (rounded average)
  with rated as (
    select g.pregunta_id, ntile(5) over (order by g.mediana_tiempo_respuesta_segundos, g.pregunta_id) as nt
      from public.estadisticas_globales_pregunta g
     where g.muestra_ultimos_usuarios >= v_min and g.puntos_fallo_1_5 is not null and g.mediana_tiempo_respuesta_segundos is not null
  )
  update public.estadisticas_globales_pregunta g
     set puntos_tiempo_1_5 = rated.nt, dificultad_calculada_1_5 = (g.puntos_fallo_1_5 + rated.nt + 1) / 2
    from rated
   where g.pregunta_id = rated.pregunta_id and (g.puntos_tiempo_1_5 is distinct from rated.nt or g.dificultad_calculada_1_5 is distinct from (g.puntos_fallo_1_5 + rated.nt + 1) / 2);
  update public.estadisticas_globales_pregunta g
     set puntos_tiempo_1_5 = null, dificultad_calculada_1_5 = null, puntos_fallo_1_5 = null
   where (g.muestra_ultimos_usuarios < v_min or g.puntos_fallo_1_5 is null) and (g.dificultad_calculada_1_5 is not null or g.puntos_tiempo_1_5 is not null);
  -- a full page: continue after the last question of it; a short page: everything up to the max seq seen at the start is done
  update public.lotes_actualizacion_ranking
     set estado = 'ok', finalizado_en = clock_timestamp(), filas = v_filas,
         detalle = jsonb_build_object('hasta_seq', greatest(case when v_pagina >= v_lim then v_nuevo else v_max end - 1000, v_hasta), 'forzado', p_forzar, 'pendiente', v_pagina >= v_lim)
   where id = v_lote;
  return jsonb_build_object('estado', 'OK', 'filas', v_filas, 'pendiente', v_pagina >= v_lim);
end;
$$;
revoke all on function public.app_job_dificultad(boolean) from public, anon, authenticated;
grant execute on function public.app_job_dificultad(boolean) to service_role;

-- admin recompute (audited, cooldown, locked); never reachable by a user endpoint
create or replace function public.admin_recompute_stats(p_actor uuid, p_alcance text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_res jsonb;
begin
  if p_alcance is distinct from 'dificultad' then
    raise exception using errcode = 'P0001', message = 'STATS_SCOPE_INVALID';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('recompute:dificultad', 0));
  if exists (select 1 from public.lotes_actualizacion_ranking l where l.tipo = 'dificultad_admin' and l.iniciado_en > clock_timestamp() - make_interval(mins => private.config_int('recalculo_admin_cooldown_min', 10))) then
    raise exception using errcode = 'P0001', message = 'STATS_RECOMPUTE_COOLDOWN';
  end if;
  insert into public.lotes_actualizacion_ranking (tipo, estado, finalizado_en, detalle) values ('dificultad_admin', 'ok', clock_timestamp(), jsonb_build_object('actor', p_actor, 'alcance', p_alcance));
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
  values (p_actor, 'estadisticas_recalculo', 'estadisticas_globales_pregunta', null, null, jsonb_build_object('alcance', p_alcance));
  v_res := public.app_job_dificultad(true);
  return v_res;
end;
$$;
revoke all on function public.admin_recompute_stats(uuid, text) from public, anon, authenticated;
grant execute on function public.admin_recompute_stats(uuid, text) to service_role;

-- ---- 7. the hook in the answer function
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
  -- ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES + STATS
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

  -- STATS (C-S1): only when THIS call wrote the first answer of the user to the question (the guard row inside decides; a replay, a retry or a double click never counts twice).
  -- A failure here fails the whole answer: no half state.
  -- C-W3-3 decision: a real first answer to a question whose correction the user already saw in a demo is stored but does NOT feed stats, Nota media, league or ranking (same rule as cuenta_para_ranking).
  if v_first is true and p_opcion_id is not null and not v_visto_en_demo then
    perform private.app_apply_first_answer_stats(p_usuario_id, p_pregunta_id, v_es_correcta, v_ms, p_intento_id);
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


commit;
