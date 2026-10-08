// WRITE-HEAVY: start attempt -> answers -> finish, with Idempotency-Key on every command.
// WRITES rows (intentos_examen, respuestas_intento, idempotency_keys). Only against a DISPOSABLE test project / branch
// database with seeded fixtures. lib/guard.js refuses production/Supabase hosts for this scenario even with the override
// and requires LOADTEST_ALLOW_WRITES=1.
import { guardInit, loadFixtures, userFor, think, summaryTo } from '../lib/common.js';
import { buildOptions } from '../lib/profiles.js';
import { fullAttempt } from '../lib/flows.js';

guardInit({ writes: true });
const fixtures = loadFixtures();

export const options = buildOptions();

export default function () {
  fullAttempt(userFor(fixtures), fixtures, { maxAnswers: Number(__ENV.ANSWERS_PER_ATTEMPT || 10) });
  think(1, 3);
}

export function handleSummary(data) {
  return summaryTo(data, 'write_heavy');
}
