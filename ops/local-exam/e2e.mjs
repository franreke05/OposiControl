#!/usr/bin/env node
// EX15/EX16/EX11/EX12: E2E del motor de examen contra el stack LOCAL (up.mjs + run-ktor.mjs). Nunca contra MAIN.
//   node ops/local-exam/e2e.mjs        -> imprime PASS/FAIL por caso y escribe .local/e2e-result.json
// Cada caso parte de un intento NUEVO: se cierran (abandonan) los abiertos de los usuarios de prueba antes de empezar.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';

// Escritura en el stack compartido: falla (exit 3) si STACK_LOCK es de otro. Ver stack-lock.mjs.
requireStackLock('e2e.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const KTOR = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
// E5-B03: el e2e usa SUS PROPIOS usuarios para todo lo que escribe (intentos, cubos de limite): XA/XB (sql/94). A, B y P son
// de los recorridos de la app: solo se LEEN (cartera, derechos, premium) y nunca se tocan sus intentos.
const rawTokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
if (!rawTokens.XA || !rawTokens.XB) { console.error('tokens.json sin XA/XB: ejecuta up.mjs'); process.exit(2); }
const tokens = { A: rawTokens.XA, B: rawTokens.XB, RA: rawTokens.A, RB: rawTokens.B, P: rawTokens.P };
const XA = '1a000000-0000-4000-8000-00000000001a', XB = '1b000000-0000-4000-8000-00000000001b';
const secrets = JSON.parse(fs.readFileSync(path.join(local, 'secrets.json'), 'utf8'));
const ktorEnv = Object.fromEntries(fs.readFileSync(path.join(local, 'ktor.env'), 'utf8').split(/\r?\n/).filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));

const EX = { NORMAL: 'e0000000-0000-4000-8000-000000000001', RAZONADA: 'e0000000-0000-4000-8000-000000000002', IMAGEN: 'e0000000-0000-4000-8000-000000000003',
  AUDIO: 'e0000000-0000-4000-8000-000000000004', PREMIUM: 'e0000000-0000-4000-8000-000000000005', GRANDE: 'e0000000-0000-4000-8000-000000000006' };
const q = (e, n) => `f${e}000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const o = (e, n, l) => `0${e}000000-0000-4000-8${l}00-${String(n).padStart(12, '0')}`;
const RES = { IMG: '5e000000-0000-4000-8000-000000000007', AUD: '5e000000-0000-4000-8000-000000000002', PDF: '5e000000-0000-4000-8000-000000000003', ABSENT: '5e000000-0000-4000-8000-000000000004' };

// DECODIFICACION REAL de un fixture binario (E5-A1): ffprobe + ffmpeg de la imagen LOCAL del backend (el mismo ffmpeg de produccion),
// por stdin. Devuelve el primer stream ({codec_name, width, height, duration}) y si el decodificado completo termino sin errores.
const FFMPEG_IMAGE = execFileSync('docker', ['images', '--format', '{{.Repository}}:{{.Tag}}', 'oposicontrol-backend'], { encoding: 'utf8' })
  .split(String.fromCharCode(10)).map((l) => l.trim()).find(Boolean);
function decode(bytes) {
  if (!FFMPEG_IMAGE) throw new Error('sin imagen local oposicontrol-backend (ffmpeg) para decodificar');
  const run = (bin, args) => { try { return { ok: true, out: execFileSync('docker', ['run', '-i', '--rm', '--entrypoint', bin, FFMPEG_IMAGE, ...args], { input: bytes, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }) }; } catch (e) { return { ok: false, out: String(e.stderr ?? e.message) }; } };
  const probe = run('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_name,width,height,duration', '-of', 'json', '-i', 'pipe:0']);
  const full = run('ffmpeg', ['-v', 'error', '-xerror', '-i', 'pipe:0', '-f', 'null', '-']);
  const stream = probe.ok ? JSON.parse(probe.out).streams?.[0] ?? null : null;
  // Audio por stdin: ffprobe no conoce la duracion de un flujo no buscable; se DECODIFICA a PCM y se mide.
  let seconds = stream?.duration ? Number(stream.duration) : null;
  if (stream && stream.width === undefined) {
    const pcm = execFileSync('docker', ['run', '-i', '--rm', '--entrypoint', 'ffmpeg', FFMPEG_IMAGE, '-v', 'error', '-i', 'pipe:0', '-f', 's16le', '-ac', '1', '-ar', '16000', 'pipe:1'],
      { input: bytes, maxBuffer: 64 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'] });
    seconds = pcm.length / 32000;
  }
  return { stream, seconds, decoded: probe.ok && full.ok && full.out.trim() === '' };
}

const sql = (text) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();

async function call(user, method, p, body, key) {
  const headers = { Authorization: `Bearer ${tokens[user]}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const started = performance.now();
  const r = await fetch(KTOR + p, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch { /* vacio */ }
  return { status: r.status, json, text, ms: performance.now() - started, headers: r.headers };
}
const key = () => crypto.randomUUID();
const canon = (v) => JSON.stringify(v, (k, x) => (x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort()) : x));
const start = (user, exam) => call(user, 'POST', `/api/v1/app/examenes/${exam}/intentos`, {}, key());
const answer = (user, attempt, question, option, base = 0, k = key()) =>
  call(user, 'PUT', `/api/v1/app/intentos/${attempt}/respuestas/${question}`, { opcionSeleccionadaId: option, tiempoRespuestaMs: 1500, baseRevision: base }, k);

const results = [];
function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
async function scenario(id, fn) { try { await fn(); } catch (e) { check(id, false, `exception: ${e.message}`); } }

function resetAttempts() {
  sql(`update public.intentos_examen set estado = 'abandonado' where estado = 'en_progreso' and usuario_id in ('${XA}','${XB}');`);
}
function republishX() { // vuelve P1..P3 de EX_NORMAL al contenido X (idempotente)
  sql(`set session_replication_role = replica;
update public.preguntas set titulo = 'Enunciado X 1.1' where id = '${q(1, 1)}';
update public.opciones_pregunta set texto_opcion = replace(texto_opcion, ' Y', ' X') where pregunta_id = '${q(1, 1)}';
update public.soluciones_pregunta set opcion_correcta_id = '${o(1, 2, 1)}', razonamiento = 'Razonamiento X' where pregunta_id = '${q(1, 2)}';
delete from public.etiquetas_pregunta where pregunta_id = '${q(1, 3)}';
insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('${q(1, 3)}', '7a000000-0000-4000-8000-000000000001') on conflict do nothing;
set session_replication_role = origin;`);
}

resetAttempts();
republishX();

// ---------- AUTH_STUB (E5-I01): /auth/v1/user solo desde los claims de un JWT local valido; nada mas de GoTrue ----------
await scenario('AUTH_STUB', async () => {
  const GW = 'http://localhost:54321';
  const mint = (claims, key = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' }), kid = secrets.kid) => {
    const h = Buffer.from(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid })).toString('base64url');
    const b = Buffer.from(JSON.stringify(claims)).toString('base64url');
    return `${h}.${b}.${crypto.sign('sha256', Buffer.from(`${h}.${b}`), { key, dsaEncoding: 'ieee-p1363' }).toString('base64url')}`;
  };
  const get = (token, path = '/auth/v1/user', method = 'GET') =>
    fetch(GW + path, { method, headers: token ? { Authorization: `Bearer ${token}` } : {} });
  const ok = await get(tokens.A);
  const body = ok.status === 200 ? await ok.json() : null;
  check('AUTH_STUB.valid_token_echoes_claims', body?.id === XA && body.email === 'xa@exam.local'
    && body.app_metadata?.providers?.[0] === 'email', JSON.stringify(body));
  const now = Math.floor(Date.now() / 1000);
  const base = { sub: XA, role: 'authenticated', aud: 'authenticated', iss: `${GW}/auth/v1`, iat: now };
  const other = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey;
  const denied = await Promise.all([
    get(null), get('nope'), get(tokens.A.slice(0, -2) + 'AA'),
    get(mint({ ...base, exp: now - 10 })),                       // caducado
    get(mint({ ...base, exp: now + 60 }, other)),                // otra clave con el mismo kid
    get(mint({ ...base, exp: now + 60, iss: 'https://evil.example/auth/v1' })),
    get(mint({ ...base, exp: now + 60, role: 'service_role' })),
  ]);
  check('AUTH_STUB.invalid_tokens_401', denied.every((r) => r.status === 401), denied.map((r) => r.status).join(','));
  const others = await Promise.all([get(tokens.A, '/auth/v1/user', 'PUT'), get(null, '/auth/v1/token?grant_type=password', 'POST'),
    get(null, '/auth/v1/signup', 'POST'), get(tokens.A, '/auth/v1/admin/users')]);
  check('AUTH_STUB.no_other_auth_routes', others[0].status === 405 && others.slice(1).every((r) => r.status === 404), others.map((r) => r.status).join(','));
  const me = await call('A', 'GET', '/api/v1/app/me');
  const authMe = await call('A', 'GET', '/v1/auth/me');
  check('AUTH_STUB.app_me_and_auth_me_200', me.status === 200 && authMe.status === 200, `${me.status}/${authMe.status}`);
  const forged = await fetch(`${KTOR}/api/v1/app/me`, { headers: { Authorization: `Bearer ${mint({ ...base, exp: now + 60 }, other)}` } });
  check('AUTH_STUB.ktor_still_rejects_foreign_key', forged.status === 401, `${forged.status}`);
});

// ---------- VERSIONING_E2E: publish X -> start X -> publish Y -> continuar -> preguntas X -> finish -> review X ----------
await scenario('VERSIONING_E2E', async () => {
  const s = await start('A', EX.NORMAL);
  const attempt = s.json?.intento?.id;
  check('VERSIONING_E2E.start_X', s.status === 201 && !!attempt, `status=${s.status}`);
  sql(fs.readFileSync(path.join(here, 'sql/91_publish_revision_y.sql'), 'utf8'));
  const page = await call('A', 'GET', `/api/v1/app/intentos/${attempt}/preguntas?limit=20`);
  const p1 = page.json.items.find((i) => i.preguntaId === q(1, 1));
  check('VERSIONING_E2E.questions_still_X', p1.titulo === 'Enunciado X 1.1' && p1.opciones.every((x) => x.texto.endsWith(' X')), p1.titulo);
  const a2 = await answer('A', attempt, q(1, 2), o(1, 2, 1)); // A es correcta en X, incorrecta en Y
  check('VERSIONING_E2E.graded_with_X', a2.status === 200 && a2.json.esCorrecta === true && a2.json.opcionCorrectaId === o(1, 2, 1), JSON.stringify(a2.json));
  await answer('A', attempt, q(1, 1), o(1, 1, 2));
  const fin = await call('A', 'POST', `/api/v1/app/intentos/${attempt}/finalizar`, {}, key());
  check('VERSIONING_E2E.finish', fin.status === 200 && fin.json.resumen.correctas === 1 && fin.json.resumen.falladas === 1, JSON.stringify(fin.json?.resumen));
  const rev = await call('A', 'GET', `/api/v1/app/intentos/${attempt}/revision?limit=50`);
  const r1 = rev.json.items.find((i) => i.preguntaId === q(1, 1));
  const r2 = rev.json.items.find((i) => i.preguntaId === q(1, 2));
  check('VERSIONING_E2E.review_X', rev.status === 200 && r1.enunciado === 'Enunciado X 1.1' && r2.correctAnswer.opcionId === o(1, 2, 1) && r2.status === 'CORRECT',
    `${r1.enunciado} / ${r2.correctAnswer?.letra} / ${r2.status}`);
  const res = await call('A', 'GET', `/api/v1/app/intentos/${attempt}/resultado`);
  check('VERSIONING_E2E.result_X', res.json.preguntas[1].opcionCorrecta === o(1, 2, 1), res.json.preguntas[1].opcionCorrecta);
  // Un intento NUEVO ve Y (y reutiliza la revision de lo que no cambio).
  const sb = await start('B', EX.NORMAL);
  const pb = await call('B', 'GET', `/api/v1/app/intentos/${sb.json.intento.id}/preguntas?limit=20`);
  check('VERSIONING_E2E.new_attempt_sees_Y', pb.json.items[0].titulo === 'Enunciado Y 1.1', pb.json.items[0].titulo);
  const reuse = sql(`select count(distinct revision_pregunta_id) from public.preguntas_intento where intento_id in ('${attempt}','${sb.json.intento.id}')`);
  check('VERSIONING_E2E.changed_content_new_revisions', reuse === '6', `distinct revisions X+Y = ${reuse} (3 of X + 3 changed in Y)`);
  const ab = await answer('B', sb.json.intento.id, q(1, 2), o(1, 2, 1));
  check('VERSIONING_E2E.new_attempt_graded_Y', ab.json.esCorrecta === false && ab.json.opcionCorrectaId === o(1, 2, 2), JSON.stringify(ab.json));
  republishX();
});

// ---------- SECURITY (EX11) ----------
resetAttempts();
await scenario('SECURITY', async () => {
  const s = await start('A', EX.NORMAL); const a = s.json.intento.id;
  for (const [m, p, b, k] of [['GET', `/api/v1/app/intentos/${a}`], ['GET', `/api/v1/app/intentos/${a}/preguntas`],
    ['PUT', `/api/v1/app/intentos/${a}/respuestas/${q(1, 1)}`, { opcionSeleccionadaId: o(1, 1, 1), tiempoRespuestaMs: 1, baseRevision: 0 }, key()],
    ['POST', `/api/v1/app/intentos/${a}/finalizar`, {}], ['GET', `/api/v1/app/intentos/${a}/resultado`], ['GET', `/api/v1/app/intentos/${a}/revision`],
    ['GET', `/api/v1/app/intentos/${a}/recursos/${RES.IMG}`]]) {
    const r = await call('B', m, p, b, k);
    check(`SECURITY.B_cannot_${m}_${p.split('/').slice(5).join('/') || 'attempt'}`, r.status === 404 && r.json?.code === 'ATTEMPT_NOT_FOUND', `${r.status} ${r.json?.code}`);
  }
  const untouched = sql(`select count(*) from public.respuestas_intento where intento_id = '${a}'`);
  check('SECURITY.B_wrote_nothing', untouched === '0', untouched);
  const premium = await start('A', EX.PREMIUM);
  check('SECURITY.no_entitlement_start_denied', premium.status === 403 && premium.json.code === 'EXAM_LOCKED', `${premium.status} ${premium.json?.code}`);
  const foreignQ = await answer('A', a, q(2, 1), o(2, 1, 1));
  check('SECURITY.question_outside_attempt', foreignQ.status === 422 && foreignQ.json.code === 'QUESTION_NOT_IN_ATTEMPT', `${foreignQ.status} ${foreignQ.json?.code} ${foreignQ.text.includes('esCorrecta')}`);
  const foreignO = await answer('A', a, q(1, 1), o(1, 2, 1));
  check('SECURITY.option_of_other_question', foreignO.status === 422 && foreignO.json.code === 'OPTION_NOT_IN_QUESTION', `${foreignO.status} ${foreignO.json?.code}`);
  // Revision stale: saltar (rev 1) y responder con base 0 -> 409 determinista con currentRevision.
  await answer('A', a, q(1, 3), null, 0);
  const stale = await answer('A', a, q(1, 3), o(1, 3, 1), 0);
  check('SECURITY.stale_revision_conflict', stale.status === 409 && stale.json.code === 'REVISION_CONFLICT' && stale.json.details?.currentRevision === 1, `${stale.status} ${JSON.stringify(stale.json?.details)}`);
  // Replay idempotente sin duplicar.
  const k = key();
  const first = await answer('A', a, q(1, 1), o(1, 1, 1), 0, k);
  const replay = await answer('A', a, q(1, 1), o(1, 1, 1), 0, k);
  const rows = sql(`select count(*) from public.respuestas_intento where intento_id = '${a}' and pregunta_id = '${q(1, 1)}'`);
  check('SECURITY.idempotent_replay_no_duplicate', first.status === 200 && replay.status === 200 && replay.headers.get('idempotent-replayed') === 'true' && rows === '1' && canon(replay.json) === canon(first.json), `rows=${rows} first=${first.status} replay=${replay.status} hdr=${replay.headers.get('idempotent-replayed')} same=${canon(replay.json) === canon(first.json)} ${replay.text.slice(0, 200)}`);
  const reused = await answer('A', a, q(1, 2), o(1, 2, 1), 0, k);
  check('SECURITY.key_reused_other_body', reused.status === 422 && reused.json.code === 'IDEMPOTENCY_KEY_REUSED', `${reused.status} ${reused.json?.code}`);
  const changed = await answer('A', a, q(1, 1), o(1, 1, 2), 1);
  check('SECURITY.answer_locked', changed.status === 409 && changed.json.code === 'ANSWER_LOCKED', `${changed.status} ${changed.json?.code}`);
  await call('A', 'POST', `/api/v1/app/intentos/${a}/finalizar`, {});
  const after = await answer('A', a, q(1, 2), o(1, 2, 1));
  check('SECURITY.finished_answer_denied', after.status === 409 && after.json.code === 'ATTEMPT_NOT_IN_PROGRESS', `${after.status} ${after.json?.code}`);
  const abandonFinished = await call('A', 'POST', `/api/v1/app/intentos/${a}/abandonar`, { posicionPregunta: 1, motivo: 'salida_usuario' });
  check('SECURITY.finished_abandon_denied', abandonFinished.status === 409 && abandonFinished.json.code === 'ATTEMPT_ALREADY_FINISHED', `${abandonFinished.status}`);
  const resultOk = await call('A', 'GET', `/api/v1/app/intentos/${a}/resultado`);
  const reviewOk = await call('A', 'GET', `/api/v1/app/intentos/${a}/revision`);
  check('SECURITY.finished_result_and_review_allowed', resultOk.status === 200 && reviewOk.status === 200, `${resultOk.status}/${reviewOk.status}`);
  const noToken = await fetch(`${KTOR}/api/v1/app/intentos/${a}`);
  check('SECURITY.no_token_401', noToken.status === 401, `${noToken.status}`);
});

// ---------- RESOURCES (EX14): imagen, audio, invalido, ausente ----------
resetAttempts();
await scenario('RESOURCES', async () => {
  const si = await start('A', EX.IMAGEN); const ai = si.json.intento.id;
  const pi = await call('A', 'GET', `/api/v1/app/intentos/${ai}/preguntas`);
  const img = pi.json.items[0].recursos?.[0];
  check('RESOURCES.image_metadata', img?.id === RES.IMG && img.tipo === 'imagen' && img.mimeType === 'image/png' && img.version?.length === 16 && !pi.text.includes('preguntas/'), JSON.stringify(img));
  const acc = await call('A', 'GET', `/api/v1/app/intentos/${ai}/recursos/${RES.IMG}`);
  const bytes = acc.status === 200 ? await fetch(acc.json.url) : null;
  const body = bytes ? Buffer.from(await bytes.arrayBuffer()) : Buffer.alloc(0);
  check('RESOURCES.image_signed_bytes', acc.status === 200 && bytes.status === 200 && body.length === img.sizeBytes && body.subarray(1, 4).toString() === 'PNG', `access=${acc.status} bytes=${bytes?.status} len=${body.length}`);
  const imgDec = decode(body);
  check('RESOURCES.image_decodes_1200x800', imgDec.decoded && imgDec.stream?.codec_name === 'png' && imgDec.stream.width === 1200 && imgDec.stream.height === 800,
    `${JSON.stringify(imgDec.stream)} decoded=${imgDec.decoded} version=${img?.version} bytes=${body.length}`);
  const sa = await start('A', EX.AUDIO); const aa = sa.json.intento.id;
  const pa = await call('A', 'GET', `/api/v1/app/intentos/${aa}/preguntas`);
  const rec = pa.json.items[0].recursos ?? [];
  const aud = rec.find((r) => r.id === RES.AUD);
  check('RESOURCES.audio_metadata_envelope', aud?.tipo === 'audio' && aud.durationMs === 2000 && aud.envelope?.stepMs === 40 && Buffer.from(aud.envelope.samples, 'base64').length === 50,
    `durationMs=${aud?.durationMs} step=${aud?.envelope?.stepMs} samples=${aud?.envelope && Buffer.from(aud.envelope.samples, 'base64').length}`);
  check('RESOURCES.invalid_pdf_excluded', !rec.some((r) => r.id === RES.PDF), rec.map((r) => r.tipo).join(','));
  const pdf = await call('A', 'GET', `/api/v1/app/intentos/${aa}/recursos/${RES.PDF}`);
  check('RESOURCES.invalid_pdf_denied', pdf.status === 404 && pdf.json.code === 'RESOURCE_NOT_FOUND', `${pdf.status} ${pdf.json?.code}`);
  const audAcc = await call('A', 'GET', `/api/v1/app/intentos/${aa}/recursos/${RES.AUD}`);
  const audBytes = audAcc.status === 200 ? await fetch(audAcc.json.url) : null;
  const audBody = audBytes ? Buffer.from(await audBytes.arrayBuffer()) : Buffer.alloc(0);
  check('RESOURCES.audio_signed_bytes', audAcc.status === 200 && audBytes?.status === 200, `${audAcc.status} ${audBytes?.status}`);
  const audDec = decode(audBody);
  check('RESOURCES.audio_decodes_2s', audDec.decoded && audDec.stream?.codec_name === 'pcm_s16le' && Math.abs(audDec.seconds - 2) < 0.01,
    `${JSON.stringify(audDec.stream)} seconds=${audDec.seconds} decoded=${audDec.decoded}`);
  const absent = await call('A', 'GET', `/api/v1/app/intentos/${aa}/recursos/${RES.ABSENT}`);
  check('RESOURCES.absent_object_503_retryable', absent.status === 503 && !!absent.headers.get('retry-after') && !absent.text.includes('preguntas/'), `${absent.status} ${absent.json?.code}`);
  const cross = await call('A', 'GET', `/api/v1/app/intentos/${ai}/recursos/${RES.AUD}`);
  check('RESOURCES.resource_of_other_attempt_denied', cross.status === 404 && cross.json.code === 'RESOURCE_NOT_FOUND', `${cross.status} ${cross.json?.code}`);
  const bAcc = await call('B', 'GET', `/api/v1/app/intentos/${ai}/recursos/${RES.IMG}`);
  check('RESOURCES.premium_resource_of_A_denied_to_B', bAcc.status === 404 && bAcc.json.code === 'ATTEMPT_NOT_FOUND', `${bAcc.status} ${bAcc.json?.code}`);
  const sb = await start('B', EX.IMAGEN);
  const same = sql(`select count(distinct revision_pregunta_id) from public.preguntas_intento where intento_id in ('${ai}', '${sb.json.intento.id}')`);
  check('RESOURCES.unchanged_content_reuses_revision', same === '1', `distinct revisions for 2 attempts on unchanged content = ${same}`);
  const direct = await fetch(`http://localhost:54321/storage/v1/object/recursos-pregunta/preguntas/${q(3, 1)}/${RES.IMG}.png`);
  check('RESOURCES.private_bucket_without_signature_denied', direct.status >= 400, `${direct.status}`);
});

// ---------- RESOURCES (EX14): video inmersivo y recurso lento ----------
resetAttempts();
await scenario('RESOURCES_VIDEO_SLOW', async () => {
  const sv = await start('A', 'e0000000-0000-4000-8000-000000000007'); const av = sv.json.intento.id;
  const pv = await call('A', 'GET', `/api/v1/app/intentos/${av}/preguntas`);
  const vid = pv.json.items[0].recursos?.[0];
  check('RESOURCES.video_metadata', vid?.tipo === 'video' && vid.mimeType === 'video/mp4' && !vid.envelope, JSON.stringify(vid));
  const va = await call('A', 'GET', `/api/v1/app/intentos/${av}/recursos/${vid?.id}`);
  const vb = va.status === 200 ? Buffer.from(await (await fetch(va.json.url)).arrayBuffer()) : Buffer.alloc(0);
  check('RESOURCES.video_signed_bytes_mp4', va.status === 200 && vb.length === vid.sizeBytes && vb.subarray(4, 8).toString() === 'ftyp', `len=${vb.length} box=${vb.subarray(4, 8)}`);
  const vidDec = decode(vb);
  check('RESOURCES.video_decodes_h264_160x120', vidDec.decoded && vidDec.stream?.codec_name === 'h264' && vidDec.stream.width === 160 && vidDec.stream.height === 120,
    `${JSON.stringify(vidDec.stream)} decoded=${vidDec.decoded}`);
  const ss = await start('A', 'e0000000-0000-4000-8000-000000000008'); const as = ss.json.intento.id;
  const ps = await call('A', 'GET', `/api/v1/app/intentos/${as}/preguntas`);
  const slow = ps.json.items[0].recursos?.[0];
  check('RESOURCES.slow_audio_metadata_envelope', slow?.durationMs === 30000 && slow.envelope?.stepMs === 40, `durationMs=${slow?.durationMs}`);
  const t0 = performance.now();
  const sa = await call('A', 'GET', `/api/v1/app/intentos/${as}/recursos/${slow?.id}`);
  const accessMs = performance.now() - t0;
  const res = await fetch(sa.json.url);
  const reader = res.body.getReader(); let got = 0; const t1 = performance.now();
  while (got < 128 * 1024) { const { value, done } = await reader.read(); if (done) break; got += value.length; }
  const kbps = got / 1024 / ((performance.now() - t1) / 1000);
  await reader.cancel();
  check('RESOURCES.slow_bytes_throttled_access_fast', sa.status === 200 && accessMs < 1000 && kbps < 64, `access=${accessMs.toFixed(0)}ms download=${kbps.toFixed(1)}KB/s`);
  // El lento se decodifica leyendo el objeto con la clave de servicio (sin el limite de 32 KB/s de la URL firmada).
  const slowObj = await fetch(`http://localhost:54321/storage/v1/object/authenticated/recursos-pregunta/preguntas/f8000000-0000-4000-8000-000000000001/${slow?.id}.wav`,
    { headers: { apikey: secrets.serviceKey, Authorization: `Bearer ${secrets.serviceKey}` } });
  const slowDec = decode(Buffer.from(await slowObj.arrayBuffer()));
  check('RESOURCES.slow_audio_decodes_30s', slowObj.ok && slowDec.decoded && Math.abs(slowDec.seconds - 30) < 0.01, `${JSON.stringify(slowDec.stream)} seconds=${slowDec.seconds} decoded=${slowDec.decoded}`);
});

// ---------- CONCURRENCY (E2-I01 items 2, 3, 5, 4) ----------
resetAttempts();
await scenario('CONCURRENCY', async () => {
  const starts = await Promise.all(Array.from({ length: 20 }, () => start('A', EX.RAZONADA)));
  const ids = new Set(starts.map((r) => r.json?.intento?.id));
  const created = starts.filter((r) => r.status === 201).length;
  const open = sql(`select count(*) from public.intentos_examen where usuario_id = '${XA}' and examen_id = '${EX.RAZONADA}' and estado = 'en_progreso'`);
  check('CONCURRENCY.20_parallel_starts_one_attempt', ids.size === 1 && created === 1 && open === '1', `ids=${ids.size} 201s=${created} open=${open} statuses=${[...new Set(starts.map((r) => r.status))]}`);
  const a = [...ids][0];
  const answers = await Promise.all(Array.from({ length: 20 }, (_, i) => answer('A', a, q(2, 1), o(2, 1, (i % 2) + 1))));
  const rows = sql(`select count(*), max(revision) from public.respuestas_intento where intento_id = '${a}' and pregunta_id = '${q(2, 1)}'`);
  const statuses = answers.map((r) => r.status);
  const ok = answers.filter((r) => r.status === 200);
  const chosen = ok.map((r) => r.json.esCorrecta);
  check('CONCURRENCY.20_parallel_answers_one_row', rows === '1|1' && statuses.every((s) => s === 200 || s === 409) && new Set(chosen).size === 1,
    `rows=${rows} 200=${ok.length} 409=${statuses.filter((s) => s === 409).length}`);
  const [f1, f2, f3] = await Promise.all([call('A', 'POST', `/api/v1/app/intentos/${a}/finalizar`, {}), call('A', 'POST', `/api/v1/app/intentos/${a}/finalizar`, {}),
    answer('A', a, q(2, 2), o(2, 2, 1))]);
  const tags = sql(`select count(*) from public.resumen_intento_etiqueta where intento_id = '${a}'`);
  const late = sql(`select count(*) from public.respuestas_intento r join public.intentos_examen i on i.id = r.intento_id where r.intento_id = '${a}' and r.creada_en > i.finalizado_en`);
  check('CONCURRENCY.double_finish_same_result', f1.status === 200 && f2.status === 200 && JSON.stringify(f1.json) === JSON.stringify(f2.json), `${f1.status}/${f2.status}`);
  check('CONCURRENCY.finish_vs_answer_no_late_row', (f3.status === 200 || (f3.status === 409 && f3.json.code === 'ATTEMPT_NOT_IN_PROGRESS')) && late === '0', `answer=${f3.status} late=${late} tagRows=${tags}`);
});

// ---------- WALLET / ENTITLEMENT (E5-B01): D6-01, D6-02, D7-02 sobre sql/92_phase_b_wallet_entitlement.sql ----------
await scenario('WALLET', async () => {
  const [wa, wb, ma, mb] = await Promise.all([call('RA', 'GET', '/api/v1/app/wallet'), call('RB', 'GET', '/api/v1/app/wallet'),
    call('RA', 'GET', '/api/v1/app/wallet/movimientos?limit=10'), call('RB', 'GET', '/api/v1/app/wallet/movimientos?limit=10')]);
  check('WALLET.D6_01_A_balance_and_recent', wa.status === 200 && wa.json.saldo === 35 && wa.json.recientes?.length === 5
    && wa.json.recientes[0].saldoResultante === 35, `${wa.status} saldo=${wa.json?.saldo} recientes=${wa.json?.recientes?.length}`);
  const tipos = new Set((ma.json?.items ?? []).map((m) => m.tipo));
  check('WALLET.D6_02_A_movements_several_categories', ma.status === 200 && ma.json.items.length === 5 && tipos.size === 5
    && ma.json.items.every((m, i, all) => i === 0 || m.saldoResultante === all[i - 1].saldoAnterior), `${ma.status} tipos=${[...tipos].join(',')}`);
  check('WALLET.D6_01_A_consumable_active', (wa.json?.consumibles ?? []).some((c) => c.servicioCodigo === 'potenciador'), JSON.stringify(wa.json?.consumibles ?? null).slice(0, 120));
  check('WALLET.D6_01_B_empty', wb.status === 200 && wb.json.saldo === 0 && wb.json.recientes.length === 0 && wb.json.consumibles.length === 0, `${wb.status}`);
  check('WALLET.D6_02_B_empty', mb.status === 200 && mb.json.items.length === 0, `${mb.status}`);
  check('WALLET.own_only', !(mb.text.includes('7d000000') || wb.text.includes('7d000000')), 'B never sees A ledger ids');
  // E2-B02: compras_usuario (captura de MAIN) presente; D7-05 kind=purchase solo lee compras (suscripciones no esta en el stack local).
  const [pa, pb] = await Promise.all(['RA', 'RB'].map((u) => call(u, 'GET', '/api/v1/app/me/billing-history?kind=purchase')));
  check('WALLET.D7_05_purchases_own_only', pa.status === 200 && pa.json.items?.length === 1 && pa.json.items[0].id === '7d100000-0000-4000-8000-000000000001'
    && pa.json.items[0].estado === 'pagada' && pb.status === 200 && pb.json.items.length === 0, `A=${pa.status}/${pa.json?.items?.length} B=${pb.status}/${pb.json?.items?.length}`);
  const fk = sql("select count(*) from pg_constraint where conname in ('transacciones_cartera_compra_id_fkey', 'compras_usuario_usuario_id_fkey', 'transacciones_cartera_idempotency_key_key')");
  check('WALLET.ledger_schema_matches_main_capture', fk === '3', `constraints=${fk}`);
  // Decision 4: B tiene una fila REAL de cartera con saldo 0 (el 0 lo confirma el servidor, no la ausencia de fila).
  const bRow = sql("select count(*) || ':' || coalesce(max(saldo_tickets), -1) from public.carteras_usuario where usuario_id = '0b000000-0000-4000-8000-00000000000b'");
  check('WALLET.B_real_zero_row_server_confirmed', bRow === '1:0' && wb.json?.saldo === 0, `row=${bRow} saldo=${wb.json?.saldo}`);
  // Decision 3: la forma local de las tablas de cartera/compras/derechos == captura de catalogo de MAIN (main-wallet-shape.txt).
  const norm = (t) => t.split(/\r?\n/).filter((l) => l && !l.startsWith('#') && l !== 'SET').map((l) => {
    const m = l.match(/^(\S+) COLS (.*)$/); return m ? `${m[1]} COLS ${m[2].split(', ').sort().join(', ')}` : l; }).sort();
  const want = norm(fs.readFileSync(path.join(here, 'main-wallet-shape.txt'), 'utf8'));
  const got = norm(sql(fs.readFileSync(path.join(here, 'sql/wallet_shape.sql'), 'utf8')));
  const diff = [...want.filter((l) => !got.includes(l)).map((l) => '- ' + l), ...got.filter((l) => !want.includes(l)).map((l) => '+ ' + l)];
  check('WALLET.local_schema_matches_main', diff.length === 0, diff.length ? diff.slice(0, 4).join(' || ').slice(0, 400) : `${want.length} lines`);
});

await scenario('ENTITLEMENT', async () => {
  const [ea, eb, ep] = await Promise.all(['RA', 'RB', 'P'].map((u) => call(u, 'GET', '/api/v1/app/me/entitlement')));
  const credit = ea.json?.credits ?? [];
  check('ENTITLEMENT.D7_02_A_non_premium_with_credits', ea.status === 200 && ea.json.isPremium === false && ea.json.status === 'sin_suscripcion'
    && credit.length === 1 && credit[0].tipoExamen === 'razonado' && credit[0].restantes === 3, `${ea.status} ${JSON.stringify(ea.json)?.slice(0, 160)}`);
  check('ENTITLEMENT.D7_02_B_empty', eb.status === 200 && eb.json.isPremium === false && eb.json.credits.length === 0, `${eb.status}`);
  check('ENTITLEMENT.D7_02_P_premium_subscription', ep.status === 200 && ep.json.isPremium === true && ep.json.status === 'activa'
    && ep.json.planCodigo === 'premium' && ep.json.planNivel === 1, `${ep.status} ${JSON.stringify(ep.json)?.slice(0, 160)}`);
  const pd = await call('P', 'GET', `/api/v1/app/examenes/${EX.PREMIUM}`);
  const ad = await call('RA', 'GET', `/api/v1/app/examenes/${EX.PREMIUM}`);
  check('ENTITLEMENT.D2_02_premium_exam_SUBSCRIPTION_for_P_LOCKED_for_A',
    pd.status === 200 && JSON.stringify(pd.json).includes('SUBSCRIPTION') && ad.status === 200 && !JSON.stringify(ad.json).includes('SUBSCRIPTION'),
    `P=${pd.status} A=${ad.status}`);
});

// ---------- DB CALLS / LATENCY (EX12 / EX13) from /metrics ----------
resetAttempts();
await scenario('METRICS', async () => {
  const before = await scrape();
  const s = await start('A', EX.GRANDE); const a = s.json.intento.id;
  await call('A', 'GET', `/api/v1/app/intentos/${a}`);
  for (let c = null, n = 0; n < 10; n++) {
    const page = await call('A', 'GET', `/api/v1/app/intentos/${a}/preguntas?limit=20${c ? `&cursor=${c}` : ''}`);
    c = page.json.nextCursor; if (!c) break;
  }
  for (let n = 1; n <= 50; n++) await answer('A', a, q(6, n), o(6, n, (n % 4) + 1));
  await call('A', 'POST', `/api/v1/app/intentos/${a}/finalizar`, {}, key());
  await call('A', 'GET', `/api/v1/app/intentos/${a}/resultado`);
  for (let c = null, n = 0; n < 5; n++) {
    const page = await call('A', 'GET', `/api/v1/app/intentos/${a}/revision?limit=50${c ? `&cursor=${c}` : ''}`);
    c = page.json.nextCursor; if (!c) break;
  }
  const after = await scrape();
  const report = {};
  for (const op of ['start', 'get', 'question_page', 'answer', 'finish', 'result', 'review', 'resource_access']) {
    const d = (name) => (after[name]?.[op] ?? 0) - (before[name]?.[op] ?? 0);
    const count = d('exam_db_calls_count');
    report[op] = count ? { requests: count, dbCallsPerRequest: +(d('exam_db_calls_sum') / count).toFixed(2), maxDbCalls: after.exam_db_calls_max?.[op],
      avgLatencyMs: +((d('exam_operation_seconds_sum') / Math.max(1, d('exam_operation_seconds_count'))) * 1000).toFixed(1) } : null;
  }
  fs.writeFileSync(path.join(local, 'metrics-report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
  check('METRICS.db_calls_per_answer_le_4_total_1_business', report.answer && report.answer.dbCallsPerRequest <= 4, JSON.stringify(report.answer));
  const text = await (await fetch(`${KTOR}/metrics`, { headers: { Authorization: `Bearer ${ktorEnv.METRICS_SCRAPE_TOKEN}` } })).text();
  const examLines = text.split('\n').filter((l) => l.startsWith('exam_'));
  const leaks = examLines.filter((l) => /[0-9a-f]{8}-[0-9a-f]{4}-|Enunciado|Opcion|eyJ|@exam\.local/.test(l));
  check('METRICS.no_pii_in_exam_metrics', examLines.length > 0 && leaks.length === 0, `lines=${examLines.length} leaks=${leaks.length}`);
});

// ---------- E2-P03: un examen PROGRAMADO (sql/93, publicado_en +7 dias) no sale por ninguna puerta antes de su hora ----------
await scenario('SCHEDULED', async () => {
  const SCHED = 'e0000000-0000-4000-8000-000000000009';
  const anon = async (p) => { const r = await fetch(KTOR + p); return { status: r.status, text: await r.text() }; };
  const [list, detail, questions] = await Promise.all([anon('/v1/examenes'), anon(`/v1/examenes/${SCHED}`), anon(`/v1/examenes/${SCHED}/preguntas`)]);
  check('SCHEDULED.v1_public_routes_hide_it', list.status === 200 && !list.text.includes(SCHED) && detail.status === 404 && questions.status === 404,
    `list=${list.status}/${list.text.includes(SCHED)} detail=${detail.status} preguntas=${questions.status}`);
  const d202 = await call('A', 'GET', `/api/v1/app/examenes/${SCHED}`);
  check('SCHEDULED.d2_02_not_found', d202.status === 404, `${d202.status} ${d202.json?.code}`);
  // PostgREST directo como usuario autenticado XA (HS256 con el secreto local, el unico que acepta PostgREST). Como anon la
  // lectura ya falla en MAIN y en local: las politicas llaman a es_admin() y anon no tiene EXECUTE sobre ella (42501).
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const nowS = Math.floor(Date.now() / 1000);
  const unsigned = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: XA, role: 'authenticated', aud: 'authenticated', iat: nowS, exp: nowS + 300 })}`;
  const userJwt = `${unsigned}.${crypto.createHmac('sha256', secrets.jwtSecret).update(unsigned).digest('base64url')}`;
  const rest = async (p, jwt = userJwt) => (await (await fetch(`http://localhost:54321/rest/v1/${p}`, {
    headers: { apikey: ktorEnv.SUPABASE_ANON_KEY, Authorization: `Bearer ${jwt}` } })).text());
  const rows = await Promise.all([rest(`examenes?select=id&id=eq.${SCHED}`), rest('preguntas?select=id&id=eq.f9000000-0000-4000-8000-000000000001'),
    rest(`preguntas_examen?select=pregunta_id&examen_id=eq.${SCHED}`), rest('opciones_pregunta?select=id&pregunta_id=eq.f9000000-0000-4000-8000-000000000001')]);
  check('SCHEDULED.postgrest_user_rls_hides_it', rows.every((t) => t.trim() === '[]'), rows.join(' '));
  const pub = await rest(`examenes?select=id&id=eq.${EX.NORMAL}`);
  check('SCHEDULED.postgrest_user_still_reads_published_free', pub.includes(EX.NORMAL), pub);
});

// ---------- E2-P02 / FA19: limites de lectura por usuario. LO ULTIMO: agota los cubos de B ----------
await scenario('RATE', async () => {
  // Home V1 legitima: D2-13 EN_CURSO + D2-13 ADQUIRIDOS + D6-01 en paralelo, 5 cargas seguidas -> nunca 429.
  const homes = [];
  for (let i = 0; i < 5; i++) {
    homes.push(...await Promise.all([call('A', 'GET', '/api/v1/app/me/examenes?vista=EN_CURSO'), call('A', 'GET', '/api/v1/app/me/examenes?vista=ADQUIRIDOS'),
      call('A', 'GET', '/api/v1/app/wallet')]));
  }
  check('RATE.home_v1_parallel_never_429', homes.every((r) => r.status === 200), homes.map((r) => r.status).join(','));
  const burst = await Promise.all(Array.from({ length: 150 }, () => call('B', 'GET', '/api/v1/app/examenes?limit=1')));
  const limited = burst.filter((r) => r.status === 429);
  check('RATE.catalog_burst_150_gets_429', limited.length >= 30 && limited.every((r) => r.json?.code === 'RATE_LIMITED' && r.headers.get('retry-after'))
    && burst.filter((r) => r.status === 200).length <= 120, `200=${burst.filter((r) => r.status === 200).length} 429=${limited.length}`);
  const ent = await call('B', 'GET', '/api/v1/app/me/entitlement');
  check('RATE.catalog_bucket_shared_with_d7_02', ent.status === 429, `${ent.status}`);
  const wburst = await Promise.all(Array.from({ length: 80 }, () => call('B', 'GET', '/api/v1/app/wallet')));
  const wl = wburst.filter((r) => r.status === 429).length;
  check('RATE.wallet_burst_80_gets_429', wl >= 20 && wburst.filter((r) => r.status === 200).length <= 60, `200=${wburst.filter((r) => r.status === 200).length} 429=${wl}`);
});

async function scrape() {
  const text = await (await fetch(`${KTOR}/metrics`, { headers: { Authorization: `Bearer ${ktorEnv.METRICS_SCRAPE_TOKEN}` } })).text();
  const out = {};
  for (const line of text.split('\n')) {
    const m = line.match(/^(exam_db_calls_(?:count|sum|max)|exam_operation_seconds_(?:count|sum))\{([^}]*)\} ([0-9.eE+-]+)$/);
    if (!m) continue;
    const op = /op="([a-z_]+)"/.exec(m[2])?.[1];
    if (!op) continue;
    out[m[1]] ??= {};
    out[m[1]][op] = (m[1].endsWith('_max') ? Math.max(out[m[1]][op] ?? 0, +m[3]) : (out[m[1]][op] ?? 0) + +m[3]);
  }
  return out;
}

resetAttempts();
const failed = results.filter((r) => r.status === 'FAIL');
fs.writeFileSync(path.join(local, 'e2e-result.json'), JSON.stringify({ at: new Date().toISOString(), pass: results.length - failed.length, fail: failed.length, results }, null, 2));
console.log(`\nE2E: ${results.length - failed.length} PASS, ${failed.length} FAIL`);
process.exit(failed.length ? 1 : 0);
