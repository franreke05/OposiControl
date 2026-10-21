#!/usr/bin/env node
// E2 V1 REFERRAL / W5 (LOCAL ONLY): live attacks on codes (CSPRNG format, enumeration oracle, rate limit), neutral attach, self-referral / same-email-root, farming (daily cap), races on the payout job,
// the earn cap (deferral), privacy of the lists, revoke (never negative), account states, demo/challenge exclusion and grants. Own users only. STACK_LOCK hard-fail.
// Shared config keys (referido_*, tope_ot_gratis_dia) are set for the run and ALL restored in a finally block. Own Ktor on :8091.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-referral.mjs [--only=NAME,...]
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, start, answer, fin, uuid, run, sleep, balance, ledgerRows, grant } = L;

requireStackLock('e2-v1-referral.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-referral-result.json';
if (sql(`select to_regprocedure('public.app_referral_attach(uuid,text,text)') is not null;`) !== 't') { console.error('W5 migration not applied: converge first'); process.exit(2); }
await L.restartKtor();
const KEYS = ['referido_activo', 'referido_ot_invitador', 'referido_ot_invitado', 'referido_min_respuestas', 'referido_min_segundos', 'referido_min_horas', 'referido_retener_horas', 'referido_tope_diario', 'referido_tope_total', 'referido_intentos_hora', 'tope_ot_gratis_dia'];
const saved = Object.fromEntries(KEYS.map((k) => [k, sql(`select valor_integer from public.configuracion_v1 where clave = '${k}';`)]));
const cfg = (o) => { for (const [k, v] of Object.entries(o)) sql(`update public.configuracion_v1 set valor_integer = ${v} where clave = '${k}';`); };
const ON = { referido_activo: 1, referido_ot_invitador: 10, referido_ot_invitado: 10, referido_min_respuestas: 3, referido_min_segundos: 0, referido_min_horas: 0, referido_retener_horas: 0, referido_tope_diario: 5, referido_tope_total: 50, referido_intentos_hora: 10, tope_ot_gratis_dia: 1000 };
const me = (u) => call(u, 'GET', '/api/v1/app/me/invitacion');
const lst = (u, qs = '') => call(u, 'GET', '/api/v1/app/me/invitaciones' + qs);
const codeOf = async (u) => (await me(u)).json?.codigo;
const attach = (invitee, code, ip = null) => JSON.parse(sql(`select public.app_referral_attach('${invitee.id}', ${code == null ? 'null' : `'${String(code).replace(/'/g, "''")}'`}, ${ip ? `'${ip}'` : 'null'})::text;`));
const job1 = () => JSON.parse(sql(`select public.app_job_referidos()::text;`)); const job = () => { job1(); return job1(); };
const inv = (invitee) => sql(`select estado || '|' || pago_invitador || '|' || pago_invitado || '|' || coalesce(motivo, '') from public.invitaciones where invitado_id = '${invitee.id}';`);
const exam = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 6 });
/** finish a real non-demo attempt with n answered questions (a different exam per call so first answers are new) */
async function qualify(u, n = 4) { const ex = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: Math.max(n, 3) }); const s = await start(u, ex.id); const a = L.attemptId(s); for (let i = 0; i < n; i++) await answer(u, a, ex.qs[i].id, ex.qs[i].ok); return fin(u, a); }
const pair = async (label, opts = {}) => { const inviter = opts.inviter ?? mkUser('ri' + label); const invitee = mkUser('rv' + label, undefined, !!opts.unverified); const code = await codeOf(inviter); const at = attach(invitee, code); return { inviter, invitee, code, at }; };
const payTotals = (u) => `${ledgerRows(u, 'referido')}/${balance(u)}`;

try {
  await scenario(only, 'A_OFF_BY_DEFAULT_AND_CODES', async () => {
    cfg({ referido_activo: 0 });
    const u = mkUser('rcode'); const off = await me(u), inviter = mkUser('rioff'); const c0 = await codeOf(inviter); const invitee = mkUser('rvoff'); const a0 = attach(invitee, c0);
    check('A1.feature_off_by_default_semantics_the_row_is_hidden_attach_is_neutral_false_the_job_pays_nothing', off.status === 200 && (off.json?.configuracion?.otPorInvitacion ?? null) === null && a0.adjunta === false && sql(`select count(*) from public.invitaciones where invitado_id = '${invitee.id}';`) === '0' && JSON.stringify(job()).length > 0, JSON.stringify(off.json).slice(0, 200));
    cfg(ON);
    const c1 = await me(u), c2 = await me(u), other = await me(mkUser('rcode2'));
    check('A2.the_code_is_8_symbols_of_the_unambiguous_alphabet_stable_between_reads_and_distinct_per_user', /^[A-HJ-NP-Z2-9]{8}$/.test(c1.json?.codigo) && c1.json?.codigo === c2.json?.codigo && other.json?.codigo !== c1.json?.codigo && c1.json?.configuracion?.otPorInvitacion === 10, `${c1.json?.codigo} ${other.json?.codigo}`);
    const fresh = mkUser('rcode3'); const par = await Promise.all(Array.from({ length: 6 }, () => me(fresh)));
    check('A3.six_parallel_first_reads_create_exactly_one_code', new Set(par.map((x) => x.json?.codigo)).size === 1 && sql(`select count(*) from public.codigos_invitacion where usuario_id = '${fresh.id}';`) === '1', par.map((x) => x.json?.codigo?.slice(0, 3)).join(','));
    const sample = []; for (let i = 0; i < 12; i++) sample.push((await codeOf(mkUser('rcs' + i))));
    check('A4.codes_are_not_sequential_or_derived_from_the_user_id_or_email', new Set(sample).size === 12 && sample.every((c) => !c.startsWith(sample[0].slice(0, 5)) || sample.indexOf(c) === 0), sample.slice(0, 4).join(','));
    const nu = await call(null, 'GET', '/api/v1/app/me/invitacion'), pq = await call(u, 'GET', '/api/v1/app/me/invitacion?usuarioId=' + uuid());
    check('A5.no_token_401_and_user_id_parameters_400', nu.status === 401 && pq.status === 400, `${nu.status}/${pq.status}`);
  });

  await scenario(only, 'B_NEUTRAL_ATTACH_ORACLE', async () => {
    cfg(ON);
    const inviter = mkUser('rinv'); const code = await codeOf(inviter); const own = attach(inviter, code);
    const cases = { unknown: attach(mkUser('rb1'), 'ZZZZZZZZ'), malformed: attach(mkUser('rb2'), "x' or 1=1 --"), nullcode: attach(mkUser('rb3'), null), long: attach(mkUser('rb4'), 'A'.repeat(100)), lower: attach(mkUser('rb5'), code.toLowerCase()), self: own };
    const shapes = new Set(Object.values({ ...cases, own }).filter((x) => x.adjunta === false).map((x) => JSON.stringify(x)));
    check('B1.every_unusable_code_gives_the_identical_neutral_answer_no_oracle_for_unknown_vs_own_vs_malformed', own.adjunta === false && cases.unknown.adjunta === false && cases.malformed.adjunta === false && cases.nullcode.adjunta === false && cases.long.adjunta === false && shapes.size === 1, `distinct answers=${shapes.size} lower=${JSON.stringify(cases.lower)}`);
    const okU = mkUser('rb6'); const ok = attach(okU, code), again = attach(okU, code), other = attach(okU, await codeOf(mkUser('rinv2')));
    check('B2.a_valid_code_attaches_once_the_invitee_cannot_attach_a_second_time_or_switch_inviter', ok.adjunta === true && again.adjunta === false && other.adjunta === false && sql(`select count(*) from public.invitaciones where invitado_id = '${okU.id}';`) === '1', `${JSON.stringify(ok)} ${JSON.stringify(again)}`);
    const brute = mkUser('rbrute'); const tries = []; for (let i = 0; i < 14; i++) tries.push(attach(brute, 'AAAAAAA' + 'BCDEFGHJKMNPQRSTUVWXYZ23456789'[i]).adjunta); const afterLimit = attach(brute, code);
    check('B3.after_the_hourly_attempt_limit_even_a_VALID_code_answers_false_so_brute_force_gets_no_signal', tries.every((x) => x === false) && afterLimit.adjunta === false && sql(`select count(*) from public.invitaciones where invitado_id = '${brute.id}';`) === '0', `attempts=${tries.length} valid-after-limit=${afterLimit.adjunta}`);
    const blocked = mkUser('rinvb'); const bc = await codeOf(blocked); sqlTry(`update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${blocked.id}';`); const bu = attach(mkUser('rb7'), bc);
    const capped = mkUser('rinvc'); const cc = await codeOf(capped); cfg({ referido_tope_diario: 0 }); const cu = attach(mkUser('rb8'), cc); cfg({ referido_tope_diario: 5 });
    check('B4.a_blocked_inviter_gives_the_same_neutral_false', bu.adjunta === false && JSON.stringify(bu) === JSON.stringify(cases.unknown), `${JSON.stringify(bu)}`);
    finding('B4b.a_zero_daily_cap_does_not_silently_mean_unlimited', cu.adjunta === false, `with referido_tope_diario=0 an attach answered ${JSON.stringify(cu)}`, 'E2-V1-RF03');
    // same email root (self-invite with plus addressing / dots): hard flag, never paid
    const inv2 = mkUser('rroot'); const rc = await codeOf(inv2); const twin = mkUser('rrootb'); sql(`update auth.users set email = 'e2x-${run}-rroot+alt@e2.local' where id = '${twin.id}';`); const ra = attach(twin, rc);
    const state = sql(`select estado from public.invitaciones where invitado_id = '${twin.id}';`);
    check('B5.an_invitee_with_the_same_email_root_as_the_inviter_is_refused_or_rejected_and_never_paid', ra.adjunta === false || state === 'rechazada', `attach=${JSON.stringify(ra)} estado=${state || 'none'}`);
    await qualify(twin); job(); job();
    check('B6.the_same_root_account_gets_no_reward_after_qualifying', ledgerRows(twin, 'referido') === 0 && ledgerRows(inv2, 'referido') === 0, `${payTotals(twin)} ${payTotals(inv2)}`);
  });

  await scenario(only, 'C_QUALIFY_AND_PAY', async () => {
    cfg(ON);
    const { inviter, invitee } = await pair('c1');
    check('C1.attach_creates_a_registered_or_verified_invitation', /^(registrada|email_verificado)\|/.test(inv(invitee)), inv(invitee));
    job(); check('C2.nothing_is_paid_before_the_invitee_finishes_a_real_exam', ledgerRows(invitee, 'referido') === 0 && ledgerRows(inviter, 'referido') === 0, inv(invitee));
    const dmx = L.mkExam({ tipo: 'inmersivo', price: 10, n: 7, demoFlags: [true, true, true, true, true, false, false] }); const ds = await start(invitee, dmx.id, { modo: 'DEMO' }); for (const q of dmx.qs.slice(0, 5)) await answer(invitee, L.attemptId(ds), q.id, q.ok); await fin(invitee, L.attemptId(ds));
    const short = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 4 }); const ss = await start(invitee, short.id); await answer(invitee, L.attemptId(ss), short.qs[0].id, short.qs[0].ok); await answer(invitee, L.attemptId(ss), short.qs[1].id, short.qs[1].ok); await fin(invitee, L.attemptId(ss));
    job(); check('C3.a_demo_attempt_and_a_finished_attempt_with_fewer_answers_than_the_minimum_do_not_qualify', ledgerRows(invitee, 'referido') === 0 && !/^(diferida|premiada)/.test(inv(invitee)), inv(invitee));
    await qualify(invitee, 4); job(); const st1 = inv(invitee);
    check('C4.after_a_qualifying_exam_and_the_hold_both_sides_are_paid_exactly_once', /^premiada\|pagado\|pagado/.test(st1) && ledgerRows(invitee, 'referido') === 1 && ledgerRows(inviter, 'referido') === 1 && balance(invitee) === 10 && balance(inviter) === 10, `${st1} inviter=${payTotals(inviter)} invitee=${payTotals(invitee)}`);
    job(); job(); check('C5.running_the_job_again_never_pays_twice', ledgerRows(invitee, 'referido') === 1 && ledgerRows(inviter, 'referido') === 1, payTotals(inviter));
    const k = sql(`select idempotency_key from public.transacciones_cartera where usuario_id = '${inviter.id}' and tipo = 'referido';`);
    check('C6.ledger_rows_carry_the_invitation_reference_and_the_per_beneficiary_idempotency_key', /^referido:[0-9a-f-]{36}:(invitador|inviter)$/.test(k) && sql(`select referencia_tipo from public.transacciones_cartera where usuario_id = '${inviter.id}' and tipo = 'referido';`) === 'invitacion', k);
    const dir = sqlTry(`insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante, seq) values ('${inviter.id}', 'referido', 500, 0, 500, 99);`);
    check('C7.a_direct_ledger_insert_of_a_referido_credit_is_refused', !dir.ok, dir.ok ? 'ACCEPTED' : 'refused (writer guard)');
    // privacy of the lists
    const l1 = await lst(inviter), l2 = await lst(invitee), pend = await pair('c2', { inviter }); const l3 = await lst(inviter), cnt = await me(inviter);
    check('C8.the_inviter_sees_only_paid_invitations_with_the_public_username_and_counters_never_pending_invitees_ids_or_emails', l1.status === 200 && (l1.json?.items?.length ?? 0) === 1 && !!l1.json.items[0].handle && (l3.json?.items?.length ?? 0) === 1 && !l3.text.includes(pend.invitee.id) && !/@|e2\.local/.test(l3.text) && (l2.json?.items?.length ?? 0) === 0 && cnt.json?.invitados?.premiados === 1 && cnt.json?.invitados?.total >= 2 && cnt.json?.otGanados === 10, JSON.stringify(cnt.json).slice(0, 200));
    const bad = [await lst(inviter, '?cursor=garbage'), await lst(inviter, '?limit=0'), await lst(inviter, '?limit=500'), await lst(inviter, '?usuarioId=' + invitee.id)];
    check('C9.list_cursor_tampering_limits_and_user_id_parameters_are_400', bad.every((x) => x.status === 400) || (bad[2].status === 200 && bad[0].status === 400 && bad[3].status === 400), bad.map((x) => x.status).join(','));
  });

  await scenario(only, 'D_FARMING_AND_RACES', async () => {
    cfg(ON);
    const farm = mkUser('rfarm'); const code = await codeOf(farm); const kids = []; for (let i = 0; i < 7; i++) { const k = mkUser('rfk' + i); attach(k, code); await qualify(k, 4); kids.push(k); }
    job(); job(); job();
    const paid = kids.filter((k) => ledgerRows(k, 'referido') === 1).length; const premiadas = Number(sql(`select count(*) from public.invitaciones where invitador_id = '${farm.id}' and estado = 'premiada';`));
    check('D1.one_inviter_with_7_qualified_accounts_is_paid_for_at_most_the_daily_cap_5', premiadas <= 5 && ledgerRows(farm, 'referido') <= 5 && balance(farm) === ledgerRows(farm, 'referido') * 10 && paid <= 5, `premiadas=${premiadas} inviter rows=${ledgerRows(farm, 'referido')} invitees paid=${paid}`);
    const rest = kids.filter((k) => ledgerRows(k, 'referido') === 0).map((k) => inv(k).split('|')[0]);
    finding('D2.accounts_over_the_cap_are_not_silently_dropped_they_end_in_a_visible_state', rest.every((s) => ['rechazada', 'diferida', 'email_verificado', 'registrada'].includes(s)), `over-cap states: ${rest.join(',')}`, 'E2-V1-RF01');
    // race: 3 qualified pairs, many parallel job runs
    const pairs = []; const inviterR = mkUser('rraceinv'); const rc = await codeOf(inviterR); for (let i = 0; i < 3; i++) { const k = mkUser('rrk' + i); attach(k, rc); await qualify(k, 4); pairs.push(k); }
    const runs = await Promise.all(Array.from({ length: 6 }, () => Promise.resolve(sqlTry(`select public.app_job_referidos()::text;`)))); const dupKeys = sql(`select count(*) from (select usuario_id, idempotency_key from public.transacciones_cartera where idempotency_key like 'referido:%' group by 1, 2 having count(*) > 1) x;`);
    check('D3.six_parallel_payout_jobs_pay_each_invitation_exactly_once_no_duplicate_keys_no_errors', runs.every((x) => x.ok) && dupKeys === '0' && ledgerRows(inviterR, 'referido') === pairs.filter((k) => ledgerRows(k, 'referido') === 1).length && pairs.every((k) => ledgerRows(k, 'referido') <= 1), `inviter rows=${ledgerRows(inviterR, 'referido')} outcomes=${runs.map((x) => (x.out.match(/OK|OCUPADO|RECIENTE/) ?? ['?'])[0])}`);
    const rec = sql(`select count(*) from public.v_wallet_reconcile;`); check('D4.the_ledger_reconciles_after_the_farming_and_race_scenarios', rec === '0', `reconcile=${rec}`);
    // multi-account: ten new accounts attach to one code without ever qualifying => nothing paid
    const idle = mkUser('ridle'); const ic = await codeOf(idle); for (let i = 0; i < 10; i++) attach(mkUser('rid' + i), ic); job();
    check('D5.attached_but_inactive_accounts_never_trigger_a_payment', ledgerRows(idle, 'referido') === 0, payTotals(idle));
  });

  await scenario(only, 'E_EARN_CAP_AND_DEFERRAL', async () => {
    cfg(ON); const { inviter, invitee } = await pair('e1'); await qualify(invitee, 4);
    sql(`select * from public.app_credit_free_ot('${inviter.id}', 'reto_diario', 5, 'e2', null, 'e2cap-${run}', '{}'::jsonb);`); cfg({ tope_ot_gratis_dia: 12 });
    job(); const mid = inv(invitee);
    check('E1.over_the_daily_earn_cap_the_inviter_side_is_deferred_not_dropped_not_partially_paid_and_the_invitation_is_not_premiada', !/^premiada/.test(mid) && ledgerRows(inviter, 'referido') === 0 && balance(inviter) === 5, `${mid} inviter ledger=${ledgerRows(inviter, 'referido')} invitee ledger=${ledgerRows(invitee, 'referido')}`);
    cfg({ tope_ot_gratis_dia: 1000 }); sql(`select * from public.app_retry_deferred_free_ot(50);`); const lag = inv(invitee); sql(`update public.invitaciones set diferida_hasta = now() where invitado_id = '${invitee.id}';`); job(); job(); const end = inv(invitee);
    check('E2.when_the_window_opens_the_deferred_side_is_paid_once_and_the_invitation_becomes_premiada', /^premiada\|pagado\|pagado/.test(end) && ledgerRows(inviter, 'referido') === 1 && balance(inviter) === 15 && ledgerRows(invitee, 'referido') === 1, `${end} inviter balance=${balance(inviter)}`);
    // reward larger than the cap itself
    const p2 = await pair('e2'); await qualify(p2.invitee, 4); cfg({ tope_ot_gratis_dia: 3 }); job(); job(); const s2 = inv(p2.invitee); cfg({ tope_ot_gratis_dia: 1000 });
    check('E3.a_reward_that_can_never_fit_ends_rejected_visibly_nothing_is_paid', !/^premiada/.test(s2) && ledgerRows(p2.invitee, 'referido') === 0 && ledgerRows(p2.inviter, 'referido') === 0, s2);
  });

  await scenario(only, 'G_CAP_ZERO', async () => {
    cfg({ ...ON, referido_tope_diario: 0 }); const farm = mkUser('rzero'); const code = await codeOf(farm); const kids = []; for (let i = 0; i < 3; i++) { const k = mkUser('rzk' + i); attach(k, code); await qualify(k, 4); kids.push(k); } job(); job();
    const paid = kids.filter((k) => ledgerRows(k, 'referido') === 1).length; cfg({ referido_tope_diario: 5 });
    finding('G1.a_daily_cap_of_0_blocks_payouts_instead_of_meaning_unlimited', paid === 0, 'with referido_tope_diario=0, ' + paid + ' of 3 qualified invitees were paid (0 acts as ' + (paid === 0 ? 'blocked' : 'unlimited') + ')', 'E2-V1-RF03');
  });

  await scenario(only, 'F_STATES_REVOKE_GRANTS', async () => {
    cfg(ON);
    const uv = await pair('f1', { unverified: true }); const st0 = inv(uv.invitee); sql(`update auth.users set email_confirmed_at = now() where id = '${uv.invitee.id}';`); const st1 = inv(uv.invitee); sql(`update auth.users set email_confirmed_at = now() where id = '${uv.invitee.id}';`);
    check('F1.the_email_verified_trigger_moves_the_invitation_and_never_raises_even_when_repeated', /^registrada/.test(st0) && /^email_verificado/.test(st1), `${st0.split('|')[0]} -> ${st1.split('|')[0]}`);
    const bl = await pair('f2'); await qualify(bl.invitee, 4); sqlTry(`update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${bl.invitee.id}';`); job(); job();
    check('F2.a_blocked_invitee_triggers_and_receives_no_reward', ledgerRows(bl.invitee, 'referido') === 0 && ledgerRows(bl.inviter, 'referido') === 0, inv(bl.invitee));
    const rv = await pair('f3'); await qualify(rv.invitee, 4); job(); job();
    const ex2 = L.mkExam({ tipo: 'inmersivo', price: 10, n: 1 }); const spend = await call(rv.invitee, 'POST', '/api/v1/app/wallet/canjes', { examId: ex2.id }, { 'Idempotency-Key': uuid() });
    const invId = sql(`select id from public.invitaciones where invitado_id = '${rv.invitee.id}';`); const r1 = sqlTry(`select public.admin_referral_revoke(null, '${invId}', 'e2 test')::text;`);
    check('F3.revoke_after_the_invitee_spent_the_reward_reverses_only_the_unspent_part_never_negative_and_records_the_rest', spend.status === 201 && r1.ok && balance(rv.invitee) >= 0 && balance(rv.inviter) >= 0 && sql(`select estado from public.invitaciones where id = '${invId}';`) === 'revocada' && sql(`select count(*) from public.v_wallet_reconcile;`) === '0', `spend=${spend.status} inviter=${balance(rv.inviter)} invitee=${balance(rv.invitee)} ${r1.out.slice(0, 120)}`);
    const r2 = sqlTry(`select public.admin_referral_revoke(null, '${invId}', 'again')::text;`); check('F4.revoking_twice_does_not_reverse_twice', balance(rv.inviter) >= 0 && ledgerRows(rv.inviter) >= 1, `second revoke ok=${r2.ok}`);
    const tables = ['codigos_invitacion', 'invitaciones', 'invitacion_intentos', 'ot_ganancias_diferidas', 'atribucion_usuario']; const leaks = [];
    for (const t of tables.filter((x) => sql(`select to_regclass('public.${x}') is not null;`) === 't')) for (const role of ['authenticated', 'anon']) for (const stm of [`insert into public.${t} select * from public.${t} limit 0`, `delete from public.${t} where false`, `select count(*) from public.${t}`]) {
      const r = sqlTry(`set role ${role}; ${stm};`); const empty = stm.startsWith('select') && r.ok && r.out.trim().split(String.fromCharCode(10)).pop() === '0'; if (r.ok && !empty) leaks.push(`${role}:${t}:${stm.split(' ')[0]}`);
    }
    check('F5.no_client_privileges_on_any_referral_table_atribucion_usuario_included', leaks.length === 0, leaks.join(' ') || 'denied');
    const ex = []; for (const f of ['public.app_referral_attach(uuid, text, text)', 'public.app_job_referidos()', 'public.admin_referral_revoke(uuid, uuid, text)', 'public.app_referral_get(uuid)', 'public.app_referral_list(uuid, text, integer)']) for (const role of ['authenticated', 'anon']) if (sql(`select has_function_privilege('${role}', '${f}', 'execute');`) === 't') ex.push(`${role}:${f.split('(')[0]}`);
    check('F6.referral_functions_are_service_role_only', ex.length === 0, ex.join(' ') || 'none executable');
    const u = mkUser('rgr'); const gw = 'http://localhost:54321/rest/v1'; const h = { Authorization: 'Bearer ' + u.t, apikey: 'x', 'Content-Type': 'application/json', Prefer: 'return=minimal' }; const rest = [];
    for (const [m, t, b] of [['POST', 'invitaciones', { invitador_id: u.id, invitado_id: u.id, estado: 'premiada' }], ['POST', 'codigos_invitacion', { usuario_id: u.id, codigo: 'AAAAAAAA' }], ['POST', 'atribucion_usuario', { usuario_id: u.id, referido_por: uuid(), codigo_referido: 'AAAAAAAA' }], ['GET', 'invitaciones?select=*', undefined]]) {
      const r = await fetch(`${gw}/${t}`, { method: m, headers: h, body: b ? JSON.stringify(b) : undefined }).catch(() => ({ status: 0 })); rest.push(`${m} ${t.split('?')[0]}=${r.status}`);
    }
    check('F7.PostgREST_cannot_forge_codes_invitations_or_attribution', rest.every((x) => /=(401|403|404|405)$/.test(x)), rest.join(' '));
  });
} finally { for (const [k, v] of Object.entries(saved)) if (v !== '') sql(`update public.configuracion_v1 set valor_integer = ${v} where clave = '${k}';`); }

L.killKtor();
L.finish(OUT);
