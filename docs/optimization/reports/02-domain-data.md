# Informe — `domain/src` (83) + `data/src` (70) · rama `refactor/kotlin-kmp-professional-optimization`

Estado: revisión completa, 153/153 ficheros leídos (verbatim del subagente). Aplicado como escritor único: H1, H2, H3, H4, H5, H6 (a-d), H13, H14, H15. El resto (H7-H12, y la sección REGISTRAR) queda documentado abajo tal cual lo entregó el revisor, para decisión de producto/arquitectura o para aplicarse en una tanda futura con red de tests.

---

**Leídos: 153/153.** Ninguno ilegible. Todas las rutas son absolutas bajo `C:\Users\franr\AndroidStudioProjects\OposiControl-KotlinOptimizer\`.

Ley aplicada: **BEHAVIOR PRESERVATION > FEWER LINES**. Cuando un hallazgo *parece* una mejora pero cambia contrato o comportamiento, lo he marcado **REGISTRAR, NO TOCAR** en vez de proponerlo.

Ordenado por (valor ÷ riesgo).

---

## TIER 1 — valor alto, riesgo casi nulo

### H1 · Código muerto: 4 repositorios + 1 fichero de DTOs que nadie construye (~2.754 líneas)

**APLICADO.** Borrados `SupabaseContentRepositoryImpl.kt` (981), `KtorContentRepositoryImpl.kt` (936), `SupabaseCommerceRepositoryImpl.kt` (344), `SupabaseNewsRepositoryImpl.kt` (238), `ContentDtos.kt` (255). Verificado con grep sobre todo el repo (excluyendo build/): cero referencias de código, solo prosa en KDoc de `ContentRepository.kt` y `KtorCommerceRepositoryImpl.kt`, reescrita para no citar clases borradas.

| Archivo | Líneas | Estado |
|---|---|---|
| `data/src/commonMain/.../repository/SupabaseContentRepositoryImpl.kt:56` | 981 | nunca instanciado |
| `data/src/commonMain/.../repository/KtorContentRepositoryImpl.kt:52` | 936 | nunca instanciado |
| `data/src/commonMain/.../repository/SupabaseCommerceRepositoryImpl.kt:36` | 344 | ya lleva `@Deprecated` en `:33` |
| `data/src/commonMain/.../repository/SupabaseNewsRepositoryImpl.kt:30` | 238 | nunca instanciado |
| `data/src/commonMain/.../remote/dto/ContentDtos.kt` (entero) | 255 | su **único** importador es `SupabaseContentRepositoryImpl.kt:8-15` |

**Qué** · `grep` en todo el repo: los cuatro nombres sólo aparecen en prosa KDoc (`domain/.../repository/ContentRepository.kt:105`, `data/.../KtorCommerceRepositoryImpl.kt:37`). `DataModule.kt` enlaza `ContentRepository → KtorAdminV1ContentRepositoryImpl` (`:100`), `NewsRepository → KtorNewsRepositoryImpl` (`:185`), `CommerceRepository → KtorCommerceRepositoryImpl` (`:122`). Confirmado el contexto: `SupabaseNewsRepositoryImpl` es legacy, el vivo es `KtorNewsRepositoryImpl`.
**Por qué es mejora objetiva** · No es estilo: es ~14 % del `data/` que se compila, se enlaza y se mantiene sin ejecutarse jamás. Arrastra además el `println` de depuración de `KtorContentRepositoryImpl.kt:417` (imprime en *cada* respuesta) y toda la superficie Supabase-directo (anon key + JWT de usuario saltándose Ktor) que el proyecto declara cerrada.
**Riesgo** · Bajo. **Dos avisos concretos:** (a) los `default` de `ContentRepository.kt:110, 133, 148` se justifican textualmente por esas implementaciones legacy (`:105`) — los defaults deben quedarse (son contrato de la interfaz), sólo hay que reescribir el KDoc; (b) `KtorContentRepositoryImpl.kt:874-896` contiene un `toAudit()` privado copiado verbatim del de dominio (`domain/.../ExamResourceAuditing.kt:16-38`) — se va con el fichero.
**Test que lo cubre hoy** · NINGUNO los toca. Los 9 tests de `data/src/jvmTest` sólo instancian los impl vivos.
**¿Seguro sin test nuevo?** · Sí. Verificación = `./gradlew :data:compileKotlin`.

---

### H2 · `backendHeaders()` copiado verbatim en 10 ficheros vivos — cuando el canónico ya existe

**APLICADO.** Las 9 copias vivas sustituidas por `adminAuthHeaders(appConfig, sessionLocalDataSource)` del canónico `AdminApiAuth.kt`.

**Canónico** · `data/src/commonMain/.../remote/api/AdminApiAuth.kt:9-17` `internal fun HttpRequestBuilder.adminAuthHeaders(appConfig, sessionLocalDataSource)`.
**Copias** (cuerpo idéntico byte a byte: token de sesión → `backendToken` → `return` sin cabecera):
`KtorAdminOperationsRepositoryImpl.kt:131` · `KtorCommerceRepositoryImpl.kt:141` · `KtorCommunitiesRepositoryImpl.kt:72` · `KtorConnectionDiagnosticsRepositoryImpl.kt:120` · `KtorForumRepositoryImpl.kt:68` · `KtorModerationRepositoryImpl.kt:98` · `KtorNewsRepositoryImpl.kt:107` · `KtorTagSearchRepositoryImpl.kt:46` · `KtorTagsRepositoryImpl.kt:70` · `KtorTesterRepositoryImpl.kt:69` (+`KtorContentRepositoryImpl.kt:425`, murió con H1).

**Por qué es mejora objetiva** · La política de autenticación del panel estaba escrita 11 veces. Ya había 9 ficheros que usan el canónico (`KtorQuestionsRepositoryImpl.kt:75`, `KtorRankingsRepositoryImpl.kt:78`, `UsersApi.kt:50`, …), así que la regla ya vivía en dos versiones. **El patrón de sustitución ya estaba resuelto en el repo**: `KtorAdminV1ContentRepositoryImpl.kt:915-917` hace exactamente `private fun HttpRequestBuilder.adminHeaders() { adminAuthHeaders(appConfig, sessionLocalDataSource) }`.
**Riesgo** · Muy bajo. Mismo orden de precedencia, mismo `?: return`, mismo `Bearer`.
**Test que lo cubre hoy** · SÍ, tres: `KtorCommerceRepositoryImplTest.kt:81` `refreshCommerceSendsABearerTokenNeverASupabaseApiKey`, `KtorRankingsRepositoryImplTest.kt:94`, `KtorProductAnalyticsRepositoryImplTest.kt:92`.
**¿Seguro sin test nuevo?** · Sí. Verificado: `:data:jvmTest` y `:domain:jvmTest` en verde tras el cambio.

---

### H3 · `KtorTagSearchRepositoryImpl.kt` — vivo en DI, pero ~60 % del fichero era inalcanzable

**APLICADO.** Reescrito para dejar solo lo que realmente hace (tres métodos que devuelven constantes), documentando por qué. `DataModule.kt` actualizado (ya no inyecta `client`/`appConfig`/`sessionLocalDataSource`, solo `adapter`).

**Qué** · Estaba enlazado (`DataModule.kt:195`) y sus tres métodos devolvían constantes: `findTags` → `emptyList()`, `search` → `emptyList()`, `reindex` → `Unit`. Nunca se llamaban: `backendUrl()`, `backendHeaders()`, `failureDetail()`, `TagDto`, `TaggedSearchResultDto`, sus `toDomain()`, y ~8 imports muertos.
**Por qué es mejora objetiva** · El compilador ya lo avisaba. Un lector que abre este fichero creía que hacía peticiones; no hacía ninguna.
**Riesgo** · Muy bajo (nada lo referencia).
**Nota aparte, no aplicada aquí (cambio de comportamiento):** los tres métodos devuelven vacío sin decirlo — mismo `0 != error` que el proyecto cerró en `RealUsersDataSource.kt`. Queda en **REGISTRAR, NO TOCAR** más abajo.

---

### H4 · Dos parsers de `SystemStatusLevel` idénticos, y un test que ya exige que coincidan

**APLICADO.** `KtorSystemStatusRepositoryImpl.toSystemStatusLevel()` promovido a `internal`; `KtorDashboardOverviewRepositoryImpl.toStatusLevel()` eliminado y sus 3 call sites usan el de System Status (mismo paquete).

**Qué** · `KtorSystemStatusRepositoryImpl.kt:123-124` `String.toSystemStatusLevel()` y `KtorDashboardOverviewRepositoryImpl.kt:362-363` `String.toStatusLevel()`. Mismo paquete, mismo cuerpo exacto.
**Por qué es mejora objetiva** · `KtorDashboardOverviewRepositoryImplTest.kt:475` se llama literalmente `healthKeepsTheSameLevelVocabularyAsTheSystemStatusScreen`: existe un test cuyo único trabajo es vigilar que estas dos copias no diverjan. Con una sola función, el test deja de ser una red y pasa a ser una tautología — mejor sitio donde estar.
**Riesgo** · Muy bajo. **Test que lo cubre hoy** · SÍ, por los dos lados. **¿Seguro sin test nuevo?** · Sí, de los más seguros del informe.

---

### H5 · `KtorModerationRepositoryImpl.kt:33-46` — `listPendingReports()` es `listReports(false)` reescrito

**APLICADO.** `listPendingReports()` ahora es `= listReports(includeResolved = false)`.

**Qué** · `listPendingReports()` y `listReports(includeResolved)` hacen el mismo `GET ADMIN_V1_REPORTES`, el mismo `requireSuccess`, el mismo mapeo. La única diferencia (`parameter("estado","todos")` si `includeResolved`) con `false` es un no-op. El propio contrato `ModerationRepository.kt:9` ya lo decía.
**Riesgo** · Bajo, demostrable leyendo las 14 líneas. **Test que lo cubre hoy** · NINGUNO (no hay `KtorModerationRepositoryImplTest`). **¿Seguro sin test nuevo?** · Sí — la equivalencia es sintáctica, no semántica.

---

### H6 · Muerto fino y verificado

**APLICADO parcialmente:**
- `AuditMapper.kt:18-27` `AuditEvent.toDto()` — **borrado** (0 call sites en todo el repo).
- `ContentModels.kt:45-46` `Exam.isPremium`/`Exam.isGratuito` — **borrados** (sin lectores; distinto de `isRafaga`/`isInmersivo` de las líneas 43-44, que SÍ se usan y no se tocaron).
- `KtorAdminV1ContentRepositoryImpl.kt:795` `postExamState(examId, endpoint)` nunca usaba `examId` — **parámetro eliminado**, los 5 call sites actualizados.
- `KtorAdminV1ContentRepositoryImpl.kt:630` plantilla de string alrededor de un String (`"${BackendRoutes.adminV1ExamenPortada(examId)}"`) — **simplificado** a la llamada directa.

**Riesgo** · Muy bajo. **Tests** · NINGUNO. **Seguro sin test nuevo** · Sí, verificado con compilación + suite de `data`/`domain`.

---

## TIER 2 — valor alto, riesgo bajo-medio (NO aplicado — queda para una tanda futura con más cuidado)

### H7 · `requireSuccess(endpoint)` — el mismo cuerpo de 3 líneas en 14 tipos

Cuerpo idéntico `if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")`:
`KtorCommerceRepositoryImpl.kt:137` · `KtorCommunitiesRepositoryImpl.kt:68` · `KtorCommercialCatalogRepositoryImpl.kt:76` · `KtorForumRepositoryImpl.kt:64` · `KtorMarketCatalogRepositoryImpl.kt:93` · `KtorModerationRepositoryImpl.kt:94` · `KtorTagsRepositoryImpl.kt:66` · `KtorTesterRepositoryImpl.kt:65` · `KtorQuestionsRepositoryImpl.kt:353` · `KtorRankingsRepositoryImpl.kt:90` · `KtorAdminV1ContentRepositoryImpl.kt:919` (`requireBackendSuccess`) · `KtorAdminOperationsRepositoryImpl.kt:138` (`checkedBody`) · `RolesApi.kt:49` (`checkedBody`) · `UsersApi.kt:80` (`checkedBody`, idéntico byte a byte al de RolesApi).
Y 5 copias *inline* del mismo mensaje: `DashboardApi.kt:24`, `UserDetailApi.kt:40`, `KtorSystemStatusRepositoryImpl.kt:51`, `KtorDashboardOverviewRepositoryImpl.kt:74`, `KtorProductAnalyticsRepositoryImpl.kt:63`.

**Por qué es mejora objetiva** · Ese texto **es observabilidad que llega al usuario** (viaja en el `IllegalStateException` → `NetworkResultAdapter` → `AppError`). Tenerlo en 19 sitios significa que el formato del error del panel no tiene dueño.
**Riesgo** · Bajo-**medio**: `KtorNewsRepositoryImpl.kt:71,100` usa mensajes distintos (**sin endpoint**, `take(300)`) — dejar fuera de la unificación. `KtorMarketCatalogRepositoryImpl.kt:93` y `KtorRankingsRepositoryImpl.kt:90` declaran `requireSuccess` **sin `private`** — miembros públicos por descuido.
**¿Seguro sin test nuevo?** · Sí **si** se mantiene el literal exacto (`take(240)`) y se deja News fuera. **No aplicado en esta tanda** por volumen (14 tipos) y por el matiz de News — recomendado para sesión dedicada.

### H8 · 46 envolturas `{"data": …}` declaradas una por una

24 `Response(val data: XDto)` + 19 `Response(val data: List<XDto>)` + variantes, en 12 ficheros. El genérico ya existe (`V1DataResponse<T>`/`V1SingleDataResponse<T>` en `KtorContentRepositoryImpl.kt:548-555`, murió con H1; `ListEnvelope<T>` en `KtorMarketCatalogRepositoryImpl.kt:105-131`).
**Riesgo** · Medio: visibilidad de DTOs `private` de fichero, y **NO unificar** los que llevan campos extra (`AdminLoteImportResponse`, `AdminFeedbacksV1Response`, `RankingPageDto`, `QPreguntasPageResponse`) ni `UserStandingResponse` (tiene default `= UserStandingDto()`).
**¿Seguro sin test nuevo?** · Sí para el subconjunto sin campos extra ni defaults, por tandas. **No aplicado** — requiere revisar visibilidad DTO por DTO.

### H9 · `nowInstant()` = formatear y reparsear para cruzar de tipo

`KtorAdminV1ContentRepositoryImpl.kt:1635` (10 usos) y `KtorCommunitiesRepositoryImpl.kt:85-86` (2 por fila, dentro de un `map`).
**Riesgo** · Medio: la sustitución debe preservar nanosegundos (`Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond)`, NUNCA `fromEpochMilliseconds`). **No aplicado** — ningún test asserta estos timestamps hoy, así que exige verificación manual cuidadosa antes de tocar un camino caliente de mapeo.

### H10 · `AdminUserV1Dto`/`AdminPermissionV1Dto` reescriben `UserDto`/`PermissionDto`

**Riesgo MEDIO real**: los defaults NO coinciden (`AdminUserV1Dto` tiene defaults, `UserDto` no — fusionar sin copiar defaults primero cambiaría `MissingFieldException` por `""`). **El propio revisor dice "con red, no sin ella"** — no aplicado, pediría un test de decodificación con campo ausente antes de tocarlo.

### H11 · `AdminFeedbackV1Dto`/`AdminFeedbackDetailV1Dto` — el mismo DTO dos veces

Riesgo bajo-medio pero **sin red de test** (`KtorTesterRepositoryImpl` no tiene test). No aplicado, prioridad baja frente a H1-H7.

### H12 · `String → QuestionResourceType` escrito dos veces (más dos copias muertas que H1 ya resolvió)

`KtorQuestionsRepositoryImpl.kt:562-568` y `KtorAdminV1ContentRepositoryImpl.kt:1602-1609`, con dos tests homónimos vigilando la misma regla. El KDoc de `KtorQuestionsRepositoryImpl.kt:44-55` advierte "a propósito NO reutiliza los DTOs/mappers privados" — esa decisión es sobre DTOs, no sobre esta función pura, pero **no aplicado en esta tanda** por el riesgo de que se lea como violar esa decisión sin dejarlo argumentado en el propio código.

---

## TIER 3 — valor medio, riesgo bajo

### H13 · `CreateContentDraftUseCase` reimplementa `AppResult.map`

**APLICADO.** Los dos bloques `when (val result = ...) { Failure -> result; Success -> AppResult.Success(...) }` sustituidos por `.map { it.id }` de `core/common/.../result/AppResult.kt`.

### H14 · `canonicalQuestionType` re-deriva lo que el enum ya guarda

**APLICADO.** `domain/.../model/ContentImport.kt` — ahora `= ContentExamType.fromApiValue(value).questionApiValue`.

### H15 · `SettingsLocalDataSource` consulta la base dos veces al arrancar

**APLICADO.** `readSettings()` se llama una sola vez (`val stored = readSettings()`), reutilizado para el `MutableStateFlow` inicial y el `if (stored == null)`.

### H16 · `KtorConnectionDiagnosticsRepositoryImpl` — seis bloques calcados

No aplicado: las seis peticiones son secuenciales a propósito (paralelizarlas cambiaría tiempos y orden de fallo). Valor bajo, se deja como está.

### H17 · `LocalBinaryAsset` es `data class` con `ByteArray`

No aplicado en esta tanda (bajo valor, no forma parte del lote de mayor prioridad); verificado que ningún `.copy()`/`==` se usa sobre él, así que `data class → class` sería seguro si se retoma.

### H18 · `LocalBinaryAsset.toMultipartBody()` duplicado

Su copia en `KtorContentRepositoryImpl.kt` murió con H1. La que queda en `KtorNewsRepositoryImpl.kt` no tiene gemela viva. No aplicado. **Aviso**: hay tres saneadores de nombre de fichero distintos (`KtorNewsRepositoryImpl.kt:129`, `KtorQuestionsRepositoryImpl.kt:646-647`, `KtorAdminV1ContentRepositoryImpl.kt:1637`) que NO son equivalentes (difieren en `trim()` y en el nombre por defecto) — no unificar sin parametrizar conscientemente.

---

## REGISTRAR, NO TOCAR (contrato / comportamiento / deuda conocida)

- **DEBT-076 (IDs enteros vs `uuid`)** — `ContentDtos.kt` (muerto, H1), `KtorCommerceRepositoryImpl.kt:178,188,199,211,219-220`, `SupabaseContentRepositoryImpl.kt:876` (muerto, H1). H1 se llevó dos terceras partes de los avistamientos sin migrar nada.
- **`RankingEntry` vs `UserCompetitiveState`** — comparten 11 campos idénticos; sus DTOs (`KtorRankingsRepositoryImpl.kt:137-154` vs `UserDetailApi.kt:101-114`) usan convenciones de `@SerialName` REALMENTE distintas (`liga_codigo` vs sin anotar). Unificar es cambio de contrato. No tocar.
- **`KtorNewsRepositoryImpl.kt:78-84` — bug, no oportunidad de refactor.** `deleteNews` no comprueba el estado de la respuesta: un 500 devuelve `AppResult.Success`. Único escritor de la capa sin `requireSuccess`. Merece ticket propio, fuera de mandato de optimización.
- **`0 != error` sin cerrar**: `RealAuditDataSource.kt:15-20` y `RealRolesDataSource.kt:16-21` (`Failure -> emit(emptyList())`). Mismo defecto que `RealUsersDataSource.kt` ya cerró. Cambiar la firma es cambio de contrato.
- **Fechas fabricadas**: `KtorCommunitiesRepositoryImpl.kt:85-86` sella `createdAt`/`updatedAt` con *ahora* para toda comunidad; `KtorAdminV1ContentRepositoryImpl.kt:1372-1373` igual con ráfagas; `AuditApi.kt:42` usa `"1970-01-01T00:00:00Z"` de relleno. Contradice la regla del proyecto (`null != 0`, no fabricar datos); exige contrato explícito antes de tocar.
- **`UnlinkedResource`/`UnlinkedResourceReason` (`ContentModels.kt:169-182`) sin productor vivo.** El productor vivo (`ExamResourceAuditing.kt:36`) devuelve siempre `emptyList()`. La rama de UI en `ExamsScreen.kt:1335-1342` es inalcanzable en producción. No se propone borrar (puede ser funcionalidad planificada); decidir junto con H1.
- **N+1 conocido**: `ContentDashboardRepositoryImpl.kt:44-46`. Ya documentado en `DashboardOverviewRepository.kt:19-25`; Dashboard V2 lo sustituye.
- **`DashboardMetricKey` vs `DashboardMetricKeys`** — dos objetos con claves solapadas, los dos vivos. Fusionar = cambio de contrato.
- **`AdminOperationsRepository`** (`KtorAdminOperationsRepositoryImpl.kt:146-298`): ~150 líneas de boilerplate DTO↔dominio. Eliminarla exige meter `kotlinx.serialization` en `domain` — decisión arquitectónica, fuera de mandato.
- **Menudencias idiomáticas** (valor real ≈ 0): `AnalyticsSectionReport.metric/availableCount` ≡ `DashboardBlockReport.metric/availableCount`; `RankingEntry.label` ≡ `UserStanding.label`; `UpsertCommunityUseCase` ≡ `UpsertTagUseCase`. **No fusionar** `MarketTicketPack.centsPerTicket` con `CommercialProduct.centsPerTicket`: las guardas de null no son iguales.
- **Flow/Coroutines** — sin hallazgos que arreglar sin cambiar comportamiento. Todo `observeX()` vivo es `MutableStateFlow.asStateFlow()` correcto; los `runCatching` son degradación deliberada y documentada.
- **`@SerialName` redundantes** — ninguno encontrado.

## Cobertura de test (columna "qué protege qué")

**Con red fuerte** → `KtorAdminV1ContentRepositoryImpl` (29 tests), `KtorDashboardOverviewRepositoryImpl` (18), `KtorProductAnalyticsRepositoryImpl` (11), `KtorRankingsRepositoryImpl` (10), `KtorQuestionsRepositoryImpl` (8), `KtorSystemStatusRepositoryImpl` (5), `KtorCommerceRepositoryImpl` (3), `unavailableDashboardMetrics` (7).

**Sin ningún test** → `KtorModerationRepositoryImpl`, `KtorForumRepositoryImpl`, `KtorTesterRepositoryImpl`, `KtorTagsRepositoryImpl`, `KtorTagSearchRepositoryImpl`, `KtorCommunitiesRepositoryImpl`, `KtorMarketCatalogRepositoryImpl`, `KtorCommercialCatalogRepositoryImpl`, `KtorAdminOperationsRepositoryImpl`, `KtorConnectionDiagnosticsRepositoryImpl`, `KtorNewsRepositoryImpl`, `ContentDashboardRepositoryImpl` (sólo su función pura), los 4 mappers, los 3 locales, las 6 APIs.

## Recuento

- **En alcance:** 153 (83 `domain/src` + 70 `data/src`). **Leídos:** 153/153. **No leídos:** ninguno.
