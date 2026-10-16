#!/usr/bin/env node
// J18 (E1, OFFICIAL RULES; E5 calls it "J16" in the charter, the J16 number is taken by access-v2-journey): official exams with annulled and reserve questions over HTTP.
// Needs STACK_LOCK, the app Ktor (:8089) and `up.mjs --converge` (migration 20261022101300). It creates its own exams (OFF-*) for the free user Q1; re-runnable.
//   OFF-0: 6 main, nothing annulled, no reserves.        OFF-1: question 2 annulled, 3 reserves (rule derived: REEMPLAZO).
//   OFF-2: questions 2 and 5 annulled, 3 reserves, penalty 1/3.    OFF-3: 6 main, 3 reserves, nothing annulled (reserves unused).
//   node ops/local-exam/official-rules-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('official-rules-journey');

requireStackLock('official-rules-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const Q1 = '2a000000-0000-4000-8000-00000000002a';
const ESP = 'e5000000-0000-4000-8000-000000000001';
const ex = (n) => `ea000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const qid = (n, p) => `fa000000-0000-4000-${String(n).padStart(4, '0')}-${String(p).padStart(12, '0')}`;
const oid = (n, p, o) => `0a000000-0000-4000-${String(n).padStart(4, '0')}-${String(p * 10 + o).padStart(12, '0')}`;

const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (method, p, body, key) => {
  const headers = { Authorization: `Bearer ${tokens.Q1}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const r = await fetch(APP + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* empty body */ }
  return { status: r.status, json, text };
};
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };

// ---- fixtures (idempotent): exam n, main questions 1..6, reserves 7..9; annulled positions per exam
const spec = [
  { n: 0, annulled: [], reserves: 0, num: 0, den: 1 },
  { n: 1, annulled: [2], reserves: 3, num: 0, den: 1 },
  { n: 2, annulled: [2, 5], reserves: 3, num: 1, den: 3 },
  { n: 3, annulled: [], reserves: 3, num: 0, den: 1 },
];
let fixtures = `set session_replication_role = replica;
delete from public.intentos_examen where usuario_id = '${Q1}' and examen_id::text like 'ea000000-%';
`;
for (const s of spec) {
  fixtures += `insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, penalizacion_num, penalizacion_den)
  select '${ex(s.n)}', 'OFF-${s.n}', '${ESP}', (select id from public.comunidades_autonomas order by id limit 1), 'Oficial OFF-${s.n}', 'rafaga', 'gratuito', true, now() - interval '1 day', ${s.num}, ${s.den}
  on conflict (id) do update set penalizacion_num = excluded.penalizacion_num, penalizacion_den = excluded.penalizacion_den, publicado_en = excluded.publicado_en, activo = true;
delete from public.preguntas_examen where examen_id = '${ex(s.n)}';
`;
  for (let p = 1; p <= 6 + s.reserves; p++) {
    const anul = s.annulled.includes(p);
    fixtures += `insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${qid(s.n, p)}', 'OFF-${s.n}-${p}', 'rafaga', 'Pregunta oficial ${s.n}-${p}', true) on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${oid(s.n, p, 1)}', '${qid(s.n, p)}', 'A', 'Correcta', 1), ('${oid(s.n, p, 2)}', '${qid(s.n, p)}', 'B', 'Otra', 2) on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${qid(s.n, p)}', '${oid(s.n, p, 1)}', 'x') on conflict (pregunta_id) do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
  values ('${ex(s.n)}', '${qid(s.n, p)}', ${p}, ${p}, ${anul}, ${anul ? "'tribunal'" : 'null'}, ${p > 6}, ${p > 6 ? p - 6 : 'null'});
`;
  }
}
fixtures += `set session_replication_role = origin;\n`;
sql(fixtures);

const start = async (n) => {
  const r = await http('POST', `/api/v1/app/examenes/${ex(n)}/intentos`, {}, crypto.randomUUID());
  return { id: r.json?.intento?.id, status: r.status, json: r.json };
};
const pages = async (id) => (await http('GET', `/api/v1/app/intentos/${id}/preguntas?limit=50`)).json?.items ?? [];
const answer = (id, n, p, ok) => http('PUT', `/api/v1/app/intentos/${id}/respuestas/${qid(n, p)}`, { opcionSeleccionadaId: oid(n, p, ok ? 1 : 2), tiempoRespuestaMs: 1500, baseRevision: 0 }, crypto.randomUUID());

// ---- OFF-1: one annulled main question, three reserves -> reserve 1 replaces position 2
const a = await start(1);
check('J18.start_official_exam', a.status === 201 && a.json?.intento?.totalPreguntas === 6, `${a.status} total ${a.json?.intento?.totalPreguntas}`);
const grid = (await http('GET', `/api/v1/app/intentos/${a.id}`)).json?.grid ?? [];
check('J18.open_grid_has_main_plus_active_reserve_only', grid.length === 7 && grid.filter((g) => g.rol === 'RESERVA').length === 1 && grid.find((g) => g.rol === 'RESERVA')?.sustituyeA === 2
  && !grid.some((g) => g.preguntaId === qid(1, 8) || g.preguntaId === qid(1, 9)), `${grid.length} ${JSON.stringify(grid.filter((g) => g.rol))}`);
const items = await pages(a.id);
check('J18.open_pages_never_serve_an_unused_reserve', items.length === 7 && !items.some((i) => i.preguntaId === qid(1, 8) || i.preguntaId === qid(1, 9)) && items.find((i) => i.posicion === 2)?.anulada === true && items.find((i) => i.rol === 'RESERVA')?.sustituyeA === 2);
const unused = await answer(a.id, 1, 8, true);
check('J18.answering_an_unused_reserve_is_QUESTION_NOT_IN_ATTEMPT', unused.status === 422 && unused.json?.code === 'QUESTION_NOT_IN_ATTEMPT', `${unused.status} ${unused.json?.code}`);
const res = await http('GET', `/api/v1/app/intentos/${a.id}/recursos/00000000-0000-4000-8000-0000000000aa`);
check('J18.media_of_an_unknown_resource_is_404', res.status === 404, `${res.status}`);
const annulled = await answer(a.id, 1, 2, true);
check('J18.an_annulled_question_is_answerable_but_stores_nothing', annulled.status === 200 && annulled.json?.estado === 'ANNULLED', `${annulled.status} ${annulled.text.slice(0, 120)}`);
for (const p of [1, 3, 4]) await answer(a.id, 1, p, true);
await answer(a.id, 1, 5, false);
const reserveAnswer = await answer(a.id, 1, 7, true);
check('J18.the_active_reserve_is_answerable', reserveAnswer.status === 200 && reserveAnswer.json?.estado === 'ANSWERED', `${reserveAnswer.status}`);
const fin = await http('POST', `/api/v1/app/intentos/${a.id}/finalizar`);
const rs = fin.json?.resumen;
// answered: 1,3,4 ok + reserve 7 ok = 4 correct, 5 wrong = 1, position 6 unanswered = 1 -> 6 evaluables, 10 x 4 / 6 = 6.67
check('J18.result_carries_rule_counts_and_database_grade', fin.status === 200 && rs?.nota === 6.67 && rs?.evaluables === 6 && rs?.anuladas === 1 && rs?.reservasUsadas === 1 && rs?.reglaAnuladas === 'REEMPLAZO_POR_RESERVA'
  && rs?.correctas === 4 && rs?.falladas === 1 && rs?.sinResponder === 1 && rs?.penalizacion === undefined, `${fin.status} ${JSON.stringify(rs)}`);
const result = (await http('GET', `/api/v1/app/intentos/${a.id}/resultado`)).json;
check('J18.result_lists_the_unused_reserves', result?.preguntas?.filter((q) => q.estado === 'RESERVA_NO_UTILIZADA').length === 2 && result?.preguntas?.find((q) => q.rol === 'RESERVA' && q.sustituyeA === 2)?.estado === 'CORRECT');
const review = (await http('GET', `/api/v1/app/intentos/${a.id}/revision?limit=50`)).json?.items ?? [];
check('J18.review_labels_unused_reserves_and_the_replacement', review.filter((i) => i.status === 'RESERVA_NO_UTILIZADA').length === 2 && review.find((i) => i.status === 'ANNULLED')?.posicion === 2
  && review.find((i) => i.rol === 'RESERVA' && i.sustituyeA === 2) !== undefined && review.filter((i) => i.status === 'RESERVA_NO_UTILIZADA').every((i) => i.finalAnswer == null && i.correctAnswer), `${review.length}`);
const gridDone = (await http('GET', `/api/v1/app/intentos/${a.id}`)).json?.grid ?? [];
check('J18.finished_grid_shows_the_unused_reserves', gridDone.filter((g) => g.estado === 'reserva_no_utilizada').length === 2 && gridDone.length === 9);

// ---- OFF-0: nothing annulled, no reserves = today's behaviour
const z = await start(0);
for (const p of [1, 2, 3, 4]) await answer(z.id, 0, p, true);
const zf = (await http('POST', `/api/v1/app/intentos/${z.id}/finalizar`)).json?.resumen;
check('J18.exam_without_annulled_or_reserves_is_unchanged', zf?.nota === 6.67 && zf?.evaluables === 6 && zf?.reservasUsadas === 0 && zf?.reglaAnuladas === 'NO_PUNTUA' && zf?.penalizacion === undefined, JSON.stringify(zf));

// ---- OFF-2: two annulled, penalty 1/3: R1 and R2 replace 2 and 5
const p2 = await start(2);
const gp2 = (await http('GET', `/api/v1/app/intentos/${p2.id}`)).json?.grid ?? [];
check('J18.two_annulled_activate_two_reserves_in_order', gp2.filter((g) => g.rol === 'RESERVA').map((g) => g.sustituyeA).sort().join() === '2,5' && !gp2.some((g) => g.preguntaId === qid(2, 9)), JSON.stringify(gp2.filter((g) => g.rol)));
for (const p of [1, 3]) await answer(p2.id, 2, p, true);
for (const p of [4, 6, 7]) await answer(p2.id, 2, p, false);
const f2 = (await http('POST', `/api/v1/app/intentos/${p2.id}/finalizar`)).json?.resumen;
// evaluables: 1,3,4,6 + reserves 7,8 = 6; correct 2 (1,3), wrong 3 (4,6,7), unanswered 1 (8) -> max(0, 2x3 - 1x3) = 3 -> 30 / 18 = 1.67
check('J18.penalty_one_third_is_applied_by_the_database', f2?.nota === 1.67 && f2?.penalizacion?.numerador === 1 && f2?.penalizacion?.denominador === 3 && f2?.reservasUsadas === 2 && f2?.evaluables === 6, JSON.stringify(f2));

// ---- OFF-3: reserves exist but nothing is annulled -> all three unused, never served
const u = await start(3);
const uItems = await pages(u.id);
check('J18.reserves_without_annulled_are_never_served', uItems.length === 6 && !uItems.some((i) => i.rol), `${uItems.length}`);
const uAns = await answer(u.id, 3, 8, true);
check('J18.and_cannot_be_answered', uAns.status === 422 && uAns.json?.code === 'QUESTION_NOT_IN_ATTEMPT', `${uAns.status}`);
sql(`delete from public.intentos_examen where id = '${u.id}';`);

// ---- publication validation: more annulled than usable reserves is refused (database, 422 in Control)
let refused = '';
try {
  sql(`update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = '${ex(1)}' and pregunta_id in ('${qid(1, 1)}', '${qid(1, 3)}', '${qid(1, 4)}');`);
} catch (error) { refused = String(error.stderr || error.message); }
check('J18.more_annulled_than_reserves_is_refused', refused.includes('OFFICIAL_RULES_INVALID'), refused.slice(0, 80));
const warn = JSON.parse(sql(`select public.admin_official_rules_check('${ex(1)}')`));
check('J18.control_check_returns_rule_and_no_block', warn.problema === null && warn.regla === 'REEMPLAZO_POR_RESERVA', JSON.stringify(warn));

const failed = results.filter((r) => !r).length;
console.log(`\nJ18: ${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
