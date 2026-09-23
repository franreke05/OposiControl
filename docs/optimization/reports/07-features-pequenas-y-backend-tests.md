# Revisión — features/moderation,support,exam-access,tickets,news-types (34) + core/ui remainder (14: DesktopScrollbar+actuals+tests) + backend/ktor/src/test (81) = 129/129

Contexto de calidad: el corpus de `backend/ktor/src/test` está muy por encima de la media — afirma sobre lo que sale por el cable, documenta la mutación que cada caso mata, y ya elimina tautologías explícitamente en algún sitio (`AdminDashboardOverviewConcurrencyTest.kt:392-423`). No hay aquí una cosecha de "tests que no prueban nada"; lo que sí hay es mucha copia verbatim de andamiaje, con el canónico a veces ya escrito y a medio migrar.

---

# TIER 1 — valor alto / riesgo muy bajo

## 1.1 · Tres copias redundantes del stub `HttpClient` de la JDK (~186 líneas)

**APLICADO.** `StubResponse`/`StubHttpClient`/`StubHttpResponse` privados de `AdminAnalyticsGatewayTest.kt`, `AdminDashboardOverviewTest.kt`, `AdminV1ExamQuestionsTest.kt` borrados; los tres ficheros ahora usan el canónico `V1StubResponse`/`V1StubHttpClient`/`V1StubHttpResponse` de `SupabaseV1GatewayExamAccessTest.kt` (ya `internal`, ya usado por 36 de 81 ficheros de test). Diferencia real que hubo que respetar: `V1StubResponse.contentRange` tiene default `= null`, las copias privadas no — se preservó pasando el tercer argumento donde hacía falta.

## 1.2 · Seis copias byte-idénticas de `HttpRequest.bodyText()` (~96 líneas → 6 llamadas)

**APLICADO.** `AdminV1ExamDeletionTest.kt`, `AdminV1ExamLifecycleTest.kt`, `AdminV1ExamQuestionsTest.kt`, `AppV1AttemptSizingTest.kt`, `IndependentPhase3ResidueTest.kt`, `IndependentResourceRuleContractTest.kt` — verificado idéntico por `md5sum` antes de tocar. Las 6 copias ahora delegan en `readRequestBody(this)` (canónico ya existente en `AdminV1ImportRulesTest.kt`, cuyo propio KDoc pedía exactamente esto).

## 1.3 · Once copias de `List<HttpRequest>.describe()` — diez idénticas y una distinta

**APLICADO (10 de 11).** Las 10 idénticas (verificado por `md5sum`) ahora delegan en `describeRequests(this)`, promovida a `internal fun` junto a `readRequestBody` en `AdminV1ImportRulesTest.kt`.
**NO tocada — la diferencia ES el hallazgo**: `IndependentDashboardFanoutContractTest.kt:153-154` omite `${it.method()}` en su mensaje de fallo. Unificarla cambiaría el texto de un mensaje de fallo ajeno; se dejó exactamente como estaba.

---

# TIER 2 — valor medio / riesgo bajo (ninguno aplicado)

## 2.1 · `FieldLabel` privado duplicado 4 veces en este lote — el canónico NO es equivalente

`ExamAccessScreen.kt:114-121`, `ModerationScreen.kt:138-145`, `SupportScreen.kt:172-179`, `TicketsScreen.kt:172-179` (+3 más fuera de este lote). El canónico de `core/ui` añade `defaultMinSize(minHeight = Sizing.iconButtonSmall)` (~10dp más). **NO aplicado — riesgo alto si se unifica sin decisión de diseño**, choca con la regla de medidas exactas de AGENTS.md.

## 2.2 · Tres copias del descubridor de raíz de repositorio (~24 líneas)

`AdminV1FoundationRoutesTest.kt:400-407`, `RoleSyncInvariantsTest.kt:542-551` (única con un `assertTrue(isFile)` extra), `SupabaseEnvironmentWiringTest.kt:229-237`. **No aplicado** — un canónico tendría que quedarse con la versión que valida (la de `RoleSyncInvariantsTest`), y no se priorizó frente a 1.1-1.3.

## 2.3 · ≥9 instaladores `Application.<x>Module(...)` con el mismo cuerpo de 4 líneas

**NO aplicado — cuidado explícito**: hay OTROS 13 ficheros que montan `install(StatusPages)` a mano, DELIBERADAMENTE parcial y distinto de `installBackendStatusPages()` (p. ej. `IndependentPhase3ResidueTest` comenta por qué: «el detalle del upstream se registra, no se devuelve»). Unificar esos 13 cambiaría los cuerpos de error observados. Sólo los ≥9 verdaderamente idénticos serían seguros, y `Application.dashboardTestModule` duplicado verbatim en 2 ficheros — no se tocó en esta tanda por el riesgo de confundir los dos grupos.

## 2.4 · Helper de contraste WCAG duplicado (2 en este lote, 2 fuera)

`core/ui/.../ContrastTest.kt:62-112` y `.../MaterialMenuColorsTest.kt:137-143` (+2 idénticas fuera, en `features/exams` y `features/specialties`). **Bloqueo estructural verificado**: `core/testing/build.gradle.kts` NO tiene Compose (sólo `core.common`+`kotlin.test`+`coroutines.test`), así que no puede alojar el helper; y `features/exams`/`features/specialties` no ven el `commonTest` de `core/ui`. Unificar exige un módulo `core/ui-testing` nuevo — decisión de módulos, no limpieza. No aplicado.

## 2.5 · `CapturingAppender` duplicado (Logback en memoria)

`ObservabilityTest.kt:30-35` y `UpstreamIoSeamTest.kt:214-219`. El appender es idéntico, pero sus envoltorios NO (uno `suspend`, el otro no, a propósito). No aplicado — bajo valor para el riesgo de tocar dos ficheros de observabilidad.

## 2.6 · Los 5 tests de cursor están escritos dos veces

`AdminV1ForoTest.kt:8-44` y `AdminV1FeedbackTest.kt:178-214` — mismos 5 casos sobre `encode/decodeForoCursor` vs `encode/decodeFeedbackCursor`. No aplicado — un helper `assertCursorContract(encode, decode)` es viable pero no se priorizó.

---

# TIER 3 — valor bajo o riesgo desfavorable

## 3.1 · `ModerationIntent.UpdateExecutor` — código muerto verificado

**APLICADO.** `ModerationIntent.kt:11` y su rama en `ModerationViewModel.kt:51` borrados. Verificado por grep exhaustivo: cero referencias en todo el repo fuera de esas dos líneas. `ModerationScreen.kt:92-96` pinta el ejecutor como `Text` de sólo lectura — no hay ni puede haber un dispatcher.

## 3.2 · Aserción redundante por construcción

`AdminV1UserIdValidationOrderTest.kt:109-113`: si `assertEquals(BadRequest, status)` pasa, `assertEquals(Conflict != status, true)` no puede fallar nunca. No aplicado — no urge, documenta intención aunque no mida nada.

## 3.3 · Diez tests sin ninguna aserción (patrón «no lanza»)

`AdminMarketTest.kt:112,118,152,196,201`, `AdminSubscriptionsTest.kt:48,153`, `CommercialCatalogAdminTest.kt:103,133`, `SpecialtyCapabilitiesTest.kt:250`. No son vacíos (afirman "no lanza"), pero una mutación que devolviera el objeto equivocado sin lanzar pasaría igual. No aplicado — arreglarlo es añadir aserciones, no una limpieza.

## 3.4 · Duplicación entre las features pequeñas — comparada cuerpo a cuerpo y NO recomendada

`ExamAccessViewModel`, `ModerationViewModel`, `NewsTypesViewModel` comparten el esqueleto `select/publish/refresh/save`, pero difieren en: campos de filtro distintos en `publish()`, orden de inserción distinto en `save()` (`ExamAccess` añade al final, `Moderation` al principio — **orden observable en pantalla**), derivación de slug sólo en `NewsTypes`, y textos de error distintos. Un genérico no ahorraría líneas netas. No aplicado.

---

# REGISTRAR, NO TOCAR

1. **`FieldLabel`** (2.1) — canónico cambia alto renderizado, choca con AGENTS.md.
2. **`describe()` de `IndependentDashboardFanoutContractTest`** (1.3) — única sin verbo HTTP, a propósito o no, no se toca sin decidirlo.
3. **Los 13 `install(StatusPages)` a mano** (2.3) — deliberadamente parciales y distintos del de producción.
4. **`AdminTableColumn(..., 1.2f)` — weights en toda la UI de este lote**: `ExamAccessScreen.kt:52-55`, `ModerationScreen.kt:52-55`, `NewsTypesScreen.kt:51-53`, `SupportScreen.kt:70-73`, `TicketsScreen.kt:57-60`. **Violación sistémica de la regla de UI del proyecto** (AGENTS.md prohíbe `weight`/fracciones): el propio `AdminTableColumn` de `core/ui` tiene la firma con `Float`. Cambiarlo es un rediseño de `AdminTable` con ancho fijo por columna — registrar en `docs/registry/TECH_DEBT.md`, no optimización.
5. **`testAuthConfiguration()` duplicado** — `OposiBotsAuthRoutesTest.kt:268-283` y `SupabaseOposiBotsAuthGatewayTest.kt:167-182`. NO son iguales: literales de clave distintos y la segunda parametriza `forwardClientIpToSupabase`. Varios asserts comparan contra esos literales exactos.
6. **`core/testing` no puede alojar helpers de Compose** (verificado, ver 2.4). Cualquier plan que diga "muévelo a `core/testing`" para contraste WCAG es incorrecto tal cual.
7. **`gatewayWith(...)` con `AdminV1Gateway.forTesting(...)`** en 29 ficheros — parece dedup obvia, pero cada uno difiere en la lambda `responder` Y en el orden de las ramas del `when`, que **tres ficheros documentan como semánticamente crítico** (`/rest/v1/preguntas_parseadas_staging` contiene `/rest/v1/preguntas` como prefijo). Viable (~150 líneas) pero NO en el mismo lote que lo demás — requiere su propia sesión con cuidado del orden.
8. **`DesktopScrollbar`** — revisado, sin hallazgos. Las dos sobrecargas tienen call sites reales; los `actual` de android/ios son `= Unit` a propósito y documentado.

---

## Cierre

`FILES_IN_SCOPE = 129` (34 features + 4 DesktopScrollbar + 10 core/ui tests + 81 backend/ktor/src/test). `FILES_READ = 129`. Ninguno sin leer.

**Aplicado de este informe**: 1.1, 1.2, 1.3 (parcial, respetando la excepción), 3.1. Total ≈ −292 líneas de test sin tocar ninguna aserción ni cambiar comportamiento observable, verificado con `:backend:ktor:compileTestKotlin` + `:backend:ktor:test` en verde.
