#!/usr/bin/env node
// E2 V1 (LOCAL ONLY): REGISTER path attacks (J6 server half) through E2's isolated Ktor (:8090) and the local GoTrue stub (:9999 via the
// gateway). Writes: new auth.users rows named e2reg-* (+ their profile rows) and, for the mail-guard cases, rows of
// solicitudes_acceso_admin for those users only. STACK_LOCK hard-fail. Never prints a password, code or e-mail address.
//   STACK_HOLDER=E2 node ops/local-exam/e2-register.mjs
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('e2-register.mjs');
const KTOR = process.env.KTOR_URL ?? 'http://127.0.0.1:8090';
const STUB = 'http://127.0.0.1:9999';
const OUT = process.env.E2_OUT ?? path.join(process.cwd(), 'e2-register-result.json');
const sql = (text) => execFileSync('docker', ['exec', '-i', 'oposi-exam-local-db-1', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
  { input: text, encoding: 'utf8' }).trim();
const SPEC = { LOC: 'e5000000-0000-4000-8000-000000000001', ING: 'e1000000-0000-4000-8000-000000000002', OBS: 'e1000000-0000-4000-8000-000000000003' };
const run = process.env.E2_RUN ?? crypto.randomBytes(3).toString('hex');
const PHASES = (process.env.E2_PHASE ?? 'R1,R2,R3,R4,R5a,R5b,R5c,R5d,R6,R7').split(',');
const phase = (p) => PHASES.includes(p);
const mail = (n) => `e2reg-${run}-${n}@e2.local`;
const PASSWORD = `Pw-${crypto.randomBytes(9).toString('base64url')}-ok1`; // 12..72 printable ASCII, never printed
const results = [];
function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
async function scenario(id, fn) { try { await fn(); } catch (e) { check(id, false, `exception: ${e.message}`); } }

async function call(method, p, body, headers = {}, raw = false) {
  const r = await fetch(KTOR + p, { method, headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers }, body: body === undefined ? undefined : (raw ? body : JSON.stringify(body)) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
}
const register = (email, password = PASSWORD) => call('POST', '/v1/auth/register', { email, password });
const mailbox = async (email) => { const r = await fetch(`${STUB}/__local/mailbox?email=${encodeURIComponent(email.trim().toLowerCase())}`); return r.status === 200 ? r.json() : null; };
const exchange = (code) => call('POST', '/v1/auth/email/exchange', { code });
const login = (email, password = PASSWORD) => call('POST', '/v1/auth/login', { email, password, keepSignedIn: false });
const userCount = (email) => Number(sql(`select count(*) from auth.users where lower(email) = lower('${email.replace(/'/g, "''")}');`));
const shape = (r) => JSON.stringify({ s: r.status, b: r.text, ct: r.headers.get('content-type'), cc: r.headers.get('cache-control') });
async function stubConfirmed(n) { // signup + verify straight on the stub (no Ktor register budget) -> {email, session}
  const email = mail(n);
  await fetch(`${STUB}/auth/v1/signup`, { method: 'POST', body: JSON.stringify({ email, password: PASSWORD }) });
  const m = await mailbox(email);
  const v = await fetch(`${STUB}/auth/v1/verify`, { method: 'POST', body: JSON.stringify({ type: 'signup', token_hash: m?.code ?? '' }) });
  const j = await v.json().catch(() => null);
  return { email, session: { accessToken: j?.access_token }, exchange: { status: v.status } };
}
async function confirmed(n) { // register + verify through the stub mailbox -> {email, session}
  const email = mail(n); const r = await register(email); const m = await mailbox(email);
  const ex = await exchange(m?.code ?? ''); return { email, register: r, session: ex.json, exchange: ex };
}

// ---------- R1 enumeration-safe register ----------
if (phase('R1')) await scenario('R1', async () => {
  const e = mail('enum'); const t0 = performance.now(); const a = await register(e); const tNew = performance.now() - t0; const t1 = performance.now(); const b = await register(e); const tDup = performance.now() - t1; // duplicate unconfirmed
  check('REGISTER.new_and_duplicate_unconfirmed_same_202', a.status === 202 && shape(a) === shape(b) && a.json?.verificationRequired === true, `${a.status}/${b.status}`);
  check('REGISTER.timing_new_vs_existing_info', true, `new=${tNew.toFixed(0)}ms existing=${tDup.toFixed(0)}ms (INFO: a timing side channel exists if the gap is large)`);
  const m1 = await mailbox(e); await exchange(m1.code);
  const c = await register(e); // duplicate confirmed
  check('REGISTER.duplicate_confirmed_same_202_and_no_new_mail', shape(a) === shape(c) && (await mailbox(e)).code === m1.code, `${c.status}`);
});

// ---------- R2 mail guard: held (pendiente / rechazada) vs approved ----------
if (phase('R2')) await scenario('R2', async () => {
  const held = mail('held');
  // Simulate an admin-access request holding the address: user row (unconfirmed) + pending request.
  const uid = crypto.randomUUID();
  sql(`insert into auth.users (instance_id, id, aud, role, email, encrypted_password, created_at, updated_at, raw_app_meta_data, raw_user_meta_data)
    values ('00000000-0000-0000-0000-000000000000', '${uid}', 'authenticated', 'authenticated', '${held}', 'x', now(), now(), '{}'::jsonb, '{}'::jsonb) on conflict do nothing;`);
  sql(`insert into public.solicitudes_acceso_admin (usuario_id, nombre, email, motivo, estado) values ('${uid}', 'E2 Held', '${held}', 'E2 test', 'pendiente') on conflict do nothing;`);
  const before = userCount(held); const r = await register(held); const reference = await register(mail('ref'));
  check('REGISTER.held_email_same_202_no_mail_no_new_user', r.status === 202 && shape(r) === shape(reference) && (await mailbox(held)) === null && userCount(held) === before, `${r.status} mail=${await mailbox(held) ? 'SENT' : 'none'}`);
  const rs = await call('POST', '/v1/auth/email/resend', { email: held }); const rc = await call('POST', '/v1/auth/recover', { email: held });
  check('REGISTER.held_email_resend_and_recover_silent', [200, 202, 204].includes(rs.status) && [200, 202, 204].includes(rc.status) && (await mailbox(held)) === null, `${rs.status}/${rc.status}`);
  // Session gate: a CONFIRMED user with a pending request must not get a consumer session.
  const g = mail('gate'); await register(g); const mg = await mailbox(g); const ex = await exchange(mg.code);
  const gid = sql(`select id from auth.users where lower(email) = '${g}';`);
  sql(`insert into public.solicitudes_acceso_admin (usuario_id, nombre, email, motivo, estado) values ('${gid}', 'E2 Gate', '${g}', 'E2 test', 'pendiente') on conflict do nothing;`);
  const refreshAfter = await call('POST', '/v1/auth/refresh', { refreshToken: ex.json?.session?.refreshToken ?? ex.json?.refreshToken ?? ex.json?.refresh_token ?? '' });
  check('REGISTER.session_gate_also_blocks_refresh', [401, 403].includes(refreshAfter.status) && !/accessToken|access_token/.test(refreshAfter.text), `${refreshAfter.status}`);
  const lg = await login(g);
  check('REGISTER.session_gate_pending_request_gets_no_session', ex.status === 200 && [401, 403].includes(lg.status) && !/access_token|accessToken/.test(lg.text), `exchange(before request)=${ex.status} login(after)=${lg.status}`);
  sql(`update public.solicitudes_acceso_admin set estado = 'aprobada', resuelto_en = now() where usuario_id = '${gid}';`);
  const lg2 = await login(g);
  check('REGISTER.approved_request_is_a_normal_account', lg2.status === 200, `${lg2.status}`);
});

// ---------- R3 login / enumeration ----------
if (phase('R3')) await scenario('R3', async () => {
  const e = mail('login'); await register(e);
  const before = await login(e); // unconfirmed
  const mm = await mailbox(e); await exchange(mm.code);
  const wrong = await login(e, PASSWORD + 'x'); const unknown = await login(mail('nobody'));
  check('LOGIN.unconfirmed_refused_no_session', [401, 403].includes(before.status) && !/access_token|accessToken/.test(before.text), `${before.status}`);
  check('LOGIN.wrong_password_and_unknown_email_same_answer', wrong.status === unknown.status && wrong.text.replace(/requestId":"[^"]*"/, '') === unknown.text.replace(/requestId":"[^"]*"/, ''), `${wrong.status}/${unknown.status}`);
  const ok = await login(e);
  check('LOGIN.ok_after_verification', ok.status === 200 && !!(ok.json?.session?.accessToken ?? ok.json?.accessToken ?? ok.json?.access_token), `${ok.status}`);
});

// ---------- R4 verification code ----------
if (phase('R4')) await scenario('R4', async () => {
  const e = mail('code'); await register(e); const m = await mailbox(e);
  const wrongCodes = [crypto.randomBytes(16).toString('hex'), '0'.repeat(32), m.code.toUpperCase(), ` ${m.code}`, `${m.code}x`, m.code.slice(1), '', "' or 1=1 --", 'a'.repeat(5000)];
  const bad = []; for (const c of wrongCodes) { const r = await exchange(c); if (r.status === 200 || r.text.includes(m.code)) bad.push(c.slice(0, 6)); }
  check('CODE.wrong_malformed_codes_rejected_without_echo', bad.length === 0, bad.join(',') || `${wrongCodes.length} rejected`);
  const ok = await exchange(m.code); const again = await exchange(m.code);
  check('CODE.valid_once_then_dead', ok.status === 200 && again.status !== 200, `${ok.status}/${again.status}`);
  const statuses = []; for (let i = 0; i < 30; i++) statuses.push((await exchange(crypto.randomBytes(16).toString('hex'))).status);
  check('CODE.guessing_is_rate_limited', statuses.includes(429), `first429=#${statuses.indexOf(429) + 1} of 30 (limit EMAIL_EXCHANGE)`);
});

// ---------- R5 credential policy at the server boundary (REGISTER is limited to 6 per IP per hour: sub-phases, restart Ktor between them) ----------
const pwCases = [
  ['11 chars', 'a'.repeat(11), 400], ['73 chars ascii', 'a'.repeat(73), 400], ['non-ascii 12 (24 bytes, server accepts; client is stricter)', 'ñ'.repeat(12), 202], ['40 x n-tilde = 80 bytes', 'ñ'.repeat(40), 400],
  ['blank', ' '.repeat(14), 400], ['72 ascii (limit)', 'Aa1-'.repeat(18), 202], ['12 ascii (limit)', 'Aa1-Aa1-Aa1-', 202],
];
const badEmails = ['a@b', 'a b@e2.local', 'a b c@e2.local', '@e2.local', 'a@@e2.local', `${'a'.repeat(330)}@e2.local`, '', ' @e2.local', 'ab@e2.local'];
if (phase('R5a')) await scenario('R5a', async () => {
  const wrong = []; for (const [name, pw, want] of pwCases.slice(0, 5)) { const r = await register(mail('pwa' + name.length), pw); if (r.status !== want) wrong.push(`${name}:${r.status}`); }
  check('POLICY.password_rules_enforced_before_gotrue_a', wrong.length === 0, wrong.join(' ') || '5 rejected cases -> 400');
});
if (phase('R5b')) await scenario('R5b', async () => {
  const wrong = []; for (const [name, pw, want] of pwCases.slice(5)) { const r = await register(mail('pwb' + name.length), pw); if (r.status !== want) wrong.push(`${name}:${r.status}`); }
  const badE = []; for (const em of badEmails.slice(0, 4)) { const r = await register(em); if (r.status !== 400) badE.push(`${JSON.stringify(em).slice(0, 12)}:${r.status}`); }
  check('POLICY.limit_passwords_accepted_b', wrong.length === 0, wrong.join(' ') || '12 and 72 ASCII accepted (202)');
  check('POLICY.malformed_emails_400_b', badE.length === 0, badE.join(' ') || '4 cases');
});
if (phase('R5c')) await scenario('R5c', async () => {
  const badE = []; for (const em of badEmails.slice(4, 6)) { const r = await register(em); if (r.status !== 400) badE.push(`${JSON.stringify(em).slice(0, 12)}:${r.status}`); }
  check('POLICY.malformed_emails_400_c', badE.length === 0, badE.join(' ') || '5 cases');
  const ctl = await register('ab@e2.local'); const nul = await register(' @e2.local');
  check('POLICY.control_characters_in_email_are_a_400_not_a_503', ctl.status === 400 && nul.status === 400, `bell=${ctl.status} nul=${nul.status} (E2-V1-S02: server must reject ISO control characters like the client canonicalEmail)`);
  const big = await call('POST', '/v1/auth/register', JSON.stringify({ email: mail('big'), password: PASSWORD, pad: 'x'.repeat(40_000) }), {}, true);
  check('POLICY.body_over_16KiB_rejected', [400, 413].includes(big.status), `${big.status}`);
});
if (phase('R5d')) await scenario('R5d', async () => {
  const unk = await call('POST', '/v1/auth/register', { email: mail('unk'), password: PASSWORD, role: 'admin' });
  check('POLICY.unknown_register_field_handled', [202, 400].includes(unk.status), `${unk.status} (INFO: ${unk.status === 202 ? 'ignored' : 'rejected'})`);
  const k = mail('kelvin').replace('e2reg', 'e2regK'); const ka = await register(k); const mk = await mailbox(k);
  check('POLICY.non_ascii_email_server_canonical_identity_info', ka.status === 202, `${ka.status} mailDeliveredToLowercasedAscii=${!!mk} (INFO: client ASCII-only lowercase would not match the link binding; ND-5)`);
});

// ---------- R6 register/profile with especialidadId (E2-V1-S01 re-review) ----------
if (phase('R6')) await scenario('R6', async () => {
  const profile = (token, body) => call('POST', '/v1/auth/register/profile', body, { Authorization: `Bearer ${token}` });
  const me = async (token) => (await call('GET', '/api/v1/app/me', undefined, { Authorization: `Bearer ${token}` })).json?.profile;
  const token = (c) => c.session?.session?.accessToken ?? c.session?.accessToken ?? c.session?.access_token;
  const A = await stubConfirmed('prof-a'); const ta = token(A);
  check('PROFILE.session_after_verification', !!ta, `exchange=${A.exchange.status}`);
  const base = { firstName: 'E2', lastName: 'Reg', username: `e2r${run}a` };
  const bad = [['malformed id', { especialidadId: 'not-a-uuid' }, [400]], ['empty id', { especialidadId: '' }, [400]], ['nil', { especialidadId: '00000000-0000-0000-0000-000000000000' }, [422]],
    ['random', { especialidadId: crypto.randomUUID() }, [422]], ['inactive', { especialidadId: SPEC.OBS }, [422]], ['sql', { especialidadId: "x' or 1=1 --" }, [400]],
    ['invalid id + valid name must NOT fall back', { especialidadId: crypto.randomUUID(), specialty: 'Local' }, [422]], ['neither', {}, [400]], ['blank name', { specialty: '  ' }, [400]]];
  const wrong = []; for (const [name, extra, want] of bad) { const r = await profile(ta, { ...base, ...extra }); if (!want.includes(r.status)) wrong.push(`${name}:${r.status}`); }
  const p0 = await me(ta);
  check('PROFILE.tampered_ids_rejected_defined_codes_and_nothing_written', wrong.length === 0 && !p0?.especialidadPrincipalId && p0?.onboardingCompleted !== true, wrong.join(' ') || `${bad.length} cases, profile untouched`);
  const both = await profile(ta, { ...base, specialty: 'Local', especialidadId: SPEC.ING }); const p1 = await me(ta);
  check('PROFILE.id_wins_over_name', both.status === 204 && p1?.especialidadPrincipalId === SPEC.ING, `${both.status} -> ${p1?.especialidadPrincipalId === SPEC.ING ? 'ING (id)' : p1?.especialidadPrincipalId}`);
  const B = await stubConfirmed('prof-b'); const tb = token(B);
  const nameOnly = await profile(tb, { firstName: 'E2', lastName: 'Reg', username: `e2r${run}b`, specialty: 'Local' }); const p2 = await me(tb);
  check('PROFILE.legacy_name_only_still_resolves_exactly', nameOnly.status === 204 && p2?.especialidadPrincipalId === SPEC.LOC, `${nameOnly.status}`);
  const C = await stubConfirmed('prof-c'); const tc = token(C);
  const amb = await profile(tc, { firstName: 'E2', lastName: 'Reg', username: `e2r${run}c`, specialty: 'zzz-no-such' });
  check('PROFILE.unknown_name_rejected_never_guessed', amb.status === 400 || amb.status === 422, `${amb.status}`);
  const noTok = await call('POST', '/v1/auth/register/profile', { ...base, especialidadId: SPEC.LOC }); const garb = await profile('not.a.jwt', { ...base, especialidadId: SPEC.LOC });
  check('PROFILE.without_valid_token_401', noTok.status === 401 && garb.status === 401, `${noTok.status}/${garb.status}`);
  const badUser = await profile(tc, { firstName: 'E2', lastName: 'Reg', username: 'a b', especialidadId: SPEC.LOC });
  check('PROFILE.invalid_username_400', badUser.status === 400, `${badUser.status}`);
  const taken = await profile(tc, { firstName: 'E2', lastName: 'Reg', username: base.username, especialidadId: SPEC.LOC });
  check('PROFILE.taken_username_conflict_and_nothing_written', [409, 422].includes(taken.status) && !(await me(tc))?.especialidadPrincipalId, `${taken.status}`);
});

// ---------- R7 logs: no password, code or e-mail anywhere (stub log + this run's Ktor log is checked by the caller) ----------
if (phase('R7')) await scenario('R7', async () => {
  const stubLog = path.join('C:/Users/franr/AndroidStudioProjects/OposiControl-PhaseB-Server/ops/local-exam/.local/gotrue-stub.log');
  const text = fs.existsSync(stubLog) ? fs.readFileSync(stubLog, 'utf8') : '';
  check('LOG.stub_log_has_no_password_code_or_email', !text.includes(PASSWORD) && !text.includes(`e2reg-${run}`) && !/[0-9a-f]{32}/.test(text), `${text.split('\n').length} lines`);
});

fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), ktor: KTOR, results }, null, 2));
const failed = results.filter((r) => r.status !== 'PASS').length;
console.log(`\n${results.length - failed} PASS / ${failed} FAIL -> ${OUT}`);
process.exit(failed ? 1 : 0);
