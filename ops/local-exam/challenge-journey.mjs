#!/usr/bin/env node
// J17 (E1, W2 + D7-33): daily challenge, streak, claim and plan catalog over HTTP. Needs STACK_LOCK, the app Ktor (:8089) and `up.mjs --converge` (migrations ..101150, ..101200 + fixtures 98b).
//   Q1 = free user (no community: the national row). Repeatable: it clears the challenge rows of Q1 for today first (the ledger is append-only, so a second run claims by REPLAY).
//   node ops/local-exam/challenge-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('challenge-journey');

requireStackLock('challenge-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const Q1 = '2a000000-0000-4000-8000-00000000002a';

const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (token, method, p, body) => {
  const headers = { Authorization: `Bearer ${token}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(APP + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* empty body */ }
  return { status: r.status, json, text, headers: r.headers };
};
const app = (method, p, body, user = 'Q1') => http(tokens[user], method, p, body);
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };

// repeatable: no challenge record for Q1 today, e-mail not verified yet, no deferrals left over
sql(`delete from public.respuestas_reto_diario where usuario_id = '${Q1}';
delete from public.registros_reto_diario where usuario_id = '${Q1}';
delete from public.ot_ganancias_diferidas where usuario_id = '${Q1}';
delete from public.rachas_usuario where usuario_id = '${Q1}';
update auth.users set confirmed_at = null where id = '${Q1}';`);

// ---- plan catalog (D7-33)
const cat = await app('GET', '/api/v1/app/plan/catalogo');
const items = cat.json?.items ?? [];
const inm = items.find((i) => i.planCodigo === 'local-inmersivo');
check('J17.catalog_lists_plans_with_coverage_and_tariffs_as_data', cat.status === 200 && items.length >= 4 && inm?.cobertura?.inmersivos === 'cupo' && inm?.cobertura?.cupoInmersivos === 2
  && inm?.tarifas?.length === 2 && inm.tarifas[0].periodo === 'mensual' && inm.tarifas[1].periodo === 'anual' && typeof inm.tarifas[1].productoCodigo === 'string', `${cat.status} ${cat.text.slice(0, 200)}`);
check('J17.catalog_gratuito_has_the_limit_and_no_tariff', items.find((i) => i.planCodigo === 'local-gratuito')?.limitePreguntas24h === 30 && items.find((i) => i.planCodigo === 'local-gratuito')?.tarifas?.length === 0);
check('J17.catalog_rejects_query_params_and_no_token', (await app('GET', '/api/v1/app/plan/catalogo?usuarioId=x')).status === 400 && (await http('bad', 'GET', '/api/v1/app/plan/catalogo')).status === 401);

// ---- today
const t0 = await app('GET', '/api/v1/app/retos/hoy');
const reto = t0.json?.retoId;
const qs = t0.json?.preguntas ?? [];
check('J17.today_available_with_three_questions', t0.status === 200 && t0.json?.estado === 'DISPONIBLE' && qs.length === 3 && new Set(qs.map((q) => q.tipoSlot)).size === 3 && t0.headers.get('cache-control') === 'no-store', `${t0.status} ${t0.text.slice(0, 160)}`);
check('J17.today_has_no_correction_and_no_internal_fields', !/opcionCorrectaId|"respuesta"|_recursos|bucket|ruta/.test(t0.text));
const t0b = await app('GET', '/api/v1/app/retos/hoy');
check('J17.the_row_is_frozen_for_the_day', t0b.json?.retoId === reto);
const other = await app('GET', '/api/v1/app/retos/hoy', undefined, 'Q2');
check('J17.another_user_reads_only_its_own_record', other.status === 200 && other.json?.retoId !== undefined);
const otherAnswer = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[0]?.preguntaId}`, { opcionSeleccionadaId: qs[0]?.opciones?.[0]?.id }, 'Q3');
check('J17.a_reto_that_is_not_mine_is_404', otherAnswer.status === 404, `${otherAnswer.status} ${otherAnswer.json?.code}`);

// ---- answers
const correct = (qid) => sql(`select opcion_correcta_id from public.soluciones_pregunta where pregunta_id = '${qid}'`);
const wrongOf = (q, ok) => q.opciones.find((o) => o.id !== ok).id;
const a1 = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[0].preguntaId}`, { opcionSeleccionadaId: correct(qs[0].preguntaId) });
check('J17.first_answer_corrects_and_is_in_progress', a1.status === 200 && a1.json?.esCorrecta === true && a1.json?.estado === 'EN_CURSO' && a1.json?.respondidas === 1, `${a1.status} ${a1.text.slice(0, 160)}`);
const a1b = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[0].preguntaId}`, { opcionSeleccionadaId: correct(qs[0].preguntaId) });
check('J17.same_option_again_is_a_stored_replay', a1b.status === 200 && a1b.json?.respondidas === 1);
const lock = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[0].preguntaId}`, { opcionSeleccionadaId: wrongOf(qs[0], correct(qs[0].preguntaId)) });
check('J17.another_option_is_ANSWER_LOCKED', lock.status === 409 && lock.json?.code === 'ANSWER_LOCKED', `${lock.status} ${lock.json?.code}`);
const bad = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[1].preguntaId}`, { opcionSeleccionadaId: '00000000-0000-4000-8000-000000000000' });
check('J17.option_of_another_question_is_refused', bad.status === 422 && bad.json?.code === 'OPTION_NOT_IN_QUESTION', `${bad.status} ${bad.json?.code}`);
const strict = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[1].preguntaId}`, { opcionSeleccionadaId: correct(qs[1].preguntaId), usuarioId: Q1 });
check('J17.strict_body', strict.status === 400, `${strict.status}`);
const early = await app('POST', `/api/v1/app/retos/${reto}/reclamar`);
check('J17.claim_before_passing_is_RETO_NOT_PASSED', early.status === 409 && early.json?.code === 'RETO_NOT_PASSED', `${early.status} ${early.json?.code}`);
await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[1].preguntaId}`, { opcionSeleccionadaId: correct(qs[1].preguntaId) });
const a3 = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[2].preguntaId}`, { opcionSeleccionadaId: wrongOf(qs[2], correct(qs[2].preguntaId)) });
check('J17.passed_with_2_of_3_and_reward_frozen_unclaimed', a3.status === 200 && a3.json?.estado === 'SUPERADO' && a3.json?.aciertos === 2 && a3.json?.recompensa?.base === 5 && a3.json?.recompensa?.reclamada === false, `${a3.status} ${a3.text.slice(0, 200)}`);
const late = await app('PUT', `/api/v1/app/retos/${reto}/respuestas/${qs[2].preguntaId}`, { opcionSeleccionadaId: correct(qs[2].preguntaId) });
check('J17.finished_challenge_keeps_the_stored_answer', late.status === 409 && late.json?.code === 'ANSWER_LOCKED', `${late.status} ${late.json?.code}`);

// ---- streak
const st = await app('GET', '/api/v1/app/racha');
check('J17.streak_starts_at_one', st.status === 200 && st.json?.actual === 1 && st.json?.maxima === 1, `${st.status} ${st.text}`);

// ---- claim: e-mail gate, then paid once
const noMail = await app('POST', `/api/v1/app/retos/${reto}/reclamar`);
const started = Number(sql(`select count(*) from public.transacciones_cartera where usuario_id = '${Q1}' and idempotency_key like 'reto:${Q1}:%:base'`)) > 0;
// the gate applies to the FIRST claim attempt only; on a repeated run of the day the ledger key exists and the claim is a replay (by design)
check('J17.claim_needs_a_verified_email', started ? noMail.status === 200 : noMail.status === 403 && noMail.json?.code === 'EMAIL_UNVERIFIED', `${noMail.status} ${noMail.json?.code}${started ? ' (replay run)' : ''}`);
sql(`update auth.users set confirmed_at = now() where id = '${Q1}';`);
const before = Number(sql(`select coalesce(saldo_tickets, 0) from public.carteras_usuario where usuario_id = '${Q1}'`) || 0);
const c1 = await app('POST', `/api/v1/app/retos/${reto}/reclamar`);
check('J17.claim_pays_the_frozen_amount', c1.status === 200 && c1.json?.recompensa?.total === 5 && c1.json?.recompensa?.reclamada === true && c1.json?.recompensa?.pendiente === false, `${c1.status} ${c1.text.slice(0, 200)}`);
const c2 = await app('POST', `/api/v1/app/retos/${reto}/reclamar`);
const after = Number(sql(`select coalesce(saldo_tickets, 0) from public.carteras_usuario where usuario_id = '${Q1}'`) || 0);
check('J17.second_claim_pays_nothing_more', c2.status === 200 && c2.json?.recompensa?.reclamada === true && (after === before + 5 || after === before), `before ${before} after ${after}`);
const ledger = sql(`select count(*) from public.transacciones_cartera where usuario_id = '${Q1}' and tipo = 'reto_diario' and idempotency_key like 'reto:${Q1}:%:base'`);
check('J17.one_ledger_entry_per_day', Number(ledger) >= 1);
const today2 = await app('GET', '/api/v1/app/retos/hoy');
check('J17.today_reads_superado_with_the_correction_and_the_reward', today2.json?.estado === 'SUPERADO' && today2.json?.preguntas?.every((q) => q.respuesta?.opcionCorrectaId) && today2.json?.recompensa?.reclamada === true, `${today2.json?.estado}`);

// ---- the earn cap: a reward above the whole cap can never be paid (409, nothing paid); a user (Q4, fresh) is used because a repeated day replays the key
const Q4 = '2e000000-0000-4000-8000-00000000002e';
sql(`delete from public.respuestas_reto_diario where usuario_id = '${Q4}'; delete from public.registros_reto_diario where usuario_id = '${Q4}'; delete from public.ot_ganancias_diferidas where usuario_id = '${Q4}';
     update auth.users set confirmed_at = now() where id = '${Q4}'; update public.configuracion_v1 set valor_integer = 3 where clave = 'tope_ot_gratis_dia';`);
try {
  const t1 = await app('GET', '/api/v1/app/retos/hoy', undefined, 'Q4');
  const q1 = t1.json?.preguntas ?? [];
  for (const q of q1.slice(0, 2)) await app('PUT', `/api/v1/app/retos/${t1.json.retoId}/respuestas/${q.preguntaId}`, { opcionSeleccionadaId: correct(q.preguntaId) }, 'Q4');
  await app('PUT', `/api/v1/app/retos/${t1.json.retoId}/respuestas/${q1[2].preguntaId}`, { opcionSeleccionadaId: wrongOf(q1[2], correct(q1[2].preguntaId)) }, 'Q4');
  const capped = await app('POST', `/api/v1/app/retos/${t1.json.retoId}/reclamar`, undefined, 'Q4');
  check('J17.a_reward_above_the_cap_itself_is_409_OT_EARN_CAP_REACHED', capped.status === 409 && capped.json?.code === 'OT_EARN_CAP_REACHED', `${capped.status} ${capped.json?.code}`);
  const paid = sql(`select count(*) from public.transacciones_cartera where usuario_id = '${Q4}' and tipo = 'reto_diario' and creado_en > now() - interval '5 minutes'`);
  check('J17.and_nothing_was_paid', paid === '0', paid);
} finally {
  sql(`update public.configuracion_v1 set valor_integer = 100 where clave = 'tope_ot_gratis_dia';`);
}

// ---- parallel claims (two devices): one ledger entry, every answer 200
const Q2 = '2b000000-0000-4000-8000-00000000002b';
sql(`delete from public.respuestas_reto_diario where usuario_id = '${Q2}'; delete from public.registros_reto_diario where usuario_id = '${Q2}'; delete from public.ot_ganancias_diferidas where usuario_id = '${Q2}';
     delete from public.rachas_usuario where usuario_id = '${Q2}'; update auth.users set confirmed_at = now() where id = '${Q2}';`);
const t2 = await app('GET', '/api/v1/app/retos/hoy', undefined, 'Q2');
for (const q of t2.json?.preguntas ?? []) await app('PUT', `/api/v1/app/retos/${t2.json.retoId}/respuestas/${q.preguntaId}`, { opcionSeleccionadaId: correct(q.preguntaId) }, 'Q2');
const race = await Promise.all([1, 2, 3, 4].map(() => app('POST', `/api/v1/app/retos/${t2.json.retoId}/reclamar`, undefined, 'Q2')));
const rows = sql(`select count(*) from public.transacciones_cartera where usuario_id = '${Q2}' and tipo = 'reto_diario' and idempotency_key like 'reto:${Q2}:%:base'`);
check('J17.parallel_claims_pay_once', race.every((r) => r.status === 200) && rows === '1', `${race.map((r) => r.status).join(',')} ledger ${rows}`);

const failed = results.filter((r) => !r).length;
console.log(`\nJ17: ${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
