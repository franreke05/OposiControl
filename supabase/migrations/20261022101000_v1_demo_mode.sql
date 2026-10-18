-- V1 DEMO MODE (client answers 2026-10-16: untouchable, in V1; E1-V1-ACCESS-V2-DESIGN.md v2.1, E2 conditions C-A1, C-D1..C-D4, C-L3).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022100900.
--
-- Fixing the 5: preguntas_examen.es_demo (Control marks the questions; exactly config 'demo_preguntas' non-annulled active ones). An exam can only SWITCH demo on when its set is complete
-- (DEMO_SET_INCOMPLETE) and a set cannot grow beyond the configured size (DEMO_SET_FULL); a set that breaks later (annulled / deactivated question) makes the exam stop offering the demo (fail closed).
-- Start: public.app_start_exam_attempt(user, exam, modo) is the authority: private.exam_start_verdict (ONE decision, access verdict + demo) decides; a demo attempt is created with es_demo = true and
-- ONLY the flagged questions are frozen into preguntas_intento, so every attempt-bound read and the answer function can never reach a sixth question (QUESTION_NOT_IN_ATTEMPT). The access state is stored
-- on the attempt (acceso_estado) for the daily limiter (exempt by the stored state). A user WITH access that sends DEMO gets a normal attempt. An open attempt of the other kind is closed, never resumed.
-- Never counts: es_demo attempts are not the first attempt, not ranking, do not shift the numbering (preparar_intento_examen), never consume the first answer (app_record_attempt_answer) and do not appear in
-- Mis examenes. A real first answer to a question already seen corrected in a demo is stored as the first answer but does NOT count for ranking (C-D4).
-- app_start_exam_attempt(uuid, uuid) stays as a wrapper (NORMAL) so existing callers keep working.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate; function bodies are restored by re-applying the earlier migration files in order)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_examenes_demo_completo on public.examenes;
-- drop function if exists private.tg_examenes_demo_completo();
-- drop trigger if exists trg_preguntas_examen_demo on public.preguntas_examen;
-- drop function if exists private.tg_preguntas_examen_demo();
-- drop function if exists public.app_start_exam_attempt(uuid, uuid, text);
-- drop function if exists private.app_congelar_preguntas_intento(uuid, uuid, boolean);
-- drop function if exists private.exam_start_verdict(uuid, uuid, text, timestamptz);
-- drop function if exists private.demo_set_ok(uuid);
-- drop index if exists public.idx_preguntas_examen_demo;
-- alter table public.intentos_examen drop constraint if exists intentos_examen_acceso_estado_check;
-- alter table public.intentos_examen drop column if exists acceso_estado;
-- alter table public.intentos_examen drop column if exists es_demo;
-- alter table public.preguntas_examen drop column if exists es_demo;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'demo_functions_not_service_role_only:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or (f.n like 'public.%' and not has_function_privilege('service_role', f.n, 'execute')) then 1 else 0 end)::bigint as failures
--   from (values ('public.app_start_exam_attempt(uuid,uuid,text)'), ('private.exam_start_verdict(uuid,uuid,text,timestamptz)'), ('private.demo_set_ok(uuid)'),
--                ('private.app_congelar_preguntas_intento(uuid,uuid,boolean)')) f(n)
-- union all
-- select 'demo_columns_missing',
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'preguntas_examen' and column_name = 'es_demo')
--               and exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'es_demo')
--               and exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'acceso_estado') then 0 else 1 end)::bigint
-- union all
-- select 'demo_guards_missing',
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_preguntas_examen_demo' and not tgisinternal)
--               and exists (select 1 from pg_trigger where tgname = 'trg_examenes_demo_completo' and not tgisinternal) then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.exam_access_verdict(uuid,uuid,timestamptz)') is null or to_regprocedure('private.config_int(text,integer)') is null then
    raise exception using errcode = 'P0001', message = 'V1_DEMO_PREFLIGHT', hint = 'apply 20261022100900 first';
  end if;
end
$$;

alter table public.preguntas_examen add column if not exists es_demo boolean not null default false;
alter table public.intentos_examen add column if not exists es_demo boolean not null default false;
alter table public.intentos_examen add column if not exists acceso_estado text;
alter table public.intentos_examen drop constraint if exists intentos_examen_acceso_estado_check;
alter table public.intentos_examen add constraint intentos_examen_acceso_estado_check check (acceso_estado is null or acceso_estado in ('FREE', 'GRANTED', 'SUBSCRIPTION', 'DEMO')) not valid;
create index if not exists idx_preguntas_examen_demo on public.preguntas_examen (examen_id) where es_demo;

-- the demo set of an exam: exactly config('demo_preguntas') non-annulled, active, flagged questions
create or replace function private.demo_set_ok(p_examen uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select (select count(*) from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
           where pe.examen_id = p_examen and pe.es_demo and pe.anulada is not true and p.activa) = private.config_int('demo_preguntas', 5);
$$;
revoke all on function private.demo_set_ok(uuid) from public, anon, authenticated;

create or replace function private.tg_preguntas_examen_demo()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.es_demo and (tg_op = 'INSERT' or not old.es_demo) then
    if (select count(*) from public.preguntas_examen pe where pe.examen_id = new.examen_id and pe.es_demo and pe.anulada is not true and pe.pregunta_id <> new.pregunta_id) >= private.config_int('demo_preguntas', 5) then
      raise exception using errcode = '23514', message = 'DEMO_SET_FULL';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_preguntas_examen_demo() from public, anon, authenticated;
drop trigger if exists trg_preguntas_examen_demo on public.preguntas_examen;
create trigger trg_preguntas_examen_demo before insert or update of es_demo on public.preguntas_examen for each row execute function private.tg_preguntas_examen_demo();

create or replace function private.tg_examenes_demo_completo()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.demo_disponible and not old.demo_disponible and not private.demo_set_ok(new.id) then
    raise exception using errcode = '23514', message = 'DEMO_SET_INCOMPLETE';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_examenes_demo_completo() from public, anon, authenticated;
drop trigger if exists trg_examenes_demo_completo on public.examenes;
create trigger trg_examenes_demo_completo before update of demo_disponible on public.examenes for each row execute function private.tg_examenes_demo_completo();

-- the ONE start decision (C-A1): access verdict + demo
create or replace function private.exam_start_verdict(p_usuario uuid, p_examen uuid, p_modo text, p_now timestamptz)
returns table(allowed boolean, state text, reason text, demo boolean, exempt boolean, acceso_estado text)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v record;
begin
  select * into v from private.exam_access_verdict(p_usuario, p_examen, p_now);
  if not found then
    return;
  end if;
  if v.state in ('FREE', 'GRANTED', 'SUBSCRIPTION') then
    -- a user WITH access that sends DEMO gets a normal attempt; GRANTED (purchase, gift, quota) is exempt from the daily limiter
    return query select true, v.state, null::text, false, v.state = 'GRANTED', v.state;
  elsif v.state = 'DEMO' and upper(coalesce(p_modo, 'NORMAL')) = 'DEMO' and private.demo_set_ok(p_examen) then
    return query select true, 'DEMO'::text, null::text, true, true, 'DEMO'::text;
  else
    return query select false, v.state, v.reason, false, false, null::text;
  end if;
end;
$$;
revoke all on function private.exam_start_verdict(uuid, uuid, text, timestamptz) from public, anon, authenticated;

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
  v_bound integer;
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED: every active question is frozen; the result is the number of EVALUABLE ones.
  -- ATTEMPT_CONTENT_REVISIONS: each frozen question is bound to the immutable revision of its content right now.
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    select array_agg(c.pregunta_id order by c.posicion, c.pregunta_id),
           array_agg(encode(sha256(convert_to(c.contenido::text || '|' || coalesce(c.solucion::text, 'null'), 'UTF8')), 'hex')
                     order by c.posicion, c.pregunta_id),
           array_agg(c.contenido order by c.posicion, c.pregunta_id),
           array_agg(c.solucion order by c.posicion, c.pregunta_id)
      into v_preguntas, v_hashes, v_contenidos, v_soluciones
    from (
      select pe.pregunta_id, pe.posicion,
             private.app_revision_pregunta_contenido(pe.pregunta_id) as contenido,
             private.app_revision_pregunta_solucion(pe.pregunta_id) as solucion
      from public.preguntas_examen pe
      join public.preguntas p on p.id = pe.pregunta_id
      where pe.examen_id = p_examen_id and p.activa and (not p_solo_demo or (pe.es_demo and not pe.anulada))
    ) c;

    if v_preguntas is not null then
      -- Statement 1: reuse or create. A concurrent start inserting the same revision makes this wait and do nothing.
      insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
      select u.pregunta_id, u.hash, u.contenido, u.solucion
      from unnest(v_preguntas, v_hashes, v_contenidos, v_soluciones) as u(pregunta_id, hash, contenido, solucion)
      on conflict (pregunta_id, hash_contenido) do nothing;

      -- Statement 2 (new snapshot: sees the revision a concurrent start committed).
      insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id)
      select p_intento_id, u.pregunta_id, u.ord::integer, r.id
      from unnest(v_preguntas, v_hashes) with ordinality as u(pregunta_id, hash, ord)
      join public.revisiones_pregunta r on r.pregunta_id = u.pregunta_id and r.hash_contenido = u.hash;
      get diagnostics v_bound = row_count;
      if v_bound <> cardinality(v_preguntas) then
        raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001', detail = 'revision binding incomplete';
      end if;
    end if;
  end if;
  select count(*) into v_n
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = p_examen_id and pe.pregunta_id = pi.pregunta_id and pe.anulada);
  return v_n;
end;
$$;

revoke all on function private.app_congelar_preguntas_intento(uuid, uuid, boolean) from public, anon, authenticated;

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
    -- Client rule: second attempts are kept for progress but not for ranking.
    new.cuenta_para_ranking = coalesce(new.cuenta_para_ranking, new.es_primer_intento_examen);
    return new;
end;
$$;

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
  -- The DEMO decision is enforced HERE (the demo set, the demo state and the per-user limit). A NORMAL start keeps trusting the route's policy check (as before); this function only records the access
  -- state it can read, for the daily limiter (unknown = not exempt, fail closed).
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
  -- DEMO starts are limited per user per day (the per-IP limit lives in the HTTP layer)
  if v_demo then
    select count(*) into v_n from public.intentos_examen ie
     where ie.usuario_id = p_usuario_id and ie.es_demo and ie.iniciado_en > clock_timestamp() - interval '24 hours';
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
      select count(*) into v_total
      from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
      where pe.examen_id = p_examen_id and pe.es_demo and pe.anulada is not true and p.activa;
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
  -- ATTEMPT_CONTENT_REVISIONS
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
  where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id;
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

  if exists (select 1 from public.preguntas_examen pe
             where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id and pe.anulada) then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
                              'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
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
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);

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

drop function if exists public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid);
create or replace function public.app_my_exams_collection(
  p_usuario_id uuid,
  p_tipo_examen text,
  p_comunidad_id uuid,
  p_anio integer,
  p_despues_en timestamp with time zone,
  p_despues_id uuid,
  p_limite integer,
  p_con_grupos boolean,
  p_especialidad_id uuid
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_ahora constant timestamp with time zone := now();
  v_limite constant integer := least(greatest(coalesce(p_limite, 21), 1), 51);
  v_resultado jsonb;
begin
  if (p_despues_en is null) <> (p_despues_id is null) then
    raise exception 'APP_MY_EXAMS_CURSOR_INCOMPLETE' using errcode = '22023';
  end if;

  with concedidos as (
    select cd.examen_id, max(cd.valido_desde) as en
    from public.contenido_desbloqueado_usuario cd
    where cd.usuario_id = p_usuario_id and cd.examen_id is not null
      and cd.valido_desde <= v_ahora and (cd.valido_hasta is null or cd.valido_hasta > v_ahora)
    group by cd.examen_id
  ),
  intentados as (
    select distinct on (ie.examen_id)
           ie.examen_id, ie.id, ie.estado, ie.total_respondidas, ie.total_preguntas, ie.actualizado_en as en
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id is not null
      and ie.origen_intento = 'examen' and ie.creado_por_comando is not null and not ie.es_demo
    order by ie.examen_id, ie.actualizado_en desc, ie.id desc
  ),
  coleccion as materialized (
    select e.id, e.titulo, e.tipo_examen, e.tipo_acceso, e.especialidad_id, e.demo_disponible, e.portada_storage_path,
           ca.codigo as comunidad_codigo,
           greatest(c.en, i.en) as orden_en,
           i.id as intento_id, i.estado as intento_estado,
           i.total_respondidas as intento_respondidas, i.total_preguntas as intento_preguntas
    from concedidos c
    full join intentados i on i.examen_id = c.examen_id
    join public.examenes e on e.id = coalesce(c.examen_id, i.examen_id)
    left join public.comunidades_autonomas ca on ca.id = e.comunidad_id
    where e.activo and e.publicado_en is not null and e.publicado_en <= v_ahora
      and (p_tipo_examen is null or e.tipo_examen = p_tipo_examen)
      and (p_comunidad_id is null or e.comunidad_id = p_comunidad_id)
      and (p_anio is null or e.anio = p_anio)
      and (p_especialidad_id is null or e.especialidad_id = p_especialidad_id)
  )
  select jsonb_build_object(
    'items', coalesce((
      select jsonb_agg(
               jsonb_build_object(
                 'orden_en', pagina.orden_en,
                 'examenes', jsonb_build_object(
                   'id', pagina.id,
                   'titulo', pagina.titulo,
                   'tipo_examen', pagina.tipo_examen,
                   'tipo_acceso', pagina.tipo_acceso,
                   'especialidad_id', pagina.especialidad_id,
                   'demo_disponible', pagina.demo_disponible,
                   'portada_storage_path', pagina.portada_storage_path,
                   'comunidades_autonomas', case when pagina.comunidad_codigo is not null
                                                 then jsonb_build_object('codigo', pagina.comunidad_codigo) end),
                 'intento', case when pagina.intento_id is not null
                                 then jsonb_build_object(
                                   'id', pagina.intento_id,
                                   'estado', pagina.intento_estado,
                                   'total_respondidas', pagina.intento_respondidas,
                                   'total_preguntas', pagina.intento_preguntas) end)
               order by pagina.orden_en desc, pagina.id desc)
      from (
        select k.*
        from coleccion k
        where p_despues_en is null or (k.orden_en, k.id) < (p_despues_en, p_despues_id)
        order by k.orden_en desc, k.id desc
        limit v_limite
      ) pagina), '[]'::jsonb),
    'grupos', case when p_con_grupos then coalesce((
      select jsonb_agg(jsonb_build_object('comunidad_codigo', g.comunidad_codigo, 'total', g.total)
                       order by g.comunidad_codigo)
      from (
        select k.comunidad_codigo, count(*) as total
        from coleccion k
        where k.comunidad_codigo is not null
        group by k.comunidad_codigo
      ) g), '[]'::jsonb) end)
  into v_resultado;

  return v_resultado;
end;
$$;

revoke all on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid) from public, anon, authenticated;
grant execute on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid) to service_role;

revoke all on function public.app_start_exam_attempt(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.app_start_exam_attempt(uuid, uuid, text) to service_role;

-- the old 2-argument entry point stays as a wrapper (NORMAL)
create or replace function public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid)
returns jsonb
language sql
security definer
set search_path = ''
as $$ select public.app_start_exam_attempt(p_usuario_id, p_examen_id, 'NORMAL'); $$;
revoke all on function public.app_start_exam_attempt(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_start_exam_attempt(uuid, uuid) to service_role;

commit;
