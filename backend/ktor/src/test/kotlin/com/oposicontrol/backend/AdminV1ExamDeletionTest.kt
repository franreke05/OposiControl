package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **BORRAR un examen es borrarlo**, y el historial de los usuarios no se borra
 * con él.
 *
 * Hasta este cambio `DELETE /api/v1/admin/examenes/{id}` llamaba a
 * `archiveExamen`: hacía `PATCH {"activo":false,"publicado_en":null}` y auditaba
 * `examen_archivado`. La consola ofrecía «Eliminar» y el examen seguía en la
 * base, así que no había forma de quitar un examen de prueba del catálogo real.
 *
 * Lo que se fija aquí son las TRES cosas que ese borrado tiene que cumplir, y
 * cada una está escrita sobre lo que sale por el cable y no sobre lo que devuelve
 * la función: un borrado que además archiva devuelve exactamente lo mismo que uno
 * que no archiva.
 *
 *  1. **Borra de verdad.** Sale un `DELETE` sobre la fila, y no un `PATCH` de
 *     visibilidad.
 *  2. **No se lleva datos de usuarios por delante.** Si cuelga algo de las seis
 *     tablas de datos de usuario, NO borra nada y responde 409.
 *  3. **Es UNA sentencia.** Los enlaces (`preguntas_examen`,
 *     `lotes_importacion_preguntas`) cascadean con la fila —verificado en la base
 *     viva—, así que el borrado es atómico y no choca con la guarda Q4.
 *
 * Y la cuarta, que es la que evita la regresión de verdad: **archivar sigue
 * existiendo**, ahora en `POST /{id}/archivar`.
 */
class AdminV1ExamDeletionTest {

    private val examenId = "77777777-7777-4777-8777-777777777777"

    // -----------------------------------------------------------------
    // 1 · Borra de verdad
    // -----------------------------------------------------------------

    @Test
    fun borrarUnExamenLimpioBorraLaFilaYNoLaArchiva() {
        val peticiones = grabando { deleteExamen(examenId) }

        assertTrue(
            peticiones.any { it.method == "DELETE" && it.uri.contains("/rest/v1/examenes?id=eq.$examenId") },
            "no salió el DELETE de la fila del examen: $peticiones",
        )
        assertFalse(
            peticiones.any { it.method == "PATCH" && it.body.contains("\"activo\"") },
            "el borrado archivó en vez de borrar: $peticiones",
        )
    }

    /**
     * **UN solo DELETE**, el de la fila (EX-19 y la guarda Q4).
     *
     * `preguntas_examen` y `lotes_importacion_preguntas` hacen `ON DELETE CASCADE`
     * (verificado en la base viva por la revisión de C). Borrarlas antes a mano no
     * era atómico —un fallo a mitad dejaba el examen en pie y sin preguntas— y, con
     * la guarda Q4, borrar las asociaciones de un examen PUBLICADO antes que el
     * examen fallaría siempre: dejaría un publicado sin preguntas activas.
     */
    @Test
    fun borrarEsUnSoloDeleteDeLaFilaYLasCascadasHacenElResto() {
        val peticiones = grabando { deleteExamen(examenId) }
        val borrados = peticiones.filter { it.method == "DELETE" }.map { it.uri }

        assertEquals(1, borrados.size, "tiene que salir exactamente un DELETE: $borrados")
        assertTrue(borrados.single().contains("/rest/v1/examenes?id=eq.$examenId"), borrados.single())
        assertFalse(
            borrados.any { it.contains("preguntas_examen") || it.contains("lotes_importacion_preguntas") },
            "se borraron a mano las tablas que cascadean: $borrados",
        )
    }

    @Test
    fun borrarDejaRastroDeAuditoriaComoEliminadoYNoComoArchivado() {
        val peticiones = grabando { deleteExamen(examenId) }
        val auditoria = peticiones.filter { it.uri.contains("/rest/v1/auditoria_log") }.map { it.body }

        assertTrue(auditoria.any { it.contains("examen_eliminado") }, "no se auditó el borrado: $auditoria")
        assertFalse(auditoria.any { it.contains("examen_archivado") }, "el borrado se auditó como archivado: $auditoria")
    }

    @Test
    fun laRutaDevuelve204CuandoElExamenSeBorra() = testApplication {
        application { examModule(::respuestaLimpia) }

        val response = client.delete("/api/v1/admin/examenes/$examenId")

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
    }

    // -----------------------------------------------------------------
    // 2 · Los datos de usuario bloquean el borrado
    // -----------------------------------------------------------------

    @Test
    fun unExamenConIntentosDeUsuarioNoSeBorra() {
        val peticiones = CopyOnWriteArrayList<Peticion>()
        val gateway = gatewayWith(record = { peticiones += it.asPeticion() }) { uri ->
            respuestaConDatosDeUsuario(uri, tabla = "intentos_examen")
        }

        val error = assertFailsWith<BackendDomainException> { gateway.deleteExamen(examenId) }

        assertEquals("EXAM_HAS_USER_DATA", error.errorCode)
        assertEquals(HttpStatusCode.Conflict, error.status)
        assertFalse(
            peticiones.any { it.method == "DELETE" },
            "se borró algo pese a haber datos de usuario: $peticiones",
        )
    }

    /** Las seis tablas bloquean, no sólo la primera que se consulta. */
    @Test
    fun cualquieraDeLasSeisTablasDeDatosDeUsuarioBloqueaElBorrado() {
        listOf(
            "intentos_examen",
            "respuestas_intento",
            "abandonos_examen",
            "feedback_examen",
            "contenido_desbloqueado_usuario",
            "eventos_conversion_premium",
        ).forEach { tabla ->
            val peticiones = CopyOnWriteArrayList<Peticion>()
            val gateway = gatewayWith(record = { peticiones += it.asPeticion() }) { uri ->
                respuestaConDatosDeUsuario(uri, tabla)
            }

            val error = assertFailsWith<BackendDomainException>("«$tabla» no bloqueó el borrado") {
                gateway.deleteExamen(examenId)
            }

            assertEquals("EXAM_HAS_USER_DATA", error.errorCode, tabla)
            assertFalse(peticiones.any { it.method == "DELETE" }, "«$tabla»: se borró algo: $peticiones")
        }
    }

    /**
     * EXAM-004, aplicado al rechazo nuevo: el mensaje dice QUÉ hay colgando y qué
     * hacer en su lugar, y no filtra nada del esquema ni el identificador.
     */
    @Test
    fun elRechazoExplicaQueHayYQueHacerSinFiltrarEsquemaNiIdentificadores() {
        val error = assertFailsWith<BackendDomainException> {
            gatewayWith { uri -> respuestaConDatosDeUsuario(uri, tabla = "contenido_desbloqueado_usuario") }
                .deleteExamen(examenId)
        }

        assertTrue(
            error.message.contains("Archívalo", ignoreCase = true),
            "no dice qué hacer en su lugar: ${error.message}",
        )
        listOf(
            "contenido_desbloqueado_usuario",
            "intentos_examen",
            "examen_id",
            "rest/v1",
            examenId,
        ).forEach { fuga ->
            assertFalse(
                error.message.contains(fuga, ignoreCase = true),
                "el mensaje filtra «$fuga»: ${error.message}",
            )
        }
    }

    @Test
    fun laRutaDevuelve409ConElCodigoDeDominio() = testApplication {
        application { examModule { uri -> respuestaConDatosDeUsuario(uri, tabla = "intentos_examen") } }

        val response = client.delete("/api/v1/admin/examenes/$examenId")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertTrue(body.contains("exam_has_user_data"), body)
    }

    /**
     * Si la comprobación no se puede hacer, NO se borra.
     *
     * Un fallo al contar dependientes no puede acabar en un borrado «por si
     * acaso»: se responde 409 diciendo que no se ha comprobado y que no se ha
     * borrado nada. La alternativa —dejar subir el fallo como 500— también sería
     * segura, pero un 500 durante una acción destructiva no dice si borró.
     */
    @Test
    fun siLaComprobacionFallaNoSeBorraNada() {
        val peticiones = CopyOnWriteArrayList<Peticion>()
        val gateway = gatewayWith(record = { peticiones += it.asPeticion() }) { uri ->
            when {
                uri.contains("/rest/v1/examenes?select=id") -> V1StubResponse(200, "[{\"id\":\"$examenId\"}]")
                uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(500, "{\"message\":\"boom\"}")
                else -> V1StubResponse(200, "[]")
            }
        }

        val error = assertFailsWith<BackendDomainException> { gateway.deleteExamen(examenId) }

        assertEquals("EXAM_DELETE_GUARD_UNAVAILABLE", error.errorCode)
        assertEquals(HttpStatusCode.Conflict, error.status)
        assertFalse(peticiones.any { it.method == "DELETE" }, "se borró sin comprobar: $peticiones")
    }

    @Test
    fun borrarUnExamenQueNoExisteEsUn404YNoTocaNada() = testApplication {
        application { examModule { V1StubResponse(200, "[]") } }

        val response = client.delete("/api/v1/admin/examenes/$examenId")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.NotFound, response.status, body)
        assertTrue(body.contains("not_found"), body)
    }

    // -----------------------------------------------------------------
    // 3 · Archivar sigue existiendo, con ruta propia
    // -----------------------------------------------------------------

    @Test
    fun laRutaDeArchivarArchivaYNoBorra() {
        val peticiones = CopyOnWriteArrayList<Peticion>()
        gatewayWith(record = { peticiones += it.asPeticion() }) { V1StubResponse(200, "[${examenRowJson()}]") }
            .archiveExamen(examenId)

        assertTrue(
            peticiones.any { it.method == "PATCH" && it.body.contains("\"activo\":false") },
            "archivar dejó de escribir `activo`: $peticiones",
        )
        assertFalse(peticiones.any { it.method == "DELETE" }, "archivar borró filas: $peticiones")
    }

    @Test
    fun laRutaDeArchivarResponde200ConElExamen() = testApplication {
        application { examModule { V1StubResponse(200, "[${examenRowJson()}]") } }

        val response = client.post("/api/v1/admin/examenes/$examenId/archivar")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun archivarUnExamenQueNoExisteSigueSiendo404() = testApplication {
        application { examModule { V1StubResponse(200, "[]") } }

        val response = client.post("/api/v1/admin/examenes/$examenId/archivar")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("not_found"))
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private data class Peticion(val method: String, val uri: String, val body: String)

    private fun grabando(accion: AdminV1Gateway.() -> Unit): List<Peticion> {
        val peticiones = CopyOnWriteArrayList<Peticion>()
        gatewayWith(record = { peticiones += it.asPeticion() }, responder = ::respuestaLimpia).accion()
        return peticiones
    }

    /** Un examen que existe y del que no cuelga ningún dato de usuario. */
    private fun respuestaLimpia(uri: String): V1StubResponse = when {
        uri.contains("/rest/v1/examenes?select=id") -> V1StubResponse(200, "[{\"id\":\"$examenId\"}]")
        else -> V1StubResponse(200, "[]")
    }

    /** El mismo examen, pero con una fila colgando de [tabla]. */
    private fun respuestaConDatosDeUsuario(uri: String, tabla: String): V1StubResponse = when {
        uri.contains("/rest/v1/examenes?select=id") -> V1StubResponse(200, "[{\"id\":\"$examenId\"}]")
        uri.contains("/rest/v1/$tabla?") -> V1StubResponse(200, "[{\"id\":\"11111111-1111-4111-8111-111111111111\"}]")
        else -> V1StubResponse(200, "[]")
    }

    private fun examenRowJson(): String =
        """{"id":"$examenId","codigo_examen":"EX-7","especialidad_id":"66666666-6666-4666-8666-666666666666",""" +
            """"titulo":"Examen","tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true,"total_preguntas":3}"""

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
    )

    private fun Application.examModule(responder: (String) -> V1StubResponse) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            exception<BackendDomainException> { call, cause ->
                call.respond(cause.status, ErrorDto(error = cause.errorCode.lowercase(), message = cause.message))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
        }
        routing { adminV1Routes(gatewayWith(responder = responder)) }
    }

    private fun HttpRequest.asPeticion(): Peticion = Peticion(method(), uri().toString(), bodyText())

    private fun HttpRequest.bodyText(): String = readRequestBody(this)
}
