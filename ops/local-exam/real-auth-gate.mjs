#!/usr/bin/env node
// REAL AUTH GATE (owner rule 2026-10-16): the bot-backend Auth routes against a REAL GoTrue (not the stub).
//   node ops/local-exam/up.mjs --real-auth      (gateway /auth/v1 -> GoTrue container, Mailpit on http://127.0.0.1:8025; the host stub must be stopped)
//   STACK_HOLDER=E1 node ops/local-exam/real-auth-gate.mjs
//   node ops/local-exam/up.mjs --stub-auth      (back to the fast path)
// PASS/FAIL lines are assertions about OUR contract; OBSERVED lines record how the real GoTrue differs from the stub (see E1-V1-REAL-AUTH-GATE.md).
// Never prints a password, token or code. Needs STACK_LOCK, the app Ktor (:8089). Test accounts *@exam.local are deleted at start (cascade) so the run repeats.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('real-auth-gate.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const KTOR = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const MAIL = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025';
const GATEWAY = 'http://localhost:54321';
const PASS = 'Real-Auth-Pass-0123456789';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };
const observed = (id, detail) => console.log(`OBSERVED  ${id}  ${detail}`);
const post = async (p, body, token) => {
  const r = await fetch(KTOR + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  return { status: r.status, json: await r.json().catch(() => null), headers: r.headers };
};
const get = async (p, token) => { const r = await fetch(KTOR + p, { headers: { Authorization: `Bearer ${token}` } }); return { status: r.status, json: await r.json().catch(() => null) }; };

// ---- the real mailbox: Mailpit API; the token_hash is the `token` parameter of the verify link in the mail
async function linkToken(email, type, since) {
  for (let i = 0; i < 20; i++) {
    const found = await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent('to:' + email)}`).then((r) => r.json()).catch(() => ({ messages: [] }));
    for (const m of found.messages ?? []) {
      if (Date.parse(m.Created) < since - 2000) continue;
      const full = await fetch(`${MAIL}/api/v1/message/${m.ID}`).then((r) => r.json());
      const text = (full.HTML || '') + ' ' + (full.Text || '');
      const url = text.match(/https?:\/\/[^\s"'<>]+\/verify\?[^\s"'<>]+/);
      if (!url) continue;
      const u = new URL(url[0].replace(/&amp;/g, '&'));
      if (u.searchParams.get('type') === type) return u.searchParams.get('token');
    }
    await sleep(500);
  }
  return null;
}
async function noMail(email, ms = 4000) {
  await sleep(ms);
  const found = await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent('to:' + email)}`).then((r) => r.json()).catch(() => ({ messages: [] }));
  return (found.messages ?? []).length === 0;
}

if (!(await fetch(MAIL + '/api/v1/info').then((r) => r.ok).catch(() => false)) || !(await fetch(GATEWAY + '/auth/v1/health').then((r) => r.ok).catch(() => false))) {
  console.error('real GoTrue / Mailpit are not up: run `node ops/local-exam/up.mjs --real-auth` first'); process.exit(2);
}
const health = await fetch(GATEWAY + '/auth/v1/health').then((r) => r.json());
observed('gotrue_version', `${health.name ?? ''} ${health.version ?? ''}`);
const E = (n) => `ra-${n}@exam.local`;
sql(`delete from auth.users where email like 'ra-%@exam.local' or email like 'ra%x@exam.local'; delete from public.solicitudes_acceso_admin where email like 'ra-%@exam.local';`);
await fetch(MAIL + '/api/v1/messages', { method: 'DELETE' }).catch(() => {});

// ---- register -> verify -> login -> refresh -> logout
const t0 = Date.now();
const reg = await post('/v1/auth/register', { email: E('main'), password: PASS });
check('REAL.register_accepted', reg.status === 202 || reg.status === 200, `${reg.status}`);
const dup = await post('/v1/auth/register', { email: E('main'), password: PASS });
check('REAL.duplicate_is_indistinguishable', dup.status === reg.status, `${dup.status}`);
const early = await post('/v1/auth/login', { email: E('main'), password: PASS });
check('REAL.login_before_verification_refused', early.status >= 400 && !early.json?.accessToken, `${early.status}`);
const code = await linkToken(E('main'), 'signup', t0);
check('REAL.verification_mail_arrives_with_a_token', !!code, code ? 'token present' : 'no mail');
const bad = await post('/v1/auth/email/exchange', { code: '0'.repeat(32) });
check('REAL.wrong_code_refused', bad.status >= 400 && !bad.json?.accessToken, `${bad.status}`);
const ex = await post('/v1/auth/email/exchange', { code });
const access = ex.json?.accessToken;
check('REAL.verify_email_gives_a_session', ex.status === 200 && !!access && ex.json.user?.email === E('main'), `${ex.status}`);
const replay = await post('/v1/auth/email/exchange', { code });
check('REAL.code_is_single_use', replay.status >= 400, `${replay.status}`);
const me = await get('/api/v1/app/me', access);
check('REAL.real_gotrue_jwt_is_accepted_by_ktor_es256_jwks', me.status === 200 && !!me.json?.profile, `${me.status}`);
const claims = JSON.parse(Buffer.from(access.split('.')[1], 'base64url').toString());
observed('jwt_claims', `alg/iss/aud/exp-iat = ${JSON.parse(Buffer.from(access.split('.')[0], 'base64url').toString()).alg} ${claims.iss} ${claims.aud} ${claims.exp - claims.iat}s; session_id=${!!claims.session_id}; is_anonymous=${claims.is_anonymous}`);
check('REAL.profile_row_created_by_the_auth_users_trigger', sql(`select count(*) from public.perfiles_usuario p join auth.users u on u.id = p.usuario_id where u.email = '${E('main')}'`) === '1');
const login = await post('/v1/auth/login', { email: E('main'), password: PASS });
check('REAL.login_after_verification', login.status === 200 && !!login.json?.accessToken && !!login.json?.refreshToken, `${login.status}`);
const wrong = await post('/v1/auth/login', { email: E('main'), password: PASS + 'x' });
check('REAL.wrong_password_refused', wrong.status === 401 && !wrong.json?.accessToken, `${wrong.status} ${wrong.json?.code}`);
const unknown = await post('/v1/auth/login', { email: E('nobody'), password: PASS });
check('REAL.unknown_email_looks_like_wrong_password', unknown.status === wrong.status && unknown.json?.code === wrong.json?.code, `${unknown.status} ${unknown.json?.code}`);
const r1 = await post('/v1/auth/refresh', { refreshToken: login.json.refreshToken });
check('REAL.refresh_rotates', r1.status === 200 && !!r1.json?.refreshToken && r1.json.refreshToken !== login.json.refreshToken, `${r1.status}`);
const reuse = await post('/v1/auth/refresh', { refreshToken: login.json.refreshToken });
check('REAL.old_refresh_token_is_dead', reuse.status >= 400, `${reuse.status}`);
const afterReuse = await post('/v1/auth/refresh', { refreshToken: r1.json.refreshToken });
observed('refresh_reuse_detection', `after the OLD token was replayed, the NEWER token answers ${afterReuse.status} (200 = family kept, 4xx = family revoked)`);

// concurrent refresh with the same token: at most one wins
const lg2 = await post('/v1/auth/login', { email: E('main'), password: PASS });
const race = await Promise.all([1, 2, 3].map(() => post('/v1/auth/refresh', { refreshToken: lg2.json.refreshToken })));
check('REAL.concurrent_refresh_at_most_one_wins', race.filter((r) => r.status === 200).length <= 1, race.map((r) => r.status).join(','));

// logout and revoked session
const lg3 = await post('/v1/auth/login', { email: E('main'), password: PASS });
const outRes = await post('/v1/auth/logout', {}, lg3.json.accessToken);
check('REAL.logout_ok', outRes.status === 204 || outRes.status === 200, `${outRes.status}`);
const dead = await post('/v1/auth/refresh', { refreshToken: lg3.json.refreshToken });
check('REAL.refresh_after_logout_is_dead', dead.status >= 400, `${dead.status}`);
const stale = await get('/api/v1/app/me', lg3.json.accessToken);
observed('revoked_session_access_token', `/me with the access token of a logged-out session answers ${stale.status} (200 = the JWT stays valid until exp; the stub never revokes)`);

// ---- recover / reset
const t1 = Date.now();
const rec = await post('/v1/auth/recover', { email: E('main') });
check('REAL.recover_is_always_204', rec.status === 204, `${rec.status}`);
const recUnknown = await post('/v1/auth/recover', { email: E('nobody') });
check('REAL.recover_unknown_is_also_204', recUnknown.status === 204);
const rtoken = await linkToken(E('main'), 'recovery', t1);
check('REAL.recovery_mail_arrives', !!rtoken);
const short = await post('/v1/auth/reset-password', { resetToken: rtoken, newPassword: 'Short-1' });
check('REAL.reset_with_a_password_below_12_is_refused', short.status === 400, `${short.status}`);
const tooLong = await post('/v1/auth/reset-password', { resetToken: rtoken, newPassword: 'A1' + 'x'.repeat(71) });
check('REAL.reset_with_73_bytes_is_refused', tooLong.status === 400, `${tooLong.status}`);
const NEW = 'New-Real-Pass-9876543210';
const reset = await post('/v1/auth/reset-password', { resetToken: rtoken, newPassword: NEW });
check('REAL.reset_with_the_mail_token', reset.status === 204, `${reset.status} ${reset.json?.code ?? ''}`);
const oldPw = await post('/v1/auth/login', { email: E('main'), password: PASS });
const newPw = await post('/v1/auth/login', { email: E('main'), password: NEW });
check('REAL.old_password_dead_new_password_works', oldPw.status >= 400 && newPw.status === 200, `${oldPw.status}/${newPw.status}`);
const reset2 = await post('/v1/auth/reset-password', { resetToken: rtoken, newPassword: NEW + 'z' });
check('REAL.reset_token_is_single_use', reset2.status >= 400, `${reset2.status}`);
const sessionAfterReset = await post('/v1/auth/refresh', { refreshToken: newPw.json.refreshToken });
observed('sessions_after_reset', `a refresh token issued after the reset answers ${sessionAfterReset.status}`);

// ---- password policy at register (ours: 12..72 bytes)
const pol = {};
for (const [n, pw] of [['p11', 'Aa1-aaaaaaa'], ['p12', 'Aa1-aaaaaaaa'], ['p72', 'Aa1-' + 'x'.repeat(68)], ['p73', 'Aa1-' + 'x'.repeat(69)]]) pol[n] = (await post('/v1/auth/register', { email: `ra-${n}@exam.local`, password: pw })).status;
check('REAL.password_policy_12_to_72', pol.p11 === 400 && pol.p73 === 400 && [200, 202].includes(pol.p12) && [200, 202].includes(pol.p72), JSON.stringify(pol));

// ---- e-mail canonicalisation (E2 flagged: Kelvin sign, non-ASCII, case)
const countLike = (pattern) => Number(sql(`select count(*) from auth.users where email like '${pattern}'`));
const canon = {};
canon.base = (await post('/v1/auth/register', { email: 'rak@exam.local', password: PASS })).status;
canon.upper = (await post('/v1/auth/register', { email: 'RAK@Exam.Local', password: PASS })).status;
canon.kelvin = (await post('/v1/auth/register', { email: 'raK@exam.local', password: PASS })).status;   // U+212A KELVIN SIGN lowercases to ASCII k
canon.dotless = (await post('/v1/auth/register', { email: 'raı@exam.local', password: PASS })).status;  // dotless i
canon.nonAscii = (await post('/v1/auth/register', { email: 'rañandú@exam.local', password: PASS })).status;
canon.fullwidth = (await post('/v1/auth/register', { email: 'ｒａk@exam.local', password: PASS })).status; // fullwidth "rak"
canon.trailingDot = (await post('/v1/auth/register', { email: 'rak@exam.local.', password: PASS })).status;
const rows = sql(`select email from auth.users where email ilike 'ra%k@exam.local' or email ilike 'ra%@exam.local' and email !~ '^ra-' order by 1`).split('\n').filter(Boolean);
observed('canonicalisation_statuses', JSON.stringify(canon));
observed('canonicalisation_rows', JSON.stringify(rows.map((e) => [...e].map((c) => (c.charCodeAt(0) > 127 ? `U+${c.codePointAt(0).toString(16).toUpperCase()}` : c)).join(''))));
check('REAL.case_variant_and_kelvin_sign_never_create_a_second_account_for_the_same_mailbox', countLike('rak@exam.local') + countLike('ra%k@exam.local') - countLike('rak@exam.local') <= 1
  && rows.filter((e) => /^ra[kK]@exam\.local$/i.test(e)).length <= 1, `${rows.length} rows`);
check('REAL.no_non_ascii_local_part_is_stored_unnormalised', rows.every((e) => /^[\x00-\x7f]+$/.test(e)), `${rows.length} rows`);

// ---- access-request hold: an e-mail with a non-approved admin access request gets the same 202 and NO mail
sql(`insert into public.solicitudes_acceso_admin (nombre, email, motivo, estado) values ('Hold', '${E('hold')}', 'test', 'pendiente')`);
const t2 = Date.now();
const held = await post('/v1/auth/register', { email: E('hold'), password: PASS });
check('REAL.access_request_hold_is_a_silent_success', held.status === reg.status, `${held.status}`);
check('REAL.access_request_hold_sends_no_mail_and_creates_no_user', (await noMail(E('hold'))) && countLike(E('hold')) === 0);
void t2;

// ---- rate limits (per account, our limiter in front of GoTrue's)
const codes = [];
for (let i = 0; i < 14; i++) codes.push((await post('/v1/auth/login', { email: E('limit'), password: 'Wrong-Pass-0123456' + i })).status);
check('REAL.login_rate_limit_kicks_in', codes.includes(429), codes.join(','));
const ra = codes.findIndex((c) => c === 429);
observed('login_rate_limit', `first 429 at attempt ${ra + 1}; GoTrue's own limits (email 30/h config here) sit behind ours`);
const resends = [];
for (let i = 0; i < 8; i++) resends.push((await post('/v1/auth/email/resend', { email: E('main') })).status);
observed('resend_statuses', resends.join(','));
check('REAL.resend_never_reveals_existence', new Set(resends).size <= 2 && !resends.some((s) => s >= 500), resends.join(','));

const failed = results.filter((r) => !r).length;
console.log(`\nREAL AUTH: ${results.length - failed}/${results.length} PASS`);
process.exit(failed ? 1 : 0);
