// Pruebas del parser y de las ignoradas de osv-audit (sin red). Ejecutar: node --test scripts/
import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseGradleDependencies, loadIgnores, isIgnored, queryAll, fetchAliases } from './osv-audit.mjs';

test('resuelve versiones (->), transitivas, sustituciones y omite proyectos, constraints y (*)', () => {
  const { coordinates, errors } = parseGradleDependencies([
    '+--- io.ktor:ktor-server-core:3.1.0',
    '|    +--- io.ktor:ktor-http:3.1.0 (*)',
    '|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0 -> 2.1.0',
    '+--- io.micrometer:micrometer-bom:1.15.12 (c)',
    '+--- io.micrometer:micrometer-registry-prometheus -> 1.15.12',
    '+--- project :domain',
    '\\--- org.foo:bar -> org.baz:bar2:2.0',
  ].join('\n'));
  assert.deepEqual(errors, []);
  const set = new Set(coordinates.map((c) => `${c.name}@${c.version}`));
  assert.ok(set.has('io.ktor:ktor-server-core@3.1.0'));
  assert.ok(set.has('io.ktor:ktor-http@3.1.0'));
  assert.ok(set.has('org.jetbrains.kotlin:kotlin-stdlib@2.1.0'));
  assert.ok(set.has('io.micrometer:micrometer-registry-prometheus@1.15.12'), 'BOM-managed dep resuelta');
  assert.ok(set.has('org.baz:bar2@2.0'));
  assert.ok(![...set].some((s) => s.includes('micrometer-bom')), 'el BOM no se consulta como paquete');
});

test('falla cerrado ante dependencias sin resolver o lineas ilegibles', () => {
  const { errors } = parseGradleDependencies('+--- org.a:b:1.0 FAILED\n+--- ???');
  assert.equal(errors.length, 2);
});

test('las ignoradas caducadas hacen fallar y las vigentes se aplican', () => {
  const dir = mkdtempSync(join(tmpdir(), 'osv-'));
  const f = join(dir, 'ignore.json');
  writeFileSync(f, JSON.stringify([{ id: 'GHSA-x', expires: '2099-01-01', reason: 'no alcanzable' }]));
  assert.ok(loadIgnores(f).has('GHSA-x'));
  writeFileSync(f, JSON.stringify([{ id: 'GHSA-x', expires: '2000-01-01', reason: 'r' }]));
  assert.throws(() => loadIgnores(f), /caducado/);
  writeFileSync(f, JSON.stringify([{ id: 'GHSA-x', expires: '2099-01-01' }]));
  assert.throws(() => loadIgnores(f), /invalido/);
});

test('una ignorada por CVE suprime el aviso GHSA con ese alias, y solo ese (MASTER3-CICD-008)', () => {
  const ignores = new Set(['CVE-2099-1']);
  assert.equal(isIgnored({ id: 'GHSA-aaaa', aliases: ['CVE-2099-1'] }, ignores), true);
  assert.equal(isIgnored({ id: 'GHSA-bbbb', aliases: ['CVE-2099-2'] }, ignores), false);
  assert.equal(isIgnored({ id: 'GHSA-cccc' }, ignores), false);
  assert.equal(isIgnored({ id: 'CVE-2099-1', aliases: [] }, ignores), true);
});

// MASTER4-CICD-002: la respuesta REAL de /v1/querybatch trae solo {id, modified}; los alias salen de /v1/vulns/{id}.
function fakeOsv({ failVulns = false } = {}) {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).endsWith('/v1/querybatch')) {
      return { ok: true, json: async () => ({ results: [{ vulns: [{ id: 'GHSA-aaaa', modified: '2026-01-01T00:00:00Z' }, { id: 'OSV-9', modified: '2026-01-01T00:00:00Z' }] }] }) };
    }
    if (failVulns) return { ok: false, status: 404, json: async () => ({}) };
    if (String(url).endsWith('/v1/vulns/GHSA-aaaa')) return { ok: true, json: async () => ({ id: 'GHSA-aaaa', aliases: ['CVE-2099-1'] }) };
    if (String(url).endsWith('/v1/vulns/OSV-9')) return { ok: true, json: async () => ({ id: 'OSV-9' }) };
    throw new Error('url inesperada ' + url);
  };
  return calls;
}

test('queryAll sin ignoradas NO consulta /vulns (respuesta real: solo id)', async () => {
  const original = globalThis.fetch;
  const calls = fakeOsv();
  try {
    const [vulns] = await queryAll([{ name: 'org.x:y', version: '1' }]);
    assert.deepEqual(vulns, [{ id: 'GHSA-aaaa', aliases: [] }, { id: 'OSV-9', aliases: [] }]);
    assert.equal(calls.length, 1);
  } finally { globalThis.fetch = original; }
});

test('queryAll con resolveAliases completa los alias desde /vulns/{id} y una ignorada por CVE suprime el GHSA', async () => {
  const original = globalThis.fetch;
  const calls = fakeOsv();
  try {
    const [vulns] = await queryAll([{ name: 'org.x:y', version: '1' }], { resolveAliases: true });
    assert.deepEqual(vulns, [{ id: 'GHSA-aaaa', aliases: ['CVE-2099-1'] }, { id: 'OSV-9', aliases: [] }]);
    assert.equal(calls.filter((c) => c.includes('/v1/vulns/')).length, 2);
    const ignores = new Set(['CVE-2099-1']);
    assert.deepEqual(vulns.filter((v) => !isIgnored(v, ignores)).map((v) => v.id), ['OSV-9']);
  } finally { globalThis.fetch = original; }
});

test('si /vulns/{id} falla con ignoradas activas, queryAll falla cerrado (no suprime por suerte)', async () => {
  const original = globalThis.fetch;
  fakeOsv({ failVulns: true });
  try {
    await assert.rejects(() => queryAll([{ name: 'org.x:y', version: '1' }], { resolveAliases: true }), /OSV/);
  } finally { globalThis.fetch = original; }
});

test('fetchAliases devuelve [] si la vuln no tiene alias', async () => {
  const original = globalThis.fetch;
  fakeOsv();
  try { assert.deepEqual(await fetchAliases('OSV-9'), []); } finally { globalThis.fetch = original; }
});
