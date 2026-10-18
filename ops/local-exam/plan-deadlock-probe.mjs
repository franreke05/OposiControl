#!/usr/bin/env node
// C-A6 gate: ROUNDS (40) of RENEWAL / TERMINAL state (estado_premium_usuario updates) vs QUOTA CHOICE vs PURCHASE vs GIFT on the same user, each in its own connection, against a DISPOSABLE PG17.
// Fails on any "deadlock detected" and checks the invariants afterwards (quota used <= quota, balance >= 0, reconcile clean).   node ops/local-exam/plan-deadlock-probe.mjs
import { spawn } from 'node:child_process';
import { startDisposable } from './disposable-db.mjs';

const ROUNDS = Number(process.env.ROUNDS ?? 40);
const db = startDisposable(`deadlock-probe-${process.pid}`);
let code = 1;
try {
  db.must(`
insert into auth.users (id, email, aud, role) values ('a0000000-0000-0000-0000-0000000000f1','dl@test.local','authenticated','authenticated');
insert into public.especialidades (id, codigo, nombre, slug, activa) values ('e1000000-0000-0000-0000-0000000000f1','DL','DL','dl',true);
insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('c1000000-0000-0000-0000-0000000000f1','CDL','Com DL','com-dl');
insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
  ('a0000000-0000-0000-0000-0000000000f1','dl','dl',true,'activa','e1000000-0000-0000-0000-0000000000f1') on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
set session_replication_role = replica;
insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, comprable_con_tickets, precio_tickets, dias_acceso_compra) values
  ('e5000000-0000-0000-0000-0000000000f1','DL-1','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','I1','inmersivo','premium',true, now() - interval '1 day', true, 10, 30),
  ('e5000000-0000-0000-0000-0000000000f2','DL-2','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','I2','inmersivo','premium',true, now() - interval '1 day', true, 10, 30),
  ('e5000000-0000-0000-0000-0000000000f3','DL-3','e1000000-0000-0000-0000-0000000000f1','c1000000-0000-0000-0000-0000000000f1','I3','inmersivo','premium',true, now() - interval '1 day', true, 10, 30);
set session_replication_role = origin;
insert into public.planes_suscripcion (id, codigo, nombre, nivel) values ('b1000000-0000-0000-0000-0000000000f3','plan-inm','Inmersivo',3);
insert into public.productos_comerciales (id, codigo, tipo_producto, nombre, precio_centimos, moneda, activo, visible, plan_id) values ('b2000000-0000-0000-0000-0000000000f3','sub-inm','suscripcion','Sub',999,'EUR',true,true,'b1000000-0000-0000-0000-0000000000f3');
insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('b1000000-0000-0000-0000-0000000000f3','inmersivos_incluidos',1);
insert into public.estado_premium_usuario (usuario_id, es_premium, estado_suscripcion, producto_codigo, inicio_en, fin_en) values ('a0000000-0000-0000-0000-0000000000f1', true, 'activa', 'sub-inm', now() - interval '1 day', now() + interval '30 days');
select public.admin_grant_tickets('a0000000-0000-0000-0000-0000000000f1', 100000, 'seed', 'seed-dl', 'ajuste');
`);
  const U = 'a0000000-0000-0000-0000-0000000000f1';
  const run = (sql) => new Promise((resolve) => {
    const p = spawn('docker', ['exec', '-i', db.name, 'psql', '-U', 'postgres', '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-At'], { stdio: ['pipe', 'pipe', 'pipe'] });
    let err = '';
    p.stderr.on('data', (d) => { err += d; });
    p.on('close', () => resolve(err));
    p.stdin.end(sql);
  });
  let deadlocks = 0;
  const E = (n) => `e5000000-0000-0000-0000-0000000000f${n}`;
  for (let i = 1; i <= ROUNDS; i++) {
    const terminal = i % 7 === 0;
    const jobs = [
      `update public.estado_premium_usuario set fin_en = fin_en + interval '1 day' where usuario_id = '${U}';`,
      `select * from public.app_choose_plan_exam('${U}', '${E(1 + (i % 3))}');`,
      `select * from public.app_redeem_exam_access('${U}', '${E(1 + ((i + 1) % 3))}', 'dl-${i}');`,
      `select * from public.admin_grant_exam_access('${U}', '${E(1 + ((i + 2) % 3))}', 'admin');`,
      `update public.estado_premium_usuario set es_premium = ${terminal ? 'false' : 'true'}, estado_suscripcion = '${terminal ? 'caducada' : 'activa'}', fin_en = now() + interval '30 days' where usuario_id = '${U}';`,
    ];
    const errs = await Promise.all(jobs.map(run));
    for (const e of errs) {
      if (/deadlock detected/i.test(e)) {
        deadlocks += 1;
        console.error(`round ${i}: ${e.split('\n')[0]}`);
      }
    }
  }
  const inv = db.must(`select
    (select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '${U}' and motivo = 'plan_cupo' and valido_desde <= clock_timestamp() and valido_hasta > clock_timestamp()),
    (select saldo_tickets from public.carteras_usuario where usuario_id = '${U}'),
    (select count(*) from public.app_wallet_reconcile('${U}'));`, ['-v', 'ON_ERROR_STOP=1', '-At']).trim();
  const [quotaActive, saldo, reconcile] = inv.split('|').map(Number);
  console.log(`DEADLOCK_PROBE rounds=${ROUNDS} deadlocks=${deadlocks} quota_active=${quotaActive} saldo=${saldo} reconcile_rows=${reconcile}`);
  if (deadlocks === 0 && quotaActive <= 1 && saldo >= 0 && reconcile === 0) {
    console.log('DEADLOCK_PROBE OK');
    code = 0;
  } else {
    console.error('DEADLOCK_PROBE FAILED');
  }
} finally {
  db.stop();
}
process.exit(code);
