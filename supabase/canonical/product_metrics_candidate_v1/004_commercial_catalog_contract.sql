-- PRODUCT METRICS CANDIDATE V1 — COMMERCIAL PRODUCT / TARIFF CONTRACT
-- productos_comerciales is stable product identity; tariffs are child rows.
begin;

alter table if exists public.productos_comerciales
  add column if not exists plan_id uuid;

do $$
begin
  if not exists (select 1 from pg_constraint where conrelid = 'public.productos_comerciales'::regclass and conname = 'productos_comerciales_precio_no_negativo_check') then
    alter table public.productos_comerciales add constraint productos_comerciales_precio_no_negativo_check
      check (precio_centimos is null or precio_centimos >= 0);
  end if;
  if not exists (select 1 from pg_constraint where conrelid = 'public.productos_comerciales'::regclass and conname = 'productos_comerciales_plan_fk') then
    alter table public.productos_comerciales add constraint productos_comerciales_plan_fk
      foreign key (plan_id) references public.planes_suscripcion(id);
  end if;
end $$;

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

comment on table public.tarifas_producto_comercial is
  'Canonical tariff rows; provider identities remain in productos_store.';

alter table public.tarifas_producto_comercial enable row level security;
revoke all on table public.tarifas_producto_comercial from public, anon, authenticated;
grant all on table public.tarifas_producto_comercial to service_role;

commit;
