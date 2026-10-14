#!/usr/bin/env node
// EX16: arranca el Ktor de ESTE worktree contra el stack local (`up.mjs` antes). Construye installDist y lo ejecuta con
// .local/ktor.env (APP_ENV=local, SERVER_PROFILE=bot, puerto 8089).
//   node ops/local-exam/run-ktor.mjs              en primer plano (Ctrl+C lo para)
//   node ops/local-exam/run-ktor.mjs --detach     en segundo plano, independiente de la terminal: log en .local/ktor.log,
//                                                 pid en .local/ktor.pid; espera a /health/live antes de volver
//   node ops/local-exam/run-ktor.mjs --stop       para el Ktor arrancado con --detach
//   --no-build                                    no recompila (usa el installDist que ya hay)
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const local = path.join(here, '.local');
const pidFile = path.join(local, 'ktor.pid');
const windows = process.platform === 'win32';

if (process.argv.includes('--stop')) {
  if (!fs.existsSync(pidFile)) { console.log('no hay Ktor en segundo plano'); process.exit(0); }
  const pid = Number(fs.readFileSync(pidFile, 'utf8'));
  try {
    if (windows) execFileSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' }); else process.kill(pid);
  } catch { /* ya parado */ }
  fs.rmSync(pidFile, { force: true });
  console.log(`Ktor ${pid} parado`);
  process.exit(0);
}

const envFile = path.join(local, 'ktor.env');
if (!fs.existsSync(envFile)) throw new Error('run `node ops/local-exam/up.mjs` first');
const env = { ...process.env };
for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
  const i = line.indexOf('=');
  if (i > 0) env[line.slice(0, i)] = line.slice(i + 1);
}
if (!process.argv.includes('--no-build')) {
  execFileSync(path.join(repo, windows ? 'gradlew.bat' : 'gradlew'), [':backend:ktor:installDist', '-q', '--console=plain'], { cwd: repo, stdio: 'inherit', shell: windows });
}
// java -cp lib/* (el .bat de installDist supera el limite de linea de cmd.exe en Windows).
const lib = path.join(repo, 'backend', 'ktor', 'build', 'install', 'ktor', 'lib', '*');
const args = ['-cp', lib, 'com.oposicontrol.backend.ApplicationKt'];

if (process.argv.includes('--detach')) {
  const log = fs.openSync(path.join(local, 'ktor.log'), 'a');
  const child = spawn('java', args, { cwd: repo, env, stdio: ['ignore', log, log], detached: true, windowsHide: true });
  child.unref();
  fs.writeFileSync(pidFile, String(child.pid));
  const url = `http://${env.HOST ?? '127.0.0.1'}:${env.PORT ?? '8089'}/health/live`;
  for (let i = 0; i < 60; i++) {
    try { if ((await fetch(url)).ok) { console.log(`Ktor en segundo plano (pid ${child.pid}): ${url} ok; log ${path.join(local, 'ktor.log')}`); process.exit(0); } } catch { /* arrancando */ }
    await new Promise((r) => setTimeout(r, 1000));
  }
  console.error(`Ktor no respondio en 60 s; mira ${path.join(local, 'ktor.log')}`);
  process.exit(1);
}

const child = spawn('java', args, { cwd: repo, env, stdio: 'inherit' });
child.on('exit', (code) => process.exit(code ?? 0));
