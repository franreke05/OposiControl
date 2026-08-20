# OposiBots User Admin CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add admin-only HTTP endpoints to OposiControl's existing Ktor backend to list, view, create, update, and hard-delete OposiBot end-user accounts (`public.usuario`) directly against Supabase.

**Architecture:** New `OposiBotsUserAdminGateway` (interface + Supabase-backed implementation, Ktor `HttpClient(Java)` — mirrors the already-tested `OposiBotsAuthGateway` pattern, not the untestable raw-JDK-`HttpClient` pattern used by `AdminV1Gateway`) talks to PostgREST (`/rest/v1/usuario`) for CRUD on the profile row and to Supabase's Auth Admin API (`/auth/v1/admin/users`) for the underlying identity, both using the service-role key. A new `OposiBotsUserAdminRoutes.kt` exposes 5 endpoints under `/api/v1/admin/oposibots/usuarios`, mounted inside the existing `authenticate("gateway-auth")` block in `Application.kt`, next to `adminV1Routes(adminV1Gateway)`.

**Tech Stack:** Kotlin, Ktor server (Netty), Ktor client (Java engine), kotlinx.serialization, Supabase (PostgREST + GoTrue Admin API), `kotlin.test` + `ktor-client-mock` + `ktor-server-test-host`.

## Global Constraints

- Target users: OposiBot's own end-users only (`public.usuario`), **not** `perfiles_usuario` (a different, pre-existing admin surface at `/api/v1/admin/usuarios` — do not touch it or its gateway methods).
- Delete = **hard delete** (confirmed with the user): remove the `public.usuario` row **and** the underlying Supabase Auth identity (`auth.users`). No soft-delete path in this feature (the `deleted_at` column stays in the schema for other consumers but this feature never sets it).
- Admin auth = reuse the existing `authenticate("gateway-auth")` mechanism exactly as-is (static `OPOSICONTROL_BACKEND_TOKEN` bearer, or a Supabase JWT with an admin role via `AppV1Gateway.hasAdminRole`). No new auth mechanism.
- `especialidad_id` (integer FK to a non-existent `public.especialidad` table) is **out of scope** — never read or write it. Only `especialidad_registro` (free text) is exposed, matching what OposiBot's real registration flow actually populates.
- These routes only exist when the backend runs with `SERVER_PROFILE=combined` (default/unset) — a `SERVER_PROFILE=consumer` deployment (what OposiBot's mobile client talks to for `/v1/auth/*`) never mounts them, by design (`Application.kt:65-71`, confirmed by reading the code).
- Reuse existing conventions exactly: `data class XResponse(val data: ...)` envelope, `sbJson`-style `Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }` for writes, `StatusPages`-driven error mapping (`IllegalArgumentException` → 400, `IllegalStateException` → 500, hand-coded `NotFoundException`/404 for missing resources — see `Application.kt:96-125`).
- No new environment variables — reuse `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY` (already used by `AdminV1Gateway.fromEnvironment`).

## Context

OposiBot (a separate Kotlin Multiplatform mobile app, `c:\Users\franr\OneDrive\Desktop\OposiBot`) already has a client-side auth flow (`RealAuthApi.kt`) wired against `/v1/auth/*` endpoints that OposiControl's backend already implements (`OposiBotsAuthRoutes.kt`, mounted via `SERVER_PROFILE=consumer`). The user now wants an admin-side capability — create, view, edit, and hard-delete these OposiBot user accounts directly from the database — to live in OposiControl's existing Ktor backend, next to its other admin CRUD surfaces (`AdminV1Routes.kt`).

**Critical finding from research, must be resolved before Task 1 runs for real:** the migration that defines the current shape of `public.usuario` — `supabase/migrations/20260730120000_oposibots_auth_profile_rls.sql` — **has not been applied** to the only live Supabase project on this account (`oposibots-ui's Project`, id `ndiooyyqtaeysnedywer`, org `oposibots`). Verified directly:
- `list_organizations` → exactly one org (`oposibots`). `list_projects` → exactly one project (`ndiooyyqtaeysnedywer`) — there is nowhere else this could be running.
- `execute_sql` against `information_schema.tables` for `usuario`, `especialidad`, and every child table the archived schema doc (`docs/supabase/oposicontrol_full_schema_with_tag_search.sql`) describes as referencing `usuario.id` (`user_role`, `acceso_examen_usuario`, `cartera_tickets`, `compra_usuario`, `inventario_usuario`, `transaccion_ticket`, `support_conversation`, `support_message`, `support_participant`, `moderation_action`, `audit_log`) → **none of them exist**. Only `perfiles_usuario` and `roles_usuario` (the unrelated "V1" table family) exist.
- `list_migrations` → empty; nothing has ever been applied via Supabase's tracked migration history.

**Silver lining:** since `public.usuario` doesn't exist yet and none of its would-be child tables exist either, there is **no pre-existing hard-delete cascade problem** to solve — Task 1 creates a clean table with zero dependents. The elaborate FK-cascade concerns in the archived schema doc are aspirational, not live, and are explicitly out of scope for this plan (if/when those tables get built later, whoever adds them is responsible for either an `ON DELETE CASCADE` or extending this feature's delete path — noted as a comment in the code).

Task 1 of this plan applies that migration (after testing it on a Supabase branch, not directly against the main project) as an explicit, reviewable first step — **do not skip straight to Task 2 without it**, the gateway has nothing to talk to otherwise.

## File Structure

- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/OposiBotsUserAdminGateway.kt` (new) — the `OposiBotsUserAdminGateway` interface, its Supabase-backed implementation, row/DTO models, and the `fromEnvironment` factory. One file, one responsibility (data access for this one entity), matching how `OposiBotsAuthGateway.kt` is organized.
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/OposiBotsUserAdminRoutes.kt` (new) — the `fun Route.oposiBotsUserAdminRoutes(gateway: OposiBotsUserAdminGateway)` extension and its request DTOs, matching how `OposiBotsAuthRoutes.kt` is organized (routes separate from gateway, gateway is an interface parameter).
- `backend/ktor/src/test/kotlin/com/oposicontrol/backend/OposiBotsUserAdminGatewayTest.kt` (new) — `MockEngine`-based gateway tests, one per method.
- `backend/ktor/src/test/kotlin/com/oposicontrol/backend/OposiBotsUserAdminRoutesTest.kt` (new) — `testApplication` + fake-gateway route tests, one per endpoint plus validation/error cases.
- `backend/ktor/src/main/kotlin/com/oposicontrol/backend/Application.kt` (modify) — construct the gateway and mount the routes, same pattern as every other gateway/route pair.

---

### Task 1: Apply the `oposibots_auth_profile_rls` migration

**Files:**
- Read: `supabase/migrations/20260730120000_oposibots_auth_profile_rls.sql` (already exists, do not modify its contents — apply it verbatim)
- No new files

**Interfaces:**
- Produces: a live `public.usuario` table (columns: `id integer identity PK`, `auth_user_id uuid unique`, `email varchar`, `nombre varchar`, `created_at timestamptz not null default now()`, `updated_at timestamptz not null default now()`, `deleted_at timestamptz` — created by Step 2; plus `apellidos varchar(100)`, `username varchar(30)`, `especialidad_registro varchar(100)`, `perfil_completo boolean not null default false`, `perfil_completado_at timestamptz` — added by Step 3's migration; 11 columns total, deliberately no `especialidad_id`) that Task 2's gateway reads/writes.

- [x] **Step 1: Create a Supabase branch to test the migration safely** — SKIPPED per explicit user direction (2026-08-02/03): app is pre-launch with no production data, apply directly to `ndiooyyqtaeysnedywer` instead of a branch.

Using the Supabase MCP tools (or the Supabase CLI if working locally), create a development branch of project `ndiooyyqtaeysnedywer` before touching the main database:

```
mcp__claude_ai_Supabase__create_branch(project_id: "ndiooyyqtaeysnedywer", name: "oposibots-user-admin-crud")
```

- [x] **Step 2: Create the base `public.usuario` table (found missing during final review — the RLS migration only ALTERs an existing table, it never creates one)** — Applied directly to `ndiooyyqtaeysnedywer` (production), `success:true`.

`supabase/migrations/20260730120000_oposibots_auth_profile_rls.sql` starts with `alter table public.usuario add column if not exists ...` — it assumes the table already exists. It doesn't, on `ndiooyyqtaeysnedywer` (verified: `list_tables` returns no `usuario` row; `list_migrations` is empty). The only `create table` for it in this repo is `docs/supabase/oposicontrol_full_schema_with_tag_search.sql:21-30`, which also FKs `especialidad_id` to a `public.especialidad` table that doesn't exist either — and this plan's Global Constraints already rule `especialidad_id` out of scope. So create the base table WITHOUT that column (11 columns, not the reference doc's 12) rather than pull in an unrelated catalog table:

```
mcp__claude_ai_Supabase__apply_migration(
  project_id: "<branch_project_id_from_step_1>",
  name: "oposibots_usuario_base_table",
  query: "create table if not exists public.usuario (\n  id integer generated always as identity primary key,\n  auth_user_id uuid unique,\n  email character varying,\n  nombre character varying,\n  created_at timestamp with time zone not null default now(),\n  updated_at timestamp with time zone not null default now(),\n  deleted_at timestamp with time zone\n);"
)
```

- [x] **Step 3: Apply the RLS/columns/RPC migration to the branch** — Applied directly to `ndiooyyqtaeysnedywer` (production), `success:true`.

```
mcp__claude_ai_Supabase__apply_migration(
  project_id: "<branch_project_id_from_step_1>",
  name: "oposibots_auth_profile_rls",
  query: "<verbatim contents of supabase/migrations/20260730120000_oposibots_auth_profile_rls.sql>"
)
```

- [x] **Step 4: Verify the table and RPC exist on the branch** — Verified on production: 11 columns match, both RPCs (`oposibots_upsert_own_profile`, `oposibots_claim_oauth_credential`) present.

```
mcp__claude_ai_Supabase__execute_sql(
  project_id: "<branch_project_id>",
  query: "select column_name, data_type, is_nullable, column_default from information_schema.columns where table_schema='public' and table_name='usuario' order by ordinal_position;"
)
```

Expected: 11 rows (the base 7 columns from Step 2, plus `apellidos`, `username`, `especialidad_registro`, `perfil_completo`, `perfil_completado_at` from Step 3) — matching the column list in the Interfaces section above, minus `especialidad_id`.

```
mcp__claude_ai_Supabase__execute_sql(
  project_id: "<branch_project_id>",
  query: "select proname from pg_proc where proname = 'oposibots_upsert_own_profile';"
)
```

Expected: 1 row.

- [x] **Step 5: Stop and ask the user before merging to the main project** — Moot: user pre-authorized direct production application explicitly ("Quiero que lo hagas todo en produccion por ahora no hay datos..."), given before this step ran. Applied directly, no separate merge step needed since Steps 2-3 targeted `ndiooyyqtaeysnedywer` directly.

- [ ] **Step 6: Commit**

No code changes in this task (pure database migration) — nothing to commit to git. If the branch tooling leaves any local migration-tracking file changed, commit that alone:

```bash
git status
git add -A -- supabase/
git commit -m "chore: confirm oposibots_auth_profile_rls migration applied" --allow-empty
```

---

### Task 2: `OposiBotsUserAdminGateway` — interface, Supabase implementation, and tests

**Files:**
- Create: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/OposiBotsUserAdminGateway.kt`
- Test: `backend/ktor/src/test/kotlin/com/oposicontrol/backend/OposiBotsUserAdminGatewayTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (Task 1 only needs to have run against a database somewhere by the time Task 4's manual verification happens — these are unit tests against `MockEngine`, no live DB needed).
- Produces (used by Task 3):
  ```kotlin
  interface OposiBotsUserAdminGateway {
      suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto>
      suspend fun getUser(id: Int): OposiBotsUserAdminDto?
      suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto
      suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto?
      suspend fun deleteUser(id: Int): Boolean
  }

  data class OposiBotsUserAdminDto(
      val id: Int,
      val authUserId: String?,
      val email: String?,
      val nombre: String?,
      val apellidos: String?,
      val username: String?,
      val especialidadRegistro: String?,
      val perfilCompleto: Boolean,
      val perfilCompletadoAt: String?,
      val createdAt: String,
      val updatedAt: String,
  )

  data class OposiBotsUserAdminCreateRequest(
      val email: String,
      val password: String?,
      val nombre: String,
      val apellidos: String,
      val username: String?,
      val especialidadRegistro: String,
  )

  data class OposiBotsUserAdminUpdateRequest(
      val nombre: String? = null,
      val apellidos: String? = null,
      val username: String? = null,
      val especialidadRegistro: String? = null,
      val perfilCompleto: Boolean? = null,
  )
  ```
  `deleteUser` returns `false` if the user didn't exist (404 for the route to report), `true` on success. `getUser`/`updateUser` return `null` for "not found" the same way.

- [ ] **Step 1: Write the failing test for `listUsers`**

```kotlin
package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OposiBotsUserAdminGatewayTest {
    @Test
    fun listUsersQueriesUsuarioTableWithLimitOffsetAndOrder() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel(
                    """[{"id":1,"auth_user_id":"11111111-1111-1111-1111-111111111111","email":"a@example.com","nombre":"Ada","apellidos":"Lovelace","username":"ada","especialidad_registro":"Enfermeria","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val result = gateway.listUsers(limit = 50, offset = 0, search = null)

        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/rest/v1/usuario"))
        assertEquals("id,auth_user_id,email,nombre,apellidos,username,especialidad_registro,perfil_completo,perfil_completado_at,created_at,updated_at", requests.single().url.parameters["select"])
        assertEquals("50", requests.single().url.parameters["limit"])
        assertEquals("0", requests.single().url.parameters["offset"])
        assertEquals("created_at.desc", requests.single().url.parameters["order"])
        assertEquals("service-role-key", requests.single().headers["apikey"])
        assertEquals("Bearer service-role-key", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(1, result.size)
        assertEquals("ada", result.single().username)
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminGatewayTest"`
Expected: FAIL — `SupabaseOposiBotsUserAdminGateway` / `OposiBotsUserAdminGateway` unresolved reference (nothing exists yet).

- [ ] **Step 3: Write the complete gateway file — interface, row/DTO models, and all 5 methods**

Writing all 5 methods now (rather than stubbing 4 of them) avoids leaving the file in a non-compiling state between steps — every step from here on compiles and runs the full test file.

```kotlin
package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

interface OposiBotsUserAdminGateway {
    suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto>
    suspend fun getUser(id: Int): OposiBotsUserAdminDto?
    suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto
    suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto?
    suspend fun deleteUser(id: Int): Boolean
}

data class OposiBotsUserAdminDto(
    val id: Int,
    val authUserId: String?,
    val email: String?,
    val nombre: String?,
    val apellidos: String?,
    val username: String?,
    val especialidadRegistro: String?,
    val perfilCompleto: Boolean,
    val perfilCompletadoAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class OposiBotsUserAdminCreateRequest(
    val email: String,
    val password: String?,
    val nombre: String,
    val apellidos: String,
    val username: String?,
    val especialidadRegistro: String,
)

data class OposiBotsUserAdminUpdateRequest(
    val nombre: String? = null,
    val apellidos: String? = null,
    val username: String? = null,
    val especialidadRegistro: String? = null,
    val perfilCompleto: Boolean? = null,
)

private const val UsuarioSelectColumns =
    "id,auth_user_id,email,nombre,apellidos,username,especialidad_registro,perfil_completo,perfil_completado_at,created_at,updated_at"

@Serializable
private data class UsuarioRow(
    val id: Int,
    @SerialName("auth_user_id") val authUserId: String? = null,
    val email: String? = null,
    val nombre: String? = null,
    val apellidos: String? = null,
    val username: String? = null,
    @SerialName("especialidad_registro") val especialidadRegistro: String? = null,
    @SerialName("perfil_completo") val perfilCompleto: Boolean = false,
    @SerialName("perfil_completado_at") val perfilCompletadoAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toDto() = OposiBotsUserAdminDto(
        id = id,
        authUserId = authUserId,
        email = email,
        nombre = nombre,
        apellidos = apellidos,
        username = username,
        especialidadRegistro = especialidadRegistro,
        perfilCompleto = perfilCompleto,
        perfilCompletadoAt = perfilCompletadoAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class SupabaseAuthAdminUserWire(val id: String)

private fun requireValidUsername(username: String) {
    require(username.length in 3..30 && username.matches(Regex("^[A-Za-z0-9._-]+$"))) {
        "Username invalido: debe tener 3-30 caracteres alfanumericos/._-."
    }
}

private fun generateRandomPassword(): String {
    val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('!', '#', '%', '&', '*', '-', '_')
    return (1..24).map { allowed.random() }.joinToString("")
}

class SupabaseOposiBotsUserAdminGateway(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = defaultUserAdminHttpClient(),
) : OposiBotsUserAdminGateway {

    override suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto> {
        val clampedLimit = limit.coerceIn(1, 200)
        val clampedOffset = offset.coerceAtLeast(0)
        val searchClause = search?.trim()?.takeIf(String::isNotBlank)?.let {
            "&or=(email.ilike.*$it*,username.ilike.*$it*,nombre.ilike.*$it*,apellidos.ilike.*$it*)"
        }.orEmpty()
        val path = "/rest/v1/usuario?select=$UsuarioSelectColumns&order=created_at.desc&limit=$clampedLimit&offset=$clampedOffset$searchClause"
        return request(HttpMethod.Get, path, null).decodeList<UsuarioRow>().map { it.toDto() }
    }

    override suspend fun getUser(id: Int): OposiBotsUserAdminDto? {
        val path = "/rest/v1/usuario?select=$UsuarioSelectColumns&id=eq.$id&limit=1"
        return request(HttpMethod.Get, path, null).decodeList<UsuarioRow>().firstOrNull()?.toDto()
    }

    override suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto {
        require(request.email.isNotBlank()) { "El usuario necesita email." }
        require(request.nombre.isNotBlank()) { "El usuario necesita nombre." }
        require(request.apellidos.isNotBlank()) { "El usuario necesita apellidos." }
        require(request.especialidadRegistro.isNotBlank()) { "El usuario necesita especialidad." }
        request.username?.let(::requireValidUsername)

        val authPassword = request.password?.takeIf(String::isNotBlank) ?: generateRandomPassword()
        val authBody = json.encodeToString(
            mapOf(
                "email" to request.email.trim(),
                "password" to authPassword,
                "email_confirm" to true,
            ),
        )
        val authUserId = json.decodeFromString<SupabaseAuthAdminUserWire>(
            request(HttpMethod.Post, "/auth/v1/admin/users", authBody),
        ).id

        val usuarioBody = json.encodeToString(
            listOf(
                mapOf(
                    "auth_user_id" to authUserId,
                    "email" to request.email.trim(),
                    "nombre" to request.nombre.trim(),
                    "apellidos" to request.apellidos.trim(),
                    "username" to request.username?.trim(),
                    "especialidad_registro" to request.especialidadRegistro.trim(),
                    "perfil_completo" to true,
                    "perfil_completado_at" to Instant.now().toString(),
                ),
            ),
        )
        return request(HttpMethod.Post, "/rest/v1/usuario", usuarioBody, prefer = "return=representation")
            .decodeList<UsuarioRow>().first().toDto()
    }

    override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto? {
        request.username?.let(::requireValidUsername)
        val fields = buildMap<String, Any?> {
            request.nombre?.let { put("nombre", it.trim()) }
            request.apellidos?.let { put("apellidos", it.trim()) }
            request.username?.let { put("username", it.trim()) }
            request.especialidadRegistro?.let { put("especialidad_registro", it.trim()) }
            request.perfilCompleto?.let { put("perfil_completo", it) }
            put("updated_at", Instant.now().toString())
        }
        if (fields.size == 1) return getUser(id) // only updated_at present -> nothing to change, just re-read
        val body = json.encodeToString(fields)
        return request(HttpMethod.Patch, "/rest/v1/usuario?id=eq.$id", body, prefer = "return=representation")
            .decodeList<UsuarioRow>().firstOrNull()?.toDto()
    }

    override suspend fun deleteUser(id: Int): Boolean {
        // Order matters: usuario row first, then the Supabase Auth identity.
        // If deleting the auth identity fails partway through, the profile is
        // already gone (an orphaned auth-only account can't do much and is
        // still addressable by an admin via auth_user_id) rather than leaving
        // a dangling usuario row that blocks future re-registration under the
        // same email/username with no account left to retry deleting.
        val existing = getUser(id) ?: return false
        request(HttpMethod.Delete, "/rest/v1/usuario?id=eq.$id", null, prefer = "return=minimal")
        existing.authUserId?.let { authUserId ->
            try {
                request(HttpMethod.Delete, "/auth/v1/admin/users/$authUserId", null)
            } catch (error: IllegalStateException) {
                throw IllegalStateException(
                    "El perfil de usuario $id fue eliminado pero la identidad de Supabase Auth " +
                        "$authUserId NO pudo eliminarse (${error.message}). Reintentar manualmente " +
                        "borrando ese auth_user_id.",
                    error,
                )
            }
        }
        return true
    }

    private suspend fun request(method: HttpMethod, path: String, body: String?, prefer: String? = null): String {
        val response = try {
            client.request("$supabaseUrl$path") {
                this.method = method
                header("apikey", serviceRoleKey)
                header(HttpHeaders.Authorization, "Bearer $serviceRoleKey")
                header(HttpHeaders.Accept, ContentType.Application.Json)
                prefer?.let { header("Prefer", it) }
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${error::class.simpleName}: ${error.message}", error)
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.status.value}: ${response.bodyAsText().take(300)}")
        }
        return response.bodyAsText()
    }

    private inline fun <reified T> String.decodeList(): List<T> =
        if (isBlank()) emptyList() else json.decodeFromString(this)

    companion object {
        fun fromEnvironment(json: Json): OposiBotsUserAdminGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseOposiBotsUserAdminGateway(url, serviceRoleKey, json)
        }
    }
}

private fun defaultUserAdminHttpClient(): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}
```

- [ ] **Step 4: Run the `listUsers` test to confirm it passes**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminGatewayTest"`
Expected: PASS.

- [ ] **Step 5: Add the `getUser` test (found vs. not found) and confirm it passes**

Add to `OposiBotsUserAdminGatewayTest.kt`:

```kotlin
    @Test
    fun getUserReturnsNullWhenPostgrestReturnsEmptyArray() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(null, gateway.getUser(999))
    }
```

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminGatewayTest"`
Expected: PASS (both tests).

- [ ] **Step 6: Add the `createUser` test and confirm it passes**

```kotlin
    @Test
    fun createUserCallsAuthAdminThenInsertsUsuarioRowLinkedByAuthUserId() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/auth/v1/admin/users") ->
                    respond(content = ByteReadChannel("""{"id":"22222222-2222-2222-2222-222222222222"}"""), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond(
                        content = ByteReadChannel("""[{"id":42,"auth_user_id":"22222222-2222-2222-2222-222222222222","email":"new@example.com","nombre":"Grace","apellidos":"Hopper","username":null,"especialidad_registro":"Celador","perfil_completo":false,"perfil_completado_at":null,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
            }
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val created = gateway.createUser(
            OposiBotsUserAdminCreateRequest(
                email = "new@example.com",
                password = null,
                nombre = "Grace",
                apellidos = "Hopper",
                username = null,
                especialidadRegistro = "Celador",
            ),
        )

        assertEquals(2, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("/auth/v1/admin/users"))
        assertTrue(requests[1].url.encodedPath.endsWith("/rest/v1/usuario"))
        assertEquals(42, created.id)
        assertEquals("22222222-2222-2222-2222-222222222222", created.authUserId)
    }
```

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminGatewayTest"`
Expected: PASS (all 3 tests so far).

- [ ] **Step 7: Add the `updateUser` tests and confirm they pass**

```kotlin
    @Test
    fun updateUserPatchesOnlyProvidedFields() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("""[{"id":42,"auth_user_id":"22222222-2222-2222-2222-222222222222","email":"new@example.com","nombre":"Grace","apellidos":"Hopper","username":"grace","especialidad_registro":"Celador","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:01Z"}]"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val updated = gateway.updateUser(42, OposiBotsUserAdminUpdateRequest(username = "grace"))

        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/rest/v1/usuario"))
        assertEquals("eq.42", requests.single().url.parameters["id"])
        assertEquals("grace", updated?.username)
    }

    @Test
    fun updateUserReturnsNullWhenPostgrestReturnsEmptyArray() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(null, gateway.updateUser(999, OposiBotsUserAdminUpdateRequest(username = "ghost")))
    }
```

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminGatewayTest"`
Expected: PASS (all 5 tests so far).

- [ ] **Step 8: Add the `deleteUser` tests and confirm they pass**

```kotlin
    @Test
    fun deleteUserRemovesUsuarioRowThenAuthIdentityInThatOrder() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.parameters["id"] == "eq.42" && request.url.encodedPath.endsWith("/rest/v1/usuario") ->
                    respond(
                        content = ByteReadChannel("""[{"id":42,"auth_user_id":"22222222-2222-2222-2222-222222222222","email":"new@example.com","nombre":"Grace","apellidos":"Hopper","username":"grace","especialidad_registro":"Celador","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent, headers = headersOf())
            }
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val deleted = gateway.deleteUser(42)

        assertEquals(true, deleted)
        assertEquals(2, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("/rest/v1/usuario"), "usuario row must be deleted first")
        assertTrue(requests[1].url.encodedPath.endsWith("/auth/v1/admin/users/22222222-2222-2222-2222-222222222222"), "auth identity deleted second")
    }

    @Test
    fun deleteUserReturnsFalseWhenUserDoesNotExist() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(false, gateway.deleteUser(999))
    }
```

- [ ] **Step 9: Run the full test file to confirm all 7 tests pass**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminGatewayTest"`
Expected: PASS (all 7 tests).

- [ ] **Step 10: Commit**

```bash
git add backend/ktor/src/main/kotlin/com/oposicontrol/backend/OposiBotsUserAdminGateway.kt backend/ktor/src/test/kotlin/com/oposicontrol/backend/OposiBotsUserAdminGatewayTest.kt
git commit -m "feat(backend): add OposiBotsUserAdminGateway with CRUD against public.usuario + Supabase Auth Admin API"
```

---

### Task 3: `OposiBotsUserAdminRoutes` — HTTP layer and tests

**Files:**
- Create: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/OposiBotsUserAdminRoutes.kt`
- Test: `backend/ktor/src/test/kotlin/com/oposicontrol/backend/OposiBotsUserAdminRoutesTest.kt`

**Interfaces:**
- Consumes: `OposiBotsUserAdminGateway` and its DTOs from Task 2 (exact signatures above — this task only needs the *interface*, so it can be written/tested with a fake implementation even before Task 2's real implementation exists, though in this plan Task 2 runs first).
- Produces: `fun Route.oposiBotsUserAdminRoutes(gateway: OposiBotsUserAdminGateway)`, mounted by Task 4.

- [ ] **Step 1: Write the failing test for `GET /api/v1/admin/oposibots/usuarios`**

```kotlin
package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OposiBotsUserAdminRoutesTest {
    private val testJson = Json { ignoreUnknownKeys = true }

    private fun Application.userAdminTestModule(gateway: OposiBotsUserAdminGateway) {
        install(ContentNegotiation) { json(testJson) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause -> call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", cause.message ?: "Invalid request")) }
            exception<IllegalStateException> { call, cause -> call.respond(HttpStatusCode.InternalServerError, ErrorDto("backend_supabase_error", cause.message ?: "Backend error")) }
        }
        routing { oposiBotsUserAdminRoutes(gateway) }
    }

    @Test
    fun listReturnsDataEnvelope() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }

        val response = client.get("/api/v1/admin/oposibots/usuarios")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"data\""))
        assertEquals(50, fake.lastListLimit)
        assertEquals(0, fake.lastListOffset)
    }
}

private class FakeUserAdminGateway : OposiBotsUserAdminGateway {
    var lastListLimit: Int? = null
    var lastListOffset: Int? = null
    var users = mutableMapOf<Int, OposiBotsUserAdminDto>()
    var nextId = 1

    override suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto> {
        lastListLimit = limit
        lastListOffset = offset
        return users.values.toList()
    }

    override suspend fun getUser(id: Int): OposiBotsUserAdminDto? = users[id]

    override suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto {
        val dto = OposiBotsUserAdminDto(
            id = nextId++,
            authUserId = "auth-${System.nanoTime()}",
            email = request.email,
            nombre = request.nombre,
            apellidos = request.apellidos,
            username = request.username,
            especialidadRegistro = request.especialidadRegistro,
            perfilCompleto = true,
            perfilCompletadoAt = "2026-08-01T00:00:00Z",
            createdAt = "2026-08-01T00:00:00Z",
            updatedAt = "2026-08-01T00:00:00Z",
        )
        users[dto.id] = dto
        return dto
    }

    override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto? {
        val existing = users[id] ?: return null
        val updated = existing.copy(
            nombre = request.nombre ?: existing.nombre,
            apellidos = request.apellidos ?: existing.apellidos,
            username = request.username ?: existing.username,
            especialidadRegistro = request.especialidadRegistro ?: existing.especialidadRegistro,
            perfilCompleto = request.perfilCompleto ?: existing.perfilCompleto,
        )
        users[id] = updated
        return updated
    }

    override suspend fun deleteUser(id: Int): Boolean = users.remove(id) != null
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminRoutesTest"`
Expected: FAIL — `oposiBotsUserAdminRoutes` unresolved reference.

- [ ] **Step 3: Write the routes file**

```kotlin
package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class OposiBotsUserAdminListResponse(val data: List<OposiBotsUserAdminResponseDto>)

@Serializable
data class OposiBotsUserAdminSingleResponse(val data: OposiBotsUserAdminResponseDto)

@Serializable
data class OposiBotsUserAdminResponseDto(
    val id: Int,
    val authUserId: String?,
    val email: String?,
    val nombre: String?,
    val apellidos: String?,
    val username: String?,
    val especialidadRegistro: String?,
    val perfilCompleto: Boolean,
    val perfilCompletadoAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

private fun OposiBotsUserAdminDto.toResponseDto() = OposiBotsUserAdminResponseDto(
    id, authUserId, email, nombre, apellidos, username, especialidadRegistro, perfilCompleto, perfilCompletadoAt, createdAt, updatedAt,
)

@Serializable
data class OposiBotsUserAdminCreateRequestBody(
    val email: String,
    val password: String? = null,
    val nombre: String,
    val apellidos: String,
    val username: String? = null,
    val especialidadRegistro: String,
)

@Serializable
data class OposiBotsUserAdminUpdateRequestBody(
    val nombre: String? = null,
    val apellidos: String? = null,
    val username: String? = null,
    val especialidadRegistro: String? = null,
    val perfilCompleto: Boolean? = null,
)

class OposiBotsUserAdminNotFoundException(message: String) : Exception(message)

fun Route.oposiBotsUserAdminRoutes(gateway: OposiBotsUserAdminGateway) {
    route("/api/v1/admin/oposibots/usuarios") {
        get {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val search = call.request.queryParameters["search"]
            val users = gateway.listUsers(limit, offset, search)
            call.respond(OposiBotsUserAdminListResponse(users.map { it.toResponseDto() }))
        }
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val user = gateway.getUser(id) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(OposiBotsUserAdminSingleResponse(user.toResponseDto()))
        }
        post {
            val body = call.receive<OposiBotsUserAdminCreateRequestBody>()
            val created = gateway.createUser(
                OposiBotsUserAdminCreateRequest(
                    email = body.email,
                    password = body.password,
                    nombre = body.nombre,
                    apellidos = body.apellidos,
                    username = body.username,
                    especialidadRegistro = body.especialidadRegistro,
                ),
            )
            call.respond(HttpStatusCode.Created, OposiBotsUserAdminSingleResponse(created.toResponseDto()))
        }
        patch("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val body = call.receive<OposiBotsUserAdminUpdateRequestBody>()
            val updated = gateway.updateUser(
                id,
                OposiBotsUserAdminUpdateRequest(
                    nombre = body.nombre,
                    apellidos = body.apellidos,
                    username = body.username,
                    especialidadRegistro = body.especialidadRegistro,
                    perfilCompleto = body.perfilCompleto,
                ),
            ) ?: throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(OposiBotsUserAdminSingleResponse(updated.toResponseDto()))
        }
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("id invalido.")
            val deleted = gateway.deleteUser(id)
            if (!deleted) throw OposiBotsUserAdminNotFoundException("Usuario $id no encontrado.")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

- [ ] **Step 4: Run to confirm the list test passes**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminRoutesTest"`
Expected: PASS.

- [ ] **Step 5: Add the remaining route tests (create, get-404, update, delete, validation)**

Append to `OposiBotsUserAdminRoutesTest.kt`:

```kotlin
    @Test
    fun createReturns201WithCreatedUser() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }

        val response = client.post("/api/v1/admin/oposibots/usuarios") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","nombre":"Grace","apellidos":"Hopper","especialidadRegistro":"Celador"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("new@example.com"))
    }

    @Test
    fun getMissingUserReturns404() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }

        val response = client.get("/api/v1/admin/oposibots/usuarios/999")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun updateAppliesPartialChangesAndReturnsUpdatedUser() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }
        val created = client.post("/api/v1/admin/oposibots/usuarios") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","nombre":"Grace","apellidos":"Hopper","especialidadRegistro":"Celador"}""")
        }
        val createdId = testJson.decodeFromString<OposiBotsUserAdminSingleResponse>(created.bodyAsText()).data.id

        val response = client.patch("/api/v1/admin/oposibots/usuarios/$createdId") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ghopper"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ghopper"))
        assertTrue(response.bodyAsText().contains("Grace"), "unchanged fields must be preserved")
    }

    @Test
    fun deleteExistingUserReturns204AndMissingUserReturns404() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }
        val created = client.post("/api/v1/admin/oposibots/usuarios") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","nombre":"Grace","apellidos":"Hopper","especialidadRegistro":"Celador"}""")
        }
        val createdId = testJson.decodeFromString<OposiBotsUserAdminSingleResponse>(created.bodyAsText()).data.id

        val firstDelete = client.delete("/api/v1/admin/oposibots/usuarios/$createdId")
        val secondDelete = client.delete("/api/v1/admin/oposibots/usuarios/$createdId")

        assertEquals(HttpStatusCode.NoContent, firstDelete.status)
        assertEquals(HttpStatusCode.NotFound, secondDelete.status)
    }
```

Also add the missing `StatusPages` mapping for `OposiBotsUserAdminNotFoundException` to `userAdminTestModule` from Step 1 (Ktor requires an explicit mapping per exception type):

```kotlin
            exception<OposiBotsUserAdminNotFoundException> { call, cause -> call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", cause.message ?: "Not found")) }
```

- [ ] **Step 6: Run to confirm it fails**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminRoutesTest"`
Expected: FAIL until the `StatusPages` mapping from Step 5 is added (without it, `get-404`/`delete-404` cases 500 instead of 404).

- [ ] **Step 7: Run again to confirm all pass**

Run: `./gradlew :backend:ktor:test --tests "com.oposicontrol.backend.OposiBotsUserAdminRoutesTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 8: Commit**

```bash
git add backend/ktor/src/main/kotlin/com/oposicontrol/backend/OposiBotsUserAdminRoutes.kt backend/ktor/src/test/kotlin/com/oposicontrol/backend/OposiBotsUserAdminRoutesTest.kt
git commit -m "feat(backend): add /api/v1/admin/oposibots/usuarios CRUD routes"
```

---

### Task 4: Wire into `Application.kt` and verify end-to-end

**Files:**
- Modify: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/Application.kt` (around lines 78-85 for gateway construction, 158-168 for route mounting — re-read the current file first, line numbers may have shifted since the research pass)

**Interfaces:**
- Consumes: `OposiBotsUserAdminGateway.fromEnvironment(json)` (Task 2), `oposiBotsUserAdminRoutes(gateway)` (Task 3).
- Produces: nothing further — this is the final integration point.

- [ ] **Step 1: Construct the gateway alongside the existing ones**

In `Application.kt`, find the block that constructs `adminV1Gateway` (search for `AdminV1Gateway.fromEnvironment`). Add immediately after it:

```kotlin
    val oposiBotsUserAdminGateway = OposiBotsUserAdminGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son obligatorios para servir la administracion de usuarios de OposiBots.")
```

- [ ] **Step 2: Mount the routes next to `adminV1Routes`**

In the same file, find `authenticate("gateway-auth") { ... adminV1Routes(adminV1Gateway) ... }` and add immediately after that line:

```kotlin
            oposiBotsUserAdminRoutes(oposiBotsUserAdminGateway)
```

- [ ] **Step 3: Compile the backend**

Run: `./gradlew :backend:ktor:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full backend test suite**

Run: `./gradlew :backend:ktor:test`
Expected: BUILD SUCCESSFUL, all tests pass (existing tests + the 12 new tests from Tasks 2-3).

- [x] **Step 5: Manual smoke test against the Supabase branch from Task 1** — Run directly against production (`ndiooyyqtaeysnedywer`), no branch, per user direction. All 5 operations passed (create 201, list 200, get 200, update 200, delete 204); hard-delete of both `public.usuario` row and `auth.users` identity confirmed via `execute_sql`. Diagnostic detour: first attempt 404'd everywhere due to a stale Gradle daemon holding `SERVER_PROFILE=consumer` from an earlier run — fixed with `./gradlew --stop` + `--no-configuration-cache`.

This step requires Task 1's migration to actually be applied (branch is enough, main project not required yet). Start the backend locally pointed at the branch's connection details (`SUPABASE_URL`/`SUPABASE_SERVICE_ROLE_KEY` env vars set to the branch project, `SERVER_PROFILE` unset so it defaults to `combined`, `OPOSICONTROL_BACKEND_TOKEN` set to any local value):

```bash
export SUPABASE_URL="https://<branch-ref>.supabase.co"
export SUPABASE_SERVICE_ROLE_KEY="<branch-service-role-key>"
export OPOSICONTROL_BACKEND_TOKEN="local-dev-token"
# Required or every request 401s: the static-token admin principal is rejected
# outside a recognized dev/test APP_ENV (Application.kt's isLocalDevelopmentPeer
# gate) — found missing from this smoke test during final review.
export APP_ENV="dev"
./gradlew :backend:ktor:run
```

In another terminal:

```bash
# Create
curl -s -X POST http://localhost:8080/api/v1/admin/oposibots/usuarios \
  -H "Authorization: Bearer local-dev-token" -H "Content-Type: application/json" \
  -d '{"email":"smoke-test@example.com","nombre":"Smoke","apellidos":"Test","especialidadRegistro":"Enfermeria"}'
# Note the returned "id" as $ID, then:

# List
curl -s http://localhost:8080/api/v1/admin/oposibots/usuarios -H "Authorization: Bearer local-dev-token"

# Get one
curl -s http://localhost:8080/api/v1/admin/oposibots/usuarios/$ID -H "Authorization: Bearer local-dev-token"

# Update
curl -s -X PATCH http://localhost:8080/api/v1/admin/oposibots/usuarios/$ID \
  -H "Authorization: Bearer local-dev-token" -H "Content-Type: application/json" \
  -d '{"username":"smoketest"}'

# Delete
curl -s -X DELETE http://localhost:8080/api/v1/admin/oposibots/usuarios/$ID -H "Authorization: Bearer local-dev-token" -w "%{http_code}\n"
```

Expected: create → 201 with a body containing `authUserId`; list → 200 with the new user in `data`; get → 200; update → 200 with `username":"smoketest"`; delete → `204`. Then confirm in the Supabase dashboard (or `execute_sql` against the branch) that both the `public.usuario` row and the `auth.users` row are gone.

- [ ] **Step 6: Commit**

```bash
git add backend/ktor/src/main/kotlin/com/oposicontrol/backend/Application.kt
git commit -m "feat(backend): wire OposiBotsUserAdminGateway and routes into Application module"
```

---

## Verification (end-to-end, after all 4 tasks)

1. [x] `./gradlew :backend:ktor:test` — all tests green, including the 12 new ones across Tasks 2-3. (Task 4)
2. [x] `./gradlew :backend:ktor:compileKotlin` — clean compile. (Task 4)
3. [x] Task 4 Step 5's manual smoke test — run against production directly, full create → list → get → update → delete cycle, confirmed in the database directly.
4. [x] Confirm `SERVER_PROFILE=consumer` excludes these routes — proven incidentally by the Step 5 diagnostic: a stale daemon stuck in `SERVER_PROFILE=consumer` served `/health/live` only (via `configureConsumerAuthOnly`) and 404'd on `/health` and every admin route, matching exactly what this item predicted.
5. [x] Apply to the main Supabase project — done directly, per explicit user direction to skip the branch workflow entirely (pre-launch, no production data yet).

**Plan complete.**
