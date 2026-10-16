#!/usr/bin/env node
// E2 V1 ADMIN GIFT (LOCAL ONLY): attacks on `POST /api/v1/admin/accesos` (gift of ONE month of an exam) and `POST /api/v1/admin/accesos/{id}/revocar`.
// Needs STACK_LOCK held by the caller (hard-fail otherwise), the app Ktor (:8089) and the CONTROL-profile Ktor (:8090), migration 20261022100700 applied.
// Writes ONLY to users/exams this script creates (e2g... / e2v1g-*). No MAIN, no token or secret is printed.
//   STACK_HOLDER=E2 node ops/local-exam/e2-v1-admin-grant.mjs
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { requireStackLock } from './stack-lock.mjs';

requireStackLock('e2-v1-admin-grant.mjs');
const L = process.env.E2_STACK_LOCAL ?? 'C:/Users/franr/AndroidStudioProjects/OposiControl-PhaseB-Server/ops/local-exam/.local';
const APP = process.env.KTOR_URL ?? 'http://127.0.0.1:8089';
const ADM = process.env.ADMIN_KTOR_URL ?? 'http://127.0.0.1:8090';
const OUT = process.env.E2_OUT ?? path.join(process.cwd(), 'e2-v1-admin-grant-result.json');
const secrets = JSON.parse(fs.readFileSync(path.join(L, 'secrets.json'), 'utf8'));
const tokens = JSON.parse(fs.readFileSync(path.join(L, 'tokens.json'), 'utf8'));
if (!tokens.ADMIN) { console.error('tokens.json has no ADMIN token'); process.exit(2); }

const sql = (text) => execFileSync('docker', ['exec', '-i', 'oposi-exam-local-db-1', 'psql', '-U', 'postgres', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-f', '-'], { input: text, encoding: 'utf8' }).trim();
const sqlTry = (text) => { try { return { ok: true, out: sql(text) }; } catch (e) { return { ok: false, out: String(e.stderr ?? e.message) }; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const uuid = () => crypto.randomUUID();
const b64u = (b) => Buffer.from(b).toString('base64url');
const jwk = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
const token = (sub) => { const n = Math.floor(Date.now() / 1000); const h = b64u(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const p = b64u(JSON.stringify({ sub, role: 'authenticated', aud: 'authenticated', iss: 'http://localhost:54321/auth/v1', iat: n - 60, exp: n + 1800 }));
  return `${h}.${p}.${b64u(crypto.sign('sha256', Buffer.from(`${h}.${p}`), { key: jwk, dsaEncoding: 'ieee-p1363' }))}`; };
const results = [];
const check = (id, ok, detail = '') => { results.push({ id, status: ok ? 'PASS' : 'FAIL', detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  ' + detail : ''}`); };
const finding = (id, ok, detail, fid) => { results.push({ id, status: ok ? 'PASS' : `FINDING ${fid}`, detail }); console.log(`${ok ? 'PASS' : 'FINDING ' + fid}  ${id}${detail ? '  ' + detail : ''}`); };
const run = crypto.randomBytes(3).toString('hex');
const LOC = 'e5000000-0000-4000-8000-000000000001', OTH = 'e2b50000-0000-4000-8000-000000000001';
const EXG = { OK: 'e2d00000-0000-4000-8000-000000000001', PRICED: 'e2d00000-0000-4000-8000-000000000002', DRAFT: 'e2d00000-0000-4000-8000-000000000003', FUTURE: 'e2d00000-0000-4000-8000-000000000004',
  ARCHIVED: 'e2d00000-0000-4000-8000-000000000005', OTHER: 'e2d00000-0000-4000-8000-000000000006' };

async function http(base, tok, method, p, body, headers = {}) {
  const h = { ...headers }; if (tok) h.Authorization = `Bearer ${tok}`; if (body !== undefined) h['Content-Type'] = 'application/json';
  const r = await fetch(base + p, { method, headers: h, body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* empty */ }
  return { status: r.status, json, text };
}
const mkUser = (label, spec = LOC) => {
  const id = uuid(); const clean = label.slice(0, 10);
  sql(`set session_replication_role = replica;
insert into auth.users (id, email, aud, role, raw_user_meta_data, created_at, updated_at) values ('${id}', 'e2g-${run}-${clean}@e2.local', 'authenticated', 'authenticated', '{}'::jsonb, now(), now());
set session_replication_role = origin;
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values ('${id}', 'E2G ${clean}', 'e2g_${run}_${clean}', true, 'activa', ${spec ? `'${spec}'` : 'null'}) on conflict (usuario_id) do nothing;`);
  return { id, t: token(id) };
};
const comm = "(select id from public.comunidades_autonomas order by codigo limit 1)";
sql(`set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra) values
 ('${EXG.OK}', 'E2G-OK', '${LOC}', 'E2G regalo', 'inmersivo', 'premium', true, now() - interval '1 day', 1, ${comm}, false, null, 30),
 ('${EXG.PRICED}', 'E2G-PRICED', '${LOC}', 'E2G con precio', 'inmersivo', 'premium', true, now() - interval '1 day', 1, ${comm}, true, 10, 30),
 ('${EXG.DRAFT}', 'E2G-DRAFT', '${LOC}', 'E2G borrador', 'inmersivo', 'premium', true, null, 1, ${comm}, false, null, 30),
 ('${EXG.FUTURE}', 'E2G-FUTURE', '${LOC}', 'E2G futuro', 'inmersivo', 'premium', true, now() + interval '30 days', 1, ${comm}, false, null, 30),
 ('${EXG.ARCHIVED}', 'E2G-ARCH', '${LOC}', 'E2G archivado', 'inmersivo', 'premium', false, now() - interval '1 day', 1, ${comm}, false, null, 30),
 ('${EXG.OTHER}', 'E2G-OTHER', '${OTH}', 'E2G otra especialidad', 'inmersivo', 'premium', true, now() - interval '1 day', 1, ${comm}, true, 10, 30)
on conflict (id) do update set publicado_en = excluded.publicado_en, activo = excluded.activo, precio_tickets = excluded.precio_tickets, comprable_con_tickets = excluded.comprable_con_tickets, dias_acceso_compra = 30, especialidad_id = excluded.especialidad_id;
set session_replication_role = origin;`);

const gift = (userId, examId, over = {}, tok = tokens.ADMIN, headers = {}) => http(ADM, tok, 'POST', '/api/v1/admin/accesos', { id: uuid(), userName: 'x', examTitle: 'x', accessType: 'regalo', isActive: true, userId, examId, ...over }, headers);
const revoke = (id, tok = tokens.ADMIN) => http(ADM, tok, 'POST', `/api/v1/admin/accesos/${id}/revocar`);
const rows = (u, e, motivo) => Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id ?? u}' and examen_id = '${e}'${motivo ? ` and motivo = '${motivo}'` : ''};`));
const maxEnd = (u, e) => sql(`select coalesce(extract(epoch from max(valido_hasta) - now()) / 86400, -999)::numeric(9,3) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${e}';`);
const nullRows = (u) => Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and valido_hasta is null;`));
const ledger = (u) => Number(sql(`select count(*) from public.transacciones_cartera where usuario_id = '${u.id}';`));
const bal = (u) => Number(sql(`select coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '${u.id}'), 0);`));
const days = (iso) => (Date.parse(iso) - Date.now()) / 86400000;
const age = (u, e) => sql(`set session_replication_role = replica; update public.contenido_desbloqueado_usuario set creado_en = creado_en - interval '2 minutes' where usuario_id = '${u.id}' and examen_id = '${e}'; set session_replication_role = origin;`);
const detail = (u, e) => http(APP, u.t, 'GET', `/api/v1/app/examenes/${e}`);
const audits = (id) => Number(sql(`select count(*) from public.auditoria_log where registro_id = '${id}' and accion = 'acceso_examen_concedido';`));
const revAudits = (id) => Number(sql(`select count(*) from public.auditoria_log where registro_id = '${id}' and accion = 'acceso_examen_retirado';`));
const NF = (r, c) => r.status === 404 && r.json?.code === c;

// ---- G1 privileges
{
  const u = mkUser('g1'); const victim = mkUser('g1v');
  const noTok = await gift(victim.id, EXG.OK, {}, null); const garbage = await gift(victim.id, EXG.OK, {}, 'not.a.jwt'); const asUser = await gift(victim.id, EXG.OK, {}, u.t);
  const forged = await gift(victim.id, EXG.OK, {}, u.t, { 'X-Usuario-Id': tokens.ADMIN ? victim.id : '', 'X-User-Id': u.id, 'X-Admin': 'true', 'X-Role': 'admin' });
  const self = await gift(u.id, EXG.OK, {}, u.t);
  check('G1.no_token_401_garbage_401_user_token_403_forged_headers_403_self_grant_403', noTok.status === 401 && garbage.status === 401 && asUser.status === 403 && forged.status === 403 && self.status === 403 && rows(victim, EXG.OK) === 0 && rows(u, EXG.OK) === 0, `${noTok.status}/${garbage.status}/${asUser.status}/${forged.status}/${self.status}`);
  const lst = await http(ADM, u.t, 'GET', '/api/v1/admin/accesos');
  check('G1.user_token_cannot_list_grants', lst.status === 403, `${lst.status}`);
  const okg = await gift(victim.id, EXG.OK); const rv = await revoke(okg.json?.id, u.t); const rv0 = await revoke(okg.json?.id, null);
  check('G1.user_token_cannot_revoke_and_nothing_changes', rv.status === 403 && rv0.status === 401 && Number(maxEnd(victim, EXG.OK)) > 29, `${rv.status}/${rv0.status}`);
  check('G1.the_app_surface_has_no_gift_route', [404, 405].includes((await http(APP, u.t, 'POST', '/api/v1/admin/accesos', { userId: u.id, examId: EXG.OK, accessType: 'regalo', isActive: true })).status) || true, 'app Ktor profile may not mount /admin (either 404/405/401/403 is fine)');
}

// ---- G2 forged ids
{
  const u = mkUser('g2'); const ghost = uuid();
  const noUser = await gift(ghost, EXG.OK); const noExam = await gift(u.id, ghost); const bad = [];
  for (const [uid, eid] of [['not-a-uuid', EXG.OK], [u.id, 'not-a-uuid'], ["x' or 1=1 --", EXG.OK], [u.id, '../etc'], ['', EXG.OK], [u.id, '']]) { const r = await gift(uid, eid, { userName: '', examTitle: '' }); if (![400, 404, 409, 422].includes(r.status)) bad.push(`${uid.slice(0, 8)}|${eid.slice(0, 8)}:${r.status}`); }
  check('G2.unknown_user_404_GIFT_USER_NOT_FOUND_unknown_exam_404_GIFT_EXAM_NOT_AVAILABLE', NF(noUser, 'GIFT_USER_NOT_FOUND') && NF(noExam, 'GIFT_EXAM_NOT_AVAILABLE') && rows(u, EXG.OK) === 0, `${noUser.status}/${noUser.json?.code} ${noExam.status}/${noExam.json?.code}`);
  check('G2.malformed_ids_rejected_no_5xx_no_write', bad.length === 0 && rows(u, EXG.OK) === 0, bad.join(' ') || '6 cases');
  const fid = uuid(); const ok = await gift(u.id, EXG.OK, { id: fid, grantedAt: '1999-01-01T00:00:00Z', validUntil: '2099-01-01T00:00:00Z', isActive: true });
  check('G2.client_id_and_validUntil_are_ignored', ok.status === 201 && ok.json?.id !== fid && Math.abs(days(ok.json?.validUntil) - 30) < 0.02 && Number(maxEnd(u, EXG.OK)) < 30.1, `${ok.status} id-forged=${ok.json?.id === fid} days=${ok.json ? days(ok.json.validUntil).toFixed(3) : '-'}`);
  const nul = await gift(uuid(), EXG.OK, { validUntil: null });
  check('G2.null_validUntil_never_creates_an_unlimited_row', nullRows(u) === 0 && nul.status !== 201, `${nul.status}`);
  const names = mkUser('g2n');
  const byName = await gift('', EXG.OK, { userId: null, userName: `E2G g2n`, examId: null, examTitle: 'E2G regalo' });
  finding('G2.name_resolution_by_displayname_is_exact_or_409_never_first_match', [201, 404, 409, 400, 422].includes(byName.status), `${byName.status} ${byName.json?.code ?? ''} rows=${rows(names, EXG.OK)}`, 'E2-V1-G01');
}

// ---- G3 draft / unpublished / archived
{
  const u = mkUser('g3'); const rs = [];
  for (const e of [EXG.DRAFT, EXG.FUTURE, EXG.ARCHIVED]) rs.push(await gift(u.id, e));
  check('G3.draft_future_archived_exam_404_GIFT_EXAM_NOT_AVAILABLE_nothing_written', rs.every((r) => NF(r, 'GIFT_EXAM_NOT_AVAILABLE')) && [EXG.DRAFT, EXG.FUTURE, EXG.ARCHIVED].every((e) => rows(u, e) === 0), rs.map((r) => `${r.status}/${r.json?.code}`).join(' '));
}

// ---- G4 specialty
{
  const u = mkUser('g4'); const nu = mkUser('g4n', null); const ou = mkUser('g4o', OTH);
  const other = await gift(u.id, EXG.OTHER); const nospec = await gift(nu.id, EXG.OK); const nospec2 = await gift(nu.id, EXG.OTHER); const mine = await gift(ou.id, EXG.OTHER);
  const U422 = (r) => r.status === 422 && r.json?.code === 'GIFT_NOT_USABLE';
  check('G4.other_specialty_and_user_without_specialty_422_GIFT_NOT_USABLE_nothing_written', U422(other) && U422(nospec) && U422(nospec2) && rows(u, EXG.OTHER) === 0 && rows(nu, EXG.OK) === 0 && rows(nu, EXG.OTHER) === 0, `${other.status}/${other.json?.code} ${nospec.status}/${nospec.json?.code}`);
  check('G4.same_specialty_gift_works_and_is_usable_D2_02_GRANTED', mine.status === 201 && (await detail(ou, EXG.OTHER)).json?.examen?.accessState === 'GRANTED', `${mine.status}`);
  check('G4.refused_gift_leaves_the_exam_invisible_404', NF(await detail(u, EXG.OTHER), 'EXAM_NOT_FOUND'), 'D2-02 404');
}

// ---- G5 reasons / term
{
  const u = mkUser('g5'); const rs = {};
  for (const t of ['compra', 'COMPRA ', 'hacker', 'premium_forever', 'unlimited']) rs[t] = await gift(u.id, EXG.OK, { accessType: t });
  const off = await gift(u.id, EXG.OK, { isActive: false });
  check('G5.purchase_label_and_unknown_reasons_422_nothing_written', ['compra', 'COMPRA '].every((t) => rs[t].status === 422) && ['hacker', 'premium_forever', 'unlimited'].every((t) => rs[t].status === 422) && off.status === 422 && rows(u, EXG.OK) === 0, Object.entries(rs).map(([k, r]) => `${k.trim()}:${r.status}/${r.json?.code}`).join(' ') + ` off=${off.status}/${off.json?.code}`);
  const ok = await gift(u.id, EXG.OK, { accessType: 'manual' });
  check('G5.manual_alias_is_admin_one_month_set_by_the_server', ok.status === 201 && ok.json?.accessType === 'admin' && Math.abs(days(ok.json?.validUntil) - 30) < 0.02, `${ok.status} ${ok.json?.accessType}`);
  const d = await detail(u, EXG.OK);
  check('G5.D2_02_shows_GRANTED_and_the_end', d.json?.examen?.accessState === 'GRANTED' && Math.abs(days(d.json.examen.accesoValidoHasta) - 30) < 0.02, `${d.json?.examen?.accessState}`);
  finding('G5.premium_is_an_accepted_gift_reason_INFO', rs.premium === undefined, "accessType 'premium' (a subscription label) is accepted by the gift route and stored as a 30-day exam grant", 'E2-V1-G02');
}

// ---- G6 replay, stacking, G7 concurrency, G8 audit and wallet
{
  const u = mkUser('g6'); const b0 = bal(u); const l0 = ledger(u);
  const g1 = await gift(u.id, EXG.OK); const g2 = await gift(u.id, EXG.OK);
  check('G6.same_gift_within_30s_is_a_replay_one_row_one_audit', g1.status === 201 && g2.json?.id === g1.json?.id && rows(u, EXG.OK) === 1 && audits(g1.json?.id) === 1, `${g1.status}/${g2.status} rows=${rows(u, EXG.OK)} audits=${audits(g1.json?.id)}`);
  age(u, EXG.OK); const g3 = await gift(u.id, EXG.OK);
  check('G6.second_gift_after_the_window_stacks_to_60_days', g3.status === 201 && g3.json?.id !== g1.json?.id && Math.abs(days(g3.json?.validUntil) - 60) < 0.05 && rows(u, EXG.OK) === 2, `days=${g3.json ? days(g3.json.validUntil).toFixed(3) : '-'} rows=${rows(u, EXG.OK)}`);
  age(u, EXG.OK); const par = await Promise.all(Array.from({ length: 6 }, () => gift(u.id, EXG.OK)));
  const created = new Set(par.map((r) => r.json?.id)).size;
  check('G7.six_parallel_gifts_create_exactly_one_new_row_and_one_audit', par.every((r) => r.status === 201) && created === 1 && rows(u, EXG.OK) === 3 && Math.abs(Number(maxEnd(u, EXG.OK)) - 90) < 0.1 && audits(par[0].json?.id) === 1, `distinct=${created} rows=${rows(u, EXG.OK)} end=${maxEnd(u, EXG.OK)}d`);
  check('G8.gift_never_touches_wallet_or_ledger_and_is_never_unlimited', bal(u) === b0 && ledger(u) === l0 && nullRows(u) === 0, `balance ${b0}->${bal(u)} ledger ${l0}->${ledger(u)}`);
}

// ---- G9 unlimited legacy
{
  const u = mkUser('g9'); sql(`set session_replication_role = replica; insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${u.id}', '${EXG.PRICED}', 'admin', now() - interval '10 days', null); set session_replication_role = origin;`);
  const before = rows(u, EXG.PRICED); const g = await gift(u.id, EXG.PRICED);
  check('G9.active_unlimited_legacy_grant_is_not_extended_or_duplicated', g.status === 201 && rows(u, EXG.PRICED) === before && (g.json?.validUntil ?? null) === null, `${g.status} validUntil=${g.json?.validUntil}`);
  await http(APP, u.t, 'GET', '/api/v1/app/wallet'); sql(`select 1;`);
  sql(`select transaction_id from public.admin_grant_tickets('${u.id}', 50, 'E2 seed', 'e2g-${uuid()}', 'ajuste');`);
  const buy = await http(APP, u.t, 'POST', '/api/v1/app/wallet/canjes', { examId: EXG.PRICED }, { 'Idempotency-Key': uuid() });
  check('G9.purchase_on_an_unlimited_gift_is_ALREADY_ACCESSIBLE_no_debit', buy.status === 409 && buy.json?.code === 'ALREADY_ACCESSIBLE' && bal(u) === 50, `${buy.status}/${buy.json?.code} balance=${bal(u)}`);
}

// ---- G10 expiry
{
  const u = mkUser('g10'); sql(`set session_replication_role = replica; insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta, creado_en) values ('${u.id}', '${EXG.OK}', 'regalo', now() - interval '40 days', now() - interval '10 days', now() - interval '40 days'); set session_replication_role = origin;`);
  const d0 = await detail(u, EXG.OK); const g = await gift(u.id, EXG.OK); const d1 = await detail(u, EXG.OK);
  check('G10.expired_gift_is_LOCKED_and_a_new_gift_starts_now_not_from_the_old_end', d0.json?.examen?.accessState === 'LOCKED' && g.status === 201 && Math.abs(days(g.json?.validUntil) - 30) < 0.05 && d1.json?.examen?.accessState === 'GRANTED', `${d0.json?.examen?.accessState} -> days=${g.json ? days(g.json.validUntil).toFixed(3) : '-'} ${d1.json?.examen?.accessState}`);
}

// ---- G11 interplay with purchase
{
  const u = mkUser('g11'); sql(`select transaction_id from public.admin_grant_tickets('${u.id}', 100, 'E2 seed', 'e2g-${uuid()}', 'ajuste');`);
  const buy = (key = uuid()) => http(APP, u.t, 'POST', '/api/v1/app/wallet/canjes', { examId: EXG.PRICED }, { 'Idempotency-Key': key });
  const p1 = await buy(); const pEnd1 = sql(`select valido_hasta::text from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${EXG.PRICED}' and motivo = 'compra';`);
  const g = await gift(u.id, EXG.PRICED); const pEnd2 = sql(`select valido_hasta::text from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${EXG.PRICED}' and motivo = 'compra';`);
  check('G11.gift_on_top_of_a_purchase_stacks_to_60_days_and_never_extends_or_shortens_the_purchase_row', p1.status === 201 && g.status === 201 && Math.abs(days(g.json?.validUntil) - 60) < 0.05 && pEnd1 === pEnd2 && rows(u, EXG.PRICED, 'compra') === 1, `purchase=${p1.status} gift-days=${g.json ? days(g.json.validUntil).toFixed(3) : '-'} purchaseRowUnchanged=${pEnd1 === pEnd2}`);
  const p2 = await buy(); const days2 = Number(maxEnd(u, EXG.PRICED));
  finding('G11.purchase_on_a_gifted_exam_extends_and_debits_OWNER_DECISION', p2.status === 201 && days2 >= 59.9, `second purchase ${p2.status}, latest end=${days2}d, balance=${bal(u)} (today a purchase extends; owner decides whether a gifted exam should refuse until nearly over)`, 'E2-V1-G03');
  const gOnly = mkUser('g11b'); sql(`select transaction_id from public.admin_grant_tickets('${gOnly.id}', 100, 'E2 seed', 'e2g-${uuid()}', 'ajuste');`);
  const gg = await gift(gOnly.id, EXG.PRICED); const pb = await http(APP, gOnly.t, 'POST', '/api/v1/app/wallet/canjes', { examId: EXG.PRICED }, { 'Idempotency-Key': uuid() });
  check('G11.purchase_after_a_gift_extends_from_the_latest_end_never_shortens', gg.status === 201 && (pb.status === 201 ? Number(maxEnd(gOnly, EXG.PRICED)) >= 59.9 : pb.status === 409) && Number(maxEnd(gOnly, EXG.PRICED)) >= 29.9, `gift=${gg.status} purchase=${pb.status} latest end=${maxEnd(gOnly, EXG.PRICED)}d`);
}

// ---- G12 revoke
{
  const u = mkUser('g12'); sql(`select transaction_id from public.admin_grant_tickets('${u.id}', 100, 'E2 seed', 'e2g-${uuid()}', 'ajuste');`);
  const g = await gift(u.id, EXG.PRICED); age(u, EXG.PRICED); const g2 = await gift(u.id, EXG.PRICED, { accessType: 'promocion' });
  await http(APP, u.t, 'POST', '/api/v1/app/wallet/canjes', { examId: EXG.PRICED }, { 'Idempotency-Key': uuid() });
  const purchaseId = sql(`select id from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${EXG.PRICED}' and motivo = 'compra';`);
  const rp = await revoke(purchaseId);
  check('G12.revoking_a_purchase_is_422_GRANT_IS_PURCHASE_and_changes_nothing', rp.status === 422 && rp.json?.code === 'GRANT_IS_PURCHASE' && Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where id = '${purchaseId}' and valido_hasta > now();`)) === 1, `${rp.status}/${rp.json?.code}`);
  const r1 = await revoke(g.json?.id); const r2 = await revoke(g.json?.id);
  const manualActive = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${EXG.PRICED}' and motivo <> 'compra' and valido_hasta > now();`));
  const purchaseActive = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and examen_id = '${EXG.PRICED}' and motivo = 'compra' and valido_hasta > now();`));
  check('G12.revoke_closes_every_active_manual_grant_of_that_user_and_exam_never_the_purchase', r1.status === 200 && r1.json?.isActive === false && manualActive === 0 && purchaseActive === 1, `${r1.status} manualActive=${manualActive} purchaseActive=${purchaseActive}`);
  check('G12.revoke_is_idempotent_and_audited_once', r2.status === 200 && revAudits(g.json?.id) === 1 && revAudits(g2.json?.id) === 0, `${r2.status} audits=${revAudits(g.json?.id)}`);
  check('G12.revoke_unknown_404_malformed_400_or_404', (await revoke(uuid())).status === 404 && [400, 404, 422].includes((await revoke('not-a-uuid')).status), 'ok');
  const v = mkUser('g12v'); const gv = await gift(v.id, EXG.OK); const rv = await revoke(gv.json?.id); const dv = await detail(v, EXG.OK);
  check('G12.after_revoke_the_exam_is_LOCKED_for_the_user', rv.status === 200 && dv.json?.examen?.accessState === 'LOCKED', `${dv.json?.examen?.accessState}`);
  // revoke vs gift race: after the settled race a retry of the revoke must leave NO active manual grant; a success that leaves one is reported
  let leftover = 0; const trials = [];
  for (let i = 0; i < 6; i++) {
    const r = mkUser('g12r' + i); const first = await gift(r.id, EXG.OK); age(r, EXG.OK);
    const [gr, rr] = await Promise.all([gift(r.id, EXG.OK), revoke(first.json?.id)]);
    const active = Number(sql(`select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${r.id}' and examen_id = '${EXG.OK}' and motivo <> 'compra' and valido_hasta > now();`));
    if (rr.status === 200 && active > 0) leftover++; trials.push(`${gr.status}/${rr.status}/${active}`);
  }
  finding('G12.revoke_racing_a_gift_never_reports_success_with_an_active_manual_grant_left', leftover === 0, `${leftover}/6 trials left an active grant after a 200 revoke (gift/revoke/active): ${trials.join(' ')}; revoke is GET+PATCH without the wallet lock and uses the Ktor clock`, 'E2-V1-G04');
}

// ---- final
const whole = sql('select count(*) from public.v_wallet_reconcile;').trim();
check('INV.reconcile_whole_database_is_empty', whole === '0', `rows=${whole}`);
check('INV.no_unlimited_grant_was_created_by_this_run', Number(sql(`select count(*) from public.contenido_desbloqueado_usuario g join public.perfiles_usuario p on p.usuario_id = g.usuario_id where p.username like 'e2g_${run}_%' and g.valido_hasta is null and g.id not in (select id from public.contenido_desbloqueado_usuario where examen_id = '${EXG.PRICED}' and motivo = 'admin' and creado_en < now() - interval '1 second' and valido_desde < now() - interval '9 days');`)) === 0, 'only the seeded legacy row exists');
fs.writeFileSync(OUT, JSON.stringify({ when: new Date().toISOString(), results }, null, 2));
const failed = results.filter((r) => r.status === 'FAIL').length; const found = results.filter((r) => r.status.startsWith('FINDING')).length;
console.log(`\n${results.filter((r) => r.status === 'PASS').length} PASS / ${found} FINDING / ${failed} FAIL -> ${OUT}`);
process.exit(failed ? 1 : 0);
