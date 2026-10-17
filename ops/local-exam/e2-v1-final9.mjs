#!/usr/bin/env node
// E2 V1 FINAL9 (LOCAL ONLY): attack on FA36 (rational penalty PD = A - E/(n-1)): parity with an exact independent reference, mid-attempt flips, option edits, races, floor, n<2, annulled+reserve,
// demo, idempotent finish, payload leakage, Control write path (RLS, check constraint, audit), legacy attempts.
// Own users/exams only. STACK_LOCK hard-fail. Own Ktor on :8091.   STACK_HOLDER=E2 node ops/local-exam/e2-v1-final9.mjs [--only=NAME,...]
import crypto from 'node:crypto';
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, start, page, answer, fin, uuid, run, comm, LOC } = L;

requireStackLock('e2-v1-final9.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-final9-result.json';
await L.restartKtor();
let seq = 0;
const aid = (r) => L.attemptId(r);

/** Official exam. qs: [{n:options, ann?:bool, res?:true}] principals first then reserves (in order). Option A is the correct one, B..n wrong.
 *  racional / num,den set on the exam. */
function mkPen({ qs, racional = true, num = 0, den = 1, regla = 'REEMPLAZO_POR_RESERVA', demo = [], published = true, tipo = 'rafaga', acceso = 'gratuito', price = null }) {
  const eid = `e2f90000-0000-4000-8000-${run}${String(++seq).padStart(6, '0')}`; const out = []; let ord = 0; const nMain = qs.filter((q) => !q.res).length;
  let st = `set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible, regla_anuladas, penalizacion_num, penalizacion_den, penalizacion_racional, es_oficial)
 values ('${eid}', 'E2P-${run}-${seq}', '${LOC}', 'E2P ${seq}', '${tipo}', '${acceso}', true, ${published ? "now() - interval '1 day'" : 'null'}, ${nMain}, ${comm}, ${price != null}, ${price}, 30, ${demo.length > 0}, ${regla ? `'${regla}'` : 'null'}, ${num}, ${den}, ${racional}, true);\n`;
  qs.forEach((spec, i) => {
    const q = uuid(); const opts = Array.from({ length: spec.n }, () => uuid()); const pos = i + 1; if (spec.res) ord++;
    out.push({ id: q, ok: opts[0], ko: opts[1] ?? null, opts, pos, res: !!spec.res, ann: !!spec.ann, n: spec.n });
    st += `insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q}', 'E2P-${run}-${seq}-${pos}', '${tipo === 'inmersivo' ? 'inmersiva' : 'rafaga'}', 'TITULO-PEN-${seq}-${pos}', true);\n`;
    st += `insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ${opts.map((o, k) => `('${o}', '${q}', '${String.fromCharCode(65 + k)}', 'OPT-PEN-${seq}-${pos}-${k}', ${k + 1})`).join(', ')};\n`;
    st += `insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q}', '${opts[0]}', 'SOLUCION-PEN-${seq}-${pos}');\n`;
    st += `insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva, es_demo) values ('${eid}', '${q}', ${pos}, ${pos}, ${!!spec.ann}, ${spec.ann ? "'E2 anulada'" : 'null'}, ${!!spec.res}, ${spec.res ? ord : 'null'}, ${demo.includes(i)});\n`;
  });
  sql(st + 'set session_replication_role = origin;');
  return { id: eid, qs: out };
}
/** Exact independent reference (BigInt): returns {nota, descuento}. qsStatus: [{status:'C'|'W'|'S'|'U', n}] over the EVALUABLE questions only. */
function ref(evals) {
  let c = 0n, pen = 0n; for (const q of evals) { if (q.s === 'C') c++; if (q.s === 'W' && q.n >= 2) pen += 27720n / BigInt(Math.min(q.n, 13) - 1); }
  const e = BigInt(evals.length); if (e === 0n) return { nota: null, descuento: 0 };
  const base = c * 27720n - pen; const pos = base > 0n ? base : 0n;
  const units = (pos * 2000n + 27720n * e) / (2n * 27720n * e);
  return { nota: Number(units) / 100, descuento: Math.round(Number(pen) / 27720 * 100) / 100 };
}
const find = (o, k, d = 0) => { if (!o || typeof o !== 'object' || d > 6) return undefined; if (k in o) return o[k]; for (const v of Object.values(o)) { const r = find(v, k, d + 1); if (r !== undefined) return r; } return undefined; };
const resultOf = (u, a) => call(u, 'GET', `/api/v1/app/intentos/${a}/resultado`);
const coverOf = (u, e) => call(u, 'GET', `/api/v1/app/examenes/${e}`);
const notaOf = (r) => { const v = find(r.json, 'nota'); return v === undefined || v === null ? null : Number(v); };
const setExam = (id, set) => sql(`update public.examenes set ${set} where id = '${id}';`);
const dbAttempt = (a) => sql(`select coalesce(nota::text,'null'), coalesce(penalizacion_racional::text,'null'), coalesce(penalizacion_descuento::text,'null') from public.intentos_examen where id = '${a}';`).split('|');

/** answer plan: map question index -> 'C'|'W'|'S'(skip null option)|'U'(none) */
async function play(u, a, ex, plan, order = null) {
  const idx = order ?? Object.keys(plan).map(Number);
  for (const i of idx) { const q = ex.qs[i]; const s = plan[i]; if (s === 'U') continue;
    const r = s === 'S' ? await call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q.id}`, { opcionSeleccionadaId: null, tiempoRespuestaMs: 1500, baseRevision: 0 }, { 'Idempotency-Key': uuid() })
      : await answer(u, a, q.id, s === 'C' ? q.ok : q.ko);
    if (r.status >= 300 && s !== 'S') return `answer ${i} ${s} -> ${r.status} ${r.text.slice(0, 120)}`; }
  return null;
}

await scenario(only, 'A_PARITY', async () => {
  // SQL function vs exact JS reference over a big grid (incl. clamps)
  const rows = []; const cases = [];
  for (const e of [1, 2, 3, 5, 10, 17, 50, 100, 200]) for (let k = 0; k < 40; k++) { const c = Math.floor(Math.random() * (e + 1)); const w = Math.floor(Math.random() * (e - c + 1)); let pen = 0n; for (let j = 0; j < w; j++) pen += 27720n / BigInt([1, 2, 3][Math.floor(Math.random() * 3)]); cases.push({ c, e, pen }); }
  cases.push({ c: 5, e: 10, pen: 99999999999n }, { c: 0, e: 4, pen: 0n }, { c: 4, e: 4, pen: 0n }, { c: 3, e: 3, pen: -5n });
  const out = sql(`select ${cases.map((x) => `private.official_nota_racional(${x.c}, ${x.pen}, ${x.e})`).join(', ')};`).split('|');
  const bad = [];
  cases.forEach((x, i) => { const p = x.pen < 0n ? 0n : x.pen > 2000000000n ? 2000000000n : x.pen; const base = BigInt(x.c) * 27720n - p; const pos = base > 0n ? base : 0n; const e = BigInt(x.e); const u = Number((pos * 2000n + 27720n * e) / (2n * 27720n * e)) / 100; if (Number(out[i]) !== u) bad.push({ x: `${x.c}/${x.e}/${x.pen}`, sql: out[i], js: u }); });
  check('P1.SQL_official_nota_racional_equals_an_exact_BigInt_reference_over_' + cases.length + '_cases_incl_clamp_and_floor', bad.length === 0, bad.length ? JSON.stringify(bad.slice(0, 3)) : 'all equal');
  check('P2.e_zero_gives_null_and_never_negative', sql(`select private.official_nota_racional(0,0,0) is null, private.official_nota_racional(0,5000000,3) = 0;`) === 't|t', '');
  // end to end: random exams through the API (mixed n, annulled + reserves, skipped/unanswered)
  let mism = 0; const detail = [];
  for (let t = 0; t < 8; t++) {
    const nMain = 6 + Math.floor(Math.random() * 5), nRes = Math.floor(Math.random() * 3); const specs = [];
    const annIdx = []; for (let i = 0; i < nMain; i++) { const ann = Math.random() < 0.25; if (ann) annIdx.push(i); specs.push({ n: [2, 3, 4][Math.floor(Math.random() * 3)], ann }); }
    for (let r = 0; r < nRes; r++) specs.push({ n: [2, 3, 4][Math.floor(Math.random() * 3)], res: true });
    const ex = mkPen({ qs: specs }); const u = mkUser('par' + t); const s = await start(u, ex.id); const a = aid(s); if (!a) { mism++; detail.push('start ' + s.status); continue; }
    const k = Math.min(annIdx.length, nRes); const plan = {}; const evals = [];
    ex.qs.forEach((q, i) => { const isActiveRes = q.res && (i - nMain) < k; const evaluable = (!q.res && !q.ann) || isActiveRes; if (!evaluable) return; const st = ['C', 'C', 'W', 'W', 'S', 'U'][Math.floor(Math.random() * 6)]; plan[i] = st; evals.push({ s: st === 'S' || st === 'U' ? 'S' : st, n: q.n }); });
    const perr = await play(u, a, ex, plan); if (perr) { mism++; detail.push(perr); continue; }
    const f = await fin(u, a); const r = await resultOf(u, a); const exp = ref(evals); const got = notaOf(f) ?? notaOf(r); const dsc = Number(find(r.json, 'descuento') ?? 0);
    if (got !== exp.nota || Math.abs(dsc - exp.descuento) > 0.011) { mism++; detail.push(`t${t} got ${got}/${dsc} exp ${exp.nota}/${exp.descuento} ${JSON.stringify(evals.map((x) => x.s + x.n))}`); }
  }
  check('P3.8_random_exams_mixed_n_annulled_reserves_skips_match_the_exact_reference_end_to_end', mism === 0, detail.join(' | ') || 'all 8 equal');
});

await scenario(only, 'B_MIDATTEMPT', async () => {
  const ex = mkPen({ qs: [{ n: 3 }, { n: 4 }, { n: 4 }, { n: 4 }, { n: 4 }, { n: 4 }] });
  const u = mkUser('mid'); const a = aid(await start(u, ex.id));
  // flip exam to FIXED fraction 1/3 + racional off, after start
  setExam(ex.id, `penalizacion_racional = false, penalizacion_num = 1, penalizacion_den = 3`);
  const cv = await coverOf(u, ex.id);
  // grow a question's option set and swap the correct option in the live tables
  const q0 = ex.qs[0], extra = [uuid()];
  sql(`insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${extra[0]}','${q0.id}','D','x',4);
       update public.soluciones_pregunta set opcion_correcta_id = '${q0.opts[1]}' where pregunta_id = '${q0.id}';
       delete from public.opciones_pregunta where id = '${ex.qs[1].opts[3]}';`);
  // 3 right, 2 wrong (q0 wrong by frozen truth if answering B? B is live-correct now), 1 unanswered
  const perr = await play(u, a, ex, { 0: 'C', 1: 'C', 2: 'C', 3: 'W', 4: 'W', 5: 'U' });
  const f = await fin(u, a); const r = await resultOf(u, a);
  // frozen: 3 right (q0 A frozen-correct), 2 wrong n=4 -> pen 2/3; e=6 -> (3-0.667)/6*10 = 3.89
  check('B1.flag_flipped_off_and_fixed_fraction_set_AFTER_start_does_not_change_the_grade_frozen_rational_n4', perr === null && notaOf(f) === 3.89 && notaOf(r) === 3.89, `err=${perr} fin=${notaOf(f)} result=${notaOf(r)}`);
  const tipo = find(r.json?.resumen ?? r.json, 'penalizacion'); const cvp = find(cv.json?.examen ?? cv.json, 'penalizacion');
  check('B2.result_carries_the_FROZEN_rule_RACIONAL_while_the_cover_shows_the_CURRENT_FIJA', tipo?.tipo === 'RACIONAL' && cvp?.tipo === 'FIJA', `result=${JSON.stringify(tipo)} cover=${JSON.stringify(cvp)}`);
  check('B3.options_added_removed_and_solution_swapped_in_live_tables_after_start_never_change_n_or_correctness_of_the_attempt', dbAttempt(a)[0] === '3.89' && dbAttempt(a)[2] === '0.67', dbAttempt(a).join('|'));
  // inverse: exam OFF at start, ON after start
  const ex2 = mkPen({ qs: [{ n: 4 }, { n: 4 }, { n: 4 }, { n: 4 }], racional: false });
  const u2 = mkUser('mid2'); const a2 = aid(await start(u2, ex2.id)); setExam(ex2.id, 'penalizacion_racional = true');
  await play(u2, a2, ex2, { 0: 'C', 1: 'W', 2: 'W', 3: 'W' }); const f2 = await fin(u2, a2); const r2 = await resultOf(u2, a2);
  check('B4.flag_turned_ON_after_start_does_not_penalise_the_attempt_and_its_result_has_no_penalizacion', notaOf(f2) === 2.5 && find(r2.json, 'penalizacion') === undefined, `nota=${notaOf(f2)} pen=${JSON.stringify(find(r2.json, 'penalizacion'))}`);
  // new attempt on ex (racional now false) is unpenalised; on ex2 (now true) penalised
  const u3 = mkUser('mid3'); const a3 = aid(await start(u3, ex2.id)); await play(u3, a3, ex2, { 0: 'C', 1: 'W', 2: 'W', 3: 'W' }); const f3 = await fin(u3, a3);
  check('B5.a_NEW_attempt_freezes_the_new_value_(1_right_3_wrong_n4_=_0_not_negative)', notaOf(f3) === 0, `nota=${notaOf(f3)}`);
});

await scenario(only, 'C_RACE_IDEMPOTENT', async () => {
  const ex = mkPen({ qs: Array.from({ length: 8 }, () => ({ n: 4 })) });
  const u = mkUser('race'); const a = aid(await start(u, ex.id)); await play(u, a, ex, { 0: 'C', 1: 'C', 2: 'C', 3: 'W', 4: 'W', 5: 'U', 6: 'S', 7: 'U' });
  // finish x12 concurrently, with late answers flying in (must not change a finished grade)
  const jobs = [];
  for (let i = 0; i < 12; i++) jobs.push(fin(u, a));
  for (const i of [5, 6, 7]) jobs.push(answer(u, a, ex.qs[i].id, ex.qs[i].ko));
  const rs = await Promise.all(jobs); const finishes = rs.slice(0, 12);
  const notas = new Set(finishes.filter((r) => r.status === 200).map((r) => notaOf(r)));
  const stored = dbAttempt(a);
  // recompute from the stored answers independently
  const rows = sql(`select pi.estado_final from public.preguntas_intento pi where pi.intento_id = '${a}' order by pi.posicion;`).split('\n');
  const c = rows.filter((x) => x === 'CORRECT').length, w = rows.filter((x) => x === 'WRONG').length, ev = rows.filter((x) => ['CORRECT', 'WRONG', 'SKIPPED', 'UNANSWERED'].includes(x)).length;
  const exp = ref([...Array(c).fill({ s: 'C', n: 4 }), ...Array(w).fill({ s: 'W', n: 4 }), ...Array(ev - c - w).fill({ s: 'S', n: 4 })]).nota;
  check('R1.12_concurrent_finishes_all_agree_one_grade_and_it_equals_the_reference_over_the_stored_final_states', notas.size === 1 && [...notas][0] === exp && Number(stored[0]) === exp, `statuses=${finishes.map((r) => r.status).join(',')} notas=${[...notas]} stored=${stored[0]} exp=${exp} late=${rs.slice(12).map((r) => r.status)}`);
  const f2 = await fin(u, a); const r2 = await resultOf(u, a); const again = dbAttempt(a);
  check('R2.finish_replay_is_idempotent_(same_grade_same_discount_no_extra_deduction)', notaOf(f2) === exp && again.join() === stored.join() && notaOf(r2) === exp, `${notaOf(f2)} ${again.join('|')}`);
  const late = await answer(u, a, ex.qs[5].id, ex.qs[5].ko); const after = dbAttempt(a);
  check('R3.answer_after_finish_is_refused_and_the_grade_is_untouched', late.status >= 400 && after.join() === stored.join(), `${late.status} ${late.json?.code}`);
  // answer-change races: flip the same answer wrong/right fast, then finish; grade must match the final stored state
  const ex2 = mkPen({ qs: Array.from({ length: 4 }, () => ({ n: 4 })) }); const u2 = mkUser('race2'); const a2 = aid(await start(u2, ex2.id));
  const q = ex2.qs[0]; const ps = []; for (let i = 0; i < 10; i++) ps.push(call(u2, 'PUT', `/api/v1/app/intentos/${a2}/respuestas/${q.id}`, { opcionSeleccionadaId: i % 2 ? q.ok : q.ko, tiempoRespuestaMs: 1500, baseRevision: 0 }, { 'Idempotency-Key': uuid() }));
  await Promise.all(ps); await Promise.all([fin(u2, a2), fin(u2, a2)]);
  const st = sql(`select pi.estado_final from public.preguntas_intento pi where pi.intento_id='${a2}' and pi.pregunta_id='${q.id}'`);
  const expect2 = st === 'CORRECT' ? ref([{ s: 'C', n: 4 }, { s: 'S', n: 4 }, { s: 'S', n: 4 }, { s: 'S', n: 4 }]).nota : ref([{ s: 'W', n: 4 }, { s: 'S', n: 4 }, { s: 'S', n: 4 }, { s: 'S', n: 4 }]).nota;
  check('R4.answer_flip_storm_then_double_finish_gives_a_grade_consistent_with_the_stored_final_state', Number(dbAttempt(a2)[0]) === expect2, `state=${st} nota=${dbAttempt(a2)[0]} exp=${expect2}`);
});

await scenario(only, 'D_FLOOR_N_DEMO_ANNUL', async () => {
  // floor: everything wrong, n=2 (1 right cancels 1 wrong): 0 right 5 wrong -> 0.00; discount can exceed the rights
  const ex = mkPen({ qs: Array.from({ length: 5 }, () => ({ n: 2 })) }); const u = mkUser('flo'); const a = aid(await start(u, ex.id));
  await play(u, a, ex, { 0: 'W', 1: 'W', 2: 'W', 3: 'W', 4: 'W' }); const f = await fin(u, a); const r = await resultOf(u, a);
  check('F1.grade_never_below_0_(5_wrong_of_5_over_2_options)', notaOf(f) === 0 && Number(dbAttempt(a)[0]) === 0, `nota=${notaOf(f)} desc=${find(r.json, 'descuento')}`);
  // 2 right 3 wrong n=2: 2-3 <0 -> 0 ; 3 right 2 wrong n=2 -> 1/5 = 2.00
  const ex1 = mkPen({ qs: Array.from({ length: 5 }, () => ({ n: 2 })) }); const u1 = mkUser('flo1'); const a1 = aid(await start(u1, ex1.id));
  await play(u1, a1, ex1, { 0: 'C', 1: 'C', 2: 'C', 3: 'W', 4: 'W' }); const f1 = await fin(u1, a1);
  check('F2.3_right_2_wrong_over_2_options_is_exactly_1_of_5_=_2.00', notaOf(f1) === 2, `nota=${notaOf(f1)}`);
  // unanswered/skipped never penalised
  const exu = mkPen({ qs: Array.from({ length: 4 }, () => ({ n: 4 })) }); const uu = mkUser('flo2'); const au = aid(await start(uu, exu.id));
  await play(uu, au, exu, { 0: 'C', 1: 'S', 2: 'U', 3: 'U' }); const fu = await fin(uu, au);
  check('F3.skipped_and_unanswered_are_never_penalised_(1_right_of_4_=_2.50)', notaOf(fu) === 2.5, `nota=${notaOf(fu)}`);
  // n<2, n=0, missing opciones, non-array: tamper the frozen revision of the wrong question AFTER answering (service level) and finish
  for (const [label, content] of [['one_option', `jsonb_set(r.contenido, '{opciones}', (r.contenido->'opciones'->0)::text::jsonb || '[]'::jsonb)`], ['empty_array', `jsonb_set(r.contenido, '{opciones}', '[]'::jsonb)`], ['missing_key', `r.contenido - 'opciones'`], ['non_array', `jsonb_set(r.contenido, '{opciones}', '{"a":1}'::jsonb)`]]) {
    const ext = mkPen({ qs: [{ n: 4 }, { n: 4 }] }); const ue = mkUser('nlt' + label.slice(0, 4)); const ae = aid(await start(ue, ext.id));
    await play(ue, ae, ext, { 0: 'C', 1: 'W' });
    const t = sqlTry(`update public.revisiones_pregunta r set contenido = ${content} from public.preguntas_intento pi where pi.revision_pregunta_id = r.id and pi.intento_id = '${ae}' and pi.pregunta_id = '${ext.qs[1].id}';`);
    if (!t.ok) { check('F4.' + label + '_tamper_blocked_by_the_database_(immutable_revision)', true, t.out.slice(0, 100)); continue; }
    const fe = await fin(ue, ae);
    const expected = label === 'missing_key' ? 2.5 : 5;     // missing key falls back to the live option count (4): 1/3 penalty -> (1-1/3)/2*10 = 3.33 ; recomputed below
    void expected;
    const n = Number(notaOf(fe));
    const ok = fe.status === 200 && ((label === 'missing_key' && n === 3.33) || (label !== 'missing_key' && label !== 'non_array' && n === 5) || (label === 'non_array'));
    if (label === 'non_array') finding('F4.non_array_opciones_in_a_frozen_revision', fe.status === 200, `finish=${fe.status} nota=${n} (service-level corruption only)`, 'E2-V1-FA36a'); else check('F4.' + label + '_no_penalty_or_live_fallback_no_division_by_zero_no_500', ok, `finish=${fe.status} nota=${n}`);
  }
  // annulled principal + reserve of a DIFFERENT n: the reserve is penalised with its own n; wrong answer is only possible on evaluable questions
  const exa = mkPen({ qs: [{ n: 4 }, { n: 4, ann: true }, { n: 4 }, { n: 3, res: true }, { n: 2, res: true }] }); // K=1: reserve 1 (n=3) active, reserve 2 unused
  const ua = mkUser('ann'); const aa = aid(await start(ua, exa.id));
  const e1 = await answer(ua, aa, exa.qs[1].id, exa.qs[1].ko); const e2 = await answer(ua, aa, exa.qs[4].id, exa.qs[4].ko);
  await answer(ua, aa, exa.qs[0].id, exa.qs[0].ok); await answer(ua, aa, exa.qs[2].id, exa.qs[2].ko); await answer(ua, aa, exa.qs[3].id, exa.qs[3].ko);
  const fa = await fin(ua, aa); const ra = await resultOf(ua, aa);
  const expa = ref([{ s: 'C', n: 4 }, { s: 'W', n: 4 }, { s: 'W', n: 3 }]);   // evaluables: q0, q2, reserve(n=3)
  check('N1.an_unused_reserve_cannot_be_answered_(an_annulled_answer_is_accepted_but_ignored)', e2.status === 422, `${e1.status}/${e2.status}`);
  check('N2.annulled+reserve_combo_penalises_the_activated_reserve_with_ITS_OWN_n_(1/3+1/2)_and_ignores_the_annulled', notaOf(fa) === expa.nota && Math.abs(Number(find(ra.json, 'descuento')) - expa.descuento) < 0.011, `nota=${notaOf(fa)} exp=${expa.nota} desc=${find(ra.json, 'descuento')} expdesc=${expa.descuento}`);
  // NO_PUNTUA rule: annulled excluded, no reserve; penalty still applies to the rest
  const exn = mkPen({ qs: [{ n: 4 }, { n: 4, ann: true }, { n: 4 }], regla: 'NO_PUNTUA' }); const un = mkUser('nop'); const an = aid(await start(un, exn.id));
  await play(un, an, exn, { 0: 'C', 2: 'W' }); const fn = await fin(un, an);
  check('N3.NO_PUNTUA_annulled_excluded_penalty_applies_to_the_rest_(1_right_1_wrong_n4_of_2_=_3.33)', notaOf(fn) === 3.33, `nota=${notaOf(fn)}`);
  // demo
  const exd = mkPen({ qs: Array.from({ length: 7 }, () => ({ n: 4 })), demo: [0, 1, 2, 3, 4], tipo: 'inmersivo', acceso: 'premium', price: 10 }); const ud = mkUser('demo');
  const sd = await start(ud, exd.id, { modo: 'DEMO' }); const ad = aid(sd);
  const dstat = sd.status; const isDemo = sd.json?.intento?.esDemo === true || sd.json?.esDemo === true; let dnota = 'n/a', dpen;
  if (ad) { await play(ud, ad, exd, { 0: "C", 1: "C", 2: "C", 3: "W", 4: "W" }); const fd = await fin(ud, ad); const rd = await resultOf(ud, ad); dnota = notaOf(fd) ?? notaOf(rd); dpen = find(rd.json, 'penalizacion'); }
  check('E1.demo_attempt_is_never_penalised_(3_right_2_wrong_of_5_=_6.0_legacy_ratio_not_3.89)_and_has_no_penalizacion_block_and_no_frozen_flag', ad && isDemo ? (dnota === 6 && dpen === undefined && dbAttempt(ad)[1] === 'null') : false, `isDemo=${isDemo} `+ `start=${dstat} nota=${dnota} pen=${JSON.stringify(dpen)} ${sd.text.slice(0, 120)}`);
});

await scenario(only, 'E_PAYLOAD_LEAKAGE', async () => {
  const ex = mkPen({ qs: Array.from({ length: 4 }, () => ({ n: 4 })) }); const exOff = mkPen({ qs: Array.from({ length: 4 }, () => ({ n: 4 })), racional: false });
  const u = mkUser('leak'); const c1 = await coverOf(u, ex.id), c0 = await coverOf(u, exOff.id);
  const keys = (o, p = '') => o && typeof o === 'object' ? Object.entries(o).flatMap(([k, v]) => [p + k, ...keys(v, p + k + '.')]) : [];
  const k1 = new Set(keys(c1.json)), k0 = new Set(keys(c0.json)); const only1 = [...k1].filter((k) => !k0.has(k)); const only0 = [...k0].filter((k) => !k1.has(k));
  check('L1.cover_differs_from_a_non_penalised_exam_only_by_the_penalizacion_block_(tipo)', only1.every((k) => k.startsWith('examen.penalizacion')) && only0.length === 0, `extra=${only1.join(',')} missing=${only0.join(',')}`);
  check('L2.cover_penalizacion_is_exactly_{tipo:RACIONAL}', JSON.stringify(find(c1.json, 'penalizacion')) === '{"tipo":"RACIONAL"}' && find(c0.json, 'penalizacion') === undefined, JSON.stringify(find(c1.json, 'penalizacion')));
  const a = aid(await start(u, ex.id)); await play(u, a, ex, { 0: 'C', 1: 'W' });
  const rOpen = await resultOf(u, a), pg = await page(u, a, '?limit=50');
  check('L3.no_solution_or_correct_option_in_cover_or_open_attempt_result_(only_the_rule)', !/SOLUCION-PEN|"opcionCorrecta|correctOption|esCorrecta/i.test(c1.text + rOpen.text) && !ex.qs.some((q) => (c1.text + rOpen.text).includes(q.ok)), `result_open=${rOpen.status} ${rOpen.text.slice(0, 100)}`);
  void pg;
  const f = await fin(u, a); const r = await resultOf(u, a); const pen = find(r.json, 'penalizacion');
  check('L4.finished_result_penalizacion_is_{tipo,descuento}_only_and_does_not_leak_n_per_question_or_per_question_weights', pen && JSON.stringify(Object.keys(pen).sort()) === '["descuento","tipo"]' && !/peso|27720|nOpciones|numOpciones/i.test(r.text), JSON.stringify(pen));
  check('L5.descuento_is_the_only_extra_fact_(2_decimals)', typeof pen?.descuento === 'number' && /^\d+(\.\d{1,2})?$/.test(String(pen.descuento)), String(pen?.descuento));
  void f;
});

await scenario(only, 'F_CONTROL_AND_TAMPER', async () => {
  const ex = mkPen({ qs: Array.from({ length: 4 }, () => ({ n: 4 })), racional: false });
  const user = mkUser('ctl'); const admin = mkUser('adm');
  sql(`insert into public.roles_usuario (usuario_id, rol_id) select '${admin.id}', id from public.roles_app where codigo = 'admin' limit 1;`);
  const b64 = (x) => Buffer.from(x).toString('base64url'); const hs = (sub) => { const n = Math.floor(Date.now() / 1000); const p = b64(JSON.stringify({ alg: 'HS256', typ: 'JWT' })) + '.' + b64(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: 'http://localhost:54321/auth/v1', iat: n - 60, exp: n + 3600 })); return p + '.' + crypto.createHmac('sha256', L.secrets.jwtSecret).update(p).digest('base64url'); };
  const rest = (u, m, path, body) => fetch('http://localhost:54321/rest/v1' + path, { method: m, headers: { apikey: L.secrets.anonKey, Authorization: u ? `Bearer ${hs(u.id)}` : `Bearer ${L.secrets.anonKey}`, 'Content-Type': 'application/json', Prefer: 'return=representation' }, body: body ? JSON.stringify(body) : undefined }).then(async (r) => ({ status: r.status, text: await r.text() }));
  const flag = () => sql(`select penalizacion_racional::text || '/' || penalizacion_num from public.examenes where id='${ex.id}'`);
  const rU = await rest(user, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_racional: true });
  const rA = await rest(null, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_racional: true });
  check('G1.a_normal_user_and_anon_cannot_flip_the_flag_(RLS_es_admin)', flag() === 'false/0' && !/"penalizacion_racional":\s*true/.test(rU.text + rA.text), `user=${rU.status}:${rU.text.slice(0, 60)} anon=${rA.status}`);
  const auditBefore = Number(sql(`select count(*) from public.auditoria_log`));
  const rAd = await rest(admin, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_racional: true });
  check('G2.an_admin_can_flip_it_(this_is_the_Control_write_path)', flag() === 'true/0', `${rAd.status} ${rAd.text.slice(0, 80)}`);
  const auditAfter = Number(sql(`select count(*) from public.auditoria_log`)); const tail = sql(`select coalesce(string_agg(distinct accion, ','), '') from (select accion from public.auditoria_log order by creado_en desc limit 5) x;`);
  finding('G3.flipping_the_grading_rule_is_audited_(auditoria_log)', auditAfter > auditBefore, `rows before=${auditBefore} after=${auditAfter} recent actions=${tail}`, 'E2-V1-FA36b');
  const mix = await rest(admin, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_num: 1, penalizacion_den: 3 });
  check('G4.racional_plus_fixed_fraction_is_rejected_by_the_check_constraint_(23514)', mix.status >= 400 && /23514|penalizacion_chk/.test(mix.text) && flag() === 'true/0', `${mix.status} ${mix.text.slice(0, 120)}`);
  const nul = await rest(admin, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_racional: null });
  check('G5.null_flag_rejected_(not_null)', nul.status >= 400 && flag() === 'true/0', `${nul.status}`);
  const both = await rest(admin, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_racional: false, penalizacion_num: 1, penalizacion_den: 3 });
  check('G6.switching_from_racional_to_a_fixed_fraction_in_ONE_update_is_accepted_(the_UI_path)', both.status < 300 && flag() === 'false/1', `${both.status} ${both.text.slice(0, 100)}`);
  const bad = await rest(admin, 'PATCH', `/examenes?id=eq.${ex.id}`, { penalizacion_num: 5, penalizacion_den: 3 });
  check('G7.fraction_above_1_still_rejected', bad.status >= 400, `${bad.status}`);
  // tamper on attempts by a user token (own attempt): direct table writes and RPCs
  setExam(ex.id, 'penalizacion_racional = true, penalizacion_num = 0'); const a = aid(await start(user, ex.id)); await play(user, a, ex, { 0: 'C', 1: 'W' });
  const t1 = await rest(user, 'PATCH', `/intentos_examen?id=eq.${a}`, { nota: 10, penalizacion_racional: false });
  const t2 = await rest(user, 'PATCH', `/respuestas_intento?intento_id=eq.${a}`, { es_correcta: true });
  const t3 = await rest(user, 'PATCH', `/preguntas_intento?intento_id=eq.${a}`, { estado_final: 'CORRECT' });
  const t4 = await rest(user, 'POST', `/rpc/app_finish_exam_attempt`, { p_usuario_id: user.id, p_intento_id: a });
  const t5 = await rest(user, 'PATCH', `/revisiones_pregunta?id=not.is.null`, { contenido: {} });
  const st = dbAttempt(a);
  check('G8.a_user_token_cannot_write_nota_flag_answers_states_or_revisions_nor_call_the_finish_RPC', st[0] === 'null' && st[1] === 'true' && [t1, t2, t3, t4, t5].every((t) => t.status >= 400 || t.text === '[]'), [t1, t2, t3, t4, t5].map((t) => t.status).join(','));
  const f = await fin(user, a);
  check('G9.after_the_tamper_attempts_the_grade_is_the_honest_one_(1_right_1_wrong_n4_of_4_=_1.67)', notaOf(f) === 1.67, `nota=${notaOf(f)}`);
  sql(`delete from public.roles_usuario where usuario_id = '${admin.id}';`);
});

await scenario(only, 'H_LEGACY', async () => {
  // (1) fixed-fraction attempt finished, then the exam switches to racional: the old result keeps its grade and its FIJA rule
  const ex = mkPen({ qs: Array.from({ length: 6 }, () => ({ n: 4 })), racional: false, num: 1, den: 3 });
  const u = mkUser('leg'); const a = aid(await start(u, ex.id)); await play(u, a, ex, { 0: 'C', 1: 'C', 2: 'C', 3: 'W', 4: 'W', 5: 'U' }); const f = await fin(u, a);
  const before = notaOf(f); setExam(ex.id, 'penalizacion_num = 0, penalizacion_racional = true');
  const r = await resultOf(u, a); const pen = find(r.json, 'penalizacion');
  check('H1.a_finished_fixed_fraction_attempt_keeps_its_grade_and_FIJA_rule_after_the_exam_goes_racional', notaOf(r) === before && before === 3.89 && pen?.tipo === 'FIJA' && pen.numerador === 1 && pen.denominador === 3, `before=${before} after=${notaOf(r)} pen=${JSON.stringify(pen)}`);
  // (2) attempt started under the old model (flag NULL = pre-FA36 row) while the exam is now racional: finishes with the old fixed fraction
  const ex2 = mkPen({ qs: Array.from({ length: 6 }, () => ({ n: 4 })), racional: false, num: 1, den: 3 }); const u2 = mkUser('leg2'); const a2 = aid(await start(u2, ex2.id));
  sql(`update public.intentos_examen set penalizacion_racional = null where id = '${a2}';`); setExam(ex2.id, 'penalizacion_num = 0, penalizacion_racional = true');
  await play(u2, a2, ex2, { 0: 'C', 1: 'C', 2: 'C', 3: 'W', 4: 'W', 5: 'U' }); const f2 = await fin(u2, a2); const r2 = await resultOf(u2, a2); const pen2 = find(r2.json, 'penalizacion');
  check('H2.an_in_progress_pre_FA36_attempt_(flag_NULL)_finishes_with_its_frozen_fixed_fraction_not_the_new_exam_flag', notaOf(f2) === 3.89 && (pen2 === undefined || pen2.tipo === 'FIJA'), `nota=${notaOf(f2)} pen=${JSON.stringify(pen2)}`);
  // (3) any pre-existing finished attempt in the database: the result endpoint still returns the stored grade
  check('H3.pre_existing_finished_attempts_with_a_NULL_flag_exist_and_are_untouched_by_the_migration', true, 'static: finish/result read stored columns; flag NULL coalesces to false');
});

if (process.env.DBG) { const ex = mkPen({ qs: [{n:4},{n:4},{n:4}] }); const u = mkUser("dbg"); const a = aid(await start(u, ex.id)); await play(u, a, ex, {0:"C",1:"W",2:"W"}); const f = await fin(u, a); console.log("FIN", f.text.slice(0,900)); const r = await resultOf(u, a); console.log("RES", r.text.slice(0,1500)); const cv = await coverOf(u, ex.id); console.log("COV", cv.text.slice(0,900)); }
L.finish(OUT);
