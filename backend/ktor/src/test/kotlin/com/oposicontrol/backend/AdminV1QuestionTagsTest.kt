package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET`/`PUT /api/v1/admin/preguntas/{id}/etiquetas`: las etiquetas (temas) de una pregunta en `etiquetas_pregunta`.
 *
 * Lo que se fija: el PUT valida antes de tocar la base, responde 404 si la pregunta no existe, escribe SÓLO el diff
 * (DELETE de las quitadas + INSERT de las nuevas), deja una fila de auditoría con los ids de antes y después, y no
 * escribe ni audita si no cambia nada. Y que sólo un administrador llega a la ruta.
 */
class AdminV1QuestionTagsTest {

    private val preguntaId = "77777777-7777-4777-8777-777777777777"
    private val tagA = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val tagB = "bbbbbbbb-0000-4000-8000-00000000000b"
    private val tagC = "cccccccc-0000-4000-8000-00000000000c"
    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val userUid = "22222222-bbbb-4bbb-8bbb-000000000002"
    private val adminToken = "admin-token"
    private val userToken = "user-token"
    private val json = Json { ignoreUnknownKeys = true }

    private inner class Upstream(
        val questionExists: Boolean = true,
        /** Lo que hay en `etiquetas_pregunta` antes del PUT. */
        val current: List<String> = listOf(tagA, tagB),
        /** Estado de cada etiqueta del catálogo (id → activa); las ausentes no existen. */
        val catalog: Map<String, Boolean> = mapOf(tagA to true, tagB to true, tagC to true),
    ) {
        val sent = CopyOnWriteArrayList<HttpRequest>()

        fun respond(uri: String): V1StubResponse = when {
            uri.contains("/rest/v1/preguntas?") ->
                V1StubResponse(200, if (questionExists) """[{"id":"$preguntaId"}]""" else "[]")
            uri.contains("/rest/v1/etiquetas_pregunta") && uri.contains("etiquetas(") ->
                V1StubResponse(200, current.joinToString(",", "[", "]") { embedded(it) })
            uri.contains("/rest/v1/etiquetas_pregunta?select=etiqueta_id") ->
                V1StubResponse(200, current.joinToString(",", "[", "]") { """{"etiqueta_id":"$it"}""" })
            uri.contains("/rest/v1/etiquetas_pregunta") -> V1StubResponse(201, "")
            uri.contains("/rest/v1/etiquetas?") -> V1StubResponse(
                200,
                catalog.entries.filter { uri.contains(it.key) }
                    .joinToString(",", "[", "]") { """{"id":"${it.key}","activa":${it.value}}""" },
            )
            uri.contains("/rest/v1/auditoria_log") -> V1StubResponse(201, "")
            else -> V1StubResponse(200, "[]")
        }

        private fun embedded(id: String) =
            """{"etiqueta_id":"$id","etiquetas":{"id":"$id","nombre":"Tema ${id.first()}","color_hex":"#112233","activa":true}}"""

        fun writes(): List<HttpRequest> = sent.filter { it.method() != "GET" && !it.uri().rawPath.endsWith("/auditoria_log") }
        fun audits(): List<HttpRequest> = sent.filter { it.method() == "POST" && it.uri().rawPath.endsWith("/auditoria_log") }
    }

    @Test fun laLecturaEsUnaSolaConsultaEmbebidaYDevuelveIdNombreYColor() = testApplication {
        val upstream = Upstream()
        application { module(upstream) }

        val response = get("/api/v1/admin/preguntas/$preguntaId/etiquetas")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val read = upstream.sent.single()
        assertTrue(read.uri().rawPath.endsWith("/rest/v1/etiquetas_pregunta"), read.uri().toString())
        assertTrue(decodedQuery(read).contains("pregunta_id=eq.$preguntaId"), decodedQuery(read))
        val data = json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray
        assertEquals(listOf(tagA, tagB), data.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("#112233", data.first().jsonObject["colorHex"]!!.jsonPrimitive.content)
    }

    @Test fun elPutCalculaElDiffBorraLasQuitadasAnadeLasNuevasYAuditaAntesYDespues() = testApplication {
        val upstream = Upstream(current = listOf(tagA, tagB))
        application { module(upstream) }

        val response = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":["$tagA","$tagC"]}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val writes = upstream.writes()
        assertEquals(listOf("DELETE", "POST"), writes.map { it.method() }, describeRequests(upstream.sent))
        val delete = decodedQuery(writes[0])
        assertTrue(delete.contains("pregunta_id=eq.$preguntaId") && delete.contains("etiqueta_id=in.($tagB)"), delete)
        assertEquals("""[{"pregunta_id":"$preguntaId","etiqueta_id":"$tagC"}]""", readRequestBody(writes[1]))
        // Sólo se comprueba el estado de las que se AÑADEN.
        val catalogRead = upstream.sent.single { it.uri().rawPath.endsWith("/rest/v1/etiquetas") }
        assertTrue(decodedQuery(catalogRead).contains("id=in.($tagC)"), decodedQuery(catalogRead))

        val audit = json.parseToJsonElement(readRequestBody(upstream.audits().single())).jsonArray.single().jsonObject
        assertEquals("pregunta_etiquetas_actualizadas", audit["accion"]!!.jsonPrimitive.content)
        assertEquals("etiquetas_pregunta", audit["tabla_afectada"]!!.jsonPrimitive.content)
        assertEquals(preguntaId, audit["registro_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(tagA, tagB), audit["antes"]!!.jsonObject["etiquetaIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(tagA, tagC), audit["despues"]!!.jsonObject["etiquetaIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun sinCambiosNoSeEscribeNiSeAudita() = testApplication {
        val upstream = Upstream(current = listOf(tagA, tagB))
        application { module(upstream) }

        val response = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":["$tagB","$tagA","$tagA"]}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(upstream.writes().isEmpty(), describeRequests(upstream.sent))
        assertTrue(upstream.audits().isEmpty(), describeRequests(upstream.sent))
    }

    @Test fun unaListaVaciaLasQuitaTodas() = testApplication {
        val upstream = Upstream(current = listOf(tagA, tagB))
        application { module(upstream) }

        val response = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":[]}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val delete = upstream.writes().single()
        assertEquals("DELETE", delete.method())
        assertTrue(decodedQuery(delete).contains("etiqueta_id=in.($tagA,$tagB)"), decodedQuery(delete))
        assertEquals(1, upstream.audits().size)
    }

    @Test fun unaPreguntaQueNoExisteEsUn404SinEscrituras() = testApplication {
        val upstream = Upstream(questionExists = false)
        application { module(upstream) }

        val response = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":["$tagA"]}""")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertTrue(upstream.writes().isEmpty() && upstream.audits().isEmpty(), describeRequests(upstream.sent))
    }

    @Test fun idsQueNoSonUuidOMasDeVeinteSonUn400SinPeticiones() = testApplication {
        val upstream = Upstream()
        application { module(upstream) }

        val noUuid = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":["$tagA#"]}""")
        assertEquals(HttpStatusCode.BadRequest, noUuid.status, noUuid.bodyAsText())

        val many = (1..21).map { "dddddddd-0000-4000-8000-%012d".format(it) }
        val tooMany = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", many.joinToString(",", """{"etiquetaIds":[""", "]}") { "\"$it\"" })
        assertEquals(HttpStatusCode.BadRequest, tooMany.status, tooMany.bodyAsText())

        val badPath = put("/api/v1/admin/preguntas/no-es-uuid/etiquetas", """{"etiquetaIds":[]}""")
        assertEquals(HttpStatusCode.BadRequest, badPath.status, badPath.bodyAsText())

        assertTrue(upstream.sent.isEmpty(), describeRequests(upstream.sent))
    }

    @Test fun anadirUnaEtiquetaInexistenteODesactivadaEsUn400SinEscrituras() = testApplication {
        val upstream = Upstream(current = emptyList(), catalog = mapOf(tagA to true, tagB to false))
        application { module(upstream) }

        val response = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":["$tagA","$tagB","$tagC"]}""")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue(body.contains(tagB) && body.contains(tagC) && !body.contains(tagA), body)
        assertTrue(upstream.writes().isEmpty() && upstream.audits().isEmpty(), describeRequests(upstream.sent))
    }

    @Test fun conservarUnaEtiquetaYaAsignadaQueEstaDesactivadaSeAdmite() = testApplication {
        val upstream = Upstream(current = listOf(tagB), catalog = mapOf(tagA to true, tagB to false))
        application { module(upstream) }

        val response = put("/api/v1/admin/preguntas/$preguntaId/etiquetas", """{"etiquetaIds":["$tagB","$tagA"]}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(listOf("POST"), upstream.writes().map { it.method() })
    }

    @Test fun sinTokenEs401YUnUsuarioNoAdminEs403AntesDeLeerNada() = testApplication {
        val upstream = Upstream()
        application { module(upstream) }
        val path = "/api/v1/admin/preguntas/$preguntaId/etiquetas"

        assertEquals(HttpStatusCode.Unauthorized, get(path, token = null).status)
        assertEquals(HttpStatusCode.Forbidden, get(path, token = userToken).status)
        assertEquals(HttpStatusCode.Unauthorized, put(path, """{"etiquetaIds":[]}""", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, put(path, """{"etiquetaIds":[]}""", token = userToken).status)
        assertTrue(upstream.sent.isEmpty(), describeRequests(upstream.sent))
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun decodedQuery(request: HttpRequest): String =
        URLDecoder.decode(request.uri().rawQuery.orEmpty(), Charsets.UTF_8)

    private fun Application.module(upstream: Upstream) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") {
                authenticate { credentials ->
                    when (credentials.token) {
                        adminToken -> UserIdPrincipal(adminUid)
                        userToken -> UserIdPrincipal(userUid)
                        else -> null
                    }
                }
            }
        }
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream.sent += it }, upstream::respond),
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

    private suspend fun ApplicationTestBuilder.get(path: String, token: String? = adminToken): HttpResponse =
        client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun ApplicationTestBuilder.put(path: String, body: String, token: String? = adminToken): HttpResponse =
        client.put(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
