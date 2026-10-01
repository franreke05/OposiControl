#!/usr/bin/env node
// MASTER-SEC-014 / MASTER2-CICD-001: auditoria SCA del grafo RESUELTO (runtimeClasspath) del backend Ktor contra OSV.
// Sin dependencias: solo Node >= 18 (fetch). La entrada es la salida de
//   ./gradlew :backend:ktor:dependencies --configuration runtimeClasspath -q > deps.txt
// (incluye BOMs, alias del catalogo, versiones resueltas y transitivas). Falla (exit 1) si:
//   - alguna coordenada resuelta tiene un aviso OSV no ignorado (scripts/osv-ignore.json, con caducidad),
//   - alguna linea del arbol no se puede interpretar o hay una dependencia sin resolver (FAILED),
//   - faltan coordenadas esperadas (senal de que se audito otra cosa),
//   - OSV no responde tras los reintentos (fail-closed).
// Uso:
//   node scripts/osv-audit.mjs deps.txt [admin-deps.txt ...]   # CI-OC-DEPS: varios volcados se auditan juntos
//   node scripts/osv-audit.mjs --self-test   # parser + comprobacion de que OSV marca log4j-core 2.14.1
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const OSV_BATCH = 'https://api.osv.dev/v1/querybatch';
const OSV_VULN = 'https://api.osv.dev/v1/vulns/';
const TIMEOUT_MS = 20000;
const RETRIES = 3;
const BATCH_SIZE = 100;
const EXPECTED = [
  'ch.qos.logback:logback-classic', 'ch.qos.logback:logback-core', 'org.apache.pdfbox:pdfbox',
  'io.lettuce:lettuce-core', 'com.auth0:java-jwt', 'io.ktor:ktor-server-core', 'io.netty:netty-handler',
];

const SAMPLE = [
  'runtimeClasspath - Runtime classpath of null/main.',
  '+--- io.ktor:ktor-server-core:3.1.0',
  '|    +--- io.ktor:ktor-http:3.1.0 (*)',
  '|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0 -> 2.1.0',
  '+--- io.micrometer:micrometer-bom:1.15.12 (c)',
  '+--- project :domain',
  '\\--- org.foo:bar -> org.baz:bar2:2.0',
].join('\n');

/** Devuelve { coordinates: [{name, version}], errors: [string] } a partir del arbol de `gradle dependencies`. */
export function parseGradleDependencies(text) {
  const found = new Map();
  const errors = [];
  for (const rawLine of text.split(/\r?\n/)) {
    const m = /^[| ]*[+\\]--- (.+)$/.exec(rawLine);
    if (!m) continue;
    let body = m[1].trim();
    if (body.startsWith('project ')) continue;
    if (/\bFAILED\b/.test(body)) { errors.push(`sin resolver: ${rawLine.trim()}`); continue; }
    if (/\(c\)\s*$/.test(body)) continue; // restriccion (BOM/constraint): no es un artefacto en el classpath
    body = body.replace(/\s+\((\*|n)\)\s*$/, '');
    const [left, right] = body.split(' -> ').map((s) => s.trim());
    const lm = /^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::(.+))?$/.exec(left);
    if (!lm) { errors.push(`linea no interpretable: ${rawLine.trim()}`); continue; }
    let name = `${lm[1]}:${lm[2]}`;
    let version = lm[3];
    if (right !== undefined) {
      const rm = /^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):(\S+)$/.exec(right);
      if (rm) { name = `${rm[1]}:${rm[2]}`; version = rm[3]; } else version = right;
    }
    if (version) version = version.replace(/^\{.*?\}\s*/, '').trim();
    if (!version || /[{}\[\]()]/.test(version)) { errors.push(`version no resuelta: ${rawLine.trim()}`); continue; }
    found.set(`${name}@${version}`, { name, version });
  }
  return { coordinates: [...found.values()], errors };
}

/** CI-OC-DEPS: varios volcados (backend runtimeClasspath + cliente admin jvmRuntimeClasspath) en una sola auditoria.
 *  Une y deduplica las coordenadas; un volcado sin ninguna coordenada es un error (se audito algo vacio). */
export function collectCoordinates(dumps) {
  const found = new Map();
  const errors = [];
  for (const { label, text } of dumps) {
    const parsed = parseGradleDependencies(text);
    errors.push(...parsed.errors.map((e) => `${label}: ${e}`));
    if (parsed.coordinates.length === 0) errors.push(`${label}: el volcado no contiene ninguna coordenada resuelta`);
    for (const c of parsed.coordinates) found.set(`${c.name}@${c.version}`, c);
  }
  return { coordinates: [...found.values()], errors };
}

async function osvRequest(url, init = {}) {
  let last;
  for (let attempt = 1; attempt <= RETRIES; attempt++) {
    try {
      const response = await fetch(url, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
      if (response.ok) return await response.json();
      last = new Error(`OSV respondio ${response.status}`);
      if (response.status < 500 && response.status !== 429) break;
    } catch (error) { last = error; }
    await new Promise((resolve) => setTimeout(resolve, attempt * 1500));
  }
  throw new Error(`OSV no disponible tras ${RETRIES} intentos: ${last?.message ?? last}`);
}

const post = (body) => osvRequest(OSV_BATCH, { method: 'POST', body: JSON.stringify(body) });

/** MASTER4-CICD-002: /v1/querybatch devuelve SOLO {id, modified} (sin aliases). Los alias (CVE <-> GHSA) salen de
 *  GET /v1/vulns/{id}; se resuelven solo cuando hay ignoradas activas (la lista vacia no hace llamadas extra). */
export async function fetchAliases(id) {
  const vuln = await osvRequest(OSV_VULN + encodeURIComponent(id));
  return Array.isArray(vuln?.aliases) ? vuln.aliases : [];
}

/** Devuelve, por coordenada, la lista de avisos { id, aliases } (sigue next_page_token). MASTER3-CICD-008: los alias
 *  (CVE-..., GHSA-...) se conservan para que una ignorada por CVE tambien suprima el aviso publicado con id GHSA. */
export async function queryAll(coordinates, { resolveAliases = false } = {}) {
  const ids = coordinates.map(() => new Map());
  for (let start = 0; start < coordinates.length; start += BATCH_SIZE) {
    let pending = coordinates.slice(start, start + BATCH_SIZE).map((c, i) => ({ index: start + i, token: undefined, c }));
    while (pending.length > 0) {
      const queries = pending.map(({ c, token }) => ({ package: { name: c.name, ecosystem: 'Maven' }, version: c.version, ...(token ? { page_token: token } : {}) }));
      const { results } = await post({ queries });
      if (!Array.isArray(results) || results.length !== queries.length) throw new Error('respuesta OSV inesperada');
      const next = [];
      results.forEach((r, i) => {
        for (const v of r.vulns ?? []) ids[pending[i].index].set(v.id, { id: v.id, aliases: Array.isArray(v.aliases) ? v.aliases : [] });
        if (r.next_page_token) next.push({ ...pending[i], token: r.next_page_token });
      });
      pending = next;
    }
  }
  if (resolveAliases) {
    const cache = new Map();
    for (const m of ids) {
      for (const v of m.values()) {
        if (v.aliases.length > 0) continue;
        if (!cache.has(v.id)) cache.set(v.id, await fetchAliases(v.id));
        v.aliases = cache.get(v.id);
      }
    }
  }
  return ids.map((m) => [...m.values()]);
}

/** Un aviso queda ignorado si su id O CUALQUIERA de sus alias esta en la lista de ignoradas. */
export function isIgnored(vuln, ignores) {
  return ignores.has(vuln.id) || (vuln.aliases ?? []).some((a) => ignores.has(a));
}

export function loadIgnores(path, now = new Date()) {
  if (!existsSync(path)) return new Set();
  const entries = JSON.parse(readFileSync(path, 'utf8'));
  const active = new Set();
  for (const { id, expires, reason } of entries) {
    if (!id || !reason || !expires || Number.isNaN(Date.parse(expires))) throw new Error(`osv-ignore invalido: ${JSON.stringify({ id, expires })}`);
    if (new Date(expires) < now) throw new Error(`osv-ignore caducado: ${id} (${expires}); revisar o eliminar`);
    active.add(id);
  }
  return active;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--self-test')) {
    const { coordinates, errors } = parseGradleDependencies(SAMPLE);
    if (errors.length > 0 || coordinates.length !== 4) throw new Error(`self-test parser: ${JSON.stringify({ coordinates, errors })}`);
    const [known] = await queryAll([{ name: 'org.apache.logging.log4j:log4j-core', version: '2.14.1' }]);
    if (known.length === 0) throw new Error('self-test: OSV no marco log4j-core 2.14.1 (Log4Shell)');
    console.log(`self-test OK: parser y OSV (log4j-core 2.14.1: ${known.length} avisos)`);
    return;
  }
  const files = args.filter((a) => !a.startsWith('--'));
  if (files.length === 0) throw new Error('uso: node scripts/osv-audit.mjs <deps.txt> [<deps2.txt> ...] | --self-test');
  const { coordinates, errors } = collectCoordinates(files.map((f) => ({ label: f, text: readFileSync(f, 'utf8') })));
  if (errors.length > 0) throw new Error(`el arbol resuelto tiene ${errors.length} problemas:\n${errors.join('\n')}`);
  const names = new Set(coordinates.map((c) => c.name));
  for (const expected of EXPECTED) if (!names.has(expected)) throw new Error(`el grafo resuelto no contiene ${expected}: se audito otra cosa`);
  const ignores = loadIgnores(join(root, 'scripts/osv-ignore.json'));
  const results = await queryAll(coordinates, { resolveAliases: ignores.size > 0 });
  let failed = false;
  coordinates.forEach((c, i) => {
    const open = results[i].filter((v) => !isIgnored(v, ignores));
    if (open.length > 0) { failed = true; console.log(`${c.name}@${c.version}: ${open.map((v) => v.id).join(', ')}`); }
  });
  console.log(`${coordinates.length} coordenadas resueltas auditadas`);
  if (failed) {
    console.error('Hay dependencias con vulnerabilidades conocidas: subir la version o ignorar con caducidad en scripts/osv-ignore.json.');
    process.exit(1);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
