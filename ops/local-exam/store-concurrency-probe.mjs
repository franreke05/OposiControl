#!/usr/bin/env node
// Store payments gate (20261022102900, OposiControl re-audit NEW-1 / NEW-2): a purchase credit and a refund / refund reversal for the SAME store
// key in TWO real connections, against a DISPOSABLE PG17 (no live stack, no lock).   node ops/local-exam/store-concurrency-probe.mjs   (ROUNDS override)
// The interleaving is forced, not left to luck: the first transaction does its work, keeps its transaction OPEN with pg_sleep, and the second one
// starts while it is open. Without the per-key lock the second call does not see the uncommitted order (or orphan row) and the two facts are never
// linked; with the lock it waits and sees it.
//   R1. credit (open) || REFUND_REVERSED of the same key   -> the reversal is RECORDED on the order (REVERSAL_EARLY), never left as an orphan.
//   R2. credit (open) || REFUND of the same key            -> the refund is APPLIED to the order (REFUNDED), never left as an orphan of a paid order.
//   R3. REFUND_REVERSED orphan (open) || credit            -> the credit sees the orphan and attaches it (REVERSAL_HELD).
//   R4. REFUND orphan (open) || credit                     -> the credit sees the orphan refund first: order held for review, nothing credited.
// Afterwards: no error, no orphan row whose order exists, ledger sum = balance, app_wallet_reconcile clean.
import { spawn } from 'node:child_process';
import { startDisposable } from './disposable-db.mjs';

const ROUNDS = Number(process.env.ROUNDS ?? 3);
const user = (n) => `a0000000-0000-0000-0000-${String(990 + n).padStart(12, '0')}`;
const db = startDisposable(`store-concurrency-probe-${process.pid}`);
const failures = [];
const check = (ok, msg) => { if (!ok) failures.push(msg); };
let code = 1;
try {
  db.must(`
insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-' || lpad((990 + n)::text, 12, '0'))::uuid, 'sc' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 4) n;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
  select ('a0000000-0000-0000-0000-' || lpad((990 + n)::text, 12, '0'))::uuid, 'sc' || n, 'sc_user_' || n, true, 'activa' from generate_series(1, 4) n
  on conflict (usuario_id) do update set username = excluded.username;
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible)
  values ('b9990000-0000-0000-0000-000000000001', 'sc-pack-100', 'pack_tickets', 'SC 100', 99, 'EUR', 100, true, true);
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
  const later = (ms, sql) => new Promise((resolve) => setTimeout(() => run(sql).then(resolve), ms));
  const credit = (u, key) => `select outcome from public.app_store_credit_purchase('${u}', public.app_store_account_token('${u}'), 'app_store', 'produccion', 'sc-pack-100', 'com.x.ot100', '${key}', '{}'::jsonb);`;
  const inbox = (ev, tipo, key) => one(`select nuevo from public.app_store_inbox_put('app_store', '${ev}', '${tipo}', '${key}', 'produccion', encode(sha256('${ev}'), 'hex'), '{}'::jsonb)`);
  const reversal = (ev, key) => `select public.app_store_refund_reversed('app_store', '${ev}', '${key}');`;
  const refund = (ev, key) => `select outcome from public.app_store_refund_purchase('app_store', '${ev}', '${key}');`;
  const held = (sql) => `begin; ${sql} select pg_sleep(1.5); commit;`;
  const errorsOf = (results, label) => results.forEach((r, i) => check(r.err === '', `${label} conn ${i}: ${r.err.split('\n')[0]}`));
  const inboxResult = (ev) => one(`select coalesce(resultado, '-') from public.tienda_bandeja where evento_id = '${ev}'`);
  const evResult = (ev) => one(`select coalesce((select resultado from public.eventos_pago where evento_id = '${ev}'), '-')`);
  const st = (key) => one(`select estado || '|' || (select count(*) from public.transacciones_cartera t where t.compra_id = c.id and t.tipo = 'pack_tickets') from public.compras_usuario c where identificador_transaccion = '${key}'`);

  for (let i = 1; i <= ROUNDS; i++) {
    // R1
    inbox(`r1-rev-${i}`, 'REEMBOLSO_REVERTIDO', `r1-${i}`);
    const r1 = await Promise.all([run(held(credit(user(1), `r1-${i}`))), later(400, reversal(`r1-rev-${i}`, `r1-${i}`))]);
    errorsOf(r1, `R1 round ${i}`);
    check(r1[1].out.split('\n').pop() === 'REVERSAL_RECORDED' && evResult(`r1-rev-${i}`) === 'REVERSAL_EARLY',
      `R1 round ${i}: reversal during the open credit -> ${r1[1].out} / inbox ${inboxResult(`r1-rev-${i}`)} (expected REVERSAL_RECORDED, linked)`);
    // R2
    inbox(`r2-ref-${i}`, 'REEMBOLSO', `r2-${i}`);
    const r2 = await Promise.all([run(held(credit(user(2), `r2-${i}`))), later(400, refund(`r2-ref-${i}`, `r2-${i}`))]);
    errorsOf(r2, `R2 round ${i}`);
    check(r2[1].out === 'REFUNDED' && st(`r2-${i}`).startsWith('reembolsada'),
      `R2 round ${i}: refund during the open credit -> ${r2[1].out}, order ${st(`r2-${i}`)} (expected REFUNDED, reembolsada)`);
    // R3
    inbox(`r3-rev-${i}`, 'REEMBOLSO_REVERTIDO', `r3-${i}`);
    const r3 = await Promise.all([run(held(reversal(`r3-rev-${i}`, `r3-${i}`))), later(400, credit(user(3), `r3-${i}`))]);
    errorsOf(r3, `R3 round ${i}`);
    check(inboxResult(`r3-rev-${i}`) === 'REVERSAL_HELD', `R3 round ${i}: credit during the open orphan reversal -> inbox ${inboxResult(`r3-rev-${i}`)} (expected REVERSAL_HELD)`);
    // R4
    inbox(`r4-ref-${i}`, 'REEMBOLSO', `r4-${i}`);
    const r4 = await Promise.all([run(held(refund(`r4-ref-${i}`, `r4-${i}`))), later(400, credit(user(4), `r4-${i}`))]);
    errorsOf(r4, `R4 round ${i}`);
    check(st(`r4-${i}`) === 'en_revision|0', `R4 round ${i}: credit during the open orphan refund -> order ${st(`r4-${i}`)} (expected en_revision, 0 credits)`);
  }
  check(one(`select count(*) from public.tienda_bandeja b where b.resultado in ('REFUND_ORPHAN_HELD', 'REVERSAL_ORPHAN_HELD')
               and exists (select 1 from public.compras_usuario c where c.proveedor = b.proveedor and c.identificador_transaccion = b.clave_compra)`) === '0',
    'an orphan row whose order exists was left behind');
  for (let n = 1; n <= 4; n++) {
    check(one(`select coalesce(sum(cantidad), 0) = coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '${user(n)}'), 0) from public.transacciones_cartera where usuario_id = '${user(n)}'`) === 't', `ledger sum != balance for user ${n}`);
  }
  check(one('select count(*) from public.app_wallet_reconcile(null)') === '0', 'app_wallet_reconcile not clean');
  if (failures.length === 0) {
    console.log(`store-concurrency-probe OK (${ROUNDS} rounds x R1..R4, two connections each)`);
    code = 0;
  } else {
    failures.forEach((f) => console.log('FAIL: ' + f));
  }
} finally {
  db.stop();
}
process.exit(code);
