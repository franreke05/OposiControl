// AUTH: POST /v1/auth/login, POST /v1/auth/refresh, GET /v1/auth/me.
// The rate limiter (OposiBotsAuthRateLimiter: LOGIN ip 30/300s, subject 8/300s, LOGIN_ACCOUNT, REFRESH ip 60/300s ...) answers
// 429 -- and 503 when Valkey fails closed -- BY DESIGN. This scenario MEASURES that (rate_limited, status_503); it does not
// try to evade it (no IP spoofing, no header tricks). Use it to learn how the limiter + GoTrue behave, not to claim capacity.
// Needs fixtures.users[i] = { email, password, refreshToken?, token? } of TEST accounts on a disposable GoTrue.
import { guardInit, loadFixtures, userFor, call, ok, json, think, summaryTo } from '../lib/common.js';
import { buildOptions } from '../lib/profiles.js';
import { Rate } from 'k6/metrics';

guardInit({ writes: false });
const fixtures = loadFixtures();

const loginAllowed = new Rate('login_allowed');

export const options = buildOptions();

export default function () {
  const user = userFor(fixtures);
  const login = call('POST', '/v1/auth/login', {
    kind: 'auth',
    body: { email: user.email, password: user.password },
    name: 'POST /v1/auth/login',
  });
  loginAllowed.add(login.status === 200);
  const session = login.status === 200 ? json(login) : null;

  if (session && session.accessToken) {
    ok(call('GET', '/v1/auth/me', { token: session.accessToken, kind: 'auth', name: 'GET /v1/auth/me' }), 'auth me');
    const refresh = call('POST', '/v1/auth/refresh', {
      kind: 'auth',
      body: { refreshToken: session.refreshToken },
      name: 'POST /v1/auth/refresh',
    });
    ok(refresh, 'refresh');
  } else if (user.refreshToken) {
    // Login throttled/failed: still exercise refresh with a pre-minted token (REFRESH has its own bucket).
    call('POST', '/v1/auth/refresh', { kind: 'auth', body: { refreshToken: user.refreshToken }, name: 'POST /v1/auth/refresh' });
  }
  think(1, 3);
}

export function handleSummary(data) {
  return summaryTo(data, 'auth');
}
