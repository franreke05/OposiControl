-- E5-B01 / PHASE B (LOCAL ONLY). Tablas de cartera, compras y derechos que D6-01, D6-02, D7-02 y D7-05 leen y que quedan
-- FUERA de supabase/baseline/main_shape_subset.sql. NO es una migracion: nunca se aplica fuera del stack local.
-- Idempotente: se puede aplicar sobre un stack vivo sin reset (no borra filas).
--
-- Procedencia de la forma:
--   compras_usuario, transacciones_cartera  sentencias LITERALES de supabase/baseline/main_shape_subset_20260929.sql (captura
--                                           de MAIN), confirmadas por la captura de catalogo de solo lectura del 2026-10-14.
--   carteras_usuario, suscripciones, servicios, activaciones_servicio_usuario, creditos_contenido_usuario, planes_suscripcion,
--   productos_comerciales, grants de estado_premium_usuario  captura de catalogo de MAIN de SOLO LECTURA del 2026-10-14
--                                           (decision del propietario 3: columnas, tipos, restricciones, indices, politicas y
--                                           grants; ninguna fila).
-- e2e WALLET.local_schema_matches_main comprueba la forma.

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
  if not exists (select 1 from pg_constraint where conname in ('transacciones_cartera_idempotency_key_key', 'transacciones_cartera_usuario_idempotency_key')) then execute 'ALTER TABLE public.transacciones_cartera ADD CONSTRAINT transacciones_cartera_idempotency_key_key UNIQUE (idempotency_key)'; end if;
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

-- ---------- Resto de tablas: forma EXACTA de MAIN (captura de catalogo de solo lectura del 2026-10-14, decision 3) ----------
-- Tablas nuevas: CREATE de MAIN. Stacks creados con la version anterior de este overlay: bloque CONVERGE (ALTER aditivos;
-- los unicos drops son politicas/restricciones con nombre o regla que NO existen en MAIN, nunca filas).
-- Diferencia deliberada: la FK activaciones_servicio_usuario.usado_en_reto_id -> retos_diarios NO se crea (retos_diarios
-- no existe en el stack local); la columna si.

create table if not exists public.carteras_usuario (
  usuario_id uuid not null,
  saldo_tickets integer not null default 0,
  actualizado_en timestamp with time zone not null default now(),
  constraint carteras_usuario_pkey primary key (usuario_id),
  constraint carteras_usuario_saldo_tickets_check check ((saldo_tickets >= 0)),
  constraint carteras_usuario_usuario_id_fkey foreign key (usuario_id) references auth.users(id) on delete cascade
);

create table if not exists public.suscripciones (
  id uuid not null default gen_random_uuid(),
  usuario_id uuid not null,
  proveedor text not null,
  producto_codigo text not null,
  estado text not null,
  inicio_en timestamp with time zone not null,
  fin_en timestamp with time zone,
  renovacion_en timestamp with time zone,
  identificador_externo text,
  payload_validacion jsonb not null default '{}'::jsonb,
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now(),
  constraint suscripciones_pkey primary key (id),
  constraint suscripciones_estado_check check ((estado = any (array['activa'::text, 'pausada'::text, 'cancelada'::text, 'caducada'::text, 'periodo_prueba'::text]))),
  constraint suscripciones_proveedor_check check ((proveedor = any (array['google_play'::text, 'app_store'::text, 'stripe'::text, 'manual'::text]))),
  constraint suscripciones_usuario_id_fkey foreign key (usuario_id) references auth.users(id) on delete cascade
);
create index if not exists idx_suscripciones_usuario on public.suscripciones using btree (usuario_id);
create index if not exists idx_suscripciones_estado on public.suscripciones using btree (estado);

create table if not exists public.servicios (
  id uuid not null default gen_random_uuid(),
  codigo text not null,
  nombre text not null,
  descripcion text,
  tipo_servicio text not null,
  precio_tickets integer,
  precio_centimos integer,
  activo boolean not null default true,
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now(),
  constraint servicios_pkey primary key (id),
  constraint servicios_codigo_key unique (codigo),
  constraint servicios_precio_centimos_check check (((precio_centimos is null) or (precio_centimos >= 0))),
  constraint servicios_precio_tickets_check check (((precio_tickets is null) or (precio_tickets >= 0))),
  constraint servicios_tipo_servicio_check check ((tipo_servicio = any (array['premium'::text, 'correccion'::text, 'tutoria'::text,
    'contenido_extra'::text, 'protector_racha'::text, 'protector_tickets'::text, 'protector_dual'::text, 'potenciador'::text,
    'doble_oportunidad'::text, 'triple_oportunidad'::text, 'modo_papel'::text, 'carta_ccaa_comodin'::text])))
);

create table if not exists public.activaciones_servicio_usuario (
  id uuid not null default gen_random_uuid(),
  usuario_id uuid not null,
  servicio_id uuid not null,
  compra_id uuid,
  estado text not null default 'activa'::text,
  activa_desde timestamp with time zone not null default now(),
  activa_hasta timestamp with time zone,
  metadatos jsonb not null default '{}'::jsonb,
  creado_en timestamp with time zone not null default now(),
  usos_disponibles integer not null default 1,
  usos_consumidos integer not null default 0,
  usado_en_reto_id uuid,
  usado_en_intento_id uuid,
  usado_en timestamp with time zone,
  constraint activaciones_servicio_usuario_pkey primary key (id),
  constraint activaciones_servicio_usuario_estado_check check ((estado = any (array['activa'::text, 'usada'::text, 'caducada'::text, 'cancelada'::text]))),
  constraint activaciones_servicio_usuario_compra_id_fkey foreign key (compra_id) references public.compras_usuario(id) on delete set null,
  constraint activaciones_servicio_usuario_servicio_id_fkey foreign key (servicio_id) references public.servicios(id) on delete cascade,
  constraint activaciones_servicio_usuario_usado_en_intento_id_fkey foreign key (usado_en_intento_id) references public.intentos_examen(id),
  constraint activaciones_servicio_usuario_usuario_id_fkey foreign key (usuario_id) references auth.users(id) on delete cascade
);

create table if not exists public.creditos_contenido_usuario (
  id uuid not null default gen_random_uuid(),
  usuario_id uuid not null,
  suscripcion_id uuid,
  tipo_examen text not null,
  cantidad_total integer not null,
  cantidad_usada integer not null default 0,
  valido_desde timestamp with time zone not null default now(),
  valido_hasta timestamp with time zone not null,
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now(),
  constraint creditos_contenido_usuario_pkey primary key (id),
  constraint creditos_contenido_usuario_canonical_v2_tipo_examen_check check ((tipo_examen = any (array['rafaga'::text, 'razonado'::text, 'inmersivo'::text]))),
  constraint creditos_contenido_usuario_cantidad_total_check check ((cantidad_total > 0)),
  constraint creditos_contenido_usuario_cantidad_usada_check check ((cantidad_usada >= 0)),
  constraint creditos_contenido_usuario_check check ((cantidad_usada <= cantidad_total)),
  constraint creditos_contenido_usuario_suscripcion_id_fkey foreign key (suscripcion_id) references public.suscripciones(id) on delete set null,
  constraint creditos_contenido_usuario_usuario_id_fkey foreign key (usuario_id) references auth.users(id) on delete cascade
);

create table if not exists public.planes_suscripcion (
  id uuid not null default gen_random_uuid(),
  codigo text not null,
  nombre text not null,
  descripcion text,
  nivel integer not null,
  activo boolean not null default true,
  visible boolean not null default true,
  posicion integer not null default 1,
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now(),
  constraint planes_suscripcion_pkey primary key (id),
  constraint planes_suscripcion_codigo_key unique (codigo),
  constraint planes_suscripcion_canonical_v2_nivel_check check (((nivel >= 0) and (nivel <= 3))),
  constraint planes_suscripcion_nivel_check check ((nivel >= 0))
);

create table if not exists public.productos_comerciales (
  id uuid not null default gen_random_uuid(),
  codigo text not null,
  tipo_producto text not null,
  nombre text not null,
  descripcion text,
  precio_centimos integer,
  moneda text not null default 'EUR'::text,
  cantidad_tickets integer,
  activo boolean not null default true,
  visible boolean not null default true,
  destacado boolean not null default false,
  etiqueta_promocional text,
  posicion integer not null default 1,
  metadatos jsonb not null default '{}'::jsonb,
  creado_en timestamp with time zone not null default now(),
  actualizado_en timestamp with time zone not null default now(),
  plan_id uuid,
  constraint productos_comerciales_pkey primary key (id),
  constraint productos_comerciales_codigo_key unique (codigo),
  constraint productos_comerciales_plan_fk foreign key (plan_id) references public.planes_suscripcion(id),
  constraint productos_comerciales_precio_no_negativo_check check (((precio_centimos is null) or (precio_centimos >= 0))),
  constraint productos_comerciales_tipo_producto_check check ((tipo_producto = any (array['suscripcion'::text, 'pack_tickets'::text,
    'compra_examen'::text, 'servicio'::text, 'consumible'::text])))
);

-- ---------- CONVERGE: stacks creados con la version anterior de este overlay ----------
do $converge$
begin
  -- carteras_usuario: nombres de MAIN (la version anterior uso PK/FK en linea con los mismos nombres por defecto).
  -- servicios
  alter table public.servicios add column if not exists descripcion text;
  alter table public.servicios add column if not exists tipo_servicio text;
  alter table public.servicios add column if not exists precio_tickets integer;
  alter table public.servicios add column if not exists precio_centimos integer;
  alter table public.servicios add column if not exists actualizado_en timestamp with time zone not null default now();
  update public.servicios set tipo_servicio = case when codigo = 'potenciador' then 'potenciador' else 'protector_racha' end where tipo_servicio is null;
  alter table public.servicios alter column tipo_servicio set not null;
  if not exists (select 1 from pg_constraint where conname = 'servicios_precio_centimos_check') then
    alter table public.servicios add constraint servicios_precio_centimos_check check (((precio_centimos is null) or (precio_centimos >= 0)));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'servicios_precio_tickets_check') then
    alter table public.servicios add constraint servicios_precio_tickets_check check (((precio_tickets is null) or (precio_tickets >= 0)));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'servicios_tipo_servicio_check') then
    alter table public.servicios add constraint servicios_tipo_servicio_check check ((tipo_servicio = any (array['premium'::text, 'correccion'::text,
      'tutoria'::text, 'contenido_extra'::text, 'protector_racha'::text, 'protector_tickets'::text, 'protector_dual'::text, 'potenciador'::text,
      'doble_oportunidad'::text, 'triple_oportunidad'::text, 'modo_papel'::text, 'carta_ccaa_comodin'::text])));
  end if;

  -- activaciones_servicio_usuario
  alter table public.activaciones_servicio_usuario add column if not exists metadatos jsonb not null default '{}'::jsonb;
  if exists (select 1 from pg_constraint where conname = 'activaciones_servicio_usuario_servicio_id_fkey' and confdeltype <> 'c') then
    alter table public.activaciones_servicio_usuario drop constraint activaciones_servicio_usuario_servicio_id_fkey;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'activaciones_servicio_usuario_servicio_id_fkey') then
    alter table public.activaciones_servicio_usuario add constraint activaciones_servicio_usuario_servicio_id_fkey
      foreign key (servicio_id) references public.servicios(id) on delete cascade;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'activaciones_servicio_usuario_compra_id_fkey') then
    alter table public.activaciones_servicio_usuario add constraint activaciones_servicio_usuario_compra_id_fkey
      foreign key (compra_id) references public.compras_usuario(id) on delete set null;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'activaciones_servicio_usuario_usado_en_intento_id_fkey') then
    alter table public.activaciones_servicio_usuario add constraint activaciones_servicio_usuario_usado_en_intento_id_fkey
      foreign key (usado_en_intento_id) references public.intentos_examen(id);
  end if;

  -- creditos_contenido_usuario
  alter table public.creditos_contenido_usuario alter column cantidad_total drop default;
  alter table public.creditos_contenido_usuario add column if not exists actualizado_en timestamp with time zone not null default now();
  if exists (select 1 from pg_constraint where conname = 'creditos_contenido_usuario_tipo_examen_check') then
    alter table public.creditos_contenido_usuario rename constraint creditos_contenido_usuario_tipo_examen_check
      to creditos_contenido_usuario_canonical_v2_tipo_examen_check;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'creditos_contenido_usuario_cantidad_total_check') then
    alter table public.creditos_contenido_usuario add constraint creditos_contenido_usuario_cantidad_total_check check ((cantidad_total > 0));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'creditos_contenido_usuario_cantidad_usada_check') then
    alter table public.creditos_contenido_usuario add constraint creditos_contenido_usuario_cantidad_usada_check check ((cantidad_usada >= 0));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'creditos_contenido_usuario_check') then
    alter table public.creditos_contenido_usuario add constraint creditos_contenido_usuario_check check ((cantidad_usada <= cantidad_total));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'creditos_contenido_usuario_suscripcion_id_fkey') then
    alter table public.creditos_contenido_usuario add constraint creditos_contenido_usuario_suscripcion_id_fkey
      foreign key (suscripcion_id) references public.suscripciones(id) on delete set null;
  end if;

  -- planes_suscripcion
  if not exists (select 1 from pg_constraint where conname = 'planes_suscripcion_canonical_v2_nivel_check') then
    alter table public.planes_suscripcion add constraint planes_suscripcion_canonical_v2_nivel_check check (((nivel >= 0) and (nivel <= 3)));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'planes_suscripcion_nivel_check') then
    alter table public.planes_suscripcion add constraint planes_suscripcion_nivel_check check ((nivel >= 0));
  end if;

  -- productos_comerciales
  if exists (select 1 from pg_constraint where conname = 'productos_comerciales_plan_id_fkey') then
    alter table public.productos_comerciales rename constraint productos_comerciales_plan_id_fkey to productos_comerciales_plan_fk;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'productos_comerciales_plan_fk') then
    alter table public.productos_comerciales add constraint productos_comerciales_plan_fk foreign key (plan_id) references public.planes_suscripcion(id);
  end if;
  if not exists (select 1 from pg_constraint where conname = 'productos_comerciales_precio_no_negativo_check') then
    alter table public.productos_comerciales add constraint productos_comerciales_precio_no_negativo_check
      check (((precio_centimos is null) or (precio_centimos >= 0)));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'productos_comerciales_tipo_producto_check') then
    alter table public.productos_comerciales add constraint productos_comerciales_tipo_producto_check check ((tipo_producto = any (array['suscripcion'::text,
      'pack_tickets'::text, 'compra_examen'::text, 'servicio'::text, 'consumible'::text])));
  end if;

  -- politica de creditos con nombre y regla de MAIN
  if exists (select 1 from pg_policy where polname = 'creditos_select_propio' and polrelid = 'public.creditos_contenido_usuario'::regclass) then
    drop policy creditos_select_propio on public.creditos_contenido_usuario;
  end if;
end
$converge$;

-- ---------- indices de MAIN ----------
create index if not exists idx_activaciones_servicio_usuario_compra_id on public.activaciones_servicio_usuario using btree (compra_id);
create index if not exists idx_activaciones_servicio_usuario_servicio_id on public.activaciones_servicio_usuario using btree (servicio_id);
create index if not exists idx_activaciones_servicio_usuario_usado_en_intento_id on public.activaciones_servicio_usuario using btree (usado_en_intento_id);
create index if not exists idx_activaciones_servicio_usuario_usado_en_reto_id on public.activaciones_servicio_usuario using btree (usado_en_reto_id);
create index if not exists idx_activaciones_servicio_usuario_usuario_id on public.activaciones_servicio_usuario using btree (usuario_id);
create index if not exists idx_creditos_contenido_usuario_suscripcion_id on public.creditos_contenido_usuario using btree (suscripcion_id);
create index if not exists idx_creditos_contenido_usuario_usuario_id on public.creditos_contenido_usuario using btree (usuario_id);

-- ---------- RLS + politicas de MAIN ----------
alter table public.carteras_usuario enable row level security;
alter table public.suscripciones enable row level security;
alter table public.servicios enable row level security;
alter table public.activaciones_servicio_usuario enable row level security;
alter table public.creditos_contenido_usuario enable row level security;
alter table public.planes_suscripcion enable row level security;
alter table public.productos_comerciales enable row level security;

do $policies$
begin
  if not exists (select 1 from pg_policy where polname = 'carteras_select_propio') then
    create policy carteras_select_propio on public.carteras_usuario for select to authenticated
      using (((usuario_id = (select auth.uid() as uid)) or public.es_admin()));
  end if;
  if not exists (select 1 from pg_policy where polname = 'suscripciones_select_propio') then
    create policy suscripciones_select_propio on public.suscripciones for select to authenticated
      using (((usuario_id = (select auth.uid() as uid)) or public.es_admin()));
  end if;
  if not exists (select 1 from pg_policy where polname = 'servicios_select_publico') then
    create policy servicios_select_publico on public.servicios for select to authenticated, anon using (((activo = true) or public.es_admin()));
  end if;
  if not exists (select 1 from pg_policy where polname = 'activaciones_select_propio') then
    create policy activaciones_select_propio on public.activaciones_servicio_usuario for select to authenticated
      using (((usuario_id = (select auth.uid() as uid)) or public.es_admin()));
  end if;
  if not exists (select 1 from pg_policy where polname = 'canonical_v2_creditos_select') then
    create policy canonical_v2_creditos_select on public.creditos_contenido_usuario for select to authenticated
      using (((select auth.uid() as uid) = usuario_id));
  end if;
  if not exists (select 1 from pg_policy where polname = 'canonical_v2_plans_public_read') then
    create policy canonical_v2_plans_public_read on public.planes_suscripcion for select to authenticated, anon using ((activo and visible));
  end if;
  if not exists (select 1 from pg_policy where polname = 'canonical_v2_products_public_read') then
    create policy canonical_v2_products_public_read on public.productos_comerciales for select to authenticated, anon using ((activo and visible));
  end if;
end
$policies$;

-- ---------- GRANTS de MAIN (exactos; el default de Supabase daba DML a anon/authenticated) ----------
revoke all on table public.carteras_usuario, public.suscripciones, public.servicios, public.activaciones_servicio_usuario,
  public.creditos_contenido_usuario, public.planes_suscripcion, public.productos_comerciales, public.estado_premium_usuario
  from public, anon, authenticated, service_role;
grant select on table public.activaciones_servicio_usuario, public.estado_premium_usuario, public.planes_suscripcion,
  public.productos_comerciales, public.servicios to anon;
grant select on table public.activaciones_servicio_usuario, public.carteras_usuario, public.creditos_contenido_usuario,
  public.estado_premium_usuario, public.planes_suscripcion, public.productos_comerciales, public.servicios, public.suscripciones to authenticated;
grant delete, insert, references, select, trigger, truncate, update on table public.activaciones_servicio_usuario, public.carteras_usuario,
  public.creditos_contenido_usuario, public.estado_premium_usuario, public.planes_suscripcion, public.productos_comerciales,
  public.servicios, public.suscripciones to service_role;

notify pgrst, 'reload schema';
