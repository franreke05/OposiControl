-- OPOSICONTROL CANONICAL V1 V2 — DENY-BY-DEFAULT RLS
-- Architecture: OposiBot -> Ktor -> service_role.
-- No authenticated INSERT/UPDATE/DELETE is granted for these business tables.
-- The five sensitive user tables called out in the review are backend-only.

begin;

do $$
declare
  table_name text;
  target_tables text[] := array[
    'atribucion_usuario', 'beneficios_plan', 'capacidades_especialidad',
    'comunidades_objetivo_usuario', 'creditos_contenido_usuario',
    'efectos_servicio', 'estado_competitivo_usuario', 'estado_opositester_usuario',
    'ligas_nivel', 'perfiles_usuario_detalle', 'planes_suscripcion',
    'popup_usuario_historial', 'preguntas_reto_diario', 'productos_comerciales',
    'productos_store', 'promociones', 'reglas_popup_comercial',
    'resumen_intento_etiqueta', 'usos_promocion_usuario', 'valor_usuario_resumen'
  ];
begin
  foreach table_name in array target_tables loop
    execute format('alter table public.%I enable row level security', table_name);
    execute format('revoke all on table public.%I from public, anon, authenticated', table_name);
    execute format('grant all on table public.%I to service_role', table_name);
  end loop;
end;
$$;

-- Backend-only: no direct client grant or policy.
-- atribucion_usuario, comunidades_objetivo_usuario,
-- perfiles_usuario_detalle, popup_usuario_historial and
-- usos_promocion_usuario are intentionally service-role-only.

-- Authenticated read-only own state; all writes stay in Ktor/service-role.
drop policy if exists canonical_v2_creditos_select on public.creditos_contenido_usuario;
create policy canonical_v2_creditos_select on public.creditos_contenido_usuario
  for select to authenticated
  using ((select auth.uid()) = usuario_id);
grant select on table public.creditos_contenido_usuario to authenticated;

drop policy if exists canonical_v2_competitivo_select on public.estado_competitivo_usuario;
create policy canonical_v2_competitivo_select on public.estado_competitivo_usuario
  for select to authenticated
  using ((select auth.uid()) = usuario_id);
grant select on table public.estado_competitivo_usuario to authenticated;

drop policy if exists canonical_v2_opositester_select on public.estado_opositester_usuario;
create policy canonical_v2_opositester_select on public.estado_opositester_usuario
  for select to authenticated
  using ((select auth.uid()) = usuario_id);
grant select on table public.estado_opositester_usuario to authenticated;

drop policy if exists canonical_v2_attempt_summary_select on public.resumen_intento_etiqueta;
create policy canonical_v2_attempt_summary_select on public.resumen_intento_etiqueta
  for select to authenticated
  using (exists (
    select 1
    from public.intentos_examen ie
    where ie.id = resumen_intento_etiqueta.intento_id
      and ie.usuario_id = (select auth.uid())
  ));
grant select on table public.resumen_intento_etiqueta to authenticated;

-- Public/read-only catalogues.
drop policy if exists canonical_v2_benefits_public_read on public.beneficios_plan;
create policy canonical_v2_benefits_public_read on public.beneficios_plan
  for select to anon, authenticated
  using (exists (
    select 1
    from public.planes_suscripcion ps
    where ps.id = beneficios_plan.plan_id
      and ps.activo
      and ps.visible
  ));
grant select on table public.beneficios_plan to anon, authenticated;

drop policy if exists canonical_v2_capabilities_public_read on public.capacidades_especialidad;
create policy canonical_v2_capabilities_public_read on public.capacidades_especialidad
  for select to anon, authenticated using (true);
grant select on table public.capacidades_especialidad to anon, authenticated;

drop policy if exists canonical_v2_leagues_public_read on public.ligas_nivel;
create policy canonical_v2_leagues_public_read on public.ligas_nivel
  for select to anon, authenticated using (activa);
grant select on table public.ligas_nivel to anon, authenticated;

drop policy if exists canonical_v2_plans_public_read on public.planes_suscripcion;
create policy canonical_v2_plans_public_read on public.planes_suscripcion
  for select to anon, authenticated using (activo and visible);
grant select on table public.planes_suscripcion to anon, authenticated;

drop policy if exists canonical_v2_products_public_read on public.productos_comerciales;
create policy canonical_v2_products_public_read on public.productos_comerciales
  for select to anon, authenticated using (activo and visible);
grant select on table public.productos_comerciales to anon, authenticated;

drop policy if exists canonical_v2_store_products_public_read on public.productos_store;
create policy canonical_v2_store_products_public_read on public.productos_store
  for select to anon, authenticated using (activo);
grant select on table public.productos_store to anon, authenticated;

drop policy if exists canonical_v2_daily_challenge_read on public.preguntas_reto_diario;
create policy canonical_v2_daily_challenge_read on public.preguntas_reto_diario
  for select to authenticated using (true);
grant select on table public.preguntas_reto_diario to authenticated;

drop policy if exists canonical_v2_promotions_read on public.promociones;
create policy canonical_v2_promotions_read on public.promociones
  for select to authenticated
  using (
    activo
    and (valido_desde is null or valido_desde <= now())
    and (valido_hasta is null or valido_hasta >= now())
  );
grant select on table public.promociones to authenticated;

-- reglas_popup_comercial, efectos_servicio and valor_usuario_resumen remain
-- service-role-only. No admin Data API grant is created.

commit;
