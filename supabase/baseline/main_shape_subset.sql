-- GENERADO por scripts/db-pg17-bootstrap-extract.mjs (no editar a mano; ver supabase/baseline/README.md).
-- Forma de MAIN (DDL de catalogo, SIN filas) de los objetos que tocan las migraciones pendientes, copiada verbatim de
-- la captura READ-ONLY de MAIN del gate local. NO es el baseline completo de MAIN.
-- Tablas (15): public.acciones_moderacion, public.atribucion_usuario, public.auditoria_log, public.comunidades_autonomas, public.comunidades_objetivo_usuario, public.documentos_legales, public.especialidades, public.eventos_app, public.perfiles_usuario, public.perfiles_usuario_detalle, public.reportes_contenido, public.roles_app, public.roles_usuario, public.sesiones_estudio, public.usuario
-- Funciones (7): public.admin_role_code_canonical, public.admin_sync_roles, public.es_admin, public.es_moderador_o_admin, public.establecer_actualizado_en, public.handle_new_auth_user, public.record_event
-- Secuencias (0): -
SET check_function_bodies = false;
SET search_path = public, extensions;
SET statement_timeout = 0;
SET check_function_bodies = false;
CREATE SCHEMA IF NOT EXISTS public;
CREATE SCHEMA IF NOT EXISTS private;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;
CREATE TABLE public.acciones_moderacion (id uuid NOT NULL DEFAULT gen_random_uuid(), reporte_id uuid, moderador_id uuid, tipo_accion text NOT NULL, notas text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.atribucion_usuario (usuario_id uuid NOT NULL, fuente text, canal text, campania text, referido_por uuid, codigo_referido text, plataforma text, creado_en timestamp with time zone NOT NULL DEFAULT now(), metadatos jsonb NOT NULL DEFAULT '{}'::jsonb);
CREATE TABLE public.auditoria_log (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, accion text NOT NULL, tabla_afectada text, registro_id uuid, antes jsonb, despues jsonb, ip text, user_agent text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.comunidades_autonomas (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, slug text NOT NULL, activa boolean NOT NULL DEFAULT true, posicion integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.comunidades_objetivo_usuario (usuario_id uuid NOT NULL, comunidad_id uuid NOT NULL, prioridad integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.documentos_legales (id uuid NOT NULL DEFAULT gen_random_uuid(), tipo_documento text NOT NULL, version text NOT NULL, titulo text NOT NULL, contenido text NOT NULL, activo boolean NOT NULL DEFAULT false, publicado_en timestamp with time zone, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.especialidades (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, slug text NOT NULL, descripcion text, activa boolean NOT NULL DEFAULT true, posicion integer NOT NULL DEFAULT 1, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.eventos_app (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid, sesion_id uuid, nombre_evento text NOT NULL, pantalla text, entidad_tipo text, entidad_id uuid, plataforma text, version_app text, idioma text, duracion_ms integer, propiedades jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), event_version integer NOT NULL DEFAULT 1, occurred_en timestamp with time zone NOT NULL DEFAULT now(), received_en timestamp with time zone NOT NULL DEFAULT now(), especialidad_id uuid, intento_id uuid, pregunta_id uuid, idempotency_key text NOT NULL);
CREATE TABLE public.perfiles_usuario (usuario_id uuid NOT NULL, nombre_mostrar text, username text, avatar_url text, especialidad_principal_id uuid, comunidad_principal_id uuid, es_invitado boolean NOT NULL DEFAULT false, onboarding_completado boolean NOT NULL DEFAULT false, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.perfiles_usuario_detalle (usuario_id uuid NOT NULL, nombre_completo text, edad integer, ciudad text, estado_laboral text, centro_trabajo text, tiempo_disponible_estudio_minutos integer, franja_preferente_estudio text, fecha_examen_objetivo date, preferencias_horarias jsonb NOT NULL DEFAULT '{}'::jsonb, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.reportes_contenido (id uuid NOT NULL DEFAULT gen_random_uuid(), reportado_por uuid, tipo_entidad text NOT NULL, entidad_id uuid NOT NULL, motivo text NOT NULL, descripcion text, estado text NOT NULL DEFAULT 'pendiente'::text, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now(), tickets_otorgados integer NOT NULL DEFAULT 0, recompensa_entregada boolean NOT NULL DEFAULT false, revisado_por uuid, revisado_en timestamp with time zone);
CREATE TABLE public.roles_app (id uuid NOT NULL DEFAULT gen_random_uuid(), codigo text NOT NULL, nombre text NOT NULL, descripcion text, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.roles_usuario (usuario_id uuid NOT NULL, rol_id uuid NOT NULL, asignado_por uuid, creado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.sesiones_estudio (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, sesion_cliente_id text, modo_estudio text, iniciada_en timestamp with time zone NOT NULL DEFAULT now(), finalizada_en timestamp with time zone, duracion_segundos integer, preguntas_respondidas integer NOT NULL DEFAULT 0, respuestas_correctas integer NOT NULL DEFAULT 0, respuestas_falladas integer NOT NULL DEFAULT 0, opositickets_ganados integer NOT NULL DEFAULT 0, plataforma text, version_app text, idioma text, metadatos jsonb NOT NULL DEFAULT '{}'::jsonb, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now());
CREATE TABLE public.usuario (id integer GENERATED ALWAYS AS IDENTITY NOT NULL, auth_user_id uuid, email character varying, nombre character varying, created_at timestamp with time zone NOT NULL DEFAULT now(), updated_at timestamp with time zone NOT NULL DEFAULT now(), deleted_at timestamp with time zone, apellidos character varying(100), username character varying(30), especialidad_registro character varying(100), perfil_completo boolean NOT NULL DEFAULT false, perfil_completado_at timestamp with time zone);
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_pkey PRIMARY KEY (id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.auditoria_log ADD CONSTRAINT auditoria_log_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_pkey PRIMARY KEY (usuario_id, comunidad_id);
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_pkey PRIMARY KEY (id);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_pkey PRIMARY KEY (id);
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_pkey PRIMARY KEY (id);
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_pkey PRIMARY KEY (usuario_id);
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_pkey PRIMARY KEY (id);
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_pkey PRIMARY KEY (id);
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_pkey PRIMARY KEY (usuario_id, rol_id);
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_pkey PRIMARY KEY (id);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_pkey PRIMARY KEY (id);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_codigo_key UNIQUE (codigo);
ALTER TABLE public.comunidades_autonomas ADD CONSTRAINT comunidades_autonomas_slug_key UNIQUE (slug);
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_tipo_documento_version_key UNIQUE (tipo_documento, version);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_codigo_key UNIQUE (codigo);
ALTER TABLE public.especialidades ADD CONSTRAINT especialidades_slug_key UNIQUE (slug);
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_username_key UNIQUE (username);
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_codigo_key UNIQUE (codigo);
ALTER TABLE public.usuario ADD CONSTRAINT usuario_auth_user_id_key UNIQUE (auth_user_id);
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_tipo_accion_check CHECK ((tipo_accion = ANY (ARRAY['ocultar'::text, 'restaurar'::text, 'eliminar'::text, 'advertir'::text, 'bloquear_usuario'::text, 'descartar'::text])));
ALTER TABLE public.documentos_legales ADD CONSTRAINT documentos_legales_tipo_documento_check CHECK ((tipo_documento = ANY (ARRAY['privacidad'::text, 'terminos'::text, 'cookies'::text, 'aviso_legal'::text])));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_duracion_ms_check CHECK (((duracion_ms IS NULL) OR (duracion_ms >= 0)));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_event_version_positive CHECK ((event_version > 0));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_idempotency_key_non_blank CHECK ((length(btrim(idempotency_key)) > 0));
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_plataforma_check CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text]))));
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_edad_check CHECK (((edad IS NULL) OR (edad >= 14)));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_estado_check CHECK ((estado = ANY (ARRAY['pendiente'::text, 'revisado'::text, 'resuelto'::text, 'descartado'::text])));
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_tipo_entidad_check CHECK ((tipo_entidad = ANY (ARRAY['pregunta'::text, 'recurso_pregunta'::text, 'opositeca_item'::text, 'publicacion_oposired'::text, 'comentario_oposired'::text, 'usuario'::text])));
ALTER TABLE public.roles_app ADD CONSTRAINT roles_app_codigo_check CHECK ((codigo = ANY (ARRAY['superadmin'::text, 'admin'::text, 'moderador'::text, 'usuario'::text])));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_duracion_segundos_check CHECK (((duracion_segundos IS NULL) OR (duracion_segundos >= 0)));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_modo_estudio_check CHECK (((modo_estudio IS NULL) OR (modo_estudio = ANY (ARRAY['examen'::text, 'rafaga'::text, 'repaso_fallos'::text, 'repaso_inteligente'::text, 'opositeca'::text, 'otro'::text]))));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_opositickets_ganados_check CHECK ((opositickets_ganados >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_plataforma_check CHECK (((plataforma IS NULL) OR (plataforma = ANY (ARRAY['android'::text, 'ios'::text, 'web'::text]))));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_preguntas_respondidas_check CHECK ((preguntas_respondidas >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_respuestas_correctas_check CHECK ((respuestas_correctas >= 0));
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_respuestas_falladas_check CHECK ((respuestas_falladas >= 0));
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_moderador_id_fkey FOREIGN KEY (moderador_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.acciones_moderacion ADD CONSTRAINT acciones_moderacion_reporte_id_fkey FOREIGN KEY (reporte_id) REFERENCES public.reportes_contenido(id) ON DELETE SET NULL;
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_referido_por_fkey FOREIGN KEY (referido_por) REFERENCES auth.users(id);
ALTER TABLE public.atribucion_usuario ADD CONSTRAINT atribucion_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.auditoria_log ADD CONSTRAINT auditoria_log_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_comunidad_id_fkey FOREIGN KEY (comunidad_id) REFERENCES public.comunidades_autonomas(id);
ALTER TABLE public.comunidades_objetivo_usuario ADD CONSTRAINT comunidades_objetivo_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_sesion_id_fkey FOREIGN KEY (sesion_id) REFERENCES public.sesiones_estudio(id) ON DELETE SET NULL;
ALTER TABLE public.eventos_app ADD CONSTRAINT eventos_app_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_comunidad_fk FOREIGN KEY (comunidad_principal_id) REFERENCES public.comunidades_autonomas(id) ON DELETE SET NULL;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_especialidad_fk FOREIGN KEY (especialidad_principal_id) REFERENCES public.especialidades(id) ON DELETE SET NULL;
ALTER TABLE public.perfiles_usuario ADD CONSTRAINT perfiles_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.perfiles_usuario_detalle ADD CONSTRAINT perfiles_usuario_detalle_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_reportado_por_fkey FOREIGN KEY (reportado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.reportes_contenido ADD CONSTRAINT reportes_contenido_revisado_por_fkey FOREIGN KEY (revisado_por) REFERENCES auth.users(id);
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_asignado_por_fkey FOREIGN KEY (asignado_por) REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_rol_id_fkey FOREIGN KEY (rol_id) REFERENCES public.roles_app(id) ON DELETE CASCADE;
ALTER TABLE public.roles_usuario ADD CONSTRAINT roles_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.sesiones_estudio ADD CONSTRAINT sesiones_estudio_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE;
CREATE INDEX idx_acciones_moderacion_moderador_id ON public.acciones_moderacion USING btree (moderador_id);
CREATE INDEX idx_acciones_moderacion_reporte_id ON public.acciones_moderacion USING btree (reporte_id);
CREATE INDEX idx_atribucion_usuario_referido_por ON public.atribucion_usuario USING btree (referido_por);
CREATE INDEX idx_auditoria_log_usuario_id ON public.auditoria_log USING btree (usuario_id);
CREATE INDEX idx_comunidades_objetivo_usuario_comunidad_id ON public.comunidades_objetivo_usuario USING btree (comunidad_id);
CREATE INDEX idx_eventos_app_creado ON public.eventos_app USING btree (creado_en);
CREATE INDEX idx_eventos_app_nombre ON public.eventos_app USING btree (nombre_evento);
CREATE INDEX idx_eventos_app_propiedades_gin ON public.eventos_app USING gin (propiedades);
CREATE INDEX idx_eventos_app_sesion_id ON public.eventos_app USING btree (sesion_id);
CREATE INDEX idx_eventos_app_usuario ON public.eventos_app USING btree (usuario_id);
CREATE INDEX idx_eventos_app_usuario_fecha ON public.eventos_app USING btree (usuario_id, creado_en);
CREATE INDEX idx_perfiles_usuario_comunidad_principal_id ON public.perfiles_usuario USING btree (comunidad_principal_id);
CREATE INDEX idx_perfiles_usuario_especialidad_principal_id ON public.perfiles_usuario USING btree (especialidad_principal_id);
CREATE INDEX idx_perfiles_usuario_username ON public.perfiles_usuario USING btree (username);
CREATE INDEX idx_reportes_contenido_estado ON public.reportes_contenido USING btree (estado);
CREATE INDEX idx_reportes_contenido_reportado_por ON public.reportes_contenido USING btree (reportado_por);
CREATE INDEX idx_reportes_contenido_revisado_por ON public.reportes_contenido USING btree (revisado_por);
CREATE INDEX idx_roles_usuario_asignado_por ON public.roles_usuario USING btree (asignado_por);
CREATE INDEX idx_roles_usuario_rol_id ON public.roles_usuario USING btree (rol_id);
CREATE INDEX idx_roles_usuario_usuario_id ON public.roles_usuario USING btree (usuario_id);
CREATE INDEX idx_sesiones_estudio_usuario_fecha ON public.sesiones_estudio USING btree (usuario_id, iniciada_en DESC);
CREATE UNIQUE INDEX idx_usuario_auth_user_id_unique ON public.usuario USING btree (auth_user_id) WHERE (auth_user_id IS NOT NULL);
CREATE UNIQUE INDEX idx_usuario_username_active_unique ON public.usuario USING btree (lower((username)::text)) WHERE ((username IS NOT NULL) AND (deleted_at IS NULL));
CREATE UNIQUE INDEX ux_eventos_app_idempotency_key ON public.eventos_app USING btree (idempotency_key);
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
CREATE TRIGGER trg_auth_user_created AFTER INSERT OR UPDATE OF email, raw_user_meta_data ON auth.users FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();
CREATE TRIGGER trg_comunidades_autonomas_actualizado_en BEFORE UPDATE ON public.comunidades_autonomas FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_documentos_legales_actualizado_en BEFORE UPDATE ON public.documentos_legales FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_especialidades_actualizado_en BEFORE UPDATE ON public.especialidades FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_perfiles_usuario_actualizado_en BEFORE UPDATE ON public.perfiles_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_reportes_contenido_actualizado_en BEFORE UPDATE ON public.reportes_contenido FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
CREATE TRIGGER trg_sesiones_estudio_actualizado_en BEFORE UPDATE ON public.sesiones_estudio FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en();
ALTER TABLE public.acciones_moderacion ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.atribucion_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auditoria_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comunidades_autonomas ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comunidades_objetivo_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.documentos_legales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.especialidades ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.eventos_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.perfiles_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.perfiles_usuario_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reportes_contenido ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.roles_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.roles_usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sesiones_estudio ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.usuario ENABLE ROW LEVEL SECURITY;
CREATE POLICY acciones_moderacion_staff_all ON public.acciones_moderacion AS PERMISSIVE FOR ALL TO authenticated USING (es_moderador_o_admin()) WITH CHECK (es_moderador_o_admin());
CREATE POLICY auditoria_admin_select ON public.auditoria_log AS PERMISSIVE FOR SELECT TO authenticated USING (es_admin());
CREATE POLICY comunidades_admin_all ON public.comunidades_autonomas AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY comunidades_select_publico ON public.comunidades_autonomas AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR es_admin()));
CREATE POLICY documentos_legales_select_publico ON public.documentos_legales AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activo = true) OR es_admin()));
CREATE POLICY especialidades_admin_all ON public.especialidades AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY especialidades_select_publico ON public.especialidades AS PERMISSIVE FOR SELECT TO anon, authenticated USING (((activa = true) OR es_admin()));
CREATE POLICY perfiles_usuario_insert_propio ON public.perfiles_usuario AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((usuario_id = ( SELECT auth.uid() AS uid)));
CREATE POLICY perfiles_usuario_select_propio ON public.perfiles_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY perfiles_usuario_update_propio ON public.perfiles_usuario AS PERMISSIVE FOR UPDATE TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY reportes_contenido_insert_auth ON public.reportes_contenido AS PERMISSIVE FOR INSERT TO authenticated WITH CHECK ((reportado_por = ( SELECT auth.uid() AS uid)));
CREATE POLICY reportes_contenido_select_staff ON public.reportes_contenido AS PERMISSIVE FOR SELECT TO authenticated USING (es_moderador_o_admin());
CREATE POLICY roles_app_select_auth ON public.roles_app AS PERMISSIVE FOR SELECT TO authenticated USING (true);
CREATE POLICY roles_usuario_admin_all ON public.roles_usuario AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK (es_admin());
CREATE POLICY roles_usuario_select_propio ON public.roles_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY sesiones_estudio_todo_propio ON public.sesiones_estudio AS PERMISSIVE FOR ALL TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())) WITH CHECK (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()));
CREATE POLICY oposibots_consumer_profile_select ON public.usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((( SELECT auth.uid() AS uid) IS NOT NULL) AND (auth_user_id = ( SELECT auth.uid() AS uid))));
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
REVOKE ALL ON TABLE public.eventos_app FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.eventos_app TO service_role;
REVOKE ALL ON TABLE public.perfiles_usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.perfiles_usuario TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario TO service_role;
REVOKE ALL ON TABLE public.perfiles_usuario_detalle FROM PUBLIC, anon, authenticated, service_role;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.perfiles_usuario_detalle TO service_role;
REVOKE ALL ON TABLE public.reportes_contenido FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.reportes_contenido TO anon;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.reportes_contenido TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.reportes_contenido TO service_role;
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
REVOKE ALL ON TABLE public.usuario FROM PUBLIC, anon, authenticated, service_role;
GRANT MAINTAIN, SELECT ON TABLE public.usuario TO anon;
GRANT MAINTAIN, SELECT ON TABLE public.usuario TO authenticated;
GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.usuario TO service_role;
REVOKE ALL ON FUNCTION public.admin_role_code_canonical(text) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.admin_role_code_canonical(text) TO PUBLIC, anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.establecer_actualizado_en() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.establecer_actualizado_en() TO PUBLIC, anon, authenticated, service_role;
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
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO postgres, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO postgres, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres, anon, authenticated, service_role;
COMMENT ON TABLE public.eventos_app IS 'Eventos ligeros recogidos en segundo plano para entender uso de la app sin molestar al usuario.';
