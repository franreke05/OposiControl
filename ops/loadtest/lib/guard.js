// Production-host guard. Imported (and executed) by EVERY scenario at k6 init time, before any VU runs.
// k6 aborts the run if an init-context exception is thrown, so no request is ever sent.

export const BLOCKED_HOST_PATTERNS = [
  /(^|\.)supabase\.co$/i,
  /(^|\.)supabase\.in$/i,
  /(^|\.)supabase\.net$/i,
  /^api\.oposibots\.es$/i,
  /(^|\.)oposibots\.es$/i,
  /(^|\.)oposicontrol\.(es|com|app)$/i,
];

export function hostOf(url) {
  const m = /^[a-z][a-z0-9+.-]*:\/\/(?:[^/@]*@)?(\[[^\]]+\]|[^/:?#]+)/i.exec(String(url || '').trim());
  return m ? m[1].toLowerCase() : '';
}

export function isBlockedHost(host) {
  return BLOCKED_HOST_PATTERNS.some((re) => re.test(host));
}

// `writes` = the scenario creates attempts / answers (idempotency rows, attempts). Those NEVER run against a blocked
// host, not even with the override: they need a disposable test project/branch database with seeded fixtures.
export function assertSafeTarget(baseUrl, { writes = false } = {}) {
  const host = hostOf(baseUrl);
  if (!host) {
    throw new Error(`LOADTEST GUARD: BASE_URL "${baseUrl}" is empty or not an absolute URL. Refusing to run.`);
  }
  if (isBlockedHost(host)) {
    if (writes) {
      throw new Error(
        `LOADTEST GUARD: write scenarios never target "${host}" (production/MAIN host), override or not. ` +
          'Use a disposable test project or branch database with seeded fixtures.',
      );
    }
    if (String(__ENV.I_UNDERSTAND_THIS_IS_NOT_PRODUCTION || '') !== '1') {
      throw new Error(
        `LOADTEST GUARD: BASE_URL host "${host}" looks like production/Supabase. Refusing to run. ` +
          'Set I_UNDERSTAND_THIS_IS_NOT_PRODUCTION=1 only if this host is really a staging stack backed by a disposable database.',
      );
    }
  }
  if (writes && String(__ENV.LOADTEST_ALLOW_WRITES || '') !== '1') {
    throw new Error('LOADTEST GUARD: write scenario needs LOADTEST_ALLOW_WRITES=1 (disposable database with seeded fixtures only).');
  }
  return host;
}
