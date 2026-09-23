-- M10 · Pregunta anulada por el tribunal EN UN EXAMEN CONCRETO.
--
-- DECISIÓN DE PRODUCTO: la anulación NO pertenece a la pregunta; pertenece a la RELACIÓN
-- pregunta ↔ examen. La misma pregunta (PREG-000123) puede estar anulada en el examen
-- oficial de 2025 y ser normal en un simulacro de 2026. Por eso `preguntas` NO se toca.
--
-- Qué cambia:
--   1. preguntas_examen         + anulada boolean NOT NULL default false + motivo_anulacion text.
--   2. preguntas_parseadas_staging + anulada / motivo_anulacion (lo que el revisor ve y corrige
--      antes de publicar; NO se mezcla con `estado_parseo`, que es el estado del PARSEO).
--   3. admin_link_question_to_exam() gana p_anulada / p_motivo_anulacion (firma NUEVA de 5 args;
--      la de 3 se elimina para que PostgREST no vea dos sobrecargas). NULL = no tocar una
--      relación existente / «normal» al crear.
--   4. admin_create_question() gana p_anulada / p_motivo_anulacion (firma NUEVA de 14 args;
--      la de 12 se elimina).
--   5. admin_import_lote_preguntas() escribe anulada/motivo en la relación al crear o vincular
--      (nunca en la pregunta global) y pide confirmación si cambia una relación de un examen
--      publicado. Sigue siendo UNA transacción.
--   6. record_answer(): una pregunta anulada en el examen del intento NO puntúa. No se guarda
--      respuesta (ni acierto, ni fallo, ni ranking, ni estadísticas de dificultad) y se
--      devuelve es_correcta NULL con la opción correcta para poder enseñarla.
--   7. tg_staging_inmutable_tras_importar() incluye las dos columnas nuevas.
--
-- CONTRATO DE PUNTUACIÓN (fuera de esta base: Ktor AppV1Routes): un intento se dimensiona con las
-- preguntas EVALUABLES (activas y no anuladas); la anulada se sirve visualmente en su posición
-- con anulada=true; finalizar ignora cualquier respuesta a una pregunta anulada.
--
-- `examenes.total_preguntas` NO cambia de significado: son las POSICIONES activas (visibles).
--
-- PREFLIGHT (2026-09-19, MAIN ndiooyyqtaeysnedywer): 3 preguntas, 2 vínculos, 0 lotes. Ningún
-- consumidor SQL lee las columnas nuevas; las funciones reemplazadas se guardan tal cual en
-- private.m10_previous_definitions_20260919 (rollback: supabase/canonical/annulled_questions_m10/).

-- ---------------------------------------------------------------------------
-- 0) Guardas: el estado de MAIN es el esperado (si no, aborta antes de tocar nada).
-- ---------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'preguntas_examen' and column_name = 'anulada') then
    raise exception 'M10_ABORT: preguntas_examen.anulada ya existe';
  end if;
  if to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer)') is null
     or to_regprocedure('public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid)') is null
     or to_regprocedure('public.admin_import_lote_preguntas(uuid, boolean, uuid)') is null
     or to_regprocedure('public.record_answer(uuid, uuid, uuid, uuid)') is null then
    raise exception 'M10_ABORT: faltan las firmas esperadas (¿M8 sin aplicar?)';
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- 1) Copia EXACTA de lo que se reemplaza (la restaura el rollback).
-- ---------------------------------------------------------------------------
create table if not exists private.m10_previous_definitions_20260919 (
  objeto text primary key,
  definicion text not null,
  respaldado_en timestamptz not null default now()
);
alter table private.m10_previous_definitions_20260919 enable row level security;
revoke all on private.m10_previous_definitions_20260919 from public, anon, authenticated;

insert into private.m10_previous_definitions_20260919 (objeto, definicion)
select f.oid::regprocedure::text, pg_get_functiondef(f.oid)
  from pg_proc f
 where f.oid in (
   'public.admin_link_question_to_exam(uuid, uuid, integer)'::regprocedure,
   'public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid)'::regprocedure,
   'public.admin_import_lote_preguntas(uuid, boolean, uuid)'::regprocedure,
   'public.record_answer(uuid, uuid, uuid, uuid)'::regprocedure,
   'private.tg_staging_inmutable_tras_importar()'::regprocedure)
on conflict (objeto) do update
  set definicion = excluded.definicion, respaldado_en = now();

-- ---------------------------------------------------------------------------
-- 2) Columnas y restricciones.
-- ---------------------------------------------------------------------------
alter table public.preguntas_examen
  add column anulada boolean not null default false,
  add column motivo_anulacion text;
alter table public.preguntas_examen
  add constraint preguntas_examen_anulada_motivo_chk
  check (motivo_anulacion is null
         or (anulada and btrim(motivo_anulacion) <> '' and length(motivo_anulacion) <= 500));
comment on column public.preguntas_examen.anulada is
  'La pregunta fue anulada por el tribunal EN ESTE examen. No puntúa: ni acierto ni fallo ni sin responder.';
comment on column public.preguntas_examen.motivo_anulacion is
  'Motivo opcional de la anulación. Sólo con anulada = true.';

alter table public.preguntas_parseadas_staging
  add column anulada boolean not null default false,
  add column motivo_anulacion text;
alter table public.preguntas_parseadas_staging
  add constraint preguntas_parseadas_staging_anulada_motivo_chk
  check (motivo_anulacion is null
         or (anulada and btrim(motivo_anulacion) <> '' and length(motivo_anulacion) <= 500));

-- ---------------------------------------------------------------------------
-- 3) Staging inmutable tras publicar: la anulación también es contenido publicado.
-- ---------------------------------------------------------------------------
create or replace function private.tg_staging_inmutable_tras_importar()
 returns trigger
 language plpgsql
 set search_path to ''
as $function$
declare
  v_publicada boolean;
begin
  v_publicada := old.pregunta_id is not null
    or exists (select 1 from public.lotes_importacion_preguntas l where l.id = old.lote_id and l.estado = 'importado');

  -- Contenido, decisión humana e identidad publicada: nada cambia tras publicar. pregunta_id y
  -- vincular_pregunta_id sí pueden pasar a NULL (ON DELETE SET NULL); error_parseo,
  -- codigo_pregunta_generado y los metadatos no cambian lo publicado.
  if v_publicada
     and (row(new.lote_id, new.posicion, new.bloque_original, new.titulo, new.opcion_a, new.opcion_b, new.opcion_c,
              new.opcion_d, new.respuesta_correcta, new.razonamiento, new.fuente_texto, new.etiqueta_texto,
              new.estado_parseo, new.anulada, new.motivo_anulacion)
          is distinct from
          row(old.lote_id, old.posicion, old.bloque_original, old.titulo, old.opcion_a, old.opcion_b, old.opcion_c,
              old.opcion_d, old.respuesta_correcta, old.razonamiento, old.fuente_texto, old.etiqueta_texto,
              old.estado_parseo, old.anulada, old.motivo_anulacion)
          or (new.pregunta_id is not null and new.pregunta_id is distinct from old.pregunta_id)
          or (new.vincular_pregunta_id is not null and new.vincular_pregunta_id is distinct from old.vincular_pregunta_id)) then
    raise exception 'STAGING_ROW_ALREADY_IMPORTED';
  end if;

  -- IM2-3: la pregunta elegida para vincular se ha BORRADO (la acción ON DELETE SET NULL llega
  -- como UPDATE anidado). Nunca se cae en silencio a «crear una nueva»: la fila vuelve a revisión.
  if not v_publicada and pg_trigger_depth() > 1
     and old.vincular_pregunta_id is not null and new.vincular_pregunta_id is null then
    new.estado_parseo := 'advertencia';
    new.error_parseo := 'La pregunta elegida para vincular ya no existe: vuelve a decidir.';
  end if;
  return new;
end;
$function$;

-- ---------------------------------------------------------------------------
-- 4) admin_link_question_to_exam: firma de 5 args.
-- ---------------------------------------------------------------------------
drop function if exists public.admin_link_question_to_exam(uuid, uuid, integer);

create function public.admin_link_question_to_exam(
  p_examen_id uuid,
  p_pregunta_id uuid,
  p_posicion integer default null,
  p_anulada boolean default null,
  p_motivo_anulacion text default null)
 returns table(posicion_asignada integer, numero_asignado integer, ya_vinculada boolean)
 language plpgsql
 security definer
 set search_path to ''
as $function$
declare
  v_tipo_pregunta text;
  v_activa boolean;
  v_tipo_examen text;
  v_n integer;
  v_pos integer;
  v_num integer;
  v_motivo text := nullif(btrim(coalesce(p_motivo_anulacion, '')), '');
begin
  if v_motivo is not null and p_anulada is not true then
    raise exception 'EXAM_QUESTION_ANNULMENT_REASON_REQUIRES_ANNULMENT';
  end if;
  if v_motivo is not null and length(v_motivo) > 500 then
    raise exception 'EXAM_QUESTION_ANNULMENT_REASON_TOO_LONG';
  end if;

  select p.tipo_pregunta, p.activa into v_tipo_pregunta, v_activa
    from public.preguntas p where p.id = p_pregunta_id for share;
  if not found then raise exception 'QUESTION_NOT_FOUND'; end if;

  select e.tipo_examen into v_tipo_examen
    from public.examenes e where e.id = p_examen_id for no key update;
  if not found then raise exception 'EXAM_NOT_FOUND'; end if;

  update public.preguntas_examen pe set posicion = r.rn
    from (select x.pregunta_id, (row_number() over (order by x.posicion, x.pregunta_id))::integer as rn
            from public.preguntas_examen x where x.examen_id = p_examen_id) r
   where pe.examen_id = p_examen_id and pe.pregunta_id = r.pregunta_id and pe.posicion <> r.rn;

  select pe.posicion, pe.numero_en_examen into v_pos, v_num
    from public.preguntas_examen pe
   where pe.examen_id = p_examen_id and pe.pregunta_id = p_pregunta_id;
  if found then
    -- Relación ya existente: sólo se toca la anulación, y sólo si el llamante la indicó.
    if p_anulada is not null then
      update public.preguntas_examen pe
         set anulada = p_anulada,
             motivo_anulacion = case when p_anulada then v_motivo else null end
       where pe.examen_id = p_examen_id and pe.pregunta_id = p_pregunta_id
         and (pe.anulada is distinct from p_anulada
              or pe.motivo_anulacion is distinct from (case when p_anulada then v_motivo else null end));
    end if;
    return query select v_pos, v_num, true;
    return;
  end if;

  if not v_activa then raise exception 'QUESTION_ARCHIVED'; end if;
  if not ((v_tipo_examen = 'rafaga' and v_tipo_pregunta = 'rafaga')
       or (v_tipo_examen = 'razonado' and v_tipo_pregunta = 'razonada')
       or (v_tipo_examen = 'inmersivo' and v_tipo_pregunta = 'inmersiva')) then
    raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
  end if;

  select count(*)::integer into v_n from public.preguntas_examen pe where pe.examen_id = p_examen_id;
  if v_n >= 200 then raise exception 'EXAM_QUESTION_LIMIT_REACHED'; end if;

  if p_posicion is not null and p_posicion < 1 then raise exception 'EXAM_QUESTION_POSITION_INVALID'; end if;
  v_pos := case when p_posicion is null or p_posicion > v_n then v_n + 1 else p_posicion end;

  select min(g)::integer into v_num
    from generate_series(1, 200) as g
   where not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = p_examen_id and pe.numero_en_examen = g);
  if v_num is null then raise exception 'EXAM_QUESTION_LIMIT_REACHED'; end if;

  if v_pos <= v_n then
    update public.preguntas_examen pe set posicion = pe.posicion + 1
     where pe.examen_id = p_examen_id and pe.posicion >= v_pos;
  end if;

  insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion)
  values (p_examen_id, p_pregunta_id, v_pos, v_num, coalesce(p_anulada, false),
          case when p_anulada is true then v_motivo else null end);
  return query select v_pos, v_num, false;
end;
$function$;

revoke all on function public.admin_link_question_to_exam(uuid, uuid, integer, boolean, text) from public, anon, authenticated;
grant execute on function public.admin_link_question_to_exam(uuid, uuid, integer, boolean, text) to service_role;

-- ---------------------------------------------------------------------------
-- 5) admin_create_question: firma de 14 args.
-- ---------------------------------------------------------------------------
drop function if exists public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid);

create function public.admin_create_question(
  p_pregunta_id uuid,
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
  p_creado_por uuid default null,
  p_anulada boolean default null,
  p_motivo_anulacion text default null)
 returns jsonb
 language plpgsql
 security definer
 set search_path to ''
as $function$
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
begin
  if p_pregunta_id is null then raise exception 'QUESTION_ID_REQUIRED'; end if;
  if v_titulo = '' then raise exception 'QUESTION_STATEMENT_REQUIRED'; end if;
  if p_examen_id is null and (p_anulada is not null or nullif(btrim(coalesce(p_motivo_anulacion, '')), '') is not null) then
    -- La anulación es de la relación con un examen: sin examen no hay dónde guardarla.
    raise exception 'QUESTION_ANNULMENT_REQUIRES_EXAM';
  end if;
  v_tipo := private.normalizar_tipo_pregunta(p_tipo_pregunta);
  v_dificultad := private.normalizar_dificultad_pregunta(p_dificultad);
  perform 1 from private.validar_opciones_pregunta(p_opciones);

  if p_examen_id is not null and to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer, boolean, text)') is null then
    raise exception 'QUESTION_EXAM_LINK_UNAVAILABLE';
  end if;
  if p_examen_id is not null then
    select e.tipo_examen into v_tipo_examen from public.examenes e where e.id = p_examen_id;
    if not found then raise exception 'EXAM_NOT_FOUND'; end if;
    if not coalesce(p_activa, true) then raise exception 'QUESTION_ARCHIVED'; end if;
    if not ((v_tipo_examen = 'rafaga' and v_tipo = 'rafaga')
         or (v_tipo_examen = 'razonado' and v_tipo = 'razonada')
         or (v_tipo_examen = 'inmersivo' and v_tipo = 'inmersiva')) then
      raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
    end if;
  end if;

  insert into public.preguntas as p (id, tipo_pregunta, titulo, fuente_texto, dificultad, activa, lote_importacion_id, creado_por)
  values (p_pregunta_id, v_tipo, v_titulo, nullif(btrim(p_fuente_texto), ''), v_dificultad,
          coalesce(p_activa, true), p_lote_importacion_id, p_creado_por)
  on conflict (id) do nothing
  returning p.id, p.codigo_pregunta into v_id, v_codigo;

  if v_id is null then
    select p.id, p.codigo_pregunta, p.tipo_pregunta, p.titulo, p.fuente_texto, p.dificultad, p.activa
      into v_existente from public.preguntas p where p.id = p_pregunta_id;
    if v_existente.id is null
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

  if p_examen_id is not null then
    select l.posicion_asignada into v_posicion
      from public.admin_link_question_to_exam(p_examen_id, v_id, p_posicion, p_anulada, p_motivo_anulacion) as l;
  end if;

  return jsonb_build_object(
    'pregunta_id', v_id,
    'codigo_pregunta', v_codigo,
    'creada', v_creada,
    'posicion_asignada', v_posicion
  );
end;
$function$;

revoke all on function public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid, boolean, text) from public, anon, authenticated;
grant execute on function public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid, boolean, text) to service_role;

-- ---------------------------------------------------------------------------
-- 6) admin_import_lote_preguntas: escribe la anulación en la RELACIÓN. Una sola transacción.
-- ---------------------------------------------------------------------------
create or replace function public.admin_import_lote_preguntas(
  p_lote_id uuid,
  p_confirmar_examen_publicado boolean default false,
  p_actor_id uuid default null)
 returns jsonb
 language plpgsql
 security definer
 set search_path to ''
as $function$
declare
  v_lote record;
  v_examen record;
  v_tipo_pregunta text;
  v_posiciones text;
  v_a_publicar integer;
  v_relaciones_cambiadas integer;
  v_fila record;
  v_creada jsonb;
  v_nuevas integer := 0;
  v_vinculadas integer := 0;
  v_ya_vinculada boolean;
begin
  if p_lote_id is null then
    raise exception 'IMPORT_LOTE_ID_REQUIRED';
  end if;

  perform 1
    from public.examenes e
   where e.id = (select l.examen_id from public.lotes_importacion_preguntas l where l.id = p_lote_id)
     for key share;

  select l.id, l.examen_id, l.tipo_examen, l.estado
    into v_lote
    from public.lotes_importacion_preguntas l
   where l.id = p_lote_id
     for update;
  if not found then
    raise exception 'IMPORT_LOTE_NOT_FOUND';
  end if;

  if v_lote.estado = 'importado' then
    return private.import_lote_totales(p_lote_id) || jsonb_build_object('ejecutado', false, 'nuevas', 0, 'vinculadas', 0);
  end if;
  if v_lote.estado not in ('parseado', 'revisado', 'fallido') then
    raise exception 'IMPORT_LOTE_STATE_INVALID';
  end if;

  perform 1
    from public.preguntas_parseadas_staging s
   where s.lote_id = p_lote_id
   order by s.posicion
     for update;

  select e.id, e.tipo_examen
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

  perform pg_advisory_xact_lock(hashtext('oposicontrol.import_lote'), hashtext(v_tipo_pregunta));

  if exists (select 1 from public.preguntas_parseadas_staging s
              where s.lote_id = p_lote_id and s.pregunta_id is null
                and s.estado_parseo not in ('ok', 'aprobada', 'omitida')) then
    raise exception 'IMPORT_PENDING_REVIEW';
  end if;

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
    raise exception 'IMPORT_ROW_INCOMPLETE'
      using detail = case when length(v_posiciones) <= 200 then v_posiciones
                          else regexp_replace(left(v_posiciones, 200), ',[0-9]*$', '') end;
  end if;

  if exists (
       select 1
         from public.admin_import_lote_duplicados(p_lote_id) d
         join public.preguntas_parseadas_staging s on s.id = d.staging_id
        where d.origen = 'banco'
          and s.estado_parseo in ('ok', 'aprobada')
          and s.vincular_pregunta_id is null)
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

  perform 1
    from public.preguntas p
   where p.id in (select s.vincular_pregunta_id
                    from public.preguntas_parseadas_staging s
                   where s.lote_id = p_lote_id and s.pregunta_id is null
                     and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is not null)
   order by p.id
     for share;

  if exists (
    select 1
      from public.preguntas_parseadas_staging s
      left join public.preguntas p on p.id = s.vincular_pregunta_id
     where s.lote_id = p_lote_id and s.pregunta_id is null
       and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is not null
       and (p.id is null or not p.activa
         or p.tipo_pregunta <> v_tipo_pregunta
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

  perform 1 from public.examenes e where e.id = v_examen.id for no key update;

  -- Relaciones NUEVAS: las que ocupan una posición (cuentan para el tope de 200).
  select count(*)::integer
    into v_a_publicar
    from public.preguntas_parseadas_staging s
   where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
     and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id);
  if (select count(*) from public.preguntas_examen pe where pe.examen_id = v_examen.id) + v_a_publicar > 200 then
    raise exception 'EXAM_QUESTION_LIMIT_REACHED';
  end if;

  -- Relaciones EXISTENTES cuya anulación cambia: alteran la puntuación de un examen ya en marcha.
  select count(*)::integer
    into v_relaciones_cambiadas
    from public.preguntas_parseadas_staging s
    join public.preguntas_examen pe
      on pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id
   where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
     and (pe.anulada is distinct from s.anulada
          or pe.motivo_anulacion is distinct from (case when s.anulada then nullif(btrim(s.motivo_anulacion), '') else null end));

  if v_a_publicar + v_relaciones_cambiadas > 0
     and (select e.publicado_en is not null and e.activo from public.examenes e where e.id = v_examen.id)
     and not coalesce(p_confirmar_examen_publicado, false) then
    raise exception 'IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED';
  end if;

  for v_fila in
    select s.id, s.titulo, s.opcion_a, s.opcion_b, s.opcion_c, s.opcion_d, s.respuesta_correcta,
           s.razonamiento, s.fuente_texto, s.vincular_pregunta_id, s.anulada, s.motivo_anulacion
      from public.preguntas_parseadas_staging s
     where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
     order by s.posicion
  loop
    if v_fila.vincular_pregunta_id is not null then
      -- Vincular NO toca la pregunta global: sólo crea/actualiza la relación con ESTE examen.
      select l.ya_vinculada into v_ya_vinculada
        from public.admin_link_question_to_exam(v_examen.id, v_fila.vincular_pregunta_id, null,
                                                v_fila.anulada, v_fila.motivo_anulacion) as l;
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
        v_tipo_pregunta,
        v_fila.titulo,
        jsonb_build_array(
          jsonb_build_object('letra', 'A', 'texto', v_fila.opcion_a, 'es_correcta', v_fila.respuesta_correcta = 'A'),
          jsonb_build_object('letra', 'B', 'texto', v_fila.opcion_b, 'es_correcta', v_fila.respuesta_correcta = 'B'),
          jsonb_build_object('letra', 'C', 'texto', v_fila.opcion_c, 'es_correcta', v_fila.respuesta_correcta = 'C'),
          jsonb_build_object('letra', 'D', 'texto', v_fila.opcion_d, 'es_correcta', v_fila.respuesta_correcta = 'D')),
        case when v_tipo_pregunta = 'rafaga' then null else v_fila.razonamiento end,
        case when v_tipo_pregunta = 'rafaga' then null else v_fila.fuente_texto end,
        null,
        true,
        v_examen.id,
        null,
        p_lote_id,
        p_actor_id,
        v_fila.anulada,
        v_fila.motivo_anulacion);
      update public.preguntas_parseadas_staging s
         set pregunta_id = (v_creada ->> 'pregunta_id')::uuid,
             codigo_pregunta_generado = v_creada ->> 'codigo_pregunta',
             estado_parseo = 'importada'
       where s.id = v_fila.id;
      v_nuevas := v_nuevas + 1;
    end if;
  end loop;

  update public.lotes_importacion_preguntas l
     set estado = 'importado',
         total_importadas = (select count(*) from public.preguntas_parseadas_staging s
                              where s.lote_id = l.id and s.pregunta_id is not null),
         mensaje_error = null
   where l.id = p_lote_id;

  return private.import_lote_totales(p_lote_id)
    || jsonb_build_object(
         'ejecutado', true,
         'nuevas', v_nuevas,
         'vinculadas', v_vinculadas,
         'anuladas', (select count(*) from public.preguntas_parseadas_staging s
                       where s.lote_id = p_lote_id and s.pregunta_id is not null and s.anulada));
end;
$function$;

-- ---------------------------------------------------------------------------
-- 7) record_answer: una pregunta anulada en el examen del intento NO puntúa.
-- ---------------------------------------------------------------------------
create or replace function public.record_answer(
  p_usuario_id uuid,
  p_intento_id uuid,
  p_pregunta_id uuid,
  p_opcion_seleccionada_id uuid)
 returns table(respuesta_id uuid, es_correcta boolean, opcion_correcta_id uuid,
               es_primer_intento_pregunta boolean, cuenta_para_ranking boolean)
 language plpgsql
 security definer
 set search_path to ''
as $function$
declare
  v_examen_id uuid;
  v_cuenta_para_ranking boolean;
  v_correcta_id uuid;
  v_anulada boolean;
  v_first boolean;
  v_constraint_name text;
  v_existing public.respuestas_intento%rowtype;
  v_response_id uuid;
  v_response_correct boolean;
  v_response_correcta_id uuid;
  v_response_first boolean;
  v_response_ranking boolean;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.examen_id, ie.cuenta_para_ranking into v_examen_id, v_cuenta_para_ranking
    from public.intentos_examen ie
   where ie.id = p_intento_id and ie.usuario_id = p_usuario_id and ie.estado = 'en_progreso'
     for update;
  if not found then raise exception 'RECORD_ANSWER_INVALID_ATTEMPT'; end if;

  select pe.anulada into v_anulada
    from public.preguntas_examen pe
   where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id;
  if not found then raise exception 'RECORD_ANSWER_QUESTION_NOT_IN_ATTEMPT'; end if;

  if not exists (select 1 from public.opciones_pregunta op
                  where op.id = p_opcion_seleccionada_id and op.pregunta_id = p_pregunta_id) then
    raise exception 'RECORD_ANSWER_OPTION_NOT_IN_QUESTION';
  end if;

  select sp.opcion_correcta_id into v_correcta_id
    from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
  if v_correcta_id is null then raise exception 'RECORD_ANSWER_MISSING_SOLUTION'; end if;

  -- Anulada por el tribunal en ESTE examen: no se guarda respuesta, luego no hay acierto, fallo,
  -- ranking ni estadística de dificultad. Se devuelve la opción correcta (es_correcta = NULL).
  if v_anulada then
    return query select null::uuid, null::boolean, v_correcta_id, false, false;
    return;
  end if;

  select ri.* into v_existing from public.respuestas_intento ri
   where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id limit 1;
  if found then
    return query select v_existing.id, v_existing.es_correcta, v_existing.opcion_correcta_id,
                        v_existing.es_primer_intento_pregunta, v_existing.cuenta_para_ranking;
    return;
  end if;

  select not exists (select 1 from public.respuestas_intento previous
                      where previous.usuario_id = p_usuario_id and previous.pregunta_id = p_pregunta_id)
    into v_first;

  begin
    insert into public.respuestas_intento as response_row (
      intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id,
      opcion_correcta_id, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta
    ) values (
      p_intento_id, p_pregunta_id, p_opcion_seleccionada_id, p_opcion_seleccionada_id = v_correcta_id,
      p_usuario_id, v_examen_id, v_correcta_id, now(), v_cuenta_para_ranking and v_first, v_first
    )
    returning response_row.id, response_row.es_correcta, response_row.opcion_correcta_id,
              response_row.es_primer_intento_pregunta, response_row.cuenta_para_ranking
      into v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
    return query select v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
  exception when unique_violation then
    get stacked diagnostics v_constraint_name = constraint_name;
    if v_constraint_name is distinct from 'ux_respuestas_first_response' or v_first is not true then
      raise;
    end if;
    insert into public.respuestas_intento as response_row (
      intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id,
      opcion_correcta_id, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta
    ) values (
      p_intento_id, p_pregunta_id, p_opcion_seleccionada_id, p_opcion_seleccionada_id = v_correcta_id,
      p_usuario_id, v_examen_id, v_correcta_id, now(), false, false
    )
    returning response_row.id, response_row.es_correcta, response_row.opcion_correcta_id,
              response_row.es_primer_intento_pregunta, response_row.cuenta_para_ranking
      into v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
    return query select v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
  end;
end;
$function$;
