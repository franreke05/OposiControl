import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import { assertSafeTarget } from './guard.js';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

// Custom metrics. `app_errors` = real failures (5xx other than the by-design 503, plus transport errors).
// 429 and 503 are NOT errors here: they are measured separately ("measure the limiter, do not fight it").
export const appErrors = new Rate('app_errors');
export const rateLimited = new Rate('rate_limited');
export const unavailable503 = new Counter('status_503');
export const serverErrors = new Counter('server_errors_5xx');

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 400, 401, 404, 409, 422, 429, 503));

export function guardInit(opts) {
  return assertSafeTarget(BASE_URL, opts);
}

// ---- fixtures (never committed; see fixtures/fixtures.example.json) -----------------------------------------------
export function loadFixtures() {
  const path = __ENV.FIXTURES_FILE || '../fixtures/fixtures.json';
  const data = JSON.parse(open(path));
  if (!data.users || data.users.length === 0) throw new Error(`fixtures ${path}: "users" is empty`);
  return data;
}

// VU i uses user (i-1) % N. If VUs > users the per-user limiter (429) WILL fire: that is data, not a bug.
export function userFor(fixtures) {
  return fixtures.users[(__VU - 1) % fixtures.users.length];
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function uuidv4() {
  const hex = '0123456789abcdef';
  let out = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) out += '-';
    else if (i === 14) out += '4';
    else if (i === 19) out += hex[8 + Math.floor(Math.random() * 4)];
    else out += hex[Math.floor(Math.random() * 16)];
  }
  return out;
}

export function think(minS, maxS) {
  const lo = minS === undefined ? 0.5 : minS;
  const hi = maxS === undefined ? 2 : maxS;
  sleep(lo + Math.random() * (hi - lo));
}

// ---- requests -----------------------------------------------------------------------------------------------------
// `name` is the ROUTE TEMPLATE (e.g. "GET /api/v1/app/intentos/{}"), never the concrete URL -> bounded tag cardinality.
// Tags used: name (bounded by route count), kind (read|write|auth). No user id, no uuid, no URL tags.
function record(res) {
  const s = res.status;
  rateLimited.add(s === 429);
  if (s === 503) unavailable503.add(1);
  if (s >= 500) serverErrors.add(1);
  appErrors.add(s === 0 || (s >= 500 && s !== 503));
  return res;
}

export function call(method, path, opts) {
  const o = opts || {};
  const headers = Object.assign({ Accept: 'application/json' }, o.headers || {});
  if (o.token) headers.Authorization = `Bearer ${o.token}`;
  let payload = null;
  if (o.body !== undefined && o.body !== null) {
    payload = typeof o.body === 'string' ? o.body : JSON.stringify(o.body);
    headers['Content-Type'] = 'application/json';
  }
  const params = {
    headers,
    tags: { name: o.name || `${method} ${path}`, kind: o.kind || 'read' },
    timeout: __ENV.REQUEST_TIMEOUT || '30s',
  };
  return record(http.request(method, `${BASE_URL}${path}`, payload, params));
}

export function ok(res, label) {
  const checks = {};
  checks[`${label} 2xx`] = (r) => r.status >= 200 && r.status < 300;
  return check(res, checks);
}

export function json(res) {
  try {
    return res.json();
  } catch (_) {
    return null;
  }
}

// ---- summary ------------------------------------------------------------------------------------------------------
export function summaryTo(data, name) {
  const dir = __ENV.RESULTS_DIR || './results';
  const out = {};
  out[`${dir}/${name}-${__ENV.PROFILE || '100'}-${__ENV.RUN_ID || 'run'}.json`] = JSON.stringify(data, null, 2);
  out.stdout = textLine(data, name);
  return out;
}

function textLine(data, name) {
  const m = data.metrics;
  const d = m.http_req_duration ? m.http_req_duration.values : {};
  const f = (v) => (v === undefined ? 'n/a' : v.toFixed(1));
  const reqs = m.http_reqs ? m.http_reqs.values : {};
  const err = m.app_errors ? m.app_errors.values.rate : undefined;
  const lim = m.rate_limited ? m.rate_limited.values.rate : undefined;
  const pct = (v) => (v === undefined ? 'n/a' : (v * 100).toFixed(2) + '%');
  return (
    `\n[${name}] profile=${__ENV.PROFILE || '100'} reqs=${reqs.count} rps=${f(reqs.rate)} ` +
    `p50=${f(d.med)}ms p95=${f(d['p(95)'])}ms p99=${f(d['p(99)'])}ms ` +
    `app_errors=${pct(err)} rate_limited=${pct(lim)}\n`
  );
}
