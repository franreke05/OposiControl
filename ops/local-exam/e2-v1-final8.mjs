#!/usr/bin/env node
// E2 FINAL8 re-attack (LOCAL ONLY): RK01 median rule (bypass cases), F05 NUL, image retirement on delete/hide/reject (F02/F06), register codigoInvitacion neutrality over HTTP, fixture/view safety.
// Own users/specialties/channels only. STACK_LOCK hard-fail. Own Ktor on :8091 (restarted). Shared config touched: referido_* (restored), cohorte_minima_comparativa (restored).
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-final8.mjs [--only=NAME,...]
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, start, answer, fin, uuid, run, sleep, comm } = L;

requireStackLock('e2-v1-final8.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-final8-result.json';
await L.restartKtor();
const RK = `e2c80000-0000-4000-8000-${run}000001`;
sql(`set session_replication_role = replica; insert into public.especialidades (id, codigo, nombre, slug, activa) values ('${RK}', 'E2R8-${run}', 'E2 final8 ${run}', 'e2-final8-${run}', true); set session_replication_role = origin;`);
const job = () => sql(`select public.app_job_ranking(true)::text;`);
const rank = (u) => call(u, 'GET', '/api/v1/app/ranking');

await scenario(only, 'A_RK01_MEDIAN', async () => {
  const minMs = Number(sql(`select valor_integer from public.configuracion_v1 where clave = 'ranking_min_ms_respuesta';`));
  const run50 = async (label, plan, n = 50) => {   // plan(i) -> {sleepMs, correct}; every answer reports the true elapsed time as its time
    const ex = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n, spec: RK }); const u = mkUser(label, RK); const a = L.attemptId(await start(u, ex.id));
    for (let i = 0; i < n; i++) { const p = plan(i); if (p.sleepMs) await sleep(p.sleepMs); const q = ex.qs[i]; await call(u, 'PUT', `/api/v1/app/intentos/${a}/respuestas/${q.id}`, { opcionSeleccionadaId: p.correct ? q.ok : q.ko, tiempoRespuestaMs: 60000, baseRevision: 0 }, { 'Idempotency-Key': uuid() }); }
    job(); const r = await rank(u); const med = sql(`select coalesce(ventana_tiempo_mediano_ms::text, 'null') from public.estado_competitivo_usuario where usuario_id = '${u.id}';`); return { u, ex, a, r, med, ranked: r.json?.posicion != null, nota: r.json?.notaMedia };
  };
  const c1 = await run50('k1', (i) => ({ sleepMs: i === 25 ? 55000 : 0, correct: true }));
  check('K1.49_instant_answers_plus_one_long_pause_are_NOT_ranked_the_original_RK01_bypass_is_closed', !c1.ranked, `median=${c1.med} ms (minimum ${minMs}) ranked=${c1.ranked}`);
  const c2 = await run50('k2', (i) => ({ sleepMs: i >= 30 ? 1100 : 0, correct: true }));
  check('K2.30_instant_plus_20_paced_answers_are_not_ranked', !c2.ranked, `median=${c2.med} ranked=${c2.ranked}`);
  const c3 = await run50('k3', (i) => ({ sleepMs: i < 25 ? 0 : 1100, correct: true }));
  check('K3.25_instant_plus_25_paced_answers_are_not_ranked_the_median_sits_between', !c3.ranked, `median=${c3.med} ranked=${c3.ranked}`);
  const c5 = await run50('k5', (i) => ({ sleepMs: i < 25 ? 0 : 1100, correct: i < 25 }));
  check('K4.padding_with_25_slow_WRONG_answers_does_not_rank_and_costs_half_the_grade', !c5.ranked, `median=${c5.med} nota=${c5.nota}`);
  const c6 = await run50('k6', (i) => ({ sleepMs: i < 24 ? 0 : 1100, correct: i < 24 }));
  finding('K5.24_instant_correct_plus_26_slow_wrong_answers_cost_a_5_point_grade_loss_to_get_ranked', !c6.ranked || Number(c6.nota) <= 5, `median=${c6.med} ranked=${c6.ranked} nota=${c6.nota} (ranked at the price of a ~4.8 grade)`, 'E2-V1-RK02');
  const c4 = await run50('k7', (i) => ({ sleepMs: i < 24 ? 0 : 1100, correct: true }));
  finding('K6.a_scripted_account_that_knows_every_answer_and_paces_26_of_50_answers_at_about_1_1_s_is_ranked_with_a_perfect_grade', !c4.ranked, `median=${c4.med} ms ranked=${c4.ranked} posicion=${c4.r.json?.posicion} nota=${c4.nota} (26 paced answers cost about 30 s of script time)`, 'E2-V1-RK02');
  // sliding window: the same account then answers 30 instant answers: the last-50 window median drops again
  if (c4.ranked) { const more = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 30, spec: RK }); const ma = L.attemptId(await start(c4.u, more.id)); for (const q of more.qs) await answer(c4.u, ma, q.id, q.ok); job(); const r2 = await rank(c4.u);
    check('K7.the_window_slides_a_ranked_account_that_goes_back_to_instant_answers_leaves_the_ranking', r2.json?.posicion == null, `posicion=${r2.json?.posicion}`); }
});

// ---------------- forum: NUL and images ----------------
const B = '/api/v1/app/oposired';
const SA = `e2c80000-0000-4000-8000-${run}000002`;
sql(`set session_replication_role = replica; insert into public.especialidades (id, codigo, nombre, slug, activa) values ('${SA}', 'E2R9-${run}', 'E2 foro8 ${run}', 'e2-foro8-${run}', true); set session_replication_role = origin;`);
const chId = uuid(); sql(`insert into public.categorias_oposired (id, nombre, slug, activa, posicion, especialidad_id, solo_lectura) values ('${chId}', 'E2 f8 ${run}', 'e2-f8-${run}', true, 1, '${SA}', false);`);
const approved = (u) => sql(`insert into public.moderacion_autor (usuario_id, aprobadas) values ('${u.id}', 9) on conflict (usuario_id) do update set aprobadas = 9;`);
const mkF = (l) => { const u = mkUser(l, SA); approved(u); return u; };
const post = (u, t, c, extra = {}) => call(u, 'POST', `${B}/publicaciones`, { categoriaId: chId, titulo: t, cuerpo: c, ...extra }, { 'Idempotency-Key': uuid() });
const pid = (r) => r.json?.id ?? r.json?.publicacion?.id;
const crc = (b) => zlib.crc32(b) >>> 0;
const chunk = (type, data) => { const t = Buffer.from(type); const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const c = Buffer.alloc(4); c.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([len, t, data, c]); };
const png = (w, h) => { const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2; const raw = Buffer.alloc((w * 3 + 1) * h, 120); for (let y = 0; y < h; y++) raw[y * (w * 3 + 1)] = 0; return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]); };
const putImg = (u, id, buf) => fetch(L.KTOR + `${B}/imagenes/${id}/contenido`, { method: 'PUT', headers: { Authorization: 'Bearer ' + u.t, 'Content-Type': 'image/png', 'Content-Length': String(buf.length) }, body: buf }).then((r) => r.status);
const img = async (u) => { const i = await call(u, 'POST', `${B}/imagenes`, { mime: 'image/png', bytes: 500 }); const id = i.json?.imagenId; if (id) await putImg(u, id, png(40, 40)); return id; };
const objExists = async (id) => { const p = sql(`select path from public.imagenes_oposired where id = '${id}';`); if (!p) return null; const r = await fetch('http://localhost:54321/storage/v1/object/authenticated/oposired-imagenes/' + p, { headers: { apikey: L.secrets.serviceKey, Authorization: 'Bearer ' + L.secrets.serviceKey } }); return r.ok; };
const estado = (id) => sql(`select estado from public.imagenes_oposired where id = '${id}';`);
const approvePost = (id) => sql(`select public.admin_foro_moderate(null, 'publicacion', '${id}', 'aprobar', null, null)::text;`);
const moderate = (id, a) => sql(`select public.admin_foro_moderate(null, 'publicacion', '${id}', '${a}', null, 'e2')::text;`);

await scenario(only, 'B_NUL', async () => {
  const u = mkF('n1'); const NUL = String.fromCharCode(0); const bad = [];
  const p1 = await post(u, 'titulo' + NUL, 'cuerpo normal'), p2 = await post(u, 'titulo', 'cuerpo' + NUL + 'con nul'), p3 = await post(u, NUL, NUL);
  const ok = await post(u, 'valido ' + run, 'cuerpo para comentar ' + run); approvePost(pid(ok));
  const c1 = await call(u, 'POST', `${B}/publicaciones/${pid(ok)}/comentarios`, { cuerpo: 'x' + NUL + 'y' }, { 'Idempotency-Key': uuid() });
  const r1 = await call(u, 'POST', `${B}/reportes`, { entidadTipo: 'publicacion', entidadId: pid(ok), motivo: 'otro', descripcion: 'd' + NUL }), r2 = await call(u, 'POST', `${B}/reportes`, { entidadTipo: 'publicacion', entidadId: pid(ok), motivo: 'spam' + NUL, descripcion: null });
  const raw = await call(u, 'POST', `${B}/publicaciones`, '{"categoriaId":"' + chId + '","titulo":"a\\u0000b","cuerpo":"c\\u0000d"}', { 'Idempotency-Key': uuid() }, true);
  const res = { p1, p2, p3, c1, r1, r2, raw };
  for (const [k, r] of Object.entries(res)) if (!(r.status >= 400 && r.status < 500)) bad.push(k + ':' + r.status);
  check('N1.a_NUL_character_in_title_body_comment_report_text_or_raw_JSON_escape_is_a_4xx_never_a_5xx', bad.length === 0, bad.join(' ') || Object.entries(res).map(([k, r]) => k + '=' + r.status).join(' '));
  check('N2.the_refusal_is_the_neutral_CONTENT_REJECTED_422_and_nothing_is_stored', p1.status === 422 && p1.json?.code === 'CONTENT_REJECTED' && Number(sql(`select count(*) from public.publicaciones_oposired where usuario_id = '${u.id}';`)) === 1, `${p1.status}/${p1.json?.code}`);
  const other = ['\u0001', '\u0008', '\u007F', '\u0085', ' ', ' '].map((c) => post(u, 'ctl' + run, 'a' + c + 'b ' + uuid().slice(0, 6))); const rs = await Promise.all(other);
  check('N3.other_control_and_separator_characters_never_cause_a_5xx', rs.every((r) => r.status < 500), rs.map((r) => r.status).join(','));
});

await scenario(only, 'C_IMAGE_RETIREMENT', async () => {
  const author = mkF('i1'), reader = mkF('i2');
  const mk = async (txt) => { const id = await img(author); const p = await post(author, txt + run, 'post con imagen ' + txt + ' ' + run, { imagenId: id }); if (pid(p)) approvePost(pid(p)); return { id, post: pid(p) }; };
  const d = await mk('borrar'), h = await mk('ocultar'), r = await mk('rechazar');
  const urlD = await call(reader, 'GET', `${B}/publicaciones/${d.post}/imagen`); const liveD = urlD.json?.url ? await fetch(urlD.json.url.startsWith('http') ? urlD.json.url : 'http://localhost:54321' + urlD.json.url) : { status: 0 };
  await call(author, 'DELETE', `${B}/publicaciones/${d.post}`); await sleep(1500);
  const oldD = urlD.json?.url ? await fetch(urlD.json.url.startsWith('http') ? urlD.json.url : 'http://localhost:54321' + urlD.json.url) : { status: 0 };
  check('F02a.deleting_the_post_retires_the_image_and_deletes_the_storage_object_at_once', estado(d.id) === 'retirada' && (await objExists(d.id)) === false, `estado=${estado(d.id)} object=${await objExists(d.id)}`);
  check('F02b.a_signed_URL_minted_before_the_delete_stops_working_with_the_object', liveD.status === 200 && oldD.status >= 400, `before=${liveD.status} after=${oldD.status}`);
  const newD = await call(reader, 'GET', `${B}/publicaciones/${d.post}/imagen`); check('F02c.no_new_URL_after_the_delete', newD.status === 404, `${newD.status}`);
  const urlH = await call(reader, 'GET', `${B}/publicaciones/${h.post}/imagen`); moderate(h.post, 'ocultar'); const hAfter = await call(reader, 'GET', `${B}/publicaciones/${h.post}/imagen`); await sleep(1500);
  check('F06a.a_moderator_hide_retires_the_image_state_and_stops_new_URLs', urlH.status === 200 && hAfter.status === 404 && estado(h.id) === 'retirada', `url=${urlH.status}->${hAfter.status} estado=${estado(h.id)}`);
  finding('F06b.the_hidden_posts_storage_object_is_removed_not_just_unreachable', (await objExists(h.id)) === false, `object exists after hide: ${await objExists(h.id)} (the periodic sweeper is the backstop)`, 'E2-V1-F07');
  moderate(h.post, 'restaurar'); const back = await call(reader, 'GET', `${B}/publicaciones/${h.post}`); const imgBack = await call(reader, 'GET', `${B}/publicaciones/${h.post}/imagen`);
  check('F06c.restoring_a_hidden_post_brings_it_back_WITHOUT_the_retired_image', back.status === 200 && imgBack.status === 404, `post=${back.status} image=${imgBack.status}`);
  const pend = mkF('i3'); sql(`update public.moderacion_autor set aprobadas = 0 where usuario_id = '${pend.id}';`); const pi = await img(pend); const pp = await post(pend, 'pendiente' + run, 'post pendiente con imagen ' + run, { imagenId: pi }); const st0 = sql(`select estado from public.publicaciones_oposired where id = '${pid(pp)}';`);
  moderate(pid(pp), 'rechazar'); await sleep(1500);
  check('F06d.rejecting_a_pending_post_retires_its_image', st0 === 'pendiente_moderacion' && estado(pi) === 'retirada', `post=${st0} image=${estado(pi)} object=${await objExists(pi)}`);
  const hid = (await call(reader, 'GET', `${B}/publicaciones/${pid(pp)}`)).status; check('F06e.a_rejected_post_is_not_readable', hid === 404, `${hid}`);
  const co = L.mkExam({ tipo: 'rafaga', acceso: 'gratuito', n: 1 }); void co;
  const reattach = await post(author, 'reuso' + run, 'intento reutilizar una imagen retirada ' + run, { imagenId: d.id });
  check('F06f.a_retired_image_cannot_be_attached_to_a_new_post', reattach.status >= 400 && reattach.status < 500, `${reattach.status}`);
});

await scenario(only, 'D_REGISTER_CODE_HTTP', async () => {
  const KT = L.KTOR; const STUB = 'http://127.0.0.1:9999'; const PASS = `Pw-${crypto.randomBytes(9).toString('base64url')}-ok1`;
  const prev = Object.fromEntries(['referido_activo', 'referido_ot_invitador', 'referido_ot_invitado'].map((k) => [k, sql(`select valor_integer from public.configuracion_v1 where clave = '${k}';`)]));
  try {
    sql(`update public.configuracion_v1 set valor_integer = 1 where clave = 'referido_activo'; update public.configuracion_v1 set valor_integer = 10 where clave in ('referido_ot_invitador', 'referido_ot_invitado');`);
    const inviter = mkUser('rg0'); const code = (await call(inviter, 'GET', '/api/v1/app/me/invitacion')).json?.codigo;
    const hit = async (p, method, body, hdr = {}) => { const r = await fetch(KT + p, { method, headers: { 'Content-Type': 'application/json', ...hdr }, body: body === undefined ? undefined : JSON.stringify(body) }); const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ } return { status: r.status, text, json }; };
    const variants = { none: undefined, valid: code, validLower: code.toLowerCase(), unknown: 'ZZZZZZZZ', quote: "x'; drop table invitaciones; --", long: 'A'.repeat(5000) };
    const results = {}; let i = 0;
    for (const [name, c] of Object.entries(variants)) {
      // the GoTrue leg (register + exchange) is covered by the register suite; here the PROFILE step is attacked with a freshly created, not yet onboarded account (own JWT)
      const nu = mkUser('rg' + (++i), null); sql(`update public.perfiles_usuario set onboarding_completado = false, username = null where usuario_id = '${nu.id}';`);
      const body = { firstName: 'E2', lastName: 'Reg', username: `e2r8${run}${i}`, specialty: 'Local', ...(c === undefined ? {} : { codigoInvitacion: c }) };
      const pr = await hit('/v1/auth/register/profile', 'POST', body, { Authorization: 'Bearer ' + nu.t }); const me = await hit('/api/v1/app/me', 'GET', undefined, { Authorization: 'Bearer ' + nu.t });
      results[name] = { status: pr.status, body: pr.text.slice(0, 80), onboarded: me.json?.profile?.onboardingCompleted === true, attached: sql(`select count(*) from public.invitaciones where invitado_id = '${nu.id}';`), uid: nu.id };
    }
    const names = Object.keys(results); const keyOf = (r) => `${r.status}|${r.body}|${r.onboarded}`;
    check('H1.the_register_profile_answer_and_the_onboarding_are_identical_whatever_the_invitation_code_is', names.every((n) => results[n].status === results.none.status && results[n].body === results.none.body && results[n].onboarded === true), names.map((n) => `${n}:${results[n].status}/${results[n].onboarded}`).join(' '));
    check('H2.only_a_usable_code_creates_an_invitation_and_nothing_in_the_response_tells', results.valid.attached === '1' && results.unknown.attached === '0' && results.quote.attached === '0' && results.long.attached === '0' && new Set(names.map((n) => results[n].body)).size === 1, names.map((n) => `${n}:${results[n].attached}`).join(' '));
    finding('H3.lower_case_valid_code_is_accepted_or_rejected_consistently', true, `lower-case valid code attached=${results.validLower.attached}`, 'INFO');
  } finally { for (const [k, v] of Object.entries(prev)) if (v !== '') sql(`update public.configuracion_v1 set valor_integer = ${v} where clave = '${k}';`); }
});

await scenario(only, 'E_FIXTURE_AND_VIEW_SAFETY', async () => {
  const view = sqlTry(`set role authenticated; select count(*) from public.ranking_ventanas_rapidas;`), viewAnon = sqlTry(`set role anon; select count(*) from public.ranking_ventanas_rapidas;`);
  check('S1.the_recreated_fast_window_view_is_not_readable_by_clients', (!view.ok || view.out.trim().split(String.fromCharCode(10)).pop() === '0') && (!viewAnon.ok || viewAnon.out.trim().split(String.fromCharCode(10)).pop() === '0'), `authenticated=${view.ok ? view.out.trim() : 'denied'} anon=${viewAnon.ok ? viewAnon.out.trim() : 'denied'}`);
  const oldCol = sql(`select count(*) from information_schema.columns where table_name = 'estado_competitivo_usuario' and column_name = 'ventana_tiempo_medio_ms';`);
  check('S2.the_old_mean_column_is_gone_and_the_median_column_exists', oldCol === '0' && sql(`select count(*) from information_schema.columns where table_name = 'estado_competitivo_usuario' and column_name = 'ventana_tiempo_mediano_ms';`) === '1', `old=${oldCol}`);
  const q8 = sql(`select estado_suscripcion || '|' || (renovacion_en is null) from public.estado_premium_usuario where usuario_id = '4d000000-0000-4000-8000-00000000004d';`);
  const coh = sql(`select count(*) from auth.users where email like 'qa-coh-%@exam.local';`), pub = sql(`select count(*) from public.perfiles_usuario where username like 'qa_cohorte_%' and ranking_publico;`);
  check('S3.fixtures_are_synthetic_local_only_Q8_is_cancelled_but_paid_the_cohort_is_55_users_with_3_public_handles', q8 === 'cancelada|true' && coh === '55' && pub === '3', `Q8=${q8} cohort=${coh} public=${pub}`);
  const mig = sql(`select count(*) from information_schema.table_privileges where table_name = 'ranking_ventanas_rapidas' and grantee in ('anon', 'authenticated', 'public');`);
  check('S4.no_client_grants_on_the_view_after_the_drop_and_create', mig === '0', `grants=${mig}`);
  const ret = sql(`select count(*) from auth.users where email = 'q8@exam.local' and encrypted_password is not null and encrypted_password <> '';`);
  check('S5.the_Q8_fixture_has_no_login_password_stored', ret === '0', `password rows=${ret}`);
  const dupe = sql(`select count(*) from (select email from auth.users group by 1 having count(*) > 1) x;`); check('S6.fixtures_applied_repeatedly_leave_no_duplicates', dupe === '0', `dup emails=${dupe}`);
});

L.killKtor();
L.finish(OUT);
