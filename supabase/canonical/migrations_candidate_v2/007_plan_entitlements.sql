-- OPOSICONTROL CANONICAL V1 V2 - PLAN CATALOGUE AND ENTITLEMENTS
-- Confirmed prices: FREE 0, RAFAGAS 299, RAZONADOS 699, INMERSIVO 999 EUR cents/month.
-- Hierarchy: FREE < RAFAGAS < RAZONADOS < INMERSIVO.
-- Prices live in productos_comerciales; entitlements live in beneficios_plan.
-- No demo, ticket or usage limit is invented here.

begin;

do $$
declare
  has_legacy_capability boolean;
  has_canonical_capability boolean;
begin
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

  if has_legacy_capability and not has_canonical_capability then
    execute 'alter table public.capacidades_especialidad rename column permite_guiados to permite_razonados';
  elsif has_canonical_capability and not has_legacy_capability then
    null;
  elsif has_legacy_capability and has_canonical_capability then
    raise exception 'CANONICAL_V2_CAPABILITY_COLUMN_CONFLICT';
  else
    raise exception 'CANONICAL_V2_CAPABILITY_COLUMN_MISSING';
  end if;
end;
$$;

do $$
begin
  if exists (
       select 1
       from information_schema.columns
       where table_schema = 'public'
         and table_name = 'capacidades_especialidad'
         and column_name = 'permite_guiados'
     )
     or not exists (
       select 1
       from information_schema.columns
       where table_schema = 'public'
         and table_name = 'capacidades_especialidad'
         and column_name = 'permite_razonados'
     ) then
    raise exception 'CANONICAL_V2_CAPABILITY_COLUMN_POSTCONDITION_FAILED';
  end if;
end;
$$;

do $$
declare
  v_missing text[];
begin
  select array_agg(required_name order by required_name)
    into v_missing
  from (
    values
      ('planes_suscripcion'),
      ('beneficios_plan'),
      ('productos_comerciales')
  ) as required(required_name)
  where to_regclass('public.' || required_name) is null;

  if v_missing is not null then
    raise exception 'CANONICAL_V2_PLAN_SCHEMA_MISSING: %', array_to_string(v_missing, ', ');
  end if;
end;
$$;

do $$
declare
  v_conflicts text;
begin
  select string_agg(codigo || ':nivel=' || nivel::text, ', ' order by codigo)
    into v_conflicts
  from public.planes_suscripcion
  where codigo in ('free', 'rafagas', 'razonados', 'inmersivo')
    and nivel <> case codigo
      when 'free' then 0
      when 'rafagas' then 1
      when 'razonados' then 2
      when 'inmersivo' then 3
    end;

  if v_conflicts is not null then
    raise exception 'CANONICAL_V2_PLAN_LEVEL_CONFLICT: %', v_conflicts;
  end if;

  if exists (select 1 from public.planes_suscripcion where nivel < 0 or nivel > 3) then
    raise exception 'CANONICAL_V2_PLAN_LEVEL_OUT_OF_RANGE';
  end if;
end;
$$;

alter table public.planes_suscripcion
  drop constraint if exists planes_suscripcion_canonical_v2_nivel_check,
  add constraint planes_suscripcion_canonical_v2_nivel_check
    check (nivel between 0 and 3);

insert into public.planes_suscripcion (codigo, nombre, descripcion, nivel, activo, visible, posicion)
values
  ('free', 'FREE', 'Acceso base sin suscripción.', 0, true, true, 0),
  ('rafagas', 'RAFAGAS', 'Acceso a contenido rafaga.', 1, true, true, 1),
  ('razonados', 'RAZONADOS', 'Acceso a contenido rafaga y razonado.', 2, true, true, 2),
  ('inmersivo', 'INMERSIVO', 'Acceso a contenido rafaga, razonado e inmersivo.', 3, true, true, 3)
on conflict (codigo) do nothing;

do $$
declare
  v_conflicts text;
begin
  select string_agg(codigo || ':nivel=' || nivel::text, ', ' order by codigo)
    into v_conflicts
  from public.planes_suscripcion
  where codigo in ('free', 'rafagas', 'razonados', 'inmersivo')
    and nivel <> case codigo
      when 'free' then 0
      when 'rafagas' then 1
      when 'razonados' then 2
      when 'inmersivo' then 3
    end;

  if v_conflicts is not null then
    raise exception 'CANONICAL_V2_PLAN_LEVEL_CONFLICT_AFTER_SEED: %', v_conflicts;
  end if;
end;
$$;

insert into public.productos_comerciales (
  codigo, tipo_producto, nombre, descripcion, precio_centimos, moneda,
  activo, visible, destacado, posicion, metadatos
)
values
  ('free', 'suscripcion', 'FREE', 'Plan base sin coste.', 0, 'EUR', true, true, false, 0, '{"plan_codigo":"free"}'::jsonb),
  ('rafagas', 'suscripcion', 'RAFAGAS', 'Suscripción mensual de rafagas.', 299, 'EUR', true, true, false, 1, '{"plan_codigo":"rafagas"}'::jsonb),
  ('razonados', 'suscripcion', 'RAZONADOS', 'Suscripción mensual de razonados.', 699, 'EUR', true, true, false, 2, '{"plan_codigo":"razonados"}'::jsonb),
  ('inmersivo', 'suscripcion', 'INMERSIVO', 'Suscripción mensual inmersiva.', 999, 'EUR', true, true, false, 3, '{"plan_codigo":"inmersivo"}'::jsonb)
on conflict (codigo) do nothing;

do $$
declare
  v_conflicts text;
begin
  select string_agg(codigo || ':precio=' || coalesce(precio_centimos::text, 'NULL'), ', ' order by codigo)
    into v_conflicts
  from public.productos_comerciales
  where codigo in ('free', 'rafagas', 'razonados', 'inmersivo')
    and (
      tipo_producto <> 'suscripcion'
      or moneda <> 'EUR'
      or precio_centimos is distinct from case codigo
        when 'free' then 0
        when 'rafagas' then 299
        when 'razonados' then 699
        when 'inmersivo' then 999
      end
    );

  if v_conflicts is not null then
    raise exception 'CANONICAL_V2_PRODUCT_CONFLICT: %', v_conflicts;
  end if;
end;
$$;

insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_boolean)
select ps.id, benefits.codigo_beneficio, benefits.valor_boolean
from public.planes_suscripcion ps
join (
  values
    ('free', 'acceso_rafaga', false),
    ('free', 'acceso_razonado', false),
    ('free', 'acceso_inmersivo', false),
    ('rafagas', 'acceso_rafaga', true),
    ('rafagas', 'acceso_razonado', false),
    ('rafagas', 'acceso_inmersivo', false),
    ('razonados', 'acceso_rafaga', true),
    ('razonados', 'acceso_razonado', true),
    ('razonados', 'acceso_inmersivo', false),
    ('inmersivo', 'acceso_rafaga', true),
    ('inmersivo', 'acceso_razonado', true),
    ('inmersivo', 'acceso_inmersivo', true)
) as benefits(plan_codigo, codigo_beneficio, valor_boolean)
  on benefits.plan_codigo = ps.codigo
on conflict (plan_id, codigo_beneficio) do nothing;

do $$
begin
  if exists (
    select 1
    from public.beneficios_plan bp
    join public.planes_suscripcion ps on ps.id = bp.plan_id
    where ps.codigo in ('free', 'rafagas', 'razonados', 'inmersivo')
      and bp.codigo_beneficio in ('acceso_rafaga', 'acceso_razonado', 'acceso_inmersivo')
      and bp.valor_boolean is distinct from case
        when ps.codigo = 'free' then false
        when ps.codigo = 'rafagas' then bp.codigo_beneficio = 'acceso_rafaga'
        when ps.codigo = 'razonados' then bp.codigo_beneficio in ('acceso_rafaga', 'acceso_razonado')
        when ps.codigo = 'inmersivo' then bp.codigo_beneficio in ('acceso_rafaga', 'acceso_razonado', 'acceso_inmersivo')
      end
  ) then
    raise exception 'CANONICAL_V2_BENEFIT_CONFLICT';
  end if;
end;
$$;

-- requiere_plan_codigo remains available for content-level metadata, but the
-- plan benefits above are the source of truth for subscription entitlement.
-- tipo_acceso is deliberately not joined to a fixed plan or price.

commit;
