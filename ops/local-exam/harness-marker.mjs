// Marks the exams a harness created so `up.mjs --clean-test-exams` / `--restore-fixtures` can clean them (see sql/88_harness_exams_local.sql).
// Usage, at the top of a harness that creates exams:   import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('stats-journey');
// Everything created in `examenes` between the call and the process exit is marked, except baseline fixtures (LOC-*). Best effort: never fails the harness.
import { execFileSync } from 'node:child_process';

const psql = (sql) => execFileSync('docker', ['exec', '-i', 'oposi-exam-local-db-1', 'psql', '-U', 'postgres', '-d', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
  { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] }).trim();

export function trackHarnessExams(label) {
  let t0;
  try { if (psql("select to_regclass('public.local_harness_exams') is not null") !== 't') return; t0 = psql('select clock_timestamp()'); } catch { return; }
  let done = false;
  const mark = () => {
    if (done) return; done = true;
    try {
      psql(`insert into public.local_harness_exams (examen_id, etiqueta) select id, '${String(label).replace(/[^A-Za-z0-9_.-]/g, '')}' from public.examenes
             where creado_en >= '${t0}'::timestamptz - interval '2 seconds' and codigo_examen not like 'LOC-%' on conflict (examen_id) do nothing`);
    } catch { /* best effort */ }
  };
  process.on('exit', mark);
}
