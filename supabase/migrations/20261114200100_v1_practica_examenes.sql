-- V1 PRACTICE FROM CHOSEN EXAMS (owner plan 2026-11-14 "Generar examen", workstream F23, contract amendment FA47b). Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Requires 20261109100100 (practice: es_practica, private.practica_pool, app_practice_availability, app_start_practice_attempt).
--
-- Decision: the Home "Generar examen" lets the user pick 1..50 of THEIR exams; the practice draws only from those. Without a choice it is the 20261109100100 behaviour.
-- An exam can be chosen only if it is in the practice scope of 20261109100100: active, PUBLISHED, of the user's specialty, access verdict FREE / GRANTED / SUBSCRIPTION
-- (free exams count as owned, owner decision). A chosen exam outside that scope = outcome EXAM_NOT_ACCESSIBLE (Ktor 422 PRACTICE_EXAM_NOT_ACCESSIBLE), nothing created.
-- Availability adds examenes_adquiridos = the number of distinct exams with a CURRENT grant (contenido_desbloqueado_usuario, any motive, valido_desde <= now < valido_hasta|inf)
-- that are active, published and of the user's specialty: exactly the set D2-13 lists as vista=ADQUIRIDOS. The client shows "Generar examen" when it is >= 3.
-- Unchanged: es_demo / es_practica flagging (practice attempts are still es_demo = true), the per-user cap (50 starts / 24 h), the daily reveal limiter rule (counts unless every
-- question came from a GRANTED exam), the frozen machinery. app_start_practice_attempt(uuid, integer) is REPLACED by app_start_practice_attempt(uuid, integer, uuid[] default null):
-- an older Ktor image calls it with the two named arguments (PostgREST) and gets the same behaviour.
-- DEPLOY ORDER: this migration BEFORE the Ktor image that sends p_examen_ids and reads examenes_adquiridos (an older image ignores both).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). Restores the two bodies of 20261109100100 verbatim and drops the new helpers. Practice attempts already created stay.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_start_practice_attempt(uuid, integer, uuid[]);
-- drop function if exists private.practica_pool_examenes(uuid, timestamptz, uuid[]);
-- drop function if exists private.practica_examenes(uuid, timestamptz);
-- create or replace function public.app_practice_availability(p_usuario_id uuid)
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_n integer;
-- begin
--   if p_usuario_id is null then
--     return jsonb_build_object('preguntas', 0);
--   end if;
--   select count(*) into v_n from (select 1 from private.practica_pool(p_usuario_id, clock_timestamp()) limit 200) x;
--   return jsonb_build_object('preguntas', v_n);
-- end;
-- $$;
-- revoke all on function public.app_practice_availability(uuid) from public, anon, authenticated;
-- grant execute on function public.app_practice_availability(uuid) to service_role;
-- create or replace function public.app_start_practice_attempt(p_usuario_id uuid, p_preguntas integer)
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_now timestamptz;
--   v_especialidad uuid;
--   v_n integer;
--   v_intento uuid;
--   v_ids uuid[];
--   v_accesos text[];
--   v_hashes text[];
--   v_contenidos jsonb[];
--   v_soluciones jsonb[];
--   v_bound integer;
--   v_acceso text;
-- begin
--   if p_usuario_id is null then
--     return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
--   end if;
--   if p_preguntas is null or p_preguntas not in (5, 10, 20) then
--     return jsonb_build_object('outcome', 'INVALID_SIZE');
--   end if;
--   perform pg_advisory_xact_lock(hashtextextended('practica:' || p_usuario_id::text, 0));
--   v_now := clock_timestamp();
--   select pf.especialidad_principal_id into v_especialidad from public.perfiles_usuario pf where pf.usuario_id = p_usuario_id;
--   if v_especialidad is null then
--     return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
--   end if;
--   select count(*) into v_n from public.intentos_examen ie
--    where ie.usuario_id = p_usuario_id and ie.es_practica and ie.iniciado_en > v_now - interval '24 hours';
--   if v_n >= 50 then  -- fixed per-user cap (abuse guard; config_v1 only accepts the keys of config_v1_range)
--     return jsonb_build_object('outcome', 'PRACTICE_LIMIT');
--   end if;
--
--   select array_agg(x.pregunta_id order by x.r), array_agg(x.acceso order by x.r)
--     into v_ids, v_accesos
--     from (select pp.pregunta_id, pp.acceso, random() as r from private.practica_pool(p_usuario_id, v_now) pp order by r limit p_preguntas) x;
--   if v_ids is null then
--     return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
--   end if;
--   -- the daily reveal limiter (app_reveal_attempt_questions) exempts the attempt only when EVERY question came from a GRANTED exam
--   v_acceso := case when 'GRANTED' = all (v_accesos) then 'GRANTED' when 'SUBSCRIPTION' = any (v_accesos) then 'SUBSCRIPTION' else 'FREE' end;
--
--   update public.intentos_examen
--      set estado = 'abandonado',
--          metadatos = metadatos || jsonb_build_object('cerradoPor', 'app_start_practice_attempt', 'motivo', 'nueva_practica')
--    where usuario_id = p_usuario_id and es_practica and estado = 'en_progreso';
--
--   insert into public.intentos_examen
--     (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento, creado_por_comando, es_demo, es_practica, acceso_estado,
--      tipo_examen_snapshot, regla_anuladas, penalizacion_num, penalizacion_den, penalizacion_racional, reservas_usadas, total_anuladas, anuladas_sin_reserva)
--   values (p_usuario_id, null, 'en_progreso', cardinality(v_ids), v_especialidad, 'examen', 'app_start_exam_attempt', true, true, v_acceso,
--           'rafaga', 'NO_PUNTUA', 0, 1, false, 0, 0, 0)
--   returning id into v_intento;
--
--   select array_agg(encode(sha256(convert_to(c.contenido::text || '|' || coalesce(c.solucion::text, 'null'), 'UTF8')), 'hex') order by c.ord),
--          array_agg(c.contenido order by c.ord), array_agg(c.solucion order by c.ord)
--     into v_hashes, v_contenidos, v_soluciones
--     from (select u.id, u.ord, private.app_revision_pregunta_contenido(u.id) as contenido, private.app_revision_pregunta_solucion(u.id) as solucion
--             from unnest(v_ids) with ordinality as u(id, ord)) c;
--   insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
--   select u.pregunta_id, u.hash, u.contenido, u.solucion
--     from unnest(v_ids, v_hashes, v_contenidos, v_soluciones) as u(pregunta_id, hash, contenido, solucion)
--   on conflict (pregunta_id, hash_contenido) do nothing;
--   insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id, rol, anulada_al_inicio, sustituye_a_posicion)
--   select v_intento, u.pregunta_id, u.ord::integer, r.id, 'PRINCIPAL', false, null
--     from unnest(v_ids, v_hashes) with ordinality as u(pregunta_id, hash, ord)
--     join public.revisiones_pregunta r on r.pregunta_id = u.pregunta_id and r.hash_contenido = u.hash;
--   get diagnostics v_bound = row_count;
--   if v_bound <> cardinality(v_ids) then
--     raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001', detail = 'revision binding incomplete';
--   end if;
--   return jsonb_build_object('outcome', 'CREATED', 'intento_id', v_intento, 'total', cardinality(v_ids));
-- end;
-- $$;
-- revoke all on function public.app_start_practice_attempt(uuid, integer) from public, anon, authenticated;
-- grant execute on function public.app_start_practice_attempt(uuid, integer) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'practica_examenes_functions_not_service_role_only:' || f.n as check_name,
--        (case when to_regprocedure(f.n) is null or has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or (f.n like 'public.%' and not has_function_privilege('service_role', f.n, 'execute')) then 1 else 0 end)::bigint as failures
--   from (values ('public.app_start_practice_attempt(uuid,integer,uuid[])'), ('public.app_practice_availability(uuid)'),
--                ('private.practica_examenes(uuid,timestamptz)'), ('private.practica_pool_examenes(uuid,timestamptz,uuid[])')) f(n)
-- union all
-- select 'practica_old_start_signature_still_present', (case when to_regprocedure('public.app_start_practice_attempt(uuid,integer)') is null then 0 else 1 end)::bigint
-- union all
-- select 'practica_availability_without_examenes_adquiridos',
--        (case when pg_get_functiondef(to_regprocedure('public.app_practice_availability(uuid)')) like '%examenes_adquiridos%' then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_start_practice_attempt(uuid,integer)') is null or to_regprocedure('private.practica_pool(uuid,timestamptz)') is null
     or to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'es_practica') then
    raise exception using errcode = 'P0001', message = 'V1_PRACTICA_EXAMENES_PREFLIGHT', hint = 'apply 20261109100100 first';
  end if;
end
$$;

-- ---- 1. the exams a practice may draw from: the scope of private.practica_pool (active, PUBLISHED, the user's specialty, verdict FREE / GRANTED / SUBSCRIPTION)
create or replace function private.practica_examenes(p_usuario uuid, p_now timestamptz)
returns table(examen_id uuid, state text)
language sql
stable
security definer
set search_path = ''
as $$
  select e.id, v.state
    from public.perfiles_usuario pf
    join public.examenes e on e.especialidad_id = pf.especialidad_principal_id and e.activo and e.publicado_en is not null and e.publicado_en <= p_now
    cross join lateral private.exam_access_verdict(p_usuario, e.id, p_now) v
   where pf.usuario_id = p_usuario and v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION')
$$;
revoke all on function private.practica_examenes(uuid, timestamptz) from public, anon, authenticated;

-- ---- 2. the pool restricted to the chosen exams (p_examenes null = every exam of the scope, i.e. private.practica_pool). Same rules: main, not annulled, active, with a solution;
-- one row per question with its best access state among the CHOSEN exams (GRANTED > SUBSCRIPTION > FREE).
create or replace function private.practica_pool_examenes(p_usuario uuid, p_now timestamptz, p_examenes uuid[])
returns table(pregunta_id uuid, acceso text)
language sql
stable
security definer
set search_path = ''
as $$
  select pe.pregunta_id,
         (array_agg(ex.state order by case ex.state when 'GRANTED' then 0 when 'SUBSCRIPTION' then 1 else 2 end))[1]
    from private.practica_examenes(p_usuario, p_now) ex
    join public.preguntas_examen pe on pe.examen_id = ex.examen_id and pe.anulada is not true and not pe.es_reserva
    join public.preguntas p on p.id = pe.pregunta_id and p.activa
   where (p_examenes is null or ex.examen_id = any (p_examenes))
     and exists (select 1 from public.soluciones_pregunta sp where sp.pregunta_id = pe.pregunta_id and sp.opcion_correcta_id is not null)
   group by pe.pregunta_id
$$;
revoke all on function private.practica_pool_examenes(uuid, timestamptz, uuid[]) from public, anon, authenticated;

-- ---- 3. availability: + examenes_adquiridos (the D2-13 vista=ADQUIRIDOS set, counted once per exam)
create or replace function public.app_practice_availability(p_usuario_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_n integer;
  v_now timestamptz := clock_timestamp();
  v_adquiridos integer;
begin
  if p_usuario_id is null then
    return jsonb_build_object('preguntas', 0, 'examenes_adquiridos', 0);
  end if;
  select count(*) into v_n from (select 1 from private.practica_pool(p_usuario_id, v_now) limit 200) x;
  select count(distinct c.examen_id) into v_adquiridos
    from public.contenido_desbloqueado_usuario c
    join public.perfiles_usuario pf on pf.usuario_id = c.usuario_id
    join public.examenes e on e.id = c.examen_id and e.especialidad_id = pf.especialidad_principal_id
                          and e.activo and e.publicado_en is not null and e.publicado_en <= v_now
   where c.usuario_id = p_usuario_id and c.examen_id is not null
     and c.valido_desde <= v_now and (c.valido_hasta is null or c.valido_hasta > v_now);
  return jsonb_build_object('preguntas', v_n, 'examenes_adquiridos', v_adquiridos);
end;
$$;
revoke all on function public.app_practice_availability(uuid) from public, anon, authenticated;
grant execute on function public.app_practice_availability(uuid) to service_role;

-- ---- 4. start: optional p_examen_ids (1..50 distinct, every one in the practice scope). The two-argument signature is replaced (named-argument calls keep working).
drop function if exists public.app_start_practice_attempt(uuid, integer);
create or replace function public.app_start_practice_attempt(p_usuario_id uuid, p_preguntas integer, p_examen_ids uuid[] default null)
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
  v_fuera uuid[];
begin
  if p_usuario_id is null then
    return jsonb_build_object('outcome', 'PRACTICE_EMPTY');
  end if;
  if p_preguntas is null or p_preguntas not in (5, 10, 20) then
    return jsonb_build_object('outcome', 'INVALID_SIZE');
  end if;
  if p_examen_ids is not null and (coalesce(cardinality(p_examen_ids), 0) not between 1 and 50 or array_position(p_examen_ids, null) is not null
      or cardinality(p_examen_ids) <> (select count(distinct x) from unnest(p_examen_ids) x)) then
    return jsonb_build_object('outcome', 'INVALID_EXAMS');
  end if;
  perform pg_advisory_xact_lock(hashtextextended('practica:' || p_usuario_id::text, 0));
  v_now := clock_timestamp();
  select pf.especialidad_principal_id into v_especialidad from public.perfiles_usuario pf where pf.usuario_id = p_usuario_id;
  if p_examen_ids is not null then
    -- every chosen exam must be in the practice scope; checked before the specialty / cap answers so a wrong id is always reported as such
    select coalesce(array_agg(x order by x), '{}') into v_fuera
      from unnest(p_examen_ids) x
     where not exists (select 1 from private.practica_examenes(p_usuario_id, v_now) pe where pe.examen_id = x);
    if cardinality(v_fuera) > 0 then
      return jsonb_build_object('outcome', 'EXAM_NOT_ACCESSIBLE', 'examen_ids', to_jsonb(v_fuera));
    end if;
  end if;
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
    from (select pp.pregunta_id, pp.acceso, random() as r from private.practica_pool_examenes(p_usuario_id, v_now, p_examen_ids) pp order by r limit p_preguntas) x;
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
     tipo_examen_snapshot, regla_anuladas, penalizacion_num, penalizacion_den, penalizacion_racional, reservas_usadas, total_anuladas, anuladas_sin_reserva, metadatos)
  values (p_usuario_id, null, 'en_progreso', cardinality(v_ids), v_especialidad, 'examen', 'app_start_exam_attempt', true, true, v_acceso,
          'rafaga', 'NO_PUNTUA', 0, 1, false, 0, 0, 0,
          case when p_examen_ids is null then '{}'::jsonb else jsonb_build_object('practicaExamenes', to_jsonb(p_examen_ids)) end)
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
revoke all on function public.app_start_practice_attempt(uuid, integer, uuid[]) from public, anon, authenticated;
grant execute on function public.app_start_practice_attempt(uuid, integer, uuid[]) to service_role;

commit;
