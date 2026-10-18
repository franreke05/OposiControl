#!/usr/bin/env node
// J21 (E1, W5a): Invita y gana over HTTP. Needs STACK_LOCK, the app Ktor (:8089) and `up.mjs --converge` (migration 20261022101800).
//   Inviter = Q1, invitee = Q4 (both onboarded fixture users). The invitee's attach goes through the RPC (the register/profile leg needs a fresh account: covered by the real-auth/e2e register flow).
//   The run moves OT of Q1 and Q4: run `up.mjs --restore-fixtures` afterwards. Amounts are LOCAL SAMPLE values, never product decisions.
//   node ops/local-exam/referral-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('referral-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const Q1 = '2a000000-0000-4000-8000-00000000002a', Q4 = '2e000000-0000-4000-8000-00000000002e';
const EXAM = 'e0000000-0000-4000-8000-000000000001';
const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const get = async (user, p) => { const r = await fetch(APP + p, { headers: { Authorization: `Bearer ${tokens[user]}` } }); const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ } return { status: r.status, json, text, headers: r.headers }; };
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };
const KEYS = ['referido_activo', 'referido_ot_invitador', 'referido_ot_invitado', 'referido_min_respuestas', 'referido_min_segundos', 'referido_min_horas', 'referido_retener_horas'];
const prev = Object.fromEntries(sql(`select clave || '=' || valor_integer from public.configuracion_v1 where clave in (${KEYS.map((k) => `'${k}'`).join(',')})`).split('\n').map((l) => l.split('=')));
const saldo = async (u) => (await get(u, '/api/v1/app/wallet')).json?.saldo;

try {
  sql(`delete from public.invitaciones where invitado_id = '${Q4}' or invitador_id = '${Q1}';
delete from public.invitacion_intentos where usuario_id = '${Q4}';   -- the per-account attach limit would otherwise block repeated runs
update public.configuracion_v1 set valor_integer = 0 where clave in ('referido_activo');
update public.configuracion_v1 set valor_integer = 7 where clave in ('referido_ot_invitador', 'referido_ot_invitado');
update public.configuracion_v1 set valor_integer = 1 where clave = 'referido_min_respuestas';
update public.configuracion_v1 set valor_integer = 0 where clave in ('referido_min_segundos', 'referido_min_horas', 'referido_retener_horas');`);

  const off = await get('Q1', '/api/v1/app/me/invitacion');
  check('J21.feature_off_by_default_shows_no_amount', off.status === 200 && /^[A-HJKMNP-TV-Z2-9]{8}$/.test(off.json?.codigo ?? '') && off.json.configuracion.otPorInvitacion === null && off.headers.get('cache-control') === 'no-store', off.text.slice(0, 160));
  const code = off.json.codigo;
  const again = await get('Q1', '/api/v1/app/me/invitacion');
  check('J21.code_is_stable_and_private_to_the_user', again.json?.codigo === code && (await get('Q4', '/api/v1/app/me/invitacion')).json?.codigo !== code);
  sql(`update public.configuracion_v1 set valor_integer = 1 where clave = 'referido_activo'`);
  const on = await get('Q1', '/api/v1/app/me/invitacion');
  check('J21.feature_on_shows_the_server_amount', on.json?.configuracion?.otPorInvitacion === 7);

  const attach = JSON.parse(sql(`select public.app_referral_attach('${Q4}', '${code}', null)`));
  const bad = JSON.parse(sql(`select public.app_referral_attach('${Q4}', 'ZZZZZZZZ', null)`));
  check('J21.attach_is_neutral_for_unusable_codes', attach.adjunta === true && bad.adjunta === false);
  const before1 = await saldo('Q1'), before4 = await saldo('Q4');
  sql(`update auth.users set email_confirmed_at = now() - interval '2 hours' where id = '${Q4}'`);
  check('J21.email_verified_signal_moves_the_state', sql(`select estado from public.invitaciones where invitado_id = '${Q4}'`) === 'email_verificado');
  const attempt = crypto.randomUUID();
  sql(`insert into public.intentos_examen (id, usuario_id, examen_id, estado, total_respondidas, duracion_segundos, iniciado_en) values ('${attempt}', '${Q4}', '${EXAM}', 'en_progreso', 3, 120, now() - interval '1 hour');
update public.intentos_examen set estado = 'finalizado', finalizado_en = now() where id = '${attempt}';`);
  check('J21.exam_finish_records_the_first_attempt_only', sql(`select intento_id from public.invitaciones where invitado_id = '${Q4}'`) === attempt);
  sql(`select public.app_job_referidos()`);
  check('J21.job_moves_to_diferida_without_paying', sql(`select estado from public.invitaciones where invitado_id = '${Q4}'`) === 'diferida' && (await saldo('Q1')) === before1);
  sql(`select public.app_job_referidos()`);
  sql(`select public.app_job_referidos()`);
  check('J21.job_pays_both_sides_exactly_once', sql(`select estado from public.invitaciones where invitado_id = '${Q4}'`) === 'premiada' && (await saldo('Q1')) === before1 + 7 && (await saldo('Q4')) === before4 + 7);

  const mine = await get('Q1', '/api/v1/app/me/invitacion');
  check('J21.inviter_counters', mine.json?.invitados?.premiados >= 1 && mine.json?.otGanados >= 7, JSON.stringify(mine.json?.invitados));
  const list = await get('Q1', '/api/v1/app/me/invitaciones');
  const theirs = await get('Q4', '/api/v1/app/me/invitaciones');
  check('J21.list_shows_the_public_handle_only', list.status === 200 && list.json?.items?.length === 1 && list.json.items[0].handle === 'q4_pagos' && !/@exam\.local|2e000000/.test(list.text) && theirs.json?.items?.length === 0, list.text.slice(0, 160));
  const badQ = [await get('Q1', '/api/v1/app/me/invitaciones?limit=abc'), await get('Q1', '/api/v1/app/me/invitaciones?usuarioId=x'), await get('Q1', '/api/v1/app/me/invitaciones?cursor=no%20valido')];
  check('J21.strict_query', badQ.every((r) => r.status === 400), badQ.map((r) => r.status).join(','));
  check('J21.unauthenticated_is_401', (await fetch(APP + '/api/v1/app/me/invitacion', { headers: { Authorization: 'Bearer bad' } })).status === 401);
  check('J21.ledger_rows_are_keyed_per_beneficiary', Number(sql(`select count(*) from public.transacciones_cartera where tipo = 'referido' and idempotency_key like 'referido:%' and referencia_tipo = 'invitacion' and usuario_id in ('${Q1}', '${Q4}')`)) >= 2);   // (a string comparison made '10' >= '2' false once the ledger grew)
} finally {
  sql(Object.entries(prev).map(([k, v]) => `update public.configuracion_v1 set valor_integer = ${v} where clave = '${k}';`).join('\n') + `\ndelete from public.invitaciones where invitado_id = '${Q4}';`);
}
const failed = results.filter((r) => !r).length;
console.log(`\nJ21: ${results.length - failed}/${results.length} checks passed (run up.mjs --restore-fixtures afterwards: Q1/Q4 balances moved)`);
process.exit(failed ? 1 : 0);
