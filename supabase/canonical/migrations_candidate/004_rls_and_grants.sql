-- OPOSICONTROL CANONICAL V1 — DENY-BY-DEFAULT RLS AND GRANTS
-- Service-role remains the backend authority. Authenticated access is table- and
-- operation-specific; no policy relies on "has any role" semantics.

begin;

-- USER_OWNED: attribution and user-owned mappings.
alter table public.atribucion_usuario enable row level security;
drop policy if exists canonical_atribucion_select on public.atribucion_usuario;
drop policy if exists canonical_atribucion_insert on public.atribucion_usuario;
drop policy if exists canonical_atribucion_update on public.atribucion_usuario;
drop policy if exists canonical_atribucion_delete on public.atribucion_usuario;
create policy canonical_atribucion_select on public.atribucion_usuario for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
create policy canonical_atribucion_insert on public.atribucion_usuario for insert to authenticated with check (usuario_id = auth.uid() or public.is_admin());
create policy canonical_atribucion_update on public.atribucion_usuario for update to authenticated using (usuario_id = auth.uid() or public.is_admin()) with check (usuario_id = auth.uid() or public.is_admin());
create policy canonical_atribucion_delete on public.atribucion_usuario for delete to authenticated using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.atribucion_usuario from public, anon;
grant select, insert, update, delete on public.atribucion_usuario to authenticated, service_role;

alter table public.comunidades_objetivo_usuario enable row level security;
drop policy if exists canonical_comunidades_select on public.comunidades_objetivo_usuario;
drop policy if exists canonical_comunidades_insert on public.comunidades_objetivo_usuario;
drop policy if exists canonical_comunidades_update on public.comunidades_objetivo_usuario;
drop policy if exists canonical_comunidades_delete on public.comunidades_objetivo_usuario;
create policy canonical_comunidades_select on public.comunidades_objetivo_usuario for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
create policy canonical_comunidades_insert on public.comunidades_objetivo_usuario for insert to authenticated with check (usuario_id = auth.uid() or public.is_admin());
create policy canonical_comunidades_update on public.comunidades_objetivo_usuario for update to authenticated using (usuario_id = auth.uid() or public.is_admin()) with check (usuario_id = auth.uid() or public.is_admin());
create policy canonical_comunidades_delete on public.comunidades_objetivo_usuario for delete to authenticated using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.comunidades_objetivo_usuario from public, anon;
grant select, insert, update, delete on public.comunidades_objetivo_usuario to authenticated, service_role;

-- USER_OWNED read/write profile detail.
alter table public.perfiles_usuario_detalle enable row level security;
drop policy if exists canonical_detalle_select on public.perfiles_usuario_detalle;
drop policy if exists canonical_detalle_insert on public.perfiles_usuario_detalle;
drop policy if exists canonical_detalle_update on public.perfiles_usuario_detalle;
drop policy if exists canonical_detalle_delete on public.perfiles_usuario_detalle;
create policy canonical_detalle_select on public.perfiles_usuario_detalle for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
create policy canonical_detalle_insert on public.perfiles_usuario_detalle for insert to authenticated with check (usuario_id = auth.uid() or public.is_admin());
create policy canonical_detalle_update on public.perfiles_usuario_detalle for update to authenticated using (usuario_id = auth.uid() or public.is_admin()) with check (usuario_id = auth.uid() or public.is_admin());
create policy canonical_detalle_delete on public.perfiles_usuario_detalle for delete to authenticated using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.perfiles_usuario_detalle from public, anon;
grant select, insert, update, delete on public.perfiles_usuario_detalle to authenticated, service_role;

-- USER_OWNED read-only balances and derived state.
alter table public.creditos_contenido_usuario enable row level security;
drop policy if exists canonical_creditos_select on public.creditos_contenido_usuario;
create policy canonical_creditos_select on public.creditos_contenido_usuario for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.creditos_contenido_usuario from public, anon, authenticated;
grant select on public.creditos_contenido_usuario to authenticated;
grant all on public.creditos_contenido_usuario to service_role;

alter table public.estado_competitivo_usuario enable row level security;
drop policy if exists canonical_competitivo_select on public.estado_competitivo_usuario;
create policy canonical_competitivo_select on public.estado_competitivo_usuario for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.estado_competitivo_usuario from public, anon, authenticated;
grant select on public.estado_competitivo_usuario to authenticated;
grant all on public.estado_competitivo_usuario to service_role;

alter table public.estado_opositester_usuario enable row level security;
drop policy if exists canonical_opositester_select on public.estado_opositester_usuario;
create policy canonical_opositester_select on public.estado_opositester_usuario for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.estado_opositester_usuario from public, anon, authenticated;
grant select on public.estado_opositester_usuario to authenticated;
grant all on public.estado_opositester_usuario to service_role;

-- USER_OWNED user interaction histories.
alter table public.popup_usuario_historial enable row level security;
drop policy if exists canonical_popup_history_select on public.popup_usuario_historial;
drop policy if exists canonical_popup_history_insert on public.popup_usuario_historial;
create policy canonical_popup_history_select on public.popup_usuario_historial for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
create policy canonical_popup_history_insert on public.popup_usuario_historial for insert to authenticated with check (usuario_id = auth.uid() or public.is_admin());
revoke all on public.popup_usuario_historial from public, anon, authenticated;
grant select, insert on public.popup_usuario_historial to authenticated;
grant all on public.popup_usuario_historial to service_role;

alter table public.usos_promocion_usuario enable row level security;
drop policy if exists canonical_promo_usage_select on public.usos_promocion_usuario;
drop policy if exists canonical_promo_usage_insert on public.usos_promocion_usuario;
create policy canonical_promo_usage_select on public.usos_promocion_usuario for select to authenticated using (usuario_id = auth.uid() or public.is_admin());
create policy canonical_promo_usage_insert on public.usos_promocion_usuario for insert to authenticated with check (usuario_id = auth.uid() or public.is_admin());
revoke all on public.usos_promocion_usuario from public, anon, authenticated;
grant select, insert on public.usos_promocion_usuario to authenticated;
grant all on public.usos_promocion_usuario to service_role;

alter table public.resumen_intento_etiqueta enable row level security;
drop policy if exists canonical_attempt_summary_select on public.resumen_intento_etiqueta;
create policy canonical_attempt_summary_select on public.resumen_intento_etiqueta for select to authenticated using (
  exists (
    select 1 from public.intentos_examen ie
    where ie.id = resumen_intento_etiqueta.intento_id
      and (ie.usuario_id = auth.uid() or public.is_admin())
  )
);
revoke all on public.resumen_intento_etiqueta from public, anon, authenticated;
grant select on public.resumen_intento_etiqueta to authenticated;
grant all on public.resumen_intento_etiqueta to service_role;

-- PUBLIC_READ / USER_READABLE catalogs.
alter table public.beneficios_plan enable row level security;
drop policy if exists canonical_benefits_public_read on public.beneficios_plan;
create policy canonical_benefits_public_read on public.beneficios_plan for select to anon, authenticated using (
  exists (select 1 from public.planes_suscripcion ps where ps.id = beneficios_plan.plan_id and ps.activo and ps.visible)
);
revoke all on public.beneficios_plan from public, anon, authenticated;
grant select on public.beneficios_plan to anon, authenticated;
grant all on public.beneficios_plan to service_role;

alter table public.capacidades_especialidad enable row level security;
drop policy if exists canonical_capabilities_public_read on public.capacidades_especialidad;
create policy canonical_capabilities_public_read on public.capacidades_especialidad for select to anon, authenticated using (true);
revoke all on public.capacidades_especialidad from public, anon, authenticated;
grant select on public.capacidades_especialidad to anon, authenticated;
grant all on public.capacidades_especialidad to service_role;

alter table public.ligas_nivel enable row level security;
drop policy if exists canonical_leagues_public_read on public.ligas_nivel;
create policy canonical_leagues_public_read on public.ligas_nivel for select to anon, authenticated using (activa);
revoke all on public.ligas_nivel from public, anon, authenticated;
grant select on public.ligas_nivel to anon, authenticated;
grant all on public.ligas_nivel to service_role;

alter table public.planes_suscripcion enable row level security;
drop policy if exists canonical_plans_public_read on public.planes_suscripcion;
create policy canonical_plans_public_read on public.planes_suscripcion for select to anon, authenticated using (activo and visible);
revoke all on public.planes_suscripcion from public, anon, authenticated;
grant select on public.planes_suscripcion to anon, authenticated;
grant all on public.planes_suscripcion to service_role;

alter table public.productos_comerciales enable row level security;
drop policy if exists canonical_products_public_read on public.productos_comerciales;
create policy canonical_products_public_read on public.productos_comerciales for select to anon, authenticated using (activo and visible);
revoke all on public.productos_comerciales from public, anon, authenticated;
grant select on public.productos_comerciales to anon, authenticated;
grant all on public.productos_comerciales to service_role;

alter table public.productos_store enable row level security;
drop policy if exists canonical_store_products_public_read on public.productos_store;
create policy canonical_store_products_public_read on public.productos_store for select to anon, authenticated using (activo);
revoke all on public.productos_store from public, anon, authenticated;
grant select on public.productos_store to anon, authenticated;
grant all on public.productos_store to service_role;

alter table public.preguntas_reto_diario enable row level security;
drop policy if exists canonical_daily_challenge_read on public.preguntas_reto_diario;
create policy canonical_daily_challenge_read on public.preguntas_reto_diario for select to authenticated using (true);
revoke all on public.preguntas_reto_diario from public, anon, authenticated;
grant select on public.preguntas_reto_diario to authenticated;
grant all on public.preguntas_reto_diario to service_role;

alter table public.promociones enable row level security;
drop policy if exists canonical_promotions_read on public.promociones;
create policy canonical_promotions_read on public.promociones for select to authenticated using (
  activo and (valido_desde is null or valido_desde <= now()) and (valido_hasta is null or valido_hasta >= now())
);
revoke all on public.promociones from public, anon, authenticated;
grant select on public.promociones to authenticated;
grant all on public.promociones to service_role;

-- ADMIN_ONLY: authenticated admins can manage; anon and PUBLIC cannot.
alter table public.reglas_popup_comercial enable row level security;
drop policy if exists canonical_popup_rules_admin on public.reglas_popup_comercial;
create policy canonical_popup_rules_admin on public.reglas_popup_comercial for all to authenticated using (public.is_admin()) with check (public.is_admin());
revoke all on public.reglas_popup_comercial from public, anon, authenticated;
grant select, insert, update, delete on public.reglas_popup_comercial to authenticated;
grant all on public.reglas_popup_comercial to service_role;

-- BACKEND_ONLY: no Data API grants.
alter table public.efectos_servicio enable row level security;
alter table public.valor_usuario_resumen enable row level security;
revoke all on public.efectos_servicio, public.valor_usuario_resumen from public, anon, authenticated;
grant all on public.efectos_servicio, public.valor_usuario_resumen to service_role;

commit;
