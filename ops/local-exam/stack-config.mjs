// The ONE config source of the local exam stack (E5 QA harness isolation). Every script under ops/local-exam/ takes from here:
//   - the Compose project name   OPOSI_STACK_PROJECT     (default `oposi-exam-local`, the name in docker-compose.yaml)
//   - its own `.local` directory  OPOSI_STACK_LOCAL_DIR   (default ops/local-exam/.local; a relative value resolves against ops/local-exam/)
//   - its published host ports    <localDir>/ports.json   (gateway, db, ktorBot, ktorControl, gotrueStub, mail, perfA, perfB)
// The db container is never named by hand: it is reached with `docker compose -p <project> ... exec db` (composeArgs / dbPsqlArgs), and the
// only place that needs a raw name (db-guard: inspect/commit) derives it from the project (`<project>-db-1`).
//
// ISOLATED COPY: with OPOSI_STACK_PROJECT set to anything else, the config REFUSES (exit 4) to load when the copy would share the default
// stack's `.local` directory or any of its ports (the default ports.json, at OPOSI_DEFAULT_STACK_LOCAL_DIR or ops/local-exam/.local, plus the
// built-in defaults). A copy without a ports.json yet resolves to the defaults + ALT_OFFSET until `up.mjs` picks and persists free ones.
// The resolved project/ports are printed (stderr) whenever a script loads this module.
//
// Why ports.json at all (E1-V1-PORT-FIX.md): Windows reserves dynamic TCP port ranges (`netsh interface ipv4 show excludedportrange protocol=tcp`)
// that change between boots and can swallow a published port. `ensurePorts()` (up.mjs) keeps an existing ports.json while its ports are outside
// every excluded range, so a running stack never moves under its users.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const HERE = path.dirname(fileURLToPath(import.meta.url));
export const COMPOSE_FILE = path.join(HERE, 'docker-compose.yaml');
export const DEFAULT_PROJECT = 'oposi-exam-local';
export const DEFAULT_LOCAL_DIR = path.join(HERE, '.local');
/** The default stack's ports (gateway/db are only the preferred start: the real ones live in its ports.json). */
export const DEFAULT_PORTS = Object.freeze({ gateway: 55321, db: 55432, ktorBot: 8089, ktorControl: 8090, gotrueStub: 9999, mail: 8025, perfA: 8091, perfB: 8092 });
export const PORT_KEYS = Object.freeze(Object.keys(DEFAULT_PORTS));
/** Kept for older callers of ports.mjs. */
export const PREFERRED = { gateway: DEFAULT_PORTS.gateway, db: DEFAULT_PORTS.db };
/** Where an isolated copy starts looking (default + this) before it has its own ports.json. */
export const ALT_OFFSET = 100;

const PROJECT_RE = /^[a-z0-9][a-z0-9_-]*$/;
const sameDir = (a, b) => path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase();

/** ports.json of a `.local` dir: only integer entries of the known keys, or null when there is no readable file. */
export function readPortsFile(localDir) {
  try {
    const raw = JSON.parse(fs.readFileSync(path.join(localDir, 'ports.json'), 'utf8'));
    const out = {};
    for (const k of PORT_KEYS) if (Number.isInteger(raw[k])) out[k] = raw[k];
    return out;
  } catch {
    return null;
  }
}

const portOf = (url) => { try { const u = new URL(url); return Number(u.port || (u.protocol === 'https:' ? 443 : 80)); } catch { return null; } };

/** Pure resolution (no exit, no print): what a script would talk to under `env`. */
export function resolveStack(env = process.env) {
  const project = env.OPOSI_STACK_PROJECT || DEFAULT_PROJECT;
  const isDefault = project === DEFAULT_PROJECT;
  const localDir = env.OPOSI_STACK_LOCAL_DIR ? path.resolve(HERE, env.OPOSI_STACK_LOCAL_DIR) : DEFAULT_LOCAL_DIR;
  const defaultLocalDir = env.OPOSI_DEFAULT_STACK_LOCAL_DIR ? path.resolve(HERE, env.OPOSI_DEFAULT_STACK_LOCAL_DIR) : DEFAULT_LOCAL_DIR;
  const fromFile = readPortsFile(localDir);
  const fallback = (k) => (isDefault ? DEFAULT_PORTS[k] : DEFAULT_PORTS[k] + ALT_OFFSET);
  const ports = Object.fromEntries(PORT_KEYS.map((k) => [k, fromFile?.[k] ?? fallback(k)]));
  const urls = {
    gateway: `http://localhost:${ports.gateway}`,
    ktorBot: env.KTOR_URL ?? `http://127.0.0.1:${ports.ktorBot}`,
    ktorControl: env.ADMIN_KTOR_URL ?? `http://127.0.0.1:${ports.ktorControl}`,
    gotrueStub: `http://127.0.0.1:${ports.gotrueStub}`,
    mail: env.MAILPIT_URL ?? `http://127.0.0.1:${ports.mail}`,
  };
  return {
    project, isDefault, localDir, defaultLocalDir, portsFromFile: fromFile !== null, ports, urls,
    // E2 harnesses historically read the env of ANOTHER worktree's stack; now they default to this stack's own dir.
    e2LocalDir: env.E2_STACK_LOCAL ? path.resolve(HERE, env.E2_STACK_LOCAL) : localDir,
    dbContainer: `${project}-db-1`,
  };
}

/** Every reason an isolated (non-default) resolution would reach the default stack. Empty for the default project. */
export function isolationProblems(stack) {
  const problems = [];
  if (!PROJECT_RE.test(stack.project)) problems.push(`invalid compose project name "${stack.project}" (lowercase letters, digits, - and _)`);
  if (stack.isDefault) return problems;
  if (sameDir(stack.localDir, stack.defaultLocalDir) || sameDir(stack.localDir, DEFAULT_LOCAL_DIR)) problems.push(`localDir ${stack.localDir} is the default stack's .local`);
  if (sameDir(stack.e2LocalDir, stack.defaultLocalDir) || sameDir(stack.e2LocalDir, DEFAULT_LOCAL_DIR)) problems.push(`E2_STACK_LOCAL ${stack.e2LocalDir} is the default stack's .local`);
  const taken = new Map();
  for (const [k, v] of Object.entries(DEFAULT_PORTS)) taken.set(v, `default ${k}`);
  for (const [k, v] of Object.entries(readPortsFile(stack.defaultLocalDir) ?? {})) taken.set(v, `default ${k} (ports.json)`);
  const seen = new Map();
  for (const [k, v] of Object.entries(stack.ports)) {
    if (taken.has(v)) problems.push(`port ${k}=${v} collides with ${taken.get(v)}`);
    if (seen.has(v)) problems.push(`port ${k}=${v} is also ${seen.get(v)}`);
    seen.set(v, k);
  }
  for (const [k, u] of Object.entries(stack.urls)) {
    const p = portOf(u);
    if (p !== null && taken.has(p)) problems.push(`url ${k}=${u} points at ${taken.get(p)}`);
  }
  return problems;
}

export const describeStack = (s) => `[stack] project=${s.project}${s.isDefault ? ' (default)' : ' (ISOLATED)'} localDir=${s.localDir} `
  + `ports=${PORT_KEYS.map((k) => `${k}:${s.ports[k]}`).join(',')}${s.portsFromFile ? '' : ' (no ports.json yet: fallback)'}`;

// ---------- the resolution every script uses (validated at load) ----------
export const STACK = resolveStack();
{
  console.error(describeStack(STACK));
  const problems = isolationProblems(STACK);
  if (problems.length) {
    console.error('STACK CONFIG: refusing to run, this copy is not isolated from the default stack:\n  - ' + problems.join('\n  - '));
    process.exit(4);
  }
}
export const PROJECT = STACK.project;
export const LOCAL_DIR = STACK.localDir;
export const PORTS = STACK.ports;
export const GATEWAY_PORT = PORTS.gateway;
export const DB_PORT = PORTS.db;
/** `http://localhost:<gateway>`: also the `iss` base of every local token (`<GATEWAY>/auth/v1`) and Ktor's SUPABASE_URL. */
export const GATEWAY = STACK.urls.gateway;
export const KTOR_URL = STACK.urls.ktorBot;
export const ADMIN_KTOR_URL = STACK.urls.ktorControl;
export const GOTRUE_STUB_URL = STACK.urls.gotrueStub;
export const MAIL_URL = STACK.urls.mail;
export const DB_CONTAINER = STACK.dbContainer;
export const E2_LOCAL_DIR = STACK.e2LocalDir;

/** `docker` args up to (not including) the compose subcommand: always pinned to this project, env file and compose file. */
export const composeArgs = (s = STACK) => ['compose', '-p', s.project, '--env-file', path.join(s.localDir, 'compose.env'), '-f', COMPOSE_FILE];
/** `docker` args for `psql <psqlArgs...>` inside this project's db service (stdin attached, no TTY). */
export const dbPsqlArgs = (psqlArgs, s = STACK) => [...composeArgs(s), 'exec', '-T', 'db', 'psql', ...psqlArgs];

// ---------- port picking (up.mjs) ----------
/** [[from, to], ...] TCP ranges Windows excludes right now (empty off Windows or if netsh is unavailable). */
export function excludedRanges() {
  if (process.platform !== 'win32') return [];
  try {
    const out = execFileSync('netsh', ['interface', 'ipv4', 'show', 'excludedportrange', 'protocol=tcp'], { encoding: 'utf8', timeout: 20000 });
    return [...out.matchAll(/^\s*(\d+)\s+(\d+)/gm)].map((m) => [Number(m[1]), Number(m[2])]);
  } catch {
    return [];
  }
}
export const isExcluded = (port, ranges = excludedRanges()) => ranges.some(([a, b]) => port >= a && port <= b);

const canBind = (port) => new Promise((resolve) => {
  const s = net.createServer();
  s.once('error', () => resolve(false));
  s.listen({ port, host: '127.0.0.1' }, () => s.close(() => resolve(true)));
});

/**
 * Used by up.mjs: validates (and if needed re-picks) this stack's ports and persists them in <localDir>/ports.json. Returns { ...ports, changed }.
 * Default stack: gateway/db as before (kept unless excluded); the process ports keep their fixed defaults (they may be bound by a running Ktor/stub).
 * Isolated copy: every port is kept only if outside the excluded ranges and the default stack's ports; otherwise the first free candidate from
 * default + ALT_OFFSET is picked.
 */
export async function ensurePorts(s = STACK) {
  const ranges = excludedRanges();
  const current = readPortsFile(s.localDir) ?? {};
  const forbidden = new Set(s.isDefault ? [] : [...Object.values(DEFAULT_PORTS), ...Object.values(readPortsFile(s.defaultLocalDir) ?? {})]);
  const taken = new Set();
  const chosen = {};
  let changed = false;
  const pick = async (start) => {
    for (let p = start; p < 65000; p++) {
      if (taken.has(p) || forbidden.has(p) || isExcluded(p, ranges) || !(await canBind(p))) continue;
      return p;
    }
    throw new Error('no free local port found');
  };
  for (const k of PORT_KEYS) {
    const fixed = s.isDefault && k !== 'gateway' && k !== 'db';
    const have = current[k];
    let port;
    if (Number.isInteger(have) && !taken.has(have) && !forbidden.has(have) && (fixed || !isExcluded(have, ranges))) port = have;
    else if (fixed) port = DEFAULT_PORTS[k];
    else port = await pick(s.isDefault ? DEFAULT_PORTS[k] : DEFAULT_PORTS[k] + ALT_OFFSET);
    if (port !== have) changed = true;
    taken.add(port);
    chosen[k] = port;
  }
  // default stack: a ports.json that only lacked the process ports is completed without reporting a gateway/db move
  const moved = changed && (chosen.gateway !== current.gateway || chosen.db !== current.db);
  if (changed) {
    fs.mkdirSync(s.localDir, { recursive: true });
    fs.writeFileSync(path.join(s.localDir, 'ports.json'), JSON.stringify(chosen, null, 2));
  }
  Object.assign(s.ports, chosen);
  return { ...chosen, changed: moved };
}
