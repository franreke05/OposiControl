-- V1 CONTENT VERSION (owner request 2026-11-15: the app refreshes by itself when Control publishes a new exam, service/consumable or news; workstream F31c,
-- contract amendment FA58, D2-69 GET /api/v1/app/contenido/version). Forward-only, READ ONLY (no table, no write, no trigger). Requires the V1 Market columns
-- of public.servicios (20261114400000: visible, posicion, seccion, usos, duracion_horas, multiplicador).
--
-- New: public.app_contenido_version(p_usuario uuid) -> jsonb {"examenes","servicios","noticias","paquetes"}, service_role only (Ktor passes the JWT `sub`).
-- Each value is an OPAQUE token (16 hex chars: the first half of an md5) of what THIS user can see in that area, so it changes whenever something visible
-- changes there (a row appears or disappears, or a field the app shows changes) and stays the same otherwise:
--   * examenes  active exams published at or before now (DB clock), of the user specialty (no specialty = every specialty, like the catalog fallback):
--               id, titulo, descripcion, tipo_examen, tipo_acceso, total_preguntas, duracion_minutos, anio, es_oficial, publicado_en, actualizado_en, comunidad_id,
--               comprable_con_tickets, precio_tickets, demo_disponible, portada_storage_path. An exam scheduled for later enters the token when its time comes.
--   * servicios activo AND visible services (the Market, D6-39): codigo, nombre, descripcion, tipo_servicio, precio_tickets, precio_centimos, seccion, posicion, usos,
--               duracion_horas, multiplicador, actualizado_en.
--   * noticias  the app news rule (D4-18, NewsFeed.kt): tipo_noticia activo, of the user specialty (none = all), estado published with published_at and publicar_desde
--               NULL or past, or estado scheduled with publicar_desde past: id, estado, titulo, publicar_desde, published_at, actualizado_en. The news tables
--               exist on MAIN (not in the PG17 gate base): without public.noticia the token is the empty one.
--   * paquetes  ticket packs on sale (D6-11): productos_comerciales pack_tickets, activo, visible, cantidad_tickets > 0, precio_centimos > 0: codigo, nombre,
--               descripcion, precio_centimos, cantidad_tickets, posicion, destacado, etiqueta_promocional, metadatos, actualizado_en.
-- Cost: four aggregates over small sets (exams of one specialty; idx_examenes_especialidad), no index added. Unknown user = WALLET_INVALID_USER.
-- DEPLOY ORDER: this migration BEFORE the Ktor image that serves D2-69 (an older image never calls it).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): drops the function; nothing else changed.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_contenido_version(uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'contenido_version_not_service_role_only' as check_name,
--        (case when to_regprocedure('public.app_contenido_version(uuid)') is null
--                or has_function_privilege('anon', 'public.app_contenido_version(uuid)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_contenido_version(uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_contenido_version(uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'contenido_version_not_security_definer',
--        (case when to_regprocedure('public.app_contenido_version(uuid)') is null
--                or exists (select 1 from pg_proc p where p.oid = to_regprocedure('public.app_contenido_version(uuid)')
--                             and (not p.prosecdef or not coalesce(p.proconfig @> array['search_path=""'], false) or p.provolatile = 'v')) then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.examenes') is null or to_regclass('public.productos_comerciales') is null
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'servicios' and c.column_name = 'visible')
     or not exists (select 1 from information_schema.columns c where c.table_schema = 'public' and c.table_name = 'servicios' and c.column_name = 'seccion') then
    raise exception using errcode = 'P0001', message = 'V1_CONTENIDO_VERSION_PREFLIGHT', hint = 'apply 20261114400000 first';
  end if;
end
$$;

create or replace function public.app_contenido_version(p_usuario uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_esp uuid;
  v_examenes text;
  v_servicios text;
  v_noticias text := '';
  v_paquetes text;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select pf.especialidad_principal_id into v_esp from public.perfiles_usuario pf where pf.usuario_id = p_usuario;

  select coalesce(string_agg(md5(row(e.id, e.titulo, e.descripcion, e.tipo_examen, e.tipo_acceso, e.total_preguntas, e.duracion_minutos, e.anio, e.es_oficial,
                                      e.publicado_en, e.actualizado_en, e.comunidad_id, e.comprable_con_tickets, e.precio_tickets, e.demo_disponible,
                                      e.portada_storage_path)::text), '' order by e.id), '')
    into v_examenes
    from public.examenes e
   where e.activo and e.publicado_en is not null and e.publicado_en <= v_now and (v_esp is null or e.especialidad_id = v_esp);

  select coalesce(string_agg(md5(row(s.codigo, s.nombre, s.descripcion, s.tipo_servicio, s.precio_tickets, s.precio_centimos, s.seccion, s.posicion, s.usos,
                                      s.duracion_horas, s.multiplicador, s.actualizado_en)::text), '' order by s.codigo), '')
    into v_servicios
    from public.servicios s
   where s.activo and s.visible;

  if to_regclass('public.noticia') is not null and to_regclass('public.tipo_noticia') is not null then
    -- dynamic: the news tables are MAIN objects that the PG17 gate base does not carry (the body must compile without them)
    execute $q$
      select coalesce(string_agg(md5(row(n.id, n.estado, n.titulo, n.publicar_desde, n.published_at, n.actualizado_en)::text), '' order by n.id), '')
        from public.noticia n join public.tipo_noticia t on t.id = n.tipo_noticia_id and t.activo
       where ($1::uuid is null or n.especialidad_id = $1)
         and ((n.estado = 'published' and n.published_at is not null and (n.publicar_desde is null or n.publicar_desde <= $2))
              or (n.estado = 'scheduled' and n.publicar_desde <= $2))
    $q$ into v_noticias using v_esp, v_now;
  end if;

  select coalesce(string_agg(md5(row(p.codigo, p.nombre, p.descripcion, p.precio_centimos, p.cantidad_tickets, p.posicion, p.destacado, p.etiqueta_promocional,
                                      p.metadatos, p.actualizado_en)::text), '' order by p.codigo), '')
    into v_paquetes
    from public.productos_comerciales p
   where p.tipo_producto = 'pack_tickets' and p.activo and p.visible and p.cantidad_tickets > 0 and p.precio_centimos > 0;

  return jsonb_build_object(
    'examenes', left(md5('examenes|' || v_examenes), 16),
    'servicios', left(md5('servicios|' || v_servicios), 16),
    'noticias', left(md5('noticias|' || v_noticias), 16),
    'paquetes', left(md5('paquetes|' || v_paquetes), 16));
end;
$$;
revoke all on function public.app_contenido_version(uuid) from public, anon, authenticated;
grant execute on function public.app_contenido_version(uuid) to service_role;

notify pgrst, 'reload schema';

commit;
