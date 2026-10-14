-- E5-B01 / PHASE B (LOCAL ONLY). Tablas de cartera y derechos que D6-01 (/api/v1/app/wallet) y D7-02
-- (/api/v1/app/me/entitlement) leen y que quedan FUERA de supabase/baseline/main_shape_subset.sql.
-- NO es una migracion: nunca se aplica fuera del stack local. Idempotente y aditivo (create ... if not exists,
-- sin borrar datos; el unico drop es un indice provisional de este mismo overlay): se puede aplicar sobre un stack vivo sin reset.
--
-- Procedencia de la forma (sin lectura nueva de MAIN):
--   carteras_usuario               columnas exactas de la captura de catalogo de MAIN (solo esquema) usada para el baseline.
--   compras_usuario, transacciones_cartera  sentencias LITERALES de supabase/baseline/main_shape_subset_20260929.sql.
--   activaciones_servicio_usuario  prefijo capturado (id, usuario_id, servicio_id, compra_id, estado default 'activa') +
--                                  columnas que lee Ktor (WalletGateway.activations) + indices de 20260803101711.
--   creditos_contenido_usuario     supabase/canonical/dry_run_fixture/001_live_shape.sql + columnas que lee Ktor
--                                  (CatalogGateway.activeCredits) + suscripcion_id (indice de 20260803101711).
--   planes_suscripcion, productos_comerciales  001_live_shape.sql + plan_id (product_metrics_candidate_v1/004).
--   servicios                      solo lo que el embed servicios(codigo) necesita.
-- Paridad columna a columna con MAIN (salvo compras_usuario y transacciones_cartera): PENDIENTE de una captura de catalogo autorizada (ver handoff E1-B01).

create table if not exists public.carteras_usuario (
  usuario_id uuid not null primary key references auth.users(id) on delete cascade,
  saldo_tickets integer not null default 0,
  actualizado_en timestamp with time zone not null default now(),
  constraint carteras_usuario_saldo_tickets_check check (saldo_tickets >= 0)
);

-- compras_usuario + transacciones_cartera: sentencias LITERALES de la captura de MAIN supabase/baseline/main_shape_subset_20260929.sql
-- (la regeneracion del subconjunto del 2026-10-14 ya no las arrastra). Cada sentencia va protegida por existencia: idempotente.
do $ledger$
begin
  if to_regclass('public.compras_usuario') is null then execute 'CREATE TABLE public.compras_usuario (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, proveedor text NOT NULL, producto_codigo text NOT NULL, tipo_producto text NOT NULL, estado text NOT NULL DEFAULT ''pendiente''::text, precio_centimos integer, moneda text NOT NULL DEFAULT ''EUR''::text, identificador_transaccion text, payload_validacion jsonb NOT NULL DEFAULT ''{}''::jsonb, comprada_en timestamp with time zone, creado_en timestamp with time zone NOT NULL DEFAULT now(), actualizado_en timestamp with time zone NOT NULL DEFAULT now())'; end if;
  if to_regclass('public.transacciones_cartera') is null then execute 'CREATE TABLE public.transacciones_cartera (id uuid NOT NULL DEFAULT gen_random_uuid(), usuario_id uuid NOT NULL, compra_id uuid, tipo text NOT NULL, cantidad integer NOT NULL, saldo_resultante integer NOT NULL, descripcion text, creado_en timestamp with time zone NOT NULL DEFAULT now(), saldo_anterior integer, referencia_tipo text, referencia_id uuid, idempotency_key text, metadatos jsonb NOT NULL DEFAULT ''{}''::jsonb)'; end if;
  if not exists (select 1 from pg_constraint where conname = 'compras_usuario_pkey') then execute 'ALTER TABLE public.compras_usuario ADD CONSTRAINT compras_usuario_pkey PRIMARY KEY (id)'; end if;
  if not exists (select 1 from pg_constraint where conname = 'compras_usuario_estado_check') then execute 'ALTER TABLE public.compras_usuario ADD CONSTRAINT compras_usuario_estado_check CHECK ((estado = ANY (ARRAY[''pendiente''::text, ''pagada''::text, ''cancelada''::text, ''reembolsada''::text, ''fallida''::text])))'; end if;
  if not exists (select 1 from pg_constraint where conname = 'compras_usuario_precio_centimos_check') then execute 'ALTER TABLE public.compras_usuario ADD CONSTRAINT compras_usuario_precio_centimos_check CHECK (((precio_centimos IS NULL) OR (precio_centimos >= 0)))'; end if;
  if not exists (select 1 from pg_constraint where conname = 'compras_usuario_proveedor_check') then execute 'ALTER TABLE public.compras_usuario ADD CONSTRAINT compras_usuario_proveedor_check CHECK ((proveedor = ANY (ARRAY[''google_play''::text, ''app_store''::text, ''stripe''::text, ''manual''::text])))'; end if;
  if not exists (select 1 from pg_constraint where conname = 'compras_usuario_tipo_producto_check') then execute 'ALTER TABLE public.compras_usuario ADD CONSTRAINT compras_usuario_tipo_producto_check CHECK ((tipo_producto = ANY (ARRAY[''opositickets''::text, ''premium''::text, ''servicio''::text, ''contenido''::text])))'; end if;
  if not exists (select 1 from pg_constraint where conname = 'compras_usuario_usuario_id_fkey') then execute 'ALTER TABLE public.compras_usuario ADD CONSTRAINT compras_usuario_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE'; end if;
  if not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_compra_id_fkey') then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_compra_id_fkey FOREIGN KEY (compra_id) REFERENCES public.compras_usuario(id) ON DELETE SET NULL'; end if;
  if to_regclass('public.idx_compras_usuario_usuario') is null then execute 'CREATE INDEX idx_compras_usuario_usuario ON public.compras_usuario USING btree (usuario_id)'; end if;
  if not exists (select 1 from pg_trigger where tgname = 'trg_compras_usuario_actualizado_en') then execute 'CREATE TRIGGER trg_compras_usuario_actualizado_en BEFORE UPDATE ON public.compras_usuario FOR EACH ROW EXECUTE FUNCTION public.establecer_actualizado_en()'; end if;
  execute 'ALTER TABLE public.compras_usuario ENABLE ROW LEVEL SECURITY';
  if not exists (select 1 from pg_policy where polname = 'compras_select_propio') then execute 'CREATE POLICY compras_select_propio ON public.compras_usuario AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()))'; end if;
  execute 'REVOKE ALL ON TABLE public.compras_usuario FROM PUBLIC, anon, authenticated, service_role';
  execute 'GRANT SELECT ON TABLE public.compras_usuario TO authenticated';
  execute 'GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.compras_usuario TO service_role';
  if not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_pkey') then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_pkey PRIMARY KEY (id)'; end if;
  if not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_idempotency_key_key') then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_idempotency_key_key UNIQUE (idempotency_key)'; end if;
  if not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_saldo_resultante_check') then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_saldo_resultante_check CHECK ((saldo_resultante >= 0))'; end if;
  if not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_tipo_check') then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_tipo_check CHECK ((tipo = ANY (ARRAY[''recarga''::text, ''pack_tickets''::text, ''gasto''::text, ''compra_examen''::text, ''compra_servicio''::text, ''ajuste''::text, ''devolucion''::text, ''regalo''::text, ''reto_diario''::text, ''bonus_racha''::text, ''referido''::text, ''reporte_validado''::text, ''potenciador''::text, ''protector''::text])))'; end if;
  if not exists (select 1 from pg_constraint where conname = 'transacciones_cartera_usuario_id_fkey') then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE'; end if;
  if to_regclass('public.idx_transacciones_cartera_compra_id') is null then execute 'CREATE INDEX idx_transacciones_cartera_compra_id ON public.transacciones_cartera USING btree (compra_id)'; end if;
  if to_regclass('public.idx_transacciones_cartera_usuario') is null then execute 'CREATE INDEX idx_transacciones_cartera_usuario ON public.transacciones_cartera USING btree (usuario_id)'; end if;
  execute 'ALTER TABLE public.transacciones_cartera ENABLE ROW LEVEL SECURITY';
  if not exists (select 1 from pg_policy where polname = 'transacciones_select_propio') then execute 'CREATE POLICY transacciones_select_propio ON public.transacciones_cartera AS PERMISSIVE FOR SELECT TO authenticated USING (((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin()))'; end if;
  execute 'REVOKE ALL ON TABLE public.transacciones_cartera FROM PUBLIC, anon, authenticated, service_role';
  execute 'GRANT SELECT ON TABLE public.transacciones_cartera TO authenticated';
  execute 'GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE public.transacciones_cartera TO service_role';
end
$ledger$;
-- 20261008100500 (en MAIN): indice keyset del libro.
create index if not exists idx_transacciones_cartera_usuario_creado_id on public.transacciones_cartera (usuario_id, creado_en desc, id desc);
-- Indice provisional de una version anterior de este overlay (solo local): lo sustituye el de 20261008100500.
drop index if exists public.idx_transacciones_cartera_usuario_creado;

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
  if not exists (select 1 from pg_policy where polname = 'activaciones_select_propio') then
    create policy activaciones_select_propio on public.activaciones_servicio_usuario for select to authenticated
      using ((usuario_id = (select auth.uid())) or public.es_admin());
  end if;
  if not exists (select 1 from pg_policy where polname = 'creditos_select_propio') then
    create policy creditos_select_propio on public.creditos_contenido_usuario for select to authenticated
      using ((usuario_id = (select auth.uid())) or public.es_admin());
  end if;
end $$;

grant select on table public.carteras_usuario, public.activaciones_servicio_usuario, public.creditos_contenido_usuario,
  public.servicios, public.planes_suscripcion, public.productos_comerciales to service_role;

notify pgrst, 'reload schema';
