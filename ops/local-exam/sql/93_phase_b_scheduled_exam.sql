-- E2-P03 / PHASE B (LOCAL ONLY). Examen PROGRAMADO: gratuito, activo, publicado_en dentro de 7 dias, 1 pregunta con 4
-- opciones. No debe salir antes de su hora ni por el catalogo canonico (D2-01/D2-02, ya filtrado), ni por las rutas
-- publicas /v1 (Ktor, publishedByNow), ni por PostgREST directo (20261022100100_security_exam_publication_time).
-- Idempotente (on conflict do nothing). EX_PROGRAMADO e0000000-…-09, pregunta f9000000-…-01.

set session_replication_role = replica;

insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas)
values ('e0000000-0000-4000-8000-000000000009', 'LOC-PROGRAMADO', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Programado',
        'rafaga', 'gratuito', true, now() + interval '7 days', 1)
on conflict (id) do nothing;

insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
values ('f9000000-0000-4000-8000-000000000001', 'LOC-9-1', 'rafaga', 'Enunciado programado 9.1', true)
on conflict (id) do nothing;

insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('09000000-0000-4000-8' || l || '00-000000000001')::uuid, 'f9000000-0000-4000-8000-000000000001', chr(64 + l), 'Opcion programada ' || chr(64 + l), l
from generate_series(1, 4) l
on conflict (id) do nothing;

insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
values ('f9000000-0000-4000-8000-000000000001', '09000000-0000-4000-8100-000000000001', 'Razonamiento programado')
on conflict do nothing;

insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select 'e0000000-0000-4000-8000-000000000009', 'f9000000-0000-4000-8000-000000000001', 1, 1
where not exists (select 1 from public.preguntas_examen where examen_id = 'e0000000-0000-4000-8000-000000000009');

set session_replication_role = origin;
