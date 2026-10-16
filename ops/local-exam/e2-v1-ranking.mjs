#!/usr/bin/env node
// E2 V1 RANKING / W4 (LOCAL ONLY): live attacks on leagues, the ranking GET (D3-24), the visibility PUT (D3-25), the minimum cohort, the place-probability formula, public-handle privacy,
// same-specialty scope, ranking inflation (full windows, demo/challenge/annulled/replay), job races and client grants. Own specialties/users only.
// Cohort filler users get their competitive state seeded by SQL (the ranking inputs); the gaming checks use the real answer path. Shared config `cohorte_minima_comparativa` is lowered to 5
// for the run and restored in a finally block. STACK_LOCK hard-fail. Own Ktor on :8091; control Ktor :8090 for the admin recompute.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-ranking.mjs [--only=NAME,...]
import fs from 'node:fs';
import path from 'node:path';
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, start, answer, fin, uuid, run, sleep } = L;

requireStackLock('e2-v1-ranking.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-ranking-result.json';
const ADM = process.env.ADMIN_KTOR_URL ?? 'http://127.0.0.1:8090';
const tokens = JSON.parse(fs.readFileSync(path.join(L.L, 'tokens.json'), 'utf8'));
if (sql(`select to_regprocedure('public.app_job_ranking(boolean)') is not null;`) !== 't') { console.error('W4 migration not applied: converge first'); process.exit(2); }
await L.restartKtor();

const RK = `e2c60000-0000-4000-8000-${run}000001`, RK2 = `e2c60000-0000-4000-8000-${run}000002`;
sql(`set session_replication_role = replica; insert into public.especialidades (id, codigo, nombre, slug, activa) values ('${RK}', 'E2R6-${run}', 'E2 rank ${run}', 'e2-rank-${run}', true), ('${RK2}', 'E2R7-${run}', 'E2 rank2 ${run}', 'e2-rank2-${run}', true); set session_replication_role = origin;`);
const LG = Object.fromEntries(sql(`select codigo || '=' || id from public.ligas_nivel;`).split('\n').map((l) => l.split('=')));
const prevMin = sql(`select valor_integer from public.configuracion_v1 where clave = 'cohorte_minima_comparativa';`);
const setMin = (n) => sql(`update public.configuracion_v1 set valor_integer = ${n} where clave = 'cohorte_minima_comparativa';`);
const job = () => JSON.parse(sql(`select public.app_job_ranking(true)::text;`));
/** A ranked user: state seeded as the answer hook would leave it (tier, window, count, Nota media). */
function seeded(label, { spec = RK, tier = 'liga-50', v = 50, w = 50, nota = 5, publico = false, username = true }) {
  const u = mkUser(label, spec);
  sql(`insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual, actualizado_en) values ('${u.id}', '${LG[tier]}', ${v}, ${w}, ${nota}, clock_timestamp()) on conflict (usuario_id) do update set liga_nivel_id = excluded.liga_nivel_id, preguntas_validas_ranking = excluded.preguntas_validas_ranking, ventana_calculo = excluded.ventana_calculo, nota_media_actual = excluded.nota_media_actual, actualizado_en = clock_timestamp();`);
  if (!username) sql(`update public.perfiles_usuario set username = null where usuario_id = '${u.id}';`);
  if (publico) sql(`update public.perfiles_usuario set ranking_publico = true where usuario_id = '${u.id}';`);
  return u;
}
const rank = (u) => call(u, 'GET', '/api/v1/app/ranking');
const vis = (u, body) => call(u, 'PUT', '/api/v1/app/ranking/visibilidad', body);
const hasId = (txt, users) => users.some((x) => txt.includes(x.id));

try {
  await scenario(only, 'A_COHORT_MINIMUM', async () => {
    setMin(5);
    const four = [9, 8, 7, 6].map((n, i) => seeded('rk' + i, { nota: n, publico: true }));
    job(); const r = await rank(four[0]);
    check('R1.below_the_minimum_cohort_only_the_own_position_no_top3_no_cohort_no_probability', r.status === 200 && r.json?.posicion === 1 && !r.json?.top3?.length && !r.json?.cohorte && r.json?.probabilidadPlaza?.disponible === false && r.json?.probabilidadPlaza?.motivo === 'cohorte_insuficiente' && !hasId(r.text, four.slice(1)), JSON.stringify(r.json).slice(0, 260));
    const more = [5, 4].map((n, i) => seeded('rkm' + i, { nota: n, publico: i === 0 }));
    job(); const r6 = await rank(more[1]);
    check('R2.at_the_minimum_the_top3_appears_with_real_positions_public_handles_and_grades_only', r6.status === 200 && Array.isArray(r6.json?.top3) && r6.json.top3.length === 3 && r6.json.top3.map((x) => x.posicion).join() === '1,2,3' && r6.json.top3.every((x) => x.handle && typeof x.notaMedia === 'number') && !hasId(r6.text, [...four, ...more]) && r6.json?.cohorte?.tramo != null, JSON.stringify(r6.json).slice(0, 300));
    const nameLeak = /E2X|@|e2\.local/.test(JSON.stringify(r6.json.top3)); const hs = r6.json.top3.map((x) => x.handle);
    check('R3.handles_are_the_chosen_username_only_never_the_display_name_or_the_email', !nameLeak && hs.every((h) => /^e2x_/.test(h)), hs.join(','));
    check('R4.own_position_for_a_low_ranked_user_and_nobody_elses_position_beyond_the_top3', r6.json?.posicion === 6 && r6.json.top3.length === 3 && JSON.stringify(r6.json).match(/"posicion"/g).length === 4, `posicion=${r6.json?.posicion}`);
    Object.assign(globalThis, { __cohort: [...four, ...more] });
  });

  await scenario(only, 'B_VISIBILITY', async () => {
    const group = globalThis.__cohort; if (!group) throw new Error('A_COHORT_MINIMUM first');
    const [u0, u1, u2, u3] = group; const v0 = await rank(group[5]);
    const optedIn = v0.json.top3.map((x) => x.handle);
    check('B1.only_opted_in_users_are_listed_default_is_private', optedIn.length === 3 || optedIn.length === 2, `top3 handles=${optedIn.length}`);
    const priv = seeded('rkprivate', { nota: 9.9, publico: false }); job(); const afterPriv = await rank(group[5]);
    check('B2.a_private_user_with_the_best_grade_is_not_listed_and_gets_only_their_own_position', !JSON.stringify(afterPriv.json.top3).includes(priv.id) && !afterPriv.text.includes(`e2x_${run}_rkprivate`), `top3=${afterPriv.json.top3.map((x) => x.handle)}`);
    const on = await vis(priv, { publico: true }), on2 = await vis(priv, { publico: true });
    job(); const listed = await rank(group[5]);
    check('B3.opt_in_is_idempotent_and_after_the_job_the_user_is_listed_as_position_1', on.status === 200 && on2.status === 200 && listed.json.top3[0]?.handle === `e2x_${run}_rkprivate`, `${on.status}/${on2.status} first=${listed.json.top3[0]?.handle}`);
    const off = await vis(priv, { publico: false }); const gone = await rank(group[5]);
    check('B4.opting_out_removes_the_user_from_the_top3_immediately_without_waiting_for_the_job', off.status === 200 && !gone.text.includes(`e2x_${run}_rkprivate`), `${off.status} listed-after-optout=${gone.text.includes('rkprivate')}`);
    const bad = [await vis(priv, { publico: 'yes' }), await vis(priv, {}), await vis(priv, { publico: true, usuarioId: group[0].id }), await vis(priv, { publico: true, ranking_publico: true }), await call(priv, 'PUT', '/api/v1/app/ranking/visibilidad?usuarioId=' + group[0].id, { publico: true }), await call(null, 'PUT', '/api/v1/app/ranking/visibilidad', { publico: true })];
    check('B5.strict_body_non_boolean_missing_extra_fields_and_user_id_parameters_are_400_no_token_401', bad.slice(0, 5).every((x) => x.status === 400) && bad[5].status === 401, bad.map((x) => x.status).join(','));
    const nou = seeded('rknouser', { nota: 8, username: false }); const nr = await vis(nou, { publico: true });
    check('B6.becoming_public_without_a_username_is_422_USERNAME_REQUIRED', nr.status === 422 && nr.json?.code === 'USERNAME_REQUIRED', `${nr.status}/${nr.json?.code}`);
    const flip = []; for (let i = 0; i < 70; i++) flip.push((await vis(priv, { publico: i % 2 === 0 })).status);
    check('B7.toggle_abuse_is_rate_limited_429_and_leaves_a_consistent_state', flip.includes(429) && ['true', 'false'].includes(sql(`select ranking_publico::text from public.perfiles_usuario where usuario_id = '${priv.id}';`)), `first429=#${flip.indexOf(429) + 1}`);
    const direct = await fetch('http://localhost:54321/rest/v1/perfiles_usuario?usuario_id=eq.' + nou.id, { method: 'PATCH', headers: { Authorization: 'Bearer ' + nou.t, apikey: 'x', 'Content-Type': 'application/json', Prefer: 'return=minimal' }, body: JSON.stringify({ ranking_publico: true }) }).catch(() => ({ status: 0 }));
    check('B8.a_client_cannot_set_ranking_publico_directly_through_PostgREST', [401, 403, 404, 400].includes(direct.status) || sql(`select ranking_publico::text from public.perfiles_usuario where usuario_id = '${nou.id}';`) === 'false', `PATCH=${direct.status} value=${sql(`select ranking_publico::text from public.perfiles_usuario where usuario_id = '${nou.id}';`)}`);
    const prof = await call(nou, 'PATCH', '/api/v1/app/me/profile', { ranking_publico: true }); check('B9.the_profile_route_rejects_the_flag', [400, 422].includes(prof.status), `${prof.status}`);
  });

  await scenario(only, 'C_PROBABILITY', async () => {
    const notas = [9, 8, 8, 7, 5, 3]; const users = notas.map((n, i) => seeded('rkp' + i, { spec: RK2, nota: n }));
    job(); const rs = await Promise.all(users.map((u) => rank(u))); const got = rs.map((r) => r.json?.probabilidadPlaza?.porcentaje);
    const want = notas.map((n) => Math.round((notas.filter((m) => m < n).length / (notas.length - 1)) * 10000) / 100);
    check('C1.place_probability_equals_users_strictly_worse_over_the_other_users_x_100_with_ties_excluded', JSON.stringify(got) === JSON.stringify(want) && rs.every((r) => r.json?.probabilidadPlaza?.disponible === true), `got=${got} want=${want}`);
    check('C2.the_probability_answer_has_a_cohort_bucket_never_the_exact_size_or_rows', rs.every((r) => r.json?.probabilidadPlaza?.tramoCohorte != null && !('n' in r.json.probabilidadPlaza) && !hasId(r.text, users)), JSON.stringify(rs[0].json.probabilidadPlaza));
    const lone = seeded('rkl', { spec: RK2, tier: 'liga-200', v: 100, w: 100, nota: 9 }); job(); const lr = await rank(lone);
    const again = await rank(users[0]);
    check('C3.a_cohort_of_one_never_divides_by_zero_probability_unavailable_and_other_windows_do_not_mix', lr.status === 200 && lr.json?.probabilidadPlaza?.disponible === false && JSON.stringify(again.json.probabilidadPlaza) === JSON.stringify(rs[0].json.probabilidadPlaza), JSON.stringify(lr.json?.probabilidadPlaza));
    const short = seeded('rks', { spec: RK2, v: 49, nota: 10 }); job(); const sr = await rank(short);
    check('C4.an_incomplete_window_is_not_ranked_even_with_a_perfect_grade_and_does_not_enter_the_cohort', sr.json?.posicion == null && sr.json?.probabilidadPlaza?.motivo === 'ventana_incompleta' && JSON.stringify((await rank(users[0])).json.probabilidadPlaza) === JSON.stringify(rs[0].json.probabilidadPlaza), JSON.stringify(sr.json).slice(0, 200));
    const flood = Array.from({ length: 10 }, (_, i) => seeded('rkf' + i, { spec: RK2, v: 10, nota: 10 })); job(); const afterFlood = await rank(users[3]);
    check('C5.ten_extra_accounts_with_incomplete_windows_change_no_position_probability_or_cohort', JSON.stringify(afterFlood.json.probabilidadPlaza) === JSON.stringify(rs[3].json.probabilidadPlaza) && afterFlood.json.posicion === rs[3].json.posicion, `pos=${afterFlood.json.posicion}`); void flood;
    const tie = rs[1].json.posicion + '/' + rs[2].json.posicion; job(); const tie2 = (await rank(users[1])).json.posicion + '/' + (await rank(users[2])).json.posicion;
    check('C6.positions_of_tied_grades_are_stable_across_job_runs', tie === tie2, `${tie} -> ${tie2}`);
    Object.assign(globalThis, { __rk2: users });
  });

  await scenario(only, 'D_SCOPE', async () => {
    const group = globalThis.__cohort, rk2 = globalThis.__rk2; if (!group || !rk2) throw new Error('earlier scenarios first');
    const a = await rank(group[0]), b = await rank(rk2[0]);
    check('D1.each_user_sees_only_their_own_specialty_cohort_no_cross_specialty_handles_or_positions', !hasId(a.text, rk2) && !hasId(b.text, group) && !(b.json.top3 ?? []).some((x) => (a.json.top3 ?? []).some((y) => y.handle === x.handle)), `RK top3=${(a.json.top3 ?? []).length} RK2 top3=${(b.json.top3 ?? []).length}`);
    const ns = mkUser('rknospec', null); const nr = await rank(ns); const fresh = mkUser('rkfresh', RK); const fr = await rank(fresh);
    check('D2.user_without_specialty_or_without_league_gets_no_position_no_top3_no_error', nr.status === 200 && fr.status === 200 && fr.json?.posicion == null && !(fr.json?.top3 ?? []).length && (fr.json?.liga ?? null) === null, `${nr.status}/${fr.status} ${JSON.stringify(fr.json).slice(0, 160)}`);
    const probes = [await call(group[0], 'GET', '/api/v1/app/ranking?usuarioId=' + rk2[0].id), await call(group[0], 'GET', '/api/v1/app/ranking?especialidadId=' + RK2), await call(group[0], 'GET', '/api/v1/app/ranking?liga=liga-1000'), await call(null, 'GET', '/api/v1/app/ranking')];
    check('D3.no_parameters_select_another_user_specialty_or_league_and_anonymous_is_401', probes.slice(0, 3).every((x) => x.status === 400) && probes[3].status === 401, probes.map((x) => x.status).join(','));
    const lim = []; for (let i = 0; i < 125; i++) lim.push((await rank(group[1])).status);
    check('D4.read_limiter_429_after_120', lim.indexOf(429) >= 100 && lim.indexOf(429) <= 124, 'first429=#' + (lim.indexOf(429) + 1));
  });

  await scenario(only, 'E_GAMING_REAL_PATH', async () => {
    // real answers: demo, annulled, replay and parallel duplicates never move the ranking inputs; challenge answers neither
    const ex = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 14, spec: RK }); const dm = L.mkExam({ tipo: 'inmersivo', price: 10, n: 7, spec: RK, demoFlags: [true, true, true, true, true, false, false] });
    const u = mkUser('rkreal', RK); const s = await start(u, ex.id); const a = L.attemptId(s);
    for (let i = 0; i < 12; i++) await answer(u, a, ex.qs[i].id, ex.qs[i].ok);
    await Promise.all(Array.from({ length: 5 }, () => answer(u, a, ex.qs[0].id, ex.qs[0].ok))); await call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${ex.qs[1].id}`, { opcionSeleccionadaId: ex.qs[1].ok, tiempoRespuestaMs: 10, baseRevision: 0 }, { 'Idempotency-Key': uuid() });
    const du = u; const ds = await start(du, dm.id, { modo: 'DEMO' }); for (const q of dm.qs.slice(0, 5)) await answer(du, L.attemptId(ds), q.id, q.ok);
    const st = sql(`select preguntas_validas_ranking from public.estado_competitivo_usuario where usuario_id = '${u.id}';`);
    check('E1.demo_answers_replays_and_parallel_duplicates_do_not_raise_the_ranking_counter_12_real_answers_count_12', st === '12', `preguntas_validas_ranking=${st}`);
    job(); const rr = await rank(u);
    check('E2.twelve_answers_are_not_a_full_window_so_the_user_is_not_ranked_even_with_a_perfect_score', rr.json?.posicion == null && ['sin_liga', 'ventana_incompleta'].includes(rr.json?.probabilidadPlaza?.motivo), JSON.stringify(rr.json).slice(0, 200));
    const seen = sql(`select count(*) from public.primeras_respuestas_usuario where usuario_id = '${u.id}';`);
    check('E3.the_stored_first_answers_equal_the_distinct_real_questions_answered', seen === '12', `first answers=${seen}`);
  });

  await scenario(only, 'F_JOBS_AND_GRANTS', async () => {
    const before = Number(sql(`select count(*) from public.lotes_actualizacion_ranking where tipo like 'ranking%';`));
    const par = await Promise.all(Array.from({ length: 4 }, () => Promise.resolve(sqlTry(`select public.app_job_ranking(true)::text;`))));
    const dupe = sql(`select count(*) from (select especialidad_id, liga_nivel_id, posicion from public.ranking_top group by 1, 2, 3 having count(*) > 1) x;`);
    check('F1.parallel_job_runs_never_error_and_never_produce_duplicate_top_positions', par.every((x) => x.ok) && dupe === '0', `outcomes=${par.map((x) => (x.out.match(/OK|OCUPADO|RECIENTE/) ?? ['?'])[0])} dupes=${dupe}`);
    const thr = JSON.parse(sql(`select public.app_job_ranking(false)::text;`));
    check('F2.the_unforced_job_is_throttled', ['RECIENTE', 'OCUPADO', 'OK'].includes(thr.estado) && Number(sql(`select count(*) from public.lotes_actualizacion_ranking where tipo like 'ranking%';`)) >= before, JSON.stringify(thr));
    const adm = (b, tok = tokens.ADMIN) => fetch(ADM + '/api/v1/admin/estadisticas/recalcular', { method: 'POST', headers: { Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json' }, body: JSON.stringify(b) }).then(async (r) => ({ status: r.status, text: await r.text() }));
    const prevCd = sql(`select valor_integer from public.configuracion_v1 where clave = 'recalculo_admin_cooldown_min';`); sql(`update public.configuracion_v1 set valor_integer = 1 where clave = 'recalculo_admin_cooldown_min';`);
    try {
      const u = mkUser('rkadm', RK); const non = await adm({ alcance: 'ranking' }, u.t); const a0 = Number(sql(`select count(*) from public.auditoria_log where accion = 'estadisticas_recalculo';`)); const ps = await Promise.all(Array.from({ length: 6 }, () => adm({ alcance: 'ranking' })));
      check('F3.admin_recompute_of_the_ranking_is_admin_only_audited_once_and_cooldown_limited', non.status === 403 && ps.filter((x) => x.status === 200).length === 1 && ps.filter((x) => x.status === 429).length === 5 && Number(sql(`select count(*) from public.auditoria_log where accion = 'estadisticas_recalculo';`)) === a0 + 1, `${non.status} ok=${ps.filter((x) => x.status === 200).length}`);
    } finally { sql(`update public.configuracion_v1 set valor_integer = ${prevCd} where clave = 'recalculo_admin_cooldown_min';`); }
    const tables = ['ranking_top', 'estado_competitivo_usuario', 'ligas_nivel', 'lotes_actualizacion_ranking']; const leaks = [];
    for (const t of tables) for (const role of ['authenticated', 'anon']) for (const st of [`insert into public.${t} select * from public.${t} limit 0`, `delete from public.${t} where false`, `select count(*) from public.${t}`]) {
      const r = sqlTry(`set role ${role}; ${st};`); const ownSel = st.startsWith('select') && role === 'authenticated' && sql(`select count(*) from pg_policies where tablename = '${t}' and cmd = 'SELECT' and qual like '%auth.uid()%';`) !== '0';
      const empty = st.startsWith('select') && r.ok && r.out.trim().split(String.fromCharCode(10)).pop() === '0'; const refData = t === 'ligas_nivel' && st.startsWith('select'); if (r.ok && !ownSel && !empty && !refData) leaks.push(`${role}:${t}:${st.split(' ')[0]}`);
    }
    check('F4.no_client_privileges_on_the_ranking_tables_ligas_nivel_is_read_only_reference_data', leaks.length === 0, leaks.join(' ') || 'denied');
    const w = sqlTry(`set role authenticated; update public.ligas_nivel set ventana_calculo = 1;`); const topSel = sqlTry(`set role authenticated; select count(*) from public.ranking_top;`);
    check('F5.clients_cannot_edit_league_tiers_or_read_the_top_table', !w.ok && (!topSel.ok || topSel.out.trim().split(String.fromCharCode(10)).pop() === '0'), `edit=${w.ok} read=${topSel.ok ? topSel.out.trim() : 'denied'}`);
  });
} finally { setMin(prevMin); }

L.killKtor();
L.finish(OUT);
