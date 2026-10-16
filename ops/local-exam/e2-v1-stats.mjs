#!/usr/bin/env node
// E2 V1 STATS / W3 (LOCAL ONLY): live attacks on the first-answer hook (exactly once), Nota media / Evolucion / league windows, weaknesses, question difficulty,
// admin recompute (cooldown, audit, non-admin), grants of every stats table and a write-path cost probe. Own users/exams/questions only. STACK_LOCK hard-fail.
// Own Ktor on :8091 (restarted by the script) for the app routes; the control Ktor (:8090, ADMIN token from the stack tokens.json, never printed) for D3-23.
// Shared config rows touched for a moment (recalculo_admin_cooldown_min) are restored in a finally block.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-stats.mjs [--only=NAME,...]     (STATUS: written, NOT RUN until the W3 migration 20261022101600 is converged on the stack)
import fs from 'node:fs';
import path from 'node:path';
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, start, page, answer, fin, uuid, run, comm, LOC, sleep } = L;

requireStackLock('e2-v1-stats.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-stats-result.json';
const ADM = process.env.ADMIN_KTOR_URL ?? 'http://127.0.0.1:8090';
const tokens = JSON.parse(fs.readFileSync(path.join(L.L, 'tokens.json'), 'utf8'));
if (sql(`select to_regprocedure('public.app_stats_summary(uuid)') is not null;`) !== 't') { console.error('W3 migration not applied on this DB: converge first'); process.exit(2); }
await L.restartKtor();

/** Free rafaga exam with n questions; tags: array of tag ids attached to every question (before any attempt: the revision snapshot carries them). */
function mkFree(n, tags = [], tipo = 'rafaga') {
  const ex = L.mkExam({ tipo, acceso: 'gratuito', n });
  for (const q of ex.qs) for (const t of tags) sql(`insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('${q.id}', '${t}') on conflict do nothing;`);
  return ex;
}
const mkTag = (name) => { const id = uuid(); sql(`insert into public.etiquetas (id, nombre, slug, activa) values ('${id}', 'E2 ${name} ${run}', 'e2-${name}-${run}-${id.slice(0, 4)}', true);`); return id; };
const sum = (u) => call(u, 'GET', '/api/v1/app/estadisticas/resumen');
const firsts = (u) => Number(sql(`select count(*) from public.primeras_respuestas_usuario where usuario_id = '${u.id}';`));
const totals = (u) => sql(`select coalesce((select preguntas_respondidas || '/' || respuestas_correctas from public.estadisticas_usuario where usuario_id = '${u.id}'), '0/0');`);
const ans = async (u, a, q, opt, base = 0) => { for (let i = 0; i < 4; i++) { const r = await answer(u, a, q, opt, base); if (r.status !== 429) return r; await sleep(1500); } return answer(u, a, q, opt, base); };
const refNota = (arr, n) => { const w = arr.slice(-n); if (!w.length) return null; return (Math.round((w.filter(Boolean).length / w.length) * 10 * 100) / 100); };

await scenario(only, 'A_EXACTLY_ONCE', async () => {
  const ex = mkFree(6); const u = mkUser('st1'); const s = await start(u, ex.id); const a = L.attemptId(s);
  const q = ex.qs;
  const r1 = await answer(u, a, q[0].id, q[0].ok); const key = uuid();
  const rk1 = await call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q[1].id}`, { opcionSeleccionadaId: q[1].ko, tiempoRespuestaMs: 900, baseRevision: 0 }, { 'Idempotency-Key': key });
  const rk2 = await call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q[1].id}`, { opcionSeleccionadaId: q[1].ko, tiempoRespuestaMs: 900, baseRevision: 0 }, { 'Idempotency-Key': key });
  check('A1.first_answers_count_once_idempotency_key_replay_included', r1.status === 200 && rk1.status === 200 && rk2.status === 200 && firsts(u) === 2 && totals(u) === '2/1', `first=${firsts(u)} totals=${totals(u)}`);
  const par = await Promise.all(Array.from({ length: 6 }, () => call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q[2].id}`, { opcionSeleccionadaId: q[2].ok, tiempoRespuestaMs: 700, baseRevision: 0 }, { 'Idempotency-Key': uuid() })));
  check('A2.six_parallel_identical_requests_with_different_keys_count_once', firsts(u) === 3 && totals(u) === '3/2', `statuses=${par.map((x) => x.status)} first=${firsts(u)} totals=${totals(u)}`);
  const ch = await answer(u, a, q[1].id, q[1].ok, 1);
  check('A3.changing_the_answer_inside_the_attempt_does_not_change_the_statistics_first_click_counts', firsts(u) === 3 && totals(u) === '3/2', `change=${ch.status} totals=${totals(u)}`);
  const skip = await call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q[3].id}`, { opcionSeleccionadaId: null, tiempoRespuestaMs: 100, baseRevision: 0 }, { 'Idempotency-Key': uuid() });
  const afterSkip = firsts(u); const real = await answer(u, a, q[3].id, q[3].ko, skip.json?.revision ?? 1);
  check('A4.a_skip_does_not_consume_the_first_answer_the_later_real_answer_counts_with_its_own_correctness', afterSkip === 3 && firsts(u) === 4 && totals(u) === '4/2', `skip=${skip.status} then ${real.status} totals=${totals(u)}`);
  await fin(u, a); const s2 = await start(u, ex.id); const a2 = L.attemptId(s2);
  await answer(u, a2, q[0].id, q[0].ko); await answer(u, a2, q[4].id, q[4].ok);
  check('A5.re_answering_a_question_in_a_second_attempt_never_counts_again_only_new_questions_do', firsts(u) === 5 && totals(u) === '5/3', `first=${firsts(u)} totals=${totals(u)}`);
  // demo, annulled, challenge, unused reserve
  const dm = L.mkExam({ tipo: 'inmersivo', price: 10, n: 7, demoFlags: [true, true, true, true, true, false, false] }); const du = mkUser('stdemo'); const ds = await start(du, dm.id, { modo: 'DEMO' }); const da = L.attemptId(ds);
  for (const x of dm.qs.slice(0, 5)) await ans(du, da, x.id, x.ok); await fin(du, da);
  check('A6.demo_answers_never_count', firsts(du) === 0 && totals(du) === '0/0', `first=${firsts(du)}`);
  const an = mkUser('stann'); const aex = mkFree(3); sql(`set session_replication_role = replica; update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = '${aex.id}' and pregunta_id = '${aex.qs[0].id}'; set session_replication_role = origin;`);
  const as = await start(an, aex.id); const aa = L.attemptId(as); await ans(an, aa, aex.qs[0].id, aex.qs[0].ok); await ans(an, aa, aex.qs[1].id, aex.qs[1].ok);
  check('A7.an_annulled_question_never_counts_a_normal_one_does', firsts(an) === 1, `first=${firsts(an)}`);
  const sm = await sum(u);
  check('A8.summary_totals_come_from_the_first_answers_only', sm.status === 200 && sm.json?.preguntasValidas === 5 && sm.json?.totales?.aciertos === 3 && sm.json?.totales?.fallos === 2, JSON.stringify(sm.json?.totales));
});

await scenario(only, 'B_NOTA_EVOLUCION_LEAGUE', async () => {
  const ex = mkFree(60); const u = mkUser('st2'); const s = await start(u, ex.id); const a = L.attemptId(s); const results = []; let pageCursor = null;
  const pattern = (i) => i % 3 !== 0; // 2 of 3 correct
  for (let i = 0; i < 60; i++) { const q = ex.qs[i]; const ok = pattern(i); const r = await ans(u, a, q.id, ok ? q.ok : q.ko); if (r.status === 200) results.push(ok);
    if (i === 11) { const m = (await sum(u)).json; check('B1.nota_media_over_12_first_answers_is_the_exact_ratio_and_provisional', Math.abs(m?.notaMedia?.valor - refNota(results, 50)) < 0.011 && m?.notaMedia?.provisional === true && m?.notaMedia?.respondidas === 12, JSON.stringify(m?.notaMedia)); }
    if (i === 24) { const m = (await sum(u)).json; check('B2.evolucion_first_reference_after_20_then_progress_counts_from_the_server', m?.evolucion?.cada === 20 && (m?.evolucion?.referencia != null) && m?.evolucion?.progreso === 5, JSON.stringify(m?.evolucion)); }
  }
  void pageCursor;
  const m = (await sum(u)).json; const expected = refNota(results, 50);
  check('B3.after_60_answers_the_window_is_the_last_50_and_the_grade_matches_the_exact_reference', results.length === 60 && Math.abs(m?.notaMedia?.valor - expected) < 0.011 && m?.notaMedia?.ventana >= 50, `served=${m?.notaMedia?.valor} ref=${expected} window=${m?.notaMedia?.ventana} league=${m?.liga?.codigo}`);
  check('B4.league_is_the_highest_tier_by_first_answers_and_the_next_one_is_reported', m?.preguntasValidas === 60 && (m?.liga?.preguntasMinimas ?? 0) <= 60 && (m?.liga?.siguiente?.preguntasMinimas ?? 9999) > 60, JSON.stringify(m?.liga));
  const dbLeague = sql(`select ln.preguntas_minimas from public.estado_competitivo_usuario e join public.ligas_nivel ln on ln.id = e.liga_nivel_id where e.usuario_id = '${u.id}';`);
  check('B5.league_never_goes_down_when_the_window_changes', Number(dbLeague) <= 60, `tier=${dbLeague}`);
});

await scenario(only, 'C_WEAKNESSES', async () => {
  const tagA = mkTag('tagA'), tagB = mkTag('tagB'); const exA = mkFree(12, [tagA]), exB = mkFree(4, [tagB]); const u = mkUser('st3'); const o = mkUser('st3o');
  const sa = await start(u, exA.id); const aa = L.attemptId(sa);
  for (let i = 0; i < 12; i++) await ans(u, aa, exA.qs[i].id, i < 3 ? exA.qs[i].ok : exA.qs[i].ko);
  const sb = await start(u, exB.id); const ab = L.attemptId(sb); for (let i = 0; i < 4; i++) await ans(u, ab, exB.qs[i].id, exB.qs[i].ok); await fin(u, aa); await fin(u, ab);
  const w = await call(u, 'GET', '/api/v1/app/estadisticas/debilidades'); const items = w.json?.items ?? [];
  const ta = items.find((x) => x.etiquetaId === tagA), tb = items.find((x) => x.etiquetaId === tagB);
  check('C1.total_scope_lists_tags_with_enough_answers_with_exact_percent_and_server_level_and_hides_small_samples', w.status === 200 && w.json?.alcance === 'TOTAL' && ta && ta.respondidas === 12 && ta.correctas === 3 && Math.abs(ta.porcentajeAcierto - 25) < 0.01 && ta.nivelDominio === 'bajo' && !tb, JSON.stringify(w.json).slice(0, 220));
  const we = await call(u, 'GET', `/api/v1/app/estadisticas/debilidades?examenId=${exA.id}`); const ei = we.json?.items?.find((x) => x.etiquetaId === tagA);
  check('C2.exam_scope_uses_the_finished_attempts_of_that_exam_only', we.json?.alcance === 'EXAMEN' && ei?.respondidas === 12 && ei?.correctas === 3, JSON.stringify(we.json?.items?.map((x) => [x.etiquetaId === tagA, x.respondidas])));
  const wo = await call(o, 'GET', `/api/v1/app/estadisticas/debilidades?examenId=${exA.id}`); const wo2 = await call(o, 'GET', '/api/v1/app/estadisticas/debilidades');
  check('C3.another_user_sees_none_of_my_numbers_even_with_my_exam_id', (wo.json?.items ?? []).length === 0 && (wo2.json?.items ?? []).length === 0, `${wo.status}/${wo2.status}`);
  const bad = [await call(u, 'GET', '/api/v1/app/estadisticas/debilidades?top=0'), await call(u, 'GET', '/api/v1/app/estadisticas/debilidades?top=51'), await call(u, 'GET', '/api/v1/app/estadisticas/debilidades?top=abc'), await call(u, 'GET', '/api/v1/app/estadisticas/debilidades?limit=5'),
    await call(u, 'GET', '/api/v1/app/estadisticas/debilidades?examenId=not-a-uuid'), await call(u, 'GET', `/api/v1/app/estadisticas/debilidades?usuarioId=${o.id}`), await call(u, 'GET', '/api/v1/app/estadisticas/resumen?usuarioId=' + o.id)];
  check('C4.bad_top_unknown_parameters_malformed_ids_and_user_id_parameters_are_400', bad.every((x) => x.status === 400), bad.map((x) => x.status).join(','));
  const noauth = await call(null, 'GET', '/api/v1/app/estadisticas/resumen'); const garbage = await call('x.y.z', 'GET', '/api/v1/app/estadisticas/resumen');
  check('C5.no_token_401', noauth.status === 401 && garbage.status === 401, `${noauth.status}/${garbage.status}`);
});

await scenario(only, 'D_DIFFICULTY_AND_ADMIN', async () => {
  const ex = mkFree(3); const users = Array.from({ length: 22 }, (_, i) => mkUser('std' + i)); const prev = sql(`select valor_integer from public.configuracion_v1 where clave = 'recalculo_admin_cooldown_min';`);
  try {
    for (const [i, u] of users.entries()) { const s = await start(u, ex.id); const a = L.attemptId(s); await ans(u, a, ex.qs[0].id, i % 4 === 0 ? ex.qs[0].ok : ex.qs[0].ko); if (i < 3) await ans(u, a, ex.qs[1].id, ex.qs[1].ok); }
    const early = await call(users[0], 'GET', `/api/v1/app/estadisticas/dificultad/${ex.qs[0].id}`);
    check('D1.below_the_minimum_sample_the_difficulty_is_null_not_a_guess', early.status === 200 && early.json?.dificultad == null, JSON.stringify(early.json));
    const nobody = mkUser('stdn'); const probe = await call(nobody, 'GET', `/api/v1/app/estadisticas/dificultad/${ex.qs[0].id}`), unknown = await call(nobody, 'GET', `/api/v1/app/estadisticas/dificultad/${uuid()}`), malformed = await call(nobody, 'GET', '/api/v1/app/estadisticas/dificultad/not-a-uuid');
    check('D2.difficulty_only_for_questions_the_caller_answered_same_404_as_unknown_questions', probe.status === 404 && unknown.status === 404 && probe.json?.code === unknown.json?.code && malformed.status === 400 && !/dificultad/.test(probe.text), `${probe.status}/${unknown.status}/${malformed.status}`);
    sql(`update public.configuracion_v1 set valor_integer = 1 where clave = 'recalculo_admin_cooldown_min';`);
    const adm = (m, b, tok = tokens.ADMIN) => fetch(ADM + '/api/v1/admin/estadisticas/recalcular', { method: m, headers: { Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json' }, body: b === undefined ? undefined : JSON.stringify(b) }).then(async (r) => ({ status: r.status, text: await r.text() }));
    const nonadmin = await adm('POST', { alcance: 'dificultad' }, users[0].t), noTok = await fetch(ADM + '/api/v1/admin/estadisticas/recalcular', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"alcance":"dificultad"}' });
    check('D3.recompute_is_admin_only_user_token_403_no_token_401', nonadmin.status === 403 && noTok.status === 401, `${nonadmin.status}/${noTok.status}`);
    const a0 = Number(sql(`select count(*) from public.auditoria_log where accion = 'estadisticas_recalculo';`));
    const par = await Promise.all(Array.from({ length: 8 }, () => adm('POST', { alcance: 'dificultad' })));
    const okN = par.filter((x) => x.status === 200).length, cdN = par.filter((x) => x.status === 429).length;
    check('D4.eight_parallel_admin_recomputes_run_once_the_rest_hit_the_cooldown_429_STATS_RECOMPUTE_COOLDOWN_and_one_audit_row', okN === 1 && cdN === 7 && /STATS_RECOMPUTE_COOLDOWN/.test(par.find((x) => x.status === 429)?.text ?? '') && Number(sql(`select count(*) from public.auditoria_log where accion = 'estadisticas_recalculo';`)) === a0 + 1, `ok=${okN} cooldown=${cdN}`);
    const sc = await adm('POST', { alcance: 'todo' }); const ex2 = await adm('POST', { alcance: 'dificultad', userId: users[0].id });
    check('D5.invalid_scope_and_extra_fields_are_refused', [400, 422, 429].includes(sc.status) && [400, 422, 429].includes(ex2.status), `${sc.status}/${ex2.status}`);
    const after = await call(users[0], 'GET', `/api/v1/app/estadisticas/dificultad/${ex.qs[0].id}`); const second = await call(users[0], 'GET', `/api/v1/app/estadisticas/dificultad/${ex.qs[1].id}`);
    check('D6.after_the_job_a_question_with_22_answers_has_a_1_to_5_difficulty_and_one_with_3_answers_stays_null', after.json?.dificultad >= 1 && after.json?.dificultad <= 5 && second.json?.dificultad == null, `${after.json?.dificultad} / ${second.json?.dificultad}`);
    const job = sqlTry(`select public.app_job_dificultad(false);`);
    check('D7.the_periodic_job_respects_its_throttle_and_the_advisory_lock', job.ok && /RECIENTE|OCUPADO|OK/.test(job.out), job.out.slice(0, 80));
    const lat = []; for (let i = 0; i < 20; i++) { const t0 = performance.now(); await call(users[1], 'GET', `/api/v1/app/estadisticas/dificultad/${ex.qs[i % 2 === 0 ? 0 : 1].id}`); lat.push(performance.now() - t0); }
    const nlat = []; for (let i = 0; i < 20; i++) { const t0 = performance.now(); await call(nobody, 'GET', `/api/v1/app/estadisticas/dificultad/${ex.qs[0].id}`); nlat.push(performance.now() - t0); }
    const med = (x) => x.slice().sort((a, b) => a - b)[Math.floor(x.length / 2)];
    finding('D8.timing_does_not_distinguish_answered_from_unanswered_questions_beyond_noise', Math.abs(med(lat) - med(nlat)) < 25, `median answered=${med(lat).toFixed(1)}ms unanswered=${med(nlat).toFixed(1)}ms`, 'E2-V1-T01');
  } finally { sql(`update public.configuracion_v1 set valor_integer = ${prev} where clave = 'recalculo_admin_cooldown_min';`); }
});

await scenario(only, 'E_GRANTS', async () => {
  const tables = ['primeras_respuestas_usuario', 'estadisticas_usuario', 'estadisticas_usuario_etiqueta', 'estadisticas_globales_pregunta', 'estadisticas_globales_etiqueta', 'estado_competitivo_usuario', 'lotes_actualizacion_ranking', 'ranking_liga', 'ot_ganancias_diferidas'];
  const leaks = []; const u = mkUser('st4');
  for (const t of tables.filter((x) => sql(`select to_regclass('public.${x}') is not null;`) === 't')) for (const role of ['authenticated', 'anon']) for (const st of [`insert into public.${t} select * from public.${t} limit 0`, `delete from public.${t} where false`, `select 1 from public.${t} limit 1`]) {
    const r = sqlTry(`set role ${role}; ${st};`); const ownSel = st.startsWith('select') && role === 'authenticated' && sql(`select count(*) from pg_policies where tablename = '${t}' and cmd = 'SELECT' and qual like '%auth.uid()%';`) !== '0'; if (r.ok && !ownSel) leaks.push(`${role}:${t}:${st.split(' ')[0]}`);
  }
  check('G1.no_client_privileges_on_any_stats_table_only_own_row_select_policies_where_they_exist', leaks.length === 0, leaks.slice(0, 6).join(' ') || 'denied');
  const fx = ['public.app_stats_summary(uuid)', 'public.app_stats_weaknesses(uuid, uuid, integer)', 'public.app_stats_difficulty(uuid, uuid)', 'public.app_job_dificultad(boolean)', 'public.admin_recompute_stats(uuid, text)', 'private.app_apply_first_answer_stats(uuid, uuid, boolean, integer, uuid)'];
  const ex = []; for (const f of fx) for (const role of ['authenticated', 'anon']) { const r = sqlTry(`set role ${role}; select has_function_privilege('${role}', '${f}', 'execute');`); if (r.ok && /t$/.test(r.out.trim())) ex.push(`${role}:${f.split('(')[0]}`); }
  check('G2.stats_functions_are_not_executable_by_clients', ex.length === 0, ex.join(' ') || 'none executable');
  const gw = 'http://localhost:54321/rest/v1'; const hdr = { Authorization: 'Bearer ' + u.t, apikey: 'x', 'Content-Type': 'application/json', Prefer: 'return=minimal' }; const rest = [];
  for (const [m, t, b] of [['POST', 'estadisticas_usuario', { usuario_id: u.id, preguntas_respondidas: 999, respuestas_correctas: 999 }], ['POST', 'primeras_respuestas_usuario', { usuario_id: u.id, pregunta_id: uuid(), correcta: true }], ['POST', 'estado_competitivo_usuario', { usuario_id: u.id, preguntas_validas_ranking: 9999 }], ['GET', 'estadisticas_globales_pregunta?select=*', undefined]]) {
    const r = await fetch(`${gw}/${t}`, { method: m, headers: hdr, body: b ? JSON.stringify(b) : undefined }).catch(() => ({ status: 0 })); rest.push(`${m} ${t.split('?')[0]}=${r.status}`);
  }
  check('G3.postgrest_cannot_write_own_stats_or_read_the_global_question_statistics', rest.every((x) => /=(401|403|404|405)$/.test(x)), rest.join(' '));
});

await scenario(only, 'F_WRITE_PATH_COST', async () => {
  const ex = mkFree(40); const seq = mkUser('st5'); const s = await start(seq, ex.id); const a = L.attemptId(s); const t = [];
  for (let i = 0; i < 40; i++) { const t0 = performance.now(); const r = await ans(seq, a, ex.qs[i].id, ex.qs[i].ok); if (r.status === 200) t.push(performance.now() - t0); }
  const p = (x, q) => x.slice().sort((m, n) => m - n)[Math.min(x.length - 1, Math.floor(x.length * q))];
  // 20 users answer the SAME question at once (hot row on the global question counters)
  const hot = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 1 }); const us = Array.from({ length: 20 }, (_, i) => mkUser('sth' + i)); const as = await Promise.all(us.map((u) => start(u, hot.id)));
  const t0 = performance.now(); const hr = await Promise.all(us.map((u, i) => { const x0 = performance.now(); return answer(u, L.attemptId(as[i]), hot.qs[0].id, hot.qs[0].ok).then((r) => ({ r, ms: performance.now() - x0 })); })); const total = performance.now() - t0;
  const ok = hr.filter((x) => x.r.status === 200); const hl = ok.map((x) => x.ms);
  const cnt = sql(`select total_respuestas from public.estadisticas_globales_pregunta where pregunta_id = '${hot.qs[0].id}';`);
  check('P1.twenty_users_answering_the_same_question_at_once_all_count_exactly_once', ok.length === 20 && Number(cnt) === 20 && Number(sql(`select count(*) from public.primeras_respuestas_usuario where pregunta_id = '${hot.qs[0].id}';`)) === 20, `ok=${ok.length} counter=${cnt}`);
  finding('P2.write_path_latency_p95_with_the_stats_hook_stays_under_250ms_sequential_and_under_1500ms_on_a_hot_question', p(t, 0.95) < 250 && p(hl, 0.95) < 1500, `sequential p50=${p(t, 0.5).toFixed(0)}ms p95=${p(t, 0.95).toFixed(0)}ms | hot question 20 parallel p50=${p(hl, 0.5).toFixed(0)}ms p95=${p(hl, 0.95).toFixed(0)}ms total=${total.toFixed(0)}ms`, 'E2-V1-T02');
});

L.killKtor();
L.finish(OUT);
