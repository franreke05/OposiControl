// Helper: a DISPOSABLE PG17 container with the gate bootstrap + extras + every pending migration of the CURRENT tree. No live stack, no lock.
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const IMAGE = process.env.PG17_IMAGE ?? 'public.ecr.aws/supabase/postgres@sha256:658d1c9b09ae4f61b8e95087b6859181b4b7d6940d769cf7b605609c8aad43e9';
const read = (p) => fs.readFileSync(path.join(repo, p), 'utf8');

export function startDisposable(name) {
  execFileSync('docker', ['run', '-d', '--name', name, '-e', 'POSTGRES_PASSWORD=postgres', IMAGE], { stdio: 'pipe' });
  const args = (extra = []) => ['exec', '-i', name, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', ...extra];
  for (let i = 0; i < 90; i++) {
    const r = spawnSync('docker', args(['-Atc', "select (select count(*) from pg_roles where rolname in ('anon','authenticated','service_role')) || (select to_regclass('auth.users') is not null)::text"]), { encoding: 'utf8' });
    if ((r.stdout || '').trim() === '3true') break;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2000);
  }
  const psql = (sql, extra = ['-v', 'ON_ERROR_STOP=1']) => spawnSync('docker', args(extra), { input: sql, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const must = (sql, extra) => {
    const r = psql(sql, extra);
    if (r.status !== 0) throw new Error((r.stderr || '').slice(0, 600));
    return r.stdout;
  };
  must(read('supabase/baseline/main_shape_subset.sql'), ['-v', 'ON_ERROR_STOP=1', '-1']);
  spawnSync('docker', ['exec', '-i', name, 'psql', '-U', 'supabase_admin', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1'], { input: read('supabase/baseline/main_shape_auth_users.sql'), encoding: 'utf8' });
  // Same extras, same order as scripts/db-pg17-gate.sh (the import extra is needed by 20261022102000).
  for (const f of ['main_shape_subset_extra.sql', 'main_shape_subset_extra_plans.sql', 'main_shape_subset_extra_engagement.sql', 'main_shape_subset_extra_import.sql']) must(read('supabase/baseline/' + f));
  for (const v of read('supabase/baseline/PENDING_MIGRATIONS.txt').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))) must(read(`supabase/migrations/${v}.sql`));
  return {
    name,
    psql,
    must,
    stop: () => {
      try { execFileSync('docker', ['rm', '-f', name], { stdio: 'pipe' }); } catch { /* ya no esta */ }
    },
  };
}
