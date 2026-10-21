# Re-auditoría de la candidata `candidate/v1-bot-ktor` (OposiBot)

- Fecha: 2026-10-21
- Auditor: OposiControl (revisión independiente, solo lectura; ningún repositorio editado, sin Docker ni Supabase)
- Revisado: HEAD `3bdf3fb` (último commit de código `baf4264`) en el worktree `OposiControl-Candidate`. Bot movió después la HEAD a `aa223df`, que solo cambia documentación (registra que su propietario aprueba la nueva redacción del paso 1b).
- Base: develop `8fecd02`. Ramas fusionadas: store `be9e0bd`, user-tables-revoke `11bae54`, qa-harness-fix `2e1dda6`, idor-sweep `b86dd42` y env-templates `aff478d`.

## Veredicto

**Apta para fusionar el panel encima, CON CONDICIONES** (abajo). Sin P0 ni P1. Hay dos P2: controles negativos de tienda sin ejecutar y cambios de contrato con el panel.

## Los 4 puntos que pidió Bot

| # | Punto | Veredicto | Notas |
|---|---|---|---|
| 1 | Paso 1b del gate: «una `_security_` de V1 sigue PENDING y debe estar listada» | ACEPTAR CON CAMBIOS | No reabre el hueco de DEBT-156b. Una `_security_` V1 debe estar en PENDING y pasa todo el gate (`db-pg17-gate.sh:40-45,48,55-59`); una no V1 queda en el paso 1b (VERIFY, ROLLBACK y dbtest, `:69-75`). El control negativo es `db-pg17-gate.test.sh:43-44`. Faltan: (a) un control para la rama `V1_MIGRATIONS.txt` de `is_v1`; (b) **P3**: cuando se recapture `MAIN_MIGRATION_HISTORY.txt` tras desplegar V1, `20261022100100` hará fallar el gate (`:42-45` la exige en PENDING y `:47` la excluye), así que hay que saltar en `:43` las versiones que ya estén en `$applied`; (c) la fila DEBT-156 de `TECH_DEBT.md:138` sigue diciendo que se prohíbe `_security_` en V1; (d) confirmación del propietario (Bot dice tenerla, `aa223df`). |
| 2 | Escenario (f) de `db-pg17-gate.test.sh` | Diagnóstico CORRECTO; propuesta ACEPTAR CON CAMBIOS | La mutación toca la sobrecarga de 2 argumentos de `app_congelar_preguntas_intento` (`100000:334/373`), que está muerta desde `101000:145/336/714`; la viva es la de 3 argumentos (`101300:820`, insert en `:894-895`). Mutar `101300:894-895` (quitar `revision_pregunta_id` y `r.id`, con el texto exacto y manteniendo `mutated`) conserva la intención: falla `assertions.sql:265`. **P2:** como el script sale en (f), **(g)/(g2)/(g3) y (h), los controles negativos de las carreras de tienda R1-R4 y D1, no se han ejecutado en la candidata.** |
| 3 | `WALLET.local_schema_matches_main` en rojo | ACEPTAR como diferencia esperada y acotada | Solo la explica `20261022102600_v1_store_payments.sql:307-323`: en `compras_usuario`, `entorno`, `tienda_producto_id` y `tienda_finalizada_en`, su check y el recorte de grants; en `suscripciones`, esas tres columnas más `version_evento`, su check, `ux_suscripciones_proveedor_externo` y el recorte de grants. El manifiesto se queda corto en `suscripciones`. Riesgo: un check en rojo permanente tapa cualquier deriva nueva. Recomendado: fijar el delta de 102600 en `main-wallet-shape.txt`, como se hizo en E5. Ktor solo LEE esas tablas, así que el recorte de grants no rompe nada. El diff exacto en ejecución queda NOT_VERIFIED. |
| 4 | DEBT-116 → DEBT-116-EXAM | ACEPTAR CON CAMBIOS | No hay referencias rotas. Pero `docs/audit-v1/INTEGRATION_CONFLICTS.md:49` (develop) acordó renumerarla a **DEBT-160** (el panel usa 157-159): hay que elegir uno y corregir el otro. |

## Regla de integración de la concesión de accesos: CUMPLE

En `backend/ktor/src/main` solo hay lecturas de `contenido_desbloqueado_usuario`. Conceder va por `rpc/admin_grant_exam_access` (`AdminV1Routes.kt:7431-7433`) y retirar por `rpc/admin_revoke_exam_access` (`:7463-7465`); las dos RPC solo se conceden a `service_role` (`20261022100700:120-121,161-162`). `AdminV1ExamAccessGrantTest` da 16/16, ejecutado por nosotros, y comprueba que no hay POST directo. Residuo Info: `ContenidoDesbloqueadoInsertRow` está definida y sin uso (`AdminV1Routes.kt:4092`); conviene borrarla.

## Hallazgos nuevos

| ID | Sev | Hallazgo |
|---|---|---|
| CAND-01 | P2 | Los controles negativos de tienda (g/h) no corren porque el test del gate sale en (f). Hasta arreglar (f), el rc=0 del gate no tiene su control negativo comprobado. |
| CAND-02 | P2 | Cambios de contrato en `/api/v1/admin/*` que el panel debe absorber: `POST /examenes` sin `comunidadId` da 422 `EXAM_COMMUNITY_REQUIRED` (`:429`); ya no se puede borrar la comunidad con PATCH (`:2000`); publicar exige comunidad (`:4442`); una comunidad inactiva da 422; `POST /accesos-examen` repetido responde 200 con `Idempotent-Replayed` (`:1429`); `razonamiento` llega con marcado FA38 (`:3166`, `:4733`). Lo verifican los E2E del panel sobre la candidata (sección del panel). |
| CAND-03 | P3 | El job del reto diario arranca con `!= CONTROL` (`Application.kt:538`), es decir, también en CONSUMER. No arranca en `control`; aun así, por coherencia conviene `serverProfile.mountsBot`. |
| CAND-04 | P3 | Orden de despliegue sin registrar: `20261023100200_security_revenue_excludes_sandbox` (manual) necesita `compras_usuario.entorno` de `20261022102600`. Su PREFLIGHT falla cerrado, pero el orden no está en `DEPLOYMENT.md` y el gate nunca aplica las dos juntas. Visto en vivo: en nuestro stack aislado, 100200 solo se aplicó después de 102600. |
| CAND-05 | P3 | Documentación: fila DEBT-156 desfasada y el choque DEBT-160 / DEBT-116-EXAM. |
| CAND-06 | Info | `POST /api/v1/app/feedback/pregunta` deja de ser legacy, `intentoId` pasa a obligatorio (`AppV1Routes.kt:1125`) y el límite se aplica antes de leer el cuerpo. Es contrato con la app, no con el panel. |
| CAND-07 | Info | `20261021100000:73` revoca `auditoria_log_append_only()` solo `from public` (preexistente en develop; el revoke de anon y authenticated estaba en `0b53da4`, que no es ancestro). Riesgo bajo: es una función de trigger. |

Sin secretos en los logs nuevos (`DailyChallengeJob.kt:23-26`). Las V1 no redefinen `es_admin()`, ni `*_admin_all`, ni `perfiles_usuario_update_propio`, ni conceden escritura a anon/authenticated, y no pisan 100000/100200/100300. La nueva ruta `POST /estadisticas/recalcular` está bajo autorización admin y el límite de mutaciones (`Application.kt:846-861`).

## Condiciones

1. Conceder siempre por RPC: al integrar, quedarse con el lado de la candidata y nunca con la inserción directa de develop o del panel. Ya se cumple en `integration/control-panel-on-candidate`.
2. Arreglar (f) mutando `101300:894-895` y ejecutar `db-pg17-gate.test.sh` hasta que pasen (g) y (h). Esto bloquea la fusión a main, no la integración local.
3. Que el panel absorba los cambios de contrato de CAND-02, con los E2E del panel en verde sobre la candidata.
4. Paso 1b: saltar las versiones aplicadas en MAIN, añadir el control de `V1_MIGRATIONS.txt` y actualizar DEBT-156; unificar el ID DEBT-160 / DEBT-116-EXAM.
5. Fijar el delta de tienda en `main-wallet-shape.txt`.
6. Registrar en `DEPLOYMENT.md` que 100200 va después de 102600.

## Sin verificar

Gate PG17 y `db-pg17-gate.test.sh` de la candidata (requieren Docker; los resultados son los que declara Bot), diff exacto de WALLET, e2e y journeys de Bot, dbtest y la suite Ktor completa de la candidata (solo se ejecutó `AdminV1ExamAccessGrantTest`) y el estado de MAIN.
