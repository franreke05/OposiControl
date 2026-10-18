// The ONE place of the local stack's published host ports (E1-V1-PORT-FIX.md). Every script reads them from here; nothing hardcodes a port.
//
// Why: Windows reserves dynamic TCP port ranges (`netsh interface ipv4 show excludedportrange protocol=tcp`) that change between boots and can swallow
// the old 54321/54329, so Docker could not publish them. The ports now live in .local/ports.json, written by `up.mjs` (ensurePorts):
// the first free candidate (starting at the preferred defaults, outside the usual 50000-54600 reserved area) that is neither inside an excluded range
// nor already bound by another process. An existing ports.json is kept as long as its ports are still outside every excluded range, so a running
// stack never moves under its users (the containers keep their own published ports until they are recreated).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const portsFile = path.join(here, '.local', 'ports.json');
export const PREFERRED = { gateway: 55321, db: 55432 };

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

function readFile() {
  try {
    const p = JSON.parse(fs.readFileSync(portsFile, 'utf8'));
    if (Number.isInteger(p.gateway) && Number.isInteger(p.db)) return p;
  } catch { /* none yet */ }
  return null;
}

/** Sync read for every consumer: the persisted ports, or the preferred defaults when `up.mjs` has not run yet. */
export const PORTS = readFile() ?? { ...PREFERRED };
export const GATEWAY_PORT = PORTS.gateway;
export const DB_PORT = PORTS.db;
/** `http://localhost:<gateway>`: also the `iss` base of every local token (`<GATEWAY>/auth/v1`) and Ktor's SUPABASE_URL. */
export const GATEWAY = `http://localhost:${GATEWAY_PORT}`;

/** Used by up.mjs: validates (and if needed re-picks) the ports and persists them. Returns { gateway, db, changed }. */
export async function ensurePorts() {
  const ranges = excludedRanges();
  const current = readFile();
  if (current && !isExcluded(current.gateway, ranges) && !isExcluded(current.db, ranges)) return { ...current, changed: false };
  const taken = new Set();
  const pick = async (start) => {
    for (let p = start; p < 65000; p++) {
      if (taken.has(p) || isExcluded(p, ranges) || !(await canBind(p))) continue;
      taken.add(p);
      return p;
    }
    throw new Error('no free local port found');
  };
  const chosen = { gateway: await pick(PREFERRED.gateway), db: await pick(PREFERRED.db) };
  fs.mkdirSync(path.dirname(portsFile), { recursive: true });
  fs.writeFileSync(portsFile, JSON.stringify(chosen, null, 2));
  return { ...chosen, changed: true };
}
