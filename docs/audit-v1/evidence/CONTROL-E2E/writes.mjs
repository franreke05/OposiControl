import fs from 'node:fs';
const [localDir, n = '50'] = process.argv.slice(2);
const t = JSON.parse(fs.readFileSync(localDir + '/tokens.json', 'utf8')).ADMIN;
const B = 'http://127.0.0.1:18190/api/v1/admin', h = { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' };
const q = (a, p) => a[Math.min(a.length - 1, Math.ceil(p * a.length) - 1)];
const exams = (await (await fetch(B + '/examenes', { headers: h })).json()).data.filter((e) => e.titulo?.startsWith('E2E J05'));
const orders = (await (await fetch(B + '/pedidos?estado=en_revision', { headers: h })).json());
const order = (orders.items ?? orders.data ?? [])[0]?.id;
async function time(name, fn) {
  const xs = []; let err = 0;
  for (let i = 0; i < Number(n); i++) { const s = performance.now(); const r = await fn(i); if (!r.ok) { err++; if (err === 1) console.log(name, r.status, (await r.text()).slice(0, 160)); } else await r.arrayBuffer(); xs.push(performance.now() - s); }
  xs.sort((a, b) => a - b);
  console.log(`${name.padEnd(34)} n=${xs.length} p50=${q(xs, .5).toFixed(1)}ms p95=${q(xs, .95).toFixed(1)}ms max=${xs.at(-1).toFixed(1)}ms errors=${err}`);
}
if (exams[0]) await time('PUT reglas-puntuacion (audit)', (i) => fetch(`${B}/examenes/${exams[0].id}/reglas-puntuacion`, { method: 'PUT', headers: h,
  body: JSON.stringify({ reglaAnuladas: 'NO_PUNTUA', penalizacionRacional: false, penalizacionNum: 1, penalizacionDen: 3 + (i % 2), motivo: 'Medicion E2E ' + i }) }));
if (order) await time('POST pedidos/{id}/nota (audit)', (i) => fetch(`${B}/pedidos/${order}/nota`, { method: 'POST', headers: h, body: JSON.stringify({ nota: 'Medicion E2E ' + i }) }));
