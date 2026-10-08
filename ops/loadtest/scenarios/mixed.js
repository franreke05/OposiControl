// MIXED: ~85% reads, ~15% full attempts (writes). The write part needs a DISPOSABLE database with seeded fixtures.
// Set MIXED_WRITE_RATIO=0 for a read-only mixed run (then no write guard is required).
import { guardInit, loadFixtures, userFor, think, summaryTo } from '../lib/common.js';
import { buildOptions } from '../lib/profiles.js';
import * as f from '../lib/flows.js';

const writeRatio = __ENV.MIXED_WRITE_RATIO ? Number(__ENV.MIXED_WRITE_RATIO) : 0.15;
guardInit({ writes: writeRatio > 0 });
const fixtures = loadFixtures();

export const options = buildOptions();

export default function () {
  const user = userFor(fixtures);
  if (Math.random() < writeRatio) {
    f.fullAttempt(user, fixtures, { maxAnswers: 5 });
  } else {
    const r = Math.random();
    if (r < 0.30) f.listExams(user);
    else if (r < 0.50) f.examDetail(user, fixtures);
    else if (r < 0.65) f.myExams(user);
    else if (r < 0.75) f.entitlement(user);
    else if (r < 0.85) f.me(user);
    else if (r < 0.93) f.attemptState(user);
    else f.attemptResult(user);
  }
  think(0.5, 2);
}

export function handleSummary(data) {
  return summaryTo(data, 'mixed');
}
