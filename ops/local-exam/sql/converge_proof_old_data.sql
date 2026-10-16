-- Data for the converge PROOF (disposable container only), written through the OLD function definitions (before refund P2 and saved items).
insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-0000-0000-0000000000d1','cp1@test.local','authenticated','authenticated'),
  ('a0000000-0000-0000-0000-0000000000d2','cp2@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000d1','CPX','CPX','cpx',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000d1','CPC','Com CP','com-cp');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('a0000000-0000-0000-0000-0000000000d1','cp1','cp1',true,'activa','e1000000-0000-0000-0000-0000000000d1'),
  ('a0000000-0000-0000-0000-0000000000d2','cp2','cp2',true,'activa','e1000000-0000-0000-0000-0000000000d1')
  on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values
  ('e5000000-0000-0000-0000-0000000000d1','CP-PAID','e1000000-0000-0000-0000-0000000000d1','c1000000-0000-0000-0000-0000000000d1','Pago','inmersivo','premium',true, now() - interval '1 day', true, 30, 30, false),
  ('e5000000-0000-0000-0000-0000000000d2','CP-FREE','e1000000-0000-0000-0000-0000000000d1','c1000000-0000-0000-0000-0000000000d1','Gratis','rafaga','gratuito',true, now() - interval '1 day', false, null, 30, false),
  ('e5000000-0000-0000-0000-0000000000d3','CP-GIFT','e1000000-0000-0000-0000-0000000000d1','c1000000-0000-0000-0000-0000000000d1','Regalo','rafaga','premium',true, now() - interval '1 day', false, null, 30, false);
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('f5000000-0000-0000-0000-0000000000d1','CP-Q1','rafaga','Pregunta gratis', true);
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('e5000000-0000-0000-0000-0000000000d2','f5000000-0000-0000-0000-0000000000d1',1,1);
set session_replication_role = origin;
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible) values
  ('b0000000-0000-0000-0000-0000000000d1','pack-100','pack_tickets','Pack 100',499,'EUR',100,true,true);
-- d1: a paid order (100 OT), an exam bought with 30 of them, a gift; d2: a pending order
do $$
declare o record;
begin
  select * into o from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000d1','pack-100','google_play','proof-paid');
  perform * from public.app_credit_payment_order('google_play', 'ev-proof-paid', o.orden_id, 'tx-proof-paid', 499, 'EUR', 'pack-100');
  perform * from public.app_redeem_exam_access('a0000000-0000-0000-0000-0000000000d1','e5000000-0000-0000-0000-0000000000d1','k-proof-buy', 30);
  perform * from public.app_create_payment_order('a0000000-0000-0000-0000-0000000000d2','pack-100','google_play','proof-pending');
  perform * from public.admin_grant_exam_access('a0000000-0000-0000-0000-0000000000d2','e5000000-0000-0000-0000-0000000000d3','admin');
end $$;
