#!/usr/bin/env node
// V1 (J6) LOCAL-ONLY subset of GoTrue so that REGISTER -> VERIFY E-MAIL -> LOGIN -> REFRESH works against the local stack.
// The real flow in Ktor (OposiBotsAuthGateway) calls /auth/v1/signup, /resend, /verify, /token and /logout; this process answers those
// and nothing else. It creates the same rows GoTrue would: a row in auth.users (the trigger trg_auth_user_created then creates the
// perfiles_usuario row, exactly like production). Tokens are ES256, signed with the local key whose JWKS the gateway already serves,
// so Ktor verifies them like any other local token.
//
// SAFETY (all enforced before anything is touched):
//   - refuses to start unless APP_ENV is EXACTLY "local" or "test" (strict equality, no normalisation), exit code 4;
//   - binds 127.0.0.1 only; the gateway reaches it through nginx (ops/local-exam/nginx.conf) on this machine;
//   - requires STACK_LOCK (stack-lock.mjs): it writes to the shared local DB;
//   - never prints or logs a password, token, code or e-mail address; the verification code of a signup is delivered ONLY through the
//     local mailbox file .local/mailbox.jsonl and GET http://127.0.0.1:9999/__local/mailbox?email=<address> (127.0.0.1 only).
// It is NOT part of the Ktor image and has no production counterpart.
//
//   STACK_HOLDER=E1 APP_ENV=local node ops/local-exam/gotrue-stub.mjs            (foreground)
//   STACK_HOLDER=E1 APP_ENV=local node ops/local-exam/run-gotrue-stub.mjs --detach | --stop
import { execFile } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

if (process.env.APP_ENV !== 'local' && process.env.APP_ENV !== 'test') {
  console.error('gotrue-stub: MUST_NOT_START_OUTSIDE_LOCAL: APP_ENV must be exactly "local" or "test".');
  process.exit(4);
}
requireStackLock('gotrue-stub.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const PORT = Number(process.env.GOTRUE_STUB_PORT ?? 9999);
import { GATEWAY as LOCAL_GATEWAY } from './ports.mjs';
const GATEWAY = process.env.GOTRUE_STUB_GATEWAY ?? LOCAL_GATEWAY;
const DB_CONTAINER = process.env.GOTRUE_STUB_DB_CONTAINER ?? 'oposi-exam-local-db-1';
// Belt and braces (E2-V1-R-REGISTER INFO-1): the stub writes only into the local stack's own database container.
if (!DB_CONTAINER.startsWith('oposi-exam-local-')) {
  console.error('gotrue-stub: the database container must be a local-stack container (oposi-exam-local-*).');
  process.exit(4);
}
const secrets = JSON.parse(fs.readFileSync(path.join(local, 'secrets.json'), 'utf8'));
const key = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
const mailboxFile = path.join(local, 'mailbox.jsonl');
const refreshTokens = new Map(); // refresh token -> { userId, email, used }

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');

// ---------- database (docker exec psql). Values NEVER travel as process arguments: each one is inlined into the SQL sent on
// stdin as a dollar-quoted literal with a random tag, so a password cannot show up in a process listing or be interpreted as SQL ----------
function sql(text, vars = {}) {
  const tag = 'q' + crypto.randomBytes(8).toString('hex');
  const literal = (v) => { const t = String(v); if (t.includes('$' + tag)) throw new Error('db'); return '$' + tag + '$' + t + '$' + tag + '$'; };
  const statement = text.replace(/:'(\w+)'/g, (_, name) => literal(vars[name]));
  return new Promise((resolve, reject) => {
    const args = ['exec', '-i', DB_CONTAINER, 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', '-f', '-'];
    const child = execFile('docker', args, { maxBuffer: 1 << 20 }, (error, stdout, stderr) => (error ? reject(new Error('db ' + String(stderr).split(/\r?\n/)[0].replace(/[^A-Za-z0-9: _.-]/g, '').slice(0, 60))) : resolve(stdout.trim())));
    child.stdin.end(statement);
  });
}

// ---------- session ----------
function accessToken(user) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const body = b64url(JSON.stringify({
    sub: user.id, email: user.email, role: 'authenticated', aud: 'authenticated', iss: `${GATEWAY}/auth/v1`, iat: now, exp: now + 3600,
    app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: {}, session_id: crypto.randomUUID(),
  }));
  const sig = crypto.sign('sha256', Buffer.from(`${head}.${body}`), { key, dsaEncoding: 'ieee-p1363' });
  return { token: `${head}.${body}.${b64url(sig)}`, exp: now + 3600 };
}
function session(user) {
  const { token, exp } = accessToken(user);
  const refresh = crypto.randomBytes(24).toString('base64url');
  refreshTokens.set(refresh, { userId: user.id, email: user.email, used: false });
  return {
    access_token: token, token_type: 'bearer', expires_in: 3600, expires_at: exp, refresh_token: refresh,
    user: { id: user.id, aud: 'authenticated', role: 'authenticated', email: user.email, email_confirmed_at: new Date().toISOString(),
      confirmed_at: new Date().toISOString(), app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: {} },
  };
}

// ---------- mailbox (local replacement of the SMTP server; the code never goes to a log) ----------
function deliver(email, type, code) {
  fs.appendFileSync(mailboxFile, JSON.stringify({ at: new Date().toISOString(), to: email, type, code }) + '\n');
}
function lastMail(email) {
  if (!fs.existsSync(mailboxFile)) return null;
  const rows = fs.readFileSync(mailboxFile, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)).filter((m) => m.to === email);
  return rows.at(-1) ?? null;
}

// ---------- helpers ----------
const json = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(body === undefined ? '' : JSON.stringify(body)); };
const gotrueError = (res, status, errorCode, msg) => json(res, status, { code: status, error_code: errorCode, msg });
async function readJson(req) {
  const chunks = []; let size = 0;
  for await (const c of req) { size += c.length; if (size > 16 * 1024) return null; chunks.push(c); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { return null; }
}
const normEmail = (e) => (typeof e === 'string' ? e.trim().toLowerCase() : '');
const validEmail = (e) => e.length > 3 && e.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e);

async function findUser(email) {
  const out = await sql(`select coalesce((select row_to_json(u)::text from (select id, email, email_confirmed_at is not null as confirmed from auth.users where lower(email) = :'email') u), '');`, { email });
  return out ? JSON.parse(out) : null;
}

const server = http.createServer(async (req, res) => {
  try {
    // DNS rebinding / a web page posting to localhost must not be able to create local users: only loopback-style Host headers.
    if (!/^(127\.0\.0\.1|localhost|host\.docker\.internal)(:\d{1,5})?$/.test(String(req.headers.host ?? ''))) {
      res.writeHead(403, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ code: 403, error_code: 'forbidden_host', msg: 'host not allowed' }));
    }
    const url = new URL(req.url, 'http://127.0.0.1');
    const p = url.pathname;
    // one line per request: method, path (no query string, so no value), final status. Never a body, a header or a value.
    res.on('finish', () => console.log(`${new Date().toISOString()} ${req.method} ${p} ${res.statusCode}`));
    if (req.method === 'GET' && p === '/__local/mailbox') {
      const email = normEmail(url.searchParams.get('email'));
      const mail = email ? lastMail(email) : null;
      return mail ? json(res, 200, mail) : json(res, 404, { error: 'no mail' });
    }
    if (req.method === 'GET' && p === '/__local/health') return json(res, 200, { ok: true });

    if (req.method === 'POST' && p === '/auth/v1/signup') {
      const body = await readJson(req);
      const email = normEmail(body?.email); const password = body?.password;
      if (!body || !validEmail(email) || typeof password !== 'string' || password.length < 6) {
        console.log('signup rejected: body keys =', Object.keys(body ?? {}).join(','), '| emailValid =', validEmail(email), '| passwordIsString =', typeof password === 'string');
        return gotrueError(res, 422, 'validation_failed', 'invalid signup data');
      }
      const existing = await findUser(email);
      const id = existing?.id ?? crypto.randomUUID();
      if (!existing) {
        const code = crypto.randomBytes(16).toString('hex');
        await sql(`insert into auth.users (instance_id, id, aud, role, email, encrypted_password, confirmation_token, confirmation_sent_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
          values ('00000000-0000-0000-0000-000000000000', :'id', 'authenticated', 'authenticated', :'email', extensions.crypt(:'password', extensions.gen_salt('bf')), :'code', now(),
                  '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb, now(), now());`, { id, email, password, code });
        deliver(email, 'signup', code);
      }
      // Enumeration-safe like GoTrue with confirmations on: the same 200 for a new and an existing address.
      return json(res, 200, { id, aud: 'authenticated', role: '', email, confirmation_sent_at: new Date().toISOString(), app_metadata: { provider: 'email', providers: ['email'] }, user_metadata: {} });
    }

    if (req.method === 'POST' && p === '/auth/v1/resend') {
      const body = await readJson(req); const email = normEmail(body?.email);
      if (validEmail(email) && (await findUser(email))?.confirmed === false) {
        const code = crypto.randomBytes(16).toString('hex');
        await sql(`update auth.users set confirmation_token = :'code', confirmation_sent_at = now() where lower(email) = :'email' and email_confirmed_at is null;`, { email, code });
        deliver(email, 'signup', code);
      }
      return json(res, 200, {});
    }

    if (req.method === 'POST' && p === '/auth/v1/verify') {
      const body = await readJson(req);
      const code = typeof body?.token_hash === 'string' ? body.token_hash : '';
      if (body?.type !== 'signup' || !/^[0-9a-f]{32}$/.test(code)) return gotrueError(res, 403, 'otp_expired', 'Email link is invalid or has expired');
      const out = await sql(`with u as (update auth.users set email_confirmed_at = now(), confirmation_token = null, last_sign_in_at = now()
        where confirmation_token = :'code' and email_confirmed_at is null returning id, email) select coalesce((select row_to_json(u)::text from u limit 1), '');`, { code });
      if (!out) return gotrueError(res, 403, 'otp_expired', 'Email link is invalid or has expired');
      return json(res, 200, session(JSON.parse(out)));
    }

    if (req.method === 'POST' && p === '/auth/v1/token') {
      const grant = url.searchParams.get('grant_type');
      const body = await readJson(req);
      if (grant === 'password') {
        const email = normEmail(body?.email); const password = body?.password;
        if (!validEmail(email) || typeof password !== 'string') return gotrueError(res, 400, 'invalid_credentials', 'Invalid login credentials');
        const out = await sql(`select coalesce((select row_to_json(u)::text from (select id, email, email_confirmed_at is not null as confirmed,
          encrypted_password = extensions.crypt(:'password', encrypted_password) as ok from auth.users where lower(email) = :'email') u), '');`, { email, password });
        const row = out ? JSON.parse(out) : null;
        if (!row || !row.ok) return gotrueError(res, 400, 'invalid_credentials', 'Invalid login credentials');
        if (!row.confirmed) return gotrueError(res, 400, 'email_not_confirmed', 'Email not confirmed');
        await sql(`update auth.users set last_sign_in_at = now() where id = :'id';`, { id: row.id });
        return json(res, 200, session(row));
      }
      if (grant === 'refresh_token') {
        const entry = typeof body?.refresh_token === 'string' ? refreshTokens.get(body.refresh_token) : undefined;
        if (!entry || entry.used) return gotrueError(res, 400, 'refresh_token_not_found', 'Invalid Refresh Token: Refresh Token Not Found');
        entry.used = true;
        return json(res, 200, session({ id: entry.userId, email: entry.email }));
      }
      return gotrueError(res, 400, 'unsupported_grant_type', 'unsupported grant type');
    }

    if (req.method === 'POST' && p === '/auth/v1/logout') return json(res, 204);
    if (req.method === 'POST' && p === '/auth/v1/recover') return json(res, 200, {}); // local: no mail is sent

    return gotrueError(res, 404, 'not_found', 'not available in the local stack');
  } catch (error) {
    console.error('gotrue-stub failure:', String(error?.message ?? error).slice(0, 100)); // only the SQLSTATE class of a db error, never a value
    return gotrueError(res, 500, 'unexpected_failure', 'local stub failure');
  }
});

server.listen(PORT, '127.0.0.1', () => console.log(`gotrue-stub (LOCAL ONLY) listening on 127.0.0.1:${PORT}`));
