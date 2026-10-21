// EX16 / E5-I01 (SOLO stack LOCAL, decision del owner 2026-10-14): stub de `GET /auth/v1/user` de GoTrue para que la app
// real restaure la sesion contra el stack (Ktor llama a ese endpoint en `GET /v1/auth/me` y `GET /api/v1/app/me`).
// Responde UNICAMENTE con los claims de un JWT ES256 ya firmado por la clave local cuyo JWKS sirve esta misma pasarela
// (/srv/jwks.json): verifica firma, `kid`, `exp`, `iss` y `role`; no concede nada nuevo, no emite tokens, no tiene login,
// registro ni ninguna otra ruta de Auth. Lo ejecuta njs dentro del nginx de ops/local-exam (escucha solo en 127.0.0.1);
// nunca forma parte de la imagen de Ktor ni de produccion.
import fs from 'fs';

// the issuer carries the gateway port (stack-config.mjs); up.mjs writes it to <localDir>/issuer.txt, mounted at /srv/issuer.txt
const ISSUER = fs.readFileSync('/srv/issuer.txt').toString().trim();

// /auth/v1/* (except /user) -> the host GoTrue stub of THIS stack (port from stack-config.mjs); up.mjs writes the URL to .local/gotrue-stub-url.txt,
// mounted at /srv/gotrue-stub-url.txt. Read per request; if it is missing, a closed port (502, the same as a stopped stub).
function stubUrl() {
  try {
    return fs.readFileSync('/srv/gotrue-stub-url.txt').toString().trim();
  } catch (e) {
    return 'http://127.0.0.1:1';
  }
}

function b64url(s) {
  return Buffer.from(s, 'base64url');
}

function deny(r) {
  r.headersOut['Content-Type'] = 'application/json';
  r.return(401, JSON.stringify({ code: 401, error_code: 'bad_jwt', msg: 'invalid JWT' }));
}

async function user(r) {
  if (r.method !== 'GET') {
    r.return(405);
    return;
  }
  const m = /^Bearer ([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)$/.exec(r.headersIn.Authorization || '');
  if (!m) return deny(r);
  let header;
  let claims;
  try {
    header = JSON.parse(b64url(m[1]).toString());
    claims = JSON.parse(b64url(m[2]).toString());
  } catch (e) {
    return deny(r);
  }
  if (header.alg !== 'ES256') return deny(r);
  const jwk = JSON.parse(fs.readFileSync('/srv/jwks.json')).keys.find((k) => k.kid === header.kid);
  if (!jwk) return deny(r);
  let ok = false;
  try {
    const key = await crypto.subtle.importKey('jwk', { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y },
      { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    ok = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, b64url(m[3]), Buffer.from(m[1] + '.' + m[2]));
  } catch (e) {
    ok = false; // firma mal formada: nunca 5xx, siempre el mismo 401
  }
  const now = Math.floor(Date.now() / 1000);
  if (!ok || claims.iss !== ISSUER || claims.role !== 'authenticated' || typeof claims.sub !== 'string' ||
      typeof claims.exp !== 'number' || claims.exp <= now) {
    return deny(r);
  }
  // Solo lo que el token ya dice: id, email, proveedores y la fecha de emision (no hay "creado en" sin GoTrue).
  const app = claims.app_metadata || {};
  r.headersOut['Content-Type'] = 'application/json';
  r.headersOut['Cache-Control'] = 'no-store';
  r.return(200, JSON.stringify({
    id: claims.sub,
    aud: claims.aud,
    role: claims.role,
    email: claims.email || null,
    app_metadata: { provider: app.provider || null, providers: app.providers || [] },
    identities: (app.providers || []).map((p) => ({ provider: p })),
    created_at: typeof claims.iat === 'number' ? new Date(claims.iat * 1000).toISOString() : null,
  }));
}

export default { user, stubUrl };
