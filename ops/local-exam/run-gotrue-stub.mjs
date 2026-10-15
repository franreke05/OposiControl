#!/usr/bin/env node
// V1 (J6) LOCAL ONLY: arranca / para el stub de GoTrue (gotrue-stub.mjs) en segundo plano.
//   STACK_HOLDER=E1 APP_ENV=local node ops/local-exam/run-gotrue-stub.mjs --detach
//   STACK_HOLDER=E1 APP_ENV=local node ops/local-exam/run-gotrue-stub.mjs --stop
// Aborta ANTES de tocar nada si STACK_LOCK es de otro o si APP_ENV no es exactamente local|test.
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('run-gotrue-stub.mjs');
if (process.env.APP_ENV !== 'local' && process.env.APP_ENV !== 'test') {
  console.error('run-gotrue-stub: MUST_NOT_START_OUTSIDE_LOCAL: APP_ENV must be exactly "local" or "test".');
  process.exit(4);
}
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const pidFile = path.join(local, 'gotrue-stub.pid');
const stop = () => {
  if (!fs.existsSync(pidFile)) return console.log('no hay stub de GoTrue en segundo plano');
  const pid = Number(fs.readFileSync(pidFile, 'utf8'));
  try { process.platform === 'win32' ? execFileSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' }) : process.kill(pid); } catch { /* ya parado */ }
  fs.rmSync(pidFile, { force: true });
  console.log(`stub de GoTrue ${pid} parado`);
};
if (process.argv.includes('--stop')) { stop(); process.exit(0); }
if (!process.argv.includes('--detach')) { console.error('uso: run-gotrue-stub.mjs --detach | --stop'); process.exit(2); }
stop();
const log = fs.openSync(path.join(local, 'gotrue-stub.log'), 'a');
const child = spawn(process.execPath, [path.join(here, 'gotrue-stub.mjs')], { env: process.env, stdio: ['ignore', log, log], detached: true, windowsHide: true });
child.unref();
fs.writeFileSync(pidFile, String(child.pid));
for (let i = 0; i < 30; i++) {
  try { if ((await fetch('http://127.0.0.1:9999/__local/health')).ok) { console.log(`stub de GoTrue (LOCAL) en segundo plano (pid ${child.pid}) en 127.0.0.1:9999`); process.exit(0); } } catch { /* arrancando */ }
  await new Promise((r) => setTimeout(r, 500));
}
console.error('el stub de GoTrue no respondio en 15 s; mira .local/gotrue-stub.log');
process.exit(1);
