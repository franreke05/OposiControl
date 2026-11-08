-- V1 EXAM ACCESS ROUTE (F15, owner decision 2026-11-08): the exam "tipo de acceso / turno" of the official call ("Libre", "Promocion interna",
-- "Estabilizacion", "Discapacidad") as an OPTIONAL exam datum. OposiBot exam cards show it under the title (owner Figma screenshot: label
-- "Inmersivo", title "Solucionario Madrid 2024", subtitle "Promocion interna"). Contract amendment FA45 (catalog.yaml: AppExamSummary D2-01,
-- AppExamDetail D2-02, MyExamItem.examen D2-13, AdminExamen / create / patch). Additive: no earlier file is edited (the collection read of
-- 20261022101000 is re-created here with ONE more key; same signature, owner, SECURITY DEFINER, empty search_path, STABLE and ACL).
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN (owner GO required). Requires 20261022101000 (app_my_exams_collection body).
--
-- NOT the same concept as examenes.tipo_acceso (gratuito/premium = commercial access tier). This column is the "turno" of the public
-- employment call the exam belongs to. No repo domain document defines this vocabulary (searched docs/ and the client spec digest: no "turno",
-- no "promocion interna", no "concurso-oposicion"); the closed set comes from the owner's F15 brief:
--   libre | promocion_interna | estabilizacion | discapacidad      (NULL = "Sin indicar": clients show their previous subtitle)
-- "discapacidad" = the reserved quota for people with disabilities. A selection SYSTEM (oposicion, concurso-oposicion, concurso) is a different
-- axis and is NOT modelled here. Adding a value later = a new forward migration that widens the CHECK + an FA amendment; OposiBot maps an
-- unknown code to "no access route" (falls back to its previous subtitle), so widening is safe for already installed apps.
--
-- WHAT IT DOES
--   1. public.examenes.turno_acceso text NULL + CHECK examenes_turno_acceso_check (NULL or one of the four codes). Nullable, no default: a
--      catalog-only change (no table rewrite); the CHECK is validated in the same statement (examenes is small; every row is NULL).
--   2. public.app_my_exams_collection (D2-13 vista=COLECCION) also returns examenes.turno_acceso, so the three app reads (D2-01, D2-02, D2-13)
--      serve the same datum. D2-01, D2-02 and D2-13 EN_CURSO/ADQUIRIDOS read it through PostgREST (CatalogGateway.EXAM_COLUMNS).
--   Nothing is backfilled: every existing exam stays "Sin indicar" until an admin sets it in Control (Examenes > Ambito y clasificacion).
--
-- DEPLOY ORDER: this migration BEFORE the Ktor build that reads it (that build selects examenes.turno_acceso: without the column PostgREST
-- answers 400 and the catalog reads fail). The Ktor running today never names the column, so applying the migration first is safe.
--
-- PREFLIGHT (fail closed, P0001 EXAM_TURNO_ACCESO_PREFLIGHT, nothing changed): examenes exists; app_my_exams_collection with the
-- 20261022101000 signature exists; if turno_acceso already exists it must be text (otherwise stop: unknown drift).
--
-- LOCKING: ADD COLUMN / ADD CONSTRAINT take ACCESS EXCLUSIVE on examenes for milliseconds (metadata + validation of a small table).
-- lock_timeout 5s: under traffic it aborts instead of queueing behind readers.
--
-- DATA LOSS: none forward. The ROLLBACK drops the column and therefore the values admins set in Control (by design: there is nowhere else to
-- keep them). Export first if needed: select id, codigo_examen, turno_acceso from public.examenes where turno_acceso is not null;
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the 20261022101000 body of app_my_exams_collection comes back verbatim (same
-- signature and ACL) BEFORE the column is dropped (the new body reads it).
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_my_exams_collection(
--   p_usuario_id uuid,
--   p_tipo_examen text,
--   p_comunidad_id uuid,
--   p_anio integer,
--   p_despues_en timestamp with time zone,
--   p_despues_id uuid,
--   p_limite integer,
--   p_con_grupos boolean,
--   p_especialidad_id uuid
-- )
-- returns jsonb
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_ahora constant timestamp with time zone := now();
--   v_limite constant integer := least(greatest(coalesce(p_limite, 21), 1), 51);
--   v_resultado jsonb;
-- begin
--   if (p_despues_en is null) <> (p_despues_id is null) then
--     raise exception 'APP_MY_EXAMS_CURSOR_INCOMPLETE' using errcode = '22023';
--   end if;
--
--   with concedidos as (
--     select cd.examen_id, max(cd.valido_desde) as en
--     from public.contenido_desbloqueado_usuario cd
--     where cd.usuario_id = p_usuario_id and cd.examen_id is not null
--       and cd.valido_desde <= v_ahora and (cd.valido_hasta is null or cd.valido_hasta > v_ahora)
--     group by cd.examen_id
--   ),
--   intentados as (
--     select distinct on (ie.examen_id)
--            ie.examen_id, ie.id, ie.estado, ie.total_respondidas, ie.total_preguntas, ie.actualizado_en as en
--     from public.intentos_examen ie
--     where ie.usuario_id = p_usuario_id and ie.examen_id is not null
--       and ie.origen_intento = 'examen' and ie.creado_por_comando is not null and not ie.es_demo
--     order by ie.examen_id, ie.actualizado_en desc, ie.id desc
--   ),
--   coleccion as materialized (
--     select e.id, e.titulo, e.tipo_examen, e.tipo_acceso, e.especialidad_id, e.demo_disponible, e.portada_storage_path,
--            ca.codigo as comunidad_codigo,
--            greatest(c.en, i.en) as orden_en,
--            i.id as intento_id, i.estado as intento_estado,
--            i.total_respondidas as intento_respondidas, i.total_preguntas as intento_preguntas
--     from concedidos c
--     full join intentados i on i.examen_id = c.examen_id
--     join public.examenes e on e.id = coalesce(c.examen_id, i.examen_id)
--     left join public.comunidades_autonomas ca on ca.id = e.comunidad_id
--     where e.activo and e.publicado_en is not null and e.publicado_en <= v_ahora
--       and (p_tipo_examen is null or e.tipo_examen = p_tipo_examen)
--       and (p_comunidad_id is null or e.comunidad_id = p_comunidad_id)
--       and (p_anio is null or e.anio = p_anio)
--       and (p_especialidad_id is null or e.especialidad_id = p_especialidad_id)
--   )
--   select jsonb_build_object(
--     'items', coalesce((
--       select jsonb_agg(
--                jsonb_build_object(
--                  'orden_en', pagina.orden_en,
--                  'examenes', jsonb_build_object(
--                    'id', pagina.id,
--                    'titulo', pagina.titulo,
--                    'tipo_examen', pagina.tipo_examen,
--                    'tipo_acceso', pagina.tipo_acceso,
--                    'especialidad_id', pagina.especialidad_id,
--                    'demo_disponible', pagina.demo_disponible,
--                    'portada_storage_path', pagina.portada_storage_path,
--                    'comunidades_autonomas', case when pagina.comunidad_codigo is not null
--                                                  then jsonb_build_object('codigo', pagina.comunidad_codigo) end),
--                  'intento', case when pagina.intento_id is not null
--                                  then jsonb_build_object(
--                                    'id', pagina.intento_id,
--                                    'estado', pagina.intento_estado,
--                                    'total_respondidas', pagina.intento_respondidas,
--                                    'total_preguntas', pagina.intento_preguntas) end)
--                order by pagina.orden_en desc, pagina.id desc)
--       from (
--         select k.*
--         from coleccion k
--         where p_despues_en is null or (k.orden_en, k.id) < (p_despues_en, p_despues_id)
--         order by k.orden_en desc, k.id desc
--         limit v_limite
--       ) pagina), '[]'::jsonb),
--     'grupos', case when p_con_grupos then coalesce((
--       select jsonb_agg(jsonb_build_object('comunidad_codigo', g.comunidad_codigo, 'total', g.total)
--                        order by g.comunidad_codigo)
--       from (
--         select k.comunidad_codigo, count(*) as total
--         from coleccion k
--         where k.comunidad_codigo is not null
--         group by k.comunidad_codigo
--       ) g), '[]'::jsonb) end)
--   into v_resultado;
--
--   return v_resultado;
-- end;
-- $$;
--
-- revoke all on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid) from public, anon, authenticated;
-- grant execute on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid) to service_role;
-- alter table public.examenes drop constraint if exists examenes_turno_acceso_check;
-- alter table public.examenes drop column if exists turno_acceso;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'turno_acceso_column_missing_or_not_nullable_text' as check_name,
--        (case when exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'examenes'
--                             and c.column_name = 'turno_acceso' and c.data_type = 'text' and c.is_nullable = 'YES' and c.column_default is null)
--              then 0 else 1 end)::bigint as failures
-- union all
-- select 'turno_acceso_check_missing_or_not_the_closed_set',
--        (case when exists (select 1 from pg_constraint k where k.conrelid = 'public.examenes'::regclass and k.conname = 'examenes_turno_acceso_check'
--                             and k.contype = 'c' and k.convalidated
--                             and pg_get_constraintdef(k.oid) like '%''libre''%' and pg_get_constraintdef(k.oid) like '%''promocion_interna''%'
--                             and pg_get_constraintdef(k.oid) like '%''estabilizacion''%' and pg_get_constraintdef(k.oid) like '%''discapacidad''%'
--                             and (length(pg_get_constraintdef(k.oid)) - length(replace(pg_get_constraintdef(k.oid), '''::text', ''))) / 7 = 4)
--              then 0 else 1 end)::bigint
-- union all
-- select 'turno_acceso_rows_outside_the_set',
--        (select count(*) from public.examenes e
--          where e.turno_acceso is not null and e.turno_acceso not in ('libre', 'promocion_interna', 'estabilizacion', 'discapacidad'))::bigint
-- union all
-- select 'collection_read_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)', 'execute')
--              then 1 else 0 end)::bigint
-- union all
-- select 'collection_read_shape_or_missing_turno_acceso',
--        (case when exists (select 1 from pg_proc p
--                            where p.oid = 'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)'::regprocedure
--                              and p.provolatile = 's' and p.prosecdef and p.proconfig @> array['search_path=""']
--                              and p.prosrc like '%''turno_acceso'', pagina.turno_acceso%' and p.prosrc like '%e.turno_acceso%')
--              then 0 else 1 end)::bigint
-- VERIFY-END

begin;
set local lock_timeout = '5s';

do $preflight$
declare
  v_type text;
begin
  if to_regclass('public.examenes') is null then
    raise exception 'EXAM_TURNO_ACCESO_PREFLIGHT: public.examenes is missing' using errcode = 'P0001';
  end if;
  if to_regprocedure('public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)') is null then
    raise exception 'EXAM_TURNO_ACCESO_PREFLIGHT: app_my_exams_collection (20261022101000) is missing' using errcode = 'P0001';
  end if;
  select c.data_type into v_type from information_schema.columns c
   where c.table_schema = 'public' and c.table_name = 'examenes' and c.column_name = 'turno_acceso';
  if v_type is not null and v_type <> 'text' then
    raise exception 'EXAM_TURNO_ACCESO_PREFLIGHT: examenes.turno_acceso exists with type %', v_type using errcode = 'P0001';
  end if;
end
$preflight$;

alter table public.examenes add column if not exists turno_acceso text;
alter table public.examenes drop constraint if exists examenes_turno_acceso_check;
alter table public.examenes add constraint examenes_turno_acceso_check
  check (turno_acceso is null or turno_acceso in ('libre', 'promocion_interna', 'estabilizacion', 'discapacidad'));
comment on column public.examenes.turno_acceso is
  'F15/FA45: tipo de acceso (turno) of the official call: libre, promocion_interna, estabilizacion, discapacidad; NULL = sin indicar. Not tipo_acceso (gratuito/premium).';

create or replace function public.app_my_exams_collection(
  p_usuario_id uuid,
  p_tipo_examen text,
  p_comunidad_id uuid,
  p_anio integer,
  p_despues_en timestamp with time zone,
  p_despues_id uuid,
  p_limite integer,
  p_con_grupos boolean,
  p_especialidad_id uuid
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_ahora constant timestamp with time zone := now();
  v_limite constant integer := least(greatest(coalesce(p_limite, 21), 1), 51);
  v_resultado jsonb;
begin
  if (p_despues_en is null) <> (p_despues_id is null) then
    raise exception 'APP_MY_EXAMS_CURSOR_INCOMPLETE' using errcode = '22023';
  end if;

  with concedidos as (
    select cd.examen_id, max(cd.valido_desde) as en
    from public.contenido_desbloqueado_usuario cd
    where cd.usuario_id = p_usuario_id and cd.examen_id is not null
      and cd.valido_desde <= v_ahora and (cd.valido_hasta is null or cd.valido_hasta > v_ahora)
    group by cd.examen_id
  ),
  intentados as (
    select distinct on (ie.examen_id)
           ie.examen_id, ie.id, ie.estado, ie.total_respondidas, ie.total_preguntas, ie.actualizado_en as en
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id is not null
      and ie.origen_intento = 'examen' and ie.creado_por_comando is not null and not ie.es_demo
    order by ie.examen_id, ie.actualizado_en desc, ie.id desc
  ),
  coleccion as materialized (
    select e.id, e.titulo, e.tipo_examen, e.tipo_acceso, e.turno_acceso, e.especialidad_id, e.demo_disponible, e.portada_storage_path,
           ca.codigo as comunidad_codigo,
           greatest(c.en, i.en) as orden_en,
           i.id as intento_id, i.estado as intento_estado,
           i.total_respondidas as intento_respondidas, i.total_preguntas as intento_preguntas
    from concedidos c
    full join intentados i on i.examen_id = c.examen_id
    join public.examenes e on e.id = coalesce(c.examen_id, i.examen_id)
    left join public.comunidades_autonomas ca on ca.id = e.comunidad_id
    where e.activo and e.publicado_en is not null and e.publicado_en <= v_ahora
      and (p_tipo_examen is null or e.tipo_examen = p_tipo_examen)
      and (p_comunidad_id is null or e.comunidad_id = p_comunidad_id)
      and (p_anio is null or e.anio = p_anio)
      and (p_especialidad_id is null or e.especialidad_id = p_especialidad_id)
  )
  select jsonb_build_object(
    'items', coalesce((
      select jsonb_agg(
               jsonb_build_object(
                 'orden_en', pagina.orden_en,
                 'examenes', jsonb_build_object(
                   'id', pagina.id,
                   'titulo', pagina.titulo,
                   'tipo_examen', pagina.tipo_examen,
                   'tipo_acceso', pagina.tipo_acceso,
                   'turno_acceso', pagina.turno_acceso,
                   'especialidad_id', pagina.especialidad_id,
                   'demo_disponible', pagina.demo_disponible,
                   'portada_storage_path', pagina.portada_storage_path,
                   'comunidades_autonomas', case when pagina.comunidad_codigo is not null
                                                 then jsonb_build_object('codigo', pagina.comunidad_codigo) end),
                 'intento', case when pagina.intento_id is not null
                                 then jsonb_build_object(
                                   'id', pagina.intento_id,
                                   'estado', pagina.intento_estado,
                                   'total_respondidas', pagina.intento_respondidas,
                                   'total_preguntas', pagina.intento_preguntas) end)
               order by pagina.orden_en desc, pagina.id desc)
      from (
        select k.*
        from coleccion k
        where p_despues_en is null or (k.orden_en, k.id) < (p_despues_en, p_despues_id)
        order by k.orden_en desc, k.id desc
        limit v_limite
      ) pagina), '[]'::jsonb),
    'grupos', case when p_con_grupos then coalesce((
      select jsonb_agg(jsonb_build_object('comunidad_codigo', g.comunidad_codigo, 'total', g.total)
                       order by g.comunidad_codigo)
      from (
        select k.comunidad_codigo, count(*) as total
        from coleccion k
        where k.comunidad_codigo is not null
        group by k.comunidad_codigo
      ) g), '[]'::jsonb) end)
  into v_resultado;

  return v_resultado;
end;
$$;

revoke all on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid) from public, anon, authenticated;
grant execute on function public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid) to service_role;

commit;
