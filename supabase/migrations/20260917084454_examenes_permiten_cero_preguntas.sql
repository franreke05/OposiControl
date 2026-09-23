-- Un examen sin publicar puede estar vacío (2026-09-14; aplicada 2026-09-17).
--
-- Regla de producto, dicha por el propietario: «el examen, si no está publicado,
-- puede estar vacío». Un borrador recién creado no tiene preguntas todavía —se
-- vinculan después, desde el banco— así que 0 es su recuento verdadero.
--
-- ## Qué impedía decirlo
--
-- `examenes_total_preguntas_check` exige `> 0`. Verificado contra la base viva el
-- 2026-09-14 con inserciones de prueba borradas acto seguido:
--   total_preguntas = 1   -> 201
--   total_preguntas = 0   -> 400, SQLSTATE 23514, examenes_total_preguntas_check
--   total_preguntas = -1  -> 400, mismo constraint
--
-- Por eso `createExamen` manda hoy un `1` que es mentira: una pregunta que no
-- existe. El `0` honesto no cabía en el esquema.
--
-- ## Qué hace esta migración
--
-- Sustituye la restricción por `>= 0`. Sigue prohibiendo los negativos, que no
-- significan nada, y deja de prohibir el cero, que significa «todavía vacío».
--
-- NO se toca la publicación: que un examen vacío no deba llegar al catálogo es una
-- regla distinta y vive en otro sitio (el rail «Antes de publicar» del editor ya
-- avisa de que no hay preguntas vinculadas). Esta migración no afloja esa puerta,
-- sólo deja de mentir sobre el estado de un borrador.
--
-- `lock_timeout`: la DDL toma ACCESS EXCLUSIVE sobre `examenes`; si tuviera que
-- esperar tras una transacción larga, bloquearía todas las lecturas de la tabla.
-- Mejor fallar a los 5 s y reintentar (revisión independiente, Fase 0).
--
-- ## Rollback — con condición
--
-- Sólo si `select count(*) from public.examenes where total_preguntas = 0` = 0.
-- Un CHECK `> 0` (aunque sea NOT VALID) se evalúa en cada UPDATE de la fila, así
-- que con exámenes a 0 bloquearía publicar, archivar y editar esos exámenes:
--   alter table public.examenes drop constraint examenes_total_preguntas_check;
--   alter table public.examenes add constraint examenes_total_preguntas_check
--     check (total_preguntas > 0) not valid;
-- Revertir antes el código Kotlin a no-cero.
--
-- ## DESPUÉS de aplicarla
--
-- Hay que devolver `createExamen` al 0 en
-- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt`
-- (busca «totalPreguntas == null»), y el test
-- `AdminV1ExamQuestionsTest.creatingAnExamSendsTheMinimumQuestionCountTheLiveCheckAccepts`
-- vuelve a afirmar 0 y recupera su nombre anterior. El propio test lo dice en su
-- KDoc: es el sitio donde volver a poner el 0 cuando esto se relaje.
--
-- El orden importa: primero esta migración, después el cambio de código. Al revés,
-- toda creación de examen vuelve a fallar.

set local lock_timeout = '5s';

alter table public.examenes drop constraint if exists examenes_total_preguntas_check;

alter table public.examenes add constraint examenes_total_preguntas_check
  check (total_preguntas >= 0);
