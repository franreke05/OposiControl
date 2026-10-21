-- EX12 (LOCAL ONLY). Volumen sintetico + EXPLAIN (ANALYZE, BUFFERS) de las lecturas y comandos calientes del motor.
-- 2 000 intentos x 50 preguntas de EX_GRANDE (100 000 preguntas_intento + 100 000 respuestas) repartidos en 200 usuarios.
-- Idempotente: solo siembra si no hay volumen. Ejecutar: docker compose ... exec -T db psql -U postgres -f - < este fichero
\set ON_ERROR_STOP 1
set session_replication_role = replica;
do $$
begin
  if (select count(*) from public.intentos_examen where metadatos ? 'volumen_local') = 0 then
    insert into auth.users (id, email, aud, role, created_at, updated_at)
    select ('0c000000-0000-4000-8000-' || lpad(u::text, 12, '0'))::uuid, 'v' || u || '@exam.local', 'authenticated', 'authenticated', now(), now()
    from generate_series(1, 200) u on conflict do nothing;
    insert into public.intentos_examen (id, usuario_id, examen_id, estado, total_preguntas, origen_intento, creado_por_comando, tipo_examen_snapshot, metadatos, iniciado_en)
    select ('0d000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid, ('0c000000-0000-4000-8000-' || lpad((i % 200 + 1)::text, 12, '0'))::uuid,
           'e0000000-0000-4000-8000-000000000006', case when i <= 200 and i % 10 = 0 then 'en_progreso' else 'finalizado' end, 50, 'examen', 'app_start_exam_attempt', 'rafaga',
           '{"volumen_local":true}'::jsonb, now() - (i || ' minutes')::interval
    from generate_series(1, 2000) i;
  end if;
end $$;
-- una sola revision por pregunta (reutilizada por todos los intentos, como en produccion)
insert into public.revisiones_pregunta (pregunta_id, hash_contenido, contenido, solucion)
select p.id, encode(sha256(convert_to(private.app_revision_pregunta_contenido(p.id)::text || '|' || coalesce(private.app_revision_pregunta_solucion(p.id)::text, 'null'), 'UTF8')), 'hex'),
       private.app_revision_pregunta_contenido(p.id), private.app_revision_pregunta_solucion(p.id)
from public.preguntas p where p.id::text like 'f6000000-%'
on conflict do nothing;
insert into public.preguntas_intento (intento_id, pregunta_id, posicion, revision_pregunta_id, estado_final)
select ie.id, r.pregunta_id, n, r.id, case when ie.estado = 'finalizado' then 'CORRECT' end
from public.intentos_examen ie
cross join generate_series(1, 50) n
join lateral (select r.id, r.pregunta_id from public.revisiones_pregunta r
              where r.pregunta_id = ('f6000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid order by r.creada_en limit 1) r on true
where ie.metadatos ? 'volumen_local' and not exists (select 1 from public.preguntas_intento x where x.intento_id = ie.id);
insert into public.respuestas_intento (intento_id, pregunta_id, usuario_id, examen_id, opcion_seleccionada_id, es_correcta, opcion_correcta_id, revision, respondida_en, actualizada_en, tiempo_respuesta_ms)
select pi.intento_id, pi.pregunta_id, ie.usuario_id, ie.examen_id,
       ('06000000-0000-4000-8100-' || lpad(pi.posicion::text, 12, '0'))::uuid, true, ('06000000-0000-4000-8100-' || lpad(pi.posicion::text, 12, '0'))::uuid, 1, now(), now(), 1000
from public.preguntas_intento pi join public.intentos_examen ie on ie.id = pi.intento_id
where ie.metadatos ? 'volumen_local' and not exists (select 1 from public.respuestas_intento x where x.intento_id = pi.intento_id and x.pregunta_id = pi.pregunta_id);
set session_replication_role = origin;
analyze public.intentos_examen; analyze public.preguntas_intento; analyze public.respuestas_intento; analyze public.revisiones_pregunta; analyze public.preguntas_examen;

\echo '=== Q1 attempt read (D2-07/08/12/65, every read)'
explain (analyze, buffers, costs off) select id, estado from public.intentos_examen
 where id = '0d000000-0000-4000-8000-000000001001' and usuario_id = '0c000000-0000-4000-8000-000000000002' and origen_intento = 'examen' and creado_por_comando is not null limit 1;
\echo '=== Q2 D2-08 page by revision (posicion keyset + revision embed)'
explain (analyze, buffers, costs off) select pi.posicion, pi.pregunta_id, pi.estado_final, pi.revision_pregunta_id, r.contenido
 from public.preguntas_intento pi left join lateral (select r.contenido from public.revisiones_pregunta r where r.id = pi.revision_pregunta_id) r on true
 where pi.intento_id = '0d000000-0000-4000-8000-000000001001' and pi.posicion > 20 order by pi.posicion limit 21;
\echo '=== Q3 own answers of a page'
explain (analyze, buffers, costs off) select pregunta_id, opcion_seleccionada_id, es_correcta from public.respuestas_intento
 where intento_id = '0d000000-0000-4000-8000-000000001001' and pregunta_id in ('f6000000-0000-4000-8000-000000000021','f6000000-0000-4000-8000-000000000022') order by pregunta_id limit 201;
\echo '=== Q4 annulled now'
explain (analyze, buffers, costs off) select pregunta_id from public.preguntas_examen where examen_id = 'e0000000-0000-4000-8000-000000000006' and anulada = true order by pregunta_id limit 201;
\echo '=== Q5 D2-66 resource lookup (jsonb containment over the attempt revisions)'
explain (analyze, buffers, costs off) select r.contenido -> 'recursos' from public.preguntas_intento pi join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id
 where pi.intento_id = '0d000000-0000-4000-8000-000000001001' and r.contenido @> '{"recursos":[{"id":"5e000000-0000-4000-8000-000000000001"}]}' limit 1;
\echo '=== Q6 start freeze: revision reuse lookup'
explain (analyze, buffers, costs off) select id from public.revisiones_pregunta where pregunta_id = 'f6000000-0000-4000-8000-000000000007' and hash_contenido = repeat('a', 64);
\echo '=== Q7 answer RPC (single command, end to end)'
explain (analyze, buffers, costs off) select public.app_record_attempt_answer(ie.usuario_id, ie.id, 'f6000000-0000-4000-8000-000000000001', null, 10, 1, null)
 from public.intentos_examen ie where ie.metadatos ? 'volumen_local' and ie.estado = 'en_progreso' limit 1;
\echo '=== Q8 revision delete path (FK from preguntas_intento, partial index)'
explain (costs off) select 1 from public.preguntas_intento where revision_pregunta_id = '00000000-0000-4000-8000-000000000000';
\echo '=== sizes'
select 'revisiones_pregunta' t, count(*), pg_size_pretty(pg_total_relation_size('public.revisiones_pregunta')) from public.revisiones_pregunta
union all select 'preguntas_intento', count(*), pg_size_pretty(pg_total_relation_size('public.preguntas_intento')) from public.preguntas_intento
union all select 'respuestas_intento', count(*), pg_size_pretty(pg_total_relation_size('public.respuestas_intento')) from public.respuestas_intento;
