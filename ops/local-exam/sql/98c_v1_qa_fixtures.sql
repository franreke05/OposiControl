-- LOCAL ONLY (never a migration, never MAIN): QA fixtures for the app screens that need DATA (E5-V1-QA-INT08 F4 + stats / ranking in the app). Idempotent; applied after 98b by up.mjs, --converge and --restore-fixtures.
--   1. Renewal dates: Q6 / Q7 are ACTIVE plans that renew (renovacion_en = fin_en): the subscription screen can show the renewal.
--   2. Q8 = CANCELLED-BUT-PAID plan (estado cancelada, fin_en in 12 days, no renewal): proves the "your plan ends on ..." copy.
--   3. Stats: Q7 has 60 first answers over two tagged topics (weak and strong) so Debilidades / Nota media / Evolucion have data; 55 synthetic "QA cohort" users of the same specialty and league give a
--      real ranking (position, top 3 by public handle, place probability above the Control minimum cohort) and a 30-question difficulty sample. All sample data, marked by the qa-coh-* e-mails.
-- 1
update public.estado_premium_usuario set renovacion_en = fin_en where usuario_id in ('2f000000-0000-4000-8000-00000000002f', '3b000000-0000-4000-8000-00000000003b');
-- 2
set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at) values
  ('4d000000-0000-4000-8000-00000000004d', 'q8@exam.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;
set session_replication_role = origin;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('4d000000-0000-4000-8000-00000000004d', 'Q8 plan cancelado', 'q8_plan_cancelado', true, 'activa', 'e5000000-0000-4000-8000-000000000001')
on conflict (usuario_id) do nothing;
insert into public.carteras_usuario (usuario_id, saldo_tickets) values ('4d000000-0000-4000-8000-00000000004d', 0) on conflict (usuario_id) do nothing;
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, proveedor, inicio_en, fin_en, renovacion_en)
values ('4d000000-0000-4000-8000-00000000004d', true, 'cancelada', 'local-sub-rafagas', 'manual', now() - interval '18 days', now() + interval '12 days', null)
on conflict (usuario_id) do update set es_premium = true, estado_suscripcion = 'cancelada', producto_codigo = excluded.producto_codigo, inicio_en = excluded.inicio_en, fin_en = excluded.fin_en, renovacion_en = null;
-- 3: tagged question bank (own questions, in no exam)
insert into public.etiquetas (id, nombre, slug) values
  ('7c000000-0000-4000-8000-0000000000a1', 'QA Tema A (fuerte)', 'qa-tema-a'), ('7c000000-0000-4000-8000-0000000000a2', 'QA Tema B (debil)', 'qa-tema-b')
on conflict (id) do nothing;
set session_replication_role = replica;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('fc000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'QA-' || n, 'rafaga', 'Pregunta de prueba QA ' || n, true from generate_series(1, 60) n on conflict (id) do nothing;
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id)
select ('fc000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, case when n <= 30 then '7c000000-0000-4000-8000-0000000000a1'::uuid else '7c000000-0000-4000-8000-0000000000a2'::uuid end from generate_series(1, 60) n on conflict do nothing;
-- the cohort: 55 users, specialty LOC, league liga-50 with a FULL window, Nota spread 2.0..9.5 (3 public handles at the top)
insert into auth.users (id, email, aud, role, created_at, updated_at)
select ('4e000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'qa-coh-' || n || '@exam.local', 'authenticated', 'authenticated', now() - interval '30 days', now() from generate_series(1, 55) n on conflict (id) do nothing;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id, ranking_publico)
select ('4e000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'QA cohorte ' || n, 'qa_cohorte_' || n, true, 'activa', 'e5000000-0000-4000-8000-000000000001', n >= 53
  from generate_series(1, 55) n on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id, ranking_publico = excluded.ranking_publico, username = excluded.username;
insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual)
select ('4e000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, (select id from public.ligas_nivel where codigo = 'liga-50'), 50, 50, round((2 + 7.5 * n / 55.0)::numeric, 2) from generate_series(1, 55) n
on conflict (usuario_id) do update set liga_nivel_id = excluded.liga_nivel_id, preguntas_validas_ranking = 50, ventana_calculo = 50, nota_media_actual = excluded.nota_media_actual;
-- difficulty sample: 40 cohort users answered the first 30 questions (mixed hit rate and times)
insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms)
select ('4e000000-0000-4000-8000-' || lpad(u::text, 12, '0'))::uuid, ('fc000000-0000-4000-8000-' || lpad(q::text, 12, '0'))::uuid, ((u + q) % 3) <> 0, 2000 + (q % 7) * 1500
  from generate_series(1, 40) u, generate_series(1, 30) q on conflict do nothing;
set session_replication_role = origin;
-- Q7 (Inmersivo subscriber, specialty LOC): 60 first answers through the REAL hook (topic A mostly right, topic B mostly wrong), 4-6 s each
do $$
declare q integer;
begin
  for q in 1..60 loop
    perform private.app_apply_first_answer_stats('3b000000-0000-4000-8000-00000000003b', ('fc000000-0000-4000-8000-' || lpad(q::text, 12, '0'))::uuid,
      case when q <= 30 then (q % 5) <> 0 else (q % 4) = 0 end, 4000 + (q % 5) * 500, null);
  end loop;
end $$;
-- F4 (E5-V1-QA-INT09): a FINISHED attempt of Q7 on a free exam made of these 60 questions, so the review screen has answered questions whose difficulty chip (D3-22) is served. The first answers already exist
-- (guard rows above), so the attempt adds no statistic; it only gives the review its data. LOC-QA-STATS is a free local exam ("QA estadisticas").
set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas)
values ('e3000000-0000-4000-8000-0000000000f1', 'LOC-QA-STATS', 'e5000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'QA estadisticas (datos de prueba)', 'rafaga', 'gratuito', true, now() - interval '30 days', 60)
on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('fd000000-0000-4000-8000-' || lpad((q * 10 + o)::text, 12, '0'))::uuid, ('fc000000-0000-4000-8000-' || lpad(q::text, 12, '0'))::uuid, chr(64 + o), case when o = 1 then 'Opcion correcta' else 'Opcion incorrecta' end, o
  from generate_series(1, 60) q, generate_series(1, 2) o on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('fc000000-0000-4000-8000-' || lpad(q::text, 12, '0'))::uuid, ('fd000000-0000-4000-8000-' || lpad((q * 10 + 1)::text, 12, '0'))::uuid, 'Explicacion de prueba.' from generate_series(1, 60) q on conflict (pregunta_id) do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select 'e3000000-0000-4000-8000-0000000000f1', ('fc000000-0000-4000-8000-' || lpad(q::text, 12, '0'))::uuid, q, q from generate_series(1, 60) q
 where not exists (select 1 from public.preguntas_examen x where x.examen_id = 'e3000000-0000-4000-8000-0000000000f1');
set session_replication_role = origin;
do $$
declare r jsonb; a uuid; q integer;
begin
  if exists (select 1 from public.intentos_examen where usuario_id = '3b000000-0000-4000-8000-00000000003b' and examen_id = 'e3000000-0000-4000-8000-0000000000f1' and estado = 'finalizado') then return; end if;
  r := public.app_start_exam_attempt('3b000000-0000-4000-8000-00000000003b', 'e3000000-0000-4000-8000-0000000000f1');
  a := (r ->> 'intento_id')::uuid;
  for q in 1..60 loop
    perform public.app_record_attempt_answer('3b000000-0000-4000-8000-00000000003b', a, ('fc000000-0000-4000-8000-' || lpad(q::text, 12, '0'))::uuid,
      ('fd000000-0000-4000-8000-' || lpad((q * 10 + case when (case when q <= 30 then (q % 5) <> 0 else (q % 4) = 0 end) then 1 else 2 end)::text, 12, '0'))::uuid, 0, 0, null);
  end loop;
  perform public.app_finish_exam_attempt('3b000000-0000-4000-8000-00000000003b', a);
end $$;
select public.app_job_dificultad(true) is not null;
select public.app_job_ranking(true) is not null;
