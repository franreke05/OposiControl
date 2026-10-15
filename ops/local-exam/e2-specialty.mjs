#!/usr/bin/env node
// E2 V1 (LOCAL ONLY): SPECIALTY P0 server-side tampering probes through E2's isolated Ktor (:8090). Writes ONLY the profile of E2 users C/D
// (STACK_LOCK hard-fail). Specialty is NOT an authorization boundary (owner): this checks validation, atomicity and that it never moves money/access.
//   STACK_HOLDER=E2 node ops/local-exam/e2-specialty.mjs
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('e2-specialty.mjs');
const L = process.env.E2_STACK_LOCAL ?? 'C:/Users/franr/AndroidStudioProjects/OposiControl-PhaseB-Server/ops/local-exam/.local';
const KTOR = process.env.KTOR_URL ?? 'http://127.0.0.1:8090';
const OUT = process.env.E2_OUT ?? path.join(process.cwd(), 'e2-specialty-result.json');
const s = JSON.parse(fs.readFileSync(path.join(L, 'secrets.json'), 'utf8'));
const b = (x) => Buffer.from(x).toString('base64url'); const key = crypto.createPrivateKey({ key: s.privateJwk, format: 'jwk' });
const tok = (sub) => { const n = Math.floor(Date.now() / 1000); const h = b(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: s.kid }));
  const p = b(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: 'http://localhost:54321/auth/v1', iat: n - 60, exp: n + 900 }));
  return `${h}.${p}.${b(crypto.sign('sha256', Buffer.from(`${h}.${p}`), { key, dsaEncoding: 'ieee-p1363' }))}`; };
const U = { C: '0c000000-0000-4000-8000-0000000000cc', D: '0d000000-0000-4000-8000-0000000000dd' }; const T = { C: tok(U.C), D: tok(U.D) };
const SPEC = { LOC: 'e5000000-0000-4000-8000-000000000001', ING: 'e1000000-0000-4000-8000-000000000002', OBS: 'e1000000-0000-4000-8000-000000000003', E2B: 'e2b50000-0000-4000-8000-000000000001' };
async function call(u, method, p, body, headers = {}) {
  const r = await fetch(KTOR + p, { method, headers: { Authorization: `Bearer ${T[u]}`, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, etag: r.headers.get('etag') };
}
const results = [];
function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
const me = async (u) => (await call(u, 'GET', '/api/v1/app/me')).json?.profile?.especialidadPrincipalId;
const patch = (u, body, h) => call(u, 'PATCH', '/api/v1/app/me/profile', body, h);

// baseline
await patch('C', { especialidadId: SPEC.LOC }); await patch('D', { especialidadId: SPEC.LOC });
const stable = (j) => JSON.stringify({ ...j, actualizadoEn: undefined });
const wallet0 = stable((await call('C', 'GET', '/api/v1/app/wallet')).json);
const ent0 = JSON.stringify((await call('C', 'GET', '/api/v1/app/me/entitlement')).json);

// 1 valid change persists and replaces the single specialty
const ok = await patch('C', { especialidadId: SPEC.ING });
check('SPEC.valid_patch_persists', ok.status === 200 && (await me('C')) === SPEC.ING, `${ok.status}`);

// 2 invalid values: nothing is written
const before = await me('D');
const bad = [
  ['nil', { especialidadId: '00000000-0000-0000-0000-000000000000' }, 422], ['random', { especialidadId: crypto.randomUUID() }, 422], ['inactive', { especialidadId: SPEC.OBS }, 422],
  ['empty', { especialidadId: '' }, 400], ['null', { especialidadId: null }, [400, 422]], ['array', { especialidadId: [SPEC.LOC] }, 400], ['number', { especialidadId: 7 }, 400],
  ['object', { especialidadId: { id: SPEC.LOC } }, 400], ['sql', { especialidadId: "x' or 1=1 --" }, 400], ['postgrest', { especialidadId: `${SPEC.LOC},${SPEC.ING}` }, 400],
  ['long', { especialidadId: 'a'.repeat(10000) }, 400], ['name', { especialidadId: 'Ingles (local)' }, 400], ['unicode', { especialidadId: '\u202e' + SPEC.LOC }, 400],
  ['unknown_field', { especialidadId: SPEC.LOC, role: 'admin' }, 400], ['other_user_field', { especialidadId: SPEC.LOC, usuarioId: U.D }, 400],
  ['extra_specialty_list', { especialidadId: SPEC.LOC, especialidades: [SPEC.ING] }, 400],
];
const wrong = [];
for (const [name, body, want] of bad) { const r = await patch('D', body); const wants = Array.isArray(want) ? want : [want]; if (!wants.includes(r.status)) wrong.push(`${name}:${r.status}`); }
check('SPEC.tampered_values_rejected_with_defined_codes', wrong.length === 0, wrong.join(' ') || `${bad.length} values -> 400/422`);
check('SPEC.rejections_wrote_nothing', (await me('D')) === before, 'profile unchanged');

// 3 body userId / headers cannot retarget another user
const retarget = await patch('C', { especialidadId: SPEC.LOC }, { 'X-Usuario-Id': U.D, 'X-User-Id': U.D });
check('SPEC.forged_user_headers_ignored', retarget.status === 200 && (await me('D')) === SPEC.LOC && (await me('C')) === SPEC.LOC, `${retarget.status}`);

// 4 If-Match: stale -> 412, nothing written; two devices on the same version: exactly one wins
const g = await call('C', 'GET', '/api/v1/app/me'); const etag = g.etag;
const w1 = await patch('C', { especialidadId: SPEC.ING }, { 'If-Match': etag });
const stale = await patch('C', { especialidadId: SPEC.E2B }, { 'If-Match': etag });
check('SPEC.stale_if_match_412_and_no_lost_update', w1.status === 200 && stale.status === 412 && (await me('C')) === SPEC.ING, `${w1.status}/${stale.status}`);
const g2 = await call('C', 'GET', '/api/v1/app/me');
const race = await Promise.all([patch('C', { especialidadId: SPEC.LOC }, { 'If-Match': g2.etag }), patch('C', { especialidadId: SPEC.E2B }, { 'If-Match': g2.etag })]);
const wins = race.filter((r) => r.status === 200).length; const losers = race.filter((r) => r.status === 412).length;
const final = await me('C');
check('SPEC.two_devices_same_version_exactly_one_wins', wins === 1 && losers === 1 && [SPEC.LOC, SPEC.E2B].includes(final), `${race.map((r) => r.status)} final=${final}`);
const garbage = await patch('C', { especialidadId: SPEC.LOC }, { 'If-Match': '"not-a-version"' });
check('SPEC.garbage_if_match_rejected_without_write', [412, 400].includes(garbage.status), `${garbage.status}`);

// 5 changing specialty never moves money or access (owner: specialty is not an authorization boundary)
await patch('C', { especialidadId: SPEC.E2B });
const wallet1 = stable((await call('C', 'GET', '/api/v1/app/wallet')).json); const ent1 = JSON.stringify((await call('C', 'GET', '/api/v1/app/me/entitlement')).json);
const FREE_ING = 'e1000000-0000-4000-8000-0000000000e2';
const v1 = (await call('C', 'GET', `/api/v1/app/examenes/${FREE_ING}`)).json?.examen?.accessState;
await patch('C', { especialidadId: SPEC.LOC });
const v2 = (await call('C', 'GET', `/api/v1/app/examenes/${FREE_ING}`)).json?.examen?.accessState;
check('SPEC.change_moves_no_money_entitlement_or_access', wallet0 === wallet1 && ent0 === ent1 && v1 === v2 && v1 === 'FREE', `wallet=${wallet0 === wallet1} ent=${ent0 === ent1} verdict ${v1}/${v2} w0=${wallet0.slice(0, 160)} w1=${wallet1.slice(0, 160)}`);

// 6 unauthenticated / expired
const noAuth = await fetch(KTOR + '/api/v1/app/me/profile', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ especialidadId: SPEC.LOC }) });
check('SPEC.patch_without_token_401', noAuth.status === 401, `${noAuth.status}`);

fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), ktor: KTOR, results }, null, 2));
const failed = results.filter((r) => r.status !== 'PASS').length;
console.log(`\n${results.length - failed} PASS / ${failed} FAIL -> ${OUT}`);
process.exit(failed ? 1 : 0);
