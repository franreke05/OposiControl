// READ-HEAVY: catalog list/detail, my exams, entitlement, attempt state, questions, result.
// Reads only. Safe against any disposable stack; still refuses production hosts (lib/guard.js).
import { guardInit, loadFixtures, userFor, think, summaryTo } from '../lib/common.js';
import { buildOptions } from '../lib/profiles.js';
import * as f from '../lib/flows.js';

guardInit({ writes: false });
const fixtures = loadFixtures();

export const options = buildOptions();

export default function () {
  const user = userFor(fixtures);
  const r = Math.random();
  if (r < 0.30) f.listExams(user);
  else if (r < 0.50) f.examDetail(user, fixtures);
  else if (r < 0.62) f.myExams(user);
  else if (r < 0.72) f.entitlement(user);
  else if (r < 0.82) f.attemptState(user);
  else if (r < 0.92) f.attemptQuestions(user);
  else f.attemptResult(user);
  think(0.5, 2);
}

export function handleSummary(data) {
  return summaryTo(data, 'read_heavy');
}
