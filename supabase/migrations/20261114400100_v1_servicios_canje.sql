-- =============================================================================
-- V1 -- Market catalog for the app and redeeming a service with OposiTickets (F28 step 2, FA52).
-- =============================================================================
-- Forward-only. Requires 20261114400000 (servicios visible/posicion/seccion/usos/duracion_horas/multiplicador) and the V1 wallet
-- (20261022100300 ledger hardening: the ledger trigger computes saldo_anterior/saldo_resultante, keeps carteras_usuario and traces FIFO in ot_consumos).
--
-- WHAT IT DOES
--   * private.servicio_disponibles(user, service, now): remaining uses of the user's usable activations of a service
--     (estado 'activa', not expired by activa_hasta, usos_consumidos < usos_disponibles). The ONE definition used by the catalog, the redeem
--     answer and the consumable effects (20261114400200).
--   * private.servicio_comprable(row): a service can be redeemed with OT when it is activo AND visible AND precio_tickets > 0 AND its type is
--     a consumable: the 7 POTENCIADORES types and modo_papel. carta_ccaa_comodin and modo_papel are purchasable but INERT in 1.3 (the client
--     documents only say "future version": no effect is defined); neither is seeded, so they are sold only if Control creates and activates them.
--     premium / correccion / tutoria / contenido_extra are NEVER redeemed with OT (no in-app fulfilment; premium is the paid subscription):
--     the catalog lists them with comprableConTickets = false.
--   * public.app_market_catalog(user) -> jsonb {"secciones":[{"codigo","titulo","items":[...]}]}: sections in the fixed order OPOSITICKETS,
--     POTENCIADORES, SERVICIOS; a section without items is omitted. OPOSITICKETS lists the ticket packs on sale (the same rule as D6-11:
--     productos_comerciales pack_tickets, activo, visible, cantidad_tickets > 0, precio_centimos > 0, by posicion); the other two list the activo AND
--     visible services of that seccion by posicion, codigo. Item: codigo, nombre, descripcion, tipo, precioTickets, precioCentimos, usos, duracionHoras,
--     multiplicador, disponibles (the user's remaining uses; 0 for packs), comprableConTickets, cantidadTickets (packs only).
--   * public.app_redeem_service(user, codigo, key, expected_price): in ONE transaction under the wallet lock (the same 'wallet:<user>' advisory lock
--     as every economic function): replay by (user, key) | NOT_FOUND (no active+visible service with that code) | NOT_PURCHASABLE | PRICE_CHANGED
--     {"price"} | INSUFFICIENT_BALANCE {"balance","price"} | debit the ledger ('compra_servicio', -precio, referencia servicio) and create the
--     activation (usos_disponibles = usos or 1, activa_hasta = now + duracion_horas or NULL, metadatos with the frozen multiplicador, price and the
--     ledger id). The replay returns the SAME transaction and the activation as it is NOW (its remaining uses may have been spent since).
--     Domain errors: SQLSTATE P0001, message = code, numbers in DETAIL as JSON; none writes anything.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). Ledger rows and activations already written are data and stay.
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_redeem_service(uuid, text, text, integer);
-- drop function if exists public.app_market_catalog(uuid);
-- drop function if exists private.servicio_activacion_json(uuid, timestamptz);
-- drop function if exists private.servicio_comprable(public.servicios);
-- drop function if exists private.servicio_disponibles(uuid, uuid, timestamptz);
-- drop index if exists public.idx_activaciones_servicio_usuario_usuario_servicio;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'redeem_service_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.app_redeem_service(uuid,text,text,integer)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_redeem_service(uuid,text,text,integer)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_redeem_service(uuid,text,text,integer)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'market_catalog_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.app_market_catalog(uuid)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_market_catalog(uuid)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_market_catalog(uuid)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'service_helpers_executable_by_clients',
--        (case when has_function_privilege('anon', 'private.servicio_disponibles(uuid,uuid,timestamptz)', 'execute')
--                or has_function_privilege('authenticated', 'private.servicio_disponibles(uuid,uuid,timestamptz)', 'execute')
--                or has_function_privilege('authenticated', 'private.servicio_activacion_json(uuid,timestamptz)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'activation_lookup_index_missing', (case when to_regclass('public.idx_activaciones_servicio_usuario_usuario_servicio') is null then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regclass('public.servicios') is null or to_regclass('public.activaciones_servicio_usuario') is null or to_regclass('public.productos_comerciales') is null
     or to_regclass('public.ot_consumos') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'servicios' and column_name = 'seccion') then
    raise exception using errcode = 'P0001', message = 'F28_CANJE_PREFLIGHT', hint = 'apply 20261114400000 and the V1 wallet first';
  end if;
end
$$;

create index if not exists idx_activaciones_servicio_usuario_usuario_servicio on public.activaciones_servicio_usuario (usuario_id, servicio_id) where estado = 'activa';

create or replace function private.servicio_disponibles(p_usuario uuid, p_servicio uuid, p_now timestamptz)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(sum(a.usos_disponibles - a.usos_consumidos), 0)::integer
    from public.activaciones_servicio_usuario a
   where a.usuario_id = p_usuario and a.servicio_id = p_servicio and a.estado = 'activa'
     and (a.activa_hasta is null or a.activa_hasta > p_now) and a.usos_consumidos < a.usos_disponibles
$$;
revoke all on function private.servicio_disponibles(uuid, uuid, timestamptz) from public, anon, authenticated;

create or replace function private.servicio_comprable(s public.servicios)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select s.activo and s.visible and coalesce(s.precio_tickets, 0) > 0
     and s.tipo_servicio in ('protector_racha', 'protector_tickets', 'protector_dual', 'potenciador', 'doble_oportunidad', 'triple_oportunidad',
                             'carta_ccaa_comodin', 'modo_papel')
$$;
revoke all on function private.servicio_comprable(public.servicios) from public, anon, authenticated;

-- the activation as the app sees it (wire names), with the REMAINING uses at p_now
create or replace function private.servicio_activacion_json(p_activacion uuid, p_now timestamptz)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_strip_nulls(jsonb_build_object(
           'id', a.id, 'servicioCodigo', s.codigo, 'tipo', s.tipo_servicio,
           'estado', case when a.estado = 'activa' and a.activa_hasta is not null and a.activa_hasta <= p_now then 'caducada' else a.estado end,
           'usosRestantes', greatest(a.usos_disponibles - a.usos_consumidos, 0),
           'activaHasta', a.activa_hasta,
           'multiplicador', (a.metadatos ->> 'multiplicador')::double precision))
    from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id
   where a.id = p_activacion
$$;
revoke all on function private.servicio_activacion_json(uuid, timestamptz) from public, anon, authenticated;

create or replace function public.app_market_catalog(p_usuario uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_packs jsonb;
  v_pot jsonb;
  v_srv jsonb;
  v_out jsonb := '[]'::jsonb;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  select coalesce(jsonb_agg(jsonb_build_object(
           'codigo', p.codigo, 'nombre', p.nombre, 'descripcion', p.descripcion, 'tipo', 'pack_tickets',
           'precioTickets', null, 'precioCentimos', p.precio_centimos, 'usos', null, 'duracionHoras', null, 'multiplicador', null,
           'disponibles', 0, 'comprableConTickets', false, 'cantidadTickets', p.cantidad_tickets) order by p.posicion, p.codigo), '[]'::jsonb)
    into v_packs
    from public.productos_comerciales p
   where p.tipo_producto = 'pack_tickets' and p.activo and p.visible and p.cantidad_tickets > 0 and p.precio_centimos > 0;
  select coalesce(jsonb_agg(x.item order by x.posicion, x.codigo) filter (where x.seccion = 'POTENCIADORES'), '[]'::jsonb),
         coalesce(jsonb_agg(x.item order by x.posicion, x.codigo) filter (where x.seccion = 'SERVICIOS'), '[]'::jsonb)
    into v_pot, v_srv
    from (select s.seccion, s.posicion, s.codigo,
                 jsonb_build_object('codigo', s.codigo, 'nombre', s.nombre, 'descripcion', s.descripcion, 'tipo', s.tipo_servicio,
                   'precioTickets', s.precio_tickets, 'precioCentimos', s.precio_centimos, 'usos', s.usos, 'duracionHoras', s.duracion_horas,
                   'multiplicador', s.multiplicador::double precision, 'disponibles', private.servicio_disponibles(p_usuario, s.id, v_now),
                   'comprableConTickets', private.servicio_comprable(s), 'cantidadTickets', null) as item
            from public.servicios s
           where s.activo and s.visible) x;
  -- a service filed under OPOSITICKETS by Control goes after the packs of that section
  select v_packs || coalesce(jsonb_agg(jsonb_build_object('codigo', s.codigo, 'nombre', s.nombre, 'descripcion', s.descripcion, 'tipo', s.tipo_servicio,
                   'precioTickets', s.precio_tickets, 'precioCentimos', s.precio_centimos, 'usos', s.usos, 'duracionHoras', s.duracion_horas,
                   'multiplicador', s.multiplicador::double precision, 'disponibles', private.servicio_disponibles(p_usuario, s.id, v_now),
                   'comprableConTickets', private.servicio_comprable(s), 'cantidadTickets', null) order by s.posicion, s.codigo), '[]'::jsonb)
    into v_packs
    from public.servicios s where s.activo and s.visible and s.seccion = 'OPOSITICKETS';
  if jsonb_array_length(v_packs) > 0 then
    v_out := v_out || jsonb_build_array(jsonb_build_object('codigo', 'OPOSITICKETS', 'titulo', 'OposiTickets', 'items', v_packs));
  end if;
  if jsonb_array_length(v_pot) > 0 then
    v_out := v_out || jsonb_build_array(jsonb_build_object('codigo', 'POTENCIADORES', 'titulo', 'Potenciadores y protecciones', 'items', v_pot));
  end if;
  if jsonb_array_length(v_srv) > 0 then
    v_out := v_out || jsonb_build_array(jsonb_build_object('codigo', 'SERVICIOS', 'titulo', 'Servicios y accesos', 'items', v_srv));
  end if;
  return jsonb_build_object('secciones', v_out);
end;
$$;
revoke all on function public.app_market_catalog(uuid) from public, anon, authenticated;
grant execute on function public.app_market_catalog(uuid) to service_role;

create or replace function public.app_redeem_service(p_usuario uuid, p_codigo text, p_idem_key text, p_expected_price integer default null)
returns table(outcome text, transaccion_id uuid, saldo integer, activacion jsonb)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  v_key text := btrim(p_idem_key);
  v_codigo text := btrim(p_codigo);
  v_fp text;
  v_now timestamptz;
  v_srv public.servicios%rowtype;
  v_ledger public.transacciones_cartera%rowtype;
  v_balance integer;
  v_act uuid := gen_random_uuid();
  v_usos integer;
  v_hasta timestamptz;
begin
  if p_usuario is null or not exists (select 1 from public.perfiles_usuario pf where pf.usuario_id = p_usuario) then
    raise exception using errcode = 'P0001', message = 'WALLET_INVALID_USER';
  end if;
  if v_key is null or length(v_key) = 0 or length(v_key) > 128 then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_KEY_REQUIRED';
  end if;
  if v_codigo is null or length(v_codigo) = 0 or length(v_codigo) > 64 then
    raise exception using errcode = 'P0001', message = 'SERVICE_NOT_FOUND';
  end if;
  v_fp := encode(sha256(convert_to('servicio|' || v_codigo || '|' || coalesce(p_expected_price::text, '-'), 'utf8')), 'hex');

  perform pg_advisory_xact_lock(hashtextextended('wallet:' || p_usuario::text, 0));
  v_now := clock_timestamp();  -- after the lock (E2-V1-E03)

  select t.* into v_ledger from public.transacciones_cartera t where t.usuario_id = p_usuario and t.idempotency_key = v_key;
  if found then
    if v_ledger.metadatos ->> 'op' is distinct from 'canje_servicio' or v_ledger.metadatos ->> 'fp' is distinct from v_fp then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_KEY_REUSED';
    end if;
    return query select 'REPLAY'::text, v_ledger.id, coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0),
                        private.servicio_activacion_json((v_ledger.metadatos ->> 'activacion_id')::uuid, v_now);
    return;
  end if;

  select s.* into v_srv from public.servicios s where s.codigo = v_codigo and s.activo and s.visible;
  if not found then
    raise exception using errcode = 'P0001', message = 'SERVICE_NOT_FOUND';
  end if;
  if not private.servicio_comprable(v_srv) then
    raise exception using errcode = 'P0001', message = 'SERVICE_NOT_PURCHASABLE';
  end if;
  if p_expected_price is not null and p_expected_price <> v_srv.precio_tickets then
    raise exception using errcode = 'P0001', message = 'PRICE_CHANGED', detail = format('{"price":%s}', v_srv.precio_tickets);
  end if;
  select coalesce((select w.saldo_tickets from public.carteras_usuario w where w.usuario_id = p_usuario), 0) into v_balance;
  if v_balance < v_srv.precio_tickets then
    raise exception using errcode = 'P0001', message = 'INSUFFICIENT_BALANCE', detail = format('{"balance":%s,"price":%s}', v_balance, v_srv.precio_tickets);
  end if;

  v_usos := coalesce(v_srv.usos, 1);
  v_hasta := case when v_srv.duracion_horas is not null then v_now + make_interval(hours => v_srv.duracion_horas) end;
  insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
  values (p_usuario, 'compra_servicio', -v_srv.precio_tickets, left('Compra: ' || v_srv.nombre, 200), 'servicio', v_srv.id, v_key,
          jsonb_build_object('op', 'canje_servicio', 'fp', v_fp, 'activacion_id', v_act, 'servicio_codigo', v_srv.codigo, 'tipo_servicio', v_srv.tipo_servicio))
  returning * into v_ledger;
  insert into public.activaciones_servicio_usuario (id, usuario_id, servicio_id, estado, activa_desde, activa_hasta, usos_disponibles, usos_consumidos, metadatos)
  values (v_act, p_usuario, v_srv.id, 'activa', v_now, v_hasta, v_usos, 0,
          jsonb_strip_nulls(jsonb_build_object('op', 'canje_servicio', 'transaccion_id', v_ledger.id, 'precio_tickets', v_srv.precio_tickets,
                                               'tipo_servicio', v_srv.tipo_servicio, 'multiplicador', v_srv.multiplicador)));
  insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, despues)
  values (p_usuario, 'canje_servicio', 'activaciones_servicio_usuario', v_act,
          jsonb_build_object('servicio', v_srv.codigo, 'precio', v_srv.precio_tickets, 'transaccion_id', v_ledger.id));

  return query select 'CREATED'::text, v_ledger.id, v_ledger.saldo_resultante, private.servicio_activacion_json(v_act, v_now);
end;
$$;
revoke all on function public.app_redeem_service(uuid, text, text, integer) from public, anon, authenticated;
grant execute on function public.app_redeem_service(uuid, text, text, integer) to service_role;

commit;
