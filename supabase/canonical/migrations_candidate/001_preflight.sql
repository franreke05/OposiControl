-- OPOSICONTROL CANONICAL V1 — PREFLIGHT ONLY
-- Candidate package. Do not apply to production without the human gate.
-- This file is intentionally read-only apart from transaction-local assertions.

begin;

do $$
declare
  relation_name text;
  legacy_function_name text;
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
  legacy_relations text[] := array[
    'public.user_role',
    'public.app_role',
    'public.examen',
    'public.pregunta',
    'public.cartera_tickets'
  ];
  legacy_functions text[] := array[
    'current_usuario_id()',
    'current_especialidad_id()',
    'has_role(text)',
    'has_any_role(text[])'
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
begin
  foreach relation_name in array expected_relations loop
    if to_regclass(relation_name) is null then
      raise exception 'CANONICAL_PREFLIGHT_MISSING_RELATION: %', relation_name;
    end if;
  end loop;

  if (select count(*) from public.usuario) <> 0 then
    raise exception 'CANONICAL_PREFLIGHT_PUBLIC_USUARIO_NOT_EMPTY';
  end if;

  if exists (select 1 from public.examenes where tipo_examen = 'guiado') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADO_DATA: public.examenes';
  end if;
  if exists (select 1 from public.preguntas where tipo_pregunta = 'guiada') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADA_DATA: public.preguntas';
  end if;
  if exists (select 1 from public.intentos_examen where tipo_examen_snapshot = 'guiado') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADO_DATA: public.intentos_examen';
  end if;
  if exists (select 1 from public.lotes_importacion_preguntas where tipo_examen = 'guiado') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADO_DATA: public.lotes_importacion_preguntas';
  end if;
  if exists (select 1 from public.creditos_contenido_usuario where tipo_examen = 'guiado') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADO_DATA: public.creditos_contenido_usuario';
  end if;
  if exists (select 1 from public.reglas_popup_comercial where tipo_examen_objetivo = 'guiado') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADO_DATA: public.reglas_popup_comercial';
  end if;
  if exists (select 1 from public.preguntas_reto_diario where tipo_slot = 'guiada') then
    raise exception 'CANONICAL_PREFLIGHT_GUIADA_DATA: public.preguntas_reto_diario';
  end if;

  foreach relation_name in array legacy_relations loop
    if to_regclass(relation_name) is not null then
      raise exception 'CANONICAL_PREFLIGHT_UNEXPECTED_LEGACY_RELATION: %', relation_name;
    end if;
  end loop;

  foreach legacy_function_name in array legacy_functions loop
    if to_regprocedure('public.' || legacy_function_name) is null then
      raise exception 'CANONICAL_PREFLIGHT_EXPECTED_LEGACY_FUNCTION_MISSING: %', legacy_function_name;
    end if;
  end loop;

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
    raise exception 'CANONICAL_PREFLIGHT_ROLE_CATALOG_INCOMPLETE';
  end if;

  if exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = any(rls_targets)
      and policyname not like 'canonical_%'
  ) then
    raise exception 'CANONICAL_PREFLIGHT_UNREVIEWED_RLS_POLICY_ON_TARGET_TABLE';
  end if;

  raise notice 'CANONICAL_PREFLIGHT_OK: zero legacy rows, UUID identity tables present, legacy cleanup boundary detected';
end;
$$;

commit;
