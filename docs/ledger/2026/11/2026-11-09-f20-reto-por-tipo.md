# F20 — Reto diario por tipo de examen (2026-11-09)

Rama local `f20/reto-per-type` desde develop `920fa29b` (desplegado hoy). Sin push. MAIN/VPS sin tocar.

## Decisión del propietario (2026-11-09)

El reto diario toma sus preguntas del banco de los exámenes PUBLICADOS de la especialidad del usuario: UNA de exámenes inmersivos, UNA de razonados, UNA de ráfaga. Hoy producción solo tiene ráfaga: debe funcionar con una sola pregunta; los otros huecos se llenan solos cuando existan esos exámenes.

## Antes (comportamiento de producción hoy)

- `private.reto_pool` filtraba por tipo de PREGUNTA y, en el hueco inmersivo, solo preguntas con la marca demo explícita de Control (`preguntas_examen.es_demo`).
- `private.ensure_challenge_row` exigía los TRES huecos: si faltaba uno, no creaba fila. Con solo ráfaga en producción no se crea ninguna fila: `GET /api/v1/app/retos/hoy` responde `NO_DISPONIBLE` a todos y la racha no avanza (los días sin reto tampoco la rompen).

## Después (migración `20261109100000_v1_reto_por_tipo`)

- Pool de un hueco = preguntas activas de exámenes activos y publicados (`publicado_en <= now`) de la especialidad, del `tipo_examen` del hueco, principales (no reserva) y no anuladas; sin marca demo; regla regional igual.
- Una pregunta por hueco con pool, elegida de forma determinista por día y segmento (`md5(fecha|especialidad|comunidad|hueco|pregunta)` mínimo); huecos vacíos se saltan; posiciones compactas 1..n (inmersiva, razonada, ráfaga); sin ningún hueco = sin fila (`NO_DISPONIBLE`).
- Regla de superación: `objetivo_preguntas = n`, `aciertos_necesarios = ceil(2n/3)` (3→2 como antes, 2→2, 1→1). La respuesta n-ésima cierra el reto. Racha y recompensa sin cambios (un reto superado = un día de racha y los importes del plan, sea cual sea n).
- Sin cambios: una fila por día y segmento, fila congelada por usuario y día de Madrid, ventana hasta la medianoche local (DST), primera apertura sin cerrojo global, respuestas finales comprobadas en servidor, sin corrección antes de responder, reclamación y tope, cadena de recursos, intentos y ranking.
- Contrato: forma sin cambios. FA46 solo texto en `openapi/engagement/engagement.yaml` (cabecera, D3-17, `necesarios`); manifiesto de congelación actualizado. Registro en CONTRACT_FREEZE.md (repo OposiBot): PENDING_OWNER.
- Cliente OposiBot (`feature/challenge/**`, `app/ChallengeFlow.kt`, rama INT): ya es dinámico (número de preguntas, `necesarios`, avance por lista). Solo copy: con 1 pregunta el panel de racha dice "al menos 1 de las 1 preguntas" (plural fijo).

## Anexo al runbook (aplicación a MAIN, la hace el propietario)

1. Requisito: la ventana 2026-11 (`20261108100000..100500`) aplicada y registrada en MAIN (predecesora `20261108100500`).
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f20.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261109100000_v1_reto_por_tipo.sql` (teclear la versión). Debe terminar en `aplicada, VERIFY = 0, registrada`.
3. Imagen: NO hace falta. El Ktor llama a las mismas funciones (`app_get_daily_challenge`, `app_answer_daily_challenge`, `app_generate_daily_challenges`) y sirve el cuerpo tal cual. Efecto inmediato: el siguiente `GET /retos/hoy` de cada usuario crea la fila del día (reto de 1 pregunta ráfaga hoy).
4. Rollback: bloque ROLLBACK de la migración (vuelven los cuerpos de 101300/103500; las filas ya creadas se quedan) y `delete from supabase_migrations.schema_migrations where version = '20261109100000'`.

## Verificación

Ver la sección de resultados del informe de F20 (dbtest `V1RetoPorTipoMigrationTest`, Ktor `ChallengeRoutesTest`/OpenAPI, gate PG17, ensayo `docs/ledger/2026/11/rehearse-f20-2026-11.sh`).
