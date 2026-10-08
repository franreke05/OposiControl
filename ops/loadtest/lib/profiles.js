// Staged load profiles. VU targets are the PEAK; every profile = ramp-up, hold, ramp-down.
// Durations are parameters (RAMP_UP, HOLD, RAMP_DOWN) so a first run can be short.
export const LEVELS = [100, 500, 1000, 2000, 3000, 5000];

export function stagesFor(level) {
  const target = Number(level);
  if (!LEVELS.includes(target) && __ENV.ALLOW_CUSTOM_PROFILE !== '1') {
    throw new Error(`PROFILE must be one of ${LEVELS.join('/')} (got ${level}); ALLOW_CUSTOM_PROFILE=1 for smoke runs`);
  }
  const up = __ENV.RAMP_UP || '2m';
  const hold = __ENV.HOLD || '5m';
  const down = __ENV.RAMP_DOWN || '1m';
  return [
    { duration: up, target },
    { duration: hold, target },
    { duration: down, target: 0 },
  ];
}

export function buildOptions(extra = {}) {
  const level = __ENV.PROFILE || '100';
  const num = (name, dflt) => {
    const v = Number(__ENV[name]);
    return Number.isFinite(v) && v > 0 ? v : dflt;
  };
  // Thresholds are PARAMETERS, not promises: the defaults are only a starting hypothesis, edit them per run.
  const thresholds = {
    app_errors: [`rate<${num('THRESHOLD_ERROR_RATE', 0.01)}`],
    'http_req_duration{kind:read}': [`p(95)<${num('THRESHOLD_P95_MS', 800)}`, `p(99)<${num('THRESHOLD_P99_MS', 2000)}`],
    'http_req_duration{kind:write}': [
      `p(95)<${num('THRESHOLD_WRITE_P95_MS', 1200)}`,
      `p(99)<${num('THRESHOLD_WRITE_P99_MS', 3000)}`,
    ],
  };
  if (__ENV.NO_THRESHOLDS === '1') {
    for (const k of Object.keys(thresholds)) thresholds[k] = [];
  }
  return Object.assign(
    {
      scenarios: {
        staged: {
          executor: 'ramping-vus',
          startVUs: 0,
          stages: stagesFor(level),
          gracefulRampDown: '30s',
          gracefulStop: '30s',
        },
      },
      thresholds,
      summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
      // No per-URL series: request names are route templates (see lib/common.js `call`).
      systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    },
    extra,
  );
}
