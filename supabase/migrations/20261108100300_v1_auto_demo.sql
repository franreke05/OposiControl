-- V1 AUTOMATIC DEMO (owner decision 2026-11-08, from the client via the owner; workstream F14). Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
-- Requires 20261022101000 (demo mode) and 20261022101300 (official rules: reserves and the freeze body this file replaces).
--
-- Decision: a user WITHOUT access can always try ~N questions (config 'demo_preguntas', default 5) of every published exam, without Control marking anything.
-- 1. EFFECTIVE DEMO SET of an exam (private.demo_preguntas_efectivas): the explicit set when it is complete (private.demo_set_ok: exactly N flagged, non-annulled, active questions:
--    the explicit set ALWAYS wins); otherwise the AUTOMATIC set = the first N MAIN (non-reserve) questions by posicion (tie: pregunta_id) that are non-annulled and whose question is
--    active (an annulled or inactive one inside the first N is skipped and the next one enters). Fewer than N such questions = NO demo (NULL, fail closed). A partial explicit set
--    (fewer than N flags) does not block: the automatic set applies.
-- 2. examenes.demo_disponible becomes DEFAULT-ON with an explicit opt-out: the column default is now true and every existing exam is switched on (in MAIN no exam has it on: neither the
--    panel nor the Ktor admin ever wrote the column, so every false there is the old default, not a decision of Control). Control opts an exam out by setting it to false.
--    The switch-on guard DEMO_SET_INCOMPLETE (trg_examenes_demo_completo) no longer applies: switching on is always allowed and an exam without an effective set simply offers no demo
--    (fail closed at read and start). The trigger and its function keep their names (the VERIFY of 20261022101000 checks them); the body is now a pass-through. DEMO_SET_FULL stays.
--    The access verdict is unchanged (DEMO = no access + demo_disponible + specialty permite_demos), so the Kotlin twin (EntitlementPolicy) and verdict-parity keep their parity.
-- 3. public.demo_listo(examenes) (PostgREST computed field, EXECUTE service_role only): demo_disponible AND an effective set exists. The Ktor reads it next to demo_disponible so the cover
--    advertises DEMO only when the start can actually serve one (an exam with fewer than N questions shows LOCKED, not a "Probar demo" that would fail).
--    DEPLOY ORDER: this migration BEFORE the Ktor image that selects demo_listo (an older image ignores it; the new image against a database without it fails the catalog reads).
-- 4. private.exam_start_verdict and public.app_start_exam_attempt use the effective set; private.app_congelar_preguntas_intento(..., p_solo_demo = true) freezes EXACTLY the effective set
--    (no flag needed), so every attempt-bound read and the answer function still never reach a question outside it (QUESTION_NOT_IN_ATTEMPT). Nothing else changes: a demo never counts
--    (ranking, first attempt, numbering, first answer, Mis examenes, stats), the per-user (database) and per-IP (HTTP) start limits stay, a user WITH access gets a normal attempt.
-- Not changed on purpose: private.reto_pool keeps using only the EXPLICIT flags for the immersive slot of the daily challenge (the automatic set does not feed the challenge).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate; the gate compares catalog names/ACLs, not bodies: the bodies of exam_start_verdict, app_congelar_preguntas_intento,
-- app_start_exam_attempt and tg_examenes_demo_completo are restored by re-applying 20261022101000 and then 20261022101300 in order). Documented: the rollback does NOT switch the demo
-- off on the exams the backfill switched on (data, not catalog); to do that run `update public.examenes set demo_disponible = false;` with the owner's GO.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.demo_listo(public.examenes);
-- drop function if exists private.demo_preguntas_efectivas(uuid);
-- alter table public.examenes alter column demo_disponible set default false;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'auto_demo_functions_not_service_role_only:' || f.n as check_name,
--        (case when to_regprocedure(f.n) is null or has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute')
--                or (f.n like 'public.%' and not has_function_privilege('service_role', f.n, 'execute')) then 1 else 0 end)::bigint as failures
--   from (values ('private.demo_preguntas_efectivas(uuid)'), ('public.demo_listo(public.examenes)')) f(n)
-- union all
-- select 'auto_demo_default_not_on',
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'examenes' and column_name = 'demo_disponible' and column_default = 'true')
--              then 0 else 1 end)::bigint
-- union all
-- select 'auto_demo_start_not_using_effective_set:' || f.n,
--        (case when to_regprocedure(f.n) is not null and pg_get_functiondef(to_regprocedure(f.n)) like '%demo_preguntas_efectivas%' then 0 else 1 end)::bigint
--   from (values ('private.exam_start_verdict(uuid,uuid,text,timestamptz)'), ('private.app_congelar_preguntas_intento(uuid,uuid,boolean)'),
--                ('public.app_start_exam_attempt(uuid,uuid,text)')) f(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.demo_set_ok(uuid)') is null or to_regprocedure('private.exam_start_verdict(uuid,uuid,text,timestamptz)') is null
     or to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid,boolean)') is null or to_regprocedure('public.app_start_exam_attempt(uuid,uuid,text)') is null
     or to_regprocedure('private.official_rule_effective(uuid)') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'preguntas_examen' and column_name = 'es_reserva') then
    raise exception using errcode = 'P0001', message = 'V1_AUTO_DEMO_PREFLIGHT', hint = 'apply 20261022101000 and 20261022101300 first';
  end if;
end
$$;

-- ---- 1. the effective demo set (ordered by posicion); NULL = no demo
create or replace function private.demo_preguntas_efectivas(p_examen uuid)
returns uuid[]
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_n integer := private.config_int('demo_preguntas', 5);
  v uuid[];
begin
  if p_examen is null or v_n is null or v_n < 1 then
    return null;
  end if;
  -- the explicit set of Control wins when it is complete (same predicate as the original demo mode)
  if private.demo_set_ok(p_examen) then
    select array_agg(pe.pregunta_id order by pe.posicion, pe.pregunta_id) into v
      from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
     where pe.examen_id = p_examen and pe.es_demo and pe.anulada is not true and p.activa;
    return v;
  end if;
  -- automatic: the first N main questions that can be served
  select array_agg(x.pregunta_id order by x.posicion, x.pregunta_id) into v
    from (select pe.pregunta_id, pe.posicion
            from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
           where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa
           order by pe.posicion, pe.pregunta_id
           limit v_n) x;
  if coalesce(cardinality(v), 0) < v_n then
    return null;  -- fewer than N servable questions: no demo (fail closed)
  end if;
  return v;
end;
$$;
revoke all on function private.demo_preguntas_efectivas(uuid) from public, anon, authenticated;

-- ---- 2. PostgREST computed field for the Ktor catalog: the exam offers a demo that can be served
create or replace function public.demo_listo(e public.examenes)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select case when e.demo_disponible then private.demo_preguntas_efectivas(e.id) is not null else false end;
$$;
revoke all on function public.demo_listo(public.examenes) from public, anon, authenticated;
grant execute on function public.demo_listo(public.examenes) to service_role;

-- ---- 3. demo_disponible: default ON with an explicit opt-out
create or replace function private.tg_examenes_demo_completo()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- F14 (2026-11-08): switching the demo on is always allowed; without an effective set (private.demo_preguntas_efectivas) the exam simply offers no demo.
  return new;
end;
$$;
revoke all on function private.tg_examenes_demo_completo() from public, anon, authenticated;

alter table public.examenes alter column demo_disponible set default true;
update public.examenes set demo_disponible = true where demo_disponible is not true;

-- ---- 4. the start decision uses the effective set
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
  elsif v.state = 'DEMO' and upper(coalesce(p_modo, 'NORMAL')) = 'DEMO' and private.demo_preguntas_efectivas(p_examen) is not null then
    return query select true, 'DEMO'::text, null::text, true, true, 'DEMO'::text;
  else
    return query select false, v.state, v.reason, false, false, null::text;
  end if;
end;
$$;
revoke all on function private.exam_start_verdict(uuid, uuid, text, timestamptz) from public, anon, authenticated;

-- ---- 5. the freeze: a DEMO attempt holds EXACTLY the effective set (body of 20261022101300, only the demo filter changes)
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
  v_demo uuid[];
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED + ATTEMPT_CONTENT_REVISIONS + OFFICIAL_RULES: every active question is frozen with its ROLE and its annulment state at start.
  -- A DEMO attempt ignores the official rules (rol null = the legacy live rule) over exactly the EFFECTIVE demo set (explicit when complete, else automatic; F14). No set = nothing frozen.
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    if p_solo_demo then
      v_demo := private.demo_preguntas_efectivas(p_examen_id);
    end if;
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
        where pe.examen_id = p_examen_id and p.activa
          and (not p_solo_demo or (pe.pregunta_id = any(v_demo) and pe.anulada is not true))
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
revoke all on function private.app_congelar_preguntas_intento(uuid, uuid, boolean) from public, anon, authenticated;

-- ---- 6. the start: the demo size is the size of the effective set (body of 20261022101000, only the demo count changes)
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

commit;
