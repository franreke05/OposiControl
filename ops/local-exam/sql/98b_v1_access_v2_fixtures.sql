-- V1 Access v2 fixtures for E2/E5 (LOCAL ONLY, TEST VALUES; never a migration, never MAIN). Idempotent.
-- EVERY number is a local test value (the owner sets the real ones in Control): plan benefits, packs, prices. Labelled "(prueba local)".
--   Plans (planes_suscripcion nivel 0..3) + benefits: Gratuito limit 30/24h; Rafagas limit 100/24h; Razonados limit 200/24h; Inmersivo quota 2 immersive exams, no limit.
--   Subscription products with plan_id: local-sub-rafagas / -razonados / -inmersivo.
--   Users: Q6 = Rafagas subscriber (2f..), Q7 = Inmersivo subscriber (3b..). Both specialty ING? no: the default local specialty, balance 0.
--   Exam LOC-DEMO-IMM: immersive, premium, demo available with 5 flagged questions (of 7); LOC-DEMO-2/3 immersive premium (quota candidates).
--   Recarga libre blocks (recarga_base): 100 OT = 1 EUR, 200 = 2, 500 = 5, 1000 = 10 (EUR cents 100 per 100 OT).

insert into public.planes_suscripcion (id, codigo, nombre, nivel, descripcion) values
  ('b1000000-0000-4000-8000-000000000000', 'local-gratuito', 'Gratuito (prueba local)', 0, 'Valor de prueba local'),
  ('b1000000-0000-4000-8000-000000000001', 'local-rafagas', 'Rafagas (prueba local)', 1, 'Valor de prueba local'),
  ('b1000000-0000-4000-8000-000000000002', 'local-razonados', 'Razonados (prueba local)', 2, 'Valor de prueba local'),
  ('b1000000-0000-4000-8000-000000000003', 'local-inmersivo', 'Inmersivo (prueba local)', 3, 'Valor de prueba local')
on conflict (id) do nothing;
insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values
  ('b1000000-0000-4000-8000-000000000000', 'limite_preguntas_24h', 30),
  ('b1000000-0000-4000-8000-000000000001', 'limite_preguntas_24h', 100),
  ('b1000000-0000-4000-8000-000000000002', 'limite_preguntas_24h', 200),
  ('b1000000-0000-4000-8000-000000000003', 'inmersivos_incluidos', 2)
on conflict (plan_id, codigo_beneficio) do nothing;
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, descripcion, precio_centimos, moneda, activo, visible, posicion, plan_id) values
  ('7b400000-0000-4000-8000-000000000001', 'local-sub-rafagas', 'suscripcion', 'Suscripcion Rafagas (prueba local)', 'Valor de prueba local', 299, 'EUR', true, true, 10, 'b1000000-0000-4000-8000-000000000001'),
  ('7b400000-0000-4000-8000-000000000002', 'local-sub-razonados', 'suscripcion', 'Suscripcion Razonados (prueba local)', 'Valor de prueba local', 699, 'EUR', true, true, 11, 'b1000000-0000-4000-8000-000000000002'),
  ('7b400000-0000-4000-8000-000000000003', 'local-sub-inmersivo', 'suscripcion', 'Suscripcion Inmersivo (prueba local)', 'Valor de prueba local', 999, 'EUR', true, true, 12, 'b1000000-0000-4000-8000-000000000003')
on conflict (id) do nothing;
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, descripcion, cantidad_tickets, precio_centimos, moneda, activo, visible, destacado, posicion, metadatos) values
  ('7b400000-0000-4000-8000-000000000101', 'LOCAL_RECARGA_100', 'pack_tickets', 'Recarga 100 OT (prueba local)', 'Bloque de recarga libre', 100, 100, 'EUR', true, true, false, 20, '{"recarga_base": true}'),
  ('7b400000-0000-4000-8000-000000000102', 'LOCAL_RECARGA_200', 'pack_tickets', 'Recarga 200 OT (prueba local)', 'Bloque de recarga libre', 200, 200, 'EUR', true, true, false, 21, '{"recarga_base": true}'),
  ('7b400000-0000-4000-8000-000000000105', 'LOCAL_RECARGA_500', 'pack_tickets', 'Recarga 500 OT (prueba local)', 'Bloque de recarga libre', 500, 500, 'EUR', true, true, false, 22, '{"recarga_base": true}'),
  ('7b400000-0000-4000-8000-000000000110', 'LOCAL_RECARGA_1000', 'pack_tickets', 'Recarga 1000 OT (prueba local)', 'Bloque de recarga libre', 1000, 1000, 'EUR', true, true, false, 23, '{"recarga_base": true}'),
  ('7b400000-0000-4000-8000-000000000113', 'LOCAL_PACK_1300', 'pack_tickets', 'Pack 1300 OT (prueba local)', 'Valor de prueba local', 1300, 1299, 'EUR', true, true, true, 24, '{}')
on conflict (id) do nothing;

-- users Q6 (Rafagas) and Q7 (Inmersivo)
set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at) values
  ('2f000000-0000-4000-8000-00000000002f', 'q6@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
  ('3b000000-0000-4000-8000-00000000003b', 'q7@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;
set session_replication_role = origin;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('2f000000-0000-4000-8000-00000000002f', 'Q6 plan rafagas', 'q6_plan_rafagas', true, 'activa', 'e5000000-0000-4000-8000-000000000001'),
  ('3b000000-0000-4000-8000-00000000003b', 'Q7 plan inmersivo', 'q7_plan_inmersivo', true, 'activa', 'e5000000-0000-4000-8000-000000000001')
on conflict (usuario_id) do nothing;
insert into public.carteras_usuario (usuario_id, saldo_tickets) values ('2f000000-0000-4000-8000-00000000002f', 0), ('3b000000-0000-4000-8000-00000000003b', 0) on conflict (usuario_id) do nothing;
-- the subscription state is written through the projection table (what the store adapter will produce); refreshed on every run so it never expires during tests
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en)
values ('2f000000-0000-4000-8000-00000000002f', true, 'activa', 'local-sub-rafagas', 'manual', now() - interval '5 days', now() + interval '25 days'),
       ('3b000000-0000-4000-8000-00000000003b', true, 'activa', 'local-sub-inmersivo', 'manual', now() - interval '5 days', now() + interval '25 days')
on conflict (usuario_id) do update set es_premium = true, estado_suscripcion = excluded.estado_suscripcion, producto_codigo = excluded.producto_codigo,
  inicio_en = excluded.inicio_en, fin_en = excluded.fin_en;

-- exams: LOC-DEMO-IMM (7 questions, 5 demo-flagged, demo on), LOC-DEMO-2 / LOC-DEMO-3 (quota candidates)
set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values
  ('e3000000-0000-4000-8000-0000000000d1', 'LOC-DEMO-IMM', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Inmersivo con demo (prueba local)', 'inmersivo', 'premium', true, now() - interval '1 day', 7, true, 40, 30, false),
  ('e3000000-0000-4000-8000-0000000000d2', 'LOC-DEMO-2', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Inmersivo 2 (prueba local)', 'inmersivo', 'premium', true, now() - interval '1 day', 1, true, 40, 30, false),
  ('e3000000-0000-4000-8000-0000000000d3', 'LOC-DEMO-3', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Inmersivo 3 (prueba local)', 'inmersivo', 'premium', true, now() - interval '1 day', 1, true, 40, 30, false)
on conflict (id) do nothing;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f3000000-0000-4000-8000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'LOC-DEMO-' || n, 'inmersiva', 'Enunciado demo ' || n, true from generate_series(10, 18) n
on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('03000000-0000-4000-8' || o || '00-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f3000000-0000-4000-8000-0000000000' || lpad(n::text, 2, '0'))::uuid, chr(64 + o), 'Opcion ' || chr(64 + o), o
from generate_series(10, 18) n, generate_series(1, 4) o
on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f3000000-0000-4000-8000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('03000000-0000-4000-8100-0000000000' || lpad(n::text, 2, '0'))::uuid, 'Razonamiento demo'
from generate_series(10, 18) n
on conflict do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_demo)
select 'e3000000-0000-4000-8000-0000000000d1', ('f3000000-0000-4000-8000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 9, n - 9, n <= 14
from generate_series(10, 16) n
where not exists (select 1 from public.preguntas_examen pe where pe.examen_id = 'e3000000-0000-4000-8000-0000000000d1' and pe.pregunta_id = ('f3000000-0000-4000-8000-0000000000' || lpad(n::text, 2, '0'))::uuid);
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select e, p, 1, 1 from (values ('e3000000-0000-4000-8000-0000000000d2'::uuid, 'f3000000-0000-4000-8000-000000000017'::uuid), ('e3000000-0000-4000-8000-0000000000d3'::uuid, 'f3000000-0000-4000-8000-000000000018'::uuid)) v(e, p)
where not exists (select 1 from public.preguntas_examen pe where pe.examen_id = v.e);
set session_replication_role = origin;
update public.examenes set demo_disponible = true where id = 'e3000000-0000-4000-8000-0000000000d1' and not demo_disponible;
