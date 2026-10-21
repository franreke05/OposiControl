#!/usr/bin/env node
// LIVE CONVERGE (LOCAL ONLY, never MAIN, never --reset, never `down -v`): bring a LIVE local DB that holds OLDER definitions of the V1 migrations
// (they were edited in place while still local-only: 20261022100400 refund P2, 20261022100700 admin gift) to the CURRENT files and add the new ones
// (20261022100800 saved items), WITHOUT data loss.
//
//   STACK_HOLDER=<holder> node ops/local-exam/converge.mjs      live stack (docker compose db). Guarded by STACK_LOCK (exit 3 if another holder has it).
//   node ops/local-exam/up.mjs --converge                        same thing (up.mjs delegates here, before it touches anything else)
//   CONVERGE_CONTAINER=<name> node ops/local-exam/converge.mjs   a disposable PG container (no lock needed; used by the proof)
//
// How: every V1 migration file is idempotent (create or replace / if not exists / drop-create of triggers) so re-applying a file converges the
// definitions and keeps the rows. Order = supabase/V1_MIGRATIONS.txt from 20261022100300; then the local overlay 97 (sandbox provider CHECK, empty wallets).
// Each stamp is recorded in private.v1_applied_migrations with the sha256 of the file (LF-normalised). VERIFY compares, for EVERY function that those
// files define, md5(pg_proc.prosrc) in the DB with md5 of the body in the file, and the stored sha256 with the file: any difference exits 1.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const FIRST = '20261022100300';

const md5 = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex');
const sha256Lf = (s) => crypto.createHash('sha256').update(s.split('\r\n').join('\n'), 'utf8').digest('hex');

export function makePsql({ container, local = path.join(here, '.local'), user = 'postgres' } = {}) {
  const args = container
    ? ['exec', '-i', container, 'psql', '-U', user, '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-At', '-f', '-']
    : ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
      'exec', '-T', 'db', 'psql', '-U', user, '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-At', '-f', '-'];
  return (sql) => execFileSync('docker', args, { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'inherit'] }).trim();
}

export function v1Migrations(root = repo) {
  return fs.readFileSync(path.join(root, 'supabase/V1_MIGRATIONS.txt'), 'utf8').split(/\r?\n/).map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#') && l.split('_')[0] >= FIRST);
}

/** Every `create or replace function` of the given sources: key `schema.name(args)` -> body text between the dollar quotes (the last definition wins). */
export function functionBodies(sources) {
  const re = /create or replace function\s+([a-z_."]+)\s*\(([\s\S]*?)\)\s*returns[\s\S]*?\bas\s+(\$[a-z_]*\$)([\s\S]*?)\3\s*;/gi;
  const out = new Map();
  for (const full of sources) {
    // the header holds the commented ROLLBACK/VERIFY blocks (they contain `create or replace function` text): only the executable part counts
    const start = full.search(/^begin;/m);
    const text = start >= 0 ? full.slice(start) : full;
    for (const m of text.matchAll(re)) {
      const name = m[1].replace(/"/g, '').includes('.') ? m[1].replace(/"/g, '') : `public.${m[1]}`;
      out.set(`${name}(${m[2].replace(/\s+/g, ' ').trim()})`, m[4]);
    }
  }
  return out;
}

export function converge({ psql, psqlAdmin, root = repo }) {
  // MAIN shape owned by supabase_auth_admin (e.g. auth.users.email_confirmed_at for the referral trigger): applied as supabase_admin when the caller provides it
  if (psqlAdmin) psqlAdmin(fs.readFileSync(path.join(root, 'supabase/baseline/main_shape_auth_users.sql'), 'utf8'));
  const stamps = v1Migrations(root);
  const files = stamps.map((v) => ({ v, text: fs.readFileSync(path.join(root, 'supabase/migrations', `${v}.sql`), 'utf8') }));
  psql('create schema if not exists private;\n' +
    'create table if not exists private.v1_applied_migrations (version text primary key, file_sha256 text not null, applied_at timestamptz not null default clock_timestamp());\n' +
    'revoke all on table private.v1_applied_migrations from public, anon, authenticated, service_role;');
  // a stack that applied the F01 draft: same repair up.mjs runs first
  if (psql("select to_regclass('public.ot_consumos') is not null") === 't') psql(fs.readFileSync(path.join(here, 'sql/89_f01_repair_local.sql'), 'utf8'));
  // MAIN objects the bootstrap subset lacks (idempotent): beneficios_plan, suscripciones
  psql(fs.readFileSync(path.join(root, 'supabase/baseline/main_shape_subset_extra_plans.sql'), 'utf8'));
  psql(fs.readFileSync(path.join(root, 'supabase/baseline/main_shape_subset_extra_engagement.sql'), 'utf8'));
  // NIGHT-A: the bulk-import vertical of MAIN (staging table + import RPCs), absent from the generated subset; idempotent
  psql(fs.readFileSync(path.join(root, 'supabase/baseline/main_shape_subset_extra_import.sql'), 'utf8'));
  for (const f of files) {
    process.stdout.write(`  apply ${f.v}\n`);
    psql(f.text);
    psql(`insert into private.v1_applied_migrations (version, file_sha256) values ('${f.v}', '${sha256Lf(f.text)}')
          on conflict (version) do update set file_sha256 = excluded.file_sha256, applied_at = clock_timestamp();`);
  }
  psql(fs.readFileSync(path.join(here, 'sql/97_v1_economy_local.sql'), 'utf8'));
  // E5-V1-S10: feedback_pregunta (MAIN has it, the local bootstrap subset does not)
  psql(fs.readFileSync(path.join(here, 'sql/91_feedback_pregunta_local.sql'), 'utf8'));
  // V1 Access v2 local fixtures (test plans, benefits, products, recarga blocks, users Q6/Q7, demo exam); only where the economy fixtures already exist (a live stack)
  if (psql("select to_regclass('public.especialidades') is not null and exists (select 1 from public.especialidades where id = 'e5000000-0000-4000-8000-000000000001')") === 't') psql(fs.readFileSync(path.join(here, 'sql/98b_v1_access_v2_fixtures.sql'), 'utf8'));
  if (psql("select to_regclass('public.especialidades') is not null and exists (select 1 from public.especialidades where id = 'e5000000-0000-4000-8000-000000000001')") === 't') psql(fs.readFileSync(path.join(here, 'sql/98c_v1_qa_fixtures.sql'), 'utf8'));
  psql("notify pgrst, 'reload schema';");
  return verify({ psql, root, files });
}

export function verify({ psql, root = repo, files }) {
  psql('create schema if not exists private; create table if not exists private.v1_applied_migrations (version text primary key, file_sha256 text not null, applied_at timestamptz not null default clock_timestamp());');
  files ??= v1Migrations(root).map((v) => ({ v, text: fs.readFileSync(path.join(root, 'supabase/migrations', `${v}.sql`), 'utf8') }));
  const problems = [];
  const stored = new Map(psql('select version || \'|\' || file_sha256 from private.v1_applied_migrations').split('\n').filter(Boolean).map((l) => l.split('|')));
  for (const f of files) {
    if (stored.get(f.v) !== sha256Lf(f.text)) problems.push(`stamp ${f.v}: ${stored.has(f.v) ? 'sha256 differs from the file' : 'not recorded'}`);
  }
  const bodies = functionBodies(files.map((f) => f.text));
  let checked = 0;
  for (const [key, body] of bodies) {
    const [schema, rest] = [key.slice(0, key.indexOf('.')), key.slice(key.indexOf('.') + 1)];
    const name = rest.slice(0, rest.indexOf('('));
    const live = psql(`select md5(p.prosrc) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = '${schema}' and p.proname = '${name}'`).split('\n').filter(Boolean);
    checked += 1;
    if (!live.includes(md5(body))) problems.push(`function ${key}: ${live.length ? 'body differs from the file' : 'missing in the database'}`);
  }
  console.log(`verify: ${files.length} stamps, ${checked} functions compared (md5 of prosrc vs file body)`);
  if (problems.length) { problems.forEach((p) => console.error('  MISMATCH ' + p)); process.exitCode = 1; return { ok: false, problems }; }
  console.log('verify: OK, the database holds exactly the definitions of the files');
  return { ok: true, problems };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const container = process.env.CONVERGE_CONTAINER;
  if (!container) requireStackLock('converge.mjs');
  const psql = makePsql({ container });
  if (process.argv.includes('--verify-only')) verify({ psql }); else converge({ psql });
}
