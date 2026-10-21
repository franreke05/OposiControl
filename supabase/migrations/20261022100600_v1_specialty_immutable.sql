-- V1 (owner decision 2026-10-16): the specialty of a user is SET ONCE and cannot be changed from the app. Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
--
-- WHAT IS ENFORCED (database level, so no route can bypass it: onboarding, PATCH /me/profile, register, admin edits, raw SQL by an app role)
--   perfiles_usuario.especialidad_principal_id may go NULL -> value (first choice) and value -> the SAME value (no-op). Any other change
--   (value -> another value, value -> NULL) is refused with SQLSTATE OC409, message SPECIALTY_ALREADY_SET. Ktor maps it to 409 SPECIALTY_ALREADY_SET.
--   Side effect to know: `perfiles_usuario_especialidad_fk` is ON DELETE SET NULL, so deleting a specialty that users hold is refused too (a specialty in use
--   is retired by setting activa = false, never deleted).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate):
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid);
-- drop trigger if exists trg_perfiles_usuario_especialidad_inmutable on public.perfiles_usuario;
-- drop function if exists private.tg_perfiles_usuario_especialidad_inmutable();
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'collection_overload_missing' as check_name, (case when to_regprocedure('public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)') is not null then 0 else 1 end)::bigint as failures
-- union all
-- select 'collection_overload_client_executable', (case when has_function_privilege('anon', 'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)', 'execute') or has_function_privilege('authenticated', 'public.app_my_exams_collection(uuid,text,uuid,integer,timestamp with time zone,uuid,integer,boolean,uuid)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'trigger_missing',
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_perfiles_usuario_especialidad_inmutable' and tgrelid = 'public.perfiles_usuario'::regclass and not tgisinternal)
--              then 0 else 1 end)::bigint as failures
-- union all
-- select 'trigger_function_executable_by_clients',
--        (case when has_function_privilege('anon', 'private.tg_perfiles_usuario_especialidad_inmutable()', 'execute')
--                or has_function_privilege('authenticated', 'private.tg_perfiles_usuario_especialidad_inmutable()', 'execute') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create or replace function private.tg_perfiles_usuario_especialidad_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.especialidad_principal_id is not null and new.especialidad_principal_id is distinct from old.especialidad_principal_id then
    raise exception using errcode = 'OC409', message = 'SPECIALTY_ALREADY_SET';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_perfiles_usuario_especialidad_inmutable() from public, anon, authenticated;

drop trigger if exists trg_perfiles_usuario_especialidad_inmutable on public.perfiles_usuario;
create trigger trg_perfiles_usuario_especialidad_inmutable
  before update of especialidad_principal_id on public.perfiles_usuario
  for each row execute function private.tg_perfiles_usuario_especialidad_inmutable();


-- D2-13 COLECCION (E2-V1-E05): the collection union (grants and attempts) takes the same specialty predicate as every other list. New overload (9th argument, REQUIRED so the 8-argument calls keep resolving to the published function; NULL = no filter); the 8-argument function of the published migration stays untouched.
drop function if exists public.app_my_exams_collection(uuid, text, uuid, integer, timestamp with time zone, uuid, integer, boolean, uuid);
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
      and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
    order by ie.examen_id, ie.actualizado_en desc, ie.id desc
  ),
  coleccion as materialized (
    select e.id, e.titulo, e.tipo_examen, e.tipo_acceso, e.especialidad_id, e.demo_disponible, e.portada_storage_path,
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
