-- M4 · Orden (UNIQUE deferrable), vincular y desvincular atómicos por RPC.
set local lock_timeout = '5s';
lock table public.preguntas, public.preguntas_examen, public.examenes in share row exclusive mode;

do $pre$
begin
  if (select c.condeferrable from pg_constraint c
       where c.conrelid = 'public.preguntas_examen'::regclass
         and c.conname = 'preguntas_examen_examen_id_posicion_key') is distinct from false then
    raise exception 'PREFLIGHT_FAILED: preguntas_examen_examen_id_posicion_key no existe o ya es deferrable';
  end if;
  if exists (select 1 from pg_constraint where conrelid = 'public.preguntas_examen'::regclass
              and conname = 'preguntas_examen_pregunta_unica') then
    raise exception 'PREFLIGHT_FAILED: aplicar antes exams_questions_many_to_many (M2)';
  end if;
  -- Falla cerrado si el trigger no existe (revisión C): tgattr '1 2' = UPDATE OF examen_id, pregunta_id.
  if coalesce((select t.tgattr::text from pg_trigger t
                where t.tgrelid = 'public.preguntas_examen'::regclass
                  and t.tgname = 'trg_validar_pregunta_examen' and not t.tgisinternal), '<ausente>') <> '1 2' then
    raise exception 'PREFLIGHT_FAILED: aplicar antes exams_questions_parent_guards (M3)';
  end if;
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public'
                and p.proname in ('admin_reorder_exam_questions', 'admin_link_question_to_exam', 'admin_unlink_question_from_exam')) then
    raise exception 'PREFLIGHT_FAILED: las RPC ya existen';
  end if;
end
$pre$;

alter table public.preguntas_examen
  drop constraint preguntas_examen_examen_id_posicion_key,
  add constraint preguntas_examen_examen_id_posicion_key
    unique (examen_id, posicion) deferrable initially immediate;

create function public.admin_reorder_exam_questions(p_examen_id uuid, p_pregunta_ids uuid[])
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_ids uuid[] := coalesce(p_pregunta_ids, '{}'::uuid[]);
  v_n integer := coalesce(array_length(coalesce(p_pregunta_ids, '{}'::uuid[]), 1), 0);
begin
  perform 1 from public.examenes e where e.id = p_examen_id for no key update;
  if not found then
    raise exception 'EXAM_NOT_FOUND';
  end if;
  -- El cuerpo tiene que ser EXACTAMENTE el conjunto vinculado, sin repetidos ni nulos.
  if v_n <> (select count(*) from public.preguntas_examen pe where pe.examen_id = p_examen_id)
     or v_n <> (select count(distinct x) from unnest(v_ids) as x)
     or exists (select 1 from unnest(v_ids) as x
                 where not exists (select 1 from public.preguntas_examen pe
                                    where pe.examen_id = p_examen_id and pe.pregunta_id = x)) then
    raise exception 'EXAM_REORDER_SET_MISMATCH';
  end if;
  -- Una sola sentencia: la unicidad (deferrable) se comprueba al final de la sentencia.
  update public.preguntas_examen pe
     set posicion = o.ord::integer
    from unnest(v_ids) with ordinality as o(pregunta_id, ord)
   where pe.examen_id = p_examen_id
     and pe.pregunta_id = o.pregunta_id
     and pe.posicion is distinct from o.ord::integer;
end;
$$;

create function public.admin_link_question_to_exam(
  p_examen_id uuid,
  p_pregunta_id uuid,
  p_posicion integer default null
)
returns table (posicion_asignada integer, numero_asignado integer, ya_vinculada boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_tipo_pregunta text;
  v_especialidad_pregunta uuid;
  v_activa boolean;
  v_tipo_examen text;
  v_especialidad_examen uuid;
  v_n integer;
  v_pos integer;
  v_num integer;
begin
  -- Cerrojos pregunta (SHARE) -> examen (NO KEY UPDATE): mismo orden que la validación y el recuento.
  select p.tipo_pregunta, p.especialidad_id, p.activa
    into v_tipo_pregunta, v_especialidad_pregunta, v_activa
    from public.preguntas p
   where p.id = p_pregunta_id
     for share;
  if not found then
    raise exception 'QUESTION_NOT_FOUND';
  end if;

  select e.tipo_examen, e.especialidad_id
    into v_tipo_examen, v_especialidad_examen
    from public.examenes e
   where e.id = p_examen_id
     for no key update;
  if not found then
    raise exception 'EXAM_NOT_FOUND';
  end if;

  -- Revisión C: la ruta REST anterior pudo dejar huecos en `posicion` (p. ej. {1,3}). El
  -- cálculo por recuento de abajo supone 1..n compacto, así que se recompacta primero, en
  -- una sentencia (UNIQUE deferrable; no dispara M1 ni M3, que sólo miran las claves).
  update public.preguntas_examen pe
     set posicion = r.rn
    from (select x.pregunta_id,
                 (row_number() over (order by x.posicion, x.pregunta_id))::integer as rn
            from public.preguntas_examen x
           where x.examen_id = p_examen_id) r
   where pe.examen_id = p_examen_id
     and pe.pregunta_id = r.pregunta_id
     and pe.posicion <> r.rn;

  -- Idempotente: ya vinculada = se devuelve tal cual; mover es reordenar.
  select pe.posicion, pe.numero_en_examen
    into v_pos, v_num
    from public.preguntas_examen pe
   where pe.examen_id = p_examen_id
     and pe.pregunta_id = p_pregunta_id;
  if found then
    return query select v_pos, v_num, true;
    return;
  end if;

  if not v_activa then
    raise exception 'QUESTION_ARCHIVED';
  end if;
  if v_especialidad_examen <> v_especialidad_pregunta then
    raise exception 'QUESTION_EXAM_SPECIALTY_MISMATCH';
  end if;
  if not ((v_tipo_examen = 'rafaga' and v_tipo_pregunta = 'rafaga')
       or (v_tipo_examen = 'razonado' and v_tipo_pregunta = 'razonada')
       or (v_tipo_examen = 'inmersivo' and v_tipo_pregunta = 'inmersiva')) then
    raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
  end if;

  select count(*)::integer into v_n
    from public.preguntas_examen pe
   where pe.examen_id = p_examen_id;
  if v_n >= 200 then
    raise exception 'EXAM_QUESTION_LIMIT_REACHED';
  end if;
  if p_posicion is not null and p_posicion < 1 then
    raise exception 'EXAM_QUESTION_POSITION_INVALID';
  end if;
  v_pos := case when p_posicion is null or p_posicion > v_n then v_n + 1 else p_posicion end;

  -- C-19: primer número libre 1..200 (nadie lee numero_en_examen: Q-F4). Nunca se renumera.
  select min(g)::integer into v_num
    from generate_series(1, 200) as g
   where not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = p_examen_id and pe.numero_en_examen = g);
  if v_num is null then
    raise exception 'EXAM_QUESTION_LIMIT_REACHED';
  end if;

  if v_pos <= v_n then
    update public.preguntas_examen pe
       set posicion = pe.posicion + 1
     where pe.examen_id = p_examen_id
       and pe.posicion >= v_pos;
  end if;

  insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
  values (p_examen_id, p_pregunta_id, v_pos, v_num);

  return query select v_pos, v_num, false;
end;
$$;

create function public.admin_unlink_question_from_exam(p_examen_id uuid, p_pregunta_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_restantes integer;
begin
  perform 1 from public.preguntas p where p.id = p_pregunta_id for share;
  perform 1 from public.examenes e where e.id = p_examen_id for no key update;
  if not found then
    raise exception 'EXAM_NOT_FOUND';
  end if;

  delete from public.preguntas_examen pe
   where pe.examen_id = p_examen_id
     and pe.pregunta_id = p_pregunta_id;
  if not found then
    raise exception 'QUESTION_NOT_LINKED_TO_EXAM';
  end if;

  -- Recompacta posicion a 1..N en una sentencia (UNIQUE deferrable). numero_en_examen no se toca:
  -- el hueco lo reutiliza el siguiente alta (C-19).
  update public.preguntas_examen pe
     set posicion = r.rn
    from (select x.pregunta_id,
                 (row_number() over (order by x.posicion, x.pregunta_id))::integer as rn
            from public.preguntas_examen x
           where x.examen_id = p_examen_id) r
   where pe.examen_id = p_examen_id
     and pe.pregunta_id = r.pregunta_id
     and pe.posicion <> r.rn;

  select count(*)::integer into v_restantes
    from public.preguntas_examen pe
   where pe.examen_id = p_examen_id;
  return v_restantes;
end;
$$;

revoke all on function public.admin_reorder_exam_questions(uuid, uuid[]) from public, anon, authenticated;
revoke all on function public.admin_link_question_to_exam(uuid, uuid, integer) from public, anon, authenticated;
revoke all on function public.admin_unlink_question_from_exam(uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_reorder_exam_questions(uuid, uuid[]) to service_role;
grant execute on function public.admin_link_question_to_exam(uuid, uuid, integer) to service_role;
grant execute on function public.admin_unlink_question_from_exam(uuid, uuid) to service_role;

do $post$
begin
  if (select c.condeferrable and not c.condeferred from pg_constraint c
       where c.conrelid = 'public.preguntas_examen'::regclass
         and c.conname = 'preguntas_examen_examen_id_posicion_key') is distinct from true then
    raise exception 'POSTCHECK_FAILED: la unicidad de posicion no es DEFERRABLE INITIALLY IMMEDIATE';
  end if;
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public'
                and p.proname in ('admin_reorder_exam_questions', 'admin_link_question_to_exam', 'admin_unlink_question_from_exam')
                and (has_function_privilege('anon', p.oid, 'EXECUTE')
                     or has_function_privilege('authenticated', p.oid, 'EXECUTE')
                     or not has_function_privilege('service_role', p.oid, 'EXECUTE')
                     or not p.prosecdef)) then
    raise exception 'POSTCHECK_FAILED: ACL o SECURITY DEFINER incorrectos en las RPC';
  end if;
end
$post$;
