// Consumer (bot profile) flows. Routes and payloads follow ops/inventory/routes.bot.txt and openapi/{catalog,attempts,entitlements}.
// NOTHING here touches /api/v1/admin, /content, /admin, import or authoring routes.
import { call, ok, json, pick, think, uuidv4 } from './common.js';

const APP = '/api/v1/app';

export function listExams(user) {
  const res = call('GET', `${APP}/examenes?limit=20`, { token: user.token, name: `GET ${APP}/examenes` });
  ok(res, 'list exams');
  return json(res);
}

export function examDetail(user, fixtures) {
  const id = pick(fixtures.examIds);
  ok(call('GET', `${APP}/examenes/${id}`, { token: user.token, name: `GET ${APP}/examenes/{}` }), 'exam detail');
}

export function myExams(user) {
  const view = pick(['EN_CURSO', 'ADQUIRIDOS', 'COLECCION']);
  ok(call('GET', `${APP}/me/examenes?vista=${view}&limit=20`, { token: user.token, name: `GET ${APP}/me/examenes` }), 'my exams');
}

export function entitlement(user) {
  ok(call('GET', `${APP}/me/entitlement`, { token: user.token, name: `GET ${APP}/me/entitlement` }), 'entitlement');
}

export function me(user) {
  ok(call('GET', `${APP}/me`, { token: user.token, name: `GET ${APP}/me` }), 'me');
}

// Needs fixtures: user.openAttemptId (in progress) and user.finishedAttemptId (finalizado), both OWNED by that user.
export function attemptState(user) {
  if (!user.openAttemptId) return;
  ok(call('GET', `${APP}/intentos/${user.openAttemptId}`, { token: user.token, name: `GET ${APP}/intentos/{}` }), 'attempt state');
}

export function attemptQuestions(user) {
  if (!user.openAttemptId) return;
  ok(
    call('GET', `${APP}/intentos/${user.openAttemptId}/preguntas?limit=10`, {
      token: user.token,
      name: `GET ${APP}/intentos/{}/preguntas`,
    }),
    'attempt questions',
  );
}

export function attemptResult(user) {
  if (!user.finishedAttemptId) return;
  ok(
    call('GET', `${APP}/intentos/${user.finishedAttemptId}/resultado`, { token: user.token, name: `GET ${APP}/intentos/{}/resultado` }),
    'attempt result',
  );
}

export function attemptReview(user) {
  if (!user.finishedAttemptId) return;
  ok(
    call('GET', `${APP}/intentos/${user.finishedAttemptId}/revision?limit=10`, {
      token: user.token,
      name: `GET ${APP}/intentos/{}/revision`,
    }),
    'attempt review',
  );
}

// One full attempt: start (Idempotency-Key) -> read question pages -> answer each -> finish. WRITES.
export function fullAttempt(user, fixtures, opts) {
  const o = opts || {};
  const maxAnswers = o.maxAnswers || Number(__ENV.ANSWERS_PER_ATTEMPT || 10);
  const examId = pick(fixtures.writableExamIds || fixtures.examIds);

  const start = call('POST', `${APP}/examenes/${examId}/intentos`, {
    token: user.token,
    kind: 'write',
    body: {},
    headers: { 'Idempotency-Key': uuidv4() },
    name: `POST ${APP}/examenes/{}/intentos`,
  });
  if (!ok(start, 'start attempt')) return;
  const attemptId = (json(start) || {}).intento && json(start).intento.id;
  if (!attemptId) return;

  const page = call('GET', `${APP}/intentos/${attemptId}/preguntas?limit=${Math.min(maxAnswers, 20)}`, {
    token: user.token,
    name: `GET ${APP}/intentos/{}/preguntas`,
  });
  if (!ok(page, 'attempt questions')) return;
  const items = ((json(page) || {}).items || []).slice(0, maxAnswers);

  for (const q of items) {
    const option = q.opciones && q.opciones.length > 0 ? pick(q.opciones).id : null;
    ok(
      call('PUT', `${APP}/intentos/${attemptId}/respuestas/${q.preguntaId}`, {
        token: user.token,
        kind: 'write',
        body: { opcionSeleccionadaId: option, tiempoRespuestaMs: 1500, baseRevision: 0 },
        headers: { 'Idempotency-Key': uuidv4() },
        name: `PUT ${APP}/intentos/{}/respuestas/{}`,
      }),
      'answer',
    );
    think(0.2, 1.0);
  }

  ok(
    call('POST', `${APP}/intentos/${attemptId}/finalizar`, {
      token: user.token,
      kind: 'write',
      body: {},
      headers: { 'Idempotency-Key': uuidv4() },
      name: `POST ${APP}/intentos/{}/finalizar`,
    }),
    'finish attempt',
  );
  ok(
    call('GET', `${APP}/intentos/${attemptId}/resultado`, { token: user.token, name: `GET ${APP}/intentos/{}/resultado` }),
    'attempt result',
  );
}
