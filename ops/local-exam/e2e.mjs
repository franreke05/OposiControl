#!/usr/bin/env node
// EX15/EX16/EX11/EX12: E2E del motor de examen contra el stack LOCAL (up.mjs + run-ktor.mjs). Nunca contra MAIN.
//   node ops/local-exam/e2e.mjs        -> imprime PASS/FAIL por caso y escribe .local/e2e-result.json
// Cada caso parte de un intento NUEVO: se cierran (abandonan) los abiertos de los usuarios de prueba antes de empezar.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const local = path.join(here, '.local');
const KTOR = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const tokens = JSON.parse(fs.readFileSync(path.join(local, 'tokens.json'), 'utf8'));
const secrets = JSON.parse(fs.readFileSync(path.join(local, 'secrets.json'), 'utf8'));
const ktorEnv = Object.fromEntries(fs.readFileSync(path.join(local, 'ktor.env'), 'utf8').split(/\r?\n/).filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));

const EX = { NORMAL: 'e0000000-0000-4000-8000-000000000001', RAZONADA: 'e0000000-0000-4000-8000-000000000002', IMAGEN: 'e0000000-0000-4000-8000-000000000003',
  AUDIO: 'e0000000-0000-4000-8000-000000000004', PREMIUM: 'e0000000-0000-4000-8000-000000000005', GRANDE: 'e0000000-0000-4000-8000-000000000006' };
const q = (e, n) => `f${e}000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const o = (e, n, l) => `0${e}000000-0000-4000-8${l}00-${String(n).padStart(12, '0')}`;
const RES = { IMG: '5e000000-0000-4000-8000-000000000001', AUD: '5e000000-0000-4000-8000-000000000002', PDF: '5e000000-0000-4000-8000-000000000003', ABSENT: '5e000000-0000-4000-8000-000000000004' };

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
  sql(`update public.intentos_examen set estado = 'abandonado' where estado = 'en_progreso' and usuario_id in ('0a000000-0000-4000-8000-00000000000a','0b000000-0000-4000-8000-00000000000b');`);
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
  check('SECURITY.idempotent_replay_no_duplicate', first.status === 200 && replay.status === 200 && replay.headers.get('idempotent-replayed') === 'true' && rows === '1' && canon(replay.json) === canon(first.json), `rows=${rows} (same body, keys reordered by the jsonb store)`);
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
  check('RESOURCES.audio_signed_bytes', audAcc.status === 200 && audBytes?.status === 200, `${audAcc.status} ${audBytes?.status}`);
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
});

// ---------- CONCURRENCY (E2-I01 items 2, 3, 5, 4) ----------
resetAttempts();
await scenario('CONCURRENCY', async () => {
  const starts = await Promise.all(Array.from({ length: 20 }, () => start('A', EX.RAZONADA)));
  const ids = new Set(starts.map((r) => r.json?.intento?.id));
  const created = starts.filter((r) => r.status === 201).length;
  const open = sql(`select count(*) from public.intentos_examen where usuario_id = '0a000000-0000-4000-8000-00000000000a' and examen_id = '${EX.RAZONADA}' and estado = 'en_progreso'`);
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
