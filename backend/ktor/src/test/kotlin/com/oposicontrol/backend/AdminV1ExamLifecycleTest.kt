package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El CICLO DE VIDA de un examen y el VOCABULARIO con el que se guarda: dos cosas
 * que hasta ahora sólo estaban garantizadas por la buena conducta del único
 * cliente que existe.
 *
 * Vive aparte de `AdminV1ExamQuestionsTest` —que fija el coste de las lecturas y
 * la semántica merge-patch de las tres columnas anulables— porque lo que se
 * afirma aquí es de otra clase: **qué columnas puede tocar cada verbo** y **qué
 * valores puede aceptar la base**. Los dos se comprueban mirando lo que sale por
 * el cable, no lo que devuelve la función: un PATCH que escribe una columna de
 * más devuelve exactamente lo mismo que uno que no la escribe.
 */
class AdminV1ExamLifecycleTest {

    private val examenId = "55555555-5555-4555-8555-555555555555"
    private val especialidadId = "66666666-6666-4666-8666-666666666666"
    private val comunidadId = "77777777-7777-4777-8777-777777777777"

    // -----------------------------------------------------------------
    // E-1 · Guardar no cambia la visibilidad
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación de E-1.**
     *
     * El defecto salía de combinar dos `encodeDefaults` opuestos:
     * `AdminExamenDto.activo` vale `true` por defecto, una clave ausente se
     * decodifica a ese default, y `sbJson` escribe con `encodeDefaults = true`.
     * Resultado: un PATCH que no mencionara `activo` escribía `"activo": true` y
     * **desarchivaba el examen en silencio**.
     *
     * Se afirma sobre el cuerpo que sale, y no sobre el DTO que vuelve, porque el
     * DTO que vuelve lo compone la respuesta de la base: si la columna se
     * reescribió, la respuesta lo confirmaría tan tranquila.
     */
    @Test
    fun aPatchThatOmitsActivoDoesNotUnarchiveTheExam() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
            },
        )

        assertFalse(
            body.contains("activo"),
            "un PATCH sin `activo` no puede escribir la columna: eso desarchiva el examen. Cuerpo: $body",
        )
    }

    /**
     * Y tampoco cuando el cliente la manda explícitamente. La regla no es «no
     * adivines la visibilidad», es **«guardar no cambia la visibilidad»**: si
     * bastara con mandarla, seguiría en pie el «último que guarda gana» (A
     * publica, B guarda con su copia vieja y despublica lo de A).
     */
    @Test
    fun aPatchThatSendsActivoExplicitlyStillDoesNotChangeVisibility() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
                put("activo", true)
                put("publicadoEn", "2026-01-01T00:00:00Z")
            },
        )

        assertFalse(body.contains("\"activo\""), "el PATCH escribió `activo`: $body")
        assertFalse(
            body.contains("publicado_en"),
            "el PATCH escribió `publicado_en`: el guardado no puede re-sellar la fecha de " +
                "publicación. Cuerpo: $body",
        )
    }

    /**
     * Publicar sella `publicado_en`, y tiene que sellarlo: es su trabajo.
     *
     * DEBT-026 —guardar un examen publicado lo re-publicaba y re-sellaba la fecha—
     * vivía en el cliente, que encadenaba un `POST …/publicar` después de cada
     * guardado. K0-2 quitó ese encadenado en `KtorAdminV1ContentRepositoryImpl`
     * (lo fija `savingAnExamNeverTouchesItsLifecycle` en `data`); lo que este caso
     * protege es la otra mitad: que nadie «arregle» DEBT-026 haciendo que publicar
     * deje de sellar la fecha.
     *
     * (Nota para quien edite este KDoc: no escribas aquí la ruta con el glob
     * de dos asteriscos. Kotlin admite comentarios de bloque ANIDADOS, así que
     * esa secuencia abre uno dentro del KDoc y el cierre de abajo cierra sólo
     * el interno: el externo se traga el resto del fichero y el error sale como
     * «Unclosed comment» en la última línea, a 300 líneas del sitio real.)
     *
     * El caso comprueba justamente eso: publicar sigue escribiendo la fecha. Si
     * alguien «arregla» DEBT-026 haciendo que `publicar` deje de sellarla, este
     * caso se pone rojo y el arreglo se ve.
     */
    @Test
    fun publishingStillSealsTheDate_soDebt026IsNotClosedByThisChange() {
        val bodies = CopyOnWriteArrayList<String>()

        gatewayRecordingBodies(bodies).publicarExamen(examenId)

        assertTrue(
            bodies.any { it.contains("\"publicado_en\":\"") },
            "publicar tiene que seguir sellando la fecha: $bodies",
        )
    }

    /** El resto del parche sigue llegando entero: esto acota una columna, no rompe el PATCH. */
    @Test
    fun everythingThatIsNotVisibilityStillTravelsInThePatch() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
                put("codigoExamen", "EX-9")
                put("esOficial", true)
                put("precioTickets", 25)
            },
        )

        assertTrue(body.contains("\"titulo\":\"Examen\""), body)
        assertTrue(body.contains("\"codigo_examen\":\"EX-9\""), body)
        assertTrue(body.contains("\"es_oficial\":true"), body)
        assertTrue(body.contains("\"precio_tickets\":25"), body)
    }

    /**
     * La otra mitad de E-1: si el PATCH deja de escribir la visibilidad, alguien
     * tiene que escribirla. Son las tres capacidades de ciclo de vida, y este
     * caso es el que impide «arreglar» el anterior quitándolas también.
     */
    @Test
    fun publishingIsWhatWritesTheVisibilityColumns() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayRecordingBodies(bodies)

        gateway.publicarExamen(examenId)

        val patch = bodies.first { it.contains("publicado_en") }
        assertTrue(patch.contains("\"activo\":true"), patch)
    }

    @Test
    fun unpublishingClearsThePublicationDateAndNeverTouchesActivo() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayRecordingBodies(bodies)

        gateway.despublicarExamen(examenId)

        val patch = bodies.first { it.contains("publicado_en") }
        // Despublicar un archivado no puede dejarlo publicado-pero-inactivo: es
        // un estado que ninguna lectura de la app entiende.
        assertFalse(patch.contains("activo"), "despublicar no toca `activo`: $patch")
    }

    // -----------------------------------------------------------------
    // E-2 · Un id inexistente es 404, no 500
    // -----------------------------------------------------------------

    /**
     * Las tres capacidades devuelven `null` cuando el filtro no casa con ninguna
     * fila. Antes hacían `error("Examen no encontrado…")`, y un
     * `IllegalStateException` sale por el handler de fallos upstream: un 500
     * redactado con una referencia de incidencia, indistinguible de un Supabase
     * caído de verdad.
     */
    @Test
    fun lifecycleActionsOnAMissingExamReturnNullInsteadOfThrowing() {
        val gateway = gatewayWith { V1StubResponse(200, "[]") }

        assertNull(gateway.publicarExamen(examenId), "publicar")
        assertNull(gateway.despublicarExamen(examenId), "despublicar")
        assertNull(gateway.archiveExamen(examenId), "archivar")
    }

    @Test
    fun theRouteTurnsThatNullIntoTheSameNotFoundAsTheDetailEndpoint() = testApplication {
        application { examModule { V1StubResponse(200, "[]") } }

        listOf(
            "/api/v1/admin/examenes/$examenId/publicar",
            "/api/v1/admin/examenes/$examenId/despublicar",
        ).forEach { path ->
            val response = client.post(path)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, response.status, "$path -> $body")
            assertTrue(body.contains("not_found"), "$path -> $body")
        }

        val archived = client.delete("/api/v1/admin/examenes/$examenId")
        val archivedBody = archived.bodyAsText()
        assertEquals(HttpStatusCode.NotFound, archived.status, archivedBody)
        assertTrue(archivedBody.contains("not_found"), archivedBody)
    }

    /** Un id que existe sigue devolviendo 200 con la fila: el 404 no se come el camino feliz. */
    @Test
    fun anExamThatExistsStillPublishesWithTwoHundred() = testApplication {
        application { examModule { V1StubResponse(200, "[${examenRowJson()}]") } }

        val response = client.post("/api/v1/admin/examenes/$examenId/publicar")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    /**
     * Los estados por defecto son parte del contrato de salida. Omitirlos hace
     * indistinguible `gratuito`/`rafaga`/activo de una respuesta incompleta y
     * rompe el PATCH parcial aunque la base haya conservado correctamente todo.
     */
    @Test
    fun defaultExamStateIsExplicitInTheHttpResponse() = testApplication {
        application { examModule { V1StubResponse(200, "[${examenRowJson()}]") } }

        val response = client.post("/api/v1/admin/examenes/$examenId/publicar")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            .getValue("data").jsonObject

        assertEquals("rafaga", body.getValue("tipoExamen").jsonPrimitive.content)
        assertEquals("gratuito", body.getValue("tipoAcceso").jsonPrimitive.content)
        assertFalse(body.getValue("esOficial").jsonPrimitive.boolean)
        assertTrue(body.getValue("activo").jsonPrimitive.boolean)
        assertEquals(0, body.getValue("precioTickets").jsonPrimitive.int)
    }

    // -----------------------------------------------------------------
    // K-DEC01 · DEC-EX-01 = A: un examen sin preguntas no se publica
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación de K-DEC01.** La regla va DENTRO del PATCH que
     * publica (`total_preguntas=gt.0`), no en una lectura previa que otro
     * administrador podría dejar vieja. El doble sólo casa la fila si el filtro NO
     * está: si alguien lo quita, el examen vacío se publica y este caso se pone rojo.
     */
    @Test
    fun anExamWithoutQuestionsIsNotPublishedAndTheRuleLivesInThePublishingPatch() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = upstream::add) { uri ->
            when {
                uri.contains("/rest/v1/examenes") && uri.contains("total_preguntas=gt.0") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[${examenRowJson()}]")
                else -> V1StubResponse(200, "[]")
            }
        }

        val error = assertFailsWith<BackendDomainException> { gateway.publicarExamen(examenId) }

        assertEquals(EXAM_PUBLISH_REQUIRES_QUESTIONS_CODE, error.errorCode)
        assertEquals(HttpStatusCode.Conflict, error.status)
        val patch = upstream.single { it.method() == "PATCH" }
        assertTrue(patch.uri().rawQuery.orEmpty().contains("total_preguntas=gt.0"), patch.uri().toString())
        assertTrue(
            upstream.none { it.uri().rawPath.contains("auditoria_log") },
            "un rechazo no deja `examen_publicado` en la auditoría: ${upstream.map { it.uri() }}",
        )
    }

    /** Y la ruta lo publica como 409 legible —el mismo que da la guarda de la base—, no como 404 ni 500. */
    @Test
    fun theRouteAnswersConflictWhenPublishingAnEmptyExam() = testApplication {
        application {
            examModule { uri ->
                if (uri.contains("total_preguntas=gt.0")) V1StubResponse(200, "[]") else V1StubResponse(200, "[${examenRowJson()}]")
            }
        }

        val response = client.post("/api/v1/admin/examenes/$examenId/publicar")

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertTrue(body.contains("exam_publish_requires_questions"), body)
    }

    // -----------------------------------------------------------------
    // E-5 · Vocabulario canónico, impuesto en el servidor
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación de E-5.** Un tipo desconocido se RECHAZA. La
     * tentación —y lo que hace la función de canonización del cliente en el
     * camino de importación— es caer a `rafaga`, que reetiqueta el examen como
     * algo que no es y destruye el dato sin decirlo.
     *
     * Se afirma además que **no salió ninguna petición**: si la validación
     * ocurriera después de llamar al upstream, el 422 estaría tapando una
     * escritura ya hecha.
     */
    @Test
    fun anUnknownExamTypeIsRejectedAndNeverCoercedToRafaga() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = upstream::add) { V1StubResponse(200, "[${examenRowJson()}]") }

        val error = assertFailsWith<BackendDomainException> {
            gateway.createExamen(
                AdminExamenDto(especialidadId = especialidadId, titulo = "Examen", tipoExamen = "chorrada"),
            )
        }

        assertEquals("EXAM_TYPE_UNKNOWN", error.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, error.status)
        assertTrue(upstream.isEmpty(), "se llamó al upstream antes de validar: ${upstream.size} peticiones")
    }

    @Test
    fun anUnknownAccessTypeIsRejectedTheSameWay() {
        val error = assertFailsWith<BackendDomainException> {
            gatewayWith { V1StubResponse(200, "[${examenRowJson()}]") }.createExamen(
                AdminExamenDto(especialidadId = especialidadId, titulo = "Examen", tipoAcceso = "vip"),
            )
        }

        assertEquals("EXAM_ACCESS_TYPE_UNKNOWN", error.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, error.status)
    }

    /**
     * El alias legacy se acepta en la ENTRADA y se escribe canónico.
     *
     * Quien manda `guiado` no está equivocado, está desactualizado. Lo que no
     * puede pasar es que `guiado` vuelva a la columna: dejaría la base con dos
     * vocabularios a la vez y cada lector tendría que conocer los dos.
     */
    @Test
    fun aLegacyAliasIsAcceptedOnInputAndWrittenCanonical() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayRecordingBodies(bodies)

        gateway.createExamen(
            AdminExamenDto(especialidadId = especialidadId, titulo = "Examen", tipoExamen = "guiado"),
        )

        val insert = bodies.first { it.contains("titulo") }
        assertTrue(insert.contains("\"tipo_examen\":\"razonado\""), insert)
        assertFalse(insert.contains("guiado"), "el alias legacy NUNCA se escribe: $insert")
    }

    @Test
    fun theSameRuleAppliesToUpdatesAndNotOnlyToCreates() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
                put("tipoExamen", "guiado")
            },
        )

        assertTrue(body.contains("\"tipo_examen\":\"razonado\""), body)
    }

    /**
     * EXAM-004: nada de lo que escribió el cliente vuelve en el mensaje, y el
     * mensaje sí nombra los valores válidos — que es lo único que le sirve a
     * quien lo lee para arreglarlo.
     */
    @Test
    fun theRejectionNamesTheValidValuesAndNeverEchoesWhatWasSent() {
        val secreto = "valor-que-no-debe-volver"
        val error = assertFailsWith<BackendDomainException> {
            gatewayWith { V1StubResponse(200, "[]") }.createExamen(
                AdminExamenDto(especialidadId = especialidadId, titulo = "Examen", tipoExamen = secreto),
            )
        }

        assertFalse(error.message.contains(secreto), "el mensaje devuelve la entrada del cliente: ${error.message}")
        listOf("rafaga", "razonado", "inmersivo").forEach { valido ->
            assertTrue(error.message.contains(valido), "el mensaje no nombra '$valido': ${error.message}")
        }
    }

    @Test
    fun theRouteAnswersFourTwentyTwoForAnUnknownVocabulary() = testApplication {
        application { examModule { V1StubResponse(200, "[${examenRowJson()}]") } }

        val response = client.post("/api/v1/admin/examenes") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"especialidadId":"$especialidadId","comunidadId":"$comunidadId","titulo":"Examen","tipoExamen":"chorrada"}""",
            )
        }

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
        assertTrue(body.contains("exam_type_unknown"), body)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // V1 · la comunidad es OBLIGATORIA (decision del propietario 2026-10-15)
    // -----------------------------------------------------------------

    private fun createBody(community: String?): String =
        """{"codigoExamen":"EX-V1","especialidadId":"$especialidadId",""" +
            (if (community == null) "" else """"comunidadId":$community,""") +
            """"titulo":"Examen V1","tipoExamen":"rafaga","tipoAcceso":"gratuito"}"""

    private suspend fun io.ktor.client.HttpClient.createExam(community: String?) =
        post("/api/v1/admin/examenes") { contentType(ContentType.Application.Json); setBody(createBody(community)) }

    @Test
    fun anExamCannotBeCreatedWithoutACommunityAndNothingReachesTheDatabase() = testApplication {
        val sent = CopyOnWriteArrayList<String>()
        application { examModule(record = { sent += "${it.method()} ${it.uri()}" }) { V1StubResponse(200, "[${examenRowJson()}]") } }

        listOf(null, "null", "\"\"", "\"   \"").forEach { community ->
            val response = client.createExam(community)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "$community -> $body")
            assertTrue(body.contains("exam_community_required"), "$community -> $body")
        }
        assertTrue(sent.isEmpty(), "una comunidad ausente no debe producir ninguna peticion: $sent")
    }

    @Test
    fun aMalformedCommunityIdIsBadRequestAndAnUnknownOrInactiveOneIsUnprocessable() = testApplication {
        application {
            examModule { uri ->
                if (uri.contains("comunidades_autonomas")) V1StubResponse(200, "[]") else V1StubResponse(200, "[${examenRowJson()}]")
            }
        }

        val malformed = client.createExam("\"not-a-uuid\"")
        assertEquals(HttpStatusCode.BadRequest, malformed.status, malformed.bodyAsText())

        val unknown = client.createExam("\"$comunidadId\"")
        val body = unknown.bodyAsText()
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status, body)
        assertTrue(body.contains("unknown_community"), body)
    }

    @Test
    fun anExamWithAnActiveCommunityIsCreated() = testApplication {
        val sent = CopyOnWriteArrayList<String>()
        application {
            examModule(record = { sent += "${it.method()} ${it.uri()}" }) { uri ->
                if (uri.contains("comunidades_autonomas")) V1StubResponse(200, """[{"id":"$comunidadId"}]""") else V1StubResponse(200, "[${examenRowJson()}]")
            }
        }

        val response = client.createExam("\"$comunidadId\"")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertTrue(sent.any { it.startsWith("GET") && it.contains("comunidades_autonomas") && it.contains("activa=eq.true") }, "$sent")
    }

    @Test
    fun theCommunityCanBeReplacedButNeverCleared() = testApplication {
        val sent = CopyOnWriteArrayList<String>()
        application {
            examModule(record = { sent += "${it.method()} ${it.uri()}" }) { uri ->
                if (uri.contains("comunidades_autonomas")) V1StubResponse(200, """[{"id":"$comunidadId"}]""") else V1StubResponse(200, "[${examenRowJson()}]")
            }
        }

        val cleared = client.patch("/api/v1/admin/examenes/$examenId") { contentType(ContentType.Application.Json); setBody("""{"comunidadId":null}""") }
        val clearedBody = cleared.bodyAsText()
        assertEquals(HttpStatusCode.UnprocessableEntity, cleared.status, clearedBody)
        assertTrue(clearedBody.contains("exam_community_required"), clearedBody)
        assertTrue(sent.none { it.startsWith("PATCH") }, "un borrado de comunidad no debe escribir nada: $sent")

        val replaced = client.patch("/api/v1/admin/examenes/$examenId") { contentType(ContentType.Application.Json); setBody("""{"comunidadId":"$comunidadId"}""") }
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
    }

    @Test
    fun anExamWithoutCommunityIsNotPublishedEvenIfItHasQuestions() = testApplication {
        application {
            examModule { uri ->
                when {
                    uri.contains("comunidad_id=not.is.null") -> V1StubResponse(200, "[]") // el filtro del PATCH no encuentra fila
                    else -> V1StubResponse(200, """[{"id":"$examenId","comunidad_id":null}]""")
                }
            }
        }

        val response = client.post("/api/v1/admin/examenes/$examenId/publicar")

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertTrue(body.contains("exam_publish_requires_community"), body)
    }

    @Test
    fun theRuleLivesInThePublishingStatementItself() {
        val sent = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { sent += "${it.method()} ${it.uri()}" }) { V1StubResponse(200, "[${examenRowJson()}]") }

        gateway.publicarExamen(examenId)

        val patch = assertNotNull(sent.firstOrNull { it.startsWith("PATCH") }, "$sent")
        assertTrue(patch.contains("comunidad_id=not.is.null") && patch.contains("total_preguntas=gt.0"), patch)
    }

    /** El cuerpo exacto del único PATCH que sale hacia `examenes`. */
    private fun patchBodyFor(raw: JsonObject): String {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(
            record = { request -> if (request.method() == "PATCH") bodies += request.bodyText() },
        ) { V1StubResponse(200, "[${examenRowJson()}]") }
        gateway.updateExamen(examenId, raw)
        return assertNotNull(bodies.singleOrNull(), "no salió exactamente un PATCH: $bodies")
    }

    private fun gatewayRecordingBodies(bodies: MutableList<String>): AdminV1Gateway =
        gatewayWith(record = { bodies += it.bodyText() }) { V1StubResponse(200, "[${examenRowJson()}]") }

    private fun examenRowJson(): String =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"$especialidadId","comunidad_id":"$comunidadId","titulo":"Examen",""" +
            """"tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true,"total_preguntas":3}"""

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
    )

    private fun Application.examModule(record: (HttpRequest) -> Unit = {}, responder: (String) -> V1StubResponse) {
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
        routing { adminV1Routes(gatewayWith(record = record, responder = responder)) }
    }

    private fun HttpRequest.bodyText(): String = readRequestBody(this)
}
