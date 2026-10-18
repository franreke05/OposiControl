#!/usr/bin/env node
// Debug helper: builds a DISPOSABLE PG17 container (gate bootstrap + extras + all pending migrations) and runs the SQL file given as argument, printing everything (NOTICEs and errors). No live stack, no lock.
//   node ops/local-exam/sql-probe.mjs path/to/probe.sql
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { startDisposable } from './disposable-db.mjs';

const name = `sql-probe-${process.pid}`;
const d = startDisposable(name);
try {
  const r = d.psql(fs.readFileSync(process.argv[2], 'utf8'), ['-v', 'VERBOSITY=terse']);
  console.log(r.stdout);
  console.log((r.stderr || '').split('\n').filter((l) => !/NOTICE:  (trigger|function|relation|table)/.test(l)).join('\n'));
} finally {
  try { execFileSync('docker', ['rm', '-f', name], { stdio: 'pipe' }); } catch { /* gone */ }
}
