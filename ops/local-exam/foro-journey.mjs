#!/usr/bin/env node
// J22 (E1, W5b): Foro / Red (text) over HTTP. Needs STACK_LOCK, the app Ktor (:8089) and `up.mjs --converge` (migration 20261022101900).
//   Author = Q1, readers = Q2 and Q3 (same specialty). Channels are created here (one open to the caller specialty, one scoped to ING). Control's moderation is called through the SQL function
//   (the Ktor admin route for it belongs to Control's integration). Everything it writes is removed at the start and at the end.
//   node ops/local-exam/foro-journey.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('foro-journey.mjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const Q1 = '2a000000-0000-4000-8000-00000000002a', Q2 = '2b000000-0000-4000-8000-00000000002b', Q3 = '2d000000-0000-4000-8000-00000000002d';
const ESP = 'e5000000-0000-4000-8000-000000000001', ING = 'e1000000-0000-4000-8000-000000000002';
const CH_OPEN = 'c0220000-0000-4000-8000-000000000001', CH_ING = 'c0220000-0000-4000-8000-000000000002';
const P = '/api/v1/app/oposired';
const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const http = async (user, method, p, body, key) => {
  const headers = { Authorization: `Bearer ${tokens[user] ?? user}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const r = await fetch(APP + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
};
/** A small valid PNG (8 x 8, red) built here with real CRC32 chunks: the server must accept it, re-encode it and serve it back. */
function tinyPng() {
  const w = 8, h = 8, raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; for (let x = 0; x < w; x++) { raw[y * (w * 3 + 1) + 1 + x * 3] = 200; raw[y * (w * 3 + 1) + 2 + x * 3] = 30; raw[y * (w * 3 + 1) + 3 + x * 3] = 30; } }
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(Buffer.concat([Buffer.from(type), data])) >>> 0); return Buffer.concat([len, Buffer.from(type), data, crc]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const rawPut = async (user, p, bytes, type) => { const r = await fetch(APP + p, { method: 'PUT', headers: { Authorization: `Bearer ${tokens[user]}`, 'Content-Type': type }, body: bytes }); const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ } return { status: r.status, json, text }; };
const results = [];
const check = (id, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };
const KEYS = ['foro_premoderacion_posts', 'foro_posts_hora', 'foro_votos_min'];
const prev = Object.fromEntries(sql(`select clave || '=' || valor_integer from public.configuracion_v1 where clave in (${KEYS.map((k) => `'${k}'`).join(',')})`).split('\n').map((l) => l.split('=')));
const clean = () => sql(`delete from public.reportes_contenido where motivo_codigo is not null and reportado_por in ('${Q1}','${Q2}','${Q3}');
delete from public.publicaciones_oposired where categoria_id in ('${CH_OPEN}', '${CH_ING}');
delete from public.categorias_oposired where id in ('${CH_OPEN}', '${CH_ING}');
delete from public.imagenes_oposired where usuario_id in ('${Q1}','${Q2}','${Q3}');
delete from public.restricciones_foro where usuario_id in ('${Q1}','${Q2}','${Q3}');
delete from public.moderacion_autor where usuario_id in ('${Q1}','${Q2}','${Q3}');
delete from public.foro_actividad where usuario_id in ('${Q1}','${Q2}','${Q3}');`);

try {
  clean();
  sql(`update auth.users set email_confirmed_at = now() - interval '3 days', created_at = now() - interval '3 days' where id in ('${Q1}','${Q2}','${Q3}');
update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_premoderacion_posts';
update public.configuracion_v1 set valor_integer = 100 where clave = 'foro_posts_hora';
update public.configuracion_v1 set valor_integer = 1000 where clave = 'foro_votos_min';
insert into public.categorias_oposired (id, nombre, slug, especialidad_id, posicion) values ('${CH_OPEN}', 'J22 abierto a mi especialidad', 'j22-abierto', '${ESP}', 90), ('${CH_ING}', 'J22 solo ING', 'j22-ing', '${ING}', 91);`);

  const canales = await http('Q1', 'GET', `${P}/canales`);
  const ids = (canales.json?.items ?? []).map((c) => c.id);
  check('J22.channels_follow_the_specialty_gate', canales.status === 200 && ids.includes(CH_OPEN) && !ids.includes(CH_ING) && canales.headers.get('cache-control') === 'no-store', `${canales.status} ${ids.length}`);
  const d501 = await http('Q1', 'GET', `${P}/categorias`);
  check('J22.d5_01_lists_only_channels_open_to_everybody', d501.status === 200 && !(d501.json?.items ?? []).some((c) => c.id === CH_OPEN || c.id === CH_ING), `${d501.status}`);

  const key = crypto.randomUUID();
  const created = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Duda de prueba J22', cuerpo: 'Una duda sobre el tema 3 que escribo desde el diario J22.' }, key);
  const postId = created.json?.id;
  check('J22.first_post_goes_to_pre_moderation', created.status === 201 && created.json?.enRevision === true && created.json?.autor?.handle === 'q1_saldo_alto' && !/@exam\.local|2a000000/.test(created.text), `${created.status} ${created.text.slice(0, 120)}`);
  const replay = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Otro titulo', cuerpo: 'Otro cuerpo distinto del anterior mensaje.' }, key);
  check('J22.same_idempotency_key_is_the_same_post', replay.status === 200 && replay.json?.id === postId && replay.headers.get('idempotent-replayed') === 'true', `${replay.status}`);
  const own = await http('Q1', 'GET', `${P}/publicaciones?categoriaId=${CH_OPEN}`);
  const others = await http('Q2', 'GET', `${P}/publicaciones?categoriaId=${CH_OPEN}`);
  const hidden = await http('Q2', 'GET', `${P}/publicaciones/${postId}`);
  check('J22.pending_post_is_visible_only_to_its_author', own.json?.items?.length === 1 && others.json?.items?.length === 0 && hidden.status === 404 && hidden.json?.code === 'NOT_FOUND', `${hidden.status}`);
  const noVote = await http('Q2', 'PUT', `${P}/publicaciones/${postId}/voto`);
  const noComment = await http('Q2', 'POST', `${P}/publicaciones/${postId}/comentarios`, { cuerpo: 'Yo tambien' });
  const noReport = await http('Q2', 'POST', `${P}/reportes`, { entidadTipo: 'publicacion', entidadId: postId, motivo: 'spam' });
  check('J22.votes_comments_reports_on_pending_posts_of_others_are_404', [noVote, noComment, noReport].every((r) => r.status === 404), [noVote, noComment, noReport].map((r) => r.status).join(','));

  sql(`select public.admin_foro_moderate('${Q3}', 'publicacion', '${postId}', 'aprobar', null, 'j22')`);
  const visible = await http('Q2', 'GET', `${P}/publicaciones/${postId}`);
  check('J22.approved_post_is_readable', visible.status === 200 && visible.json?.enRevision === false && visible.json?.cuerpo?.includes('tema 3'), `${visible.status}`);
  const c = await http('Q2', 'POST', `${P}/publicaciones/${postId}/comentarios`, { cuerpo: 'Buena duda, yo tambien la tenia.' }, crypto.randomUUID());
  const comments = await http('Q1', 'GET', `${P}/publicaciones/${postId}/comentarios`);
  const detail = await http('Q1', 'GET', `${P}/publicaciones/${postId}`);
  check('J22.flat_comment_with_counter', c.status === 201 && comments.json?.items?.length === 1 && comments.json.items[0].autor.handle === 'q2_falta_un_ot' && detail.json?.comentarios === 1, `${c.status} ${detail.json?.comentarios}`);
  const v1 = await http('Q2', 'PUT', `${P}/publicaciones/${postId}/voto`);
  const v2 = await http('Q2', 'PUT', `${P}/publicaciones/${postId}/voto`);
  const own1 = await http('Q1', 'PUT', `${P}/publicaciones/${postId}/voto`);
  check('J22.vote_is_idempotent_and_never_on_own_content', v1.json?.meGusta === 1 && v2.json?.meGusta === 1 && own1.status === 404, `${v1.json?.meGusta} ${v2.json?.meGusta} ${own1.status}`);
  const unvote = await http('Q2', 'DELETE', `${P}/publicaciones/${postId}/voto`);
  check('J22.unvote', unvote.json?.meGusta === 0 && unvote.json?.miVoto === false);

  // ---- images: init -> bytes (verified + re-encoded by the server) -> attach -> signed URL -> retirement
  const bytes = tinyPng();
  const init = await http('Q1', 'POST', `${P}/imagenes`, { mime: 'image/png', bytes: bytes.length });
  const imgId = init.json?.imagenId;
  check('J22.image_init_returns_an_id_and_never_the_path', init.status === 201 && !!imgId && !/\.png|oposired/.test(init.text), `${init.status} ${init.text.slice(0, 100)}`);
  const stranger = await http('Q2', 'GET', `${P}/imagenes/${imgId}`);
  check('J22.another_user_cannot_see_the_upload', stranger.status === 404);
  const gif = await rawPut('Q1', `${P}/imagenes/${imgId}/contenido`, Buffer.from('GIF89a-this-is-not-a-png'), 'image/png');
  const init2 = await http('Q1', 'POST', `${P}/imagenes`, { mime: 'image/png', bytes: bytes.length });
  const up = await rawPut('Q1', `${P}/imagenes/${init2.json?.imagenId}/contenido`, bytes, 'image/png');
  check('J22.fake_image_is_422_and_a_real_one_is_verified', gif.status === 422 && gif.json?.code === 'IMAGE_REJECTED' && up.status === 200 && up.json?.estado === 'verificada', `${gif.status} ${up.status} ${up.text.slice(0, 80)}`);
  const reuse = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Con imagen rechazada', cuerpo: 'Intento adjuntar una imagen que nunca se verifico.', imagenId: imgId });
  check('J22.unverified_image_cannot_be_attached', reuse.status === 422 && reuse.json?.code === 'IMAGE_REJECTED', `${reuse.status}`);
  const withImg = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Con imagen', cuerpo: 'Una publicacion con una imagen verificada propia.', imagenId: init2.json.imagenId });
  check('J22.post_with_a_verified_own_image', withImg.status === 201 && withImg.json?.imagenId === init2.json.imagenId, `${withImg.status}`);
  const stolen = await http('Q2', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Imagen ajena', cuerpo: 'Intento usar la imagen de otra persona.', imagenId: init2.json.imagenId });
  check('J22.someone_elses_or_used_image_is_refused', stolen.status === 422, `${stolen.status}`);
  const url = await http('Q2', 'GET', `${P}/publicaciones/${withImg.json.id}/imagen`);
  const fetched = url.json?.url ? await fetch(url.json.url) : null;
  const body = fetched ? Buffer.from(await fetched.arrayBuffer()) : Buffer.alloc(0);
  check('J22.signed_url_serves_the_reencoded_png', url.status === 200 && fetched?.status === 200 && body.subarray(0, 4).toString('hex') === '89504e47', `${url.status} ${fetched?.status} ${body.length}B`);
  const urlOther = await http('Q2', 'GET', `${P}/publicaciones/${postId}/imagen`);
  check('J22.no_image_url_for_a_post_without_image', urlOther.status === 404);
  const delImg = await http('Q1', 'DELETE', `${P}/publicaciones/${withImg.json.id}`);
  const afterDel = await http('Q2', 'GET', `${P}/publicaciones/${withImg.json.id}/imagen`);
  check('J22.deleting_the_post_retires_the_image', delImg.status === 200 && afterDel.status === 404 && sql(`select estado from public.imagenes_oposired where id = '${init2.json.imagenId}'`) === 'retirada');

  const ing = await http('Q2', 'POST', `${P}/publicaciones`, { categoriaId: CH_ING, titulo: 'Fuera de mi especialidad', cuerpo: 'No deberia poder publicar aqui nunca jamas.' });
  const ingFeed = await http('Q2', 'GET', `${P}/publicaciones?categoriaId=${CH_ING}`);
  check('J22.another_specialty_channel_is_404_for_read_and_write', ing.status === 404 && ingFeed.status === 404, `${ing.status} ${ingFeed.status}`);

  const link = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Mira esto', cuerpo: 'visita https://ejemplo.com ahora mismo por favor' });
  const spaced = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Mira esto', cuerpo: 'visita ejemplo . com ahora mismo por favor' });
  const zwj = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Familia', cuerpo: 'una familia \u{1F468}‍\u{1F469}‍\u{1F467} en el texto' });
  check('J22.links_and_zwj_are_422_content_rejected', [link, spaced, zwj].every((r) => r.status === 422 && r.json?.code === 'CONTENT_REJECTED'), [link, spaced, zwj].map((r) => r.status).join(','));
  const dup = await http('Q1', 'POST', `${P}/publicaciones`, { categoriaId: CH_OPEN, titulo: 'Duda de prueba J22', cuerpo: 'Una duda sobre el tema 3 que escribo desde el diario J22.' }, crypto.randomUUID());
  check('J22.same_text_again_is_409', dup.status === 409 && dup.json?.code === 'DUPLICATE_POST', `${dup.status}`);

  const r1 = await http('Q2', 'POST', `${P}/reportes`, { entidadTipo: 'publicacion', entidadId: postId, motivo: 'spam', descripcion: 'parece publicidad' });
  const r2 = await http('Q2', 'POST', `${P}/reportes`, { entidadTipo: 'publicacion', entidadId: postId, motivo: 'ofensivo' });
  const rBad = await http('Q2', 'POST', `${P}/reportes`, { entidadTipo: 'usuario', entidadId: postId, motivo: 'spam' });
  check('J22.report_once_per_reporter_and_never_a_user', r1.status === 202 && r2.status === 202 && rBad.status === 400 && sql(`select count(*) from public.reportes_contenido where reportado_por = '${Q2}' and entidad_id = '${postId}'`) === '1', `${r1.status} ${r2.status} ${rBad.status}`);

  sql(`select public.admin_foro_moderate('${Q3}', 'publicacion', '${postId}', 'silenciar', 1, 'j22')`);
  const muted = await http('Q1', 'POST', `${P}/publicaciones/${postId}/comentarios`, { cuerpo: 'Estoy silenciado y no deberia poder' });
  check('J22.muted_user_gets_403_without_a_reason', muted.status === 403 && muted.json?.code === 'POSTING_RESTRICTED' && !/silenc|motivo|j22/i.test(muted.text), `${muted.status}`);
  sql(`delete from public.restricciones_foro where usuario_id = '${Q1}'`);

  const del = await http('Q1', 'DELETE', `${P}/publicaciones/${postId}`);
  const gone = await http('Q2', 'GET', `${P}/publicaciones/${postId}`);
  const delOthers = await http('Q2', 'DELETE', `${P}/publicaciones/${postId}`);
  check('J22.delete_own_then_gone_for_everybody', del.status === 200 && gone.status === 404 && delOthers.status === 404, `${del.status} ${gone.status} ${delOthers.status}`);
  const badQuery = [await http('Q1', 'GET', `${P}/publicaciones?usuarioId=x`), await http('Q1', 'GET', `${P}/publicaciones?cursor=no%20valido`), await http('Q1', 'GET', `${P}/publicaciones/not-a-uuid`)];
  check('J22.strict_queries', badQuery.every((r) => r.status === 400), badQuery.map((r) => r.status).join(','));
  check('J22.unauthenticated_is_401', (await http('bad', 'GET', `${P}/canales`)).status === 401);
  check('J22.counters_match_the_rows', sql(`select count(*) from public.foro_reconcile()`) === '0');
} finally {
  clean();
  sql(Object.entries(prev).map(([k, v]) => `update public.configuracion_v1 set valor_integer = ${v} where clave = '${k}';`).join('\n'));
}
const failed = results.filter((r) => !r).length;
console.log(`\nJ22: ${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
