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

// VALIDITY GATE: the owner reading its own resource must return 2xx for every route family; otherwise the token is wrong
// (as in the hardcoded-iss regression) and every "refused" result below would be a false pass -> the whole sweep is invalid.
await scenario(only, 'Z_POSITIVE_CONTROLS', async () => {
  const pc = mkUser('pc'); const ex = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 2 });
  const fams = [['me', '/api/v1/app/me'], ['plan', '/api/v1/app/plan/catalogo'], ['opositeca', '/api/v1/app/opositeca'], ['examenes', '/api/v1/app/examenes'], ['public', '/api/v1/public/especialidades']];
  const failed = [];
  for (const [fam, p] of fams) { const r = await GET(pc, p); if (r.status < 200 || r.status >= 300) failed.push(`${fam} ${p} -> ${r.status}`); }
  const s = await call(pc, 'POST', `/api/v1/app/examenes/${ex.id}/intentos`, {}, { 'Idempotency-Key': uuid() });
  const aid = s.json?.intento?.id ?? s.json?.id;
  const own = aid ? await GET(pc, `/api/v1/app/intentos/${aid}`) : { status: 0 };
  if (!(own.status >= 200 && own.status < 300)) failed.push(`intentos start=${s.status} read=${own.status}`);
  check('Z0.POSITIVE_CONTROL_owner_reads_own_resource_2xx_for_every_route_family', failed.length === 0, failed.join(' | ') || 'me/plan/opositeca/examenes/public/intentos all 2xx');
});

await scenario(only, 'A_ME_ROUTES', async () => {
  const a = mkUser('mea'); const b = mkUser('meb');
  // Full no-path-param /app GET surface from openapi/root.yaml + public no-param routes. POST/PUT-only routes are included on
  // purpose: an owner GET returns 405/404 (never 5xx, never a 200 leak), and the no-token/garbled/tampered pass must still be 4xx.
  const routes = ['/api/v1/app/me', '/api/v1/app/me/onboarding', '/api/v1/app/me/profile', '/api/v1/app/me/avatar', '/api/v1/app/me/legal-acceptances', '/api/v1/app/me/credentials', '/api/v1/app/me/entitlement', '/api/v1/app/me/billing-history', '/api/v1/app/me/invitacion', '/api/v1/app/me/invitaciones', '/api/v1/app/me/examenes', '/api/v1/app/legal/documents', '/api/v1/app/racha', '/api/v1/app/plan/cupo', '/api/v1/app/plan/catalogo', '/api/v1/app/opositeca', '/api/v1/app/payments/orders', '/api/v1/app/opositester/me', '/api/v1/app/feedback/pregunta', '/api/v1/app/estadisticas/resumen', '/api/v1/app/estadisticas/debilidades', '/api/v1/app/wallet', '/api/v1/app/wallet/movimientos', '/api/v1/app/wallet/paquetes', '/api/v1/app/wallet/canjes', '/api/v1/app/ranking', '/api/v1/app/ranking/visibilidad', '/api/v1/app/oposired/canales', '/api/v1/app/oposired/categorias', '/api/v1/app/oposired/imagenes', '/api/v1/app/oposired/publicaciones', '/api/v1/app/oposired/reportes', '/api/v1/app/reto-diario', '/api/v1/app/retos/hoy', '/api/v1/app/examenes', '/api/v1/public/examenes', '/api/v1/public/especialidades'];
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
  // no token / garbled token / tampered signature on every route.
  // ANON-by-contract app routes (openapi x-roles:[ANON], security:[]) are legitimately reachable without a token.
  const publicApp = new Set(['/api/v1/app/legal/documents']);
  const bad = [];
  for (const p of routes) {
    if (p.startsWith('/api/v1/public/') || publicApp.has(p)) continue;
    const n = await noToken(p); const g = await garbled(p); const t = await otherSig(a, p);
    if (n.status < 400 || n.status >= 500) bad.push(`${p} no-token -> ${n.status}`);
    if (g.status < 400 || g.status >= 500) bad.push(`${p} garbled-token -> ${g.status}`);
    if (t.status < 400 || t.status >= 500) bad.push(`${p} tampered-sig -> ${t.status}`);
  }
  check('M2.every_protected_me_shaped_route_refuses_no_token_garbled_token_and_a_tampered_signature_(4xx_never_5xx)', bad.length === 0, bad.join(' | ') || 'all refused cleanly');
});

await scenario(only, 'B_PROFILE_WRITE_IDOR', async () => {
  const a = mkUser('wra'); const b = mkUser('wrb');
  // Strip the per-request echo `requestId` before comparing: it differs between any two reads and is not profile state.
  const stable = (t) => t.replace(/"requestId":"[^"]*"/g, '"requestId":"_"');
  const before = stable((await GET(b, '/api/v1/app/me/profile')).text);
  const r1 = await call(a, 'PATCH', '/api/v1/app/me/profile', { nombreMostrar: 'HACKED BY A', usuarioId: b.id, usuario_id: b.id });
  const after = stable((await GET(b, '/api/v1/app/me/profile')).text);
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
  // V1 OposiTeca saves QUESTIONS only (openapi: PUT /opositeca/guardados/{tipo}/{targetId}, tipo enum = [pregunta]).
  const ex = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 1 });
  const target = ex.qs[0].id;
  const a = mkUser('opa'); const b = mkUser('opb');
  const save = await call(a, 'PUT', `/api/v1/app/opositeca/guardados/pregunta/${target}`, {}, { 'Idempotency-Key': uuid() });
  const listA = await GET(a, '/api/v1/app/opositeca?tipo=pregunta'); const listB = await GET(b, '/api/v1/app/opositeca?tipo=pregunta');
  check('O1.saved_item_is_per_user_(a_second_users_list_does_not_show_it)', (save.status === 200 || save.status === 201 || save.status === 204) && !listB.text.includes(target) && listA.text.includes(target), `save=${save.status} a_has=${listA.text.includes(target)} b_has=${listB.text.includes(target)}`);
  const delB = await call(b, 'DELETE', `/api/v1/app/opositeca/guardados/pregunta/${target}`);
  const listA2 = await GET(a, '/api/v1/app/opositeca?tipo=pregunta');
  check('O2.a_user_cannot_delete_another_users_saved_item_by_guessing_the_target_id', listA2.text.includes(target), `b_delete=${delB.status} a_still_has=${listA2.text.includes(target)}`);
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

// ============================================================================
// SECOND PASS (LOCAL ONLY): true cross-account BOLA/IDOR/BFLA. For each case,
// test user B creates a REAL resource; test user A acts on the route with B's
// real id -> expect 4xx and NO side effect (re-read as B / DB unchanged);
// positive control: B on its own resource -> 2xx. Own synthetic users/exams only.
// Shared catalog objects (exams, segment retos) are shared by design, not per-user;
// for those the IDOR surface is noted explicitly. No destructive ops beyond the
// synthetic rows this script creates. Run restores fixtures afterwards (up.mjs).
// ============================================================================
const OPO = '/api/v1/app/oposired';
const pending = (id, detail) => { L.results.push({ id, status: 'PENDING', detail }); console.log(`PENDING  ${id}  ${detail}`); };
const pid = (r) => r.json?.id ?? r.json?.publicacion?.id ?? r.json?.post?.id;
const approvedAuthor = (u) => sql(`insert into public.moderacion_autor (usuario_id, aprobadas) values ('${u.id}', 9) on conflict (usuario_id) do update set aprobadas = 9;`);
const mkChan = (name, spec = null, ro = false) => { const id = uuid(); sql(`insert into public.categorias_oposired (id, nombre, slug, activa, posicion, especialidad_id, solo_lectura) values ('${id}', 'E2N2 ${name} ${run}', 'e2n2-${name}-${run}', true, 1, ${spec ? `'${spec}'` : 'null'}, ${ro});`); return id; };
const mkPost = (u, categoriaId, titulo, cuerpo, key = uuid()) => call(u, 'POST', `${OPO}/publicaciones`, { categoriaId, titulo, cuerpo }, { 'Idempotency-Key': key });
const mkComment = (u, postId, cuerpo) => call(u, 'POST', `${OPO}/publicaciones/${postId}/comentarios`, { cuerpo }, { 'Idempotency-Key': uuid() });
const approvePost = (id) => sql(`select public.admin_foro_moderate(null, 'publicacion', '${id}', 'aprobar', null, null)::text;`);
const postEstado = (id) => sql(`select coalesce((select estado from public.publicaciones_oposired where id = '${id}'), 'MISSING');`);
const postOwner = (id) => sql(`select coalesce((select usuario_id::text from public.publicaciones_oposired where id = '${id}'), 'MISSING');`);
const commentEstado = (id) => sql(`select coalesce((select estado from public.comentarios_oposired where id = '${id}'), 'MISSING');`);
const lastCommentId = (u, postId) => sql(`select coalesce((select id::text from public.comentarios_oposired where usuario_id = '${u.id}' and publicacion_id = '${postId}' order by creado_en desc limit 1), '');`);
const orderRow = (id) => { try { return JSON.parse(sql(`select coalesce(row_to_json(c)::text, 'null') from (select id, usuario_id::text as usuario_id, estado from public.compras_usuario where id = '${id}') c;`)); } catch { return null; } };
const is4xx = (s) => s >= 400 && s < 500;

await scenario(only, 'G_PAYMENTS_ORDER_IDOR', async () => {
  const b = mkUser('g2b'); const a = mkUser('g2a');
  const ob = await call(b, 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_10', provider: 'sandbox' }, { 'Idempotency-Key': uuid() });
  const orderId = ob.json?.order?.id ?? ob.json?.id ?? ob.json?.orderId;
  check('G0.POS_B_creates_and_reads_own_payment_order', (ob.status === 200 || ob.status === 201) && !!orderId && (await GET(b, `/api/v1/app/payments/orders/${orderId}`)).status === 200, `create=${ob.status} id=${String(orderId).slice(0, 8)}`);
  if (!orderId) { check('G1.A_cannot_read_Bs_order', false, 'B order not created; cannot run cross-read'); return; }
  const before = orderRow(orderId);
  const aGet = await GET(a, `/api/v1/app/payments/orders/${orderId}`);
  check('G1.A_cannot_read_Bs_payment_order_by_id_4xx_no_leak', is4xx(aGet.status) && !aGet.text.includes(b.id), `A_get=${aGet.status} ${aGet.text.slice(0, 80)}`);
  const aVerify = await call(a, 'POST', `/api/v1/app/payments/orders/${orderId}/verify`, { receipt: { eventId: 'evt-' + uuid(), orderId, transactionId: 'tx-' + uuid(), amountCents: 999, currency: 'eur', productCode: 'LOCAL_PACK_10' } }, { 'Idempotency-Key': uuid() });
  const after = orderRow(orderId);
  check('G2.A_cannot_verify_Bs_order_4xx_and_Bs_order_state_unchanged', is4xx(aVerify.status) && JSON.stringify(before) === JSON.stringify(after), `A_verify=${aVerify.status} before=${before?.estado} after=${after?.estado}`);
  const bReRead = await GET(b, `/api/v1/app/payments/orders/${orderId}`);
  check('G3.POS_B_still_owns_and_reads_its_order_after_the_cross_attempts', bReRead.status === 200, `B_reread=${bReRead.status}`);
});

await scenario(only, 'H_WALLET_REDEEM_IDOR', async () => {
  const b = mkUser('h2b'); const a = mkUser('h2a');
  L.grant(b, 50); const exB = mkExam({ tipo: 'inmersivo', acceso: 'premium', price: 10, n: 1 });
  const key = uuid();
  const bOrder = await call(b, 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_10', provider: 'sandbox' }, { 'Idempotency-Key': uuid() });
  const bOrderId = bOrder.json?.order?.id ?? bOrder.json?.id;
  const bRedeem = await call(b, 'POST', '/api/v1/app/wallet/canjes', { examId: exB.id }, { 'Idempotency-Key': key });
  check('H0.POS_B_redeems_its_own_exam_with_its_own_tickets', bRedeem.status === 201 || bRedeem.status === 200, `B_redeem=${bRedeem.status} ${bRedeem.text.slice(0, 80)}`);
  const bBalBefore = L.balance(b); const aBalBefore = L.balance(a);
  // A reuses B's idempotency key and forges usuarioId/orderId in the body -> must not consume B's order or draw from B's wallet.
  const aForge = await call(a, 'POST', '/api/v1/app/wallet/canjes', { examId: exB.id, usuarioId: b.id, usuario_id: b.id, orderId: bOrderId, ordenId: bOrderId }, { 'Idempotency-Key': key, 'X-Usuario-Id': b.id });
  const bBalAfter = L.balance(b); const aBalAfter = L.balance(a);
  const aCrossRows = Number(sql(`select count(*) from public.transacciones_cartera where usuario_id = '${a.id}' and tipo = 'compra_examen' and referencia_id = '${exB.id}';`));
  check('H1.A_redeem_with_Bs_idem_key_and_forged_usuarioId_orderId_does_not_touch_Bs_wallet_or_A_draw_from_B', is4xx(aForge.status) || aForge.status === 409 ? bBalAfter === bBalBefore : (bBalAfter === bBalBefore && aBalAfter <= aBalBefore && aCrossRows === 0), `A_forge=${aForge.status} Bbal ${bBalBefore}->${bBalAfter} Abal ${aBalBefore}->${aBalAfter} crossRows=${aCrossRows}`);
  // B's legit redeem (same key) replays idempotently as B, never as A:
  const bReplay = await call(b, 'POST', '/api/v1/app/wallet/canjes', { examId: exB.id }, { 'Idempotency-Key': key });
  check('H2.Bs_own_key_still_replays_for_B_only_wallet_conserved', (bReplay.status === 200 || bReplay.status === 201 || bReplay.status === 409) && L.balance(b) === bBalAfter, `B_replay=${bReplay.status} bal=${L.balance(b)}`);
});

await scenario(only, 'I_OPOSIRED_CONTENT_IDOR', async () => {
  const chAll = mkChan('todos', null);
  const b = mkUser('i2b'); const a = mkUser('i2a'); approvedAuthor(b); approvedAuthor(a);
  const pB = await mkPost(b, chAll, 'post de B', 'contenido real de B ' + run); const postB = pid(pB); if (postB) approvePost(postB);
  const cB = await mkComment(b, postB, 'comentario real de B ' + run); const commentB = cB.json?.id ?? cB.json?.comentario?.id ?? lastCommentId(b, postB);
  check('I0.POS_B_creates_a_real_post_and_comment', !!postB && !!commentB && postOwner(postB) === b.id, `post=${pB.status} comment=${cB.status}`);
  if (!postB) return;
  // DELETE another user's post / comment -> must 4xx and leave the content intact (owner + estado unchanged)
  const estPostBefore = postEstado(postB); const estCommentBefore = commentEstado(commentB);
  const aDelPost = await call(a, 'DELETE', `${OPO}/publicaciones/${postB}`);
  const aDelComment = commentB ? await call(a, 'DELETE', `${OPO}/comentarios/${commentB}`) : { status: 0 };
  check('I1.A_cannot_delete_Bs_post_4xx_post_intact', is4xx(aDelPost.status) && postOwner(postB) === b.id && postEstado(postB) === estPostBefore, `A_del_post=${aDelPost.status} estado ${estPostBefore}->${postEstado(postB)}`);
  check('I2.A_cannot_delete_Bs_comment_4xx_comment_intact', (is4xx(aDelComment.status)) && commentEstado(commentB) === estCommentBefore, `A_del_comment=${aDelComment.status} estado ${estCommentBefore}->${commentEstado(commentB)}`);
  // VOTE / REPORT on another user's content are allowed by design; the security property is correct attribution (to A, never forged to B) and no content corruption / no 5xx.
  const aVote = await call(a, 'PUT', `${OPO}/publicaciones/${postB}/voto`);
  const aReport = await call(a, 'POST', `${OPO}/reportes`, { entidadTipo: 'publicacion', entidadId: postB, motivo: 'spam', descripcion: 'cross report by A' });
  const voteOwner = sql(`select coalesce((select usuario_id::text from public.reacciones_oposired where publicacion_id = '${postB}' order by creado_en desc limit 1), '');`);
  const reportOwner = sql(`select coalesce((select reportado_por::text from public.reportes_contenido where entidad_id = '${postB}' order by creado_en desc limit 1), '');`);
  check('I3.vote_on_Bs_post_is_allowed_but_attributed_to_A_never_B_no_5xx_post_intact', aVote.status < 500 && aReport.status < 500 && (voteOwner === '' || voteOwner === a.id) && (reportOwner === '' || reportOwner === a.id) && postOwner(postB) === b.id, `vote=${aVote.status}->${voteOwner.slice(0, 8)} report=${aReport.status}->${reportOwner.slice(0, 8)}`);
  // oposired image: B inits an image slot; A must not read its status nor PUT content into B's slot.
  const bImg = await call(b, 'POST', `${OPO}/imagenes`, { mime: 'image/png', bytes: 256 });
  const imgB = bImg.json?.imagenId ?? bImg.json?.id;
  if (!imgB) { pending('I4.imagenes_contenido_cross', `B image init did not return an id (status=${bImg.status}); image pipeline may be gated`); }
  else {
    const aImgGet = await call(a, 'GET', `${OPO}/imagenes/${imgB}`);
    const aImgPut = await fetch(L.KTOR + `${OPO}/imagenes/${imgB}/contenido`, { method: 'PUT', headers: { Authorization: 'Bearer ' + a.t, 'Content-Type': 'image/png' }, body: Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0]) }).then(async (r) => ({ status: r.status, text: await r.text() })).catch((e) => ({ status: -1, text: String(e) }));
    const estAfter = sql(`select coalesce((select estado from public.imagenes_oposired where id = '${imgB}'), 'MISSING');`);
    check('I4.A_cannot_read_Bs_image_status_nor_PUT_content_into_Bs_image_slot_4xx_no_verified_by_A', is4xx(aImgGet.status) && is4xx(aImgPut.status) && estAfter !== 'verificada', `A_get=${aImgGet.status} A_put=${aImgPut.status} estado=${estAfter}`);
    check('I5.POS_B_reads_its_own_image_status', (await call(b, 'GET', `${OPO}/imagenes/${imgB}`)).status === 200, 'B own image status');
  }
});

await scenario(only, 'J_RETOS_SEGMENT_IDOR', async () => {
  // Daily challenges are derived from the user's community segment (shared within a segment), not owned per user.
  // The IDOR surface is a user answering/reading a challenge of a segment they are not in -> expect 404.
  const b = mkUser('j2b'); const a = mkUser('j2a');
  const tB = await GET(b, '/api/v1/app/retos/hoy');
  const retoB = tB.json?.retoId; const pregB = tB.json?.preguntas?.[0]?.preguntaId;
  if (tB.status !== 200 || !retoB || !pregB) { pending('J.retos_cross_answer', `retos/hoy not seeded for this run (B status=${tB.status}, reto=${!!retoB}); segment-reto cross-answer already covered by e2-v1-challenge C9`); return; }
  check('J0.POS_B_opens_its_own_daily_challenge', tB.status === 200 && !!retoB && !!pregB, `B_hoy=${tB.status}`);
  const aAns = await call(a, 'PUT', `/api/v1/app/retos/${retoB}/respuestas/${pregB}`, { opcionSeleccionadaId: uuid() });
  const aRes = await call(a, 'GET', `/api/v1/app/retos/${retoB}/preguntas/${pregB}/recursos/${uuid()}`);
  check('J1.A_cannot_answer_or_read_resources_of_a_reto_question_outside_its_segment_404_no_5xx', aAns.status === 404 && aRes.status >= 400 && aRes.status < 500, `A_answer=${aAns.status} A_resource=${aRes.status}`);
});

await scenario(only, 'K_INTENTOS_RECURSOS_IDOR', async () => {
  const b = mkUser('k2b'); const a = mkUser('k2a');
  const exB = mkExam({ tipo: 'inmersivo', acceso: 'gratuito', n: 1 });
  const rid = uuid();
  sql(`set session_replication_role = replica; insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, ruta_storage, metadatos, posicion, activo) values ('${rid}', '${exB.qs[0].id}', 'E2N2-${run}', 'imagen', 'img', 'preguntas/${exB.qs[0].id}/${rid}.png', '{"mime":"image/png","bytes":100}'::jsonb, 1, true); set session_replication_role = origin;`);
  const s = await call(b, 'POST', `/api/v1/app/examenes/${exB.id}/intentos`, {}, { 'Idempotency-Key': uuid() });
  const attemptB = s.json?.intento?.id ?? s.json?.id;
  const bOwn = await GET(b, `/api/v1/app/intentos/${attemptB}/recursos/${rid}`);
  // Ownership is enforced BEFORE the storage layer: B's own request passes the ownership gate (never 401/403). The DB row has
  // no backing storage object in this fixture, so the content layer may answer 404/503 - that is a storage result, not an authz result.
  check('K0.POS_Bs_own_attempt_resource_passes_the_ownership_gate_not_401_403', !!attemptB && bOwn.status !== 401 && bOwn.status !== 403, `B_attempt=${s.status} B_resource=${bOwn.status} (no storage object seeded)`);
  if (!attemptB) return;
  const aCross = await GET(a, `/api/v1/app/intentos/${attemptB}/recursos/${rid}`);
  const aTrav = await call(a, 'GET', `/api/v1/app/intentos/${attemptB}/recursos/..%2F..%2Fsecret`);
  const bTrav = await call(b, 'GET', `/api/v1/app/intentos/${attemptB}/recursos/..%2F..%2Fsecret`);
  check('K1.A_cannot_read_a_resource_of_Bs_attempt_4xx_and_path_traversal_refused_no_5xx', is4xx(aCross.status) && aTrav.status < 500 && aTrav.status >= 400 && bTrav.status < 500 && bTrav.status >= 400, `A_cross=${aCross.status} A_trav=${aTrav.status} B_trav=${bTrav.status}`);
});

await scenario(only, 'L_STATS_DIFFICULTY_IDOR', async () => {
  const b = mkUser('l2b'); const a = mkUser('l2a');
  const exB = mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 1 });
  const s = await call(b, 'POST', `/api/v1/app/examenes/${exB.id}/intentos`, {}, { 'Idempotency-Key': uuid() });
  const attemptB = s.json?.intento?.id ?? s.json?.id;
  if (attemptB) await call(b, 'PUT', `/api/v1/app/intentos/${attemptB}/respuestas/${exB.qs[0].id}`, { opcionSeleccionadaId: exB.qs[0].ok, tiempoRespuestaMs: 1000, baseRevision: 0 }, { 'Idempotency-Key': uuid() });
  const bDiff = await GET(b, `/api/v1/app/estadisticas/dificultad/${exB.qs[0].id}`);
  const aDiff = await GET(a, `/api/v1/app/estadisticas/dificultad/${exB.qs[0].id}`);
  check('L1.difficulty_is_gated_to_questions_the_caller_answered_A_gets_404_for_a_question_only_B_answered', bDiff.status === 200 && aDiff.status === 404 && !/dificultad":[0-9]/.test(aDiff.text), `B=${bDiff.status} A=${aDiff.status} ${aDiff.text.slice(0, 60)}`);
});

await scenario(only, 'M_RANKING_VISIBILITY_IDOR', async () => {
  const b = mkUser('m2b'); const a = mkUser('m2a');
  const bVisBefore = sql(`select ranking_publico::text from public.perfiles_usuario where usuario_id = '${b.id}';`);
  const aForgeBody = await call(a, 'PUT', '/api/v1/app/ranking/visibilidad', { publico: true, usuarioId: b.id, usuario_id: b.id, ranking_publico: true });
  const aForgeQuery = await call(a, 'PUT', `/api/v1/app/ranking/visibilidad?usuarioId=${b.id}`, { publico: true });
  const bVisAfter = sql(`select ranking_publico::text from public.perfiles_usuario where usuario_id = '${b.id}';`);
  check('M1.A_setting_visibility_with_forged_usuarioId_only_changes_A_never_B', aForgeBody.status < 500 && aForgeQuery.status < 500 && bVisBefore === bVisAfter, `A_body=${aForgeBody.status} A_query=${aForgeQuery.status} Bvis ${bVisBefore}->${bVisAfter}`);
  const aOwn = await call(a, 'PUT', '/api/v1/app/ranking/visibilidad', { publico: true });
  const aVis = sql(`select ranking_publico::text from public.perfiles_usuario where usuario_id = '${a.id}';`);
  check('M2.POS_A_can_set_its_own_visibility_with_a_clean_body', (aOwn.status === 200 || aOwn.status === 204 || aOwn.status === 429) && ['true', 'false'].includes(aVis), `A_own_status=${aOwn.status} Avis=${aVis}`);
});

await scenario(only, 'N_INVITACION_IDOR', async () => {
  const hasAttach = sql(`select to_regprocedure('public.app_referral_attach(uuid,text,text)') is not null;`) === 't';
  const b = mkUser('n2b'); const a = mkUser('n2a');
  const aCode = (await GET(a, '/api/v1/app/me/invitacion')).json?.codigo;
  const bCode = (await GET(b, '/api/v1/app/me/invitacion')).json?.codigo;
  const aPlain = await GET(a, '/api/v1/app/me/invitacion');
  const aForged = await GET(a, `/api/v1/app/me/invitacion?usuarioId=${b.id}`);
  // Security property: a forged usuarioId query must NEVER return another user's code; A's plain read returns A's own code.
  check('N1.me_invitacion_never_returns_another_users_code_via_a_forged_usuarioId_query', aPlain.json?.codigo === aCode && aCode !== bCode && aForged.json?.codigo !== bCode, `A=${aCode} forged=${aForged.json?.codigo ?? '(none)'} B=${bCode}`);
  if (!hasAttach) { pending('N2.invitacion_redeem_neutral', 'app_referral_attach RPC not present on this stack'); return; }
  const self = JSON.parse(sql(`select public.app_referral_attach('${a.id}', '${aCode}', null)::text;`));
  const blocked = mkUser('n2blk'); const blockedCode = (await GET(blocked, '/api/v1/app/me/invitacion')).json?.codigo;
  L.sqlTry(`update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${blocked.id}';`);
  const fresh = mkUser('n2f'); const redeemBlocked = JSON.parse(sql(`select public.app_referral_attach('${fresh.id}', '${blockedCode}', null)::text;`));
  const unknown = JSON.parse(sql(`select public.app_referral_attach('${mkUser('n2u').id}', 'ZZZZZZZZ', null)::text;`));
  check('N2.redeeming_own_code_or_a_blocked_users_code_is_a_neutral_rejection_same_shape_as_unknown', self.adjunta === false && redeemBlocked.adjunta === false && JSON.stringify(redeemBlocked) === JSON.stringify(unknown), `self=${JSON.stringify(self)} blocked=${JSON.stringify(redeemBlocked)} unknown=${JSON.stringify(unknown)}`);
});

await scenario(only, 'O_BFLA_ADMIN_ROUTES', async () => {
  const ADM = process.env.ADMIN_KTOR_URL ?? 'http://127.0.0.1:8090';
  const u = mkUser('o2norm');
  const admUp = await fetch(`${ADM}/api/v1/admin/me`, { headers: { Authorization: `Bearer ${u.t}` } }).then((r) => r.status).catch(() => -1);
  const probe = async (base, method, p, body) => fetch(base + p, { method, headers: { Authorization: `Bearer ${u.t}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined }).then(async (r) => ({ p: `${method} ${p}`, status: r.status })).catch((e) => ({ p: `${method} ${p}`, status: -1, e: String(e) }));
  const routes = [['GET', '/api/v1/admin/me'], ['GET', '/api/v1/admin/dashboard'], ['GET', '/api/v1/admin/examenes'], ['GET', '/api/v1/admin/especialidades'], ['GET', '/api/v1/admin/auditoria'], ['GET', '/api/v1/admin/accesos'], ['POST', '/api/v1/admin/accesos', { userId: u.id, examId: uuid(), accessType: 'regalo', isActive: true }]];
  const target = admUp >= 0 ? ADM : L.KTOR; // control Ktor if reachable, else the app Ktor profile (must also never serve /admin to a normal user)
  const results2 = []; for (const [m, p, b] of routes) results2.push(await probe(target, m, p, b));
  const ok2xx = results2.filter((r) => r.status >= 200 && r.status < 300);
  const refused = results2.filter((r) => [401, 403, 404, 405].includes(r.status));
  check('O1.a_normal_user_JWT_is_refused_on_every_admin_route_never_2xx', ok2xx.length === 0 && refused.length === results2.length, `where=${target === ADM ? ':8090' : ':8091'} statuses=${results2.map((r) => r.p + '=' + r.status).join(' | ')}`);
});

// Store/commerce purchase routes of commit 8859ee4 are not mounted on this PhaseB server -> explicitly PENDING.
pending('P.store_routes_8859ee4', 'store/purchase routes from 8859ee4 are not part of this server build; not testable here');

L.finish(OUT);
