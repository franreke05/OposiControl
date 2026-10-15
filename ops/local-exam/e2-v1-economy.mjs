#!/usr/bin/env node
// E2 V1 ECONOMY (LOCAL ONLY): adversarial attacks X1..X15 on the OposiTickets economy + payments foundation, through E2's own Ktor
// (:8090, restarted by this script so the in-memory limiters start empty). Writes ONLY to users/exams/orders this script creates
// (ids e2c.../e2v1-*), never to A, B, P or the shared fixtures. STACK_LOCK hard-fail (the caller must hold it while it runs).
// No MAIN, no secret printed: the sandbox secret is read from the local ktor.env and only used to sign; logs are scanned at the end.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-economy.mjs [--only=NAME,NAME]
import { execFileSync, spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('e2-v1-economy.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const L = process.env.E2_STACK_LOCAL ?? 'C:/Users/franr/AndroidStudioProjects/OposiControl-PhaseB-Server/ops/local-exam/.local';
const PORT = process.env.E2_PORT ?? '8091';
const KTOR = `http://127.0.0.1:${PORT}`;
const OUT = process.env.E2_OUT ?? path.join(process.cwd(), 'e2-v1-economy-result.json');
const LOGDIR = process.env.E2_LOGDIR ?? path.join(process.cwd(), 'e2-v1-economy-logs');
fs.mkdirSync(LOGDIR, { recursive: true });
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const wanted = (n) => only.length === 0 || only.includes(n);
const secrets = JSON.parse(fs.readFileSync(path.join(L, 'secrets.json'), 'utf8'));
const ktorEnv = Object.fromEntries(fs.readFileSync(path.join(L, 'ktor.env'), 'utf8').split(/\r?\n/).filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
const SANDBOX_SECRET = ktorEnv.PAYMENTS_SANDBOX_SECRET;
const GATEWAY = 'http://localhost:54321';

const sql = (text) => execFileSync('docker', ['exec', '-i', 'oposi-exam-local-db-1', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const sqlTry = (text) => { try { return { ok: true, out: sql(text) }; } catch (e) { return { ok: false, out: String(e.stderr ?? e.message) }; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64u = (b) => Buffer.from(b).toString('base64url');
const jwk = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
const token = (sub) => { const n = Math.floor(Date.now() / 1000); const h = b64u(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const p = b64u(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: `${GATEWAY}/auth/v1`, iat: n - 60, exp: n + 3000 }));
  return `${h}.${p}.${b64u(crypto.sign('sha256', Buffer.from(`${h}.${p}`), { key: jwk, dsaEncoding: 'ieee-p1363' }))}`; };

// ---------- Ktor control (own worktree build) ----------
function killKtor() {
  execFileSync('powershell', ['-NoProfile', '-Command', "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*OposiControl-ExamSecurity*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"], { stdio: 'ignore' });
}
let ktorSeq = 0;
async function startKtor() {
  const log = path.join(LOGDIR, `ktor-${++ktorSeq}.log`);
  const fd = fs.openSync(log, 'a');
  spawn(process.execPath, [path.join(here, 'e2-ktor.mjs'), PORT], { cwd: repo, env: { ...process.env, E2_STACK_LOCAL: L }, stdio: ['ignore', fd, fd], detached: true, windowsHide: true }).unref();
  for (let i = 0; i < 60; i++) { await sleep(1000); try { if ((await fetch(`${KTOR}/api/v1/public/especialidades`)).status === 200) return; } catch { /* starting */ } }
  throw new Error('Ktor did not start');
}
async function restartKtor() { killKtor(); await sleep(2000); await startKtor(); }

// ---------- harness ----------
const results = [];
function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
function finding(id, ok, detail, fid) { results.push({ id, status: ok ? 'PASS' : `FINDING ${fid}`, detail }); console.log(`${ok ? 'PASS' : 'FINDING ' + fid}  ${id}${detail ? '  ' + detail : ''}`); }
async function scenario(name, fn) { if (!wanted(name)) return; try { await fn(); } catch (e) { check(`${name}.exception`, false, String(e.message).slice(0, 300)); } }
const run = crypto.randomBytes(3).toString('hex');
const allUsers = [];
function mkUser(label) {
  const id = crypto.randomUUID();
  const clean = label.replace(/W/g, '').slice(0, 10);
  sql(`set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at) values ('${id}', 'e2v1-${run}-${clean}@e2.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now());
set session_replication_role = origin;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta) values ('${id}', 'E2V1 ${clean}', 'e2v1_${run}_${clean}', true, 'activa') on conflict (usuario_id) do nothing;`);
  allUsers.push(id);
  return { id, label, t: token(id) };
}
const grant = (u, n) => sql(`select transaction_id from public.admin_grant_tickets('${u.id}', ${n}, 'E2 seed', 'e2-seed-${crypto.randomUUID()}', 'ajuste');`);
const balance = (u) => Number(sql(`select coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '${u.id}'), 0);`));
const ledgerRows = (u, tipo) => Number(sql(`select count(*) from public.transacciones_cartera where usuario_id = '${u.id}'${tipo ? ` and tipo = '${tipo}'` : ''};`));
const uuid = () => crypto.randomUUID();
async function call(u, method, p, body, headers = {}, raw = false) {
  const h = { ...headers }; if (u) h.Authorization = `Bearer ${u.t ?? u}`; if (body !== undefined) h['Content-Type'] = 'application/json';
  const r = await fetch(KTOR + p, { method, headers: h, body: body === undefined ? undefined : (raw ? body : JSON.stringify(body)) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
}
const redeem = (u, examId, body = {}, key = uuid(), headers = {}) => call(u, 'POST', '/api/v1/app/wallet/canjes', { examId, ...body }, { 'Idempotency-Key': key, ...headers });
const createOrder = (u, product = 'LOCAL_PACK_10', key = uuid(), provider = 'sandbox') => call(u, 'POST', '/api/v1/app/payments/orders', { productCode: product, provider }, { 'Idempotency-Key': key });
const hmac = (data) => crypto.createHmac('sha256', SANDBOX_SECRET).update(data).digest('hex');
const orderRow = (id) => JSON.parse(sql(`select row_to_json(c)::text from (select id, usuario_id, estado, producto_codigo, cantidad_tickets, precio_centimos, moneda, identificador_transaccion from public.compras_usuario where id = '${id}') c;`));
function webhookBody(order, over = {}) {
  return { eventId: `evt-${uuid()}`, type: 'payment.succeeded', orderId: order.id, transactionId: `tx-${uuid()}`, amountCents: order.precio_centimos, currency: order.moneda, productCode: order.producto_codigo, ...over };
}
async function webhook(body, { ts = Math.floor(Date.now() / 1000), sig, provider = 'sandbox', rawOverride, headers = {} } = {}) {
  const raw = rawOverride ?? JSON.stringify(body);
  const signature = sig ?? hmac(`${ts}.${raw}`);
  return call(null, 'POST', `/api/v1/webhooks/payments/${provider}`, raw, { 'X-Sandbox-Timestamp': String(ts), 'X-Sandbox-Signature': signature, ...headers }, true);
}
const receiptFor = (userId, order, over = {}) => {
  const payload = { eventId: `evt-${uuid()}`, orderId: order.id, transactionId: `tx-${uuid()}`, amountCents: order.precio_centimos, currency: order.moneda, productCode: order.producto_codigo, ...over };
  const enc = b64u(JSON.stringify(payload)); return `${enc}.${hmac(`${userId}|${enc}`)}`;
};
const verify = (u, orderId, receipt, key) => call(u, 'POST', `/api/v1/app/payments/orders/${orderId}/verify`, { receipt }, key ? { 'Idempotency-Key': key } : {});
async function paidOrder(u, product = 'LOCAL_PACK_10') { // helper: order + credited by a signed webhook
  const o = await createOrder(u, product); const row = orderRow(o.json.order.id); const w = await webhook(webhookBody(row)); return { order: row, create: o, webhook: w };
}

// ---------- invariants INV1..INV6 (+ reconcile) ----------
function invariants(label, users, expectRedeemRows) {
  const ids = users.map((u) => `'${u.id}'`).join(',');
  const q = (s) => Number(sql(s));
  const problems = [];
  const rec = q(`select count(*) from public.app_wallet_reconcile(null) where usuario_id in (${ids});`); if (rec) problems.push(`reconcile=${rec}`);
  const inv1 = q(`select count(*) from public.carteras_usuario c where c.usuario_id in (${ids}) and c.saldo_tickets <> coalesce((select sum(t.cantidad) from public.transacciones_cartera t where t.usuario_id = c.usuario_id and t.seq is not null), 0);`); if (inv1) problems.push(`INV1=${inv1}`);
  const inv2 = q(`select count(*) from (select usuario_id, count(*) n, max(seq) mx, count(distinct seq) dn from public.transacciones_cartera where usuario_id in (${ids}) and seq is not null group by usuario_id) s where s.mx <> s.n or s.dn <> s.n;`); if (inv2) problems.push(`INV2seq=${inv2}`);
  const inv2b = q(`select count(*) from (select t.usuario_id, t.saldo_anterior, t.saldo_resultante, t.cantidad, coalesce(lag(t.saldo_resultante) over (partition by t.usuario_id order by t.seq), 0) e from public.transacciones_cartera t where t.usuario_id in (${ids}) and t.seq is not null) c where c.saldo_anterior <> c.e or c.saldo_resultante <> c.saldo_anterior + c.cantidad;`); if (inv2b) problems.push(`INV2chain=${inv2b}`);
  const inv3 = q(`select count(*) from public.carteras_usuario where usuario_id in (${ids}) and saldo_tickets < 0;`) + q(`select count(*) from public.transacciones_cartera where usuario_id in (${ids}) and saldo_resultante < 0;`); if (inv3) problems.push(`INV3=${inv3}`);
  const inv4 = q(`select count(*) from (select 1 from public.contenido_desbloqueado_usuario where motivo = 'compra' and usuario_id in (${ids}) group by usuario_id, examen_id having count(*) > 1) x;`); if (inv4) problems.push(`INV4=${inv4}`);
  const inv5 = q(`select count(*) from public.compras_usuario c where c.usuario_id in (${ids}) and c.cantidad_tickets is not null and (
      (c.estado in ('pagada','reembolsada') and not exists (select 1 from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'pack_tickets'))
   or (c.estado not in ('pagada','reembolsada') and exists (select 1 from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'pack_tickets')));`)
    + q(`select count(*) from (select 1 from public.transacciones_cartera t where t.usuario_id in (${ids}) and t.tipo = 'pack_tickets' group by t.compra_id having count(*) > 1) z;`); if (inv5) problems.push(`INV5=${inv5}`);
  const inv6 = q(`select count(*) from public.contenido_desbloqueado_usuario g where g.usuario_id in (${ids}) and g.motivo = 'compra' and not exists (select 1 from public.transacciones_cartera t where t.id = g.transaccion_id and t.usuario_id = g.usuario_id and t.tipo = 'compra_examen' and t.referencia_id = g.examen_id);`); if (inv6) problems.push(`INV6=${inv6}`);
  if (expectRedeemRows !== undefined) { const rows = q(`select count(*) from public.transacciones_cartera where usuario_id in (${ids}) and tipo = 'compra_examen';`); if (rows !== expectRedeemRows) problems.push(`redeemRows=${rows}!=${expectRedeemRows}`); }
  check(`INV.${label}`, problems.length === 0, problems.join(' ') || 'INV1-6 + reconcile = 0');
}

// ---------- fixtures: own exams ----------
const EX = { IMM30: 'e2c00000-0000-4000-8000-000000000030', CHEAP: 'e2c00000-0000-4000-8000-000000000010', FREEPRICED: 'e2c00000-0000-4000-8000-000000000011', NOPRICE: 'e2c00000-0000-4000-8000-000000000012',
  DRAFT: 'e2c00000-0000-4000-8000-000000000013', FUTURE: 'e2c00000-0000-4000-8000-000000000014', FLAGOFF: 'e2c00000-0000-4000-8000-000000000015' };
function seedExams() {
  sql(`set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra) values
 ('${EX.IMM30}', 'E2C-IMM30', 'e5000000-0000-4000-8000-000000000001', 'E2C inmersivo 30', 'inmersivo', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 30, 30),
 ('${EX.CHEAP}', 'E2C-CHEAP', 'e5000000-0000-4000-8000-000000000001', 'E2C barato', 'inmersivo', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 10, 30),
 ('${EX.FREEPRICED}', 'E2C-FREEP', 'e5000000-0000-4000-8000-000000000001', 'E2C gratis con precio', 'rafaga', 'gratuito', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 5, 30),
 ('${EX.NOPRICE}', 'E2C-NOPRICE', 'e5000000-0000-4000-8000-000000000001', 'E2C sin precio', 'inmersivo', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, null, 30),
 ('${EX.DRAFT}', 'E2C-DRAFT', 'e5000000-0000-4000-8000-000000000001', 'E2C borrador', 'inmersivo', 'premium', true, null, 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 10, 30),
 ('${EX.FUTURE}', 'E2C-FUTURE', 'e5000000-0000-4000-8000-000000000001', 'E2C futuro', 'inmersivo', 'premium', true, now() + interval '30 days', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 10, 30),
 ('${EX.FLAGOFF}', 'E2C-FLAGOFF', 'e5000000-0000-4000-8000-000000000001', 'E2C flag off', 'inmersivo', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), false, 10, 30)
on conflict (id) do update set publicado_en = excluded.publicado_en, precio_tickets = excluded.precio_tickets, comprable_con_tickets = excluded.comprable_con_tickets, dias_acceso_compra = excluded.dias_acceso_compra, activo = true;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('f2c00000-0000-4000-8000-000000000001', 'E2C-Q1', 'inmersiva', 'E2C pregunta', true) on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) select ('02c00000-0000-4000-8' || l || '00-000000000001')::uuid, 'f2c00000-0000-4000-8000-000000000001', chr(64 + l), 'op ' || l, l from generate_series(1, 2) l on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('f2c00000-0000-4000-8000-000000000001', '02c00000-0000-4000-8100-000000000001', 'x') on conflict (pregunta_id) do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
 select e, 'f2c00000-0000-4000-8000-000000000001', 1, 1 from unnest(array['${EX.IMM30}','${EX.CHEAP}']::uuid[]) e where not exists (select 1 from public.preguntas_examen pe where pe.examen_id = e);
set session_replication_role = origin;`);
}

// ================== SCENARIOS ==================
await restartKtor();
seedExams();
const price30 = 30;

const fresh = (label, bal = 100) => { const x = mkUser(label); grant(x, bal); return x; };
await scenario('A_PARAMS', async () => {
  const d = mkUser('a2'); const pool = [fresh('a'), fresh('a1'), fresh('a1b')];
  const forged = ['price', 'precio', 'precioPagado', 'pricePaid', 'amount', 'cantidad', 'saldo', 'balance', 'userId', 'usuarioId', 'currency', 'moneda', 'validUntil', 'validFrom', 'validoHasta', 'diasAcceso', 'tickets', 'es_premium', 'isPremium', 'accessState', 'transactionId'];
  const bad = []; for (let i = 0; i < forged.length; i++) { const f = forged[i]; const r = await redeem(pool[Math.floor(i / 8)], EX.IMM30, { [f]: f.includes('Id') ? d.id : 1 }); if (r.status !== 400) bad.push(f + ':' + r.status); }
  check('X13.forged_body_fields_rejected_400', bad.length === 0 && pool.every((p) => balance(p) === 100 && ledgerRows(p, 'compra_examen') === 0), bad.join(' ') || forged.length + ' fields -> 400, balances unchanged');
  const tu = fresh('atime'); const before = balance(tu);
  const q = await call(tu, 'POST', '/api/v1/app/wallet/canjes?price=1&userId=' + d.id + '&now=2099-01-01T00:00:00Z&validUntil=2099-01-01', { examId: EX.IMM30 }, { 'Idempotency-Key': uuid(), 'X-Usuario-Id': d.id, 'X-Client-Time': '2099-01-01T00:00:00Z', Date: 'Thu, 01 Jan 2099 00:00:00 GMT', 'X-Premium': 'true' });
  const until = q.json?.canje?.validUntil ? Date.parse(q.json.canje.validUntil) : 0; const days = (until - Date.now()) / 86400000;
  check('X13.client_time_headers_query_userid_ignored_or_400', q.status === 400 || (q.status === 201 && days > 29.9 && days < 30.1 && balance(tu) === before - price30 && balance(d) === 0 && ledgerRows(d) === 0), q.status + ' spanDays=' + days.toFixed(3));
  const cu = fresh('acases'); const cases = [['draft', EX.DRAFT, 404], ['future', EX.FUTURE, 404], ['nonexistent', uuid(), 404], ['free with price set', EX.FREEPRICED, 409], ['no price', EX.NOPRICE, 422], ['flag off', EX.FLAGOFF, 422]];
  const wrong = []; for (const [n, id, want] of cases) { const r = await redeem(cu, id); if (r.status !== want) wrong.push(n + ':' + r.status + '/' + r.json?.code); }
  check('X13.examId_manipulation_defined_codes_no_debit', wrong.length === 0 && balance(cu) === 100 && ledgerRows(cu, 'compra_examen') === 0, wrong.join(' ') || cases.length + ' cases');
  const mu = fresh('amal'); const notUuid = []; for (const id of ['not-a-uuid', '', "x' or 1=1", '../etc', 'e2c00000-0000-4000-8000-00000000003']) { const r = await redeem(mu, id); if (![400, 404].includes(r.status)) notUuid.push(id.slice(0, 8) + ':' + r.status); }
  check('X13.malformed_examId_rejected', notUuid.length === 0 && balance(mu) === 100, notUuid.join(' ') || '5 cases');
  const eu = fresh('aeps'); const eps = [['plus1', price30 + 1], ['zero', 0], ['neg', -1], ['2^31', 2147483648], ['2^53', 9007199254740992], ['float', 30.5], ['array', [30]], ['object', {}], ['bool', true]];
  const badEp = []; for (const [n, v] of eps) { const r = await redeem(eu, EX.IMM30, { expectedPrice: v }); if (![400, 409].includes(r.status)) badEp.push(n + ':' + r.status); }
  check('A4.expectedPrice_mismatch_or_garbage_never_charges', badEp.length === 0 && balance(eu) === 100 && ledgerRows(eu, 'compra_examen') === 0, badEp.join(' ') || eps.length + ' cases -> 400/409');
  const su = fresh('astr'); const strPrice = await redeem(su, EX.IMM30, { expectedPrice: '30' });
  finding('A4.expectedPrice_json_string_is_not_coerced_to_int', strPrice.status === 400, 'string "30" -> ' + strPrice.status + (strPrice.status === 201 ? ' (charged the correct DB price; the JSON decoder coerces strings to numbers)' : ''), 'E2-V1-E04');
  const gu = fresh('aguard'); const mism = await redeem(gu, EX.IMM30, { expectedPrice: 29 });
  check('A4.price_changed_409_with_server_price', mism.status === 409 && mism.json?.code === 'PRICE_CHANGED' && /30/.test(JSON.stringify(mism.json?.details ?? {})), mism.status + ' ' + mism.json?.code);
  const noGuard = await redeem(gu, EX.CHEAP);
  finding('A4.expectedPrice_is_optional_on_the_wire_info', noGuard.status === 201 && balance(gu) === 90, noGuard.status + ' balance=' + balance(gu) + ' (E1 design: optional guard; the server charges the DB price either way; the client must always send it)', 'E2-V1-I04');
  invariants('A_PARAMS', [...pool, d, tu, cu, mu, eu, gu, su]);
});

await scenario('B_IDEMPOTENCY', async () => {
  const u = mkUser('b'); grant(u, 100); const d = mkUser('b2'); grant(d, 100);
  const key = uuid(); const r1 = await redeem(u, EX.CHEAP, {}, key); const r2 = await redeem(u, EX.CHEAP, {}, key);
  const c1 = r1.json?.canje, c2 = r2.json?.canje;
  check('B3.replay_same_key_same_transaction_one_debit', r1.status === 201 && [200, 201].includes(r2.status) && c1?.transactionId === c2?.transactionId && c1?.validUntil === c2?.validUntil && balance(u) === 90 && ledgerRows(u, 'compra_examen') === 1, `${r1.status}/${r2.status} balance=${balance(u)}`);
  const other = await redeem(u, EX.IMM30, {}, key);
  check('B3.same_key_other_exam_422_reused', other.status === 422 && other.json?.code === 'IDEMPOTENCY_KEY_REUSED' && balance(u) === 90, `${other.status} ${other.json?.code}`);
  const sameKeyOtherUser = await redeem(d, EX.CHEAP, {}, key);
  check('B3.same_key_other_user_is_independent', sameKeyOtherUser.status === 201 && balance(d) === 90, `${sameKeyOtherUser.status}`);
  const withExp = await redeem(u, EX.CHEAP, { expectedPrice: 10 }, key);
  check('B3.same_key_other_body_(expectedPrice added)_422', withExp.status === 422 && ledgerRows(u, 'compra_examen') === 1, `${withExp.status}`);
  // 20 parallel same-key
  const u3 = mkUser('b3'); grant(u3, 100); const k3 = uuid(); const par = await Promise.all(Array.from({ length: 9 }, () => redeem(u3, EX.CHEAP, {}, k3)));
  const ok = par.filter((r) => [200, 201].includes(r.status)); const ids = new Set(ok.map((r) => r.json.canje.transactionId));
  check('B4.same_key_9_parallel_one_debit_(10_per_min_limit)', ids.size === 1 && ledgerRows(u3, 'compra_examen') === 1 && balance(u3) === 90 && par.every((r) => [200, 201, 409].includes(r.status)), `statuses=${[...new Set(par.map((r) => r.status))]} tx=${ids.size}`);
  // 12 parallel distinct keys, balance for exactly 3
  const u4 = mkUser('b4'); grant(u4, 35); const dist = await Promise.all(Array.from({ length: 10 }, () => redeem(u4, EX.CHEAP)));
  const succ = dist.filter((r) => r.status === 201).length; const insuff = dist.filter((r) => r.status === 409 && r.json?.code === 'INSUFFICIENT_BALANCE').length;
  const until = sql(`select extract(epoch from valido_hasta - now()) / 86400 from public.contenido_desbloqueado_usuario where usuario_id = '${u4.id}' and examen_id = '${EX.CHEAP}' and motivo = 'compra';`);
  check('B5.parallel_redeems_exactly_k_succeed_balance_conserved', succ === 3 && insuff === 7 && balance(u4) === 5 && ledgerRows(u4, 'compra_examen') === 3, `succ=${succ} insufficient=${insuff} balance=${balance(u4)}`);
  check('X10.extension_race_each_success_extends_from_previous_end', Math.abs(Number(until) - 90) < 0.05, `validUntil in ${Number(until).toFixed(3)} days (expected 90 = 3 x 30)`);
  invariants('B_IDEMPOTENCY', [u, d, u3, u4]);
});

await scenario('M_EXTENSION_RACE', async () => {
  const users = []; const rows = [];
  for (let trial = 0; trial < 6; trial++) {
    const u = mkUser('m' + trial); users.push(u); grant(u, 35);
    const rs = await Promise.all(Array.from({ length: 10 }, () => redeem(u, EX.CHEAP)));
    const ok = rs.filter((r) => r.status === 201).length;
    const days = Number(sql("select extract(epoch from valido_hasta - now()) / 86400 from public.contenido_desbloqueado_usuario where usuario_id = '" + u.id + "' and examen_id = '" + EX.CHEAP + "' and motivo = 'compra';"));
    rows.push({ ok, debit: 35 - balance(u), days: Math.round(days * 1000) / 1000 });
  }
  const bad = rows.filter((r) => Math.abs(r.days - 30 * r.ok) > 0.05);
  finding('X10.parallel_redeems_each_debit_extends_30_days_from_the_previous_end_never_shortens', bad.length === 0,
    bad.length ? bad.length + '/6 trials ended SHORTER than 30 x purchases: ' + JSON.stringify(rows) : '6 trials exact: ' + JSON.stringify(rows), 'E2-V1-E03');
  // Deterministic: a transaction that STARTED before another purchase committed (stale now()) must still extend from that purchase's end.
  const dets = [];
  for (const gapMs of [1500, 4000]) {
    const u = mkUser('mdet' + gapMs); users.push(u); grant(u, 35);
    const key1 = uuid();
    const ps = spawn('docker', ['exec', '-i', 'oposi-exam-local-db-1', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1'], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    let out = ''; ps.stdout.on('data', (d) => { out += d; }); ps.stderr.on('data', (d) => { out += d; });
    const exited = new Promise((r) => ps.on('exit', r));
    ps.stdin.write('begin;\nselect now();\n');
    await sleep(gapMs);
    const r2 = await redeem(u, EX.CHEAP);
    ps.stdin.write("select outcome from public.app_redeem_exam_access('" + u.id + "', '" + EX.CHEAP + "', '" + key1 + "', null);\ncommit;\n");
    ps.stdin.end(); await exited;
    const days = Number(sql("select extract(epoch from valido_hasta - now()) / 86400 from public.contenido_desbloqueado_usuario where usuario_id = '" + u.id + "' and examen_id = '" + EX.CHEAP + "' and motivo = 'compra';"));
    dets.push({ gapMs, apiStatus: r2.status, sqlOutcome: out.includes('CREATED') ? 'CREATED' : out.trim().slice(0, 60), debits: ledgerRows(u, 'compra_examen'), days: Math.round(days * 100) / 100 });
  }
  const badDet = dets.filter((r) => r.debits !== 2 || r.days < 59.9);
  finding('X10.stale_transaction_clock_never_shortens_or_skips_an_extension', badDet.length === 0,
    badDet.length ? 'two paid purchases but the entitlement ends in ' + badDet.map((r) => r.days).join('/') + ' days instead of 60: ' + JSON.stringify(dets) : JSON.stringify(dets), 'E2-V1-E03');
  invariants('M_EXTENSION_RACE', users);
});

await scenario('C_ENTITLEMENT', async () => {
  const u = mkUser('c'); grant(u, 200);
  const r1 = await redeem(u, EX.IMM30);
  const g = (x) => sql(`select valido_desde::text || '|' || valido_hasta::text from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${x}' and motivo = 'compra';`);
  const d1 = g(EX.IMM30); const r2 = await redeem(u, EX.IMM30); const d2 = g(EX.IMM30);
  const gap = (Date.parse(r2.json.canje.validUntil) - Date.parse(r1.json.canje.validUntil)) / 86400000;
  check('C1.re_purchase_extends_30_days_from_current_end_never_shortens', r1.status === 201 && r2.status === 201 && Math.abs(gap - 30) < 0.01 && d1.split('|')[0] === d2.split('|')[0], `gap=${gap.toFixed(3)}d validFrom kept=${d1.split('|')[0] === d2.split('|')[0]}`);
  const rows = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${EX.IMM30}' and motivo = 'compra';`));
  check('C2.one_purchased_row_per_user_exam', rows === 1 && Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and precio_pagado is null and motivo = 'compra';`)) === 0, `rows=${rows}`);
  const d02 = await call(u, 'GET', `/api/v1/app/examenes/${EX.IMM30}`);
  check('C.accessState_granted_and_validUntil_from_server', d02.json?.examen?.accessState === 'GRANTED' && !!d02.json?.examen?.accesoValidoHasta, `${d02.json?.examen?.accessState}`);
  // expire it, then re-buy: starts NOW, validFrom reset
  sql(`set session_replication_role = replica; update public.contenido_desbloqueado_usuario set valido_desde = now() - interval '40 days', valido_hasta = now() - interval '10 days' where usuario_id = '${u.id}' and examen_id = '${EX.IMM30}' and motivo = 'compra'; set session_replication_role = origin;`);
  const expired = await call(u, 'GET', `/api/v1/app/examenes/${EX.IMM30}`);
  const start = await call(u, 'POST', `/api/v1/app/examenes/${EX.IMM30}/intentos`, {}, { 'Idempotency-Key': uuid() });
  check('C.expired_entitlement_locks_exam_and_blocks_start', expired.json?.examen?.accessState === 'LOCKED' && start.status === 403, `${expired.json?.examen?.accessState} start=${start.status} ${start.json?.code}`);
  const rb = await redeem(u, EX.IMM30); const span = (Date.parse(rb.json.canje.validUntil) - Date.now()) / 86400000; const from = Date.parse(rb.json.canje.validFrom);
  check('C6.re_buy_after_expiry_starts_now_not_at_old_date', rb.status === 201 && Math.abs(span - 30) < 0.01 && Math.abs(from - Date.now()) < 60_000, `span=${span.toFixed(3)}d validFrom age=${Math.round((Date.now() - from) / 1000)}s`);
  const ok = await call(u, 'POST', `/api/v1/app/examenes/${EX.IMM30}/intentos`, {}, { 'Idempotency-Key': uuid() });
  check('C.granted_entitlement_opens_the_exam', ok.status === 201, `${ok.status}`);
  // already accessible: premium user and unlimited grant
  const p = mkUser('cprem'); grant(p, 100);
  sql(`insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, fin_en) values ('${p.id}', true, 'activa', now() + interval '10 days') on conflict (usuario_id) do update set es_premium = true, estado_suscripcion = 'activa', fin_en = now() + interval '10 days';`);
  const rp = await redeem(p, EX.CHEAP);
  check('B9.premium_user_cannot_pay_for_accessible_exam', rp.status === 409 && rp.json?.code === 'ALREADY_ACCESSIBLE' && balance(p) === 100, `${rp.status} ${rp.json?.code}`);
  const ul = mkUser('cunl'); grant(ul, 100);
  sql(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${ul.id}', '${EX.CHEAP}', 'regalo', now() - interval '1 day', null);`);
  const ru = await redeem(ul, EX.CHEAP);
  check('B9.unlimited_grant_cannot_be_bought_again', ru.status === 409 && ru.json?.code === 'ALREADY_ACCESSIBLE' && balance(ul) === 100, `${ru.status} ${ru.json?.code}`);
  const lim = mkUser('clim'); grant(lim, 100);
  sql(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${lim.id}', '${EX.CHEAP}', 'regalo', now() - interval '1 day', now() + interval '100 days');`);
  const rl = await redeem(lim, EX.CHEAP); const lspan = (Date.parse(rl.json?.canje?.validUntil) - Date.now()) / 86400000;
  check('C7.gift_end_is_the_base_of_the_extension_and_gift_row_untouched', rl.status === 201 && Math.abs(lspan - 130) < 0.05 && Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${lim.id}' and motivo = 'regalo';`)) === 1, `span=${lspan.toFixed(2)}d`);
  // cross-user
  const x = mkUser('cx'); const xd = await call(x, 'GET', `/api/v1/app/examenes/${EX.IMM30}`); const xs = await call(x, 'POST', `/api/v1/app/examenes/${EX.IMM30}/intentos`, {}, { 'Idempotency-Key': uuid() });
  const xcol = await call(x, 'GET', '/api/v1/app/me/examenes?vista=ADQUIRIDOS&limit=50');
  check('X9.cross_user_entitlement_not_visible_or_usable', xd.json?.examen?.accessState === 'LOCKED' && xs.status === 403 && !xcol.text.includes(EX.IMM30), `${xd.json?.examen?.accessState} start=${xs.status}`);
  const xr = await redeem(x, EX.IMM30); check('X9.other_user_without_balance_cannot_redeem', xr.status === 409 && xr.json?.code === 'INSUFFICIENT_BALANCE' && /"balance":0/.test(JSON.stringify(xr.json.details)), `${xr.status} ${xr.json?.code}`);
  invariants('C_ENTITLEMENT', [u, p, ul, lim, x]);
});

await scenario('D_REDEEM_VS_TOPUP', async () => {
  const users = []; let anomalies = 0, trials = 8;
  for (let i = 0; i < trials; i++) {
    const u = mkUser('d' + i); users.push(u); grant(u, 10);
    const o = await createOrder(u); const row = orderRow(o.json.order.id);
    const wb = webhookBody(row);
    const calls = [...Array.from({ length: 4 }, () => redeem(u, EX.CHEAP)), webhook(wb)];
    const rs = await Promise.all(calls); const okR = rs.slice(0, 4).filter((r) => r.status === 201).length; const w = rs[4];
    const expected = 20 - 10 * okR;
    if (w.json?.result !== 'CREDITED' || balance(u) !== expected || okR < 1 || okR > 2 || balance(u) < 0) anomalies++;
  }
  check('B6.redeem_vs_topup_race_conservation', anomalies === 0, `${trials} trials, anomalies=${anomalies}`);
  invariants('D_REDEEM_VS_TOPUP', users);
});

await scenario('E_PAYMENTS', async () => {
  const u = mkUser('e'); const d = mkUser('e2'); const fu = mkUser('efield'); const pu = mkUser('eprov'); const ku = mkUser('ekey');
  const o = await createOrder(u); const row = orderRow(o.json.order.id);
  check('D2.order_frozen_from_catalogue', o.status === 201 && row.cantidad_tickets === 10 && row.precio_centimos === 99 && row.moneda === 'EUR' && o.json.order.status === 'PENDING', `${o.status} ${row.cantidad_tickets} OT ${row.precio_centimos} ${row.moneda}`);
  const bodyBad = []; for (const f of ['tickets', 'amountCents', 'price', 'currency', 'cantidad', 'userId']) { const r = await call(fu, 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_10', provider: 'sandbox', [f]: 1 }, { 'Idempotency-Key': uuid() }); if (r.status !== 400) bodyBad.push(`${f}:${r.status}`); }
  check('D2.order_request_carries_no_amounts_or_users', bodyBad.length === 0, bodyBad.join(' ') || '6 forged fields -> 400');
  const prov = await call(pu, 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_10', provider: 'google_play' }, { 'Idempotency-Key': uuid() });
  const prod = await call(pu, 'POST', '/api/v1/app/payments/orders', { productCode: 'NO_SUCH', provider: 'sandbox' }, { 'Idempotency-Key': uuid() });
  check('D.unknown_provider_or_product_refused', prov.status === 422 && prod.status === 422, `${prov.status}/${prod.status}`);
  const kk = uuid(); const o1 = await createOrder(ku, 'LOCAL_PACK_10', kk); const o2 = await createOrder(ku, 'LOCAL_PACK_10', kk); const o3 = await createOrder(ku, 'LOCAL_PACK_50', kk);
  check('D10.order_create_idempotent_and_key_reuse_422', o1.json.order.id === o2.json.order.id && o3.status === 422 && o3.json?.code === 'IDEMPOTENCY_KEY_REUSED', `${o1.status}/${o2.status}/${o3.status}`);
  const xo = await call(d, 'GET', `/api/v1/app/payments/orders/${row.id}`); const xv = await verify(d, row.id, receiptFor(d.id, row));
  check('X9.other_users_order_is_404_for_read_and_verify', xo.status === 404 && [404, 422].includes(xv.status) && orderRow(row.id).estado === 'pendiente', `${xo.status}/${xv.status}`);
  // forged signature family: zero writes
  const writes = () => sql(`select (select count(*) from public.eventos_pago) || '|' || (select count(*) from public.transacciones_cartera);`);
  const w0 = writes(); const body = webhookBody(row); const raw = JSON.stringify(body); const ts = Math.floor(Date.now() / 1000);
  const attempts = [
    ['no headers', () => call(null, 'POST', '/api/v1/webhooks/payments/sandbox', raw, {}, true)], ['bad hex', () => webhook(body, { sig: 'f'.repeat(64) })], ['empty sig', () => webhook(body, { sig: '' })],
    ['stale -6min', () => webhook(body, { ts: ts - 361 })], ['future +6min', () => webhook(body, { ts: ts + 361 })], ['sig over other body', () => webhook(body, { sig: hmac(`${ts}.${raw}x`) })],
    ['sig for other timestamp', () => webhook(body, { ts, sig: hmac(`${ts - 5}.${raw}`) })], ['non-numeric ts', () => call(null, 'POST', '/api/v1/webhooks/payments/sandbox', raw, { 'X-Sandbox-Timestamp': 'abc', 'X-Sandbox-Signature': hmac(`abc.${raw}`) }, true)],
    ['secret with wrong key', () => webhook(body, { sig: crypto.createHmac('sha256', 'x'.repeat(32)).update(`${ts}.${raw}`).digest('hex') })], ['signature with body whitespace changed', () => webhook(body, { rawOverride: raw + ' ', sig: hmac(`${ts}.${raw}`) })],
  ];
  const badSig = []; for (const [n, fn] of attempts) { const r = await fn(); if (r.status !== 401) badSig.push(`${n}:${r.status}`); }
  check('X11.forged_or_stale_signature_401_zero_writes', badSig.length === 0 && writes() === w0 && orderRow(row.id).estado === 'pendiente', badSig.join(' ') || `${attempts.length} attempts -> 401, writes unchanged`);
  const unk = await webhook(body, { provider: 'stripe' }); const unk2 = await webhook(webhookBody({ ...row, id: uuid() }), { provider: 'SANDBOX ' });
  check('X11.unknown_provider_404_before_any_work', unk.status === 404 && writes() === w0, `${unk.status} / padded provider id (trim+lowercase normalised, unknown order)=${unk2.status}`);
  const big = await call(null, 'POST', '/api/v1/webhooks/payments/sandbox', 'x'.repeat(70 * 1024), { 'X-Sandbox-Timestamp': String(ts), 'X-Sandbox-Signature': 'a'.repeat(64) }, true);
  check('X11.body_over_64KiB_413_before_hmac', big.status === 413 && writes() === w0, `${big.status}`);
  // forged receipt family
  const rcpts = [['random', 'abc.def'], ['empty', ''], ['no dot', b64u('{}')], ['other user', receiptFor(d.id, row)], ['tampered payload', (() => { const r = receiptFor(u.id, row); const [p, s] = r.split('.'); const j = JSON.parse(Buffer.from(p, 'base64url')); j.amountCents = 1; return `${b64u(JSON.stringify(j))}.${s}`; })()],
    ['other order', receiptFor(u.id, { ...row, id: uuid() })], ['sig uppercase junk', receiptFor(u.id, row).toUpperCase()], ['huge', 'a'.repeat(100000)]];
  const badR = []; for (const [n, rc] of rcpts) { const r = await verify(u, row.id, rc); if (![400, 413, 422].includes(r.status)) badR.push(`${n}:${r.status}`); }
  check('X1.forged_receipts_rejected_nothing_credited', badR.length === 0 && orderRow(row.id).estado === 'pendiente' && balance(u) === 0, badR.join(' ') || `${rcpts.length} receipts rejected`);
  // genuine webhook, replay, parallel duplicates
  const good = await webhook(body); const replay = await webhook(body);
  check('X2.webhook_credits_once_and_replay_is_noop', good.json?.result === 'CREDITED' && replay.json?.result === 'REPLAY' && balance(u) === 10 && ledgerRows(u, 'pack_tickets') === 1, `${good.json?.result}/${replay.json?.result} balance=${balance(u)}`);
  const u2 = mkUser('e3'); const o2b = await createOrder(u2); const r2 = orderRow(o2b.json.order.id); const b2 = webhookBody(r2); const raw2 = JSON.stringify(b2);
  const dup = await Promise.all(Array.from({ length: 20 }, () => webhook(b2, { rawOverride: raw2 }))); const res = dup.map((r) => r.json?.result);
  check('X3.20_parallel_duplicate_callbacks_one_credit', res.filter((x) => x === 'CREDITED').length === 1 && res.every((x) => ['CREDITED', 'REPLAY'].includes(x)) && balance(u2) === 10 && ledgerRows(u2, 'pack_tickets') === 1, `${[...new Set(res)]} balance=${balance(u2)}`);
  const second = await webhook(webhookBody(r2)); // another event for the paid order
  check('X7.second_event_for_a_paid_order_no_second_credit', second.json?.result === 'REPLAY' && balance(u2) === 10, `${second.json?.result}`);
  // verify path after webhook, and credit by verify then webhook
  const u3 = mkUser('e4'); const o3b = await createOrder(u3); const r3 = orderRow(o3b.json.order.id);
  const v1 = await verify(u3, r3.id, receiptFor(u3.id, r3)); const v2 = await verify(u3, r3.id, receiptFor(u3.id, r3)); const w3 = await webhook(webhookBody(r3));
  check('X7.verify_then_verify_then_webhook_single_credit', v1.status === 200 && balance(u3) === 10 && ledgerRows(u3, 'pack_tickets') === 1 && w3.json?.result === 'REPLAY', `${v1.status}/${v2.status}/${w3.json?.result}`);
  // same provider transaction on another order
  const u4 = mkUser('e5'); const oa = orderRow((await createOrder(u4)).json.order.id); const ob = orderRow((await createOrder(u4)).json.order.id);
  const tx = `tx-${uuid()}`; const wa = await webhook(webhookBody(oa, { transactionId: tx })); const wb = await webhook(webhookBody(ob, { transactionId: tx }));
  check('X7.same_provider_transaction_on_a_second_order_goes_to_review', wa.json?.result === 'CREDITED' && wb.json?.result === 'EN_REVISION' && balance(u4) === 10 && orderRow(ob.id).estado === 'en_revision', `${wa.json?.result}/${wb.json?.result} balance=${balance(u4)}`);
  // wrong user / package / currency / amount
  const u5 = mkUser('e6'); const wrong = [];
  const variants = [['package', { productCode: 'LOCAL_PACK_50' }], ['currency USD', { currency: 'USD' }], ['amount +1', { amountCents: 100 }], ['amount -1', { amountCents: 98 }], ['amount 0', { amountCents: 0 }], ['amount negative', { amountCents: -99 }], ['amount int max', { amountCents: 2147483647 }], ['empty product', { productCode: '' }]];
  for (const [n, over] of variants) { const orw = orderRow((await createOrder(u5)).json.order.id); const r = await webhook(webhookBody(orw, over)); const st = orderRow(orw.id).estado; if (!(r.json?.result === 'EN_REVISION' && st === 'en_revision') && !(r.status >= 400 && st === 'pendiente')) wrong.push(`${n}:${r.status}/${r.json?.result}/${st}`); }
  check('X5X6.mismatching_amount_currency_package_credit_nothing', wrong.length === 0 && balance(u5) === 0 && ledgerRows(u5, 'pack_tickets') === 0, wrong.join(' ') || `${variants.length} variants -> review/refused, balance 0`);
  const unknownOrder = await webhook(webhookBody({ ...row, id: uuid() })); const garbageOrder = await webhook(webhookBody(row, { orderId: 'not-a-uuid' }));
  check('X4.unknown_order_never_creates_or_credits', [404, 400, 422].includes(unknownOrder.status) && [400, 422].includes(garbageOrder.status), `${unknownOrder.status}/${garbageOrder.status}`);
  const credited = Number(sql(`select coalesce(sum(cantidad),0) from public.transacciones_cartera where usuario_id = '${d.id}';`));
  check('X4.credit_goes_to_the_order_owner_only', credited === 0 && balance(d) === 0, `other user's ledger sum=${credited}`);
  invariants('E_PAYMENTS', [u, u2, u3, u4, u5, d, fu, pu, ku]);
});

await scenario('F_LATE_AND_STATES', async () => {
  const u = mkUser('f'); const mk = async () => orderRow((await createOrder(u)).json.order.id);
  const setSql = (id, set) => sql(`set session_replication_role = replica; update public.compras_usuario set ${set} where id = '${id}'; set session_replication_role = origin;`);
  const late = await mk(); setSql(late.id, "expira_en = now() - interval '2 minutes'");
  const read = await call(u, 'GET', `/api/v1/app/payments/orders/${late.id}`); const lw = await webhook(webhookBody(late));
  check('D7.expired_order_reads_EXPIRED_and_authentic_late_payment_is_credited', read.json?.order?.status === 'EXPIRED' && lw.json?.result === 'CREDITED_LATE' && balance(u) === 10, `${read.json?.order?.status} ${lw.json?.result} balance=${balance(u)}`);
  const lateMismatch = await mk(); setSql(lateMismatch.id, "expira_en = now() - interval '2 minutes'"); const lm = await webhook(webhookBody(lateMismatch, { amountCents: 1 }));
  check('D7.late_payment_with_mismatching_amount_is_not_credited', lm.json?.result === 'EN_REVISION' && balance(u) === 10, `${lm.json?.result}`);
  const states = {};
  for (const st of ['cancelada', 'fallida']) { const o = await mk(); setSql(o.id, `estado = '${st}'`); const r = await webhook(webhookBody(o)); states[st] = `${r.json?.result}/${orderRow(o.id).estado}`; }
  check('D8.paid_event_for_cancelled_or_failed_order_goes_to_review_no_credit', states.cancelada === 'EN_REVISION/en_revision' && states.fallida === 'EN_REVISION/en_revision' && balance(u) === 10, JSON.stringify(states));
  const frozen = await mk(); const tamper = [];
  for (const [n, s] of [['quantity', 'cantidad_tickets = 9999'], ['price', 'precio_centimos = 1'], ['owner', `usuario_id = '${uuid()}'`], ['currency', "moneda = 'USD'"], ['product', "producto_codigo = 'LOCAL_PACK_50'"], ['expiry', "expira_en = now() + interval '10 years'"]]) {
    const r = sqlTry(`update public.compras_usuario set ${s} where id = '${frozen.id}';`); if (r.ok) tamper.push(n); }
  check('D2.order_amount_quantity_owner_expiry_are_frozen_by_trigger', tamper.length === 0, tamper.join(',') || '6 mutations rejected (ORDER_FROZEN)');
  const back = sqlTry(`update public.compras_usuario set estado = 'pendiente' where id = '${late.id}';`); const refundedBack = sqlTry(`update public.compras_usuario set estado = 'pendiente' where id = '${frozen.id}' and false;`);
  check('D8.paid_order_cannot_go_back_to_pending', !back.ok && /ORDER_STATE_TRANSITION_FORBIDDEN/.test(back.out), back.out.split('\n')[0].slice(0, 100));
  const pending = await mk(); const cap = []; for (let i = 0; i < 8; i++) { const r = await createOrder(u); cap.push(r.status); }
  const open = Number(sql(`select count(*) from public.compras_usuario where usuario_id = '${u.id}' and estado = 'pendiente' and cantidad_tickets is not null;`));
  finding('C9.open_pending_orders_per_user_are_capped', open <= 5, `open pending orders=${open}, statuses=${[...new Set(cap)]} (only the 10/min rate limit applies)`, 'E2-V1-E02');
  invariants('F_LATE_AND_STATES', [u]);
});

await scenario('G_REFUNDS', async () => {
  const users = [];
  const refund = (order, over = {}) => webhook(webhookBody(order, { type: 'payment.refunded', ...over }));
  const buy = async (u, product = 'LOCAL_PACK_10') => { const o = await createOrder(u, product); const row = orderRow(o.json.order.id); const w = await webhook(webhookBody(row)); return { row, w }; };
  const caseRow = (id) => sql(`select ot_revertidos || '/' || ot_gastados from public.refund_case where compra_id = '${id}';`);
  // A unspent
  const A = mkUser('ga'); users.push(A); const a = await buy(A); const ra = await refund(a.row);
  check('X8.caseA_unspent_fully_reversed_no_case', ra.json?.result === 'REFUNDED' && balance(A) === 0 && caseRow(a.row.id) === '' && orderRow(a.row.id).estado === 'reembolsada', `${ra.json?.result} balance=${balance(A)}`);
  const ra2 = await refund(a.row); const ra3 = await refund(a.row, { eventId: 'new-' + uuid() });
  check('X8.second_refund_event_is_a_noop', ['REPLAY', 'NOOP'].includes(ra2.json?.result) && ['REPLAY', 'NOOP'].includes(ra3.json?.result) && balance(A) === 0 && ledgerRows(A, 'devolucion') === 1, `${ra2.json?.result}/${ra3.json?.result}`);
  // B partly spent (owner example scaled: 10 bought, 8 spent (exam price 10 -> use balance trick)
  const B = mkUser('gb'); users.push(B); const b = await buy(B); sql(`select 1;`);
  // spend 10 on CHEAP then top-up no: for "partly spent" we need spend < 10: use a 1-OT exam
  sql(`set session_replication_role = replica; insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra) values ('e2c00000-0000-4000-8000-000000000008', 'E2C-8', 'e5000000-0000-4000-8000-000000000001', 'E2C 8', 'inmersivo', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 8, 30) on conflict (id) do update set precio_tickets = 8, comprable_con_tickets = true, activo = true; set session_replication_role = origin;`);
  const sp = await redeem(B, 'e2c00000-0000-4000-8000-000000000008'); const grantBefore = sql(`select valido_hasta::text from public.contenido_desbloqueado_usuario where usuario_id = '${B.id}' and examen_id = 'e2c00000-0000-4000-8000-000000000008';`);
  const rb = await refund(b.row);
  check('X8.caseB_D_partly_spent_reverses_only_unspent_opens_case_keeps_access', sp.status === 201 && rb.json?.result === 'REFUNDED_CASE_OPEN' && balance(B) === 0 && caseRow(b.row.id) === '2/8' && sql(`select valido_hasta::text from public.contenido_desbloqueado_usuario where usuario_id = '${B.id}' and examen_id = 'e2c00000-0000-4000-8000-000000000008';`) === grantBefore, `${rb.json?.result} case=${caseRow(b.row.id)} balance=${balance(B)}`);
  // C fully spent
  const C = mkUser('gc'); users.push(C); const c = await buy(C); await redeem(C, EX.CHEAP); const rc = await refund(c.row);
  check('X8.caseC_fully_spent_no_reversal_case_only_no_negative_balance', rc.json?.result === 'REFUNDED_CASE_OPEN' && balance(C) === 0 && caseRow(c.row.id) === '0/10' && ledgerRows(C, 'devolucion') === 0, `${rc.json?.result} case=${caseRow(c.row.id)}`);
  // E expired entitlement
  const E = mkUser('ge'); users.push(E); const e = await buy(E); await redeem(E, EX.CHEAP);
  sql(`set session_replication_role = replica; update public.contenido_desbloqueado_usuario set valido_desde = now() - interval '40 days', valido_hasta = now() - interval '10 days' where usuario_id = '${E.id}' and motivo = 'compra'; set session_replication_role = origin;`);
  const re = await refund(e.row);
  check('X8.caseE_expired_entitlement_case_only', re.json?.result === 'REFUNDED_CASE_OPEN' && caseRow(e.row.id) === '0/10', `${re.json?.result}`);
  // F multiple mixed purchases: c1=10, c2=10, spend 12 -> c1 fully spent, 2 from c2
  const F = mkUser('gf'); users.push(F); const f1 = await buy(F); const f2 = await buy(F);
  sql(`set session_replication_role = replica; insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra) values ('e2c00000-0000-4000-8000-000000000012', 'E2C-12', 'e5000000-0000-4000-8000-000000000001', 'E2C 12', 'inmersivo', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1), true, 12, 30) on conflict (id) do update set precio_tickets = 12, comprable_con_tickets = true, activo = true; set session_replication_role = origin;`);
  await redeem(F, 'e2c00000-0000-4000-8000-000000000012'); const rf1 = await refund(f1.row); const rf2 = await refund(f2.row);
  check('X8.caseF_FIFO_traces_each_order_separately', rf1.json?.result === 'REFUNDED_CASE_OPEN' && caseRow(f1.row.id) === '0/10' && rf2.json?.result === 'REFUNDED_CASE_OPEN' && caseRow(f2.row.id) === '8/2' && balance(F) === 0, `o1=${caseRow(f1.row.id)} o2=${caseRow(f2.row.id)} balance=${balance(F)}`);
  // refund of other statuses / unknown order
  const G = mkUser('gg'); users.push(G); const pend = orderRow((await createOrder(G)).json.order.id); const rp = await refund(pend); const rn = await refund({ ...pend, id: uuid() });
  check('X8.refund_for_unpaid_or_unknown_order_is_noop_or_refused', ['NOOP'].includes(rp.json?.result) && orderRow(pend.id).estado === 'pendiente' && [404, 400, 422].includes(rn.status), `${rp.json?.result}/${rn.status}`);
  // refund vs redeem race: credit 10, 5 parallel redeems of price 10 and 1 refund
  const H = mkUser('gh'); users.push(H); const h = await buy(H); const rr = await Promise.all([...Array.from({ length: 5 }, () => redeem(H, EX.CHEAP)), refund(h.row)]);
  const sold = rr.slice(0, 5).filter((r) => r.status === 201).length; const rh = rr[5].json?.result;
  check('X8.refund_vs_redeem_race_totals_consistent', (sold === 1 && rh === 'REFUNDED_CASE_OPEN' && balance(H) === 0) || (sold === 0 && rh === 'REFUNDED' && balance(H) === 0), `sold=${sold} refund=${rh} balance=${balance(H)}`);
  invariants('G_REFUNDS', users);
});

await scenario('H_LEDGER_TAMPER', async () => {
  const u = mkUser('h'); grant(u, 20); await redeem(u, EX.CHEAP); const tx = sql(`select id from public.transacciones_cartera where usuario_id = '${u.id}' and cantidad > 0 limit 1;`); const hb = balance(u); const hr = ledgerRows(u);
  const att = {
    'UPDATE ledger': `update public.transacciones_cartera set cantidad = 999 where id = '${tx}';`, 'DELETE ledger': `delete from public.transacciones_cartera where id = '${tx}';`,
    'UPDATE snapshot': `update public.carteras_usuario set saldo_tickets = 99999 where usuario_id = '${u.id}';`, 'INSERT snapshot': `insert into public.carteras_usuario (usuario_id, saldo_tickets) values ('${uuid()}', 5);`,
    'DELETE snapshot': `delete from public.carteras_usuario where usuario_id = '${u.id}';`, 'UPDATE consumos': `update public.ot_consumos set cantidad = 1 where usuario_id = '${u.id}';`,
    'ledger bad chain (saldo_resultante)': `insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_anterior, saldo_resultante) values ('${u.id}', 'ajuste', 5, 20, 999);`,
    'ledger bad chain (seq)': `insert into public.transacciones_cartera (usuario_id, tipo, cantidad, seq) values ('${u.id}', 'ajuste', 5, 77);`,
    'ledger overdraft': `insert into public.transacciones_cartera (usuario_id, tipo, cantidad) values ('${u.id}', 'ajuste', -21);`,
    'ledger zero amount': `insert into public.transacciones_cartera (usuario_id, tipo, cantidad) values ('${u.id}', 'ajuste', 0);`,
    'refund without a credit reference': `insert into public.transacciones_cartera (usuario_id, tipo, cantidad) values ('${u.id}', 'devolucion', -5);`,
    'refund beyond the unspent part': `insert into public.transacciones_cartera (usuario_id, tipo, cantidad, referencia_id) values ('${u.id}', 'devolucion', -${hb + 1}, '${tx}');`,
  };
  const accepted = []; for (const [n, s] of Object.entries(att)) if (sqlTry(s).ok) accepted.push(n);
  check('X15.direct_sql_tampering_rejected_by_triggers', accepted.length === 0 && balance(u) === hb, accepted.join(',') || `${Object.keys(att).length} attempts rejected`);
  const one = sqlTry(`insert into public.transacciones_cartera (usuario_id, tipo, cantidad) values ('${u.id}', 'ajuste', 1), ('${u.id}', 'ajuste', 1);`);
  check('X15.two_rows_of_one_wallet_in_one_statement_fail_closed_nothing_inserted', !one.ok && /WALLET_INCONSISTENT/.test(one.out) && balance(u) === hb && ledgerRows(u) === hr, one.out.split('\n')[0].slice(0, 90));
  const multi = sqlTry(`insert into public.transacciones_cartera (usuario_id, tipo, cantidad) select x.id, 'ajuste', 3 from (values ('${u.id}'::uuid), ('${uuid()}'::uuid)) x(id) where x.id = '${u.id}';`);
  check('X15.one_row_per_wallet_in_a_multi_wallet_statement_works', multi.ok && balance(u) === hb + 3, multi.out.split('\n')[0].slice(0, 90));
  // PostgREST: service_role cannot write; user token cannot call functions
  const svc = { apikey: ktorEnv.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${ktorEnv.SUPABASE_SERVICE_ROLE_KEY}`, 'Content-Type': 'application/json' };
  const usr = { apikey: ktorEnv.SUPABASE_ANON_KEY, Authorization: `Bearer ${u.t}`, 'Content-Type': 'application/json' };
  const w1 = await fetch(`${GATEWAY}/rest/v1/transacciones_cartera`, { method: 'POST', headers: svc, body: JSON.stringify({ usuario_id: u.id, tipo: 'ajuste', cantidad: 500 }) });
  const w2 = await fetch(`${GATEWAY}/rest/v1/carteras_usuario?usuario_id=eq.${u.id}`, { method: 'PATCH', headers: svc, body: JSON.stringify({ saldo_tickets: 99999 }) });
  const fns = [['app_redeem_exam_access', { p_usuario: u.id, p_examen: EX.CHEAP, p_idem_key: uuid() }], ['app_credit_payment_order', { p_proveedor: 'sandbox', p_evento_id: 'x', p_orden: uuid(), p_transaccion: 'x', p_importe: 1, p_moneda: 'EUR', p_producto: 'x' }],
    ['app_refund_payment_order', { p_proveedor: 'sandbox', p_evento_id: 'x', p_orden: uuid() }], ['app_create_payment_order', { p_usuario: u.id, p_producto: 'LOCAL_PACK_10', p_proveedor: 'sandbox', p_idem_key: uuid() }], ['admin_grant_tickets', { p_usuario_id: u.id, p_cantidad: 500, p_descripcion: 'x', p_idempotency_key: uuid() }], ['app_wallet_reconcile', {}]];
  const fnOpen = []; for (const [fn, args] of fns) { const r = await fetch(`${GATEWAY}/rest/v1/rpc/${fn}`, { method: 'POST', headers: usr, body: JSON.stringify(args) }); const a = await fetch(`${GATEWAY}/rest/v1/rpc/${fn}`, { method: 'POST', headers: { apikey: ktorEnv.SUPABASE_ANON_KEY, Authorization: `Bearer ${ktorEnv.SUPABASE_ANON_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(args) }); if (r.status < 400 || a.status < 400) fnOpen.push(`${fn}:${r.status}/${a.status}`); }
  check('C5.service_role_cannot_write_ledger_or_snapshot_directly', w1.status >= 400 && w2.status >= 400 && balance(u) === hb + 3, `${w1.status}/${w2.status}`);
  check('C5.economic_functions_not_executable_by_anon_or_authenticated', fnOpen.length === 0, fnOpen.join(' ') || `${fns.length} functions denied for anon and user tokens`);
  const priv = sql(`select string_agg(p.proname, ',') from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public','private') and p.proname in ('app_redeem_exam_access','app_create_payment_order','app_get_payment_order','app_credit_payment_order','app_refund_payment_order','app_wallet_reconcile','app_server_now','exam_access_verdict','ledger_allocate_debit','ot_credit_remaining') and (has_function_privilege('anon', p.oid, 'EXECUTE') or has_function_privilege('authenticated', p.oid, 'EXECUTE') or has_function_privilege('public', p.oid, 'EXECUTE'));`);
  check('C5.has_function_privilege_false_for_anon_authenticated_public', priv === '', priv || 'none executable');
  const tbl = sql(`select string_agg(table_name || ':' || privilege_type, ',') from information_schema.role_table_grants where table_schema = 'public' and grantee in ('anon','authenticated') and table_name in ('transacciones_cartera','carteras_usuario','ot_consumos','compras_usuario','eventos_pago','refund_case') and privilege_type <> 'SELECT';`);
  check('C5.no_write_grants_for_client_roles_on_economy_tables', tbl === '', tbl || 'none');
  invariants('H_LEDGER_TAMPER', [u]);
});

await scenario('I_RATE_AND_LOGS', async () => {
  await restartKtor();
  const u = mkUser('i'); const statuses = []; for (let i = 0; i < 13; i++) statuses.push((await redeem(u, EX.CHEAP)).status);
  check('G3.redeem_limited_to_10_per_minute_with_retry_after', statuses.indexOf(429) >= 10 && statuses.indexOf(429) <= 11, `first429=#${statuses.indexOf(429) + 1}`);
  const o = mkUser('i2'); const os = []; for (let i = 0; i < 13; i++) os.push((await createOrder(o)).status);
  const pend = Number(sql(`select count(*) from public.compras_usuario where usuario_id = '${o.id}' and estado = 'pendiente';`));
  check('G3.order_create_limited_to_10_per_minute', os.indexOf(429) >= 10 && os.indexOf(429) <= 11, `first429=#${os.indexOf(429) + 1} pendingOrdersCreated=${pend}`);
  const w = []; const body = { eventId: 'x', type: 'payment.succeeded', orderId: uuid(), transactionId: 'x', amountCents: 1, currency: 'EUR', productCode: 'x' };
  for (let i = 0; i < 310; i++) w.push((await webhook(body, { sig: 'f'.repeat(64) })).status);
  check('G3.webhook_limited_per_source_ip', w.includes(429), `first429=#${w.indexOf(429) + 1} of 310`);
  const noJwt = await call(null, 'POST', '/api/v1/app/wallet/canjes', { examId: EX.CHEAP }, { 'Idempotency-Key': uuid() }); const garb = await redeem({ t: 'not.a.jwt' }, EX.CHEAP);
  check('G1.no_or_garbage_token_401_and_nothing_written', noJwt.status === 401 && garb.status === 401, `${noJwt.status}/${garb.status}`);
  const blocked = mkUser('iblk'); grant(blocked, 50); sql(`update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${blocked.id}';`);
  const rb = await redeem(blocked, EX.CHEAP); const ob = await createOrder(blocked);
  check('G1.blocked_account_cannot_spend_or_order', rb.status === 403 && ob.status === 403 && balance(blocked) === 50, `${rb.status}/${ob.status}`);
  invariants('I_RATE', [u, o, blocked]);
});

await scenario('J_LOST_RESPONSE', async () => {
  const users = []; const verdicts = [];
  for (const delay of [8, 35, 90]) {
    const u = mkUser('j' + delay); users.push(u); grant(u, 40); const key = uuid();
    const inflight = fetch(`${KTOR}/api/v1/app/wallet/canjes`, { method: 'POST', headers: { Authorization: `Bearer ${u.t}`, 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify({ examId: EX.CHEAP }) }).then((r) => r.status).catch(() => 'cut');
    await sleep(delay); killKtor(); const first = await inflight; await sleep(1500); await startKtor();
    let last = null; for (let i = 0; i < 45; i++) { last = await redeem(u, EX.CHEAP, {}, key); if ([200, 201].includes(last.status)) break; if (last.status < 500 && last.json?.code !== 'IDEMPOTENCY_KEY_IN_PROGRESS') break; await sleep(1000); }
    const rows = ledgerRows(u, 'compra_examen'); const again = await redeem(u, EX.CHEAP, {}, key);
    verdicts.push(`delay=${delay}ms first=${first} retry=${last.status} rows=${rows} balance=${balance(u)} replayTx=${again.json?.canje?.transactionId === last.json?.canje?.transactionId}`);
    if (!([200, 201].includes(last.status) && rows === 1 && balance(u) === 30 && again.json?.canje?.transactionId === last.json?.canje?.transactionId)) verdicts.push('ANOMALY');
  }
  check('X14.commit_then_lost_response_retry_with_same_key_is_exactly_one_debit', !verdicts.includes('ANOMALY'), verdicts.filter((v) => v !== 'ANOMALY').join(' | '));
  invariants('J_LOST_RESPONSE', users);
});

await scenario('K_DEADLOCK_PROBE', async () => {
  await restartKtor();
  const users = []; const bad = []; let n = 0;
  for (let i = 0; i < 40; i++) {
    const u = mkUser('k' + i); users.push(u); const key = uuid(); const o = await createOrder(u, 'LOCAL_PACK_10', key); const row = orderRow(o.json.order.id);
    sql(`set session_replication_role = replica; update public.compras_usuario set expira_en = now() - interval '1 minute' where id = '${row.id}'; set session_replication_role = origin;`);
    const rs = await Promise.all([createOrder(u, 'LOCAL_PACK_10', key), webhook(webhookBody(row)), createOrder(u, 'LOCAL_PACK_10', key), webhook(webhookBody(row, { eventId: 'ev2-' + uuid() }))]);
    n++; if (rs.some((r) => r.status >= 500)) bad.push(rs.map((r) => r.status).join('/'));
  }
  finding('C4.lock_order_wallet_then_order_no_deadlock_between_order_replay_and_credit', bad.length === 0, bad.length ? `${bad.length}/${n} rounds returned 5xx (deadlock 40P01 suspected): ${bad.slice(0, 3).join(' ')}` : `${n} rounds of replay x credit with an expired order: no 5xx`, 'E2-V1-E01');
  invariants('K_DEADLOCK', users);
});

await scenario('L_SANDBOX_GUARD', async () => {
  killKtor(); await sleep(2000);
  const lib = path.join(repo, 'backend', 'ktor', 'build', 'install', 'ktor', 'lib', '*');
  const hostile = ['production', 'prod', 'staging', 'development', 'dev', '', 'Local', 'LOCAL', ' local', 'local ', 'test2', 'Test'];
  const bad = [];
  for (const appEnv of hostile) {
    const env = { ...process.env, ...ktorEnv, APP_ENV: appEnv, PORT: '18099', PAYMENTS_SANDBOX_SECRET: SANDBOX_SECRET };
    const code = await new Promise((resolve) => { const child = spawn('java', ['-cp', lib, 'com.oposicontrol.backend.ApplicationKt'], { cwd: repo, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }); let out = '';
      child.stdout.on('data', (d) => { out += d; }); child.stderr.on('data', (d) => { out += d; });
      const t = setTimeout(() => { child.kill(); resolve({ code: 'still-running', out }); }, 25_000); child.on('exit', (c) => { clearTimeout(t); resolve({ code: c, out }); }); });
    let listening = false; try { await fetch('http://127.0.0.1:18099/api/v1/public/especialidades'); listening = true; } catch { /* closed */ }
    if (code.code !== 78 || listening || code.out.includes(SANDBOX_SECRET)) bad.push(`${JSON.stringify(appEnv)}:${code.code}/${listening ? 'LISTENING' : 'closed'}`);
    killKtor(); await sleep(500);
  }
  check('X12.sandbox_provider_cannot_start_outside_exact_local_or_test_exit_78_before_binding', bad.length === 0, bad.join(' ') || `${hostile.length} hostile APP_ENV values -> exit 78, no listener, no secret in output`);
  const envNo = { ...process.env, ...ktorEnv, APP_ENV: 'local', PORT: '18099' }; delete envNo.PAYMENTS_SANDBOX_SECRET;
  const child = spawn('java', ['-cp', lib, 'com.oposicontrol.backend.ApplicationKt'], { cwd: repo, env: envNo, stdio: 'ignore', windowsHide: true });
  let up = false; for (let i = 0; i < 40 && !up; i++) { await sleep(1000); try { up = (await fetch('http://127.0.0.1:18099/api/v1/public/especialidades')).status === 200; } catch { /* starting */ } }
  const u = mkUser('l'); const wh = await fetch('http://127.0.0.1:18099/api/v1/webhooks/payments/sandbox', { method: 'POST', body: '{}', headers: { 'X-Sandbox-Timestamp': '1', 'X-Sandbox-Signature': 'a' } });
  const ord = await fetch('http://127.0.0.1:18099/api/v1/app/payments/orders', { method: 'POST', headers: { Authorization: `Bearer ${u.t}`, 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }, body: JSON.stringify({ productCode: 'LOCAL_PACK_10', provider: 'sandbox' }) });
  child.kill(); killKtor();
  check('X12.no_secret_means_no_sandbox_provider_webhook_404_order_422', up && wh.status === 404 && ord.status === 422, `up=${up} webhook=${wh.status} order=${ord.status}`);
  const prodCheck = sql(`select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'public.compras_usuario'::regclass and conname = 'compras_usuario_proveedor_check';`);
  const migs = fs.readdirSync(path.join(repo, 'supabase', 'migrations')).filter((f) => f.startsWith('20261018')).map((f) => fs.readFileSync(path.join(repo, 'supabase', 'migrations', f), 'utf8')).join('\n');
  check('X12.sandbox_value_exists_only_in_the_local_overlay_never_in_a_migration', !/'sandbox'/.test(migs) && /sandbox/.test(prodCheck), `local CHECK has sandbox=${/sandbox/.test(prodCheck)}; migrations mention it=${/'sandbox'/.test(migs)}`);
  await startKtor();
});

// ---------- logs: no secret / signature / receipt / email / token ----------
await scenario('Z_LOGS', async () => {
  killKtor(); await sleep(1500);
  const text = fs.readdirSync(LOGDIR).filter((f) => f.startsWith('ktor-')).map((f) => fs.readFileSync(path.join(LOGDIR, f), 'utf8')).join('\n');
  const leaks = [];
  if (text.includes(SANDBOX_SECRET)) leaks.push('sandbox secret');
  if (/e2v1-[0-9a-f]{6}-[a-z0-9]+@e2\.local/.test(text)) leaks.push('e-mail');
  if (/eyJ[A-Za-z0-9_-]{20,}/.test(text)) leaks.push('JWT');
  if (/X-Sandbox-Signature|[0-9a-f]{64}/.test(text)) leaks.push('signature');
  if (/evt-[0-9a-f-]{20,}|tx-[0-9a-f-]{20,}/.test(text)) leaks.push('provider event/transaction ids');
  check('D12.ktor_logs_contain_no_secret_signature_receipt_email_or_token', leaks.length === 0, leaks.join(',') || `${text.split('\n').length} log lines scanned`);
  const recFinal = Number(sql(`select count(*) from public.app_wallet_reconcile(null);`));
  check('INV.reconcile_whole_database_is_empty', recFinal === 0, `rows=${recFinal}`);
});

fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), run, results }, null, 2));
const failed = results.filter((r) => r.status === 'FAIL').length; const findings = results.filter((r) => r.status.startsWith('FINDING')).length;
console.log(`\n${results.length - failed - findings} PASS / ${findings} FINDING / ${failed} FAIL -> ${OUT}`);
process.exit(failed ? 1 : 0);
