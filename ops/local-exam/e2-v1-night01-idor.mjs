#!/usr/bin/env node
// E2 NIGHT-01 wave 1 (LOCAL ONLY): IDOR / authz sweep of /api/v1/app routes not yet covered by other e2-v1-* scripts:
// me (profile/avatar/legal-acceptances/credentials/onboarding/billing-history), plan (cupo/catalogo), opositeca, payments/orders,
// feedback/pregunta, opositester/me, racha; plus a generic no-token / malformed-token / other-user-token pass over the route list.
// Own users/exams only. STACK_LOCK hard-fail. Own Ktor on :8091.   STACK_HOLDER=E2 node ops/local-exam/e2-v1-night01-idor.mjs [--only=NAME,...]
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, mkExam, sql, call, uuid, run } = L;

requireStackLock('e2-v1-night01-idor.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-night01-idor-result.json';
await L.restartKtor();

const GET = (u, p) => call(u, 'GET', p);
const noToken = (p, method = 'GET', body) => call(null, method, p, body);
const garbled = (p, method = 'GET', body) => call({ t: 'not-a-jwt-at-all' }, method, p, body);
const otherSig = (u, p, method = 'GET', body) => { const parts = u.t.split('.'); const tampered = parts[0] + '.' + parts[1] + '.' + 'ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ'; return call({ t: tampered }, method, p, body); };

await scenario(only, 'A_ME_ROUTES', async () => {
  const a = mkUser('mea'); const b = mkUser('meb');
  const routes = ['/api/v1/app/me', '/api/v1/app/me/onboarding', '/api/v1/app/me/profile', '/api/v1/app/me/legal-acceptances', '/api/v1/app/me/credentials', '/api/v1/app/me/entitlement', '/api/v1/app/me/billing-history', '/api/v1/app/me/invitacion', '/api/v1/app/me/invitaciones', '/api/v1/app/racha', '/api/v1/app/plan/cupo', '/api/v1/app/plan/catalogo', '/api/v1/app/opositeca', '/api/v1/app/payments/orders', '/api/v1/app/opositester/me', '/api/v1/app/estadisticas/resumen', '/api/v1/app/estadisticas/debilidades', '/api/v1/app/wallet', '/api/v1/app/wallet/movimientos', '/api/v1/app/wallet/paquetes', '/api/v1/app/ranking', '/api/v1/app/ranking/visibilidad', '/api/v1/app/oposired/canales', '/api/v1/app/oposired/categorias', '/api/v1/app/reto-diario', '/api/v1/app/retos/hoy', '/api/v1/app/examenes', '/api/v1/public/examenes', '/api/v1/public/especialidades'];
  const leaksB = [];
  for (const p of routes) {
    const ra = await GET(a, p); const rb = await GET(b, p);
    if (ra.status === 200 && rb.status === 200 && ra.text !== '[]' && rb.text !== '[]' && ra.text === rb.text && /[A-Za-z0-9-]{20,}/.test(ra.text)) {
      // same body for two different users on a /me-shaped route with real content is suspicious (fixtures might legitimately share empty lists; flag only on identical non-trivial bodies for a personal route)
      if (/\/me(\/|$)/.test(p)) leaksB.push(`${p} identical body for two different users`);
    }
    if (ra.status >= 500) leaksB.push(`${p} 5xx for an authenticated own-data GET (${ra.status})`);
  }
  check('M1.every_me_shaped_route_answers_per_user_data_(no_shared_body_no_5xx)', leaksB.length === 0, leaksB.join(' | ') || `${routes.length} routes checked`);
  // no token / garbled token / tampered signature on every route
  const bad = [];
  for (const p of routes) {
    if (p.startsWith('/api/v1/public/')) continue;
    const n = await noToken(p); const g = await garbled(p); const t = await otherSig(a, p);
    if (n.status < 400 || n.status >= 500) bad.push(`${p} no-token -> ${n.status}`);
    if (g.status < 400 || g.status >= 500) bad.push(`${p} garbled-token -> ${g.status}`);
    if (t.status < 400 || t.status >= 500) bad.push(`${p} tampered-sig -> ${t.status}`);
  }
  check('M2.every_protected_me_shaped_route_refuses_no_token_garbled_token_and_a_tampered_signature_(4xx_never_5xx)', bad.length === 0, bad.join(' | ') || 'all refused cleanly');
});

await scenario(only, 'B_PROFILE_WRITE_IDOR', async () => {
  const a = mkUser('wra'); const b = mkUser('wrb');
  const before = (await GET(b, '/api/v1/app/me/profile')).text;
  const r1 = await call(a, 'PATCH', '/api/v1/app/me/profile', { nombreMostrar: 'HACKED BY A', usuarioId: b.id, usuario_id: b.id });
  const after = (await GET(b, '/api/v1/app/me/profile')).text;
  check('P1.a_user_cannot_edit_another_users_profile_by_injecting_usuarioId_in_the_body_(mass_assignment)', after === before && !/HACKED BY A/.test(after), `write=${r1.status} before==after:${before === after}`);
  const r2 = await call(a, 'PATCH', '/api/v1/app/me/profile', { estadoCuenta: 'premium_vip', esAdmin: true, rol: 'admin' });
  const mine = await GET(a, '/api/v1/app/me');
  check('P2.mass_assignment_of_privileged_fields_on_own_profile_is_ignored_(no_admin_no_estado_cuenta_override)', !/"admin"|"premium_vip"/.test(mine.text), `write=${r2.status} ${mine.text.slice(0, 150)}`);
});

await scenario(only, 'C_IDOR_INTENTOS_AND_ENTITLEMENT', async () => {
  const ex = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 3 });
  const owner = mkUser('ow'); const attacker = mkUser('atk');
  const s = await call(owner, 'POST', `/api/v1/app/examenes/${ex.id}/intentos`, {}, { 'Idempotency-Key': uuid() });
  const attemptId = s.json?.intento?.id ?? s.json?.id;
  const routes = [['GET', `/api/v1/app/intentos/${attemptId}`], ['GET', `/api/v1/app/intentos/${attemptId}/preguntas`], ['GET', `/api/v1/app/intentos/${attemptId}/resultado`], ['GET', `/api/v1/app/intentos/${attemptId}/revision`], ['POST', `/api/v1/app/intentos/${attemptId}/finalizar`], ['POST', `/api/v1/app/intentos/${attemptId}/abandonar`], ['PUT', `/api/v1/app/intentos/${attemptId}/respuestas/${ex.qs[0].id}`]];
  const leaks = [];
  for (const [m, p] of routes) {
    const r = await call(attacker, m, p, m === 'PUT' ? { opcionSeleccionadaId: ex.qs[0].ok, tiempoRespuestaMs: 1000, baseRevision: 0 } : {}, m !== 'GET' ? { 'Idempotency-Key': uuid() } : {});
    if (r.status < 400 || r.status >= 500) leaks.push(`${m} ${p.replace(attemptId, 'ID')} -> ${r.status} ${r.text.slice(0, 80)}`);
  }
  check('I1.every_intentos_subroute_refuses_another_users_attempt_id_(4xx_never_200_never_5xx)', leaks.length === 0, leaks.join(' | ') || `${routes.length} subroutes refused`);
  const own = await call(owner, 'GET', `/api/v1/app/intentos/${attemptId}`); check('I2.the_real_owner_still_works', own.status === 200, own.status);
  // entitlement / orders IDOR by random uuid (no real other-order to target; check 404 shape and no 5xx)
  const ent = await GET(attacker, '/api/v1/app/me/entitlement'); check('I3.entitlement_route_is_per_caller_(no_examId_param_IDOR_surface)', ent.status === 200 || ent.status === 404, ent.status);
  const ord = await call(attacker, 'GET', `/api/v1/app/payments/orders/${uuid()}`); check('I4.a_random_order_id_is_404_not_500_and_leaks_no_other_order', ord.status === 404 && !/amount|monto|"estado":"completad/i.test(ord.text), `${ord.status} ${ord.text.slice(0, 80)}`);
});

await scenario(only, 'D_OPOSITECA_SAVED_IDOR', async () => {
  const ex = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 1 });
  const a = mkUser('opa'); const b = mkUser('opb');
  const save = await call(a, 'PUT', `/api/v1/app/opositeca/guardados/examen/${ex.id}`, {}, { 'Idempotency-Key': uuid() });
  const listA = await GET(a, '/api/v1/app/opositeca'); const listB = await GET(b, '/api/v1/app/opositeca');
  check('O1.saved_item_is_per_user_(a_second_users_list_does_not_show_it)', (save.status === 200 || save.status === 201 || save.status === 204) && !listB.text.includes(ex.id) && listA.text.includes(ex.id), `save=${save.status} a_has=${listA.text.includes(ex.id)} b_has=${listB.text.includes(ex.id)}`);
  const delB = await call(b, 'DELETE', `/api/v1/app/opositeca/guardados/examen/${ex.id}`);
  const listA2 = await GET(a, '/api/v1/app/opositeca');
  check('O2.a_user_cannot_delete_another_users_saved_item_by_guessing_the_target_id', listA2.text.includes(ex.id), `b_delete=${delB.status} a_still_has=${listA2.text.includes(ex.id)}`);
});

await scenario(only, 'E_VERB_AND_CONTENT_TYPE', async () => {
  const a = mkUser('vct'); const bad = [];
  const target = '/api/v1/app/me/profile';
  const r1 = await fetch(L.KTOR + target, { method: 'GET', headers: { Authorization: `Bearer ${a.t}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ x: 1 }) }).catch((e) => ({ status: -1, e }));
  const r2 = await call(a, 'DELETE', target); const r3 = await call(a, 'TRACE', target).catch(() => ({ status: -1 }));
  const r4 = await fetch(L.KTOR + target, { method: 'PATCH', headers: { Authorization: `Bearer ${a.t}`, 'Content-Type': 'application/xml' }, body: '<nombreMostrar>x</nombreMostrar>' });
  const r5 = await fetch(L.KTOR + target, { method: 'PATCH', headers: { Authorization: `Bearer ${a.t}` }, body: JSON.stringify({ nombreMostrar: 'x' }) }); // no content-type
  if (r2.status !== 405 && r2.status !== 404) bad.push(`DELETE on a PATCH-only route -> ${r2.status}`);
  if (r4.status < 400 || r4.status >= 500) bad.push(`xml content-type -> ${r4.status}`);
  if (r5.status >= 500) bad.push(`missing content-type -> ${r5.status}`);
  check('V1.verb_tampering_and_content_type_confusion_are_refused_cleanly', bad.length === 0, bad.join(' | ') || 'ok');
});

await scenario(only, 'F_OVERSIZED_UNICODE', async () => {
  const a = mkUser('ovs');
  const big = await call(a, 'PATCH', '/api/v1/app/me/profile', { nombreMostrar: 'x'.repeat(2_000_000) });
  const nested = (() => { let o = { a: 1 }; for (let i = 0; i < 20000; i++) o = { n: o }; return o; })();
  const deep = await call(a, 'PATCH', '/api/v1/app/me/profile', nested).catch((e) => ({ status: -1, text: String(e) }));
  const uni = await call(a, 'PATCH', '/api/v1/app/me/profile', { nombreMostrar: 'A\u0000B‮x﻿' });
  const bad = [];
  if (big.status >= 500) bad.push(`2MB body -> ${big.status}`);
  if (deep.status >= 500) bad.push(`20000-deep JSON -> ${deep.status} ${deep.text?.slice(0, 80)}`);
  if (uni.status >= 500) bad.push(`NUL/RTL-override/BOM -> ${uni.status}`);
  check('U1.oversized_body_deep_nesting_and_unicode_control_chars_are_refused_not_500', bad.length === 0, bad.join(' | ') || `big=${big.status} deep=${deep.status} uni=${uni.status}`);
});

L.finish(OUT);
