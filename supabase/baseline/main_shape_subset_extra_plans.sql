-- MAIN objects of the subscription/plan area that the generated bootstrap subset does not carry (shape from the structure-only reads of MAIN, 2026-10-16).
-- Loaded by the PG17 gate after main_shape_subset_extra.sql, and by the local stack (up.mjs, converge.mjs). IDEMPOTENT and safe on a live local DB.
-- NOT a migration: MAIN already has these objects; V1 migrations build on them.
create table if not exists public.beneficios_plan (
  id uuid primary key default gen_random_uuid(),
  plan_id uuid not null references public.planes_suscripcion(id) on delete cascade,
  codigo_beneficio text not null,
  valor_boolean boolean,
  valor_integer integer,
  valor_numeric numeric,
  valor_text text,
  metadatos jsonb not null default '{}'::jsonb,
  creado_en timestamptz not null default now(),
  constraint beneficios_plan_plan_id_codigo_beneficio_key unique (plan_id, codigo_beneficio)
);
alter table public.beneficios_plan enable row level security;
do $$ begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'beneficios_plan' and policyname = 'canonical_v2_benefits_public_read') then
    create policy canonical_v2_benefits_public_read on public.beneficios_plan for select to anon, authenticated using (true);
  end if;
end $$;
grant select on public.beneficios_plan to anon, authenticated;
grant all on public.beneficios_plan to service_role;

create table if not exists public.suscripciones (
  id uuid primary key default gen_random_uuid(),
  usuario_id uuid not null references auth.users(id) on delete cascade,
  proveedor text not null,
  producto_codigo text not null,
  estado text not null,
  inicio_en timestamptz not null default now(),
  fin_en timestamptz,
  renovacion_en timestamptz,
  identificador_externo text,
  payload_validacion jsonb not null default '{}'::jsonb,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now()
);
alter table public.suscripciones enable row level security;
do $$ begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'suscripciones' and policyname = 'suscripciones_select_propio') then
    create policy suscripciones_select_propio on public.suscripciones for select to authenticated using (usuario_id = (select auth.uid()));
  end if;
end $$;
grant select on public.suscripciones to authenticated;
grant all on public.suscripciones to service_role;

-- The MAIN projection function and its trigger as MAIN has them TODAY (only activa / periodo_prueba keep access; search_path public). Created only when absent, so a live DB that
-- already holds the V1 replacement (20261022100900) keeps it. The V1 migration replaces the body; its ROLLBACK block restores this one.
do $plans$
begin
  if to_regprocedure('public.sincronizar_estado_premium_usuario()') is null then
    execute $fn$
create or replace function public.sincronizar_estado_premium_usuario()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $body$
declare
  v_usuario_id uuid;
  v_suscripcion record;
begin
  v_usuario_id := coalesce(new.usuario_id, old.usuario_id);
  select s.usuario_id, s.estado, s.producto_codigo, s.proveedor, s.inicio_en, s.fin_en, s.renovacion_en
    into v_suscripcion
    from public.suscripciones s
   where s.usuario_id = v_usuario_id and s.estado in ('activa', 'periodo_prueba') and (s.fin_en is null or s.fin_en > now())
   order by case when s.estado = 'activa' then 1 when s.estado = 'periodo_prueba' then 2 else 3 end, s.renovacion_en desc nulls last, s.fin_en desc nulls last
   limit 1;
  if v_suscripcion.usuario_id is not null then
    insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en, actualizado_en)
    values (v_usuario_id, true, v_suscripcion.estado, v_suscripcion.producto_codigo, v_suscripcion.proveedor, v_suscripcion.inicio_en, v_suscripcion.fin_en, v_suscripcion.renovacion_en, now())
    on conflict (usuario_id) do update set es_premium = excluded.es_premium, estado_suscripcion = excluded.estado_suscripcion, producto_codigo = excluded.producto_codigo,
      proveedor = excluded.proveedor, inicio_en = excluded.inicio_en, fin_en = excluded.fin_en, renovacion_en = excluded.renovacion_en, actualizado_en = now();
  else
    insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en, actualizado_en)
    values (v_usuario_id, false, 'sin_suscripcion', null, null, null, null, null, now())
    on conflict (usuario_id) do update set es_premium = false, estado_suscripcion = 'sin_suscripcion', producto_codigo = null, proveedor = null,
      inicio_en = null, fin_en = null, renovacion_en = null, actualizado_en = now();
  end if;
  return coalesce(new, old);
end;
$body$
$fn$;
    revoke all on function public.sincronizar_estado_premium_usuario() from public, anon, authenticated;
    grant execute on function public.sincronizar_estado_premium_usuario() to service_role;
  end if;
  if not exists (select 1 from pg_trigger t where t.tgrelid = 'public.suscripciones'::regclass and not t.tgisinternal) then
    create trigger trg_suscripciones_sync_estado_premium after insert or update or delete on public.suscripciones
      for each row execute function public.sincronizar_estado_premium_usuario();
  end if;
end
$plans$;

-- MAIN public.tarifas_producto_comercial (tariffs per product, period and price phase; the plan catalog read serves the 'standard' phase only). Structure-only shape.
create table if not exists public.tarifas_producto_comercial (
  id uuid primary key default gen_random_uuid(),
  producto_id uuid not null references public.productos_comerciales(id) on delete restrict,
  periodo_facturacion text not null check (periodo_facturacion in ('mensual', 'anual', 'unitario')),
  fase_precio text not null check (fase_precio in ('first_year', 'renewal', 'standard')),
  precio_centimos integer not null check (precio_centimos >= 0),
  moneda text not null default 'EUR',
  activo boolean not null default true,
  visible boolean not null default true,
  posicion integer not null default 1,
  metadatos jsonb not null default '{}'::jsonb,
  creado_en timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  unique (producto_id, periodo_facturacion, fase_precio)
);
alter table public.tarifas_producto_comercial enable row level security;
revoke all on table public.tarifas_producto_comercial from public, anon, authenticated;
grant all on table public.tarifas_producto_comercial to service_role;
