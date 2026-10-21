-- V1-DEPLOY-REHEARSAL: semilla SINTETICA (ningun dato de MAIN). Tamano parecido al MAIN conocido (4 perfiles, 1 superadmin,
-- carteras con saldo y libro heredado sin `seq`, una compra pagada y una suscripcion). Sirve para que el ensayo pase por los
-- caminos de DATOS de las migraciones (relleno `legacy_opening` de 20261022100300, indices unicos sobre filas existentes,
-- PREFLIGHT de duplicados) y no solo por el catalogo vacio. Se carga como `postgres` con los triggers apagados (replica),
-- igual que hace scripts/db-pg17-gate.sh con sus filas de carrera.
begin;
set local session_replication_role = replica;

insert into auth.users (id, email, aud, role, email_confirmed_at) values
  ('d0000000-0000-0000-0000-000000000001', 'reh-admin@test.local', 'authenticated', 'authenticated', now() - interval '30 days'),
  ('d0000000-0000-0000-0000-000000000002', 'reh-u2@test.local',    'authenticated', 'authenticated', now() - interval '20 days'),
  ('d0000000-0000-0000-0000-000000000003', 'reh-u3@test.local',    'authenticated', 'authenticated', now() - interval '10 days'),
  ('d0000000-0000-0000-0000-000000000004', 'reh-u4@test.local',    'authenticated', 'authenticated', null);

insert into public.roles_app (id, codigo, nombre) values
  ('d1000000-0000-0000-0000-000000000001', 'superadmin', 'Superadmin'), ('d1000000-0000-0000-0000-000000000002', 'admin', 'Admin'),
  ('d1000000-0000-0000-0000-000000000003', 'moderador', 'Moderador'),   ('d1000000-0000-0000-0000-000000000004', 'usuario', 'Usuario');
insert into public.roles_usuario (usuario_id, rol_id) values ('d0000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001');

insert into public.especialidades (id, codigo, nombre, slug) values ('d2000000-0000-0000-0000-000000000001', 'REH', 'Ensayo', 'ensayo');
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('d3000000-0000-0000-0000-000000000001', 'REH', 'Comunidad ensayo', 'comunidad-ensayo');
insert into public.capacidades_especialidad (especialidad_id) values ('d2000000-0000-0000-0000-000000000001');

insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
  select ('d0000000-0000-0000-0000-00000000000' || n)::uuid, 'reh' || n, 'reh_user_' || n, true, 'activa', 'd2000000-0000-0000-0000-000000000001'
  from generate_series(1, 4) n;

insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas)
  values ('d4000000-0000-0000-0000-000000000001', 'REH-1', 'd2000000-0000-0000-0000-000000000001', 'd3000000-0000-0000-0000-000000000001',
          'Examen de ensayo', 'rafaga', 'gratuito', true, now() - interval '5 days', 3);
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo)
  select ('d5000000-0000-0000-0000-00000000000' || n)::uuid, 'REH-P' || n, 'rafaga', 'Pregunta de ensayo ' || n from generate_series(1, 3) n;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
  select ('d6000000-0000-0000-000' || n || '-00000000000' || o)::uuid, ('d5000000-0000-0000-0000-00000000000' || n)::uuid, chr(64 + o), 'Opcion ' || o, o
  from generate_series(1, 3) n, generate_series(1, 4) o;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
  select ('d5000000-0000-0000-0000-00000000000' || n)::uuid, ('d6000000-0000-0000-000' || n || '-000000000001')::uuid, 'Razonamiento' from generate_series(1, 3) n;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
  select 'd4000000-0000-0000-0000-000000000001', ('d5000000-0000-0000-0000-00000000000' || n)::uuid, n, n from generate_series(1, 3) n;

-- Carteras: u2 saldo sin libro (relleno = apertura de 50); u3 libro heredado que ya cuadra (sin apertura); u4 libro que no cuadra (apertura de 10).
insert into public.carteras_usuario (usuario_id, saldo_tickets) values
  ('d0000000-0000-0000-0000-000000000002', 50), ('d0000000-0000-0000-0000-000000000003', 30), ('d0000000-0000-0000-0000-000000000004', 20);
insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, descripcion, creado_en, idempotency_key) values
  ('d0000000-0000-0000-0000-000000000003', 'regalo',  20, 0,  20, 'heredado', now() - interval '9 days', 'reh-u3-1'),
  ('d0000000-0000-0000-0000-000000000003', 'ajuste',  10, 20, 30, 'heredado', now() - interval '8 days', 'reh-u3-2'),
  ('d0000000-0000-0000-0000-000000000004', 'regalo',  10, 0,  10, 'heredado', now() - interval '7 days', 'reh-u4-1');

insert into public.compras_usuario (usuario_id, proveedor, producto_codigo, tipo_producto, estado, precio_centimos, identificador_transaccion, comprada_en)
  values ('d0000000-0000-0000-0000-000000000002', 'manual', 'free', 'contenido', 'pagada', 0, 'reh-manual-1', now() - interval '6 days');
insert into public.suscripciones (usuario_id, proveedor, producto_codigo, estado, inicio_en, identificador_externo)
  values ('d0000000-0000-0000-0000-000000000003', 'manual', 'free', 'activa', now() - interval '6 days', 'reh-sub-1');
commit;
