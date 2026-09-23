-- IM3 · Publicación atómica de un lote: todo o nada, idempotente, con claim, reutilizando Q1 (código), Q2 (alta) y M4 (vínculo).
set local lock_timeout = '5s';
lock table public.lotes_importacion_preguntas, public.preguntas_parseadas_staging in share row exclusive mode;

do $pre$
begin
  if to_regprocedure('public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid)') is null then
    raise exception 'PREFLIGHT_FAILED: aplicar antes questions_write_rpcs (Q2)';
  end if;
  if to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer)') is null then
    raise exception 'PREFLIGHT_FAILED: aplicar antes exams_questions_order_and_link_rpcs (M4)';
  end if;
  -- coalesce (revisión C, IM3-3): sin el constraint, `NULL not like …` es NULL y el IF no lanzaría.
  if coalesce((select pg_get_constraintdef(c.oid) from pg_constraint c
                where c.conrelid = 'public.preguntas_parseadas_staging'::regclass
                  and c.conname = 'preguntas_parseadas_staging_estado_parseo_check'), '') not like '%aprobada%omitida%' then
    raise exception 'PREFLIGHT_FAILED: aplicar antes import_staging_states (IM1)';
  end if;
  if not exists (select 1 from pg_attribute where attrelid = 'public.preguntas_parseadas_staging'::regclass
                  and attname = 'vincular_pregunta_id' and not attisdropped)
     or not exists (select 1 from pg_trigger where tgrelid = 'public.preguntas_parseadas_staging'::regclass
                     and tgname = 'trg_staging_inmutable_tras_importar') then
    raise exception 'PREFLIGHT_FAILED: aplicar antes import_staging_review (IM2)';
  end if;
  -- I-12: ninguna sobrecarga previa (candidatos 001/002). Si existe, se retira con su propia autorización; aquí no se hace DROP.
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where (n.nspname = 'public' and p.proname in ('admin_import_lote_preguntas', 'admin_import_lote_duplicados'))
                 or (n.nspname = 'private' and p.proname in ('normalizar_texto_pregunta', 'import_lote_totales'))) then
    raise exception 'PREFLIGHT_FAILED: alguna función ya existe';
  end if;
end
$pre$;

create function private.normalizar_texto_pregunta(p_texto text)
returns text
language sql
immutable
set search_path = ''
as $$
  select lower(btrim(regexp_replace(coalesce(p_texto, ''), '\s+', ' ', 'g')))
$$;

create function private.import_lote_totales(p_lote_id uuid)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object(
    'lote_id', l.id,
    'estado', l.estado,
    'total_detectadas', l.total_detectadas,
    'total_importadas', (select count(*) from public.preguntas_parseadas_staging s where s.lote_id = l.id and s.pregunta_id is not null),
    'omitidas', (select count(*) from public.preguntas_parseadas_staging s where s.lote_id = l.id and s.estado_parseo = 'omitida'))
    from public.lotes_importacion_preguntas l
   where l.id = p_lote_id
$$;

-- DEC-IMP-01: huella = título + A + B + C + D normalizados, entre preguntas ACTIVAS de la misma especialidad y tipo.
-- La correcta no entra en la huella (así se detectan también respuestas contradictorias). Sólo lectura.
create function public.admin_import_lote_duplicados(p_lote_id uuid)
returns table (staging_id uuid, origen text, pregunta_id uuid, codigo_pregunta text, staging_original_id uuid)
language sql
stable
security definer
set search_path = ''
as $$
  with lote as (
    select e.especialidad_id,
           case e.tipo_examen when 'rafaga' then 'rafaga' when 'razonado' then 'razonada' when 'inmersivo' then 'inmersiva' end as tipo
      from public.lotes_importacion_preguntas l
      join public.examenes e on e.id = l.examen_id
     where l.id = p_lote_id
  ),
  filas as (
    select s.id, s.posicion,
           private.normalizar_texto_pregunta(s.titulo) as titulo_n,
           concat_ws(chr(31), private.normalizar_texto_pregunta(s.opcion_a), private.normalizar_texto_pregunta(s.opcion_b),
                     private.normalizar_texto_pregunta(s.opcion_c), private.normalizar_texto_pregunta(s.opcion_d)) as opciones_n
      from public.preguntas_parseadas_staging s
     where s.lote_id = p_lote_id
       and s.pregunta_id is null
  ),
  candidatas as (
    select f.id as staging_id, p.id as pregunta_id, p.codigo_pregunta, f.opciones_n
      from filas f
      cross join lote
      join public.preguntas p
        on p.especialidad_id = lote.especialidad_id
       and p.tipo_pregunta = lote.tipo
       and p.activa
       and private.normalizar_texto_pregunta(p.titulo) = f.titulo_n
  )
  select c.staging_id, 'banco'::text, c.pregunta_id, c.codigo_pregunta, null::uuid
    from candidatas c
   where c.opciones_n = (
           select concat_ws(chr(31),
                    private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'A')),
                    private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'B')),
                    private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'C')),
                    private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'D')))
             from public.opciones_pregunta o
            where o.pregunta_id = c.pregunta_id)
  union all
  -- Entre paréntesis: sin ellos el ORDER BY del DISTINCT ON se aplicaría a toda la UNION y no compilaría.
  (select distinct on (f2.id) f2.id, 'lote'::text, null::uuid, null::text, f1.id
     from filas f1
     join filas f2 on f2.titulo_n = f1.titulo_n and f2.opciones_n = f1.opciones_n and f2.posicion > f1.posicion
    order by f2.id, f1.posicion)
$$;

create function public.admin_import_lote_preguntas(
  p_lote_id uuid,
  p_confirmar_examen_publicado boolean default false,
  p_actor_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_lote record;
  v_examen record;
  v_tipo_pregunta text;
  v_posiciones text;
  v_a_publicar integer;
  v_fila record;
  v_creada jsonb;
  v_nuevas integer := 0;
  v_vinculadas integer := 0;
  v_ya_vinculada boolean;
begin
  if p_lote_id is null then
    raise exception 'IMPORT_LOTE_ID_REQUIRED';
  end if;

  -- 0) Revisión C (IM3-1): KEY SHARE del examen ANTES que el cerrojo del lote. Un DELETE del examen
  --    (cascada a lotes) espera aquí en vez de cruzarse con el paso 9 (40P01).
  --    KEY SHARE es compatible con el NO KEY UPDATE de M1/M3/M4/Q2: no altera R-b.
  --    Si el examen ya se borró, el claim del paso 1 no encuentra el lote: IMPORT_LOTE_NOT_FOUND.
  perform 1
    from public.examenes e
   where e.id = (select l.examen_id from public.lotes_importacion_preguntas l where l.id = p_lote_id)
     for key share;

  -- 1) Claim del lote: dos publicaciones del mismo lote se serializan aquí.
  select l.id, l.examen_id, l.tipo_examen, l.estado
    into v_lote
    from public.lotes_importacion_preguntas l
   where l.id = p_lote_id
     for update;
  if not found then
    raise exception 'IMPORT_LOTE_NOT_FOUND';
  end if;

  -- 2) Idempotencia real (I-15): un lote importado devuelve sus totales derivados y no escribe nada.
  if v_lote.estado = 'importado' then
    return private.import_lote_totales(p_lote_id) || jsonb_build_object('ejecutado', false, 'nuevas', 0, 'vinculadas', 0);
  end if;
  if v_lote.estado not in ('parseado', 'revisado', 'fallido') then
    raise exception 'IMPORT_LOTE_STATE_INVALID';
  end if;

  -- 3) Cerrojo de todas las filas del lote, en orden (I-17): nadie las edita mientras se publica.
  perform 1
    from public.preguntas_parseadas_staging s
   where s.lote_id = p_lote_id
   order by s.posicion
     for update;

  -- 4) Examen y tipo (I-8). Todavía sin cerrojo: R-b exige bloquear antes las preguntas existentes.
  select e.id, e.especialidad_id, e.tipo_examen
    into v_examen
    from public.examenes e
   where e.id = v_lote.examen_id;
  if not found then
    raise exception 'IMPORT_EXAM_NOT_FOUND';
  end if;
  if v_lote.tipo_examen is distinct from v_examen.tipo_examen then
    raise exception 'IMPORT_TYPE_MISMATCH';
  end if;
  v_tipo_pregunta := case v_examen.tipo_examen
                       when 'rafaga' then 'rafaga' when 'razonado' then 'razonada' when 'inmersivo' then 'inmersiva' end;
  if v_tipo_pregunta is null then
    raise exception 'IMPORT_EXAM_TYPE_UNKNOWN';
  end if;

  -- 4b) Revisión C (IM3-2): serializa las publicaciones de la misma especialidad y tipo mientras se
  --     comprueban duplicados y se crean preguntas (DEC-IMP-01 «nunca copia»: dos lotes con el mismo
  --     contenido en exámenes distintos no pueden pasar a la vez la comprobación del paso 7).
  --     Orden estable: examen KEY SHARE (0) → lote (1) → filas (3) → este cerrojo → preguntas (9) → examen NKU (9).
  perform pg_advisory_xact_lock(hashtext('oposicontrol.import_lote'),
                                hashtext(v_examen.especialidad_id::text || ':' || v_tipo_pregunta));

  -- 5) Todo revisado (I-16): ninguna fila pendiente en error o advertencia.
  if exists (select 1 from public.preguntas_parseadas_staging s
              where s.lote_id = p_lote_id and s.pregunta_id is null
                and s.estado_parseo not in ('ok', 'aprobada', 'omitida')) then
    raise exception 'IMPORT_PENDING_REVIEW';
  end if;

  -- 6) Contenido completo para su tipo (I-4, DEC-Q-01, DEC-IMP-04). El detalle lleva SÓLO posiciones.
  select string_agg(s.posicion::text, ',' order by s.posicion)
    into v_posiciones
    from public.preguntas_parseadas_staging s
   where s.lote_id = p_lote_id and s.pregunta_id is null
     and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is null
     and (private.normalizar_texto_pregunta(s.titulo) = ''
          or private.normalizar_texto_pregunta(s.opcion_a) = ''
          or private.normalizar_texto_pregunta(s.opcion_b) = ''
          or private.normalizar_texto_pregunta(s.opcion_c) = ''
          or private.normalizar_texto_pregunta(s.opcion_d) = ''
          or s.respuesta_correcta is null
          or (v_tipo_pregunta in ('razonada', 'inmersiva')
              and private.normalizar_texto_pregunta(s.razonamiento) = ''));
  if v_posiciones is not null then
    -- Revisión C (IM3-6): el detalle no corta una posición por la mitad.
    raise exception 'IMPORT_ROW_INCOMPLETE'
      using detail = case when length(v_posiciones) <= 200 then v_posiciones
                          else regexp_replace(left(v_posiciones, 200), ',[0-9]*$', '') end;
  end if;

  -- 7) Duplicados sin decidir (DEC-IMP-01). Del banco: omitir o vincular. Dentro del lote: omitir una de las dos.
  if exists (
       select 1
         from public.admin_import_lote_duplicados(p_lote_id) d
         join public.preguntas_parseadas_staging s on s.id = d.staging_id
        where d.origen = 'banco'
          and s.estado_parseo in ('ok', 'aprobada')
          and s.vincular_pregunta_id is null)
     -- Dentro del lote se comparan todas las filas que se van a publicar, también
     -- las que vinculan: dos copias del PDF no pueden aprobarse ambas apuntando al
     -- mismo (o a distintos) duplicados del banco; una debe quedar omitida.
     or exists (
       select 1
         from public.preguntas_parseadas_staging a
         join public.preguntas_parseadas_staging b
           on b.lote_id = a.lote_id and b.posicion > a.posicion
        where a.lote_id = p_lote_id
          and a.pregunta_id is null and b.pregunta_id is null
          and a.estado_parseo in ('ok', 'aprobada') and b.estado_parseo in ('ok', 'aprobada')
          and private.normalizar_texto_pregunta(a.titulo) = private.normalizar_texto_pregunta(b.titulo)
          and private.normalizar_texto_pregunta(a.opcion_a) = private.normalizar_texto_pregunta(b.opcion_a)
          and private.normalizar_texto_pregunta(a.opcion_b) = private.normalizar_texto_pregunta(b.opcion_b)
          and private.normalizar_texto_pregunta(a.opcion_c) = private.normalizar_texto_pregunta(b.opcion_c)
          and private.normalizar_texto_pregunta(a.opcion_d) = private.normalizar_texto_pregunta(b.opcion_d))
  then
    raise exception 'IMPORT_DUPLICATES_UNRESOLVED';
  end if;

  -- 8) PRIMERO bloquear los destinos de «vincular», ordenados por id. Q2 toma un
  --    lock incompatible sobre la pregunta antes de cambiar título u opciones, de
  --    modo que la huella que se revalida a continuación queda estable.
  perform 1
    from public.preguntas p
   where p.id in (select s.vincular_pregunta_id
                    from public.preguntas_parseadas_staging s
                   where s.lote_id = p_lote_id and s.pregunta_id is null
                     and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is not null)
   order by p.id
     for share;

  -- 8b) Bajo el lock, la decisión «vincular» sólo es válida si el destino sigue
  --     siendo exactamente el duplicado revisado: mismo ámbito y misma huella
  --     normalizada (título + A-D). La respuesta correcta no forma parte de la
  --     huella por DEC-IMP-01.
  if exists (
    select 1
      from public.preguntas_parseadas_staging s
      left join public.preguntas p on p.id = s.vincular_pregunta_id
     where s.lote_id = p_lote_id and s.pregunta_id is null
       and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is not null
       and (p.id is null or not p.activa
         or p.especialidad_id <> v_examen.especialidad_id or p.tipo_pregunta <> v_tipo_pregunta
         or private.normalizar_texto_pregunta(p.titulo)
              is distinct from private.normalizar_texto_pregunta(s.titulo)
         or (select concat_ws(chr(31),
               private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'A')),
               private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'B')),
               private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'C')),
               private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'D')))
               from public.opciones_pregunta o where o.pregunta_id = p.id)
              is distinct from concat_ws(chr(31),
               private.normalizar_texto_pregunta(s.opcion_a),
               private.normalizar_texto_pregunta(s.opcion_b),
               private.normalizar_texto_pregunta(s.opcion_c),
               private.normalizar_texto_pregunta(s.opcion_d)))) then
    raise exception 'IMPORT_LINK_TARGET_INVALID';
  end if;

  -- 9) DESPUÉS el examen: conserva el orden pregunta → examen de Q2/M4.
  perform 1 from public.examenes e where e.id = v_examen.id for no key update;

  -- 10) Tope de 200 (I-10), antes de escribir nada. Revisión C (IM3-4): con M:N, «vincular» una pregunta
  --     que ya está en este examen no añade fila, así que no cuenta para el tope.
  select count(*)::integer
    into v_a_publicar
    from public.preguntas_parseadas_staging s
   where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
     and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id);
  if (select count(*) from public.preguntas_examen pe where pe.examen_id = v_examen.id) + v_a_publicar > 200 then
    raise exception 'EXAM_QUESTION_LIMIT_REACHED';
  end if;

  -- 11) Examen publicado (I-20, DEC-IMP-02), ya bajo cerrojo. Revisión C (IM3-5): sólo si hay algo que
  --     se verá (activo y publicado, que es lo que lista la app) y hay filas que publicar.
  if v_a_publicar > 0
     and (select e.publicado_en is not null and e.activo from public.examenes e where e.id = v_examen.id)
     and not coalesce(p_confirmar_examen_publicado, false) then
    raise exception 'IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED';
  end if;

  -- 12) Publicación en orden de posición. Cada alta: código PREG-n (Q1), 4 opciones y solución (Q2), vínculo al final (M4).
  for v_fila in
    select s.id, s.titulo, s.opcion_a, s.opcion_b, s.opcion_c, s.opcion_d, s.respuesta_correcta,
           s.razonamiento, s.fuente_texto, s.vincular_pregunta_id
      from public.preguntas_parseadas_staging s
     where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
     order by s.posicion
  loop
    if v_fila.vincular_pregunta_id is not null then
      -- Revisión C (IM3-8b): sólo cuenta como vinculada si M4 añadió el vínculo (ya_vinculada = false).
      select l.ya_vinculada into v_ya_vinculada
        from public.admin_link_question_to_exam(v_examen.id, v_fila.vincular_pregunta_id, null) as l;
      update public.preguntas_parseadas_staging s
         set pregunta_id = v_fila.vincular_pregunta_id,
             estado_parseo = 'importada'
       where s.id = v_fila.id;
      if not coalesce(v_ya_vinculada, false) then
        v_vinculadas := v_vinculadas + 1;
      end if;
    else
      v_creada := public.admin_create_question(
        gen_random_uuid(),
        v_examen.especialidad_id,
        v_tipo_pregunta,
        v_fila.titulo,
        jsonb_build_array(
          jsonb_build_object('letra', 'A', 'texto', v_fila.opcion_a, 'es_correcta', v_fila.respuesta_correcta = 'A'),
          jsonb_build_object('letra', 'B', 'texto', v_fila.opcion_b, 'es_correcta', v_fila.respuesta_correcta = 'B'),
          jsonb_build_object('letra', 'C', 'texto', v_fila.opcion_c, 'es_correcta', v_fila.respuesta_correcta = 'C'),
          jsonb_build_object('letra', 'D', 'texto', v_fila.opcion_d, 'es_correcta', v_fila.respuesta_correcta = 'D')),
        case when v_tipo_pregunta = 'rafaga' then null else v_fila.razonamiento end,   -- Ráfaga no publica explicación
        case when v_tipo_pregunta = 'rafaga' then null else v_fila.fuente_texto end,   -- ni fuente (§3.1)
        null,            -- dificultad
        true,            -- activa
        v_examen.id,     -- vínculo en esta misma transacción (M4)
        null,            -- al final del examen (Q-14)
        p_lote_id,
        p_actor_id);
      update public.preguntas_parseadas_staging s
         set pregunta_id = (v_creada ->> 'pregunta_id')::uuid,
             codigo_pregunta_generado = v_creada ->> 'codigo_pregunta',
             estado_parseo = 'importada'
       where s.id = v_fila.id;
      v_nuevas := v_nuevas + 1;
    end if;
  end loop;

  -- 13) Cierre con totales DERIVADOS (I-15). Sin 'parcial' ni 'revisar' (C-11).
  update public.lotes_importacion_preguntas l
     set estado = 'importado',
         total_importadas = (select count(*) from public.preguntas_parseadas_staging s
                              where s.lote_id = l.id and s.pregunta_id is not null),
         mensaje_error = null
   where l.id = p_lote_id;

  return private.import_lote_totales(p_lote_id) || jsonb_build_object('ejecutado', true, 'nuevas', v_nuevas, 'vinculadas', v_vinculadas);
end;
$$;

revoke all on function private.normalizar_texto_pregunta(text) from public, anon, authenticated;
revoke all on function private.import_lote_totales(uuid) from public, anon, authenticated;
revoke all on function public.admin_import_lote_duplicados(uuid) from public, anon, authenticated;
revoke all on function public.admin_import_lote_preguntas(uuid, boolean, uuid) from public, anon, authenticated;
grant execute on function public.admin_import_lote_duplicados(uuid) to service_role;
grant execute on function public.admin_import_lote_preguntas(uuid, boolean, uuid) to service_role;

comment on function public.admin_import_lote_preguntas(uuid, boolean, uuid) is
  'Publica un lote de staging todo o nada. Idempotente (lote importado => ejecutado=false). Exige revisión completa, duplicados decididos '
  'y confirmación si el examen está publicado. Reutiliza admin_create_question (Q1/Q2) y admin_link_question_to_exam (M4).';

do $post$
begin
  if (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname in ('admin_import_lote_preguntas', 'admin_import_lote_duplicados')
         and p.prosecdef and p.proconfig @> array['search_path=""']
         and not has_function_privilege('anon', p.oid, 'EXECUTE')
         and not has_function_privilege('authenticated', p.oid, 'EXECUTE')
         and has_function_privilege('service_role', p.oid, 'EXECUTE')) <> 2 then
    raise exception 'POSTCHECK_FAILED: RPC sin SECURITY DEFINER, search_path vacío o ACL esperada';
  end if;
  if (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'private' and p.proname in ('normalizar_texto_pregunta', 'import_lote_totales')
         and not has_function_privilege('anon', p.oid, 'EXECUTE')
         and not has_function_privilege('authenticated', p.oid, 'EXECUTE')) <> 2 then
    raise exception 'POSTCHECK_FAILED: ayudantes privados ausentes o con ACL abierta';
  end if;
end
$post$;
