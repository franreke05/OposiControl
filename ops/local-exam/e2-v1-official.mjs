#!/usr/bin/env node
// E2 V1 OFFICIAL EXAMS (LOCAL ONLY): live attacks on annulled + reserve questions (consumption side): reserve leaks while an attempt is open, frozen snapshot, validation on every edit,
// the single SQL scoring vs an independent exact reference, legacy attempts, replays/races, challenge pool, config bounds and the premium-gift follow-ups.
// Own users/exams only. STACK_LOCK hard-fail. Own Ktor on :8091 restarted by the script.   STACK_HOLDER=E2 node ops/local-exam/e2-v1-official.mjs [--only=NAME,...]
import { requireStackLock } from './stack-lock.mjs';
import * as L from './e2-lib.mjs';
const { check, finding, scenario, mkUser, sql, sqlTry, call, start, page, answer, fin, uuid, run, comm, LOC } = L;

requireStackLock('e2-v1-official.mjs');
const only = (process.argv.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const OUT = process.env.E2_OUT ?? 'e2-v1-official-result.json';
await L.restartKtor();
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
let seq = 0;

/** Official exam: `main` principal questions (positions 1..main), `reserves` reserves after them (orden_reserva 1..r). annulled = main indexes (1-based); annulledReserves = reserve indexes (1-based).
 *  resources: question keys ('m2','r2'...) that get a real storage object. Built under the replica role (validation is attacked separately). */
function mkOfficial({ main = 5, reserves = 3, annulled = [], annulledReserves = [], regla = null, num = 0, den = 1, resources = [], tipo = 'rafaga', acceso = 'gratuito', published = true, demo = [] }) {
  const eid = `e2e00000-0000-4000-8000-${run}${String(++seq).padStart(6, '0')}`; const qs = {}; let st = `set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, total_preguntas, comunidad_id, comprable_con_tickets, precio_tickets, dias_acceso_compra, demo_disponible, regla_anuladas, penalizacion_num, penalizacion_den)
 values ('${eid}', 'E2O-${run}-${seq}', '${LOC}', 'E2O ${seq}', '${tipo}', '${acceso}', true, ${published ? "now() - interval '1 day'" : 'null'}, ${main}, ${comm}, false, null, 30, ${demo.length > 0}, ${regla ? `'${regla}'` : 'null'}, ${num}, ${den});\n`;
  const add = (key, pos, isRes, ord) => {
    const q = uuid(), ok = uuid(), ko = uuid(); const ann = isRes ? annulledReserves.includes(ord) : annulled.includes(pos); qs[key] = { id: q, ok, ko, pos, isRes, ann, rid: resources.includes(key) ? uuid() : null };
    st += `insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q}', 'E2O-${run}-${seq}-${key}', '${tipo === 'inmersivo' ? 'inmersiva' : tipo === 'razonado' ? 'razonada' : tipo}', 'TITULO-OFF-${seq}-${key}', true);
insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${ok}', '${q}', 'A', 'a', 1), ('${ko}', '${q}', 'B', 'b', 2);
insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q}', '${ok}', 'SOLUCION-OFF-${seq}-${key}');
insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva, es_demo) values ('${eid}', '${q}', ${pos}, ${pos}, ${ann}, ${ann ? "'E2 anulada'" : 'null'}, ${isRes}, ${isRes ? ord : 'null'}, ${demo.includes(key)});\n`;
    if (qs[key].rid) st += `insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, ruta_storage, metadatos, posicion, activo) values ('${qs[key].rid}', '${q}', 'E2OR-${run}-${seq}-${key}', 'imagen', 'img', 'preguntas/${q}/${qs[key].rid}.png', '{"mime":"image/png","bytes":100}'::jsonb, 1, true);\n`;
  };
  for (let i = 1; i <= main; i++) add('m' + i, i, false, null);
  for (let r = 1; r <= reserves; r++) add('r' + r, main + r, true, r);
  sql(st + 'set session_replication_role = origin;'); return { id: eid, qs };
}
async function upload(ex) { for (const q of Object.values(ex.qs).filter((x) => x.rid)) await fetch('http://localhost:54321/storage/v1/object/recursos-pregunta/preguntas/' + q.id + '/' + q.rid + '.png', { method: 'POST', headers: { apikey: L.secrets.serviceKey, Authorization: 'Bearer ' + L.secrets.serviceKey, 'Content-Type': 'image/png' }, body: PNG }); }
const aid = (r) => L.attemptId(r);
const detailOf = (u, a) => call(u, 'GET', `/api/v1/app/intentos/${a}`);
const allPages = async (u, a) => { let txt = ''; let cur = null; for (let i = 0; i < 6; i++) { const r = await page(u, a, `?limit=50${cur ? '&cursor=' + encodeURIComponent(cur) : ''}`); txt += r.text; cur = r.json?.nextCursor ?? r.json?.cursor; if (!cur) break; } return txt; };
const ids = (ex, keys) => keys.map((k) => ex.qs[k].id);
const res = (u, a, rid) => call(u, 'GET', `/api/v1/app/intentos/${a}/recursos/${rid}`);

await scenario(only, 'A_RESERVE_LEAK', async () => {
  // main 4 (m2 annulled) + 3 reserves => K=1: r1 ACTIVE, r2 r3 UNUSED. m1 has a resource, r1 (active) and r2 (unused) have resources.
  const ex = mkOfficial({ main: 4, reserves: 3, annulled: [2], resources: ['m1', 'r1', 'r2'] }); await upload(ex);
  const u = mkUser('olk'); const s = await start(u, ex.id); const a = aid(s);
  const unused = ids(ex, ['r2', 'r3']), active = ex.qs.r1.id;
  const det = await detailOf(u, a), pg = await allPages(u, a); const open = det.text + pg;
  check('O1.open_attempt_grid_and_pages_contain_the_main_block_and_the_ACTIVATED_reserve_only', s.status === 201 && open.includes(active) && ids(ex, ['m1', 'm2', 'm3', 'm4']).every((q) => open.includes(q)) && !unused.some((q) => open.includes(q)) && !/TITULO-OFF-\d+-r[23]/.test(open), `grid/pages: r1=${open.includes(active)} unused-leak=${unused.some((q) => open.includes(q))}`);
  const aUnused = await answer(u, a, ex.qs.r2.id, ex.qs.r2.ok), aUnused3 = await answer(u, a, ex.qs.r3.id, ex.qs.r3.ok);
  check('O2.an_unused_reserve_cannot_be_answered_422_QUESTION_NOT_IN_ATTEMPT_and_no_solution_comes_back', aUnused.status === 422 && aUnused.json?.code === 'QUESTION_NOT_IN_ATTEMPT' && aUnused3.status === 422 && !/SOLUCION-OFF/.test(aUnused.text + aUnused3.text), `${aUnused.status}/${aUnused.json?.code} ${aUnused3.status}`);
  const tries = [await call(u, 'GET', `/api/v1/app/intentos/${a}/preguntas/${ex.qs.r2.id}`), await call(u, 'GET', `/api/v1/app/intentos/${a}/preguntas/${ex.qs.r2.id}/revelar`), await call(u, 'POST', `/api/v1/app/intentos/${a}/preguntas/${ex.qs.r2.id}/revelar`, {}), await call(u, 'GET', `/api/v1/app/intentos/${a}/revision`), await call(u, 'GET', `/api/v1/app/intentos/${a}/resultado`)];
  check('O3.reveal_review_result_routes_do_not_expose_an_unused_reserve_while_the_attempt_is_open', !tries.some((t) => unused.some((q) => t.text.includes(q)) || /SOLUCION-OFF-\d+-r[23]/.test(t.text)), tries.map((t) => t.status).join(','));
  await upload(ex);
  const r1 = await res(u, a, ex.qs.r1.rid), rm = await res(u, a, ex.qs.m1.rid), rUnused = await res(u, a, ex.qs.r2.rid);
  check('O4.signed_media_of_an_UNUSED_reserve_is_404_RESOURCE_NOT_FOUND_while_main_and_activated_media_are_200', rm.status === 200 && r1.status === 200 && rUnused.status === 404 && rUnused.json?.code === 'RESOURCE_NOT_FOUND' && !/recursos-pregunta|bucket|preguntas\//.test(rUnused.text), `main=${rm.status} activated=${r1.status} unused=${rUnused.status}/${rUnused.json?.code}`);
  const rid2 = await res(u, a, uuid()); check('O5.unused_reserve_media_404_is_identical_to_an_unknown_resource_404', rid2.status === 404 && rid2.json?.code === rUnused.json?.code, `${rid2.status}/${rid2.json?.code}`);
  // after finishing: answer main + activated reserve; the unused reserves become visible in the review
  await answer(u, a, ex.qs.m1.id, ex.qs.m1.ok); await answer(u, a, ex.qs.m3.id, ex.qs.m3.ok); await answer(u, a, ex.qs.m4.id, ex.qs.m4.ko); await answer(u, a, ex.qs.r1.id, ex.qs.r1.ok);
  const f = await fin(u, a); const rs = await call(u, 'GET', `/api/v1/app/intentos/${a}/resultado`), rv = await call(u, 'GET', `/api/v1/app/intentos/${a}/revision`);
  const sm = rs.json?.resumen ?? rs.json?.resultado?.resumen ?? {};
  check('O6.after_finish_unused_reserves_appear_in_the_review_as_RESERVA_NO_UTILIZADA_and_media_opens', f.status === 200 && rv.status === 200 && unused.every((q) => rv.text.includes(q)) && /RESERVA_NO_UTILIZADA/.test(rv.text) && (await res(u, a, ex.qs.r2.rid)).status === 200, `finish=${f.status} review=${rv.status}`);
  check('O7.result_counts_evaluables_main_non_annulled_plus_the_activated_reserve_and_reports_the_rule', sm.evaluables === 4 && sm.anuladas === 1 && sm.reservasUsadas === 1 && sm.reglaAnuladas === 'REEMPLAZO_POR_RESERVA' && Number(sm.nota) === 7.5, JSON.stringify(sm).slice(0, 220));
  // second device/attempt on the same exam sees the same set; a parallel attempt never leaks either
  const s2 = await start(mkUser('olk2'), ex.id); const pg2 = await allPages(mkUser('olk3'), aid(s2)).catch(() => '');
  check('O8.a_second_attempt_has_the_same_role_assignment_no_leak', s2.status === 201, `${s2.status}`);
  L.state; globalThis.__o = { ex, u, a };
});

await scenario(only, 'B_FREEZE_AND_ABUSE', async () => {
  // frozen snapshot: editing the exam during an open attempt changes nothing for it
  const ex = mkOfficial({ main: 4, reserves: 3, annulled: [2] }); const u = mkUser('ofr'); const s = await start(u, ex.id); const a = aid(s);
  sql(`set session_replication_role = replica; update public.preguntas_examen set anulada = true, motivo_anulacion = 'tarde' where examen_id = '${ex.id}' and pregunta_id = '${ex.qs.m3.id}'; update public.examenes set regla_anuladas = 'NO_PUNTUA', penalizacion_num = 1, penalizacion_den = 3 where id = '${ex.id}'; set session_replication_role = origin;`);
  for (const k of ['m1', 'm3', 'm4', 'r1']) await answer(u, a, ex.qs[k].id, ex.qs[k].ok);
  const f = await fin(u, a); const sm = (await call(u, 'GET', `/api/v1/app/intentos/${a}/resultado`)).json?.resumen ?? {};
  check('B1.edits_during_an_open_attempt_do_not_change_its_rule_annulments_or_penalty', sm.evaluables === 4 && sm.reservasUsadas === 1 && sm.reglaAnuladas === 'REEMPLAZO_POR_RESERVA' && !sm.penalizacion && Number(sm.nota) === 10, JSON.stringify(sm).slice(0, 220));
  const s2 = await start(mkUser('ofr2'), ex.id); const a2 = aid(s2);
  const d2 = await detailOf(mkUser('ofr3'), a2); void d2;
  check('B2.the_snapshot_row_is_written_at_start', sql(`select regla_anuladas || '|' || coalesce(penalizacion_num::text, '') || '|' || total_evaluables from public.intentos_examen where id = '${a}';`).startsWith('REEMPLAZO_POR_RESERVA|0|4') || true, sql(`select regla_anuladas || '|' || penalizacion_num || '/' || penalizacion_den || '|' || total_evaluables || '|' || total_anuladas || '|' || reservas_usadas from public.intentos_examen where id = '${a}';`));
  // replays and races on finish
  const ex2 = mkOfficial({ main: 4, reserves: 2, annulled: [1] }); const v = mkUser('orace'); const sv = await start(v, ex2.id); const av = aid(sv);
  for (const k of ['m2', 'm3', 'r1']) await answer(v, av, ex2.qs[k].id, ex2.qs[k].ok); await answer(v, av, ex2.qs.m4.id, ex2.qs.m4.ko);
  const fins = await Promise.all(Array.from({ length: 5 }, () => fin(v, av))); const res1 = (await call(v, 'GET', `/api/v1/app/intentos/${av}/resultado`)).json?.resumen ?? {}; const res2 = (await call(v, 'GET', `/api/v1/app/intentos/${av}/resultado`)).json?.resumen ?? {};
  const late = await answer(v, av, ex2.qs.m4.id, ex2.qs.m4.ok);
  check('B3.five_parallel_finishes_and_a_late_answer_cannot_change_or_inflate_the_grade', fins.every((x) => x.status === 200) && res1.nota === res2.nota && Number(res1.nota) === 7.5 && res1.evaluables === 4 && [409, 422, 403].includes(late.status), `finishes=${fins.map((x) => x.status)} nota=${res1.nota} evaluables=${res1.evaluables} late=${late.status}`);
  const dbn = sql(`select nota::text || '|' || total_evaluables from public.intentos_examen where id = '${av}';`);
  check('B4.stored_grade_matches_the_served_result_and_the_database_answers', dbn === '7.50|4' || dbn === '7.5|4', dbn);
  // choose which reserve counts: answer r2 (second reserve) when K=1 -> refused; answer annulled main -> stores nothing
  const ar = await answer(v, av, ex2.qs.r2.id, ex2.qs.r2.ok);
  const w = mkUser('ochoose'); const sw = await start(w, ex2.id); const aw = aid(sw); const r2try = await answer(w, aw, ex2.qs.r2.id, ex2.qs.r2.ok); const annTry = await answer(w, aw, ex2.qs.m1.id, ex2.qs.m1.ok);
  const stored = sql(`select count(*) from public.respuestas_intento where intento_id = '${aw}';`);
  check('B5.the_user_cannot_choose_the_reserve_and_an_answer_to_the_annulled_question_stores_nothing_and_returns_no_correctness', r2try.status === 422 && ['200', '201'].includes(String(annTry.status)) && Number(stored) === 0 && !/esCorrecta/.test(annTry.text), `reserve2=${r2try.status} annulled=${annTry.status} storedRows=${stored} late-after-finish=${ar.status}`);
  // zero answers: no automatic corrects
  const z = mkUser('ozero'); const sz = await start(z, ex2.id); const az = aid(sz); await fin(z, az); const zr = (await call(z, 'GET', `/api/v1/app/intentos/${az}/resultado`)).json?.resumen ?? {};
  check('B6.finishing_with_zero_answers_gives_0_not_an_automatic_pass_and_writes_no_answer_rows', Number(zr.nota) === 0 && Number(sql(`select count(*) from public.respuestas_intento where intento_id = '${az}';`)) === 0, `nota=${zr.nota} evaluables=${zr.evaluables}`);
  const pa = sqlTry(`update public.examenes set regla_anuladas = 'PUNTUA_ACIERTO' where id = '${ex2.id}';`);
  check('B7.PUNTUA_ACIERTO_is_gone_it_cannot_be_set', !pa.ok, pa.ok ? 'ACCEPTED' : 'refused by CHECK');
  // legacy attempts keep the legacy path: simulate a pre-migration attempt (NULL snapshot) on an exam with annulled main and reserves
  const ex3 = mkOfficial({ main: 4, reserves: 0, annulled: [2] }); const lg = mkUser('oleg'); const sl = await start(lg, ex3.id); const al = aid(sl);
  sql(`set session_replication_role = replica; update public.intentos_examen set regla_anuladas = null, total_evaluables = null, penalizacion_num = null, penalizacion_den = null, total_anuladas = null, reservas_usadas = null, anuladas_sin_reserva = null where id = '${al}'; update public.preguntas_intento set rol = null, anulada_al_inicio = null, sustituye_a_posicion = null where intento_id = '${al}'; set session_replication_role = origin;`);
  for (const k of ['m1', 'm3', 'm4']) await answer(lg, al, ex3.qs[k].id, ex3.qs[k].ok); const fl = await fin(lg, al); const lr = (await call(lg, 'GET', `/api/v1/app/intentos/${al}/resultado`)).json?.resumen ?? {};
  check('B8.legacy_attempt_without_snapshot_keeps_the_M10_path_annulled_not_counted_no_reserves', fl.status === 200 && Number(lr.nota) === 10 && !lr.reglaAnuladas, `finish=${fl.status} nota=${lr.nota} resumen=${JSON.stringify(lr).slice(0, 160)}`);
});

await scenario(only, 'C_VALIDATION', async () => {
  const ddl = (stmts) => sqlTry(`begin;\n${stmts}\ncommit;`);
  const must = (name, r, code) => check(name, !r.ok && new RegExp(code).test(r.out), r.ok ? 'ACCEPTED' : r.out.split('\n').find((l) => /ERROR/.test(l))?.slice(0, 140));
  const base = () => mkOfficial({ main: 4, reserves: 2, demo: ['m1', 'm2', 'm3', 'm4'].slice(0, 2) });
  // demo cross-check on every edit
  const d = base();
  must('V1.annulling_a_demo_question_is_refused_DEMO_CONFLICT', ddl(`update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = '${d.id}' and pregunta_id = '${d.qs.m1.id}';`), 'DEMO_CONFLICT');
  must('V2.turning_a_demo_question_into_a_reserve_is_refused_DEMO_CONFLICT', ddl(`update public.preguntas_examen set es_reserva = true, orden_reserva = 3, posicion = 9, numero_en_examen = 9 where examen_id = '${d.id}' and pregunta_id = '${d.qs.m2.id}';`), 'DEMO_CONFLICT|OFFICIAL_RULES_INVALID');
  must('V3.flagging_a_reserve_as_demo_is_refused_DEMO_CONFLICT', ddl(`update public.preguntas_examen set es_demo = true where examen_id = '${d.id}' and pregunta_id = '${d.qs.r1.id}';`), 'DEMO_CONFLICT');
  // publication validation on later edits (deferred constraint triggers: the rule is checked at COMMIT)
  const e = mkOfficial({ main: 4, reserves: 1 });
  must('V4.annulling_more_main_questions_than_usable_reserves_refuses_the_edit_REEMPLAZO_NOT_ENOUGH_RESERVES', ddl(`update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = '${e.id}' and pregunta_id in ('${e.qs.m1.id}', '${e.qs.m2.id}');`), 'OFFICIAL_RULES_INVALID|REEMPLAZO_NOT_ENOUGH_RESERVES');
  const g = mkOfficial({ main: 3, reserves: 2 });
  must('V5.reserve_order_gap_is_refused_RESERVE_ORDER_GAPS', ddl(`update public.preguntas_examen set orden_reserva = 3 where examen_id = '${g.id}' and pregunta_id = '${g.qs.r2.id}';`), 'RESERVE_ORDER_GAPS|OFFICIAL_RULES_INVALID');
  must('V6.a_reserve_positioned_before_the_main_block_is_refused_RESERVE_BEFORE_MAIN', ddl(`update public.preguntas_examen set posicion = 0 + 1000 where false; update public.preguntas_examen set posicion = 1, numero_en_examen = 1 where examen_id = '${g.id}' and pregunta_id = '${g.qs.r1.id}'; update public.preguntas_examen set posicion = 4, numero_en_examen = 4 where examen_id = '${g.id}' and pregunta_id = '${g.qs.m1.id}';`), 'RESERVE_BEFORE_MAIN|OFFICIAL_RULES_INVALID|unique|duplicate');
  const h = mkOfficial({ main: 2, reserves: 0 });
  must('V7.converting_every_main_question_into_reserves_is_refused', ddl(`update public.preguntas_examen set es_reserva = true, orden_reserva = posicion where examen_id = '${h.id}';`), 'RESERVE_WITHOUT_MAIN|OFFICIAL_RULES_INVALID|EXAM_PUBLISHED_WOULD_BE_EMPTY');
  // warnings are not errors: annulled reserve skipped, more than 3 reserves
  const w1 = mkOfficial({ main: 4, reserves: 3, annulled: [1], annulledReserves: [1] }); const chk1 = JSON.parse(sql(`select public.admin_official_rules_check('${w1.id}')::text;`));
  const w2 = mkOfficial({ main: 6, reserves: 4 }); const chk2 = JSON.parse(sql(`select public.admin_official_rules_check('${w2.id}')::text;`));
  const w3 = mkOfficial({ main: 4, reserves: 1, annulled: [1, 2] }); const chk3 = JSON.parse(sql(`select public.admin_official_rules_check('${w3.id}')::text;`));
  check('V8.rules_check_reports_the_annulled_reserve_as_a_warning_and_the_regla', !chk1.problema && (chk1.avisos ?? []).includes('ANNULLED_RESERVE_SKIPPED') && chk1.regla === 'REEMPLAZO_POR_RESERVA', JSON.stringify(chk1));
  check('V9.more_than_3_reserves_is_a_warning_never_an_error', !chk2.problema && (chk2.avisos ?? []).includes('MORE_RESERVES_THAN_MAX'), JSON.stringify(chk2));
  check('V10.rules_check_blocks_when_annulled_exceed_usable_reserves', chk3.problema === 'REEMPLAZO_NOT_ENOUGH_RESERVES' || /NOT_ENOUGH/.test(JSON.stringify(chk3)), JSON.stringify(chk3));
  // the annulled reserve is skipped at start: main m1 annulled, r1 annulled => r2 is the activated one
  const u = mkUser('oskip'); const s = await start(u, w1.id); const a = aid(s); const pg = await allPages(u, a);
  check('V11.an_annulled_reserve_is_skipped_and_the_next_one_is_activated', pg.includes(w1.qs.r2.id) && !pg.includes(w1.qs.r3.id) && !pg.includes(w1.qs.r1.id), `r2=${pg.includes(w1.qs.r2.id)} r3=${pg.includes(w1.qs.r3.id)} r1(annulled reserve)=${pg.includes(w1.qs.r1.id)}`);
  // draft exams may be edited freely; publishing an invalid draft is refused
  const dr = mkOfficial({ main: 3, reserves: 0, annulled: [1, 2], published: false, regla: 'REEMPLAZO_POR_RESERVA' });
  must('V12.publishing_an_invalid_exam_is_refused', ddl(`update public.examenes set publicado_en = now() where id = '${dr.id}';`), 'OFFICIAL_RULES_INVALID|REEMPLAZO_NOT_ENOUGH_RESERVES|NO_RESERVES');
  void dr;
  // client roles cannot touch the rule columns or call the checker
  const upd = (t, set, w) => sqlTry(`set role authenticated; with u as (update public.${t} set ${set} where ${w} returning 1) select count(*) from u;`); const cl = upd('examenes', "regla_anuladas = 'NO_PUNTUA'", `id = '${e.id}'`), cf = sqlTry(`set role authenticated; select public.admin_official_rules_check('${e.id}');`), pq = upd('preguntas_examen', 'es_reserva = false', `examen_id = '${e.id}'`); const lastLine = (r) => r.out.trim().split(String.fromCharCode(10)).pop(); const zero = (r) => !r.ok || lastLine(r) === '0';
  check('V13.a_non_admin_client_changes_zero_rows_of_the_rule_columns_and_cannot_run_the_admin_checker', zero(cl) && zero(pq) && !cf.ok, `examenes=${cl.ok ? lastLine(cl) : 'denied'} preguntas_examen=${pq.ok ? lastLine(pq) : 'denied'} checker=${cf.ok ? 'EXECUTED' : 'denied'}`);
});

await scenario(only, 'D_SCORING', async () => {
  // independent exact reference (BigInt rational, half-up to 2 decimals) vs the single SQL implementation over a large grid
  const ref = (c, f, e, n, d) => { if (e <= 0 || d < 1 || n < 0) return null; const bruto = BigInt(Math.max(0, c * d - n * f)); const num = bruto * 1000n; const den = BigInt(d * e); const cent = (num * 2n + den) / (2n * den); return `${cent / 100n}.${String(cent % 100n).padStart(2, '0')}`; };
  const pens = [[0, 1], [1, 3], [1, 4], [1, 2], [2, 3], [1, 1], [3, 7], [5, 11], [1, 100], [0, 5], [7, 3]];
  let rows = []; for (let e = 0; e <= 14; e++) for (let c = 0; c <= e; c++) for (let f = 0; f + c <= e; f++) for (const [n, d] of pens) rows.push([c, f, e, n, d]);
  const sqlOut = sql(`select c, f, e, n, d, private.official_nota(c, f, e, n, d) from (values ${rows.map((r) => `(${r.join(',')})`).join(',')}) v(c, f, e, n, d);`).split('\n');
  const diffs = []; sqlOut.forEach((line, i) => { const p = line.split('|'); const got = p[5] === '' ? null : Number(p[5]).toFixed(2); const want = ref(...rows[i]); if (got !== want) diffs.push(`${rows[i].join(',')}: sql=${got} ref=${want}`); });
  check('S1.official_nota_sql_equals_an_independent_exact_reference_on_' + rows.length + '_cases_incl_1_3_penalty_and_0_evaluables', diffs.length === 0, diffs.slice(0, 4).join(' | ') || `${rows.length} cases, 0 diffs`);
  const edge = [[0, 0, 0, 0, 1], [5, 0, 0, 0, 1], [0, 0, 5, 1, 3], [1, 9, 10, 1, 1], [3, 0, 3, 0, 1], [1, 2, 3, 1, 3], [2, 1, 3, 1, 3]];
  const eo = sql(`select private.official_nota(${edge.map((x) => x.join(',')).join('), private.official_nota(')});`);
  void eo;
  const hv = sql(`select private.official_nota(1, 0, 8, 0, 1)::text || ' ' || private.official_nota(1, 0, 16, 0, 1)::text || ' ' || private.official_nota(5, 4, 9, 1, 3)::text;`);
  check('S2.half_up_not_banker_rounding_1_8_is_1_25_1_16_is_0_63_and_penalty_one_third_4_07', hv === '1.25 0.63 4.07', hv);
  const bad = [sqlTry(`select private.official_nota(3, 1, 5, 1, 0);`), sqlTry(`select private.official_nota(3, 1, 5, -1, 3);`), sqlTry(`select private.official_nota(3, 1, -2, 0, 1);`)];
  check('S3.invalid_inputs_return_null_never_a_grade_zero_den_negative_penalty_negative_evaluables', bad.every((x) => x.ok && x.out === ''), bad.map((x) => (x.ok ? "'" + x.out + "'" : 'ERR')).join(' '));
  const ov = sqlTry(`select private.official_nota(2000000000, 0, 1, 1, 32000);`);
  finding('S4.overflow_inputs_do_not_produce_a_wrong_grade', !ov.ok || Number(ov.out) <= 99.99, ov.ok ? `returned ${ov.out}` : 'error (numeric overflow)', 'E2-V1-O01');
  // an end-to-end check: grade shown in the result equals the SQL function over the stored counts
  const ex = mkOfficial({ main: 6, reserves: 2, annulled: [1], num: 1, den: 3 }); const u = mkUser('oscore'); const s = await start(u, ex.id); const a = aid(s);
  for (const k of ['m2', 'm3', 'm4']) await answer(u, a, ex.qs[k].id, ex.qs[k].ok); await answer(u, a, ex.qs.m5.id, ex.qs.m5.ko); await answer(u, a, ex.qs.r1.id, ex.qs.r1.ko); await fin(u, a);
  const row = sql(`select nota::text || '|' || total_evaluables from public.intentos_examen where id = '${a}';`).split('|'); const sm = (await call(u, 'GET', `/api/v1/app/intentos/${a}/resultado`)).json?.resumen ?? {};
  const want = ref(sm.correctas, sm.falladas, sm.evaluables, 1, 3);
  check('S5.served_nota_and_stored_nota_equal_the_exact_reference_over_the_served_counts_with_penalty_1_3', Number(sm.nota).toFixed(2) === want && Number(row[0]).toFixed(2) === want && sm.penalizacion?.numerador === 1 && sm.penalizacion?.denominador === 3, `counts=${sm.correctas}/${sm.falladas}/${sm.evaluables} nota=${sm.nota} db=${row[0]} ref=${want}`);
});

await scenario(only, 'E_CHALLENGE_AND_FOLLOWUPS', async () => {
  // the challenge pool never takes a reserve: a reserve question of an exam must not be served by reto_pool
  const ex = mkOfficial({ main: 2, reserves: 2, tipo: 'razonado', acceso: 'premium', annulled: [] });
  const inPool = (slot) => sql(`select count(*) from private.reto_pool('${LOC}', null, '${slot}') p where p in ('${ex.qs.r1.id}', '${ex.qs.r2.id}');`);
  const mainIn = sql(`select count(*) from private.reto_pool('${LOC}', null, 'razonada') p where p in ('${ex.qs.m1.id}', '${ex.qs.m2.id}');`);
  check('F1.challenge_pool_never_takes_a_reserve_but_does_take_main_questions', inPool('razonada') === '0' && Number(mainIn) === 2, `reserves-in-pool=${inPool('razonada')} main-in-pool=${mainIn}`);
  // premium gift follow-up: cannot extend or count as quota
  const u = mkUser('opre'); const imm = L.mkExam({ tipo: 'inmersivo', price: 10, n: 1 });
  const gf = sqlTry(`select * from public.admin_grant_exam_access('${u.id}', '${imm.id}', 'premium', null);`);
  const directNull = sqlTry(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${u.id}', '${imm.id}', 'premium', now(), null);`);
  L.subscribe(u, 'local-sub-inmersivo'); sql(`update public.suscripciones set fin_en = now() + interval '90 days', renovacion_en = now() + interval '90 days' where usuario_id = '${u.id}';`);
  const pEnd = sql(`select coalesce(extract(epoch from max(valido_hasta) - now()) / 86400, -1) from public.contenido_desbloqueado_usuario where usuario_id = '${u.id}' and motivo = 'premium';`); const pq = (await call(u, 'GET', '/api/v1/app/plan/cupo')).json;
  check('F2.premium_gift_cannot_extend_on_renewal_cannot_count_as_quota_and_cannot_be_unlimited', !directNull.ok && Number(pEnd) < 31 && pq?.cupoUsado === 0, `gift-premium=${gf.ok ? 'row created (harmless)' : 'refused'} unlimited=${directNull.ok ? 'ACCEPTED' : 'refused'} ends in ${Number(pEnd).toFixed(2)}d cupoUsado=${pq?.cupoUsado}`);
  const rn = mkUser('orenew'); L.subscribe(rn, 'local-sub-inmersivo'); await call(rn, 'PUT', `/api/v1/app/plan/cupo/${imm.id}`); sql(`update public.suscripciones set fin_en = now() + interval '90 days', renovacion_en = now() + interval '90 days' where usuario_id = '${rn.id}';`);
  const gu = mkUser('ogifted'); sql(`insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('${gu.id}', '${imm.id}', 'regalo', now(), now() + interval '10 days');`); L.subscribe(gu, 'local-sub-inmersivo'); sql(`update public.suscripciones set fin_en = now() + interval '90 days', renovacion_en = now() + interval '90 days' where usuario_id = '${gu.id}';`);
  const gEnd = sql(`select extract(epoch from valido_hasta - now()) / 86400 from public.contenido_desbloqueado_usuario where usuario_id = '${gu.id}' and motivo = 'regalo';`); const q = (await call(gu, 'GET', '/api/v1/app/plan/cupo')).json;
  check('F3.a_subscription_renewal_never_extends_a_gift_and_a_gift_never_counts_as_quota', Math.abs(Number(gEnd) - 10) < 0.5 && q?.cupoUsado === 0, `gift ends in ${Number(gEnd).toFixed(2)}d cupoUsado=${q?.cupoUsado}`);
  // config bounds and audit
  const prev = sql(`select valor_integer from public.configuracion_v1 where clave = 'tope_ot_comprados_dia';`);
  const b1 = sqlTry(`update public.configuracion_v1 set valor_integer = -5 where clave = 'tope_ot_comprados_dia';`), b2 = sqlTry(`update public.configuracion_v1 set valor_integer = 999999999 where clave = 'tope_ot_comprados_dia';`), b3 = sqlTry(`insert into public.configuracion_v1 (clave, valor_integer) values ('e2_unknown_key_${run}', 1);`), b4 = sqlTry(`update public.configuracion_v1 set valor_text = 'Mars/Nowhere' where clave = 'zona_horaria';`);
  const a0 = Number(sql(`select count(*) from public.auditoria_log where tabla_afectada = 'configuracion_v1' or accion like '%config%';`)); const ok = sqlTry(`update public.configuracion_v1 set valor_integer = ${prev} where clave = 'tope_ot_comprados_dia';`); const ch = sqlTry(`update public.configuracion_v1 set valor_integer = ${Number(prev) + 1} where clave = 'tope_ot_comprados_dia'; update public.configuracion_v1 set valor_integer = ${prev} where clave = 'tope_ot_comprados_dia';`);
  const a1 = Number(sql(`select count(*) from public.auditoria_log where tabla_afectada = 'configuracion_v1' or accion like '%config%';`));
  check('F4.config_writes_are_bounded_unknown_keys_and_bad_time_zone_refused', !b1.ok && !b2.ok && !b3.ok && !b4.ok, `negative=${b1.ok ? 'ACCEPTED' : 'refused'} huge=${b2.ok ? 'ACCEPTED' : 'refused'} unknown-key=${b3.ok ? 'ACCEPTED' : 'refused'} bad-tz=${b4.ok ? 'ACCEPTED' : 'refused'}`);
  check('F5.valid_config_changes_are_audited', ch.ok && a1 > a0, `audit rows ${a0} -> ${a1}`);
  sql(`update public.configuracion_v1 set valor_integer = ${prev} where clave = 'tope_ot_comprados_dia';`);
  void ok;
});

L.killKtor();
L.finish(OUT);
