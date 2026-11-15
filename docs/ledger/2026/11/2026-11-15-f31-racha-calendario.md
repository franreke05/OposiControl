# F31 — Calendario de racha (FA56, D3-26) (2026-11-15)

Rama local `f31/racha-calendario` desde develop `39b5dd85` (produccion corre `b9a5c1e3`). Sin push. MAIN/VPS sin tocar.
Peticion del propietario: el panel "Manten tu racha" dibuja el calendario del mes de Figma (`calendar{racha activa=mes}`) y la API no daba historial de dias.

## Contrato (FA56, aditivo)

`GET /api/v1/app/racha/calendario?mes=YYYY-MM` (JWT; limite por usuario CATALOG_READ; `Cache-Control: private, no-store`).

- `mes` opcional; ausente = mes actual de Madrid. Permitido desde el mes del PRIMER reto del usuario (sin ningun reto: solo el mes actual) hasta el mes actual.
  Formato malo = 400 `VALIDATION` (issue `mes` `INVALID_FORMAT`); fuera de rango = 400 `VALIDATION` (issue `mes` `OUT_OF_RANGE`). Parametro desconocido = 400 `UNKNOWN_FIELD`.
- 200:

```json
{"mes":"2026-11","hoy":"2026-11-15","rachaActual":2,"rachaMaxima":7,"rachaMes":3,
 "dias":[{"fecha":"2026-11-01","estado":"SUPERADO"},{"fecha":"2026-11-02","estado":"PROTEGIDO"}]}
```

- `dias`: un elemento por dia desde el 1 hasta HOY inclusive, en orden; los dias futuros se omiten; un mes pasado lista todos.
- `estado`:
  - `SUPERADO` reto completado y superado.
  - `PROTEGIDO` completado y fallado, y la racha la salvo un protector (`protector_racha`, o `protector_dual` con la racha viva; la regla "viva" se recalcula con la misma funcion de la respuesta, `private.reto_streak_gap_free`).
  - `FALLADO` fallado sin protector de racha (un protector solo de tickets guarda la recompensa, no la racha), o un dia PASADO empezado (`progreso > 0`) y nunca terminado.
  - `PENDIENTE` hoy, sin terminar (el usuario tiene especialidad).
  - `SIN_ACTIVIDAD` sin fila ese dia, o una fila pasada sin ninguna respuesta definitiva; tambien hoy sin especialidad. Un valor desconocido = `SIN_ACTIVIDAD` en el cliente.
- `rachaActual` / `rachaMaxima` = los numeros de D3-04 (`private.reto_streak_json`). `rachaMes` = la racha mas larga DENTRO del mes (SUPERADO o PROTEGIDO; un dia sin reto disponible no la rompe; los dias del mes anterior no cuentan).
- Mapeo sugerido a Figma (decision del cliente): SUPERADO/PROTEGIDO de la racha viva = "racha"/"check"; hoy SUPERADO = "check-hoy"; FALLADO = "perdido"; SIN_ACTIVIDAD = "sin actividad"; PENDIENTE = hoy sin marcar.
- Limite: solo se leen filas V1 (`registros_reto_diario.dia`); filas legadas (`dia` NULL) y contadores de racha pre-V1 no se dibujan.

## Migracion `20261115100000_v1_racha_calendario` (solo lectura)

- `private.racha_calendario(uuid, date, timestamptz)` (cuerpo, reloj inyectable para la prueba de cambio de hora) y `public.app_racha_calendario(uuid, date default null)` (service_role, SECURITY DEFINER, `search_path = ''`, pasa `clock_timestamp()`).
- Usa el indice existente `ux_registros_reto_usuario_dia (usuario_id, dia)`. Sin tablas, sin escrituras, sin triggers.
- ROLLBACK: borra las dos funciones. VERIFY: 4 filas (service_role only, helper no ejecutable por clientes, SECURITY DEFINER + search_path, regla de racha compartida).
- Manifiesto `ops/deploy/v1/main-migrations-2026-11-f31.manifest`, predecesora `20261115000000` (hotfix servicios seccion, rama `hf/servicios-seccion`, va ANTES).

## Anexo al runbook (lo hace el propietario)

1. Requisito: `20261115000000` aplicada y registrada en MAIN.
2. `MANIFEST=ops/deploy/v1/main-migrations-2026-11-f31.manifest bash ops/deploy/v1/apply-main-migration.sh supabase/migrations/20261115100000_v1_racha_calendario.sql` -> `aplicada, VERIFY = 0, registrada`.
3. DESPUES, imagen nueva de Ktor (D3-26 llama a la funcion nueva; una imagen anterior no la llama).
4. Rollback: imagen anterior primero; luego el bloque ROLLBACK y `delete from supabase_migrations.schema_migrations where version = '20261115100000'`.

## Verificacion

- dbtest `V1RachaCalendarioMigrationTest` (5): mes con SUPERADO / FALLADO / PROTEGIDO (racha y dual con racha viva) / dual sin racha viva = FALLADO / tickets = FALLADO / empezado sin terminar = FALLADO / abierto sin respuesta = SIN_ACTIVIDAD / PENDIENTE; datos de otro usuario nunca visibles; rango de meses; frontera de mes de Madrid en los dos cambios de hora (2026-10-31 22:59:59Z / 23:00Z y 2027-03-31 21:59:59Z / 22:00Z); integracion con la respuesta real de 6 argumentos (protector_racha -> PROTEGIDO); sin especialidad; privilegios, VERIFY y ROLLBACK ida y vuelta. 5/5 verde.
- Ktor `ChallengeRoutesTest` (2 nuevas: cuerpo servido tal cual y conforme al OpenAPI, `p_mes`, identidad = `sub`, limite READ, `private, no-store` unica; validaciones antes de la base, rango -> `OUT_OF_RANGE`, perfil ausente, 401). Suite Ktor completa: 2582 tests, solo falla `openApiIsFrozenNothingUnderOpenapiDiffersFromHead` antes del commit (compara con HEAD; esperado).
- Inventarios regenerados (`routes.bot.txt`, `routes.combined.txt`) y fila en `route-profiles.tsv`.
- Gate PG17 y ensayo `docs/ledger/2026/11/rehearse-f31-2026-11.sh`: resultados en la seccion final (tras fusionar el hotfix).

## Resultados finales (2026-11-15, rama con F31 + F31b + F31c + HF1 fusionado `3e3f1cb0`)

- Gate PG17 (`scripts/db-pg17-gate.sh`): 59 pendientes aplicadas en orden con VERIFY = 0, `assertions.sql`, rollback inverso == bootstrap, re-aplicacion == primera, carreras de tienda e intento: `db-pg17-gate OK`.
- Test del gate (`scripts/db-pg17-gate.test.sh`): 21 escenarios OK.
- Ensayo `docs/ledger/2026/11/rehearse-f31-2026-11.sh` (forma de MAIN de hoy + HF1, las 3 migraciones F31 con `apply-main-migration.sh` y el manifiesto f31): negativas STOP, VERIFY = 0 (4 / 7 / 2 filas), re-aplicar = STOP, sonda del calendario antes/despues, ROLLBACK con huella == punto de partida, re-aplicacion == primera: `F31-2026-11-REHEARSAL OK`.
- Suites: Ktor completa 2584 tests verde; dbtest `com.oposicontrol.dbtest.v1.*` 125 tests verde.
- Imagen: UNA imagen Ktor nueva para F31/F31b/F31c, DESPUES de aplicar HF1 y las 3 migraciones (la imagen nueva envia `p_admite_reintento_pago` y llama a `app_racha_calendario` / `app_contenido_version`).

## Revision Control 2026-11-15 (APTO CON CAMBIOS) — punto 2, migracion enmendada en su sitio

- Nuevo estado `PERDIDO`: dia PASADO no jugado (sin fila o sin respuesta definitiva) con reto activo de la especialidad del usuario mientras la racha seguia viva (su ultimo dia de racha es gap-free hasta ese dia, `private.reto_streak_gap_free`): el dia en que se perdio la racha por no jugar. Solo ese dia; los siguientes sin jugar (sin racha que perder) siguen `SIN_ACTIVIDAD`, igual que los dias sin reto. OpenAPI: enum de `StreakCalendarDay.estado`. Prueba en `aMonthShowsPassedFailedProtectedPendingAndIdleDays` (dia 15 PERDIDO; 9 y 11 SIN_ACTIVIDAD).
- Mapeo Figma sugerido: PERDIDO = "perdido"; FALLADO = reto jugado y fallado.
