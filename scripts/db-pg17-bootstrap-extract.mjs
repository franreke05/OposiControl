#!/usr/bin/env node
// DB-CI-PG17: genera supabase/baseline/main_shape_subset.sql, el bootstrap del job `db-pg17` del CI.
//
// Entrada: la captura de catalogo READ-ONLY de MAIN (baseline_main.sql, sin filas) que vive FUERA del repositorio
// (gate local, .oposibots-gate/baseline/). Esta herramienta NO lee MAIN ni ningun secreto: solo transforma ese fichero.
// Salida: SOLO la forma de MAIN que tocan las migraciones pendientes (supabase/baseline/PENDING_MIGRATIONS.txt), por
// cierre determinista:
//   semillas = objetos `public.x` / `private.x` nombrados en el SQL (sin comentarios) de las migraciones pendientes y de
//              supabase/baseline/assertions.sql que
//              existen en la captura;
//   cierre   = destinos de FK de cada tabla incluida + funciones de sus triggers + tablas y funciones nombradas en sus
//              politicas RLS (punto fijo).
// De cada objeto incluido se copian VERBATIM sus sentencias de la captura: CREATE TABLE/SEQUENCE, constraints, FKs,
// indices, ENABLE RLS, politicas, triggers, REVOKE/GRANT, COMMENT y la definicion de funcion. Se copian tambien los
// esquemas, extensiones y los ALTER DEFAULT PRIVILEGES FOR ROLE postgres. Se OMITEN (sin allowlist de errores):
//   - ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin: el rol postgres local no puede ejecutarlos (los 3 errores
//     conocidos del gate local); la imagen de Supabase ya trae esos privilegios por defecto;
//   - buckets de storage, publicacion realtime, vistas y cualquier tabla/funcion fuera del cierre.
// Los comentarios de la captura (que citan la referencia del proyecto) no se copian.
//
// Uso: node scripts/db-pg17-bootstrap-extract.mjs <baseline_main.sql> > supabase/baseline/main_shape_subset.sql
// Es determinista: misma captura + mismas migraciones pendientes = misma salida (lo comprueba la prueba de la herramienta).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = process.env.MIG_ROOT || path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

export function splitStatements(src) {
  const out = []; let cur = []; let tag = null;
  for (const line of src.replace(/\r\n/g, '\n').split('\n')) {
    if (!tag && cur.length === 0 && (line.startsWith('--') || line.trim() === '')) continue;
    cur.push(line);
    const re = /\$([A-Za-z_]*)\$/g; let m;
    while ((m = re.exec(line))) { if (!tag) tag = m[0]; else if (m[0] === tag) tag = null; }
    if (!tag && /;\s*$/.test(line)) { out.push(cur.join('\n')); cur = []; }
  }
  if (cur.length) throw new Error('sentencia sin cerrar al final de la captura: ' + cur[0].slice(0, 80));
  return out;
}

const Q = '((?:public|private|auth)\\.[a-z_0-9]+)';
const rx = {
  table: new RegExp('^CREATE TABLE ' + Q + ' '),
  seq: new RegExp('^CREATE SEQUENCE IF NOT EXISTS ' + Q + ' '),
  func: new RegExp('^CREATE OR REPLACE (?:FUNCTION|PROCEDURE) ' + Q + '\\('),
  alterTable: new RegExp('^ALTER TABLE (?:ONLY )?' + Q + ' '),
  index: new RegExp('^CREATE (?:UNIQUE )?INDEX \\S+ ON (?:ONLY )?' + Q + ' '),
  trigger: new RegExp('^CREATE (?:CONSTRAINT )?TRIGGER \\S+ .*? ON ' + Q + ' .*EXECUTE FUNCTION ' + Q + '\\(', 's'),
  policy: new RegExp('^CREATE POLICY \\S+ ON ' + Q + ' '),
  privTable: new RegExp('^(?:GRANT|REVOKE) .*? ON TABLE ' + Q + ' '),
  privSeq: new RegExp('^(?:GRANT|REVOKE) .*? ON SEQUENCE ' + Q + ' '),
  privFunc: new RegExp('^(?:GRANT|REVOKE) .*? ON (?:FUNCTION|PROCEDURE) ' + Q + '\\('),
  commentRel: new RegExp('^COMMENT ON (?:TABLE|SEQUENCE) ' + Q + ' '),
  commentCol: new RegExp('^COMMENT ON COLUMN ' + Q + '\\.[a-z_0-9]+ '),
  commentCon: new RegExp('^COMMENT ON CONSTRAINT \\S+ ON ' + Q + ' '),
  commentFn: new RegExp('^COMMENT ON (?:FUNCTION|PROCEDURE) ' + Q + '\\('),
  fk: new RegExp('REFERENCES ' + Q + '\\('),
};

export function extract(baselineSrc, pendingSql) {
  const stmts = splitStatements(baselineSrc);
  const tables = new Set(); const funcs = new Set(); const seqs = new Set();
  for (const s of stmts) {
    let m;
    if ((m = rx.table.exec(s))) tables.add(m[1]);
    else if ((m = rx.func.exec(s))) funcs.add(m[1]);
    else if ((m = rx.seq.exec(s))) seqs.add(m[1]);
  }
  const code = pendingSql.split('\n').filter((l) => !/^\s*--/.test(l)).join('\n');
  const named = new Set(code.match(/\b(?:public|private)\.[a-z_0-9]+/g) || []);
  const incT = new Set([...named].filter((n) => tables.has(n)));
  const incF = new Set([...named].filter((n) => funcs.has(n)));
  const bare = (q) => q.split('.')[1];
  const byBare = (set) => new Map([...set].map((q) => [bare(q), q]));
  const tBare = byBare(tables); const fBare = byBare(funcs);
  for (let changed = true; changed;) {
    changed = false;
    const add = (set, v) => { if (v && !set.has(v)) { set.add(v); changed = true; } };
    for (const s of stmts) {
      let m;
      if ((m = rx.alterTable.exec(s)) && incT.has(m[1])) {
        const fk = rx.fk.exec(s); if (fk && tables.has(fk[1])) add(incT, fk[1]);
      } else if ((m = rx.trigger.exec(s)) && incT.has(m[1])) {
        add(incF, m[2]);
      } else if ((m = rx.policy.exec(s)) && incT.has(m[1])) {
        for (const w of s.match(/[a-z_][a-z_0-9]*/g) || []) { add(incT, tBare.get(w)); add(incF, fBare.get(w)); }
      } else if ((m = rx.func.exec(s)) && incF.has(m[1])) {
        // Cuerpo de una funcion incluida: las tablas y funciones que nombra CALIFICADAS (public.x / private.x) tambien
        // (con la captura de MAIN ya aplicada, los ayudantes no los define ninguna migracion pendiente).
        for (const q of s.match(/\b(?:public|private)\.[a-z_0-9]+/g) || []) { if (tables.has(q)) add(incT, q); if (funcs.has(q)) add(incF, q); }
      }
    }
  }
  const incS = new Set();
  for (const t of incT) {
    const ct = stmts.find((s) => s.startsWith('CREATE TABLE ' + t + ' '));
    for (const sq of seqs) if (ct.includes(sq)) incS.add(sq);
  }
  const keep = (s) => {
    let m;
    if (/^(CREATE SCHEMA|CREATE EXTENSION|SET )/.test(s)) return true;
    if (/^ALTER DEFAULT PRIVILEGES FOR ROLE postgres /.test(s)) return true;
    if ((m = rx.seq.exec(s))) return incS.has(m[1]);
    if ((m = rx.table.exec(s))) return incT.has(m[1]);
    if ((m = rx.func.exec(s))) return incF.has(m[1]);
    if ((m = rx.trigger.exec(s))) return incF.has(m[2]) && (incT.has(m[1]) || m[1] === 'auth.users');
    if ((m = rx.alterTable.exec(s))) return incT.has(m[1]);
    if ((m = rx.index.exec(s))) return incT.has(m[1]);
    if ((m = rx.policy.exec(s))) return incT.has(m[1]);
    if ((m = rx.privTable.exec(s))) return incT.has(m[1]);
    if ((m = rx.privSeq.exec(s))) return incS.has(m[1]);
    if ((m = rx.privFunc.exec(s))) return incF.has(m[1]);
    if ((m = rx.commentRel.exec(s)) || (m = rx.commentCol.exec(s)) || (m = rx.commentCon.exec(s))) return incT.has(m[1]) || incS.has(m[1]);
    if ((m = rx.commentFn.exec(s))) return incF.has(m[1]);
    return false;
  };
  const kept = stmts.filter(keep);
  return { kept, tables: [...incT].sort(), funcs: [...incF].sort(), seqs: [...incS].sort() };
}

export function pendingSqlFrom(dir) {
  const list = fs.readFileSync(path.join(dir, 'supabase/baseline/PENDING_MIGRATIONS.txt'), 'utf8')
    .replace(/\r/g, '').split('\n').filter((l) => l.trim() && !l.startsWith('#'));
  return list.map((v) => fs.readFileSync(path.join(dir, 'supabase/migrations', v + '.sql'), 'utf8').replace(/\r\n/g, '\n')).join('\n');
}

// Semillas del cierre: las migraciones pendientes Y las aserciones del gate (supabase/baseline/assertions.sql), que siguen
// comprobando objetos de MAIN ya aplicados (Slice 1/2/3): sin ellas el subconjunto no los traeria.
export function seedSqlFrom(dir) {
  const assertions = path.join(dir, 'supabase/baseline/assertions.sql');
  const extra = fs.existsSync(assertions) ? '\n' + fs.readFileSync(assertions, 'utf8').replace(/\r\n/g, '\n') : '';
  return pendingSqlFrom(dir) + extra;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const src = process.argv[2];
  if (!src) { console.error('uso: node scripts/db-pg17-bootstrap-extract.mjs <baseline_main.sql>'); process.exit(2); }
  const { kept, tables, funcs, seqs } = extract(fs.readFileSync(src, 'utf8'), seedSqlFrom(root));
  const head = [
    '-- GENERADO por scripts/db-pg17-bootstrap-extract.mjs (no editar a mano; ver supabase/baseline/README.md).',
    '-- Forma de MAIN (DDL de catalogo, SIN filas) de los objetos que tocan las migraciones pendientes, copiada verbatim de',
    '-- la captura READ-ONLY de MAIN del gate local. NO es el baseline completo de MAIN.',
    '-- Tablas (' + tables.length + '): ' + tables.join(', '),
    '-- Funciones (' + funcs.length + '): ' + funcs.join(', '),
    '-- Secuencias (' + seqs.length + '): ' + (seqs.join(', ') || '-'),
    '',
  ];
  process.stdout.write(head.join('\n') + kept.join('\n') + '\n');
}
