-- MAIN objects that the generated bootstrap (main_shape_subset.sql, capture 2026-10-14) does not carry but that the V1 migrations build on:
-- public.compras_usuario (payment orders) and public.transacciones_cartera (wallet ledger). The statements are the LITERAL ones of the MAIN
-- capture main_shape_subset_20260929.sql, confirmed by the read-only schema query of MAIN on 2026-10-14 (columns, constraints, indexes, policies,
-- grants). Loaded by scripts/db-pg17-gate.sh right after the bootstrap and by the local overlay. Idempotent. NOT a migration.
-- Next regeneration of main_shape_subset.sql: add the V1 migrations to the extractor seeds and delete this file.

-- public.carteras_usuario (wallet snapshot): shape from the read-only MAIN schema query of 2026-10-14.
create table if not exists public.carteras_usuario (
  usuario_id uuid not null,
  saldo_tickets integer not null default 0,
  actualizado_en timestamp with time zone not null default now(),
  constraint carteras_usuario_pkey primary key (usuario_id),
  constraint carteras_usuario_saldo_tickets_check check ((saldo_tickets >= 0)),
  constraint carteras_usuario_usuario_id_fkey foreign key (usuario_id) references auth.users(id) on delete cascade
);
alter table public.carteras_usuario enable row level security;
do $carteras$
begin
  if not exists (select 1 from pg_policy where polname = 'carteras_select_propio') then
    create policy carteras_select_propio on public.carteras_usuario for select to authenticated
      using (((usuario_id = (select auth.uid() as uid)) or public.es_admin()));
  end if;
end
$carteras$;
revoke all on table public.carteras_usuario from public, anon, authenticated, service_role;
grant select on table public.carteras_usuario to authenticated;
grant delete, insert, maintain, references, select, trigger, truncate, update on table public.carteras_usuario to service_role;

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

-- 20261008100500 (in MAIN): keyset index of the ledger.
create index if not exists idx_transacciones_cartera_usuario_creado_id on public.transacciones_cartera (usuario_id, creado_en desc, id desc);

-- public.admin_grant_tickets: the MAIN definition (read-only definition query of 2026-10-14), before the V1 hardening.
create or replace function public.admin_grant_tickets(p_usuario_id uuid, p_cantidad integer, p_descripcion text, p_idempotency_key text, p_tipo text default 'ajuste'::text)
 returns table(transaction_id uuid, wallet_user_id uuid, cantidad integer, saldo_anterior integer, saldo_resultante integer, idempotency_key text)
 language plpgsql security definer set search_path to '' as $f$
declare
  v_key text := btrim(p_idempotency_key);
  v_existing public.transacciones_cartera%rowtype;
  v_saldo_anterior integer;
  v_saldo_resultante integer;
  v_transaction_id uuid;
begin
  if p_usuario_id is null or not exists (select 1 from auth.users u where u.id = p_usuario_id) then raise exception 'WALLET_INVALID_USER'; end if;
  if p_cantidad is null or p_cantidad = 0 then raise exception 'WALLET_AMOUNT_INVALID'; end if;
  if p_tipo is null or p_tipo not in ('ajuste', 'reporte_validado') then raise exception 'WALLET_TYPE_INVALID'; end if;
  if p_tipo <> 'ajuste' and p_cantidad < 0 then raise exception 'WALLET_GRANT_AMOUNT_MUST_BE_POSITIVE'; end if;
  if p_descripcion is null or length(btrim(p_descripcion)) = 0 then raise exception 'WALLET_DESCRIPTION_REQUIRED'; end if;
  if p_idempotency_key is null or length(v_key) = 0 then raise exception 'WALLET_IDEMPOTENCY_KEY_REQUIRED'; end if;
  perform pg_advisory_xact_lock(hashtextextended('wallet-key:' || v_key, 0));
  select t.* into v_existing from public.transacciones_cartera t where t.idempotency_key = v_key for update;
  if found then
    if v_existing.usuario_id is not distinct from p_usuario_id and v_existing.tipo is not distinct from p_tipo
      and v_existing.cantidad is not distinct from p_cantidad and v_existing.descripcion is not distinct from btrim(p_descripcion)
      and v_existing.compra_id is null and v_existing.referencia_tipo is not distinct from 'admin' and v_existing.referencia_id is null then
      return query select v_existing.id, v_existing.usuario_id, v_existing.cantidad, v_existing.saldo_anterior, v_existing.saldo_resultante, v_existing.idempotency_key;
      return;
    end if;
    raise exception 'WALLET_IDEMPOTENCY_CONFLICT';
  end if;
  insert into public.carteras_usuario (usuario_id, saldo_tickets, actualizado_en) values (p_usuario_id, 0, now()) on conflict (usuario_id) do nothing;
  select w.saldo_tickets into v_saldo_anterior from public.carteras_usuario w where w.usuario_id = p_usuario_id for update;
  v_saldo_resultante := v_saldo_anterior + p_cantidad;
  if v_saldo_resultante < 0 then raise exception 'WALLET_INSUFFICIENT_BALANCE'; end if;
  update public.carteras_usuario set saldo_tickets = v_saldo_resultante, actualizado_en = now() where usuario_id = p_usuario_id;
  insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, descripcion, referencia_tipo, idempotency_key, metadatos)
  values (p_usuario_id, p_tipo, p_cantidad, v_saldo_anterior, v_saldo_resultante, btrim(p_descripcion), 'admin', v_key, '{}'::jsonb) returning id into v_transaction_id;
  return query select v_transaction_id, p_usuario_id, p_cantidad, v_saldo_anterior, v_saldo_resultante, v_key;
end;
$f$;
revoke all on function public.admin_grant_tickets(uuid, integer, text, text, text) from public, anon, authenticated;
grant execute on function public.admin_grant_tickets(uuid, integer, text, text, text) to service_role;

-- public.planes_suscripcion and public.productos_comerciales (commercial catalogue): shape from the read-only MAIN schema query of 2026-10-14.
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
alter table public.planes_suscripcion enable row level security;
alter table public.productos_comerciales enable row level security;
do $catalog$
begin
  if not exists (select 1 from pg_policy where polname = 'canonical_v2_plans_public_read') then
    create policy canonical_v2_plans_public_read on public.planes_suscripcion for select to authenticated, anon using ((activo and visible));
  end if;
  if not exists (select 1 from pg_policy where polname = 'canonical_v2_products_public_read') then
    create policy canonical_v2_products_public_read on public.productos_comerciales for select to authenticated, anon using ((activo and visible));
  end if;
end
$catalog$;
revoke all on table public.planes_suscripcion, public.productos_comerciales from public, anon, authenticated, service_role;
grant select on table public.planes_suscripcion, public.productos_comerciales to anon, authenticated;
grant delete, insert, maintain, references, select, trigger, truncate, update on table public.planes_suscripcion, public.productos_comerciales to service_role;

-- public.capacidades_especialidad (per-specialty exam-type switches): defaults as in MAIN since migration 20260917084358 (all modes open).
create table if not exists public.capacidades_especialidad (
  especialidad_id uuid primary key references public.especialidades(id) on delete cascade,
  permite_rafagas boolean not null default true,
  permite_razonados boolean not null default true,
  permite_inmersivos boolean not null default true,
  permite_demos boolean not null default true,
  permite_oposimarket boolean not null default true,
  mensaje_limitacion text,
  actualizado_en timestamptz not null default now()
);
alter table public.capacidades_especialidad enable row level security;
grant select on table public.capacidades_especialidad to service_role;

-- MAIN public.admin_dashboard_economy_totals() (Dashboard V2): body VERBATIM from 20260917090229 (MAIN), ACL from 20260917084214 (service_role only).
-- Created only when absent, so a live DB keeps its version; Control's 20261023100200 redefines it later. NOT a migration.
do $econ$
begin
  if to_regprocedure('public.admin_dashboard_economy_totals()') is null then
    execute $fn$
create or replace function public.admin_dashboard_economy_totals()
returns table (gross_revenue_cents bigint, paid_purchases integer, tickets_in_circulation bigint)
language sql
stable
security invoker
set search_path = ''
as $$
  select
    (select case
              when count(*) filter (where c.precio_centimos is null) > 0 then null
              when count(*) filter (where upper(btrim(c.moneda)) <> 'EUR') > 0 then null
              else coalesce(sum(c.precio_centimos), 0)::bigint
            end
       from public.compras_usuario c
      where c.estado = 'pagada'),
    (select count(*)::integer
       from public.compras_usuario c
      where c.estado = 'pagada'),
    (select coalesce(sum(w.saldo_tickets), 0)::bigint
       from public.carteras_usuario w);
$$;
$fn$;
    execute $c$
comment on function public.admin_dashboard_economy_totals() is
  'Dashboard V2: ingresos EUR de compras en estado pagada (NULL si alguna pagada no tiene precio o no es EUR), numero de compras pagadas y OposiTickets en circulacion (suma de saldos). Solo service_role.'
$c$;
    revoke all on function public.admin_dashboard_economy_totals() from public, anon, authenticated;
    grant execute on function public.admin_dashboard_economy_totals() to service_role;
  end if;
end
$econ$;
