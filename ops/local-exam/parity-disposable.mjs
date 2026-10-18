#!/usr/bin/env node
// Runs the GATE-1 verdict parity (Kotlin EntitlementPolicy vs SQL private.exam_access_verdict) against a DISPOSABLE PG17 container built from the CURRENT tree
// (gate bootstrap + extras + every pending migration). No live stack, no lock, nothing persists.   node ops/local-exam/parity-disposable.mjs
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const IMAGE = process.env.PG17_IMAGE ?? 'public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9';
const ctr = `parity-${process.pid}`;
const psql = (sql, tx = false) => {
  const r = spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1', ...(tx ? ['-1'] : [])], { input: sql, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (r.status !== 0) { console.error((r.stderr || '').slice(0, 800)); throw new Error('psql failed'); }
};
let code = 1;
try {
  execFileSync('docker', ['run', '-d', '--name', ctr, '-e', 'POSTGRES_PASSWORD=postgres', IMAGE], { stdio: 'pipe' });
  for (let i = 0; i < 90; i++) {
    const r = spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-Atc', "select (select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')) || (select to_regclass('auth.users') is not null)::text"], { encoding: 'utf8' });
    if ((r.stdout || '').trim() === '3true') break;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2000);
  }
  const read = (p) => fs.readFileSync(path.join(repo, p), 'utf8');
  psql(read('supabase/baseline/main_shape_subset.sql'), true);
  spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'supabase_admin', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1'], { input: read('supabase/baseline/main_shape_auth_users.sql'), encoding: 'utf8' });
  for (const f of ['main_shape_subset_extra.sql', 'main_shape_subset_extra_plans.sql', 'main_shape_subset_extra_engagement.sql']) psql(read('supabase/baseline/' + f));
  const pending = read('supabase/baseline/PENDING_MIGRATIONS.txt').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  for (const v of pending) psql(read(`supabase/migrations/${v}.sql`));
  const r = spawnSync('node', [path.join(here, 'verdict-parity.mjs')], { stdio: 'inherit', env: { ...process.env, PARITY_CONTAINER: ctr } });
  code = r.status ?? 1;
} finally {
  try { execFileSync('docker', ['rm', '-f', ctr], { stdio: 'pipe' }); } catch { /* ya no esta */ }
}
process.exit(code);
