-- V1 NIGHT-A: reserve / annulled / duplicate-lot support in the bulk question import (FA37). Contract: E1-V1-CONTROL-SPEC-IMPORT.md.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20260919122139 (annulled import) and 20261022101300 (official rules: es_reserva, orden_reserva, official_rules_problem).
-- Adds: lotes_importacion_preguntas.codigo_examen (file header CODIGO_EXAMEN) and .huella (content fingerprint, unique among imported lots of one exam);
-- preguntas_parseadas_staging.bloque PRINCIPAL | RESERVA; private.import_lote_huella(); admin_import_lote_huella(); admin_import_lote_preguntas() extended (same signature).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_import_lote_huella(uuid);
-- create or replace function public.admin_import_lote_preguntas(
--   p_lote_id uuid,
--   p_confirmar_examen_publicado boolean default false,
--   p_actor_id uuid default null)
--  returns jsonb
--  language plpgsql
--  security definer
--  set search_path to ''
-- as $function$
-- declare
--   v_lote record;
--   v_examen record;
--   v_tipo_pregunta text;
--   v_posiciones text;
--   v_a_publicar integer;
--   v_relaciones_cambiadas integer;
--   v_fila record;
--   v_creada jsonb;
--   v_nuevas integer := 0;
--   v_vinculadas integer := 0;
--   v_ya_vinculada boolean;
-- begin
--   if p_lote_id is null then
--     raise exception 'IMPORT_LOTE_ID_REQUIRED';
--   end if;
-- 
--   perform 1
--     from public.examenes e
--    where e.id = (select l.examen_id from public.lotes_importacion_preguntas l where l.id = p_lote_id)
--      for key share;
-- 
--   select l.id, l.examen_id, l.tipo_examen, l.estado
--     into v_lote
--     from public.lotes_importacion_preguntas l
--    where l.id = p_lote_id
--      for update;
--   if not found then
--     raise exception 'IMPORT_LOTE_NOT_FOUND';
--   end if;
-- 
--   if v_lote.estado = 'importado' then
--     return private.import_lote_totales(p_lote_id) || jsonb_build_object('ejecutado', false, 'nuevas', 0, 'vinculadas', 0);
--   end if;
--   if v_lote.estado not in ('parseado', 'revisado', 'fallido') then
--     raise exception 'IMPORT_LOTE_STATE_INVALID';
--   end if;
-- 
--   perform 1
--     from public.preguntas_parseadas_staging s
--    where s.lote_id = p_lote_id
--    order by s.posicion
--      for update;
-- 
--   select e.id, e.tipo_examen
--     into v_examen
--     from public.examenes e
--    where e.id = v_lote.examen_id;
--   if not found then
--     raise exception 'IMPORT_EXAM_NOT_FOUND';
--   end if;
--   if v_lote.tipo_examen is distinct from v_examen.tipo_examen then
--     raise exception 'IMPORT_TYPE_MISMATCH';
--   end if;
--   v_tipo_pregunta := case v_examen.tipo_examen
--                        when 'rafaga' then 'rafaga' when 'razonado' then 'razonada' when 'inmersivo' then 'inmersiva' end;
--   if v_tipo_pregunta is null then
--     raise exception 'IMPORT_EXAM_TYPE_UNKNOWN';
--   end if;
-- 
--   perform pg_advisory_xact_lock(hashtext('oposicontrol.import_lote'), hashtext(v_tipo_pregunta));
-- 
--   if exists (select 1 from public.preguntas_parseadas_staging s
--               where s.lote_id = p_lote_id and s.pregunta_id is null
--                 and s.estado_parseo not in ('ok', 'aprobada', 'omitida')) then
--     raise exception 'IMPORT_PENDING_REVIEW';
--   end if;
-- 
--   select string_agg(s.posicion::text, ',' order by s.posicion)
--     into v_posiciones
--     from public.preguntas_parseadas_staging s
--    where s.lote_id = p_lote_id and s.pregunta_id is null
--      and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is null
--      and (private.normalizar_texto_pregunta(s.titulo) = ''
--           or private.normalizar_texto_pregunta(s.opcion_a) = ''
--           or private.normalizar_texto_pregunta(s.opcion_b) = ''
--           or private.normalizar_texto_pregunta(s.opcion_c) = ''
--           or private.normalizar_texto_pregunta(s.opcion_d) = ''
--           or s.respuesta_correcta is null
--           or (v_tipo_pregunta in ('razonada', 'inmersiva')
--               and private.normalizar_texto_pregunta(s.razonamiento) = ''));
--   if v_posiciones is not null then
--     raise exception 'IMPORT_ROW_INCOMPLETE'
--       using detail = case when length(v_posiciones) <= 200 then v_posiciones
--                           else regexp_replace(left(v_posiciones, 200), ',[0-9]*$', '') end;
--   end if;
-- 
--   if exists (
--        select 1
--          from public.admin_import_lote_duplicados(p_lote_id) d
--          join public.preguntas_parseadas_staging s on s.id = d.staging_id
--         where d.origen = 'banco'
--           and s.estado_parseo in ('ok', 'aprobada')
--           and s.vincular_pregunta_id is null)
--      or exists (
--        select 1
--          from public.preguntas_parseadas_staging a
--          join public.preguntas_parseadas_staging b
--            on b.lote_id = a.lote_id and b.posicion > a.posicion
--         where a.lote_id = p_lote_id
--           and a.pregunta_id is null and b.pregunta_id is null
--           and a.estado_parseo in ('ok', 'aprobada') and b.estado_parseo in ('ok', 'aprobada')
--           and private.normalizar_texto_pregunta(a.titulo) = private.normalizar_texto_pregunta(b.titulo)
--           and private.normalizar_texto_pregunta(a.opcion_a) = private.normalizar_texto_pregunta(b.opcion_a)
--           and private.normalizar_texto_pregunta(a.opcion_b) = private.normalizar_texto_pregunta(b.opcion_b)
--           and private.normalizar_texto_pregunta(a.opcion_c) = private.normalizar_texto_pregunta(b.opcion_c)
--           and private.normalizar_texto_pregunta(a.opcion_d) = private.normalizar_texto_pregunta(b.opcion_d))
--   then
--     raise exception 'IMPORT_DUPLICATES_UNRESOLVED';
--   end if;
-- 
--   perform 1
--     from public.preguntas p
--    where p.id in (select s.vincular_pregunta_id
--                     from public.preguntas_parseadas_staging s
--                    where s.lote_id = p_lote_id and s.pregunta_id is null
--                      and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is not null)
--    order by p.id
--      for share;
-- 
--   if exists (
--     select 1
--       from public.preguntas_parseadas_staging s
--       left join public.preguntas p on p.id = s.vincular_pregunta_id
--      where s.lote_id = p_lote_id and s.pregunta_id is null
--        and s.estado_parseo in ('ok', 'aprobada') and s.vincular_pregunta_id is not null
--        and (p.id is null or not p.activa
--          or p.tipo_pregunta <> v_tipo_pregunta
--          or private.normalizar_texto_pregunta(p.titulo)
--               is distinct from private.normalizar_texto_pregunta(s.titulo)
--          or (select concat_ws(chr(31),
--                private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'A')),
--                private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'B')),
--                private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'C')),
--                private.normalizar_texto_pregunta(max(o.texto_opcion) filter (where o.letra = 'D')))
--                from public.opciones_pregunta o where o.pregunta_id = p.id)
--               is distinct from concat_ws(chr(31),
--                private.normalizar_texto_pregunta(s.opcion_a),
--                private.normalizar_texto_pregunta(s.opcion_b),
--                private.normalizar_texto_pregunta(s.opcion_c),
--                private.normalizar_texto_pregunta(s.opcion_d)))) then
--     raise exception 'IMPORT_LINK_TARGET_INVALID';
--   end if;
-- 
--   perform 1 from public.examenes e where e.id = v_examen.id for no key update;
-- 
--   -- Relaciones NUEVAS: las que ocupan una posición (cuentan para el tope de 200).
--   select count(*)::integer
--     into v_a_publicar
--     from public.preguntas_parseadas_staging s
--    where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
--      and not exists (select 1 from public.preguntas_examen pe
--                       where pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id);
--   if (select count(*) from public.preguntas_examen pe where pe.examen_id = v_examen.id) + v_a_publicar > 200 then
--     raise exception 'EXAM_QUESTION_LIMIT_REACHED';
--   end if;
-- 
--   -- Relaciones EXISTENTES cuya anulación cambia: alteran la puntuación de un examen ya en marcha.
--   select count(*)::integer
--     into v_relaciones_cambiadas
--     from public.preguntas_parseadas_staging s
--     join public.preguntas_examen pe
--       on pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id
--    where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
--      and (pe.anulada is distinct from s.anulada
--           or pe.motivo_anulacion is distinct from (case when s.anulada then nullif(btrim(s.motivo_anulacion), '') else null end));
-- 
--   if v_a_publicar + v_relaciones_cambiadas > 0
--      and (select e.publicado_en is not null and e.activo from public.examenes e where e.id = v_examen.id)
--      and not coalesce(p_confirmar_examen_publicado, false) then
--     raise exception 'IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED';
--   end if;
-- 
--   for v_fila in
--     select s.id, s.titulo, s.opcion_a, s.opcion_b, s.opcion_c, s.opcion_d, s.respuesta_correcta,
--            s.razonamiento, s.fuente_texto, s.vincular_pregunta_id, s.anulada, s.motivo_anulacion
--       from public.preguntas_parseadas_staging s
--      where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
--      order by s.posicion
--   loop
--     if v_fila.vincular_pregunta_id is not null then
--       -- Vincular NO toca la pregunta global: sólo crea/actualiza la relación con ESTE examen.
--       select l.ya_vinculada into v_ya_vinculada
--         from public.admin_link_question_to_exam(v_examen.id, v_fila.vincular_pregunta_id, null,
--                                                 v_fila.anulada, v_fila.motivo_anulacion) as l;
--       update public.preguntas_parseadas_staging s
--          set pregunta_id = v_fila.vincular_pregunta_id,
--              estado_parseo = 'importada'
--        where s.id = v_fila.id;
--       if not coalesce(v_ya_vinculada, false) then
--         v_vinculadas := v_vinculadas + 1;
--       end if;
--     else
--       v_creada := public.admin_create_question(
--         gen_random_uuid(),
--         v_tipo_pregunta,
--         v_fila.titulo,
--         jsonb_build_array(
--           jsonb_build_object('letra', 'A', 'texto', v_fila.opcion_a, 'es_correcta', v_fila.respuesta_correcta = 'A'),
--           jsonb_build_object('letra', 'B', 'texto', v_fila.opcion_b, 'es_correcta', v_fila.respuesta_correcta = 'B'),
--           jsonb_build_object('letra', 'C', 'texto', v_fila.opcion_c, 'es_correcta', v_fila.respuesta_correcta = 'C'),
--           jsonb_build_object('letra', 'D', 'texto', v_fila.opcion_d, 'es_correcta', v_fila.respuesta_correcta = 'D')),
--         case when v_tipo_pregunta = 'rafaga' then null else v_fila.razonamiento end,
--         case when v_tipo_pregunta = 'rafaga' then null else v_fila.fuente_texto end,
--         null,
--         true,
--         v_examen.id,
--         null,
--         p_lote_id,
--         p_actor_id,
--         v_fila.anulada,
--         v_fila.motivo_anulacion);
--       update public.preguntas_parseadas_staging s
--          set pregunta_id = (v_creada ->> 'pregunta_id')::uuid,
--              codigo_pregunta_generado = v_creada ->> 'codigo_pregunta',
--              estado_parseo = 'importada'
--        where s.id = v_fila.id;
--       v_nuevas := v_nuevas + 1;
--     end if;
--   end loop;
-- 
--   update public.lotes_importacion_preguntas l
--      set estado = 'importado',
--          total_importadas = (select count(*) from public.preguntas_parseadas_staging s
--                               where s.lote_id = l.id and s.pregunta_id is not null),
--          mensaje_error = null
--    where l.id = p_lote_id;
-- 
--   return private.import_lote_totales(p_lote_id)
--     || jsonb_build_object(
--          'ejecutado', true,
--          'nuevas', v_nuevas,
--          'vinculadas', v_vinculadas,
--          'anuladas', (select count(*) from public.preguntas_parseadas_staging s
--                        where s.lote_id = p_lote_id and s.pregunta_id is not null and s.anulada));
-- end;
-- $function$;
-- drop trigger if exists trg_staging_bloque_inmutable on public.preguntas_parseadas_staging;
-- drop function if exists private.tg_staging_bloque_inmutable();
-- drop function if exists private.import_lote_huella(uuid);
-- drop index if exists public.ux_lotes_importacion_huella;
-- alter table public.lotes_importacion_preguntas drop column if exists huella, drop column if exists codigo_examen;
-- alter table public.preguntas_parseadas_staging drop column if exists bloque;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'import_functions_private_acl:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') or not has_function_privilege('service_role', f.n, 'execute') and f.n like 'public.%' then 1 else 0 end)::bigint as failures
--   from (values ('private.import_lote_huella(uuid)'), ('public.admin_import_lote_huella(uuid)'), ('public.admin_import_lote_preguntas(uuid,boolean,uuid)')) f(n)
-- union all
-- select 'import_columns_missing',
--        (3 - (select count(*) from information_schema.columns c where (c.table_name, c.column_name) in (('lotes_importacion_preguntas', 'huella'), ('lotes_importacion_preguntas', 'codigo_examen'), ('preguntas_parseadas_staging', 'bloque'))))::bigint
-- union all
-- select 'import_unique_lot_index_missing', (case when to_regclass('public.ux_lotes_importacion_huella') is null then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.admin_import_lote_preguntas(uuid,boolean,uuid)') is null
     or to_regprocedure('private.official_rules_problem(uuid)') is null
     or to_regprocedure('private.official_rules_warnings(uuid)') is null
     or not exists (select 1 from information_schema.columns where table_name = 'preguntas_parseadas_staging' and column_name = 'anulada') then
    raise exception using errcode = 'P0001', message = 'V1_IMPORT_RESERVES_PREFLIGHT', hint = 'apply 20260919122139 and 20261022101300 first';
  end if;
end
$$;

lock table public.lotes_importacion_preguntas, public.preguntas_parseadas_staging in share row exclusive mode;

alter table public.lotes_importacion_preguntas
  add column if not exists codigo_examen text,   -- the CODIGO_EXAMEN header of the file (null = lot created without a header)
  add column if not exists huella text;          -- sha256 of the published content, written when the lot is imported
create unique index if not exists ux_lotes_importacion_huella on public.lotes_importacion_preguntas (examen_id, huella) where estado = 'importado' and huella is not null;

alter table public.preguntas_parseadas_staging
  add column if not exists bloque text not null default 'PRINCIPAL' check (bloque in ('PRINCIPAL', 'RESERVA'));

create or replace function private.tg_staging_bloque_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.bloque is distinct from old.bloque
     and (old.pregunta_id is not null or exists (select 1 from public.lotes_importacion_preguntas l where l.id = old.lote_id and l.estado = 'importado')) then
    raise exception 'STAGING_ROW_ALREADY_IMPORTED';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_staging_bloque_inmutable() from public, anon, authenticated;
drop trigger if exists trg_staging_bloque_inmutable on public.preguntas_parseadas_staging;
create trigger trg_staging_bloque_inmutable before update of bloque on public.preguntas_parseadas_staging
  for each row execute function private.tg_staging_bloque_inmutable();

-- the fingerprint of what a lot would publish: block, normalised text, options, answer, reasoning, annulment, link decision, in lot order (null = nothing to publish)
create or replace function private.import_lote_huella(p_lote_id uuid)
returns text
language sql
stable
set search_path = ''
as $$
  select encode(sha256(convert_to(string_agg(concat_ws(chr(31), s.bloque, private.normalizar_texto_pregunta(s.titulo),
           private.normalizar_texto_pregunta(s.opcion_a), private.normalizar_texto_pregunta(s.opcion_b), private.normalizar_texto_pregunta(s.opcion_c), private.normalizar_texto_pregunta(s.opcion_d),
           coalesce(s.respuesta_correcta, ''), private.normalizar_texto_pregunta(s.razonamiento), s.anulada::text, coalesce(s.vincular_pregunta_id::text, '')), chr(30) order by s.posicion), 'UTF8')), 'hex')
    from public.preguntas_parseadas_staging s
   where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
$$;
revoke all on function private.import_lote_huella(uuid) from public, anon, authenticated;

-- Control reads this at review time: the fingerprint and the already imported lot of the same exam with the same content (null = none)
create or replace function public.admin_import_lote_huella(p_lote_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object('huella', h.v, 'duplicado_de',
           (select o.id from public.lotes_importacion_preguntas o, public.lotes_importacion_preguntas l
             where l.id = p_lote_id and o.examen_id = l.examen_id and o.id <> l.id and o.estado = 'importado' and h.v is not null and o.huella = h.v limit 1))
    from (select private.import_lote_huella(p_lote_id) as v) h
$$;
revoke all on function public.admin_import_lote_huella(uuid) from public, anon, authenticated;
grant execute on function public.admin_import_lote_huella(uuid) to service_role;

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
  v_huella text;
  v_hay_reserva boolean;
  v_orden integer;
  v_pregunta uuid;
  v_reservas integer := 0;
  v_problema text;
  v_publicado boolean;
begin
  if p_lote_id is null then
    raise exception 'IMPORT_LOTE_ID_REQUIRED';
  end if;

  perform 1
    from public.examenes e
   where e.id = (select l.examen_id from public.lotes_importacion_preguntas l where l.id = p_lote_id)
     for key share;

  select l.id, l.examen_id, l.tipo_examen, l.estado, l.codigo_examen
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

  select e.id, e.tipo_examen, e.codigo_examen, e.es_oficial, e.publicado_en is not null as publicado
    into v_examen
    from public.examenes e
   where e.id = v_lote.examen_id;
  if not found then
    raise exception 'IMPORT_EXAM_NOT_FOUND';
  end if;
  if v_lote.tipo_examen is distinct from v_examen.tipo_examen then
    raise exception 'IMPORT_TYPE_MISMATCH';
  end if;
  -- FA37: the file header (CODIGO_EXAMEN) must name the exam the lot was created for
  if v_lote.codigo_examen is not null and v_lote.codigo_examen is distinct from v_examen.codigo_examen then
    raise exception 'IMPORT_EXAM_CODE_MISMATCH';
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

  -- FA37: duplicate-lot guard. The fingerprint is the normalised content of the rows about to be published (block, text, options, answer, annulment, link decision).
  v_huella := private.import_lote_huella(p_lote_id);
  if v_huella is not null and exists (select 1 from public.lotes_importacion_preguntas o
                                       where o.examen_id = v_lote.examen_id and o.id <> p_lote_id and o.estado = 'importado' and o.huella = v_huella) then
    raise exception 'IMPORT_LOT_DUPLICATE'
      using detail = (select o.id::text from public.lotes_importacion_preguntas o
                       where o.examen_id = v_lote.examen_id and o.id <> p_lote_id and o.estado = 'importado' and o.huella = v_huella limit 1);
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

  -- FA37: reserves / annulled / demo rules of an OFFICIAL exam (the exam is locked here, so the counts are stable).
  v_hay_reserva := exists (select 1 from public.preguntas_parseadas_staging s
                            where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada') and s.bloque = 'RESERVA');
  if v_hay_reserva and not v_examen.es_oficial then
    raise exception 'IMPORT_RESERVE_REQUIRES_OFFICIAL_EXAM';
  end if;
  -- linking a question that is already in this exam never moves it between MAIN and RESERVE
  if exists (select 1 from public.preguntas_parseadas_staging s
               join public.preguntas_examen pe on pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id
              where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
                and (s.bloque = 'RESERVA') is distinct from pe.es_reserva) then
    raise exception 'IMPORT_BLOCK_CONFLICT';
  end if;
  -- a demo question is a fixed evaluable MAIN question: never a reserve, never annulled
  if exists (select 1 from public.preguntas_parseadas_staging s
               join public.preguntas_examen pe on pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id
              where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
                and pe.es_demo and (s.bloque = 'RESERVA' or s.anulada)) then
    raise exception 'IMPORT_DEMO_CONFLICT';
  end if;
  -- new MAIN rows would be placed after the reserves already in the exam (reserves always come last)
  if exists (select 1 from public.preguntas_examen pe where pe.examen_id = v_examen.id and pe.es_reserva)
     and exists (select 1 from public.preguntas_parseadas_staging s
                  where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada') and s.bloque = 'PRINCIPAL'
                    and not exists (select 1 from public.preguntas_examen pe where pe.examen_id = v_examen.id and pe.pregunta_id = s.vincular_pregunta_id)) then
    raise exception 'IMPORT_MAIN_AFTER_RESERVE';
  end if;
  select coalesce(max(pe.orden_reserva), 0) into v_orden from public.preguntas_examen pe where pe.examen_id = v_examen.id;

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
           s.razonamiento, s.fuente_texto, s.vincular_pregunta_id, s.anulada, s.motivo_anulacion, s.bloque
      from public.preguntas_parseadas_staging s
     where s.lote_id = p_lote_id and s.pregunta_id is null and s.estado_parseo in ('ok', 'aprobada')
     order by (s.bloque = 'RESERVA'), s.posicion
  loop
    if v_fila.vincular_pregunta_id is not null then
      -- Vincular NO toca la pregunta global: sólo crea/actualiza la relación con ESTE examen.
      select l.ya_vinculada into v_ya_vinculada
        from public.admin_link_question_to_exam(v_examen.id, v_fila.vincular_pregunta_id, null,
                                                v_fila.anulada, v_fila.motivo_anulacion) as l;
      v_pregunta := v_fila.vincular_pregunta_id;
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
      v_pregunta := (v_creada ->> 'pregunta_id')::uuid;
      update public.preguntas_parseadas_staging s
         set pregunta_id = (v_creada ->> 'pregunta_id')::uuid,
             codigo_pregunta_generado = v_creada ->> 'codigo_pregunta',
             estado_parseo = 'importada'
       where s.id = v_fila.id;
      v_nuevas := v_nuevas + 1;
    end if;
    -- FA37: reserves are appended after every MAIN row (the loop order) and numbered in the lot order, continuing the exam's own numbering
    if v_fila.bloque = 'RESERVA' and not exists (select 1 from public.preguntas_examen pe where pe.examen_id = v_examen.id and pe.pregunta_id = v_pregunta and pe.es_reserva) then
      v_orden := v_orden + 1;
      v_reservas := v_reservas + 1;
      update public.preguntas_examen pe set es_reserva = true, orden_reserva = v_orden where pe.examen_id = v_examen.id and pe.pregunta_id = v_pregunta;
    end if;
  end loop;

  -- FA37: a lot that declares reserves, or lands in a PUBLISHED exam, must leave a coherent official exam (more annulled than reserves, order gaps...)
  v_problema := private.official_rules_problem(v_examen.id);
  v_publicado := v_examen.publicado;
  if v_problema is not null and (v_hay_reserva or v_publicado) then
    raise exception 'IMPORT_OFFICIAL_RULES_INVALID' using hint = v_problema;
  end if;

  update public.lotes_importacion_preguntas l
     set estado = 'importado',
         huella = v_huella,
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
                       where s.lote_id = p_lote_id and s.pregunta_id is not null and s.anulada),
         'reservas', v_reservas,
         'huella', v_huella,
         'problema', v_problema,
         'avisos', to_jsonb(private.official_rules_warnings(v_examen.id)));
end;
$function$;

commit;
