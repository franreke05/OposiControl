#!/usr/bin/env node
// J19 (E1, W3): stats over HTTP. Needs STACK_LOCK, the app Ktor (:8089) and `up.mjs --converge` (migration 20261022101600 + fixtures 98b with the league tiers).
// It creates its own exam (STAT-1, 8 questions, 2 tags) for the free user Q1 and resets Q1's stats rows first (the ledger of first answers is the exactly-once guard, so a re-run must clear it).
//   node ops/local-exam/stats-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('stats-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const Q1 = '2a000000-0000-4000-8000-00000000002a';
const ESP = 'e5000000-0000-4000-8000-000000000001';
const EX = 'eb000000-0000-4000-8000-000000000001';
const qid = (p) => `fb000000-0000-4000-0001-${String(p).padStart(12, '0')}`;
const oid = (p, o) => `0b000000-0000-4000-0001-${String(p * 10 + o).padStart(12, '0')}`;
const TAG_A = '7b900000-0000-4000-8000-000000000001', TAG_B = '7b900000-0000-4000-8000-000000000002';

const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (method, p, body) => {
  const headers = { Authorization: `Bearer ${tokens.Q1}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers['Idempotency-Key'] = crypto.randomUUID();
  const r = await fetch(APP + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* empty body */ }
  return { status: r.status, json, text, headers: r.headers };
};
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };

// ---- fixtures + reset (idempotent)
let fx = `set session_replication_role = replica;
delete from public.intentos_examen where usuario_id = '${Q1}' and examen_id = '${EX}';
delete from public.primeras_respuestas_usuario where usuario_id = '${Q1}';
delete from public.estadisticas_usuario where usuario_id = '${Q1}';
delete from public.estadisticas_usuario_etiqueta where usuario_id = '${Q1}';
delete from public.estado_competitivo_usuario where usuario_id = '${Q1}';
delete from public.estadisticas_globales_pregunta where pregunta_id::text like 'fb000000-%';
insert into public.etiquetas (id, nombre, slug) values ('${TAG_A}', 'Stats A', 'stats-a'), ('${TAG_B}', 'Stats B', 'stats-b') on conflict (id) do nothing;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
  select '${EX}', 'STAT-1', '${ESP}', (select id from public.comunidades_autonomas order by id limit 1), 'Estadisticas STAT-1', 'rafaga', 'gratuito', true, now() - interval '1 day'
  on conflict (id) do update set activo = true, publicado_en = excluded.publicado_en;
delete from public.preguntas_examen where examen_id = '${EX}';
`;
for (let p = 1; p <= 8; p++) {
  fx += `insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${qid(p)}', 'STAT-1-${p}', 'rafaga', 'Pregunta estadisticas ${p}', true) on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${oid(p, 1)}', '${qid(p)}', 'A', 'Correcta', 1), ('${oid(p, 2)}', '${qid(p)}', 'B', 'Otra', 2) on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${qid(p)}', '${oid(p, 1)}', 'x') on conflict (pregunta_id) do nothing;
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('${qid(p)}', '${p <= 4 ? TAG_A : TAG_B}') on conflict do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('${EX}', '${qid(p)}', ${p}, ${p});
`;
}
fx += `set session_replication_role = origin;
update public.configuracion_v1 set valor_integer = 4 where clave = 'evolucion_cada_preguntas';
update public.configuracion_v1 set valor_integer = 1 where clave = 'debilidad_min_respuestas';
update public.configuracion_v1 set valor_integer = 3 where clave = 'dificultad_min_respuestas';
`;
sql(fx);

try {
  const empty = await http('GET', '/api/v1/app/estadisticas/resumen');
  check('J19.summary_of_a_new_user_is_empty', empty.status === 200 && empty.json?.preguntasValidas === 0 && empty.json?.notaMedia?.provisional === true && empty.json?.notaMedia?.valor === undefined
    && empty.headers.get('cache-control') === 'no-store', `${empty.status} ${empty.text.slice(0, 160)}`);

  const st = await http('POST', `/api/v1/app/examenes/${EX}/intentos`, {});
  const att = st.json?.intento?.id;
  check('J19.start', st.status === 201 && !!att, `${st.status}`);
  const answer = (p, ok) => http('PUT', `/api/v1/app/intentos/${att}/respuestas/${qid(p)}`, { opcionSeleccionadaId: oid(p, ok ? 1 : 2), tiempoRespuestaMs: 1200, baseRevision: 0 });
  const plan = [true, false, true, true, true, false];
  for (let p = 1; p <= plan.length; p++) await answer(p, plan[p - 1]);
  // a replay of the same answer never counts twice
  const replay = await answer(1, true);
  const s = (await http('GET', '/api/v1/app/estadisticas/resumen')).json;
  check('J19.replay_never_counts_twice', replay.status === 200 && s?.preguntasValidas === 6 && s?.totales?.aciertos === 4 && s?.totales?.fallos === 2, JSON.stringify(s?.totales));
  check('J19.nota_media_is_provisional_over_the_default_window', s?.notaMedia?.valor === 6.67 && s?.notaMedia?.ventana === 50 && s?.notaMedia?.respondidas === 6 && s?.notaMedia?.provisional === true, JSON.stringify(s?.notaMedia));
  check('J19.evolution_reference_after_the_first_step', s?.evolucion?.cada === 4 && s?.evolucion?.referencia === 7.5 && s?.evolucion?.progreso === 2 && s?.evolucion?.valor === undefined, JSON.stringify(s?.evolucion));
  check('J19.streak_is_part_of_the_summary', typeof s?.racha?.actual === 'number' && typeof s?.racha?.maxima === 'number');

  const weak = (await http('GET', '/api/v1/app/estadisticas/debilidades')).json;
  check('J19.weaknesses_total_weakest_first', weak?.alcance === 'TOTAL' && weak?.items?.[0]?.nombre === 'Stats A' && weak?.items?.[0]?.porcentajeAcierto === 50 && weak?.items?.[0]?.nivelDominio === 'medio' && weak?.items?.length === 2, JSON.stringify(weak));
  const fin = await http('POST', `/api/v1/app/intentos/${att}/finalizar`);
  const perExam = (await http('GET', `/api/v1/app/estadisticas/debilidades?examenId=${EX}&top=5`)).json;
  const sinB = perExam?.items?.find((i) => i.nombre === 'Stats B')?.sinResponder;
  check('J19.weaknesses_per_exam_from_the_finished_attempt', fin.status === 200 && perExam?.alcance === 'EXAMEN' && sinB === 2 && perExam?.items?.find((i) => i.nombre === 'Stats A')?.correctas === 2, JSON.stringify(perExam));
  const after = (await http('GET', '/api/v1/app/estadisticas/resumen')).json;
  check('J19.unanswered_questions_are_counted_after_finishing', after?.totales?.noContestadas >= 2, JSON.stringify(after?.totales));
  const bad = await http('GET', '/api/v1/app/estadisticas/debilidades?top=0');
  const other = await http('GET', '/api/v1/app/estadisticas/debilidades?usuarioId=x');
  check('J19.strict_query', bad.status === 400 && other.status === 400);

  // difficulty: only for answered questions, null below the minimum, then the job fills it with a real sample
  const unanswered = await http('GET', `/api/v1/app/estadisticas/dificultad/${qid(7)}`);
  check('J19.difficulty_of_an_unanswered_question_is_404', unanswered.status === 404, `${unanswered.status}`);
  const noData = await http('GET', `/api/v1/app/estadisticas/dificultad/${qid(1)}`);
  check('J19.difficulty_below_the_minimum_is_null', noData.status === 200 && noData.json?.dificultad === undefined, noData.text);
  sql(`insert into public.estadisticas_globales_pregunta (pregunta_id) values ('${qid(1)}') on conflict do nothing;
insert into auth.users (id, email, aud, role, created_at, updated_at) select ('3c000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'stats-s' || n || '@exam.local', 'authenticated', 'authenticated', now(), now() from generate_series(1, 4) n on conflict do nothing;
insert into public.primeras_respuestas_usuario (usuario_id, pregunta_id, correcta, tiempo_ms)
  select ('3c000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, '${qid(1)}', n > 2, 3000 from generate_series(1, 4) n on conflict do nothing;`);
  const job = JSON.parse(sql(`select public.app_job_dificultad(true)`));
  const withData = await http('GET', `/api/v1/app/estadisticas/dificultad/${qid(1)}`);
  check('J19.difficulty_job_publishes_with_enough_answers', job.estado === 'OK' && withData.status === 200 && Number.isInteger(withData.json?.dificultad) && withData.json.dificultad >= 1 && withData.json.dificultad <= 5, `${JSON.stringify(job)} ${withData.text}`);
  const throttled = JSON.parse(sql(`select public.app_job_dificultad(false)`));
  check('J19.job_is_throttled_by_control', throttled.estado === 'RECIENTE', JSON.stringify(throttled));
  sql(`delete from public.lotes_actualizacion_ranking where tipo = 'dificultad_admin';`);
  const rec = JSON.parse(sql(`select public.admin_recompute_stats(null, 'dificultad')`));
  let cooldown = '';
  try { sql(`select public.admin_recompute_stats(null, 'dificultad')`); } catch (error) { cooldown = String(error.stderr || error.message); }
  check('J19.admin_recompute_is_audited_with_a_cooldown', rec.estado === 'OK' && cooldown.includes('STATS_RECOMPUTE_COOLDOWN') && sql(`select count(*) from public.auditoria_log where accion = 'estadisticas_recalculo'`) >= '1', cooldown.slice(0, 80));
} finally {
  sql(`update public.configuracion_v1 set valor_integer = 20 where clave = 'evolucion_cada_preguntas';
update public.configuracion_v1 set valor_integer = 10 where clave = 'debilidad_min_respuestas';
update public.configuracion_v1 set valor_integer = 20 where clave = 'dificultad_min_respuestas';`);
}
const failed = results.filter((r) => !r).length;
console.log(`\nJ19: ${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
