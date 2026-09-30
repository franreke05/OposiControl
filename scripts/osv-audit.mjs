#!/usr/bin/env node
// MASTER-SEC-014: auditoria SCA de las dependencias fijadas del backend Ktor contra la base OSV (api.osv.dev).
// Sin dependencias: solo Node >= 18 (fetch). Falla (exit 1) si alguna coordenada fijada tiene una vulnerabilidad
// conocida; hay que subir la version antes de fusionar. Uso:
//   node scripts/osv-audit.mjs              # audita backend/ktor/build.gradle.kts
//   node scripts/osv-audit.mjs --self-test  # comprueba el parser y que OSV marca una version vulnerable conocida
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const OSV_QUERY = 'https://api.osv.dev/v1/query';

export function extractCoordinates(buildScript, tomlText) {
  const ktorVersion = /^ktor\s*=\s*"([^"]+)"/m.exec(tomlText)?.[1];
  const found = new Map();
  const literal = /"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^"$]+|\$\{libs\.versions\.ktor\.get\(\)\})"/g;
  for (const [, group, artifact, rawVersion] of buildScript.matchAll(literal)) {
    const version = rawVersion.includes('libs.versions.ktor') ? ktorVersion : rawVersion;
    if (version) found.set(`${group}:${artifact}@${version}`, { name: `${group}:${artifact}`, version });
  }
  return [...found.values()];
}

async function vulnerabilities({ name, version }) {
  const response = await fetch(OSV_QUERY, {
    method: 'POST',
    body: JSON.stringify({ package: { name, ecosystem: 'Maven' }, version }),
  });
  if (!response.ok) throw new Error(`OSV respondio ${response.status} para ${name}@${version}`);
  return (await response.json()).vulns ?? [];
}

async function main() {
  const selfTest = process.argv.includes('--self-test');
  const buildScript = readFileSync(join(root, 'backend/ktor/build.gradle.kts'), 'utf8');
  const toml = readFileSync(join(root, 'gradle/libs.versions.toml'), 'utf8');
  const coordinates = extractCoordinates(buildScript, toml);
  const names = coordinates.map((c) => c.name);
  for (const expected of ['ch.qos.logback:logback-classic', 'org.apache.pdfbox:pdfbox', 'io.lettuce:lettuce-core', 'com.auth0:java-jwt', 'io.ktor:ktor-server-metrics-micrometer']) {
    if (!names.includes(expected)) throw new Error(`el parser no encontro ${expected}`);
  }
  if (selfTest) {
    const known = await vulnerabilities({ name: 'org.apache.logging.log4j:log4j-core', version: '2.14.1' });
    if (known.length === 0) throw new Error('self-test: OSV no marco log4j-core 2.14.1 (Log4Shell)');
    console.log(`self-test OK: ${coordinates.length} coordenadas, log4j-core 2.14.1 marcado (${known.length} avisos)`);
    return;
  }
  let failed = false;
  for (const coordinate of coordinates) {
    const vulns = await vulnerabilities(coordinate);
    console.log(`${coordinate.name}@${coordinate.version}: ${vulns.length === 0 ? 'sin avisos' : vulns.map((v) => v.id).join(', ')}`);
    if (vulns.length > 0) failed = true;
  }
  if (failed) {
    console.error('Hay dependencias con vulnerabilidades conocidas: subir la version.');
    process.exit(1);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
