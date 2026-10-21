-- EX16 (LOCAL ONLY). Fixtures del motor de examen. Ids fijos (los usan e2e.mjs, E2 y E5). Contenido con
-- session_replication_role = replica, como backend/dbtest: los triggers de AUTORIA de MAIN llaman a ayudantes fuera del
-- subconjunto y la autoria no es lo que se prueba (protegida, solo lectura). Los triggers del runtime corren normal.
--
--   Usuarios     A 0a000000-…-0a (activa)   B 0b000000-…-0b (activa)          (JWT: .local/tokens.json)
--   Especialidad e5000000-…-01 con capacidades abiertas (rafaga, razonado, inmersivo)
--   EX_NORMAL    e0000000-…-01 rafaga gratuito, 3 preguntas (P1..P3), P1 es la del escenario de REVISION X -> Y
--   EX_RAZONADA  e0000000-…-02 razonado gratuito, 2 preguntas razonadas
--   EX_IMAGEN    e0000000-…-03 inmersivo gratuito, 1 pregunta con IMAGEN 1200x800 (ingesta real, recurso 5e…07)
--   EX_AUDIO     e0000000-…-04 inmersivo gratuito, 1 pregunta con AUDIO (envolvente + durationMs de la ingesta real), un recurso INVALIDO (pdf, fuera de V1) y un audio AUSENTE (fila sin objeto en Storage)
--   EX_PREMIUM   e0000000-…-05 rafaga premium (A y B sin premium: 403 EXAM_LOCKED)
--   EX_GRANDE    e0000000-…-06 rafaga gratuito, 200 preguntas (rendimiento / paginas)
--   EX_VIDEO     e0000000-…-07 inmersivo gratuito, 1 pregunta con VIDEO mp4 real
--   EX_LENTO     e0000000-…-08 inmersivo gratuito, 1 pregunta con AUDIO de 30 s servido a 32 KB/s (nginx.conf)
-- Imagen, audio, video y audio lento los crea la INGESTA REAL (AdminV1Gateway.addRecursoArchivo) desde up.mjs: durationMs y
-- envelope del audio los calcula la ingesta, no este fichero.
-- Revision X = este contenido. Revision Y = sql/91_publish_revision_y.sql (edicion simulada, solo local).

set session_replication_role = replica;

insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at)
values ('0a000000-0000-4000-8000-00000000000a', 'a@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()),
       ('0b000000-0000-4000-8000-00000000000b', 'b@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;

insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
values ('0a000000-0000-4000-8000-00000000000a', 'Opositor A', 'opositor_a', true, 'activa'),
       ('0b000000-0000-4000-8000-00000000000b', 'Opositor B', 'opositor_b', true, 'activa')
on conflict (usuario_id) do nothing;

insert into public.especialidades (id, codigo, nombre, slug) values ('e5000000-0000-4000-8000-000000000001', 'LOC', 'Local', 'local')
on conflict (id) do nothing;
insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos)
values ('e5000000-0000-4000-8000-000000000001', true, true, true, true) on conflict (especialidad_id) do nothing;

insert into public.etiquetas (id, nombre, slug) values
  ('7a000000-0000-4000-8000-000000000001', 'Tema 1', 'loc-tema-1'),
  ('7a000000-0000-4000-8000-000000000002', 'Tema 2', 'loc-tema-2')
on conflict (id) do nothing;

-- V1: toda examen lleva comunidad (columna NOT NULL). Comunidad de PRUEBA local; nunca un valor por defecto de producto.
insert into public.comunidades_autonomas (id, codigo, nombre, slug, activa, posicion) values
  ('c1000000-0000-4000-8000-000000000001', 'ES-MD', 'Comunidad de Madrid', 'comunidad-de-madrid', true, 13),
  ('c1000000-0000-4000-8000-000000000002', 'ES-AN', 'Andalucía', 'andalucia', true, 1)
on conflict (id) do nothing;

insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas) values
  ('e0000000-0000-4000-8000-000000000001', 'LOC-NORMAL',   'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Normal',    'rafaga',    'gratuito', true, now() - interval '1 day', 3),
  ('e0000000-0000-4000-8000-000000000002', 'LOC-RAZONADA', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Razonada',  'razonado',  'gratuito', true, now() - interval '1 day', 2),
  ('e0000000-0000-4000-8000-000000000003', 'LOC-IMAGEN',   'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Imagen',    'inmersivo', 'gratuito', true, now() - interval '1 day', 1),
  ('e0000000-0000-4000-8000-000000000004', 'LOC-AUDIO',    'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Audio',     'inmersivo', 'gratuito', true, now() - interval '1 day', 1),
  ('e0000000-0000-4000-8000-000000000005', 'LOC-PREMIUM',  'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Premium',   'rafaga',    'premium',  true, now() - interval '1 day', 1),
  ('e0000000-0000-4000-8000-000000000006', 'LOC-GRANDE',   'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Grande',    'rafaga',    'gratuito', true, now() - interval '1 day', 200),
  ('e0000000-0000-4000-8000-000000000007', 'LOC-VIDEO',    'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Video',     'inmersivo', 'gratuito', true, now() - interval '1 day', 1),
  ('e0000000-0000-4000-8000-000000000008', 'LOC-LENTO',    'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Lento',     'inmersivo', 'gratuito', true, now() - interval '1 day', 1)
on conflict (id) do nothing;

-- Preguntas: q(examen, n) = 'f<examen>000000-0000-4000-8000-<n 12 digitos>'
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f' || e || '000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'LOC-' || e || '-' || n, t, 'Enunciado X ' || e || '.' || n, true
from (values (1, 3, 'rafaga'), (2, 2, 'razonada'), (3, 1, 'inmersiva'), (4, 1, 'inmersiva'), (5, 1, 'rafaga'), (6, 200, 'rafaga'), (7, 1, 'inmersiva'), (8, 1, 'inmersiva')) x(e, total, t),
     generate_series(1, x.total) n
on conflict (id) do nothing;

-- 4 opciones por pregunta; la correcta es la A. o(examen, n, letra) = '0<examen>000000-0000-4000-8<letra>00-<n 12>'
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('0' || e || '000000-0000-4000-8' || l || '00-' || lpad(n::text, 12, '0'))::uuid,
       ('f' || e || '000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, chr(64 + l), 'Opcion ' || chr(64 + l) || ' X', l
from (values (1, 3), (2, 2), (3, 1), (4, 1), (5, 1), (6, 200), (7, 1), (8, 1)) x(e, total), generate_series(1, x.total) n, generate_series(1, 4) l
on conflict (id) do nothing;

insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f' || e || '000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, ('0' || e || '000000-0000-4000-8100-' || lpad(n::text, 12, '0'))::uuid, 'Razonamiento X'
from (values (1, 3), (2, 2), (3, 1), (4, 1), (5, 1), (6, 200), (7, 1), (8, 1)) x(e, total), generate_series(1, x.total) n
on conflict do nothing;

insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select ('e0000000-0000-4000-8000-00000000000' || e)::uuid, ('f' || e || '000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, n, n
from (values (1, 3), (2, 2), (3, 1), (4, 1), (5, 1), (6, 200), (7, 1), (8, 1)) x(e, total), generate_series(1, x.total) n
where not exists (select 1 from public.preguntas_examen pe where pe.examen_id = ('e0000000-0000-4000-8000-00000000000' || e)::uuid);

insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id)
select ('f1000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, '7a000000-0000-4000-8000-000000000001' from generate_series(1, 3) n
on conflict do nothing;

-- Recursos V1 (el objeto lo sube up.mjs; el audio AUSENTE no se sube nunca).
insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, bucket_storage, ruta_storage, posicion, metadatos) values
  ('5e000000-0000-4000-8000-000000000003', 'f4000000-0000-4000-8000-000000000001', 'LOC-R3', 'pdf', 'Fuera de V1', 'recursos-pregunta',
   'preguntas/f4000000-0000-4000-8000-000000000001/5e000000-0000-4000-8000-000000000003.pdf', 2, '{"mime":"application/pdf","bytes":10}'),
  ('5e000000-0000-4000-8000-000000000004', 'f4000000-0000-4000-8000-000000000001', 'LOC-R4', 'audio', 'Ausente', 'recursos-pregunta',
   'preguntas/f4000000-0000-4000-8000-000000000001/5e000000-0000-4000-8000-000000000004.mp3', 3, '{"mime":"audio/mpeg","bytes":999}')
on conflict (id) do nothing;

set session_replication_role = origin;
