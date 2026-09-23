-- CANDIDATE ONLY — NOT APPLIED. Requires explicit approval before running.
--
-- RPC transaccionales para escritura de preguntas:
--   * public.admin_create_question(...)
--   * public.admin_replace_question_options(...)
--
-- POR QUÉ
-- `AdminV1Routes.kt` ya llama a estas dos funciones (`/rest/v1/rpc/admin_create_question`
-- y `/rest/v1/rpc/admin_replace_question_options`), pero NINGUNA existe en la base
-- de datos. Verificado en vivo el 2026-09-04 por introspección de PostgREST
-- (`GET /rest/v1/` → paths `/rpc/*`): las únicas RPC administrativas expuestas son
-- `admin_grant_tickets` y las de estadísticas/roles. Consecuencia: crear una
-- pregunta y reemplazar sus opciones fallan hoy en producción.
--
-- Una pregunta NO es una fila: son cuatro tablas —`preguntas`, `opciones_pregunta`,
-- `soluciones_pregunta` y `preguntas_examen`. Hacerlo por REST fila a fila puede
-- dejar preguntas malformadas (opciones sin solución, o pregunta sin opciones).
-- Una función plpgsql corre en una única transacción: entra todo o no entra nada.
--
-- ESQUEMA VERIFICADO EN VIVO (2026-09-04, introspección PostgREST del proyecto real)
--   preguntas(id uuid pk, codigo_pregunta text NOT NULL, especialidad_id uuid NOT NULL,
--             tipo_pregunta text NOT NULL, titulo text NOT NULL, fuente_texto text,
--             dificultad text, activa boolean NOT NULL, lote_importacion_id uuid,
--             creado_por uuid, creado_en timestamptz NOT NULL, actualizado_en timestamptz NOT NULL)
--   opciones_pregunta(id uuid pk, pregunta_id uuid NOT NULL, letra text NOT NULL,
--             texto_opcion text NOT NULL, posicion integer NOT NULL, creado_en, actualizado_en)
--   soluciones_pregunta(id uuid pk, pregunta_id uuid NOT NULL, opcion_correcta_id uuid NOT NULL,
--             razonamiento text NOT NULL, creado_en, actualizado_en)
--   preguntas_examen(examen_id uuid, pregunta_id uuid, [PK compuesta],
--             posicion integer NOT NULL, numero_en_examen integer NOT NULL, creado_en)
--
-- LO QUE NO SE INVENTA
--   * `codigo_pregunta` es NOT NULL y su FORMATO NO ESTÁ DECIDIDO: la única fila
--     existente usa `TCAE-TEST-RAF-P001`, que no sigue el patrón `PREG-000001` de la
--     plantilla propuesta. Por eso esta función NO genera el código: lo exige como
--     parámetro y falla si viene vacío. Generarlo es una decisión de producto
--     (ver PDF_IMPORT.md / CLIENT_DECISIONS_REQUIRED.md), y derivarlo del UUID está
--     explícitamente prohibido.
--   * No se toca `dificultad` más allá de pasar el valor recibido: su CHECK exacto
--     no está verificado.
--   * No se asume que existan 4 opciones: el contrato de 4 lo aplican hoy el cliente
--     y Ktor. Aquí se exige lo que la integridad del dato requiere: al menos dos
--     opciones, letras únicas y EXACTAMENTE UNA correcta.
--
-- SEGURIDAD
--   `security definer` + `set search_path = ''` (sin esquema implícito, todas las
--   referencias cualificadas) + privilegios revocados a anon/authenticated. Sólo
--   `service_role` —es decir, sólo Ktor— puede ejecutarlas. Ningún cliente llega.

begin;

-- ---------------------------------------------------------------- PREFLIGHT
-- Aborta antes de crear nada si el esquema no es el verificado.
do $preflight$
declare
  v_missing text[] := array[]::text[];
begin
  if to_regclass('public.preguntas') is null then v_missing := v_missing || 'public.preguntas'; end if;
  if to_regclass('public.opciones_pregunta') is null then v_missing := v_missing || 'public.opciones_pregunta'; end if;
  if to_regclass('public.soluciones_pregunta') is null then v_missing := v_missing || 'public.soluciones_pregunta'; end if;
  if to_regclass('public.preguntas_examen') is null then v_missing := v_missing || 'public.preguntas_examen'; end if;

  if array_length(v_missing, 1) is not null then
    raise exception 'PREFLIGHT_FAILED: faltan tablas %', array_to_string(v_missing, ', ');
  end if;

  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'preguntas_examen' and column_name = 'numero_en_examen'
  ) then
    raise exception 'PREFLIGHT_FAILED: preguntas_examen.numero_en_examen no existe; revisar el esquema antes de aplicar';
  end if;
end
$preflight$;

-- ------------------------------------------------------ admin_create_question
create or replace function public.admin_create_question(
  p_codigo_pregunta text,
  p_especialidad_id uuid,
  p_tipo_pregunta text,
  p_titulo text,
  p_opciones jsonb,               -- [{"letra":"A","texto":"...","posicion":1,"es_correcta":false}, ...]
  p_razonamiento text default '',
  p_fuente_texto text default '',
  p_dificultad text default 'media',
  p_activa boolean default true,
  p_examen_id uuid default null,
  p_posicion integer default null,
  p_lote_importacion_id uuid default null,
  p_creado_por uuid default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pregunta_id uuid := gen_random_uuid();
  v_opcion jsonb;
  v_correctas integer;
  v_total integer;
  v_letras integer;
  v_opcion_correcta_id uuid;
  v_id uuid;
  v_numero integer;
begin
  -- Validación de entrada -------------------------------------------------
  if coalesce(btrim(p_codigo_pregunta), '') = '' then
    raise exception 'INVALID_REQUEST: codigo_pregunta es obligatorio (su generación es una decisión de producto pendiente)';
  end if;
  if coalesce(btrim(p_titulo), '') = '' then
    raise exception 'INVALID_REQUEST: titulo (enunciado) es obligatorio';
  end if;
  if p_especialidad_id is null then
    raise exception 'INVALID_REQUEST: especialidad_id es obligatorio';
  end if;
  if p_opciones is null or jsonb_typeof(p_opciones) <> 'array' then
    raise exception 'INVALID_REQUEST: opciones debe ser un array JSON';
  end if;

  select count(*), count(*) filter (where (value->>'es_correcta')::boolean is true),
         count(distinct upper(btrim(value->>'letra')))
    into v_total, v_correctas, v_letras
    from jsonb_array_elements(p_opciones) as value;

  if v_total < 2 then
    raise exception 'BUSINESS_RULE: una pregunta necesita al menos dos opciones';
  end if;
  if v_letras <> v_total then
    raise exception 'BUSINESS_RULE: las letras de opción deben ser únicas';
  end if;
  if v_correctas <> 1 then
    raise exception 'INVALID_CORRECT_ANSWER: debe haber exactamente una opción correcta (recibidas %)', v_correctas;
  end if;
  if exists (select 1 from jsonb_array_elements(p_opciones) as value
             where coalesce(btrim(value->>'texto'), '') = '') then
    raise exception 'BUSINESS_RULE: ninguna opción puede estar vacía';
  end if;

  if exists (select 1 from public.preguntas where codigo_pregunta = btrim(p_codigo_pregunta)) then
    raise exception 'CONFLICT: ya existe una pregunta con codigo_pregunta %', btrim(p_codigo_pregunta);
  end if;
  if not exists (select 1 from public.especialidades where id = p_especialidad_id) then
    raise exception 'NOT_FOUND: especialidad % no existe', p_especialidad_id;
  end if;
  if p_examen_id is not null and not exists (select 1 from public.examenes where id = p_examen_id) then
    raise exception 'NOT_FOUND: examen % no existe', p_examen_id;
  end if;

  -- Pregunta ---------------------------------------------------------------
  insert into public.preguntas (
    id, codigo_pregunta, especialidad_id, tipo_pregunta, titulo,
    fuente_texto, dificultad, activa, lote_importacion_id, creado_por
  ) values (
    v_pregunta_id, btrim(p_codigo_pregunta), p_especialidad_id, p_tipo_pregunta, btrim(p_titulo),
    coalesce(p_fuente_texto, ''), coalesce(nullif(btrim(p_dificultad), ''), 'media'),
    coalesce(p_activa, true), p_lote_importacion_id, p_creado_por
  );

  -- Opciones ---------------------------------------------------------------
  for v_opcion in select value from jsonb_array_elements(p_opciones) as value loop
    v_id := gen_random_uuid();
    insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
    values (
      v_id,
      v_pregunta_id,
      upper(btrim(v_opcion->>'letra')),
      btrim(v_opcion->>'texto'),
      coalesce((v_opcion->>'posicion')::integer, 0)
    );
    if (v_opcion->>'es_correcta')::boolean is true then
      v_opcion_correcta_id := v_id;
    end if;
  end loop;

  -- Solución ---------------------------------------------------------------
  insert into public.soluciones_pregunta (id, pregunta_id, opcion_correcta_id, razonamiento)
  values (gen_random_uuid(), v_pregunta_id, v_opcion_correcta_id, coalesce(p_razonamiento, ''));

  -- Relación con examen ----------------------------------------------------
  if p_examen_id is not null then
    select coalesce(max(numero_en_examen), 0) + 1 into v_numero
      from public.preguntas_examen where examen_id = p_examen_id;

    insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
    values (
      p_examen_id,
      v_pregunta_id,
      coalesce(nullif(p_posicion, 0), v_numero),
      v_numero
    );
  end if;

  return v_pregunta_id;
end;
$$;

-- ------------------------------------------- admin_replace_question_options
create or replace function public.admin_replace_question_options(
  p_pregunta_id uuid,
  p_opciones jsonb,
  p_razonamiento text default null   -- null = conservar el razonamiento actual
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_opcion jsonb;
  v_correctas integer;
  v_total integer;
  v_letras integer;
  v_opcion_correcta_id uuid;
  v_id uuid;
  v_razonamiento text;
begin
  if p_pregunta_id is null then
    raise exception 'INVALID_REQUEST: pregunta_id es obligatorio';
  end if;
  if not exists (select 1 from public.preguntas where id = p_pregunta_id) then
    raise exception 'NOT_FOUND: pregunta % no existe', p_pregunta_id;
  end if;
  if p_opciones is null or jsonb_typeof(p_opciones) <> 'array' then
    raise exception 'INVALID_REQUEST: opciones debe ser un array JSON';
  end if;

  select count(*), count(*) filter (where (value->>'es_correcta')::boolean is true),
         count(distinct upper(btrim(value->>'letra')))
    into v_total, v_correctas, v_letras
    from jsonb_array_elements(p_opciones) as value;

  if v_total < 2 then
    raise exception 'BUSINESS_RULE: una pregunta necesita al menos dos opciones';
  end if;
  if v_letras <> v_total then
    raise exception 'BUSINESS_RULE: las letras de opción deben ser únicas';
  end if;
  if v_correctas <> 1 then
    raise exception 'INVALID_CORRECT_ANSWER: debe haber exactamente una opción correcta (recibidas %)', v_correctas;
  end if;
  if exists (select 1 from jsonb_array_elements(p_opciones) as value
             where coalesce(btrim(value->>'texto'), '') = '') then
    raise exception 'BUSINESS_RULE: ninguna opción puede estar vacía';
  end if;

  -- Conservar el razonamiento si el llamante no envía uno nuevo: reemplazar
  -- opciones no debería borrar la explicación por omisión.
  select coalesce(p_razonamiento, razonamiento, '')
    into v_razonamiento
    from public.soluciones_pregunta
   where pregunta_id = p_pregunta_id
   limit 1;
  v_razonamiento := coalesce(v_razonamiento, coalesce(p_razonamiento, ''));

  -- Orden obligatorio: la solución referencia una opción (FK), así que se borra
  -- primero la solución, después las opciones. Todo dentro de la misma
  -- transacción: nunca quedan opciones nuevas con solución vieja.
  delete from public.soluciones_pregunta where pregunta_id = p_pregunta_id;
  delete from public.opciones_pregunta where pregunta_id = p_pregunta_id;

  for v_opcion in select value from jsonb_array_elements(p_opciones) as value loop
    v_id := gen_random_uuid();
    insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
    values (
      v_id,
      p_pregunta_id,
      upper(btrim(v_opcion->>'letra')),
      btrim(v_opcion->>'texto'),
      coalesce((v_opcion->>'posicion')::integer, 0)
    );
    if (v_opcion->>'es_correcta')::boolean is true then
      v_opcion_correcta_id := v_id;
    end if;
  end loop;

  insert into public.soluciones_pregunta (id, pregunta_id, opcion_correcta_id, razonamiento)
  values (gen_random_uuid(), p_pregunta_id, v_opcion_correcta_id, v_razonamiento);

  update public.preguntas set actualizado_en = now() where id = p_pregunta_id;

  return p_pregunta_id;
end;
$$;

-- ------------------------------------------------------------- PRIVILEGIOS
revoke all on function public.admin_create_question(text, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) from public, anon, authenticated;
revoke all on function public.admin_replace_question_options(uuid, jsonb, text) from public, anon, authenticated;
grant execute on function public.admin_create_question(text, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) to service_role;
grant execute on function public.admin_replace_question_options(uuid, jsonb, text) to service_role;

commit;

-- ------------------------------------------------------------- ROLLBACK
-- Ejecutar sólo si hay que revertir esta aplicación. No borra datos: estas
-- funciones no existían antes, así que eliminarlas devuelve la base al estado
-- previo exacto (el flujo de creación vuelve a fallar, como fallaba antes).
--
-- begin;
-- drop function if exists public.admin_create_question(text, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid);
-- drop function if exists public.admin_replace_question_options(uuid, jsonb, text);
-- commit;
