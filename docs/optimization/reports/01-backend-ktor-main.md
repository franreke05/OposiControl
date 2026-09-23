# Revisor read-only — backend/ktor (src/main)

Estado: revisión completa, 118/118 ficheros leídos. Aplicado como escritor único: A3, A7, A9, B11 (ver `LEDGER.md` para el resumen de aplicación). El resto queda registrado abajo para decisión de producto/arquitectura.

## Hallazgos aplicados

- **A3** — `InMemoryGateway` (clase completa, 117 líneas) sin ningún consumidor. Borrado de `Application.kt`.
- **A7** — Clave de idempotencia de la recompensa de reporte (`"report-reward:$id"`) escrita a mano en `AdminV1Routes.kt` en vez de usar la función ya existente `opositesterRewardIdempotencyKey(reportId)` de `AdminOposiTickets.kt`. Sustituido.
- **A9** — `settings()` byte-idéntico en `AdminGateway.kt` y en el gateway de `AdminV1Routes.kt`. Extraído a `adminSettingsFromEnvironment()` en `AdminGateway.kt`, ambos delegan.
- **B11** — `hasModeratorOrAdminRole(usuarioId)` en `AppV1Routes.kt` sin ningún call site. Borrado.

## Hallazgos NO aplicados — registrados

- **A1** — 61 de 113 llamadas al gateway bloquean el hilo de Netty mientras el KDoc del propio fichero afirma lo contrario. Requiere decisión de arquitectura (mover a `Dispatchers.IO` o corregir el KDoc); no es una limpieza mecánica.
- **A2** — La verificación de JWT para `/api/v1/app/*` corre en el hilo de Netty, contradiciendo su propio KDoc. Mismo tipo de hallazgo que A1: decisión de arquitectura, no refactor.
- **A4/A5** — ~310 líneas de helpers de test duplicados. Resuelto en gran parte por la revisión de `backend/ktor/src/test` (ver `07-features-pequenas-y-backend-tests.md`) y aplicado en el ledger (stub HTTP, `bodyText()`, `describe()`).
- **C18–C22** — Hallazgos explícitamente NO recomendados por el propio revisor porque cambiarían comportamiento observable. No aplicados a propósito.

## Nota de alcance

El informe original completo (22 hallazgos con fichero:línea exacto) se entregó como mensaje de subagente dentro de la sesión de trabajo que produjo este ledger. Este fichero resume su resultado neto; si se necesita el detalle línea a línea de A1/A2 para una futura corrección de arquitectura, hay que relanzar una revisión dirigida a esos dos hallazgos con el contexto de threading de Ktor.
