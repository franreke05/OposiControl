-- 002_content_live_shape.sql
--
-- REPLICA DE LA FORMA VIVA (no la deseada) del dominio de contenido de MAIN.
-- Solo para PostgreSQL DESECHABLE (backend/dbtest). NO es una migracion: nunca va
-- en supabase/migrations y nunca se ejecuta contra MAIN ni contra ningun proyecto
-- de Supabase.
--
-- Para que sirve: demostrar en un PostgreSQL real el comportamiento que hoy tiene
-- MAIN (CHECK, UNIQUE no diferibles, orden de FKs, triggers, RLS, grants) ANTES de
-- escribir en MAIN los cambios de contenido (total_preguntas, codigo_pregunta,
-- RPC atomicas, M:N, reordenacion, RLS de borradores). Si la replica no es la forma
-- viva, lo que se demuestre aqui no vale nada.
--
-- ============================================================================
-- EXTRACCION
-- ============================================================================
-- Fecha:     2026-09-17, 08:10-08:45 UTC. Solo SELECT sobre catalogos; MAIN_WRITES: NONE.
-- Proyecto:  ndiooyyqtaeysnedywer (MAIN), via mcp__supabase__execute_sql.
-- Servidor:  PostgreSQL 17.6 on aarch64-unknown-linux-gnu (server_version_num 170006).
-- Sesion:    search_path = "$user", public, extensions.
-- Replica:   PostgreSQL 16.2 embebido (zonky, backend/dbtest). Diferencias abajo.
--
-- Consultas (todas de solo lectura; $T = lista de tablas incluidas):
--   B0-Q01 select version(), current_setting('server_version_num')
--   B0-Q02 pg_class: relkind, relrowsecurity, relforcerowsecurity, reloptions ($T)
--   B0-Q03 pg_attribute + pg_attrdef: attnum, format_type(atttypid, atttypmod),
--          attnotnull, pg_get_expr(adbin, adrelid), attidentity, attgenerated
--   B0-Q04 pg_constraint: conname, contype, pg_get_constraintdef(oid, true),
--          condeferrable, condeferred, convalidated, confdeltype, oid (orden)
--   B0-Q05 pg_index: pg_get_indexdef(indexrelid), indexrelid (orden)
--   B0-Q06 pg_trigger (not tgisinternal): pg_get_triggerdef(oid), tgenabled
--   B0-Q07 pg_proc: pg_get_functiondef(oid), prosecdef, proconfig, proacl
--          (funciones de trigger + es_admin + auth.uid)
--   B0-Q08 pg_policies: policyname, permissive, roles, cmd, qual, with_check
--   B0-Q09 pg_class.relacl y pg_attribute.attacl (no hay grants por columna)
--   B0-Q10 has_function_privilege / has_schema_privilege / pg_auth_members para
--          anon, authenticated, service_role
--   B0-Q11 pg_rewrite + pg_depend: vistas que dependen de columnas de $T;
--          pg_get_viewdef(oid, true)
--   B0-Q12 pg_constraint: FKs entrantes desde tablas NO incluidas (documentadas)
--   B0-Q13 pg_trigger (tgisinternal) de accion RI por tabla referenciada, por
--          nombre (orden de disparo en vivo)
--   B0-Q14 huella md5 por categoria (columnas, restricciones, indices, triggers,
--          politicas, vistas, funciones, grants): la misma consulta que ejecuta
--          ContentLiveShapeParityTest contra esta replica.
--
-- Tablas objetivo: especialidades, comunidades_autonomas, capacidades_especialidad,
-- examenes, preguntas, opciones_pregunta, soluciones_pregunta, preguntas_examen,
-- recursos_pregunta, lotes_importacion_preguntas, preguntas_parseadas_staging,
-- intentos_examen, respuestas_intento.
--
-- Dependencias incluidas CON SU FORMA VIVA COMPLETA (no son stubs), porque algo de
-- las tablas objetivo las necesita para comportarse como en vivo:
--   roles_app, roles_usuario       -> cuerpo de es_admin() (usado por las politicas)
--   etiquetas                      -> FK respuestas_intento_etiqueta_principal_id_fkey
--   etiquetas_pregunta             -> trigger validar_y_calcular_respuesta_intento
--   estado_premium_usuario         -> politica recursos_pregunta_select_premium
--   estadisticas_globales_pregunta -> vista v_preguntas_dificultad_global
--   v_preguntas_dificultad_global  -> depende de preguntas.id, codigo_pregunta,
--                                     tipo_pregunta y titulo: bloquea ALTER TYPE/DROP
--
-- ============================================================================
-- PRERREQUISITOS (los instala el harness ANTES de este fichero; no estan aqui)
-- ============================================================================
--   roles de cluster: anon, authenticated (nologin), service_role (nologin bypassrls)
--   esquema auth: auth.users(id uuid pk, email text)       -> STUB (vivo: GoTrue)
--                 auth.uid() lee request.jwt.claim.sub      -> STUB (vivo: ademas
--                 hace fallback a request.jwt.claims->>'sub')
--                 usage on schema auth to anon, authenticated, service_role
--   esquema private (usage solo para service_role, como en vivo)
--   gen_random_uuid() y now() son de pg_catalog en vivo (B0-Q03: los DEFAULT no
--   dependen de extensions.gen_random_uuid), asi que no hace falta pgcrypto.
--
-- ============================================================================
-- OMITIDO A PROPOSITO (documentado, no es forma de estas tablas)
-- ============================================================================
--   * Tablas con FK ENTRANTE hacia las incluidas (B0-Q12). Borrar en la replica
--     no tropieza con ellas. La mas relevante: preguntas_intento.pregunta_id ->
--     preguntas ON DELETE RESTRICT (en vivo impide borrar una pregunta ya servida
--     en un intento). Resto: abandonos_examen, contenido_desbloqueado_usuario,
--     eventos_conversion_premium, feedback_examen, feedback_pregunta,
--     cola_repaso_usuario, estadisticas_usuario_pregunta, preguntas_favoritas_usuario,
--     preguntas_reto_diario, uso_recursos_pregunta, activaciones_servicio_usuario,
--     resumen_intento_etiqueta, estadisticas_usuario_etiqueta,
--     estadisticas_globales_etiqueta, etiquetas_opositeca, opositeca_items,
--     perfiles_usuario, retos_diarios, comunidades_objetivo_usuario.
--   * Comentarios (COMMENT ON) de 4 objetos: documentacion, sin efecto.
--   * Publicacion supabase_realtime (incluye estado_premium_usuario): sin efecto.
--   * Event triggers de plataforma (pgrst_*, issue_*): sin efecto sobre el DML.
--   * Filas: ninguna. Ni datos de usuarios ni datos de negocio; los tests crean
--     lo que necesitan.
--
-- ============================================================================
-- DIFERENCIAS CONOCIDAS PG16 (replica) <-> PG17 (vivo)
-- ============================================================================
--   * MAINTAIN (PG17): en vivo anon tiene `rm` (SELECT+MAINTAIN) y el resto
--     `arwdDxtm`. PG16 no tiene MAINTAIN; el bloque final lo concede solo si el
--     servidor es >= 17. No cambia ningun SELECT/INSERT/UPDATE/DELETE.
--   * Propietario: en vivo `postgres` NO es superusuario (tiene BYPASSRLS); en la
--     replica los objetos son del superusuario `postgres`. es_admin() (SECURITY
--     DEFINER) lee roles_usuario/roles_app igual en ambos (los dos saltan RLS).
--   * Cuerpos de funcion: en vivo casi todos usan CRLF; aqui LF. Para plpgsql es
--     espacio en blanco; la huella B0-Q14 compara sin CR.
--
-- ORDEN: las tablas se crean en el orden vivo (pg_class.oid), los indices de cada
-- tabla en el orden vivo (indexrelid) y las FKs en el orden vivo global
-- (pg_constraint.oid). No es cosmetico: PostgreSQL dispara los triggers RI de una
-- misma tabla por nombre (RI_ConstraintTrigger_<oid>) y comprueba los indices
-- UNIQUE por OID, asi que ese orden decide que cascada corre antes y que
-- restriccion salta primero cuando se violan dos.

begin;

-- Las definiciones literales de pg_get_constraintdef/pg_policies/pg_get_viewdef
-- usan nombres sin esquema para public; se resuelven igual que en vivo.
set local search_path = public;

do $preflight$
declare
  v_existentes text;
begin
  if to_regclass('auth.users') is null then
    raise exception 'CONTENT_LIVE_SHAPE_PREREQ_MISSING: auth.users';
  end if;
  if to_regprocedure('auth.uid()') is null then
    raise exception 'CONTENT_LIVE_SHAPE_PREREQ_MISSING: auth.uid()';
  end if;
  if (select count(*) from pg_roles where rolname in ('anon', 'authenticated', 'service_role')) <> 3 then
    raise exception 'CONTENT_LIVE_SHAPE_PREREQ_MISSING: roles anon/authenticated/service_role';
  end if;

  select string_agg(c.relname, ', ' order by c.relname) into v_existentes
  from pg_class c
  where c.relnamespace = 'public'::regnamespace
    and c.relname in ('roles_app', 'roles_usuario', 'especialidades', 'comunidades_autonomas',
                      'etiquetas', 'examenes', 'lotes_importacion_preguntas',
                      'preguntas_parseadas_staging', 'preguntas', 'opciones_pregunta',
                      'soluciones_pregunta', 'preguntas_examen', 'recursos_pregunta',
                      'etiquetas_pregunta', 'intentos_examen', 'respuestas_intento',
                      'estadisticas_globales_pregunta', 'estado_premium_usuario',
                      'capacidades_especialidad', 'v_preguntas_dificultad_global');
  if v_existentes is not null then
    -- 001_live_shape.sql crea especialidades/examenes/preguntas con OTRA forma:
    -- mezclar los dos fixtures daria una replica que no existe en ningun sitio.
    raise exception 'CONTENT_LIVE_SHAPE_TARGET_NOT_EMPTY: %', v_existentes;
  end if;
end
$preflight$;

-- ============================================================================
-- TABLAS (orden vivo por pg_class.oid) con sus CHECK
-- ============================================================================

-- oid 33577
create table public.roles_app (
  id uuid default gen_random_uuid() not null,
  codigo text not null,
  nombre text not null,
  descripcion text,
  creado_en timestamp with time zone default now() not null,
  constraint roles_app_codigo_check CHECK (codigo = ANY (ARRAY['superadmin'::text, 'admin'::text, 'moderador'::text, 'usuario'::text]))
);

-- oid 33589
create table public.roles_usuario (
  usuario_id uuid not null,
  rol_id uuid not null,
  asignado_por uuid,
  creado_en timestamp with time zone default now() not null
);

-- oid 33653
create table public.especialidades (
  id uuid default gen_random_uuid() not null,
  codigo text not null,
  nombre text not null,
  slug text not null,
  descripcion text,
  activa boolean default true not null,
  posicion integer default 1 not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null
);

-- oid 33669
create table public.comunidades_autonomas (
  id uuid default gen_random_uuid() not null,
  codigo text not null,
  nombre text not null,
  slug text not null,
  activa boolean default true not null,
  posicion integer default 1 not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null
);

-- oid 33685
create table public.etiquetas (
  id uuid default gen_random_uuid() not null,
  nombre text not null,
  slug text not null,
  descripcion text,
  color_hex text,
  activa boolean default true not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null
);

-- oid 33708
create table public.examenes (
  id uuid default gen_random_uuid() not null,
  codigo_examen text not null,
  especialidad_id uuid not null,
  comunidad_id uuid,
  titulo text not null,
  descripcion text,
  tipo_examen text not null,
  tipo_acceso text not null,
  total_preguntas integer default 50 not null,
  duracion_minutos integer,
  anio integer,
  es_oficial boolean default false not null,
  activo boolean default false not null,
  publicado_en timestamp with time zone,
  creado_por uuid,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  comprable_con_tickets boolean default false not null,
  precio_tickets integer,
  dias_acceso_compra integer default 30 not null,
  demo_disponible boolean default false not null,
  requiere_plan_codigo text,
  creditos_inmersivos_necesarios integer default 0 not null,
  constraint examenes_canonical_v2_tipo_examen_check CHECK (tipo_examen = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])),
  constraint examenes_creditos_inmersivos_necesarios_check CHECK (creditos_inmersivos_necesarios >= 0),
  constraint examenes_dias_acceso_compra_check CHECK (dias_acceso_compra > 0),
  constraint examenes_duracion_minutos_check CHECK (duracion_minutos IS NULL OR duracion_minutos > 0),
  constraint examenes_precio_tickets_check CHECK (precio_tickets IS NULL OR precio_tickets >= 0),
  constraint examenes_tipo_acceso_check CHECK (tipo_acceso = ANY (ARRAY['gratuito'::text, 'premium'::text])),
  constraint examenes_total_preguntas_check CHECK (total_preguntas > 0)
);

-- oid 33743
create table public.lotes_importacion_preguntas (
  id uuid default gen_random_uuid() not null,
  examen_id uuid not null,
  subido_por uuid,
  nombre_archivo_original text not null,
  ruta_archivo_storage text,
  tipo_examen text not null,
  estado text default 'pendiente'::text not null,
  total_detectadas integer default 0 not null,
  total_importadas integer default 0 not null,
  mensaje_error text,
  metadatos jsonb default '{}'::jsonb not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  constraint lotes_importacion_preguntas_canonical_v2_tipo_examen_check CHECK (tipo_examen = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])),
  constraint lotes_importacion_preguntas_estado_check CHECK (estado = ANY (ARRAY['pendiente'::text, 'parseado'::text, 'revisado'::text, 'importado'::text, 'fallido'::text])),
  constraint lotes_importacion_preguntas_total_detectadas_check CHECK (total_detectadas >= 0),
  constraint lotes_importacion_preguntas_total_importadas_check CHECK (total_importadas >= 0)
);

-- oid 33771 (creada ANTES que preguntas; su FK a preguntas se anadio despues)
create table public.preguntas_parseadas_staging (
  id uuid default gen_random_uuid() not null,
  lote_id uuid not null,
  pregunta_id uuid,
  posicion integer not null,
  bloque_original text not null,
  codigo_pregunta_generado text,
  titulo text,
  opcion_a text,
  opcion_b text,
  opcion_c text,
  opcion_d text,
  respuesta_correcta text,
  razonamiento text,
  fuente_texto text,
  etiqueta_texto text,
  estado_parseo text default 'ok'::text not null,
  error_parseo text,
  metadatos jsonb default '{}'::jsonb not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  constraint preguntas_parseadas_staging_estado_parseo_check CHECK (estado_parseo = ANY (ARRAY['ok'::text, 'advertencia'::text, 'error'::text, 'importada'::text])),
  constraint preguntas_parseadas_staging_posicion_check CHECK (posicion > 0),
  constraint preguntas_parseadas_staging_respuesta_correcta_check CHECK (respuesta_correcta IS NULL OR (respuesta_correcta = ANY (ARRAY['A'::text, 'B'::text, 'C'::text, 'D'::text])))
);

-- oid 33793
create table public.preguntas (
  id uuid default gen_random_uuid() not null,
  codigo_pregunta text not null,
  especialidad_id uuid not null,
  tipo_pregunta text not null,
  titulo text not null,
  fuente_texto text,
  dificultad text,
  activa boolean default true not null,
  lote_importacion_id uuid,
  creado_por uuid,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  constraint preguntas_canonical_v2_tipo_pregunta_check CHECK (tipo_pregunta = ANY (ARRAY['rafaga'::text, 'razonada'::text, 'inmersiva'::text])),
  constraint preguntas_dificultad_check CHECK (dificultad IS NULL OR (dificultad = ANY (ARRAY['facil'::text, 'media'::text, 'dificil'::text])))
);

-- oid 33828
create table public.opciones_pregunta (
  id uuid default gen_random_uuid() not null,
  pregunta_id uuid not null,
  letra text not null,
  texto_opcion text not null,
  posicion integer not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  constraint opciones_pregunta_letra_check CHECK (letra = ANY (ARRAY['A'::text, 'B'::text, 'C'::text, 'D'::text])),
  constraint opciones_pregunta_posicion_check CHECK (posicion >= 1 AND posicion <= 4)
);

-- oid 33849
create table public.soluciones_pregunta (
  id uuid default gen_random_uuid() not null,
  pregunta_id uuid not null,
  opcion_correcta_id uuid not null,
  razonamiento text not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null
);

-- oid 33871
create table public.preguntas_examen (
  examen_id uuid not null,
  pregunta_id uuid not null,
  posicion integer not null,
  creado_en timestamp with time zone default now() not null,
  numero_en_examen integer not null,
  constraint preguntas_examen_numero_check CHECK (numero_en_examen >= 1 AND numero_en_examen <= 200),
  constraint preguntas_examen_posicion_check CHECK (posicion >= 1 AND posicion <= 200)
);

-- oid 33890
create table public.recursos_pregunta (
  id uuid default gen_random_uuid() not null,
  pregunta_id uuid not null,
  codigo_recurso text not null,
  tipo_recurso text not null,
  titulo text,
  descripcion text,
  bucket_storage text default 'recursos-pregunta'::text,
  ruta_storage text,
  url_externa text,
  contenido_texto text,
  posicion integer default 1 not null,
  activo boolean default true not null,
  metadatos jsonb default '{}'::jsonb not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  constraint recursos_pregunta_posicion_check CHECK (posicion > 0),
  constraint recursos_pregunta_tipo_recurso_check CHECK (tipo_recurso = ANY (ARRAY['imagen'::text, 'audio'::text, 'video'::text, 'texto'::text, 'pdf'::text, 'enlace'::text])),
  constraint recursos_pregunta_ubicacion_chk CHECK (ruta_storage IS NOT NULL OR url_externa IS NOT NULL OR contenido_texto IS NOT NULL)
);

-- oid 33914
create table public.etiquetas_pregunta (
  pregunta_id uuid not null,
  etiqueta_id uuid not null,
  creado_en timestamp with time zone default now() not null
);

-- oid 33936
create table public.intentos_examen (
  id uuid default gen_random_uuid() not null,
  usuario_id uuid not null,
  examen_id uuid,
  estado text default 'en_progreso'::text not null,
  iniciado_en timestamp with time zone default now() not null,
  finalizado_en timestamp with time zone,
  duracion_segundos integer,
  total_preguntas integer default 0 not null,
  total_respondidas integer default 0 not null,
  total_correctas integer default 0 not null,
  puntuacion numeric(6,2),
  metadatos jsonb default '{}'::jsonb not null,
  creado_en timestamp with time zone default now() not null,
  actualizado_en timestamp with time zone default now() not null,
  tipo_examen_snapshot text,
  intento_numero_usuario integer,
  es_primer_intento_examen boolean default false not null,
  cuenta_para_ranking boolean default false not null,
  total_falladas integer default 0 not null,
  total_sin_responder integer default 0 not null,
  porcentaje_acierto numeric(5,2),
  puntuacion_base numeric(8,2),
  puntuacion_final numeric(8,2),
  opositickets_ganados integer default 0 not null,
  modo_estudio text,
  abandonado_en_pregunta_id uuid,
  abandonado_en_posicion integer,
  ultima_pregunta_posicion integer,
  calculado_en timestamp with time zone,
  origen_intento text default 'examen'::text not null,
  constraint intentos_examen_abandonado_en_posicion_check CHECK (abandonado_en_posicion IS NULL OR abandonado_en_posicion > 0),
  constraint intentos_examen_canonical_v2_tipo_examen_snapshot_check CHECK (tipo_examen_snapshot = ANY (ARRAY['rafaga'::text, 'razonado'::text, 'inmersivo'::text])),
  constraint intentos_examen_duracion_segundos_check CHECK (duracion_segundos IS NULL OR duracion_segundos >= 0),
  constraint intentos_examen_estado_check CHECK (estado = ANY (ARRAY['en_progreso'::text, 'finalizado'::text, 'abandonado'::text])),
  constraint intentos_examen_modo_estudio_check CHECK (modo_estudio IS NULL OR (modo_estudio = ANY (ARRAY['examen'::text, 'rafaga'::text, 'repaso_fallos'::text, 'repaso_inteligente'::text, 'entrenamiento'::text]))),
  constraint intentos_examen_opositickets_ganados_check CHECK (opositickets_ganados >= 0),
  constraint intentos_examen_origen_check CHECK (origen_intento = ANY (ARRAY['examen'::text, 'repaso_random'::text])),
  constraint intentos_examen_origen_examen_check CHECK (origen_intento = 'examen'::text AND examen_id IS NOT NULL OR origen_intento = 'repaso_random'::text AND examen_id IS NULL),
  constraint intentos_examen_total_correctas_check CHECK (total_correctas >= 0),
  constraint intentos_examen_total_falladas_check CHECK (total_falladas >= 0),
  constraint intentos_examen_total_preguntas_check CHECK (total_preguntas >= 0),
  constraint intentos_examen_total_respondidas_check CHECK (total_respondidas >= 0),
  constraint intentos_examen_total_sin_responder_check CHECK (total_sin_responder >= 0),
  constraint intentos_examen_ultima_pregunta_posicion_check CHECK (ultima_pregunta_posicion IS NULL OR ultima_pregunta_posicion > 0)
);

-- oid 33967
create table public.respuestas_intento (
  id uuid default gen_random_uuid() not null,
  intento_id uuid not null,
  pregunta_id uuid not null,
  opcion_seleccionada_id uuid,
  es_correcta boolean,
  respondida_en timestamp with time zone,
  tiempo_respuesta_segundos integer,
  creada_en timestamp with time zone default now() not null,
  usuario_id uuid,
  examen_id uuid,
  opcion_correcta_id uuid,
  posicion_pregunta integer,
  etiqueta_principal_id uuid,
  cuenta_para_ranking boolean default false not null,
  es_primer_intento_pregunta boolean default false not null,
  nivel_confianza text,
  dificultad_percibida text,
  tipo_error_usuario text,
  marcada_para_repasar boolean default false not null,
  respondida_al_azar boolean default false not null,
  cambio_respuesta boolean default false not null,
  tiempo_visualizacion_recursos_segundos integer,
  metadatos jsonb default '{}'::jsonb not null,
  constraint respuestas_intento_dificultad_percibida_check CHECK (dificultad_percibida IS NULL OR (dificultad_percibida = ANY (ARRAY['facil'::text, 'normal'::text, 'dificil'::text, 'muy_dificil'::text]))),
  constraint respuestas_intento_first_response_user_not_null CHECK (NOT es_primer_intento_pregunta OR usuario_id IS NOT NULL),
  constraint respuestas_intento_nivel_confianza_check CHECK (nivel_confianza IS NULL OR (nivel_confianza = ANY (ARRAY['seguro'::text, 'duda'::text, 'azar'::text]))),
  constraint respuestas_intento_posicion_pregunta_check CHECK (posicion_pregunta IS NULL OR posicion_pregunta > 0),
  constraint respuestas_intento_ranking_requires_first_response CHECK (NOT cuenta_para_ranking OR es_primer_intento_pregunta),
  constraint respuestas_intento_tiempo_respuesta_segundos_check CHECK (tiempo_respuesta_segundos IS NULL OR tiempo_respuesta_segundos >= 0),
  constraint respuestas_intento_tiempo_visualizacion_recursos_segundos_check CHECK (tiempo_visualizacion_recursos_segundos IS NULL OR tiempo_visualizacion_recursos_segundos >= 0),
  constraint respuestas_intento_tipo_error_usuario_check CHECK (tipo_error_usuario IS NULL OR (tipo_error_usuario = ANY (ARRAY['no_sabia_concepto'::text, 'confundio_opciones'::text, 'leyo_mal'::text, 'falto_tiempo'::text, 'azar'::text, 'otro'::text])))
);

-- oid 34818
create table public.estadisticas_globales_pregunta (
  pregunta_id uuid not null,
  total_respuestas integer default 0 not null,
  total_aciertos integer default 0 not null,
  total_fallos integer default 0 not null,
  total_respuestas_ranking integer default 0 not null,
  total_aciertos_ranking integer default 0 not null,
  total_fallos_ranking integer default 0 not null,
  porcentaje_acierto numeric(5,2) default 0 not null,
  porcentaje_fallo numeric(5,2) default 0 not null,
  tiempo_medio_respuesta_segundos numeric(8,2) default 0 not null,
  dificultad_calculada_1_10 numeric(4,2) default 1 not null,
  actualizado_en timestamp with time zone default now() not null,
  dificultad_calculada_1_5 integer,
  puntos_fallo_1_5 integer,
  puntos_tiempo_1_5 integer,
  muestra_ultimos_usuarios integer default 100 not null,
  mediana_tiempo_respuesta_segundos numeric,
  constraint estadisticas_globales_pregunta_dificultad_calculada_1_10_check CHECK (dificultad_calculada_1_10 >= 1::numeric AND dificultad_calculada_1_10 <= 10::numeric),
  constraint estadisticas_globales_pregunta_dificultad_calculada_1_5_check CHECK (dificultad_calculada_1_5 IS NULL OR dificultad_calculada_1_5 >= 1 AND dificultad_calculada_1_5 <= 5),
  constraint estadisticas_globales_pregunta_total_aciertos_check CHECK (total_aciertos >= 0),
  constraint estadisticas_globales_pregunta_total_aciertos_ranking_check CHECK (total_aciertos_ranking >= 0),
  constraint estadisticas_globales_pregunta_total_fallos_check CHECK (total_fallos >= 0),
  constraint estadisticas_globales_pregunta_total_fallos_ranking_check CHECK (total_fallos_ranking >= 0),
  constraint estadisticas_globales_pregunta_total_respuestas_check CHECK (total_respuestas >= 0),
  constraint estadisticas_globales_pregunta_total_respuestas_ranking_check CHECK (total_respuestas_ranking >= 0)
);

-- oid 35266
create table public.estado_premium_usuario (
  usuario_id uuid not null,
  es_premium boolean default false not null,
  estado_suscripcion text default 'sin_suscripcion'::text not null,
  producto_codigo text,
  proveedor text,
  inicio_en timestamp with time zone,
  fin_en timestamp with time zone,
  renovacion_en timestamp with time zone,
  actualizado_en timestamp with time zone default now() not null,
  constraint estado_premium_usuario_estado_suscripcion_check CHECK (estado_suscripcion = ANY (ARRAY['sin_suscripcion'::text, 'activa'::text, 'periodo_prueba'::text, 'pausada'::text, 'cancelada'::text, 'caducada'::text])),
  constraint estado_premium_usuario_proveedor_check CHECK (proveedor IS NULL OR (proveedor = ANY (ARRAY['google_play'::text, 'app_store'::text, 'stripe'::text, 'manual'::text])))
);

-- oid 35422
create table public.capacidades_especialidad (
  especialidad_id uuid not null,
  permite_rafagas boolean default true not null,
  permite_razonados boolean default false not null,
  permite_inmersivos boolean default false not null,
  permite_demos boolean default true not null,
  permite_oposimarket boolean default true not null,
  mensaje_limitacion text,
  actualizado_en timestamp with time zone default now() not null
);

-- ============================================================================
-- PRIMARY KEY / UNIQUE / INDICES (por tabla, en orden vivo de indexrelid)
-- ============================================================================
-- Ninguna PK/UNIQUE es DEFERRABLE en vivo: la unicidad se comprueba fila a fila,
-- en el momento, y un intercambio de valores con dos UPDATE choca.

alter table only public.roles_app add constraint roles_app_pkey PRIMARY KEY (id);
alter table only public.roles_app add constraint roles_app_codigo_key UNIQUE (codigo);

alter table only public.roles_usuario add constraint roles_usuario_pkey PRIMARY KEY (usuario_id, rol_id);
CREATE INDEX idx_roles_usuario_usuario_id ON public.roles_usuario USING btree (usuario_id);
CREATE INDEX idx_roles_usuario_rol_id ON public.roles_usuario USING btree (rol_id);
CREATE INDEX idx_roles_usuario_asignado_por ON public.roles_usuario USING btree (asignado_por);

alter table only public.especialidades add constraint especialidades_pkey PRIMARY KEY (id);
alter table only public.especialidades add constraint especialidades_codigo_key UNIQUE (codigo);
alter table only public.especialidades add constraint especialidades_slug_key UNIQUE (slug);

alter table only public.comunidades_autonomas add constraint comunidades_autonomas_pkey PRIMARY KEY (id);
alter table only public.comunidades_autonomas add constraint comunidades_autonomas_codigo_key UNIQUE (codigo);
alter table only public.comunidades_autonomas add constraint comunidades_autonomas_slug_key UNIQUE (slug);

alter table only public.etiquetas add constraint etiquetas_pkey PRIMARY KEY (id);
alter table only public.etiquetas add constraint etiquetas_slug_key UNIQUE (slug);

alter table only public.examenes add constraint examenes_pkey PRIMARY KEY (id);
alter table only public.examenes add constraint examenes_codigo_examen_key UNIQUE (codigo_examen);
CREATE INDEX idx_examenes_especialidad ON public.examenes USING btree (especialidad_id);
CREATE INDEX idx_examenes_tipo ON public.examenes USING btree (tipo_examen);
CREATE INDEX idx_examenes_acceso ON public.examenes USING btree (tipo_acceso);
CREATE INDEX idx_examenes_activo ON public.examenes USING btree (activo);
CREATE INDEX idx_examenes_tipo_activo ON public.examenes USING btree (tipo_examen, activo);
CREATE INDEX idx_examenes_comunidad_id ON public.examenes USING btree (comunidad_id);
CREATE INDEX idx_examenes_creado_por ON public.examenes USING btree (creado_por);

alter table only public.lotes_importacion_preguntas add constraint lotes_importacion_preguntas_pkey PRIMARY KEY (id);
CREATE INDEX idx_lotes_importacion_examen ON public.lotes_importacion_preguntas USING btree (examen_id);
CREATE INDEX idx_lotes_importacion_preguntas_subido_por ON public.lotes_importacion_preguntas USING btree (subido_por);

alter table only public.preguntas_parseadas_staging add constraint preguntas_parseadas_staging_pkey PRIMARY KEY (id);
alter table only public.preguntas_parseadas_staging add constraint preguntas_parseadas_staging_lote_id_posicion_key UNIQUE (lote_id, posicion);
CREATE INDEX idx_preguntas_staging_lote ON public.preguntas_parseadas_staging USING btree (lote_id);
CREATE INDEX idx_preguntas_staging_estado ON public.preguntas_parseadas_staging USING btree (estado_parseo);
CREATE INDEX idx_preguntas_parseadas_staging_pregunta_id ON public.preguntas_parseadas_staging USING btree (pregunta_id);

alter table only public.preguntas add constraint preguntas_pkey PRIMARY KEY (id);
alter table only public.preguntas add constraint preguntas_codigo_pregunta_key UNIQUE (codigo_pregunta);
CREATE INDEX idx_preguntas_codigo ON public.preguntas USING btree (codigo_pregunta);
CREATE INDEX idx_preguntas_tipo ON public.preguntas USING btree (tipo_pregunta);
CREATE INDEX idx_preguntas_especialidad ON public.preguntas USING btree (especialidad_id);
CREATE INDEX idx_preguntas_tipo_activa ON public.preguntas USING btree (tipo_pregunta, activa);
CREATE INDEX idx_preguntas_creado_por ON public.preguntas USING btree (creado_por);
CREATE INDEX idx_preguntas_lote_importacion_id ON public.preguntas USING btree (lote_importacion_id);

alter table only public.opciones_pregunta add constraint opciones_pregunta_pkey PRIMARY KEY (id);
alter table only public.opciones_pregunta add constraint opciones_pregunta_pregunta_id_letra_key UNIQUE (pregunta_id, letra);
alter table only public.opciones_pregunta add constraint opciones_pregunta_pregunta_id_posicion_key UNIQUE (pregunta_id, posicion);
CREATE INDEX idx_opciones_pregunta_pregunta ON public.opciones_pregunta USING btree (pregunta_id);

alter table only public.soluciones_pregunta add constraint soluciones_pregunta_pkey PRIMARY KEY (id);
alter table only public.soluciones_pregunta add constraint soluciones_pregunta_pregunta_id_key UNIQUE (pregunta_id);
CREATE INDEX idx_soluciones_pregunta_pregunta ON public.soluciones_pregunta USING btree (pregunta_id);
CREATE INDEX idx_soluciones_pregunta_opcion_correcta_id ON public.soluciones_pregunta USING btree (opcion_correcta_id);

-- preguntas_examen_pregunta_unica (UNIQUE (pregunta_id)) y preguntas_examen_numero_unico
-- se anadieron despues de los indices simples: su OID es el mayor de la tabla.
alter table only public.preguntas_examen add constraint preguntas_examen_pkey PRIMARY KEY (examen_id, pregunta_id);
alter table only public.preguntas_examen add constraint preguntas_examen_examen_id_posicion_key UNIQUE (examen_id, posicion);
CREATE INDEX idx_preguntas_examen_examen ON public.preguntas_examen USING btree (examen_id);
CREATE INDEX idx_preguntas_examen_pregunta ON public.preguntas_examen USING btree (pregunta_id);
CREATE INDEX idx_preguntas_examen_examen_posicion ON public.preguntas_examen USING btree (examen_id, posicion);
alter table only public.preguntas_examen add constraint preguntas_examen_pregunta_unica UNIQUE (pregunta_id);
alter table only public.preguntas_examen add constraint preguntas_examen_numero_unico UNIQUE (examen_id, numero_en_examen);

alter table only public.recursos_pregunta add constraint recursos_pregunta_pkey PRIMARY KEY (id);
alter table only public.recursos_pregunta add constraint recursos_pregunta_codigo_recurso_key UNIQUE (codigo_recurso);
CREATE INDEX idx_recursos_pregunta_pregunta ON public.recursos_pregunta USING btree (pregunta_id);
CREATE INDEX idx_recursos_pregunta_codigo ON public.recursos_pregunta USING btree (codigo_recurso);

alter table only public.etiquetas_pregunta add constraint etiquetas_pregunta_pkey PRIMARY KEY (pregunta_id, etiqueta_id);
CREATE INDEX idx_etiquetas_pregunta_etiqueta ON public.etiquetas_pregunta USING btree (etiqueta_id);

alter table only public.intentos_examen add constraint intentos_examen_pkey PRIMARY KEY (id);
CREATE INDEX idx_intentos_examen_examen ON public.intentos_examen USING btree (examen_id);
CREATE INDEX idx_intentos_examen_ranking ON public.intentos_examen USING btree (examen_id, cuenta_para_ranking, estado);
CREATE INDEX idx_intentos_examen_usuario_examen ON public.intentos_examen USING btree (usuario_id, examen_id);
CREATE INDEX idx_intentos_examen_finalizado ON public.intentos_examen USING btree (finalizado_en);
CREATE INDEX idx_intentos_usuario ON public.intentos_examen USING btree (usuario_id);
CREATE INDEX idx_intentos_examen_abandonado_en_pregunta_id ON public.intentos_examen USING btree (abandonado_en_pregunta_id);

alter table only public.respuestas_intento add constraint respuestas_intento_pkey PRIMARY KEY (id);
alter table only public.respuestas_intento add constraint respuestas_intento_intento_id_pregunta_id_key UNIQUE (intento_id, pregunta_id);
CREATE INDEX idx_respuestas_intento_intento ON public.respuestas_intento USING btree (intento_id);
CREATE INDEX idx_respuestas_intento_pregunta ON public.respuestas_intento USING btree (pregunta_id);
CREATE INDEX idx_respuestas_intento_usuario ON public.respuestas_intento USING btree (usuario_id);
CREATE INDEX idx_respuestas_intento_examen ON public.respuestas_intento USING btree (examen_id);
CREATE INDEX idx_respuestas_intento_usuario_pregunta ON public.respuestas_intento USING btree (usuario_id, pregunta_id);
CREATE INDEX idx_respuestas_intento_ranking ON public.respuestas_intento USING btree (cuenta_para_ranking, es_correcta);
CREATE INDEX idx_respuestas_intento_etiqueta ON public.respuestas_intento USING btree (etiqueta_principal_id);
CREATE INDEX idx_respuestas_intento_opcion_correcta_id ON public.respuestas_intento USING btree (opcion_correcta_id);
CREATE INDEX idx_respuestas_intento_opcion_seleccionada_id ON public.respuestas_intento USING btree (opcion_seleccionada_id);
CREATE UNIQUE INDEX ux_respuestas_first_response ON public.respuestas_intento USING btree (usuario_id, pregunta_id) WHERE (es_primer_intento_pregunta IS TRUE);

alter table only public.estadisticas_globales_pregunta add constraint estadisticas_globales_pregunta_pkey PRIMARY KEY (pregunta_id);
CREATE INDEX idx_estadisticas_globales_pregunta_dificultad ON public.estadisticas_globales_pregunta USING btree (dificultad_calculada_1_10 DESC);

alter table only public.estado_premium_usuario add constraint estado_premium_usuario_pkey PRIMARY KEY (usuario_id);
CREATE INDEX idx_estado_premium_usuario_usuario ON public.estado_premium_usuario USING btree (usuario_id);
CREATE INDEX idx_estado_premium_usuario_premium ON public.estado_premium_usuario USING btree (es_premium);

alter table only public.capacidades_especialidad add constraint capacidades_especialidad_pkey PRIMARY KEY (especialidad_id);

-- ============================================================================
-- FOREIGN KEYS (orden vivo GLOBAL por pg_constraint.oid)
-- ============================================================================
-- Ninguna es DEFERRABLE. Orden de disparo en vivo al borrar una pregunta:
-- staging (SET NULL), opciones (CASCADE), soluciones (CASCADE), preguntas_examen,
-- recursos, etiquetas_pregunta, respuestas_intento, intentos_examen.abandonado_en
-- (SET NULL), estadisticas_globales_pregunta.

alter table only public.roles_usuario add constraint roles_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
alter table only public.roles_usuario add constraint roles_usuario_rol_id_fkey FOREIGN KEY (rol_id) REFERENCES roles_app(id) ON DELETE CASCADE;
alter table only public.roles_usuario add constraint roles_usuario_asignado_por_fkey FOREIGN KEY (asignado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
alter table only public.examenes add constraint examenes_especialidad_id_fkey FOREIGN KEY (especialidad_id) REFERENCES especialidades(id) ON DELETE RESTRICT;
alter table only public.examenes add constraint examenes_comunidad_id_fkey FOREIGN KEY (comunidad_id) REFERENCES comunidades_autonomas(id) ON DELETE SET NULL;
alter table only public.examenes add constraint examenes_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
alter table only public.lotes_importacion_preguntas add constraint lotes_importacion_preguntas_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES examenes(id) ON DELETE CASCADE;
alter table only public.lotes_importacion_preguntas add constraint lotes_importacion_preguntas_subido_por_fkey FOREIGN KEY (subido_por) REFERENCES auth.users(id) ON DELETE SET NULL;
alter table only public.preguntas_parseadas_staging add constraint preguntas_parseadas_staging_lote_id_fkey FOREIGN KEY (lote_id) REFERENCES lotes_importacion_preguntas(id) ON DELETE CASCADE;
alter table only public.preguntas add constraint preguntas_especialidad_id_fkey FOREIGN KEY (especialidad_id) REFERENCES especialidades(id) ON DELETE RESTRICT;
alter table only public.preguntas add constraint preguntas_lote_importacion_id_fkey FOREIGN KEY (lote_importacion_id) REFERENCES lotes_importacion_preguntas(id) ON DELETE SET NULL;
alter table only public.preguntas add constraint preguntas_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
alter table only public.preguntas_parseadas_staging add constraint preguntas_parseadas_staging_pregunta_fk FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE SET NULL;
alter table only public.opciones_pregunta add constraint opciones_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.soluciones_pregunta add constraint soluciones_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.soluciones_pregunta add constraint soluciones_pregunta_opcion_correcta_id_fkey FOREIGN KEY (opcion_correcta_id) REFERENCES opciones_pregunta(id) ON DELETE RESTRICT;
alter table only public.preguntas_examen add constraint preguntas_examen_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES examenes(id) ON DELETE CASCADE;
alter table only public.preguntas_examen add constraint preguntas_examen_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.recursos_pregunta add constraint recursos_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.etiquetas_pregunta add constraint etiquetas_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.etiquetas_pregunta add constraint etiquetas_pregunta_etiqueta_id_fkey FOREIGN KEY (etiqueta_id) REFERENCES etiquetas(id) ON DELETE CASCADE;
alter table only public.intentos_examen add constraint intentos_examen_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
alter table only public.intentos_examen add constraint intentos_examen_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES examenes(id) ON DELETE CASCADE;
alter table only public.respuestas_intento add constraint respuestas_intento_intento_id_fkey FOREIGN KEY (intento_id) REFERENCES intentos_examen(id) ON DELETE CASCADE;
alter table only public.respuestas_intento add constraint respuestas_intento_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.respuestas_intento add constraint respuestas_intento_opcion_seleccionada_id_fkey FOREIGN KEY (opcion_seleccionada_id) REFERENCES opciones_pregunta(id) ON DELETE SET NULL;
alter table only public.intentos_examen add constraint intentos_examen_abandonado_en_pregunta_id_fkey FOREIGN KEY (abandonado_en_pregunta_id) REFERENCES preguntas(id) ON DELETE SET NULL;
alter table only public.respuestas_intento add constraint respuestas_intento_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
alter table only public.respuestas_intento add constraint respuestas_intento_examen_id_fkey FOREIGN KEY (examen_id) REFERENCES examenes(id) ON DELETE CASCADE;
alter table only public.respuestas_intento add constraint respuestas_intento_opcion_correcta_id_fkey FOREIGN KEY (opcion_correcta_id) REFERENCES opciones_pregunta(id) ON DELETE SET NULL;
alter table only public.respuestas_intento add constraint respuestas_intento_etiqueta_principal_id_fkey FOREIGN KEY (etiqueta_principal_id) REFERENCES etiquetas(id) ON DELETE SET NULL;
alter table only public.estadisticas_globales_pregunta add constraint estadisticas_globales_pregunta_pregunta_id_fkey FOREIGN KEY (pregunta_id) REFERENCES preguntas(id) ON DELETE CASCADE;
alter table only public.estado_premium_usuario add constraint estado_premium_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
alter table only public.capacidades_especialidad add constraint capacidades_especialidad_especialidad_id_fkey FOREIGN KEY (especialidad_id) REFERENCES especialidades(id) ON DELETE CASCADE;

-- ============================================================================
-- VISTA (bloquea ALTER COLUMN TYPE / DROP COLUMN en preguntas.id, codigo_pregunta,
-- tipo_pregunta, titulo)
-- ============================================================================

create view public.v_preguntas_dificultad_global with (security_invoker = true) as
 SELECT p.id AS pregunta_id,
    p.codigo_pregunta,
    p.tipo_pregunta,
    p.titulo,
    egp.total_respuestas,
    egp.total_aciertos,
    egp.total_fallos,
    egp.porcentaje_fallo,
    egp.dificultad_calculada_1_10,
    egp.tiempo_medio_respuesta_segundos,
    egp.actualizado_en
   FROM preguntas p
     LEFT JOIN estadisticas_globales_pregunta egp ON egp.pregunta_id = p.id;

-- ============================================================================
-- FUNCIONES (pg_get_functiondef literal; cuerpos con LF en vez de CRLF)
-- ============================================================================
-- Ojo: los mensajes de error van sin tildes en vivo ('opcion', 'rafaga').

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

CREATE OR REPLACE FUNCTION public.validar_pregunta_examen()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
DECLARE
    v_tipo_examen text;
    v_especialidad_examen uuid;
    v_tipo_pregunta text;
    v_especialidad_pregunta uuid;
BEGIN
    SELECT tipo_examen, especialidad_id
      INTO v_tipo_examen, v_especialidad_examen
    FROM public.examenes
    WHERE id = NEW.examen_id;

    SELECT tipo_pregunta, especialidad_id
      INTO v_tipo_pregunta, v_especialidad_pregunta
    FROM public.preguntas
    WHERE id = NEW.pregunta_id;

    IF v_especialidad_examen <> v_especialidad_pregunta THEN
        RAISE EXCEPTION
            'La pregunta y el examen pertenecen a especialidades distintas';
    END IF;

    IF NOT (
           (v_tipo_examen = 'rafaga'
            AND v_tipo_pregunta = 'rafaga')
        OR (v_tipo_examen = 'razonado'
            AND v_tipo_pregunta = 'razonada')
        OR (v_tipo_examen = 'inmersivo'
            AND v_tipo_pregunta = 'inmersiva')
    ) THEN
        RAISE EXCEPTION
            'El tipo de pregunta no coincide con el tipo de examen';
    END IF;

    RETURN NEW;
END;
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
$function$;

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

-- ============================================================================
-- TRIGGERS (pg_get_triggerdef literal). Dentro de una tabla se disparan por
-- NOMBRE: en preguntas_examen corre antes trg_validar_pregunta_examen que
-- trg_validar_pregunta_examen_mismo_tipo, y en recursos_pregunta antes
-- trg_validar_recurso_inmersivo. Todos son BEFORE: saltan antes que los CHECK.
-- ============================================================================

CREATE TRIGGER trg_comunidades_autonomas_actualizado_en BEFORE UPDATE ON public.comunidades_autonomas FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_especialidades_actualizado_en BEFORE UPDATE ON public.especialidades FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_etiquetas_actualizado_en BEFORE UPDATE ON public.etiquetas FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_examenes_actualizado_en BEFORE UPDATE ON public.examenes FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_intentos_examen_actualizado_en BEFORE UPDATE ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_preparar_intento_examen BEFORE INSERT ON public.intentos_examen FOR EACH ROW EXECUTE FUNCTION preparar_intento_examen();
CREATE TRIGGER trg_lotes_importacion_preguntas_actualizado_en BEFORE UPDATE ON public.lotes_importacion_preguntas FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_opciones_pregunta_actualizado_en BEFORE UPDATE ON public.opciones_pregunta FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_preguntas_actualizado_en BEFORE UPDATE ON public.preguntas FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_validar_pregunta_examen BEFORE INSERT OR UPDATE ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION validar_pregunta_examen();
CREATE TRIGGER trg_validar_pregunta_examen_mismo_tipo BEFORE INSERT OR UPDATE ON public.preguntas_examen FOR EACH ROW EXECUTE FUNCTION validar_pregunta_examen_mismo_tipo();
CREATE TRIGGER trg_preguntas_parseadas_staging_actualizado_en BEFORE UPDATE ON public.preguntas_parseadas_staging FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_recursos_pregunta_actualizado_en BEFORE UPDATE ON public.recursos_pregunta FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_validar_recurso_inmersivo BEFORE INSERT OR UPDATE OF pregunta_id ON public.recursos_pregunta FOR EACH ROW EXECUTE FUNCTION validar_recurso_inmersivo();
CREATE TRIGGER trg_validar_recurso_solo_pregunta_inmersiva BEFORE INSERT OR UPDATE ON public.recursos_pregunta FOR EACH ROW EXECUTE FUNCTION validar_recurso_solo_pregunta_inmersiva();
CREATE TRIGGER trg_validar_y_calcular_respuesta_intento BEFORE INSERT OR UPDATE ON public.respuestas_intento FOR EACH ROW EXECUTE FUNCTION validar_y_calcular_respuesta_intento();
CREATE TRIGGER trg_soluciones_pregunta_actualizado_en BEFORE UPDATE ON public.soluciones_pregunta FOR EACH ROW EXECUTE FUNCTION establecer_actualizado_en();
CREATE TRIGGER trg_validar_solucion_opcion_misma_pregunta BEFORE INSERT OR UPDATE ON public.soluciones_pregunta FOR EACH ROW EXECUTE FUNCTION validar_solucion_opcion_misma_pregunta();

-- ============================================================================
-- RLS (enable, ninguna FORCE) + POLITICAS (reconstruidas de pg_policies)
-- ============================================================================
-- Todas PERMISSIVE. Ninguna politica de SELECT mira publicado_en.

alter table public.roles_app enable row level security;
alter table public.roles_usuario enable row level security;
alter table public.especialidades enable row level security;
alter table public.comunidades_autonomas enable row level security;
alter table public.etiquetas enable row level security;
alter table public.examenes enable row level security;
alter table public.lotes_importacion_preguntas enable row level security;
alter table public.preguntas_parseadas_staging enable row level security;
alter table public.preguntas enable row level security;
alter table public.opciones_pregunta enable row level security;
alter table public.soluciones_pregunta enable row level security;
alter table public.preguntas_examen enable row level security;
alter table public.recursos_pregunta enable row level security;
alter table public.etiquetas_pregunta enable row level security;
alter table public.intentos_examen enable row level security;
alter table public.respuestas_intento enable row level security;
alter table public.estadisticas_globales_pregunta enable row level security;
alter table public.estado_premium_usuario enable row level security;
alter table public.capacidades_especialidad enable row level security;

create policy canonical_v2_capabilities_public_read on public.capacidades_especialidad
  as permissive for select to anon, authenticated
  using (true);

create policy comunidades_admin_all on public.comunidades_autonomas
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy comunidades_select_publico on public.comunidades_autonomas
  as permissive for select to anon, authenticated
  using (((activa = true) OR es_admin()));

create policy especialidades_admin_all on public.especialidades
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy especialidades_select_publico on public.especialidades
  as permissive for select to anon, authenticated
  using (((activa = true) OR es_admin()));

create policy estadisticas_globales_pregunta_select_auth on public.estadisticas_globales_pregunta
  as permissive for select to authenticated
  using (true);

create policy estado_premium_select_propio on public.estado_premium_usuario
  as permissive for select to authenticated
  using (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));

create policy etiquetas_admin_all on public.etiquetas
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy etiquetas_select_publico on public.etiquetas
  as permissive for select to anon, authenticated
  using (((activa = true) OR es_admin()));

create policy etiquetas_pregunta_admin_all on public.etiquetas_pregunta
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy etiquetas_pregunta_select_gratuitas on public.etiquetas_pregunta
  as permissive for select to anon, authenticated
  using (((EXISTS ( SELECT 1
   FROM ((preguntas p
     JOIN preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = etiquetas_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.tipo_acceso = 'gratuito'::text)))) OR es_admin()));

create policy examenes_admin_all on public.examenes
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy examenes_select_activos on public.examenes
  as permissive for select to anon, authenticated
  using (((activo = true) OR es_admin()));

create policy product_metrics_intentos_select_propio on public.intentos_examen
  as permissive for select to authenticated
  using ((usuario_id = ( SELECT auth.uid() AS uid)));

create policy lotes_importacion_admin_all on public.lotes_importacion_preguntas
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());

create policy opciones_pregunta_admin_all on public.opciones_pregunta
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy opciones_pregunta_select_gratuitas on public.opciones_pregunta
  as permissive for select to anon, authenticated
  using (((EXISTS ( SELECT 1
   FROM ((preguntas p
     JOIN preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = opciones_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.tipo_acceso = 'gratuito'::text)))) OR es_admin()));

create policy preguntas_admin_all on public.preguntas
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy preguntas_select_gratuitas on public.preguntas
  as permissive for select to anon, authenticated
  using ((((activa = true) AND (EXISTS ( SELECT 1
   FROM (preguntas_examen pe
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((pe.pregunta_id = preguntas.id) AND (e.activo = true) AND (e.tipo_acceso = 'gratuito'::text))))) OR es_admin()));

create policy preguntas_examen_admin_all on public.preguntas_examen
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy preguntas_examen_select_gratuitas on public.preguntas_examen
  as permissive for select to anon, authenticated
  using (((EXISTS ( SELECT 1
   FROM examenes e
  WHERE ((e.id = preguntas_examen.examen_id) AND (e.activo = true) AND (e.tipo_acceso = 'gratuito'::text)))) OR es_admin()));

create policy preguntas_parseadas_admin_all on public.preguntas_parseadas_staging
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());

create policy recursos_pregunta_admin_all on public.recursos_pregunta
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy recursos_pregunta_select_premium on public.recursos_pregunta
  as permissive for select to authenticated
  using (((activo = true) AND (EXISTS ( SELECT 1
   FROM estado_premium_usuario epu
  WHERE ((epu.usuario_id = ( SELECT auth.uid() AS uid)) AND (epu.es_premium = true)))) AND (EXISTS ( SELECT 1
   FROM ((preguntas p
     JOIN preguntas_examen pe ON ((pe.pregunta_id = p.id)))
     JOIN examenes e ON ((e.id = pe.examen_id)))
  WHERE ((p.id = recursos_pregunta.pregunta_id) AND (p.activa = true) AND (e.activo = true) AND (e.tipo_acceso = 'premium'::text) AND (e.tipo_examen = 'inmersivo'::text))))));

create policy product_metrics_respuestas_select_propio on public.respuestas_intento
  as permissive for select to authenticated
  using ((usuario_id = ( SELECT auth.uid() AS uid)));

create policy roles_app_select_auth on public.roles_app
  as permissive for select to authenticated
  using (true);

create policy roles_usuario_admin_all on public.roles_usuario
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());
create policy roles_usuario_select_propio on public.roles_usuario
  as permissive for select to authenticated
  using (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));

create policy soluciones_pregunta_admin_all on public.soluciones_pregunta
  as permissive for all to authenticated
  using (es_admin()) with check (es_admin());

-- ============================================================================
-- GRANTS (relacl / proacl vivos, en el mismo orden de concesion)
-- ============================================================================
-- anon: solo SELECT en contenido publico; NUNCA en intentos_examen ni
-- respuestas_intento. authenticated: todo en contenido (la RLS es la unica
-- barrera). service_role: todo.

grant select on table public.roles_app to anon;
grant select on table public.roles_app to authenticated;
grant all on table public.roles_app to service_role;

grant select on table public.roles_usuario to anon;
grant all on table public.roles_usuario to authenticated;
grant all on table public.roles_usuario to service_role;

grant select on table public.especialidades to anon;
grant all on table public.especialidades to authenticated;
grant all on table public.especialidades to service_role;

grant select on table public.comunidades_autonomas to anon;
grant all on table public.comunidades_autonomas to authenticated;
grant all on table public.comunidades_autonomas to service_role;

grant select on table public.etiquetas to anon;
grant all on table public.etiquetas to authenticated;
grant all on table public.etiquetas to service_role;

grant select on table public.examenes to anon;
grant all on table public.examenes to authenticated;
grant all on table public.examenes to service_role;

grant select on table public.lotes_importacion_preguntas to anon;
grant all on table public.lotes_importacion_preguntas to authenticated;
grant all on table public.lotes_importacion_preguntas to service_role;

grant select on table public.preguntas_parseadas_staging to anon;
grant all on table public.preguntas_parseadas_staging to authenticated;
grant all on table public.preguntas_parseadas_staging to service_role;

grant select on table public.preguntas to anon;
grant all on table public.preguntas to authenticated;
grant all on table public.preguntas to service_role;

grant select on table public.opciones_pregunta to anon;
grant all on table public.opciones_pregunta to authenticated;
grant all on table public.opciones_pregunta to service_role;

grant select on table public.soluciones_pregunta to anon;
grant all on table public.soluciones_pregunta to authenticated;
grant all on table public.soluciones_pregunta to service_role;

grant select on table public.preguntas_examen to anon;
grant all on table public.preguntas_examen to authenticated;
grant all on table public.preguntas_examen to service_role;

grant select on table public.recursos_pregunta to anon;
grant all on table public.recursos_pregunta to authenticated;
grant all on table public.recursos_pregunta to service_role;

grant select on table public.etiquetas_pregunta to anon;
grant all on table public.etiquetas_pregunta to authenticated;
grant all on table public.etiquetas_pregunta to service_role;

grant all on table public.intentos_examen to service_role;
grant select on table public.intentos_examen to authenticated;

grant all on table public.respuestas_intento to service_role;
grant select on table public.respuestas_intento to authenticated;

grant select on table public.estadisticas_globales_pregunta to anon;
grant select on table public.estadisticas_globales_pregunta to authenticated;
grant all on table public.estadisticas_globales_pregunta to service_role;

grant select on table public.estado_premium_usuario to anon;
grant select on table public.estado_premium_usuario to authenticated;
grant all on table public.estado_premium_usuario to service_role;

grant all on table public.capacidades_especialidad to service_role;
grant select on table public.capacidades_especialidad to anon;
grant select on table public.capacidades_especialidad to authenticated;

grant select on table public.v_preguntas_dificultad_global to anon;
grant select on table public.v_preguntas_dificultad_global to authenticated;
grant all on table public.v_preguntas_dificultad_global to service_role;

-- es_admin(): SIN execute para PUBLIC ni para anon. Consecuencia directa: toda
-- politica de anon que contenga `OR es_admin()` falla al preparar la consulta
-- (42501), aunque la otra rama del OR ya sea verdadera.
revoke all on function public.es_admin() from public;
grant execute on function public.es_admin() to service_role;
grant execute on function public.es_admin() to authenticated;

grant execute on function public.establecer_actualizado_en() to anon, authenticated, service_role;
grant execute on function public.preparar_intento_examen() to anon, authenticated, service_role;
grant execute on function public.validar_pregunta_examen() to anon, authenticated, service_role;
grant execute on function public.validar_pregunta_examen_mismo_tipo() to anon, authenticated, service_role;
grant execute on function public.validar_recurso_inmersivo() to anon, authenticated, service_role;
grant execute on function public.validar_recurso_solo_pregunta_inmersiva() to anon, authenticated, service_role;
grant execute on function public.validar_solucion_opcion_misma_pregunta() to anon, authenticated, service_role;
grant execute on function public.validar_y_calcular_respuesta_intento() to anon, authenticated, service_role;

-- MAINTAIN solo existe desde PG17. En vivo: `rm` para anon en todo lo que lee y
-- para authenticated en roles_app, estado_premium_usuario,
-- estadisticas_globales_pregunta y la vista; `arwdDxtm` (grant all) en el resto.
do $maintain$
begin
  if current_setting('server_version_num')::integer >= 170000 then
    execute 'grant maintain on table public.roles_app, public.roles_usuario, public.especialidades,'
         || ' public.comunidades_autonomas, public.etiquetas, public.examenes,'
         || ' public.lotes_importacion_preguntas, public.preguntas_parseadas_staging, public.preguntas,'
         || ' public.opciones_pregunta, public.soluciones_pregunta, public.preguntas_examen,'
         || ' public.recursos_pregunta, public.etiquetas_pregunta, public.estadisticas_globales_pregunta,'
         || ' public.estado_premium_usuario, public.v_preguntas_dificultad_global to anon';
    execute 'grant maintain on table public.roles_app, public.estadisticas_globales_pregunta,'
         || ' public.estado_premium_usuario, public.v_preguntas_dificultad_global to authenticated';
  end if;
end
$maintain$;

commit;
