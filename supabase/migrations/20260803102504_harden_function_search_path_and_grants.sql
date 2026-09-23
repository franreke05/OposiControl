-- Database audit (2026-08-03): 18 functions had no search_path pinned (Supabase advisor:
-- function_search_path_mutable). Verified every one is fully schema-qualified in its body
-- (all table references use `public.foo`, no bare unqualified names beyond built-ins), so
-- pinning search_path is behavior-preserving. Matches the search_path='public' convention
-- already used by this project's other SECURITY DEFINER functions (es_admin, current_usuario_id, etc).

alter function public.slugify(text) set search_path = 'public';
alter function public.safe_int(text) set search_path = 'public';
alter function public.safe_uuid(text) set search_path = 'public';
alter function public.handle_updated_at() set search_path = 'public';
alter function public.set_slug_from_nombre() set search_path = 'public';
alter function public.block_recurso_pregunta_for_rafagas() set search_path = 'public';
alter function public.establecer_actualizado_en() set search_path = 'public';
alter function public.validar_solucion_opcion_misma_pregunta() set search_path = 'public';
alter function public.validar_pregunta_examen_mismo_tipo() set search_path = 'public';
alter function public.validar_recurso_solo_pregunta_inmersiva() set search_path = 'public';
alter function public.validar_y_calcular_respuesta_intento() set search_path = 'public';
alter function public.preparar_intento_examen() set search_path = 'public';
alter function public.recalcular_estadisticas_usuario_preguntas(uuid) set search_path = 'public';
alter function public.recalcular_estadisticas_usuario_etiquetas(uuid) set search_path = 'public';
alter function public.recalcular_estadisticas_globales_preguntas() set search_path = 'public';
alter function public.recalcular_estadisticas_globales_etiquetas() set search_path = 'public';
alter function public.recalcular_estadisticas_usuario_globales(uuid) set search_path = 'public';
alter function public.sincronizar_estado_premium_usuario() set search_path = 'public';

-- Supabase advisor also flagged 4 SECURITY DEFINER functions as directly callable by
-- anon/authenticated. All 4 are trigger functions (RETURNS trigger) -- Postgres rejects
-- direct invocation of trigger functions via SQL/RPC regardless of grants, so the EXECUTE
-- grant was always dead surface area. fn_audit_row_change and ensure_wallet_for_usuario
-- have zero trigger attachments (confirmed via pg_trigger) -- fully unused. The other two
-- ARE live triggers (handle_new_auth_user on auth.users, sincronizar_estado_premium_usuario
-- on public.suscripciones) -- trigger firing does not require the firing role to hold
-- EXECUTE, so revoking the direct-call grant does not affect them.
revoke execute on function public.fn_audit_row_change() from anon, authenticated, public;
revoke execute on function public.ensure_wallet_for_usuario() from anon, authenticated, public;
revoke execute on function public.handle_new_auth_user() from anon, authenticated, public;
revoke execute on function public.sincronizar_estado_premium_usuario() from anon, authenticated, public;
