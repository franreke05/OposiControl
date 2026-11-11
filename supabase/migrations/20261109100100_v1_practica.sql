-- V1 PRACTICE TESTS "OposiQuizzes rapidos" (owner decision 2026-11-09, workstream F23, contract amendment FA47). Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Requires 20261022101000 (demo mode), 20261022101100 (limiter), 20261022101300 (official rules), 20261022101600 (stats) and 20261108100300 (auto demo: the body of app_start_exam_attempt replaced here).
--
-- Decision: a random test of 5 / 10 / 20 questions drawn ONLY from the exams the user can open (access verdict FREE / GRANTED / SUBSCRIPTION) of the user's specialty; no access = nothing to draw
-- (the client invites to the Market). PRACTICE ONLY: corrected and with a result, but never ranking, league, streak, stats, OT, referral or Mis examenes.
-- Design: REUSE the exam attempt. A practice attempt is a row of intentos_examen with es_practica = true, examen_id NULL (its questions come from several exams) and es_demo = true, so every
-- "never counts" rule already written for the demo applies unchanged (preparar_intento_examen: not first attempt, not ranking, no numbering; app_record_attempt_answer: never the first answer,
-- no stats, and a later real first answer to a question already seen corrected does not count (C-D4 / C-W3-3); app_my_exams_collection, stats, referral and challenge exclude es_demo).
-- Questions are frozen like any attempt (immutable revision, rol PRINCIPAL, NO_PUNTUA without penalty), so answers, finish, result and review use the existing RPCs and routes.
-- Changed bodies (minimal): validar_y_calcular_respuesta_intento accepts the exam-less practice attempt; app_start_exam_attempt does not count practice attempts in the per-user DEMO quota;
-- app_reveal_attempt_questions does NOT exempt a practice attempt by its es_demo flag: it is limited like a normal attempt unless all its questions came from GRANTED exams;
-- it also fixes a latent error of that body: a first page larger than the whole limit (nothing revealed yet) failed with a negative OFFSET instead of DAILY_LIMIT_REACHED.
-- New: private.practica_pool, public.app_practice_availability (Home), public.app_start_practice_attempt (fixed per-user cap: 50 starts / 24 h).
-- DEPLOY ORDER: this migration BEFORE the Ktor image that selects es_practica (that image reads the column in every attempt read; an older image ignores it).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). It DELETES the practice attempts (they never counted anywhere; their answers and frozen questions cascade) because the restored
-- origin check forbids an exam attempt without an exam. The three changed bodies are restored verbatim below.
-- ROLLBACK-BEGIN
-- begin;
-- delete from public.intentos_examen where es_practica;
-- drop function if exists public.app_start_practice_attempt(uuid, integer);
-- drop function if exists public.app_practice_availability(uuid);
-- drop function if exists private.practica_pool(uuid, timestamptz);
-- CREATE OR REPLACE FUNCTION public.validar_y_calcular_respuesta_intento()
--  RETURNS trigger
--  LANGUAGE plpgsql
--  SET search_path TO 'public'
-- AS $function$
-- declare
--     v_usuario_id uuid;
--     v_examen_id uuid;
--     v_cuenta_para_ranking boolean;
--     v_pregunta_de_opcion uuid;
--     v_opcion_correcta uuid;
--     v_posicion int;
--     v_etiqueta_id uuid;
--     v_respuestas_previas_usuario int;
-- begin
--     select usuario_id, examen_id, cuenta_para_ranking
--     into v_usuario_id, v_examen_id, v_cuenta_para_ranking
--     from public.intentos_examen
--     where id = new.intento_id;
--
--     if v_usuario_id is null or v_examen_id is null then
--         raise exception 'El intento de examen no existe o no tiene usuario/examen asociado';
--     end if;
--
--     new.usuario_id = coalesce(new.usuario_id, v_usuario_id);
--     new.examen_id = coalesce(new.examen_id, v_examen_id);
--     new.cuenta_para_ranking = coalesce(new.cuenta_para_ranking, v_cuenta_para_ranking);
--
--     select posicion
--     into v_posicion
--     from public.preguntas_examen
--     where examen_id = v_examen_id
--       and pregunta_id = new.pregunta_id;
--
--     new.posicion_pregunta = coalesce(new.posicion_pregunta, v_posicion);
--
--     select ep.etiqueta_id
--     into v_etiqueta_id
--     from public.etiquetas_pregunta ep
--     where ep.pregunta_id = new.pregunta_id
--     order by ep.creado_en asc
--     limit 1;
--
--     new.etiqueta_principal_id = coalesce(new.etiqueta_principal_id, v_etiqueta_id);
--
--     if new.opcion_seleccionada_id is not null then
--         select pregunta_id
--         into v_pregunta_de_opcion
--         from public.opciones_pregunta
--         where id = new.opcion_seleccionada_id;
--
--         if v_pregunta_de_opcion is null or v_pregunta_de_opcion <> new.pregunta_id then
--             raise exception 'La opcion seleccionada no pertenece a la pregunta indicada';
--         end if;
--
--         select opcion_correcta_id
--         into v_opcion_correcta
--         from public.soluciones_pregunta
--         where pregunta_id = new.pregunta_id;
--
--         new.opcion_correcta_id = coalesce(new.opcion_correcta_id, v_opcion_correcta);
--
--         if v_opcion_correcta is not null then
--             new.es_correcta = (new.opcion_seleccionada_id = v_opcion_correcta);
--         end if;
--     end if;
--
--     select count(*)
--     into v_respuestas_previas_usuario
--     from public.respuestas_intento ri
--     where ri.usuario_id = v_usuario_id
--       and ri.pregunta_id = new.pregunta_id
--       and ri.id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);
--
--     new.es_primer_intento_pregunta = coalesce(new.es_primer_intento_pregunta, v_respuestas_previas_usuario = 0);
--
--     if new.respondida_en is null and new.opcion_seleccionada_id is not null then
--         new.respondida_en = now();
--     end if;
--
--     if new.nivel_confianza = 'azar' then
--         new.respondida_al_azar = true;
--     end if;
--
--     return new;
-- end;
-- $function$
-- ;
-- create or replace function public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid, p_modo text)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_especialidad_id uuid;
--   v_intento_id uuid;
--   v_total integer;
--   v_frozen integer;
--   v_try integer;
--   v_marcador text;
--   v_otro_demo boolean;
--   v_sin_marcador uuid;
--   sv record;
--   v_n integer;
--   v_demo boolean := false;
--   v_acceso text;
-- begin
--   if p_usuario_id is null or p_examen_id is null then
--     return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
--   end if;
--   select e.especialidad_id into v_especialidad_id
--   from public.examenes e
--   where e.id = p_examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= now();
--   if not found then
--     return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
--   end if;
--   -- C-A1: the ONE start decision (access verdict + demo decision) lives in SQL; Kotlin keeps a parity-tested twin for its error mapping.
--   -- The DEMO decision is enforced HERE (the effective demo set, the demo state and the per-user limit). A NORMAL start keeps trusting the route's policy check (as before); this function only
--   -- records the access state it can read, for the daily limiter (unknown = not exempt, fail closed).
--   select * into sv from private.exam_start_verdict(p_usuario_id, p_examen_id, p_modo, clock_timestamp());
--   if upper(coalesce(p_modo, 'NORMAL')) = 'DEMO' then
--     if not found or not sv.allowed then
--       return jsonb_build_object('outcome', case when sv.reason in ('SPECIALTY_MISMATCH') then 'EXAM_NOT_FOUND' when sv.reason = 'CAPABILITY_DISABLED' then 'SPECIALTY_TYPE_NOT_ALLOWED' else 'EXAM_LOCKED' end, 'state', sv.state);
--     end if;
--     v_demo := sv.demo;
--     v_acceso := sv.acceso_estado;
--   elsif found and sv.allowed then
--     v_acceso := sv.acceso_estado;
--   end if;
--   -- DEMO starts are limited per user per day (the per-IP limit lives in the HTTP layer)
--   if v_demo then
--     select count(*) into v_n from public.intentos_examen ie
--      where ie.usuario_id = p_usuario_id and ie.es_demo and ie.iniciado_en > clock_timestamp() - interval '24 hours';
--     if v_n >= private.config_int('demo_inicios_usuario_dia', 20) then
--       return jsonb_build_object('outcome', 'DEMO_LIMIT');
--     end if;
--   end if;
--
--   for v_try in 1..3 loop
--     v_sin_marcador := null;
--     select ie.id, ie.creado_por_comando, ie.es_demo into v_intento_id, v_marcador, v_otro_demo
--     from public.intentos_examen ie
--     where ie.usuario_id = p_usuario_id and ie.examen_id = p_examen_id and ie.estado = 'en_progreso'
--     limit 1;
--     if found and v_marcador is not null then
--       if v_otro_demo = v_demo then
--         return jsonb_build_object('outcome', 'RESUMED', 'intento_id', v_intento_id, 'es_demo', v_demo);
--       end if;
--       -- the open attempt is of the OTHER kind (a demo that outlived the purchase, or a real attempt of a user who lost access): it is closed, never resumed as the wrong kind
--       update public.intentos_examen set estado = 'abandonado',
--              metadatos = metadatos || jsonb_build_object('cerradoPor', 'app_start_exam_attempt', 'motivo', 'cambio_de_modo')
--        where id = v_intento_id and estado = 'en_progreso';
--       v_intento_id := null;
--     elsif found then
--       v_sin_marcador := v_intento_id;
--     end if;
--
--     if v_demo then
--       v_total := coalesce(cardinality(private.demo_preguntas_efectivas(p_examen_id)), 0);
--     else
--       select count(*) into v_total
--       from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
--       where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa;
--     end if;
--     if v_total = 0 then
--       return jsonb_build_object('outcome', 'EXAM_EMPTY');
--     end if;
--     if v_total > 200 then
--       return jsonb_build_object('outcome', 'EXAM_TOO_LARGE', 'total', v_total);
--     end if;
--
--     if v_sin_marcador is not null then
--       update public.intentos_examen
--          set estado = 'abandonado',
--              metadatos = metadatos || jsonb_build_object('cerradoPor', 'app_start_exam_attempt', 'motivo', 'intento_sin_marcador_de_comando')
--        where id = v_sin_marcador and estado = 'en_progreso' and creado_por_comando is null;
--     end if;
--
--     v_intento_id := null;
--     insert into public.intentos_examen as nuevo
--       (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento, creado_por_comando, es_demo, acceso_estado)
--     values (p_usuario_id, p_examen_id, 'en_progreso', v_total, v_especialidad_id, 'examen', 'app_start_exam_attempt', v_demo, v_acceso)
--     on conflict (usuario_id, examen_id) where estado = 'en_progreso' do nothing
--     returning nuevo.id into v_intento_id;
--
--     if v_intento_id is not null then
--       v_frozen := private.app_congelar_preguntas_intento(v_intento_id, p_examen_id, v_demo);
--       if v_frozen = 0 or v_frozen > 200 then
--         raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
--       end if;
--       if v_frozen <> v_total then
--         update public.intentos_examen set total_preguntas = v_frozen where id = v_intento_id;
--       end if;
--       return jsonb_build_object('outcome', 'CREATED', 'intento_id', v_intento_id, 'es_demo', v_demo);
--     end if;
--   end loop;
--   raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
-- end;
-- $$;
-- revoke all on function public.app_start_exam_attempt(uuid, uuid, text) from public, anon, authenticated;
-- grant execute on function public.app_start_exam_attempt(uuid, uuid, text) to service_role;
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
--   if exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = any (p_preguntas) and pi.rol = 'RESERVA_NO_USADA') then
--     return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');  -- OFFICIAL_RULES C-O1
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
-- drop index if exists public.idx_intentos_examen_practica_abierta;
-- alter table public.intentos_examen drop constraint if exists intentos_examen_practica_check;
-- alter table public.intentos_examen drop constraint if exists intentos_examen_origen_examen_check;
-- alter table public.intentos_examen add constraint intentos_examen_origen_examen_check check ((((origen_intento = 'examen'::text) and (examen_id is not null)) or ((origen_intento = 'repaso_random'::text) and (examen_id is null))));
-- alter table public.intentos_examen drop column if exists es_practica;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'practica_functions_not_service_role_only:' || f.n as check_name,
--        (case when to_regprocedure(f.n) is null or has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or (f.n like 'public.%' and not has_function_privilege('service_role', f.n, 'execute')) then 1 else 0 end)::bigint as failures
--   from (values ('public.app_start_practice_attempt(uuid,integer)'), ('public.app_practice_availability(uuid)'), ('private.practica_pool(uuid,timestamptz)')) f(n)
-- union all
-- select 'practica_column_or_checks_missing',
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'es_practica')
--               and exists (select 1 from pg_constraint where conname = 'intentos_examen_practica_check' and conrelid = 'public.intentos_examen'::regclass)
--               and exists (select 1 from pg_constraint where conname = 'intentos_examen_origen_examen_check' and conrelid = 'public.intentos_examen'::regclass) then 0 else 1 end)::bigint
-- union all
-- select 'practica_bodies_not_redefined:' || f.n,
--        (case when to_regprocedure(f.n) is not null and pg_get_functiondef(to_regprocedure(f.n)) like '%es_practica%' then 0 else 1 end)::bigint
--   from (values ('public.validar_y_calcular_respuesta_intento()'), ('public.app_start_exam_attempt(uuid,uuid,text)'), ('public.app_reveal_attempt_questions(uuid,uuid,uuid[])')) f(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null or to_regprocedure('private.demo_preguntas_efectivas(uuid)') is null
     or to_regprocedure('private.app_revision_pregunta_contenido(uuid)') is null or to_regprocedure('private.app_apply_first_answer_stats(uuid,uuid,boolean,integer,uuid)') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'regla_anuladas') then
    raise exception using errcode = 'P0001', message = 'V1_PRACTICA_PREFLIGHT', hint = 'apply 20261022101000..101600 and 20261108100300 first';
  end if;
end
$$;

-- ---- 1. schema: a practice attempt is an exam attempt WITHOUT an exam (es_practica), always es_demo (so every "never counts" rule of the demo applies unchanged)
alter table public.intentos_examen add column if not exists es_practica boolean not null default false;
alter table public.intentos_examen drop constraint if exists intentos_examen_origen_examen_check;
alter table public.intentos_examen add constraint intentos_examen_origen_examen_check
  check ((origen_intento = 'examen' and (examen_id is not null or es_practica)) or (origen_intento = 'repaso_random' and examen_id is null));
alter table public.intentos_examen drop constraint if exists intentos_examen_practica_check;
alter table public.intentos_examen add constraint intentos_examen_practica_check
  check (not es_practica or (es_demo and examen_id is null and origen_intento = 'examen'));
create index if not exists idx_intentos_examen_practica_abierta on public.intentos_examen (usuario_id) where es_practica and estado = 'en_progreso';

-- ---- 2. the pool: active main non-annulled questions of the PUBLISHED exams of the user's specialty that the user can open (access verdict FREE / GRANTED / SUBSCRIPTION).
-- One row per question (a question linked to several exams keeps its best access state: GRANTED > SUBSCRIPTION > FREE). No access, no specialty or no exam = no rows.
create or replace function private.practica_pool(p_usuario uuid, p_now timestamptz)
returns table(pregunta_id uuid, acceso text)
language sql
stable
security definer
set search_path = ''
as $$
  with ex as (
    select e.id, v.state
      from public.perfiles_usuario pf
      join public.examenes e on e.especialidad_id = pf.especialidad_principal_id and e.activo and e.publicado_en is not null and e.publicado_en <= p_now
      cross join lateral private.exam_access_verdict(p_usuario, e.id, p_now) v
     where pf.usuario_id = p_usuario and v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION')
  )
  select pe.pregunta_id,
         (array_agg(ex.state order by case ex.state when 'GRANTED' then 0 when 'SUBSCRIPTION' then 1 else 2 end))[1]
    from ex
    join public.preguntas_examen pe on pe.examen_id = ex.id and pe.anulada is not true and not pe.es_reserva
    join public.preguntas p on p.id = pe.pregunta_id and p.activa
   where exists (select 1 from public.soluciones_pregunta sp where sp.pregunta_id = pe.pregunta_id and sp.opcion_correcta_id is not null)
   group by pe.pregunta_id
$$;
revoke all on function private.practica_pool(uuid, timestamptz) from public, anon, authenticated;

-- ---- 3. availability (Home): how many questions a practice test can draw from right now (capped at 200; 0 = the section invites to the Market)
create or replace function public.app_practice_availability(p_usuario_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_n integer;
begin
  if p_usuario_id is null then
    return jsonb_build_object('preguntas', 0);
  end if;
  select count(*) into v_n from (select 1 from private.practica_pool(p_usuario_id, clock_timestamp()) limit 200) x;
  return jsonb_build_object('preguntas', v_n);
end;
$$;
revoke all on function public.app_practice_availability(uuid) from public, anon, authenticated;
grant execute on function public.app_practice_availability(uuid) to service_role;

-- ---- 4. start: a NEW practice attempt with N (5 | 10 | 20) random questions of the pool (fewer when the pool is smaller). Generating closes the previous open practice
-- (a new test was asked for; the HTTP Idempotency-Key makes a retried start return the same attempt). Same frozen machinery as an exam attempt: immutable revisions,
-- rol PRINCIPAL, NO_PUNTUA without penalty; answers, finish, result and review go through the existing attempt RPCs and routes.
create or replace function public.app_start_practice_attempt(p_usuario_id uuid, p_preguntas integer)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_especialidad uuid;
  v_n integer;
  v_intento uuid;
  v_ids uuid[];
  v_accesos text[];
  v_hashes text[];
  v_contenidos jsonb[];
  v_soluciones jsonb[];
  v_bound integer;
  v_acceso text;
begin
  if p_usuario_id is null then
    return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
  end if;
  if p_preguntas is null or p_preguntas not in (5, 10, 20) then
    return jsonb_build_object('outcome', 'INVALID_SIZE');
  end if;
  perform pg_advisory_xact_lock(hashtextextended('practica:' || p_usuario_id::text, 0));
  v_now := clock_timestamp();
  select pf.especialidad_principal_id into v_especialidad from public.perfiles_usuario pf where pf.usuario_id = p_usuario_id;
  if v_especialidad is null then
    return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
  end if;
  select count(*) into v_n from public.intentos_examen ie
   where ie.usuario_id = p_usuario_id and ie.es_practica and ie.iniciado_en > v_now - interval '24 hours';
  if v_n >= 50 then  -- fixed per-user cap (abuse guard; config_v1 only accepts the keys of config_v1_range)
    return jsonb_build_object('outcome', 'PRACTICE_LIMIT');
  end if;

  select array_agg(x.pregunta_id order by x.r), array_agg(x.acceso order by x.r)
    into v_ids, v_accesos
    from (select pp.pregunta_id, pp.acceso, random() as r from private.practica_pool(p_usuario_id, v_now) pp order by r limit p_preguntas) x;
  if v_ids is null then
    return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
  end if;
  -- the daily reveal limiter (app_reveal_attempt_questions) exempts the attempt only when EVERY question came from a GRANTED exam
  v_acceso := case when 'GRANTED' = all (v_accesos) then 'GRANTED' when 'SUBSCRIPTION' = any (v_accesos) then 'SUBSCRIPTION' else 'FREE' end;

  update public.intentos_examen
     set estado = 'abandonado',
         metadatos = metadatos || jsonb_build_object('cerradoPor', 'app_start_practice_attempt', 'motivo', 'nueva_practica')
   where usuario_id = p_usuario_id and es_practica and estado = 'en_progreso';

  insert into public.intentos_examen
    (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento, creado_por_comando, es_demo, es_practica, acceso_estado,
     tipo_examen_snapshot, regla_anuladas, penalizacion_num, penalizacion_den, penalizacion_racional, reservas_usadas, total_anuladas, anuladas_sin_reserva)
  values (p_usuario_id, null, 'en_progreso', cardinality(v_ids), v_especialidad, 'examen', 'app_start_exam_attempt', true, true, v_acceso,
          'rafaga', 'NO_PUNTUA', 0, 1, false, 0, 0, 0)
  returning id into v_intento;

  select array_agg(encode(sha256(convert_to(c.contenido::text || '|' || coalesce(c.solucion::text, 'null'), 'UTF8')), 'hex') order by c.ord),
         array_agg(c.contenido order by c.ord), array_agg(c.solucion order by c.ord)
    into v_hashes, v_contenidos, v_soluciones
    from (select u.id, u.ord, private.app_revision_pregunta_contenido(u.id) as contenido, private.app_revision_pregunta_solucion(u.id) as solucion
            from unnest(v_ids) with ordinality as u(id, ord)) c;
  insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
  select u.pregunta_id, u.hash, u.contenido, u.solucion
    from unnest(v_ids, v_hashes, v_contenidos, v_soluciones) as u(pregunta_id, hash, contenido, solucion)
  on conflict (pregunta_id, hash_contenido) do nothing;
  insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id, rol, anulada_al_inicio, sustituye_a_posicion)
  select v_intento, u.pregunta_id, u.ord::integer, r.id, 'PRINCIPAL', false, null
    from unnest(v_ids, v_hashes) with ordinality as u(pregunta_id, hash, ord)
    join public.revisiones_pregunta r on r.pregunta_id = u.pregunta_id and r.hash_contenido = u.hash;
  get diagnostics v_bound = row_count;
  if v_bound <> cardinality(v_ids) then
    raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001', detail = 'revision binding incomplete';
  end if;
  return jsonb_build_object('outcome', 'CREATED', 'intento_id', v_intento, 'total', cardinality(v_ids));
end;
$$;
revoke all on function public.app_start_practice_attempt(uuid, integer) from public, anon, authenticated;
grant execute on function public.app_start_practice_attempt(uuid, integer) to service_role;

-- ---- 5. the three changed bodies
create or replace function public.validar_y_calcular_respuesta_intento()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    v_usuario_id uuid;
    v_examen_id uuid;
    v_cuenta_para_ranking boolean;
    v_pregunta_de_opcion uuid;
    v_opcion_correcta uuid;
    v_posicion int;
    v_etiqueta_id uuid;
    v_respuestas_previas_usuario int;
    v_practica boolean;
begin
    select usuario_id, examen_id, cuenta_para_ranking, es_practica
    into v_usuario_id, v_examen_id, v_cuenta_para_ranking, v_practica
    from public.intentos_examen
    where id = new.intento_id;

    -- PRACTICA (F23): a practice attempt has no exam (its questions come from several exams); it is the only attempt allowed without one
    if v_usuario_id is null or (v_examen_id is null and not coalesce(v_practica, false)) then
        raise exception 'El intento de examen no existe o no tiene usuario/examen asociado';
    end if;

    new.usuario_id = coalesce(new.usuario_id, v_usuario_id);
    new.examen_id = coalesce(new.examen_id, v_examen_id);
    new.cuenta_para_ranking = coalesce(new.cuenta_para_ranking, v_cuenta_para_ranking);

    select posicion
    into v_posicion
    from public.preguntas_examen
    where examen_id = v_examen_id
      and pregunta_id = new.pregunta_id;

    new.posicion_pregunta = coalesce(new.posicion_pregunta, v_posicion);

    select ep.etiqueta_id
    into v_etiqueta_id
    from public.etiquetas_pregunta ep
    where ep.pregunta_id = new.pregunta_id
    order by ep.creado_en asc
    limit 1;

    new.etiqueta_principal_id = coalesce(new.etiqueta_principal_id, v_etiqueta_id);

    if new.opcion_seleccionada_id is not null then
        select pregunta_id
        into v_pregunta_de_opcion
        from public.opciones_pregunta
        where id = new.opcion_seleccionada_id;

        if v_pregunta_de_opcion is null or v_pregunta_de_opcion <> new.pregunta_id then
            raise exception 'La opcion seleccionada no pertenece a la pregunta indicada';
        end if;

        select opcion_correcta_id
        into v_opcion_correcta
        from public.soluciones_pregunta
        where pregunta_id = new.pregunta_id;

        new.opcion_correcta_id = coalesce(new.opcion_correcta_id, v_opcion_correcta);

        if v_opcion_correcta is not null then
            new.es_correcta = (new.opcion_seleccionada_id = v_opcion_correcta);
        end if;
    end if;

    select count(*)
    into v_respuestas_previas_usuario
    from public.respuestas_intento ri
    where ri.usuario_id = v_usuario_id
      and ri.pregunta_id = new.pregunta_id
      and ri.id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);

    new.es_primer_intento_pregunta = coalesce(new.es_primer_intento_pregunta, v_respuestas_previas_usuario = 0);

    if new.respondida_en is null and new.opcion_seleccionada_id is not null then
        new.respondida_en = now();
    end if;

    if new.nivel_confianza = 'azar' then
        new.respondida_al_azar = true;
    end if;

    return new;
end;
$function$;

create or replace function public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid, p_modo text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_especialidad_id uuid;
  v_intento_id uuid;
  v_total integer;
  v_frozen integer;
  v_try integer;
  v_marcador text;
  v_otro_demo boolean;
  v_sin_marcador uuid;
  sv record;
  v_n integer;
  v_demo boolean := false;
  v_acceso text;
begin
  if p_usuario_id is null or p_examen_id is null then
    return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
  end if;
  select e.especialidad_id into v_especialidad_id
  from public.examenes e
  where e.id = p_examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= now();
  if not found then
    return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
  end if;
  -- C-A1: the ONE start decision (access verdict + demo decision) lives in SQL; Kotlin keeps a parity-tested twin for its error mapping.
  -- The DEMO decision is enforced HERE (the effective demo set, the demo state and the per-user limit). A NORMAL start keeps trusting the route's policy check (as before); this function only
  -- records the access state it can read, for the daily limiter (unknown = not exempt, fail closed).
  select * into sv from private.exam_start_verdict(p_usuario_id, p_examen_id, p_modo, clock_timestamp());
  if upper(coalesce(p_modo, 'NORMAL')) = 'DEMO' then
    if not found or not sv.allowed then
      return jsonb_build_object('outcome', case when sv.reason in ('SPECIALTY_MISMATCH') then 'EXAM_NOT_FOUND' when sv.reason = 'CAPABILITY_DISABLED' then 'SPECIALTY_TYPE_NOT_ALLOWED' else 'EXAM_LOCKED' end, 'state', sv.state);
    end if;
    v_demo := sv.demo;
    v_acceso := sv.acceso_estado;
  elsif found and sv.allowed then
    v_acceso := sv.acceso_estado;
  end if;
  -- DEMO starts are limited per user per day (the per-IP limit lives in the HTTP layer); practice attempts (es_practica, also es_demo) are not demo starts
  if v_demo then
    select count(*) into v_n from public.intentos_examen ie
     where ie.usuario_id = p_usuario_id and ie.es_demo and not ie.es_practica and ie.iniciado_en > clock_timestamp() - interval '24 hours';  -- PRACTICA (F23): practice attempts never use the demo quota
    if v_n >= private.config_int('demo_inicios_usuario_dia', 20) then
      return jsonb_build_object('outcome', 'DEMO_LIMIT');
    end if;
  end if;

  for v_try in 1..3 loop
    v_sin_marcador := null;
    select ie.id, ie.creado_por_comando, ie.es_demo into v_intento_id, v_marcador, v_otro_demo
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id = p_examen_id and ie.estado = 'en_progreso'
    limit 1;
    if found and v_marcador is not null then
      if v_otro_demo = v_demo then
        return jsonb_build_object('outcome', 'RESUMED', 'intento_id', v_intento_id, 'es_demo', v_demo);
      end if;
      -- the open attempt is of the OTHER kind (a demo that outlived the purchase, or a real attempt of a user who lost access): it is closed, never resumed as the wrong kind
      update public.intentos_examen set estado = 'abandonado',
             metadatos = metadatos || jsonb_build_object('cerradoPor', 'app_start_exam_attempt', 'motivo', 'cambio_de_modo')
       where id = v_intento_id and estado = 'en_progreso';
      v_intento_id := null;
    elsif found then
      v_sin_marcador := v_intento_id;
    end if;

    if v_demo then
      v_total := coalesce(cardinality(private.demo_preguntas_efectivas(p_examen_id)), 0);
    else
      select count(*) into v_total
      from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
      where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa;
    end if;
    if v_total = 0 then
      return jsonb_build_object('outcome', 'EXAM_EMPTY');
    end if;
    if v_total > 200 then
      return jsonb_build_object('outcome', 'EXAM_TOO_LARGE', 'total', v_total);
    end if;

    if v_sin_marcador is not null then
      update public.intentos_examen
         set estado = 'abandonado',
             metadatos = metadatos || jsonb_build_object('cerradoPor', 'app_start_exam_attempt', 'motivo', 'intento_sin_marcador_de_comando')
       where id = v_sin_marcador and estado = 'en_progreso' and creado_por_comando is null;
    end if;

    v_intento_id := null;
    insert into public.intentos_examen as nuevo
      (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento, creado_por_comando, es_demo, acceso_estado)
    values (p_usuario_id, p_examen_id, 'en_progreso', v_total, v_especialidad_id, 'examen', 'app_start_exam_attempt', v_demo, v_acceso)
    on conflict (usuario_id, examen_id) where estado = 'en_progreso' do nothing
    returning nuevo.id into v_intento_id;

    if v_intento_id is not null then
      v_frozen := private.app_congelar_preguntas_intento(v_intento_id, p_examen_id, v_demo);
      if v_frozen = 0 or v_frozen > 200 then
        raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
      end if;
      if v_frozen <> v_total then
        update public.intentos_examen set total_preguntas = v_frozen where id = v_intento_id;
      end if;
      return jsonb_build_object('outcome', 'CREATED', 'intento_id', v_intento_id, 'es_demo', v_demo);
    end if;
  end loop;
  raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
end;
$$;
revoke all on function public.app_start_exam_attempt(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.app_start_exam_attempt(uuid, uuid, text) to service_role;

create or replace function public.app_reveal_attempt_questions(p_usuario uuid, p_intento uuid, p_preguntas uuid[])
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_demo boolean;
  v_practica boolean;
  v_acceso text;
  v_now timestamptz;
  v_limit integer;
  v_nuevas uuid[];
  v_actual integer;
  v_exceso integer;
  v_libera timestamptz;
begin
  select ie.es_demo, ie.es_practica, ie.acceso_estado into v_demo, v_practica, v_acceso
    from public.intentos_examen ie where ie.id = p_intento and ie.usuario_id = p_usuario and ie.creado_por_comando is not null;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento and pi.pregunta_id = any (p_preguntas) and pi.rol = 'RESERVA_NO_USADA') then
    return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');  -- OFFICIAL_RULES C-O1
  end if;
  -- PRACTICA (F23): a practice attempt is es_demo but NOT exempt: it is limited like a normal attempt unless every question came from a GRANTED exam (acceso_estado = 'GRANTED')
  if (v_demo and not v_practica) or v_acceso in ('GRANTED', 'DEMO') or coalesce(cardinality(p_preguntas), 0) = 0 then
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
     order by r.revelada_en offset greatest(least(v_exceso, v_actual) - 1, 0) limit 1;  -- F23: a first page larger than the limit (nothing revealed yet) no longer fails with a negative OFFSET
    return jsonb_build_object('outcome', 'DAILY_LIMIT_REACHED', 'retry_after_seconds',
      greatest(1, ceil(extract(epoch from (coalesce(v_libera, v_now + interval '24 hours') - v_now)))::integer), 'limit', v_limit);
  end if;
  insert into public.preguntas_reveladas_usuario (usuario_id, pregunta_id, revelada_en)
  select p_usuario, q, v_now from unnest(v_nuevas) as q
  on conflict (usuario_id, pregunta_id) do update set revelada_en = excluded.revelada_en;
  return jsonb_build_object('outcome', 'OK');
end;
$$;

commit;
