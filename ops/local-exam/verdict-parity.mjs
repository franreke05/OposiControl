#!/usr/bin/env node
// GATE-1 (E2): paridad Kotlin <-> SQL del veredicto de acceso a un examen, sobre una matriz GENERADA (204 examenes x 140 usuarios x 3 modos de especialidad).
//   node ops/local-exam/verdict-parity.mjs
// 1. Ejecuta AccessVerdictParityTest, que vuelca la matriz de hechos y el veredicto de EntitlementPolicy (Kotlin) a .local/verdict-parity.json.
// 2. Construye esos MISMOS hechos en la base local, DENTRO de una transaccion que se deshace (session_replication_role = replica: solo se escriben
//    las tablas que lee la funcion; nada queda), evalua private.exam_access_verdict con la misma hora y compara caso por caso.
// Sale con codigo != 0 ante la menor diferencia. Escribe (y deshace) en el stack compartido: exige STACK_LOCK como el resto de scripts.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('verdict-parity.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const local = path.join(here, '.local');
const out = path.join(local, 'verdict-parity.json');
const windows = process.platform === 'win32';
fs.mkdirSync(local, { recursive: true });
fs.rmSync(out, { force: true });

execFileSync(path.join(repo, windows ? 'gradlew.bat' : 'gradlew'),
  [':backend:ktor:test', '--tests', 'com.oposicontrol.backend.AccessVerdictParityTest', '--rerun', '-q', '--console=plain'],
  { cwd: repo, stdio: 'inherit', shell: windows, env: { ...process.env, PARITY_OUT: out } });
const matrix = JSON.parse(fs.readFileSync(out, 'utf8'));

const uuid = (prefix, n) => `${prefix}000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const q = (v) => (v === null || v === undefined ? 'null' : `'${String(v).replace(/'/g, "''")}'`);
const SPEC = (i) => uuid('a1', i), EXAM = (i) => uuid('a2', i), OTHER = uuid('a5', 1);
const E = matrix.exams.length, U = matrix.users.length;

// Una fila de usuario POR CASO (usuario x examen x modo de especialidad): el modo 'misma especialidad' depende del examen. Todo set-based.
const sql = [];
sql.push('begin;', 'set local session_replication_role = replica;');
sql.push('create temp table pe(e int, kind text, free bool, demo bool);', 'create temp table pc(e int, r bool, z bool, i bool, d bool);',
  'create temp table pu(u int, es bool, st text, fin timestamptz);', 'create temp table pg(u int, motivo text, f timestamptz, t timestamptz);');
sql.push(`insert into pe values ${matrix.exams.map((e, i) => `(${i}, ${q(e.kind)}, ${e.free}, ${e.demo})`).join(',')};`);
const caps = matrix.exams.flatMap((e, i) => (e.caps ? [`(${i}, ${e.caps.join(', ')})`] : []));
sql.push(`insert into pc values ${caps.join(',')};`);
const prem = matrix.users.flatMap((u, i) => (u.premium ? [`(${i}, ${u.premium.esPremium}, ${q(u.premium.status)}, ${q(u.premium.endsAt)}::timestamptz)`] : []));
sql.push(`insert into pu values ${prem.join(',')};`);
const grants = matrix.users.flatMap((u, i) => u.grants.map((g) => `(${i}, ${q(g.motivo)}, ${q(g.from)}::timestamptz, ${q(g.until)}::timestamptz)`));
sql.push(`insert into pg values ${grants.join(',')};`);
const exam = (e) => `('${EXAM(0).slice(0, 8)}-0000-4000-8000-' || lpad(${e}::text, 12, '0'))::uuid`;
const spec = (e) => `('a1000000-0000-4000-8000-' || lpad(${e}::text, 12, '0'))::uuid`;
const usr = `('a3000000-0000-4000-8000-' || lpad(((c.u * ${E} + c.e) * 3 + c.m)::text, 12, '0'))::uuid`;
sql.push(`create temp table cs as select u, e, m from generate_series(0, ${U - 1}) u, generate_series(0, ${E - 1}) e, generate_series(0, 2) m;`);
sql.push(`insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, demo_disponible, activo, publicado_en)
  select ${exam('p.e')}, 'PAR-' || p.e, ${spec('p.e')}, ${q(uuid('a4', 1))}, 'parity', p.kind, case when p.free then 'gratuito' else 'premium' end, p.demo, true, now() from pe p;`);
sql.push(`insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos) select ${spec('p.e')}, p.r, p.z, p.i, p.d from pc p;`);
sql.push(`insert into public.perfiles_usuario (usuario_id, especialidad_principal_id) select ${usr}, case c.m when 0 then null when 1 then ${spec('c.e')} else ${q(OTHER)}::uuid end from cs c;`);
sql.push(`insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, fin_en) select ${usr}, p.es, p.st, p.fin from cs c join pu p on p.u = c.u;`);
sql.push(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) select ${usr}, ${exam('c.e')}, g.motivo, g.f, g.t from cs c join pg g on g.u = c.u;`);
sql.push(`select r.u || '|' || string_agg(left(r.state, 1), '' order by r.e, r.m)
  from (select c.u, c.e, c.m, v.state from cs c, lateral private.exam_access_verdict(${usr}, ${exam('c.e')}, ${q(matrix.now)}::timestamptz) v) r
  group by r.u order by r.u;`);
sql.push('rollback;');

const raw = execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
{ input: sql.join('\n'), encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

const got = new Map(raw.split(/\r?\n/).filter((l) => l.includes('|')).map((l) => [Number(l.split('|')[0]), l.split('|')[1]]));
let cases = 0, diffs = 0;
const first = [];
matrix.expected.forEach((expected, u) => {
  const actual = got.get(u) ?? '';
  for (let e = 0; e < expected.length; e++) {
    cases++;
    if (actual[e] !== expected[e]) {
      diffs++;
      if (first.length < 8) first.push({ user: matrix.users[u], exam: matrix.exams[e], kotlin: expected[e], sql: actual[e] ?? '(none)' });
    }
  }
});
console.log(`VERDICT_PARITY cases=${cases} users=${matrix.users.length} exams=${matrix.exams.length} differences=${diffs}`);
if (diffs) {
  for (const d of first) console.log(JSON.stringify(d));
  process.exit(1);
}
console.log('VERDICT_PARITY OK: la funcion SQL y EntitlementPolicy coinciden en toda la matriz');
