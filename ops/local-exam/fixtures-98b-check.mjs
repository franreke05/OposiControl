#!/usr/bin/env node
// Checks sql/98b_v1_access_v2_fixtures.sql on a DISPOSABLE PG17 (twice: idempotent) and that the fixtures behave: Q6 covers rafagas only, Q7 can choose 2 immersive exams, the demo exam starts a 5-question demo.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startDisposable } from './disposable-db.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const db = startDisposable(`fx98b-${process.pid}`);
let code = 1;
try {
  db.must(`insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e5000000-0000-4000-8000-000000000001','LOC','Local','local',true) on conflict do nothing;
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-4000-8000-000000000001','LC','Local','local-c') on conflict do nothing;`);
  const sql = fs.readFileSync(path.join(here, 'sql/98b_v1_access_v2_fixtures.sql'), 'utf8');
  db.must(sql);
  db.must(sql);
  const out = db.must(`do $$
declare q6 uuid := '2f000000-0000-4000-8000-00000000002f'; q7 uuid := '3b000000-0000-4000-8000-00000000003b'; demo uuid := 'e3000000-0000-4000-8000-0000000000d1'; r jsonb;
begin
  assert (select count(*) from public.preguntas_examen where examen_id = demo and es_demo) = 5 and (select demo_disponible from public.examenes where id = demo), 'demo exam: 5 flagged, demo on';
  assert (select valor_integer from public.beneficios_plan where plan_id = 'b1000000-0000-4000-8000-000000000003' and codigo_beneficio = 'inmersivos_incluidos') = 2, 'inmersivo plan quota 2';
  assert private.plan_daily_limit(q6, clock_timestamp()) = 100 and private.plan_daily_limit('2a000000-0000-4000-8000-00000000002a', clock_timestamp()) = 30, 'limits';
  perform public.app_choose_plan_exam(q7, demo);
  perform public.app_choose_plan_exam(q7, 'e3000000-0000-4000-8000-0000000000d2');
  assert (public.app_plan_quota(q7) ->> 'cupoUsado')::int = 2 and (public.app_plan_quota(q7) ->> 'cupoLibre')::int = 0, 'Q7 holds 2 immersive exams';
  r := public.app_start_exam_attempt(q6, demo, 'DEMO');
  assert r ->> 'outcome' = 'EXAM_LOCKED' or r ->> 'outcome' = 'CREATED', 'start answers';
  raise notice 'FIXTURES OK %', r;
end $$;`, ['-v', 'ON_ERROR_STOP=1']);
  console.log('FIXTURES_98B OK');
  code = 0;
} catch (e) {
  console.error(String(e.message ?? e));
} finally {
  db.stop();
}
process.exit(code);
