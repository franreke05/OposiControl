#!/usr/bin/env node
// SEC-V1-05 gate (20261022102400): CONCURRENCY of payment / refund events on the same order, each call in its own connection, against a DISPOSABLE PG17
// (no live stack, no lock).   node ops/local-exam/refund-order-probe.mjs   (ROUNDS, N override the defaults)
//   A. ROUNDS x (PAID || REFUNDED) on a fresh pending order: whatever wins, the order never ends credited-and-not-refunded (net ledger of the order = 0).
//   B. N parallel PAID with the SAME event id on one order: exactly one CREDITED, the rest REPLAY, one credit row.
//   C. N parallel PAID with DIFFERENT event ids on one order: exactly one CREDITED, the rest REPLAY, one credit row.
//   D. N parallel REFUNDED with different event ids on one paid order: exactly one REFUNDED, the rest REPLAY, one reversal row.
//   E. N parallel order creations with the SAME idempotency key: one CREATED, the rest REPLAY, one order.
// Afterwards: no deadlock / unexpected error, ledger sum = balance and balance >= 0 for every user, app_wallet_reconcile clean.
import { spawn } from 'node:child_process';
import { startDisposable } from './disposable-db.mjs';

const ROUNDS = Number(process.env.ROUNDS ?? 30);
const N = Number(process.env.N ?? 8);
const PRODUCT = 'rp-pack-100';
const user = (n) => `a0000000-0000-0000-0000-${String(900 + n).padStart(12, '0')}`;
const UA = user(1); const UB = user(2); const UC = user(3); const UD = user(4); const UE = user(5);
const db = startDisposable(`refund-order-probe-${process.pid}`);
const failures = [];
const check = (ok, msg) => { if (!ok) failures.push(msg); };
let code = 1;
try {
  db.must(`
insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-' || lpad((900 + n)::text, 12, '0'))::uuid, 'rp' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 5) n;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
  select ('a0000000-0000-0000-0000-' || lpad((900 + n)::text, 12, '0'))::uuid, 'rp' || n, 'rp_user_' || n, true, 'activa' from generate_series(1, 5) n
  on conflict (usuario_id) do update set username = excluded.username;
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible)
  values ('b9900000-0000-0000-0000-000000000001', '${PRODUCT}', 'pack_tickets', 'RP 100', 499, 'EUR', 100, true, true);
`);
  const one = (sql) => db.must(sql, ['-v', 'ON_ERROR_STOP=1', '-At']).trim();
  const run = (sql) => new Promise((resolve) => {
    const p = spawn('docker', ['exec', '-i', db.name, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1'], { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = ''; let err = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('close', () => resolve({ out: out.trim(), err: err.trim() }));
    p.stdin.end(sql);
  });
  const order = (u, key) => one(`select orden_id from public.app_create_payment_order('${u}', '${PRODUCT}', 'google_play', '${key}')`);
  const paid = (o, ev, tx) => `select outcome from public.app_credit_payment_order('google_play', '${ev}', '${o}', '${tx}', 499, 'EUR', '${PRODUCT}');`;
  const refund = (o, ev) => `select outcome from public.app_refund_payment_order('google_play', '${ev}', '${o}');`;
  const errorsOf = (results, label) => results.forEach((r, i) => check(r.err === '', `${label} job ${i}: ${r.err.split('\n')[0]}`));
  const count = (results, v) => results.filter((r) => r.out === v).length;

  // A. PAID || REFUNDED on the same pending order
  const seen = {};
  for (let i = 1; i <= ROUNDS; i++) {
    const o = order(UA, `rp-a-${i}`);
    const jobs = i % 2 === 0 ? [paid(o, `pa-${i}`, `ta-${i}`), refund(o, `ra-${i}`)] : [refund(o, `ra-${i}`), paid(o, `pa-${i}`, `ta-${i}`)];
    const res = await Promise.all(jobs.map(run));
    errorsOf(res, `A round ${i}`);
    const [estado, net, credits] = one(`select c.estado, coalesce((select sum(t.cantidad) from public.transacciones_cartera t where t.compra_id = c.id), 0), (select count(*) from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'pack_tickets') from public.compras_usuario c where c.id = '${o}'`).split('|');
    seen[estado] = (seen[estado] ?? 0) + 1;
    check(Number(net) === 0, `A round ${i}: order ${estado} keeps ${net} OT after a refund (outcomes ${res.map((r) => r.out).join(',')})`);
    check(estado === 'reembolsada' || (estado === 'en_revision' && Number(credits) === 0), `A round ${i}: unexpected final state ${estado} credits=${credits}`);
  }

  // B. N x PAID, same event id
  const ob = order(UB, 'rp-b');
  const rb = await Promise.all(Array.from({ length: N }, () => run(paid(ob, 'pb-same', 'tb'))));
  errorsOf(rb, 'B');
  check(count(rb, 'CREDITED') === 1 && count(rb, 'REPLAY') === N - 1, `B: outcomes ${rb.map((r) => r.out).join(',')}`);
  check(one(`select count(*) from public.transacciones_cartera where compra_id = '${ob}' and tipo = 'pack_tickets'`) === '1', 'B: exactly one credit row');

  // C. N x PAID, different event ids
  const oc = order(UC, 'rp-c');
  const rc = await Promise.all(Array.from({ length: N }, (_, i) => run(paid(oc, `pc-${i}`, 'tc'))));
  errorsOf(rc, 'C');
  check(count(rc, 'CREDITED') === 1 && count(rc, 'REPLAY') === N - 1, `C: outcomes ${rc.map((r) => r.out).join(',')}`);
  check(one(`select count(*) from public.transacciones_cartera where compra_id = '${oc}' and tipo = 'pack_tickets'`) === '1', 'C: exactly one credit row');

  // D. N x REFUNDED, different event ids, on a paid order
  const od = order(UD, 'rp-d');
  check(one(paid(od, 'pd', 'td')) === 'CREDITED', 'D: fixture credit');
  const rd = await Promise.all(Array.from({ length: N }, (_, i) => run(refund(od, `rd-${i}`))));
  errorsOf(rd, 'D');
  check(count(rd, 'REFUNDED') === 1 && count(rd, 'REPLAY') === N - 1, `D: outcomes ${rd.map((r) => r.out).join(',')}`);
  check(one(`select count(*) from public.transacciones_cartera where compra_id = '${od}' and tipo = 'devolucion'`) === '1', 'D: exactly one reversal row');

  // E. N x create order, same idempotency key
  const re = await Promise.all(Array.from({ length: N }, () => run(`select outcome from public.app_create_payment_order('${UE}', '${PRODUCT}', 'google_play', 'rp-e-same');`)));
  errorsOf(re, 'E');
  check(count(re, 'CREATED') === 1 && count(re, 'REPLAY') === N - 1, `E: outcomes ${re.map((r) => r.out).join(',')}`);
  check(one(`select count(*) from public.compras_usuario where usuario_id = '${UE}' and idempotency_key = 'rp-e-same'`) === '1', 'E: one order');

  // invariants
  for (const u of [UA, UB, UC, UD, UE]) {
    const [sum, saldo] = one(`select coalesce((select sum(cantidad) from public.transacciones_cartera where usuario_id = '${u}'), 0), coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '${u}'), 0)`).split('|').map(Number);
    check(sum === saldo, `user ${u}: ledger sum ${sum} != balance ${saldo}`);
    check(saldo >= 0, `user ${u}: negative balance ${saldo}`);
  }
  const reconcile = Number(one('select count(*) from public.app_wallet_reconcile(null)'));
  check(reconcile === 0, `reconcile rows ${reconcile}`);
  console.log(`REFUND_ORDER_PROBE rounds=${ROUNDS} n=${N} A_final_states=${JSON.stringify(seen)} reconcile_rows=${reconcile} failures=${failures.length}`);
  if (failures.length === 0) {
    console.log('REFUND_ORDER_PROBE OK');
    code = 0;
  } else {
    for (const f of failures.slice(0, 40)) console.error(`  FAIL ${f}`);
    console.error('REFUND_ORDER_PROBE FAILED');
  }
} finally {
  db.stop();
}
process.exit(code);
