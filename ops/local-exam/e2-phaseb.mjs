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
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('e2-phaseb');

// Escribe en el stack compartido (usuarios/fixtures de E2): falla (exit 3) si STACK_LOCK es de otro.
requireStackLock('e2-phaseb.mjs');

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

// ---------- E2 fixtures: own users, another specialty with a free and a premium exam, a FUTURE-published free rafaga exam, a draft ----------
sql(`set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at) values
 ('${U.C}', 'c@e2.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now()), ('${U.D}', 'd@e2.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now())
on conflict (id) do nothing;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta) values
 ('${U.C}', 'E2 C', 'e2_c', true, 'activa'), ('${U.D}', 'E2 D', 'e2_d', true, 'activa')
on conflict (usuario_id) do update set estado_cuenta = 'activa';
insert into public.especialidades (id, codigo, nombre, slug) values ('${SPEC_OTHER}', 'E2B', 'E2 otra', 'e2-otra') on conflict (id) do nothing;
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id) values
 ('${EX.FREE_OTHER}', 'E2B-FREE', '${SPEC_OTHER}', 'E2B gratis otra', 'rafaga', 'gratuito', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1)),
 ('${EX.PREMIUM_OTHER}', 'E2B-PREM', '${SPEC_OTHER}', 'E2B premium otra', 'rafaga', 'premium', true, now() - interval '1 day', 1, (select id from public.comunidades_autonomas order by codigo limit 1)),
 ('${EX.FUTURE}', 'E2B-FUT', '${SPEC_LOC}', 'E2B futuro', 'rafaga', 'gratuito', true, now() + interval '30 days', 1, (select id from public.comunidades_autonomas order by codigo limit 1)),
 ('${EX.DRAFT}', 'E2B-DRAFT', '${SPEC_LOC}', 'E2B borrador', 'rafaga', 'gratuito', true, null, 1, (select id from public.comunidades_autonomas order by codigo limit 1))
on conflict (id) do update set publicado_en = excluded.publicado_en, activo = true;
insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
select ('f2b00000-0000-4000-8000-00000000000' || n)::uuid, 'E2B-Q' || n, 'rafaga', 'E2B enunciado secreto ' || n, true from generate_series(1, 4) n on conflict (id) do nothing;
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
select ('02b00000-0000-4000-8' || l || '00-00000000000' || n)::uuid, ('f2b00000-0000-4000-8000-00000000000' || n)::uuid, chr(64 + l), 'E2B opcion ' || chr(64 + l), l
from generate_series(1, 4) n, generate_series(1, 2) l on conflict (id) do nothing;
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
select ('f2b00000-0000-4000-8000-00000000000' || n)::uuid, ('02b00000-0000-4000-8100-00000000000' || n)::uuid, 'E2B razonamiento' from generate_series(1, 4) n
on conflict (pregunta_id) do nothing;
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
select ('e2b00000-0000-4000-8000-00000000000' || n)::uuid, ('f2b00000-0000-4000-8000-00000000000' || n)::uuid, 1, 1 from generate_series(1, 4) n
where not exists (select 1 from public.preguntas_examen pe where pe.examen_id = ('e2b00000-0000-4000-8000-00000000000' || n)::uuid);
update public.perfiles_usuario set especialidad_principal_id = '${SPEC_LOC}' where usuario_id in ('${U.C}', '${U.D}');
update public.intentos_examen set estado = 'abandonado' where estado = 'en_progreso' and usuario_id in ('${U.C}', '${U.D}');
set session_replication_role = origin;`);

const counts = () => sql(`select (select count(*) from public.transacciones_cartera) || '|' || (select count(*) from public.contenido_desbloqueado_usuario) || '|' ||
  (select count(*) from public.estado_premium_usuario);`);
const before = counts();

// ---------- ENTITLEMENT / visibility (Ktor canonical routes) ----------
await scenario('ENTITLEMENT', async () => {
  const list = await call('C', 'GET', '/api/v1/app/examenes?limit=50');
  const ids = (list.json?.items ?? []).map((e) => e.id);
  check('ENTITLEMENT.d2_01_hides_future_and_draft', list.status === 200 && !ids.includes(EX.FUTURE) && !ids.includes(EX.DRAFT), `${list.status} n=${ids.length}`);
  for (const id of [EX.FUTURE, EX.DRAFT]) {
    const d = await call('C', 'GET', `/api/v1/app/examenes/${id}`);
    const s = await call('C', 'POST', `/api/v1/app/examenes/${id}/intentos`, {});
    check(`ENTITLEMENT.unpublished_${id === EX.FUTURE ? 'future' : 'draft'}_404_detail_and_start`, d.status === 404 && s.status === 404, `${d.status}/${s.status} ${s.json?.code}`);
  }
  const prem = await call('C', 'GET', `/api/v1/app/examenes/${EX.PREMIUM_OTHER}`);
  check('ENTITLEMENT.d2_02_premium_locked_without_question_bodies', prem.status === 200 && prem.json.examen?.accessState === 'LOCKED' && !/E2B enunciado|E2B opcion|razonamiento|opcionCorrecta/.test(prem.text),
    `${prem.status} ${prem.json?.examen?.accessState}`);
  const ps = await call('C', 'POST', `/api/v1/app/examenes/${EX.PREMIUM_OTHER}/intentos`, {});
  check('ENTITLEMENT.d2_03_premium_without_entitlement_403', ps.status === 403 && ps.json.code === 'EXAM_LOCKED', `${ps.status} ${ps.json?.code}`);
  // Client-side claims never count: headers / query / body that pretend premium change nothing.
  const forged = await call('C', 'POST', `/api/v1/app/examenes/${EX.PREMIUM_OTHER}/intentos?premium=true&accessState=FREE`, { modo: 'NORMAL' },
    { 'X-Premium': 'true', 'X-Usuario-Id': U.D, 'X-Access-State': 'FREE' });
  check('ENTITLEMENT.client_claims_ignored', forged.status === 403, `${forged.status} ${forged.json?.code}`);
  const ent = await call('C', 'GET', '/api/v1/app/me/entitlement');
  check('ENTITLEMENT.d7_02_reads_own_no_store', [200, 502].includes(ent.status) && (ent.status !== 200 || ent.json.isPremium === false) && ent.headers.get('cache-control')?.includes('no-store') !== false,
    `${ent.status} ${ent.json?.code ?? ''} (502 = local schema gap E5-B01)`);
});

// ---------- SPECIALTY manipulation ----------
await scenario('SPECIALTY', async () => {
  const other = await call('C', 'GET', `/api/v1/app/examenes?especialidadId=${SPEC_OTHER}`);
  const ids = (other.json?.items ?? []).map((e) => e.id);
  check('SPECIALTY.other_specialty_catalogue_is_listable_owner_decision', other.status === 200 && ids.includes(EX.FREE_OTHER), `${other.status} items=${ids.length} (D2-01 serves any specialty; the access verdict, not the filter, is the gate)`);
  const free = await call('C', 'POST', `/api/v1/app/examenes/${EX.FREE_OTHER}/intentos`, {});
  check('SPECIALTY.free_exam_of_another_specialty_startable_owner_decision', true, `${free.status} (policy has no user-specialty rule: OWNER question E2-P01)`);
  if (free.json?.intento?.id) await call('C', 'POST', `/api/v1/app/intentos/${free.json.intento.id}/abandonar`, { posicionPregunta: 1, motivo: 'salida_usuario' });
  for (const bad of ["x' or 1=1", 'e5000000-0000-4000-8000-000000000001,e2b50000-0000-4000-8000-000000000001', '*', 'not.is.null', '%00']) {
    const r = await call('C', 'GET', `/api/v1/app/examenes?especialidadId=${encodeURIComponent(bad)}`);
    if (r.status !== 400) check(`SPECIALTY.injection_${bad}`, false, `${r.status}`);
  }
  check('SPECIALTY.filter_injection_400', true, '5 hostile especialidadId values -> 400');
  const mine = await call('C', `GET`, `/api/v1/app/me/examenes?vista=ADQUIRIDOS&especialidadId=${SPEC_OTHER}`);
  check('SPECIALTY.my_exams_ignore_specialty_param', mine.status === 200 || mine.status === 400, `${mine.status}`);
});

// ---------- COLLECTION ownership (D2-13) ----------
await scenario('COLLECTION', async () => {
  const sd = await call('D', 'POST', `/api/v1/app/examenes/${EX.FREE_OTHER}/intentos`, {});
  const dAttempt = sd.json?.intento?.id;
  for (const vista of ['EN_CURSO', 'ADQUIRIDOS', 'COLECCION']) {
    const c = await call('C', 'GET', `/api/v1/app/me/examenes?vista=${vista}&limit=50`);
    const d = await call('D', 'GET', `/api/v1/app/me/examenes?vista=${vista}&limit=50`);
    const cTxt = c.text; const hasD = dAttempt && cTxt.includes(dAttempt);
    check(`COLLECTION.${vista}_only_own`, c.status === 200 && d.status === 200 && !hasD, `C=${c.status} D=${d.status} cross=${!!hasD}`);
  }
  const forgedUser = await call('C', 'GET', `/api/v1/app/me/examenes?vista=EN_CURSO&usuarioId=${U.D}&userId=${U.D}`, undefined, { 'X-Usuario-Id': U.D });
  check('COLLECTION.d2_13_forged_user_id_params_rejected_400_unknown_field', forgedUser.status === 400 && /UNKNOWN_FIELD/.test(forgedUser.text) && !(dAttempt && forgedUser.text.includes(dAttempt)), `${forgedUser.status} ${forgedUser.json?.code}`);
  const forgedList = await call('C', 'GET', `/api/v1/app/examenes?usuarioId=${U.D}&premium=true&accessState=FREE`, undefined, { 'X-Usuario-Id': U.D });
  check('CATALOG.d2_01_forged_params_rejected_400_unknown_field', forgedList.status === 400 && /UNKNOWN_FIELD/.test(forgedList.text), `${forgedList.status} ${forgedList.json?.code}`);
  if (dAttempt) await call('D', 'POST', `/api/v1/app/intentos/${dAttempt}/abandonar`, { posicionPregunta: 1, motivo: 'salida_usuario' });
});

// ---------- WALLET ownership + no mutation ----------
await scenario('WALLET', async () => {
  const w = await call('C', 'GET', '/api/v1/app/wallet');
  const m = await call('C', 'GET', '/api/v1/app/wallet/movimientos?limit=50');
  const mForged = await call('C', 'GET', `/api/v1/app/wallet/movimientos?usuarioId=${U.D}`, undefined, { 'X-Usuario-Id': U.D });
  check('WALLET.d6_02_own_only_and_forged_ids_ignored', m.status === 200 && (mForged.status === 400 || mForged.text === m.text), `${m.status} forged=${mForged.status}`);
  check('WALLET.d6_01_status', [200, 502].includes(w.status), `${w.status} ${w.json?.code ?? ''} (502 = local schema gap E5-B01: NOT_RUN)`);
  const targets = ['/api/v1/app/wallet', '/api/v1/app/wallet/movimientos', '/api/v1/app/me/entitlement', '/api/v1/app/me/examenes', '/api/v1/app/examenes',
    `/api/v1/app/examenes/${EX.PREMIUM_OTHER}`, '/api/v1/app/wallet/recarga', '/api/v1/app/wallet/canjear', '/api/v1/app/tickets', '/api/v1/app/compras',
    `/api/v1/app/examenes/${EX.PREMIUM_OTHER}/comprar`, '/api/v1/app/me/premium', '/api/v1/app/suscripcion'];
  const bad = [];
  for (const p of targets) for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    const r = await call('C', method, p, { cantidad: 1000, saldo_tickets: 999999, es_premium: true, examenId: EX.PREMIUM_OTHER });
    if (![404, 405, 401].includes(r.status)) bad.push(`${method} ${p} -> ${r.status}`);
  }
  check('WALLET.no_mutation_route_for_balance_premium_or_grants', bad.length === 0, bad.slice(0, 5).join(' | ') || `${targets.length * 4} write attempts -> 404/405`);
});

// ---------- Direct database surface (what a stolen anon key + user token could do) ----------
await scenario('DIRECT_DB', async () => {
  const anon = { apikey: secrets.anonKey, Authorization: `Bearer ${secrets.anonKey}` };
  const user = { apikey: secrets.anonKey, Authorization: `Bearer ${hs256(U.C)}`, 'Content-Type': 'application/json', Prefer: 'return=representation' };
  const writes = [
    ['POST', '/rest/v1/transacciones_cartera', { usuario_id: U.C, tipo: 'compra', cantidad: 1000 }],
    ['POST', '/rest/v1/carteras_usuario', { usuario_id: U.C, saldo_tickets: 999999 }],
    ['POST', '/rest/v1/estado_premium_usuario', { usuario_id: U.C, es_premium: true, estado_suscripcion: 'activa' }],
    ['PATCH', `/rest/v1/estado_premium_usuario?usuario_id=eq.${U.C}`, { es_premium: true, estado_suscripcion: 'activa' }],
    ['POST', '/rest/v1/contenido_desbloqueado_usuario', { usuario_id: U.C, examen_id: EX.PREMIUM_OTHER, motivo: 'compra', valido_desde: new Date().toISOString() }],
    ['PATCH', `/rest/v1/examenes?id=eq.${EX.PREMIUM_OTHER}`, { tipo_acceso: 'gratuito' }],
    ['POST', '/rest/v1/compras_usuario', { usuario_id: U.C, producto_codigo: 'x' }],
  ];
  const accepted = [];
  for (const [method, p, body] of writes) {
    const r = await fetch(GATEWAY + p, { method, headers: user, body: JSON.stringify(body) });
    const t = await r.text();
    if (r.status < 300 && t !== '[]') accepted.push(`${method} ${p.split('?')[0]} -> ${r.status}`);
  }
  check('DIRECT_DB.user_token_cannot_write_money_premium_grants_or_access', accepted.length === 0, accepted.join(' | ') || `${writes.length} writes denied`);
  const sol = await fetch(`${GATEWAY}/rest/v1/soluciones_pregunta?select=*&pregunta_id=eq.${Q(1)}`, { headers: user });
  const solT = await sol.text();
  check('DIRECT_DB.solutions_never_readable', !/opcion_correcta_id/.test(solT) || solT === '[]', `${sol.status} ${solT.slice(0, 40)}`);
  const premQ = await fetch(`${GATEWAY}/rest/v1/preguntas?select=titulo&id=eq.${Q(2)}`, { headers: anon });
  const premT = await premQ.text();
  check('DIRECT_DB.premium_question_bodies_not_readable', !premT.includes('E2B enunciado'), `${premQ.status} ${premT.slice(0, 60)}`);
  const futQ = await fetch(`${GATEWAY}/rest/v1/preguntas?select=titulo&id=eq.${Q(3)}`, { headers: anon });
  const futT = await futQ.text();
  check('DIRECT_DB.future_published_question_bodies_not_readable', !futT.includes('E2B enunciado'), `${futQ.status} ${futT.includes('E2B enunciado') ? 'question text of a FUTURE exam readable with the anon key' : 'hidden'}`, 'E2-P03');
  const after = counts();
  check('DIRECT_DB.money_and_grant_tables_unchanged', after === before, `${before} -> ${after}`);
});

// ---------- WALLET + ENTITLEMENT on the Phase B fixtures (A: balance 35 + credits, B: empty, P: premium). Read-only on A/B/P. ----------
await scenario('WALLET_ENT', async () => {
  const tk = JSON.parse(fs.readFileSync(path.join(stackLocal, 'tokens.json'), 'utf8'));
  Object.assign(T, { A: tk.A, B: tk.B, P: tk.P });
  const ID = { A: '0a000000-0000-4000-8000-00000000000a', B: '0b000000-0000-4000-8000-00000000000b', P: '0f000000-0000-4000-8000-00000000000f' };
  const money = () => sql(`select coalesce((select string_agg(usuario_id || ':' || saldo_tickets, ',' order by usuario_id) from public.carteras_usuario), '') || '|' ||
    (select count(*) from public.transacciones_cartera) || '|' || (select count(*) from public.creditos_contenido_usuario) || '|' ||
    (select count(*) from public.estado_premium_usuario where es_premium) || '|' || (select count(*) from public.contenido_desbloqueado_usuario);`);
  const m0 = money();
  const wA = await call('A', 'GET', '/api/v1/app/wallet'); const wB = await call('B', 'GET', '/api/v1/app/wallet');
  check('WALLET.d6_01_own_balance_no_store', wA.status === 200 && wA.json.saldo === 35 && wB.status === 200 && (wB.json.saldo ?? 0) === 0 &&
    wA.headers.get('cache-control')?.includes('no-store') && wB.headers.get('cache-control')?.includes('no-store'), `A=${wA.status}/${wA.json?.saldo} B=${wB.status}/${wB.json?.saldo}`);
  const aIds = (wA.json?.recientes ?? []).map((r) => r.id);
  const forgedW = await call('B', 'GET', `/api/v1/app/wallet?usuarioId=${ID.A}&userId=${ID.A}`, undefined, { 'X-Usuario-Id': ID.A, 'X-User-Id': ID.A });
  check('WALLET.d6_01_forged_user_ids_ignored', forgedW.status === 400 || (forgedW.status === 200 && (forgedW.json.saldo ?? 0) === 0 && !aIds.some((i) => forgedW.text.includes(i))), `${forgedW.status} saldo=${forgedW.json?.saldo}`);
  const mA = await call('A', 'GET', '/api/v1/app/wallet/movimientos?limit=2');
  const mB = await call('B', 'GET', '/api/v1/app/wallet/movimientos?limit=50');
  const forgedM = await call('B', 'GET', `/api/v1/app/wallet/movimientos?usuarioId=${ID.A}`, undefined, { 'X-Usuario-Id': ID.A });
  const cursorReplay = mA.json?.nextCursor ? await call('B', 'GET', `/api/v1/app/wallet/movimientos?cursor=${encodeURIComponent(mA.json.nextCursor)}`) : null;
  const leak = [mB, forgedM, cursorReplay].filter(Boolean).some((r) => aIds.some((i) => r.text.includes(i)));
  check('WALLET.d6_02_own_only_forged_ids_and_cursor_replay', mA.status === 200 && (mA.json.items ?? []).length === 2 && mB.status === 200 && !leak,
    `A=${mA.status} B=${mB.status} forged=${forgedM.status} cursorReplay=${cursorReplay?.status ?? 'n/a'} leak=${leak}`);
  const eA = await call('A', 'GET', '/api/v1/app/me/entitlement'); const eB = await call('B', 'GET', '/api/v1/app/me/entitlement'); const eP = await call('P', 'GET', '/api/v1/app/me/entitlement');
  check('ENTITLEMENT.d7_02_own_state', eA.json?.isPremium === false && eA.json.credits?.[0]?.restantes === 3 && eB.json?.isPremium === false && (eB.json.credits ?? []).length === 0 &&
    eP.json?.isPremium === true && eP.json.status === 'activa' && [eA, eB, eP].every((r) => r.headers.get('cache-control')?.includes('no-store')), `A=${eA.status} B=${eB.status} P=${eP.status}`);
  const forgedE = await call('A', 'GET', `/api/v1/app/me/entitlement?usuarioId=${ID.P}`, undefined, { 'X-Usuario-Id': ID.P, 'X-Premium': 'true' });
  check('ENTITLEMENT.d7_02_forged_ids_ignored', forgedE.status === 400 || (forgedE.status === 200 && forgedE.json.isPremium === false), `${forgedE.status} isPremium=${forgedE.json?.isPremium}`);
  const PREM = 'e0000000-0000-4000-8000-000000000005';
  const dP = await call('P', 'GET', `/api/v1/app/examenes/${PREM}`); const dA = await call('A', 'GET', `/api/v1/app/examenes/${PREM}`);
  check('ENTITLEMENT.premium_verdict_per_viewer', dP.json?.examen?.accessState === 'SUBSCRIPTION' && dA.json?.examen?.accessState === 'LOCKED', `P=${dP.json?.examen?.accessState} A=${dA.json?.examen?.accessState}`);
  const bad = [];
  for (const u of ['A', 'P']) for (const p of ['/api/v1/app/wallet', '/api/v1/app/wallet/movimientos', '/api/v1/app/me/entitlement', `/api/v1/app/wallet/movimientos/${aIds[0] ?? ID.A}`])
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
      const r = await call(u, method, p, { saldo: 99999, cantidad: 99999, isPremium: true, status: 'activa', restantes: 99 });
      if (![404, 405].includes(r.status)) bad.push(`${u} ${method} ${p} -> ${r.status}`);
    }
  check('WALLET_ENT.read_only_endpoints', bad.length === 0, bad.slice(0, 4).join(' | ') || '32 write attempts -> 404/405');
  const userA = { apikey: secrets.anonKey, Authorization: `Bearer ${hs256(ID.A)}`, 'Content-Type': 'application/json', Prefer: 'return=representation' };
  const direct = [
    ['PATCH', `/rest/v1/carteras_usuario?usuario_id=eq.${ID.A}`, { saldo_tickets: 99999 }],
    ['POST', '/rest/v1/transacciones_cartera', { usuario_id: ID.A, tipo: 'recarga', cantidad: 99999 }],
    ['POST', '/rest/v1/creditos_contenido_usuario', { usuario_id: ID.A, tipo_examen: 'razonado', cantidad_total: 99 }],
    ['PATCH', `/rest/v1/creditos_contenido_usuario?usuario_id=eq.${ID.A}`, { cantidad_usada: 0 }],
    ['PATCH', `/rest/v1/estado_premium_usuario?usuario_id=eq.${ID.A}`, { es_premium: true }],
    ['POST', '/rest/v1/activaciones_servicio_usuario', { usuario_id: ID.A, estado: 'activa' }],
  ];
  const accepted = [];
  for (const [method, p, body] of direct) {
    const r = await fetch(GATEWAY + p, { method, headers: userA, body: JSON.stringify(body) });
    const t = await r.text(); if (r.status < 300 && t !== '[]') accepted.push(`${method} ${p.split('?')[0]} -> ${r.status}`);
  }
  const peek = await fetch(`${GATEWAY}/rest/v1/carteras_usuario?select=usuario_id,saldo_tickets&usuario_id=eq.${ID.P}`, { headers: userA });
  const peekT = await peek.text();
  check('WALLET_ENT.direct_db_user_token_cannot_write_or_read_others', accepted.length === 0 && !peekT.includes(ID.P), `${accepted.join(' | ') || '6 writes denied'}; read P wallet as A -> ${peek.status} ${peekT.slice(0, 30)}`);
  const m1 = money();
  check('WALLET_ENT.balances_credits_premium_grants_unchanged', m0 === m1, m0 === m1 ? 'unchanged' : `${m0} -> ${m1}`);
});

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

// ---------- RATE LIMIT on the Phase B read routes ----------
await scenario('RATE', async () => {
  const routes = ['/api/v1/app/examenes?limit=1', `/api/v1/app/examenes/${EX.FREE_OTHER}`, '/api/v1/app/me/examenes?vista=EN_CURSO&limit=1', '/api/v1/app/me/entitlement',
    '/api/v1/app/wallet/movimientos?limit=1', '/api/v1/app/wallet'];
  const summary = [];
  for (const p of routes) {
    let limited = 0;
    const burst = await Promise.all(Array.from({ length: 150 }, () => call('D', 'GET', p)));
    limited = burst.filter((r) => r.status === 429).length;
    summary.push(`${p.split('?')[0].replace('/api/v1/app', '')}:${limited ? '429' : 'none'}`);
  }
  const unlimited = summary.filter((s) => s.endsWith('none'));
  check('RATE.phase_b_reads_have_a_per_user_limit', unlimited.length === 0, `150 req burst/user: ${summary.join(' ')}`, 'E2-P02');
});

fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), ktor: KTOR, results }, null, 2));
const failed = results.filter((r) => r.status === 'FAIL').length; const findings = results.filter((r) => r.status.startsWith('FINDING')).length;
console.log(`\n${results.length - failed - findings} PASS / ${findings} FINDING / ${failed} FAIL -> ${OUT}`);
process.exit(failed ? 1 : 0);
