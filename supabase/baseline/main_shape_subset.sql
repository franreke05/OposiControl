-- GENERADO por scripts/db-pg17-bootstrap-extract.mjs (no editar a mano; ver supabase/baseline/README.md).
-- Forma de MAIN (DDL de catalogo, SIN filas) de los objetos que tocan las migraciones pendientes, copiada verbatim de
-- la captura READ-ONLY de MAIN del gate local. NO es el baseline completo de MAIN.
-- Tablas (28): public.abandonos_examen, public.acciones_moderacion, public.atribucion_usuario, public.auditoria_log, public.comunidades_autonomas, public.comunidades_objetivo_usuario, public.documentos_legales, public.especialidades, public.etiquetas, public.etiquetas_pregunta, public.eventos_app, public.examenes, public.intentos_examen, public.lotes_importacion_preguntas, public.opciones_pregunta, public.perfiles_usuario, public.perfiles_usuario_detalle, public.preguntas, public.preguntas_examen, public.preguntas_intento, public.reportes_contenido, public.respuestas_intento, public.resumen_intento_etiqueta, public.roles_app, public.roles_usuario, public.sesiones_estudio, public.soluciones_pregunta, public.usuario
-- Funciones (20): private.tg_examenes_bloquear_cambio_tipo, private.tg_examenes_publicado_no_vacio, private.tg_examenes_total_preguntas_guard, private.tg_examenes_total_preguntas_insert, private.tg_preguntas_activa_total_preguntas, private.tg_preguntas_bloquear_cambio_tipo, private.tg_preguntas_codigo_pregunta, private.tg_preguntas_examen_total_preguntas, public.admin_role_code_canonical, public.admin_sync_roles, public.es_admin, public.es_moderador_o_admin, public.establecer_actualizado_en, public.handle_new_auth_user, public.preparar_intento_examen, public.record_event, public.validar_pregunta_examen, public.validar_pregunta_examen_mismo_tipo, public.validar_solucion_opcion_misma_pregunta, public.validar_y_calcular_respuesta_intento
-- Secuencias (0): -
SET check_function_bodies = false;
SET search_path = public, extensions;
SET statement_timeout = 0;
SET check_function_bodies = false;
CREATE SCHEMA IF NOT EXISTS public;
CREATE SCHEMA IF NOT EXISTS private;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;
CREATE TABLE public.abandonos_examen (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, intento_id uuid, examen_id uuid, pregunta_id uuid, posicion_pregunta integer, tiempo_transcurrido_segundos integer, total_preguntas integer, motivo_detectado text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.acciones_moderacion (id uuid NOT NULL DEFAULT gen_random_uuid(), reporte_id uuid, moderador_id uuid, tipo_accion text NOT NULL, notas text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.atribucion_usuario (usuario_id uuid NOT NULL, fuente text, canal text, campania text, referido_por uuid, codigo_referido text, plataforma text, creado_en timestamp with time zone NOT NULL DEFAULT now(), metadatos jsonb NOT NULL DEFAULT '{}'::jsonb);
CREATE TABLE public.auditoria_log (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, accion text NOT NULL, tabla_afectada text, registro_id uuid, antes jsonb, despues jsonb, ip text, user_agent text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.comunidades_autonomas (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, slug text NOT NULL, activa boolean NOT NULL DEFAULT true, posicion integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.comunidades_objetivo_usuario (usuario_id uuid NOT NULL, comunidad_id uuid NOT NULL, prioridad integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.documentos_legales (id uuid NOT NULL DEFAULT gen_random_uuid(), tipo_documento text NOT NULL, version text NOT NULL, titulo text NOT NULL, contenido text NOT NULL, activo boolean NOT NULL DEFAULT false, publicado_en timestamp with time zone, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.especialidades (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, slug text NOT NULL, descripcion text, activa boolean NOT NULL DEFAULT true, posicion integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.etiquetas (id uuid NOT NULL DEFAULT gen_random_uuid(), nombre text NOT NULL, slug text NOT NULL, descripcion text, color_hex text, activa boolean NOT NULL DEFAULT true, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.etiquetas_pregunta (pregunta_id uuid NOT NULL, etiqueta_id uuid NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.eventos_app (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, sesion_id uuid, nombre_evento text NOT NULL, pantalla text, entidad_tipo text, entidad_id uuid, plataforma text, version_app text, idioma text, duracion_ms integer, propiedades jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), event_version integer NOT NULL DEFAULT 1, occurred_en timestamp with time zone NOT NULL DEFAULT now(), received_en timestamp with time zone NOT NULL DEFAULT now(), especialidad_id uuid, intento_id uuid, pregunta_id uuid, idempotency_key text NOT NULL);
CREATE TABLE public.examenes (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo_examen text NOT NULL, especialidad_id uuid NOT NULL, comunidad_id uuid, titulo text NOT NULL, descripcion text, tipo_examen text NOT NULL, tipo_acceso text NOT NULL, total_preguntas integer NOT NULL DEFAULT 0, duracion_minutos integer, anio integer, es_oficial boolean NOT NULL DEFAULT false, activo boolean NOT NULL DEFAULT false, publicado_en timestamp with time zone, creado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), comprable_con_tickets boolean NOT NULL DEFAULT false, precio_tickets integer, dias_acceso_compra integer NOT NULL DEFAULT 30, demo_disponible boolean NOT NULL DEFAULT false, requiere_plan_codigo text, creditos_inmersivos_necesarios integer NOT NULL DEFAULT 0, portada_storage_path text, portada_mime_type text);
CREATE TABLE public.intentos_examen (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, examen_id uuid, estado text NOT NULL DEFAULT 'en_progreso'::text, iniciado_en timestamp with time zone NOT NULL DEFAULT now(), finalizado_en timestamp with time zone, duracion_segundos integer, total_preguntas integer NOT NULL DEFAULT 0, total_respondidas integer NOT NULL DEFAULT 0, total_correctas integer NOT NULL DEFAULT 0, puntuacion numeric(6,2), metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), tipo_examen_snapshot text, intento_numero_usuario integer, es_primer_intento_examen boolean NOT NULL DEFAULT false, cuenta_para_ranking boolean NOT NULL DEFAULT false, total_falladas integer NOT NULL DEFAULT 0, total_sin_responder integer NOT NULL DEFAULT 0, porcentaje_acierto numeric(5,2), puntuacion_base numeric(8,2), puntuacion_final numeric(8,2), opositickets_ganados integer NOT NULL DEFAULT 0, modo_estudio text, abandonado_en_pregunta_id uuid, abandonado_en_posicion integer, ultima_pregunta_posicion integer, calculado_en timestamp with time zone, origen_intento text NOT NULL DEFAULT 'examen'::text);
CREATE TABLE public.lotes_importacion_preguntas (id uuid NOT NULL DEFAULT gen_random_uuid(), examen_id uuid NOT NULL, subido_por uuid, nombre_archivo_original text NOT NULL, ruta_archivo_storage text, tipo_examen text NOT NULL, estado text NOT NULL DEFAULT 'pendiente'::text, total_detectadas integer NOT NULL DEFAULT 0, total_importadas integer NOT NULL DEFAULT 0, mensaje_error text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.opciones_pregunta (id uuid NOT NULL DEFAULT gen_random_uuid(), pregunta_id uuid NOT NULL, letra text NOT NULL, texto_opcion text NOT NULL, posicion integer NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.perfiles_usuario (usuario_id uuid NOT NULL, nombre_mostrar text, username text, avatar_url text, especialidad_principal_id uuid, comunidad_principal_id uuid, es_invitado boolean NOT NULL DEFAULT false, onboarding_completado boolean NOT NULL DEFAULT false, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.perfiles_usuario_detalle (usuario_id uuid NOT NULL, nombre_completo text, edad integer, ciudad text, estado_laboral text, centro_trabajo text, tiempo_disponible_estudio_minutos integer, franja_preferente_estudio text, fecha_examen_objetivo date, preferencias_horarias jsonb NOT NULL DEFAULT '{}'::jsonb, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.preguntas (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo_pregunta text NOT NULL, tipo_pregunta text NOT NULL, titulo text NOT NULL, fuente_texto text, dificultad text, activa boolean NOT NULL DEFAULT true, lote_importacion_id uuid, creado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.preguntas_examen (examen_id uuid NOT NULL, pregunta_id uuid NOT NULL, posicion integer NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), numero_en_examen integer NOT NULL, anulada boolean NOT NULL DEFAULT false, motivo_anulacion text);
CREATE TABLE public.preguntas_intento (id uuid NOT NULL DEFAULT gen_random_uuid(), intento_id uuid NOT NULL, pregunta_id uuid NOT NULL, posicion integer NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.reportes_contenido (id uuid NOT NULL DEFAULT gen_random_uuid(), reportado_por uuid, tipo_entidad text NOT NULL, entidad_id uuid NOT NULL, motivo text NOT NULL, descripcion text, estado text NOT NULL DEFAULT 'pendiente'::text, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), tickets_otorgados integer NOT NULL DEFAULT 0, recompensa_entregada boolean NOT NULL DEFAULT false, revisado_por uuid, revisado_en timestamp with time zone);
CREATE TABLE public.respuestas_intento (id uuid NOT NULL DEFAULT gen_random_uuid(), intento_id uuid NOT NULL, pregunta_id uuid NOT NULL, opcion_seleccionada_id uuid, es_correcta boolean, respondida_en timestamp with time zone, tiempo_respuesta_segundos integer, creada_en timestamp with time zone NOT NULL DEFAULT now(), usuario_id uuid, examen_id uuid, opcion_correcta_id uuid, posicion_pregunta integer, etiqueta_principal_id uuid, cuenta_para_ranking boolean NOT NULL DEFAULT false, es_primer_intento_pregunta boolean NOT NULL DEFAULT false, nivel_confianza text, dificultad_percibida text, tipo_error_usuario text, marcada_para_repasar boolean NOT NULL DEFAULT false, respondida_al_azar boolean NOT NULL DEFAULT false, cambio_respuesta boolean NOT NULL DEFAULT false, tiempo_visualizacion_recursos_segundos integer, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb);
CREATE TABLE public.resumen_intento_etiqueta (intento_id uuid NOT NULL, etiqueta_id uuid NOT NULL, total_preguntas integer NOT NULL DEFAULT 0, correctas integer NOT NULL DEFAULT 0, falladas integer NOT NULL DEFAULT 0, sin_responder integer NOT NULL DEFAULT 0, tiempo_total_segundos integer NOT NULL DEFAULT 0, tiempo_medio_segundos numeric NOT NULL DEFAULT 0, porcentaje_acierto numeric NOT NULL DEFAULT 0);
CREATE TABLE public.roles_app (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, descripcion text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.roles_usuario (usuario_id uuid NOT NULL, rol_id uuid NOT NULL, asignado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.sesiones_estudio (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, sesion_cliente_id text, modo_estudio text, iniciada_en timestamp with time zone NOT NULL DEFAULT now(), finalizada_en timestamp with time zone, duracion_segundos integer, preguntas_respondidas integer NOT NULL DEFAULT 0, respuestas_correctas integer NOT NULL DEFAULT 0, respuestas_falladas integer NOT NULL DEFAULT 0, opositickets_ganados integer NOT NULL DEFAULT 0, plataforma text, version_app text, idioma text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.soluciones_pregunta (id uuid NOT NULL DEFAULT gen_random_uuid(), pregunta_id uuid NOT NULL, opcion_correcta_id uuid NOT NULL, razonamiento text NOT NULL, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.usuario (id integer GENERATED ALWAYS AS IDENTITY NOT NULL, auth_user_id uuid, email character varying, nombre character varying, created_at timestamp with time zone NOT NULL DEFAULT now(), updated_at timestamp with time zone NOT NULL DEFAULT now(), deleted_at timestamp with time zone, apellidos character varying(100), username character varying(30), especialidad_registro character varying(100), perfil_completo boolean NOT NULL DEFAULT false, perfil_completado_at timestamp with time zone);
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_pkey PRIMARY KEY (id);
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_pkey PRIMARY KEY (id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.auditoria_log ADD CONSTRAINT auditoria_log_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_pkey PRIMARY KEY (usuario_id, comunidad_id);
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_pkey PRIMARY KEY (id);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_pkey PRIMARY KEY (id);
ALTER TABLE public.etiquetas ADD CONSTRAINT etiquetas_pkey PRIMARY KEY (id);
ALTER TABLE public.etiquetas_pregunta ADD CONSTRAINT etiquetas_pregunta_pkey PRIMARY KEY (pregunta_id, etiqueta_id);
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_pkey PRIMARY KEY (id);
ALTER TABLE public.examenes ADD CONSTRAINT examenes_pkey PRIMARY KEY (id);
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_pkey PRIMARY KEY (id);
ALTER TABLE public.lotes_importacion_preguntas ADD CONSTRAINT lotes_importacion_preguntas_pkey PRIMARY KEY (id);
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pkey PRIMARY KEY (id);
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_pkey PRIMARY KEY (id);
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_pkey PRIMARY KEY (examen_id, pregunta_id);
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_pkey PRIMARY KEY (id);
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_pkey PRIMARY KEY (id);
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_pkey PRIMARY KEY (id);
ALTER TABLE public.resumen_intento_etiqueta ADD CONSTRAINT resumen_intento_etiqueta_pkey PRIMARY KEY (intento_id, etiqueta_id);
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_pkey PRIMARY KEY (id);
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_pkey PRIMARY KEY (usuario_id, rol_id);
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_pkey PRIMARY KEY (id);
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_pkey PRIMARY KEY (id);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_codigo_key UNIQUE (codigo);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_slug_key UNIQUE (slug);
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_tipo_documento_version_key UNIQUE (tipo_documento, version);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_codigo_key UNIQUE (codigo);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_slug_key UNIQUE (slug);
ALTER TABLE public.etiquetas ADD CONSTRAINT etiquetas_slug_key UNIQUE (slug);
ALTER TABLE public.examenes ADD CONSTRAINT examenes_codigo_examen_key UNIQUE (codigo_examen);
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pregunta_id_letra_key UNIQUE (pregunta_id, letra);
ALTER TABLE public.opciones_pregunta ADD CONSTRAINT opciones_pregunta_pregunta_id_posicion_key UNIQUE (pregunta_id, posicion);
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_username_key UNIQUE (username);
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_codigo_pregunta_key UNIQUE (codigo_pregunta);
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_examen_id_posicion_key UNIQUE (examen_id, posicion) DEFERRABLE;
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_numero_unico UNIQUE (examen_id, numero_en_examen);
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_intento_id_posicion_key UNIQUE (intento_id, posicion);
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_intento_id_pregunta_id_key UNIQUE (intento_id, pregunta_id);
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_intento_id_pregunta_id_key UNIQUE (intento_id, pregunta_id);
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_codigo_key UNIQUE (codigo);
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_pregunta_id_key UNIQUE (pregunta_id);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_auth_user_id_key UNIQUE (auth_user_id);
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_motivo_detectado_check CHECK (((motivo_detectado IS NULL) OR (motivo_detectado = ANY (ARRAY['salida_app'::text, 'cerrar_examen'::text, 'timeout'::text, 'error_tecnico'::text, 'desconocido'::text]))));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_posicion_pregunta_check CHECK (((posicion_pregunta IS NULL) OR (posicion_pregunta > 0)));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_tiempo_transcurrido_segundos_check CHECK (((tiempo_transcurrido_segundos IS NULL) OR (tiempo_transcurrido_segundos >= 0)));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_total_preguntas_check CHECK (((total_preguntas IS NULL) OR (total_preguntas >= 0)));
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_tipo_accion_check CHECK ((tipo_accion = ANY (ARRAY['ocultar'::text, 'restaurar'::text, 'eliminar'::text, 'advertir'::text, 'bloquear_usuario'::text, 'descartar'::text])));
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_tipo_documento_check CHECK ((tipo_documento = ANY (ARRAY['privacidad'::text, 'terminos'::text, 'cookies'::text, 'aviso_legal'::text])));
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
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_abandonado_en_posicion_check CHECK (((abandonado_en_posicion IS NULL) OR (abandonado_en_posicion > 0)));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_canonical_v2_tipo_examen_snapshot_check CHECK ((tipo_examen_snapshot = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_duracion_segundos_check CHECK (((duracion_segundos IS NULL) OR (duracion_segundos >= 0)));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_estado_check CHECK ((estado = ANY (ARRAY['en_progreso'::text, 'finalizado'::text, 'abandonado'::text])));
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_modo_estudio_check CHECK (((modo_estudio IS NULL) OR (modo_estudio = ANY (ARRAY['examen'::text, 'rafaga'::text, 'repaso_fallos'::text, 'repaso_inteligente'::text, 'entrenamiento'::text]))));
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
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_edad_check CHECK (((edad IS NULL) OR (edad >= 14)));
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_canonical_v2_tipo_pregunta_check CHECK ((tipo_pregunta = ANY (ARRAY['rafaga'::text, 'razonada'::text, 'inmersiva'::text])));
ALTER TABLE public.preguntas ADD CONSTRAINT preguntas_dificultad_check CHECK (((dificultad IS NULL) OR (dificultad = ANY (ARRAY['facil'::text, 'media'::text, 'dificil'::text]))));
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_anulada_motivo_chk CHECK (((motivo_anulacion IS NULL) OR (anulada AND (btrim(motivo_anulacion) <> ''::text) AND (length(motivo_anulacion) <= 500))));
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_numero_check CHECK (((numero_en_examen >= 1) AND (numero_en_examen <= 200)));
ALTER TABLE public.preguntas_examen ADD CONSTRAINT preguntas_examen_posicion_check CHECK (((posicion >= 1) AND (posicion <= 200)));
ALTER TABLE public.preguntas_intento ADD CONSTRAINT preguntas_intento_posicion_check CHECK ((posicion > 0));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_estado_check CHECK ((estado = ANY (ARRAY['pendiente'::text, 'revisado'::text, 'resuelto'::text, 'descartado'::text])));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_tipo_entidad_check CHECK ((tipo_entidad = ANY (ARRAY['pregunta'::text, 'recurso_pregunta'::text, 'opositeca_item'::text, 'publicacion_oposired'::text, 'comentario_oposired'::text, 'usuario'::text])));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_dificultad_percibida_check CHECK (((dificultad_percibida IS NULL) OR (dificultad_percibida = ANY (ARRAY['facil'::text, 'normal'::text, 'dificil'::text, 'muy_dificil'::text]))));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_first_response_user_not_null CHECK (((NOT es_primer_intento_pregunta) OR (usuario_id IS NOT NULL)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_nivel_confianza_check CHECK (((nivel_confianza IS NULL) OR (nivel_confianza = ANY (ARRAY['seguro'::text, 'duda'::text, 'azar'::text]))));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_posicion_pregunta_check CHECK (((posicion_pregunta IS NULL) OR (posicion_pregunta > 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_ranking_requires_first_response CHECK (((NOT cuenta_para_ranking) OR es_primer_intento_pregunta));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tiempo_respuesta_segundos_check CHECK (((tiempo_respuesta_segundos IS NULL) OR (tiempo_respuesta_segundos >= 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tiempo_visualizacion_recursos_segundos_check CHECK (((tiempo_visualizacion_recursos_segundos IS NULL) OR (tiempo_visualizacion_recursos_segundos >= 0)));
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_tipo_error_usuario_check CHECK (((tipo_error_usuario IS NULL) OR (tipo_error_usuario = ANY (ARRAY['no_sabia_concepto'::text, 'confundio_opciones'::text, 'leyo_mal'::text, 'falto_tiempo'::text, 'azar'::text, 'otro'::text]))));
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_codigo_check CHECK ((codigo = ANY (ARRAY['superadmin'::text, 'admin'::text, 'moderador'::text, 'usuario'::text])));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_duracion_segundos_check CHECK (((duracion_segundos IS NULL) OR (duracion_segundos >= 0)));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_modo_estudio_check CHECK (((modo_estudio IS NULL) OR (modo_estudio = ANY (ARRAY['examen'::text, 'rafaga'::text, 'repaso_fallos'::text, 'repaso_inteligente'::text, 'opositeca'::text, 'otro'::text]))));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_opositickets_ganados_check CHECK ((opositickets_ganados >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_plataforma_check CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text]))));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_preguntas_respondidas_check CHECK ((preguntas_respondidas >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_respuestas_correctas_check CHECK ((respuestas_correctas >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_respuestas_falladas_check CHECK ((respuestas_falladas >= 0));
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE SET NULL;
ALTER TABLE public.abandonos_examen ADD CONSTRAINT abandonos_examen_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_moderador_id_fkey FOREIGN KEY (moderador_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_reporte_id_fkey FOREIGN KEY (reporte_id) REFERENCES public.reportes_contenido(id) ON DELETE SET NULL;
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_referido_por_fkey FOREIGN KEY (referido_por) REFERENCES auth.users(id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.auditoria_log ADD CONSTRAINT auditoria_log_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_comunidad_id_fkey FOREIGN KEY (comunidad_id) REFERENCES public.comunidades_autonomas(id);
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.etiquetas_pregunta ADD CONSTRAINT etiquetas_pregunta_etiqueta_id_fkey FOREIGN KEY (etiqueta_id) REFERENCES public.etiquetas(id) ON DELETE CASCADE;
ALTER TABLE public.etiquetas_pregunta ADD CONSTRAINT etiquetas_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_sesion_id_fkey FOREIGN KEY (sesion_id) REFERENCES public.sesiones_estudio(id) ON DELETE SET NULL;
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.examenes ADD CONSTRAINT examenes_comunidad_id_fkey FOREIGN KEY (comunidad_id) REFERENCES public.comunidades_autonomas(id) ON DELETE SET NULL;
ALTER TABLE public.examenes ADD CONSTRAINT examenes_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.examenes ADD CONSTRAINT examenes_especialidad_id_fkey FOREIGN KEY (especialidad_id) REFERENCES public.especialidades(id) ON DELETE RESTRICT;
ALTER TABLE public.intentos_examen ADD CONSTRAINT intentos_examen_abandonado_en_pregunta_id_fkey FOREIGN KEY (abandonado_en_pregunta_id) REFERENCES public.preguntas(id) ON DELETE SET NULL;
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
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_reportado_por_fkey FOREIGN KEY (reportado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_revisado_por_fkey FOREIGN KEY (revisado_por) REFERENCES auth.users(id);
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_etiqueta_principal_id_fkey FOREIGN KEY (etiqueta_principal_id) REFERENCES public.etiquetas(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES public.examenes(id) ON DELETE CASCADE;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_opcion_correcta_id_fkey FOREIGN KEY (opcion_correcta_id) REFERENCES public.opciones_pregunta(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_opcion_seleccionada_id_fkey FOREIGN KEY (opcion_seleccionada_id) REFERENCES public.opciones_pregunta(id) ON DELETE SET NULL;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
ALTER TABLE public.respuestas_intento ADD CONSTRAINT respuestas_intento_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.resumen_intento_etiqueta ADD CONSTRAINT resumen_intento_etiqueta_etiqueta_id_fkey FOREIGN KEY (etiqueta_id) REFERENCES public.etiquetas(id);
ALTER TABLE public.resumen_intento_etiqueta ADD CONSTRAINT resumen_intento_etiqueta_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES public.intentos_examen(id) ON DELETE CASCADE;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_asignado_por_fkey FOREIGN KEY (asignado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_rol_id_fkey FOREIGN KEY (rol_id) REFERENCES public.roles_app(id) ON DELETE CASCADE;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_opcion_correcta_id_fkey FOREIGN KEY (opcion_correcta_id) REFERENCES public.opciones_pregunta(id) ON DELETE RESTRICT;
ALTER TABLE public.soluciones_pregunta ADD CONSTRAINT soluciones_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES public.preguntas(id) ON DELETE CASCADE;
CREATE INDEX idx_abandonos_examen_examen ON public.abandonos_examen USING btree (examen_id, posicion_pregunta);
CREATE INDEX idx_abandonos_examen_intento_id ON public.abandonos_examen USING btree (intento_id);
CREATE INDEX idx_abandonos_examen_pregunta_id ON public.abandonos_examen USING btree (pregunta_id);
CREATE INDEX idx_abandonos_examen_usuario_id ON public.abandonos_examen USING btree (usuario_id);
CREATE INDEX idx_acciones_moderacion_moderador_id ON public.acciones_moderacion USING btree (moderador_id);
CREATE INDEX idx_acciones_moderacion_reporte_id ON public.acciones_moderacion USING btree (reporte_id);
CREATE INDEX idx_atribucion_usuario_referido_por ON public.atribucion_usuario USING btree (referido_por);
CREATE INDEX idx_auditoria_log_usuario_id ON public.auditoria_log USING btree (usuario_id);
CREATE INDEX idx_comunidades_objetivo_usuario_comunidad_id ON public.comunidades_objetivo_usuario USING btree (comunidad_id);
CREATE INDEX idx_etiquetas_pregunta_etiqueta ON public.etiquetas_pregunta USING btree (etiqueta_id);
CREATE INDEX idx_eventos_app_creado ON public.eventos_app USING btree (creado_en);
CREATE INDEX idx_eventos_app_nombre ON public.eventos_app USING btree (nombre_evento);
CREATE INDEX idx_eventos_app_propiedades_gin ON public.eventos_app USING gin (propiedades);
CREATE INDEX idx_eventos_app_sesion_id ON public.eventos_app USING btree (sesion_id);
CREATE INDEX idx_eventos_app_usuario ON public.eventos_app USING btree (usuario_id);
CREATE INDEX idx_eventos_app_usuario_fecha ON public.eventos_app USING btree (usuario_id, creado_en);
CREATE INDEX idx_examenes_acceso ON public.examenes USING btree (tipo_acceso);
CREATE INDEX idx_examenes_activo ON public.examenes USING btree (activo);
CREATE INDEX idx_examenes_comunidad_id ON public.examenes USING btree (comunidad_id);
CREATE INDEX idx_examenes_creado_por ON public.examenes USING btree (creado_por);
CREATE INDEX idx_examenes_especialidad ON public.examenes USING btree (especialidad_id);
CREATE INDEX idx_examenes_tipo ON public.examenes USING btree (tipo_examen);
CREATE INDEX idx_examenes_tipo_activo ON public.examenes USING btree (tipo_examen, activo);
CREATE INDEX idx_intentos_examen_abandonado_en_pregunta_id ON public.intentos_examen USING btree (abandonado_en_pregunta_id);
CREATE INDEX idx_intentos_examen_examen ON public.intentos_examen USING btree (examen_id);
CREATE INDEX idx_intentos_examen_finalizado ON public.intentos_examen USING btree (finalizado_en);
CREATE INDEX idx_intentos_examen_ranking ON public.intentos_examen USING btree (examen_id, cuenta_para_ranking, estado);
CREATE INDEX idx_intentos_examen_usuario_examen ON public.intentos_examen USING btree (usuario_id, examen_id);
CREATE INDEX idx_intentos_usuario ON public.intentos_examen USING btree (usuario_id);
CREATE INDEX idx_lotes_importacion_examen ON public.lotes_importacion_preguntas USING btree (examen_id);
CREATE INDEX idx_lotes_importacion_preguntas_subido_por ON public.lotes_importacion_preguntas USING btree (subido_por);
CREATE INDEX idx_opciones_pregunta_pregunta ON public.opciones_pregunta USING btree (pregunta_id);
CREATE INDEX idx_perfiles_usuario_comunidad_principal_id ON public.perfiles_usuario USING btree (comunidad_principal_id);
CREATE INDEX idx_perfiles_usuario_especialidad_principal_id ON public.perfiles_usuario USING btree (especialidad_principal_id);
CREATE INDEX idx_perfiles_usuario_username ON public.perfiles_usuario USING btree (username);
CREATE INDEX idx_preguntas_codigo ON public.preguntas USING btree (codigo_pregunta);
CREATE INDEX idx_preguntas_creado_por ON public.preguntas USING btree (creado_por);
CREATE INDEX idx_preguntas_examen_examen ON public.preguntas_examen USING btree (examen_id);
CREATE INDEX idx_preguntas_examen_examen_posicion ON public.preguntas_examen USING btree (examen_id, posicion);
CREATE INDEX idx_preguntas_examen_pregunta ON public.preguntas_examen USING btree (pregunta_id);
CREATE INDEX idx_preguntas_intento_pregunta ON public.preguntas_intento USING btree (pregunta_id);
CREATE INDEX idx_preguntas_lote_importacion_id ON public.preguntas USING btree (lote_importacion_id);
CREATE INDEX idx_preguntas_tipo ON public.preguntas USING btree (tipo_pregunta);
CREATE INDEX idx_preguntas_tipo_activa ON public.preguntas USING btree (tipo_pregunta, activa);
CREATE INDEX idx_reportes_contenido_estado ON public.reportes_contenido USING btree (estado);
CREATE INDEX idx_reportes_contenido_reportado_por ON public.reportes_contenido USING btree (reportado_por);
CREATE INDEX idx_reportes_contenido_revisado_por ON public.reportes_contenido USING btree (revisado_por);
CREATE INDEX idx_respuestas_intento_etiqueta ON public.respuestas_intento USING btree (etiqueta_principal_id);
CREATE INDEX idx_respuestas_intento_examen ON public.respuestas_intento USING btree (examen_id);
CREATE INDEX idx_respuestas_intento_intento ON public.respuestas_intento USING btree (intento_id);
CREATE INDEX idx_respuestas_intento_opcion_correcta_id ON public.respuestas_intento USING btree (opcion_correcta_id);
CREATE INDEX idx_respuestas_intento_opcion_seleccionada_id ON public.respuestas_intento USING btree (opcion_seleccionada_id);
CREATE INDEX idx_respuestas_intento_pregunta ON public.respuestas_intento USING btree (pregunta_id);
CREATE INDEX idx_respuestas_intento_ranking ON public.respuestas_intento USING btree (cuenta_para_ranking, es_correcta);
CREATE INDEX idx_respuestas_intento_usuario ON public.respuestas_intento USING btree (usuario_id);
CREATE INDEX idx_respuestas_intento_usuario_pregunta ON public.respuestas_intento USING btree (usuario_id, pregunta_id);
CREATE INDEX idx_resumen_intento_etiqueta_etiqueta_id ON public.resumen_intento_etiqueta USING btree (etiqueta_id);
CREATE INDEX idx_roles_usuario_asignado_por ON public.roles_usuario USING btree (asignado_por);
CREATE INDEX idx_roles_usuario_rol_id ON public.roles_usuario USING btree (rol_id);
CREATE INDEX idx_roles_usuario_usuario_id ON public.roles_usuario USING btree (usuario_id);
CREATE INDEX idx_sesiones_estudio_usuario_fecha ON public.sesiones_estudio USING btree (usuario_id, iniciada_en DESC);
CREATE INDEX idx_soluciones_pregunta_opcion_correcta_id ON public.soluciones_pregunta USING btree (opcion_correcta_id);
CREATE INDEX idx_soluciones_pregunta_pregunta ON public.soluciones_pregunta USING btree (pregunta_id);
CREATE UNIQUE INDEX idx_usuario_auth_user_id_unique ON public.usuario USING btree (auth_user_id) WHERE (auth_user_id IS NOT NULL);
CREATE UNIQUE INDEX idx_usuario_username_active_unique ON public.usuario USING btree (lower((username)::text)) WHERE ((username IS NOT NULL) AND (deleted_at IS NULL));
CREATE UNIQUE INDEX ux_eventos_app_idempotency_key ON public.eventos_app USING btree (idempotency_key);
CREATE UNIQUE INDEX ux_respuestas_first_response ON public.respuestas_intento USING btree (usuario_id, pregunta_id) WHERE (es_primer_intento_pregunta IS TRUE);
CREATE OR REPLACE FUNCTION public.admin_role_code_canonical(p_codigo text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO ''
AS $function$
  select lower(
    regexp_replace(
      coalesce(p_codigo, ''),
      '^[	--    -     　]+|[	--    -     　]+$',
      '',
      'g'
    ) collate "C"
  );
$function$;
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
$function$;
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
$function$;
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
$function$;
CREATE OR REPLACE FUNCTION public.establecer_actualizado_en()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
    new.actualizado_en = now();
    return new;
end;
$function$;
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
    username,
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
    nullif(new.raw_user_meta_data ->> 'username', ''),
    nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
    now()
  )
  on conflict (usuario_id) do update
  set
    nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
    username = coalesce(public.perfiles_usuario.username, excluded.username),
    avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
    actualizado_en = now();

  return new;
end;
$function$;
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
$function$;
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
$function$;
CREATE OR REPLACE FUNCTION private.tg_examenes_publicado_no_vacio()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  raise exception 'EXAM_PUBLISHED_WOULD_BE_EMPTY';
end;
$function$;
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
$function$;
CREATE OR REPLACE FUNCTION private.tg_examenes_total_preguntas_insert()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
  new.total_preguntas := 0; -- un examen recién insertado no puede tener vínculos (FK)
  return new;
end;
$function$;
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
$function$;
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
$function$;
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
$function$;
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
$function$;
CREATE OR REPLACE FUNCTION public.record_event(p_usuario_id uuid, p_nombre_evento text, p_pantalla text DEFAULT NULL::text, p_entidad_tipo text DEFAULT NULL::text, p_entidad_id uuid DEFAULT NULL::uuid, p_propiedades jsonb DEFAULT '{}'::jsonb, p_event_version integer DEFAULT 1, p_occurred_en timestamp with time zone DEFAULT now(), p_especialidad_id uuid DEFAULT NULL::uuid, p_intento_id uuid DEFAULT NULL::uuid, p_pregunta_id uuid DEFAULT NULL::uuid, p_idempotency_key text DEFAULT NULL::text)
 RETURNS TABLE(event_id uuid)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_existing public.eventos_app%rowtype;
  v_event_id uuid;
begin
  if p_event_version is null or p_event_version <= 0 then
    raise exception 'EVENT_VERSION_INVALID';
  end if;
  if p_idempotency_key is null or length(btrim(p_idempotency_key)) = 0 then
    raise exception 'EVENT_IDEMPOTENCY_KEY_REQUIRED';
  end if;

  select e.* into v_existing
  from public.eventos_app e
  where e.idempotency_key = btrim(p_idempotency_key)
  for update;

  if found then
    if v_existing.usuario_id is not distinct from p_usuario_id
      and v_existing.nombre_evento is not distinct from p_nombre_evento
      and v_existing.pantalla is not distinct from p_pantalla
      and v_existing.entidad_tipo is not distinct from p_entidad_tipo
      and v_existing.entidad_id is not distinct from p_entidad_id
      and v_existing.propiedades is not distinct from coalesce(p_propiedades, '{}'::jsonb)
      and v_existing.event_version is not distinct from p_event_version
      and v_existing.especialidad_id is not distinct from p_especialidad_id
      and v_existing.intento_id is not distinct from p_intento_id
      and v_existing.pregunta_id is not distinct from p_pregunta_id then
      return query select v_existing.id;
      return;
    end if;
    raise exception 'EVENT_IDEMPOTENCY_CONFLICT';
  end if;

  insert into public.eventos_app (
    usuario_id, nombre_evento, pantalla, entidad_tipo, entidad_id,
    propiedades, event_version, occurred_en, received_en,
    especialidad_id, intento_id, pregunta_id, idempotency_key
  ) values (
    p_usuario_id, p_nombre_evento, p_pantalla, p_entidad_tipo, p_entidad_id,
    coalesce(p_propiedades, '{}'::jsonb), p_event_version, coalesce(p_occurred_en, now()), now(),
    p_especialidad_id, p_intento_id, p_pregunta_id, btrim(p_idempotency_key)
  ) returning id into v_event_id;

  return query select v_event_id;
end;
$function$;
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
$function$;
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
$function$;
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
$function$;
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
$function$;
CREATE TRIGGER trg_auth_user_created AFTER INSERT OR UPDATE OF email, raw_user_meta_data ON auth.users FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();
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
CREATE TRIGGER trg_preparar_intento_examen BEFORE INSERT ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION public.preparar_intento_examen();
CREATE TRIGGER trg_lotes_importacion_preguntas_actualizado_en BEFORE UPDATE ON public.lotes_importacion_preguntas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_opciones_pregunta_actualizado_en BEFORE UPDATE ON public.opciones_pregunta FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_perfiles_usuario_actualizado_en BEFORE UPDATE ON public.perfiles_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_preguntas_activa_total_preguntas AFTER UPDATE OF activa ON public.preguntas FOR EACH ROW WHEN ((old.activa IS DISTINCT FROM new.activa)) EXECUTE FUNCTION private.tg_preguntas_activa_total_preguntas();
CREATE TRIGGER trg_preguntas_actualizado_en BEFORE UPDATE ON public.preguntas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_preguntas_bloquear_cambio_tipo BEFORE UPDATE OF tipo_pregunta ON public.preguntas FOR EACH ROW WHEN ((old.tipo_pregunta IS DISTINCT FROM new.tipo_pregunta)) EXECUTE FUNCTION private.tg_preguntas_bloquear_cambio_tipo();
CREATE TRIGGER trg_preguntas_codigo_pregunta_insert BEFORE INSERT ON public.preguntas FOR EACH ROW EXECUTE FUNCTION private.tg_preguntas_codigo_pregunta();
CREATE TRIGGER trg_preguntas_codigo_pregunta_update BEFORE UPDATE OF codigo_pregunta ON public.preguntas FOR EACH ROW EXECUTE FUNCTION private.tg_preguntas_codigo_pregunta();
CREATE TRIGGER trg_preguntas_examen_total_preguntas AFTER INSERT OR DELETE OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION private.tg_preguntas_examen_total_preguntas();
CREATE TRIGGER trg_validar_pregunta_examen BEFORE INSERT OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION public.validar_pregunta_examen();
CREATE TRIGGER trg_validar_pregunta_examen_mismo_tipo BEFORE INSERT OR UPDATE OF examen_id, pregunta_id ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION public.validar_pregunta_examen_mismo_tipo();
CREATE TRIGGER trg_reportes_contenido_actualizado_en BEFORE UPDATE ON public.reportes_contenido FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_validar_y_calcular_respuesta_intento BEFORE INSERT OR UPDATE ON public.respuestas_intento FOR EACH ROW EXECUTE FUNCTION public.validar_y_calcular_respuesta_intento();
CREATE TRIGGER trg_sesiones_estudio_actualizado_en BEFORE UPDATE ON public.sesiones_estudio FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_soluciones_pregunta_actualizado_en BEFORE UPDATE ON public.soluciones_pregunta FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_validar_solucion_opcion_misma_pregunta BEFORE INSERT OR UPDATE ON public.soluciones_pregunta FOR EACH ROW EXECUTE FUNCTION public.validar_solucion_opcion_misma_pregunta();
ALTER TABLE public.abandonos_examen ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.acciones_moderacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.atribucion_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auditoria_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comunidades_autonomas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comunidades_objetivo_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.documentos_legales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.especialidades ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.etiquetas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.etiquetas_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.eventos_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.examenes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.intentos_examen ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.lotes_importacion_preguntas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.opciones_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.perfiles_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.perfiles_usuario_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.preguntas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.preguntas_examen ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.preguntas_intento ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reportes_contenido ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.respuestas_intento ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.resumen_intento_etiqueta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.roles_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.roles_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sesiones_estudio ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.soluciones_pregunta ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.usuario ENABLE ROW LEVEL SECURITY;
CREATE POLICY abandonos_examen_insert_propio ON public.abandonos_examen AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY abandonos_examen_select_staff_o_propio ON public.abandonos_examen AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY acciones_moderacion_staff_all ON public.acciones_moderacion AS PERMISSIVE FOR ALL TO authenticated USING (es_moderador_o_admin()) WITH CHECK (es_moderador_o_admin());
CREATE POLICY auditoria_admin_select ON public.auditoria_log AS PERMISSIVE FOR SELECT TO authenticated USING (es_admin());
CREATE POLICY comunidades_admin_all ON public.comunidades_autonomas AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY comunidades_select_publico ON public.comunidades_autonomas AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR es_admin()));
CREATE POLICY documentos_legales_select_publico ON public.documentos_legales AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activo = true) OR es_admin()));
CREATE POLICY especialidades_admin_all ON public.especialidades AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY especialidades_select_publico ON public.especialidades AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR es_admin()));
CREATE POLICY etiquetas_admin_all ON public.etiquetas AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY etiquetas_select_publico ON public.etiquetas AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR es_admin()));
CREATE POLICY etiquetas_pregunta_admin_all ON public.etiquetas_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY etiquetas_pregunta_select_gratuitas ON public.etiquetas_pregunta AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((EXISTS ( SELECT 1
   FROM ((preguntas p
     JOIN preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = etiquetas_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text)))) OR es_admin()));
CREATE POLICY examenes_admin_all ON public.examenes AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY examenes_select_activos ON public.examenes AS PERMISSIVE FOR SELECT TO anon, authenticated USING ((((activo = true) AND (publicado_en IS NOT NULL)) OR es_admin()));
CREATE POLICY product_metrics_intentos_select_propio ON public.intentos_examen AS PERMISSIVE FOR SELECT TO authenticated USING ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY lotes_importacion_admin_all ON public.lotes_importacion_preguntas AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY opciones_pregunta_admin_all ON public.opciones_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY opciones_pregunta_select_gratuitas ON public.opciones_pregunta AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((EXISTS ( SELECT 1
   FROM ((preguntas p
     JOIN preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = opciones_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text)))) OR es_admin()));
CREATE POLICY perfiles_usuario_insert_propio ON public.perfiles_usuario AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY perfiles_usuario_select_propio ON public.perfiles_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY perfiles_usuario_update_propio ON public.perfiles_usuario AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY preguntas_admin_all ON public.preguntas AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY preguntas_select_gratuitas ON public.preguntas AS PERMISSIVE FOR SELECT TO anon, authenticated USING ((((activa = true) AND (EXISTS ( SELECT 1
   FROM (preguntas_examen pe
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((pe.pregunta_id = preguntas.id) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text))))) OR es_admin()));
CREATE POLICY preguntas_examen_admin_all ON public.preguntas_examen AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY preguntas_examen_select_gratuitas ON public.preguntas_examen AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((EXISTS ( SELECT 1
   FROM examenes e
  WHERE ((e.id = preguntas_examen.examen_id) AND (e.activo = true) AND (e.publicado_en IS NOT NULL) AND (e.tipo_acceso = 'gratuito'::text)))) OR es_admin()));
CREATE POLICY reportes_contenido_insert_auth ON public.reportes_contenido AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((reportado_por = ( SELECT auth.uid() AS uid)));
CREATE POLICY reportes_contenido_select_staff ON public.reportes_contenido AS PERMISSIVE FOR SELECT TO authenticated USING (es_moderador_o_admin());
CREATE POLICY product_metrics_respuestas_select_propio ON public.respuestas_intento AS PERMISSIVE FOR SELECT TO authenticated USING ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY canonical_v2_attempt_summary_select ON public.resumen_intento_etiqueta AS PERMISSIVE FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM intentos_examen ie
  WHERE ((ie.id = resumen_intento_etiqueta.intento_id) AND (ie.usuario_id = ( SELECT auth.uid() AS uid))))));
CREATE POLICY roles_app_select_auth ON public.roles_app AS PERMISSIVE FOR SELECT TO authenticated USING (true);
CREATE POLICY roles_usuario_admin_all ON public.roles_usuario AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY roles_usuario_select_propio ON public.roles_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY sesiones_estudio_todo_propio ON public.sesiones_estudio AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY soluciones_pregunta_admin_all ON public.soluciones_pregunta AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY oposibots_consumer_profile_select ON public.usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((( SELECT auth.uid() AS uid) IS NOT NULL) AND (auth_user_id = ( SELECT auth.uid() AS uid))));
REVOKE ALL ON TABLE public.abandonos_examen FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.abandonos_examen TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.abandonos_examen TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.abandonos_examen TO service_role;
REVOKE ALL ON TABLE public.acciones_moderacion FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.acciones_moderacion TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.acciones_moderacion TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.acciones_moderacion TO service_role;
REVOKE ALL ON TABLE public.atribucion_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.atribucion_usuario TO service_role;
REVOKE ALL ON TABLE public.auditoria_log FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.auditoria_log TO anon;
GRANT MAINTAIN, SELECT ON TABLE public.auditoria_log TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.auditoria_log TO service_role;
REVOKE ALL ON TABLE public.comunidades_autonomas FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.comunidades_autonomas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.comunidades_autonomas TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.comunidades_autonomas TO service_role;
REVOKE ALL ON TABLE public.comunidades_objetivo_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.comunidades_objetivo_usuario TO service_role;
REVOKE ALL ON TABLE public.documentos_legales FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.documentos_legales TO anon;
GRANT MAINTAIN, SELECT ON TABLE public.documentos_legales TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.documentos_legales TO service_role;
REVOKE ALL ON TABLE public.especialidades FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.especialidades TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.especialidades TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.especialidades TO service_role;
REVOKE ALL ON TABLE public.etiquetas FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.etiquetas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.etiquetas TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.etiquetas TO service_role;
REVOKE ALL ON TABLE public.etiquetas_pregunta FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.etiquetas_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.etiquetas_pregunta TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.etiquetas_pregunta TO service_role;
REVOKE ALL ON TABLE public.eventos_app FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.eventos_app TO service_role;
REVOKE ALL ON TABLE public.examenes FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.examenes TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.examenes TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.examenes TO service_role;
REVOKE ALL ON TABLE public.intentos_examen FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON TABLE public.intentos_examen TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.intentos_examen TO service_role;
REVOKE ALL ON TABLE public.lotes_importacion_preguntas FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.lotes_importacion_preguntas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.lotes_importacion_preguntas TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.lotes_importacion_preguntas TO service_role;
REVOKE ALL ON TABLE public.opciones_pregunta FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.opciones_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.opciones_pregunta TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.opciones_pregunta TO service_role;
REVOKE ALL ON TABLE public.perfiles_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.perfiles_usuario TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario TO service_role;
REVOKE ALL ON TABLE public.perfiles_usuario_detalle FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario_detalle TO service_role;
REVOKE ALL ON TABLE public.preguntas FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.preguntas TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas TO service_role;
REVOKE ALL ON TABLE public.preguntas_examen FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.preguntas_examen TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas_examen TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas_examen TO service_role;
REVOKE ALL ON TABLE public.preguntas_intento FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.preguntas_intento TO service_role;
REVOKE ALL ON TABLE public.reportes_contenido FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.reportes_contenido TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.reportes_contenido TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.reportes_contenido TO service_role;
REVOKE ALL ON TABLE public.respuestas_intento FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON TABLE public.respuestas_intento TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.respuestas_intento TO service_role;
REVOKE ALL ON TABLE public.resumen_intento_etiqueta FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON TABLE public.resumen_intento_etiqueta TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.resumen_intento_etiqueta TO service_role;
REVOKE ALL ON TABLE public.roles_app FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.roles_app TO anon;
GRANT MAINTAIN, SELECT ON TABLE public.roles_app TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.roles_app TO service_role;
REVOKE ALL ON TABLE public.roles_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.roles_usuario TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.roles_usuario TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.roles_usuario TO service_role;
REVOKE ALL ON TABLE public.sesiones_estudio FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.sesiones_estudio TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.sesiones_estudio TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.sesiones_estudio TO service_role;
REVOKE ALL ON TABLE public.soluciones_pregunta FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.soluciones_pregunta TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.soluciones_pregunta TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.soluciones_pregunta TO service_role;
REVOKE ALL ON TABLE public.usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.usuario TO anon;
GRANT MAINTAIN, SELECT ON TABLE public.usuario TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.usuario TO service_role;
REVOKE ALL ON FUNCTION public.admin_role_code_canonical(text) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.admin_role_code_canonical(text) TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.establecer_actualizado_en() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.establecer_actualizado_en() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.preparar_intento_examen() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.preparar_intento_examen() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_pregunta_examen_mismo_tipo() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_pregunta_examen_mismo_tipo() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_pregunta_examen() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_pregunta_examen() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_solucion_opcion_misma_pregunta() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_solucion_opcion_misma_pregunta() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.validar_y_calcular_respuesta_intento() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.validar_y_calcular_respuesta_intento() TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.es_admin() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.es_admin() TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.es_moderador_o_admin() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.es_moderador_o_admin() TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.admin_sync_roles(uuid,uuid[]) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.admin_sync_roles(uuid,uuid[]) TO service_role;
REVOKE ALL ON FUNCTION public.handle_new_auth_user() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.handle_new_auth_user() TO service_role;
REVOKE ALL ON FUNCTION public.record_event(uuid,text,text,text,uuid,jsonb,integer,timestamp with time zone,uuid,uuid,uuid,text) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.record_event(uuid,text,text,text,uuid,jsonb,integer,timestamp with time zone,uuid,uuid,uuid,text) TO service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_bloquear_cambio_tipo() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_publicado_no_vacio() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_total_preguntas_guard() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_examenes_total_preguntas_insert() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_activa_total_preguntas() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_bloquear_cambio_tipo() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_codigo_pregunta() FROM PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION private.tg_preguntas_examen_total_preguntas() FROM PUBLIC, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO postgres, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO postgres, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres, anon, authenticated, service_role;
COMMENT ON COLUMN public.examenes.portada_mime_type IS 'Tipo MIME de la portada. Ktor admite jpeg/png/webp; el CHECK acepta también gif por coherencia con el bucket.';
COMMENT ON COLUMN public.examenes.portada_storage_path IS 'Objeto en el bucket PRIVADO exam-resources: exam-covers/<examen_id>/<uuid>.<ext>. La URL se firma en lectura y nunca se guarda.';
COMMENT ON COLUMN public.examenes.total_preguntas IS 'Nº de preguntas ACTIVAS vinculadas. Lo mantiene la base (M1: triggers trg_*_total_preguntas); no se escribe desde la API.';
COMMENT ON COLUMN public.intentos_examen.cuenta_para_ranking IS 'Solo debe ser true en el primer intento válido del usuario para ese examen.';
COMMENT ON COLUMN public.preguntas_examen.anulada IS 'La pregunta fue anulada por el tribunal EN ESTE examen. No puntúa: ni acierto ni fallo ni sin responder.';
COMMENT ON COLUMN public.preguntas_examen.motivo_anulacion IS 'Motivo opcional de la anulación. Sólo con anulada = true.';
COMMENT ON COLUMN public.respuestas_intento.dificultad_percibida IS 'Dato opcional preguntado puntualmente: facil, normal, dificil o muy_dificil.';
COMMENT ON COLUMN public.respuestas_intento.nivel_confianza IS 'Dato opcional preguntado puntualmente: seguro, duda o azar.';
COMMENT ON TABLE public.eventos_app IS 'Eventos ligeros recogidos en segundo plano para entender uso de la app sin molestar al usuario.';
COMMENT ON TABLE public.preguntas_examen IS 'Vínculo M:N pregunta <-> examen (desde 2026-09-17). Primera respuesta y ranking son globales por (usuario, pregunta): una pregunta compartida puntúa ranking sólo la primera vez que el usuario la responde en cualquier examen (decisión C-09).';
COMMENT ON FUNCTION private.tg_preguntas_codigo_pregunta() IS 'BEFORE INSERT: asigna SIEMPRE codigo_pregunta (ignora el recibido). BEFORE UPDATE OF codigo_pregunta: QUESTION_CODE_IMMUTABLE. INSERT ... ON CONFLICT (codigo_pregunta) nunca hace match (el código es siempre nuevo). INSERT ... ON CONFLICT (id) DO UPDATE SET codigo_pregunta = excluded.codigo_pregunta lanza QUESTION_CODE_IMMUTABLE: los upserts deben excluir la columna. Cargas data-only con triggers activos regenerarían códigos: usar session_replication_role = replica.';
