#!/usr/bin/env node
// J15 (E5): admin gift of an exam, end to end over HTTP. Needs STACK_LOCK, the app Ktor (KTOR_URL, stack-config.mjs) and the CONTROL-profile Ktor (ADMIN_KTOR_URL, `run-ktor.mjs --admin`), and `up.mjs` applied (migration
// 20261022100700 + fixtures 98/99). The gift is ONE month (the exam's dias_acceso_compra, 30) set by the server; it stacks max(now, current end) + 30 days and never shortens; it is never unlimited;
// the specialty and capability gates apply; it never touches the wallet. Revoke exists in Control (POST /accesos/{id}/revocar): it closes every active manual grant, never a purchase.
//   node ops/local-exam/admin-grant-journey.mjs
import { ADMIN_KTOR_URL, KTOR_URL, LOCAL_DIR, composeArgs } from './stack-config.mjs';
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('admin-grant-journey');

requireStackLock('admin-grant-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = LOCAL_DIR;
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = KTOR_URL;
const ADMIN = ADMIN_KTOR_URL;
const Q = { Q1: '2a000000-0000-4000-8000-00000000002a', Q2Z: '2c000000-0000-4000-8000-00000000002c', Q3: '2d000000-0000-4000-8000-00000000002d', Q4: '2e000000-0000-4000-8000-00000000002e' };
const OT = { IMM30: 'e3000000-0000-4000-8000-000000000030', ONE: 'e3000000-0000-4000-8000-000000000001', ING: 'e3000000-0000-4000-8000-000000000005' };
const day = 86400000;

const sql = (text) => execFileSync('docker', [...composeArgs(), 'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (base, token, method, p, body, key) => {
  const headers = { Authorization: `Bearer ${token}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const r = await fetch(base + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text };
};
const app = (user, method, p, body, key) => http(APP, tokens[user], method, p, body, key);
const admin = (method, p, body) => http(ADMIN, tokens.ADMIN, method, p, body);
const gift = (userId, examId, accessType = 'regalo') => admin('POST', '/api/v1/admin/accesos', { id: crypto.randomUUID(), userName: 'x', examTitle: 'x', accessType, isActive: true, userId, examId });
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };
const days = (iso) => (Date.parse(iso) - Date.now()) / day;
const ledger = (u) => Number(sql(`select count(*) from public.transacciones_cartera where usuario_id = '${u}'`));
const detail = async (user, exam) => (await app(user, 'GET', '/api/v1/app/examenes/' + exam));

// repeatable: start without grants of these users on these exams
sql(`delete from public.contenido_desbloqueado_usuario where usuario_id in ('${Q.Q1}','${Q.Q2Z}','${Q.Q3}','${Q.Q4}') and examen_id in ('${OT.IMM30}','${OT.ONE}','${OT.ING}')`);

// 1. admin gift: one month, GRANTED, listed in ADQUIRIDOS, no wallet effect, audit
const l0 = ledger(Q.Q2Z);
const g1 = await gift(Q.Q2Z, OT.IMM30);
check('J15.gift_is_201_one_month_server_set', g1.status === 201 && Math.abs(days(g1.json?.validUntil) - 30) < 0.01 && g1.json?.isActive === true, `${g1.status} ${g1.text.slice(0, 140)}`);
const d1 = await detail('Q2Z', OT.IMM30);
check('J15.D2_02_shows_GRANTED_with_the_end', d1.status === 200 && d1.json?.examen?.accessState === 'GRANTED' && Math.abs(days(d1.json.examen.accesoValidoHasta) - 30) < 0.01, `${d1.status} ${d1.json?.examen?.accessState}`);
const mine = await app('Q2Z', 'GET', '/api/v1/app/me/examenes?vista=ADQUIRIDOS');
check('J15.home_ADQUIRIDOS_lists_the_gift', mine.status === 200 && (mine.json?.items ?? []).some((i) => i.examen?.id === OT.IMM30), `${mine.status}`);
check('J15.gift_does_not_touch_the_wallet', ledger(Q.Q2Z) === l0);
check('J15.gift_is_audited', Number(sql(`select count(*) from public.auditoria_log where accion = 'acceso_examen_concedido' and registro_id = '${g1.json?.id}'`)) === 1);
check('J15.gift_never_creates_an_unlimited_row', sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${Q.Q2Z}' and valido_hasta is null`) === '0');

// 2. duplicate click: same gift again = same row, not a second month
const g2 = await gift(Q.Q2Z, OT.IMM30);
check('J15.duplicate_gift_is_the_same_row_200_replay', g2.status === 200 && g2.json?.id === g1.json?.id && sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${Q.Q2Z}' and examen_id = '${OT.IMM30}'`) === '1', `${g2.status}`);

// 3. gift on top of a purchase: stacks from the purchase end (+30), never shortens
const buy = await app('Q1', 'POST', '/api/v1/app/wallet/canjes', { examId: OT.ONE }, crypto.randomUUID());
const g3 = await gift(Q.Q1, OT.ONE);
check('J15.gift_on_top_of_a_purchase_stacks_to_60_days', buy.status === 201 && g3.status === 201 && Math.abs(days(g3.json?.validUntil) - 60) < 0.05, `buy=${buy.status} days=${days(g3.json?.validUntil)}`);
const d3 = await detail('Q1', OT.ONE);
check('J15.D2_02_end_is_the_stacked_one', Math.abs(days(d3.json?.examen?.accesoValidoHasta) - 60) < 0.05, `${days(d3.json?.examen?.accesoValidoHasta)}`);
const g3b = await gift(Q.Q1, OT.ONE, 'admin');
check('J15.another_gift_reason_stacks_again_to_90_days', g3b.status === 201 && Math.abs(days(g3b.json?.validUntil) - 90) < 0.05, `${days(g3b.json?.validUntil)}`);

// 4. owner GO 2026-10-16: a purchase on an exam open through a GIFT (non-purchase grant) answers ALREADY_ACCESSIBLE (only a purchase extends a purchase); nothing is debited
const buy2 = await app('Q1', 'POST', '/api/v1/app/wallet/canjes', { examId: OT.ONE }, crypto.randomUUID());
check('J15.purchase_on_a_gifted_exam_is_ALREADY_ACCESSIBLE', buy2.status === 409 && buy2.json?.code === 'ALREADY_ACCESSIBLE', `${buy2.status} ${buy2.json?.code}`);

// 5. gates: another specialty, unknown exam, bad reason
const other = await gift(Q.Q1, OT.ING);
check('J15.gift_to_a_user_of_another_specialty_is_422_GIFT_NOT_USABLE', other.status === 422 && other.json?.code === 'GIFT_NOT_USABLE' && sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${Q.Q1}' and examen_id = '${OT.ING}'`) === '0', `${other.status} ${other.json?.code}`);
const unknown = await gift(Q.Q1, 'e3999999-0000-4000-8000-000000000999');
check('J15.gift_of_an_unknown_exam_is_404', unknown.status === 404, `${unknown.status} ${unknown.json?.code}`);
const asPurchase = await gift(Q.Q1, OT.ONE, 'compra');
check('J15.a_purchase_cannot_be_gifted_by_hand_422', asPurchase.status === 422 && asPurchase.json?.code === 'GRANT_REASON_NOT_ALLOWED', `${asPurchase.status}`);

// 6. expired gift: blocks
sql(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${Q.Q3}', '${OT.IMM30}', 'regalo', now() - interval '40 days', now() - interval '10 days')`);
const d6 = await detail('Q3', OT.IMM30);
check('J15.expired_gift_blocks_LOCKED', d6.status === 200 && d6.json?.examen?.accessState === 'LOCKED' && d6.json?.examen?.accesoValidoHasta == null, `${d6.json?.examen?.accessState}`);
const g6 = await gift(Q.Q3, OT.IMM30);
check('J15.a_new_gift_after_expiry_starts_now_for_30_days', g6.status === 201 && Math.abs(days(g6.json?.validUntil) - 30) < 0.01, `${days(g6.json?.validUntil)}`);

// 7. revoke (exists in Control): closes the gift, idempotent, never a purchase
const g7 = await gift(Q.Q4, OT.IMM30);
const rv = await admin('POST', `/api/v1/admin/accesos/${g7.json?.id}/revocar`);
const d7 = await detail('Q4', OT.IMM30);
check('J15.revoke_closes_the_gift_and_D2_02_is_LOCKED', rv.status === 200 && rv.json?.isActive === false && d7.json?.examen?.accessState === 'LOCKED', `${rv.status} ${d7.json?.examen?.accessState}`);
const rv2 = await admin('POST', `/api/v1/admin/accesos/${g7.json?.id}/revocar`);
check('J15.second_revoke_is_idempotent_200', rv2.status === 200 && rv2.json?.isActive === false, `${rv2.status}`);
const purchaseRow = sql(`select id from public.contenido_desbloqueado_usuario where usuario_id = '${Q.Q1}' and examen_id = '${OT.ONE}' and motivo = 'compra' limit 1`);
const rvBuy = await admin('POST', `/api/v1/admin/accesos/${purchaseRow}/revocar`);
check('J15.a_purchase_is_not_revoked_here_422', rvBuy.status === 422 && rvBuy.json?.code === 'GRANT_IS_PURCHASE', `${rvBuy.status} ${rvBuy.json?.code}`);

// auth
const noAuth = await fetch(ADMIN + '/api/v1/admin/accesos', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
const appUser = await http(ADMIN, tokens.Q1, 'POST', '/api/v1/admin/accesos', {});
check('J15.admin_route_needs_an_admin_token', noAuth.status === 401 && appUser.status === 403, `${noAuth.status}/${appUser.status}`);

const failed = results.filter((x) => !x).length;
console.log(`\nJ15: ${results.length - failed} PASS, ${failed} FAIL`);
process.exit(failed ? 1 : 0);
