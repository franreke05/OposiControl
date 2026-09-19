-- M8 · Las preguntas no pertenecen a una especialidad.
--
-- DECISIÓN DE PRODUCTO: la especialidad es un atributo del EXAMEN, no de la PREGUNTA.
-- Una pregunta puede no estar en ningún examen y una misma pregunta puede estar en
-- exámenes de especialidades distintas (la relación sigue siendo M:N).
--
-- La compatibilidad pregunta ↔ examen depende SÓLO del tipo:
--   pregunta rafaga    ↔ examen rafaga
--   pregunta razonada  ↔ examen razonado
--   pregunta inmersiva ↔ examen inmersivo
--
-- Qué cambia:
--   1. trg_preguntas_bloquear_cambio_tipo deja de vigilar `especialidad_id`.
--   2. validar_pregunta_examen() (trigger de preguntas_examen) y
--      admin_link_question_to_exam() (M4) ya no comparan especialidades: sólo tipo.
--      Se conservan el cerrojo pregunta → examen, el tope de 200 y «archivada».
--   3. admin_create_question() pierde `p_especialidad_id`. Firma NUEVA (12 args); la
--      antigua (13) se elimina para que PostgREST no vea dos sobrecargas.
--   4. admin_import_lote_duplicados() y admin_import_lote_preguntas() (IM3): el banco es
--      compartido, así que el duplicado se busca por TIPO en todo el banco, y el cerrojo
--      de serialización pasa de «especialidad:tipo» a «tipo».
--   5. Se retira `preguntas.especialidad_id` (FK preguntas_especialidad_id_fkey e índice
--      idx_preguntas_especialidad incluidos). Antes se guarda el mapeo en
--      private.preguntas_especialidad_backup_20260919 (rollback: supabase/canonical/questions_without_specialty_m8/ROLLBACK.sql).
--
-- PREFLIGHT (2026-09-19, MAIN ndiooyyqtaeysnedywer): dependientes de la columna según
-- pg_depend = 1 trigger, 1 FK, 1 índice. Ninguna vista, matview ni policy la usa. Sólo
-- 6 funciones mencionan especialidad + pregunta: las cuatro de arriba, record_event
-- (su p_especialidad_id es de analítica, no de la pregunta) y admin_import_lote_*.
-- El consumidor (AppV1Routes / SupabaseV1Gateway) lee preguntas por id y por examen, y
-- nunca `especialidad_id` de la pregunta. Sin datos inventados: no se rellena nada.

-- ---------------------------------------------------------------------------
-- 0) Copia de seguridad del mapeo pregunta → especialidad (3 filas en el preflight).
--    Esquema `private`: PostgREST no lo expone. Es lo que restaura el rollback.
-- ---------------------------------------------------------------------------
create table if not exists private.preguntas_especialidad_backup_20260919 (
  pregunta_id uuid primary key,
  especialidad_id uuid not null,
  respaldado_en timestamptz not null default now()
);
alter table private.preguntas_especialidad_backup_20260919 enable row level security;
revoke all on private.preguntas_especialidad_backup_20260919 from public, anon, authenticated;

do $$
begin
  if exists (
    select 1 from information_schema.columns
     where table_schema = 'public' and table_name = 'preguntas' and column_name = 'especialidad_id'
  ) then
    insert into private.preguntas_especialidad_backup_20260919 (pregunta_id, especialidad_id)
    select p.id, p.especialidad_id from public.preguntas p
    on conflict (pregunta_id) do nothing;
  end if;
end;
$$;

-- Definiciones EXACTAS de lo que este script reemplaza, tal como están en MAIN antes de
-- aplicarlo (no las de los ficheros de migraciones anteriores: MAIN pudo divergir). El
-- rollback las ejecuta tal cual.
create table if not exists private.m8_previous_definitions_20260919 (
  objeto text primary key,
  definicion text not null,
  respaldado_en timestamptz not null default now()
);
alter table private.m8_previous_definitions_20260919 enable row level security;
revoke all on private.m8_previous_definitions_20260919 from public, anon, authenticated;

insert into private.m8_previous_definitions_20260919 (objeto, definicion)
select f.oid::regprocedure::text, pg_get_functiondef(f.oid)
  from pg_proc f
 where f.oid in (
   to_regprocedure('public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid)'),
   to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer)'),
   to_regprocedure('public.validar_pregunta_examen()'),
   to_regprocedure('public.admin_import_lote_duplicados(uuid)'),
   to_regprocedure('public.admin_import_lote_preguntas(uuid, boolean, uuid)')
 )
on conflict (objeto) do nothing;

insert into private.m8_previous_definitions_20260919 (objeto, definicion)
select 'trigger trg_preguntas_bloquear_cambio_tipo', pg_get_triggerdef(t.oid)
  from pg_trigger t
 where t.tgrelid = 'public.preguntas'::regclass and t.tgname = 'trg_preguntas_bloquear_cambio_tipo'
on conflict (objeto) do nothing;

-- ---------------------------------------------------------------------------
-- 1) Trigger de tipo bloqueado: sólo `tipo_pregunta`.
-- ---------------------------------------------------------------------------
drop trigger if exists trg_preguntas_bloquear_cambio_tipo on public.preguntas;
create trigger trg_preguntas_bloquear_cambio_tipo
  before update of tipo_pregunta on public.preguntas
  for each row
  when (old.tipo_pregunta is distinct from new.tipo_pregunta)
  execute function private.tg_preguntas_bloquear_cambio_tipo();

-- ---------------------------------------------------------------------------
-- 2a) Trigger de preguntas_examen: sólo tipo.
-- ---------------------------------------------------------------------------
create or replace function public.validar_pregunta_examen()
 returns trigger
 language plpgsql
 set search_path to ''
as $function$
declare
  v_tipo_examen text;
  v_tipo_pregunta text;
begin
  -- Cerrojos en orden pregunta -> examen (mismo orden que el recuento de total_preguntas
  -- y que admin_link_question_to_exam). NO KEY UPDATE sobre el examen es el cerrojo que
  -- después pide el recuento: no hay subida de cerrojo, y sigue siendo compatible con el
  -- FOR KEY SHARE de las FK de consumidores.
  select p.tipo_pregunta
    into v_tipo_pregunta
    from public.preguntas p
   where p.id = new.pregunta_id
     for share;

  select e.tipo_examen
    into v_tipo_examen
    from public.examenes e
   where e.id = new.examen_id
     for no key update;

  -- La especialidad es del examen, no de la pregunta: sólo importa el tipo.
  if not (
       (v_tipo_examen = 'rafaga' and v_tipo_pregunta = 'rafaga')
    or (v_tipo_examen = 'razonado' and v_tipo_pregunta = 'razonada')
    or (v_tipo_examen = 'inmersivo' and v_tipo_pregunta = 'inmersiva')
  ) then
    raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
  end if;

  return new;
end;
$function$;

-- ---------------------------------------------------------------------------
-- 2b) Vínculo (M4): sólo tipo. Sin condiciones de especialidad, año ni estado del examen.
-- ---------------------------------------------------------------------------
create or replace function public.admin_link_question_to_exam(p_examen_id uuid, p_pregunta_id uuid, p_posicion integer default null::integer)
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
begin
  -- Cerrojos pregunta (SHARE) -> examen (NO KEY UPDATE): mismo orden que la validación y el recuento.
  select p.tipo_pregunta, p.activa
    into v_tipo_pregunta, v_activa
    from public.preguntas p
   where p.id = p_pregunta_id
     for share;
  if not found then
    raise exception 'QUESTION_NOT_FOUND';
  end if;

  select e.tipo_examen
    into v_tipo_examen
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
$function$;

-- ---------------------------------------------------------------------------
-- 3) Alta de pregunta: sin especialidad. La firma de 13 argumentos se retira.
-- ---------------------------------------------------------------------------
drop function if exists public.admin_create_question(uuid, uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid);

create or replace function public.admin_create_question(p_pregunta_id uuid, p_tipo_pregunta text, p_titulo text, p_opciones jsonb, p_razonamiento text default null::text, p_fuente_texto text default null::text, p_dificultad text default null::text, p_activa boolean default true, p_examen_id uuid default null::uuid, p_posicion integer default null::integer, p_lote_importacion_id uuid default null::uuid, p_creado_por uuid default null::uuid)
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
  -- 1) Todo se valida ANTES de escribir: ningún error de validación consume número de PREG-n.
  if p_pregunta_id is null then
    raise exception 'QUESTION_ID_REQUIRED';
  end if;
  if v_titulo = '' then
    raise exception 'QUESTION_STATEMENT_REQUIRED';
  end if;
  v_tipo := private.normalizar_tipo_pregunta(p_tipo_pregunta);
  v_dificultad := private.normalizar_dificultad_pregunta(p_dificultad);
  perform 1 from private.validar_opciones_pregunta(p_opciones);
  if p_examen_id is not null
     and to_regprocedure('public.admin_link_question_to_exam(uuid, uuid, integer)') is null then
    raise exception 'QUESTION_EXAM_LINK_UNAVAILABLE';
  end if;
  -- Revisión C (Q2-3): el vínculo se valida ANTES de insertar (mismos códigos y orden que M4),
  -- para fallar pronto y sin consumir número PREG-n. M4 vuelve a validar bajo cerrojo.
  -- Sólo tipo: la especialidad es del examen y no interviene.
  if p_examen_id is not null then
    select e.tipo_examen
      into v_tipo_examen
      from public.examenes e
     where e.id = p_examen_id;
    if not found then
      raise exception 'EXAM_NOT_FOUND';
    end if;
    if not coalesce(p_activa, true) then
      raise exception 'QUESTION_ARCHIVED';
    end if;
    if not ((v_tipo_examen = 'rafaga' and v_tipo = 'rafaga')
         or (v_tipo_examen = 'razonado' and v_tipo = 'razonada')
         or (v_tipo_examen = 'inmersivo' and v_tipo = 'inmersiva')) then
      raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
    end if;
  end if;

  -- 2) Idempotencia (Q-4): el id lo genera el cliente una vez por alta. codigo_pregunta lo pone Q1.
  insert into public.preguntas as p
    (id, tipo_pregunta, titulo, fuente_texto, dificultad, activa, lote_importacion_id, creado_por)
  values
    (p_pregunta_id, v_tipo, v_titulo, nullif(btrim(p_fuente_texto), ''), v_dificultad,
     coalesce(p_activa, true), p_lote_importacion_id, p_creado_por)
  on conflict (id) do nothing
  returning p.id, p.codigo_pregunta into v_id, v_codigo;

  if v_id is null then
    -- Revisión C (Q2-1): un reintento sólo es idempotente si TODO el contenido coincide.
    -- Mismo id con otro contenido = conflicto explícito, nunca «éxito» sin aplicar nada.
    select p.id, p.codigo_pregunta, p.tipo_pregunta, p.titulo, p.fuente_texto, p.dificultad, p.activa
      into v_existente
      from public.preguntas p
     where p.id = p_pregunta_id;
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
$function$;

revoke all on function public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_create_question(uuid, text, text, jsonb, text, text, text, boolean, uuid, integer, uuid, uuid) to service_role;

-- ---------------------------------------------------------------------------
-- 4a) Duplicados del lote: por TIPO en todo el banco (el banco es compartido).
-- ---------------------------------------------------------------------------
create or replace function public.admin_import_lote_duplicados(p_lote_id uuid)
 returns table(staging_id uuid, origen text, pregunta_id uuid, codigo_pregunta text, staging_original_id uuid)
 language sql
 stable security definer
 set search_path to ''
as $function$
  with lote as (
    select case e.tipo_examen when 'rafaga' then 'rafaga' when 'razonado' then 'razonada' when 'inmersivo' then 'inmersiva' end as tipo
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
        on p.tipo_pregunta = lote.tipo
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
$function$;

-- ---------------------------------------------------------------------------
-- 4b) Publicación del lote: la pregunta se crea SIN especialidad y se vincula al examen.
-- ---------------------------------------------------------------------------
create or replace function public.admin_import_lote_preguntas(p_lote_id uuid, p_confirmar_examen_publicado boolean default false, p_actor_id uuid default null::uuid)
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

  -- 4b) Revisión C (IM3-2): serializa las publicaciones del mismo TIPO mientras se comprueban
  --     duplicados y se crean preguntas (DEC-IMP-01 «nunca copia»: dos lotes con el mismo
  --     contenido en exámenes distintos no pueden pasar a la vez la comprobación del paso 7).
  --     M8: el banco es compartido y el duplicado se busca por tipo en todo él, así que el
  --     cerrojo es por tipo y ya no por «especialidad:tipo».
  --     Orden estable: examen KEY SHARE (0) → lote (1) → filas (3) → este cerrojo → preguntas (9) → examen NKU (9).
  perform pg_advisory_xact_lock(hashtext('oposicontrol.import_lote'), hashtext(v_tipo_pregunta));

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
  --     siendo exactamente el duplicado revisado: mismo tipo y misma huella
  --     normalizada (título + A-D). La respuesta correcta no forma parte de la
  --     huella por DEC-IMP-01.
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
$function$;

-- ---------------------------------------------------------------------------
-- 5) Retirada de la columna. Trigger, funciones y RPCs ya no la mencionan.
--    DROP COLUMN arrastra la FK y el índice, pero se nombran para que quede escrito.
-- ---------------------------------------------------------------------------
drop index if exists public.idx_preguntas_especialidad;
alter table public.preguntas drop constraint if exists preguntas_especialidad_id_fkey;
alter table public.preguntas drop column if exists especialidad_id;
