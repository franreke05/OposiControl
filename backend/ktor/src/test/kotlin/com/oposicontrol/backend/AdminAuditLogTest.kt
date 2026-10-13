package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pestana «Auditoria»: `GET /api/v1/admin/auditoria/movimientos` y `GET /api/v1/admin/auditoria/{id}`, de punta a punta
 * (ruta -> gateway -> transporte real) contra un PostgREST falso que solo devuelve lo que se le prepara.
 *
 * Lo que se protege es sobre todo lo que SALE hacia Supabase: que los filtros los aplique la base y no el panel, que la
 * lista no descargue los jsonb, que poner nombre a los actores cueste una consulta por pagina y no una por fila, y que un
 * parametro mal formado no llegue a salir.
 */
class AdminAuditLogTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val adminToken = "admin-token"
    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val ana = "5a5a5a5a-0000-4000-8000-000000000001"
    private val sinPerfil = "5a5a5a5a-0000-4000-8000-000000000002"
    private val target = "7b7b7b7b-0000-4000-8000-000000000009"

    private val sent = CopyOnWriteArrayList<HttpRequest>()

    /** Lo que el PostgREST falso contesta a `auditoria_log` y a `perfiles_usuario`. */
    private var auditRows: String = "[]"
    private var perfiles: String = "[]"

    private fun rowId(n: Int) = "cccccccc-0000-4000-8000-%012d".format(n)

    private fun row(n: Int, actor: String? = ana, accion: String = "cuenta_dada_de_baja", extra: String = ""): String =
        """{"id":"${rowId(n)}","usuario_id":${actor?.let { "\"$it\"" } ?: "null"},"accion":"$accion",""" +
            """"tabla_afectada":"perfiles_usuario","registro_id":"$target","creado_en":"2026-10-13T12:34:%02d.123456+00:00"$extra}""".format(n)

    private fun Application.module() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") { authenticate { if (it.token == adminToken) UserIdPrincipal(adminUid) else null } }
        }
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient(sent::add) { uri ->
                when {
                    "/rest/v1/auditoria_log" in uri -> V1StubResponse(200, auditRows)
                    "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, perfiles)
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
        routing {
            authenticate("gateway-auth") {
                install(AdminAuthorizationPlugin) {
                    this.appEnv = "production"
                    this.hasAdminRole = { it == adminUid }
                }
                installAdminAuditActor()
                adminV1Routes(gateway)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(path: String, token: String? = adminToken): HttpResponse =
        client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun ApplicationTestBuilder.page(query: String = ""): JsonObject {
        val response = call("/api/v1/admin/auditoria/movimientos$query")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        return json.parseToJsonElement(body).jsonObject
    }

    private fun auditQueries(): List<String> =
        sent.map { it.uri().toString() }.filter { "/rest/v1/auditoria_log" in it }.map { URLDecoder.decode(it.substringAfter('?'), Charsets.UTF_8) }

    private fun profileQueries(): List<String> = sent.map { it.uri().toString() }.filter { "/rest/v1/perfiles_usuario" in it }

    private fun String.param(name: String): List<String> = split('&').filter { it.startsWith("$name=") }.map { it.substringAfter('=') }

    // ------------------------------------------------------------------------------------------------ lista

    @Test
    fun theFiltersAreAppliedByTheDatabaseAndTheListAsksForExplicitColumnsOnly() = testApplication {
        application { module() }
        auditRows = "[${row(1)}]"
        page("?accion=cuenta_dada_de_baja&actor=${ana.uppercase()}&tabla=perfiles_usuario&desde=2026-10-01&hasta=2026-10-13")

        val query = auditQueries().single()
        assertEquals(listOf("eq.cuenta_dada_de_baja"), query.param("accion"))
        // El uuid sale normalizado en minusculas, no como lo escribio el cliente.
        assertEquals(listOf("eq.$ana"), query.param("usuario_id"))
        assertEquals(listOf("eq.perfiles_usuario"), query.param("tabla_afectada"))
        // Una fecha es un dia UTC entero: `desde` su primer instante, `hasta` hasta el primer instante del dia siguiente.
        assertEquals(listOf("gte.2026-10-01T00:00:00Z", "lt.2026-10-14T00:00:00Z"), query.param("creado_en"))
        assertEquals(listOf("creado_en.desc,id.desc"), query.param("order"))
        // Defecto 50, y una fila de mas para saber si hay pagina siguiente.
        assertEquals(listOf("51"), query.param("limit"))

        val columns = query.param("select").single().split(',')
        assertEquals(listOf("id", "usuario_id", "accion", "tabla_afectada", "registro_id", "creado_en"), columns)
        assertFalse("*" in query.param("select").single(), "la lista nunca pide todas las columnas")
        listOf("antes", "despues", "ip", "user_agent").forEach { assertFalse(it in columns, "la lista no descarga `$it`") }
    }

    @Test
    fun anInstantRangeTravelsAsGivenAndTheLimitIsCappedAtOneHundred() = testApplication {
        application { module() }
        page("?desde=2026-10-12T22:00:00%2B02:00&hasta=2026-10-13T22:00:00Z&limit=5000")

        val query = auditQueries().single()
        // El instante lo reescribe el servidor (aqui, a UTC): el texto del cliente no se interpola.
        assertEquals(listOf("gte.2026-10-12T20:00:00Z", "lt.2026-10-13T22:00:00Z"), query.param("creado_en"))
        assertEquals(listOf("101"), query.param("limit"))
    }

    @Test
    fun theListIsKeysetPagedAndTheCursorBelongsToItsFilters() = testApplication {
        application { module() }
        auditRows = "[${row(3)},${row(2)},${row(1)}]"
        val first = page("?limit=2&accion=cuenta_dada_de_baja")
        assertEquals(listOf(rowId(3), rowId(2)), first["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val cursor = first["nextCursor"]!!.jsonPrimitive.content

        auditRows = "[${row(1)}]"
        val second = page("?limit=2&accion=cuenta_dada_de_baja&cursor=$cursor")
        assertEquals(listOf(rowId(1)), second["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        // Ultima pagina: sin cursor (el Json del modulo omite los nulos).
        assertTrue(second["nextCursor"] == null || second["nextCursor"] is JsonNull)
        // La segunda pagina sigue desde la ultima fila vista, con el instante TAL CUAL lo dio la base y el id de desempate.
        val after = auditQueries().last().param("or").single()
        assertEquals("(creado_en.lt.2026-10-13T12:34:02.123456+00:00,and(creado_en.eq.2026-10-13T12:34:02.123456+00:00,id.lt.${rowId(2)}))", after)

        // El mismo cursor con otro filtro no es «otra pagina»: es un cursor que no vale.
        val before = sent.size
        val foreign = call("/api/v1/admin/auditoria/movimientos?limit=2&accion=cuenta_reactivada&cursor=$cursor")
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        assertEquals("INVALID_CURSOR", json.parseToJsonElement(foreign.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(before, sent.size, "un cursor ajeno no llega a Supabase")
    }

    @Test
    fun theActorsOfAPageAreNamedWithASingleProfileQuery() = testApplication {
        application { module() }
        auditRows = "[${row(4)},${row(3, actor = sinPerfil)},${row(2, actor = null)},${row(1)}]"
        perfiles = """[{"usuario_id":"$ana","nombre_mostrar":"Ana García","username":"ana"}]"""
        val items = page()["items"]!!.jsonArray.map { it.jsonObject }

        assertEquals(1, profileQueries().size, "una consulta de perfiles por pagina, no una por fila: ${profileQueries()}")
        val ids = profileQueries().single().substringAfter("usuario_id=in.(").substringBefore(')').split(',').toSet()
        assertEquals(setOf(ana, sinPerfil), ids, "cada actor una sola vez, y el del sistema (null) no se pregunta")

        assertEquals(listOf("Ana García", null, null, "Ana García"), items.map { it["actorNombre"]?.takeIf { v -> v !is JsonNull }?.jsonPrimitive?.content })
        // Sin perfil se devuelve el identificador y NINGUN nombre; sin actor, ni lo uno ni lo otro (accion del sistema).
        assertEquals(sinPerfil, items[1]["actorId"]!!.jsonPrimitive.content)
        assertTrue(items[2]["actorId"] == null || items[2]["actorId"] is JsonNull)
        // La fila viaja con los nombres del contrato, no con los de la tabla.
        assertEquals(setOf("id", "actorId", "actorNombre", "accion", "tabla", "registroId", "creadoEn"), items[0].keys)
        assertEquals("2026-10-13T12:34:04.123456+00:00", items[0]["creadoEn"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPageOfSystemActionsAsksForNoProfilesAtAll() = testApplication {
        application { module() }
        auditRows = "[${row(1, actor = null)}]"
        page()
        assertTrue(profileQueries().isEmpty(), "sin actores no hay nada que resolver")
    }

    @Test
    fun malformedParametersAreRejectedBeforeAnythingLeaves() = testApplication {
        application { module() }
        val bad = mapOf(
            "accion=cuenta;drop" to "accion",
            "accion=Cuenta_Dada" to "accion",
            "accion=a.b" to "accion",
            "tabla=perfiles_usuario%26select%3D*" to "tabla",
            "tabla=${"a".repeat(65)}" to "tabla",
            "actor=1%20or%201%3D1" to "actor",
            "actor=1-1-1-1-1" to "actor",
            "desde=ayer" to "desde",
            "desde=2026-02-30" to "desde",
            "hasta=2026-10-13T25:00:00Z" to "hasta",
            "hasta=13/10/2026" to "hasta",
            // Un rango al reves.
            "desde=2026-10-13&hasta=2026-10-01" to "hasta",
            "limit=0" to "limit",
            "limit=muchos" to "limit",
        )
        bad.forEach { (query, field) ->
            val response = call("/api/v1/admin/auditoria/movimientos?$query")
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(HttpStatusCode.BadRequest, response.status, "$query -> $body")
            assertEquals("VALIDATION", body["code"]!!.jsonPrimitive.content, query)
            val fields = body["details"]!!.jsonObject["issues"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content }
            assertEquals(listOf(field), fields, query)
        }
        assertTrue(sent.isEmpty(), "ninguna peticion mal formada llega a Supabase: ${sent.map { it.uri() }}")

        val garbage = call("/api/v1/admin/auditoria/movimientos?cursor=no-es-un-cursor")
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun blankFiltersDoNotFilterAndAnEmptyTableIsAnEmptyPage() = testApplication {
        application { module() }
        val body = page("?accion=&actor=%20&desde=")
        assertTrue(body["items"]!!.jsonArray.isEmpty())
        val query = auditQueries().single()
        listOf("accion", "usuario_id", "tabla_afectada", "creado_en", "or").forEach { assertTrue(query.param(it).isEmpty(), "$it no deberia filtrar: $query") }
        assertTrue(profileQueries().isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ ficha

    @Test
    fun theDetailCarriesBeforeAndAfterAsStoredAndNeverTheIpOrTheUserAgent() = testApplication {
        application { module() }
        auditRows = "[" + row(
            7,
            extra = ""","antes":{"estado_cuenta":"activa"},"despues":{"estado_cuenta":"bloqueada","motivo":"Uso indebido."},"ip":"203.0.113.7","user_agent":"curl/8"""",
        ) + "]"
        val response = call("/api/v1/admin/auditoria/${rowId(7).uppercase()}")
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = json.parseToJsonElement(text).jsonObject

        assertEquals(rowId(7), body["id"]!!.jsonPrimitive.content)
        assertEquals(ana, body["actorId"]!!.jsonPrimitive.content)
        assertEquals("activa", body["antes"]!!.jsonObject["estado_cuenta"]!!.jsonPrimitive.content)
        assertEquals("Uso indebido.", body["despues"]!!.jsonObject["motivo"]!!.jsonPrimitive.content)
        // Aunque la base los devolviera, no salen: ni estan en el `select` ni en el DTO.
        assertFalse("203.0.113.7" in text || "curl/8" in text || "user_agent" in text || "\"ip\"" in text, text)

        val query = auditQueries().single()
        assertEquals(listOf("eq.${rowId(7)}"), query.param("id"))
        assertEquals(listOf("id,usuario_id,accion,tabla_afectada,registro_id,creado_en,antes,despues"), query.param("select"))
        assertTrue(profileQueries().isEmpty(), "la ficha no vuelve a pedir el perfil: el nombre ya vino en la lista")
    }

    @Test
    fun aRowWithoutBeforeOrAfterSaysSoWithNulls() = testApplication {
        application { module() }
        auditRows = "[" + row(8, actor = null, extra = ""","antes":null,"despues":null""") + "]"
        val body = json.parseToJsonElement(call("/api/v1/admin/auditoria/${rowId(8)}").bodyAsText()).jsonObject
        assertNull(body["antes"]?.takeIf { it !is JsonNull })
        assertNull(body["despues"]?.takeIf { it !is JsonNull })
        assertNull(body["actorId"]?.takeIf { it !is JsonNull })
    }

    @Test
    fun anUnknownMovementIsNotFoundAndAnIdThatIsNotAUuidNeverLeaves() = testApplication {
        application { module() }
        val missing = call("/api/v1/admin/auditoria/${rowId(404)}")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("NOT_FOUND", json.parseToJsonElement(missing.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(1, auditQueries().size)

        sent.clear()
        assertEquals(HttpStatusCode.BadRequest, call("/api/v1/admin/auditoria/1%20or%201%3D1").status)
        assertTrue(sent.isEmpty(), "un id que no es uuid no llega a Supabase")
    }

    @Test
    fun bothReadsNeedAnAdministrator() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, call("/api/v1/admin/auditoria/movimientos", token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, call("/api/v1/admin/auditoria/${rowId(1)}", token = null).status)
        assertTrue(sent.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ ruta antigua

    @Test
    fun theOldRouteKeepsItsShapeForThePanelAlreadyInstalled() = testApplication {
        application { module() }
        auditRows = "[${row(1)}]"
        val response = call("/api/v1/admin/auditoria")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray.single().jsonObject
        assertEquals(rowId(1), data["id"]!!.jsonPrimitive.content)
        assertEquals(ana, data["usuarioId"]!!.jsonPrimitive.content)
        assertEquals("perfiles_usuario", data["tablaAfectada"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------------------------------------ contador

    @Test
    fun aLostAuditWriteIsCountedAndARequiredOneStillFailsTheRequest() {
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({}) { V1StubResponse(500, """{"message":"boom"}""") },
        )
        fun count(required: Boolean): Double =
            BackendPrometheusMetrics.registry.find(AUDIT_WRITE_FAILURES_METRIC).tag("required", required.toString()).counter()?.count() ?: 0.0

        val optionalBefore = count(false)
        val requiredBefore = count(true)

        // No obligatoria: la operacion sigue (no lanza), pero la perdida queda contada.
        gateway.registrarAuditoria("etiqueta_eliminada", "etiquetas", target)
        assertEquals(optionalBefore + 1, count(false))
        assertEquals(requiredBefore, count(true))

        // Obligatoria: se cuenta aparte y el comportamiento no cambia, la peticion falla.
        val error = runCatching { gateway.registrarAuditoria("cuenta_dada_de_baja", "perfiles_usuario", target, required = true) }.exceptionOrNull()
        assertTrue(error is BackendDomainException && error.errorCode == "INTERNAL", error.toString())
        assertEquals(requiredBefore + 1, count(true))

        // El nombre con el que lo vera Prometheus.
        assertTrue("audit_write_failures_total" in BackendPrometheusMetrics.registry.scrape())
    }
}
