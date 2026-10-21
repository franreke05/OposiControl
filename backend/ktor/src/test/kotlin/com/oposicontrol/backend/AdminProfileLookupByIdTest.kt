package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RC-04 (MASTERF2-API-003 / MASTERF1-API-008) y RC-06 (MASTERF2-API-008).
 *
 * El PostgREST simulado tiene 1004 perfiles: la ventana de los 1000 MAS NUEVOS contiene relleno y las dos "Ana", y los
 * referenciados por las filas (usuaria antigua, staff antiguo) quedan FUERA; solo aparecen si se piden por id/nombre. Con el
 * AdminV1Routes.kt de HEAD (`order=creado_en.desc&limit=1000` + `firstOrNull`) los nombres salen como uuid, la respuesta
 * de soporte descarga 1000 filas y la concesion de acceso toma el PRIMER "Ana" (o falla con un usuario antiguo).
 */
class AdminProfileLookupByIdTest {
    private val oldUser = "7c7c7c7c-0000-4000-8000-000000000001"
    private val oldStaff = "7c7c7c7c-0000-4000-8000-000000000002"
    private val anaOne = "7c7c7c7c-0000-4000-8000-000000000003"
    private val anaTwo = "7c7c7c7c-0000-4000-8000-000000000004"
    private val conversation = "7c7c7c7c-0000-4000-8000-0000000000c1"
    private val report = "7c7c7c7c-0000-4000-8000-0000000000e1"
    private val examA1 = "7c7c7c7c-0000-4000-8000-0000000000a1"
    private val examA2 = "7c7c7c7c-0000-4000-8000-0000000000a2"
    private val examB = "7c7c7c7c-0000-4000-8000-0000000000b1"
    private val specialty = "7c7c7c7c-0000-4000-8000-0000000000f1"

    private data class Perfil(val id: String, val nombre: String, val username: String)

    private val old = listOf(
        Perfil(oldUser, "Usuaria Antigua", "antigua"),
        Perfil(oldStaff, "Staff Antiguo", "staff_antiguo"),
        Perfil(anaOne, "Ana", "ana_uno"),
        Perfil(anaTwo, "Ana", "ana_dos"),
    )
    private val newest = (1..1000).map { Perfil("8d8d8d8d-0000-4000-8000-%012d".format(it), "Relleno $it", "relleno_$it") }
    private val exams = listOf(examA1 to "Examen A", examA2 to "Examen A", examB to "Examen B")

    private fun row(p: Perfil) =
        """{"usuario_id":"${p.id}","nombre_mostrar":"${p.nombre}","username":"${p.username}","especialidad_principal_id":"$specialty"}"""

    private fun rows(ps: List<Perfil>) = ps.joinToString(",", "[", "]") { row(it) }

    private fun decoded(uri: String) = URLDecoder.decode(uri, StandardCharsets.UTF_8)

    private fun profiles(uri: String): String {
        val all = newest + old
        val q = decoded(uri)
        return when {
            "usuario_id=in.(" in q -> {
                val ids = q.substringAfter("usuario_id=in.(").substringBefore(')').split(',').toSet()
                rows(all.filter { it.id in ids })
            }
            "usuario_id=eq." in q -> rows(all.filter { it.id == q.substringAfter("usuario_id=eq.").substringBefore('&') })
            "username=eq." in q -> rows(all.filter { it.username == q.substringAfter("username=eq.").substringBefore('&') })
            "nombre_mostrar=eq." in q -> rows(all.filter { it.nombre == q.substringAfter("nombre_mostrar=eq.").substringBefore('&') })
            // La ventana antigua: los 1000 mas nuevos (las dos "Ana" estan dentro: el first-match de HEAD concederia a una).
            else -> rows((old.filter { it.nombre == "Ana" } + newest).take(1000))
        }
    }

    private fun examsFor(uri: String): String {
        val q = decoded(uri)
        val match = when {
            "id=eq." in q -> exams.filter { it.first == q.substringAfter("id=eq.").substringBefore('&') }
            "titulo=eq." in q -> exams.filter { it.second == q.substringAfter("titulo=eq.").substringBefore('&') }
            else -> exams
        }
        return match.joinToString(",", "[", "]") { """{"id":"${it.first}","titulo":"${it.second}"}""" }
    }

    private fun responder(uri: String): V1StubResponse = when {
        "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, profiles(uri))
        "/rest/v1/especialidades" in uri -> V1StubResponse(200, """[{"id":"$specialty","nombre":"Enfermeria"}]""")
        "/rest/v1/carteras_usuario" in uri -> V1StubResponse(200, """[{"usuario_id":"$oldUser","saldo_tickets":5}]""")
        "/rest/v1/conversaciones_soporte" in uri ->
            V1StubResponse(200, """[{"id":"$conversation","usuario_id":"$oldUser","asunto":"Ayuda"}]""")
        "/rest/v1/mensajes_soporte" in uri -> V1StubResponse(
            200,
            """[{"id":"m1","conversacion_id":"$conversation","remitente_id":"$oldStaff","cuerpo":"hola","es_staff":true}]""",
        )
        "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
        "/rest/v1/rpc/admin_grant_exam_access" in uri ->
            V1StubResponse(200, """[{"outcome":"CREATED","grant_id":"g1","valido_desde":"2026-10-16T10:00:00+00:00","valido_hasta":"2026-11-15T10:00:00+00:00"}]""")
        "/rest/v1/examenes" in uri -> V1StubResponse(200, examsFor(uri))
        // La comprobacion previa de «ya tiene acceso» (por usuario y examen): no lo tiene.
        "/rest/v1/contenido_desbloqueado_usuario" in uri && "usuario_id=eq." in uri -> V1StubResponse(200, "[]")
        "/rest/v1/contenido_desbloqueado_usuario" in uri ->
            V1StubResponse(200, """[{"id":"g1","usuario_id":"$oldUser","examen_id":"$examB","motivo":"admin"}]""")
        "/rest/v1/reportes_contenido" in uri -> V1StubResponse(200, """[{"id":"$report","reportado_por":"$oldUser"}]""")
        "/rest/v1/acciones_moderacion" in uri ->
            V1StubResponse(200, """[{"id":"a1","reporte_id":"$report","moderador_id":"$oldStaff","tipo_accion":"advertir"}]""")
        else -> V1StubResponse(200, "[]")
    }

    private fun gateway(sent: MutableList<HttpRequest>) = AdminV1Gateway.forTesting(
        "https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true },
        V1StubHttpClient({ sent += it }, ::responder),
    )

    private fun List<HttpRequest>.profileQueries() =
        map { it.uri().toString() }.filter { "/rest/v1/perfiles_usuario" in it }

    // ------------------------------------------------------------------ RC-04

    @Test
    fun everyDisplayNameSiteResolvesUsersOutsideTheNewest1000ById() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val g = gateway(sent)
        val wallet = g.listTicketWallets().single()
        assertEquals("Usuaria Antigua", wallet.userName)
        assertEquals("Enfermeria", wallet.specialty, "fila completa: especialidad principal")
        assertEquals("Usuaria Antigua", g.listSupportConversations().single().userName)
        assertEquals("Staff Antiguo", g.listSupportMessages(conversation).single().authorName)
        assertEquals("Usuaria Antigua", g.listExamAccesses().single().userName)
        val action = g.listModerationActions().single()
        assertEquals("Usuaria Antigua", action.targetUser)
        assertEquals("Staff Antiguo", action.executor)
        val queries = sent.profileQueries()
        assertTrue(queries.none { "limit=1000" in it }, "ninguna ventana de 1000: $queries")
        assertTrue(queries.all { "usuario_id=in.(" in it }, "solo lecturas por id: $queries")
    }

    @Test
    fun aSupportReplyReadsTheSenderWithOneEqQuery() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val saved = """[{"id":"m2","conversacion_id":"$conversation","remitente_id":"$oldStaff","cuerpo":"respuesta","es_staff":true}]"""
        val g = AdminV1Gateway.forTesting(
            "https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true },
            V1StubHttpClient({ sent += it }) { uri ->
                if ("/rest/v1/mensajes_soporte" in uri) V1StubResponse(201, saved) else responder(uri)
            },
        )
        val message = g.sendSupportMessage(conversation, AdminSupportReplyDto(assignee = "", text = "respuesta", internal = false), oldStaff)
        assertEquals("Staff Antiguo", message.authorName)
        assertEquals(listOf("/rest/v1/perfiles_usuario?select=*&usuario_id=eq.$oldStaff&limit=1"), sent.profileQueries().map { it.substringAfter("stub.supabase.test") })
    }

    @Test
    fun manyIdsAreReadInBoundedChunks() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val ids = (1..150).map { "9e9e9e9e-0000-4000-8000-%012d".format(it) }
        val conversations = ids.joinToString(",", "[", "]") { """{"id":"c$it","usuario_id":"$it","asunto":"x"}""" }
        val g = AdminV1Gateway.forTesting(
            "https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true },
            V1StubHttpClient({ sent += it }) { uri ->
                when {
                    "/rest/v1/conversaciones_soporte" in uri -> V1StubResponse(200, conversations)
                    "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, "[]")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
        assertEquals(150, g.listSupportConversations().size)
        val chunks = sent.profileQueries().map { decoded(it).substringAfter("usuario_id=in.(").substringBefore(')').split(',') }
        assertEquals(listOf(100, 50), chunks.map { it.size }, "bloques acotados")
        assertEquals(ids.toSet(), chunks.flatten().toSet(), "cada id referenciado se pide exactamente una vez")
    }

    // ------------------------------------------------------------------ RC-06

    private fun grant(userName: String, examTitle: String, userId: String? = null, examId: String? = null) = AdminExamAccessDto(
        id = "", userName = userName, examTitle = examTitle, accessType = "admin", isActive = true, userId = userId, examId = examId,
    )

    private fun List<HttpRequest>.grantWrites() =
        filter { it.method() == "POST" && "admin_grant_exam_access" in it.uri().toString() }

    @Test
    fun anAmbiguousDisplayNameIs409AndGrantsNothing() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val failure = assertFailsWith<BackendDomainException> { gateway(sent).upsertExamAccess(grant("Ana", "Examen B")) }
        assertEquals(HttpStatusCode.Conflict, failure.status)
        assertEquals("EXAM_ACCESS_USER_AMBIGUOUS", failure.errorCode)
        assertTrue(sent.grantWrites().isEmpty(), "ninguna concesion con un nombre ambiguo")
    }

    @Test
    fun anAmbiguousExamTitleIs409AndGrantsNothing() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val failure = assertFailsWith<BackendDomainException> { gateway(sent).upsertExamAccess(grant("ana_uno", "Examen A")) }
        assertEquals(HttpStatusCode.Conflict, failure.status)
        assertEquals("EXAM_ACCESS_EXAM_AMBIGUOUS", failure.errorCode)
        assertTrue(sent.grantWrites().isEmpty())
    }

    @Test
    fun theUuidPathGrantsExactlyTheSelectedUserAndExam() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val result = gateway(sent).upsertExamAccess(grant("Ana", "Examen A", userId = anaTwo, examId = examA2))
        val written = Json.parseToJsonElement(readRequestBody(sent.grantWrites().single())).jsonObject
        assertEquals(anaTwo, written["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(examA2, written["p_examen"]!!.jsonPrimitive.content)
        assertEquals(anaTwo, result.userId)
    }

    @Test
    fun aUserOutsideTheNewest1000CanBeGrantedByUniqueName() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertExamAccess(grant("Usuaria Antigua", "Examen B"))
        val written = Json.parseToJsonElement(readRequestBody(sent.grantWrites().single())).jsonObject
        assertEquals(oldUser, written["p_usuario"]!!.jsonPrimitive.content)
        assertTrue(sent.profileQueries().none { "limit=1000" in it })
    }

    @Test
    fun anUnknownUserIs404AndAMalformedIdIs400() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val missing = assertFailsWith<BackendDomainException> { gateway(sent).upsertExamAccess(grant("Nadie", "Examen B")) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertFailsWith<IllegalArgumentException> { gateway(sent).upsertExamAccess(grant("Ana", "Examen B", userId = "ana")) }
        assertTrue(sent.grantWrites().isEmpty())
    }

    @Test
    fun theRouteAnswers409ForAnAmbiguousNameAndTheListCarriesTheIds() = testApplication {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { adminV1Routes(gateway(sent)) }
        }
        val response = client.post("/api/v1/admin/accesos") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"","userName":"Ana","examTitle":"Examen B","accessType":"admin","isActive":true}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertTrue(sent.grantWrites().isEmpty())
        val listed = Json.parseToJsonElement(client.get("/api/v1/admin/accesos").bodyAsText()).jsonArray.single().jsonObject
        assertEquals(oldUser, listed["userId"]!!.jsonPrimitive.content)
        assertEquals(examB, listed["examId"]!!.jsonPrimitive.content)
    }
}
