#!/usr/bin/env node
// Self-test of stack-config.mjs (E5 QA harness isolation). Static/unit only: never calls Docker, never touches a running stack or the real .local
// (every `.local` used here is a temp dir). Exit 0 = all PASS, 1 = some FAIL.
//   node ops/local-exam/stack-selftest.mjs
//   1. default resolution: project oposi-exam-local, ports from the default ports.json, compose pinned to that project
//   2. alternative project: no default container name / project / port anywhere in its resolution (with and without its own ports.json)
//   3. the guard refuses (exit 4) an alternative copy that shares a default port, the default .local, or a URL override pointing at the default stack
//   4. grep: no `oposi-exam-local-db-1`, no literal stack port and no `path.join(here, '.local')` outside stack-config.mjs
//      (a line may opt out only with an explicit `stack-port-check:` justification, e.g. a container-internal port)
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const configUrl = pathToFileURL(path.join(here, 'stack-config.mjs')).href;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'stack-selftest-'));
const defDir = path.join(tmp, 'default-local');
const altDir = path.join(tmp, 'alt-local');
fs.mkdirSync(defDir, { recursive: true });
// the default stack as it is persisted today: only gateway/db, re-picked away from the preferred defaults
fs.writeFileSync(path.join(defDir, 'ports.json'), JSON.stringify({ gateway: 55322, db: 55433 }));

// load the module with a neutral env (default project, default dir = defDir) so its own load-time guard passes
const baseEnv = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(OPOSI_|KTOR_URL$|ADMIN_KTOR_URL$|MAILPIT_URL$|E2_STACK_LOCAL$)/.test(k)));
Object.keys(process.env).forEach((k) => { if (!(k in baseEnv)) delete process.env[k]; });
process.env.OPOSI_STACK_LOCAL_DIR = defDir;
process.env.OPOSI_DEFAULT_STACK_LOCAL_DIR = defDir;
const C = await import(configUrl);

const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : ' :: ' + detail}`); };
const DEFAULT_DB = `${C.DEFAULT_PROJECT}-db-1`;

// ---------- 1. default ----------
const def = C.resolveStack({ OPOSI_STACK_LOCAL_DIR: defDir, OPOSI_DEFAULT_STACK_LOCAL_DIR: defDir });
check('default: project', def.project === C.DEFAULT_PROJECT && def.isDefault, def.project);
check('default: db container derived', def.dbContainer === DEFAULT_DB, def.dbContainer);
check('default: ports from its ports.json', def.ports.gateway === 55322 && def.ports.db === 55433, JSON.stringify(def.ports));
check('default: process ports keep their defaults', def.ports.ktorBot === C.DEFAULT_PORTS.ktorBot && def.ports.ktorControl === C.DEFAULT_PORTS.ktorControl
  && def.ports.gotrueStub === C.DEFAULT_PORTS.gotrueStub && def.ports.mail === C.DEFAULT_PORTS.mail, JSON.stringify(def.ports));
const defArgs = C.dbPsqlArgs(['-U', 'postgres'], def);
check('default: compose pinned to project + its env file', defArgs.slice(0, 3).join(' ') === `compose -p ${C.DEFAULT_PROJECT}` && defArgs.includes(path.join(defDir, 'compose.env'))
  && defArgs.join(' ').includes('exec -T db psql'), defArgs.join(' '));
check('default: no isolation problems', C.isolationProblems(def).length === 0, C.isolationProblems(def).join('; '));

// ---------- 2. alternative project ----------
const ALT = 'oposi-exam-qa';
const altEnv = { OPOSI_STACK_PROJECT: ALT, OPOSI_STACK_LOCAL_DIR: altDir, OPOSI_DEFAULT_STACK_LOCAL_DIR: defDir };
const forbiddenPorts = new Set([...Object.values(C.DEFAULT_PORTS), 55322, 55433]);
function assertAlt(label, alt) {
  const blob = JSON.stringify({ alt, compose: C.composeArgs(alt), psql: C.dbPsqlArgs(['-U', 'postgres'], alt) });
  check(`${label}: project / container`, alt.project === ALT && !alt.isDefault && alt.dbContainer === `${ALT}-db-1`, `${alt.project} ${alt.dbContainer}`);
  check(`${label}: no default container name anywhere`, !blob.includes(DEFAULT_DB), blob);
  check(`${label}: compose never targets the default project`, !C.composeArgs(alt).includes(C.DEFAULT_PROJECT) && C.composeArgs(alt)[2] === ALT, C.composeArgs(alt).join(' '));
  const used = JSON.stringify([C.dbPsqlArgs(['-U', 'postgres'], alt), alt.localDir, alt.e2LocalDir]); // defaultLocalDir is only read, for the guard
  check(`${label}: own .local`, alt.localDir === altDir && !used.includes(JSON.stringify(defDir).slice(1, -1)), used);
  const clash = Object.entries(alt.ports).filter(([, v]) => forbiddenPorts.has(v));
  check(`${label}: no default port`, clash.length === 0, JSON.stringify(clash));
  const urlClash = Object.entries(alt.urls).filter(([, u]) => forbiddenPorts.has(Number(new URL(u).port)));
  check(`${label}: no URL on a default port`, urlClash.length === 0, JSON.stringify(urlClash));
  check(`${label}: guard accepts it`, C.isolationProblems(alt).length === 0, C.isolationProblems(alt).join('; '));
}
assertAlt('alt (no ports.json yet)', C.resolveStack(altEnv));

// ensurePorts picks and persists free, non-default ports for the alternative copy (binds 127.0.0.1 briefly; no Docker)
const picked = await C.ensurePorts(C.resolveStack(altEnv));
check('alt: ensurePorts wrote ports.json', fs.existsSync(path.join(altDir, 'ports.json')), altDir);
check('alt: picked ports avoid the default stack', C.PORT_KEYS.every((k) => !forbiddenPorts.has(picked[k])), JSON.stringify(picked));
check('alt: picked ports are distinct', new Set(C.PORT_KEYS.map((k) => picked[k])).size === C.PORT_KEYS.length, JSON.stringify(picked));
const reread = C.resolveStack(altEnv);
check('alt: resolution reads its own ports.json', reread.portsFromFile && C.PORT_KEYS.every((k) => reread.ports[k] === picked[k]), JSON.stringify(reread.ports));
assertAlt('alt (own ports.json)', reread);

// ---------- 3. guard ----------
const collideDir = path.join(tmp, 'alt-collide');
fs.mkdirSync(collideDir, { recursive: true });
fs.writeFileSync(path.join(collideDir, 'ports.json'), JSON.stringify({ ...picked, gateway: 55322 }));
const colliding = C.resolveStack({ ...altEnv, OPOSI_STACK_LOCAL_DIR: collideDir });
check('guard: port shared with the default ports.json is a problem', C.isolationProblems(colliding).some((p) => p.includes('gateway=55322')), C.isolationProblems(colliding).join('; '));
check('guard: alt sharing the default .local is a problem', C.isolationProblems(C.resolveStack({ ...altEnv, OPOSI_STACK_LOCAL_DIR: defDir })).some((p) => p.includes('localDir')));
check('guard: KTOR_URL at the default app Ktor is a problem',
  C.isolationProblems(C.resolveStack({ ...altEnv, KTOR_URL: `http://127.0.0.1:${C.DEFAULT_PORTS.ktorBot}` })).some((p) => p.includes('ktorBot')));
check('guard: E2_STACK_LOCAL at the default .local is a problem', C.isolationProblems(C.resolveStack({ ...altEnv, E2_STACK_LOCAL: defDir })).some((p) => p.includes('E2_STACK_LOCAL')));
check('guard: invalid project name is a problem', C.isolationProblems(C.resolveStack({ ...altEnv, OPOSI_STACK_PROJECT: 'Bad Name' })).length > 0);

const load = (env) => spawnSync(process.execPath, ['--input-type=module', '-e', `await import(${JSON.stringify(configUrl)}); console.log('LOADED');`],
  { env: { ...baseEnv, ...env }, encoding: 'utf8' });
const refused = load({ ...altEnv, OPOSI_STACK_LOCAL_DIR: collideDir });
check('guard: a script loading the colliding config exits 4 before doing anything', refused.status === 4 && !refused.stdout.includes('LOADED') && refused.stderr.includes('refusing'),
  `status ${refused.status} ${refused.stderr}`);
const ok = load(altEnv);
check('guard: an isolated config loads and prints its project/ports', ok.status === 0 && ok.stdout.includes('LOADED') && ok.stderr.includes(`[stack] project=${ALT} (ISOLATED)`)
  && ok.stderr.includes(`gateway:${picked.gateway}`), `status ${ok.status} ${ok.stderr}`);
const okDefault = load({ OPOSI_STACK_LOCAL_DIR: defDir, OPOSI_DEFAULT_STACK_LOCAL_DIR: defDir });
check('guard: the default config loads and prints its project/ports', okDefault.status === 0 && okDefault.stderr.includes(`[stack] project=${C.DEFAULT_PROJECT} (default)`)
  && okDefault.stderr.includes('gateway:55322'), `status ${okDefault.status} ${okDefault.stderr}`);

// ---------- 4. grep ----------
const SELF = new Set(['stack-config.mjs', 'stack-selftest.mjs']);
const PATTERN = new RegExp(`${DEFAULT_DB}|\\b(${Object.values(C.DEFAULT_PORTS).join('|')})\\b|path\\.join\\(here, '\\.local'\\)`);
const offenders = [];
const walk = (dir) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === '.local' || e.name === 'node_modules' || e.name.startsWith('.local')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { walk(p); continue; }
    if (dir === here && SELF.has(e.name)) continue;
    if (!/\.(mjs|js|cjs|ya?ml|conf|md|sql|json|txt|sh|ps1)$/.test(e.name)) continue;
    fs.readFileSync(p, 'utf8').split(/\r?\n/).forEach((line, i) => {
      if (PATTERN.test(line) && !line.includes('stack-port-check:')) offenders.push(`${path.relative(here, p)}:${i + 1}: ${line.trim().slice(0, 140)}`);
    });
  }
};
walk(here);
check('grep: no hardcoded db container / stack port / .local outside stack-config.mjs', offenders.length === 0, '\n  ' + offenders.join('\n  '));

fs.rmSync(tmp, { recursive: true, force: true });
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} PASS${failed ? `, ${failed} FAIL` : ''}`);
process.exit(failed ? 1 : 0);
