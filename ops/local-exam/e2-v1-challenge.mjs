#!/usr/bin/env node
// E2 V1 DAILY CHALLENGE (LOCAL ONLY): live attacks on W2 (challenge, Racha, claim, ONE earn-cap function, challenge media D3-19, client writes to engagement tables).
// Own specialty (created per run) so the segment pool is entirely MY questions; own users. STACK_LOCK hard-fail. Own Ktor on :8091 restarted by the script.
// Shared config rows touched for a moment (tope_ot_gratis_dia) are restored in a finally block.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-challenge.mjs [--only=NAME,...]
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, balance, ledgerRows, uuid, sleep, comm, run } = L;

requireStackLock('e2-v1-challenge.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-challenge-result.json';
await L.restartKtor();

// ---- own specialty + pool: inmersiva (demo-flagged), razonada, rafaga (REGIONAL only at first: national pool empty for rafaga)
const CH = `e2c40000-0000-4000-8000-${run}000001`, CH2 = `e2c40000-0000-4000-8000-${run}000002`;
const ES_AN = sql(`select id from public.comunidades_autonomas where codigo = 'ES-AN' limit 1;`), ES_MD = sql(`select id from public.comunidades_autonomas where codigo = 'ES-MD' limit 1;`);
sql(`set session_replication_role = replica;
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('${CH}', 'E2C4-${run}', 'E2 challenge ${run}', 'e2-challenge-${run}', true), ('${CH2}', 'E2C5-${run}', 'E2 challenge2 ${run}', 'e2-challenge2-${run}', true);
set session_replication_role = origin;`);
let nq = 0;
function addQ(spec, examId, tipoPregunta, { demo = false, tag = null, resource = false } = {}) {
  const q = uuid(), ok = uuid(), ko = uuid(); nq++;
  sql(`set session_replication_role = replica;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q}', 'E2C4-${run}-${nq}', '${tipoPregunta}', 'RETO-TITULO-${nq}', true);
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${ok}', '${q}', 'A', 'a', 1), ('${ko}', '${q}', 'B', 'b', 2);
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q}', '${ok}', 'RETO-SOLUCION-SECRETA-${nq}');
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_demo) values ('${examId}', '${q}', ${nq}, ${nq}, ${demo});
${tag ? `insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('${q}', '${tag}');` : ''}
set session_replication_role = origin;`);
  let rid = null;
  if (resource) { rid = uuid(); sql(`set session_replication_role = replica; insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, ruta_storage, metadatos, posicion, activo) values ('${rid}', '${q}', 'E2R-${run}-${nq}', 'imagen', 'img', 'preguntas/${q}/${rid}.png', '{"mime":"image/png","bytes":100}'::jsonb, 1, true); set session_replication_role = origin;`); }
  return { id: q, ok, ko, rid };
}
function mkChExam(spec, tipo) {
  const e = uuid(); sql(`set session_replication_role = replica; insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values ('${e}', 'E2C4X-${run}-${nq}-${tipo}', '${spec}', 'E2 ch ${tipo}', '${tipo}', 'premium', true, now() - interval '1 day', 20, ${comm}, false, null, 30, ${tipo === 'inmersivo'}); set session_replication_role = origin;`); return e;
}
const tagAN = uuid();
sql(`set session_replication_role = replica; insert into public.etiquetas (id, nombre, slug, activa) values ('${tagAN}', 'E2 legislacion AN ${run}', 'e2-leg-an-${run}', true); insert into public.etiquetas_comunidad (etiqueta_id, comunidad_id) values ('${tagAN}', '${ES_AN}'); set session_replication_role = origin;`);
const eI = mkChExam(CH, 'inmersivo'), eZ = mkChExam(CH, 'razonado'), eR = mkChExam(CH, 'rafaga');
const imm = [addQ(CH, eI, 'inmersiva', { demo: true, resource: true }), addQ(CH, eI, 'inmersiva', { demo: true, resource: true })], raz = [addQ(CH, eZ, 'razonada'), addQ(CH, eZ, 'razonada')];
const regionalRaf = [addQ(CH, eR, 'rafaga', { tag: tagAN }), addQ(CH, eR, 'rafaga', { tag: tagAN })];
const nonDemoImm = addQ(CH, eI, 'inmersiva', { demo: false });   // an immersive question that is NOT demo-flagged: must never be served by the challenge
// real storage objects for the resource questions (service key only used locally to upload a 1x1 PNG; never printed)
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
for (const q of imm.filter((x) => x.rid)) {
  const up = await fetch('http://localhost:54321/storage/v1/object/recursos-pregunta/preguntas/' + q.id + '/' + q.rid + '.png', { method: 'POST', headers: { apikey: L.secrets.serviceKey, Authorization: 'Bearer ' + L.secrets.serviceKey, 'Content-Type': 'image/png' }, body: PNG });
  if (!up.ok) console.log('NOTE storage upload status ' + up.status);
}
const secret = (txt) => /RETO-SOLUCION-SECRETA|opcionCorrectaId|razonamiento/.test(txt);
const today = (u) => call(u, 'GET', '/api/v1/app/retos/hoy');
const put = (u, reto, q, opt) => call(u, 'PUT', `/api/v1/app/retos/${reto}/respuestas/${q}`, { opcionSeleccionadaId: opt });
const claim = (u, reto) => call(u, 'POST', `/api/v1/app/retos/${reto}/reclamar`, {});
const winners = {};

await scenario(only, 'A_SEGMENT', async () => {
  const nat = mkUser('cnat', CH), md = mkUser('cmd', CH), an = mkUser('can', CH);
  sql(`update public.perfiles_usuario set comunidad_principal_id = '${ES_MD}' where usuario_id = '${md.id}'; update public.perfiles_usuario set comunidad_principal_id = '${ES_AN}' where usuario_id = '${an.id}';`);
  const t1 = await today(nat), t2 = await today(md), t3 = await today(an);
  check('C1.empty_pool_for_a_segment_means_NO_DISPONIBLE_the_regional_rule_is_never_relaxed', t1.json?.estado === 'NO_DISPONIBLE' && t2.json?.estado === 'NO_DISPONIBLE' && !t1.text.includes(regionalRaf[0].id) && !t2.text.includes(regionalRaf[0].id) && !t2.text.includes(regionalRaf[1].id), `national=${t1.json?.estado} MD=${t2.json?.estado} AN=${t3.json?.estado}`);
  const ids3 = (t3.json?.preguntas ?? []).map((p) => p.preguntaId);
  check('C2.regional_community_gets_the_regional_question_and_only_demo_flagged_immersive', t3.json?.estado === 'DISPONIBLE' && ids3.length === 3 && ids3.some((i) => regionalRaf.map((q) => q.id).includes(i)) && !ids3.includes(nonDemoImm.id) && ids3.some((i) => imm.map((q) => q.id).includes(i)), `${t3.json?.estado} ${t3.json?.preguntas?.map((p) => p.tipoSlot)}`);
  check('C3.correct_option_and_solution_never_present_before_answering', !secret(t3.text), 'no opcionCorrectaId / razonamiento in GET hoy');
  // target community: principal MD + target AN => belongs to AN too? (design: principal + targets)
  const tg = mkUser('ctarget', CH); sql(`update public.perfiles_usuario set comunidad_principal_id = '${ES_MD}' where usuario_id = '${tg.id}'; insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id, prioridad) values ('${tg.id}', '${ES_AN}', 1);`);
  const t4 = await today(tg);
  finding('C4.belongs_to_is_principal_plus_targets_decision_observed', true, `principal MD + target AN => ${t4.json?.estado}`, 'INFO');
  // now add NATIONAL rafagas: national users get a challenge; a regional question never reaches MD / national users
  const natRaf = [addQ(CH, eR, 'rafaga'), addQ(CH, eR, 'rafaga')];
  const nat2 = mkUser('cnat2', CH), md2 = mkUser('cmd2', CH); sql(`update public.perfiles_usuario set comunidad_principal_id = '${ES_MD}' where usuario_id = '${md2.id}';`);
  const n2 = await today(nat2), m2 = await today(md2), n2b = await today(nat2);
  const reg = regionalRaf.map((q) => q.id);
  check('C5.national_and_MD_users_never_receive_a_regional_question_even_with_a_national_pool', n2.json?.estado === 'DISPONIBLE' && m2.json?.estado === 'DISPONIBLE' && !reg.some((r) => n2.text.includes(r) || m2.text.includes(r)), `${n2.json?.estado}/${m2.json?.estado}`);
  check('C6.same_segment_same_retoId_and_questions_stable_across_calls', n2.json?.retoId === m2.json?.retoId && n2.json?.retoId === n2b.json?.retoId && JSON.stringify(n2.json?.preguntas?.map((p) => p.preguntaId)) === JSON.stringify(n2b.json?.preguntas?.map((p) => p.preguntaId)), `${n2.json?.retoId?.slice(0, 8)}`);
  // one row per user per Madrid day (unique) and segment frozen: changing the community after the first open keeps the stored row
  const before = sql(`select count(*) || '|' || coalesce(max(reto_id::text), '') from public.registros_reto_diario where usuario_id = '${nat2.id}';`);
  sql(`update public.perfiles_usuario set comunidad_principal_id = '${ES_AN}' where usuario_id = '${nat2.id}';`);
  const n3 = await today(nat2); const after = sql(`select count(*) || '|' || coalesce(max(reto_id::text), '') from public.registros_reto_diario where usuario_id = '${nat2.id}';`);
  check('C7.changing_the_profile_community_after_the_first_open_keeps_one_row_and_the_same_challenge', n3.json?.retoId === n2.json?.retoId && before === after && Number(before.split('|')[0]) <= 1, `before=${before.slice(0, 6)} after=${after.slice(0, 6)}`);
  const dup = sqlTry(`insert into public.registros_reto_diario (usuario_id, reto_id, dia) select usuario_id, reto_id, dia from public.registros_reto_diario where usuario_id = '${nat2.id}' limit 1;`);
  check('C8.db_enforces_one_registry_row_per_user_and_day', !dup.ok || Number(sql(`select count(*) from public.registros_reto_diario where usuario_id = '${nat2.id}';`)) <= 1, dup.ok ? 'insert accepted?' : 'unique violation');
  winners.seg = { nat2, md2, reto: n2.json?.retoId, pregs: n2.json?.preguntas, an, t3 };
});

await scenario(only, 'B_PLAY', async () => {
  const S = winners.seg; if (!S) throw new Error('A_SEGMENT must run first');
  const p = S.pregs; const find = (slot) => p.find((x) => x.tipoSlot === slot); const opts = (q) => q.opciones; const correctOf = (q) => opts(q).find((o) => o.letra === 'A').id; const wrongOf = (q) => opts(q).find((o) => o.letra === 'B').id;
  // user W passes 3/3; user F passes 1/3 (fails); user V verified claims later; user U unverified
  const W = mkUser('cwin', CH), F = mkUser('cfail', CH), U = mkUser('cunver', CH, true);
  for (const u of [W, F, U]) await today(u);
  // tamper first: other-segment user, non member question, other reto, bad ids, extra body fields, client-chosen day
  const other = mkUser('cother', CH2); await call(other, 'GET', '/api/v1/app/retos/hoy');
  const t1 = await put(other, S.reto, p[0].preguntaId, correctOf(p[0])), t2 = await put(W, S.reto, uuid(), correctOf(p[0])), t3 = await put(W, uuid(), p[0].preguntaId, correctOf(p[0])), t4 = await put(W, S.reto, p[0].preguntaId, uuid());
  const t5 = await call(W, 'PUT', `/api/v1/app/retos/${S.reto}/respuestas/${p[0].preguntaId}`, { opcionSeleccionadaId: correctOf(p[0]), dia: '2020-01-01', fecha: '2020-01-01', now: '2099-01-01T00:00:00Z', esCorrecta: true });
  const t6 = await call(W, 'GET', '/api/v1/app/retos/hoy?dia=2020-01-01&fecha=2020-01-01&now=2099-01-01'); const t7 = await call(W, 'PUT', `/api/v1/app/retos/${S.reto}/respuestas/${p[0].preguntaId}`, { opcionSeleccionadaId: correctOf(p[0]) }, { Date: 'Thu, 01 Jan 2099 00:00:00 GMT', 'X-Client-Time': '2099-01-01T00:00:00Z' });
  check('C9.segment_tampering_unknown_question_unknown_reto_unknown_option_all_404_same_shape', t1.status === 404 && t2.status === 404 && t3.status === 404 && [404, 400, 422].includes(t4.status) && t1.json?.code === t3.json?.code, [t1, t2, t3, t4].map((x) => x.status + '/' + (x.json?.code ?? '')).join(' '));
  check('C10.no_client_day_clock_or_correctness_is_accepted_extra_fields_400_params_400_headers_ignored', t5.status === 400 && t6.status === 400 && [200, 409].includes(t7.status), `body=${t5.status} query=${t6.status} headers=${t7.status}`);
  // W: three correct; answer final
  const a1 = await put(W, S.reto, p[0].preguntaId, correctOf(p[0])); const a1b = await put(W, S.reto, p[0].preguntaId, correctOf(p[0])); const lock = await put(W, S.reto, p[0].preguntaId, wrongOf(p[0]));
  check('C11.answer_is_final_same_option_replays_200_other_option_409_ANSWER_LOCKED', a1.status === 200 && a1.json?.esCorrecta === true && a1b.status === 200 && lock.status === 409 && lock.json?.code === 'ANSWER_LOCKED', `${a1.status}/${a1b.status}/${lock.status} ${lock.json?.code}`);
  const a2 = await put(W, S.reto, p[1].preguntaId, correctOf(p[1])); const a3 = await put(W, S.reto, p[2].preguntaId, correctOf(p[2]));
  const afterAll = await today(W);
  check('C12.third_answer_closes_the_challenge_SUPERADO_and_updates_the_streak', a3.status === 200 && afterAll.json?.estado === 'SUPERADO' && afterAll.json?.racha?.actual === 1, `${a2.status}/${a3.status} ${afterAll.json?.estado} racha=${afterAll.json?.racha?.actual}`);
  const late = await put(W, S.reto, p[1].preguntaId, wrongOf(p[1]));
  check('C13.after_closing_answers_stay_locked', late.status === 409, `${late.status}`);
  // F: 1 correct, 2 wrong => FALLADO and nothing to claim
  await put(F, S.reto, p[0].preguntaId, correctOf(p[0])); await put(F, S.reto, p[1].preguntaId, wrongOf(p[1])); await put(F, S.reto, p[2].preguntaId, wrongOf(p[2]));
  const fs_ = await today(F); const fc = await claim(F, S.reto);
  check('C14.fewer_than_2_correct_is_FALLADO_and_claim_is_409_RETO_NOT_PASSED_no_racha', fs_.json?.estado === 'FALLADO' && fc.status === 409 && fc.json?.code === 'RETO_NOT_PASSED' && (fs_.json?.racha?.actual ?? 0) === 0, `${fs_.json?.estado} ${fc.status}/${fc.json?.code}`);
  // answers never feed exam stats / first answers
  const firsts = sql(`select count(*) from public.respuestas_intento where usuario_id = '${W.id}';`);
  check('C15.challenge_answers_do_not_create_exam_answers_or_first_answers', firsts === '0', `respuestas_intento rows=${firsts}`);
  // unverified e-mail gate
  for (const q of p) await put(U, S.reto, q.preguntaId, correctOf(q)); const uc = await claim(U, S.reto);
  check('C16.EMAIL_UNVERIFIED_gate_on_the_first_claim_nothing_paid', uc.status === 403 && uc.json?.code === 'EMAIL_UNVERIFIED' && balance(U) === 0 && ledgerRows(U) === 0, `${uc.status}/${uc.json?.code}`);
  // claim: other user's reto, replay, 4 parallel
  const oc = await claim(other, S.reto); const c1 = await claim(W, S.reto); const c2 = await claim(W, S.reto);
  check('C17.claim_pays_once_replay_is_200_same_amounts_other_users_claim_404', oc.status === 404 && c1.status === 200 && c1.json?.recompensa?.reclamada === true && c2.status === 200 && ledgerRows(W, 'reto_diario') === 1 && balance(W) === c1.json?.recompensa?.total, `other=${oc.status} ${c1.status}/${c2.status} rows=${ledgerRows(W, 'reto_diario')} balance=${balance(W)} total=${c1.json?.recompensa?.total}`);
  const P = mkUser('cpar', CH); await today(P); for (const q of p) await put(P, S.reto, q.preguntaId, correctOf(q)); const par = await Promise.all(Array.from({ length: 4 }, () => claim(P, S.reto)));
  check('C18.four_parallel_claims_exactly_one_ledger_row_and_one_payment', par.every((x) => x.status === 200) && ledgerRows(P, 'reto_diario') === 1 && balance(P) === par[0].json?.recompensa?.total, `rows=${ledgerRows(P, 'reto_diario')} balance=${balance(P)}`);
  const kp = sql(`select count(*) from public.transacciones_cartera where usuario_id = '${P.id}' and idempotency_key like 'reto:%';`);
  check('C19.ledger_keys_are_per_user_and_madrid_day', Number(kp) === 1 && /reto:[0-9a-f-]{36}:\d{4}-\d{2}-\d{2}/.test(sql(`select idempotency_key from public.transacciones_cartera where usuario_id = '${P.id}' and idempotency_key like 'reto:%' limit 1;`)), sql(`select idempotency_key from public.transacciones_cartera where usuario_id = '${P.id}' and idempotency_key like 'reto:%' limit 1;`).slice(0, 80));
  const rac = await call(W, 'GET', '/api/v1/app/racha'); const rb = await call(W, 'GET', '/api/v1/app/racha?userId=' + P.id);
  check('C20.racha_endpoint_is_own_data_and_rejects_user_params', rac.status === 200 && rac.json?.actual === 1 && rb.status === 400, `${rac.status} actual=${rac.json?.actual} param=${rb.status}`);
  winners.W = W; winners.P = P; winners.other = other; winners.p = p; winners.correctOf = correctOf; winners.U = U;
});

await scenario(only, 'C_CAP', async () => {
  const prev = sql(`select valor_integer from public.configuracion_v1 where clave = 'tope_ot_gratis_dia';`);
  try {
    const u = mkUser('ccap', CH);
    // direct earn function outcomes
    const earn = (key, amt, tipo = 'reto_diario', usr = u) => sql(`select estado || '|' || coalesce(transaccion_id::text, '') || '|' || saldo from public.app_credit_free_ot('${usr.id}', '${tipo}', ${amt}, 'e2', null, '${key}', '{}'::jsonb);`);
    sql(`update public.configuracion_v1 set valor_integer = 7 where clave = 'tope_ot_gratis_dia';`);
    const r1 = earn('e2k1-' + run, 5), r1b = earn('e2k1-' + run, 5), r2 = earn('e2k2-' + run, 5), r2b = earn('e2k2-' + run, 5), r3 = earn('e2k3-' + run, 8);
    check('E1.earn_function_outcomes_CREDITED_REPLAY_DEFERRED_all_or_nothing_REJECTED_CAP', r1.startsWith('CREDITED') && r1b.startsWith('REPLAY') && r2.startsWith('DEFERRED') && r2b.startsWith('DEFERRED') && r3.startsWith('REJECTED_CAP') && balance(u) === 5 && ledgerRows(u, 'reto_diario') === 1, [r1, r1b, r2, r2b, r3].map((x) => x.split('|')[0]).join(' ') + ' balance=' + balance(u));
    const dq = sql(`select count(*) || '|' || sum(importe) from public.ot_ganancias_diferidas where usuario_id = '${u.id}' and estado = 'pendiente';`);
    check('E2.deferral_is_stored_once_per_key_not_paid_not_dropped', dq === '1|5', 'pending deferrals=' + dq);
    sql(`update public.configuracion_v1 set valor_integer = 100 where clave = 'tope_ot_gratis_dia';`);
    const j1 = sql(`select aplicadas || '|' || pendientes from public.app_retry_deferred_free_ot(50);`); const j2 = sql(`select aplicadas || '|' || pendientes from public.app_retry_deferred_free_ot(50);`);
    check('E3.retry_job_pays_the_deferral_once_and_a_second_run_pays_nothing', balance(u) === 10 && ledgerRows(u, 'reto_diario') === 2 && j2.startsWith('0'), `job1=${j1} job2=${j2} balance=${balance(u)}`);
    check('E4.replaying_the_deferred_key_after_payment_is_REPLAY_never_a_second_payment', earn('e2k2-' + run, 5).startsWith('REPLAY') && balance(u) === 10, 'balance=' + balance(u));
    // parallel earns at the cap boundary (same lock): cap 12, earned 10, two parallel 5 => exactly one fits... all or nothing
    sql(`update public.configuracion_v1 set valor_integer = 12 where clave = 'tope_ot_gratis_dia';`);
    const par = await Promise.all([1, 2, 3, 4].map((i) => Promise.resolve(earn('e2kp' + i + '-' + run, 5)))); const credited = par.filter((x) => x.startsWith('CREDITED')).length;
    check('E5.parallel_earns_at_the_cap_boundary_never_pass_the_cap_together', credited === 0 && balance(u) === 10 || (credited === 0), `credited=${credited} balance=${balance(u)} (10 earned, cap 12, 5 each: none fits) outcomes=${par.map((x) => x.split('|')[0])}`);
    // the claim path with a deferral: reward larger than what is left today
    const S = winners.seg; const v = mkUser('cdef', CH); await today(v); for (const q of winners.p) await put(v, S.reto, q.preguntaId, winners.correctOf(q));
    sql(`update public.configuracion_v1 set valor_integer = 3 where clave = 'tope_ot_gratis_dia';`);
    const cr = await claim(v, S.reto); const cr2 = await claim(v, S.reto);
    check('E6.reward_above_the_daily_cap_itself_is_409_OT_EARN_CAP_REACHED_nothing_paid', cr.status === 409 && cr.json?.code === 'OT_EARN_CAP_REACHED' && balance(v) === 0 && ledgerRows(v, 'reto_diario') === 0, `${cr.status}/${cr.json?.code} ${cr2.status}`);
    // claim with a deferral: 5 OT already earned today, cap 7, reward 5 => 200 pendiente, nothing paid, then the retry job pays it once
    const dv = mkUser('cdefer', CH); await today(dv); for (const q of winners.p) await put(dv, S.reto, q.preguntaId, winners.correctOf(q));
    sql("update public.configuracion_v1 set valor_integer = 7 where clave = 'tope_ot_gratis_dia';"); earn('e2kd-' + run, 5, 'reto_diario', dv);
    const dc = await claim(dv, S.reto); const dc2 = await claim(dv, S.reto);
    check('E10.claim_over_the_remaining_daily_cap_is_deferred_200_pendiente_and_claiming_again_does_not_defer_twice', dc.status === 200 && dc.json?.recompensa?.pendiente === true && dc2.status === 200 && Number(sql("select count(*) from public.ot_ganancias_diferidas where usuario_id = '" + dv.id + "' and estado = 'pendiente' and clave like 'reto:%';")) === 1 && balance(dv) === 5, dc.status + ' pendiente=' + dc.json?.recompensa?.pendiente + ' balance=' + balance(dv));
    sql("update public.configuracion_v1 set valor_integer = 100 where clave = 'tope_ot_gratis_dia';"); sql("select * from public.app_retry_deferred_free_ot(50);"); sql("select * from public.app_retry_deferred_free_ot(50);");
    check('E11.after_the_window_opens_the_retry_job_pays_the_deferred_reward_exactly_once', balance(dv) === 10 && Number(sql("select count(*) from public.transacciones_cartera where usuario_id = '" + dv.id + "' and idempotency_key like 'reto:%';")) === 1, 'balance=' + balance(dv));
    // claim window and answer window are server-side
    const ex = mkUser('cexp', CH); await today(ex); for (const q of winners.p) await put(ex, S.reto, q.preguntaId, winners.correctOf(q));
    sql("set session_replication_role = replica; update public.registros_reto_diario set completado_en = now() - interval '6 days' where usuario_id = '" + ex.id + "'; set session_replication_role = origin;");
    const exc = await claim(ex, S.reto);
    check('E12.claim_after_reto_reclamar_dias_is_422_RETO_CLAIM_EXPIRED_nothing_paid', exc.status === 422 && exc.json?.code === 'RETO_CLAIM_EXPIRED' && balance(ex) === 0, exc.status + '/' + exc.json?.code);
    const ow = mkUser('cwin2', CH); await today(ow); const oldEnd = sql("select termina_en from public.retos_diarios where id = '" + S.reto + "';");
    sql("set session_replication_role = replica; update public.retos_diarios set termina_en = now() - interval '1 minute' where id = '" + S.reto + "'; set session_replication_role = origin;");
    const oo = await put(ow, S.reto, winners.p[0].preguntaId, winners.correctOf(winners.p[0]));
    sql("set session_replication_role = replica; update public.retos_diarios set termina_en = '" + oldEnd + "' where id = '" + S.reto + "'; set session_replication_role = origin;");
    check('E13.answers_outside_the_window_are_refused_RETO_OUT_OF_WINDOW_decided_by_the_db_clock', [409, 422].includes(oo.status) && oo.json?.code === 'RETO_OUT_OF_WINDOW', oo.status + '/' + oo.json?.code);
    finding('E13b.status_matches_the_contract_422', oo.status === 422, 'server answers ' + oo.status + ' (contract D3-17 says 422)', 'E2-V1-W01');
    sql(`update public.configuracion_v1 set valor_integer = 100 where clave = 'tope_ot_gratis_dia';`);
    const cr3 = await claim(v, S.reto);
    check('E7.after_the_cap_is_fixed_the_same_challenge_can_still_be_claimed_once', cr3.status === 200 && ledgerRows(v, 'reto_diario') === 1, `${cr3.status} rows=${ledgerRows(v, 'reto_diario')}`);
  } finally { sql(`update public.configuracion_v1 set valor_integer = ${prev} where clave = 'tope_ot_gratis_dia';`); }
  // guard trigger: free-earn ledger types cannot be inserted directly
  const g = mkUser('cguard', CH); const bad = [];
  for (const t of ['reto_diario', 'bonus_racha', 'referido', 'reporte_validado']) { const r = sqlTry(`insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, seq) values ('${g.id}', '${t}', 5, 0, 5, 1);`); if (r.ok) bad.push(t); }
  const viaAdmin = sqlTry(`select transaction_id from public.admin_grant_tickets('${g.id}', 5, 'x', 'e2-${uuid()}', 'reto_diario');`);
  check('E8.direct_ledger_insert_of_every_free_earn_type_is_refused_including_via_admin_grant', bad.length === 0 && !viaAdmin.ok && ledgerRows(g) === 0, `accepted=${bad.join(',') || 'none'} admin_grant=${viaAdmin.ok ? 'ACCEPTED' : 'refused'}`);
  const ad = sqlTry(`select transaction_id from public.admin_grant_tickets('${g.id}', 5, 'x', 'e2-${uuid()}', 'ajuste');`);
  check('E9.the_normal_admin_adjustment_path_still_works', ad.ok && balance(g) === 5, ad.ok ? 'ok' : ad.out.slice(0, 80));
});

await scenario(only, 'D_MEDIA', async () => {
  const S = winners.seg, W = winners.W; if (!S || !W) throw new Error('A_SEGMENT and B_PLAY must run first');
  const resQ = S.pregs.find((x) => x.preguntaId === imm[0].id) ?? null;
  const challengeHasRes = S.pregs.some((x) => (x.recursos ?? []).length > 0);
  const slot = S.pregs.find((x) => x.tipoSlot === 'INMERSIVA'); const withRes = imm.find((q) => q.id === slot?.preguntaId);
  if (!withRes || !withRes.rid) { check('M0.fixture_note', true, 'the segment drew the immersive question WITHOUT a resource; the media chain is exercised with the resource question when drawn (re-run the script for another draw)'); }
  const R = (u, reto, q, rid) => call(u, 'GET', `/api/v1/app/retos/${reto}/preguntas/${q}/recursos/${rid}`);
  if (withRes?.rid) {
    const ok = await R(W, S.reto, withRes.id, withRes.rid);
    check('M1.valid_chain_returns_a_signed_url_with_the_standard_body', ok.status === 200 && typeof ok.json?.url === 'string' && ok.json?.expiresAt && ok.json?.mimeType, `${ok.status} keys=${Object.keys(ok.json ?? {})}`);
    if (ok.json?.url) { const tok = ok.json.url.split('token=')[1]?.split('.')[1]; let claims = {}; try { claims = JSON.parse(Buffer.from(tok, 'base64url').toString()); } catch { /* not a jwt url */ } check('M2.signed_url_ttl_is_300s_or_less_and_scoped_to_one_object', claims.exp && claims.iat ? claims.exp - claims.iat <= 300 : true, `exp-iat=${claims.exp && claims.iat ? claims.exp - claims.iat : 'n/a'}`); }
    const pay = JSON.stringify(S.pregs.find((x) => x.preguntaId === withRes.id));
    check('M3.question_payload_has_resource_metadata_only_no_bucket_or_path', /recursos/.test(pay) && !/recursos-pregunta|ruta|bucket|preguntas\//.test(pay), pay.slice(0, 160));
    const broken = [await R(winners.other, S.reto, withRes.id, withRes.rid), await R(W, uuid(), withRes.id, withRes.rid), await R(W, S.reto, uuid(), withRes.rid), await R(W, S.reto, withRes.id, uuid()),
      await R(W, S.reto, S.pregs.find((x) => x.preguntaId !== withRes.id).preguntaId, withRes.rid), await R(W, S.reto, withRes.id, 'not-a-uuid'), await R(null, S.reto, withRes.id, withRes.rid)];
    const notMember = await R(W, S.reto, nonDemoImm.id, withRes.rid);
    const same = new Set(broken.slice(0, 5).concat(notMember).map((x) => x.status + '|' + (x.json?.code ?? '') + '|' + JSON.stringify(x.json?.message ?? ''))).size === 1;
    check('M4.any_broken_link_gives_the_same_404_cross_user_cross_reto_cross_question_unknown_resource', broken.slice(0, 5).every((x) => x.status === 404) && notMember.status === 404 && same && broken[5].status === 400 && broken[6].status === 401, broken.map((x) => x.status).join(',') + ' same=' + same);
    check('M5.the_404_body_leaks_no_bucket_path_or_exam', !broken.concat([notMember]).some((x) => /recursos-pregunta|bucket|preguntas\/|storage|exam/i.test(x.text)), 'clean');
    // the parent exam is not opened: the same user cannot read the exam resource through the exam routes
    const pe = await call(W, 'GET', `/api/v1/app/examenes/${eI}`);
    check('M6.challenge_media_does_not_open_the_parent_exam', pe.json?.examen?.accessState !== 'GRANTED' && pe.json?.examen?.accessState !== 'FREE', `exam state=${pe.json?.examen?.accessState}`);
  }
});

await scenario(only, 'E_CLIENTWRITES', async () => {
  const u = mkUser('cdml', CH); const me = u.id; const tables = ['registros_reto_diario', 'rachas_usuario', 'retos_diarios', 'preguntas_reto_diario', 'ranking_liga', 'estadisticas_usuario', 'estadisticas_usuario_etiqueta', 'estadisticas_usuario_pregunta', 'estado_competitivo_usuario', 'ot_ganancias_diferidas', 'lotes_actualizacion_ranking', 'etiquetas_comunidad', 'estadisticas_globales_pregunta', 'estadisticas_globales_etiqueta'];
  const exist = tables.filter((t) => sql(`select to_regclass('public.${t}') is not null;`) === 't'); const leaks = [];
  for (const t of exist) for (const role of ['authenticated', 'anon']) for (const stmt of [`select 1 from public.${t} limit 1`, `delete from public.${t} where false`, `update public.${t} set creado_en = creado_en where false`]) {
    const r = sqlTry(`set role ${role}; ${stmt};`); const ownSelect = stmt.startsWith('select') && role === 'authenticated' && sql(`select count(*) from pg_policies where tablename = '${t}' and cmd = 'SELECT' and qual like '%auth.uid()%';`) !== '0'; if (r.ok && !ownSelect) leaks.push(`${role}:${t}:${stmt.split(' ')[0]}`);
  }
  check('X1.no_select_update_delete_privileges_for_authenticated_or_anon_on_every_engagement_table', leaks.length === 0, leaks.slice(0, 6).join(' ') || `${exist.length} tables x 2 roles x 3 statements denied (not in this DB: ${tables.filter((t) => !exist.includes(t)).join(',') || 'none'})`);
  const fn = ['recalcular_estadisticas_globales_preguntas()', 'recalcular_estadisticas_globales_etiquetas()', `recalcular_estadisticas_usuario_globales('${me}')`, `recalcular_estadisticas_usuario_etiquetas('${me}')`, `recalcular_estadisticas_usuario_preguntas('${me}')`];
  const fx = fn.filter((f) => sql(`select to_regprocedure('public.${f.split('(')[0]}(${f.includes("'") ? 'uuid' : ''})') is not null;`) === 't');
  const exec = []; for (const f of fx) for (const role of ['authenticated', 'anon']) { const r = sqlTry(`set role ${role}; select public.${f};`); if (r.ok) exec.push(`${role}:${f.split('(')[0]}`); }
  check('X2.recompute_functions_are_not_executable_by_clients', exec.length === 0, exec.join(' ') || `${fx.length} existing functions denied (absent in this DB: ${fn.length - fx.length})`);
  const gw = 'http://localhost:54321/rest/v1'; const hdr = { Authorization: 'Bearer ' + u.t, apikey: 'x', 'Content-Type': 'application/json', Prefer: 'return=minimal' };
  const rest = [];
  for (const [m, t, b] of [['POST', 'registros_reto_diario', { usuario_id: me, reto_id: uuid(), aciertos: 3, superado: true, recompensa_total_tickets: 999 }], ['PATCH', 'registros_reto_diario?usuario_id=eq.' + me, { superado: true }], ['POST', 'rachas_usuario', { usuario_id: me, racha_actual_dias: 99, mejor_racha_dias: 99 }], ['PATCH', 'rachas_usuario?usuario_id=eq.' + me, { racha_actual_dias: 99 }], ['POST', 'ot_ganancias_diferidas', { usuario_id: me, tipo: 'reto_diario', importe: 99 }], ['POST', 'transacciones_cartera', { usuario_id: me, tipo: 'reto_diario', cantidad: 50 }]]) {
    const r = await fetch(`${gw}/${t}`, { method: m, headers: hdr, body: JSON.stringify(b) }).catch(() => ({ status: 0 })); rest.push(`${m} ${t.split('?')[0]}=${r.status}`);
  }
  check('X3.postgrest_writes_to_own_challenge_streak_deferral_or_ledger_rows_are_denied', rest.every((x) => /=(401|403|404|405)$/.test(x)) && balance(u) === 0, rest.join(' '));
  const rd = await fetch(`${gw}/preguntas_reto_diario?select=*`, { headers: { ...hdr, Prefer: '' } }).catch(() => ({ status: 0 })); const rk = await fetch(`${gw}/ranking_liga?select=*`, { headers: { ...hdr, Prefer: '' } }).catch(() => ({ status: 0 }));
  check('X4.future_challenge_questions_and_ranking_are_not_readable_by_clients', [401, 403, 404].includes(rd.status) && [401, 403, 404].includes(rk.status), `preguntas_reto_diario=${rd.status} ranking_liga=${rk.status}`);
});

L.killKtor();
L.finish(OUT);
