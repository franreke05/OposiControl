#!/usr/bin/env node
// J20 (E1, W4): leagues, ranking, top 3 and place probability over HTTP. Needs STACK_LOCK, the app Ktor (:8089), the control Ktor (:8090) and `up.mjs --converge` (migration 20261022101700).
//   Caller = Q1 (specialty ESP). Synthetic users R1..R9 (same specialty, liga-50, FULL window, Nota media 9..1), R1 and R2 opted in; R10 of another specialty; R11 without a full window.
//   node ops/local-exam/ranking-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('ranking-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const ADMIN = process.env.ADMIN_KTOR_URL ?? 'http://127.0.0.1:8090';
const Q1 = '2a000000-0000-4000-8000-00000000002a', Q4 = '2e000000-0000-4000-8000-00000000002e';
// the journey runs in its OWN specialty (the live stack holds other users of the main specialty from other suites: they would change the positions); Q1 is moved there and back
const ESP = 'e1000000-0000-4000-8000-0000000000e0', ESP2 = 'e1000000-0000-4000-8000-000000000002', ESP_Q1 = 'e5000000-0000-4000-8000-000000000001';
const R = (n) => `4c000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (base, token, method, p, body) => {
  const headers = { Authorization: `Bearer ${token}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(base + p, { method, headers, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
};
const app = (user, method, p, body) => http(APP, tokens[user], method, p, body);
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };

const prevMin = sql(`select valor_integer from public.configuracion_v1 where clave = 'cohorte_minima_comparativa'`);
const prevUser = sql(`select coalesce(username, '') from public.perfiles_usuario where usuario_id = '${Q4}'`);
const prevPublic = sql(`select ranking_publico from public.perfiles_usuario where usuario_id = '${Q1}'`);
const prevEsp = sql(`select especialidad_principal_id from public.perfiles_usuario where usuario_id = '${Q1}'`) || ESP_Q1;
try {
  // ---- fixtures (idempotent): users R1..R11 and the caller Q1 at Nota 6.5 inside the same tier
  sql(`insert into public.especialidades (id, codigo, nombre, slug, activa) values ('${ESP}', 'J20', 'J20 ranking', 'j20-ranking', true) on conflict (id) do nothing;
set session_replication_role = replica;
update public.perfiles_usuario set especialidad_principal_id = '${ESP}' where usuario_id = '${Q1}';
delete from public.ranking_top where usuario_id::text like '4c000000-%' or usuario_id = '${Q1}';
delete from public.estado_competitivo_usuario where usuario_id::text like '4c000000-%' or usuario_id = '${Q1}';
insert into auth.users (id, email, aud, role, created_at, updated_at) select ('4c000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'rk-j20-' || n || '@exam.local', 'authenticated', 'authenticated', now(), now() from generate_series(1, 11) n on conflict do nothing;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id, ranking_publico)
  select ('4c000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'Rank ' || n, 'rk_j20_' || n, true, 'activa', case when n = 10 then '${ESP2}'::uuid else '${ESP}'::uuid end, n in (1, 2)
  from generate_series(1, 11) n on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id, ranking_publico = excluded.ranking_publico, username = excluded.username;
insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual)
  select ('4c000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, (select id from public.ligas_nivel where codigo = 'liga-50'), case when n = 11 then 7 else 50 end, 50, case when n = 10 then 9.9 when n = 11 then 10 else 10 - n end
  from generate_series(1, 11) n;
insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual)
  values ('${Q1}', (select id from public.ligas_nivel where codigo = 'liga-50'), 50, 50, 6.5);
set session_replication_role = origin;
update public.perfiles_usuario set ranking_publico = false where usuario_id = '${Q1}';
update public.configuracion_v1 set valor_integer = 6 where clave = 'cohorte_minima_comparativa';
update public.lotes_actualizacion_ranking set iniciado_en = now() - interval '1 day' where tipo = 'ranking';
delete from public.lotes_actualizacion_ranking where tipo = 'ranking_admin';`);

  // ---- before the job: league known, nothing ranked
  const before = await app('Q1', 'GET', '/api/v1/app/ranking');
  check('J20.before_the_job_the_user_has_a_league_but_no_position', before.status === 200 && before.json?.liga?.codigo === 'liga-50' && before.json?.posicion === undefined && before.json?.top3?.length === 0
    && before.json?.probabilidadPlaza?.disponible === false && before.headers.get('cache-control') === 'no-store', before.text.slice(0, 200));

  // ---- the job (forced here; in service it runs periodically with a throttle)
  const job = JSON.parse(sql(`select public.app_job_ranking(true)`));
  const view = await app('Q1', 'GET', '/api/v1/app/ranking');
  const v = view.json;
  // cohort = Q1 + R1..R9 (R10 other specialty, R11 incomplete window): 10 users; Nota R1 9, R2 8, R3 7, R4 6 ...; Q1 6.5 -> position 4
  check('J20.job_ranks_the_caller_inside_tier_and_specialty', job.estado === 'OK' && v?.posicion === 4 && v?.notaMedia === 6.5 && v?.liga?.codigo === 'liga-50', `${JSON.stringify(job)} pos=${v?.posicion}`);
  check('J20.cohort_bucket_never_the_exact_size', v?.cohorte?.tramo === 6 && Object.keys(v?.cohorte ?? {}).join() === 'tramo', JSON.stringify(v?.cohorte));
  check('J20.top3_only_opted_in_handles_with_real_positions', v?.top3?.length === 2 && v.top3[0].handle === 'rk_j20_1' && v.top3[0].posicion === 1 && v.top3[1].handle === 'rk_j20_2' && v.top3[1].posicion === 2, JSON.stringify(v?.top3));
  // worse than Q1 (6.5) among full windows of the same specialty and window: R4..R9 (6) of 9 others -> 66.67 %
  check('J20.place_probability_worse_over_comparable_minus_one', v?.probabilidadPlaza?.disponible === true && v.probabilidadPlaza.porcentaje === 66.67 && v.probabilidadPlaza.tramoCohorte === 6, JSON.stringify(v?.probabilidadPlaza));
  check('J20.no_other_user_identifier_leaks', !/rk-j20|@exam\.local|rk_j20_3|4c000000|Rank \d/.test(view.text), view.text.slice(0, 120));
  check('J20.incomplete_window_and_other_specialty_are_not_in_the_cohort', sql(`select count(*) from public.estado_competitivo_usuario where usuario_id = '${R(11)}' and ranking_liga_posicion is not null`) === '0'
    && sql(`select ranking_cohorte from public.estado_competitivo_usuario where usuario_id = '${R(10)}'`) === '1');

  // ---- minimum cohort enforced by the database
  sql(`update public.configuracion_v1 set valor_integer = 20 where clave = 'cohorte_minima_comparativa';`);
  const small = (await app('Q1', 'GET', '/api/v1/app/ranking')).json;
  check('J20.below_the_minimum_cohort_only_the_own_position', small?.posicion === 4 && small?.cohorte === undefined && small?.top3?.length === 0 && small?.probabilidadPlaza?.disponible === false && small.probabilidadPlaza.motivo === 'cohorte_insuficiente', JSON.stringify(small).slice(0, 220));
  sql(`update public.configuracion_v1 set valor_integer = 6 where clave = 'cohorte_minima_comparativa';`);

  // ---- visibility: strict body, opt-in, username required, immediate opt-out
  const bads = [];
  for (const b of ['{}', '{"publico":"yes"}', '{"publico":true,"usuarioId":"x"}', '[]']) bads.push((await app('Q1', 'PUT', '/api/v1/app/ranking/visibilidad', b)).status);
  check('J20.visibility_body_is_strict', bads.every((s) => s === 400), bads.join(','));
  const on = await app('Q1', 'PUT', '/api/v1/app/ranking/visibilidad', { publico: true });
  const on2 = await app('Q1', 'PUT', '/api/v1/app/ranking/visibilidad', { publico: true });
  check('J20.opt_in_is_idempotent', on.status === 200 && on.json?.publico === true && on2.status === 200 && on2.json?.publico === true && sql(`select ranking_publico from public.perfiles_usuario where usuario_id = '${Q1}'`) === 't');
  sql(`update public.perfiles_usuario set username = null where usuario_id = '${Q4}';`);
  const noUser = await app('Q4', 'PUT', '/api/v1/app/ranking/visibilidad', { publico: true });
  check('J20.becoming_public_needs_a_username', noUser.status === 422 && noUser.json?.code === 'USERNAME_REQUIRED', `${noUser.status} ${noUser.json?.code}`);
  sql(`insert into public.ranking_top (especialidad_id, liga_nivel_id, posicion, usuario_id, handle, nota_media) values ('${ESP}', (select id from public.ligas_nivel where codigo = 'liga-50'), 3, '${Q1}', 'q1_saldo_alto', 6.5) on conflict do nothing;`);
  const off = await app('Q1', 'PUT', '/api/v1/app/ranking/visibilidad', { publico: false });
  check('J20.opt_out_removes_the_listing_immediately', off.status === 200 && off.json?.publico === false && sql(`select count(*) from public.ranking_top where usuario_id = '${Q1}'`) === '0');
  check('J20.unauthenticated_is_401', (await http(APP, 'bad', 'GET', '/api/v1/app/ranking')).status === 401);

  // ---- admin recompute: audited with cooldown (control profile)
  const rec = await http(ADMIN, tokens.ADMIN, 'POST', '/api/v1/admin/estadisticas/recalcular', { alcance: 'ranking' });
  const rec2 = await http(ADMIN, tokens.ADMIN, 'POST', '/api/v1/admin/estadisticas/recalcular', { alcance: 'ranking' });
  const badScope = await http(ADMIN, tokens.ADMIN, 'POST', '/api/v1/admin/estadisticas/recalcular', { alcance: 'todo' });
  check('J20.admin_recompute_is_audited_with_a_cooldown', rec.status === 200 && rec.json?.estado === 'OK' && rec2.status === 429 && rec2.json?.code === 'STATS_RECOMPUTE_COOLDOWN' && badScope.status === 422
    && Number(sql(`select count(*) from public.auditoria_log where accion = 'estadisticas_recalculo' and despues ->> 'alcance' = 'ranking'`)) >= 1, `${rec.status} ${rec2.status} ${badScope.status}`);
  const throttled = JSON.parse(sql(`select public.app_job_ranking(false)`));
  check('J20.job_is_throttled_by_control', throttled.estado === 'RECIENTE', JSON.stringify(throttled));
} finally {
  sql(`set session_replication_role = replica;
update public.configuracion_v1 set valor_integer = ${prevMin || 50} where clave = 'cohorte_minima_comparativa';
update public.perfiles_usuario set username = ${prevUser ? `'${prevUser}'` : 'null'} where usuario_id = '${Q4}';
update public.perfiles_usuario set ranking_publico = ${prevPublic === 't' ? 'true' : 'false'}, especialidad_principal_id = '${prevEsp}' where usuario_id = '${Q1}';
delete from public.ranking_top where usuario_id = '${Q1}';
set session_replication_role = replica;
delete from public.estado_competitivo_usuario where usuario_id = '${Q1}';`);
}
const failed = results.filter((r) => !r).length;
console.log(`\nJ20: ${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
