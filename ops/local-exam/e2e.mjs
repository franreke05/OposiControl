#!/usr/bin/env node
// EX15/EX16/EX11/EX12: E2E del motor de examen contra el stack LOCAL (up.mjs + run-ktor.mjs). Nunca contra MAIN.
//   node ops/local-exam/e2e.mjs        -> imprime PASS/FAIL por caso y escribe .local/e2e-result.json
// Cada caso parte de un intento NUEVO: se cierran (abandonan) los abiertos de los usuarios de prueba antes de empezar.
import { ADMIN_KTOR_URL, GATEWAY as GATEWAY_URL, GOTRUE_STUB_URL, KTOR_URL, LOCAL_DIR, composeArgs } from './stack-config.mjs';
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireStackLock } from './stack-lock.mjs';
import { trackHarnessExams } from './harness-marker.mjs'; trackHarnessExams('e2e');

// Escritura en el stack compartido: falla (exit 3) si STACK_LOCK es de otro. Ver stack-lock.mjs.
requireStackLock('e2e.mjs');

const here = path.dirname(fileURLToPath(import.meta.url));
const local = LOCAL_DIR;
const KTOR = KTOR_URL;
// V1: segundo Ktor con el perfil `control` (node ops/local-exam/run-ktor.mjs --admin) para la superficie de administracion.
const ADMIN_KTOR = ADMIN_KTOR_URL;
// E5-B03: el e2e usa SUS PROPIOS usuarios para todo lo que escribe (intentos, cubos de limite): XA/XB (sql/94). A, B y P son
// de los recorridos de la app: solo se LEEN (cartera, derechos, premium) y nunca se tocan sus intentos.
const rawTokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
if (!rawTokens.XA || !rawTokens.XB) { console.error('tokens.json sin XA/XB: ejecuta up.mjs'); process.exit(2); }
const tokens = { A: rawTokens.XA, B: rawTokens.XB, RA: rawTokens.A, RB: rawTokens.B, P: rawTokens.P, Q1: rawTokens.Q1, Q2: rawTokens.Q2, Q2Z: rawTokens.Q2Z, Q3: rawTokens.Q3, Q4: rawTokens.Q4, ADMIN: rawTokens.ADMIN };
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

const sql = (text) => execFileSync('docker', [...composeArgs(), 'exec', '-T', 'db', 'psql', '-U', 'postgres', '-h', 'localhost', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();

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
let stubStartedHere = false, stubStartError = '';
function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
// A prerequisite that could not be met: never a PASS. Counted apart; the run exits 5 (not 0) when nothing FAILED but something was NOT_RUN.
function notRun(id, detail) { results.push({ id, status: 'NOT_RUN', detail }); console.log(`NOT_RUN  ${id}  ${detail}`); }
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
  const GW = GATEWAY_URL;
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
  // V1 (J6): /auth/v1/token and /signup are served by the LOCAL gotrue-stub (or 502 when it is not running); the admin routes stay unavailable.
  // What must hold: PUT user is still 405, no route answers 2xx without valid credentials, and /admin/* is not served.
  check('AUTH_STUB.no_session_without_credentials', others[0].status === 405 && others[1].status >= 400 && others[2].status >= 400 && [404, 502].includes(others[3].status), others.map((r) => r.status).join(','));
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
  const direct = await fetch(`${GATEWAY_URL}/storage/v1/object/recursos-pregunta/preguntas/${q(3, 1)}/${RES.IMG}.png`);
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
  const slowObj = await fetch(`${GATEWAY_URL}/storage/v1/object/authenticated/recursos-pregunta/preguntas/f8000000-0000-4000-8000-000000000001/${slow?.id}.wav`,
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
  check('WALLET.D6_02_A_movements_several_categories', ma.status === 200 && ma.json.items.length === 5 && tipos.size === 5 && ma.json.items[0].saldoResultante === 35 && !ma.json.items.some((m) => m.referencia?.tipo === 'legacy_opening')
    && ma.json.items.every((m, i, all) => i === 0 || m.saldoResultante === all[i - 1].saldoAnterior), `${ma.status} tipos=${[...tipos].join(',')}`);
  check('WALLET.D6_01_A_consumable_active', (wa.json?.consumibles ?? []).some((c) => c.servicioCodigo === 'potenciador'), JSON.stringify(wa.json?.consumibles ?? null).slice(0, 120));
  check('WALLET.D6_01_B_empty', wb.status === 200 && wb.json.saldo === 0 && wb.json.recientes.length === 0 && wb.json.consumibles.length === 0, `${wb.status}`);
  check('WALLET.D6_02_B_empty', mb.status === 200 && mb.json.items.length === 0, `${mb.status}`);
  check('WALLET.own_only', !(mb.text.includes('7d000000') || wb.text.includes('7d000000')), 'B never sees A ledger ids');
  // E2-B02: compras_usuario (captura de MAIN) presente; D7-05 kind=purchase solo lee compras (suscripciones no esta en el stack local).
  const [pa, pb] = await Promise.all(['RA', 'RB'].map((u) => call(u, 'GET', '/api/v1/app/me/billing-history?kind=purchase')));
  check('WALLET.D7_05_purchases_own_only', pa.status === 200 && pa.json.items?.length === 1 && pa.json.items[0].id === '7d100000-0000-4000-8000-000000000001'
    && pa.json.items[0].estado === 'pagada' && pb.status === 200 && pb.json.items.length === 0, `A=${pa.status}/${pa.json?.items?.length} B=${pb.status}/${pb.json?.items?.length}`);
  const fk = sql("select count(*) from pg_constraint where conname in ('transacciones_cartera_compra_id_fkey', 'compras_usuario_usuario_id_fkey', 'transacciones_cartera_usuario_idempotency_key', 'transacciones_cartera_usuario_seq_key')");
  check('WALLET.ledger_schema_matches_main_capture', fk === '4', `constraints=${fk}`);
  // V1: reconciliation is clean on the live local stack (ledger == snapshot, seq 1..n per wallet, chain closed)
  const rec = sql('select count(*) from public.app_wallet_reconcile(null)');
  check('WALLET.reconcile_is_clean', rec === '0', `rows=${rec}`);
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

// ---------- V1 J6 (server half): REGISTER -> VERIFY E-MAIL -> LOGIN -> SPECIALTY -> /me, through the LOCAL GoTrue stub ----------
await scenario('REGISTER_J6', async () => {
  const MAIL = 'j6@exam.local', PASS = 'Local-Pass-0123456789';
  const ING = 'e1000000-0000-4000-8000-000000000002';
  const stub = GOTRUE_STUB_URL;
  const stubUp = () => fetch(stub + '/__local/health').then((r) => r.ok).catch(() => false);
  if (!(await stubUp()) && process.env.E2E_NO_STUB_START !== '1') {
    // start the LOCAL stub with the same guards as by hand (STACK_LOCK holder and APP_ENV=local|test are inherited, never forced); stopped again at the end
    try {
      execFileSync(process.execPath, [path.join(here, 'run-gotrue-stub.mjs'), '--detach'], { env: process.env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
      stubStartedHere = true;
    } catch (e) { stubStartError = String(e.stderr || e.message).trim().split(/\r?\n/).pop(); }
  }
  if (!(await stubUp())) {
    const why = stubStartError ? ` (auto-start failed: ${stubStartError})` : process.env.E2E_NO_STUB_START === '1' ? ' (E2E_NO_STUB_START=1)' : '';
    notRun('REGISTER_J6', `GoTrue stub not reachable on ${stub}${why}; start it: STACK_HOLDER=<holder> APP_ENV=local node ops/local-exam/run-gotrue-stub.mjs --detach`);
    return;
  }
  check('REGISTER_J6.gotrue_stub_running', true, stubStartedHere ? 'started by e2e' : 'already running');
  sql(`delete from auth.users where lower(email) = '${MAIL}'`); // el usuario del e2e; el perfil cae en cascada
  const post = (p, body, token) => fetch(KTOR + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) })
    .then(async (r) => ({ status: r.status, json: await r.json().catch(() => null) }));
  const reg = await post('/v1/auth/register', { email: MAIL, password: PASS });
  check('REGISTER_J6.register_202', reg.status === 202 || reg.status === 200, `${reg.status}`);
  const dup = await post('/v1/auth/register', { email: MAIL, password: PASS });
  check('REGISTER_J6.duplicate_is_indistinguishable', dup.status === reg.status, `${dup.status}`);
  // E2-V1-S02: a control character in the e-mail is a 400 (never a 202 with a mail, never a 503)
  const ctl = await Promise.all(['a\u0007b@exam.local', '\u0000@exam.local'].map((email) => post('/v1/auth/register', { email, password: PASS })));
  check('REGISTER_J6.control_characters_in_email_are_a_400', ctl.every((r) => r.status === 400), ctl.map((r) => r.status).join(','));
  const early = await post('/v1/auth/login', { email: MAIL, password: PASS });
  check('REGISTER_J6.login_before_verification_refused', early.status >= 400 && !early.json?.accessToken, `${early.status}`);
  const mail = await fetch(stub + '/__local/mailbox?email=' + encodeURIComponent(MAIL)).then((r) => r.json());
  const bad = await post('/v1/auth/email/exchange', { code: '0'.repeat(32) });
  check('REGISTER_J6.wrong_code_refused', bad.status >= 400 && !bad.json?.accessToken, `${bad.status}`);
  const ex = await post('/v1/auth/email/exchange', { code: mail.code });
  const token = ex.json?.accessToken;
  check('REGISTER_J6.verify_email_gives_a_session', ex.status === 200 && !!token && ex.json.user?.email === MAIL, `${ex.status}`);
  const replay = await post('/v1/auth/email/exchange', { code: mail.code });
  check('REGISTER_J6.code_is_single_use', replay.status >= 400, `${replay.status}`);
  // The new user has a REAL wallet row (balance 0, no movement) created with the profile, and D6-01 answers a real 0.
  const walletRow = sql(`select count(*) || ':' || coalesce(max(c.saldo_tickets), -1) from public.carteras_usuario c join auth.users u on u.id = c.usuario_id where lower(u.email) = '${MAIL}'`);
  const walletNew = await fetch(KTOR + '/api/v1/app/wallet', { headers: { Authorization: `Bearer ${token}` } });
  const walletNewBody = await walletNew.json().catch(() => null);
  check('REGISTER_J6.new_user_has_a_real_wallet_row_with_balance_0', walletRow === '1:0' && walletNew.status === 200 && walletNewBody?.saldo === 0 && walletNewBody?.recientes?.length === 0, `row=${walletRow} d6_01=${walletNew.status}/${walletNewBody?.saldo}`);
  const prof = await fetch(KTOR + '/api/v1/app/me', { headers: { Authorization: `Bearer ${token}` } }).then((r) => r.json());
  check('REGISTER_J6.profile_row_created_like_production', !!prof?.profile && prof.profile.especialidadPrincipalId == null, JSON.stringify(prof?.profile ?? null).slice(0, 120));
  const save = await post('/v1/auth/register/profile', { firstName: 'Jay', lastName: 'Seis', username: 'j6_local', especialidadId: ING }, token);
  check('REGISTER_J6.register_profile_with_especialidadId', save.status === 204, `${save.status} ${JSON.stringify(save.json)?.slice(0, 100)}`);
  const after = await fetch(KTOR + '/api/v1/app/me', { headers: { Authorization: `Bearer ${token}` } }).then((r) => r.json());
  check('REGISTER_J6.get_me_returns_the_specialty', after.profile?.especialidadPrincipalId === ING && after.profile?.onboardingCompleted === true, `${after.profile?.especialidadPrincipalId}`);
  const login = await post('/v1/auth/login', { email: MAIL, password: PASS });
  check('REGISTER_J6.login_after_verification', login.status === 200 && !!login.json?.accessToken, `${login.status}`);
  const wrongPass = await post('/v1/auth/login', { email: MAIL, password: PASS + 'x' });
  check('REGISTER_J6.wrong_password_refused', wrongPass.status >= 400 && !wrongPass.json?.accessToken, `${wrongPass.status}`);
  const refreshed = await post('/v1/auth/refresh', { refreshToken: login.json?.refreshToken });
  check('REGISTER_J6.refresh_rotates', refreshed.status === 200 && !!refreshed.json?.accessToken, `${refreshed.status}`);
  const again = await post('/v1/auth/refresh', { refreshToken: login.json?.refreshToken });
  check('REGISTER_J6.old_refresh_token_is_dead', again.status >= 400, `${again.status}`);
  const relogin = await fetch(KTOR + '/api/v1/app/me', { headers: { Authorization: `Bearer ${login.json?.accessToken}` } }).then((r) => r.json());
  check('REGISTER_J6.specialty_survives_relogin', relogin.profile?.especialidadPrincipalId === ING, `${relogin.profile?.especialidadPrincipalId}`);
});

// ---------- V1 STEP 1: SPECIALTY P0 (server half). Usuarios XA/XB; sql/95 (ING activa, OBS inactiva) ----------
await scenario('SPECIALTY', async () => {
  const LOC = 'e5000000-0000-4000-8000-000000000001', ING = 'e1000000-0000-4000-8000-000000000002', OBS = 'e1000000-0000-4000-8000-000000000003';
  const patch = (u, body, headers = {}) => fetch(KTOR + '/api/v1/app/me/profile', { method: 'PATCH', headers: { Authorization: `Bearer ${tokens[u]}`, 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) })
    .then(async (r) => ({ status: r.status, json: await r.json().catch(() => null) }));
  const me = async (u) => (await call(u, 'GET', '/api/v1/app/me')).json?.profile?.especialidadPrincipalId ?? null;
  const d217 = await fetch(KTOR + '/api/v1/public/especialidades').then((r) => r.json());
  const ids = d217.items.map((i) => i.id);
  check('SPECIALTY.d2_17_is_the_canonical_source_active_only', ids.includes(LOC) && ids.includes(ING) && !ids.includes(OBS), `${ids.length} items`);
  const ok = await patch('A', { especialidadId: LOC });
  check('SPECIALTY.patch_valid_then_get_me_returns_it', ok.status === 200 && ok.json?.profile?.especialidadPrincipalId === LOC && (await me('A')) === LOC, `${ok.status}`);
  const bad = { nil: '00000000-0000-0000-0000-000000000000', random: crypto.randomUUID(), inactive: OBS };
  const r422 = await Promise.all(Object.values(bad).map((id) => patch('A', { especialidadId: id })));
  check('SPECIALTY.unknown_or_inactive_id_is_422_not_silent', r422.every((r) => r.status === 422 && r.json?.code === 'UNKNOWN_SPECIALTY'), r422.map((r) => `${r.status}/${r.json?.code}`).join(','));
  const malformed = await Promise.all(['not-a-uuid', '', 'x'.repeat(10000), "' or 1=1 --", '../', '‮', null, 5, [LOC], { id: LOC }].map((v) => patch('A', { especialidadId: v })));
  check('SPECIALTY.malformed_values_are_400_never_written', malformed.every((r) => r.status === 400), malformed.map((r) => r.status).join(','));
  const unknownField = await patch('A', { especialidadId: LOC, usuarioId: '0b000000-0000-4000-8000-00000000000b' });
  check('SPECIALTY.unknown_field_is_400', unknownField.status === 400, `${unknownField.status}`);
  check('SPECIALTY.nothing_written_by_the_rejected_requests', (await me('A')) === LOC, `${await me('A')}`);
  // V1 (owner 2026-10-16): the specialty is SET ONCE; another id is 409 SPECIALTY_ALREADY_SET and nothing changes; the same id is a no-op
  const ch = await patch('A', { especialidadId: ING });
  check('SPECIALTY.change_is_409_specialty_already_set', ch.status === 409 && ch.json?.code === 'SPECIALTY_ALREADY_SET' && (await me('A')) === LOC, `${ch.status}/${ch.json?.code}`);
  const same = await patch('A', { especialidadId: LOC });
  check('SPECIALTY.same_id_is_a_no_op_200', same.status === 200 && (await me('A')) === LOC, `${same.status}`);
  const forged = await patch('B', { especialidadId: LOC }, { 'X-Usuario-Id': '1a000000-0000-4000-8000-00000000001a', 'X-User-Id': '1a000000-0000-4000-8000-00000000001a' });
  check('SPECIALTY.forged_user_headers_are_ignored', forged.status === 200 && (await me('B')) === LOC && (await me('A')) === LOC, `${forged.status}`);
  const stale = await patch('A', { especialidadId: LOC }, { 'If-Match': '"1999-01-01T00:00:00Z"' });
  check('SPECIALTY.stale_if_match_is_412_nothing_written', stale.status === 412 && (await me('A')) === LOC, `${stale.status}`);
  // relogin: un token nuevo del mismo usuario (otra sesion) lee la misma especialidad persistida
  const fresh = await fetch(KTOR + '/api/v1/app/me', { headers: { Authorization: `Bearer ${rawTokens.XA}` } }).then((r) => r.json());
  check('SPECIALTY.persists_across_sessions', fresh.profile?.especialidadPrincipalId === LOC, `${fresh.profile?.especialidadPrincipalId}`);
  // the specialty is an ACCESS GATE: lists are scoped to it, another specialty is empty, an exam of another specialty does not exist for the user
  const mine = await call('A', 'GET', '/api/v1/app/examenes');
  check('SPECIALTY.list_is_scoped_to_the_own_specialty', mine.status === 200 && mine.json.items.length >= 1 && mine.json.items.every((i) => i.especialidadId === LOC || !i.especialidadId), `${mine.json?.items?.length}`);
  const mk = await call('A', 'GET', `/api/v1/app/examenes?especialidadId=${ING}`);
  check('SPECIALTY.other_specialty_filter_is_empty', mk.status === 200 && mk.json.items.length === 0, `${mk.json?.items?.length}`);
  const gateDetail = await call('A', 'GET', '/api/v1/app/examenes/e1000000-0000-4000-8000-0000000000e2');
  const gateStart = await call('A', 'POST', '/api/v1/app/examenes/e1000000-0000-4000-8000-0000000000e2/intentos', {}, key());
  check('SPECIALTY.exam_of_another_specialty_is_404_on_detail_and_start', gateDetail.status === 404 && gateDetail.json?.code === 'EXAM_NOT_FOUND' && gateStart.status === 404, `${gateDetail.status}/${gateStart.status}`);
  const none = await call('A', 'GET', `/api/v1/app/examenes?especialidadId=${OBS}`);
  check('SPECIALTY.own_specialty_exam_still_opens', none.status === 200 && none.json.items.length === 0 && (await call('A', 'GET', `/api/v1/app/examenes/${EX.NORMAL}`)).status === 200, `${none.status}`);
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
  check('SCHEDULED.v1_public_routes_hide_it', list.status === 200 && !list.text.includes(SCHED) && detail.status === 404 && questions.status === 410 /* EP-128 retired: CERT-EXAMS-2026-10-21 B-07 */,
    `list=${list.status}/${list.text.includes(SCHED)} detail=${detail.status} preguntas=${questions.status}`);
  const d202 = await call('A', 'GET', `/api/v1/app/examenes/${SCHED}`);
  check('SCHEDULED.d2_02_not_found', d202.status === 404, `${d202.status} ${d202.json?.code}`);
  // PostgREST directo como usuario autenticado XA (HS256 con el secreto local, el unico que acepta PostgREST). Como anon la
  // lectura ya falla en MAIN y en local: las politicas llaman a es_admin() y anon no tiene EXECUTE sobre ella (42501).
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const nowS = Math.floor(Date.now() / 1000);
  const unsigned = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: XA, role: 'authenticated', aud: 'authenticated', iat: nowS, exp: nowS + 300 })}`;
  const userJwt = `${unsigned}.${crypto.createHmac('sha256', secrets.jwtSecret).update(unsigned).digest('base64url')}`;
  const rest = async (p, jwt = userJwt) => (await (await fetch(`${GATEWAY_URL}/rest/v1/${p}`, {
    headers: { apikey: ktorEnv.SUPABASE_ANON_KEY, Authorization: `Bearer ${jwt}` } })).text());
  const rows = await Promise.all([rest(`examenes?select=id&id=eq.${SCHED}`), rest('preguntas?select=id&id=eq.f9000000-0000-4000-8000-000000000001'),
    rest(`preguntas_examen?select=pregunta_id&examen_id=eq.${SCHED}`), rest('opciones_pregunta?select=id&pregunta_id=eq.f9000000-0000-4000-8000-000000000001')]);
  check('SCHEDULED.postgrest_user_rls_hides_it', rows.every((t) => t.trim() === '[]'), rows.join(' '));
  const pub = await rest(`examenes?select=id&id=eq.${EX.NORMAL}`);
  check('SCHEDULED.postgrest_user_still_reads_published_free', pub.includes(EX.NORMAL), pub);
});

// ---------- E2-P02 / FA20: limites de lectura por usuario. LO ULTIMO: agota los cubos de B ----------
// ---------- V1 ECONOMIA (STEP 3/4): canje, paquetes, pedidos de pago, aviso firmado, reembolso, reconciliacion ----------
await scenario('ECONOMY', async () => {
  const OT = { IMM30: 'e3000000-0000-4000-8000-000000000030', ONE: 'e3000000-0000-4000-8000-000000000001', EXPIRED: 'e3000000-0000-4000-8000-000000000010',
    EXPIRING: 'e3000000-0000-4000-8000-000000000011', ING: 'e3000000-0000-4000-8000-000000000005' };
  const Q = { Q1: '2a000000-0000-4000-8000-00000000002a', Q2: '2b000000-0000-4000-8000-00000000002b', Q2Z: '2c000000-0000-4000-8000-00000000002c',
    Q3: '2d000000-0000-4000-8000-00000000002d', Q4: '2e000000-0000-4000-8000-00000000002e' };
  const redeem = (user, examId, body = {}, k = key()) => call(user, 'POST', '/api/v1/app/wallet/canjes', { examId, ...body }, k);
  const bal = (user) => sql("select saldo_tickets from public.carteras_usuario where usuario_id = '" + Q[user] + "'");
  const ledgerCount = (user, ref) => Number(sql("select count(*) from public.transacciones_cartera where usuario_id = '" + Q[user] + "' and tipo = 'compra_examen' and referencia_id = '" + ref + "' and creado_en >= '" + t0 + "'"));
  const day = 86400000;

  // Repetible: devuelve el escenario al estado de los fixtures (sql/98) antes de empezar. Los saldos se corrigen con apuntes de AJUSTE en el libro
  // (ledger-first: nunca se escribe el saldo), y los derechos de compra de estos examenes se borran y se vuelven a crear los de Q3.
  const target = { Q1: 200, Q2: 29, Q2Z: 0, Q3: 30, Q4: 25 };
  sql("delete from public.contenido_desbloqueado_usuario where motivo = 'compra' and usuario_id in ('" + Object.values(Q).join("','") + "') and examen_id::text like 'e3000000-%'");
  for (const [u, want] of Object.entries(target)) {
    const delta = want - Number(bal(u));
    if (delta !== 0) sql("insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, idempotency_key, metadatos) values ('" + Q[u] + "', 'ajuste', " + delta + ", 'Reinicio de fixture e2e', 'e2e:reset:" + crypto.randomUUID() + "', '{\"op\":\"e2e_reset\"}')");
  }
  sql(fs.readFileSync(path.join(here, 'sql/98_v1_economy_fixtures.sql'), 'utf8'));
  const t0 = sql('select clock_timestamp()');

  // --- Q1: canje correcto, repeticion, misma clave con otro cuerpo ---
  const k1 = key();
  const r1 = await redeem('Q1', OT.IMM30, {}, k1);
  const c1 = r1.json?.canje;
  const spanDays = c1 ? (Date.parse(c1.validUntil) - Date.parse(c1.validFrom)) / day : 0;
  check('ECONOMY.D6_03_redeem_201', r1.status === 201 && c1?.pricePaid === 30 && c1?.saldo === 170 && c1?.currency === 'OPOSITICKETS' && Math.abs(spanDays - 30) < 0.01 && !r1.headers.get('idempotent-replayed'),
    r1.status + ' ' + r1.text.slice(0, 160));
  const r1b = await redeem('Q1', OT.IMM30, {}, k1);
  check('ECONOMY.D6_03_replay_same_key_same_body', r1b.status === 201 && r1b.headers.get('idempotent-replayed') === 'true' && r1b.json?.canje?.transactionId === c1?.transactionId && bal('Q1') === '170'
    && ledgerCount('Q1', OT.IMM30) === 1, r1b.status + ' bal=' + bal('Q1'));
  const r1c = await redeem('Q1', OT.IMM30, { expectedPrice: 30 }, k1);
  check('ECONOMY.D6_03_same_key_different_body_422', r1c.status === 422 && r1c.json?.code === 'IDEMPOTENCY_KEY_REUSED' && bal('Q1') === '170', r1c.status + ' ' + r1c.json?.code);
  const d02 = await call('Q1', 'GET', '/api/v1/app/examenes/' + OT.IMM30);
  check('ECONOMY.D2_02_after_redeem_accessible', d02.status === 200 && d02.json?.examen?.accessState === 'GRANTED' && Math.abs((Date.parse(d02.json.examen.accesoValidoHasta) - Date.now()) / 86400000 - 30) < 0.01, d02.status + ' ' + JSON.stringify(d02.json?.examen ?? null).slice(0, 200));
  const d02Before = await call('Q2', 'GET', '/api/v1/app/examenes/' + OT.IMM30);
  check('ECONOMY.D2_02_price_comes_from_db_and_community_name', d02Before.status === 200 && d02Before.json?.examen?.precio?.tickets === 30 && d02Before.json?.examen?.precio?.diasAcceso === 30
    && d02Before.json?.examen?.comunidadNombre === 'Andaluc\u00eda' && d02Before.json?.examen?.accessState === 'LOCKED',
    d02Before.status + ' ' + JSON.stringify(d02Before.json?.examen ?? null).slice(0, 200));

  // --- 5 peticiones simultaneas con la MISMA clave: un solo debito ---
  const kp = key();
  const par = await Promise.all(Array.from({ length: 5 }, () => redeem('Q1', OT.EXPIRED, {}, kp)));
  const okPar = par.filter((r) => r.status === 201 || r.status === 200);
  check('ECONOMY.D6_03_parallel_same_key_one_debit', ledgerCount('Q1', OT.EXPIRED) === 1 && bal('Q1') === '160' && new Set(okPar.map((r) => r.json?.canje?.transactionId)).size === 1,
    'ok=' + okPar.length + ' codes=' + par.map((r) => r.status + (r.json?.code ? ':' + r.json.code : '')).join(',') + ' bal=' + bal('Q1'));

  // --- guardas: precio esperado, saldo, gratis, sin precio, inexistente, premium ---
  // specialty gate: Q1 holds the LOC specialty, so the priced ING exam does not exist for Q1 (redeem 404, nothing written, detail 404)
  const ing = await redeem('Q1', OT.ING);
  const ingDetail = await call('Q1', 'GET', '/api/v1/app/examenes/' + OT.ING);
  check('ECONOMY.specialty_gate_redeem_and_detail_of_another_specialty_404', ing.status === 404 && ing.json?.code === 'EXAM_NOT_FOUND' && ledgerCount('Q1', OT.ING) === 0 && ingDetail.status === 404 && bal('Q1') === '160', ing.status + ' ' + ingDetail.status);
  const pc = await redeem('Q1', OT.ONE, { expectedPrice: 5 });
  check('ECONOMY.D6_03_price_changed_409', pc.status === 409 && pc.json?.code === 'PRICE_CHANGED' && pc.json?.details?.price === 1 && ledgerCount('Q1', OT.ONE) === 0, pc.status + ' ' + pc.text.slice(0, 120));
  const short = await redeem('Q2', OT.IMM30);
  check('ECONOMY.D6_03_one_OT_short_409', short.status === 409 && short.json?.code === 'INSUFFICIENT_BALANCE' && short.json?.details?.balance === 29 && short.json?.details?.price === 30 && bal('Q2') === '29', short.status + ' ' + short.text.slice(0, 140));
  const zero = await redeem('Q2Z', OT.ONE);
  check('ECONOMY.D6_03_zero_balance_409', zero.status === 409 && zero.json?.code === 'INSUFFICIENT_BALANCE' && zero.json?.details?.balance === 0 && bal('Q2Z') === '0', zero.status + ' ' + zero.text.slice(0, 140));
  const free = await redeem('Q4', EX.NORMAL);
  check('ECONOMY.D6_03_free_exam_is_not_purchasable_422', free.status === 422 && free.json?.code === 'NOT_PURCHASABLE', free.status + ' ' + free.json?.code);
  const unset = await redeem('Q4', EX.PREMIUM);
  check('ECONOMY.D6_03_no_ot_price_422', unset.status === 422 && unset.json?.code === 'NOT_PURCHASABLE', unset.status + ' ' + unset.json?.code);
  const none = await redeem('Q4', 'e3999999-0000-4000-8000-000000000999');
  check('ECONOMY.D6_03_unknown_exam_404', none.status === 404 && none.json?.code === 'EXAM_NOT_FOUND', none.status + ' ' + none.json?.code);
  const forged = await call('Q4', 'POST', '/api/v1/app/wallet/canjes', { examId: OT.ONE, usuarioId: Q.Q2, saldo: 9999, precioPagado: 0 }, key());   // stack-port-check: data value, not a port
  check('ECONOMY.D6_03_forged_fields_400', forged.status === 400 && forged.json?.code === 'VALIDATION' && ledgerCount('Q4', OT.ONE) === 0 && bal('Q2') === '29', forged.status + ' ' + forged.json?.code);
  const noKey = await call('Q4', 'POST', '/api/v1/app/wallet/canjes', { examId: OT.ONE });
  check('ECONOMY.D6_03_key_required_400', noKey.status === 400 && noKey.json?.code === 'IDEMPOTENCY_KEY_REQUIRED', noKey.status + ' ' + noKey.json?.code);
  const prem = await redeem('P', OT.ONE);
  check('ECONOMY.D6_03_premium_subscriber_409', prem.status === 409 && prem.json?.code === 'ALREADY_ACCESSIBLE' && prem.json?.details?.accessState === 'SUBSCRIPTION', prem.status + ' ' + prem.text.slice(0, 140));

  // --- Q3: derecho caducado (nueva ventana desde ahora) y derecho que caduca en 1 h (se EXTIENDE desde su fin) ---
  const ex = await redeem('Q3', OT.EXPIRED);
  const exFrom = ex.json?.canje ? Date.parse(ex.json.canje.validFrom) : 0;
  check('ECONOMY.D6_03_expired_entitlement_new_window', ex.status === 201 && Math.abs(exFrom - Date.now()) < 120000 && Math.abs((Date.parse(ex.json.canje.validUntil) - Date.now()) / day - 30) < 0.01,
    ex.status + ' ' + ex.text.slice(0, 200));
  const ep = await redeem('Q3', OT.EXPIRING);
  const epUntil = ep.json?.canje ? Date.parse(ep.json.canje.validUntil) : 0;
  check('ECONOMY.D6_03_active_entitlement_extends_from_its_end', ep.status === 201 && Math.abs((epUntil - Date.now()) / day - (30 + 1 / 24)) < 0.01, ep.status + ' days=' + ((epUntil - Date.now()) / day));
  check('ECONOMY.D6_03_one_entitlement_row_per_user_exam', sql("select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '" + Q.Q3 + "' and examen_id = '" + OT.EXPIRING + "' and motivo = 'compra'") === '1');

  // --- E2-V1-E03 regression: two paid purchases ALWAYS add up (sequential and parallel), never 30 days for two debits ---
  const base0 = bal('Q3');
  const s1 = await redeem('Q3', OT.ONE);
  const s2 = await redeem('Q3', OT.ONE);
  const seqDays = s2.json?.canje ? (Date.parse(s2.json.canje.validUntil) - Date.now()) / day : 0;
  check('ECONOMY.E03_two_sequential_purchases_give_60_days', s1.status === 201 && s2.status === 201 && Math.abs(seqDays - 60) < 0.01 && ledgerCount('Q3', OT.ONE) === 2, 'days=' + seqDays);
  const par4 = await Promise.all(Array.from({ length: 4 }, () => redeem('Q3', OT.ONE)));
  const okc = par4.filter((r) => r.status === 201).length;
  const finalUntil = sql("select extract(epoch from valido_hasta) from public.contenido_desbloqueado_usuario where usuario_id = '" + Q.Q3 + "' and examen_id = '" + OT.ONE + "' and motivo = 'compra'");
  const parDays = (Number(finalUntil) * 1000 - Date.now()) / day;
  check('ECONOMY.E03_parallel_purchases_add_up_one_30_day_block_per_debit', okc === 4 && ledgerCount('Q3', OT.ONE) === 6 && Math.abs(parDays - 180) < 0.05 && Number(bal('Q3')) === Number(base0) - 6, 'ok=' + okc + ' debits=' + ledgerCount('Q3', OT.ONE) + ' days=' + parDays);
  // E2-V1-E04: a JSON string is not a number
  const strBody = await fetch(KTOR + '/api/v1/app/wallet/canjes', { method: 'POST', headers: { Authorization: 'Bearer ' + tokens.Q4, 'Content-Type': 'application/json', 'Idempotency-Key': key() }, body: '{"examId":"' + OT.ONE + '","expectedPrice":"1"}' });
  check('ECONOMY.E04_string_expectedPrice_is_400', strBody.status === 400, String(strBody.status));

  // --- D6-11 paquetes ---
  const packs = await call('Q4', 'GET', '/api/v1/app/wallet/paquetes');
  const codes = (packs.json?.items ?? []).map((p) => p.code);
  // every visible pack_tickets fixture, in the server order (posicion asc, codigo asc): sql/98 (LOCAL_PACK_10 pos 1, LOCAL_PACK_50 pos 2)
  // + sql/98b (LOCAL_RECARGA_100..1000 pos 20..23, LOCAL_PACK_1300 pos 24). The old 2-pack expectation predates sql/98b (E5 live run 2026-10-19).
  const PACKS = 'LOCAL_PACK_10,LOCAL_PACK_50,LOCAL_RECARGA_100,LOCAL_RECARGA_200,LOCAL_RECARGA_500,LOCAL_RECARGA_1000,LOCAL_PACK_1300';
  check('ECONOMY.D6_11_packs', packs.status === 200 && codes.join(',') === PACKS && packs.json.items[1].tickets === 50 && packs.json.items[1].priceCents === 449 && packs.json.items[1].currency === 'EUR',
    packs.status + ' ' + codes.join(','));

  // --- pagos: pedido, aviso firmado, repeticion, importes, caducidad, reembolso ---
  const secret = ktorEnv.PAYMENTS_SANDBOX_SECRET;
  const sign = (raw, ts) => crypto.createHmac('sha256', secret).update(ts + '.' + raw).digest('hex');
  const hook = async (event, over = {}) => {
    const raw = JSON.stringify(event);
    const ts = over.ts ?? Math.floor(Date.now() / 1000);
    return fetch(KTOR + '/api/v1/webhooks/payments/' + (over.provider ?? 'sandbox'), { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Sandbox-Timestamp': String(ts), 'X-Sandbox-Signature': over.sig ?? sign(raw, ts) }, body: over.body ?? raw })
      .then(async (r) => { const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* vacio */ } return { status: r.status, json, text }; });
  };
  const newOrder = (k = key(), productCode = 'LOCAL_PACK_50') => call('Q4', 'POST', '/api/v1/app/payments/orders', { productCode, provider: 'sandbox' }, k);
  const evt = (order, over = {}) => ({ eventId: 'evt-' + crypto.randomUUID(), type: 'payment.succeeded', orderId: order.id, transactionId: 'tx-' + crypto.randomUUID(), amountCents: order.amountCents, currency: order.currency, productCode: order.productCode, ...over });
  const events = () => Number(sql('select count(*) from public.eventos_pago'));
  const credits = () => Number(sql("select count(*) from public.transacciones_cartera where usuario_id = '" + Q.Q4 + "' and tipo = 'pack_tickets' and creado_en >= '" + t0 + "'"));

  const ko = key();
  const o1 = await newOrder(ko);
  const ord = o1.json?.order;
  check('ECONOMY.D6_12_order_created', o1.status === 201 && ord?.status === 'PENDING' && ord?.tickets === 50 && ord?.amountCents === 449 && o1.headers.get('location') === '/api/v1/app/payments/orders/' + ord?.id && bal('Q4') === '25',
    o1.status + ' ' + o1.text.slice(0, 200));
  const o1b = await newOrder(ko);
  check('ECONOMY.D6_12_order_replay_same_key', (o1b.status === 201 || o1b.status === 200) && o1b.headers.get('idempotent-replayed') === 'true' && o1b.json?.order?.id === ord?.id, o1b.status + ' ' + o1b.json?.order?.id);
  const forgedOrder = await call('Q2Z', 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_50', provider: 'sandbox', amountCents: 1, tickets: 9999 }, key());   // stack-port-check: data value, not a port
  check('ECONOMY.D6_12_client_amounts_rejected', forgedOrder.status === 400 && forgedOrder.json?.code === 'VALIDATION', forgedOrder.status + ' ' + forgedOrder.json?.code);
  const tampered = await call('Q2Z', 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_UNKNOWN', provider: 'sandbox' }, key());
  check('ECONOMY.D6_12_unknown_pack_422', tampered.status === 422 && tampered.json?.code === 'PRODUCT_NOT_AVAILABLE', tampered.status + ' ' + tampered.json?.code);
  const badProv = await call('Q2Z', 'POST', '/api/v1/app/payments/orders', { productCode: 'LOCAL_PACK_50', provider: 'stripe' }, key());
  check('ECONOMY.D6_12_unknown_provider_422', badProv.status === 422 && badProv.json?.code === 'PAYMENT_PROVIDER_UNAVAILABLE', badProv.status + ' ' + badProv.json?.code);
  const foreignRead = await call('Q1', 'GET', '/api/v1/app/payments/orders/' + ord.id);
  check('ECONOMY.D6_13_other_user_order_404', foreignRead.status === 404 && foreignRead.json?.code === 'ORDER_NOT_FOUND', foreignRead.status + ' ' + foreignRead.json?.code);

  // aviso con firma mala / vieja / cuerpo alterado: 401 y CERO escrituras
  const e0 = events();
  const raw0 = JSON.stringify(evt(ord));
  const badSig = await hook(JSON.parse(raw0), { sig: '0'.repeat(64) });
  const stale = await hook(JSON.parse(raw0), { ts: Math.floor(Date.now() / 1000) - 400 });
  const alteredRaw = raw0.replace('"amountCents":449', '"amountCents":1');
  const altered = await hook(JSON.parse(raw0), { body: alteredRaw });
  check('ECONOMY.W01_bad_signature_stale_altered_401_zero_writes', [badSig, stale, altered].every((r) => r.status === 401 && r.json?.code === 'WEBHOOK_SIGNATURE_INVALID') && events() === e0 && bal('Q4') === '25' && credits() === 0,
    [badSig.status, stale.status, altered.status].join(','));
  const noProv = await hook(JSON.parse(raw0), { provider: 'stripe' });
  check('ECONOMY.W01_unknown_provider_404', noProv.status === 404, String(noProv.status));
  const big = await fetch(KTOR + '/api/v1/webhooks/payments/sandbox', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Sandbox-Timestamp': '1', 'X-Sandbox-Signature': '0' }, body: 'x'.repeat(70000) });
  check('ECONOMY.W01_oversized_413', big.status === 413, String(big.status));

  // importe distinto: no acredita, pedido a revision
  const wrong = await hook(evt(ord, { amountCents: 1 }));
  check('ECONOMY.W01_wrong_amount_no_credit_in_review', wrong.status === 200 && wrong.json?.result === 'EN_REVISION' && bal('Q4') === '25' && credits() === 0, wrong.status + ' ' + wrong.text);
  const o2 = (await newOrder()).json.order;
  const good = evt(o2);
  const w1 = await hook(good);
  check('ECONOMY.W01_paid_event_credits_once', w1.status === 200 && w1.json?.result === 'CREDITED' && bal('Q4') === '75' && credits() === 1, w1.status + ' ' + w1.text + ' bal=' + bal('Q4'));
  const w2 = await hook(good);
  const w3 = await hook(evt(o2));
  check('ECONOMY.W01_duplicate_callback_and_second_event_do_not_credit_again', w2.json?.result === 'REPLAY' && w3.json?.result === 'REPLAY' && bal('Q4') === '75' && credits() === 1, w2.text + ' ' + w3.text);
  const read2 = await call('Q4', 'GET', '/api/v1/app/payments/orders/' + o2.id);
  check('ECONOMY.D6_13_paid_status_and_balance', read2.status === 200 && read2.json?.order?.status === 'PAID' && read2.json?.order?.balance === 75, read2.status + ' ' + read2.text.slice(0, 160));

  // verify con justificante opaco ATADO a usuario y pedido
  const o3 = (await newOrder()).json.order;
  const receipt = (userId, order, eventId) => {
    const payload = Buffer.from(JSON.stringify({ eventId, orderId: order.id, transactionId: 'tx-' + eventId, amountCents: order.amountCents, currency: order.currency, productCode: order.productCode })).toString('base64url');
    return payload + '.' + crypto.createHmac('sha256', secret).update(userId + '|' + payload).digest('hex');
  };
  const foreignReceipt = await call('Q4', 'POST', '/api/v1/app/payments/orders/' + o3.id + '/verify', { receipt: receipt(Q.Q1, o3, 'rcpt-' + crypto.randomUUID()) });
  check('ECONOMY.D6_14_receipt_of_another_user_422', foreignReceipt.status === 422 && foreignReceipt.json?.code === 'PAYMENT_RECEIPT_INVALID' && bal('Q4') === '75', foreignReceipt.status + ' ' + foreignReceipt.json?.code);
  const rid = 'rcpt-' + crypto.randomUUID();
  const v1 = await call('Q4', 'POST', '/api/v1/app/payments/orders/' + o3.id + '/verify', { receipt: receipt(Q.Q4, o3, rid) });
  const v2 = await call('Q4', 'POST', '/api/v1/app/payments/orders/' + o3.id + '/verify', { receipt: receipt(Q.Q4, o3, rid) });
  check('ECONOMY.D6_14_verify_credits_once_and_retry_is_safe', v1.status === 200 && v1.json?.order?.status === 'PAID' && v2.status === 200 && v2.json?.order?.status === 'PAID' && bal('Q4') === '125' && credits() === 2,
    v1.status + ' ' + v2.status + ' bal=' + bal('Q4'));

  // pedido caducado (30 min, reloj de la base): una confirmacion autentica TARDIA acredita; cancelado/fallido/reembolsado: a revision
  const o4 = (await newOrder()).json.order;
  // expira_en esta congelado por la maquina de estados: para SIMULAR que pasaron 30 minutos el arnes (superusuario, solo local) apaga el trigger un instante.
  sql("begin; alter table public.compras_usuario disable trigger trg_compras_usuario_order_machine; update public.compras_usuario set expira_en = now() - interval '1 minute' where id = '" + o4.id + "'; alter table public.compras_usuario enable trigger trg_compras_usuario_order_machine; commit;");
  const expired = await call('Q4', 'GET', '/api/v1/app/payments/orders/' + o4.id);
  check('ECONOMY.D6_13_pending_order_past_30min_reads_EXPIRED', expired.status === 200 && expired.json?.order?.status === 'EXPIRED' && bal('Q4') === '125', expired.status + ' ' + expired.json?.order?.status);
  const late = await hook(evt(o4));
  check('ECONOMY.W01_late_authentic_payment_for_expired_order_credits', late.status === 200 && late.json?.result === 'CREDITED_LATE' && bal('Q4') === '175', late.status + ' ' + late.text + ' bal=' + bal('Q4'));
  const o5 = (await newOrder()).json.order;
  sql("update public.compras_usuario set estado = 'cancelada' where id = '" + o5.id + "'");
  const paidCancelled = await hook(evt(o5));
  check('ECONOMY.W01_paid_event_for_cancelled_order_goes_to_review_without_credit', paidCancelled.json?.result === 'EN_REVISION' && bal('Q4') === '175', paidCancelled.text);

  // reembolso: se revierte SOLO lo no gastado de ESE credito
  const refund = await hook({ eventId: 'evt-' + crypto.randomUUID(), type: 'payment.refunded', orderId: o2.id, transactionId: 'tx-r', amountCents: o2.amountCents, currency: o2.currency, productCode: o2.productCode });
  check('ECONOMY.W01_refund_reverses_unspent_credit', refund.status === 200 && ['REFUNDED', 'REFUNDED_P2'].includes(refund.json?.result) && Number(bal('Q4')) <= 175, refund.text + ' bal=' + bal('Q4'));
  const refundAgain = await hook({ eventId: 'evt-' + crypto.randomUUID(), type: 'payment.refunded', orderId: o2.id, transactionId: 'tx-r', amountCents: o2.amountCents, currency: o2.currency, productCode: o2.productCode });
  check('ECONOMY.W01_second_refund_does_nothing', refundAgain.json?.result === 'REPLAY', refundAgain.text);

  // reconciliacion limpia (libro == snapshot, cadena cerrada, seq 1..n) tras TODO lo anterior
  check('ECONOMY.reconcile_clean_after_economy', sql('select count(*) from public.app_wallet_reconcile(null)') === '0');

  // el secreto del proveedor jamas llega a los registros
  const log = fs.existsSync(path.join(local, 'ktor.log')) ? fs.readFileSync(path.join(local, 'ktor.log'), 'utf8') : '';
  check('ECONOMY.sandbox_secret_never_in_ktor_log', secret.length >= 32 && !log.includes(secret), 'log bytes=' + log.length);
});

// ---------- V1 COMUNIDAD OBLIGATORIA por HTTP real contra la superficie de administracion (Ktor perfil control, JWT de un admin local) ----------
await scenario('ADMIN_COMMUNITY', async () => {
  const admin = async (method, p, body) => {
    const r = await fetch(ADMIN_KTOR + p, { method, headers: { Authorization: 'Bearer ' + tokens.ADMIN, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* vacio */ }
    return { status: r.status, json, text };
  };
  const noAuth = await fetch(ADMIN_KTOR + '/api/v1/admin/examenes', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  check('ADMIN.no_token_401', noAuth.status === 401, String(noAuth.status));
  const appUser = await fetch(ADMIN_KTOR + '/api/v1/admin/examenes', { method: 'POST', headers: { Authorization: 'Bearer ' + tokens.Q1, 'Content-Type': 'application/json' }, body: '{}' });
  check('ADMIN.non_admin_user_403', appUser.status === 403, String(appUser.status));
  const before = Number(sql("select count(*) from public.examenes where codigo_examen like 'E2E-COM-%'"));
  const base = { especialidadId: 'e5000000-0000-4000-8000-000000000001', titulo: 'E2E comunidad', tipoExamen: 'rafaga', tipoAcceso: 'gratuito' };
  const missing = await admin('POST', '/api/v1/admin/examenes', { codigoExamen: 'E2E-COM-1', ...base });
  check('ADMIN.exam_without_community_rejected_422', missing.status === 422 && /exam_community_required/i.test(missing.text), missing.status + ' ' + missing.text.slice(0, 160));
  const empty = await admin('POST', '/api/v1/admin/examenes', { codigoExamen: 'E2E-COM-2', comunidadId: null, ...base });
  check('ADMIN.exam_with_null_community_rejected_422', empty.status === 422 && /exam_community_required/i.test(empty.text), empty.status + ' ' + empty.text.slice(0, 160));
  const unknown = await admin('POST', '/api/v1/admin/examenes', { codigoExamen: 'E2E-COM-3', comunidadId: 'c1999999-0000-4000-8000-000000000999', ...base });
  check('ADMIN.exam_with_unknown_community_rejected_422', unknown.status === 422 && /unknown_community/i.test(unknown.text), unknown.status + ' ' + unknown.text.slice(0, 160));
  check('ADMIN.nothing_written_by_the_rejections', Number(sql("select count(*) from public.examenes where codigo_examen like 'E2E-COM-%'")) === before);
  const ok = await admin('POST', '/api/v1/admin/examenes', { codigoExamen: 'E2E-COM-OK-' + Date.now(), comunidadId: 'c1000000-0000-4000-8000-000000000001', ...base });
  const created = ok.json?.id ?? ok.json?.examen?.id ?? ok.json?.data?.id;
  check('ADMIN.exam_with_active_community_created_201', ok.status === 201 && !!created, ok.status + ' ' + ok.text.slice(0, 160));
  if (created) {
    const clear = await admin('PATCH', '/api/v1/admin/examenes/' + created, { comunidadId: null });
    check('ADMIN.community_cannot_be_cleared_422', clear.status === 422 && /exam_community_required/i.test(clear.text)
      && sql("select comunidad_id is not null from public.examenes where id = '" + created + "'") === 't', clear.status + ' ' + clear.text.slice(0, 160));
    sql("delete from public.examenes where id = '" + created + "'");
  }
  // el trigger de la base es la ultima defensa (aunque alguien se salte Ktor): sin comunidad no se inserta ni se publica
  const direct = (() => { try { sql("insert into public.examenes (codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso) values ('E2E-COM-DB', 'e5000000-0000-4000-8000-000000000001', 'x', 'rafaga', 'gratuito')"); return 'inserted'; } catch (e) { return 'rejected'; } })();
  check('ADMIN.database_rejects_exam_without_community_too', direct === 'rejected');
});

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
if (stubStartedHere) { try { execFileSync(process.execPath, [path.join(here, 'run-gotrue-stub.mjs'), '--stop'], { env: process.env, stdio: 'ignore' }); } catch { /* best effort: leave the stack as found */ } }
const failed = results.filter((r) => r.status === 'FAIL');
const skipped = results.filter((r) => r.status === 'NOT_RUN');
const passed = results.filter((r) => r.status === 'PASS');
fs.writeFileSync(path.join(local, 'e2e-result.json'), JSON.stringify({ at: new Date().toISOString(), pass: passed.length, fail: failed.length, notRun: skipped.length, results }, null, 2));
console.log(`\nE2E: ${passed.length} PASS, ${failed.length} FAIL, ${skipped.length} NOT_RUN${skipped.length ? ' (' + skipped.map((r) => r.id).join(', ') + ')' : ''}`);
process.exit(failed.length ? 1 : skipped.length ? 5 : 0);
