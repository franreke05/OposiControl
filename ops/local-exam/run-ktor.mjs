#!/usr/bin/env node
// EX16: arranca el Ktor de ESTE worktree contra el stack local (`up.mjs` antes). Construye installDist y lo ejecuta con
// .local/ktor.env (APP_ENV=local, SERVER_PROFILE=bot, puerto 8089). Ctrl+C lo para.
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const envFile = path.join(here, '.local', 'ktor.env');
if (!fs.existsSync(envFile)) throw new Error('run `node ops/local-exam/up.mjs` first');
const env = { ...process.env };
for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
  const i = line.indexOf('=');
  if (i > 0) env[line.slice(0, i)] = line.slice(i + 1);
}
const windows = process.platform === 'win32';
if (!process.argv.includes('--no-build')) {
  execFileSync(path.join(repo, windows ? 'gradlew.bat' : 'gradlew'), [':backend:ktor:installDist', '-q', '--console=plain'], { cwd: repo, stdio: 'inherit', shell: windows });
}
// java -cp lib/* (el .bat de installDist supera el limite de linea de cmd.exe en Windows).
const lib = path.join(repo, 'backend', 'ktor', 'build', 'install', 'ktor', 'lib', '*');
const child = spawn('java', ['-cp', lib, 'com.oposicontrol.backend.ApplicationKt'], { cwd: repo, env, stdio: 'inherit' });
child.on('exit', (code) => process.exit(code ?? 0));
