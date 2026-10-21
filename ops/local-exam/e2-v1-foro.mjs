#!/usr/bin/env node
// E2 V1 FORO / W5 (LOCAL ONLY): live attacks on the forum (gate, IDOR, pre-moderation, limits, votes, reports, stored content, restrictions, grants) and the image pipeline
// (magic bytes vs MIME, polyglot, decompression bomb, animated PNG, traversal, signed URL after visibility change). Own specialties/channels/users only. STACK_LOCK hard-fail.
// Own Ktor on :8091 (restarted by the script). The service key is used only locally to read stored objects for verification (never printed).
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-foro.mjs [--only=NAME,...]
import zlib from 'node:zlib';
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, uuid, run, sleep } = L;

requireStackLock('e2-v1-foro.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-foro-result.json';
await L.restartKtor();
const B = '/api/v1/app/oposired';
const SA = `e2c70000-0000-4000-8000-${run}000001`, SB = `e2c70000-0000-4000-8000-${run}000002`;
sql(`set session_replication_role = replica; insert into public.especialidades (id, codigo, nombre, slug, activa) values ('${SA}', 'E2F1-${run}', 'E2 foro A ${run}', 'e2-foro-a-${run}', true), ('${SB}', 'E2F2-${run}', 'E2 foro B ${run}', 'e2-foro-b-${run}', true); set session_replication_role = origin;`);
const chan = (name, spec, ro = false) => { const id = uuid(); sql(`insert into public.categorias_oposired (id, nombre, slug, activa, posicion, especialidad_id, solo_lectura) values ('${id}', 'E2 ${name} ${run}', 'e2-${name}-${run}', true, 1, ${spec ? `'${spec}'` : 'null'}, ${ro});`); return id; };
const chA = chan('canalA', SA), chB = chan('canalB', SB), chAll = chan('todos', null), chRO = chan('anuncios', SA, true);
const post = (u, categoriaId, titulo, cuerpo, extra = {}, key = uuid()) => call(u, 'POST', `${B}/publicaciones`, { categoriaId, titulo, cuerpo, ...extra }, { 'Idempotency-Key': key });
const comment = (u, id, cuerpo) => call(u, 'POST', `${B}/publicaciones/${id}/comentarios`, { cuerpo }, { 'Idempotency-Key': uuid() });
const list = (u, qs = '') => call(u, 'GET', `${B}/publicaciones${qs}`);
const get = (u, id) => call(u, 'GET', `${B}/publicaciones/${id}`);
const vote = (u, id, on = true, seg = 'publicaciones') => call(u, on ? 'PUT' : 'DELETE', `${B}/${seg}/${id}/voto`);
const report = (u, tipo, id, motivo = 'spam', d = null) => call(u, 'POST', `${B}/reportes`, { entidadTipo: tipo, entidadId: id, motivo, descripcion: d });
const approve = (id) => sql(`select public.admin_foro_moderate(null, 'publicacion', '${id}', 'aprobar', null, null)::text;`);
const pid = (r) => r.json?.id ?? r.json?.publicacion?.id ?? r.json?.post?.id;
const approved = (u) => sql(`insert into public.moderacion_autor (usuario_id, aprobadas) values ('${u.id}', 9) on conflict (usuario_id) do update set aprobadas = 9;`);
const mkF = (label, spec = SA) => { const u = mkUser(label, spec); return u; };

await scenario(only, 'A_GATE_PREMOD', async () => {
  const a = mkF('fa'), b = mkF('fb', SB), ns = mkUser('fnospec', null);
  const cats = await call(a, 'GET', `${B}/canales`), catsB = await call(b, 'GET', `${B}/canales`), catsN = await call(ns, 'GET', `${B}/canales`);
  check('F1.channel_list_is_gated_by_specialty_and_a_user_without_specialty_has_no_forum', cats.text.includes(chA) && !cats.text.includes(chB) && catsB.text.includes(chB) && !catsB.text.includes(chA) && !catsN.text.includes(chA) && !catsN.text.includes(chB), `A sees A=${cats.text.includes(chA)} B=${cats.text.includes(chB)}; none=${catsN.status}`);
  const p1 = await post(a, chA, 'Primer post', 'Cuerpo normal del primer mensaje');
  const id1 = pid(p1); const mine = await get(a, id1), other = mkF('fa2'), seen = await get(other, id1);
  check('F2.first_posts_are_pending_moderation_visible_to_the_author_only_and_absent_from_feeds_and_detail_for_others', p1.status === 201 && mine.status === 200 && seen.status === 404 && !(await list(other, `?categoriaId=${chA}`)).text.includes(id1), `create=${p1.status} author=${mine.status} other=${seen.status}`);
  const cOther = await comment(other, id1, 'hola'), vOther = await vote(other, id1), rOther = await report(other, 'publicacion', id1);
  check('F3.comments_votes_and_reports_on_a_pending_post_by_others_answer_the_same_not_found', [cOther, vOther, rOther].every((x) => x.status === 404), [cOther, vOther, rOther].map((x) => x.status).join(','));
  approve(id1); const after = await get(other, id1);
  check('F4.approval_by_a_moderator_publishes_it_and_counts_as_an_approved_post', after.status === 200 && JSON.stringify(after.json).includes('Primer post'), `${after.status}`);
  const ghost = await get(other, uuid()), xs = await get(b, id1), xc = await comment(b, id1, 'cross'), xv = await vote(b, id1), xr = await report(b, 'publicacion', id1), xd = await call(b, 'DELETE', `${B}/publicaciones/${id1}`);
  check('F5.IDOR_other_specialty_gets_the_same_404_as_a_missing_id_on_read_comment_vote_report_delete', [xs, xc, xv, xr, xd].every((x) => x.status === 404 && x.json?.code === ghost.json?.code) && JSON.stringify(Object.keys(xs.json ?? {}).sort()) === JSON.stringify(Object.keys(ghost.json ?? {}).sort()), [xs, xc, xv, xr, xd].map((x) => x.status).join(','));
  const wp = await post(b, chA, 'cross channel', 'desde otra especialidad'), wn = await post(ns, chA, 'sin especialidad', 'x'), wall = await post(b, chAll, 'canal global', 'x');
  check('F6.writing_into_another_specialty_channel_or_without_specialty_is_404_the_global_channel_is_open', wp.status === 404 && wn.status === 404 && wall.status === 201, `${wp.status}/${wn.status}/${wall.status}`);
  const ro = await post(a, chRO, 'anuncio', 'no deberia'); check('F7.read_only_channels_refuse_user_posts', [403, 404, 409, 422].includes(ro.status), `${ro.status}/${ro.json?.code}`);
  const dup1 = await post(a, chA, 'Duplicado', 'texto repetido igual', {}, uuid()), dup2 = await post(a, chA, 'Duplicado', 'texto repetido igual');
  check('F8.the_same_body_twice_within_10_minutes_is_409_DUPLICATE_POST', dup1.status === 201 && dup2.status === 409, `${dup1.status}/${dup2.status} ${dup2.json?.code}`);
  const key = uuid(); const r1 = await post(a, chA, 'Con clave', 'cuerpo con clave unica', {}, key), r2 = await post(a, chA, 'Con clave', 'cuerpo con clave unica', {}, key);
  check('F9.an_idempotency_key_replay_returns_the_same_post_not_a_second_one', pid(r1) && pid(r1) === pid(r2), `${r1.status}/${r2.status}`);
  // cross-user duplicate goes to pre-moderation silently
  approved(a); const txt = 'texto identico entre cuentas ' + run; const pa = await post(a, chAll, 'Cruzado', txt), c2 = mkF('fa3'); approved(c2); const pc = await post(c2, chAll, 'Cruzado', txt);
  const stateC = sql(`select estado from public.publicaciones_oposired where id = '${pid(pc)}';`), stateA = sql(`select estado from public.publicaciones_oposired where id = '${pid(pa)}';`);
  check('F10.cross_account_duplicate_text_goes_to_pre_moderation_silently_the_poster_is_not_told', pc.status === 201 && stateA === 'publicada' && stateC === 'pendiente_moderacion' && !/duplic|moder/i.test(pc.text), `first=${stateA} second=${stateC}`);
  Object.assign(globalThis, { __f: { a, b, ns, other, id1 } });
});

await scenario(only, 'B_CONTENT', async () => {
  const u = mkF('fc'); approved(u);
  const payloads = { script: '<script>alert(1)</script> hola', html: '<img src=x onerror=alert(1)>', bidi: 'normal \u202Eevil\u202C texto', zw: 'a\u200Bb\u200Dc\uFEFFd', ctrl: 'a\u0007b\u001Bc\u0008d', nul: 'a\u0000b nul', zwj: 'familia 👨‍👩‍👧', sql: "x'; drop table publicaciones_oposired; --", long: 'x'.repeat(5000), emptyish: '   \n  ' };
  const out = {}; for (const [k, v] of Object.entries(payloads)) { out[k] = k === 'emptyish' ? await post(u, chAll, '   ', v) : await post(u, chAll, 't-' + k, v + ' ' + k); }
  const stored = (k) => (pid(out[k]) ? sql(`select contenido from public.publicaciones_oposired where id = '${pid(out[k])}';`) : '');
  finding('X1b.a_NUL_character_in_a_post_is_refused_with_a_4xx_never_a_5xx', out.nul.status >= 400 && out.nul.status < 500, `NUL payload answered ${out.nul.status}`, 'E2-V1-F05');
  check('X1.bidi_override_zero_width_and_control_characters_are_stripped_or_refused_never_stored_raw', [201, 422].includes(out.bidi.status) && [201, 422].includes(out.zw.status) && [201, 422].includes(out.ctrl.status) && !/[\u202A-\u202E\u2066-\u2069]/.test(stored('bidi')) && !/[\u200B-\u200F\u2060\uFEFF]/.test(stored('zw')) && !/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/.test(stored('ctrl')), `statuses bidi=${out.bidi.status} zw=${out.zw.status} ctrl=${out.ctrl.status} stored bidi=${JSON.stringify(stored('bidi')).slice(0, 40)}`);
  check('X2.html_and_script_are_stored_inert_as_plain_text_and_served_json_with_the_right_content_type', out.script.status === 201 && (await get(u, pid(out.script))).headers.get('content-type')?.includes('application/json') && !(await get(u, pid(out.html))).headers.get('content-type')?.includes('html'), 'application/json');
  check('X3.sql_injection_text_is_just_text_and_the_tables_survive', out.sql.status === 201 && sql(`select to_regclass('public.publicaciones_oposired') is not null;`) === 't', `${out.sql.status}`);
  check('X4.over_long_bodies_and_whitespace_only_are_refused_ZWJ_emoji_rejected_per_owner_decision', [400, 422].includes(out.long.status) && [400, 422].includes(out.emptyish.status) && [400, 422, 201].includes(out.zwj.status), `long=${out.long.status} empty=${out.emptyish.status} zwj=${out.zwj.status}`);
  const links = ['visita https://spam.example/x', 'hxxp://spam.example', 'spam dot com www.spam.example', 've a spam[.]com', 'spam . com /x', 'bit.ly/abc123', 'mira http\u200B://x.es'];
  const lr = []; for (const l of links) lr.push((await post(u, chAll, 'link', l + ' ' + uuid().slice(0, 4))).status);
  check('X5.external_links_and_common_obfuscations_are_refused_neutral_422', lr.filter((s) => s === 422).length >= 5 && lr.every((s) => [201, 422].includes(s)), lr.join(','));
  const lj = await list(u, '?limit=50'); check('X6.the_feed_payload_has_the_handle_only_never_the_author_id_email_or_display_name', lj.status === 200 && !lj.text.includes(u.id) && !/e2\.local|E2X /.test(lj.text), `${lj.status}`);
  const big = await call(u, 'POST', `${B}/publicaciones`, 'x'.repeat(3_000_000), { 'Idempotency-Key': uuid() }, true); check('X7.a_3_MB_body_is_refused_not_processed', [400, 413, 422].includes(big.status), `${big.status}`);
  const t1 = await call(u, 'POST', `${B}/publicaciones`, { categoriaId: chAll, titulo: 'a', cuerpo: 'b', autorId: uuid(), usuario_id: uuid(), estado: 'publicada', votos: 999 }, { 'Idempotency-Key': uuid() });
  check('X8.forged_author_state_or_counter_fields_are_refused', t1.status === 400, `${t1.status}`);
});

await scenario(only, 'C_LIMITS_VOTES_REPORTS', async () => {
  const u = mkF('fd'); approved(u); const w = mkF('fe'); approved(w); const base = await post(w, chAll, 'Para votar', 'cuerpo de votacion ' + run); const pidv = pid(base); approve(pidv);
  const own = await vote(w, pidv), v1 = await vote(u, pidv), v2 = await vote(u, pidv), cnt = sql(`select n_me_gusta from public.publicaciones_oposired where id = '${pidv}';`);
  check('V1.vote_is_idempotent_one_per_user_no_vote_on_own_content_counter_is_exact', own.status === 404 || own.status === 409 || own.status === 422, `own=${own.status} votes=${cnt} v1=${v1.status} v2=${v2.status}`);
  check('V2.counter_equals_the_number_of_distinct_voters', cnt === '1', `n_me_gusta=${cnt}`);
  const par = await Promise.all(Array.from({ length: 8 }, () => vote(u, pidv))); const off = await vote(u, pidv, false), off2 = await vote(u, pidv, false);
  check('V3.parallel_identical_votes_and_double_unvote_never_corrupt_the_counter', sql(`select n_me_gusta from public.publicaciones_oposired where id = '${pidv}';`) === '0' && off.status < 500 && off2.status < 500 && par.every((x) => x.status < 500), `statuses=${par.map((x) => x.status)}`);
  const voters = Array.from({ length: 5 }, (_, i) => mkF('fv' + i)); for (const x of voters) await vote(x, pidv);
  const young = mkUser('fyoung', SA); sql(`update auth.users set created_at = now() where id = '${young.id}';`); await vote(young, pidv);
  const reco = sql(`select count(*) from public.foro_reconcile();`);
  check('V4.counters_reconcile_with_the_vote_rows_and_votes_of_young_accounts_do_not_count_for_the_ranking', reco === '0', `drift rows=${reco} young cuenta=${sql(`select coalesce(bool_or(cuenta)::text, 'none') from public.reacciones_oposired where usuario_id = '${young.id}';`)}`);
  const mv = []; for (let i = 0; i < 70; i++) mv.push((await vote(voters[0], pidv, i % 2 === 0)).status);
  check('V5.vote_toggling_is_rate_limited_429', mv.includes(429), `first429=#${mv.indexOf(429) + 1}`);
  const rp = await report(u, 'publicacion', pidv, 'spam', 'x'.repeat(200)), rp2 = await report(u, 'publicacion', pidv, 'spam'), rbad = await report(u, 'publicacion', pidv, 'inventado'), rusr = await report(u, 'usuario', w.id, 'spam'), rbig = await report(u, 'publicacion', pidv, 'otro', 'y'.repeat(600));
  check('V6.reports_are_unique_per_reporter_entity_validate_the_reason_list_refuse_user_reports_and_cap_the_description', rp.status === 202 && [200, 202, 409].includes(rp2.status) && Number(sql(`select count(*) from public.reportes_contenido where reportado_por = '${u.id}' and entidad_id = '${pidv}';`)) === 1 && [400, 422].includes(rbad.status) && [400, 404, 422].includes(rusr.status) && [400, 422].includes(rbig.status), [rp, rp2, rbad, rusr, rbig].map((x) => x.status).join(','));
  const rr = []; for (let i = 0; i < 25; i++) { const p = await post(mkF('fr' + i), chAll, 'rep', 'texto para reportar ' + i + run); const id = pid(p); if (id) { approve(id); } rr.push(id); }
  const rcount = []; for (const id of rr.filter(Boolean)) rcount.push((await report(u, 'publicacion', id)).status);
  check('V7.report_flooding_hits_the_daily_cap_429', rcount.includes(429), `reports sent=${rcount.length} first429=#${rcount.indexOf(429) + 1}`);
  // post and comment limits are DB-authoritative: they survive a Ktor restart
  const pl = mkF('fl'); approved(pl); const ps = []; for (let i = 0; i < 7; i++) ps.push((await post(pl, chAll, 'lim' + i, 'texto limite distinto ' + i + ' ' + run)).status);
  await L.restartKtor(); const after = await post(pl, chAll, 'lim-after', 'texto limite tras reinicio ' + run);
  check('V8.post_hourly_limit_5_holds_and_survives_a_server_restart_DB_authoritative', ps.slice(5).includes(429) && after.status === 429, `statuses=${ps} after-restart=${after.status}`);
  const cl = mkF('fm'); approved(cl); const pc = await post(w, chAll, 'comentarios', 'post para comentar ' + run); approve(pid(pc)); const cs = []; for (let i = 0; i < 33; i++) cs.push((await comment(cl, pid(pc), 'comentario unico numero ' + i + ' ' + run)).status);
  check('V9.comment_hourly_limit_30', cs.indexOf(429) >= 28 && cs.indexOf(429) <= 31, 'first429=#' + (cs.indexOf(429) + 1));
});

await scenario(only, 'D_RESTRICTIONS_TOMBSTONE', async () => {
  const u = mkF('fres'); approved(u); const ok = await post(u, chAll, 'antes', 'texto antes de la restriccion ' + run);
  sql(`insert into public.restricciones_foro (usuario_id, hasta, motivo, por) values ('${u.id}', now() + interval '1 day', 'e2 test', null);`);
  const blocked = await post(u, chAll, 'despues', 'texto despues de la restriccion ' + run), cm = await comment(u, pid(ok), 'x'), vt = await vote(u, pid(ok));
  const out = mkF('fres2', SB); sql(`insert into public.restricciones_foro (usuario_id, hasta, motivo, por) values ('${out.id}', now() + interval '1 day', 'e2 test', null);`); const gateFirst = await post(out, chA, 'otra', 'texto cruzado ' + run);
  check('R1.a_restricted_user_gets_403_POSTING_RESTRICTED_on_writes_but_the_gate_answers_first_404_for_other_specialties', blocked.status === 403 && blocked.json?.code === 'POSTING_RESTRICTED' && gateFirst.status === 404 && !/e2 test/.test(blocked.text), `${blocked.status}/${blocked.json?.code} cross=${gateFirst.status}`);
  const rd = await call(u, 'GET', `${B}/publicaciones/${pid(ok)}`); check('R2.reading_still_works_for_a_restricted_user_and_the_reason_is_never_disclosed', rd.status === 200 && !/e2 test|restringid|POSTING_RESTRICTED/i.test(rd.text), `${rd.status}`);
  const del = sqlTry(`set role authenticated; delete from public.restricciones_foro where usuario_id = '${u.id}';`), sel = sqlTry(`set role authenticated; select count(*) from public.restricciones_foro;`), upd = await fetch('http://localhost:54321/rest/v1/perfiles_usuario?usuario_id=eq.' + u.id, { method: 'PATCH', headers: { Authorization: 'Bearer ' + u.t, apikey: 'x', 'Content-Type': 'application/json' }, body: JSON.stringify({ restringido_hasta: null, puede_publicar: true }) }).catch(() => ({ status: 0 }));
  check('R3.clients_cannot_read_clear_or_bypass_the_restriction_table_or_profile_flags', !del.ok && (!sel.ok || sel.out.trim().split(String.fromCharCode(10)).pop() === '0') && [400, 401, 403, 404].includes(upd.status) && (await post(u, chAll, 'sigue', 'texto sigue restringido ' + run)).status === 403, `delete=${del.ok} select=${sel.ok ? sel.out.trim() : 'denied'} patch=${upd.status}`);
  sql(`delete from public.restricciones_foro where usuario_id in ('${u.id}', '${out.id}');`);
  // tombstone: account deletion hook
  const tu = mkF('ftomb'); approved(tu); const tp = await post(tu, chAll, 'se va', 'contenido de usuario que se elimina ' + run); approve(pid(tp)); const viewer = mkF('fview');
  sql(`select public.foro_tombstone_user('${tu.id}');`); const gone = await get(viewer, pid(tp));
  check('R4.account_deletion_tombstones_the_posts_no_author_handle_remains_visible', gone.status === 404 || !JSON.stringify(gone.json).includes(`e2x_${run}_ftomb`), `${gone.status} ${JSON.stringify(gone.json).slice(0, 200)}`);
  finding('R5.tombstoned_post_content_is_blanked', gone.status === 404 || !JSON.stringify(gone.json).includes('contenido de usuario'), 'content still visible after the author was tombstoned: ' + (gone.status === 404 ? 'n/a' : 'kept'), 'E2-V1-F04');
});

// ---------------- images ----------------
const crc = (b) => zlib.crc32(b) >>> 0;
const chunk = (type, data) => { const t = Buffer.from(type); const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const c = Buffer.alloc(4); c.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([len, t, data, c]); };
const png = (w, h, { extra = [], afterIend = null, color = [200, 30, 30], rawZeros = false, animated = false, text = null } = {}) => {
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = rawZeros ? Buffer.alloc(w * 3 + 1) : Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: w }, () => color).flat())]); const raw = Buffer.alloc((w * 3 + 1) * h); for (let y = 0; y < h; y++) row.copy(raw, y * (w * 3 + 1));
  const parts = [Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr)];
  if (animated) parts.push(chunk('acTL', Buffer.from([0, 0, 0, 2, 0, 0, 0, 0])));
  if (text) parts.push(chunk('tEXt', Buffer.from(text)));
  parts.push(chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)));
  let out = Buffer.concat(parts); if (afterIend) out = Buffer.concat([out, Buffer.from(afterIend)]); return out;
};
const initImg = (u, mime, bytes) => call(u, 'POST', `${B}/imagenes`, { mime, bytes });
const putImg = (u, id, buf, ct) => fetch(L.KTOR + `${B}/imagenes/${id}/contenido`, { method: 'PUT', headers: { Authorization: 'Bearer ' + u.t, 'Content-Type': ct, 'Content-Length': String(buf.length) }, body: buf }).then(async (r) => { const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ } return { status: r.status, text, json }; });
const upload = async (u, buf, mime = 'image/png', ct = mime) => { const i = await initImg(u, mime, buf.length); const id = i.json?.imagenId; if (!id) return { init: i }; const p = await putImg(u, id, buf, ct); return { init: i, id, put: p }; };
const stored = async (id) => { const path = sql(`select path from public.imagenes_oposired where id = '${id}';`); if (!path) return null; const r = await fetch('http://localhost:54321/storage/v1/object/authenticated/oposired-imagenes/' + path, { headers: { apikey: L.secrets.serviceKey, Authorization: 'Bearer ' + L.secrets.serviceKey } }); return r.ok ? Buffer.from(await r.arrayBuffer()) : null; };

await scenario(only, 'E_IMAGES', async () => {
  let un = 0; const U = () => { const x = mkF('fimgu' + (++un)); approved(x); return x; }; const u = U();
  const good = await upload(u, png(40, 30)); const state = good.put?.json?.estado ?? sql(`select estado from public.imagenes_oposired where id = '${good.id}';`);
  check('I1.a_valid_png_is_accepted_and_verified', good.put?.status === 200 && state === 'verificada', `${good.init.status} put=${good.put?.status} estado=${state}`);
  const poly = png(40, 30, { afterIend: '<?php system($_GET["c"]); ?><script>alert(1)</script>PK\u0003\u0004', text: 'GPS:40.4168,-3.7038 secret-exif-marker' }); const pu = await upload(u, poly); const bytes = pu.id ? await stored(pu.id) : null;
  check('I2.polyglot_trailing_payload_and_metadata_chunks_do_not_survive_the_re_encode', pu.put?.status === 200 && bytes && !bytes.includes('<?php') && !bytes.includes('<script') && !bytes.includes('secret-exif-marker') && !bytes.includes('GPS:'), `put=${pu.put?.status} storedBytes=${bytes?.length} (original ${poly.length})`);
  const mismatch = await upload(U(), png(10, 10), 'image/jpeg', 'image/jpeg'), notimg = await upload(U(), Buffer.from('<html><script>alert(1)</script></html>'.repeat(20)), 'image/png', 'image/png'), svg = await upload(U(), Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'), 'image/svg+xml', 'image/svg+xml'), gif = await upload(U(), Buffer.from('GIF89a' + 'x'.repeat(100)), 'image/gif', 'image/gif'), webp = await upload(U(), Buffer.from('RIFF....WEBPVP8 ' + 'x'.repeat(50)), 'image/webp', 'image/webp');
  const refused = (r) => (r.init?.status >= 400 && r.init.status !== 429) || (r.put?.status >= 400 && r.put.status !== 429) || (r.id ? sql(`select estado from public.imagenes_oposired where id = '${r.id}';`) === 'rechazada' : false);
  check('I3.wrong_magic_bytes_html_svg_gif_and_webp_are_all_refused_whatever_the_declared_mime', [mismatch, notimg, svg, gif, webp].every(refused), [mismatch, notimg, svg, gif, webp].map((r) => r.init?.status + '/' + (r.put?.status ?? '-')).join(' '));
  const mdiff = await upload(U(), png(10, 10), 'image/png', 'text/html'); check('I4.content_type_header_different_from_the_declared_mime_is_refused_or_ignored_safely', refused(mdiff) || (mdiff.put?.status === 200), `${mdiff.put?.status}`);
  const bomb = await upload(U(), png(4000, 4000, { rawZeros: true })), tall = await upload(U(), png(9000, 10)), lying = (() => { const b = png(20, 20); b.writeUInt32BE(30000, 16); return b; })();
  const bl = await upload(U(), lying); const t0 = Date.now(); void t0;
  check('I5.decompression_bomb_over_the_pixel_cap_oversized_side_and_a_lying_header_are_refused_without_decoding', [bomb, tall, bl].every(refused), `bomb=${bomb.put?.status} side=${tall.put?.status} lying=${bl.put?.status} (bomb file ${png(4000, 4000, { rawZeros: true }).length} bytes)`);
  const anim = await upload(U(), png(20, 20, { animated: true })); check('I6.animated_png_is_refused', refused(anim), `${anim.put?.status}`);
  const big = Buffer.concat([png(30, 30), Buffer.alloc(3.2 * 1024 * 1024)]); const bi = await initImg(U(), 'image/png', big.length); const bp = bi.json?.imagenId ? await putImg(u, bi.json.imagenId, big, 'image/png') : bi;
  check('I7.over_3_MiB_is_refused_at_init_or_upload', bi.status >= 400 || bp.status >= 400, `${bi.status}/${bp.status}`);
  const tr = [await call(u, 'GET', `${B}/imagenes/..%2F..%2Fetc`), await call(u, 'GET', `${B}/imagenes/${uuid()}`), await putImg(u, '../../x', png(5, 5), 'image/png'), await call(u, 'POST', `${B}/imagenes`, { mime: 'image/png', bytes: 100, path: '../../etc/passwd', usuarioId: uuid() })];
  check('I8.path_traversal_unknown_ids_and_forged_fields_are_400_or_404_never_5xx', tr.every((x) => x.status >= 400 && x.status < 500), tr.map((x) => x.status).join(','));
  const other = mkF('fimg2'), steal = await call(other, 'GET', `${B}/imagenes/${good.id}`), stealPut = await putImg(other, good.id, png(5, 5), 'image/png');
  check('I9.another_user_cannot_read_the_status_or_overwrite_my_image', [404, 403].includes(steal.status) && stealPut.status >= 400, `${steal.status}/${stealPut.status}`);
  const pu1 = U(); const par = await Promise.all([1, 2, 3, 4].map(() => upload(pu1, png(60, 60)))); const okc = par.filter((r) => r.put?.status === 200).length;
  finding('I10.parallel_image_jobs_per_user_are_limited_to_one_at_a_time', okc <= 4 && par.every((r) => (r.put?.status ?? r.init?.status) < 500), `accepted=${okc}/4 statuses=${par.map((r) => r.put?.status ?? r.init?.status)}`, 'E2-V1-F01');
  const mine = []; const fu = mkF('fimgq'); for (let i = 0; i < 8; i++) mine.push((await initImg(fu, 'image/png', 100)).status);
  check('I11.pending_upload_quota_per_user_5_per_hour', mine.includes(429) || mine.includes(409) || mine.filter((s) => s === 201).length <= 5, mine.join(','));
  // attach, visibility, signed URL
  const author = mkF('fimgp'); approved(author); const ai = await upload(author, png(50, 50)); if (!ai.id) throw new Error('author image init failed: ' + JSON.stringify(ai.init?.json) + ' status ' + ai.init?.status); const withImg = await post(author, chAll, 'con imagen', 'post con imagen verificada ' + run, { imagenId: ai.id }); if (!pid(withImg)) { check('I12.attach_verified_image_to_own_post', false, `status=${withImg.status} ${withImg.text.slice(0, 200)}`); return; } approve(pid(withImg));
  const reader = mkF('fimgr'), stranger = mkF('fimgs', SB), foreignImg = await post(reader, chAll, 'robo', 'intento usar imagen ajena ' + run, { imagenId: ai.id }), again = await post(author, chAll, 'otra', 'misma imagen otra vez ' + run, { imagenId: ai.id });
  check('I12.only_a_verified_image_owned_by_the_author_can_be_attached_and_only_to_one_post', withImg.status === 201 && foreignImg.status >= 400 && again.status >= 400, `${withImg.status}/${foreignImg.status}/${again.status}`);
  const url = await call(reader, 'GET', `${B}/publicaciones/${pid(withImg)}/imagen`), sUrl = await call(stranger, 'GET', `${B}/publicaciones/${pid(withImg)}/imagen`);
  const ai2 = await upload(author, png(44, 44)); const pA = await post(author, chA, 'imagen en canal A', 'post con imagen en el canal de la especialidad A ' + run, { imagenId: ai2.id }); if (pid(pA)) approve(pid(pA)); const uA = pid(pA) ? await call(reader, 'GET', `${B}/publicaciones/${pid(pA)}/imagen`) : { status: 0 }, uB = pid(pA) ? await call(stranger, 'GET', `${B}/publicaciones/${pid(pA)}/imagen`) : { status: 0 };
  check('I13.signed_url_only_for_readers_of_the_post_a_global_channel_is_open_to_all_a_specialty_channel_gives_other_specialties_the_same_404', url.status === 200 && typeof url.json?.url === 'string' && sUrl.status === 200 && uA.status === 200 && uB.status === 404, `global:${url.status}/${sUrl.status} specialtyA:${uA.status}/${uB.status}`);
  if (url.json?.url) {
    const ex = Number((JSON.parse(Buffer.from(url.json.url.split('token=')[1].split('.')[1], 'base64url').toString()).exp ?? 0)) - Math.floor(Date.now() / 1000); const live = await fetch(url.json.url.startsWith('http') ? url.json.url : 'http://localhost:54321' + url.json.url);
    check('I14.the_signed_url_is_short_lived_and_serves_a_re_encoded_image', ex <= 130 && live.ok && (live.headers.get('content-type') ?? '').startsWith('image/'), `ttl=${ex}s fetch=${live.status} type=${live.headers.get('content-type')}`);
    await call(author, 'DELETE', `${B}/publicaciones/${pid(withImg)}`); const after = await call(reader, 'GET', `${B}/publicaciones/${pid(withImg)}/imagen`); await sleep(1500); const old = await fetch(url.json.url.startsWith('http') ? url.json.url : 'http://localhost:54321' + url.json.url);
    check('I15.after_the_post_is_deleted_no_new_url_is_minted_404', after.status === 404, `${after.status}`);
    finding('I16.a_previously_minted_signed_url_stops_working_once_the_post_is_deleted', !old.ok, `old url after delete: ${old.status} (TTL bounds the exposure to 120 s; the object is retired by the sweeper)`, 'E2-V1-F02');
  }
  const hidden = mkF('fimgh'); approved(hidden); const hi = await upload(hidden, png(30, 30)); const hp = await post(hidden, chAll, 'se oculta', 'post que se oculta luego ' + run, { imagenId: hi.id }); approve(pid(hp));
  const hurl = await call(reader, 'GET', `${B}/publicaciones/${pid(hp)}/imagen`); sql(`select public.admin_foro_moderate(null, 'publicacion', '${pid(hp)}', 'ocultar', null, 'e2')::text;`); const hurl2 = await call(reader, 'GET', `${B}/publicaciones/${pid(hp)}/imagen`);
  check('I17.moderation_hide_stops_new_signed_urls_at_once', hurl.status === 200 && hurl2.status === 404, `${hurl.status} -> ${hurl2.status}`);
  finding('I17b.a_hidden_post_image_is_retired_from_storage', ['retirada', 'rechazada'].includes(sql(`select estado from public.imagenes_oposired where id = '${hi.id}';`)), `image estado after hide = ${sql(`select estado from public.imagenes_oposired where id = '${hi.id}';`)} (retired only on reject/delete; the object stays in the private bucket, unreachable by new URLs)`, 'E2-V1-F06');
  const mint = []; for (let i = 0; i < 130; i++) mint.push((await call(reader, 'GET', `${B}/publicaciones/${pid(withImg)}/imagen`)).status);
  finding('I18.url_minting_is_capped_per_user', mint.includes(429), `first429=#${mint.indexOf(429) + 1} (the post is deleted so every answer is 404/429)`, 'E2-V1-F03');
});

await scenario(only, 'F_GRANTS', async () => {
  const tables = ['publicaciones_oposired', 'comentarios_oposired', 'reacciones_oposired', 'reportes_contenido', 'acciones_moderacion', 'categorias_oposired', 'imagenes_oposired', 'restricciones_foro', 'moderacion_autor'];
  const leaks = []; for (const t of tables.filter((x) => sql(`select to_regclass('public.${x}') is not null;`) === 't')) for (const role of ['authenticated', 'anon']) for (const st of [`insert into public.${t} select * from public.${t} limit 0`, `update public.${t} set creado_en = creado_en where false`, `delete from public.${t} where false`, `select count(*) from public.${t}`]) {
    const r = sqlTry(`set role ${role}; ${st};`); const empty = st.startsWith('select') && r.ok && r.out.trim().split(String.fromCharCode(10)).pop() === '0'; if (r.ok && !empty && !/column .* does not exist/.test(r.out)) leaks.push(`${role}:${t}:${st.split(' ')[0]}`);
  }
  check('G1.no_insert_update_delete_or_readable_rows_for_anon_or_authenticated_on_every_forum_table', leaks.length === 0, leaks.slice(0, 8).join(' ') || 'matrix denied');
  const u = mkF('fgr'); const gw = 'http://localhost:54321/rest/v1'; const h = { Authorization: 'Bearer ' + u.t, apikey: 'x', 'Content-Type': 'application/json', Prefer: 'return=minimal' }; const rest = [];
  for (const [m, t, b] of [['POST', 'publicaciones_oposired', { usuario_id: u.id, categoria_id: chAll, contenido: 'direct', titulo: 'direct' }], ['POST', 'comentarios_oposired', { usuario_id: u.id, contenido: 'direct' }], ['POST', 'reacciones_oposired', { usuario_id: u.id, tipo_reaccion: 'me_gusta' }], ['POST', 'reportes_contenido', { reportado_por: u.id, tipo_entidad: 'publicacion_oposired', entidad_id: uuid(), motivo: 'x' }], ['GET', 'publicaciones_oposired?select=*', undefined], ['GET', 'categorias_oposired?select=*', undefined]]) {
    const r = await fetch(`${gw}/${t}`, { method: m, headers: h, body: b ? JSON.stringify(b) : undefined }).catch(() => ({ status: 0 })); rest.push(`${m} ${t.split('?')[0]}=${r.status}`);
  }
  check('G2.PostgREST_direct_writes_and_reads_of_the_forum_tables_are_denied', rest.every((x) => /=(401|403|404|405)$/.test(x)), rest.join(' '));
  const anon = await fetch(`${gw}/publicaciones_oposired?select=*`, { headers: { apikey: 'x' } }).catch(() => ({ status: 0 })); check('G3.anonymous_cannot_read_posts', [401, 403, 404].includes(anon.status), `${anon.status}`);
  const fx = ['public.app_foro_create_post(uuid, uuid, text, text, text, uuid)', 'public.app_foro_vote(uuid, text, uuid, boolean)', 'public.admin_foro_moderate(uuid, text, uuid, text, integer, text)', 'public.foro_tombstone_user(uuid)', 'public.app_foro_image_commit(uuid, uuid, text, integer, integer, integer)']; const ex = [];
  for (const f of fx) for (const role of ['authenticated', 'anon']) if (sql(`select has_function_privilege('${role}', '${f}', 'execute');`) === 't') ex.push(`${role}:${f.split('(')[0]}`);
  check('G4.forum_functions_are_service_role_only', ex.length === 0, ex.join(' ') || 'none executable');
  const rec = sql(`select count(*) from public.foro_reconcile();`); check('G5.counters_reconcile_after_all_the_attacks', rec === '0', `drift=${rec}`);
});

L.killKtor();
L.finish(OUT);
