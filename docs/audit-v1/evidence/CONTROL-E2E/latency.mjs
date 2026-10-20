// p50/p95 of Control admin operations against the ISOLATED stack (oposi-control-e2e, :18190). Sequential, synthetic data only.
import fs from 'node:fs';
const [localDir, n = '60'] = process.argv.slice(2);
const tokens = JSON.parse(fs.readFileSync(localDir + '/tokens.json', 'utf8'));
const BASE = 'http://127.0.0.1:18190/api/v1/admin';
const h = { Authorization: 'Bearer ' + tokens.ADMIN, 'Content-Type': 'application/json' };
const q = (a, p) => a[Math.min(a.length - 1, Math.ceil(p * a.length) - 1)];
async function time(name, fn) {
  const xs = []; let errors = 0;
  for (let i = 0; i < 5; i++) await fn().catch(() => {});           // warm-up
  for (let i = 0; i < Number(n); i++) {
    const t = performance.now();
    const r = await fn(); if (!r.ok) errors++; await r.arrayBuffer();
    xs.push(performance.now() - t);
  }
  xs.sort((a, b) => a - b);
  console.log(`${name.padEnd(34)} n=${xs.length} p50=${q(xs, .5).toFixed(1)}ms p95=${q(xs, .95).toFixed(1)}ms max=${xs.at(-1).toFixed(1)}ms errors=${errors}`);
}
const get = (p) => () => fetch(BASE + p, { headers: h });
await time('GET /sesion', get('/sesion'));
await time('GET /examenes', get('/examenes'));
await time('GET /especialidades', get('/especialidades'));
await time('GET /pedidos?estado=en_revision', get('/pedidos?estado=en_revision'));
await time('GET /pedidos/en-revision/total', get('/pedidos/en-revision/total'));
await time('GET /auditoria/movimientos', get('/auditoria/movimientos'));
await time('GET /foro/cola publicacion', get('/foro/cola?tipo=publicacion&estado=pendiente_moderacion'));
await time('GET /accesos', get('/accesos'));
await time('GET /examenes-economia', get('/examenes-economia'));
await time('GET /comercial/productos', get('/comercial/productos'));
const exam = (await (await fetch(BASE + '/examenes', { headers: h })).json()).data?.[0]?.id;
if (exam) {
  await time('GET /examenes/{id}/reglas-puntuacion', get(`/examenes/${exam}/reglas-puntuacion`));
  await time('GET /examenes/{id}/preguntas-oficiales', get(`/examenes/${exam}/preguntas-oficiales`));
}
