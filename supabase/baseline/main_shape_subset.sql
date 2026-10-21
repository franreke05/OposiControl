-- GENERADO por scripts/db-pg17-bootstrap-extract.mjs (no editar a mano; ver supabase/baseline/README.md).
-- Forma de MAIN (DDL de catalogo, SIN filas) de los objetos que tocan las migraciones pendientes, copiada verbatim de
-- la captura READ-ONLY de MAIN del gate local. NO es el baseline completo de MAIN.
-- Tablas (35): private.identity_backfill_report, public.abandonos_examen, public.acciones_moderacion, public.aceptaciones_legales, public.atribucion_usuario, public.auditoria_log, public.comunidades_autonomas, public.comunidades_objetivo_usuario, public.contenido_desbloqueado_usuario, public.documentos_legales, public.especialidades, public.estado_premium_usuario, public.etiquetas, public.etiquetas_pregunta, public.eventos_app, public.examenes, public.idempotency_keys, public.intentos_examen, public.lotes_importacion_preguntas, public.opciones_pregunta, public.perfiles_usuario, public.perfiles_usuario_detalle, public.preguntas, public.preguntas_examen, public.preguntas_intento, public.recursos_pregunta, public.reportes_contenido, public.respuestas_intento, public.resumen_intento_etiqueta, public.roles_app, public.roles_usuario, public.sesiones_estudio, public.solicitudes_eliminacion_cuenta, public.soluciones_pregunta, public.usuario
-- Funciones (49): private.app_congelar_preguntas_intento, private.guard_aceptaciones_append_only, private.guard_perfiles_estado_cuenta, private.guard_perfiles_server_owned, private.recalcular_total_preguntas_examen, private.slice1_int, private.slice1_lock_profile, private.slice1_profile_json, private.slice1_text, private.slice1_trim, private.slice1_uuid, private.tg_examenes_bloquear_cambio_tipo, private.tg_examenes_publicado_no_vacio, private.tg_examenes_total_preguntas_guard, private.tg_examenes_total_preguntas_insert, private.tg_intentos_examen_creado_por_comando_inmutable, private.tg_intentos_examen_especialidad_snapshot_inmutable, private.tg_preguntas_activa_total_preguntas, private.tg_preguntas_bloquear_cambio_tipo, private.tg_preguntas_codigo_pregunta, private.tg_preguntas_examen_total_preguntas, private.validate_perfiles_refs, public.admin_record_report_action, public.admin_role_code_canonical, public.admin_sync_roles, public.admin_sync_roles_audited, public.app_abandon_exam_attempt, public.app_aceptar_documentos_legales, public.app_actualizar_perfil, public.app_completar_onboarding, public.app_finish_exam_attempt, public.app_my_exams_collection, public.app_record_attempt_answer, public.app_registrar_revocacion_sesiones, public.app_solicitar_eliminacion_cuenta, public.app_start_exam_attempt, public.es_admin, public.es_moderador_o_admin, public.establecer_actualizado_en, public.handle_new_auth_user, public.idem_begin, public.preparar_intento_examen, public.record_answer, public.validar_pregunta_examen, public.validar_pregunta_examen_mismo_tipo, public.validar_recurso_inmersivo, public.validar_recurso_solo_pregunta_inmersiva, public.validar_solucion_opcion_misma_pregunta, public.validar_y_calcular_respuesta_intento
-- Secuencias (0): -
SET check_function_bodies = false;
SET search_path = public, extensions;
CREATE SCHEMA IF NOT EXISTS private;
CREATE SCHEMA IF NOT EXISTS public;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;
CREATE TABLE private.identity_backfill_report (id bigint GENERATED ALWAYS AS IDENTITY NOT NULL, run_id uuid NOT NULL, usuario_id uuid, legacy_usuario_id integer NOT NULL, field text NOT NULL, outcome text NOT NULL, detail text, reported_at timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.abandonos_examen (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, intento_id uuid, examen_id uuid, pregunta_id uuid, posicion_pregunta integer, tiempo_transcurrido_segundos integer, total_preguntas integer, motivo_detectado text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.acciones_moderacion (id uuid NOT NULL DEFAULT gen_random_uuid(), reporte_id uuid, moderador_id uuid, tipo_accion text NOT NULL, notas text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.aceptaciones_legales (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, documento_id uuid NOT NULL, tipo_documento text NOT NULL, version text NOT NULL, plataforma text NOT NULL, aceptado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.atribucion_usuario (usuario_id uuid NOT NULL, fuente text, canal text, campania text, referido_por uuid, codigo_referido text, plataforma text, creado_en timestamp with time zone NOT NULL DEFAULT now(), metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, install_referrer text);
CREATE TABLE public.auditoria_log (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, accion text NOT NULL, tabla_afectada text, registro_id uuid, antes jsonb, despues jsonb, ip text, user_agent text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.comunidades_autonomas (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, slug text NOT NULL, activa boolean NOT NULL DEFAULT true, posicion integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.comunidades_objetivo_usuario (usuario_id uuid NOT NULL, comunidad_id uuid NOT NULL, prioridad integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.contenido_desbloqueado_usuario (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, examen_id uuid, pregunta_id uuid, motivo text NOT NULL, valido_desde timestamp with time zone NOT NULL DEFAULT now(), valido_hasta timestamp with time zone, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.documentos_legales (id uuid NOT NULL DEFAULT gen_random_uuid(), tipo_documento text NOT NULL, version text NOT NULL, titulo text NOT NULL, contenido text NOT NULL, activo boolean NOT NULL DEFAULT false, publicado_en timestamp with time zone, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.especialidades (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, slug text NOT NULL, descripcion text, activa boolean NOT NULL DEFAULT true, posicion integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.estado_premium_usuario (usuario_id uuid NOT NULL, es_premium boolean NOT NULL DEFAULT false, estado_suscripcion text NOT NULL DEFAULT 'sin_suscripcion'::text, producto_codigo text, proveedor text, inicio_en timestamp with time zone, fin_en timestamp with time zone, renovacion_en timestamp with time zone, actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.etiquetas (id uuid NOT NULL DEFAULT gen_random_uuid(), nombre text NOT NULL, slug text NOT NULL, descripcion text, color_hex text, activa boolean NOT NULL DEFAULT true, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.etiquetas_pregunta (pregunta_id uuid NOT NULL, etiqueta_id uuid NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.eventos_app (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, sesion_id uuid, nombre_evento text NOT NULL, pantalla text, entidad_tipo text, entidad_id uuid, plataforma text, version_app text, idioma text, duracion_ms integer, propiedades jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), event_version integer NOT NULL DEFAULT 1, occurred_en timestamp with time zone NOT NULL DEFAULT now(), received_en timestamp with time zone NOT NULL DEFAULT now(), especialidad_id uuid, intento_id uuid, pregunta_id uuid, idempotency_key text NOT NULL);
CREATE TABLE public.examenes (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo_examen text NOT NULL, especialidad_id uuid NOT NULL, comunidad_id uuid, titulo text NOT NULL, descripcion text, tipo_examen text NOT NULL, tipo_acceso text NOT NULL, total_preguntas integer NOT NULL DEFAULT 0, duracion_minutos integer, anio integer, es_oficial boolean NOT NULL DEFAULT false, activo boolean NOT NULL DEFAULT false, publicado_en timestamp with time zone, creado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), comprable_con_tickets boolean NOT NULL DEFAULT false, precio_tickets integer, dias_acceso_compra integer NOT NULL DEFAULT 30, demo_disponible boolean NOT NULL DEFAULT false, requiere_plan_codigo text, creditos_inmersivos_necesarios integer NOT NULL DEFAULT 0, portada_storage_path text, portada_mime_type text);
CREATE TABLE public.idempotency_keys (id uuid NOT NULL DEFAULT gen_random_uuid(), actor_id uuid NOT NULL, scope text NOT NULL, idem_key text NOT NULL, request_fingerprint text NOT NULL, state text NOT NULL, response_status smallint, response_body jsonb, response_headers jsonb, locked_until timestamp with time zone, claim_token uuid NOT NULL DEFAULT gen_random_uuid(), created_at timestamp with time zone NOT NULL DEFAULT now(), completed_at timestamp with time zone, expires_at timestamp with time zone NOT NULL);
CREATE TABLE public.intentos_examen (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, examen_id uuid, estado text NOT NULL DEFAULT 'en_progreso'::text, iniciado_en timestamp with time zone NOT NULL DEFAULT now(), finalizado_en timestamp with time zone, duracion_segundos integer, total_preguntas integer NOT NULL DEFAULT 0, total_respondidas integer NOT NULL DEFAULT 0, total_correctas integer NOT NULL DEFAULT 0, puntuacion numeric(6,2), metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), tipo_examen_snapshot text, intento_numero_usuario integer, es_primer_intento_examen boolean NOT NULL DEFAULT false, cuenta_para_ranking boolean NOT NULL DEFAULT false, total_falladas integer NOT NULL DEFAULT 0, total_sin_responder integer NOT NULL DEFAULT 0, porcentaje_acierto numeric(5,2), puntuacion_base numeric(8,2), puntuacion_final numeric(8,2), opositickets_ganados integer NOT NULL DEFAULT 0, modo_estudio text, abandonado_en_pregunta_id uuid, abandonado_en_posicion integer, ultima_pregunta_posicion integer, calculado_en timestamp with time zone, origen_intento text NOT NULL DEFAULT 'examen'::text, motivo_fin text, especialidad_snapshot_id uuid, creado_por_comando text);
CREATE TABLE public.lotes_importacion_preguntas (id uuid NOT NULL DEFAULT gen_random_uuid(), examen_id uuid NOT NULL, subido_por uuid, nombre_archivo_original text NOT NULL, ruta_archivo_storage text, tipo_examen text NOT NULL, estado text NOT NULL DEFAULT 'pendiente'::text, total_detectadas integer NOT NULL DEFAULT 0, total_importadas integer NOT NULL DEFAULT 0, mensaje_error text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.opciones_pregunta (id uuid NOT NULL DEFAULT gen_random_uuid(), pregunta_id uuid NOT NULL, letra text NOT NULL, texto_opcion text NOT NULL, posicion integer NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.perfiles_usuario (usuario_id uuid NOT NULL, nombre_mostrar text, username text, avatar_url text, especialidad_principal_id uuid, comunidad_principal_id uuid, es_invitado boolean NOT NULL DEFAULT false, onboarding_completado boolean NOT NULL DEFAULT false, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), nombre text, apellidos text, estado_cuenta text NOT NULL DEFAULT 'activa'::text, perfil_intro_visto_en timestamp with time zone);
CREATE TABLE public.perfiles_usuario_detalle (usuario_id uuid NOT NULL, nombre_completo text, edad integer, ciudad text, estado_laboral text, centro_trabajo text, tiempo_disponible_estudio_minutos integer, franja_preferente_estudio text, fecha_examen_objetivo date, preferencias_horarias jsonb NOT NULL DEFAULT '{}'::jsonb, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.preguntas (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo_pregunta text NOT NULL, tipo_pregunta text NOT NULL, titulo text NOT NULL, fuente_texto text, dificultad text, activa boolean NOT NULL DEFAULT true, lote_importacion_id uuid, creado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.preguntas_examen (examen_id uuid NOT NULL, pregunta_id uuid NOT NULL, posicion integer NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), numero_en_examen integer NOT NULL, anulada boolean NOT NULL DEFAULT false, motivo_anulacion text);
CREATE TABLE public.preguntas_intento (id uuid NOT NULL DEFAULT gen_random_uuid(), intento_id uuid NOT NULL, pregunta_id uuid NOT NULL, posicion integer NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), estado_final text);
CREATE TABLE public.recursos_pregunta (id uuid NOT NULL DEFAULT gen_random_uuid(), pregunta_id uuid NOT NULL, codigo_recurso text NOT NULL, tipo_recurso text NOT NULL, titulo text, descripcion text, bucket_storage text DEFAULT 'recursos-pregunta'::text, ruta_storage text, url_externa text, contenido_texto text, posicion integer NOT NULL DEFAULT 1, activo boolean NOT NULL DEFAULT true, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.reportes_contenido (id uuid NOT NULL DEFAULT gen_random_uuid(), reportado_por uuid, tipo_entidad text NOT NULL, entidad_id uuid NOT NULL, motivo text NOT NULL, descripcion text, estado text NOT NULL DEFAULT 'pendiente'::text, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), tickets_otorgados integer NOT NULL DEFAULT 0, recompensa_entregada boolean NOT NULL DEFAULT false, revisado_por uuid, revisado_en timestamp with time zone, accion_pendiente boolean NOT NULL DEFAULT false);
CREATE TABLE public.respuestas_intento (id uuid NOT NULL DEFAULT gen_random_uuid(), intento_id uuid NOT NULL, pregunta_id uuid NOT NULL, opcion_seleccionada_id uuid, es_correcta boolean, respondida_en timestamp with time zone, tiempo_respuesta_segundos integer, creada_en timestamp with time zone NOT NULL DEFAULT now(), usuario_id uuid, examen_id uuid, opcion_correcta_id uuid, posicion_pregunta integer, etiqueta_principal_id uuid, cuenta_para_ranking boolean NOT NULL DEFAULT false, es_primer_intento_pregunta boolean NOT NULL DEFAULT false, nivel_confianza text, dificultad_percibida text, tipo_error_usuario text, marcada_para_repasar boolean NOT NULL DEFAULT false, respondida_al_azar boolean NOT NULL DEFAULT false, cambio_respuesta boolean NOT NULL DEFAULT false, tiempo_visualizacion_recursos_segundos integer, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, primera_opcion_id uuid, primera_es_correcta boolean, revision integer NOT NULL DEFAULT 1, actualizada_en timestamp with time zone, tiempo_respuesta_ms integer);
CREATE TABLE public.resumen_intento_etiqueta (intento_id uuid NOT NULL, etiqueta_id uuid NOT NULL, total_preguntas integer NOT NULL DEFAULT 0, correctas integer NOT NULL DEFAULT 0, falladas integer NOT NULL DEFAULT 0, sin_responder integer NOT NULL DEFAULT 0, tiempo_total_segundos integer NOT NULL DEFAULT 0, tiempo_medio_segundos numeric NOT NULL DEFAULT 0, porcentaje_acierto numeric NOT NULL DEFAULT 0);
CREATE TABLE public.roles_app (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, descripcion text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.roles_usuario (usuario_id uuid NOT NULL, rol_id uuid NOT NULL, asignado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.sesiones_estudio (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, sesion_cliente_id text, modo_estudio text, iniciada_en timestamp with time zone NOT NULL DEFAULT now(), finalizada_en timestamp with time zone, duracion_segundos integer, preguntas_respondidas integer NOT NULL DEFAULT 0, respuestas_correctas integer NOT NULL DEFAULT 0, respuestas_falladas integer NOT NULL DEFAULT 0, opositickets_ganados integer NOT NULL DEFAULT 0, plataforma text, version_app text, idioma text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.solicitudes_eliminacion_cuenta (usuario_id uuid NOT NULL, solicitada_en timestamp with time zone NOT NULL DEFAULT now(), estado text NOT NULL DEFAULT 'solicitada'::text, revocacion_sesiones text NOT NULL DEFAULT 'pendiente'::text, paso_ejecucion smallint NOT NULL DEFAULT 1, reintentos integer NOT NULL DEFAULT 0, ultimo_error_codigo text, actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.soluciones_pregunta (id uuid NOT NULL DEFAULT gen_random_uuid(), pregunta_id uuid NOT NULL, opcion_correcta_id uuid NOT NULL, razonamiento text NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.usuario (id integer GENERATED ALWAYS AS IDENTITY NOT NULL, auth_user_id uuid, email character varying, nombre character varying, created_at timestamp with time zone NOT NULL DEFAULT now(), updated_at timestamp with time zone NOT NULL DEFAULT now(), deleted_at timestamp with time zone, apellidos character varying(100), username character varying(30), especialidad_registro character varying(100), perfil_completo boolean NOT NULL DEFAULT false, perfil_completado_at timestamp with time zone);
ALTER TABLE private.identity_backfill_report ADD CONSTRAINT identity_backfill_report_pkey PRIMARY KEY (id);
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_pkey PRIMARY KEY (id);
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_pkey PRIMARY KEY (id);
ALTER TABLE public.aceptaciones_legales ADD CONSTRAINT aceptaciones_legales_pkey PRIMARY KEY (id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.auditoria_log ADD CONSTRAINT auditoria_log_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_pkey PRIMARY KEY (usuario_id, comunidad_id);
ALTER TABLE public.contenido_desbloqueado_usuario ADD CONSTRAINT contenido_desbloqueado_usuario_pkey PRIMARY KEY (id);
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_pkey PRIMARY KEY (id);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_pkey PRIMARY KEY (id);
ALTER TABLE public.estado_premium_usuario ADD CONSTRAINT estado_premium_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.etiquetas ADD CONSTRAINT etiquetas_pkey PRIMARY KEY (id);
ALTER TABLE public.etiquetas_pregunta ADD CONSTRAINT etiquetas_pregunta_pkey PRIMARY KEY (pregunta_id, etiqueta_id);
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_pkey PRIMARY KEY (id);
ALTER TABLE public.examenes ADD CONSTRAINT examenes_pkey PRIMARY KEY (id);
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (id);
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_pkey PRIMARY KEY (id);
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_pkey PRIMARY KEY (id);
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pkey PRIMARY KEY (id);
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_pkey PRIMARY KEY (id);
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_pkey PRIMARY KEY (examen_id, pregunta_id);
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_pkey PRIMARY KEY (id);
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_pkey PRIMARY KEY (id);
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_pkey PRIMARY KEY (id);
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_pkey PRIMARY KEY (id);
ALTER TABLE public.resumen_intento_etiqueta ADD CONSTRAINT resumen_intento_etiqueta_pkey PRIMARY KEY (intento_id, etiqueta_id);
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_pkey PRIMARY KEY (id);
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_pkey PRIMARY KEY (usuario_id, rol_id);
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_pkey PRIMARY KEY (id);
ALTER TABLE public.solicitudes_eliminacion_cuenta ADD CONSTRAINT solicitudes_eliminacion_cuenta_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_pkey PRIMARY KEY (id);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_pkey PRIMARY KEY (id);
ALTER TABLE public.aceptaciones_legales ADD CONSTRAINT aceptaciones_legales_usuario_documento_key UNIQUE (usuario_id, documento_id);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_codigo_key UNIQUE (codigo);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_slug_key UNIQUE (slug);
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_tipo_documento_version_key UNIQUE (tipo_documento, version);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_codigo_key UNIQUE (codigo);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_slug_key UNIQUE (slug);
ALTER TABLE public.etiquetas ADD CONSTRAINT etiquetas_slug_key UNIQUE (slug);
ALTER TABLE public.examenes ADD CONSTRAINT examenes_codigo_examen_key UNIQUE (codigo_examen);
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_actor_scope_key_uq UNIQUE (actor_id, scope, idem_key);
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pregunta_id_letra_key UNIQUE (pregunta_id, letra);
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pregunta_id_posicion_key UNIQUE (pregunta_id, posicion);
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_username_key UNIQUE (username);
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_codigo_pregunta_key UNIQUE (codigo_pregunta);
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_examen_id_posicion_key UNIQUE (examen_id, posicion) DEFERRABLE;
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_numero_unico UNIQUE (examen_id, numero_en_examen);
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_intento_id_posicion_key UNIQUE (intento_id, posicion);
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_intento_id_pregunta_id_key UNIQUE (intento_id, pregunta_id);
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_codigo_recurso_key UNIQUE (codigo_recurso);
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_intento_id_pregunta_id_key UNIQUE (intento_id, pregunta_id);
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_codigo_key UNIQUE (codigo);
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_pregunta_id_key UNIQUE (pregunta_id);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_auth_user_id_key UNIQUE (auth_user_id);
ALTER TABLE private.identity_backfill_report ADD CONSTRAINT identity_backfill_report_field_check CHECK ((field = ANY (ARRAY['row'::text, 'profile'::text, 'nombre'::text, 'apellidos'::text, 'username'::text, 'especialidad_principal_id'::text, 'onboarding_completado'::text])));
ALTER TABLE private.identity_backfill_report ADD CONSTRAINT identity_backfill_report_outcome_check CHECK ((outcome = ANY (ARRAY['orphan_no_auth_user'::text, 'orphan_unknown_auth_user'::text, 'orphan_deleted'::text, 'profile_created'::text, 'filled'::text, 'conflict_kept_existing'::text, 'skipped_username_collision'::text, 'specialty_unmatched'::text, 'specialty_ambiguous'::text])));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_metadatos_max CHECK ((pg_column_size(metadatos) <= 16384)) NOT VALID;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_motivo_detectado_check CHECK (((motivo_detectado IS NULL) OR (motivo_detectado = ANY (ARRAY['salida_app'::text, 'cerrar_examen'::text, 'timeout'::text, 'error_tecnico'::text, 'desconocido'::text]))));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_posicion_pregunta_check CHECK (((posicion_pregunta IS NULL) OR (posicion_pregunta > 0)));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_tiempo_transcurrido_segundos_check CHECK (((tiempo_transcurrido_segundos IS NULL) OR (tiempo_transcurrido_segundos >= 0)));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_total_preguntas_check CHECK (((total_preguntas IS NULL) OR (total_preguntas >= 0)));
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_tipo_accion_check CHECK ((tipo_accion = ANY (ARRAY['ocultar'::text, 'restaurar'::text, 'eliminar'::text, 'advertir'::text, 'bloquear_usuario'::text, 'descartar'::text])));
ALTER TABLE public.aceptaciones_legales ADD CONSTRAINT aceptaciones_legales_plataforma_chk CHECK ((plataforma = ANY (ARRAY['android'::text, 'ios'::text])));
ALTER TABLE public.aceptaciones_legales ADD CONSTRAINT aceptaciones_legales_tipo_chk CHECK ((tipo_documento = ANY (ARRAY['privacidad'::text, 'terminos'::text, 'cookies'::text, 'aviso_legal'::text])));
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_plataforma_chk CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text])))) NOT VALID;
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_texto_len_chk CHECK (((char_length(fuente) <= 500) AND (char_length(canal) <= 500) AND (char_length(campania) <= 500) AND (char_length(install_referrer) <= 500) AND (char_length(codigo_referido) <= 64))) NOT VALID;
ALTER TABLE public.contenido_desbloqueado_usuario ADD CONSTRAINT contenido_desbloqueado_objetivo_chk CHECK (((examen_id IS NOT NULL) OR (pregunta_id IS NOT NULL)));
ALTER TABLE public.contenido_desbloqueado_usuario ADD CONSTRAINT contenido_desbloqueado_usuario_motivo_check CHECK ((motivo = ANY (ARRAY['premium'::text, 'compra'::text, 'regalo'::text, 'admin'::text, 'promocion'::text])));
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_tipo_documento_check CHECK ((tipo_documento = ANY (ARRAY['privacidad'::text, 'terminos'::text, 'cookies'::text, 'aviso_legal'::text])));
ALTER TABLE public.estado_premium_usuario ADD CONSTRAINT estado_premium_usuario_estado_suscripcion_check CHECK ((estado_suscripcion = ANY (ARRAY['sin_suscripcion'::text, 'activa'::text, 'periodo_prueba'::text, 'pausada'::text, 'cancelada'::text, 'caducada'::text])));
ALTER TABLE public.estado_premium_usuario ADD CONSTRAINT estado_premium_usuario_proveedor_check CHECK (((proveedor IS NULL) OR (proveedor = ANY (ARRAY['google_play'::text, 'app_store'::text, 'stripe'::text, 'manual'::text]))));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_duracion_ms_check CHECK (((duracion_ms IS NULL) OR (duracion_ms >= 0)));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_event_version_positive CHECK ((event_version > 0));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_idempotency_key_non_blank CHECK ((length(btrim(idempotency_key)) > 0));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_plataforma_check CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text]))));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_canonical_v2_tipo_examen_check CHECK ((tipo_examen = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_codigo_examen_no_vacio_chk CHECK ((codigo_examen ~ '[^[:space:]]'::text));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_creditos_inmersivos_necesarios_check CHECK ((creditos_inmersivos_necesarios >= 0));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_dias_acceso_compra_check CHECK ((dias_acceso_compra > 0));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_duracion_minutos_check CHECK (((duracion_minutos IS NULL) OR (duracion_minutos > 0)));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_portada_coherente_chk CHECK ((((portada_storage_path IS NULL) AND (portada_mime_type IS NULL)) OR ((portada_storage_path ~ (('^exam-covers/'::text || (id)::text) || '/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|jpeg|png|webp|gif)$'::text)) AND (portada_mime_type = ANY (ARRAY['image/jpeg'::text, 'image/png'::text, 'image/webp'::text, 'image/gif'::text])))));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_precio_tickets_check CHECK (((precio_tickets IS NULL) OR (precio_tickets >= 0)));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_tipo_acceso_check CHECK ((tipo_acceso = ANY (ARRAY['gratuito'::text, 'premium'::text])));
ALTER TABLE public.examenes ADD CONSTRAINT examenes_total_preguntas_check CHECK ((total_preguntas >= 0));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_completed_chk CHECK (((state <> 'completed'::text) OR ((response_status IS NOT NULL) AND (completed_at IS NOT NULL))));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_idem_key_check CHECK ((idem_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'::text));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_request_fingerprint_check CHECK ((request_fingerprint ~ '^[0-9a-f]{64}$'::text));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_response_status_check CHECK (((response_status >= 200) AND (response_status <= 299)));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_scope_check CHECK (((char_length(scope) >= 1) AND (char_length(scope) <= 200)));
ALTER TABLE public.idempotency_keys ADD CONSTRAINT idempotency_keys_state_check CHECK ((state = ANY (ARRAY['in_progress'::text, 'completed'::text])));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_abandonado_en_posicion_check CHECK (((abandonado_en_posicion IS NULL) OR (abandonado_en_posicion > 0)));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_canonical_v2_tipo_examen_snapshot_check CHECK ((tipo_examen_snapshot = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_creado_por_comando_check CHECK (((creado_por_comando IS NULL) OR (creado_por_comando = 'app_start_exam_attempt'::text)));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_duracion_segundos_check CHECK (((duracion_segundos IS NULL) OR (duracion_segundos >= 0)));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_estado_check CHECK ((estado = ANY (ARRAY['en_progreso'::text, 'finalizado'::text, 'abandonado'::text])));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_modo_estudio_check CHECK (((modo_estudio IS NULL) OR (modo_estudio = ANY (ARRAY['examen'::text, 'rafaga'::text, 'repaso_fallos'::text, 'repaso_inteligente'::text, 'entrenamiento'::text]))));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_motivo_fin_check CHECK (((motivo_fin IS NULL) OR (motivo_fin = ANY (ARRAY['USER'::text, 'TIMEOUT'::text]))));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_opositickets_ganados_check CHECK ((opositickets_ganados >= 0));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_origen_check CHECK ((origen_intento = ANY (ARRAY['examen'::text, 'repaso_random'::text])));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_origen_examen_check CHECK ((((origen_intento = 'examen'::text) AND (examen_id IS NOT NULL)) OR ((origen_intento = 'repaso_random'::text) AND (examen_id IS NULL))));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_total_correctas_check CHECK ((total_correctas >= 0));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_total_falladas_check CHECK ((total_falladas >= 0));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_total_preguntas_check CHECK ((total_preguntas >= 0));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_total_respondidas_check CHECK ((total_respondidas >= 0));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_total_sin_responder_check CHECK ((total_sin_responder >= 0));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_ultima_pregunta_posicion_check CHECK (((ultima_pregunta_posicion IS NULL) OR (ultima_pregunta_posicion > 0)));
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_canonical_v2_tipo_examen_check CHECK ((tipo_examen = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])));
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_estado_check CHECK ((estado = ANY (ARRAY['pendiente'::text, 'parseado'::text, 'revisado'::text, 'importado'::text, 'fallido'::text])));
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_total_detectadas_check CHECK ((total_detectadas >= 0));
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_total_importadas_check CHECK ((total_importadas >= 0));
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_letra_check CHECK ((letra = ANY (ARRAY['A'::text, 'B'::text, 'C'::text, 'D'::text])));
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_posicion_check CHECK (((posicion >= 1) AND (posicion <= 4)));
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_posicion_letra_chk CHECK ((posicion = (ascii(letra) - 64)));
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_apellidos_len_chk CHECK (((apellidos IS NULL) OR (char_length(apellidos) <= 100)));
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_avatar_url_max CHECK ((char_length(COALESCE(avatar_url, ''::text)) <= 1000)) NOT VALID;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_estado_cuenta_chk CHECK ((estado_cuenta = ANY (ARRAY['activa'::text, 'bloqueada'::text, 'eliminacion_pendiente'::text])));
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_nombre_len_chk CHECK (((nombre IS NULL) OR (char_length(nombre) <= 100)));
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_nombre_mostrar_max CHECK ((char_length(COALESCE(nombre_mostrar, ''::text)) <= 120)) NOT VALID;
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_edad_check CHECK (((edad IS NULL) OR (edad >= 14)));
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_canonical_v2_tipo_pregunta_check CHECK ((tipo_pregunta = ANY (ARRAY['rafaga'::text, 'razonada'::text, 'inmersiva'::text])));
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_dificultad_check CHECK (((dificultad IS NULL) OR (dificultad = ANY (ARRAY['facil'::text, 'media'::text, 'dificil'::text]))));
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_anulada_motivo_chk CHECK (((motivo_anulacion IS NULL) OR (anulada AND (btrim(motivo_anulacion) <> ''::text) AND (length(motivo_anulacion) <= 500))));
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_numero_check CHECK (((numero_en_examen >= 1) AND (numero_en_examen <= 200)));
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_posicion_check CHECK (((posicion >= 1) AND (posicion <= 200)));
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_estado_final_check CHECK (((estado_final IS NULL) OR (estado_final = ANY (ARRAY['CORRECT'::text, 'WRONG'::text, 'SKIPPED'::text, 'UNANSWERED'::text, 'ANNULLED'::text]))));
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_posicion_check CHECK ((posicion > 0));
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_posicion_check CHECK ((posicion > 0));
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_tipo_recurso_check CHECK ((tipo_recurso = ANY (ARRAY['imagen'::text, 'audio'::text, 'video'::text, 'texto'::text, 'pdf'::text, 'enlace'::text])));
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_ubicacion_chk CHECK (((ruta_storage IS NOT NULL) OR (url_externa IS NOT NULL) OR (contenido_texto IS NOT NULL)));
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_ubicacion_formato_chk CHECK ((((url_externa IS NULL) OR (url_externa ~* '^https?://[^[:space:]]+$'::text)) AND ((ruta_storage IS NULL) OR ((COALESCE(bucket_storage, ''::text) = 'recursos-pregunta'::text) AND (ruta_storage ~ '^preguntas/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{2,4}$'::text) AND (split_part(ruta_storage, '/'::text, 2) = (pregunta_id)::text) AND (split_part(split_part(ruta_storage, '/'::text, 3), '.'::text, 1) = (id)::text)))));
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_ubicacion_por_tipo_chk CHECK (
CASE
    WHEN (tipo_recurso = 'texto'::text) THEN ((contenido_texto IS NOT NULL) AND (ruta_storage IS NULL) AND (url_externa IS NULL))
    WHEN (tipo_recurso = 'enlace'::text) THEN ((url_externa IS NOT NULL) AND (ruta_storage IS NULL))
    ELSE ((ruta_storage IS NOT NULL) AND (url_externa IS NULL))
END);
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_descripcion_longitud_check CHECK (((descripcion IS NULL) OR (char_length(descripcion) <= 2000)));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_estado_check CHECK ((estado = ANY (ARRAY['pendiente'::text, 'revisado'::text, 'resuelto'::text, 'descartado'::text])));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_motivo_longitud_check CHECK ((char_length(motivo) <= 2000));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_tipo_entidad_check CHECK ((tipo_entidad = ANY (ARRAY['pregunta'::text, 'recurso_pregunta'::text, 'opositeca_item'::text, 'publicacion_oposired'::text, 'comentario_oposired'::text, 'usuario'::text])));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_dificultad_percibida_check CHECK (((dificultad_percibida IS NULL) OR (dificultad_percibida = ANY (ARRAY['facil'::text, 'normal'::text, 'dificil'::text, 'muy_dificil'::text]))));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_first_response_user_not_null CHECK (((NOT es_primer_intento_pregunta) OR (usuario_id IS NOT NULL)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_nivel_confianza_check CHECK (((nivel_confianza IS NULL) OR (nivel_confianza = ANY (ARRAY['seguro'::text, 'duda'::text, 'azar'::text]))));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_posicion_pregunta_check CHECK (((posicion_pregunta IS NULL) OR (posicion_pregunta > 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_ranking_requires_first_response CHECK (((NOT cuenta_para_ranking) OR es_primer_intento_pregunta));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_revision_check CHECK ((revision >= 1));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tiempo_respuesta_ms_check CHECK (((tiempo_respuesta_ms IS NULL) OR (tiempo_respuesta_ms >= 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tiempo_respuesta_segundos_check CHECK (((tiempo_respuesta_segundos IS NULL) OR (tiempo_respuesta_segundos >= 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tiempo_visualizacion_recursos_segundos_check CHECK (((tiempo_visualizacion_recursos_segundos IS NULL) OR (tiempo_visualizacion_recursos_segundos >= 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tipo_error_usuario_check CHECK (((tipo_error_usuario IS NULL) OR (tipo_error_usuario = ANY (ARRAY['no_sabia_concepto'::text, 'confundio_opciones'::text, 'leyo_mal'::text, 'falto_tiempo'::text, 'azar'::text, 'otro'::text]))));
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_codigo_check CHECK ((codigo = ANY (ARRAY['superadmin'::text, 'admin'::text, 'moderador'::text, 'usuario'::text])));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_duracion_segundos_check CHECK (((duracion_segundos IS NULL) OR (duracion_segundos >= 0)));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_metadatos_max CHECK ((pg_column_size(metadatos) <= 16384)) NOT VALID;
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_modo_estudio_check CHECK (((modo_estudio IS NULL) OR (modo_estudio = ANY (ARRAY['examen'::text, 'rafaga'::text, 'repaso_fallos'::text, 'repaso_inteligente'::text, 'opositeca'::text, 'otro'::text]))));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_opositickets_ganados_check CHECK ((opositickets_ganados >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_plataforma_check CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text]))));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_preguntas_respondidas_check CHECK ((preguntas_respondidas >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_respuestas_correctas_check CHECK ((respuestas_correctas >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_respuestas_falladas_check CHECK ((respuestas_falladas >= 0));
ALTER TABLE public.solicitudes_eliminacion_cuenta ADD CONSTRAINT solicitudes_eliminacion_cuenta_estado_chk CHECK ((estado = ANY (ARRAY['solicitada'::text, 'en_ejecucion'::text, 'completada'::text, 'fallida'::text])));
ALTER TABLE public.solicitudes_eliminacion_cuenta ADD CONSTRAINT solicitudes_eliminacion_cuenta_revocacion_chk CHECK ((revocacion_sesiones = ANY (ARRAY['pendiente'::text, 'hecha'::text, 'fallida'::text])));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE SET NULL;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_moderador_id_fkey FOREIGN KEY (moderador_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_reporte_id_fkey FOREIGN KEY (reporte_id) REFERENCES public.reportes_contenido(id) ON DELETE SET NULL;
ALTER TABLE public.aceptaciones_legales ADD CONSTRAINT aceptaciones_legales_documento_id_fkey FOREIGN KEY (documento_id) REFERENCES public.documentos_legales(id);
ALTER TABLE public.aceptaciones_legales ADD CONSTRAINT aceptaciones_legales_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_referido_por_fkey FOREIGN KEY (referido_por) REFERENCES auth.users(id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.auditoria_log ADD CONSTRAINT auditoria_log_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_comunidad_id_fkey FOREIGN KEY (comunidad_id) REFERENCES public.comunidades_autonomas(id);
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.contenido_desbloqueado_usuario ADD CONSTRAINT contenido_desbloqueado_usuario_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.contenido_desbloqueado_usuario ADD CONSTRAINT contenido_desbloqueado_usuario_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.contenido_desbloqueado_usuario ADD CONSTRAINT contenido_desbloqueado_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.estado_premium_usuario ADD CONSTRAINT estado_premium_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.etiquetas_pregunta ADD CONSTRAINT etiquetas_pregunta_etiqueta_id_fkey FOREIGN KEY (etiqueta_id) REFERENCES public.etiquetas(id) ON DELETE CASCADE;
ALTER TABLE public.etiquetas_pregunta ADD CONSTRAINT etiquetas_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_sesion_id_fkey FOREIGN KEY (sesion_id) REFERENCES public.sesiones_estudio(id) ON DELETE SET NULL;
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.examenes ADD CONSTRAINT examenes_comunidad_id_fkey FOREIGN KEY (comunidad_id) REFERENCES public.comunidades_autonomas(id) ON DELETE SET NULL;
ALTER TABLE public.examenes ADD CONSTRAINT examenes_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.examenes ADD CONSTRAINT examenes_especialidad_id_fkey FOREIGN KEY (especialidad_id) REFERENCES public.especialidades(id) ON DELETE RESTRICT;
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_abandonado_en_pregunta_id_fkey FOREIGN KEY (abandonado_en_pregunta_id) REFERENCES public.preguntas(id) ON DELETE SET NULL;
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_especialidad_snapshot_id_fkey FOREIGN KEY (especialidad_snapshot_id) REFERENCES public.especialidades(id);
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_subido_por_fkey FOREIGN KEY (subido_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_comunidad_fk FOREIGN KEY (comunidad_principal_id) REFERENCES public.comunidades_autonomas(id) ON DELETE SET NULL;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_especialidad_fk FOREIGN KEY (especialidad_principal_id) REFERENCES public.especialidades(id) ON DELETE SET NULL;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_lote_importacion_id_fkey FOREIGN KEY (lote_importacion_id) REFERENCES public.lotes_importacion_preguntas(id) ON DELETE SET NULL;
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE RESTRICT;
ALTER TABLE public.recursos_pregunta ADD CONSTRAINT recursos_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_reportado_por_fkey FOREIGN KEY (reportado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_revisado_por_fkey FOREIGN KEY (revisado_por) REFERENCES auth.users(id);
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_etiqueta_principal_id_fkey FOREIGN KEY (etiqueta_principal_id) REFERENCES public.etiquetas(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_opcion_correcta_id_fkey FOREIGN KEY (opcion_correcta_id) REFERENCES public.opciones_pregunta(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_opcion_seleccionada_id_fkey FOREIGN KEY (opcion_seleccionada_id) REFERENCES public.opciones_pregunta(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_primera_opcion_id_fkey FOREIGN KEY (primera_opcion_id) REFERENCES public.opciones_pregunta(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.resumen_intento_etiqueta ADD CONSTRAINT resumen_intento_etiqueta_etiqueta_id_fkey FOREIGN KEY (etiqueta_id) REFERENCES public.etiquetas(id);
ALTER TABLE public.resumen_intento_etiqueta ADD CONSTRAINT resumen_intento_etiqueta_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_asignado_por_fkey FOREIGN KEY (asignado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_rol_id_fkey FOREIGN KEY (rol_id) REFERENCES public.roles_app(id) ON DELETE CASCADE;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.solicitudes_eliminacion_cuenta ADD CONSTRAINT solicitudes_eliminacion_cuenta_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id);
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_opcion_correcta_id_fkey FOREIGN KEY (opcion_correcta_id) REFERENCES public.opciones_pregunta(id) ON DELETE RESTRICT;
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
CREATE INDEX idx_abandonos_examen_examen ON public.abandonos_examen USING btree (examen_id, posicion_pregunta);
CREATE INDEX idx_abandonos_examen_intento_id ON public.abandonos_examen USING btree (intento_id);
CREATE INDEX idx_abandonos_examen_pregunta_id ON public.abandonos_examen USING btree (pregunta_id);
CREATE INDEX idx_abandonos_examen_usuario_id ON public.abandonos_examen USING btree (usuario_id);
CREATE INDEX idx_acciones_moderacion_moderador_id ON public.acciones_moderacion USING btree (moderador_id);
CREATE INDEX idx_acciones_moderacion_reporte_id ON public.acciones_moderacion USING btree (reporte_id);
CREATE INDEX idx_aceptaciones_legales_documento_id ON public.aceptaciones_legales USING btree (documento_id);
CREATE INDEX idx_atribucion_usuario_referido_por ON public.atribucion_usuario USING btree (referido_por);
CREATE INDEX auditoria_log_creado_en_idx ON public.auditoria_log USING btree (creado_en DESC, id DESC);
CREATE UNIQUE INDEX auditoria_log_tickets_grant_tx_uq ON public.auditoria_log USING btree (((despues ->> 'transactionId'::text))) WHERE ((accion = 'tickets_grant'::text) AND ((despues ->> 'transactionId'::text) IS NOT NULL));
CREATE INDEX idx_auditoria_log_usuario_id ON public.auditoria_log USING btree (usuario_id);
CREATE INDEX idx_comunidades_objetivo_usuario_comunidad_id ON public.comunidades_objetivo_usuario USING btree (comunidad_id);
CREATE INDEX idx_contenido_desbloqueado_usuario_examen_id ON public.contenido_desbloqueado_usuario USING btree (examen_id);
CREATE INDEX idx_contenido_desbloqueado_usuario_pregunta_id ON public.contenido_desbloqueado_usuario USING btree (pregunta_id);
CREATE INDEX idx_contenido_desbloqueado_usuario_usuario_id ON public.contenido_desbloqueado_usuario USING btree (usuario_id);
CREATE INDEX idx_estado_premium_usuario_premium ON public.estado_premium_usuario USING btree (es_premium);
CREATE INDEX idx_estado_premium_usuario_usuario ON public.estado_premium_usuario USING btree (usuario_id);
CREATE INDEX idx_etiquetas_pregunta_etiqueta ON public.etiquetas_pregunta USING btree (etiqueta_id);
CREATE INDEX idx_eventos_app_creado ON public.eventos_app USING btree (creado_en);
CREATE INDEX idx_eventos_app_nombre ON public.eventos_app USING btree (nombre_evento);
CREATE INDEX idx_eventos_app_propiedades_gin ON public.eventos_app USING gin (propiedades);
CREATE INDEX idx_eventos_app_sesion_id ON public.eventos_app USING btree (sesion_id);
CREATE INDEX idx_eventos_app_usuario ON public.eventos_app USING btree (usuario_id);
CREATE INDEX idx_eventos_app_usuario_fecha ON public.eventos_app USING btree (usuario_id, creado_en);
CREATE UNIQUE INDEX ux_eventos_app_idempotency_key_usuario ON public.eventos_app USING btree (idempotency_key, usuario_id);
CREATE INDEX idx_examenes_acceso ON public.examenes USING btree (tipo_acceso);
CREATE INDEX idx_examenes_activo ON public.examenes USING btree (activo);
CREATE INDEX idx_examenes_comunidad_id ON public.examenes USING btree (comunidad_id);
CREATE INDEX idx_examenes_creado_por ON public.examenes USING btree (creado_por);
CREATE INDEX idx_examenes_especialidad ON public.examenes USING btree (especialidad_id);
CREATE INDEX idx_examenes_tipo ON public.examenes USING btree (tipo_examen);
CREATE INDEX idx_examenes_tipo_activo ON public.examenes USING btree (tipo_examen, activo);
CREATE INDEX idx_idempotency_keys_expires_at ON public.idempotency_keys USING btree (expires_at);
CREATE INDEX idx_intentos_examen_abandonado_en_pregunta_id ON public.intentos_examen USING btree (abandonado_en_pregunta_id);
CREATE INDEX idx_intentos_examen_especialidad_snapshot_id ON public.intentos_examen USING btree (especialidad_snapshot_id) WHERE (especialidad_snapshot_id IS NOT NULL);
CREATE INDEX idx_intentos_examen_examen ON public.intentos_examen USING btree (examen_id);
CREATE INDEX idx_intentos_examen_finalizado ON public.intentos_examen USING btree (finalizado_en);
CREATE INDEX idx_intentos_examen_ranking ON public.intentos_examen USING btree (examen_id, cuenta_para_ranking, estado);
CREATE INDEX idx_intentos_examen_usuario_examen ON public.intentos_examen USING btree (usuario_id, examen_id);
CREATE INDEX idx_intentos_usuario ON public.intentos_examen USING btree (usuario_id);
CREATE UNIQUE INDEX ux_intentos_examen_abierto ON public.intentos_examen USING btree (usuario_id, examen_id) WHERE (estado = 'en_progreso'::text);
CREATE INDEX idx_lotes_importacion_examen ON public.lotes_importacion_preguntas USING btree (examen_id);
CREATE INDEX idx_lotes_importacion_preguntas_subido_por ON public.lotes_importacion_preguntas USING btree (subido_por);
CREATE INDEX idx_opciones_pregunta_pregunta ON public.opciones_pregunta USING btree (pregunta_id);
CREATE INDEX idx_perfiles_usuario_comunidad_principal_id ON public.perfiles_usuario USING btree (comunidad_principal_id);
CREATE INDEX idx_perfiles_usuario_especialidad_principal_id ON public.perfiles_usuario USING btree (especialidad_principal_id);
CREATE INDEX idx_perfiles_usuario_username ON public.perfiles_usuario USING btree (username);
CREATE UNIQUE INDEX perfiles_usuario_username_lower_uq ON public.perfiles_usuario USING btree (lower(username)) WHERE (username IS NOT NULL);
CREATE INDEX idx_preguntas_codigo ON public.preguntas USING btree (codigo_pregunta);
CREATE INDEX idx_preguntas_creado_por ON public.preguntas USING btree (creado_por);
CREATE INDEX idx_preguntas_lote_importacion_id ON public.preguntas USING btree (lote_importacion_id);
CREATE INDEX idx_preguntas_tipo ON public.preguntas USING btree (tipo_pregunta);
CREATE INDEX idx_preguntas_tipo_activa ON public.preguntas USING btree (tipo_pregunta, activa);
CREATE INDEX idx_preguntas_examen_examen ON public.preguntas_examen USING btree (examen_id);
CREATE INDEX idx_preguntas_examen_examen_posicion ON public.preguntas_examen USING btree (examen_id, posicion);
CREATE INDEX idx_preguntas_examen_pregunta ON public.preguntas_examen USING btree (pregunta_id);
CREATE INDEX idx_preguntas_intento_pregunta ON public.preguntas_intento USING btree (pregunta_id);
CREATE INDEX idx_recursos_pregunta_codigo ON public.recursos_pregunta USING btree (codigo_recurso);
CREATE INDEX idx_recursos_pregunta_pregunta ON public.recursos_pregunta USING btree (pregunta_id);
CREATE INDEX idx_reportes_contenido_estado ON public.reportes_contenido USING btree (estado);
CREATE INDEX idx_reportes_contenido_reportado_por ON public.reportes_contenido USING btree (reportado_por);
CREATE INDEX idx_reportes_contenido_revisado_por ON public.reportes_contenido USING btree (revisado_por);
CREATE INDEX idx_respuestas_intento_etiqueta ON public.respuestas_intento USING btree (etiqueta_principal_id);
CREATE INDEX idx_respuestas_intento_examen ON public.respuestas_intento USING btree (examen_id);
CREATE INDEX idx_respuestas_intento_intento ON public.respuestas_intento USING btree (intento_id);
CREATE INDEX idx_respuestas_intento_opcion_correcta_id ON public.respuestas_intento USING btree (opcion_correcta_id);
CREATE INDEX idx_respuestas_intento_opcion_seleccionada_id ON public.respuestas_intento USING btree (opcion_seleccionada_id);
CREATE INDEX idx_respuestas_intento_pregunta ON public.respuestas_intento USING btree (pregunta_id);
CREATE INDEX idx_respuestas_intento_primera_opcion_id ON public.respuestas_intento USING btree (primera_opcion_id) WHERE (primera_opcion_id IS NOT NULL);
CREATE INDEX idx_respuestas_intento_ranking ON public.respuestas_intento USING btree (cuenta_para_ranking, es_correcta);
CREATE INDEX idx_respuestas_intento_usuario ON public.respuestas_intento USING btree (usuario_id);
CREATE INDEX idx_respuestas_intento_usuario_pregunta ON public.respuestas_intento USING btree (usuario_id, pregunta_id);
CREATE UNIQUE INDEX ux_respuestas_first_response ON public.respuestas_intento USING btree (usuario_id, pregunta_id) WHERE (es_primer_intento_pregunta IS TRUE);
CREATE INDEX idx_resumen_intento_etiqueta_etiqueta_id ON public.resumen_intento_etiqueta USING btree (etiqueta_id);
CREATE INDEX idx_roles_usuario_asignado_por ON public.roles_usuario USING btree (asignado_por);
CREATE INDEX idx_roles_usuario_rol_id ON public.roles_usuario USING btree (rol_id);
CREATE INDEX idx_roles_usuario_usuario_id ON public.roles_usuario USING btree (usuario_id);
CREATE INDEX idx_sesiones_estudio_usuario_fecha ON public.sesiones_estudio USING btree (usuario_id, iniciada_en DESC);
CREATE INDEX idx_soluciones_pregunta_opcion_correcta_id ON public.soluciones_pregunta USING btree (opcion_correcta_id);
CREATE INDEX idx_soluciones_pregunta_pregunta ON public.soluciones_pregunta USING btree (pregunta_id);
CREATE UNIQUE INDEX idx_usuario_auth_user_id_unique ON public.usuario USING btree (auth_user_id) WHERE (auth_user_id IS NOT NULL);
CREATE UNIQUE INDEX idx_usuario_username_active_unique ON public.usuario USING btree (lower((username)::text)) WHERE ((username IS NOT NULL) AND (deleted_at IS NULL));
CREATE OR REPLACE FUNCTION private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
  v_n integer;
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED: every active question is frozen; the result is the number of EVALUABLE ones.
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    insert into public.preguntas_intento (intento_id, pregunta_id, posicion)
    select p_intento_id, q.pregunta_id, (row_number() over (order by q.posicion, q.pregunta_id))::integer
    from (
      select pe.pregunta_id, pe.posicion
      from public.preguntas_examen pe
      join public.preguntas p on p.id = pe.pregunta_id
      where pe.examen_id = p_examen_id and p.activa
    ) q;
  end if;
  select count(*) into v_n
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = p_examen_id and pe.pregunta_id = pi.pregunta_id and pe.anulada);
  return v_n;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.guard_aceptaciones_append_only()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
    raise exception using errcode = '42501', message = 'ACEPTACIONES_LEGALES_APPEND_ONLY';
end;
$function$
;
CREATE OR REPLACE FUNCTION private.guard_perfiles_estado_cuenta()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' and new.estado_cuenta is distinct from 'activa' then
            raise exception using errcode = '42501', message = 'ESTADO_CUENTA_NOT_CLIENT_WRITABLE';
        end if;
        -- PF3-3-02: a held access token keeps working against PostgREST for up to ~1h, so a
        -- profile that is not 'activa' (eliminacion_pendiente = DEC-008 anonymization in
        -- progress, bloqueada) is read-only for clients: the user cannot rewrite it (undoing
        -- the anonymization or editing a blocked profile). Server roles are unaffected.
        if tg_op = 'UPDATE' and old.estado_cuenta <> 'activa' then
            raise exception using errcode = '42501', message = 'ESTADO_CUENTA_NOT_ACTIVE';
        end if;
        if tg_op = 'UPDATE' and new.estado_cuenta is distinct from old.estado_cuenta then
            raise exception using errcode = '42501', message = 'ESTADO_CUENTA_NOT_CLIENT_WRITABLE';
        end if;
    end if;
    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.guard_perfiles_server_owned()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' then
            if new.onboarding_completado
               or new.es_invitado
               or new.especialidad_principal_id is not null
               or new.comunidad_principal_id is not null
               or new.username is not null
               or new.perfil_intro_visto_en is not null then
                raise exception using errcode = '42501', message = 'PROFILE_FIELD_NOT_CLIENT_WRITABLE';
            end if;
        elsif (new.onboarding_completado, new.es_invitado, new.especialidad_principal_id,
               new.comunidad_principal_id, new.username, new.perfil_intro_visto_en)
              is distinct from
              (old.onboarding_completado, old.es_invitado, old.especialidad_principal_id,
               old.comunidad_principal_id, old.username, old.perfil_intro_visto_en) then
            raise exception using errcode = '42501', message = 'PROFILE_FIELD_NOT_CLIENT_WRITABLE';
        end if;
    end if;
    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.recalcular_total_preguntas_examen(p_examen_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_total integer;
  v_prev text;
begin
  if p_examen_id is null then
    return;
  end if;
  -- C-03: NO KEY UPDATE es compatible con el FOR KEY SHARE de las FK (vínculos
  -- concurrentes e intentos de consumidores no se bloquean ni forman 40P01).
  perform 1 from public.examenes e where e.id = p_examen_id for no key update;
  if not found then
    return; -- inexistente o borrado en esta misma transacción (cascada)
  end if;
  -- Sentencia posterior al cerrojo: ve los vínculos confirmados por quien lo tenía.
  select count(*)::integer
    into v_total
    from public.preguntas_examen pe
    join public.preguntas p on p.id = pe.pregunta_id
   where pe.examen_id = p_examen_id
     and p.activa;
  -- C-05: sólo con esta bandera transaccional deja pasar la guarda la escritura.
  v_prev := current_setting('oposicontrol.total_preguntas_recount', true);
  perform set_config('oposicontrol.total_preguntas_recount', 'on', true);
  update public.examenes e
     set total_preguntas = v_total
   where e.id = p_examen_id
     and e.total_preguntas is distinct from v_total;
  perform set_config('oposicontrol.total_preguntas_recount', coalesce(nullif(v_prev, ''), 'off'), true);
end;
$function$
;
CREATE OR REPLACE FUNCTION private.slice1_int(p_json jsonb, p_key text, p_min integer, p_max integer)
 RETURNS integer
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
    v_n numeric;
begin
    if p_json -> p_key is null or jsonb_typeof(p_json -> p_key) = 'null' then
        return null;
    end if;
    if jsonb_typeof(p_json -> p_key) is distinct from 'number' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    v_n := (p_json ->> p_key)::numeric;
    if v_n <> trunc(v_n) or v_n < p_min or v_n > p_max then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    return v_n::integer;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.slice1_lock_profile(p_usuario_id uuid, p_allow_pending boolean DEFAULT false)
 RETURNS public.perfiles_usuario
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
    v_p public.perfiles_usuario;
begin
    if p_usuario_id is null then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'usuarioId';
    end if;
    select * into v_p from public.perfiles_usuario pu where pu.usuario_id = p_usuario_id for update;
    if not found then
        raise exception using errcode = 'OC404', message = 'PROFILE_MISSING';
    end if;
    if v_p.estado_cuenta = 'activa' then
        return v_p;
    end if;
    if p_allow_pending and v_p.estado_cuenta = 'eliminacion_pendiente' then
        return v_p;
    end if;
    if v_p.estado_cuenta = 'bloqueada' then
        raise exception using errcode = '42501', message = 'ACCOUNT_BLOCKED';
    elsif v_p.estado_cuenta = 'eliminacion_pendiente' then
        raise exception using errcode = '42501', message = 'ACCOUNT_DELETION_PENDING';
    end if;
    raise exception using errcode = '42501', message = 'FORBIDDEN';
end;
$function$
;
CREATE OR REPLACE FUNCTION private.slice1_profile_json(p_usuario_id uuid)
 RETURNS jsonb
 LANGUAGE sql
 SET search_path TO ''
AS $function$
    select jsonb_build_object(
        'usuarioId', p.usuario_id,
        'username', p.username,
        'nombreMostrar', p.nombre_mostrar,
        'nombre', p.nombre,
        'apellidos', p.apellidos,
        'especialidadPrincipalId', p.especialidad_principal_id,
        'comunidadPrincipalId', p.comunidad_principal_id,
        'avatarUrl', p.avatar_url,
        'onboardingCompleted', p.onboarding_completado,
        'estadoCuenta', p.estado_cuenta,
        'introSeenAt', to_char(p.perfil_intro_visto_en at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'creadoEn', to_char(p.creado_en at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'actualizadoEn', to_char(p.actualizado_en at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    )
    from public.perfiles_usuario p
    where p.usuario_id = p_usuario_id
$function$
;
CREATE OR REPLACE FUNCTION private.slice1_text(p_json jsonb, p_key text, p_max integer)
 RETURNS text
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
    v_t text;
begin
    if p_json -> p_key is null or jsonb_typeof(p_json -> p_key) = 'null' then
        return null;
    end if;
    if jsonb_typeof(p_json -> p_key) is distinct from 'string' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    v_t := private.slice1_trim(p_json ->> p_key);
    if v_t is not null and char_length(v_t) > p_max then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    return v_t;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.slice1_trim(p_text text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO ''
AS $function$
    select nullif(
        regexp_replace(
            p_text,
            '^[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+|[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+$',
            '',
            'g'
        ),
        ''
    )
$function$
;
CREATE OR REPLACE FUNCTION private.slice1_uuid(p_text text, p_field text)
 RETURNS uuid
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
    return p_text::uuid;
exception when invalid_text_representation then
    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_field;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_examenes_bloquear_cambio_tipo()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  if exists (select 1 from public.preguntas_examen pe where pe.examen_id = new.id) then
    raise exception 'EXAM_TYPE_LOCKED_BY_QUESTIONS';
  end if;
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_examenes_publicado_no_vacio()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  raise exception 'EXAM_PUBLISHED_WOULD_BE_EMPTY';
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_examenes_total_preguntas_guard()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  -- C-05: la columna no es escribible desde fuera del recuento.
  if new.total_preguntas is distinct from old.total_preguntas
     and coalesce(current_setting('oposicontrol.total_preguntas_recount', true), 'off') <> 'on' then
    new.total_preguntas := old.total_preguntas;
  end if;
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_examenes_total_preguntas_insert()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  new.total_preguntas := 0; -- un examen recién insertado no puede tener vínculos (FK)
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_intentos_examen_creado_por_comando_inmutable()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  if new.creado_por_comando is distinct from old.creado_por_comando then
    raise exception 'INTENTO_CREADO_POR_COMANDO_INMUTABLE'
      using detail = 'intentos_examen.creado_por_comando is written only by app_start_exam_attempt, on INSERT.';
  end if;
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_intentos_examen_especialidad_snapshot_inmutable()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  if old.especialidad_snapshot_id is not null
     and new.especialidad_snapshot_id is distinct from old.especialidad_snapshot_id then
    raise exception 'INTENTO_ESPECIALIDAD_SNAPSHOT_INMUTABLE'
      using detail = 'intentos_examen.especialidad_snapshot_id is fixed at start (DEC-002).';
  end if;
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_preguntas_activa_total_preguntas()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_examen_id uuid;
begin
  -- C-04: orden determinista de cerrojos entre archivados concurrentes.
  for v_examen_id in
    select pe.examen_id
      from public.preguntas_examen pe
     where pe.pregunta_id = new.id
     order by pe.examen_id
  loop
    perform private.recalcular_total_preguntas_examen(v_examen_id);
  end loop;
  return null;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_preguntas_bloquear_cambio_tipo()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  if exists (select 1 from public.preguntas_examen pe where pe.pregunta_id = new.id) then
    raise exception 'QUESTION_TYPE_LOCKED_BY_EXAMS';
  end if;
  if old.tipo_pregunta is distinct from new.tipo_pregunta
     and old.tipo_pregunta = 'inmersiva'
     and exists (select 1 from public.recursos_pregunta rp where rp.pregunta_id = new.id) then
    raise exception 'QUESTION_TYPE_LOCKED_BY_RESOURCES';
  end if;
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_preguntas_codigo_pregunta()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_n bigint;
begin
  if tg_op = 'INSERT' then
    -- Siempre la base: cualquier código recibido (cliente, RPC, importación) se ignora.
    v_n := nextval('private.preguntas_codigo_pregunta_seq');
    new.codigo_pregunta := 'PREG-' || lpad(v_n::text, greatest(6, length(v_n::text)), '0');
    return new;
  end if;
  -- UPDATE OF codigo_pregunta: reenviar el mismo valor vale; cambiarlo, no.
  if new.codigo_pregunta is distinct from old.codigo_pregunta then
    raise exception 'QUESTION_CODE_IMMUTABLE'
      using hint = 'codigo_pregunta lo genera la base y no cambia al editar';
  end if;
  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.tg_preguntas_examen_total_preguntas()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
  if tg_op in ('INSERT', 'UPDATE') then
    -- C-04: cerrojo pregunta -> examen. Serializa con el archivado de la pregunta
    -- (que toma NO KEY UPDATE sobre ella) y cierra el write-skew.
    perform 1 from public.preguntas p where p.id = new.pregunta_id for share;
  end if;
  if tg_op = 'DELETE' then
    perform private.recalcular_total_preguntas_examen(old.examen_id);
  elsif tg_op = 'UPDATE' and old.examen_id is distinct from new.examen_id then
    perform private.recalcular_total_preguntas_examen(least(old.examen_id, new.examen_id));
    perform private.recalcular_total_preguntas_examen(greatest(old.examen_id, new.examen_id));
  else
    perform private.recalcular_total_preguntas_examen(new.examen_id);
  end if;
  return null;
end;
$function$
;
CREATE OR REPLACE FUNCTION private.validate_perfiles_refs()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
    if new.especialidad_principal_id is not null
       and (tg_op = 'INSERT' or new.especialidad_principal_id is distinct from old.especialidad_principal_id) then
        if not exists (
            select 1 from public.especialidades e
            where e.id = new.especialidad_principal_id and e.activa
        ) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;
    if new.comunidad_principal_id is not null
       and (tg_op = 'INSERT' or new.comunidad_principal_id is distinct from old.comunidad_principal_id) then
        if not exists (
            select 1 from public.comunidades_autonomas c
            where c.id = new.comunidad_principal_id
        ) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
    end if;
    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.admin_record_report_action(p_reporte_id uuid, p_estado text, p_moderador_id uuid, p_tipo_accion text, p_notas text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
 SET row_security TO 'off'
AS $function$
declare
  v_estado text;
  v_revisado_por uuid;
  v_accion_pendiente boolean;
begin
  if p_estado is null or p_estado not in ('resuelto', 'descartado') then
    return false;
  end if;

  -- Serialises every call for this report; under READ COMMITTED the next statement sees what the previous holder
  -- committed (including the cleared marker).
  select r.estado, r.revisado_por, r.accion_pendiente
  into v_estado, v_revisado_por, v_accion_pendiente
  from public.reportes_contenido r
  where r.id = p_reporte_id
  for update;

  -- A NULL p_moderador_id only matches a report closed WITHOUT an actor (revisado_por NULL): see NULL MODERATOR.
  -- Only a resolution the new compare-and-set left in flight (marker true) is recorded: historical closures never.
  if not found or v_estado is distinct from p_estado or v_revisado_por is distinct from p_moderador_id
    or v_accion_pendiente is not true then
    return false;
  end if;

  -- creado_en: the DB default now() (no application clock, never in the future).
  insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion, notas)
  values (p_reporte_id, p_moderador_id, p_tipo_accion, p_notas);

  update public.reportes_contenido
  set accion_pendiente = false
  where id = p_reporte_id;

  return true;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.admin_role_code_canonical(p_codigo text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO ''
AS $function$
  select lower(
    regexp_replace(
      coalesce(p_codigo, ''),
      '^[	--    -   +|[	--    -   +$',
      '',
      'g'
    ) collate "C"
  );
$function$
;
CREATE OR REPLACE FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[])
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
 SET row_security TO 'off'
AS $function$
declare
  v_role_ids uuid[] := coalesce(p_role_ids, array[]::uuid[]);
  v_desconocidos integer;
  v_era_administrativo boolean;
  v_superadmins_antes integer;
  v_superadmins_despues integer;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  if not exists (select 1 from auth.users u where u.id = p_usuario_id) then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  select count(*) into v_desconocidos
  from unnest(v_role_ids) as solicitado(rol_id)
  where not exists (
    select 1 from public.roles_app r where r.id = solicitado.rol_id
  );
  if v_desconocidos > 0 then
    raise exception 'ROLE_SYNC_UNKNOWN_ROLE';
  end if;

  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app r on r.id = ru.rol_id
    where ru.usuario_id = p_usuario_id
      and public.admin_role_code_canonical(r.codigo) in ('superadmin', 'admin')
  ) into v_era_administrativo;

  if v_era_administrativo and cardinality(v_role_ids) = 0 then
    raise exception 'ROLE_SYNC_EMPTY_ROLES';
  end if;

  select count(*) into v_superadmins_antes
  from public.roles_usuario ru
  join public.roles_app r on r.id = ru.rol_id
  where public.admin_role_code_canonical(r.codigo) = 'superadmin';

  delete from public.roles_usuario where usuario_id = p_usuario_id;

  insert into public.roles_usuario (usuario_id, rol_id)
  select distinct p_usuario_id, asignado.rol_id
  from unnest(v_role_ids) as asignado(rol_id);

  select count(*) into v_superadmins_despues
  from public.roles_usuario ru
  join public.roles_app r on r.id = ru.rol_id
  where public.admin_role_code_canonical(r.codigo) = 'superadmin';

  if v_superadmins_antes > 0 and v_superadmins_despues = 0 then
    raise exception 'ROLE_SYNC_LAST_SUPERADMIN';
  end if;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
 SET row_security TO 'off'
AS $function$
declare
  v_role_ids uuid[] := coalesce(p_role_ids, array[]::uuid[]);
  v_cambia_administrativos boolean;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Same global lock as the 2-argument guard, BEFORE the first read: the actor's roles and the target's
  -- current roles cannot change (through the RPC) between this check and the write below.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  with actuales as (
    select distinct public.admin_role_code_canonical(r.codigo) as codigo
    from public.roles_usuario ru
    join public.roles_app r on r.id = ru.rol_id
    where ru.usuario_id = p_usuario_id
  ),
  pedidos as (
    select distinct public.admin_role_code_canonical(r.codigo) as codigo
    from unnest(v_role_ids) as solicitado(rol_id)
    join public.roles_app r on r.id = solicitado.rol_id
  ),
  diferencia as (
    (select codigo from actuales except select codigo from pedidos)
    union all
    (select codigo from pedidos except select codigo from actuales)
  )
  select exists (select 1 from diferencia d where d.codigo in ('superadmin', 'admin'))
  into v_cambia_administrativos;

  if v_cambia_administrativos and (
    p_actor_id is null
    or not exists (
      select 1
      from public.roles_usuario ru
      join public.roles_app r on r.id = ru.rol_id
      where ru.usuario_id = p_actor_id
        and public.admin_role_code_canonical(r.codigo) = 'superadmin'
    )
  ) then
    raise exception 'SUPERADMIN_REQUIRED';
  end if;

  -- Write + invariants A/B (user exists, known roles, no empty admin, never zero superadmins).
  perform public.admin_sync_roles(p_usuario_id, p_role_ids);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
 SET row_security TO 'off'
AS $function$
declare
  v_antes jsonb;
  v_despues jsonb;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Same global lock as both guard overloads, BEFORE the first read: `antes` is exactly the set this sync replaces.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
  into v_antes
  from (select distinct ru.rol_id from public.roles_usuario ru where ru.usuario_id = p_usuario_id) x;

  -- Guard (SUPERADMIN under the lock) + write + invariants A/B. Errors propagate unchanged.
  perform public.admin_sync_roles(p_usuario_id, p_role_ids, p_actor_id);

  select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
  into v_despues
  from (select distinct ru.rol_id from public.roles_usuario ru where ru.usuario_id = p_usuario_id) x;

  return jsonb_build_object('antes', v_antes, 'despues', v_despues);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid, p_expected_role_ids uuid[])
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
 SET row_security TO 'off'
AS $function$
declare
  v_actuales uuid[];
  v_antes jsonb;
  v_esperados jsonb;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Same global lock as every role-sync overload, BEFORE the first read: no other sync can commit between this
  -- comparison and the write below.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  if p_expected_role_ids is not null then
    v_actuales := array(select distinct ru.rol_id from public.roles_usuario ru where ru.usuario_id = p_usuario_id);

    select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
    into v_antes
    from unnest(v_actuales) as x(rol_id);

    -- Sorted distinct expected set. A NULL element becomes JSON null and never equals a stored role id.
    select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
    into v_esperados
    from (select distinct e.rol_id from unnest(p_expected_role_ids) as e(rol_id)) x;

    if v_esperados is distinct from v_antes then
      -- Unknown target: delegated to the existence guard of public.admin_sync_roles(uuid, uuid[]) (its own check, its
      -- own ROLE_SYNC_INVALID_USER, propagated unchanged), so this function never reads the auth schema itself. For a
      -- known target that call rewrites the SAME role set (no guard can fire: same roles, same superadmin count) and
      -- the raise below aborts the caller's (sub)transaction, which undoes it: a conflict never writes.
      perform public.admin_sync_roles(p_usuario_id, v_actuales);
      raise exception 'ROLE_SYNC_VERSION_CONFLICT';
    end if;
  end if;

  -- Re-entrant lock: `antes`/`despues` are read under the same lock; guard errors propagate unchanged.
  return public.admin_sync_roles_audited(p_usuario_id, p_role_ids, p_actor_id);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_abandon_exam_attempt(p_usuario_id uuid, p_intento_id uuid, p_posicion integer, p_motivo text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_estado text;
  v_examen_id uuid;
  v_iniciado timestamp with time zone;
  v_total integer;
  v_pregunta_id uuid;
  v_motivo_detectado text;
begin
  if p_usuario_id is null or p_intento_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  select ie.estado, ie.examen_id, ie.iniciado_en, ie.total_preguntas
    into v_estado, v_examen_id, v_iniciado, v_total
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado = 'abandonado' then
    return jsonb_build_object('outcome', 'OK', 'already', true);
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_ALREADY_FINISHED');
  end if;

  -- The wire value is kept verbatim in metadatos; motivo_detectado uses MAIN's vocabulary (CHECK).
  v_motivo_detectado := case p_motivo when 'salida_usuario' then 'cerrar_examen' when 'otro' then 'desconocido' end;
  if v_motivo_detectado is null then
    return jsonb_build_object('outcome', 'INVALID_REASON');
  end if;
  if p_posicion is null or p_posicion < 1 or (v_total > 0 and p_posicion > v_total) then
    return jsonb_build_object('outcome', 'INVALID_POSITION', 'total', v_total);
  end if;

  select pi.pregunta_id into v_pregunta_id
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id and pi.posicion = p_posicion;

  update public.intentos_examen
     set estado = 'abandonado',
         abandonado_en_posicion = p_posicion,
         abandonado_en_pregunta_id = v_pregunta_id,
         ultima_pregunta_posicion = p_posicion
   where id = p_intento_id;

  insert into public.abandonos_examen
    (usuario_id, intento_id, examen_id, pregunta_id, posicion_pregunta, tiempo_transcurrido_segundos,
     total_preguntas, motivo_detectado, metadatos)
  values
    (p_usuario_id, p_intento_id, v_examen_id, v_pregunta_id, p_posicion,
     greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
     v_total, v_motivo_detectado, jsonb_build_object('motivo', p_motivo, 'origen', 'D2-11'));

  return jsonb_build_object('outcome', 'OK', 'already', false);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_aceptar_documentos_legales(p_usuario_id uuid, p_documentos uuid[], p_plataforma text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_docs uuid[];
    v_n integer;
    v_pending jsonb;
begin
    perform private.slice1_lock_profile(p_usuario_id);

    select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
    if cardinality(v_docs) not between 1 and 5 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
    end if;
    if p_plataforma is null or p_plataforma not in ('android', 'ios') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'plataforma';
    end if;
    select count(*) into v_n from public.documentos_legales d where d.id = any (v_docs) and d.activo;
    if v_n <> cardinality(v_docs) then
        raise exception using errcode = '22023', message = 'DOCUMENT_NOT_ACTIVE';
    end if;

    insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma)
    select p_usuario_id, d.id, d.tipo_documento, d.version, p_plataforma
    from public.documentos_legales d
    where d.id = any (v_docs)
    on conflict (usuario_id, documento_id) do nothing;

    -- Only the OpenAPI LegalDocumentType values (terminos|privacidad) are ever returned.
    select coalesce(jsonb_agg(jsonb_build_object('id', q.id, 'tipo', q.tipo_documento, 'version', q.version)
                              order by q.tipo_documento, q.publicado_en, q.id), '[]'::jsonb)
    into v_pending
    from (
        select d.id, d.tipo_documento, d.version, d.publicado_en
        from public.documentos_legales d
        where d.activo and d.tipo_documento in ('terminos', 'privacidad')
          and not exists (select 1 from public.aceptaciones_legales a
                          where a.usuario_id = p_usuario_id and a.documento_id = d.id)
        order by d.tipo_documento, d.publicado_en, d.id
        limit 20
    ) q;

    return jsonb_build_object('pendingLegalDocuments', v_pending);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_actualizar_perfil(p_usuario_id uuid, p_patch jsonb, p_if_match timestamp with time zone)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_p public.perfiles_usuario;
    v_allowed constant text[] := array['username', 'nombre', 'apellidos', 'especialidadId',
                                        'comunidadPrincipalId', 'detail', 'targetCommunityIds', 'introSeen'];
    v_detail_allowed constant text[] := array['edad', 'ciudad', 'estadoLaboral', 'centroTrabajo',
                                               'tiempoEstudioMin', 'franja', 'fechaExamenObjetivo'];
    k text;
    v_username text;
    v_nombre text;
    v_apellidos text;
    v_esp uuid;
    v_com uuid;
    v_intro timestamptz;
    v_touch boolean := false;
    v_detail jsonb;
    v_d public.perfiles_usuario_detalle;
    v_edad integer;
    v_ciudad text;
    v_laboral text;
    v_centro text;
    v_tiempo integer;
    v_franja text;
    v_fecha date;
    v_raw uuid[];
    v_targets uuid[];
    v_current uuid[];
    v_i integer;
begin
    v_p := private.slice1_lock_profile(p_usuario_id);

    if p_patch is null or jsonb_typeof(p_patch) is distinct from 'object' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'patch';
    end if;
    for k in select jsonb_object_keys(p_patch) loop
        if not (k = any (v_allowed)) then
            raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = k;
        end if;
    end loop;

    if p_if_match is not null and p_if_match is distinct from v_p.actualizado_en then
        raise exception using errcode = 'OC412', message = 'PRECONDITION_FAILED';
    end if;

    v_username := v_p.username;
    v_nombre := v_p.nombre;
    v_apellidos := v_p.apellidos;
    v_esp := v_p.especialidad_principal_id;
    v_com := v_p.comunidad_principal_id;
    v_intro := v_p.perfil_intro_visto_en;

    if p_patch ? 'username' then
        if jsonb_typeof(p_patch -> 'username') is distinct from 'string'
           or (p_patch ->> 'username') !~ '^[A-Za-z0-9._-]{3,30}$' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
        end if;
        v_username := p_patch ->> 'username';
        perform pg_advisory_xact_lock(hashtext('slice1.username'), hashtext(lower(v_username)));
        if exists (select 1 from public.perfiles_usuario x
                   where lower(x.username) = lower(v_username) and x.usuario_id <> p_usuario_id) then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end if;
    end if;
    if p_patch ? 'nombre' then
        v_nombre := private.slice1_text(p_patch, 'nombre', 100);
        if v_nombre is null then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
        end if;
    end if;
    if p_patch ? 'apellidos' then
        v_apellidos := private.slice1_text(p_patch, 'apellidos', 100);
        if v_apellidos is null then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
        end if;
    end if;
    if p_patch ? 'especialidadId' then
        if jsonb_typeof(p_patch -> 'especialidadId') is distinct from 'string' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'especialidadId';
        end if;
        v_esp := private.slice1_uuid(p_patch ->> 'especialidadId', 'especialidadId');
        if not exists (select 1 from public.especialidades e where e.id = v_esp and e.activa) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;
    if p_patch ? 'comunidadPrincipalId' then
        if jsonb_typeof(p_patch -> 'comunidadPrincipalId') is distinct from 'string' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'comunidadPrincipalId';
        end if;
        v_com := private.slice1_uuid(p_patch ->> 'comunidadPrincipalId', 'comunidadPrincipalId');
        if not exists (select 1 from public.comunidades_autonomas c where c.id = v_com) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
    end if;
    if p_patch ? 'introSeen' then
        if jsonb_typeof(p_patch -> 'introSeen') is distinct from 'boolean' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'introSeen';
        end if;
        if (p_patch ->> 'introSeen')::boolean and v_intro is null then
            v_intro := now();
        end if;
    end if;

    -- detail (perfiles_usuario_detalle): only present keys change; JSON null clears the field.
    if p_patch ? 'detail' then
        v_detail := p_patch -> 'detail';
        if jsonb_typeof(v_detail) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'detail';
        end if;
        for k in select jsonb_object_keys(v_detail) loop
            if not (k = any (v_detail_allowed)) then
                raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = 'detail.' || k;
            end if;
        end loop;
        select * into v_d from public.perfiles_usuario_detalle d where d.usuario_id = p_usuario_id for update;
        v_edad := v_d.edad;
        v_ciudad := v_d.ciudad;
        v_laboral := v_d.estado_laboral;
        v_centro := v_d.centro_trabajo;
        v_tiempo := v_d.tiempo_disponible_estudio_minutos;
        v_franja := v_d.franja_preferente_estudio;
        v_fecha := v_d.fecha_examen_objetivo;
        if v_detail ? 'edad' then v_edad := private.slice1_int(v_detail, 'edad', 14, 120); end if;
        if v_detail ? 'ciudad' then v_ciudad := private.slice1_text(v_detail, 'ciudad', 100); end if;
        if v_detail ? 'estadoLaboral' then v_laboral := private.slice1_text(v_detail, 'estadoLaboral', 100); end if;
        if v_detail ? 'centroTrabajo' then v_centro := private.slice1_text(v_detail, 'centroTrabajo', 100); end if;
        if v_detail ? 'tiempoEstudioMin' then v_tiempo := private.slice1_int(v_detail, 'tiempoEstudioMin', 0, 1440); end if;
        if v_detail ? 'franja' then v_franja := private.slice1_text(v_detail, 'franja', 100); end if;
        if v_detail ? 'fechaExamenObjetivo' then
            if jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'null' then
                v_fecha := null;
            elsif jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'string'
                  and (v_detail ->> 'fechaExamenObjetivo') ~ '^\d{4}-\d{2}-\d{2}$' then
                begin
                    v_fecha := (v_detail ->> 'fechaExamenObjetivo')::date;
                exception when others then
                    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
                end;
            else
                raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
            end if;
        end if;

        if v_d.usuario_id is null then
            if (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
               (null::integer, null::text, null::text, null::text, null::integer, null::text, null::date) then
                insert into public.perfiles_usuario_detalle (usuario_id, edad, ciudad, estado_laboral, centro_trabajo,
                    tiempo_disponible_estudio_minutos, franja_preferente_estudio, fecha_examen_objetivo, actualizado_en)
                values (p_usuario_id, v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha, now());
                v_touch := true;
            end if;
        elsif (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
              (v_d.edad, v_d.ciudad, v_d.estado_laboral, v_d.centro_trabajo,
               v_d.tiempo_disponible_estudio_minutos, v_d.franja_preferente_estudio, v_d.fecha_examen_objetivo) then
            update public.perfiles_usuario_detalle
            set edad = v_edad, ciudad = v_ciudad, estado_laboral = v_laboral, centro_trabajo = v_centro,
                tiempo_disponible_estudio_minutos = v_tiempo, franja_preferente_estudio = v_franja,
                fecha_examen_objetivo = v_fecha, actualizado_en = now()
            where usuario_id = p_usuario_id;
            v_touch := true;
        end if;
    end if;

    -- target communities: the array REPLACES the set; prioridad = position (1..n).
    if p_patch ? 'targetCommunityIds' then
        if jsonb_typeof(p_patch -> 'targetCommunityIds') is distinct from 'array'
           or jsonb_array_length(p_patch -> 'targetCommunityIds') > 20 then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
        end if;
        v_raw := '{}'::uuid[];
        for v_i in 0 .. jsonb_array_length(p_patch -> 'targetCommunityIds') - 1 loop
            if jsonb_typeof(p_patch -> 'targetCommunityIds' -> v_i) is distinct from 'string' then
                raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
            end if;
            v_raw := v_raw || private.slice1_uuid(p_patch -> 'targetCommunityIds' ->> v_i, 'targetCommunityIds');
        end loop;
        select coalesce(array_agg(s.id order by s.first_ord), '{}'::uuid[]) into v_targets
        from (select t.id, min(t.ord) as first_ord from unnest(v_raw) with ordinality as t(id, ord) group by t.id) s;
        if (select count(*) from public.comunidades_autonomas c where c.id = any (v_targets)) <> cardinality(v_targets) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
        select coalesce(array_agg(o.comunidad_id order by o.prioridad, o.comunidad_id), '{}'::uuid[]) into v_current
        from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario_id;
        if v_current is distinct from v_targets then
            delete from public.comunidades_objetivo_usuario where usuario_id = p_usuario_id;
            insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id, prioridad)
            select p_usuario_id, t.id, t.ord::integer from unnest(v_targets) with ordinality as t(id, ord);
            v_touch := true;
        end if;
    end if;

    if v_touch
       or (v_username, v_nombre, v_apellidos, v_esp, v_com, v_intro) is distinct from
          (v_p.username, v_p.nombre, v_p.apellidos, v_p.especialidad_principal_id,
           v_p.comunidad_principal_id, v_p.perfil_intro_visto_en) then
        begin
            update public.perfiles_usuario
            set username = v_username, nombre = v_nombre, apellidos = v_apellidos,
                especialidad_principal_id = v_esp, comunidad_principal_id = v_com,
                perfil_intro_visto_en = v_intro
            where usuario_id = p_usuario_id;
        exception when unique_violation then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end;
    end if;

    return private.slice1_profile_json(p_usuario_id);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_completar_onboarding(p_usuario_id uuid, p_username text, p_nombre text, p_apellidos text, p_especialidad_id uuid, p_documentos uuid[], p_plataforma text, p_attribution jsonb, p_referral_code text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_p public.perfiles_usuario;
    v_nombre text := private.slice1_trim(p_nombre);
    v_apellidos text := private.slice1_trim(p_apellidos);
    v_docs uuid[];
    v_attr jsonb := coalesce(p_attribution, '{}'::jsonb);
    v_ref text := private.slice1_trim(p_referral_code);
    v_fuente text;
    v_canal text;
    v_campania text;
    v_plat text;
    v_install text;
    v_n integer;
    v_replay boolean := false;
begin
    v_p := private.slice1_lock_profile(p_usuario_id);

    if p_username is null or p_username !~ '^[A-Za-z0-9._-]{3,30}$' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
    end if;
    if v_nombre is null or char_length(v_nombre) not between 1 and 100 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
    end if;
    if v_apellidos is null or char_length(v_apellidos) not between 1 and 100 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
    end if;

    -- Idempotent on usuario_id: identical replay = profile untouched but the body's legal documents and
    -- write-once attribution are still applied; different identity = conflict (nothing validated or written).
    if v_p.onboarding_completado then
        if coalesce(v_p.username = p_username and v_p.nombre = v_nombre and v_p.apellidos = v_apellidos
                    and (p_especialidad_id is null or v_p.especialidad_principal_id = p_especialidad_id), false) then
            v_replay := true;
        else
            raise exception using errcode = 'OC409', message = 'ALREADY_ONBOARDED_CONFLICT';
        end if;
    end if;

    if jsonb_typeof(v_attr) is distinct from 'object' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution';
    end if;
    v_fuente := private.slice1_text(v_attr, 'fuente', 500);
    v_canal := private.slice1_text(v_attr, 'canal', 500);
    v_campania := private.slice1_text(v_attr, 'campania', 500);
    v_plat := private.slice1_text(v_attr, 'plataforma', 20);
    v_install := private.slice1_text(v_attr, 'installReferrer', 500);
    if v_plat is not null and v_plat not in ('android', 'ios', 'web') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution.plataforma';
    end if;
    if v_ref is not null and char_length(v_ref) > 64 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'referralCode';
    end if;

    if not v_replay then
        -- Serialise concurrent claims of the same case-insensitive username (the unique index stays the backstop).
        perform pg_advisory_xact_lock(hashtext('slice1.username'), hashtext(lower(p_username)));
        if exists (select 1 from public.perfiles_usuario x
                   where lower(x.username) = lower(p_username) and x.usuario_id <> p_usuario_id) then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end if;

        if p_especialidad_id is not null
           and not exists (select 1 from public.especialidades e where e.id = p_especialidad_id and e.activa) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;

    select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
    if cardinality(v_docs) > 20 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
    end if;
    if cardinality(v_docs) > 0 then
        if p_plataforma is null or p_plataforma not in ('android', 'ios') then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'plataforma';
        end if;
        select count(*) into v_n from public.documentos_legales d where d.id = any (v_docs) and d.activo;
        if v_n <> cardinality(v_docs) then
            raise exception using errcode = '22023', message = 'DOCUMENT_NOT_ACTIVE';
        end if;
        insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma)
        select p_usuario_id, d.id, d.tipo_documento, d.version, p_plataforma
        from public.documentos_legales d
        where d.id = any (v_docs)
        on conflict (usuario_id, documento_id) do nothing;
    end if;

    if not v_replay then
        begin
            update public.perfiles_usuario
            set username = p_username,
                nombre = v_nombre,
                apellidos = v_apellidos,
                especialidad_principal_id = coalesce(p_especialidad_id, especialidad_principal_id),
                onboarding_completado = true
            where usuario_id = p_usuario_id;
        exception when unique_violation then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end;
    end if;

    if v_fuente is not null or v_canal is not null or v_campania is not null or v_plat is not null
       or v_install is not null or v_ref is not null then
        insert into public.atribucion_usuario (usuario_id, fuente, canal, campania, plataforma, install_referrer, codigo_referido)
        values (p_usuario_id, v_fuente, v_canal, v_campania, v_plat, v_install, v_ref)
        on conflict (usuario_id) do nothing;
    end if;

    if v_replay then
        return private.slice1_profile_json(p_usuario_id) || jsonb_build_object('replay', true);
    end if;
    return private.slice1_profile_json(p_usuario_id);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_estado text;
  v_examen_id uuid;
  v_iniciado timestamp with time zone;
  v_correctas integer;
  v_falladas integer;
  v_sin_responder integer;
begin
  if p_usuario_id is null or p_intento_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  select ie.estado, ie.examen_id, ie.iniciado_en into v_estado, v_examen_id, v_iniciado
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado = 'finalizado' then
    return jsonb_build_object('outcome', 'OK', 'already', true);
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_ABANDONED');
  end if;

  update public.preguntas_intento pi
     set estado_final = s.estado_final
  from (
    select q.id,
           case
             when exists (select 1 from public.preguntas_examen pe
                          where pe.examen_id = v_examen_id and pe.pregunta_id = q.pregunta_id and pe.anulada) then 'ANNULLED'
             when ri.id is null then 'UNANSWERED'
             when ri.opcion_seleccionada_id is null then 'SKIPPED'
             when ri.es_correcta is true then 'CORRECT'
             else 'WRONG'
           end as estado_final
    from public.preguntas_intento q
    left join public.respuestas_intento ri on ri.intento_id = q.intento_id and ri.pregunta_id = q.pregunta_id
    where q.intento_id = p_intento_id
  ) s
  where pi.id = s.id;

  select count(*) filter (where pi.estado_final = 'CORRECT'),
         count(*) filter (where pi.estado_final = 'WRONG'),
         count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED'))
    into v_correctas, v_falladas, v_sin_responder
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id;

  insert into public.resumen_intento_etiqueta
    (intento_id, etiqueta_id, total_preguntas, correctas, falladas, sin_responder,
     tiempo_total_segundos, tiempo_medio_segundos, porcentaje_acierto)
  select p_intento_id,
         t.etiqueta_id,
         count(*)::integer,
         (count(*) filter (where pi.estado_final = 'CORRECT'))::integer,
         (count(*) filter (where pi.estado_final = 'WRONG'))::integer,
         (count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED')))::integer,
         (coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000)::integer,
         round(coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000.0 / count(*), 2),
         case when count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')) > 0
              then round(count(*) filter (where pi.estado_final = 'CORRECT') * 100.0
                         / count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')), 2)
              else 0 end
  from public.preguntas_intento pi
  join lateral (
    select ep.etiqueta_id from public.etiquetas_pregunta ep
    where ep.pregunta_id = pi.pregunta_id
    order by ep.creado_en asc, ep.etiqueta_id asc
    limit 1
  ) t on true
  left join public.respuestas_intento ri on ri.intento_id = pi.intento_id and ri.pregunta_id = pi.pregunta_id
  where pi.intento_id = p_intento_id and pi.estado_final <> 'ANNULLED'
  group by t.etiqueta_id
  on conflict (intento_id, etiqueta_id) do nothing;

  update public.intentos_examen
     set estado = 'finalizado',
         motivo_fin = 'USER',
         finalizado_en = now(),
         duracion_segundos = greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
         total_respondidas = v_correctas + v_falladas,
         total_correctas = v_correctas,
         total_falladas = v_falladas,
         total_sin_responder = v_sin_responder,
         porcentaje_acierto = case when v_correctas + v_falladas > 0
                                   then round(v_correctas * 100.0 / (v_correctas + v_falladas), 2) else 0 end,
         puntuacion = v_correctas,
         calculado_en = now()
   where id = p_intento_id;

  return jsonb_build_object('outcome', 'OK', 'already', false);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_my_exams_collection(p_usuario_id uuid, p_tipo_examen text, p_comunidad_id uuid, p_anio integer, p_despues_en timestamp with time zone, p_despues_id uuid, p_limite integer, p_con_grupos boolean)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
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
$function$
;
CREATE OR REPLACE FUNCTION public.app_record_attempt_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_id uuid, p_tiempo_respuesta_ms integer, p_base_revision integer, p_nivel_confianza integer)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_estado text;
  v_examen_id uuid;
  v_ranking boolean;
  v_iniciado timestamp with time zone;
  v_posicion integer;
  v_correcta_id uuid;
  v_row public.respuestas_intento%rowtype;
  v_exists boolean;
  v_first boolean;
  v_last_event timestamp with time zone;
  v_gap_ms bigint;
  v_ms integer;
  v_clamped boolean;
  v_meta jsonb;
  v_revision integer;
  v_respondidas integer;
  v_constraint text;
begin
  if p_usuario_id is null or p_intento_id is null or p_pregunta_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  -- Same key and same order as public.record_answer: (usuario, pregunta) first, then the attempt row.
  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.estado, ie.examen_id, ie.cuenta_para_ranking, ie.iniciado_en
    into v_estado, v_examen_id, v_ranking, v_iniciado
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_IN_PROGRESS', 'estado', v_estado);
  end if;

  select pi.posicion into v_posicion
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id;
  if not found then
    return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');
  end if;

  if p_opcion_id is not null and not exists (
       select 1 from public.opciones_pregunta op where op.id = p_opcion_id and op.pregunta_id = p_pregunta_id) then
    return jsonb_build_object('outcome', 'OPTION_NOT_IN_QUESTION');
  end if;

  select ri.* into v_row
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id
  limit 1;
  v_exists := found;
  v_revision := case when v_exists then v_row.revision else 0 end;

  -- Annulled between start and now: nothing is stored, nothing scores.
  if exists (select 1 from public.preguntas_examen pe
             where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id and pe.anulada) then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
                              'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
    -- Same option (or skip again): no-op whatever the base revision.
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object(
      'outcome', 'OK',
      'estado', case when v_row.opcion_seleccionada_id is null then 'SKIPPED' else 'ANSWERED' end,
      'revision', v_revision,
      'es_primera_respuesta', v_row.es_primer_intento_pregunta,
      'respondidas', v_respondidas,
      'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not null then
    -- DEC-015 open: a recorded answer is not changed (nor cleared) until the product rule exists.
    return jsonb_build_object('outcome', 'ANSWER_LOCKED', 'current_revision', v_revision);
  end if;

  if coalesce(p_base_revision, -1) <> v_revision then
    return jsonb_build_object('outcome', 'REVISION_CONFLICT', 'current_revision', v_revision);
  end if;

  if p_opcion_id is not null then
    select sp.opcion_correcta_id into v_correcta_id
    from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
    if v_correcta_id is null then
      raise exception 'APP_RECORD_ANSWER_MISSING_SOLUTION';
    end if;
  end if;

  -- Client time is advisory: clamp to [0, server gap since the previous event of this attempt].
  select greatest(v_iniciado, coalesce(max(greatest(ri.respondida_en, ri.actualizada_en, ri.creada_en)), v_iniciado))
    into v_last_event
  from public.respuestas_intento ri where ri.intento_id = p_intento_id;
  v_gap_ms := greatest(0, floor(extract(epoch from (clock_timestamp() - v_last_event)) * 1000))::bigint;
  v_ms := least(greatest(coalesce(p_tiempo_respuesta_ms, 0), 0)::bigint, v_gap_ms, 2147483647)::integer;
  v_clamped := coalesce(p_tiempo_respuesta_ms, 0) <> v_ms;
  v_meta := jsonb_strip_nulls(jsonb_build_object(
    'nivelConfianza', p_nivel_confianza,
    'tiempoRecortado', case when v_clamped then true end));

  -- First response of the user to the question (PDF 2.1-05): a skip never consumes it.
  v_first := p_opcion_id is not null and not exists (
    select 1 from public.respuestas_intento prev
    where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id
      and prev.opcion_seleccionada_id is not null
      and (not v_exists or prev.id <> v_row.id));

  if not v_exists then
    begin
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), coalesce(v_ranking, false) and v_first, v_first,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), false, false,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    end;
  else
    -- The row was a skip and is answered now. primera_* (the skip) is never touched.
    begin
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = v_first,
             cuenta_para_ranking = coalesce(v_ranking, false) and v_first,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = false,
             cuenta_para_ranking = false,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    end;
  end if;

  select count(*) into v_respondidas
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);

  update public.intentos_examen
     set total_respondidas = v_respondidas,
         ultima_pregunta_posicion = v_posicion
   where id = p_intento_id;

  return jsonb_build_object(
    'outcome', 'OK',
    'estado', case when p_opcion_id is null then 'SKIPPED' else 'ANSWERED' end,
    'revision', v_revision,
    'es_primera_respuesta', coalesce(v_first, false),
    'respondidas', v_respondidas,
    'noop', false);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_registrar_revocacion_sesiones(p_usuario_id uuid, p_resultado text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_actual text;
begin
    if p_usuario_id is null or p_resultado is null or p_resultado not in ('hecha', 'fallida') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'resultado';
    end if;
    -- Existence on usuario_id only (an ignored downgrade must never look like a missing request) and a row
    -- lock that serialises concurrent recorders of the same request.
    select s.revocacion_sesiones into v_actual
    from public.solicitudes_eliminacion_cuenta s
    where s.usuario_id = p_usuario_id
    for update;
    if not found then
        raise exception using errcode = 'OC404', message = 'NO_DELETION_REQUEST';
    end if;
    -- MASTER2-AUTH-009: 'hecha' is terminal; a late/retried 'fallida' never downgrades it.
    if v_actual = 'hecha' then
        return jsonb_build_object('revocacionSesiones', 'hecha');
    end if;
    update public.solicitudes_eliminacion_cuenta s
    set revocacion_sesiones = p_resultado,
        actualizado_en = case when s.revocacion_sesiones = p_resultado then s.actualizado_en else now() end
    where s.usuario_id = p_usuario_id;
    return jsonb_build_object('revocacionSesiones', p_resultado);
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_solicitar_eliminacion_cuenta(p_usuario_id uuid)
 RETURNS TABLE(estado_cuenta text, solicitada_en timestamp with time zone, ya_pendiente boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
#variable_conflict use_column
declare
    v_p public.perfiles_usuario;
    v_req public.solicitudes_eliminacion_cuenta;
begin
    v_p := private.slice1_lock_profile(p_usuario_id, true);

    if v_p.estado_cuenta = 'activa' then
        update public.perfiles_usuario set estado_cuenta = 'eliminacion_pendiente' where usuario_id = p_usuario_id;
        insert into public.solicitudes_eliminacion_cuenta as s (usuario_id, solicitada_en, actualizado_en)
        values (p_usuario_id, now(), now())
        on conflict (usuario_id) do update
        set solicitada_en = now(), estado = 'solicitada', revocacion_sesiones = 'pendiente',
            paso_ejecucion = 1, reintentos = 0, ultimo_error_codigo = null, actualizado_en = now()
        returning s.* into v_req;
        insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
        values (p_usuario_id, 'cuenta_eliminacion_solicitada', 'perfiles_usuario', p_usuario_id,
                jsonb_build_object('estado_cuenta', 'activa'),
                jsonb_build_object('estado_cuenta', 'eliminacion_pendiente'));
        return query select 'eliminacion_pendiente'::text, v_req.solicitada_en, false;
        return;
    end if;

    -- eliminacion_pendiente (the helper rejected every other state): original row, no writes.
    select * into v_req from public.solicitudes_eliminacion_cuenta s where s.usuario_id = p_usuario_id;
    if not found then
        insert into public.solicitudes_eliminacion_cuenta as s (usuario_id, solicitada_en, actualizado_en)
        values (p_usuario_id, now(), now())
        returning s.* into v_req;
    end if;
    return query select 'eliminacion_pendiente'::text, v_req.solicitada_en, true;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_especialidad_id uuid;
  v_intento_id uuid;
  v_total integer;
  v_frozen integer;
  v_try integer;
  v_marcador text;
  v_sin_marcador uuid;
begin
  if p_usuario_id is null or p_examen_id is null then
    return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
  end if;

  select e.especialidad_id into v_especialidad_id
  from public.examenes e
  where e.id = p_examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= now();
  if not found then
    return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
  end if;

  for v_try in 1..3 loop
    v_sin_marcador := null;
    select ie.id, ie.creado_por_comando into v_intento_id, v_marcador
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id = p_examen_id and ie.estado = 'en_progreso'
    limit 1;
    if found and v_marcador is not null then
      return jsonb_build_object('outcome', 'RESUMED', 'intento_id', v_intento_id);
    end if;
    if found then
      -- Open attempt that this command did not create (legacy start: no entitlement check, no frozen set). It is
      -- never resumed and never frozen late. It is closed below, only once the exam is known to be startable.
      v_sin_marcador := v_intento_id;
    end if;

    select count(*) into v_total
    from public.preguntas_examen pe
    join public.preguntas p on p.id = pe.pregunta_id
    where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa;
    if v_total = 0 then
      return jsonb_build_object('outcome', 'EXAM_EMPTY');
    end if;
    if v_total > 200 then
      return jsonb_build_object('outcome', 'EXAM_TOO_LARGE', 'total', v_total);
    end if;

    if v_sin_marcador is not null then
      -- The caller passed the entitlement policy for THIS start; the unmarked attempt is unreachable through every
      -- route (commands and reads report it as not found) and would block ux_intentos_examen_abierto for ever.
      update public.intentos_examen
         set estado = 'abandonado',
             metadatos = metadatos || jsonb_build_object(
               'cerradoPor', 'app_start_exam_attempt', 'motivo', 'intento_sin_marcador_de_comando')
       where id = v_sin_marcador and estado = 'en_progreso' and creado_por_comando is null;
    end if;

    v_intento_id := null;
    insert into public.intentos_examen as nuevo
      (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento, creado_por_comando)
    values (p_usuario_id, p_examen_id, 'en_progreso', v_total, v_especialidad_id, 'examen', 'app_start_exam_attempt')
    on conflict (usuario_id, examen_id) where estado = 'en_progreso' do nothing
    returning nuevo.id into v_intento_id;

    if v_intento_id is not null then
      v_frozen := private.app_congelar_preguntas_intento(v_intento_id, p_examen_id);
      if v_frozen = 0 or v_frozen > 200 then
        -- The exam changed between the count and the freeze: roll the whole command back; the caller retries.
        raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
      end if;
      if v_frozen <> v_total then
        update public.intentos_examen set total_preguntas = v_frozen where id = v_intento_id;
      end if;
      return jsonb_build_object('outcome', 'CREATED', 'intento_id', v_intento_id);
    end if;
    -- Another transaction holds the open attempt: it is committed by now, the next round returns it.
  end loop;

  raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
end;
$function$
;
CREATE OR REPLACE FUNCTION public.es_admin()
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin')
  );
$function$
;
CREATE OR REPLACE FUNCTION public.es_moderador_o_admin()
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin', 'moderador')
  );
$function$
;
CREATE OR REPLACE FUNCTION public.establecer_actualizado_en()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
    new.actualizado_en = now();
    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  insert into public.perfiles_usuario (
    usuario_id,
    nombre_mostrar,
    avatar_url,
    actualizado_en
  )
  values (
    new.id,
    coalesce(
      new.raw_user_meta_data ->> 'nombre',
      new.raw_user_meta_data ->> 'name',
      split_part(coalesce(new.email, 'usuario'), '@', 1)
    ),
    nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
    now()
  )
  on conflict (usuario_id) do update
  set
    nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
    avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
    actualizado_en = now()
  where public.perfiles_usuario.estado_cuenta = 'activa';

  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.idem_begin(p_actor_id uuid, p_scope text, p_idem_key text, p_request_fingerprint text, p_ttl interval DEFAULT '24:00:00'::interval, p_lease interval DEFAULT '00:00:30'::interval)
 RETURNS TABLE(outcome text, record_id uuid, stored_status smallint, stored_body jsonb, stored_headers jsonb, retry_after_seconds integer, claim_token uuid)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_key text := lower(btrim(p_idem_key));
    v_row public.idempotency_keys%rowtype;
    v_id uuid;
    v_token uuid;
    v_attempt integer := 0;
begin
    if p_actor_id is null then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_ACTOR';
    end if;
    if p_scope is null or char_length(p_scope) not between 1 and 200 then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_SCOPE';
    end if;
    if v_key is null or v_key !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_KEY';
    end if;
    if p_request_fingerprint is null or p_request_fingerprint !~ '^[0-9a-f]{64}$' then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_FINGERPRINT';
    end if;
    if p_ttl is null or p_ttl <= interval '0' or p_ttl > interval '7 days' then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_TTL';
    end if;
    if p_lease is null or p_lease <= interval '0' or p_lease > interval '15 minutes' or p_lease > p_ttl then
        raise exception using errcode = '22023', message = 'IDEM_INVALID_LEASE';
    end if;

    -- Expired row with the same key: gone before we look.
    delete from public.idempotency_keys k
    where k.actor_id = p_actor_id and k.scope = p_scope and k.idem_key = v_key
      and k.expires_at <= now();

    -- Bounded sweep of other expired rows (no background worker).
    delete from public.idempotency_keys k
    where k.id in (
        select e.id from public.idempotency_keys e
        where e.expires_at <= now()
        order by e.expires_at
        limit 100
        for update skip locked
    );

    loop
        v_attempt := v_attempt + 1;
        v_id := null;

        insert into public.idempotency_keys as k (
            actor_id, scope, idem_key, request_fingerprint, state, locked_until, expires_at
        )
        values (
            p_actor_id, p_scope, v_key, p_request_fingerprint, 'in_progress',
            now() + p_lease, now() + p_ttl
        )
        on conflict (actor_id, scope, idem_key) do nothing
        returning k.id, k.claim_token into v_id, v_token;

        if v_id is not null then
            return query select 'NEW'::text, v_id, null::smallint, null::jsonb, null::jsonb, null::integer, v_token;
            return;
        end if;

        select * into v_row
        from public.idempotency_keys k
        where k.actor_id = p_actor_id and k.scope = p_scope and k.idem_key = v_key
        for update;

        exit when found;

        -- The conflicting row was released between our INSERT and SELECT.
        if v_attempt >= 3 then
            raise exception using errcode = '40001', message = 'IDEM_BEGIN_CONTENTION';
        end if;
    end loop;

    if v_row.request_fingerprint <> p_request_fingerprint then
        return query select 'MISMATCH'::text, null::uuid, null::smallint, null::jsonb, null::jsonb, null::integer, null::uuid;
        return;
    end if;

    if v_row.state = 'completed' then
        return query select 'REPLAY'::text, v_row.id, v_row.response_status, v_row.response_body,
                            v_row.response_headers, null::integer, null::uuid;
        return;
    end if;

    if v_row.locked_until is not null and v_row.locked_until > now() then
        return query select 'IN_PROGRESS'::text, null::uuid, null::smallint, null::jsonb, null::jsonb, 1, null::uuid;
        return;
    end if;

    -- Takeover: new lease AND new fencing token; the previous owner's token dies here.
    update public.idempotency_keys k
    set locked_until = now() + p_lease,
        claim_token = gen_random_uuid()
    where k.id = v_row.id
    returning k.claim_token into v_token;

    return query select 'STALE'::text, v_row.id, null::smallint, null::jsonb, null::jsonb, null::integer, v_token;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.preparar_intento_examen()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    v_tipo_examen text;
    v_numero int;
begin
    select tipo_examen
    into v_tipo_examen
    from public.examenes
    where id = new.examen_id;

    new.tipo_examen_snapshot = coalesce(new.tipo_examen_snapshot, v_tipo_examen);

    select count(*) + 1
    into v_numero
    from public.intentos_examen ie
    where ie.usuario_id = new.usuario_id
      and ie.examen_id = new.examen_id
      and ie.id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);

    new.intento_numero_usuario = coalesce(new.intento_numero_usuario, v_numero);
    new.es_primer_intento_examen = (new.intento_numero_usuario = 1);

    -- Regla de cliente: los segundos intentos se guardan para progreso, pero no para ranking.
    new.cuenta_para_ranking = coalesce(new.cuenta_para_ranking, new.es_primer_intento_examen);

    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.record_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_seleccionada_id uuid)
 RETURNS TABLE(respuesta_id uuid, es_correcta boolean, opcion_correcta_id uuid, es_primer_intento_pregunta boolean, cuenta_para_ranking boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_examen_id uuid;
  v_cuenta_para_ranking boolean;
  v_correcta_id uuid;
  v_anulada boolean;
  v_first boolean;
  v_constraint_name text;
  v_existing public.respuestas_intento%rowtype;
  v_response_id uuid;
  v_response_correct boolean;
  v_response_correcta_id uuid;
  v_response_first boolean;
  v_response_ranking boolean;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.examen_id, ie.cuenta_para_ranking into v_examen_id, v_cuenta_para_ranking
    from public.intentos_examen ie
   where ie.id = p_intento_id and ie.usuario_id = p_usuario_id and ie.estado = 'en_progreso'
     for update;
  if not found then raise exception 'RECORD_ANSWER_INVALID_ATTEMPT'; end if;

  select pe.anulada into v_anulada
    from public.preguntas_examen pe
   where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id;
  if not found then raise exception 'RECORD_ANSWER_QUESTION_NOT_IN_ATTEMPT'; end if;

  if not exists (select 1 from public.opciones_pregunta op
                  where op.id = p_opcion_seleccionada_id and op.pregunta_id = p_pregunta_id) then
    raise exception 'RECORD_ANSWER_OPTION_NOT_IN_QUESTION';
  end if;

  select sp.opcion_correcta_id into v_correcta_id
    from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
  if v_correcta_id is null then raise exception 'RECORD_ANSWER_MISSING_SOLUTION'; end if;

  if v_anulada then
    return query select null::uuid, null::boolean, v_correcta_id, false, false;
    return;
  end if;

  select ri.* into v_existing from public.respuestas_intento ri
   where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id limit 1;
  if found then
    return query select v_existing.id, v_existing.es_correcta, v_existing.opcion_correcta_id,
                        v_existing.es_primer_intento_pregunta, v_existing.cuenta_para_ranking;
    return;
  end if;

  select not exists (select 1 from public.respuestas_intento previous
                      where previous.usuario_id = p_usuario_id and previous.pregunta_id = p_pregunta_id)
    into v_first;

  begin
    insert into public.respuestas_intento as response_row (
      intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id,
      opcion_correcta_id, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta
    ) values (
      p_intento_id, p_pregunta_id, p_opcion_seleccionada_id, p_opcion_seleccionada_id = v_correcta_id,
      p_usuario_id, v_examen_id, v_correcta_id, now(), v_cuenta_para_ranking and v_first, v_first
    )
    returning response_row.id, response_row.es_correcta, response_row.opcion_correcta_id,
              response_row.es_primer_intento_pregunta, response_row.cuenta_para_ranking
      into v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
    return query select v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
  exception when unique_violation then
    get stacked diagnostics v_constraint_name = constraint_name;
    if v_constraint_name is distinct from 'ux_respuestas_first_response' or v_first is not true then
      raise;
    end if;
    insert into public.respuestas_intento as response_row (
      intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id,
      opcion_correcta_id, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta
    ) values (
      p_intento_id, p_pregunta_id, p_opcion_seleccionada_id, p_opcion_seleccionada_id = v_correcta_id,
      p_usuario_id, v_examen_id, v_correcta_id, now(), false, false
    )
    returning response_row.id, response_row.es_correcta, response_row.opcion_correcta_id,
              response_row.es_primer_intento_pregunta, response_row.cuenta_para_ranking
      into v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
    return query select v_response_id, v_response_correct, v_response_correcta_id, v_response_first, v_response_ranking;
  end;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.validar_pregunta_examen()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
  v_tipo_examen text;
  v_tipo_pregunta text;
begin
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

  if not (
       (v_tipo_examen = 'rafaga' and v_tipo_pregunta = 'rafaga')
    or (v_tipo_examen = 'razonado' and v_tipo_pregunta = 'razonada')
    or (v_tipo_examen = 'inmersivo' and v_tipo_pregunta = 'inmersiva')
  ) then
    raise exception 'QUESTION_EXAM_TYPE_MISMATCH';
  end if;

  return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.validar_pregunta_examen_mismo_tipo()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    v_tipo_examen text;
    v_tipo_pregunta text;
begin
    select tipo_examen into v_tipo_examen
    from public.examenes
    where id = new.examen_id;

    select tipo_pregunta into v_tipo_pregunta
    from public.preguntas
    where id = new.pregunta_id;

    if v_tipo_examen = 'inmersivo' and v_tipo_pregunta <> 'inmersiva' then
        raise exception 'Un examen inmersivo solo puede contener preguntas inmersivas';
    end if;

    if v_tipo_examen = 'rafaga' and v_tipo_pregunta <> 'rafaga' then
        raise exception 'Un examen rafaga solo puede contener preguntas rafaga';
    end if;

    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.validar_recurso_inmersivo()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
DECLARE
    v_tipo text;
BEGIN
    SELECT tipo_pregunta
      INTO v_tipo
    FROM public.preguntas
    WHERE id = NEW.pregunta_id;

    IF v_tipo <> 'inmersiva' THEN
        RAISE EXCEPTION
            'Solo las preguntas inmersivas pueden tener recursos';
    END IF;

    RETURN NEW;
END;
$function$
;
CREATE OR REPLACE FUNCTION public.validar_recurso_solo_pregunta_inmersiva()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    v_tipo_pregunta text;
begin
    select tipo_pregunta into v_tipo_pregunta
    from public.preguntas
    where id = new.pregunta_id;

    if v_tipo_pregunta <> 'inmersiva' then
        raise exception 'Solo las preguntas inmersivas pueden tener recursos';
    end if;

    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.validar_solucion_opcion_misma_pregunta()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    pregunta_de_opcion uuid;
begin
    select pregunta_id
    into pregunta_de_opcion
    from public.opciones_pregunta
    where id = new.opcion_correcta_id;

    if pregunta_de_opcion is null or pregunta_de_opcion <> new.pregunta_id then
        raise exception 'La opcion correcta no pertenece a la misma pregunta';
    end if;

    return new;
end;
$function$
;
CREATE OR REPLACE FUNCTION public.validar_y_calcular_respuesta_intento()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
    v_usuario_id uuid;
    v_examen_id uuid;
    v_cuenta_para_ranking boolean;
    v_pregunta_de_opcion uuid;
    v_opcion_correcta uuid;
    v_posicion int;
    v_etiqueta_id uuid;
    v_respuestas_previas_usuario int;
begin
    select usuario_id, examen_id, cuenta_para_ranking
    into v_usuario_id, v_examen_id, v_cuenta_para_ranking
    from public.intentos_examen
    where id = new.intento_id;

    if v_usuario_id is null or v_examen_id is null then
        raise exception 'El intento de examen no existe o no tiene usuario/examen asociado';
    end if;

    new.usuario_id = coalesce(new.usuario_id, v_usuario_id);
    new.examen_id = coalesce(new.examen_id, v_examen_id);
    new.cuenta_para_ranking = coalesce(new.cuenta_para_ranking, v_cuenta_para_ranking);

    select posicion
    into v_posicion
    from public.preguntas_examen
    where examen_id = v_examen_id
      and pregunta_id = new.pregunta_id;

    new.posicion_pregunta = coalesce(new.posicion_pregunta, v_posicion);

    select ep.etiqueta_id
    into v_etiqueta_id
    from public.etiquetas_pregunta ep
    where ep.pregunta_id = new.pregunta_id
    order by ep.creado_en asc
    limit 1;

    new.etiqueta_principal_id = coalesce(new.etiqueta_principal_id, v_etiqueta_id);

    if new.opcion_seleccionada_id is not null then
        select pregunta_id
        into v_pregunta_de_opcion
        from public.opciones_pregunta
        where id = new.opcion_seleccionada_id;

        if v_pregunta_de_opcion is null or v_pregunta_de_opcion <> new.pregunta_id then
            raise exception 'La opcion seleccionada no pertenece a la pregunta indicada';
        end if;

        select opcion_correcta_id
        into v_opcion_correcta
        from public.soluciones_pregunta
        where pregunta_id = new.pregunta_id;

        new.opcion_correcta_id = coalesce(new.opcion_correcta_id, v_opcion_correcta);

        if v_opcion_correcta is not null then
            new.es_correcta = (new.opcion_seleccionada_id = v_opcion_correcta);
        end if;
    end if;

    select count(*)
    into v_respuestas_previas_usuario
    from public.respuestas_intento ri
    where ri.usuario_id = v_usuario_id
      and ri.pregunta_id = new.pregunta_id
      and ri.id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);

    new.es_primer_intento_pregunta = coalesce(new.es_primer_intento_pregunta, v_respuestas_previas_usuario = 0);

    if new.respondida_en is null and new.opcion_seleccionada_id is not null then
        new.respondida_en = now();
    end if;

    if new.nivel_confianza = 'azar' then
        new.respondida_al_azar = true;
    end if;

    return new;
end;
$function$
;
CREATE TRIGGER trg_auth_user_created AFTER INSERT OR UPDATE OF email, raw_user_meta_data ON auth.users FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();
CREATE TRIGGER trg_aceptaciones_legales_append_only BEFORE DELETE OR UPDATE ON public.aceptaciones_legales FOR EACH ROW EXECUTE FUNCTION private.guard_aceptaciones_append_only();
CREATE TRIGGER trg_comunidades_autonomas_actualizado_en BEFORE UPDATE ON public.comunidades_autonomas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_documentos_legales_actualizado_en BEFORE UPDATE ON public.documentos_legales FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_especialidades_actualizado_en BEFORE UPDATE ON public.especialidades FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_etiquetas_actualizado_en BEFORE UPDATE ON public.etiquetas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_examenes_actualizado_en BEFORE UPDATE ON public.examenes FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_examenes_bloquear_cambio_tipo BEFORE UPDATE OF tipo_examen ON public.examenes FOR EACH ROW WHEN ((old.tipo_examen IS DISTINCT FROM new.tipo_examen)) EXECUTE FUNCTION private.tg_examenes_bloquear_cambio_tipo();
CREATE TRIGGER trg_examenes_total_preguntas_guard BEFORE UPDATE OF total_preguntas ON public.examenes FOR EACH ROW EXECUTE FUNCTION private.tg_examenes_total_preguntas_guard();
CREATE TRIGGER trg_examenes_total_preguntas_insert BEFORE INSERT ON public.examenes FOR EACH ROW EXECUTE FUNCTION private.tg_examenes_total_preguntas_insert();
CREATE TRIGGER trg_examenes_total_preguntas_publicado BEFORE UPDATE OF total_preguntas ON public.examenes FOR EACH ROW WHEN (((old.total_preguntas > 0) AND (new.total_preguntas = 0) AND (new.publicado_en IS NOT NULL) AND new.activo)) EXECUTE FUNCTION private.tg_examenes_publicado_no_vacio();
CREATE TRIGGER trg_intentos_examen_actualizado_en BEFORE UPDATE ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_intentos_examen_creado_por_comando_inmutable BEFORE UPDATE OF creado_por_comando ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION private.tg_intentos_examen_creado_por_comando_inmutable();
CREATE TRIGGER trg_intentos_examen_especialidad_snapshot_inmutable BEFORE UPDATE OF especialidad_snapshot_id ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION private.tg_intentos_examen_especialidad_snapshot_inmutable();
CREATE TRIGGER trg_preparar_intento_examen BEFORE INSERT ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION public.preparar_intento_examen();
CREATE TRIGGER trg_lotes_importacion_preguntas_actualizado_en BEFORE UPDATE ON public.lotes_importacion_preguntas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_opciones_pregunta_actualizado_en BEFORE UPDATE ON public.opciones_pregunta FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_perfiles_usuario_actualizado_en BEFORE UPDATE ON public.perfiles_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_perfiles_usuario_estado_cuenta_guard BEFORE INSERT OR UPDATE ON public.perfiles_usuario FOR EACH ROW EXECUTE FUNCTION private.guard_perfiles_estado_cuenta();
CREATE TRIGGER trg_perfiles_usuario_server_owned_guard BEFORE INSERT OR UPDATE ON public.perfiles_usuario FOR EACH ROW EXECUTE FUNCTION private.guard_perfiles_server_owned();
CREATE TRIGGER trg_perfiles_usuario_validar_referencias BEFORE INSERT OR UPDATE OF especialidad_principal_id, comunidad_principal_id ON public.perfiles_usuario FOR EACH ROW EXECUTE FUNCTION private.validate_perfiles_refs();
CREATE TRIGGER trg_preguntas_activa_total_preguntas AFTER UPDATE OF activa ON public.preguntas FOR EACH ROW WHEN ((old.activa IS DISTINCT FROM new.activa)) EXECUTE FUNCTION private.tg_preguntas_activa_total_preguntas();
CREATE TRIGGER trg_preguntas_actualizado_en BEFORE UPDATE ON public.preguntas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_preguntas_bloquear_cambio_tipo BEFORE UPDATE OF tipo_pregunta ON public.preguntas FOR EACH ROW WHEN ((old.tipo_pregunta IS DISTINCT FROM new.tipo_pregunta)) EXECUTE FUNCTION private.tg_preguntas_bloquear_cambio_tipo();
CREATE TRIGGER trg_preguntas_codigo_pregunta_insert BEFORE INSERT ON public.preguntas FOR EACH ROW EXECUTE FUNCTION private.tg_preguntas_codigo_pregunta();
CREATE TRIGGER trg_preguntas_codigo_pregunta_update BEFORE UPDATE OF codigo_pregunta ON public.preguntas FOR EACH ROW EXECUTE FUNCTION private.tg_preguntas_codigo_pregunta();
CREATE TRIGGER trg_preguntas_examen_total_preguntas AFTER INSERT OR DELETE OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION private.tg_preguntas_examen_total_preguntas();
CREATE TRIGGER trg_validar_pregunta_examen BEFORE INSERT OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION public.validar_pregunta_examen();
CREATE TRIGGER trg_validar_pregunta_examen_mismo_tipo BEFORE INSERT OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION public.validar_pregunta_examen_mismo_tipo();
CREATE TRIGGER trg_recursos_pregunta_actualizado_en BEFORE UPDATE ON public.recursos_pregunta FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_validar_recurso_inmersivo BEFORE INSERT OR UPDATE OF pregunta_id ON public.recursos_pregunta FOR EACH ROW EXECUTE FUNCTION public.validar_recurso_inmersivo();
CREATE TRIGGER trg_validar_recurso_solo_pregunta_inmersiva BEFORE INSERT OR UPDATE ON public.recursos_pregunta FOR EACH ROW EXECUTE FUNCTION public.validar_recurso_solo_pregunta_inmersiva();
CREATE TRIGGER trg_reportes_contenido_actualizado_en BEFORE UPDATE ON public.reportes_contenido FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_validar_y_calcular_respuesta_intento BEFORE INSERT OR UPDATE ON public.respuestas_intento FOR EACH ROW EXECUTE FUNCTION public.validar_y_calcular_respuesta_intento();
CREATE TRIGGER trg_sesiones_estudio_actualizado_en BEFORE UPDATE ON public.sesiones_estudio FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_soluciones_pregunta_actualizado_en BEFORE UPDATE ON public.soluciones_pregunta FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_validar_solucion_opcion_misma_pregunta BEFORE INSERT OR UPDATE ON public.soluciones_pregunta FOR EACH ROW EXECUTE FUNCTION public.validar_solucion_opcion_misma_pregunta();
ALTER TABLE private.identity_backfill_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.abandonos_examen ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.acciones_moderacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.aceptaciones_legales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.atribucion_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auditoria_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comunidades_autonomas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comunidades_objetivo_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contenido_desbloqueado_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.documentos_legales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.especialidades ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.estado_premium_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.etiquetas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.etiquetas_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.eventos_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.examenes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.intentos_examen ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.lotes_importacion_preguntas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.opciones_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.perfiles_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.perfiles_usuario_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.preguntas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.preguntas_examen ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.preguntas_intento ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recursos_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reportes_contenido ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.respuestas_intento ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.resumen_intento_etiqueta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.roles_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.roles_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sesiones_estudio ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.solicitudes_eliminacion_cuenta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.soluciones_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.usuario ENABLE ROW LEVEL SECURITY;
CREATE POLICY abandonos_examen_insert_propio ON public.abandonos_examen AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY abandonos_examen_select_staff_o_propio ON public.abandonos_examen AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY acciones_moderacion_staff_all ON public.acciones_moderacion AS PERMISSIVE FOR ALL TO authenticated USING (public.es_moderador_o_admin()) WITH CHECK (public.es_moderador_o_admin());
CREATE POLICY auditoria_admin_select ON public.auditoria_log AS PERMISSIVE FOR SELECT TO authenticated USING (public.es_admin());
CREATE POLICY comunidades_admin_all ON public.comunidades_autonomas AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY comunidades_select_publico ON public.comunidades_autonomas AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR public.es_admin()));
CREATE POLICY contenido_desbloqueado_select_propio ON public.contenido_desbloqueado_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY documentos_legales_select_publico ON public.documentos_legales AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activo = true) OR public.es_admin()));
CREATE POLICY especialidades_admin_all ON public.especialidades AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY especialidades_select_publico ON public.especialidades AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR public.es_admin()));
CREATE POLICY estado_premium_select_propio ON public.estado_premium_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY etiquetas_admin_all ON public.etiquetas AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY etiquetas_select_publico ON public.etiquetas AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR public.es_admin()));
CREATE POLICY etiquetas_pregunta_admin_all ON public.etiquetas_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY etiquetas_pregunta_select_gratuitas ON public.etiquetas_pregunta AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((EXISTS ( SELECT 1
   FROM ((public.preguntas p
     JOIN public.preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN public.examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = etiquetas_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text)))) OR public.es_admin()));
CREATE POLICY examenes_admin_all ON public.examenes AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY examenes_select_activos ON public.examenes AS PERMISSIVE FOR SELECT TO anon, authenticated USING ((((activo = true) AND (publicado_en IS NOT NULL)) OR public.es_admin()));
CREATE POLICY product_metrics_intentos_select_propio ON public.intentos_examen AS PERMISSIVE FOR SELECT TO authenticated USING ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY lotes_importacion_admin_all ON public.lotes_importacion_preguntas AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY opciones_pregunta_admin_all ON public.opciones_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY opciones_pregunta_select_gratuitas ON public.opciones_pregunta AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((EXISTS ( SELECT 1
   FROM ((public.preguntas p
     JOIN public.preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN public.examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = opciones_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text)))) OR public.es_admin()));
CREATE POLICY perfiles_usuario_insert_propio ON public.perfiles_usuario AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY perfiles_usuario_select_propio ON public.perfiles_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY perfiles_usuario_update_propio ON public.perfiles_usuario AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY preguntas_admin_all ON public.preguntas AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY preguntas_select_gratuitas ON public.preguntas AS PERMISSIVE FOR SELECT TO anon, authenticated USING ((((activa = true) AND (EXISTS ( SELECT 1
   FROM (public.preguntas_examen pe
     JOIN public.examenes e ON ((e.id = pe.examen_id)))
  WHERE ((pe.pregunta_id = preguntas.id) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text))))) OR public.es_admin()));
CREATE POLICY preguntas_examen_admin_all ON public.preguntas_examen AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY preguntas_examen_select_gratuitas ON public.preguntas_examen AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((EXISTS ( SELECT 1
   FROM public.examenes e
  WHERE ((e.id = preguntas_examen.examen_id) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text)))) OR public.es_admin()));
CREATE POLICY recursos_pregunta_admin_all ON public.recursos_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY recursos_pregunta_select_premium ON public.recursos_pregunta AS PERMISSIVE FOR SELECT TO authenticated USING (((activo = true) AND (EXISTS ( SELECT 1
   FROM public.estado_premium_usuario epu
  WHERE ((epu.usuario_id = ( SELECT auth.uid() AS uid)) AND (epu.es_premium = true)))) AND (EXISTS ( SELECT 1
   FROM ((public.preguntas p
     JOIN public.preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN public.examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = recursos_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'premium'::text) AND (e.tipo_examen = 'inmersivo'::text))))));
CREATE POLICY reportes_contenido_insert_auth ON public.reportes_contenido AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK (((reportado_por = ( SELECT auth.uid() AS uid)) AND (estado = 'pendiente'::text) AND (revisado_por IS NULL) AND (revisado_en IS NULL) AND (tickets_otorgados = 0) AND (recompensa_entregada = false) AND (accion_pendiente = false) AND (creado_en = now()) AND (actualizado_en = now())));
CREATE POLICY reportes_contenido_select_staff ON public.reportes_contenido AS PERMISSIVE FOR SELECT TO authenticated USING (public.es_moderador_o_admin());
CREATE POLICY product_metrics_respuestas_select_propio ON public.respuestas_intento AS PERMISSIVE FOR SELECT TO authenticated USING ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY canonical_v2_attempt_summary_select ON public.resumen_intento_etiqueta AS PERMISSIVE FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM public.intentos_examen ie
  WHERE ((ie.id = resumen_intento_etiqueta.intento_id) AND (ie.usuario_id = ( SELECT auth.uid() AS uid))))));
CREATE POLICY roles_app_select_auth ON public.roles_app AS PERMISSIVE FOR SELECT TO authenticated USING (true);
CREATE POLICY roles_usuario_select_propio ON public.roles_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY sesiones_estudio_todo_propio ON public.sesiones_estudio AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR public.es_admin()));
CREATE POLICY soluciones_pregunta_admin_all ON public.soluciones_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (public.es_admin()) WITH CHECK (public.es_admin());
CREATE POLICY oposibots_consumer_profile_select ON public.usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((( SELECT auth.uid() AS uid) IS NOT NULL) AND (auth_user_id = ( SELECT auth.uid() AS uid))));
REVOKE ALL ON FUNCTION private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.guard_aceptaciones_append_only() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.guard_perfiles_estado_cuenta() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.guard_perfiles_server_owned() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.recalcular_total_preguntas_examen(p_examen_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.slice1_int(p_json jsonb, p_key text, p_min integer, p_max integer) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.slice1_lock_profile(p_usuario_id uuid, p_allow_pending boolean) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.slice1_profile_json(p_usuario_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.slice1_text(p_json jsonb, p_key text, p_max integer) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.slice1_trim(p_text text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.slice1_uuid(p_text text, p_field text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_bloquear_cambio_tipo() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_publicado_no_vacio() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_total_preguntas_guard() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_total_preguntas_insert() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_intentos_examen_creado_por_comando_inmutable() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_intentos_examen_especialidad_snapshot_inmutable() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_activa_total_preguntas() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_bloquear_cambio_tipo() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_codigo_pregunta() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_examen_total_preguntas() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.validate_perfiles_refs() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_record_report_action(p_reporte_id uuid, p_estado text, p_moderador_id uuid, p_tipo_accion text, p_notas text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_role_code_canonical(p_codigo text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[]) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid, p_expected_role_ids uuid[]) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_abandon_exam_attempt(p_usuario_id uuid, p_intento_id uuid, p_posicion integer, p_motivo text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_aceptar_documentos_legales(p_usuario_id uuid, p_documentos uuid[], p_plataforma text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_actualizar_perfil(p_usuario_id uuid, p_patch jsonb, p_if_match timestamp with time zone) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_completar_onboarding(p_usuario_id uuid, p_username text, p_nombre text, p_apellidos text, p_especialidad_id uuid, p_documentos uuid[], p_plataforma text, p_attribution jsonb, p_referral_code text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_my_exams_collection(p_usuario_id uuid, p_tipo_examen text, p_comunidad_id uuid, p_anio integer, p_despues_en timestamp with time zone, p_despues_id uuid, p_limite integer, p_con_grupos boolean) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_record_attempt_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_id uuid, p_tiempo_respuesta_ms integer, p_base_revision integer, p_nivel_confianza integer) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_registrar_revocacion_sesiones(p_usuario_id uuid, p_resultado text) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_solicitar_eliminacion_cuenta(p_usuario_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.es_admin() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.es_moderador_o_admin() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.establecer_actualizado_en() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.handle_new_auth_user() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.idem_begin(p_actor_id uuid, p_scope text, p_idem_key text, p_request_fingerprint text, p_ttl interval, p_lease interval) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.preparar_intento_examen() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.record_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_seleccionada_id uuid) FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_pregunta_examen() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_pregunta_examen_mismo_tipo() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_recurso_inmersivo() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_recurso_solo_pregunta_inmersiva() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_solucion_opcion_misma_pregunta() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_y_calcular_respuesta_intento() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE private.identity_backfill_report FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.abandonos_examen FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.acciones_moderacion FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.aceptaciones_legales FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.atribucion_usuario FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.auditoria_log FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.comunidades_autonomas FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.comunidades_objetivo_usuario FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.contenido_desbloqueado_usuario FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.documentos_legales FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.especialidades FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.estado_premium_usuario FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.etiquetas FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.etiquetas_pregunta FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.eventos_app FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.examenes FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.idempotency_keys FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.intentos_examen FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.lotes_importacion_preguntas FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.opciones_pregunta FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.perfiles_usuario FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.perfiles_usuario_detalle FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.preguntas FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.preguntas_examen FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.preguntas_intento FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.recursos_pregunta FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.reportes_contenido FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.respuestas_intento FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.resumen_intento_etiqueta FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.roles_app FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.roles_usuario FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.sesiones_estudio FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.solicitudes_eliminacion_cuenta FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.soluciones_pregunta FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON TABLE public.usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.admin_record_report_action(p_reporte_id uuid, p_estado text, p_moderador_id uuid, p_tipo_accion text, p_notas text) TO service_role;
GRANT EXECUTE ON FUNCTION public.admin_role_code_canonical(p_codigo text) TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[]) TO service_role;
GRANT EXECUTE ON FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid, p_expected_role_ids uuid[]) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_abandon_exam_attempt(p_usuario_id uuid, p_intento_id uuid, p_posicion integer, p_motivo text) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_aceptar_documentos_legales(p_usuario_id uuid, p_documentos uuid[], p_plataforma text) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_actualizar_perfil(p_usuario_id uuid, p_patch jsonb, p_if_match timestamp with time zone) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_completar_onboarding(p_usuario_id uuid, p_username text, p_nombre text, p_apellidos text, p_especialidad_id uuid, p_documentos uuid[], p_plataforma text, p_attribution jsonb, p_referral_code text) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_my_exams_collection(p_usuario_id uuid, p_tipo_examen text, p_comunidad_id uuid, p_anio integer, p_despues_en timestamp with time zone, p_despues_id uuid, p_limite integer, p_con_grupos boolean) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_record_attempt_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_id uuid, p_tiempo_respuesta_ms integer, p_base_revision integer, p_nivel_confianza integer) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_registrar_revocacion_sesiones(p_usuario_id uuid, p_resultado text) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_solicitar_eliminacion_cuenta(p_usuario_id uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.es_admin() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.es_moderador_o_admin() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.establecer_actualizado_en() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.handle_new_auth_user() TO service_role;
GRANT EXECUTE ON FUNCTION public.idem_begin(p_actor_id uuid, p_scope text, p_idem_key text, p_request_fingerprint text, p_ttl interval, p_lease interval) TO service_role;
GRANT EXECUTE ON FUNCTION public.preparar_intento_examen() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.record_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_seleccionada_id uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.validar_pregunta_examen() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_pregunta_examen_mismo_tipo() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_recurso_inmersivo() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_recurso_solo_pregunta_inmersiva() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_solucion_opcion_misma_pregunta() TO PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_y_calcular_respuesta_intento() TO PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.abandonos_examen TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.abandonos_examen TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.abandonos_examen TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.acciones_moderacion TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.acciones_moderacion TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.acciones_moderacion TO anon;
GRANT INSERT, SELECT ON TABLE public.aceptaciones_legales TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.atribucion_usuario TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.auditoria_log TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.auditoria_log TO anon, authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.comunidades_autonomas TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.comunidades_autonomas TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.comunidades_autonomas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.comunidades_objetivo_usuario TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.contenido_desbloqueado_usuario TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.contenido_desbloqueado_usuario TO anon, authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.documentos_legales TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.documentos_legales TO anon, authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.especialidades TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.especialidades TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.especialidades TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.estado_premium_usuario TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.estado_premium_usuario TO anon, authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.etiquetas TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.etiquetas TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.etiquetas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.etiquetas_pregunta TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.etiquetas_pregunta TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.etiquetas_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.eventos_app TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.examenes TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.examenes TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.examenes TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.idempotency_keys TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.intentos_examen TO service_role;
GRANT SELECT ON TABLE public.intentos_examen TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.lotes_importacion_preguntas TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.lotes_importacion_preguntas TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.lotes_importacion_preguntas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.opciones_pregunta TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.opciones_pregunta TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.opciones_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.perfiles_usuario TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.perfiles_usuario TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario_detalle TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.preguntas TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.preguntas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas_examen TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.preguntas_examen TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.preguntas_examen TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas_intento TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.recursos_pregunta TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.recursos_pregunta TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.recursos_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.reportes_contenido TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.reportes_contenido TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.reportes_contenido TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.respuestas_intento TO service_role;
GRANT SELECT ON TABLE public.respuestas_intento TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.resumen_intento_etiqueta TO service_role;
GRANT SELECT ON TABLE public.resumen_intento_etiqueta TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.roles_app TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.roles_app TO anon, authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.roles_usuario TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.roles_usuario TO anon, authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.sesiones_estudio TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.sesiones_estudio TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.sesiones_estudio TO anon;
GRANT INSERT, SELECT, UPDATE ON TABLE public.solicitudes_eliminacion_cuenta TO service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.soluciones_pregunta TO service_role;
GRANT DELETE, INSERT, MAINTAIN, SELECT, UPDATE ON TABLE public.soluciones_pregunta TO authenticated;
GRANT MAINTAIN, SELECT ON TABLE public.soluciones_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.usuario TO service_role;
GRANT MAINTAIN, SELECT ON TABLE public.usuario TO anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT, UPDATE, USAGE ON SEQUENCES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT, UPDATE, USAGE ON SEQUENCES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT, UPDATE, USAGE ON SEQUENCES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT, UPDATE, USAGE ON SEQUENCES TO service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLES TO service_role;
COMMENT ON TABLE public.eventos_app IS 'Eventos ligeros recogidos en segundo plano para entender uso de la app sin molestar al usuario.';
COMMENT ON TABLE public.preguntas_examen IS 'Vínculo M:N pregunta <-> examen (desde 2026-09-17). Primera respuesta y ranking son globales por (usuario, pregunta): una pregunta compartida puntúa ranking sólo la primera vez que el usuario la responde en cualquier examen (decisión C-09).';
COMMENT ON COLUMN public.examenes.portada_mime_type IS 'Tipo MIME de la portada. Ktor admite jpeg/png/webp; el CHECK acepta también gif por coherencia con el bucket.';
COMMENT ON COLUMN public.examenes.portada_storage_path IS 'Objeto en el bucket PRIVADO exam-resources: exam-covers/<examen_id>/<uuid>.<ext>. La URL se firma en lectura y nunca se guarda.';
COMMENT ON COLUMN public.examenes.total_preguntas IS 'Nº de preguntas ACTIVAS vinculadas. Lo mantiene la base (M1: triggers trg_*_total_preguntas); no se escribe desde la API.';
COMMENT ON COLUMN public.intentos_examen.cuenta_para_ranking IS 'Solo debe ser true en el primer intento válido del usuario para ese examen.';
COMMENT ON COLUMN public.preguntas_examen.anulada IS 'La pregunta fue anulada por el tribunal EN ESTE examen. No puntúa: ni acierto ni fallo ni sin responder.';
COMMENT ON COLUMN public.preguntas_examen.motivo_anulacion IS 'Motivo opcional de la anulación. Sólo con anulada = true.';
COMMENT ON COLUMN public.reportes_contenido.accion_pendiente IS 'true only between the Ktor compare-and-set that closes the report and the admin_record_report_action call that records its moderation action (20260930102000)';
COMMENT ON COLUMN public.respuestas_intento.dificultad_percibida IS 'Dato opcional preguntado puntualmente: facil, normal, dificil o muy_dificil.';
COMMENT ON COLUMN public.respuestas_intento.nivel_confianza IS 'Dato opcional preguntado puntualmente: seguro, duda o azar.';
COMMENT ON CONSTRAINT recursos_pregunta_ubicacion_formato_chk ON public.recursos_pregunta IS 'Nunca rutas locales ni URLs firmadas persistidas: url_externa sólo http(s) y sólo para enlace; los ficheros van a recursos-pregunta/preguntas/<pregunta_id>/<id>.<ext> (Fase 4, punto 7).';
COMMENT ON FUNCTION private.tg_preguntas_codigo_pregunta() IS 'BEFORE INSERT: asigna SIEMPRE codigo_pregunta (ignora el recibido). BEFORE UPDATE OF codigo_pregunta: QUESTION_CODE_IMMUTABLE. INSERT ... ON CONFLICT (codigo_pregunta) nunca hace match (el código es siempre nuevo). INSERT ... ON CONFLICT (id) DO UPDATE SET codigo_pregunta = excluded.codigo_pregunta lanza QUESTION_CODE_IMMUTABLE: los upserts deben excluir la columna. Cargas data-only con triggers activos regenerarían códigos: usar session_replication_role = replica.';
