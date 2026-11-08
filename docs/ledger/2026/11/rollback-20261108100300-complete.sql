-- ROLLBACK COMPLETO de 20261108100300_v1_auto_demo (F14) para MAIN — preparado por SRV 2026-11-08, ensayado en rehearse-server-2026-11.sh.
-- Sustituye al procedimiento de la cabecera de la migracion (bloque + RE-APLICAR 20261022101000 y 20261022101300 enteras), que el ensayo
-- demostro INCORRECTO: re-aplicar 101300 devuelve app_record_attempt_answer al cuerpo de 101300 y pierde el de 20261022101600 (stats).
-- Aqui: el bloque ROLLBACK de la migracion + SOLO los cuatro cuerpos que la migracion sustituyo, copiados de su ultima version
-- (exam_start_verdict, app_start_exam_attempt(uuid,uuid,text) y tg_examenes_demo_completo de 20261022101000; app_congelar_preguntas_intento
-- de 20261022101300), con sus revoke/grant. NO apaga la demo encendida por el backfill (datos): ver el runbook, seccion 9.
-- GENERADO por extraccion literal de esos ficheros; si cambia alguno, regenerar.
begin;
drop function if exists public.demo_listo(public.examenes);
drop function if exists private.demo_preguntas_efectivas(uuid);
alter table public.examenes alter column demo_disponible set default false;
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
  v_rac boolean;
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED + ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES: every active question is frozen with its ROLE and its annulment state at start.
  -- A DEMO attempt ignores the official rules (rol null = the legacy live rule over exactly the flagged questions).
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    select private.official_rule_effective(p_examen_id), e.penalizacion_num, e.penalizacion_den, e.penalizacion_racional into v_regla, v_num, v_den, v_rac from public.examenes e where e.id = p_examen_id;
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
           set regla_anuladas = v_regla, penalizacion_num = v_num, penalizacion_den = v_den, penalizacion_racional = v_rac,
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
commit;
