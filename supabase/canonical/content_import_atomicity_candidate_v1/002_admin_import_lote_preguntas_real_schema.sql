-- CANDIDATE ONLY — NOT APPLIED. Requires explicit approval before running.
--
-- Publicación atómica de un lote de importación de preguntas.
--
-- POR QUÉ
-- El camino actual (`importarLote` en AdminV1Routes.kt) recorre las filas de
-- staging y hace llamadas REST sueltas por cada una. Una pregunta NO es una
-- fila: son cuatro tablas —`preguntas`, `opciones_pregunta`,
-- `soluciones_pregunta` y `preguntas_examen`—, así que un fallo a mitad no deja
-- sólo un lote incompleto, deja **preguntas malformadas**: sin opciones, o con
-- opciones y sin solución. Eso es contenido corrupto servido a usuarios reales.
--
-- Una función plpgsql se ejecuta en una única transacción, de modo que o entra
-- el lote entero o no entra nada.
--
-- SOBRE EL CANDIDATO ANTERIOR (001)
-- `001_admin_import_lote_preguntas.sql` se escribió sin acceso al esquema y
-- delega en `admin_create_question(...)`. Esa función **no existe** en la base de
-- datos (lista de RPC verificada en vivo el 2026-09-02), así que 001 no puede
-- aplicarse tal cual. Este fichero lo sustituye insertando directamente en las
-- cuatro tablas reales, con las columnas verificadas contra el esquema vivo.
--
-- DUPLICADOS
-- Nunca se auto-omite ni se auto-reemplaza. Si se detectan candidatos a
-- duplicado, la función **aborta** y los devuelve en el mensaje de error, de modo
-- que la transacción no escribe nada y una persona decide. Sólo si quien
-- administra lo pide explícitamente (`p_permitir_duplicados => true`) se importan.
-- No se introduce ningún valor nuevo en `estado_parseo`: se desconoce el CHECK
-- exacto de esa columna y un valor inventado rompería la inserción.

begin;

create or replace function public.admin_import_lote_preguntas(
  p_lote_id uuid,
  p_permitir_duplicados boolean default false
)
returns table (
  lote_id uuid,
  importadas integer,
  omitidas integer,
  duplicados_detectados integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_examen_id uuid;
  v_especialidad_id uuid;
  v_tipo_examen text;
  v_tipo_pregunta text;
  v_staging record;
  v_pregunta_id uuid;
  v_opcion_correcta_id uuid;
  v_importadas integer := 0;
  v_omitidas integer := 0;
  v_duplicados text[] := array[]::text[];
  v_max_posicion integer;
begin
  if p_lote_id is null then
    raise exception 'IMPORT_LOTE_ID_REQUIRED';
  end if;

  select l.examen_id, l.tipo_examen
    into v_examen_id, v_tipo_examen
  from public.lotes_importacion_preguntas l
  where l.id = p_lote_id
  for update;

  if not found then
    raise exception 'IMPORT_LOTE_NOT_FOUND';
  end if;

  select e.especialidad_id into v_especialidad_id
  from public.examenes e
  where e.id = v_examen_id;

  if v_especialidad_id is null then
    raise exception 'IMPORT_EXAM_NOT_FOUND';
  end if;

  -- Vocabulario canónico. `guiado` sólo se acepta como alias legacy de entrada y
  -- se normaliza a `razonada`; nunca se escribe `guiada` en datos nuevos.
  v_tipo_pregunta := case lower(coalesce(v_tipo_examen, ''))
    when 'rafaga' then 'rafaga'
    when 'razonado' then 'razonada'
    when 'guiado' then 'razonada'
    when 'inmersivo' then 'inmersiva'
    else 'rafaga'
  end;

  -- Candidatos a duplicado: mismo título dentro de la misma especialidad, entre
  -- preguntas activas que no vengan de este mismo lote. Se comparan normalizados
  -- (sin mayúsculas ni espacios sobrantes) porque el PDF de origen introduce
  -- diferencias de formato que no cambian la pregunta.
  select array_agg(distinct s.titulo)
    into v_duplicados
  from public.preguntas_parseadas_staging s
  join public.preguntas q
    on q.especialidad_id = v_especialidad_id
   and q.activa
   and coalesce(q.lote_importacion_id, '00000000-0000-0000-0000-000000000000'::uuid) <> p_lote_id
   and lower(btrim(q.titulo)) = lower(btrim(s.titulo))
  where s.lote_id = p_lote_id
    and s.pregunta_id is null
    and s.estado_parseo in ('ok', 'aprobada');

  v_duplicados := coalesce(v_duplicados, array[]::text[]);

  if array_length(v_duplicados, 1) > 0 and not p_permitir_duplicados then
    -- Aborta la transacción entera: no se escribe nada y decide una persona.
    raise exception 'IMPORT_DUPLICATE_CANDIDATES: %', array_to_string(v_duplicados, ' | ');
  end if;

  -- Punto de partida para numerar dentro del examen, para no pisar preguntas ya
  -- asociadas si el examen ya tenía contenido.
  select coalesce(max(pe.posicion), 0) into v_max_posicion
  from public.preguntas_examen pe
  where pe.examen_id = v_examen_id;

  for v_staging in
    select *
    from public.preguntas_parseadas_staging s
    where s.lote_id = p_lote_id
      and s.pregunta_id is null
      and s.estado_parseo in ('ok', 'aprobada')
    order by s.posicion asc, s.creado_en asc
  loop
    if btrim(coalesce(v_staging.titulo, '')) = '' then
      raise exception 'IMPORT_QUESTION_WITHOUT_TITLE: staging %', v_staging.id;
    end if;
    if btrim(coalesce(v_staging.respuesta_correcta, '')) = '' then
      raise exception 'IMPORT_QUESTION_WITHOUT_ANSWER: staging %', v_staging.id;
    end if;

    insert into public.preguntas (
      codigo_pregunta, especialidad_id, tipo_pregunta, titulo,
      fuente_texto, activa, lote_importacion_id
    )
    values (
      coalesce(nullif(btrim(v_staging.codigo_pregunta_generado), ''),
               'IMP-' || substr(replace(v_staging.id::text, '-', ''), 1, 10)),
      v_especialidad_id,
      v_tipo_pregunta,
      btrim(v_staging.titulo),
      nullif(btrim(coalesce(v_staging.fuente_texto, '')), ''),
      true,
      p_lote_id
    )
    returning id into v_pregunta_id;

    -- Opciones. Sólo las no vacías: un PDF puede traer preguntas de tres opciones.
    insert into public.opciones_pregunta (pregunta_id, letra, texto_opcion, posicion)
    select v_pregunta_id, o.letra, o.texto, o.pos
    from (
      values
        ('A', btrim(coalesce(v_staging.opcion_a, '')), 1),
        ('B', btrim(coalesce(v_staging.opcion_b, '')), 2),
        ('C', btrim(coalesce(v_staging.opcion_c, '')), 3),
        ('D', btrim(coalesce(v_staging.opcion_d, '')), 4)
    ) as o(letra, texto, pos)
    where o.texto <> '';

    select op.id into v_opcion_correcta_id
    from public.opciones_pregunta op
    where op.pregunta_id = v_pregunta_id
      and upper(op.letra) = upper(btrim(v_staging.respuesta_correcta))
    limit 1;

    if v_opcion_correcta_id is null then
      -- La respuesta marcada no corresponde a ninguna opción con texto: la
      -- pregunta sería incontestable. Aborta todo el lote.
      raise exception 'IMPORT_ANSWER_NOT_IN_OPTIONS: staging % (respuesta %)',
        v_staging.id, v_staging.respuesta_correcta;
    end if;

    insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
    values (
      v_pregunta_id,
      v_opcion_correcta_id,
      nullif(btrim(coalesce(v_staging.razonamiento, '')), '')
    );

    v_max_posicion := v_max_posicion + 1;
    insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
    values (v_examen_id, v_pregunta_id, v_max_posicion, v_max_posicion);

    update public.preguntas_parseadas_staging
       set pregunta_id = v_pregunta_id,
           estado_parseo = 'importada',
           actualizado_en = now()
     where id = v_staging.id;

    v_importadas := v_importadas + 1;
  end loop;

  select count(*) into v_omitidas
  from public.preguntas_parseadas_staging s
  where s.lote_id = p_lote_id and s.estado_parseo = 'omitida';

  -- El lote sólo puede quedar 'importada' o fallar entero: no existe 'parcial'
  -- por diseño, que es justamente lo que esta función viene a eliminar.
  update public.lotes_importacion_preguntas
     set estado = 'importada',
         total_importadas = v_importadas,
         mensaje_error = null,
         actualizado_en = now()
   where id = p_lote_id;

  return query select p_lote_id, v_importadas, v_omitidas::integer,
                      coalesce(array_length(v_duplicados, 1), 0);
end;
$$;

revoke execute on function public.admin_import_lote_preguntas(uuid, boolean)
  from public, anon, authenticated;
grant execute on function public.admin_import_lote_preguntas(uuid, boolean)
  to service_role;

commit;
