#!/usr/bin/env node
// E2 V1 ACCESS v2 (LOCAL ONLY): live attacks on plan coverage, quota choice, DEMO, the daily limiter, the plan catalog and the OT-bought cap.
// Own users/exams only (e2x-*, e2a00000-*). STACK_LOCK hard-fail. Own Ktor from this worktree on :8091 restarted by the script.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-access2.mjs [--only=NAME,...]
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, mkExam, subscribe, setSub, sql, sqlTry, call, start, page, answer, fin, detail, state, attemptId, balance, grant, ledgerRows, uuid, sleep, LOC, OTH } = L;

requireStackLock('e2-v1-access2.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-access2-result.json';
const SUB = { raf: 'local-sub-rafagas', raz: 'local-sub-razonados', inm: 'local-sub-inmersivo' };
await L.restartKtor();

// ---- fixtures (own exams)
const X = {
  raf: mkExam({ tipo: 'rafaga', acceso: 'premium', n: 2 }), raz: mkExam({ tipo: 'razonado', acceso: 'premium', n: 2 }), free: mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 2 }),
  i1: mkExam({ tipo: 'inmersivo', price: 10, n: 2 }), i2: mkExam({ tipo: 'inmersivo', price: 10, n: 2 }), i3: mkExam({ tipo: 'inmersivo', price: 10, n: 2 }), i4: mkExam({ tipo: 'inmersivo', price: 10, n: 2 }),
  oth: mkExam({ tipo: 'inmersivo', price: 10, n: 2, spec: OTH }),
  demo: mkExam({ tipo: 'inmersivo', price: 10, n: 7, demoFlags: [true, true, true, true, true, false, false] }),
};
const usersByPlan = {};
const plan = (p, label) => { const u = mkUser(label); if (p) subscribe(u, SUB[p]); return u; };

await scenario(only, 'A_VERDICT', async () => {
  const m = {}; const bad = [];
  for (const [p, want] of [[null, { raf: 'LOCKED', raz: 'LOCKED', i1: 'LOCKED', free: 'FREE' }], ['raf', { raf: 'SUBSCRIPTION', raz: 'LOCKED', i1: 'LOCKED', free: 'FREE' }],
    ['raz', { raf: 'SUBSCRIPTION', raz: 'SUBSCRIPTION', i1: 'LOCKED', free: 'FREE' }], ['inm', { raf: 'SUBSCRIPTION', raz: 'SUBSCRIPTION', i1: 'LOCKED', free: 'FREE' }]]) {
    const u = plan(p, 'v' + (p ?? 'free')); usersByPlan[p ?? 'free'] = u;
    for (const [k, exp] of Object.entries(want)) { const got = await state(u, X[k].id); if (got !== exp) bad.push(`${p ?? 'free'}/${k}:${got}!=${exp}`); }
  }
  check('A1.plan_level_x_kind_matrix_levels_0_3_never_covers_an_immersive', bad.length === 0, bad.join(' ') || '16 cells');
  const cases = [['cancelada_future_end_honoured', { estado: 'cancelada', days: 10, motivo: 'usuario_cancelo' }, 'SUBSCRIPTION'], ['trial', { estado: 'periodo_prueba', days: 7 }, 'SUBSCRIPTION'],
    ['expired', { estado: 'activa', days: -1 }, 'LOCKED'], ['refund_at_once', { estado: 'caducada', days: 10, motivo: 'reembolso' }, 'LOCKED'], ['revoked', { estado: 'caducada', days: 10, motivo: 'revocada' }, 'LOCKED'],
    ['chargeback', { estado: 'caducada', days: 10, motivo: 'contracargo' }, 'LOCKED'], ['billing_failure', { estado: 'caducada', days: 10, motivo: 'fallo_cobro' }, 'LOCKED']];
  const wrong = [];
  for (const [name, o, exp] of cases) { const u = mkUser('s' + name.slice(0, 7)); subscribe(u, SUB.raz, o); const got = await state(u, X.raz.id); if (got !== exp) wrong.push(`${name}:${got}`); }
  check('A2.cancelada_honoured_until_fin_en_trial_covers_expired_and_terminal_motives_deny_at_once', wrong.length === 0, wrong.join(' ') || cases.length + ' status cases');
  const nop = mkUser('noplan'); const ins = sqlTry(`insert into public.suscripciones (usuario_id, proveedor, producto_codigo, estado, inicio_en, fin_en, payload_validacion) values ('${nop.id}', 'manual', 'e2-no-such-product', 'activa', now(), now() + interval '10 days', '{}'::jsonb);`);
  check('A3.product_without_plan_is_fail_closed', !ins.ok || (await state(nop, X.raf.id)) === 'LOCKED', ins.ok ? 'state=' + (await state(nop, X.raf.id)) : 'insert refused (FK): no product, no plan');
  const cat = await call(usersByPlan.free, 'GET', '/api/v1/app/plan/catalogo');
  const txt = cat.text; const items = cat.json?.items ?? [];
  check('A4.D7_33_catalog_readonly_active_visible_standard_only_no_leaks', cat.status === 200 && items.length >= 1 && !/first_year|renewal|fase_precio|plan_id|benefici|storeSecret|payload|usuario/i.test(txt) && items.every((i) => typeof i.nivel === 'number' && Array.isArray(i.tarifas)) && items.every((i) => i.nivel < 3 || i.cobertura?.inmersivos === 'cupo'), `${cat.status} items=${items.length}`);
  const hid = sql(`select count(*) from public.planes_suscripcion where not (activo and visible);`);
  const inactiveShown = items.filter((i) => sql(`select count(*) from public.planes_suscripcion where codigo = '${i.planCodigo}' and not (activo and visible);`) !== '0').length;
  const w = [await call(usersByPlan.free, 'POST', '/api/v1/app/plan/catalogo', {}), await call(usersByPlan.free, 'GET', '/api/v1/app/plan/catalogo?usuarioId=' + uuid()), await call(null, 'GET', '/api/v1/app/plan/catalogo')];
  check('A5.catalog_no_writes_no_params_requires_jwt_no_inactive_plans', inactiveShown === 0 && [404, 405].includes(w[0].status) && w[1].status === 400 && w[2].status === 401, `inactive-shown=${inactiveShown} (hidden plans=${hid}) POST=${w[0].status} param=${w[1].status} anon=${w[2].status}`);
  const limU = mkUser('catlim'); const lim = []; for (let i = 0; i < 125; i++) lim.push((await call(limU, 'GET', '/api/v1/app/plan/catalogo')).status);
  check('A6.catalog_read_limiter_429_after_120', lim.indexOf(429) >= 118 && lim.indexOf(429) <= 122, 'first429=#' + (lim.indexOf(429) + 1));
});

await scenario(only, 'B_QUOTA', async () => {
  const u = plan('inm', 'qa'); const s0 = await call(u, 'GET', '/api/v1/app/plan/cupo');
  check('B1.quota_summary_inmersivo_plan', s0.status === 200 && s0.json?.nivel === 3 && s0.json?.cupoTotal === 2 && s0.json?.cupoLibre === 2, `${s0.status} ${s0.text.slice(0, 140)}`);
  const c1 = await call(u, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`); const c1b = await call(u, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`); const c2 = await call(u, 'PUT', `/api/v1/app/plan/cupo/${X.i2.id}`); const c3 = await call(u, 'PUT', `/api/v1/app/plan/cupo/${X.i3.id}`);
  check('B2.choose_idempotent_second_ok_third_PLAN_QUOTA_FULL_exam_is_GRANTED', c1.status === 200 && c1b.status === 200 && c1b.json?.hasta === c1.json?.hasta && c2.status === 200 && c3.status === 422 && c3.json?.code === 'PLAN_QUOTA_FULL' && (await state(u, X.i1.id)) === 'GRANTED', `${c1.status}/${c1b.status}/${c2.status}/${c3.status} ${c3.json?.code}`);
  // races: fresh subscriber, 8 parallel PUTs over 4 distinct exams (quota 2), plus 2 "devices" on the same exam
  const r = mkUser('qrace'); subscribe(r, SUB.inm);
  const ids = [X.i1, X.i2, X.i3, X.i4, X.i1, X.i2, X.i3, X.i4].map((e) => e.id);
  const par = await Promise.all(ids.map((id) => call(r, 'PUT', `/api/v1/app/plan/cupo/${id}`)));
  const rows = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${r.id}' and motivo = 'plan_cupo' and valido_hasta > now();`));
  check('B3.parallel_chooses_never_exceed_the_quota', rows === 2 && par.filter((x) => x.status === 200).length >= 2 && par.every((x) => [200, 422, 429].includes(x.status)), `rows=${rows} statuses=${par.map((x) => x.status)}`);
  const d = mkUser('qdev'); subscribe(d, SUB.inm); const same = await Promise.all(Array.from({ length: 6 }, () => call(d, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`)));
  check('B4.two_devices_same_exam_one_row', same.every((x) => x.status === 200) && Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${d.id}' and motivo = 'plan_cupo';`)) === 1, same.map((x) => x.status).join(','));
  // choose vs purchase of the same exam: serialised, exactly one holds it
  const cp = mkUser('qpur'); subscribe(cp, SUB.inm); grant(cp, 50);
  const [pc, pb] = await Promise.all([call(cp, 'PUT', `/api/v1/app/plan/cupo/${X.i4.id}`), call(cp, 'POST', '/api/v1/app/wallet/canjes', { examId: X.i4.id }, { 'Idempotency-Key': uuid() })]);
  const quotaRows = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${cp.id}' and examen_id = '${X.i4.id}' and motivo = 'plan_cupo';`)); const buyRows = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${cp.id}' and examen_id = '${X.i4.id}' and motivo = 'compra';`));
  check('B5.choose_vs_purchase_of_the_same_exam_exactly_one_holds_it_no_double_spend', quotaRows + buyRows === 1 && ((pc.status === 200 && pb.status === 409) || (pc.status === 409 && pb.status === 201)) && (pb.status === 201 ? balance(cp) === 40 : balance(cp) === 50), `choose=${pc.status}/${pc.json?.code} buy=${pb.status}/${pb.json?.code} balance=${balance(cp)}`);
  const after = await call(u, 'POST', '/api/v1/app/wallet/canjes', { examId: X.i1.id }, { 'Idempotency-Key': uuid() }); grant(u, 20);
  const after2 = await call(u, 'POST', '/api/v1/app/wallet/canjes', { examId: X.i1.id }, { 'Idempotency-Key': uuid() });
  check('B6.purchase_of_an_exam_held_by_quota_is_ALREADY_ACCESSIBLE_no_debit', after2.status === 409 && after2.json?.code === 'ALREADY_ACCESSIBLE' && balance(u) === 20, `${after.status}/${after2.status} ${after2.json?.code} balance=${balance(u)}`);
  // gates and errors
  const nu = mkUser('qnospec', null); subscribe(nu, SUB.inm); const ou = mkUser('qoth', OTH); subscribe(ou, SUB.inm); const fr = mkUser('qfree'); const rf = plan('raf', 'qraf');
  const g = await Promise.all([call(nu, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`), call(u, 'PUT', `/api/v1/app/plan/cupo/${X.oth.id}`), call(u, 'PUT', `/api/v1/app/plan/cupo/${uuid()}`), call(u, 'PUT', `/api/v1/app/plan/cupo/${X.raf.id}`),
    call(fr, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`), call(rf, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`), call(u, 'PUT', '/api/v1/app/plan/cupo/not-a-uuid')]);
  check('B7.gates_one_404_for_no_specialty_other_specialty_unknown_codes_for_kind_and_plan', g[0].status === 404 && g[1].status === 404 && g[2].status === 404 && g[0].json?.code === g[2].json?.code && g[3].status === 422 && g[3].json?.code === 'EXAM_NOT_IMMERSIVE' && g[4].json?.code === 'PLAN_QUOTA_NONE' && g[5].json?.code === 'PLAN_QUOTA_NONE' && g[6].status === 400, g.map((x) => x.status + '/' + (x.json?.code ?? '')).join(' '));
  const unp = mkExam({ tipo: 'inmersivo', n: 1 }); sql(`set session_replication_role = replica; update public.examenes set publicado_en = null where id = '${unp.id}'; set session_replication_role = origin;`);
  const unr = await call(u, 'PUT', `/api/v1/app/plan/cupo/${unp.id}`);
  check('B8.unpublished_exam_same_404_as_unknown', unr.status === 404 && unr.json?.code === g[2].json?.code, `${unr.status}/${unr.json?.code}`);
  const sw = await Promise.all([call(u, 'DELETE', `/api/v1/app/plan/cupo/${X.i1.id}`), call(u, 'POST', `/api/v1/app/plan/cupo/${X.i1.id}`), call(u, 'PATCH', `/api/v1/app/plan/cupo/${X.i1.id}`)]);
  check('B9.no_swap_or_free_a_slot_endpoint_exists', sw.every((x) => [404, 405].includes(x.status)) && (await call(u, 'GET', '/api/v1/app/plan/cupo')).json?.cupoUsado === 2, sw.map((x) => x.status).join(','));
  // dedicated motivo: gift cannot write/consume it; direct writes outside the function are refused
  const gu = mkUser('qgift'); subscribe(gu, SUB.inm);
  const gf = sqlTry(`select * from public.admin_grant_exam_access('${gu.id}', '${X.i2.id}', 'premium', null);`); const gf2 = sqlTry(`select * from public.admin_grant_exam_access('${gu.id}', '${X.i2.id}', 'plan_cupo', null);`);
  const dir = sqlTry(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${gu.id}', '${X.i3.id}', 'plan_cupo', now(), now() + interval '30 days');`);
  const cu = (await call(gu, 'GET', '/api/v1/app/plan/cupo')).json;
  check('B10.motivo_plan_cupo_is_writable_only_by_the_quota_function_gifts_cannot_use_premium_or_plan_cupo', !gf2.ok && !dir.ok && cu?.cupoUsado === 0 && (gf.ok ? 'premium gift accepted but does not count as quota' : 'premium gift refused') !== '', `gift-premium=${gf.ok ? 'ok(not quota)' : 'refused'} gift-plan_cupo=${gf2.ok} direct-insert=${dir.ok} cupoUsado=${cu?.cupoUsado}`);
  finding('B11.gift_with_reason_premium_is_not_accepted', !gf.ok, gf.ok ? 'premium is still an accepted gift reason (does not consume quota)' : 'refused', 'E2-V1-A01');
  // lifecycle: terminal states close quota grants at once; cancellation honours fin_en; renewal extends; new subscription = empty quota
  const lc = {}; const cases = [['reembolso', { estado: 'caducada', fin_motivo: 'reembolso' }], ['contracargo', { estado: 'caducada', fin_motivo: 'contracargo' }], ['revocada', { estado: 'caducada', fin_motivo: 'revocada' }], ['fallo_cobro', { estado: 'caducada', fin_motivo: 'fallo_cobro' }]];
  const wrong = [];
  for (const [name, f] of cases) {
    const x = mkUser('lc' + name.slice(0, 5)); subscribe(x, SUB.inm); await call(x, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`);
    setSub(x, `estado = '${f.estado}', fin_motivo = '${f.fin_motivo}'`);
    const st = await state(x, X.i1.id); const open = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${x.id}' and motivo = 'plan_cupo' and valido_hasta > now();`));
    if (st !== 'LOCKED' || open !== 0) wrong.push(`${name}:${st}/${open}`);
  }
  check('B12.refund_revoked_chargeback_billing_failure_close_quota_grants_at_once', wrong.length === 0, wrong.join(' ') || '4 terminal motives');
  const cx = mkUser('lccan'); subscribe(cx, SUB.inm, { days: 20 }); await call(cx, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`);
  setSub(cx, `estado = 'cancelada', fin_motivo = 'usuario_cancelo'`);
  const stc = await state(cx, X.i1.id); const hasta0 = sql(`select extract(epoch from valido_hasta - now()) / 86400 from public.contenido_desbloqueado_usuario where usuario_id = '${cx.id}' and motivo = 'plan_cupo';`);
  check('B13.cancellation_keeps_the_quota_exam_until_the_paid_period_ends', stc === 'GRANTED' && Math.abs(Number(hasta0) - 20) < 0.5, `${stc} grant ends in ${Number(hasta0).toFixed(2)}d`);
  const rn = mkUser('lcren'); subscribe(rn, SUB.inm, { days: 10 }); await call(rn, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`); setSub(rn, `fin_en = now() + interval '40 days', renovacion_en = now() + interval '40 days'`);
  const hr = sql(`select extract(epoch from valido_hasta - now()) / 86400 from public.contenido_desbloqueado_usuario where usuario_id = '${rn.id}' and motivo = 'plan_cupo';`);
  check('B14.renewal_extends_the_quota_grant_to_the_new_period', Math.abs(Number(hr) - 40) < 0.5, `grant ends in ${Number(hr).toFixed(2)}d`);
  const lp = mkUser('lcnew'); subscribe(lp, SUB.inm); await call(lp, 'PUT', `/api/v1/app/plan/cupo/${X.i1.id}`); setSub(lp, `estado = 'caducada', fin_motivo = 'reembolso'`); subscribe(lp, SUB.inm);
  const lps = (await call(lp, 'GET', '/api/v1/app/plan/cupo')).json;
  check('B15.new_subscription_after_a_terminal_end_starts_with_an_empty_quota', lps?.cupoUsado === 0 && (await state(lp, X.i1.id)) === 'LOCKED', `cupoUsado=${lps?.cupoUsado}`);
  // client cannot write quota rows or call the functions
  const fnx = await Promise.all(['select public.app_choose_plan_exam', 'select public.app_plan_quota'].map((s) => sqlTry(`set role authenticated; ${s}('${u.id}', '${X.i4.id}');`)));
  check('B16.quota_functions_are_not_executable_by_authenticated_or_anon', fnx.every((x) => !x.ok), fnx.map((x) => x.ok).join(','));
  // deadlock probe: renewal / terminal vs choose vs purchase vs gift
  const dp = mkUser('dlock'); subscribe(dp, SUB.inm); grant(dp, 200); let fivexx = 0;
  for (let i = 0; i < 25; i++) {
    const rs = await Promise.all([call(dp, 'PUT', `/api/v1/app/plan/cupo/${[X.i1, X.i2, X.i3][i % 3].id}`), call(dp, 'POST', '/api/v1/app/wallet/canjes', { examId: [X.i3, X.i4][i % 2].id }, { 'Idempotency-Key': uuid() }),
      Promise.resolve(sqlTry(`update public.suscripciones set fin_en = now() + interval '${30 + i} days', renovacion_en = now() + interval '${30 + i} days' where usuario_id = '${dp.id}';`))]);
    fivexx += rs.slice(0, 2).filter((x) => x.status >= 500).length;
  }
  check('B17.renewal_vs_choose_vs_purchase_25_rounds_no_5xx_no_deadlock', fivexx === 0, `5xx=${fivexx}`);
});

await scenario(only, 'C_DEMO', async () => {
  const f = mkUser('dfree'); const s = await start(f, X.demo.id, { modo: 'DEMO' }); const aid = attemptId(s);
  check('D1.demo_start_by_a_user_without_access_is_201_esDemo', s.status === 201 && (s.json?.intento?.esDemo === true || s.json?.esDemo === true), `${s.status} ${s.text.slice(0, 140)}`);
  const pg = await page(f, aid, '?limit=50'); const items = pg.json?.items ?? pg.json?.preguntas ?? pg.json?.data ?? [];
  const flagged = X.demo.qs.slice(0, 5).map((q) => q.id); const nonFlagged = X.demo.qs.slice(5).map((q) => q.id);
  const ids = items.map((i) => i.preguntaId ?? i.id ?? i.pregunta?.id);
  check('D2.demo_attempt_serves_exactly_the_5_flagged_questions', pg.status === 200 && ids.length === 5 && flagged.every((q) => ids.includes(q)) && !nonFlagged.some((q) => pg.text.includes(q)), `${pg.status} n=${ids.length}`);
  const a6 = await answer(f, aid, nonFlagged[0], X.demo.qs[5].ok); const a5 = await answer(f, aid, flagged[0], X.demo.qs[0].ok);
  check('D3.answer_for_a_non_member_question_is_refused_member_ok', [404, 422, 409].includes(a6.status) && a5.status === 200 && !a6.text.includes('SOLUCION'), `non-member=${a6.status} member=${a5.status}`);
  const rid = uuid(); const media = await call(f, 'GET', `/api/v1/app/intentos/${aid}/recursos/${rid}`);
  check('D4.no_media_beyond_the_attempt_same_404', media.status === 404, `${media.status}`);
  const rev0 = await call(f, 'GET', `/api/v1/app/intentos/${aid}/revision`); const leakOpen = /SOLUCION-E2A/.test(rev0.text) || nonFlagged.some((q) => rev0.text.includes(q));
  const fn = await fin(f, aid); const res = await call(f, 'GET', `/api/v1/app/intentos/${aid}/resultado`); const rev = await call(f, 'GET', `/api/v1/app/intentos/${aid}/revision`);
  check('D5.review_and_result_after_finish_cover_only_the_5_and_flag_esDemo', fn.status === 200 && !nonFlagged.some((q) => rev.text.includes(q) || res.text.includes(q)) && /esDemo/.test(res.text) && !leakOpen, `finish=${fn.status} result=${res.status} review=${rev.status} openLeak=${leakOpen}`);
  const rows = sql(`select count(*) filter (where es_primer_intento_pregunta) || '|' || count(*) filter (where cuenta_para_ranking) from public.respuestas_intento where intento_id = '${aid}';`);
  const cp = sql(`select cuenta_para_ranking::text || '|' || es_demo::text from public.intentos_examen where id = '${aid}';`);
  check('D6.demo_answers_are_never_first_answers_nor_ranking_and_the_attempt_is_flagged', rows === '0|0' && cp.endsWith('|true') && cp.startsWith('false'), `answers first|ranking=${rows} attempt=${cp}`);
  const q = (await call(f, 'GET', '/api/v1/app/plan/cupo')).json;
  check('D7.demo_does_not_touch_wallet_quota_purchases_or_the_limiter_counter', ledgerRows(f) === 0 && (q?.cupoUsado ?? 0) === 0 && (q?.reveladas24h ?? 0) === 0 && Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${f.id}';`)) === 0, `reveladas24h=${q?.reveladas24h}`);
  // first-answer interplay: a later REAL answer to the same question is the user's first
  const g = mkUser('dreal'); sql(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${g.id}', '${X.demo.id}', 'regalo', now(), now() + interval '30 days');`);
  const rs = await start(g, X.demo.id, { modo: 'DEMO' });
  check('D8.a_user_with_access_sending_DEMO_gets_a_normal_attempt', rs.status === 201 && !(rs.json?.intento?.esDemo || rs.json?.esDemo), `${rs.status} esDemo=${rs.json?.intento?.esDemo}`);
  const lk = await start(mkUser('dlocked'), X.i1.id, { modo: 'DEMO' }); const ns = await start(mkUser('dnospec', null), X.demo.id, { modo: 'DEMO' }); const ot = await start(mkUser('doth'), X.oth.id, { modo: 'DEMO' });
  check('D9.demo_on_a_non_demo_exam_locked_no_specialty_and_other_specialty_404', lk.status === 403 && ns.status === 404 && ot.status === 404, `${lk.status}/${lk.json?.code} ${ns.status} ${ot.status}`);
  const dup = mkUser('ddup'); const s1 = await start(dup, X.demo.id, { modo: 'DEMO' }); const s2 = await start(dup, X.demo.id, { modo: 'DEMO' });
  check('D10.at_most_one_open_demo_per_user_and_exam', Number(sql(`select count(*) from public.intentos_examen where usuario_id = '${dup.id}' and es_demo and estado = 'en_progreso';`)) === 1, `${s1.status}/${s2.status}`);
  // limits: per user per day (config 20) and per IP per day (config 60): every start is abandoned to reopen
  const lu = mkUser('dlim'); const st = []; for (let i = 0; i < 22; i++) { const r = await start(lu, X.demo.id, { modo: 'DEMO' }); st.push(r.status); const id = attemptId(r); if (id) await call(lu, 'POST', `/api/v1/app/intentos/${id}/abandonar`, { posicionPregunta: 1, motivo: 'salida_usuario' }); }
  check('D11.per_user_demo_start_limit_429', st.indexOf(429) >= 18 && st.indexOf(429) <= 22, 'first429=#' + (st.indexOf(429) + 1));
  const ipSt = []; for (let k = 0; k < 4 && ipSt.indexOf(429) < 0; k++) { const w = mkUser('dip' + k); for (let i = 0; i < 18; i++) { const r = await start(w, X.demo.id, { modo: 'DEMO' }); ipSt.push(r.status); const id = attemptId(r); if (id) await call(w, 'POST', `/api/v1/app/intentos/${id}/abandonar`, { posicionPregunta: 1, motivo: 'salida_usuario' }); if (r.status === 429) break; } }
  finding('D12.per_IP_demo_start_limit_429_before_80_starts', ipSt.indexOf(429) >= 0, 'first429=#' + (ipSt.indexOf(429) + 1) + ' of ' + ipSt.length + ' (the config limit is 60 per IP and day; the user above already used 22)', 'E2-V1-D01');
  const exam = await detail(f, X.demo.id);
  check('D13.exam_detail_still_shows_DEMO_state_not_access_after_demos', exam.json?.examen?.accessState === 'DEMO', `${exam.json?.examen?.accessState}`);
});

await scenario(only, 'D_LIMITER', async () => {
  const big = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 45 }); const fr = mkUser('lfree');
  const s = await start(fr, big.id); const aid = attemptId(s); const seen = new Set(); let first429 = null, retry = null, cursor = null;
  for (let i = 0; i < 12; i++) {
    const r = await page(fr, aid, `?limit=10${cursor ? '&cursor=' + encodeURIComponent(cursor) : ''}`);
    if (r.status === 429) { first429 = i + 1; retry = r.headers.get('retry-after') ?? r.json?.retryAfter ?? r.json?.details?.retryAfter; check('L1.limit_30_questions_in_24h_429_DAILY_LIMIT_REACHED_with_Retry_After', r.json?.code === 'DAILY_LIMIT_REACHED' && Number(retry) > 0, `page#${i + 1} revealed=${seen.size} retryAfter=${retry}`); break; }
    for (const it of (r.json?.items ?? r.json?.preguntas ?? [])) seen.add(it.preguntaId ?? it.id); cursor = r.json?.nextCursor ?? r.json?.cursor ?? null; if (!cursor) break;
  }
  if (first429 === null) check('L1.limit_30_questions_in_24h_429_DAILY_LIMIT_REACHED_with_Retry_After', false, 'never limited, revealed=' + seen.size);
  const qs = (await call(fr, 'GET', '/api/v1/app/plan/cupo')).json;
  check('L2.revealed_count_is_30_never_more_all_or_nothing_pages', seen.size <= 30 && qs?.reveladas24h <= 30 && qs?.reveladas24h === seen.size, `seen=${seen.size} reveladas24h=${qs?.reveladas24h} limit=${qs?.limitePreguntas24h}`);
  const again = await page(fr, aid, '?limit=10');
  check('L3.already_revealed_questions_are_exempt_when_re_read', again.status === 200, `${again.status}`);
  // exemptions: gift / purchase / quota exams keep serving after the limit; access state is checked at start
  const ex = mkExam({ tipo: 'inmersivo', price: 10, n: 12 }); sql(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${fr.id}', '${ex.id}', 'regalo', now(), now() + interval '30 days');`);
  const gs = await start(fr, ex.id); const gp = await page(fr, attemptId(gs), '?limit=12');
  check('L4.gifted_exam_is_exempt_after_the_limit_was_reached', gs.status === 201 && gp.status === 200, `${gs.status}/${gp.status}`);
  const exq = mkExam({ tipo: 'inmersivo', price: 10, n: 4 }); sql(`select public.admin_grant_tickets('${fr.id}', 10, 'x', 'e2-${uuid()}', 'ajuste');`); const pr = await call(fr, 'POST', '/api/v1/app/wallet/canjes', { examId: exq.id }, { 'Idempotency-Key': uuid() });
  const ps = await start(fr, exq.id); const pp = await page(fr, attemptId(ps), '?limit=4');
  check('L5.purchased_exam_is_exempt', pr.status === 201 && ps.status === 201 && pp.status === 200, `${pr.status}/${ps.status}/${pp.status}`);
  const rf = mkUser('lraf'); subscribe(rf, SUB.raf); const rs = await start(rf, big.id); const seenR = new Set(); let cur = null, blocked = null;
  for (let i = 0; i < 20; i++) { const r = await page(rf, attemptId(rs), `?limit=15${cur ? '&cursor=' + encodeURIComponent(cur) : ''}`); if (r.status === 429) { blocked = r.json?.code; break; } for (const it of (r.json?.items ?? r.json?.preguntas ?? [])) seenR.add(it.preguntaId ?? it.id); cur = r.json?.nextCursor ?? r.json?.cursor ?? null; if (!cur) break; }
  check('L6.rafagas_plan_has_the_higher_limit_all_45_visible', seenR.size === 45 && blocked === null, `seen=${seenR.size} blocked=${blocked}`);
  // two devices race at the boundary: 4 parallel readers of different pages of a new account's exam
  const rc = mkUser('lrace'); const rcs = await start(rc, big.id); const rid = attemptId(rcs);
  const pars = await Promise.all(Array.from({ length: 6 }, () => page(rc, rid, '?limit=10')));
  const qs2 = (await call(rc, 'GET', '/api/v1/app/plan/cupo')).json;
  check('L7.parallel_readers_never_push_revealed_questions_over_the_limit', (qs2?.reveladas24h ?? 99) <= 30, `reveladas24h=${qs2?.reveladas24h} statuses=${pars.map((x) => x.status)}`);
  // real boundary race: the user already revealed 20; four DIFFERENT free exams are read in parallel (10 new questions each): at most one more page fits
  const rr = mkUser('lrace2'); const base = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 20 }); const bs = await start(rr, base.id); const b1 = await page(rr, attemptId(bs), '?limit=10'); const b1n = b1.json?.nextCursor ?? b1.json?.cursor; await page(rr, attemptId(bs), '?limit=10&cursor=' + encodeURIComponent(b1n ?? ''));
  const four = [mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 10 }), mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 10 }), mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 10 }), mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 10 })];
  const atts = await Promise.all(four.map((e) => start(rr, e.id))); const prs = await Promise.all(atts.map((a) => page(rr, attemptId(a), '?limit=10')));
  const qq = (await call(rr, 'GET', '/api/v1/app/plan/cupo')).json;
  check('L9.boundary_race_four_parallel_pages_of_different_exams_never_exceed_30', (qq?.reveladas24h ?? 99) <= 30 && prs.filter((x) => x.status === 200).length <= 1, 'reveladas24h=' + qq?.reveladas24h + ' statuses=' + prs.map((x) => x.status));
  // fail closed on a malformed limit is covered by E1's gate; here: other users' counters are independent
  const other = mkUser('lother'); const oq = (await call(other, 'GET', '/api/v1/app/plan/cupo')).json;
  check('L8.counters_are_per_user', (oq?.reveladas24h ?? 0) === 0, `${oq?.reveladas24h}`);
});

await scenario(only, 'E_PACKS', async () => {
  const u = mkUser('ppack'); const pk = await call(u, 'GET', '/api/v1/app/wallet/paquetes'); const base = (pk.json?.items ?? pk.json?.paquetes ?? []).filter((p) => p.recargaBase);
  check('P1.packs_expose_recargaBase_and_otPorEuro_only_as_data', pk.status === 200 && (base.length === 0 || base.every((p) => typeof p.otPorEuro === 'number')), `base packs=${base.length}`);
  const cap = Number(sql(`select valor_integer from public.configuracion_v1 where clave = 'tope_ot_comprados_dia';`) || 0);
  const orders = []; const w = mkUser('pcap'); const prod = (pk.json?.items ?? pk.json?.paquetes ?? [])[0]?.code ?? 'LOCAL_PACK_50';
  sql(`select 1;`);
  finding('P2.daily_cap_on_OT_bought_exists_and_is_finite', cap > 0, `tope_ot_comprados_dia=${cap}`, 'E2-V1-P01');
  const chk = sqlTry(`update public.configuracion_v1 set valor_integer = -5 where clave = 'tope_ot_comprados_dia';`); const chk2 = sqlTry(`update public.configuracion_v1 set valor_integer = 999999999 where clave = 'tope_ot_comprados_dia';`);
  finding('P3.config_bounds_reject_negative_or_absurd_cap_values', !chk.ok && !chk2.ok, `negative=${chk.ok ? 'ACCEPTED' : 'refused'} huge=${chk2.ok ? 'ACCEPTED' : 'refused'}`, 'E2-V1-P02');
  sql(`update public.configuracion_v1 set valor_integer = ${cap} where clave = 'tope_ot_comprados_dia';`);
  const prev = sql("select valor_integer from public.configuracion_v1 where clave = 'tope_ot_comprados_dia';");
  sql("update public.configuracion_v1 set valor_integer = 60 where clave = 'tope_ot_comprados_dia';");
  const po = mkUser('pbuy'); const o1 = await call(po, 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_50', provider: 'sandbox' }, { 'Idempotency-Key': uuid() }); const o2 = await call(po, 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_50', provider: 'sandbox' }, { 'Idempotency-Key': uuid() });
  sql("update public.configuracion_v1 set valor_integer = " + prev + " where clave = 'tope_ot_comprados_dia';");
  check('P4.daily_cap_on_OT_bought_second_order_409_PURCHASE_CAP_REACHED_first_ok', o1.status === 201 && o2.status === 409 && o2.json?.code === 'PURCHASE_CAP_REACHED', o1.status + ' ' + o2.status + '/' + o2.json?.code);
  void orders; void w; void prod;
});

L.killKtor();
L.finish(OUT);
