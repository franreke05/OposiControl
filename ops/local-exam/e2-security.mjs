#!/usr/bin/env node
// E2 (exam security, LOCAL ONLY): adversarial probes against the local exam stack through an ISOLATED Ktor (e2-ktor.mjs, ktorControl port of stack-config.mjs).
// Uses only its own users C/D/E/F (created here); never touches A/B, never resets the stack. Tokens and signed URLs are never
// printed. Phase "content" (--content) edits ONLY an E2-own exam fixture (e2e2...), never the shared ones.
//   node ops/local-exam/e2-security.mjs [--content] [--expiry]   -> PASS/FAIL per check, result in E2_OUT (json)
import { ADMIN_KTOR_URL, E2_LOCAL_DIR, GATEWAY, composeArgs } from './stack-config.mjs';
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('e2-security');

// Escribe en el stack compartido (usuarios/fixtures de E2): falla (exit 3) si STACK_LOCK es de otro.
requireStackLock('e2-security.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const stackLocal = E2_LOCAL_DIR; // E2_STACK_LOCAL or this stack's own .local (stack-config.mjs)
const KTOR = (process.env.KTOR_URL ?? ADMIN_KTOR_URL);
const OUT = process.env.E2_OUT ?? path.join(process.cwd(), 'e2-security-result.json');
const CONTENT = process.argv.includes('--content');
const EXPIRY = process.argv.includes('--expiry');
const secrets = JSON.parse(fs.readFileSync(path.join(stackLocal, 'secrets.json'), 'utf8'));

const EX = { NORMAL: 'e0000000-0000-4000-8000-000000000001', IMAGEN: 'e0000000-0000-4000-8000-000000000003', AUDIO: 'e0000000-0000-4000-8000-000000000004',
  PREMIUM: 'e0000000-0000-4000-8000-000000000005', GRANDE: 'e0000000-0000-4000-8000-000000000006' };
const q = (e, n) => `f${e}000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const o = (e, n, l) => `0${e}000000-0000-4000-8${l}00-${String(n).padStart(12, '0')}`;
const RES = { IMG: '5e000000-0000-4000-8000-000000000001', AUD: '5e000000-0000-4000-8000-000000000002', PDF: '5e000000-0000-4000-8000-000000000003' };
const USERS = { C: '0c000000-0000-4000-8000-0000000000cc', D: '0d000000-0000-4000-8000-0000000000dd', E: '0e000000-0000-4000-8000-0000000000ee', F: '0f000000-0000-4000-8000-0000000000ff' };

const sql = (text) => execFileSync('docker', [...composeArgs(), 'exec', '-T', 'db', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
  { input: text, encoding: 'utf8' }).trim();

const b64url = (b) => Buffer.from(b).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
const jwkKey = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
function es256(sub, expOffset = 3600) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const body = b64url(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: `${GATEWAY}/auth/v1`, iat: now - 60, exp: now + expOffset }));
  const sig = crypto.sign('sha256', Buffer.from(`${head}.${body}`), { key: jwkKey, dsaEncoding: 'ieee-p1363' });
  return `${head}.${body}.${b64url(sig)}`;
}
const tokens = Object.fromEntries(Object.entries(USERS).map(([k, v]) => [k, es256(v)]));
tokens.C_EXPIRED = es256(USERS.C, -120);

async function call(user, method, p, body, key, extraHeaders = {}) {
  const headers = { ...extraHeaders };
  if (user) headers.Authorization = `Bearer ${tokens[user] ?? user}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const r = await fetch(KTOR + p, { method, headers, body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)) });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
}
const key = () => crypto.randomUUID();
const start = (user, exam) => call(user, 'POST', `/api/v1/app/examenes/${exam}/intentos`, {}, key());
const answer = (user, attempt, question, option, base = 0, k = key(), extra = {}) =>
  call(user, 'PUT', `/api/v1/app/intentos/${attempt}/respuestas/${question}`, { opcionSeleccionadaId: option, tiempoRespuestaMs: 1500, baseRevision: base, ...extra }, k);
const finish = (user, attempt) => call(user, 'POST', `/api/v1/app/intentos/${attempt}/finalizar`, {}, key());
const abandon = (user, attempt) => call(user, 'POST', `/api/v1/app/intentos/${attempt}/abandonar`, { posicionPregunta: 1, motivo: 'salida_usuario' });
const resource = (user, attempt, id) => call(user, 'GET', `/api/v1/app/intentos/${attempt}/recursos/${id}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
async function scenario(id, fn) { try { await fn(); } catch (e) { check(id, false, `exception: ${e.message}`); } }
const leaksStorage = (text) => /recursos-pregunta|preguntas\/f|ruta|bucket|token=|signedURL|\/storage\/v1/.test(text);

// ---------- setup: own users only ----------
sql(`set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at) values
${Object.entries(USERS).map(([k, v]) => `('${v}', '${k.toLowerCase()}@e2.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())`).join(',\n')}
on conflict (id) do nothing;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta) values
${Object.entries(USERS).map(([k, v]) => `('${v}', 'E2 ${k}', 'e2_${k.toLowerCase()}', true, 'activa')`).join(',\n')}
on conflict (usuario_id) do update set estado_cuenta = 'activa';
update public.intentos_examen set estado = 'abandonado' where estado = 'en_progreso' and usuario_id in (${Object.values(USERS).map((v) => `'${v}'`).join(',')});
set session_replication_role = origin;`);

const signedForExpiry = [];

// ---------- SIGNED_URL ----------
await scenario('SIGNED_URL', async () => {
  const s = await start('C', EX.IMAGEN); const a = s.json.intento.id;
  const acc = await resource('C', a, RES.IMG);
  check('SIGNED_URL.access_no_store', acc.status === 200 && acc.headers.get('cache-control') === 'no-store', `${acc.status} cache=${acc.headers.get('cache-control')}`);
  const ttl = (Date.parse(acc.json.expiresAt) - Date.now()) / 1000;
  check('SIGNED_URL.expires_at_about_300s', ttl > 280 && ttl <= 301, `ttl=${ttl.toFixed(0)}s`);
  const u = new URL(acc.json.url);
  const token = u.searchParams.get('token');
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  check('SIGNED_URL.token_scoped_to_one_object_300s', claims.exp - claims.iat === 300 && claims.url === `recursos-pregunta/preguntas/${q(3, 1)}/${RES.IMG}.png`,
    `exp-iat=${claims.exp - claims.iat} scope=${claims.url ? 'one object' : 'none'}`);
  const ok = await fetch(acc.json.url);
  check('SIGNED_URL.fetch_ok', ok.status === 200, `${ok.status}`);
  const tampered = new URL(acc.json.url); tampered.searchParams.set('token', token.slice(0, -2) + (token.endsWith('AA') ? 'BB' : 'AA'));
  const t = await fetch(tampered);
  check('SIGNED_URL.tampered_token_denied', t.status >= 400, `${t.status}`);
  const swapped = acc.json.url.replace(`${RES.IMG}.png`, `${RES.AUD}.wav`).replace(q(3, 1), q(4, 1));
  const sw = await fetch(swapped);
  check('SIGNED_URL.path_swap_denied', sw.status >= 400, `${sw.status}`);
  const otherBucket = acc.json.url.replace('/recursos-pregunta/', '/avatars/');
  const ob = await fetch(otherBucket);
  check('SIGNED_URL.bucket_swap_denied', ob.status >= 400, `${ob.status}`);
  const noToken = new URL(acc.json.url); noToken.search = '';
  check('SIGNED_URL.without_token_denied', (await fetch(noToken)).status >= 400);
  const again = await resource('C', a, RES.IMG);
  check('SIGNED_URL.each_request_signs_anew_same_version', again.status === 200 && again.json.version === acc.json.version, `version stable=${again.json.version === acc.json.version}`);
  signedForExpiry.push({ url: acc.json.url, expiresAt: acc.json.expiresAt });
});

// ---------- RESOURCE_SECURITY: cross-attempt, cross-user, unknown, states ----------
await scenario('RESOURCE', async () => {
  const sImg = await start('C', EX.IMAGEN); const aImg = sImg.json.intento.id;
  const sNorm = await start('C', EX.NORMAL); const aNorm = sNorm.json.intento.id;
  const cross = await resource('C', aNorm, RES.IMG);
  check('RESOURCE.cross_attempt_same_user_404', cross.status === 404 && cross.json.code === 'RESOURCE_NOT_FOUND', `${cross.status} ${cross.json?.code}`);
  const foreign = await resource('D', aImg, RES.IMG);
  const ghost = await resource('D', crypto.randomUUID(), RES.IMG);
  check('RESOURCE.cross_user_404_indistinguishable', foreign.status === 404 && foreign.json.code === 'ATTEMPT_NOT_FOUND' && ghost.status === 404 &&
    foreign.json.code === ghost.json.code && foreign.json.message === ghost.json.message, `${foreign.status} ${foreign.json?.code} vs ${ghost.json?.code}`);
  const unknown = await resource('C', aImg, crypto.randomUUID());
  check('RESOURCE.unknown_id_404', unknown.status === 404 && unknown.json.code === 'RESOURCE_NOT_FOUND', `${unknown.status}`);
  const pdf = await resource('C', (await start('C', EX.AUDIO)).json.intento.id, RES.PDF);
  check('RESOURCE.out_of_v1_type_404', pdf.status === 404 && pdf.json.code === 'RESOURCE_NOT_FOUND', `${pdf.status}`);
  const bad = await call('C', 'GET', `/api/v1/app/intentos/${aImg}/recursos/..%2F..%2Fsecret`);
  check('RESOURCE.non_uuid_id_400', bad.status === 400, `${bad.status}`);
  const premium = await start('C', EX.PREMIUM);
  check('RESOURCE.premium_start_denied', premium.status === 403 && premium.json.code === 'EXAM_LOCKED', `${premium.status} ${premium.json?.code}`);
  const dOwn = await start('D', EX.IMAGEN);
  const dAcc = await resource('D', dOwn.json.intento.id, RES.IMG);
  check('RESOURCE.other_user_own_attempt_ok', dAcc.status === 200, `${dAcc.status}`);
  for (const r of [cross, foreign, unknown, pdf, bad]) {
    if (leaksStorage(r.text)) check('RESOURCE.error_body_leaks_storage', false, r.text.slice(0, 120));
  }
  check('RESOURCE.error_bodies_without_storage_details', ![cross, foreign, unknown, pdf, bad].some((r) => leaksStorage(r.text)));
  const pg = await call('C', 'GET', `/api/v1/app/intentos/${aImg}/preguntas`);
  check('RESOURCE.metadata_without_storage_path', pg.status === 200 && !leaksStorage(pg.text), `${pg.status}`);
  await finish('C', aImg);
  const afterFinish = await resource('C', aImg, RES.IMG);
  check('RESOURCE.finished_attempt_still_owner_only', afterFinish.status === 200 && (await resource('D', aImg, RES.IMG)).status === 404, `${afterFinish.status}`);
  await abandon('C', aNorm);
});

// ---------- ANSWER_LEAKAGE on the wire ----------
await scenario('LEAKAGE', async () => {
  const s = await start('C', EX.NORMAL); const a = s.json.intento.id;
  const pg = await call('C', 'GET', `/api/v1/app/intentos/${a}/preguntas`);
  check('LEAKAGE.open_page_no_solution', pg.status === 200 && !/solucion|razonamiento|opcionCorrecta|esCorrecta|explanation|correctAnswer/.test(pg.text), `${pg.status}`);
  const wrong = await answer('C', a, q(1, 1), o(1, 1, 2));
  const locked = await answer('C', a, q(1, 1), o(1, 1, 3));
  check('LEAKAGE.rejected_change_reveals_nothing', locked.status === 409 && locked.json.code === 'ANSWER_LOCKED' && !/opcionCorrecta|esCorrecta/.test(locked.text), `${locked.status}`);
  const replay = await answer('C', a, q(1, 1), o(1, 1, 2));
  check('LEAKAGE.replay_same_correction', wrong.status === 200 && replay.status === 200 && replay.json.opcionCorrectaId === wrong.json.opcionCorrectaId && replay.json.esCorrecta === wrong.json.esCorrecta, `${wrong.status}/${replay.status}`);
  const skip = await answer('C', a, q(1, 2), null);
  check('LEAKAGE.skip_reveals_nothing', skip.status === 200 && skip.json.estado === 'SKIPPED' && skip.json.opcionCorrectaId === undefined && skip.json.esCorrecta === undefined, skip.text.slice(0, 120));
  const review = await call('C', 'GET', `/api/v1/app/intentos/${a}/revision`);
  const result = await call('C', 'GET', `/api/v1/app/intentos/${a}/resultado`);
  check('LEAKAGE.review_and_result_closed_while_open', review.status === 409 && result.status === 409, `${review.status}/${result.status}`);
  await abandon('C', a);
});

// ---------- SESSION_LOSS on the server ----------
await scenario('SESSION', async () => {
  const s = await start('F', EX.NORMAL); const a = s.json.intento.id;
  await answer('F', a, q(1, 1), o(1, 1, 1));
  sql(`update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${USERS.F}';`);
  const blocked = await answer('F', a, q(1, 2), o(1, 2, 1));
  const blockedRead = await call('F', 'GET', `/api/v1/app/intentos/${a}`);
  const blockedFinish = await finish('F', a);
  const row = sql(`select estado || '|' || total_respondidas from public.intentos_examen where id = '${a}';`);
  check('SESSION.blocked_account_denied_attempt_untouched', blocked.status === 403 && blocked.json.code === 'ACCOUNT_BLOCKED' && blockedRead.status === 403 && blockedFinish.status === 403 && row === 'en_progreso|1',
    `${blocked.status} ${blocked.json?.code} read=${blockedRead.status} finish=${blockedFinish.status} row=${row}`);
  sql(`update public.perfiles_usuario set estado_cuenta = 'activa' where usuario_id = '${USERS.F}';`);
  const resumed = await answer('F', a, q(1, 2), o(1, 2, 1));
  check('SESSION.reactivated_continues_same_attempt', resumed.status === 200 && resumed.json.respondidas === 2, `${resumed.status} ${resumed.json?.respondidas}`);
  const expired = await answer('C_EXPIRED', a, q(1, 3), o(1, 3, 1));
  const noAuth = await answer(null, a, q(1, 3), o(1, 3, 1));
  const garbage = await answer('not.a.jwt', a, q(1, 3), o(1, 3, 1));
  const rows = sql(`select count(*) from public.respuestas_intento where intento_id = '${a}' and pregunta_id = '${q(1, 3)}';`);
  check('SESSION.expired_missing_garbage_token_401_nothing_written', expired.status === 401 && noAuth.status === 401 && garbage.status === 401 && rows === '0', `${expired.status}/${noAuth.status}/${garbage.status} rows=${rows}`);
  await abandon('F', a);
});

// ---------- IDEMPOTENCY / CONCURRENCY (real DB) ----------
await scenario('CONCURRENCY', async () => {
  const s = await start('D', EX.GRANDE); const a = s.json.intento.id;
  const k = key();
  const same = await Promise.all(Array.from({ length: 20 }, () => answer('D', a, q(6, 1), o(6, 1, 2), 0, k)));
  const ok = same.filter((r) => r.status === 200);
  const rows = sql(`select count(*) || '|' || max(revision) from public.respuestas_intento where intento_id = '${a}' and pregunta_id = '${q(6, 1)}';`);
  check('CONCURRENCY.same_key_20_parallel_one_row', rows === '1|1' && same.every((r) => r.status === 200 || (r.status === 409 && r.json.code === 'IDEMPOTENCY_KEY_IN_PROGRESS')) &&
    new Set(ok.map((r) => `${r.json.esCorrecta}|${r.json.opcionCorrectaId}|${r.json.revision}`)).size === 1, `rows=${rows} statuses=${[...new Set(same.map((r) => r.status))]}`);
  // Item 8: connection dropped at a random moment after the request left; retry with the SAME key -> one row, one correction.
  let consistent = 0; const trials = 15; const bad = [];
  for (let i = 0; i < trials; i++) {
    const question = q(6, 10 + i); const option = o(6, 10 + i, 1 + (i % 4)); const kk = key();
    const ac = new AbortController(); setTimeout(() => ac.abort(), 2 + (i * 7) % 45);
    try { await fetch(`${KTOR}/api/v1/app/intentos/${a}/respuestas/${question}`, { method: 'PUT', signal: ac.signal,
      headers: { Authorization: `Bearer ${tokens.D}`, 'Content-Type': 'application/json', 'Idempotency-Key': kk },
      body: JSON.stringify({ opcionSeleccionadaId: option, tiempoRespuestaMs: 1500, baseRevision: 0 }) }); } catch { /* aborted */ }
    let retry; for (let t = 0; t < 10; t++) { retry = await answer('D', a, question, option, 0, kk); if (retry.status !== 409 || retry.json.code !== 'IDEMPOTENCY_KEY_IN_PROGRESS') break; await sleep(300); }
    const again = await answer('D', a, question, option, 0, kk);
    const n = sql(`select count(*) || '|' || coalesce(max(revision), 0) from public.respuestas_intento where intento_id = '${a}' and pregunta_id = '${question}';`);
    if (retry.status === 200 && again.status === 200 && n === '1|1' && retry.json.opcionCorrectaId === again.json.opcionCorrectaId) consistent++; else bad.push(`${i}:${retry.status}/${again.status}/${n}`);
  }
  check('CONCURRENCY.abort_after_send_retry_same_key_exactly_once', consistent === trials, `${consistent}/${trials} ${bad.slice(0, 3).join(' ')}`);
  // finish || abandon race: one terminal state, never both.
  const s2 = await start('E', EX.NORMAL); const a2 = s2.json.intento.id;
  const race = await Promise.all([...Array.from({ length: 5 }, () => finish('E', a2)), ...Array.from({ length: 5 }, () => abandon('E', a2))]);
  const st = sql(`select estado || '|' || (select count(*) from public.abandonos_examen where intento_id = '${a2}') from public.intentos_examen where id = '${a2}';`);
  const fin = race.slice(0, 5).filter((r) => r.status === 200).length; const ab = race.slice(5).filter((r) => r.status === 204).length;
  check('CONCURRENCY.finish_vs_abandon_one_terminal_state', (st === 'finalizado|0' && ab === 0 && fin === 5) || (st === 'abandonado|1' && fin === 0 && ab === 5),
    `state=${st} finish200=${fin} abandon204=${ab} statuses=${race.map((r) => r.status)}`);
  // Two devices start + answer the same question with different options at once.
  const s3 = await start('E', EX.GRANDE); const a3 = s3.json.intento.id;
  const both = await Promise.all([answer('E', a3, q(6, 2), o(6, 2, 1)), answer('E', a3, q(6, 2), o(6, 2, 3))]);
  const n3 = sql(`select count(*) from public.respuestas_intento where intento_id = '${a3}' and pregunta_id = '${q(6, 2)}';`);
  check('CONCURRENCY.two_devices_different_options_one_wins', n3 === '1' && both.filter((r) => r.status === 200).length === 1 && both.some((r) => r.status === 409),
    `${both.map((r) => `${r.status} ${r.json?.code ?? ''}`)} rows=${n3}`);
  await abandon('D', a); await abandon('E', a3);
});

// ---------- PAYLOAD LIMITS ----------
await scenario('PAYLOAD', async () => {
  const s = await start('C', EX.NORMAL); const a = s.json.intento.id;
  const big = await call('C', 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q(1, 1)}`, JSON.stringify({ opcionSeleccionadaId: o(1, 1, 1), tiempoRespuestaMs: 1, baseRevision: 0, pad: 'x'.repeat(2 * 1024 * 1024) }), key());
  check('PAYLOAD.body_over_1MiB_413', big.status === 413, `${big.status}`);
  const unknown = await answer('C', a, q(1, 1), o(1, 1, 1), 0, key(), { esCorrecta: true });
  check('PAYLOAD.client_grading_field_rejected', unknown.status === 400, `${unknown.status} ${unknown.json?.code}`);
  const badKey = await call('C', 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q(1, 1)}`, { opcionSeleccionadaId: o(1, 1, 1), tiempoRespuestaMs: 1, baseRevision: 0 }, 'not-a-uuid');
  check('PAYLOAD.bad_idempotency_key_400', badKey.status === 400, `${badKey.status} ${badKey.json?.code}`);
  const badOpt = await answer('C', a, q(1, 1), "' or 1=1 --");
  check('PAYLOAD.non_uuid_option_400', badOpt.status === 400, `${badOpt.status}`);
  const lim = await call('C', 'GET', `/api/v1/app/intentos/${a}/preguntas?limit=1000`);
  const cur = await call('C', 'GET', `/api/v1/app/intentos/${a}/preguntas?cursor=${encodeURIComponent('{"k":[1]}')}`);
  check('PAYLOAD.page_limit_clamped_and_cursor_validated', lim.status === 200 && lim.json.items.length <= 20 && cur.status === 400 && cur.json.code === 'INVALID_CURSOR', `limit=1000 -> ${lim.status} items=${lim.json?.items?.length} cursor=${cur.status} ${cur.json?.code}`);
  const huge = await answer('C', a, q(1, 1), o(1, 1, 1), 0, key(), { tiempoRespuestaMs: 2147483647, nivelConfianza: 2147483647 });
  const stored = sql(`select tiempo_respuesta_ms from public.respuestas_intento where intento_id = '${a}' and pregunta_id = '${q(1, 1)}';`);
  check('PAYLOAD.client_time_clamped_to_server_gap', huge.status === 200 && Number(stored) < 10 * 60 * 1000, `stored=${stored}ms`);
  await abandon('C', a);
});

// ---------- RATE LIMIT (isolated limiter of this Ktor) ----------
await scenario('RATE', async () => {
  const s = await start('E', EX.IMAGEN); const a = s.json.intento.id;
  const reads = [];
  for (let i = 0; i < 125; i++) reads.push(await call('E', 'GET', `/api/v1/app/intentos/${a}`));
  const first429 = reads.findIndex((r) => r.status === 429);
  const r429 = reads[first429];
  check('RATE.read_limited_after_120_per_min_with_retry_after', first429 >= 115 && first429 <= 121 && !!r429?.headers.get('retry-after') && r429.json.code === 'RATE_LIMITED',
    `first429=#${first429 + 1} retryAfter=${r429?.headers.get('retry-after')}`);
  const res = [];
  for (let i = 0; i < 305; i++) res.push((await resource('E', a, RES.IMG)).status);
  const firstRes429 = res.indexOf(429);
  check('RATE.resource_limited_after_300_per_min', firstRes429 >= 295 && firstRes429 <= 301, `first429=#${firstRes429 + 1}`);
  const other = await call('C', 'GET', `/api/v1/app/intentos/${(await start('C', EX.NORMAL)).json.intento.id}`);
  check('RATE.limit_is_per_user', other.status === 200, `${other.status}`);
});

// ---------- VERSION / IMMUTABLE_REVIEW on an E2-OWN exam fixture (never edits the shared fixtures; safe while another holds STACK_LOCK) ----------
if (CONTENT) {
  await scenario('VERSION', async () => {
    const EXV = 'e2e20000-0000-4000-8000-0000000000e2';
    const Q1 = 'f2e20000-0000-4000-8000-000000000001'; const Q2 = 'f2e20000-0000-4000-8000-000000000002';
    const opt = (n, l) => `02e20000-0000-4000-8${l}00-${String(n).padStart(12, '0')}`;
    const R_OLD = '5e2e0000-0000-4000-8000-000000000001'; const R_NEW = '5e2e0000-0000-4000-8000-000000000002';
    const pathOf = (r) => `preguntas/${Q1}/${r}.png`;
    // Bytes of the shared IMG (read through a signed URL) uploaded as E2's own object.
    const src = await start('C', EX.IMAGEN);
    const png = Buffer.from(await (await fetch((await resource('C', src.json.intento.id, RES.IMG)).json.url)).arrayBuffer());
    const up = await fetch(`${GATEWAY}/storage/v1/object/recursos-pregunta/${pathOf(R_OLD)}`, { method: 'POST',
      headers: { Authorization: `Bearer ${secrets.serviceKey}`, apikey: secrets.serviceKey, 'Content-Type': 'image/png', 'x-upsert': 'true' }, body: png });
    check('VERSION.setup_own_object', up.status === 200, `${up.status}`);
    sql(`set session_replication_role = replica;
update public.intentos_examen set estado = 'abandonado' where examen_id = '${EXV}' and estado = 'en_progreso';
delete from public.recursos_pregunta where id in ('${R_OLD}', '${R_NEW}');
delete from public.preguntas_examen where examen_id = '${EXV}';
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas)
values ('${EXV}', 'E2-VERSION', 'e5000000-0000-4000-8000-000000000001', 'E2 version', 'inmersivo', 'gratuito', true, now() - interval '1 day', 1)
on conflict (id) do update set activo = true;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values
  ('${Q1}', 'E2-Q1', 'inmersiva', 'E2 enunciado X', true), ('${Q2}', 'E2-Q2', 'inmersiva', 'E2 pregunta nueva Y', true)
on conflict (id) do update set titulo = excluded.titulo;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('02e20000-0000-4000-8' || l || '00-00000000000' || n)::uuid, ('f2e20000-0000-4000-8000-00000000000' || n)::uuid, chr(64 + l), 'Opcion ' || chr(64 + l), l
from generate_series(1, 2) n, generate_series(1, 3) l
on conflict (id) do nothing;
delete from public.opciones_pregunta where id = '${opt(1, 4)}';
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${Q1}', '${opt(1, 1)}', 'X'), ('${Q2}', '${opt(2, 1)}', 'Y')
on conflict (pregunta_id) do update set opcion_correcta_id = excluded.opcion_correcta_id;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('${EXV}', '${Q1}', 1, 1);
insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, bucket_storage, ruta_storage, posicion, metadatos)
values ('${R_OLD}', '${Q1}', 'E2-R1', 'imagen', 'Old', 'recursos-pregunta', '${pathOf(R_OLD)}', 1, '{"mime":"image/png","bytes":${png.length}}');
set session_replication_role = origin;`);
    const s = await start('C', EXV); const a = s.json.intento.id;
    check('VERSION.start_own_exam', s.status === 201, `${s.status} ${s.json?.code ?? ''}`);
    // Publish "Y" after the start: new resource, old one deactivated, statement edited, a 4th option + new solution, a new question linked.
    sql(`set session_replication_role = replica;
insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, bucket_storage, ruta_storage, posicion, metadatos)
values ('${R_NEW}', '${Q1}', 'E2-R2', 'imagen', 'New', 'recursos-pregunta', '${pathOf(R_NEW)}', 2, '{"mime":"image/png","bytes":70}');
update public.recursos_pregunta set activo = false where id = '${R_OLD}';
update public.preguntas set titulo = 'E2 enunciado Y' where id = '${Q1}';
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${opt(1, 4)}', '${Q1}', 'D', 'Opcion D nueva', 4);
update public.soluciones_pregunta set opcion_correcta_id = '${opt(1, 4)}' where pregunta_id = '${Q1}';
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('${EXV}', '${Q2}', 2, 2);
set session_replication_role = origin;`);
    const pg = await call('C', 'GET', `/api/v1/app/intentos/${a}/preguntas`);
    const it = pg.json.items;
    const ids = (it[0].recursos ?? []).map((r) => r.id);
    check('VERSION.pinned_attempt_keeps_revision_X', it.length === 1 && it[0].titulo === 'E2 enunciado X' && it[0].opciones.length === 3 && ids.join() === R_OLD,
      `items=${it.length} titulo=${it[0].titulo} opciones=${it[0].opciones.length} recursos=${ids}`);
    const hopRes = await resource('C', a, R_NEW);
    check('VERSION.hop_resource_of_Y_from_attempt_X_404', hopRes.status === 404 && hopRes.json.code === 'RESOURCE_NOT_FOUND', `${hopRes.status} ${hopRes.json?.code}`);
    const hopQ = await answer('C', a, Q2, opt(2, 1));
    check('VERSION.hop_question_of_Y_from_attempt_X_rejected', hopQ.status === 422 && hopQ.json.code === 'QUESTION_NOT_IN_ATTEMPT' && !/opcionCorrecta/.test(hopQ.text), `${hopQ.status} ${hopQ.json?.code}`);
    const hopO = await answer('C', a, Q1, opt(1, 4));
    check('VERSION.hop_option_of_Y_from_attempt_X_rejected', hopO.status === 422 && hopO.json.code === 'OPTION_NOT_IN_QUESTION' && !/opcionCorrecta/.test(hopO.text), `${hopO.status} ${hopO.json?.code}`);
    const oldOk = await resource('C', a, R_OLD);
    const oldBytes = oldOk.status === 200 ? (await fetch(oldOk.json.url)).status : 0;
    check('VERSION.deactivated_live_resource_still_served_to_pinned_attempt', oldOk.status === 200 && oldBytes === 200, `${oldOk.status} bytes=${oldBytes}`);
    const graded = await answer('C', a, Q1, opt(1, 1));
    check('VERSION.graded_with_X_solution', graded.status === 200 && graded.json.esCorrecta === true && graded.json.opcionCorrectaId === opt(1, 1), graded.text.slice(0, 120));
    const fin = await finish('C', a);
    const rv1 = await call('C', 'GET', `/api/v1/app/intentos/${a}/revision`); const rs1 = await call('C', 'GET', `/api/v1/app/intentos/${a}/resultado`);
    const rvRes = (rv1.json?.items?.[0]?.resources ?? []).map((r) => r.id).join();
    check('IMMUTABLE_REVIEW.review_shows_revision_X', fin.status === 200 && rv1.status === 200 && rv1.json.items.length === 1 && rv1.json.items[0].enunciado === 'E2 enunciado X' &&
      rv1.json.items[0].correctAnswer?.opcionId === opt(1, 1) && rvRes === R_OLD, `${rv1.status} ${rv1.json?.items?.[0]?.enunciado} res=${rvRes}`);
    sql(`set session_replication_role = replica;
update public.preguntas set titulo = 'E2 enunciado Z' where id = '${Q1}';
update public.soluciones_pregunta set opcion_correcta_id = '${opt(1, 2)}' where pregunta_id = '${Q1}';
delete from public.recursos_pregunta where id = '${R_OLD}';
set session_replication_role = origin;`);
    const rv2 = await call('C', 'GET', `/api/v1/app/intentos/${a}/revision`); const rs2 = await call('C', 'GET', `/api/v1/app/intentos/${a}/resultado`);
    const after = await resource('C', a, R_OLD);
    check('IMMUTABLE_REVIEW.unchanged_after_live_edit_and_delete', rv1.text === rv2.text && rs1.text === rs2.text, `review=${rv1.text === rv2.text} result=${rs1.text === rs2.text}`);
    check('IMMUTABLE_REVIEW.resource_of_finished_attempt_survives_live_delete', after.status === 200, `${after.status}`);
    const reopen = await start('C', EXV);
    const pgY = await call('C', 'GET', `/api/v1/app/intentos/${reopen.json.intento.id}/preguntas`);
    check('VERSION.reopen_creates_new_attempt_on_current_content', reopen.status === 201 && reopen.json.intento.id !== a && pgY.json.items.length === 2 && pgY.json.items[0].titulo === 'E2 enunciado Z',
      `${reopen.status} items=${pgY.json?.items?.length}`);
    const oldStill = await call('C', 'GET', `/api/v1/app/intentos/${a}`);
    check('FINISHED.reopen_never_resurrects', oldStill.json.intento.estado === 'finalizado', oldStill.json.intento.estado);
    await abandon('C', reopen.json.intento.id);
    sql(`update public.examenes set activo = false where id = '${EXV}';`);
  });
}

// ---------- SIGNED_URL expiry (slow: waits for expiresAt) ----------
if (EXPIRY && signedForExpiry.length) {
  const { url, expiresAt } = signedForExpiry[0];
  const wait = Date.parse(expiresAt) - Date.now() + 15_000;
  console.log(`waiting ${Math.round(wait / 1000)}s for the signed URL to expire...`);
  await sleep(Math.max(0, wait));
  const late = await fetch(url);
  check('SIGNED_URL.expired_url_denied', late.status >= 400, `${late.status}`);
}

fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), ktor: KTOR, content: CONTENT, results }, null, 2));
const failed = results.filter((r) => r.status === 'FAIL').length;
console.log(`\n${results.length - failed} PASS / ${failed} FAIL -> ${OUT}`);
process.exit(failed ? 1 : 0);
