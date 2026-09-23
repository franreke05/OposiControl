-- Q2 · Alta y edición atómicas: pregunta + 4 opciones (por letra, sin borrar) + solución [+ vínculo vía M4].
-- Requiere Q1. El vínculo usa public.admin_link_question_to_exam (M4) si está instalada; si no, QUESTION_EXAM_LINK_UNAVAILABLE.
set local lock_timeout = '5s';
lock table public.preguntas, public.opciones_pregunta, public.soluciones_pregunta in share row exclusive mode;

do $pre$
begin
  if (select count(*) from pg_trigger
       where tgrelid = 'public.preguntas'::regclass and not tgisinternal
         and tgname in ('trg_preguntas_codigo_pregunta_insert', 'trg_preguntas_codigo_pregunta_update')) <> 2 then
    raise exception 'PREFLIGHT_FAILED: aplicar antes questions_codigo_pregunta_sequence (Q1)';
  end if;
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where (n.nspname = 'public' and p.proname in ('admin_create_question', 'admin_update_question', 'admin_replace_question_options'))
                 or (n.nspname = 'private' and p.proname in ('normalizar_tipo_pregunta', 'normalizar_dificultad_pregunta',
                                                             'validar_opciones_pregunta', 'aplicar_opciones_pregunta'))) then
    raise exception 'PREFLIGHT_FAILED: alguna función ya existe (¿se aplicó el candidato?)';
  end if;
  if (select count(*) from pg_constraint c
       where (c.conrelid = 'public.preguntas'::regclass and c.conname = 'preguntas_pkey' and c.contype = 'p')
          or (c.conrelid = 'public.opciones_pregunta'::regclass and c.conname = 'opciones_pregunta_pregunta_id_letra_key' and c.contype = 'u')
          or (c.conrelid = 'public.soluciones_pregunta'::regclass and c.conname = 'soluciones_pregunta_pregunta_id_key' and c.contype = 'u')) <> 3 then
    raise exception 'PREFLIGHT_FAILED: faltan los árbitros de ON CONFLICT';
  end if;
  if exists (select 1 from pg_constraint where conrelid = 'public.opciones_pregunta'::regclass
              and conname = 'opciones_pregunta_posicion_letra_chk') then
    raise exception 'PREFLIGHT_FAILED: opciones_pregunta_posicion_letra_chk ya existe';
  end if;
  if exists (select 1 from public.opciones_pregunta o where o.posicion <> ascii(o.letra) - 64) then
    raise exception 'PREFLIGHT_FAILED: hay opciones cuya posicion no corresponde a su letra (alinearlas antes)';
  end if;
end
$pre$;

-- La posición ES la letra: el upsert por letra nunca choca con UNIQUE (pregunta_id, posicion), que no es deferrable.
alter table public.opciones_pregunta
  add constraint opciones_pregunta_posicion_letra_chk check (posicion = ascii(letra) - 64);

create function private.normalizar_tipo_pregunta(p_tipo text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_tipo text := lower(btrim(coalesce(p_tipo, '')));
begin
  if v_tipo = 'guiada' then
    v_tipo := 'razonada';   -- alias heredado (Q-7), el mismo que acepta QuestionType.RAZONADA en Kotlin
  end if;
  if v_tipo not in ('rafaga', 'razonada', 'inmersiva') then
    raise exception 'QUESTION_TYPE_UNKNOWN';
  end if;
  return v_tipo;
end;
$$;

create function private.normalizar_dificultad_pregunta(p_dificultad text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_dificultad text := nullif(lower(btrim(coalesce(p_dificultad, ''))), '');
begin
  if v_dificultad is not null and v_dificultad not in ('facil', 'media', 'dificil') then
    raise exception 'QUESTION_DIFFICULTY_UNKNOWN';
  end if;
  return v_dificultad;
end;
$$;

-- DEC-Q-01: exactamente 4 opciones A-D, texto no vacío, es_correcta booleano, una sola correcta. Devuelve la forma normalizada.
create function private.validar_opciones_pregunta(p_opciones jsonb)
returns table (letra text, texto text, posicion integer, es_correcta boolean)
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_elemento jsonb;
  v_letra text;
  v_texto text;
  v_vistas text[] := '{}';
  v_correctas integer := 0;
begin
  if p_opciones is null or jsonb_typeof(p_opciones) <> 'array' or jsonb_array_length(p_opciones) <> 4 then
    raise exception 'QUESTION_OPTIONS_INVALID';
  end if;
  for v_elemento in select e.value from jsonb_array_elements(p_opciones) as e loop
    if jsonb_typeof(v_elemento) <> 'object'
       or jsonb_typeof(v_elemento -> 'letra') is distinct from 'string'
       or jsonb_typeof(v_elemento -> 'texto') is distinct from 'string'
       or jsonb_typeof(v_elemento -> 'es_correcta') is distinct from 'boolean' then
      raise exception 'QUESTION_OPTIONS_INVALID';
    end if;
    v_letra := upper(btrim(v_elemento ->> 'letra'));
    v_texto := btrim(v_elemento ->> 'texto');
    if v_letra not in ('A', 'B', 'C', 'D') or v_letra = any (v_vistas) or v_texto = '' then
      raise exception 'QUESTION_OPTIONS_INVALID';
    end if;
    v_vistas := v_vistas || v_letra;
    if (v_elemento ->> 'es_correcta')::boolean then
      v_correctas := v_correctas + 1;
    end if;
    letra := v_letra;
    texto := v_texto;
    posicion := ascii(v_letra) - 64;
    es_correcta := (v_elemento ->> 'es_correcta')::boolean;
    return next;
  end loop;
  if v_correctas <> 1 then
    raise exception 'QUESTION_CORRECT_ANSWER_INVALID';
  end if;
end;
$$;

-- Q-3: por letra y SIN borrar. Los id de opción se conservan: respuestas_intento (ON DELETE SET NULL) no pierde referencias.
-- p_razonamiento null = conservar el existente ('' si la solución es nueva). No recalifica respuestas históricas (instantáneas).
create function private.aplicar_opciones_pregunta(p_pregunta_id uuid, p_opciones jsonb, p_razonamiento text)
returns void
language plpgsql
set search_path = ''
as $$
declare
  v_opcion record;
  v_correcta_id uuid;
begin
  for v_opcion in select * from private.validar_opciones_pregunta(p_opciones) loop
    insert into public.opciones_pregunta as o (pregunta_id, letra, texto_opcion, posicion)
    values (p_pregunta_id, v_opcion.letra, v_opcion.texto, v_opcion.posicion)
    on conflict (pregunta_id, letra) do update
       set texto_opcion = excluded.texto_opcion
     where o.texto_opcion is distinct from excluded.texto_opcion;
    if v_opcion.es_correcta then
      select o.id into v_correcta_id
        from public.opciones_pregunta o
       where o.pregunta_id = p_pregunta_id
         and o.letra = v_opcion.letra;
    end if;
  end loop;

  insert into public.soluciones_pregunta as s (pregunta_id, opcion_correcta_id, razonamiento)
  values (p_pregunta_id, v_correcta_id, coalesce(p_razonamiento, ''))
  on conflict (pregunta_id) do update
     set opcion_correcta_id = excluded.opcion_correcta_id,
         razonamiento = coalesce(p_razonamiento, s.razonamiento)
   where s.opcion_correcta_id is distinct from excluded.opcion_correcta_id
      or (p_razonamiento is not null and s.razonamiento is distinct from p_razonamiento);
end;
$$;

create function public.admin_create_question(
  p_pregunta_id uuid,
  p_especialidad_id uuid,
  p_tipo_pregunta text,
  p_titulo text,
  p_opciones jsonb,
  p_razonamiento text default null,
  p_fuente_texto text default null,
  p_dificultad text default null,
  p_activa boolean default true,
  p_examen_id uuid default null,
  p_posicion integer default null,
  p_lote_importacion_id uuid default null,
  p_creado_por uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_titulo text := btrim(coalesce(p_titulo, ''));
  v_tipo text;
  v_dificultad text;
  v_id uuid;
  v_codigo text;
  v_existente record;
  v_creada boolean := true;
  v_posicion integer;
  v_tipo_examen text;
  v_especialidad_examen uuid;
begin
  -- 1) Todo se valida ANTES de escribir: ningún error de validación consume número de PREG-n.
  if p_pregunta_id is null then
    raise exception 'QUESTION_ID_REQUIRED';
  end if;
  if v_titulo = '' then
    raise exception 'QUESTION_STATEMENT_REQUIRED';
  end if;
  if p_especialidad_id is null then
    raise exception 'QUESTION_SPECIALTY_REQUIRED';
  end if;
  v_tipo := private.normalizar_tipo_pregunta(p_tipo_pregunta);
  v_dificultad := private.normalizar_dificultad_pregunta(p_dificultad);
  perform 1 from private.validar_opciones_pregunta(p_opciones);
  perform 1 from public.especialidades e where e.id = p_especialidad_id;
  if not found then
    raise exception 'QUESTION_SPECIALTY_NOT_FOUND';
  end if;
  if p_examen_id is not null
     and to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer)') is null then
    raise exception 'QUESTION_EXAM_LINK_UNAVAILABLE';
  end if;
  -- Revisión C (Q2-3): el vínculo se valida ANTES de insertar (mismos códigos y orden que M4),
  -- para fallar pronto y sin consumir número PREG-n. M4 vuelve a validar bajo cerrojo.
  if p_examen_id is not null then
    select e.tipo_examen, e.especialidad_id
      into v_tipo_examen, v_especialidad_examen
      from public.examenes e
     where e.id = p_examen_id;
    if not found then
      raise exception 'EXAM_NOT_FOUND';
    end if;
    if not coalesce(p_activa, true) then
      raise exception 'QUESTION_ARCHIVED';
    end if;
    if v_especialidad_examen <> p_especialidad_id then
      raise exception 'QUESTION_EXAM_SPECIALTY_MISMATCH';
    end if;
    if not ((v_tipo_examen = 'rafaga' and v_tipo = 'rafaga')
         or (v_tipo_examen = 'razonado' and v_tipo = 'razonada')
         or (v_tipo_examen = 'inmersivo' and v_tipo = 'inmersiva')) then
      raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
    end if;
  end if;

  -- 2) Idempotencia (Q-4): el id lo genera el cliente una vez por alta. codigo_pregunta lo pone Q1.
  insert into public.preguntas as p
    (id, especialidad_id, tipo_pregunta, titulo, fuente_texto, dificultad, activa, lote_importacion_id, creado_por)
  values
    (p_pregunta_id, p_especialidad_id, v_tipo, v_titulo, nullif(btrim(p_fuente_texto), ''), v_dificultad,
     coalesce(p_activa, true), p_lote_importacion_id, p_creado_por)
  on conflict (id) do nothing
  returning p.id, p.codigo_pregunta into v_id, v_codigo;

  if v_id is null then
    -- Revisión C (Q2-1): un reintento sólo es idempotente si TODO el contenido coincide.
    -- Mismo id con otro contenido = conflicto explícito, nunca «éxito» sin aplicar nada.
    select p.id, p.codigo_pregunta, p.especialidad_id, p.tipo_pregunta, p.titulo, p.fuente_texto, p.dificultad, p.activa
      into v_existente
      from public.preguntas p
     where p.id = p_pregunta_id;
    if v_existente.id is null
       or v_existente.especialidad_id is distinct from p_especialidad_id
       or v_existente.tipo_pregunta is distinct from v_tipo
       or v_existente.titulo is distinct from v_titulo
       or v_existente.fuente_texto is distinct from nullif(btrim(p_fuente_texto), '')
       or v_existente.dificultad is distinct from v_dificultad
       or v_existente.activa is distinct from coalesce(p_activa, true)
       or (select s.razonamiento from public.soluciones_pregunta s where s.pregunta_id = p_pregunta_id)
          is distinct from coalesce(btrim(p_razonamiento), '')
       or exists (select 1
                    from private.validar_opciones_pregunta(p_opciones) n
                    left join public.opciones_pregunta o on o.pregunta_id = p_pregunta_id and o.letra = n.letra
                    left join public.soluciones_pregunta s on s.pregunta_id = p_pregunta_id
                   where o.id is null
                      or o.texto_opcion is distinct from n.texto
                      or (n.es_correcta and s.opcion_correcta_id is distinct from o.id)) then
      raise exception 'QUESTION_ID_CONFLICT';
    end if;
    v_id := v_existente.id;
    v_codigo := v_existente.codigo_pregunta;
    v_creada := false;
  else
    perform private.aplicar_opciones_pregunta(v_id, p_opciones, coalesce(btrim(p_razonamiento), ''));
  end if;

  -- 3) Vínculo opcional en la MISMA transacción, por la única implementación del vínculo (M4; idempotente).
  if p_examen_id is not null then
    select l.posicion_asignada into v_posicion
      from public.admin_link_question_to_exam(p_examen_id, v_id, p_posicion) as l;
  end if;

  return jsonb_build_object(
    'pregunta_id', v_id,
    'codigo_pregunta', v_codigo,
    'creada', v_creada,
    'posicion_asignada', v_posicion
  );
end;
$$;

-- DEC-Q-03: edición completa con claves presentes (ausente = se conserva; presente con null = se limpia si la columna lo admite).
create function public.admin_update_question(p_pregunta_id uuid, p_cambios jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_c jsonb := coalesce(p_cambios, '{}'::jsonb);
  v_actual record;
  v_titulo text;
  v_tipo text;
  v_fuente text;
  v_dificultad text;
  v_activa boolean;
  v_razonamiento text;   -- null = no tocar
begin
  -- Revisión C (Q2-2): dos comprobaciones separadas; jsonb_object_keys sobre un array lanza 22023
  -- y PostgreSQL no garantiza el cortocircuito de un OR.
  if jsonb_typeof(v_c) <> 'object' then
    raise exception 'QUESTION_PATCH_INVALID';
  end if;
  if exists (select 1 from jsonb_object_keys(v_c) as k(clave)
              where k.clave not in ('titulo', 'tipo_pregunta', 'fuente_texto', 'dificultad', 'activa', 'opciones', 'razonamiento')) then
    raise exception 'QUESTION_PATCH_INVALID';
  end if;

  -- Cerrojo de la pregunta primero (orden pregunta -> examen, igual que M1, M3 y M4).
  select p.titulo, p.tipo_pregunta, p.fuente_texto, p.dificultad, p.activa
    into v_actual
    from public.preguntas p
   where p.id = p_pregunta_id
     for no key update;
  if not found then
    raise exception 'QUESTION_NOT_FOUND';
  end if;

  v_titulo := v_actual.titulo;
  if v_c ? 'titulo' then
    if jsonb_typeof(v_c -> 'titulo') is distinct from 'string' or btrim(v_c ->> 'titulo') = '' then
      raise exception 'QUESTION_STATEMENT_REQUIRED';
    end if;
    v_titulo := btrim(v_c ->> 'titulo');
  end if;

  v_tipo := v_actual.tipo_pregunta;
  if v_c ? 'tipo_pregunta' then
    v_tipo := private.normalizar_tipo_pregunta(v_c ->> 'tipo_pregunta');
  end if;

  v_fuente := v_actual.fuente_texto;
  if v_c ? 'fuente_texto' then
    if jsonb_typeof(v_c -> 'fuente_texto') not in ('string', 'null') then
      raise exception 'QUESTION_PATCH_INVALID';
    end if;
    v_fuente := nullif(btrim(v_c ->> 'fuente_texto'), '');
  end if;

  v_dificultad := v_actual.dificultad;
  if v_c ? 'dificultad' then
    if jsonb_typeof(v_c -> 'dificultad') not in ('string', 'null') then
      raise exception 'QUESTION_PATCH_INVALID';
    end if;
    v_dificultad := private.normalizar_dificultad_pregunta(v_c ->> 'dificultad');
  end if;

  v_activa := v_actual.activa;
  if v_c ? 'activa' then
    if jsonb_typeof(v_c -> 'activa') is distinct from 'boolean' then
      raise exception 'QUESTION_PATCH_INVALID';
    end if;
    v_activa := (v_c ->> 'activa')::boolean;
  end if;

  if v_c ? 'razonamiento' then
    if jsonb_typeof(v_c -> 'razonamiento') not in ('string', 'null') then
      raise exception 'QUESTION_PATCH_INVALID';
    end if;
    v_razonamiento := coalesce(v_c ->> 'razonamiento', '');
  end if;

  if v_c ? 'opciones' then
    perform 1 from private.validar_opciones_pregunta(v_c -> 'opciones');   -- JSON null o forma mala: QUESTION_OPTIONS_INVALID
  end if;

  -- D-13 / C-06: las mismas guardas y códigos que M3, bajo el cerrojo de la pregunta (M4 vincula con FOR SHARE sobre ella).
  if v_tipo is distinct from v_actual.tipo_pregunta then
    if exists (select 1 from public.preguntas_examen pe where pe.pregunta_id = p_pregunta_id) then
      raise exception 'QUESTION_TYPE_LOCKED_BY_EXAMS';
    end if;
    if v_actual.tipo_pregunta = 'inmersiva'
       and exists (select 1 from public.recursos_pregunta rp where rp.pregunta_id = p_pregunta_id) then
      raise exception 'QUESTION_TYPE_LOCKED_BY_RESOURCES';
    end if;
  end if;

  update public.preguntas p
     set titulo = v_titulo,
         tipo_pregunta = v_tipo,
         fuente_texto = v_fuente,
         dificultad = v_dificultad,
         activa = v_activa
   where p.id = p_pregunta_id
     and (p.titulo, p.tipo_pregunta, p.fuente_texto, p.dificultad, p.activa)
         is distinct from (v_titulo, v_tipo, v_fuente, v_dificultad, v_activa);

  if v_c ? 'opciones' then
    perform private.aplicar_opciones_pregunta(p_pregunta_id, v_c -> 'opciones', v_razonamiento);
  elsif v_c ? 'razonamiento' then
    update public.soluciones_pregunta s
       set razonamiento = v_razonamiento
     where s.pregunta_id = p_pregunta_id
       and s.razonamiento is distinct from v_razonamiento;
    if not found and not exists (select 1 from public.soluciones_pregunta s where s.pregunta_id = p_pregunta_id) then
      raise exception 'QUESTION_SOLUTION_MISSING';
    end if;
  end if;
end;
$$;

revoke all on function private.normalizar_tipo_pregunta(text) from public, anon, authenticated;
revoke all on function private.normalizar_dificultad_pregunta(text) from public, anon, authenticated;
revoke all on function private.validar_opciones_pregunta(jsonb) from public, anon, authenticated;
revoke all on function private.aplicar_opciones_pregunta(uuid, jsonb, text) from public, anon, authenticated;
revoke all on function public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) from public, anon, authenticated;
revoke all on function public.admin_update_question(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) to service_role;
grant execute on function public.admin_update_question(uuid, jsonb) to service_role;

comment on function public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) is
  'Alta atómica: pregunta + 4 opciones + solución [+ vínculo vía admin_link_question_to_exam]. Idempotente por p_pregunta_id (id del cliente): '
  'mismo id y mismo contenido => creada=false; id ajeno => QUESTION_ID_CONFLICT. codigo_pregunta lo asigna Q1.';
comment on function public.admin_update_question(uuid, jsonb) is
  'Edición atómica con claves presentes: titulo, tipo_pregunta, fuente_texto, dificultad, activa, opciones (4, por letra, sin borrar), razonamiento. '
  'Cambiar la correcta no recalifica respuestas_intento históricas.';

do $post$
begin
  if (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname in ('admin_create_question', 'admin_update_question')
         and p.prosecdef
         and p.proconfig @> array['search_path=""']
         and not has_function_privilege('anon', p.oid, 'EXECUTE')
         and not has_function_privilege('authenticated', p.oid, 'EXECUTE')
         and has_function_privilege('service_role', p.oid, 'EXECUTE')) <> 2 then
    raise exception 'POSTCHECK_FAILED: RPC públicas sin SECURITY DEFINER, search_path vacío o ACL esperada';
  end if;
  if (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'private'
         and p.proname in ('normalizar_tipo_pregunta', 'normalizar_dificultad_pregunta', 'validar_opciones_pregunta', 'aplicar_opciones_pregunta')
         and not has_function_privilege('anon', p.oid, 'EXECUTE')
         and not has_function_privilege('authenticated', p.oid, 'EXECUTE')) <> 4 then
    raise exception 'POSTCHECK_FAILED: ayudantes privados ausentes o con ACL abierta';
  end if;
  if not exists (select 1 from pg_constraint where conrelid = 'public.opciones_pregunta'::regclass
                  and conname = 'opciones_pregunta_posicion_letra_chk' and convalidated) then
    raise exception 'POSTCHECK_FAILED: falta opciones_pregunta_posicion_letra_chk validada';
  end if;
end
$post$;
