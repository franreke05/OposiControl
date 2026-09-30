// Pruebas del parser y de las ignoradas de osv-audit (sin red). Ejecutar: node --test scripts/
import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseGradleDependencies, loadIgnores } from './osv-audit.mjs';

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
