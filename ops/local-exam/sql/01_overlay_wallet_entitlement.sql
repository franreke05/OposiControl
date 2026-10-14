-- E5-B01 / PHASE B (LOCAL ONLY). Tablas de cartera y derechos que D6-01 (/api/v1/app/wallet) y D7-02
-- (/api/v1/app/me/entitlement) leen y que quedan FUERA de supabase/baseline/main_shape_subset.sql.
-- NO es una migracion: nunca se aplica fuera del stack local. Idempotente y aditivo (create ... if not exists,
-- sin drop, sin alter de tablas existentes): se puede aplicar sobre un stack vivo sin reset.
--
-- Procedencia de la forma (sin lectura nueva de MAIN):
--   carteras_usuario, transacciones_cartera  columnas exactas de la captura de catalogo de MAIN (solo esquema) usada para el baseline.
--   activaciones_servicio_usuario  prefijo capturado (id, usuario_id, servicio_id, compra_id, estado default 'activa') +
--                                  columnas que lee Ktor (WalletGateway.activations) + indices de 20260803101711.
--   creditos_contenido_usuario     supabase/canonical/dry_run_fixture/001_live_shape.sql + columnas que lee Ktor
--                                  (CatalogGateway.activeCredits) + suscripcion_id (indice de 20260803101711).
--   planes_suscripcion, productos_comerciales  001_live_shape.sql + plan_id (product_metrics_candidate_v1/004).
--   servicios                      solo lo que el embed servicios(codigo) necesita.
-- Paridad columna a columna con MAIN: PENDIENTE de una captura de catalogo autorizada (ver handoff E1-B01).

create table if not exists public.carteras_usuario (
  usuario_id uuid not null primary key references auth.users(id) on delete cascade,
  saldo_tickets integer not null default 0,
  actualizado_en timestamp with time zone not null default now(),
  constraint carteras_usuario_saldo_tickets_check check (saldo_tickets >= 0)
);

-- Columnas exactas de la captura de catalogo de MAIN; CHECKs tal como estaban en el stack local previo.
create table if not exists public.transacciones_cartera (
  id uuid not null default gen_random_uuid() primary key,
  usuario_id uuid not null references auth.users(id) on delete cascade,
  compra_id uuid,
  tipo text not null,
  cantidad integer not null,
  saldo_resultante integer not null,
  descripcion text,
  creado_en timestamp with time zone not null default now(),
  saldo_anterior integer,
  referencia_tipo text,
  referencia_id uuid,
  idempotency_key text,
  metadatos jsonb not null default '{}'::jsonb,
  constraint transacciones_cartera_saldo_resultante_check check (saldo_resultante >= 0),
  constraint transacciones_cartera_tipo_check check (tipo = any (array['recarga', 'pack_tickets', 'gasto', 'compra_examen',
    'compra_servicio', 'ajuste', 'devolucion', 'regalo', 'reto_diario', 'bonus_racha', 'referido', 'reporte_validado',
    'potenciador', 'protector']))
);
create index if not exists idx_transacciones_cartera_usuario_creado on public.transacciones_cartera (usuario_id, creado_en desc, id desc);

create table if not exists public.servicios (
  id uuid not null default gen_random_uuid() primary key,
  codigo text not null unique,
  nombre text not null,
  activo boolean not null default true,
  creado_en timestamp with time zone not null default now()
);

create table if not exists public.activaciones_servicio_usuario (
  id uuid not null default gen_random_uuid() primary key,
  usuario_id uuid not null references auth.users(id) on delete cascade,
  servicio_id uuid not null references public.servicios(id),
  compra_id uuid,
  estado text not null default 'activa',
  activa_desde timestamp with time zone not null default now(),
  activa_hasta timestamp with time zone,
  usos_disponibles integer not null default 1,
  usos_consumidos integer not null default 0,
  usado_en timestamp with time zone,
  usado_en_intento_id uuid,
  usado_en_reto_id uuid,
  creado_en timestamp with time zone not null default now(),
  constraint activaciones_servicio_usuario_estado_check check (estado = any (array['activa', 'usada', 'caducada', 'cancelada']))
);
create index if not exists idx_activaciones_servicio_usuario_usuario_id on public.activaciones_servicio_usuario (usuario_id);
create index if not exists idx_activaciones_servicio_usuario_servicio_id on public.activaciones_servicio_usuario (servicio_id);

create table if not exists public.creditos_contenido_usuario (
  id uuid not null default gen_random_uuid() primary key,
  usuario_id uuid not null references auth.users(id) on delete cascade,
  tipo_examen text not null,
  cantidad_total integer not null default 0,
  cantidad_usada integer not null default 0,
  valido_desde timestamp with time zone not null default now(),
  valido_hasta timestamp with time zone not null,
  suscripcion_id uuid,
  creado_en timestamp with time zone not null default now(),
  constraint creditos_contenido_usuario_tipo_examen_check check (tipo_examen = any (array['rafaga', 'razonado', 'inmersivo']))
);
create index if not exists idx_creditos_contenido_usuario_usuario_id on public.creditos_contenido_usuario (usuario_id);

create table if not exists public.planes_suscripcion (
  id uuid not null default gen_random_uuid() primary key,
  codigo text not null unique,
  nombre text not null,
  descripcion text,
  nivel integer not null,
  activo boolean not null default true,
  visible boolean not null default true,
  posicion integer not null default 1,
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now()
);

create table if not exists public.productos_comerciales (
  id uuid not null default gen_random_uuid() primary key,
  codigo text not null unique,
  tipo_producto text not null,
  nombre text not null,
  descripcion text,
  precio_centimos integer,
  moneda text not null default 'EUR',
  cantidad_tickets integer,
  activo boolean not null default true,
  visible boolean not null default true,
  destacado boolean not null default false,
  etiqueta_promocional text,
  posicion integer not null default 1,
  metadatos jsonb not null default '{}'::jsonb,
  plan_id uuid references public.planes_suscripcion(id),
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now()
);

-- RLS como en MAIN: lectura propia (o admin); ninguna politica de escritura para anon/authenticated.
-- Ktor lee con service_role (bypass RLS).
alter table public.carteras_usuario enable row level security;
alter table public.transacciones_cartera enable row level security;
alter table public.activaciones_servicio_usuario enable row level security;
alter table public.creditos_contenido_usuario enable row level security;
alter table public.servicios enable row level security;
alter table public.planes_suscripcion enable row level security;
alter table public.productos_comerciales enable row level security;

do $$
begin
  if not exists (select 1 from pg_policy where polname = 'carteras_select_propio') then
    create policy carteras_select_propio on public.carteras_usuario for select to authenticated
      using ((usuario_id = (select auth.uid())) or public.es_admin());
  end if;
  if not exists (select 1 from pg_policy where polname = 'transacciones_select_propio') then
    create policy transacciones_select_propio on public.transacciones_cartera for select to authenticated
      using ((usuario_id = (select auth.uid())) or public.es_admin());
  end if;
  if not exists (select 1 from pg_policy where polname = 'activaciones_select_propio') then
    create policy activaciones_select_propio on public.activaciones_servicio_usuario for select to authenticated
      using ((usuario_id = (select auth.uid())) or public.es_admin());
  end if;
  if not exists (select 1 from pg_policy where polname = 'creditos_select_propio') then
    create policy creditos_select_propio on public.creditos_contenido_usuario for select to authenticated
      using ((usuario_id = (select auth.uid())) or public.es_admin());
  end if;
end $$;

grant select on table public.carteras_usuario, public.transacciones_cartera, public.activaciones_servicio_usuario, public.creditos_contenido_usuario,
  public.servicios, public.planes_suscripcion, public.productos_comerciales to service_role;

notify pgrst, 'reload schema';
