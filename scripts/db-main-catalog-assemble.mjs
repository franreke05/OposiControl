#!/usr/bin/env node
// DB-CI-PG17: convierte el resultado de scripts/db-main-catalog-capture.sql (filas {seccion, sentencias, sql}) en
// baseline_main.sql, la entrada de scripts/db-pg17-bootstrap-extract.mjs. No lee MAIN ni secretos: solo transforma el JSON.
// Acepta el JSON tal cual (array de filas) o la respuesta del MCP de Supabase (texto con el array entre etiquetas).
//   node scripts/db-main-catalog-assemble.mjs <resultado.json|txt> > <dir fuera del repo>/baseline_main.sql
import fs from 'node:fs';

const src = process.argv[2];
if (!src) { console.error('uso: node scripts/db-main-catalog-assemble.mjs <resultado>'); process.exit(2); }
let text = fs.readFileSync(src, 'utf8');
try { const outer = JSON.parse(text); if (typeof outer.result === 'string') text = outer.result; } catch { /* ya es el array */ }
const start = text.indexOf('[{');
const end = text.lastIndexOf('}]');
if (start < 0 || end < 0) throw new Error('no hay filas {seccion, sentencias, sql} en ' + src);
const rows = JSON.parse(text.slice(start, end + 2));
const expected = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10'];
if (rows.map((r) => r.seccion).join() !== expected.join()) throw new Error('secciones inesperadas: ' + rows.map((r) => r.seccion).join());
const out = ['-- OposiBot data-free schema baseline of Supabase MAIN, captured READ-ONLY from pg_catalog with',
  '-- scripts/db-main-catalog-capture.sql (no data rows, no secrets). Input of scripts/db-pg17-bootstrap-extract.mjs.'];
for (const r of rows) {
  const lines = r.sql.split('\n');
  out.push(`-- seccion ${r.seccion}: ${r.sentencias} sentencias`);
  out.push(r.sql);
  if (!lines.length) throw new Error('seccion vacia ' + r.seccion);
}
process.stdout.write(out.join('\n') + '\n');
console.error(rows.map((r) => `${r.seccion}:${r.sentencias}`).join(' '));
