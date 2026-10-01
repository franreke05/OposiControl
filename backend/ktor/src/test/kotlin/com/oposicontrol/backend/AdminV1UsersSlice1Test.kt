package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SLICE 1 · admin D1-22 (lista), D1-23 (usuario), D1-24 (detalle) y D1-26 (roles): `accountStatus` visible, sin endpoints nuevos. */
class AdminV1UsersSlice1Test {

    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val u2 = "22222222-2222-4222-8222-222222222222"
    private val u3 = "33333333-3333-4333-8333-333333333333"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-aaaaaaaaaaaa"
    private val roleUser = "bbbbbbbb-0000-4000-8000-bbbbbbbbbbbb"

    private fun perfil(id: String, username: String?, nombre: String, estado: String?, onboarding: Boolean = true) =
        """{"usuario_id":"$id","username":${username?.let { "\"$it\"" } ?: "null"},"nombre_mostrar":"$nombre","es_invitado":false,""" +
            """"onboarding_completado":$onboarding,"estado_cuenta":${estado?.let { "\"$it\"" } ?: "null"},"creado_en":"2026-01-02T03:04:05.123456+00:00"}"""

    private class Sent(val method: String, val uri: String)

    private class Harness(val sent: CopyOnWriteArrayList<Sent>, val responder: (String) -> V1StubResponse)

    private fun Application.adminModule(sent: CopyOnWriteArrayList<Sent>, responder: (String) -> V1StubResponse) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ sent += Sent(it.method(), it.uri().toString()) }, responder),
        )
        routing { adminV1Routes(gateway) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun usersResponder(rows: String, holders: String = "[]", roleRows: String = "[]"): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/perfiles_usuario?select=usuario_id" in uri -> V1StubResponse(200, "[]", contentRange = "0-0/3")
            "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, rows)
            "/rest/v1/roles_app?select=id" in uri -> V1StubResponse(200, """[{"id":"$roleAdmin"}]""")
            "/rest/v1/roles_usuario?select=usuario_id" in uri -> V1StubResponse(200, holders)
            "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, roleRows)
            "/rest/v1/carteras_usuario" in uri -> V1StubResponse(200, """[{"usuario_id":"$u1","saldo_tickets":7}]""")
            else -> V1StubResponse(200, "[]")
        }
    }

    // ------------------------------------------------------------------ D1-22

    @Test
    fun theListReturnsCanonicalItemsWithAccountStatusAndKeepsTheLegacyDataDuplicate() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val rows = "[${perfil(u1, "ada", "Ada L", "activa")},${perfil(u2, "bob", "Bob", "bloqueada")},${perfil(u3, null, "Eve", "eliminacion_pendiente", onboarding = false)}]"
        application {
            adminModule(sent, usersResponder(rows, roleRows = """[{"usuario_id":"$u1","rol_id":"$roleUser"},{"usuario_id":"$u2","rol_id":"$roleAdmin"}]"""))
        }
        val page = client.get("/api/v1/admin/usuarios").json()

        val items = page["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("activa", "bloqueada", "eliminacion_pendiente"), items.map { it.str("accountStatus") })
        val first = items.first()
        assertEquals(setOf("id", "username", "fullName", "roleIds", "accountStatus", "createdAt", "ticketsBalance"), first.keys, "email es null en la lista y no se emite")
        assertEquals("ada", first.str("username"))
        assertEquals("Ada L", first.str("fullName"))
        assertEquals("2026-01-02T03:04:05.123456Z", first.str("createdAt"))
        assertEquals("7", first["ticketsBalance"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleUser), first["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(items[1].str("ticketsBalance"), "sin cartera es null, no 0")
        assertEquals("3", page["total"]!!.jsonPrimitive.content)
        assertEquals("50", page["limit"]!!.jsonPrimitive.content)

        // LEGACY: `data` con `email` = username, `isActive` SIEMPRE emitido (@EncodeDefault) y saldo solo con cartera.
        val data = page["data"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, data.size)
        assertEquals("ada", data.first().str("email"))
        // `isActive` se emite aunque sea true (el cliente no distingue omitido de desconocido); sin cartera no hay saldo (no un 0 fabricado).
        assertEquals("true", data.first()["isActive"]!!.jsonPrimitive.content)
        assertEquals("7", data.first()["ticketsBalance"]!!.jsonPrimitive.content)
        assertNull(data[1]["ticketsBalance"], "sin cartera el legacy no fabrica 0")
    }

    @Test
    fun anUnrecognizedOrNullAccountStatusIsNeverListedAsActive() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val rows = "[${perfil(u1, "ada", "Ada", "activa")},${perfil(u2, "bob", "Bob", "weird")},${perfil(u3, "eve", "Eve", null)}]"
        application { adminModule(sent, usersResponder(rows)) }
        val page = client.get("/api/v1/admin/usuarios").json()
        val items = page["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(u1), items.map { it.str("id") }, "solo lo reconocido; lo demas no se presenta como activa")
        assertTrue(items.all { it.str("accountStatus") in setOf("activa", "bloqueada", "eliminacion_pendiente") })
        assertEquals(3, page["data"]!!.jsonArray.size, "el duplicado legacy no cambia")
    }

    @Test
    fun theFiltersAreValidatedAndTranslatedToPostgrestFilters() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, usersResponder("[${perfil(u1, "ada", "Ada", "bloqueada")}]")) }

        client.get("/api/v1/admin/usuarios?accountStatus=bloqueada&q=ada&sort=username_asc&limit=500&offset=10")
        val list = sent.map { it.uri }.first { "/rest/v1/perfiles_usuario?select=*" in it }
        assertTrue("estado_cuenta=eq.bloqueada" in list, list)
        assertTrue("or=(username.ilike.ada*,nombre_mostrar.ilike.ada*)" in list, list)
        assertTrue("order=username.asc.nullslast,usuario_id.asc" in list, list)
        assertTrue("limit=200" in list && "offset=10" in list, "limit 1..200: $list")
        val count = sent.map { it.uri }.first { "select=usuario_id&estado_cuenta" in it }
        assertTrue("estado_cuenta=eq.bloqueada" in count && "or=(" in count, "el total respeta los filtros: $count")
    }

    @Test
    fun invalidFiltersAreRejectedWithTheOffendingFieldBeforeAnyRead() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, usersResponder("[]")) }
        val cases = mapOf(
            "accountStatus=activo" to "accountStatus",
            "accountStatus=ACTIVA" to "accountStatus",
            "roleCode=root" to "roleCode",
            "sort=creado" to "sort",
            "q=ada%2C)%28*" to "q",
            "q=%26select%3D*" to "q",
        )
        cases.forEach { (query, field) ->
            val response = client.get("/api/v1/admin/usuarios?$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("VALIDATION", response.json().str("code"), query)
            assertTrue(field in response.json().issueFields(), query)
        }
        assertTrue(sent.isEmpty(), "ninguna lectura: ${sent.map { it.uri }}")
    }

    @Test
    fun roleCodeFiltersByTheHoldersOfThatRole() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, usersResponder("[${perfil(u2, "bob", "Bob", "activa")}]", holders = """[{"usuario_id":"$u2"}]""")) }
        client.get("/api/v1/admin/usuarios?roleCode=admin")
        val uris = sent.map { it.uri }
        assertTrue(uris.any { "/rest/v1/roles_app?select=id&codigo=eq.admin" in it })
        assertTrue(uris.any { "/rest/v1/perfiles_usuario?select=*" in it && "usuario_id=in.($u2)" in it }, uris.toString())
    }

    @Test
    fun aRoleWithoutHoldersIsAnEmptyPageWithTotalZeroNeverAPageOfEveryone() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, usersResponder("[${perfil(u1, "ada", "Ada", "activa")}]", holders = "[]")) }
        val body = client.get("/api/v1/admin/usuarios?roleCode=moderador").json()
        assertEquals("0", body["total"]!!.jsonPrimitive.content)
        assertEquals(0, body["items"]!!.jsonArray.size)
        assertTrue(sent.none { "/rest/v1/perfiles_usuario" in it.uri })
    }

    @Test
    fun aRoleFilterThatWouldBeTruncatedIsRejectedExplicitly() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val many = (1..501).joinToString(",", "[", "]") { """{"usuario_id":"$u2"}""" }
        application { adminModule(sent, usersResponder("[]", holders = many)) }
        val response = client.get("/api/v1/admin/usuarios?roleCode=usuario")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("TOO_MANY_MATCHES"), response.json().issueNames())
        assertTrue(sent.none { "/rest/v1/perfiles_usuario" in it.uri })
    }

    /** Owner FA6 (D1-22): exactamente 100 titulares se admite (pagina filtrada); 101 jamas devuelve los 100 primeros. */
    @Test
    fun exactlyOneHundredRoleHoldersIsAllowed() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val holders = (1..100).joinToString(",", "[", "]") { """{"usuario_id":"$u2"}""" }
        application { adminModule(sent, usersResponder("[${perfil(u2, "bob", "Bob", "activa")}]", holders = holders)) }
        val response = client.get("/api/v1/admin/usuarios?roleCode=admin")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(sent.any { "/rest/v1/roles_usuario?select=usuario_id" in it.uri && "limit=101" in it.uri }, "se pide cap+1 para detectar el exceso")
        assertTrue(sent.any { "/rest/v1/perfiles_usuario?select=*" in it.uri })
    }

    @Test
    fun oneHundredAndOneRoleHoldersIs400AndNoTruncatedPageIsReturned() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val many = (1..101).joinToString(",", "[", "]") { """{"usuario_id":"$u2"}""" }
        application { adminModule(sent, usersResponder("[${perfil(u2, "bob", "Bob", "activa")}]", holders = many)) }
        val response = client.get("/api/v1/admin/usuarios?roleCode=admin")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(listOf("TOO_MANY_MATCHES"), response.json().issueNames())
        assertTrue("items" !in response.json().keys, "ninguna pagina truncada")
        assertTrue(sent.none { "/rest/v1/perfiles_usuario" in it.uri })
    }

    // ------------------------------------------------------------------ D1-23

    private fun userResponder(
        row: String = "[${perfil(u1, "ada", "Ada L", "activa")}]",
        goTrue: V1StubResponse = V1StubResponse(
            200,
            """{"id":"$u1","email":"ada@example.com","app_metadata":{"providers":["email","google","github"]},"identities":[{"provider":"google"},{"provider":"apple"}]}""",
        ),
    ): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, row)
            "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, """[{"usuario_id":"$u1","rol_id":"$roleUser"}]""")
            "/auth/v1/admin/users/$u1" in uri -> goTrue
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun getUserReturnsTheFrozenAdminUserShapeWithEmailAndProvidersFromOneGoTrueLookupAndTheLegacyData() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, userResponder()) }
        val user = client.get("/api/v1/admin/usuarios/$u1").json()

        assertEquals(u1, user.str("id"))
        assertEquals("ada", user.str("username"))
        assertEquals("Ada L", user.str("fullName"))
        assertEquals("ada@example.com", user.str("email"))
        assertEquals(listOf(roleUser), user["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("activa", user.str("accountStatus"))
        assertEquals("true", user["onboardingCompleted"]!!.jsonPrimitive.content)
        assertEquals("2026-01-02T03:04:05.123456Z", user.str("createdAt"))
        assertEquals(listOf("email", "google", "apple"), user["authProviders"]!!.jsonArray.map { it.jsonPrimitive.content }, "github se descarta")
        assertTrue(user["bloqueo"] == null || user["bloqueo"] is kotlinx.serialization.json.JsonNull, "bloqueo es siempre null en SLICE 1")
        assertFalse(user.keys.any { it.contains("especialidad") }, "la especialidad NO esta en AdminUser")
        // Duplicado legacy `data` (email = username, isActive...).
        val data = user["data"]!!.jsonObject
        assertEquals("ada", data.str("email"))
        assertEquals("true", data["isActive"]!!.jsonPrimitive.content)
        assertEquals(1, sent.count { "/auth/v1/admin/users/" in it.uri }, "UNA lectura de GoTrue admin")
    }

    @Test
    fun getUserDegradesEmailAndProvidersWhenGoTrueFailsInsteadOfFailingTheCall() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, userResponder(goTrue = V1StubResponse(500, """{"msg":"down"}"""))) }
        val response = client.get("/api/v1/admin/usuarios/$u1")
        assertEquals(HttpStatusCode.OK, response.status)
        val user = response.json()
        assertNull(user.str("email"))
        assertEquals(0, user["authProviders"]!!.jsonArray.size)
        assertEquals("activa", user.str("accountStatus"))
    }

    @Test
    fun getUserShowsEveryFrozenAccountStatusAndNeverInventsActive() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        var estado: String? = "eliminacion_pendiente"
        application {
            adminModule(sent) { uri ->
                if ("/rest/v1/perfiles_usuario" in uri) V1StubResponse(200, "[${perfil(u1, "ada", "Ada", estado)}]") else userResponder()(uri)
            }
        }
        assertEquals("eliminacion_pendiente", client.get("/api/v1/admin/usuarios/$u1").json().str("accountStatus"))
        estado = "bloqueada"
        assertEquals("bloqueada", client.get("/api/v1/admin/usuarios/$u1").json().str("accountStatus"))
        estado = "DELETED"
        val unknown = client.get("/api/v1/admin/usuarios/$u1")
        assertEquals(HttpStatusCode.BadGateway, unknown.status, "un estado no reconocido no se presenta como activa")
        assertEquals("UPSTREAM_CONTRACT_ERROR", unknown.json().str("code"))
        estado = null
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/v1/admin/usuarios/$u1").status)
    }

    @Test
    fun getUserRejectsANonUuidWithNonUuidBeforeAnyReadAndAnUnknownUserIs404() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, userResponder(row = "[]")) }
        val bad = client.get("/api/v1/admin/usuarios/no-es-uuid")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("VALIDATION", bad.json().str("code"))
        assertEquals(listOf("id"), bad.json().issueFields())
        assertEquals(listOf("NON_UUID"), bad.json().issueNames())
        assertTrue(sent.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/admin/usuarios/$u1").status)
    }

    // ------------------------------------------------------------------ D1-24

    @Test
    fun theDetailPerfilIsTheAdminUserShapeAlongsideTheLegacyProfileFields() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, userResponder()) }
        // La ficha viaja envuelta en `data` (AdminUserDetailResponse), como hasta ahora.
        val detail = client.get("/api/v1/admin/usuarios/$u1/detalle").json()["data"]!!.jsonObject
        val perfil = detail["perfil"]!!.jsonObject

        // AdminUser (camelCase, congelado)
        assertEquals(u1, perfil.str("id"))
        assertEquals("activa", perfil.str("accountStatus"))
        assertEquals("true", perfil["onboardingCompleted"]!!.jsonPrimitive.content)
        assertEquals("ada@example.com", perfil.str("email"))
        assertEquals(listOf("email", "google", "apple"), perfil["authProviders"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(roleUser), perfil["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Ada L", perfil.str("fullName"))
        // Campos legacy de siempre, intactos.
        assertEquals(u1, perfil.str("usuario_id"))
        assertEquals("Ada L", perfil.str("nombre_mostrar"))
        assertEquals("true", perfil["onboarding_completado"]!!.jsonPrimitive.content)
        assertTrue(detail["seccionesNoDisponibles"]?.jsonArray.orEmpty().none { it.jsonPrimitive.content == "perfil" })
    }

    @Test
    fun ifTheRolesReadFailsThePerfilSectionIsFlaggedUnavailableAndTheLegacyProfileSurvives() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application {
            adminModule(sent) { uri ->
                if ("/rest/v1/roles_usuario" in uri) V1StubResponse(500, """{"message":"boom"}""") else userResponder()(uri)
            }
        }
        val detail = client.get("/api/v1/admin/usuarios/$u1/detalle").json()["data"]!!.jsonObject
        assertEquals(u1, detail["perfil"]!!.jsonObject.str("usuario_id"))
        assertTrue(detail["seccionesNoDisponibles"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("perfil"))
    }

    // ------------------------------------------------------------------ D1-26

    private fun rolesResponder(rpc: V1StubResponse): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/rpc/admin_sync_roles" in uri -> rpc
            "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, """[{"usuario_id":"$u1","rol_id":"$roleUser"},{"usuario_id":"$u1","rol_id":"$roleAdmin"}]""")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun replacingRolesReturnsTheResultingRolesReReadAfterTheSyncAndKeepsOkForOc() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, rolesResponder(V1StubResponse(204, ""))) }
        val response = client.post("/api/v1/admin/usuarios/$u1/roles") {
            contentType(ContentType.Application.Json)
            setBody("""{"roleIds":["$roleUser","$roleAdmin"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(listOf(roleUser, roleAdmin), body["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", body["ok"]!!.jsonPrimitive.content, "aditivo para OC")
        val order = sent.map { it.uri }
        assertTrue(order.indexOfFirst { "admin_sync_roles" in it } < order.indexOfLast { "/rest/v1/roles_usuario?select=rol_id" in it }, "se relee DESPUES de sincronizar")
    }

    @Test
    fun theRoleSyncErrorsKeepTheirCanonicalCodesAndStatuses() = testApplication {
        val expected = mapOf(
            "SUPERADMIN_REQUIRED" to (HttpStatusCode.Forbidden to "SUPERADMIN_REQUIRED"),
            "ROLE_SYNC_LAST_SUPERADMIN" to (HttpStatusCode.Conflict to "ROLE_SYNC_LAST_SUPERADMIN"),
            "ROLE_SYNC_EMPTY_ROLES" to (HttpStatusCode.UnprocessableEntity to "ROLE_SYNC_EMPTY_ROLES"),
            "ROLE_SYNC_UNKNOWN_ROLE" to (HttpStatusCode.UnprocessableEntity to "ROLE_SYNC_UNKNOWN_ROLE"),
            "ROLE_SYNC_INVALID_USER" to (HttpStatusCode.UnprocessableEntity to "ROLE_SYNC_INVALID_USER"),
        )
        val sent = CopyOnWriteArrayList<Sent>()
        var raised = "SUPERADMIN_REQUIRED"
        application {
            adminModule(sent) { uri ->
                if ("admin_sync_roles" in uri) V1StubResponse(400, """{"code":"P0001","message":"$raised","details":null,"hint":null}""") else V1StubResponse(200, "[]")
            }
        }
        expected.forEach { (key, outcome) ->
            raised = key
            val response = client.post("/api/v1/admin/usuarios/$u1/roles") {
                contentType(ContentType.Application.Json)
                setBody("""{"roleIds":["$roleUser"]}""")
            }
            assertEquals(outcome.first, response.status, key)
            assertEquals(outcome.second, response.json().str("code"), key)
        }
    }
}
