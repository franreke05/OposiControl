#!/usr/bin/env node
// J16 (E1, W1): Access v2 over HTTP. Needs STACK_LOCK, the app Ktor (:8089) and `up.mjs --converge` (or up.mjs) applied (migrations 20261022100900..101100 + fixtures 98b).
//   Q7 = Inmersivo subscriber (quota 2), Q6 = Rafagas subscriber, Q1 = free user. Demo exam LOC-DEMO-IMM has 5 flagged questions of 7.
//   node ops/local-exam/access-v2-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('access-v2-journey');

requireStackLock('access-v2-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const Q7 = '3b000000-0000-4000-8000-00000000003b';
const DEMO = 'e3000000-0000-4000-8000-0000000000d1', IM2 = 'e3000000-0000-4000-8000-0000000000d2', IM3 = 'e3000000-0000-4000-8000-0000000000d3';

const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (token, method, p, body, key) => {
  const headers = { Authorization: `Bearer ${token}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const r = await fetch(APP + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* empty body */ }
  return { status: r.status, json, text, headers: r.headers };
};
const app = (user, method, p, body, key) => http(tokens[user], method, p, body, key);
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };

// repeatable: Q7 starts with an empty quota, no open demo attempts for Q6
sql(`select set_config('oc.plan_cupo_writer', 'on', false);
update public.contenido_desbloqueado_usuario set valido_hasta = now() - interval '1 second', valido_desde = now() - interval '1 day' where usuario_id = '${Q7}' and motivo = 'plan_cupo' and valido_hasta > now();
select set_config('oc.plan_cupo_writer', 'off', false);
update public.intentos_examen set estado = 'abandonado' where usuario_id in ('2f000000-0000-4000-8000-00000000002f') and es_demo and estado = 'en_progreso';`);

// ---- quota
const s0 = await app('Q7', 'GET', '/api/v1/app/plan/cupo');
check('J16.quota_summary', s0.status === 200 && s0.json?.nivel === 3 && s0.json?.cupoTotal === 2 && s0.json?.cupoLibre === 2, `${s0.status} ${s0.text.slice(0, 160)}`);
const c1 = await app('Q7', 'PUT', `/api/v1/app/plan/cupo/${DEMO}`);
check('J16.choose_1', c1.status === 200 && c1.json?.examenId === DEMO, `${c1.status} ${c1.text.slice(0, 120)}`);
const c1b = await app('Q7', 'PUT', `/api/v1/app/plan/cupo/${DEMO}`);
check('J16.choose_is_idempotent', c1b.status === 200 && c1b.json?.hasta === c1.json?.hasta, `${c1b.status}`);
const d1 = await app('Q7', 'GET', `/api/v1/app/examenes/${DEMO}`);
check('J16.exam_is_GRANTED_after_choose', d1.status === 200 && d1.json?.examen?.accessState === 'GRANTED', `${d1.status} ${d1.json?.examen?.accessState}`);
const c2 = await app('Q7', 'PUT', `/api/v1/app/plan/cupo/${IM2}`);
const c3 = await app('Q7', 'PUT', `/api/v1/app/plan/cupo/${IM3}`);
check('J16.third_choice_is_PLAN_QUOTA_FULL', c2.status === 200 && c3.status === 422 && c3.json?.code === 'PLAN_QUOTA_FULL', `${c2.status} ${c3.status} ${c3.json?.code}`);
const bad = await app('Q7', 'PUT', '/api/v1/app/plan/cupo/not-a-uuid');
const q6 = await app('Q6', 'PUT', `/api/v1/app/plan/cupo/${IM2}`);
check('J16.no_quota_plan_and_bad_ids', bad.status === 400 && q6.status === 422 && q6.json?.code === 'PLAN_QUOTA_NONE', `${bad.status} ${q6.status} ${q6.json?.code}`);

// ---- demo (Q6 has no access to the demo exam: a rafagas plan does not cover an immersive)
const key = () => crypto.randomUUID();
const locked = await app('Q6', 'POST', `/api/v1/app/examenes/${DEMO}/intentos`, {}, key());
check('J16.normal_start_of_a_demo_only_exam_is_EXAM_LOCKED', locked.status === 403 && locked.json?.code === 'EXAM_LOCKED' && locked.json?.details?.accessState === 'DEMO', `${locked.status} ${locked.text.slice(0, 120)}`);
const st = await app('Q6', 'POST', `/api/v1/app/examenes/${DEMO}/intentos`, { modo: 'DEMO' }, key());
const attemptId = st.json?.intento?.id;
check('J16.demo_start', st.status === 201 && st.json?.intento?.esDemo === true && st.json?.intento?.totalPreguntas === 5, `${st.status} ${st.text.slice(0, 160)}`);
const pg = await app('Q6', 'GET', `/api/v1/app/intentos/${attemptId}/preguntas?limit=10`);
check('J16.demo_serves_exactly_five_questions', pg.status === 200 && pg.json?.items?.length === 5 && !pg.json?.nextCursor, `${pg.status} ${pg.json?.items?.length}`);
const flagged = new Set(sql(`select pregunta_id from public.preguntas_examen where examen_id = '${DEMO}' and es_demo`).split('\n'));
check('J16.only_flagged_questions', (pg.json?.items ?? []).every((i) => flagged.has(i.preguntaId)));
const sixth = sql(`select pregunta_id from public.preguntas_examen where examen_id = '${DEMO}' and not es_demo limit 1`);
const opt = sql(`select id from public.opciones_pregunta where pregunta_id = '${sixth}' limit 1`);
const ans = await app('Q6', 'PUT', `/api/v1/app/intentos/${attemptId}/respuestas/${sixth}`, { opcionSeleccionadaId: opt, tiempoRespuestaMs: 1000, baseRevision: 0 }, key());
check('J16.sixth_question_is_not_in_the_attempt', ans.status >= 400 && ans.status < 500, `${ans.status} ${ans.json?.code}`);
const firstFlag = pg.json?.items?.[0]?.preguntaId;
const firstOpt = pg.json?.items?.[0]?.opciones?.[0]?.id;
const a1 = await app('Q6', 'PUT', `/api/v1/app/intentos/${attemptId}/respuestas/${firstFlag}`, { opcionSeleccionadaId: firstOpt, tiempoRespuestaMs: 1000, baseRevision: 0 }, key());
const isFirst = sql(`select es_primer_intento_pregunta from public.respuestas_intento where intento_id = '${attemptId}' and pregunta_id = '${firstFlag}'`);
check('J16.demo_answer_never_the_first_answer', a1.status === 200 && isFirst === 'f', `${a1.status} ${isFirst}`);

// ---- packs: recarga libre blocks
const packs = await app('Q1', 'GET', '/api/v1/app/wallet/paquetes');
const blocks = (packs.json?.items ?? []).filter((p) => p.recargaBase);
check('J16.packs_flag_the_recarga_blocks', packs.status === 200 && blocks.length >= 3 && blocks.every((b) => b.otPorEuro === 100), `${packs.status} ${blocks.length}`);

// ---- redeem verdict (E5-V1-QA-INT06 F1): ALREADY_ACCESSIBLE only when the v2 verdict lets the user start; a LOCKED immersive is bought with OT even by a subscriber
const Q6 = '2f000000-0000-4000-8000-00000000002f', IMM30 = 'e3000000-0000-4000-8000-000000000030', RAF1 = 'e3000000-0000-4000-8000-000000000001';
sql(`delete from public.contenido_desbloqueado_usuario where usuario_id in ('${Q6}', '${Q7}') and motivo = 'compra';
     select public.admin_grant_tickets('${Q6}', 100, 'j16 seed', 'j16-seed-q6-' || clock_timestamp()::text, 'ajuste');
     select public.admin_grant_tickets('${Q7}', 100, 'j16 seed', 'j16-seed-q7-' || clock_timestamp()::text, 'ajuste');`);
const q6raf = await app('Q6', 'POST', '/api/v1/app/wallet/canjes', { examId: RAF1 }, key());
check('J16.rafagas_plan_covers_a_rafaga_not_bought_again', q6raf.status === 409 && q6raf.json?.code === 'ALREADY_ACCESSIBLE' && q6raf.json?.details?.accessState === 'SUBSCRIPTION', `${q6raf.status} ${q6raf.json?.code} ${q6raf.json?.details?.accessState}`);
const q6imm = await app('Q6', 'POST', '/api/v1/app/wallet/canjes', { examId: IMM30 }, key());
check('J16.rafagas_subscriber_buys_an_immersive_with_OT', q6imm.status === 201, `${q6imm.status} ${q6imm.json?.code}`);
const q7imm = await app('Q7', 'POST', '/api/v1/app/wallet/canjes', { examId: IMM30 }, key());
check('J16.inmersivo_subscriber_buys_an_immersive_outside_the_quota_with_OT', q7imm.status === 201, `${q7imm.status} ${q7imm.json?.code}`);
const q7again = await app('Q7', 'POST', '/api/v1/app/wallet/canjes', { examId: DEMO }, key());
check('J16.quota_held_immersive_is_ALREADY_ACCESSIBLE', q7again.status === 409 && q7again.json?.code === 'ALREADY_ACCESSIBLE', `${q7again.status} ${q7again.json?.code}`);
sql(`delete from public.contenido_desbloqueado_usuario where usuario_id in ('${Q6}', '${Q7}') and motivo = 'compra';`);

// ---- daily limit (free plan 30 per 24 h): Q1 reveals fresh questions until the limit
const failed = results.filter((r) => !r).length;
console.log(`J16 ${results.length - failed}/${results.length} PASS`);
process.exit(failed ? 1 : 0);
