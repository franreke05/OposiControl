-- E5-B01 / PHASE B (LOCAL ONLY). Fixtures de cartera y derechos (D6-01, D6-02, D7-02). Idempotente y aditivo
-- (on conflict do nothing): no toca filas existentes ni los fixtures del motor de examen (90_fixtures.sql).
--
--   A 0a000000-…-0a  SIN premium (D7-02 isPremium=false, status sin_suscripcion) + 1 lote vigente de creditos razonado
--                    (5 total / 2 usados) + 1 lote CADUCADO (no debe salir); cartera 35 tickets; 5 movimientos de
--                    categorias distintas (recarga, reto_diario, regalo, compra_examen, potenciador) con saldo encadenado;
--                    1 activacion `activa` de potenciador.
--                    A NO es premium a proposito: e2e SECURITY.no_entitlement_start_denied exige 403 en EX_PREMIUM.
--   B 0b000000-…-0b  fila REAL de cartera con saldo 0 (el servidor confirma 0, decision 4), sin movimientos, sin premium,
--                    sin creditos, sin compras.
--   P 0f000000-…-0f  PREMIUM (SUBSCRIPTION): estado_premium_usuario activa, producto premium_mensual -> plan premium nivel 1,
--                    fin_en dentro de 30 dias. EX_PREMIUM le da SUBSCRIPTION. JWT: .local/tokens.json "P".

set session_replication_role = replica;

insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at)
values ('0f000000-0000-4000-8000-00000000000f', 'p@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;

insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
values ('0f000000-0000-4000-8000-00000000000f', 'Opositor P', 'opositor_p', true, 'activa')
on conflict (usuario_id) do nothing;

-- ---------- catalogo comercial minimo ----------
insert into public.planes_suscripcion (id, codigo, nombre, nivel)
values ('7a000000-0000-4000-8000-000000000001', 'premium', 'Premium', 1)
on conflict (id) do nothing;

insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, plan_id)
values ('7b000000-0000-4000-8000-000000000001', 'premium_mensual', 'suscripcion', 'Premium mensual', 999, '7a000000-0000-4000-8000-000000000001')
on conflict (id) do nothing;

insert into public.servicios (id, codigo, nombre, tipo_servicio)
values ('7c000000-0000-4000-8000-000000000001', 'potenciador', 'Potenciador', 'potenciador'),
       ('7c000000-0000-4000-8000-000000000002', 'protector', 'Protector', 'protector_racha')
on conflict (id) do nothing;

-- ---------- premium (P) ----------
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en)
values ('0f000000-0000-4000-8000-00000000000f', true, 'activa', 'premium_mensual', 'google_play',
        now() - interval '1 day', now() + interval '30 days', now() + interval '30 days')
on conflict (usuario_id) do nothing;

-- Desde aqui los TRIGGERS corren (el libro de cartera y los guardas de pedido son parte de lo que se prueba).
set session_replication_role = origin;

-- ---------- compras (A): una compra pagada de OposiTickets (D7-05 kind=purchase); B y P sin compras ----------
insert into public.compras_usuario (id, usuario_id, proveedor, producto_codigo, tipo_producto, estado, precio_centimos, moneda, creado_en)
values ('7d100000-0000-4000-8000-000000000001', '0a000000-0000-4000-8000-00000000000a', 'google_play', 'pack_50', 'opositickets', 'pagada', 499, 'EUR', now() - interval '5 days')
on conflict (id) do nothing;

-- ---------- cartera y libro (A) ----------
-- Ledger-first (V1): el saldo de A lo escribe SOLO el libro (trigger); aqui no hay ni snapshot ni cadena a mano. El libro asigna seq,
-- saldo_anterior y saldo_resultante. B tiene una fila REAL de cartera con saldo 0 (una cartera vacia si se puede crear; con valor, no).
insert into public.carteras_usuario (usuario_id, saldo_tickets)
values ('0b000000-0000-4000-8000-00000000000b', 0)
on conflict (usuario_id) do nothing;

insert into public.transacciones_cartera (id, usuario_id, tipo, cantidad, descripcion, creado_en)
values ('7d000000-0000-4000-8000-000000000001', '0a000000-0000-4000-8000-00000000000a', 'recarga',        50, 'Recarga local',        now() - interval '5 days'),
       ('7d000000-0000-4000-8000-000000000002', '0a000000-0000-4000-8000-00000000000a', 'reto_diario',     5, 'Reto diario',          now() - interval '4 days'),
       ('7d000000-0000-4000-8000-000000000003', '0a000000-0000-4000-8000-00000000000a', 'regalo',         10, 'Regalo de bienvenida', now() - interval '3 days'),
       ('7d000000-0000-4000-8000-000000000004', '0a000000-0000-4000-8000-00000000000a', 'compra_examen', -20, 'Compra de examen',     now() - interval '2 days'),
       ('7d000000-0000-4000-8000-000000000005', '0a000000-0000-4000-8000-00000000000a', 'potenciador',   -10, 'Potenciador',          now() - interval '1 day')
on conflict (id) do nothing;

insert into public.activaciones_servicio_usuario (id, usuario_id, servicio_id, estado, activa_desde, activa_hasta, usos_disponibles, usos_consumidos)
values ('7e000000-0000-4000-8000-000000000001', '0a000000-0000-4000-8000-00000000000a', '7c000000-0000-4000-8000-000000000001',
        'activa', now() - interval '1 day', now() + interval '6 days', 3, 1)
on conflict (id) do nothing;

-- ---------- creditos de contenido (A) ----------
insert into public.creditos_contenido_usuario (id, usuario_id, tipo_examen, cantidad_total, cantidad_usada, valido_desde, valido_hasta)
values ('7f000000-0000-4000-8000-000000000001', '0a000000-0000-4000-8000-00000000000a', 'razonado', 5, 2, now() - interval '1 day', now() + interval '20 days'),
       ('7f000000-0000-4000-8000-000000000002', '0a000000-0000-4000-8000-00000000000a', 'rafaga',   9, 0, now() - interval '40 days', now() - interval '10 days')
on conflict (id) do nothing;

set session_replication_role = origin;
