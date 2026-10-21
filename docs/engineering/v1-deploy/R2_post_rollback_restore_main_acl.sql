-- V1 deploy · R-2 · RESTAURACION EXACTA del estado de MAIN tras los ROLLBACK de 20261022101200, 20261022101600 y 20261022101900.
-- HALLAZGO del ensayo V1-DEPLOY-REHEARSAL (2026-10-21): los bloques ROLLBACK de esas tres migraciones NO devuelven la forma de
-- MAIN (la escribieron contra los stubs main_shape_subset_extra_engagement.sql, no contra la captura real):
--   * 101900 da `grant all` a anon y authenticated en publicaciones/comentarios/reacciones/categorias_oposired (MAIN: anon solo
--     SELECT/MAINTAIN; authenticated sin TRUNCATE/REFERENCES/TRIGGER). TRUNCATE ignora RLS: anon podria vaciar el foro. P1.
--   * 101900 recrea 5 politicas con `auth.uid()` en vez de `(select auth.uid())` (misma semantica, peor plan).
--   * 101200 da SELECT a anon en estado_competitivo_usuario / estado_opositester_usuario (MAIN: solo authenticated) y quita
--     MAINTAIN; el texto de ranking_liga_select_auth_ligas_activas y el orden del ACL de recalcular_estadisticas_* cambian (cosmetico).
--   * 101600 crea la politica estadisticas_globales_pregunta_select, que MAIN NO tiene.
-- Este fichero se ejecuta (P, ESCRITURA, mismo GO que el ROLLBACK) JUSTO DESPUES del ROLLBACK de 20261022101200 (el ultimo de los
-- tres en orden inverso). Sentencias copiadas VERBATIM de la captura READ-ONLY de MAIN del 2026-10-14. Probado en el ensayo:
-- tras ROLLBACK + este fichero, la huella del catalogo es IDENTICA a la de MAIN (ver rehearsal.log).
-- Correccion definitiva (Bot, propietario de las migraciones): arreglar esos bloques ROLLBACK y regenerar los stubs del gate desde la captura.
begin;
set local lock_timeout = '5s';
drop policy if exists estadisticas_globales_pregunta_select on public.estadisticas_globales_pregunta;
REVOKE ALL ON TABLE public.categorias_oposired FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.categorias_oposired TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.categorias_oposired TO anon, authenticated;
REVOKE ALL ON TABLE public.comentarios_oposired FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.comentarios_oposired TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.comentarios_oposired TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.comentarios_oposired TO anon;
REVOKE ALL ON TABLE public.estado_competitivo_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.estado_competitivo_usuario TO service_role;
GRANT SELECT ON TABLE public.estado_competitivo_usuario TO authenticated;
REVOKE ALL ON TABLE public.estado_opositester_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.estado_opositester_usuario TO service_role;
GRANT SELECT ON TABLE public.estado_opositester_usuario TO authenticated;
REVOKE ALL ON TABLE public.publicaciones_oposired FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.publicaciones_oposired TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.publicaciones_oposired TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.publicaciones_oposired TO anon;
REVOKE ALL ON TABLE public.ranking_liga FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.ranking_liga TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.ranking_liga TO anon, authenticated;
REVOKE ALL ON TABLE public.reacciones_oposired FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.reacciones_oposired TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.reacciones_oposired TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.reacciones_oposired TO anon;
REVOKE ALL ON TABLE public.registros_reto_diario FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.registros_reto_diario TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.registros_reto_diario TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.registros_reto_diario TO anon;
REVOKE ALL ON FUNCTION public.recalcular_estadisticas_globales_etiquetas() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.recalcular_estadisticas_globales_etiquetas() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.recalcular_estadisticas_globales_preguntas() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.recalcular_estadisticas_globales_preguntas() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.recalcular_estadisticas_usuario_etiquetas(p_usuario_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.recalcular_estadisticas_usuario_globales(p_usuario_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.recalcular_estadisticas_usuario_preguntas(p_usuario_id uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.recalcular_estadisticas_usuario_etiquetas(p_usuario_id uuid) TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.recalcular_estadisticas_usuario_globales(p_usuario_id uuid) TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.recalcular_estadisticas_usuario_preguntas(p_usuario_id uuid) TO PUBLIC, anon, authenticated, service_role;
DROP POLICY IF EXISTS comentarios_oposired_insert_auth ON public.comentarios_oposired;
CREATE POLICY comentarios_oposired_insert_auth ON public.comentarios_oposired AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((usuario_id = ( SELECT auth.uid() AS uid)));
DROP POLICY IF EXISTS comentarios_oposired_update_propios ON public.comentarios_oposired;
CREATE POLICY comentarios_oposired_update_propios ON public.comentarios_oposired AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_moderador_o_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_moderador_o_admin()));
DROP POLICY IF EXISTS publicaciones_oposired_insert_auth ON public.publicaciones_oposired;
CREATE POLICY publicaciones_oposired_insert_auth ON public.publicaciones_oposired AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((usuario_id = ( SELECT auth.uid() AS uid)));
DROP POLICY IF EXISTS publicaciones_oposired_update_propias ON public.publicaciones_oposired;
CREATE POLICY publicaciones_oposired_update_propias ON public.publicaciones_oposired AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_moderador_o_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_moderador_o_admin()));
DROP POLICY IF EXISTS reacciones_oposired_todo_propias ON public.reacciones_oposired;
CREATE POLICY reacciones_oposired_todo_propias ON public.reacciones_oposired AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_moderador_o_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_moderador_o_admin()));
DROP POLICY IF EXISTS ranking_liga_select_auth_ligas_activas ON public.ranking_liga;
CREATE POLICY ranking_liga_select_auth_ligas_activas ON public.ranking_liga AS PERMISSIVE FOR SELECT TO authenticated USING (((EXISTS ( SELECT 1
   FROM public.ligas l
  WHERE ((l.id = ranking_liga.liga_id) AND (l.activa = true)))) OR public.es_admin()));
commit;
