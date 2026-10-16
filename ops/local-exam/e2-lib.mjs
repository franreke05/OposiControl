// E2 shared helpers for the LOCAL attack scripts (e2-v1-access2.mjs, e2-v1-challenge.mjs). Own users and exams only (ids e2g/e2a/e2h, e-mails e2*-<run>-*@e2.local).
// STACK_LOCK hard-fail is applied by the caller (requireStackLock). No secret is printed. Own Ktor from THIS worktree on E2_PORT (default 8091), restarted by the script.
import { execFileSync, spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const here = path.dirname(fileURLToPath(import.meta.url));
export const repo = path.resolve(here, '..', '..');
export const L = process.env.E2_STACK_LOCAL ?? 'C:/Users/franr/AndroidStudioProjects/OposiControl-PhaseB-Server/ops/local-exam/.local';
export const PORT = process.env.E2_PORT ?? '8091';
export const KTOR = `http://127.0.0.1:${PORT}`;
export const secrets = JSON.parse(fs.readFileSync(path.join(L, 'secrets.json'), 'utf8'));
export const LOC = 'e5000000-0000-4000-8000-000000000001';
export const OTH = 'e2b50000-0000-4000-8000-000000000001';
export const run = crypto.randomBytes(3).toString('hex');
export const uuid = () => crypto.randomUUID();
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const sql = (text) => execFileSync('docker', ['exec', '-i', 'oposi-exam-local-db-1', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
export const sqlTry = (text) => { try { return { ok: true, out: sql(text) }; } catch (e) { return { ok: false, out: String(e.stderr ?? e.message) }; } };
const b64u = (b) => Buffer.from(b).toString('base64url');
const jwk = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
export const token = (sub) => { const n = Math.floor(Date.now() / 1000); const h = b64u(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const p = b64u(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: 'http://localhost:54321/auth/v1', iat: n - 60, exp: n + 3000 }));
  return `${h}.${p}.${b64u(crypto.sign('sha256', Buffer.from(`${h}.${p}`), { key: jwk, dsaEncoding: 'ieee-p1363' }))}`; };

export const results = [];
export function check(id, ok, detail = '') { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); }
export function finding(id, ok, detail, fid) { results.push({ id, status: ok ? 'PASS' : `FINDING ${fid}`, detail }); console.log(`${ok ? 'PASS' : 'FINDING ' + fid}  ${id}${detail ? '  ' + detail : ''}`); }
export async function scenario(only, name, fn) { if (only.length && !only.includes(name)) return; try { await fn(); } catch (e) { check(`${name}.exception`, false, String(e.message).slice(0, 300)); } }
export function finish(out) {
  fs.writeFileSync(out, JSON.stringify({ when: new Date().toISOString(), results }, null, 2));
  const failed = results.filter((r) => r.status === 'FAIL').length; const found = results.filter((r) => r.status.startsWith('FINDING')).length;
  console.log(`\n${results.filter((r) => r.status === 'PASS').length} PASS / ${found} FINDING / ${failed} FAIL -> ${out}`);
  process.exit(failed ? 1 : 0);
}

export async function call(u, method, p, body, headers = {}, raw = false) {
  const h = { ...headers }; if (u) h.Authorization = `Bearer ${u.t ?? u}`; if (body !== undefined) h['Content-Type'] = 'application/json';
  const r = await fetch(KTOR + p, { method, headers: h, body: body === undefined ? undefined : (raw ? body : JSON.stringify(body)) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text, headers: r.headers };
}

// ---------- own Ktor ----------
export function killKtor() {
  execFileSync('powershell', ['-NoProfile', '-Command', "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*OposiControl-ExamSecurity*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"], { stdio: 'ignore' });
}
let seq = 0;
export async function startKtor(extraEnv = {}) {
  const logdir = process.env.E2_LOGDIR ?? path.join(process.cwd(), 'e2-logs'); fs.mkdirSync(logdir, { recursive: true });
  const fd = fs.openSync(path.join(logdir, `ktor-${++seq}.log`), 'a');
  spawn(process.execPath, [path.join(here, 'e2-ktor.mjs'), PORT], { cwd: repo, env: { ...process.env, E2_STACK_LOCAL: L, ...extraEnv }, stdio: ['ignore', fd, fd], detached: true, windowsHide: true }).unref();
  for (let i = 0; i < 60; i++) { await sleep(1000); try { if ((await fetch(`${KTOR}/api/v1/public/especialidades`)).status === 200) return; } catch { /* starting */ } }
  throw new Error('Ktor did not start');
}
export async function restartKtor(extraEnv = {}) { killKtor(); await sleep(2000); await startKtor(extraEnv); }

// ---------- own users, exams, questions ----------
export const allUsers = [];
export function mkUser(label, spec = LOC, unverified = false) {
  const id = uuid(); const clean = label.replace(/\W/g, '').slice(0, 10);
  sql(`set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at, confirmed_at) values ('${id}', 'e2x-${run}-${clean}@e2.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now(), ${unverified ? 'null' : 'now()'});
set session_replication_role = origin;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('${id}', 'E2X ${clean}', 'e2x_${run}_${clean}', true, 'activa', ${spec ? `'${spec}'` : 'null'}) on conflict (usuario_id) do nothing;`);
  allUsers.push(id);
  return { id, label, t: token(id) };
}
export const comm = "(select id from public.comunidades_autonomas order by codigo limit 1)";
let exSeq = 0;
/** Creates an exam (+ n questions with 2 options each, option letter A correct) under the replica role. Returns {id, qs:[{id, ok, ko}]}. demoFlags: array of booleans per question. */
export function mkExam({ tipo = 'inmersivo', acceso = 'premium', price = null, n = 3, demoFlags = null, spec = LOC, title }) {
  const eid = `e2a00000-0000-4000-8000-${run}${String(++exSeq).padStart(6, '0')}`;
  const qs = []; let stmt = `set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible) values ('${eid}', 'E2A-${run}-${exSeq}', '${spec}', '${title ?? 'E2A ' + tipo + ' ' + exSeq}', '${tipo}', '${acceso}', true, now() - interval '1 day', ${n}, ${comm}, ${price != null}, ${price}, 30, ${demoFlags ? 'true' : 'false'});\n`;
  for (let i = 1; i <= n; i++) {
    const q = uuid(), ok = uuid(), ko = uuid(); qs.push({ id: q, ok, ko, pos: i });
    stmt += `insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q}', 'E2A-${run}-${exSeq}-${i}', '${tipo === 'inmersivo' ? 'inmersiva' : tipo === 'razonado' ? 'razonada' : tipo}', 'TITULO-E2A-${exSeq}-${i}', true);
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${ok}', '${q}', 'A', 'a', 1), ('${ko}', '${q}', 'B', 'b', 2);
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q}', '${ok}', 'SOLUCION-E2A-${exSeq}-${i}');
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_demo) values ('${eid}', '${q}', ${i}, ${i}, ${demoFlags ? !!demoFlags[i - 1] : false});\n`;
  }
  sql(stmt + 'set session_replication_role = origin;');
  return { id: eid, qs };
}
/** Subscription: state/end/motive of the plan product (local fixtures: local-sub-rafagas | razonados | inmersivo). */
export function subscribe(u, product, { estado = 'activa', days = 30, motivo = null } = {}) {
  sql(`insert into public.suscripciones (usuario_id, proveedor, producto_codigo, estado, inicio_en, fin_en, renovacion_en, identificador_externo, payload_validacion, fin_motivo)
 values ('${u.id}', 'manual', '${product}', '${estado}', now() - interval '1 day', now() + interval '${days} days', ${estado === 'activa' ? `now() + interval '${days} days'` : 'null'}, 'e2-${run}-${u.id.slice(0, 8)}', '{}'::jsonb, ${motivo ? `'${motivo}'` : 'null'});`);
}
export const setSub = (u, set) => sql(`update public.suscripciones set ${set}, actualizado_en = now() where usuario_id = '${u.id}';`);
export const balance = (u) => Number(sql(`select coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '${u.id}'), 0);`));
export const grant = (u, n) => sql(`select transaction_id from public.admin_grant_tickets('${u.id}', ${n}, 'E2 seed', 'e2-seed-${uuid()}', 'ajuste');`);
export const ledgerRows = (u, tipo) => Number(sql(`select count(*) from public.transacciones_cartera where usuario_id = '${u.id}'${tipo ? ` and tipo = '${tipo}'` : ''};`));

// ---------- API shortcuts ----------
export const key = () => uuid();
export const start = (u, exam, body = {}, headers = {}) => call(u, 'POST', `/api/v1/app/examenes/${exam}/intentos`, body, { 'Idempotency-Key': key(), ...headers });
export const page = (u, attempt, qs = '') => call(u, 'GET', `/api/v1/app/intentos/${attempt}/preguntas${qs}`);
export const answer = (u, attempt, q, opt, base = 0) => call(u, 'PUT', `/api/v1/app/intentos/${attempt}/respuestas/${q}`, { opcionSeleccionadaId: opt, tiempoRespuestaMs: 1500, baseRevision: base }, { 'Idempotency-Key': key() });
export const fin = (u, attempt) => call(u, 'POST', `/api/v1/app/intentos/${attempt}/finalizar`, {}, { 'Idempotency-Key': key() });
export const detail = (u, exam) => call(u, 'GET', `/api/v1/app/examenes/${exam}`);
export const state = async (u, exam) => (await detail(u, exam)).json?.examen?.accessState;
export const attemptId = (r) => r.json?.intento?.id ?? r.json?.id ?? r.json?.attempt?.id;
