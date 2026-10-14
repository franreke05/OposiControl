#!/usr/bin/env node
// EX16: levanta el stack LOCAL del motor de examen y lo deja listo para Ktor. Sin secretos de produccion: todas las
// claves se generan aqui, en ops/local-exam/.local/ (gitignored). Nunca toca MAIN ni ningun proyecto de Supabase.
//
//   node ops/local-exam/up.mjs            crea o reutiliza el stack (los datos persisten en el volumen del contenedor)
//   node ops/local-exam/up.mjs --reset    lo destruye (docker compose down -v) y lo vuelve a crear desde cero
//
// Salida: .local/compose.env (compose), .local/ktor.env (Ktor), .local/tokens.json (JWT de A, B y P), .local/jwks.json.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const local = path.join(here, '.local');
const GATEWAY = 'http://localhost:54321';
const reset = process.argv.includes('--reset');
fs.mkdirSync(local, { recursive: true });

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const hs256 = (payload, secret) => {
  const head = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify(payload));
  return `${head}.${body}.${b64url(crypto.createHmac('sha256', secret).update(`${head}.${body}`).digest())}`;
};

// ---------- claves locales (se generan una vez) ----------
const secretsFile = path.join(local, 'secrets.json');
let secrets = fs.existsSync(secretsFile) && !reset ? JSON.parse(fs.readFileSync(secretsFile, 'utf8')) : null;
if (!secrets) {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwtSecret = crypto.randomBytes(32).toString('hex');
  const now = Math.floor(Date.now() / 1000);
  secrets = {
    jwtSecret,
    anonKey: hs256({ role: 'anon', iss: 'oposi-exam-local', iat: now, exp: now + 10 * 365 * 86400 }, jwtSecret),
    serviceKey: hs256({ role: 'service_role', iss: 'oposi-exam-local', iat: now, exp: now + 10 * 365 * 86400 }, jwtSecret),
    rateLimitSecret: crypto.randomBytes(24).toString('hex'),
    metricsToken: crypto.randomBytes(16).toString('hex'),
    kid: 'oposi-exam-local-' + crypto.randomBytes(4).toString('hex'),
    privateJwk: privateKey.export({ format: 'jwk' }),
    publicJwk: publicKey.export({ format: 'jwk' }),
  };
  fs.writeFileSync(secretsFile, JSON.stringify(secrets, null, 2));
}
fs.writeFileSync(path.join(local, 'jwks.json'), JSON.stringify({ keys: [{ ...secrets.publicJwk, kid: secrets.kid, alg: 'ES256', use: 'sig' }] }));
fs.writeFileSync(path.join(local, 'compose.env'),
  `LOCAL_JWT_SECRET=${secrets.jwtSecret}\nLOCAL_ANON_KEY=${secrets.anonKey}\nLOCAL_SERVICE_KEY=${secrets.serviceKey}\n`);

const compose = (...args) => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'), ...args], { stdio: 'inherit' });
const psql = (sql, vars = {}, user = 'postgres') => execFileSync('docker', ['compose', '--env-file', path.join(local, 'compose.env'), '-f', path.join(here, 'docker-compose.yaml'),
  'exec', '-T', 'db', 'psql', '-U', user, '-h', 'localhost', '-d', 'postgres', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-At',
  ...Object.entries(vars).flatMap(([k, v]) => ['-v', `${k}=${v}`]), '-f', '-'], { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'inherit'] }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (reset) compose('down', '-v', '--remove-orphans');
compose('up', '-d', '--wait', 'db');
for (let i = 0; i < 60; i++) {
  try { if (psql("select count(*) from pg_roles where rolname in ('anon','authenticated','service_role','authenticator','supabase_storage_admin')") === '5') break; } catch { /* arrancando */ }
  await sleep(2000);
}

// ---------- esquema: forma de MAIN + objetos fuera del subconjunto + migraciones pendientes + fixtures ----------
const loaded = psql("select to_regclass('public.revisiones_pregunta') is not null") === 't';
if (!loaded) {
  console.log('loading MAIN shape subset, overlay, pending migrations and fixtures ...');
  psql("alter role authenticator with password 'postgres'; alter role supabase_storage_admin with password 'postgres';", {}, 'supabase_admin');
  psql('begin;\n' + fs.readFileSync(path.join(repo, 'supabase/baseline/main_shape_subset.sql'), 'utf8') + '\ncommit;');
  psql(fs.readFileSync(path.join(here, 'sql/00_overlay_main_objects.sql'), 'utf8'));
  const pending = fs.readFileSync(path.join(repo, 'supabase/baseline/PENDING_MIGRATIONS.txt'), 'utf8')
    .split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  for (const v of pending) {
    process.stdout.write(`  migration ${v}\n`);
    psql(fs.readFileSync(path.join(repo, 'supabase/migrations', `${v}.sql`), 'utf8'));
  }
}
const fixtures = psql("select count(*) from public.recursos_pregunta where id = '5e000000-0000-4000-8000-000000000004'") === '1';
if (!fixtures) {
  psql(fs.readFileSync(path.join(here, 'sql/90_fixtures.sql'), 'utf8'));
  // Supabase da a service_role todos los privilegios por defecto; el overlay no sustituye grants de MAIN.
  psql('notify pgrst, \'reload schema\';');
}
// PHASE B (E5-B01): cartera y derechos. Ambos ficheros son idempotentes y aditivos: se aplican siempre, tambien sobre un
// stack ya creado, sin reset.
psql(fs.readFileSync(path.join(here, 'sql/01_overlay_wallet_entitlement.sql'), 'utf8'));
psql(fs.readFileSync(path.join(here, 'sql/02_overlay_idempotency_finalize.sql'), 'utf8'));
psql(fs.readFileSync(path.join(here, 'sql/92_phase_b_wallet_entitlement.sql'), 'utf8'));
// E2-P03: la migracion pendiente 20261017100000 solo reescribe cuatro politicas (ALTER POLICY, idempotente): tambien se
// aplica sobre un stack creado antes de ella. En un stack nuevo ya la aplico el bucle de pendientes.
psql(fs.readFileSync(path.join(repo, 'supabase/migrations/20261017100000_security_exam_publication_time.sql'), 'utf8'));
psql(fs.readFileSync(path.join(here, 'sql/93_phase_b_scheduled_exam.sql'), 'utf8'));
psql('notify pgrst, \'reload schema\';');

compose('up', '-d', 'rest', 'storage', 'gateway');
const service = { apikey: secrets.serviceKey, Authorization: `Bearer ${secrets.serviceKey}` };
for (let i = 0; i < 90; i++) {
  try {
    const r = await fetch(`${GATEWAY}/storage/v1/bucket`, { headers: service });
    const t = await fetch(`${GATEWAY}/rest/v1/examenes?select=id&limit=1`, { headers: service });
    if (r.ok && t.ok) break;
  } catch { /* arrancando */ }
  await sleep(2000);
}

// ---------- Storage: bucket privado de Q3 + objetos de los recursos (el AUSENTE nunca se sube) ----------
await fetch(`${GATEWAY}/storage/v1/bucket`, {
  method: 'POST', headers: { ...service, 'Content-Type': 'application/json' },
  body: JSON.stringify({ id: 'recursos-pregunta', name: 'recursos-pregunta', public: false }),
});
// E5-A1: el PNG 1x1 que se subia aqui directamente estaba CORRUPTO (IDAT truncado). La imagen de EX_IMAGEN se crea ahora por
// la INGESTA (abajo, recurso 5e...0007) y la fila antigua 5e...0001 de stacks ya creados queda inactiva (las revisiones de
// intentos anteriores la conservan; las nuevas ya no la ven).
psql("update public.recursos_pregunta set activo = false where id = '5e000000-0000-4000-8000-000000000001' and activo");

// ---------- JWT ES256 de los usuarios de prueba (el JWKS lo sirve la pasarela en /auth/v1) ----------
const key = crypto.createPrivateKey({ key: secrets.privateJwk, format: 'jwk' });
// Mismos claims que un access token de GoTrue (email + app_metadata): el stub de /auth/v1/user (auth-stub.js) responde solo con ellos.
const es256 = (sub, email) => {
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: secrets.kid }));
  const body = b64url(JSON.stringify({ sub, email, app_metadata: { provider: 'email', providers: ['email'] }, role: 'authenticated', aud: 'authenticated', iss: `${GATEWAY}/auth/v1`, iat: now, exp: now + 12 * 3600 }));
  const sig = crypto.sign('sha256', Buffer.from(`${head}.${body}`), { key, dsaEncoding: 'ieee-p1363' });
  return `${head}.${body}.${b64url(sig)}`;
};
const tokens = { A: es256('0a000000-0000-4000-8000-00000000000a', 'a@exam.local'), B: es256('0b000000-0000-4000-8000-00000000000b', 'b@exam.local'),
  P: es256('0f000000-0000-4000-8000-00000000000f', 'p@exam.local') };
fs.writeFileSync(path.join(local, 'tokens.json'), JSON.stringify(tokens, null, 2));

fs.writeFileSync(path.join(local, 'ktor.env'), [
  'APP_ENV=local', 'SERVER_PROFILE=bot', 'HOST=127.0.0.1', 'PORT=8089', 'LOG_LEVEL=INFO',
  `SUPABASE_URL=${GATEWAY}`, `SUPABASE_SERVICE_ROLE_KEY=${secrets.serviceKey}`, `SUPABASE_ANON_KEY=${secrets.anonKey}`,
  'OPOSIBOTS_AUTH_ENABLED=true', 'AUTH_RATE_LIMIT_MODE=memory', `AUTH_RATE_LIMIT_HMAC_SECRET=${secrets.rateLimitSecret}`,
  'AUTH_CAPTCHA_REQUIRED=false', 'METRICS_ENABLED=true', `METRICS_SCRAPE_TOKEN=${secrets.metricsToken}`, '',
].join('\n'));

// ---------- recursos inmersivos por la INGESTA REAL (AdminV1Gateway.addRecursoArchivo, autorizada 2026-10-14) ----------
// audio (2 s), video (mp4 real hecho con el ffmpeg de la imagen local del backend, si existe) y audio LENTO (30 s, la
// pasarela lo sirve a 32 KB/s). La ingesta calcula durationMs + envelope del audio; nada de eso se escribe a mano.
// Imagen de 1200x800 (3:2, no cuadrada) generada aqui (sin descargas) y creada por la ingesta como el resto.
const required = ['5e000000-0000-4000-8000-000000000002', '5e000000-0000-4000-8000-000000000005', '5e000000-0000-4000-8000-000000000006', '5e000000-0000-4000-8000-000000000007'];
const ingested = psql(`select count(*) from public.recursos_pregunta where id in (${required.map((id) => `'${id}'`).join(', ')})`) === String(required.length);
if (!ingested) {
  fs.writeFileSync(path.join(local, 'image.png'), makePng(1200, 800));
  fs.writeFileSync(path.join(local, 'audio.wav'), makeWav(2000, (t) => (t < 500 ? 0.2 : 0.9)));
  fs.writeFileSync(path.join(local, 'slow.wav'), makeWav(30000, (t) => 0.3 + 0.6 * Math.abs(Math.sin(t / 700))));
  makeVideo(path.join(local, 'video.mp4'));
  const windows = process.platform === 'win32';
  execFileSync(path.join(repo, windows ? 'gradlew.bat' : 'gradlew'),
    [':backend:ktor:test', '--tests', 'com.oposicontrol.backend.LocalExamStackIngestFixture', '--rerun', '-q', '--console=plain'],
    { cwd: repo, stdio: 'inherit', shell: windows, env: { ...process.env, LOCAL_EXAM_INGEST: local } });
  if (psql("select count(*) from public.recursos_pregunta where id = '5e000000-0000-4000-8000-000000000002' and metadatos ? 'envelope'") !== '1') {
    throw new Error('la ingesta local no dejo el audio con envolvente');
  }
}

console.log(`\nLOCAL STACK READY: gateway ${GATEWAY} (rest, storage, jwks), postgres 127.0.0.1:54329 (postgres/postgres)`);
console.log(`Ktor env: ${path.join(local, 'ktor.env')}   tokens: ${path.join(local, 'tokens.json')}`);

/** WAV mono 16 kHz de [ms] con un seno de 440 Hz y amplitud amp(tiempo en ms). */
function makeWav(ms, amp) {
  const rate = 16000, n = rate * ms / 1000;
  const data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const t = i * 1000 / rate;
    data.writeInt16LE(Math.round(amp(t) * Math.sin(2 * Math.PI * 440 * i / rate) * 32767), i * 2);
  }
  const header = Buffer.alloc(44);
  header.write('RIFF', 0); header.writeUInt32LE(36 + data.length, 4); header.write('WAVE', 8); header.write('fmt ', 12);
  header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22); header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * 2, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34); header.write('data', 36); header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

/** mp4 H.264 de 2 s con el ffmpeg de una imagen LOCAL del backend (ops/deploy/Dockerfile.backend lo instala). Sin imagen: sin video. */
function makeVideo(target) {
  const image = execFileSync('docker', ['images', '--format', '{{.Repository}}:{{.Tag}}', 'oposicontrol-backend'], { encoding: 'utf8' }).split(/\r?\n/).find(Boolean);
  if (!image) { console.log('  sin imagen local oposicontrol-backend: fixture de VIDEO omitido'); return; }
  const mp4 = execFileSync('docker', ['run', '--rm', '--entrypoint', 'ffmpeg', image, '-hide_banner', '-loglevel', 'error',
    '-f', 'lavfi', '-i', 'testsrc=duration=2:size=160x120:rate=10', '-pix_fmt', 'yuv420p', '-c:v', 'libx264',
    '-movflags', 'frag_keyframe+empty_moov', '-f', 'mp4', 'pipe:1'], { maxBuffer: 64 * 1024 * 1024 });
  fs.writeFileSync(target, mp4);
}

/**
 * PNG RGB de [w]x[h] (por defecto 1200x800, 3:2): un "plano" con papel con grano, cuadricula, muros, mobiliario y un
 * recorrido. Determinista (PRNG con semilla fija), chunks con CRC32 reales y IDAT deflate: decodificable por cualquier
 * visor (Android BitmapFactory incluido). E5-A1: sustituye al PNG 1x1 corrupto anterior.
 */
function makePng(w = 1200, h = 800) {
  let seed = 20261014;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296);
  const px = Buffer.alloc(w * h * 3);
  const set = (x, y, r, g, b) => { if (x < 0 || y < 0 || x >= w || y >= h) return; const i = (y * w + x) * 3; px[i] = r; px[i + 1] = g; px[i + 2] = b; };
  const rect = (x0, y0, x1, y1, c) => { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) set(x, y, ...c); };
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const n = (((x >> 2) * 7 + (y >> 2) * 13) % 5) + (rnd() < 0.02 ? 8 : 0);
    set(x, y, 236 - n, 232 - n, 220 - n);                                   // papel con grano
    if (x % 40 === 0 || y % 40 === 0) set(x, y, 190, 205, 225);              // cuadricula
  }
  const wall = [45, 45, 52];
  rect(80, 80, 1120, 92, wall); rect(80, 708, 1120, 720, wall); rect(80, 80, 92, 720, wall); rect(1108, 80, 1120, 720, wall);
  rect(480, 92, 492, 420, wall); rect(480, 500, 492, 708, wall); rect(92, 400, 380, 412, wall); rect(760, 300, 1108, 312, wall);
  rect(140, 140, 360, 260, [176, 132, 92]); rect(560, 160, 700, 240, [96, 140, 176]); rect(820, 380, 1040, 640, [120, 170, 120]);
  rect(160, 480, 400, 560, [200, 170, 120]); rect(600, 560, 700, 660, [150, 110, 160]);
  for (let t = 0; t < 1; t += 0.0005) {                                     // recorrido de evacuacion
    const x = Math.round(240 + 760 * t), y = Math.round(600 - 400 * Math.sin(Math.PI * t));
    rect(x - 3, y - 3, x + 3, y + 3, [210, 40, 40]);
  }
  for (let a = 0; a < 2 * Math.PI; a += 0.002) {
    for (let r = 26; r < 34; r++) set(Math.round(1000 + r * Math.cos(a)), Math.round(200 + r * Math.sin(a)), 210, 40, 40);
  }
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; px.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3); }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(Buffer.concat([Buffer.from(type), data])) >>> 0);
    return Buffer.concat([len, Buffer.from(type), data, crc]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
