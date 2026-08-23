-- OPOSICONTROL CANONICAL V1 V2 — PREFLIGHT ONLY
-- Candidate-only. No live execution is authorized by this file.
-- V2 reuses the live UUID authorization source: es_admin() and
-- es_moderador_o_admin(). It deliberately does not create an is_* family.

begin;

do $$
declare
  relation_name text;
  expected_relations text[] := array[
    'auth.users',
    'public.perfiles_usuario',
    'public.roles_usuario',
    'public.roles_app',
    'public.usuario',
    'public.examenes',
    'public.preguntas',
    'public.intentos_examen',
    'public.lotes_importacion_preguntas',
    'public.creditos_contenido_usuario',
    'public.reglas_popup_comercial',
    'public.preguntas_reto_diario',
    'public.capacidades_especialidad',
    'public.opositeca_items',
    'public.etiquetas_opositeca'
  ];
  rls_targets text[] := array[
    'atribucion_usuario', 'beneficios_plan', 'capacidades_especialidad',
    'comunidades_objetivo_usuario', 'creditos_contenido_usuario',
    'efectos_servicio', 'estado_competitivo_usuario', 'estado_opositester_usuario',
    'ligas_nivel', 'perfiles_usuario_detalle', 'planes_suscripcion',
    'popup_usuario_historial', 'preguntas_reto_diario', 'productos_comerciales',
    'productos_store', 'promociones', 'reglas_popup_comercial',
    'resumen_intento_etiqueta', 'usos_promocion_usuario', 'valor_usuario_resumen'
  ];
  admin_definition text;
  moderator_definition text;
  has_legacy_capability boolean;
  has_canonical_capability boolean;
begin
  foreach relation_name in array expected_relations loop
    if to_regclass(relation_name) is null then
      raise exception 'CANONICAL_V2_PREFLIGHT_MISSING_RELATION: %', relation_name;
    end if;
  end loop;

  if (select count(*) from public.usuario) <> 0 then
    raise exception 'CANONICAL_V2_PREFLIGHT_PUBLIC_USUARIO_NOT_EMPTY';
  end if;

  if exists (select 1 from public.examenes where tipo_examen = 'guiado') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADO_DATA: public.examenes';
  end if;
  if exists (select 1 from public.preguntas where tipo_pregunta = 'guiada') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADA_DATA: public.preguntas';
  end if;
  if exists (select 1 from public.intentos_examen where tipo_examen_snapshot = 'guiado') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADO_DATA: public.intentos_examen';
  end if;
  if exists (select 1 from public.lotes_importacion_preguntas where tipo_examen = 'guiado') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADO_DATA: public.lotes_importacion_preguntas';
  end if;
  if exists (select 1 from public.creditos_contenido_usuario where tipo_examen = 'guiado') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADO_DATA: public.creditos_contenido_usuario';
  end if;
  if exists (select 1 from public.reglas_popup_comercial where tipo_examen_objetivo = 'guiado') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADO_DATA: public.reglas_popup_comercial';
  end if;
  if exists (select 1 from public.preguntas_reto_diario where tipo_slot = 'guiada') then
    raise exception 'CANONICAL_V2_PREFLIGHT_GUIADA_DATA: public.preguntas_reto_diario';
  end if;

  select exists (
           select 1
           from information_schema.columns
           where table_schema = 'public'
             and table_name = 'capacidades_especialidad'
             and column_name = 'permite_guiados'
         ),
         exists (
           select 1
           from information_schema.columns
           where table_schema = 'public'
             and table_name = 'capacidades_especialidad'
             and column_name = 'permite_razonados'
         )
    into has_legacy_capability, has_canonical_capability;

  if has_legacy_capability and has_canonical_capability then
    raise exception 'CANONICAL_V2_PREFLIGHT_CAPABILITY_COLUMN_CONFLICT';
  end if;
  if not has_legacy_capability and not has_canonical_capability then
    raise exception 'CANONICAL_V2_PREFLIGHT_CAPABILITY_COLUMN_MISSING';
  end if;

  if (
    select count(distinct codigo)
    from public.roles_app
    where codigo in ('superadmin', 'admin', 'moderador', 'usuario')
  ) <> 4
  or exists (
    select 1
    from public.roles_app
    where codigo in ('superadmin', 'admin', 'moderador', 'usuario')
    group by codigo
    having count(*) <> 1
  ) then
    raise exception 'CANONICAL_V2_PREFLIGHT_ROLE_CATALOG_INCOMPLETE';
  end if;

  if to_regprocedure('public.es_admin()') is null
     or to_regprocedure('public.es_moderador_o_admin()') is null then
    raise exception 'CANONICAL_V2_PREFLIGHT_CANONICAL_AUTH_FUNCTION_MISSING';
  end if;

  if to_regprocedure('public.can_access_conversation(integer)') is null
     or to_regprocedure('public.can_access_conversation(uuid)') is null
     or to_regprocedure('public.can_access_exam(integer)') is null
     or to_regprocedure('public.can_access_question(integer)') is null
     or to_regprocedure('public.has_role(text)') is null
     or to_regprocedure('public.has_any_role(text[])') is null
     or to_regprocedure('public.current_usuario_id()') is null
     or to_regprocedure('public.current_especialidad_id()') is null
     or to_regprocedure('public.ensure_wallet_for_usuario()') is null
     or to_regprocedure('public.fn_audit_row_change()') is null
     or to_regprocedure('public.rpc_grant_exam_access(integer,integer,character varying,boolean)') is null
     or to_regprocedure('public.rpc_purchase_articulo(integer,integer)') is null
     or to_regprocedure('public.oposibots_upsert_own_profile(text,text,text,text)') is null then
    raise exception 'CANONICAL_V2_PREFLIGHT_EXPECTED_COMPATIBILITY_FUNCTION_MISSING';
  end if;

  admin_definition := pg_get_functiondef(to_regprocedure('public.es_admin()'));
  moderator_definition := pg_get_functiondef(to_regprocedure('public.es_moderador_o_admin()'));
  if admin_definition not ilike '%roles_usuario%'
     or admin_definition not ilike '%roles_app%'
     or admin_definition not ilike '%auth.uid()%'
     or admin_definition not ilike '%superadmin%' then
    raise exception 'CANONICAL_V2_PREFLIGHT_ES_ADMIN_NOT_UUID_CANONICAL';
  end if;
  if moderator_definition not ilike '%roles_usuario%'
     or moderator_definition not ilike '%roles_app%'
     or moderator_definition not ilike '%auth.uid()%'
     or moderator_definition not ilike '%moderador%' then
    raise exception 'CANONICAL_V2_PREFLIGHT_ES_MODERATOR_NOT_UUID_CANONICAL';
  end if;

  if exists (
    select 1 from pg_proc
    where pronamespace = 'public'::regnamespace
      and proname in ('is_admin', 'is_superadmin', 'is_moderator_or_admin')
  ) then
    raise exception 'CANONICAL_V2_PREFLIGHT_DUPLICATE_AUTHORIZATION_FAMILY';
  end if;

  if exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = any(rls_targets)
      and policyname not like 'canonical_v2_%'
  ) then
    raise exception 'CANONICAL_V2_PREFLIGHT_UNREVIEWED_RLS_POLICY_ON_TARGET_TABLE';
  end if;

  raise notice 'CANONICAL_V2_PREFLIGHT_OK: UUID authorization reused, capability column is in a valid pre/post-rename state, legacy adapter retained, no direct business writes approved';
end;
$$;

commit;
