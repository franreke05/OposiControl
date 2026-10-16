#!/usr/bin/env node
// PROOF of the live converge path on a DISPOSABLE PG17 container (never the live stack, never MAIN). Needs docker; takes the PG17 image of the gate.
//   node ops/local-exam/converge-proof.mjs [OLD_REF]      OLD_REF = the commit that holds the PREVIOUS definitions (default 9afd1c8, before refund P2 and saved items)
// 1. Build the PREVIOUS state: the bootstrap + the pending migrations exactly as they were at OLD_REF, plus data written through the OLD functions.
// 2. Prove that VERIFY detects the drift (exit 1) before converging.
// 3. Run converge (twice: idempotent). Rows must be identical, VERIFY must say OK.
// 4. Prove behaviour on the PRE-EXISTING data: P2 refund shortens the access of an order paid BEFORE the converge; saved items work.
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { converge, makePsql, verify } from './converge.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const OLD = process.argv[2] ?? '9afd1c8';
const IMAGE = process.env.PG17_IMAGE ?? 'public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9';
const ctr = `converge-proof-${process.pid}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'converge-proof-'));
const sh = (cmd, args, opts = {}) => execFileSync(cmd, args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'inherit'], ...opts });
const psql = makePsql({ container: ctr });
const assert = (cond, msg) => { if (!cond) { console.error('PROOF FAILED: ' + msg); cleanup(); process.exit(1); } console.log('ok:   ' + msg); };
function cleanup() { try { sh('docker', ['rm', '-f', ctr]); } catch { /* ya no esta */ } fs.rmSync(tmp, { recursive: true, force: true }); }

try {
  // previous tree
  const tar = path.join(tmp, 'old.tar');
  sh('git', ['-C', repo, 'archive', '-o', tar, OLD, 'supabase']);
  sh('tar', ['-xf', 'old.tar'], { cwd: tmp });
  const old = path.join(tmp, 'supabase');
  sh('docker', ['run', '-d', '--name', ctr, '-e', 'POSTGRES_PASSWORD=postgres', IMAGE]);
  for (let i = 0; i < 90; i++) {
    try { if (psql("select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')") === '3' && psql("select to_regclass('auth.users') is not null") === 't') break; } catch { /* arrancando */ }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2000);
  }
  const psqlTx = (file) => spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-1'], { input: fs.readFileSync(file), encoding: 'utf8' });
  let r = psqlTx(path.join(old, 'baseline/main_shape_subset.sql')); assert(r.status === 0, 'previous bootstrap loads ' + (r.stderr || '').slice(0, 200));
  const extra = path.join(old, 'baseline/main_shape_subset_extra.sql');
  if (fs.existsSync(extra)) psql(fs.readFileSync(extra, 'utf8'));
  const pending = fs.readFileSync(path.join(old, 'baseline/PENDING_MIGRATIONS.txt'), 'utf8').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  for (const v of pending) psql(fs.readFileSync(path.join(old, 'migrations', `${v}.sql`), 'utf8'));
  psql(fs.readFileSync(path.join(here, 'sql/97_v1_economy_local.sql'), 'utf8'));
  assert(psql("select count(*) from pg_proc where proname = 'app_save_item'") === '0', `previous state has no saved-items functions (${pending.length} migrations of ${OLD})`);
  assert(psql("select count(*) from pg_proc where proname = 'app_refund_payment_order' and prosrc like '%REFUNDED_CASE_OPEN%'") === '1', 'previous state holds the OLD refund function');

  // data written through the OLD definitions
  psql(fs.readFileSync(path.join(here, 'sql/converge_proof_old_data.sql'), 'utf8'));
  const snapshot = () => psql(`select 'wallets:' || coalesce(string_agg(usuario_id::text || '=' || saldo_tickets, ',' order by usuario_id), '') from public.carteras_usuario
    union all select 'ledger:' || count(*) || ':' || coalesce(sum(cantidad), 0) from public.transacciones_cartera
    union all select 'orders:' || count(*) || ':' || string_agg(estado, ',' order by id) from public.compras_usuario
    union all select 'access:' || count(*) || ':' || coalesce(string_agg(valido_hasta::text, ',' order by id), '') from public.contenido_desbloqueado_usuario`);
  const before = snapshot();
  console.log(before.split('\n').map((l) => '      ' + l).join('\n'));

  // drift is detected before converging
  const stampsBefore = psql("select to_regclass('private.v1_applied_migrations') is not null");
  assert(stampsBefore === 'f', 'no stamp table yet (the live stack has none either)');
  process.exitCode = 0;
  const drift = verify({ psql });
  assert(drift.ok === false && drift.problems.some((p) => p.includes('app_refund_payment_order')), 'VERIFY detects the drift before converging (refund function differs)');
  process.exitCode = 0;

  // converge, twice
  converge({ psql });
  assert(process.exitCode !== 1, 'converge #1 ends with VERIFY OK');
  converge({ psql });
  assert(process.exitCode !== 1, 'converge #2 (idempotent) ends with VERIFY OK');
  assert(snapshot() === before, 'no data loss: wallets, ledger, orders and access are identical after the converge');
  assert(psql('select count(*) from private.v1_applied_migrations') === '11', 'eleven stamps recorded (20261022100300..101200)');
  assert(psql("select (public.app_plan_quota('a0000000-0000-0000-0000-0000000000d1') ->> 'nivel')") === '0', 'the plan summary RPC answers for a pre-existing user (no subscription: nivel 0)');
  assert(psql("select count(*) from pg_proc where proname = 'app_choose_plan_exam'") === '1', 'the quota function exists after the converge');
  assert(psql("select count(*) from pg_proc where proname = 'app_save_item'") === '1', 'saved-items functions exist now');

  // behaviour on PRE-EXISTING data: the order paid before the converge is refunded with P2
  const paid = psql("select id from public.compras_usuario where idempotency_key = 'proof-paid' ");
  const res = psql(`select outcome || '|' || saldo from public.app_refund_payment_order('google_play', 'ev-proof-refund', '${paid}')`);
  assert(res === 'REFUNDED_P2|0', 'P2 refund on an order paid BEFORE the converge: ' + res);
  assert(psql("select (valido_hasta <= clock_timestamp() + interval '2 seconds')::text from public.contenido_desbloqueado_usuario where usuario_id = 'a0000000-0000-0000-0000-0000000000d1' and motivo = 'compra'") === 'true', 'the access financed by that credit was shortened');
  assert(psql("select (select count(*) from public.app_wallet_reconcile(null))") === '0', 'reconcile is clean after the refund');
  const saved = psql("select count(*) from public.app_save_item('a0000000-0000-0000-0000-0000000000d1', 'pregunta', 'f5000000-0000-0000-0000-0000000000d1')");
  assert(saved === '1', 'a question of a free exam can be saved after the converge');
  assert(psql("select to_regclass('public.feedback_pregunta') is not null") === 't', 'feedback_pregunta exists after the converge (E5-V1-S10)');
  assert(psql("insert into public.feedback_pregunta (usuario_id, pregunta_id, tipo_feedback, descripcion) values ('a0000000-0000-0000-0000-0000000000d1', 'f5000000-0000-0000-0000-0000000000d1', 'otro', 'prueba') returning estado") === 'pendiente', 'a report can be inserted (estado pendiente)');
  console.log('PROOF OK (old ref ' + OLD + ')');
} finally {
  cleanup();
}
