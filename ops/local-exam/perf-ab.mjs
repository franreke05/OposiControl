#!/usr/bin/env node
// PERF A/B (LOCAL ONLY, E5 PERF finding): two Ktor builds side by side on the SAME stack, same env, interleaved requests, so the
// only variable is the build. Writes ONLY attempts of the e2e user XA (sql/94). Never touches the app Ktor (ktorBot port) or A/B/P.
//   STACK_HOLDER=E1 node ops/local-exam/perf-ab.mjs <installDirA> <installDirB> [iterations=25]
//   installDir = <worktree>/backend/ktor/build/install/ktor  (A and B on the perfA / perfB ports of stack-config.mjs)
// Output: p50/p95 per operation and build (client wall time) + JSON in .local/perf-ab.json.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';
import { LOCAL_DIR, PORTS } from './stack-config.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('perf-ab');

// Arranca Ktor y escribe intentos de XA en el stack compartido: falla (exit 3) ANTES de nada si STACK_LOCK es de otro.
requireStackLock('perf-ab.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const local = LOCAL_DIR;
const [dirA, dirB] = process.argv.slice(2, 4);
const iterations = Number(process.argv[4] ?? 25);
if (!dirA || !dirB) { console.error('uso: perf-ab.mjs <installDirA> <installDirB> [iteraciones]'); process.exit(2); }
// ATTEMPT_START es 30/300 s por usuario y proceso (limitador en memoria de cada Ktor): una apertura por iteracion y build.
if (iterations > 28) { console.error('maximo 28 iteraciones (ATTEMPT_START 30/300 s)'); process.exit(2); }

const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const env = { ...process.env, ...Object.fromEntries(fs.readFileSync(path.join(local, 'ktor.env'), 'utf8').split(/\r?\n/)
  .filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)])) };
const NORMAL = 'e0000000-0000-4000-8000-000000000001';
const builds = [{ name: 'A', dir: dirA, port: PORTS.perfA }, { name: 'B', dir: dirB, port: PORTS.perfB }];

const children = [];
const stopAll = () => children.forEach((c) => { try { process.platform === 'win32' ? spawn('taskkill', ['/PID', String(c.pid), '/T', '/F']) : c.kill(); } catch { /* ya parado */ } });
process.on('exit', stopAll);
for (const b of builds) {
  const log = fs.openSync(path.join(local, `perf-ab-${b.name}.log`), 'w');
  const child = spawn('java', ['-cp', path.join(b.dir, 'lib', '*'), 'com.oposicontrol.backend.ApplicationKt'],
    { env: { ...env, PORT: String(b.port) }, stdio: ['ignore', log, log], windowsHide: true });
  children.push(child);
  b.url = `http://127.0.0.1:${b.port}`;
  let ok = false;
  for (let i = 0; i < 90 && !ok; i++) { try { ok = (await fetch(`${b.url}/health/live`)).ok; } catch { await new Promise((r) => setTimeout(r, 1000)); } }
  if (!ok) { console.error(`build ${b.name} no arranca (mira ${path.join(local, `perf-ab-${b.name}.log`)})`); process.exit(1); }
}

async function call(b, method, p, body, key) {
  const headers = { Authorization: `Bearer ${tokens.XA}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const t0 = performance.now();
  const r = await fetch(b.url + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  const ms = performance.now() - t0;
  let json = null; try { json = JSON.parse(text); } catch { /* vacio */ }
  return { status: r.status, json, ms };
}
const samples = {};
const rec = (b, op, r, okStatuses) => {
  if (!okStatuses.includes(r.status)) throw new Error(`${b.name} ${op} -> ${r.status} ${JSON.stringify(r.json)?.slice(0, 160)}`);
  ((samples[op] ??= {})[b.name] ??= []).push(r.ms);
};

// Calentamiento identico (JIT, pools, JWKS): 5 lecturas por build, no se miden.
for (const b of builds) for (let i = 0; i < 5; i++) await call(b, 'GET', `/api/v1/app/examenes/${NORMAL}`);

for (let i = 0; i < iterations; i++) {
  for (const b of (i % 2 === 0 ? builds : [...builds].reverse())) {
    const start = await call(b, 'POST', `/api/v1/app/examenes/${NORMAL}/intentos`, {}, crypto.randomUUID());
    rec(b, 'start', start, [200, 201]);
    const id = start.json.intento.id;
    rec(b, 'get', await call(b, 'GET', `/api/v1/app/intentos/${id}`), [200]);
    const page = await call(b, 'GET', `/api/v1/app/intentos/${id}/preguntas?limit=20`);
    rec(b, 'question_page', page, [200]);
    const q = page.json.items[0];
    const ans = await call(b, 'PUT', `/api/v1/app/intentos/${id}/respuestas/${q.preguntaId}`,
      { opcionSeleccionadaId: q.opciones[0].id, tiempoRespuestaMs: 1500, baseRevision: q.respuesta?.revision ?? 0 }, crypto.randomUUID());
    rec(b, 'answer', ans, [200, 409]);
    rec(b, 'd2_02_detail', await call(b, 'GET', `/api/v1/app/examenes/${NORMAL}`), [200]);
    rec(b, 'd2_01_list', await call(b, 'GET', '/api/v1/app/examenes?limit=20'), [200]);
    rec(b, 'abandon', await call(b, 'POST', `/api/v1/app/intentos/${id}/abandonar`, { posicionPregunta: 1, motivo: 'otro' }), [204, 200]);
  }
}

const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
const report = {};
for (const [op, byBuild] of Object.entries(samples)) {
  report[op] = Object.fromEntries(Object.entries(byBuild).map(([n, xs]) => [n, { n: xs.length, p50: +pct(xs, 0.5).toFixed(1), p95: +pct(xs, 0.95).toFixed(1) }]));
  console.log(`${op.padEnd(14)} A p50 ${report[op].A.p50} p95 ${report[op].A.p95} | B p50 ${report[op].B.p50} p95 ${report[op].B.p95}`);
}
fs.writeFileSync(path.join(local, 'perf-ab.json'), JSON.stringify({ at: new Date().toISOString(), A: dirA, B: dirB, iterations, report }, null, 2));
stopAll();
process.exit(0);
