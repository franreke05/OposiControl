// Pruebas del parser y de las ignoradas de osv-audit (sin red). Ejecutar: node --test scripts/
import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseGradleDependencies, loadIgnores, isIgnored, queryAll } from './osv-audit.mjs';

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

test('queryAll conserva los alias de OSV (fetch simulado, sin red)', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ results: [{ vulns: [{ id: 'GHSA-aaaa', aliases: ['CVE-2099-1'] }, { id: 'OSV-9' }] }] }) });
  try {
    const [vulns] = await queryAll([{ name: 'org.x:y', version: '1' }]);
    assert.deepEqual(vulns, [{ id: 'GHSA-aaaa', aliases: ['CVE-2099-1'] }, { id: 'OSV-9', aliases: [] }]);
  } finally { globalThis.fetch = original; }
});
