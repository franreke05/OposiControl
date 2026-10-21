-- V1 economy fixtures for E5 (LOCAL ONLY, TEST VALUES; never a migration, never MAIN). Idempotent; re-run to refresh the time-relative rows.
--
-- EVERY number below is a LOCAL TEST VALUE so that journeys have something to run against. NONE of them is a product decision:
-- the owner has not set exam OT prices, pack sizes or money prices, or the Premium plan (BLOCKED_BY_PRODUCT_DECISION, see
-- E1-V1-ECONOMY.md). The local packs are named "(prueba local)" so no screenshot can be mistaken for a real price list.
--
-- Users (JWT in .local/tokens.json under the key shown). Balances are written ONLY through the ledger (ledger-first); nothing here touches the snapshot.
--   Q1 2a..2a  balance 200            -> above every price (redeem succeeds)
--   Q2 2b..2b  balance 29             -> ONE OT short for the 30 OT exam (INSUFFICIENT_BALANCE details.balance=29, price=30)
--   Q2Z 2c..2c balance 0 (real row)   -> the zero variant
--   Q3 2d..2d  balance 30 (50 - 2x10) -> an EXPIRED entitlement (LOC-OT-EXPIRED) and one that expires in about 1 hour (LOC-OT-EXPIRING; refreshed on every run)
--   Q4 2e..2e  balance 25             -> known balance for the payment journeys
--   Q5         created by the app through register (no fixture)
--
-- Exams (all published, all with a community; tipo_acceso premium + comprable_con_tickets):
--   e3..30  LOC-OT-IMM30    inmersivo, 30 OT, 30 days, community Andalucia (readable name with accent)
--   e3..01  LOC-OT-1        rafaga,     1 OT, 30 days, community Comunidad de Madrid
--   e3..10  LOC-OT-EXPIRED  rafaga,    10 OT, 30 days (Q3 holds an expired entitlement)
--   e3..11  LOC-OT-EXPIRING rafaga,    10 OT, 30 days (Q3 holds an entitlement that ends in 1 hour)
--   e3..05  LOC-OT-ING      rafaga,     5 OT, 30 days, second specialty (ING) and its own community
-- Packs (pack_tickets, TEST values): LOCAL_PACK_10 = 10 OT for 99 EUR cents, LOCAL_PACK_50 = 50 OT for 449 EUR cents.

set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at)
values ('2a000000-0000-4000-8000-00000000002a', 'q1@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
       ('2b000000-0000-4000-8000-00000000002b', 'q2@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
       ('2c000000-0000-4000-8000-00000000002c', 'q2z@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
       ('2d000000-0000-4000-8000-00000000002d', 'q3@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
       ('2e000000-0000-4000-8000-00000000002e', 'q4@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;
set session_replication_role = origin;

-- Triggers on: the profile creates the empty wallet row, exactly like production.
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
values ('2a000000-0000-4000-8000-00000000002a', 'Q1 saldo alto', 'q1_saldo_alto', true, 'activa', 'e5000000-0000-4000-8000-000000000001'),
       ('2b000000-0000-4000-8000-00000000002b', 'Q2 falta un OT', 'q2_falta_un_ot', true, 'activa', 'e5000000-0000-4000-8000-000000000001'),
       ('2c000000-0000-4000-8000-00000000002c', 'Q2Z saldo cero', 'q2z_saldo_cero', true, 'activa', 'e5000000-0000-4000-8000-000000000001'),
       ('2d000000-0000-4000-8000-00000000002d', 'Q3 derechos', 'q3_derechos', true, 'activa', 'e5000000-0000-4000-8000-000000000001'),
       ('2e000000-0000-4000-8000-00000000002e', 'Q4 pagos', 'q4_pagos', true, 'activa', 'e5000000-0000-4000-8000-000000000001')
on conflict (usuario_id) do nothing;

insert into public.carteras_usuario (usuario_id, saldo_tickets)
select u, 0 from (values ('2a000000-0000-4000-8000-00000000002a'::uuid), ('2b000000-0000-4000-8000-00000000002b'::uuid), ('2c000000-0000-4000-8000-00000000002c'::uuid),
                         ('2d000000-0000-4000-8000-00000000002d'::uuid), ('2e000000-0000-4000-8000-00000000002e'::uuid)) v(u)
on conflict (usuario_id) do nothing;

-- ---------- balances, through the ledger only ----------
insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, idempotency_key, metadatos)
values ('2a000000-0000-4000-8000-00000000002a', 'recarga', 200, 'Saldo de prueba E5', 'fixture:q1:init', '{"op":"fixture"}'),
       ('2b000000-0000-4000-8000-00000000002b', 'recarga', 29,  'Saldo de prueba E5', 'fixture:q2:init', '{"op":"fixture"}'),
       ('2d000000-0000-4000-8000-00000000002d', 'recarga', 50,  'Saldo de prueba E5', 'fixture:q3:init', '{"op":"fixture"}'),
       ('2e000000-0000-4000-8000-00000000002e', 'recarga', 25,  'Saldo de prueba E5', 'fixture:q4:init', '{"op":"fixture"}')
on conflict (usuario_id, idempotency_key) do nothing;

-- ---------- exams (TEST prices) ----------
set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas,
                             comprable_con_tickets, precio_tickets, dias_acceso_compra) values
  ('e3000000-0000-4000-8000-000000000030', 'LOC-OT-IMM30',    'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000002', 'Inmersivo de canje (prueba local)', 'inmersivo', 'premium', true, now() - interval '1 day', 1, true, 30, 30),
  ('e3000000-0000-4000-8000-000000000001', 'LOC-OT-1',        'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Examen de 1 OT (prueba local)',   'rafaga',    'premium', true, now() - interval '1 day', 1, true, 1,  30),
  ('e3000000-0000-4000-8000-000000000010', 'LOC-OT-EXPIRED',  'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Derecho caducado (prueba local)',  'rafaga',    'premium', true, now() - interval '1 day', 1, true, 10, 30),
  ('e3000000-0000-4000-8000-000000000011', 'LOC-OT-EXPIRING', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Derecho a punto de caducar (prueba local)', 'rafaga', 'premium', true, now() - interval '1 day', 1, true, 10, 30),
  ('e3000000-0000-4000-8000-000000000005', 'LOC-OT-ING',      'e1000000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000002', 'Ingles de pago (prueba local)',    'rafaga',    'premium', true, now() - interval '1 day', 1, true, 5,  30)
on conflict (id) do nothing;

-- one question per exam; q(n) = 'f3000000-0000-4000-8000-<n>', o(n, l) = '03000000-0000-4000-8<l>00-<n>'
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f3000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'LOC-OT-' || n, t, 'Enunciado de canje ' || n, true
from (values (30, 'inmersiva'), (1, 'rafaga'), (10, 'rafaga'), (11, 'rafaga'), (5, 'rafaga')) x(n, t)
on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('03000000-0000-4000-8' || l || '00-' || lpad(n::text, 12, '0'))::uuid, ('f3000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, chr(64 + l), 'Opcion ' || chr(64 + l), l
from (values (30), (1), (10), (11), (5)) x(n), generate_series(1, 4) l
on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f3000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, ('03000000-0000-4000-8100-' || lpad(n::text, 12, '0'))::uuid, 'Razonamiento de canje'
from (values (30), (1), (10), (11), (5)) x(n)
on conflict do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select ('e3000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, ('f3000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 1, 1
from (values (30), (1), (10), (11), (5)) x(n)
where not exists (select 1 from public.preguntas_examen pe where pe.examen_id = ('e3000000-0000-4000-8000-' || lpad(x.n::text, 12, '0'))::uuid);
set session_replication_role = origin;

-- ---------- packs (TEST values; the owner has not set any) ----------
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, descripcion, cantidad_tickets, precio_centimos, moneda, activo, visible, destacado, posicion)
values ('7b300000-0000-4000-8000-000000000010', 'LOCAL_PACK_10', 'pack_tickets', 'Pack 10 OT (prueba local)', 'Valor de prueba local, no es un precio de producto.', 10, 99,  'EUR', true, true, false, 1),
       ('7b300000-0000-4000-8000-000000000050', 'LOCAL_PACK_50', 'pack_tickets', 'Pack 50 OT (prueba local)', 'Valor de prueba local, no es un precio de producto.', 50, 449, 'EUR', true, true, true,  2)
on conflict (id) do nothing;

-- ---------- Q3 entitlements: ledger debit first, then the entitlement that points at it ----------
-- One statement per ledger row of the SAME wallet: the snapshot trigger runs after the statement, so two rows of one wallet in one INSERT fail closed (WALLET_INCONSISTENT).
insert into public.transacciones_cartera (id, usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
select id::uuid, usuario_id::uuid, tipo, cantidad::int, descripcion, referencia_tipo, referencia_id::uuid, idempotency_key, metadatos::jsonb from (values ('7d300000-0000-4000-8000-000000000010', '2d000000-0000-4000-8000-00000000002d', 'compra_examen', -10, 'Acceso a examen', 'examen', 'e3000000-0000-4000-8000-000000000010', 'fixture:q3:expired', '{"op":"fixture"}')) v(id, usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
 where not exists (select 1 from public.transacciones_cartera t where t.usuario_id = '2d000000-0000-4000-8000-00000000002d' and t.idempotency_key = 'fixture:q3:expired');
insert into public.transacciones_cartera (id, usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
select id::uuid, usuario_id::uuid, tipo, cantidad::int, descripcion, referencia_tipo, referencia_id::uuid, idempotency_key, metadatos::jsonb from (values ('7d300000-0000-4000-8000-000000000011', '2d000000-0000-4000-8000-00000000002d', 'compra_examen', -10, 'Acceso a examen', 'examen', 'e3000000-0000-4000-8000-000000000011', 'fixture:q3:expiring', '{"op":"fixture"}')) v(id, usuario_id, tipo, cantidad, descripcion, referencia_tipo, referencia_id, idempotency_key, metadatos)
 where not exists (select 1 from public.transacciones_cartera t where t.usuario_id = '2d000000-0000-4000-8000-00000000002d' and t.idempotency_key = 'fixture:q3:expiring');

insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta, precio_pagado, moneda, transaccion_id, comprado_en)
values ('2d000000-0000-4000-8000-00000000002d', 'e3000000-0000-4000-8000-000000000010', 'compra', now() - interval '40 days', now() - interval '10 days', 10, 'OPOSITICKETS', '7d300000-0000-4000-8000-000000000010', now() - interval '40 days'),
       ('2d000000-0000-4000-8000-00000000002d', 'e3000000-0000-4000-8000-000000000011', 'compra', now() - interval '29 days', now() + interval '1 hour', 10, 'OPOSITICKETS', '7d300000-0000-4000-8000-000000000011', now() - interval '29 days')
on conflict (usuario_id, examen_id) where motivo = 'compra'
do update set valido_desde = excluded.valido_desde, valido_hasta = excluded.valido_hasta;

-- Specialty is an access gate and is set ONCE (owner 2026-10-16): the Q users hold the default local specialty (profiles created by an earlier run have none yet; NULL -> value is allowed).
update public.perfiles_usuario set especialidad_principal_id = 'e5000000-0000-4000-8000-000000000001'
 where usuario_id in ('2a000000-0000-4000-8000-00000000002a', '2b000000-0000-4000-8000-00000000002b', '2c000000-0000-4000-8000-00000000002c',
                      '2d000000-0000-4000-8000-00000000002d', '2e000000-0000-4000-8000-00000000002e') and especialidad_principal_id is null;

-- A user without a specialty is denied everything (owner 2026-10-16, E2-V1-E06). Local fixture profiles that predate the rule (A, B, P, X*, E2 harness users...) get the default local
-- specialty; NULL -> value is allowed by the set-once trigger. Users created through register carry the specialty they chose.
update public.perfiles_usuario set especialidad_principal_id = 'e5000000-0000-4000-8000-000000000001' where especialidad_principal_id is null;
