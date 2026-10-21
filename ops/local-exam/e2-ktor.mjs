#!/usr/bin/env node
// E2 (exam security, LOCAL ONLY): runs the installDist Ktor of THIS worktree against the shared local stack on its own port
// (default 8090) so its in-memory rate limiter and its log are isolated from other users of the stack (:8089).
// Env comes from the stack's .local/ktor.env (E2_STACK_LOCAL, default: the E1 worktree); values are never printed.
//   node ops/local-exam/e2-ktor.mjs [port]   (stdout/stderr -> the caller; redirect to a file to audit the log)
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('e2-ktor');

// Arranca un Ktor / ejecuta sondas contra el stack compartido: falla (exit 3) ANTES de nada si STACK_LOCK es de otro.
requireStackLock('e2-ktor.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const stackLocal = process.env.E2_STACK_LOCAL ?? 'C:\\Users\\franr\\AndroidStudioProjects\\OposiControl-ExamServer\\ops\\local-exam\\.local';
const env = { ...process.env };
for (const line of fs.readFileSync(path.join(stackLocal, 'ktor.env'), 'utf8').split(/\r?\n/)) {
  const i = line.indexOf('=');
  if (i > 0) env[line.slice(0, i)] = line.slice(i + 1);
}
env.PORT = process.argv[2] ?? '8090';
const lib = path.join(repo, 'backend', 'ktor', 'build', 'install', 'ktor', 'lib', '*');
const child = spawn('java', ['-cp', lib, 'com.oposicontrol.backend.ApplicationKt'], { cwd: repo, env, stdio: 'inherit' });
child.on('exit', (code) => process.exit(code ?? 0));
