-- Database audit (2026-08-03): 51 RLS policies called auth.uid() directly in USING/WITH CHECK,
-- causing per-row re-evaluation instead of once per query (Supabase advisor: auth_rls_initplan).
-- Rewritten to wrap every auth.uid() call in `(select auth.uid())`. Logic is unchanged --
-- verified by diffing each policy's expression against pg_policies before/after.

alter policy abandonos_examen_insert_propio on public.abandonos_examen with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy abandonos_examen_select_staff_o_propio on public.abandonos_examen using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy activaciones_select_propio on public.activaciones_servicio_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy carteras_select_propio on public.carteras_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy cola_repaso_select_propio on public.cola_repaso_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy cola_repaso_update_propio on public.cola_repaso_usuario using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy comentarios_oposired_insert_auth on public.comentarios_oposired with check ((usuario_id = (select auth.uid())));
alter policy comentarios_oposired_update_propios on public.comentarios_oposired using (((usuario_id = (select auth.uid())) OR es_moderador_o_admin())) with check (((usuario_id = (select auth.uid())) OR es_moderador_o_admin()));
alter policy compras_select_propio on public.compras_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy configuraciones_usuario_todo_propio on public.configuraciones_usuario using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy contenido_desbloqueado_select_propio on public.contenido_desbloqueado_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy conversaciones_soporte_todo_propio on public.conversaciones_soporte using (((usuario_id = (select auth.uid())) OR es_moderador_o_admin())) with check (((usuario_id = (select auth.uid())) OR es_moderador_o_admin()));
alter policy errores_app_insert_auth on public.errores_app with check (((usuario_id = (select auth.uid())) OR (usuario_id IS NULL)));
alter policy estadisticas_select_propio on public.estadisticas_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy estadisticas_usuario_etiqueta_select_propio on public.estadisticas_usuario_etiqueta using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy estadisticas_usuario_pregunta_select_propio on public.estadisticas_usuario_pregunta using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy estado_premium_select_propio on public.estado_premium_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy eventos_app_insert_auth on public.eventos_app with check (((usuario_id = (select auth.uid())) OR (usuario_id IS NULL)));
alter policy eventos_conversion_insert_auth on public.eventos_conversion_premium with check (((usuario_id = (select auth.uid())) OR (usuario_id IS NULL)));
alter policy feedback_examen_insert_auth on public.feedback_examen with check (((usuario_id = (select auth.uid())) OR (usuario_id IS NULL)));
alter policy feedback_examen_select_staff_o_propio on public.feedback_examen using ((es_moderador_o_admin() OR (usuario_id = (select auth.uid()))));
alter policy feedback_pregunta_insert_auth on public.feedback_pregunta with check (((usuario_id = (select auth.uid())) OR (usuario_id IS NULL)));
alter policy feedback_pregunta_select_staff on public.feedback_pregunta using ((es_moderador_o_admin() OR (usuario_id = (select auth.uid()))));
alter policy intentos_examen_todo_propio on public.intentos_examen using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy invitaciones_referido_insert_propias on public.invitaciones_referido with check (((usuario_origen_id = (select auth.uid())) AND (usuario_destino_id IS NULL) AND (estado = 'pendiente'::text) AND (recompensa_entregada = false) AND (aceptado_en IS NULL)));
alter policy invitaciones_referido_select_propias on public.invitaciones_referido using (((usuario_origen_id = (select auth.uid())) OR (usuario_destino_id = (select auth.uid())) OR es_admin()));
alter policy mensajes_soporte_insert_propios on public.mensajes_soporte with check ((es_moderador_o_admin() OR (EXISTS ( SELECT 1 FROM conversaciones_soporte cs WHERE ((cs.id = mensajes_soporte.conversacion_id) AND (cs.usuario_id = (select auth.uid())))))));
alter policy mensajes_soporte_select_propio on public.mensajes_soporte using ((EXISTS ( SELECT 1 FROM conversaciones_soporte cs WHERE ((cs.id = mensajes_soporte.conversacion_id) AND ((cs.usuario_id = (select auth.uid())) OR es_moderador_o_admin())))));
alter policy metricas_rendimiento_insert_auth on public.metricas_rendimiento_app with check (((usuario_id = (select auth.uid())) OR (usuario_id IS NULL)));
alter policy perfiles_usuario_insert_propio on public.perfiles_usuario with check ((usuario_id = (select auth.uid())));
alter policy perfiles_usuario_select_propio on public.perfiles_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy perfiles_usuario_update_propio on public.perfiles_usuario using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy favoritas_todo_propio on public.preguntas_favoritas_usuario using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy publicaciones_oposired_insert_auth on public.publicaciones_oposired with check ((usuario_id = (select auth.uid())));
alter policy publicaciones_oposired_update_propias on public.publicaciones_oposired using (((usuario_id = (select auth.uid())) OR es_moderador_o_admin())) with check (((usuario_id = (select auth.uid())) OR es_moderador_o_admin()));
alter policy rachas_select_propio on public.rachas_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy reacciones_oposired_todo_propias on public.reacciones_oposired using (((usuario_id = (select auth.uid())) OR es_moderador_o_admin())) with check (((usuario_id = (select auth.uid())) OR es_moderador_o_admin()));
alter policy recomendaciones_select_propio on public.recomendaciones_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy recomendaciones_update_propio on public.recomendaciones_usuario using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy recursos_pregunta_select_premium on public.recursos_pregunta using (((activo = true) AND (EXISTS ( SELECT 1 FROM estado_premium_usuario epu WHERE ((epu.usuario_id = (select auth.uid())) AND (epu.es_premium = true)))) AND (EXISTS ( SELECT 1 FROM ((preguntas p JOIN preguntas_examen pe ON ((pe.pregunta_id = p.id))) JOIN examenes e ON ((e.id = pe.examen_id))) WHERE ((p.id = recursos_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.tipo_acceso = 'premium'::text) AND (e.tipo_examen = 'inmersivo'::text))))));
alter policy registros_reto_todo_propio on public.registros_reto_diario using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy reportes_contenido_insert_auth on public.reportes_contenido with check ((reportado_por = (select auth.uid())));
alter policy respuestas_intento_todo_propio on public.respuestas_intento using ((EXISTS ( SELECT 1 FROM intentos_examen ie WHERE ((ie.id = respuestas_intento.intento_id) AND ((ie.usuario_id = (select auth.uid())) OR es_admin()))))) with check ((EXISTS ( SELECT 1 FROM intentos_examen ie WHERE ((ie.id = respuestas_intento.intento_id) AND ((ie.usuario_id = (select auth.uid())) OR es_admin())))));
alter policy resumen_usuario_semanal_select_propio on public.resumen_usuario_semanal using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy roles_usuario_select_propio on public.roles_usuario using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy sesiones_estudio_todo_propio on public.sesiones_estudio using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy suscripciones_select_propio on public.suscripciones using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy tokens_dispositivo_todo_propio on public.tokens_dispositivo using (((usuario_id = (select auth.uid())) OR es_admin())) with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy transacciones_select_propio on public.transacciones_cartera using (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy uso_recursos_insert_propio on public.uso_recursos_pregunta with check (((usuario_id = (select auth.uid())) OR es_admin()));
alter policy uso_recursos_select_staff_o_propio on public.uso_recursos_pregunta using (((usuario_id = (select auth.uid())) OR es_admin()));
