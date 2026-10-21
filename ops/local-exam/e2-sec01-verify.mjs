#!/usr/bin/env node
// E2 PHASE B (LOCAL ONLY): entitlement / catalogue / collection / wallet security probes through E2's isolated Ktor (e2-ktor.mjs,
// ktorControl port of stack-config.mjs) and, for the direct-database surface, PostgREST with an anon key and a user token minted from the LOCAL stack secrets.
// Only E2 users C/D and E2 fixtures (ids e2b...) are written; A/B and the shared fixtures are never touched. Nothing secret printed.
//   node ops/local-exam/e2-phaseb.mjs   -> PASS/FAIL/FINDING per check, json in E2_OUT
import { ADMIN_KTOR_URL, E2_LOCAL_DIR, GATEWAY, composeArgs } from './stack-config.mjs';
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('e2-sec01-verify');

// Arranca un Ktor / ejecuta sondas contra el stack compartido: falla (exit 3) ANTES de nada si STACK_LOCK es de otro.
requireStackLock('e2-sec01-verify.mjs');

const stackLocal = E2_LOCAL_DIR; // E2_STACK_LOCAL or this stack's own .local (stack-config.mjs)
const KTOR = (process.env.KTOR_URL ?? ADMIN_KTOR_URL);
const OUT = process.env.E2_OUT ?? path.join(process.cwd(), 'e2-phaseb-result.json');
const secrets = JSON.parse(fs.readFileSync(path.join(stackLocal, 'secrets.json'), 'utf8'));
const sql = (text) => execFileSync('docker', [...composeArgs(), 'exec', '-T', 'db', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
  { input: text, encoding: 'utf8' }).trim();

const b64url = (b) => Buffer.from(b).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
const jwk = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
function es256(sub) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const body = b64url(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: `${GATEWAY}/auth/v1`, iat: now - 60, exp: now + 3600 }));
  return `${head}.${body}.${b64url(crypto.sign('sha256', Buffer.from(`${head}.${body}`), { key: jwk, dsaEncoding: 'ieee-p1363' }))}`;
}
function hs256(sub) { // PostgREST verifies the LOCAL stack secret (HS256): the token a client would hold after login.
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iat: now - 60, exp: now + 3600 }));
  return `${head}.${body}.${b64url(crypto.createHmac('sha256', secrets.jwtSecret).update(`${head}.${body}`).digest())}`;
}

const U = { C: '0c000000-0000-4000-8000-0000000000cc', D: '0d000000-0000-4000-8000-0000000000dd' };
const T = { C: es256(U.C), D: es256(U.D) };
const SPEC_LOC = 'e5000000-0000-4000-8000-000000000001';
const SPEC_OTHER = 'e2b50000-0000-4000-8000-000000000001';
const EX = { FREE_OTHER: 'e2b00000-0000-4000-8000-000000000001', PREMIUM_OTHER: 'e2b00000-0000-4000-8000-000000000002', FUTURE: 'e2b00000-0000-4000-8000-000000000003',
  DRAFT: 'e2b00000-0000-4000-8000-000000000004' };
const Q = (n) => `f2b00000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const O = (n, l) => `02b00000-0000-4000-8${l}00-${String(n).padStart(12, '0')}`;

async function call(user, method, p, body, extra = {}) {
  const headers = { ...extra };
  if (user) headers.Authorization = `Bearer ${T[user] ?? user}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (['POST', 'PUT'].includes(method) && p.startsWith('/api/')) headers['Idempotency-Key'] = crypto.randomUUID();
  const r = await fetch((p.startsWith('http') ? '' : KTOR) + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
}
const results = [];
function check(id, ok, detail = '', finding = null) {
  const status = ok ? 'PASS' : (finding ? `FINDING ${finding}` : 'FAIL');
  results.push({ id, status, detail }); console.log(`${status}  ${id}${detail ? '  ' + detail : ''}`);
}
async function scenario(id, fn) { try { await fn(); } catch (e) { check(id, false, `exception: ${e.message}`); } }

// READ-ONLY verification of E1-SEC01 (E2-P02 / E2-P03): no SQL write, no attempt; uses E2 user D (existing) and the E2B fixtures
// already seeded by e2-phaseb.mjs. Run on a freshly started Ktor so the per-user buckets start empty.
// ---------- Public unauthenticated /v1 reads (shared profile) ----------
await scenario('PUBLIC_V1', async () => {
  const list = await call(null, 'GET', '/v1/examenes');
  const listed = (list.json?.data ?? []).map((e) => e.id);
  check('PUBLIC_V1.future_exam_not_listed', !listed.includes(EX.FUTURE), `${list.status} listed=${listed.includes(EX.FUTURE)}`, 'E2-P03');
  const det = await call(null, 'GET', `/v1/examenes/${EX.FUTURE}`);
  check('PUBLIC_V1.future_exam_detail_404', det.status === 404, `${det.status}`, 'E2-P03');
  const qs = await call(null, 'GET', `/v1/examenes/${EX.FUTURE}/preguntas`);
  check('PUBLIC_V1.future_exam_questions_not_served', qs.status !== 200 || !qs.text.includes('E2B enunciado'), `${qs.status} ${qs.text.includes('E2B enunciado') ? 'questions of a FUTURE exam served without auth' : ''}`, 'E2-P03');
  const draft = await call(null, 'GET', `/v1/examenes/${EX.DRAFT}/preguntas`);
  check('PUBLIC_V1.draft_exam_questions_not_served', draft.status === 404, `${draft.status}`);
  const prem = await call(null, 'GET', `/v1/examenes/${EX.PREMIUM_OTHER}/preguntas`);
  check('PUBLIC_V1.premium_exam_questions_forbidden', prem.status === 403 && !prem.text.includes('E2B enunciado'), `${prem.status}`);
  const free = await call(null, 'GET', `/v1/examenes/${EX.FREE_OTHER}/preguntas`);
  check('PUBLIC_V1.free_questions_without_solution', free.status === 200 && !/opcionCorrecta|esCorrecta|razonamiento|solucion/i.test(free.text), `${free.status}`);
});

// ---------- RATE LIMIT on the Phase B read routes (E2-P02 verification; dedicated user E, fresh buckets of THIS Ktor) ----------
await scenario('RATE', async () => {
  T.E = T.D;
  // Legitimate Home V1 load: D2-13 EN_CURSO + D2-13 ADQUIRIDOS + D6-01 in parallel, 5 times (5 app opens / pull-to-refresh).
  let home429 = 0;
  for (let i = 0; i < 5; i++) {
    const load = await Promise.all([call('E', 'GET', '/api/v1/app/me/examenes?vista=EN_CURSO&limit=20'), call('E', 'GET', '/api/v1/app/me/examenes?vista=ADQUIRIDOS&limit=20'), call('E', 'GET', '/api/v1/app/wallet')]);
    home429 += load.filter((r) => r.status === 429).length;
  }
  check('RATE.legit_home_loads_never_429', home429 === 0, `5 Home loads x 3 parallel calls -> 429s=${home429}`);
  // Catalog bucket (120/60 s shared by D2-01, D2-02, D2-13, D7-02): 10 used by Home, so the 429 must come by call #111.
  let first = -1; let r429 = null;
  for (let i = 1; i <= 125 && first < 0; i++) {
    const r = await call('E', 'GET', '/api/v1/app/examenes?limit=1');
    if (r.status === 429) { first = i; r429 = r; }
  }
  const ent = await call('E', 'GET', '/api/v1/app/me/entitlement');
  const det = await call('E', 'GET', `/api/v1/app/examenes/${EX.FREE_OTHER}`);
  check('RATE.catalog_bucket_429_with_retry_after_and_shared', first >= 105 && first <= 111 && r429.json?.code === 'RATE_LIMITED' && Number(r429.headers.get('retry-after')) >= 1 &&
    ent.status === 429 && det.status === 429, `first429=#${first} code=${r429?.json?.code} retryAfter=${r429?.headers.get('retry-after')} D7-02=${ent.status} D2-02=${det.status}`);
  // Wallet bucket (60/60 s shared by D6-01, D6-02): 5 used by Home.
  let wFirst = -1;
  for (let i = 1; i <= 70 && wFirst < 0; i++) if ((await call('E', 'GET', '/api/v1/app/wallet')).status === 429) wFirst = i;
  const mov = await call('E', 'GET', '/api/v1/app/wallet/movimientos?limit=1');
  check('RATE.wallet_bucket_429_and_shared', wFirst >= 50 && wFirst <= 56 && mov.status === 429, `first429=#${wFirst} D6-02=${mov.status}`);
  const other = await call('C', 'GET', '/api/v1/app/examenes?limit=1');
  check('RATE.limit_is_per_user', other.status === 200, `C=${other.status}`);
  const anon = await Promise.all(Array.from({ length: 150 }, () => call(null, 'GET', '/api/v1/public/especialidades')));
  check('RATE.d2_17_public_cached_not_user_limited', anon.every((x) => x.status === 200), `150 anon -> ${[...new Set(anon.map((x) => x.status))]}`);
});

// ---------- E2-P03 at the database: the 4 select policies require publicado_en <= now() ----------
await scenario('RLS_PUBLICATION', async () => {
  const rows = sql(`select tablename || '.' || policyname || '=' || (qual like '%publicado_en <= now()%') from pg_policies where schemaname = 'public' and policyname in
    ('examenes_select_activos', 'preguntas_examen_select_gratuitas', 'preguntas_select_gratuitas', 'opciones_pregunta_select_gratuitas') order by 1;`).split('\n');
  check('RLS_PUBLICATION.four_policies_require_publication_time', rows.length === 4 && rows.every((x) => x.endsWith('=true')), rows.join(' '));
});

const det = await call('C', 'GET', `/api/v1/app/examenes/${EX.FUTURE}`);
check('CANONICAL.future_exam_detail_404', det.status === 404, `${det.status}`);
fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), ktor: KTOR, results }, null, 2));
const failed = results.filter((r) => r.status !== 'PASS').length;
console.log(`\n${results.length - failed} PASS / ${failed} NOT PASS -> ${OUT}`);
process.exit(failed ? 1 : 0);
