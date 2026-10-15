#!/usr/bin/env node
// GATE-1 (E2): paridad Kotlin <-> SQL del veredicto de acceso a un examen, sobre una matriz GENERADA (28.560 casos).
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
const SPEC = (i) => uuid('a1', i), EXAM = (i) => uuid('a2', i), USER = (i) => uuid('a3', i);

const sql = [];
sql.push('begin;', "set local session_replication_role = replica;");
// examenes + capacidades (una especialidad por examen: sin colisiones)
const examRows = matrix.exams.map((e, i) => `(${q(EXAM(i))}, ${q('PAR-' + i)}, ${q(SPEC(i))}, ${q(uuid('a4', 1))}, 'parity', ${q(e.kind)}, ${q(e.free ? 'gratuito' : 'premium')}, ${e.demo}, true, now())`);
sql.push(`insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, demo_disponible, activo, publicado_en) values ${examRows.join(',')};`);
const capRows = matrix.exams.flatMap((e, i) => (e.caps ? [`(${q(SPEC(i))}, ${e.caps.join(', ')})`] : []));
sql.push(`insert into public.capacidades_especialidad (especialidad_id, permite_rafagas, permite_razonados, permite_inmersivos, permite_demos) values ${capRows.join(',')};`);
// premium por usuario
const premRows = matrix.users.flatMap((u, i) => (u.premium ? [`(${q(USER(i))}, ${u.premium.esPremium}, ${q(u.premium.status)}, ${q(u.premium.endsAt)})`] : []));
sql.push(`insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, fin_en) values ${premRows.join(',')};`);
// concesiones: las mismas para TODOS los examenes de cada usuario
const grantRows = matrix.users.flatMap((u, i) => u.grants.map((g) => `(${i}, ${q(g.motivo)}, ${q(g.from)}::timestamptz, ${q(g.until)}::timestamptz)`));
sql.push(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta)
  select ('a3000000-0000-4000-8000-' || lpad(g.u::text, 12, '0'))::uuid, ('a2000000-0000-4000-8000-' || lpad(e.i::text, 12, '0'))::uuid, g.motivo, g.f, g.t
  from (values ${grantRows.join(',')}) g(u, motivo, f, t), generate_series(0, ${matrix.exams.length - 1}) e(i);`);
// veredictos SQL: una cadena por usuario, una letra por examen (en orden)
sql.push(`select r.u || '|' || string_agg(left(r.state, 1), '' order by r.e)
  from (select u.u, e.e, v.state
          from generate_series(0, ${matrix.users.length - 1}) u(u), generate_series(0, ${matrix.exams.length - 1}) e(e),
               lateral private.exam_access_verdict(('a3000000-0000-4000-8000-' || lpad(u.u::text, 12, '0'))::uuid, ('a2000000-0000-4000-8000-' || lpad(e.e::text, 12, '0'))::uuid, ${q(matrix.now)}::timestamptz) v) r
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
