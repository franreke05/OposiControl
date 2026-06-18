# OposiControl — Contexto completo para Codex/AI

> Última actualización: 2026-06-18. Backend V1 admin verificado tras reinicio real de Ktor.

---

## 1. Arquitectura general

Kotlin Multiplatform (KMP) multi-módulo + backend Ktor JVM.

```
:composeApp          → app Android/iOS (Compose Multiplatform)
:domain              → modelos, repositorios (interfaces), use cases, validación
:data                → implementaciones de repositorios (Ktor client, local)
:core:common         → AppConfig, AppResult, AppError, MviViewModel, UiAsync
:core:network        → ApiClientFactory, NetworkResultAdapter
:core:database       → DatabaseFactory (SQLDelight)
:features:*          → 16+ módulos feature (MVI: Intent/State/ViewModel/Screen/DI)
:backend:ktor        → servidor Ktor JVM con acceso service-role a Supabase
```

**Regla de oro**: La app KMP NUNCA escribe directamente a Supabase. Todo va via Ktor.

---

## 2. Backend Ktor — rutas activas

Fichero principal: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/Application.kt`

### Rutas públicas (sin auth)
- `/v1/*` — lecturas app móvil, servidas por `SupabaseV1Gateway` en `V1Routes.kt`

### Rutas admin (require `authenticate("gateway-auth")`)
Auth = header `Authorization: Bearer $OPOSICONTROL_BACKEND_TOKEN`

- `/content/*` — CRUD legacy (SupabaseContentGateway, en Application.kt)
- `/api/v1/admin/*` — nueva superficie admin, en `AdminV1Routes.kt` (gateway: `AdminV1Gateway`)
  - GET/POST/PATCH `/especialidades`
  - GET/POST/GET{id}/PATCH/publicar/despublicar `/examenes`
  - GET/POST/GET{id}/PATCH `/preguntas` + GET/POST `/{id}/recursos`
  - GET/POST `/importaciones/preguntas`, GET `/{loteId}`, PATCH `/staging/{id}`, POST `/{loteId}/importar`
  - GET/PATCH{id}/resolver `/feedback/preguntas`
  - GET `/auditoria`

### Rutas app (auth JWT propia — sin authenticate de Ktor)
- `/api/v1/app/*` — servidas por `AppV1Routes.kt` (gateway: `AppV1Gateway`)
  - Auth: JWT Supabase ES256 via `Authorization: Bearer <token>`
  - Fallback dev: header `X-Usuario-Id` solo si `OPOSICONTROL_ALLOW_DEV_USER=true`
  - GET `/home`, GET `/examenes`, GET `/examenes/{id}`
  - POST `/examenes/{id}/iniciar`
  - POST `/intentos/{id}/responder`, POST `/intentos/{id}/finalizar`
  - GET `/estadisticas`, GET `/reto-diario`
  - POST `/feedback/pregunta`, POST `/eventos`

---

## 3. JWT Supabase — verificación ES256

Clase: `SupabaseJwtVerifier` en `AppV1Routes.kt`

```kotlin
// JWKS en: $SUPABASE_URL/auth/v1/.well-known/jwks.json
// kid activo: eb9b61e2-fa51-489b-8963-35a6f8597d81
// x: LQKviIIGq2B5gkkFf9hM0nqlAyauTxOAZTkru4S257w
// y: MUMs6KSS0piU5hSX0Mak9lj7c5uSAJ4-yN8VXLnwF8Y
// alg: ES256, crv: P-256
```

Clave construida con `KeyFactory.getInstance("EC")` + `ECPublicKeySpec` (sin librería jwks-rsa).
Verificada con `com.auth0:java-jwt:4.4.0`.
**VERIFICADO end-to-end el 2026-06-17** — token real → HTTP 200 confirmado.

Usuario de prueba JWT: `jwt.test@oposicontrol.dev` / `JwtTest2026!`
ID: `d38c76a2-db6c-49e7-8e98-9dcb93eeec2a`

---

## 4. Variables de entorno (`.env` en raíz, chmod 600, gitignored)

```env
SUPABASE_URL=https://ndiooyyqtaeysnedywer.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<secret — ver .env real, NUNCA en git ni en código KMP>
OPOSICONTROL_BACKEND_TOKEN=<secret — ver .env real, NUNCA en git ni en código KMP>
BASE_URL=http://10.0.2.2:8080
OPOSICONTROL_ALLOW_DEV_USER=true   ← DEV ONLY, false en producción
```

El `tasks.named<JavaExec>("run")` en `backend/ktor/build.gradle.kts` autocarga `.env`.

---

## 5. Esquema Supabase REAL (≠ docs SQL)

Los ficheros en `docs/supabase/*.sql` usan nombres **singulares** con **PK int** — NO coinciden con la base desplegada. La realidad:

| Tabla real | PK | Columnas clave |
|---|---|---|
| `especialidades` | uuid | nombre, slug, activa, posicion, creado_en, actualizado_en |
| `examenes` | uuid | titulo, slug, descripcion, tipo_examen (rafaga\|inmersivo), tipo_acceso, total_preguntas (CHECK>0), activo, publicado_en (null=no publicado), anio |
| `preguntas` | uuid | titulo, tipo_pregunta (rafaga\|**inmersiva**\|guiada), activa, justificacion, nivel_dificultad |
| `opciones_pregunta` | uuid | pregunta_id, letra, texto_opcion, posicion |
| `soluciones_pregunta` | uuid | pregunta_id, opcion_correcta_id, explicacion |
| `preguntas_examen` | uuid | pregunta_id, examen_id, posicion |
| `recursos_pregunta` | uuid | pregunta_id, tipo_recurso, url, posicion (CHECK>0) |
| `etiquetas` | uuid | nombre, slug, origen |
| `comunidades_autonomas` | uuid | nombre, codigo, slug, activa, posicion |
| `perfiles_usuario` | — | usuario_id (FK auth.users), nombre_mostrar, username, avatar_url, especialidad_principal_id, comunidad_principal_id, es_invitado, onboarding_completado |
| `intentos_examen` | uuid | usuario_id, examen_id, estado (en_progreso\|finalizado\|abandonado), iniciado_en, finalizado_en, total_preguntas, total_respondidas, total_correctas, porcentaje_acierto, puntuacion |
| `respuestas_intento` | uuid | intento_id, pregunta_id, opcion_seleccionada_id, opcion_correcta_id, es_correcta, usuario_id, examen_id |
| `estadisticas_usuario` | uuid | usuario_id, examenes_finalizados, preguntas_respondidas, respuestas_correctas, porcentaje_acierto, puntos_totales, nivel |
| `retos_diarios` | uuid | fecha, titulo, descripcion, objetivo_preguntas, recompensa_puntos, activo |
| `feedback_pregunta` | uuid | usuario_id, pregunta_id, intento_id?, tipo_feedback, descripcion, estado (pendiente\|resuelto\|descartado\|revisado) |
| `eventos_app` | uuid | usuario_id, nombre_evento, pantalla, entidad_tipo, entidad_id, propiedades (jsonb) |
| `auditoria_log` | uuid | accion, tabla_afectada, registro_id, creado_en |
| `lotes_importacion_preguntas` | uuid | nombre, descripcion, estado (pendiente\|parseado\|importado), total_preguntas, importadas, errores |
| `preguntas_parseadas_staging` | uuid | lote_id, titulo, tipo_pregunta, nivel_dificultad, estado_parseo (ok\|importada\|error), pregunta_id? |

**Reglas de negocio reales descubiertas**:
- `examenes.total_preguntas` tiene CHECK > 0 → clamp a mínimo 1
- `recursos_pregunta.posicion` tiene CHECK > 0 → clamp a mínimo 1
- Solo preguntas `tipo_pregunta=inmersiva` admiten recursos (trigger en DB)
- Publicar = setear `publicado_en=now()` + `activo=true`; despublicar = `publicado_en=null` (usar raw string body, NO kotlinx serialization, porque `explicitNulls=false` lo omitiría)
- Serialización para escrituras Supabase: usar `Json { encodeDefaults=true; explicitNulls=false }` (llamado `sbJson` en AdminV1Routes)

---

## 6. Capa KMP — cómo está conectado actualmente

### BackendRoutes.kt (data/remote/api/)
```kotlin
object BackendRoutes {
    const val HEALTH = "/health"
    const val V1_ESPECIALIDADES = "/v1/especialidades"
    const val V1_EXAMENES = "/v1/examenes"
    fun url(baseUrl: String, path: String) = baseUrl.trimEnd('/') + path
    fun v1ExamenPreguntas(examenId: String) = "/v1/examenes/$examenId/preguntas"
}
```
**PENDIENTE**: añadir constantes para `/api/v1/admin/*` y `/api/v1/app/*`.

### Repositorios implementados (data/repository/)
- `KtorContentRepositoryImpl` — `/content/*` (exámenes, preguntas legacy)
- `KtorNewsRepositoryImpl` — `/content/noticias`
- `KtorTagsRepositoryImpl` — `/content/etiquetas`
- `KtorCommunitiesRepositoryImpl` — `/content/comunidades`
- `KtorTagSearchRepositoryImpl` — búsqueda global
- `KtorAdminOperationsRepositoryImpl` — operaciones admin legacy
- `KtorConnectionDiagnosticsRepositoryImpl` — diagnóstico

**PENDIENTE**: No existen aún repositorios KMP para `/api/v1/admin/*` ni `/api/v1/app/*` (especialidades V1, examenes V1, preguntas V1, importaciones, intentos, estadisticas, etc.).

### Auth en repositorios KMP
```kotlin
// Patrón en todos los Ktor repos:
private fun HttpRequestBuilder.backendHeaders() {
    val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
        ?: appConfig.backendToken.takeIf { it.isNotBlank() }
        ?: return
    header(HttpHeaders.Authorization, "Bearer $token")
}
```
- `appConfig.backendToken` = `OPOSICONTROL_BACKEND_TOKEN` (para rutas `/content/*` y `/api/v1/admin/*`)
- Para `/api/v1/app/*` el token debe ser el JWT de Supabase del usuario logueado (`sessionLocalDataSource.currentSession()?.accessToken`)

---

## 7. Patrón MVI de features

Cada feature en `features/<nombre>/src/commonMain/kotlin/.../presentation/`:
```
<Nombre>Intent.kt   — sealed interface con todos los intents
<Nombre>State.kt    — data class con UiAsync<T> para cada dato
<Nombre>ViewModel.kt — class extends MviViewModel<State, Intent>
<Nombre>Screen.kt   — @Composable que observa state, emite intents
di/<Nombre>FeatureModule.kt — Koin module
```

Registrados en:
- `settings.gradle.kts` — `include(":features:<nombre>")`
- `composeApp/build.gradle.kts` — `implementation(projects.features.<nombre>)`
- `composeApp/.../bootstrap/AppModules.kt` — `<nombre>FeatureModule`
- `composeApp/.../navigation/AppDestination.kt` — enum entry en `AdminSection`
- `composeApp/.../root/AppRoot.kt` — case en when para navegar
- `composeApp/.../root/GlobalTagSearch.kt` — case en `searchSectionKey()` (exhaustive when)

Features recientes añadidas: `:features:tags`, `:features:communities`

---

## 8. Módulos domain recientes

### Tags
- `domain/model/` — usa `Tag` (en `TagSearchModels.kt`, campos: id, name, slug, origin)
- `domain/repository/TagsRepository.kt` — `observeTags()`, `refreshTags()`, `upsertTag(Tag)`, `deleteTag(id)`
- `domain/usecase/tags/TagUseCases.kt`

### Communities
- `domain/model/Community.kt` — `data class Community(id, name, code, slug, isActive, sortOrder, createdAt, updatedAt)`
- `domain/repository/CommunitiesRepository.kt` — `observeCommunities()`, `refreshCommunities()`, `upsertCommunity(Community)`, `deleteCommunity(id)`
- `domain/usecase/communities/CommunityUseCases.kt`

### Validación
- `domain/validation/ValidationResult.kt` — DSL: `validate { require(condition) { FieldError("field", "msg") } }`
- `domain/validation/CatalogValidators.kt` — `EtiquetaValidator` (MAX_NAME=80), `ComunidadValidator` (MAX_NAME=120, MAX_CODE=10)

---

## 9. Serialización Ktor client (KMP)

`ApiClientFactory` usa Kotlin Serialization. La config por defecto omite campos con valor por defecto (`encodeDefaults=false`) y nulls (`explicitNulls=false`). Los DTOs cliente tienen `= ""` / `= null` defaults para absorber campos ausentes del JSON.

---

## 10. Próximos pasos naturales (no implementados)

1. **BackendRoutes.kt** — añadir constantes `/api/v1/admin/*` y `/api/v1/app/*`
2. **Repositorios V1 admin KMP** — `KtorEspecialidadesRepositoryImpl`, `KtorExamenesV1RepositoryImpl`, etc. siguiendo el patrón de `KtorContentRepositoryImpl`
3. **Repositorios V1 app KMP** — `KtorExamenesAppRepositoryImpl`, `KtorIntentosRepositoryImpl`, `KtorEstadisticasRepositoryImpl`, etc.
4. **Features V1** — `:features:especialidades`, `:features:examenes-admin`, `:features:importaciones`, `:features:intentos`
5. **Auth KMP app** — el JWT del usuario viene de `sessionLocalDataSource.currentSession()?.accessToken`; las rutas `/api/v1/app/*` ya lo leen del header `Authorization: Bearer`

---

## 11. Comandos útiles

```bash
# Arrancar backend (lee .env automáticamente)
./gradlew :backend:ktor:run

# Test rápido JWT (token fresco desde Supabase Auth)
SERVICE_KEY=$(grep SUPABASE_SERVICE_ROLE_KEY .env | cut -d= -f2)
TOKEN=$(curl -s -X POST "https://ndiooyyqtaeysnedywer.supabase.co/auth/v1/token?grant_type=password" \
  -H "apikey: $SERVICE_KEY" -H "Content-Type: application/json" \
  -d '{"email":"jwt.test@oposicontrol.dev","password":"JwtTest2026!"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/app/home

# Test admin (token fijo, leído desde .env)
BTOKEN=$(grep OPOSICONTROL_BACKEND_TOKEN .env | cut -d= -f2)
curl -H "Authorization: Bearer $BTOKEN" http://localhost:8080/api/v1/admin/especialidades

# Dev sin JWT (solo si OPOSICONTROL_ALLOW_DEV_USER=true)
curl -H "X-Usuario-Id: b69164ad-1851-4dea-ab2a-572be7cef33f" http://localhost:8080/api/v1/app/home
```

---

## 12. Seguridad — restricciones permanentes

- `SUPABASE_SERVICE_ROLE_KEY` y `OPOSICONTROL_BACKEND_TOKEN` solo en `.env` (chmod 600, gitignored). NUNCA en código KMP ni en git.
- `OPOSICONTROL_ALLOW_DEV_USER=true` es DEV ONLY. Debe ser `false` en producción.
- La app KMP NUNCA conecta directamente a Supabase para escrituras.

---

## 13. Sesión 2026-06-18 — fixes, diagnóstico y plan de migración admin

### 13.1 Fixes aplicados en esta sesión

#### iOS real device / configuración local

Archivos tocados:
- `iosApp/Configuration/Config.xcconfig`
- `iosApp/iosApp/Info.plist`
- `composeApp/src/iosMain/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSource.ios.kt`
- `.gitignore`

Cambios relevantes:
- Para iPhone físico se dejó de usar `localhost`; `BASE_URL` apunta a la IP LAN del Mac (`LOCAL_BACKEND_HOST` en `iosApp/Configuration/Config.xcconfig`, actualmente `192.168.1.52`). OJO: es la IP DHCP del Mac y cambia al reconectar a la WiFi — si el iPhone da `NSURLErrorDomain Code=-1004 "Could not connect"`, actualizar `LOCAL_BACKEND_HOST` con `ipconfig getifaddr en0` y reconstruir en Xcode (es build-time vía Info.plist).
- `Info.plist` expone `BASE_URL`, flags locales y permisos de red local (`NSAllowsLocalNetworking`, `NSLocalNetworkUsageDescription`).
- `PlatformRuntimeConfigSource.ios.kt` resuelve configuración en este orden:
  1. `NSProcessInfo.processInfo.environment`
  2. `Info.plist`
  3. `LocalSecrets.env` empaquetado localmente en el bundle
- Se añadió lectura de `LocalSecrets.env` para `BACKEND_TOKEN` sin codificar secretos en fuentes.
- `.gitignore` ignora:
  - `iosApp/Configuration/Local.xcconfig`
  - `iosApp/iosApp/LocalSecrets.plist`
  - `iosApp/iosApp/LocalSecrets.env`
- Se añadió link de SQLite en iOS (`-lsqlite3`) para resolver símbolos `sqlite3_*` en arm64.

Restricción permanente:
- `LocalSecrets.env` puede existir localmente para desarrollo, pero nunca debe entrar en git ni imprimirse en logs.

#### Backend Ktor admin V1

Archivo tocado:
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt`

Fix aplicado:
- `AdminExamenRow.precioTickets` acepta `null`:
  - antes: `Int = 0`
  - ahora: `Int? = null`
- El mapper expone `precioTickets = precioTickets ?: 0`.

Motivo:
- Supabase real devuelve `precio_tickets: null` en al menos una fila de `examenes`.
- El backend devolvía `400 Bad Request` en `GET /api/v1/admin/examenes` por error de Kotlin Serialization.
- La app mostraba "Falla" aunque la red y el token admin estaban bien.

Nota operativa:
- `POST /admin/backend/restart` reinicia conexión/estado interno, pero NO recarga bytecode Kotlin.
- Para aplicar cambios de código hay que parar el proceso Java que escucha en `8080` y arrancar de nuevo con:

```bash
./gradlew :backend:ktor:run
```

#### KMP data / errores legacy legibles

Archivo tocado:
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorAdminOperationsRepositoryImpl.kt`

Cambio aplicado:
- Se añadió `HttpResponse.checkedBody<T>(endpoint)`.
- Las llamadas legacy `/admin/*` de `AdminOperationsRepository` ya no hacen `.body<List<...>>()` directamente si el status HTTP no es 2xx.

Motivo:
- Cuando el backend legacy devolvía `500` con JSON `{ "error": ... }`, Ktor Client intentaba parsearlo como lista `[...]`.
- El error visible era engañoso: `Expected start of the array '[' but had '{'`.
- Ahora el error conserva status, endpoint y preview del body.

#### Backend Ktor legacy / hardening Fase 0

Archivo tocado:
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminGateway.kt`

Cambio aplicado:
- Se añadió fallback controlado para lecturas legacy cuando Supabase devuelve error de esquema inexistente (`PGRST205`, `Could not find the table`, `schema cache`).
- Los listados legacy devuelven `200 []` si la tabla antigua no existe.
- `/dashboard/metrics` devuelve métricas vacías controladas si depende de tablas legacy inexistentes.
- Otros errores distintos a esquema legacy siguen subiendo; no se ocultan fallos reales.

Endpoints cubiertos por el fallback:
- `GET /admin/users`
- `GET /admin/roles`
- `GET /admin/audit`
- `GET /admin/news-types`
- `GET /admin/exam-access`
- `GET /admin/ticket-wallets`
- `GET /admin/ticket-wallets/{userId}/transactions`
- `GET /admin/support/conversations`
- `GET /admin/support/conversations/{conversationId}/messages`
- `GET /admin/moderation/actions`
- `GET /dashboard/metrics`

### 13.2 Verificación realizada

Comandos que pasaron:

```bash
./gradlew :backend:ktor:compileKotlin
./gradlew :composeApp:compileKotlinIosArm64
```

Endpoints V1 admin verificados tras reinicio real de Ktor:

| Endpoint | Estado |
|---|---|
| `GET /health` | `200` |
| `GET /api/v1/admin/especialidades` | `200` |
| `GET /api/v1/admin/examenes` | `200` |
| `GET /api/v1/admin/feedback/preguntas` | `200` |
| `GET /api/v1/admin/auditoria` | `200` |

Endpoints legacy que antes fallaban contra el esquema real y ahora quedan estabilizados:

| Endpoint | Estado observado | Causa |
|---|---:|---|
| `GET /admin/users` | `200 []` | Fallback por tabla legacy `app_role` / `usuario` inexistente |
| `GET /admin/roles` | `200 []` | Fallback por tabla legacy `app_role` inexistente |
| `GET /admin/exam-access` | `200 []` | Fallback por tabla legacy `usuario` inexistente |
| `GET /admin/ticket-wallets` | `200 []` | Fallback por tabla legacy `usuario` inexistente |
| `GET /admin/support/conversations` | `200 []` | Fallback por tabla legacy `usuario` inexistente |
| `GET /admin/moderation/actions` | `200 []` | Fallback por tabla legacy `usuario` inexistente |
| `GET /admin/news-types` | `200 []` | Fallback si falta tabla legacy |
| `GET /dashboard/metrics` | `200` con ceros | Fallback si faltan tablas legacy |

Conclusión:
- La conexión V1 admin funciona.
- Los listados legacy ya no deben provocar `500` por tabla inexistente, pero siguen pendientes de migración real.

### 13.3 Inventario de pantallas y riesgo de 500

Revisión con subagente realizada el 2026-06-18. No editó archivos.

| Pantalla / feature | Módulo | Repositorio/API | Endpoint(s) | Estado |
|---|---|---|---|---|
| Dashboard | `features:dashboard` | `ContentDashboardRepositoryImpl` | mixto: admin V1 + tienda directa Supabase | Riesgo medio |
| Usuarios | `features:users` | `UsersRepositoryImpl` → `UsersApi` | `/admin/users` | Alto: legacy roto |
| Roles | `features:roles` | `RolesRepositoryImpl` → `RolesApi` | `/admin/roles` | Alto: legacy roto |
| Especialidades | `features:specialties`, `features:exams` | `ContentRepository` → `KtorAdminV1ContentRepositoryImpl` | `/api/v1/admin/especialidades` | Bajo |
| Exámenes | `features:exams` | `KtorAdminV1ContentRepositoryImpl` | `/api/v1/admin/examenes` | Bajo |
| Preguntas / recursos | `features:exams` | `KtorAdminV1ContentRepositoryImpl` | `/api/v1/admin/preguntas`, recursos, importaciones | Bajo |
| Accesos | `features:exam-access` | `AdminOperationsRepository` | `/admin/exam-access` | Alto: legacy roto |
| Tickets | `features:tickets` | `AdminOperationsRepository` | `/admin/ticket-wallets` | Alto: legacy roto |
| Soporte | `features:support` | `AdminOperationsRepository` | `/admin/support/conversations` | Alto: legacy roto |
| Moderación | `features:moderation` | `AdminOperationsRepository` | `/admin/moderation/actions` | Alto: legacy |
| Ajustes | `features:settings` | `SettingsApi` | `/admin/settings` | Bajo-medio; no toca Supabase |
| Auditoría | `features:audit` | `AuditApi` | lectura `/api/v1/admin/auditoria`, escritura legacy `/admin/audit` | Medio |
| Noticias | `features:news` | `KtorNewsRepositoryImpl` | `/content/noticias` | Alto: legacy |
| Tipos de noticia | `features:news-types` | `AdminOperationsRepository` | `/admin/news-types` | Alto: legacy |
| Etiquetas | `features:tags` | `KtorTagsRepositoryImpl` | `/content/etiquetas` | Alto: legacy |
| Comunidades | `features:communities` | `KtorCommunitiesRepositoryImpl` | `/content/comunidades` | Alto: legacy |
| Tienda | `features:store` | `SupabaseCommerceRepositoryImpl` | Supabase REST directo | Alto: viola regla KMP no escribe directo |
| Login/Auth | `features:auth` | `AuthApi` | Supabase Auth | Aceptable; auth directa es caso especial |
| Búsqueda global | `GlobalTagSearch` | `KtorTagSearchRepositoryImpl` | legacy deshabilitado | Bajo actual, pendiente migrar |

### 13.4 Decisión técnica: NO rehacer Ktor desde cero

Decisión:
- No conviene reescribir Ktor desde cero.
- La superficie `/api/v1/admin/*` ya funciona para contenido core y está alineada con el esquema real.
- La estrategia correcta es "strangler migration":
  1. Mantener `/api/v1/admin/*` como superficie nueva estable.
  2. Migrar pantallas legacy una a una.
  3. Dejar `/admin/*` como compatibilidad temporal o convertirlo en wrapper hacia V1.
  4. Eliminar consultas a tablas antiguas (`usuario`, `app_role`, nombres singulares con PK int).

Motivo:
- Rehacer todo Ktor retrasaría lo que ya está verificado.
- Los 500 actuales están localizados en `SupabaseAdminGateway` y rutas legacy, no en la arquitectura V1.

### 13.5 Plan inmediato para eliminar 500

#### Fase 0 — estabilización rápida

Objetivo:
- Ninguna pantalla debe romper por `500` causado por tablas legacy inexistentes.

Acciones:
1. En backend `AdminGateway.kt`, evitar que endpoints legacy consulten tablas inexistentes.
2. Para módulos sin esquema V1 listo, devolver respuesta estable:
   - `200 []` para listados todavía no migrados, o
   - `501 not_implemented` con JSON controlado si la UI puede mostrarlo correctamente.
3. En KMP, aplicar patrón `checkedBody()` a APIs que aún hacen `.body()` directo:
   - `UsersApi`
   - `RolesApi`
   - `DashboardApi`
   - `SettingsApi` si se amplía
   - escrituras legacy de `AuditApi`

Preferencia:
- Para UX admin actual, usar `200 []` temporal en listados secundarios es más pragmático que `501`, porque evita estados de error falsos mientras se migra.

#### Fase 1 — usuarios y roles reales

Objetivo:
- Sustituir `/admin/users` y `/admin/roles` por contratos V1 o adaptar su implementación al esquema real.

Tablas reales candidatas:
- `perfiles_usuario`
- `roles_usuario` si existe y está alineada con la base real
- `auth.users` solo via backend/service-role si hace falta enriquecer datos

Pendiente:
- Confirmar estructura real de `roles_usuario` antes de implementar escritura.

#### Fase 2 — accesos, tickets, soporte y moderación

Objetivo:
- Sacarlos de tablas antiguas:
  - `usuario`
  - `acceso_examen_usuario`
  - `cartera_tickets`
  - `transaccion_ticket`
  - soporte/moderación legacy

Opciones:
- Si existen tablas reales equivalentes, mapearlas.
- Si no existen, crear endpoints V1 que devuelvan vacío controlado hasta diseñar el esquema.

#### Fase 3 — catálogos secundarios

Migrar:
- `features:tags` de `/content/etiquetas` a `/api/v1/admin/etiquetas` o equivalente.
- `features:communities` de `/content/comunidades` a `/api/v1/admin/comunidades`.
- `features:news-types` de `/admin/news-types` a V1.
- `features:news` de `/content/noticias` a V1.

#### Fase 4 — tienda

Objetivo:
- Eliminar `SupabaseCommerceRepositoryImpl` directo desde KMP.
- Crear backend Ktor:
  - `/api/v1/admin/commerce/items`
  - `/api/v1/admin/commerce/offers`
  - `/api/v1/admin/commerce/resources`
  - `/api/v1/admin/commerce/campaigns`

Regla:
- La app KMP no debe escribir ni leer administración directamente desde Supabase REST.

#### Fase 5 — dashboard y búsqueda global

Dashboard:
- Debe depender solo de repositorios V1 y estados vacíos controlados.

Búsqueda global:
- Rehabilitar sobre ruta nueva:
  - `/api/v1/admin/search`
  - o endpoints específicos por entidad.

### 13.6 Archivos creados/modificados relevantes en esta línea de trabajo

Backend:
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt`
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AppV1Routes.kt`
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/Application.kt`
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminGateway.kt`

KMP data:
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/BackendRoutes.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorAdminV1ContentRepositoryImpl.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorAdminOperationsRepositoryImpl.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorConnectionDiagnosticsRepositoryImpl.kt`

iOS/config:
- `iosApp/Configuration/Config.xcconfig`
- `iosApp/iosApp/Info.plist`
- `composeApp/src/iosMain/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSource.ios.kt`
- `.gitignore`

Módulos/features recientes ya presentes en el workspace:
- `features/tags`
- `features/communities`
- `domain/usecase/tags`
- `domain/usecase/communities`
- `domain/validation`

### 13.7 Estado actual para el próximo Codex

Estado bueno:
- Backend V1 admin de contenido core responde `200`.
- iOS real device puede llegar al backend usando IP LAN.
- Token admin local para iOS se inyecta mediante archivo local ignorado.
- Compila iOS arm64.
- Fase 0 backend aplicada: listados `/admin/*` legacy devuelven vacío controlado en vez de `500` cuando falta una tabla antigua.

Estado pendiente:
- Escrituras `/admin/*` legacy pueden seguir fallando si intentan escribir en tablas antiguas.
- `UsersApi` y `RolesApi` aún deben recibir protección `checkedBody()` o migración V1.
- Tienda sigue usando Supabase directo desde KMP y debe migrarse a Ktor.
- Noticias, etiquetas y comunidades siguen en `/content/*`.

Siguiente paso recomendado:
1. Migrar `UsersApi`/`RolesApi` a errores controlados.
2. Añadir endpoints V1 para usuarios/roles o wrappers sobre `perfiles_usuario`.
3. Migrar escrituras legacy o bloquearlas con respuesta controlada hasta tener esquema V1.
4. Volver a ejecutar:

```bash
./gradlew :backend:ktor:compileKotlin
./gradlew :composeApp:compileKotlinIosArm64
```

---

## 14. TAREA CODEX — Dashboard con gráficas de rendimiento de usuarios (2026-06-18)

> Estado anterior: compilación OK, endpoints V1 etiquetas/comunidades/dashboard/usuarios verificados.

### Cambios ya hechos (Claude, NO los rehaga Codex)

**Mobile** (`MobileContentScreen.kt`):
- Añadida `val mobileVisible: Boolean = true` al enum `MobileContentView`
- `Noticias` tiene `mobileVisible = false` → excluida de tabs y swipe en móvil
- `companion object { val mobileTabs = entries.filter { it.mobileVisible } }` 
- Tab bar y `previousView()`/`nextView()` usan `mobileTabs` en lugar de `entries`

**Desktop** (`DesktopModerationRoot.kt`):
- Secciones añadidas: `SPECIALTIES, TAGS, EXAMS, QUESTIONS_RESOURCES, NEWS` (antes solo moderación/admin)
- Noticias sigue accesible solo desde desktop ✅

### Lo que DEBE hacer Codex

#### A. Backend: ampliar `/api/v1/admin/dashboard` con analytics de usuarios

Fichero: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt`

La función `getDashboardMetrics()` en `AdminV1Gateway` ya existe. Debe ampliarse con datos de rendimiento.
Usar las siguientes queries PostgREST (vía `request()`):

```
Tablas Supabase reales (esquema verificado):

intentos_examen: id, usuario_id, examen_id, estado, iniciado_en, finalizado_en,
  total_preguntas, total_correctas, total_falladas, total_sin_responder,
  porcentaje_acierto, puntuacion_final, opositickets_ganados, modo_estudio

respuestas_intento: id, intento_id, pregunta_id, usuario_id, examen_id,
  es_correcta (boolean), respondida_en, tiempo_respuesta_segundos,
  etiqueta_principal_id, tipo_error_usuario, marcada_para_repasar
```

Queries a añadir en `getDashboardMetrics()`:

```kotlin
// 1. Conteo de intentos completados
val totalIntentos = count("intentos_examen", "estado=eq.completado")

// 2. Usuarios activos (últimos 30 días) — count distinct no es directo en PostgREST,
//    usa: request("GET", "/rest/v1/intentos_examen?select=usuario_id&estado=eq.completado&iniciado_en=gte.${thirtyDaysAgo}").decodeList<UsuarioIdRow>().map { it.usuarioId }.distinct().size
//    donde UsuarioIdRow(@SerialName("usuario_id") val usuarioId: String)

// 3. Puntuación promedio: fetch porcentaje_acierto list, avg en JVM
val scores = request("GET", "/rest/v1/intentos_examen?select=porcentaje_acierto&estado=eq.completado", null).decodeList<ScoreRow>()
val avgScore = if (scores.isEmpty()) 0.0 else scores.mapNotNull { it.puntuacion }.average()

// 4. Serie temporal últimos 30 días (intentos por día):
//    fetch iniciado_en de intentos completados, group en JVM por fecha (LocalDate)
//    max 500 registros. PostgREST: ?select=iniciado_en&estado=eq.completado&order=iniciado_en.asc&limit=500

// 5. Tasa de error global:
//    request("GET", "/rest/v1/respuestas_intento?select=es_correcta", null).decodeList<EsCorrectaRow>()
//    errorRate = total_false / total * 100
```

DTOs privados a añadir en AdminV1Routes.kt:
```kotlin
@Serializable private data class ScoreRow(@SerialName("porcentaje_acierto") val puntuacion: Double? = null)
@Serializable private data class UsuarioIdRow(@SerialName("usuario_id") val usuarioId: String = "")
@Serializable private data class EsCorrectaRow(@SerialName("es_correcta") val esCorrecta: Boolean = true)
@Serializable private data class IniciadoEnRow(@SerialName("iniciado_en") val iniciadoEn: String = "")
```

Ampliar `AdminDashboardDto` (que YA existe en AdminV1Routes.kt línea ~684 aprox):
```kotlin
@Serializable
data class AdminDashboardDto(
    // ya existentes:
    val totalSpecialties: Int = 0,
    val totalExams: Int = 0,
    val publishedExams: Int = 0,
    val totalQuestions: Int = 0,
    val lastUpdatedAt: String = "",
    val draftExams: Int = 0,
    // NUEVOS:
    val totalAttempts: Int = 0,
    val activeUsers30d: Int = 0,
    val avgScore: Double = 0.0,
    val globalErrorRate: Double = 0.0,
    val attemptsPerDay: List<DayCountDto> = emptyList(),
)

@Serializable data class DayCountDto(val date: String, val count: Int)  // añadir también
```

#### B. KMP: actualizar DashboardMetrics + DashboardApi + DashboardScreen

**1. `domain/src/commonMain/kotlin/com/oposicontrol/admin/domain/model/DashboardMetrics.kt`**

Añadir campos (NO eliminar los existentes):
```kotlin
val totalAttempts: Int = 0,
val activeUsers30d: Int = 0,
val avgScore: Double = 0.0,        // 0-100
val globalErrorRate: Double = 0.0,  // 0-100
val attemptsPerDay: List<DayCount> = emptyList(),
```

Y añadir data class:
```kotlin
data class DayCount(val date: String, val count: Int)
```

**2. `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/DashboardApi.kt`**

Añadir DTO privado local para deserializar y mapear al modelo:
```kotlin
@Serializable private data class AdminDashboardV1Dto(
    val totalSpecialties: Int = 0, val totalExams: Int = 0, val publishedExams: Int = 0,
    val totalQuestions: Int = 0, val lastUpdatedAt: String = "", val draftExams: Int = 0,
    val totalAttempts: Int = 0, val activeUsers30d: Int = 0,
    val avgScore: Double = 0.0, val globalErrorRate: Double = 0.0,
    val attemptsPerDay: List<DayCountV1Dto> = emptyList(),
)
@Serializable private data class DayCountV1Dto(val date: String = "", val count: Int = 0)
```

`getMetrics()` debe hacer `.body<AdminDashboardV1Dto>()` y mapear a `DashboardMetrics`.

IMPORTANTE: el `DashboardMetricsDto` en `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/dto/` puede estar desactualizado — ignorarlo y deserializar directamente al domain model mapeando desde `AdminDashboardV1Dto`.

**3. `features/dashboard/src/commonMain/kotlin/com/oposicontrol/admin/features/dashboard/presentation/DashboardScreen.kt`**

Añadir una sección de gráficas en `DashboardScreen` JUSTO ANTES de las quick actions, dentro del `Column` scrollable. Usar SOLO Compose sin dependencias externas.

Gráficas a implementar (barras simples con Box/Row en Compose):

**Gráfica 1 — Intentos por día (últimos días):**
```kotlin
@Composable
fun AttemptsBarChart(attemptsPerDay: List<DayCount>, modifier: Modifier = Modifier) {
    // Bar chart horizontal usando Row + Box con altura proporcional
    // max = attemptsPerDay.maxOfOrNull { it.count } ?: 1
    // cada barra: Box(modifier = Modifier.height((count * 80 / max).dp).width(12.dp).background(color))
    // label: fecha corta (últimos 4 chars o "dd/MM")
}
```

**Gráfica 2 — Tarjetas de métricas clave:**
```kotlin
// 4 GlassSurface cards en grid 2x2:
// • "Usuarios activos (30d)" → activeUsers30d
// • "Intentos totales" → totalAttempts
// • "Puntuación media" → "${avgScore.roundToInt()}%"  
// • "Tasa de error" → "${globalErrorRate.roundToInt()}%"
```

Estilo: usar `GlassSurface`, `GlassTheme.tokens`, colores semánticos ya en el theme.
NO añadir nuevas dependencias de charting (MPAndroidChart, Vico, etc).
Las barras son simples Box coloreados con Compose básico.

#### C. Verificación final

```bash
./gradlew :backend:ktor:compileKotlin
./gradlew :composeApp:compileKotlinIosArm64
# reiniciar backend y comprobar:
curl -H "Authorization: Bearer TOKEN" http://localhost:8080/api/v1/admin/dashboard
# debe incluir: totalAttempts, activeUsers30d, avgScore, globalErrorRate, attemptsPerDay
```

### Precauciones

- `ContentDashboardRepositoryImpl.kt` es la impl actual de `DashboardRepository` — sigue siendo la impl de DI. La fuente de datos para los campos nuevos viene de `DashboardApi.getMetrics()` que llama al backend V1. NO crear un nuevo repositorio, extender `ContentDashboardRepositoryImpl` para que llame también a `DashboardApi`.
- Los campos antiguos de `DashboardMetrics` (commerce, news, alerts, etc.) NO se tocan.
- Compilar con `./gradlew :backend:ktor:compileKotlin :composeApp:compileKotlinIosArm64` antes de dar por terminado.
- El `AdminDashboardDto` ya existe — Codex solo lo amplía, no lo redeclara.

### 14.1 Implementación Codex realizada

Backend:
- `AdminDashboardDto` ampliado con:
  - `totalAttempts`
  - `activeUsers30d`
  - `avgScore`
  - `globalErrorRate`
  - `attemptsPerDay`
- Añadido `DayCountDto`.
- Añadidos DTOs privados de lectura PostgREST:
  - `ScoreRow`
  - `UsuarioIdRow`
  - `EsCorrectaRow`
  - `IniciadoEnRow`
- `AdminV1Gateway.getDashboardMetrics()` ahora calcula:
  - intentos completados
  - usuarios activos distintos en los últimos 30 días
  - puntuación media
  - intentos por día para 30 días
  - tasa global de error
- Las métricas analíticas usan `runCatching` y devuelven cero/lista vacía si falla una consulta secundaria, para evitar `500` en el dashboard por una métrica no crítica.
- Los campos nuevos de `AdminDashboardDto` usan `@EncodeDefault` para que el JSON los incluya aunque el valor sea `0` o `0.0`.

KMP/domain/data:
- `DashboardMetrics` conserva todos los campos antiguos y añade las métricas de rendimiento.
- Añadido `DayCount` en dominio.
- `DashboardApi.getMetrics()` deserializa directamente un DTO local V1 (`AdminDashboardV1Dto`) y devuelve `DashboardMetrics`; el DTO legacy `DashboardMetricsDto` queda ignorado para este endpoint.
- `RealDashboardDataSource` ya no aplica el mapper legacy.
- `ContentDashboardRepositoryImpl` sigue siendo la implementación activa de `DashboardRepository`; ahora inyecta `DashboardApi` y mezcla las métricas nuevas del backend V1 con las métricas locales existentes de contenido, tienda, noticias, alertas y actividad.
- `DataModule` inyecta `DashboardApi` en `ContentDashboardRepositoryImpl`.

UI dashboard:
- `DashboardScreen.kt` añade `DashboardPerformanceSection` justo antes de `DashboardQuickActions`.
- Añadido `AttemptsBarChart` con barras Compose (`Box`/`Row`) sin dependencias externas.
- Añadidas 4 tarjetas `GlassSurface` en grid 2x2:
  - usuarios activos 30d
  - intentos totales
  - puntuación media
  - tasa de error
- La preview del dashboard incluye datos de ejemplo para la nueva sección.

Archivos modificados en esta tarea:
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt`
- `domain/src/commonMain/kotlin/com/oposicontrol/admin/domain/model/DashboardMetrics.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/DashboardApi.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/source/RealDashboardDataSource.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/ContentDashboardRepositoryImpl.kt`
- `data/src/commonMain/kotlin/com/oposicontrol/admin/data/di/DataModule.kt`
- `features/dashboard/src/commonMain/kotlin/com/oposicontrol/admin/features/dashboard/presentation/DashboardScreen.kt`
- `CODEX_CONTEXT.md`
