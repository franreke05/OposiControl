-- PRODUCT METRICS CANDIDATE V1 — PREFLIGHT ONLY
-- Read-only assertions. This file must not change schema or data.
begin;

do $$
declare
  required_table text;
begin
  foreach required_table in array array[
    'eventos_app', 'intentos_examen', 'respuestas_intento',
    'estadisticas_globales_pregunta', 'estadisticas_usuario_etiqueta',
    'sesiones_estudio', 'carteras_usuario', 'transacciones_cartera',
    'compras_usuario', 'suscripciones', 'productos_comerciales',
    'productos_store', 'planes_suscripcion', 'beneficios_plan',
    'creditos_contenido_usuario', 'usuario'
  ] loop
    if to_regclass(format('public.%s', required_table)) is null then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_MISSING_TABLE: %', required_table;
    end if;
  end loop;
end $$;

-- These are the exact columns consumed by 002, 003, 004, 005 and 006.
do $$
declare
  required record;
  actual_udt text;
  actual_nullable text;
begin
  for required in
    select * from (values
      ('eventos_app', 'id', 'uuid', 'NO'),
      ('eventos_app', 'usuario_id', 'uuid', 'YES'),
      ('eventos_app', 'sesion_id', 'uuid', 'YES'),
      ('eventos_app', 'nombre_evento', 'text', 'NO'),
      ('eventos_app', 'pantalla', 'text', 'YES'),
      ('eventos_app', 'entidad_tipo', 'text', 'YES'),
      ('eventos_app', 'entidad_id', 'uuid', 'YES'),
      ('eventos_app', 'plataforma', 'text', 'YES'),
      ('eventos_app', 'version_app', 'text', 'YES'),
      ('eventos_app', 'idioma', 'text', 'YES'),
      ('eventos_app', 'duracion_ms', 'int4', 'YES'),
      ('eventos_app', 'propiedades', 'jsonb', 'NO'),
      ('eventos_app', 'creado_en', 'timestamptz', 'NO'),
      ('intentos_examen', 'id', 'uuid', 'NO'),
      ('intentos_examen', 'usuario_id', 'uuid', 'NO'),
      ('intentos_examen', 'examen_id', 'uuid', 'NO'),
      ('intentos_examen', 'estado', 'text', 'NO'),
      ('intentos_examen', 'cuenta_para_ranking', 'bool', 'NO'),
      ('respuestas_intento', 'id', 'uuid', 'NO'),
      ('respuestas_intento', 'intento_id', 'uuid', 'NO'),
      ('respuestas_intento', 'pregunta_id', 'uuid', 'NO'),
      ('respuestas_intento', 'usuario_id', 'uuid', 'YES'),
      ('respuestas_intento', 'cuenta_para_ranking', 'bool', 'NO'),
      ('respuestas_intento', 'es_primer_intento_pregunta', 'bool', 'NO'),
      ('respuestas_intento', 'opcion_seleccionada_id', 'uuid', 'YES'),
      ('respuestas_intento', 'opcion_correcta_id', 'uuid', 'YES'),
      ('respuestas_intento', 'es_correcta', 'bool', 'YES'),
      ('respuestas_intento', 'examen_id', 'uuid', 'YES'),
      ('respuestas_intento', 'respondida_en', 'timestamptz', 'YES'),
      ('transacciones_cartera', 'saldo_anterior', 'int4', 'YES'),
      ('transacciones_cartera', 'saldo_resultante', 'int4', 'NO'),
      ('productos_comerciales', 'id', 'uuid', 'NO'),
      ('productos_comerciales', 'codigo', 'text', 'NO'),
      ('productos_comerciales', 'tipo_producto', 'text', 'NO'),
      ('productos_comerciales', 'precio_centimos', 'int4', 'YES'),
      ('productos_comerciales', 'moneda', 'text', 'NO'),
      ('productos_comerciales', 'activo', 'bool', 'NO'),
      ('productos_comerciales', 'visible', 'bool', 'NO'),
      ('productos_comerciales', 'metadatos', 'jsonb', 'NO'),
      ('productos_store', 'producto_id', 'uuid', 'NO'),
      ('productos_store', 'proveedor', 'text', 'NO'),
      ('productos_store', 'product_id_externo', 'text', 'NO'),
      ('productos_store', 'tipo_store', 'text', 'NO'),
      ('productos_store', 'activo', 'bool', 'NO'),
      ('productos_store', 'metadatos', 'jsonb', 'NO'),
      ('beneficios_plan', 'plan_id', 'uuid', 'NO'),
      ('beneficios_plan', 'codigo_beneficio', 'text', 'NO'),
      ('beneficios_plan', 'valor_integer', 'int4', 'YES')
    ) as columns(table_name, column_name, expected_udt, expected_nullable)
  loop
    select c.udt_name, c.is_nullable
      into actual_udt, actual_nullable
    from information_schema.columns c
    where c.table_schema = 'public'
      and c.table_name = required.table_name
      and c.column_name = required.column_name;

    if actual_udt is null then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_MISSING_COLUMN: public.%.%', required.table_name, required.column_name;
    end if;
    if actual_udt <> required.expected_udt then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_WRONG_TYPE: public.%.% expected %, found %',
        required.table_name, required.column_name, required.expected_udt, actual_udt;
    end if;
    if actual_nullable <> required.expected_nullable then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_WRONG_NULLABILITY: public.%.% expected %, found %',
        required.table_name, required.column_name, required.expected_nullable, actual_nullable;
    end if;
  end loop;
end $$;

-- A first response is unique by user/question, regardless of ranking eligibility.
do $$
begin
  if exists (
    select 1 from public.respuestas_intento
    where es_primer_intento_pregunta is true
    group by usuario_id, pregunta_id having count(*) > 1
  ) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_DUPLICATE_FIRST_RESPONSES: user/question has more than one first response';
  end if;
  if exists (
    select 1 from public.respuestas_intento
    where es_primer_intento_pregunta is true and usuario_id is null
  ) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_NULL_FIRST_RESPONSE_USER: first response cannot have null usuario_id';
  end if;
  if exists (select 1 from public.respuestas_intento where pregunta_id is null) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_NULL_RESPONSE_QUESTION: pregunta_id cannot be null';
  end if;
  if exists (
    select 1 from public.respuestas_intento
    where cuenta_para_ranking is true and es_primer_intento_pregunta is not true
  ) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_RANKING_REQUIRES_FIRST_RESPONSE: ranking=true with first=false';
  end if;
end $$;

-- The raw-event table must remain the single source of raw events.
do $$
declare
  r record;
begin
  if exists (
    select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind in ('r', 'p')
      and c.relname in ('eventos_raw', 'eventos_analytics', 'metricas_evento')
  ) then raise exception 'PRODUCT_METRICS_PREFLIGHT_SECOND_RAW_EVENT_TABLE'; end if;
  for r in select unnest(array['eventos_app_insert_auth', 'intentos_examen_todo_propio', 'respuestas_intento_todo_propio']) as policy_name loop
    if exists (select 1 from pg_policies where schemaname = 'public' and policyname = r.policy_name) then
      raise notice 'PRODUCT_METRICS_PREFLIGHT_DIRECT_WRITE_POLICY_PRESENT: % (005 must remove or replace it)', r.policy_name;
    end if;
  end loop;
end $$;

-- Detect a partially applied event contract before 002 is allowed to run.
do $$
declare
  candidate_count integer;
begin
  if exists (select 1 from public.eventos_app) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_EVENT_ROWS_BLOCK_MANDATORY_IDEMPOTENCY: existing events require an explicit backfill decision';
  end if;
  select count(*) into candidate_count
  from information_schema.columns
  where table_schema = 'public' and table_name = 'eventos_app'
    and column_name in ('event_version', 'occurred_en', 'received_en', 'especialidad_id', 'intento_id', 'pregunta_id', 'idempotency_key');
  if candidate_count not in (0, 7) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_PARTIAL_EVENT_CONTRACT: expected 0 or 7 candidate columns, found %', candidate_count;
  end if;
  if candidate_count = 7 and exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'eventos_app'
      and ((column_name = 'event_version' and (udt_name <> 'int4' or is_nullable <> 'NO'))
        or (column_name in ('occurred_en', 'received_en') and (udt_name <> 'timestamptz' or is_nullable <> 'NO'))
        or (column_name in ('especialidad_id', 'intento_id', 'pregunta_id') and udt_name <> 'uuid')
        or (column_name = 'idempotency_key' and (udt_name <> 'text' or is_nullable <> 'NO')))
  ) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_EVENT_CONTRACT_SHAPE_MISMATCH';
  end if;
end $$;

-- Capture the current policy/grant boundary before 005 changes it.
do $$
declare table_name text;
begin
  foreach table_name in array array['eventos_app', 'intentos_examen', 'respuestas_intento', 'carteras_usuario', 'transacciones_cartera', 'compras_usuario', 'suscripciones'] loop
    if not exists (
      select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relname = table_name and c.relrowsecurity
    ) then raise exception 'PRODUCT_METRICS_PREFLIGHT_RLS_DISABLED: public.%', table_name; end if;
  end loop;
  if not exists (select 1 from pg_roles where rolname = 'authenticated')
     or not exists (select 1 from pg_roles where rolname = 'anon')
     or not exists (select 1 from pg_roles where rolname = 'service_role') then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_REQUIRED_SUPABASE_ROLES_MISSING';
  end if;
  raise notice 'PRODUCT_METRICS_PREFLIGHT_GRANTS eventos_app authenticated_insert=% authenticated_select=%',
    has_table_privilege('authenticated', 'public.eventos_app', 'INSERT'), has_table_privilege('authenticated', 'public.eventos_app', 'SELECT');
  raise notice 'PRODUCT_METRICS_PREFLIGHT_GRANTS respuestas_intento authenticated_insert=% authenticated_select=%',
    has_table_privilege('authenticated', 'public.respuestas_intento', 'INSERT'), has_table_privilege('authenticated', 'public.respuestas_intento', 'SELECT');
end $$;

-- Commercial and provider mapping compatibility checks.
do $$
begin
  if not exists (select 1 from pg_indexes where schemaname = 'public' and tablename = 'productos_comerciales' and indexdef ilike '%unique%' and indexdef ilike '%(codigo)%') then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_COMMERCIAL_CODE_NOT_UNIQUE';
  end if;
  if not exists (select 1 from pg_indexes where schemaname = 'public' and tablename = 'productos_store' and indexdef ilike '%proveedor%' and indexdef ilike '%product_id_externo%') then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_PROVIDER_MAPPING_UNIQUE_KEY_MISSING';
  end if;
  if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'productos_comerciales' and column_name in ('proveedor_product_id', 'proveedor_price_id')) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_PROVIDER_ID_DUPLICATION_IN_COMMERCIAL_CATALOG';
  end if;
  if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'productos_comerciales' and column_name in ('periodo_facturacion', 'fase_precio')) then
    raise exception 'PRODUCT_METRICS_PREFLIGHT_LEGACY_TARIFF_COLUMNS_IN_PRODUCT_CATALOG';
  end if;
end $$;

-- If the normalized tariff table already exists, it must be complete; absence is
-- the expected pre-004 state.
do $$
declare
  required_count integer;
begin
  if to_regclass('public.tarifas_producto_comercial') is not null then
    select count(*) into required_count
    from information_schema.columns
    where table_schema = 'public' and table_name = 'tarifas_producto_comercial'
      and column_name in ('id', 'producto_id', 'periodo_facturacion', 'fase_precio', 'precio_centimos', 'moneda', 'activo', 'visible', 'posicion', 'metadatos', 'creado_en', 'actualizado_en');
    if required_count <> 12 then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_PARTIAL_TARIFF_TABLE: expected 12 columns, found %', required_count;
    end if;
    if exists (
      select 1 from information_schema.columns
      where table_schema = 'public' and table_name = 'tarifas_producto_comercial'
        and ((column_name in ('id', 'producto_id') and (udt_name <> 'uuid' or is_nullable <> 'NO'))
          or (column_name in ('periodo_facturacion', 'fase_precio', 'moneda') and (udt_name <> 'text' or is_nullable <> 'NO'))
          or (column_name = 'precio_centimos' and (udt_name <> 'int4' or is_nullable <> 'NO'))
          or (column_name in ('activo', 'visible') and (udt_name <> 'bool' or is_nullable <> 'NO'))
          or (column_name = 'posicion' and (udt_name <> 'int4' or is_nullable <> 'NO'))
          or (column_name = 'metadatos' and (udt_name <> 'jsonb' or is_nullable <> 'NO'))
          or (column_name in ('creado_en', 'actualizado_en') and (udt_name <> 'timestamptz' or is_nullable <> 'NO')))
    ) then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_TARIFF_TABLE_SHAPE_MISMATCH';
    end if;
    if not exists (
      select 1 from pg_indexes
      where schemaname = 'public' and tablename = 'tarifas_producto_comercial'
        and indexdef ilike '%unique%'
        and indexdef ilike '%producto_id%'
        and indexdef ilike '%periodo_facturacion%'
        and indexdef ilike '%fase_precio%'
    ) then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_TARIFF_UNIQUENESS_MISSING';
    end if;
    if not exists (
      select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relname = 'tarifas_producto_comercial' and c.relrowsecurity
    ) then
      raise exception 'PRODUCT_METRICS_PREFLIGHT_TARIFF_RLS_DISABLED';
    end if;
  end if;
end $$;

rollback;
