#!/usr/bin/env node
// Debug helper: builds the gate DB (bootstrap + extras + pending migrations) in a disposable container and runs assertions.sql with verbose errors.   node ops/local-exam/gate-debug.mjs
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const IMAGE = process.env.PG17_IMAGE ?? 'public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9';
const ctr = `gate-debug-${process.pid}`;
const run = (sql, extra = []) => spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose', ...extra], { input: sql, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const read = (p) => fs.readFileSync(path.join(repo, p), 'utf8');
try {
  execFileSync('docker', ['run', '-d', '--name', ctr, '-e', 'POSTGRES_PASSWORD=postgres', IMAGE], { stdio: 'pipe' });
  for (let i = 0; i < 90; i++) {
    const r = spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-Atc', "select (select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')) || (select to_regclass('auth.users') is not null)::text"], { encoding: 'utf8' });
    if ((r.stdout || '').trim() === '3true') break;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2000);
  }
  run(read('supabase/baseline/main_shape_subset.sql'), ['-1']);
  spawnSync('docker', ['exec', '-i', ctr, 'psql', '-U', 'supabase_admin', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1'], { input: read('supabase/baseline/main_shape_auth_users.sql'), encoding: 'utf8' });
  for (const f of ['main_shape_subset_extra.sql', 'main_shape_subset_extra_plans.sql', 'main_shape_subset_extra_engagement.sql', 'main_shape_subset_extra_import.sql']) {
    const x = run(read('supabase/baseline/' + f));
    if (x.status !== 0) console.log('EXTRA FAILED', f, x.stderr.split('\n').filter((l) => !/NOTICE/.test(l)).slice(0, 8).join('\n'));
  }
  for (const v of read('supabase/baseline/PENDING_MIGRATIONS.txt').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))) {
    const r = run(read(`supabase/migrations/${v}.sql`)); if (r.status !== 0) { console.log('MIGRATION FAILED', v, r.stderr); process.exit(1); }
  }
  const r = run(read('supabase/baseline/assertions.sql'));
  console.log(r.status === 0 ? 'ASSERTIONS OK' : r.stderr.split('\n').filter((l) => !/NOTICE/.test(l)).slice(0, 40).join('\n'));
} finally { try { execFileSync('docker', ['rm', '-f', ctr], { stdio: 'pipe' }); } catch { /* */ } }
