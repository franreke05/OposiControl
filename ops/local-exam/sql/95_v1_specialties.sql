-- V1 STEP 1 (LOCAL ONLY). Especialidades extra para los recorridos de especialidad (J6) y el e2e del servidor:
--   e1000000-...-02  ING "Ingles (local)"  ACTIVA   con su propio examen gratuito publicado (EX_ING)
--   e1000000-...-03  OBS "Obsoleta (local)" INACTIVA (una especialidad inactiva no se puede elegir: 422)
-- Idempotente (on conflict do nothing). Sin precios, sin comunidad inventada: el examen EX_ING usa la misma comunidad que los demas (ninguna).
set session_replication_role = replica;

insert into public.especialidades (id, codigo, nombre, slug, activa, posicion) values
  ('e1000000-0000-4000-8000-000000000002', 'ING', 'Ingles (local)', 'ingles-local', true, 20),
  ('e1000000-0000-4000-8000-000000000003', 'OBS', 'Obsoleta (local)', 'obsoleta-local', false, 30)
on conflict (id) do nothing;

insert into public.capacidades_especialidad (especialidad_id) values ('e1000000-0000-4000-8000-000000000002')
on conflict do nothing;

insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas) values
  ('e1000000-0000-4000-8000-0000000000e2', 'LOC-ING', 'e1000000-0000-4000-8000-000000000002', 'Ingles', 'rafaga', 'gratuito', true, now() - interval '1 day', 1)
on conflict (id) do nothing;

insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
values ('f1000000-0000-4000-8000-0000000000e2', 'LOC-ING-1', 'rafaga', 'Enunciado ingles 1', true) on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('01000000-0000-4000-8' || l || '00-0000000000e2')::uuid, 'f1000000-0000-4000-8000-0000000000e2', chr(64 + l), 'Opcion ingles ' || chr(64 + l), l
from generate_series(1, 4) l on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
values ('f1000000-0000-4000-8000-0000000000e2', '01000000-0000-4000-8100-0000000000e2', 'Razonamiento ingles') on conflict do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select 'e1000000-0000-4000-8000-0000000000e2', 'f1000000-0000-4000-8000-0000000000e2', 1, 1
where not exists (select 1 from public.preguntas_examen where examen_id = 'e1000000-0000-4000-8000-0000000000e2');

set session_replication_role = origin;
