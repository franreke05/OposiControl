package com.oposicontrol.backend

import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El coste y la semántica de las preguntas de un examen, contra un upstream
 * simulado.
 *
 * Estos tests no miran sólo el resultado: miran **lo que sale por el cable**.
 * Las dos reglas que fijan —el coste no crece con el número de preguntas, y
 * desvincular no archiva— son exactamente las que se rompieron antes y las que
 * un refactor descuidado volvería a romper sin que ningún assert de contenido
 * se enterase.
 */
class AdminV1ExamQuestionsTest {

    // UUID reales: desde K-C01 el PATCH valida `especialidadId` y `comunidadId`.
    private val especialidadId = "66666666-6666-4666-8666-666666666666"
    private val comunidadId = "12121212-1212-4121-8121-121212121212"

    // -----------------------------------------------------------------
    // N+1: el coste no puede crecer con el número de preguntas
    // -----------------------------------------------------------------

    @Test
    fun readingTheQuestionsOfAnExamCostsTheSameForThreeAsForThirty() {
        val forThree = requestsForListingQuestions(questionCount = 3)
        val forThirty = requestsForListingQuestions(questionCount = 30)

        // ESTA ES LA PRUEBA DE MUTACIÓN del N+1. Antes esto era
        // `links.mapNotNull { getPregunta(it.preguntaId) }`, con 4 peticiones por
        // pregunta: 3 preguntas costaban 13 y 30 costaban 121. Si alguien
        // reintroduce el bucle, estas dos listas dejan de medir lo mismo y el
        // test cae aquí, no en un assert de contenido que seguiría en verde.
        assertEquals(
            forThree.size,
            forThirty.size,
            "El número de peticiones al upstream no puede depender del número de preguntas. " +
                "3 preguntas -> ${forThree.size} peticiones, 30 preguntas -> ${forThirty.size}.",
        )

        // 1 asociaciones + 1 preguntas + 1 opciones + 1 soluciones + 1 recursos.
        assertEquals(5, forThirty.size, "Se esperaban 5 lecturas agrupadas: ${forThirty.joinToString("\n")}")
    }

    @Test
    fun readingTheQuestionsOfAnExamNeverFetchesResourcesOncePerQuestion() {
        val requests = requestsForListingQuestions(questionCount = 30)

        assertEquals(
            1,
            requests.count { it.contains("/rest/v1/recursos_pregunta") },
            "Los recursos se piden una sola vez para todo el examen, no uno por pregunta.",
        )
        // La forma de una sola pregunta (`id=eq.`) delata el bucle; la agrupada
        // usa `id=in.(...)`.
        assertFalse(
            requests.any { it.contains("/rest/v1/preguntas?select=*&id=eq.") },
            "Ninguna pregunta se lee de una en una: ${requests.joinToString("\n")}",
        )
        assertTrue(
            requests.any { it.contains("/rest/v1/preguntas?select=") && it.contains("id=in.(") },
            "Las preguntas se leen agrupadas con id=in.(...).",
        )
    }

    @Test
    fun archivedQuestionsAreFilteredUpstreamInsteadOfDownloadedAndDiscarded() {
        val requests = requestsForListingQuestions(questionCount = 3)

        assertTrue(
            requests.any { it.contains("/rest/v1/preguntas?select=") && it.contains("activa=eq.true") },
            "Una pregunta archivada que conserve su vínculo no debe ni viajar.",
        )
    }

    @Test
    fun theAssembledQuestionsKeepOrderOptionsSolutionAndResources() {
        val gateway = gatewayWith(responder = examQuestionsResponder(questionCount = 3))

        val preguntas = gateway.listPreguntasExamen("EX1")

        assertEquals(listOf("P1", "P2", "P3"), preguntas.map { it.id })
        assertEquals(listOf(1, 2, 3), preguntas.map { it.posicion })
        val primera = preguntas.first()
        assertEquals("EX1", primera.examenId)
        assertEquals(2, primera.opciones.size)
        // La correcta se resuelve cruzando soluciones_pregunta con las opciones.
        assertEquals(listOf("A"), primera.opciones.filter { it.esCorrecta }.map { it.letra })
        assertEquals("porque si", primera.razonamiento)
        assertEquals(1, primera.recursos.size)
    }

    // -----------------------------------------------------------------
    // Desvincular no es archivar
    // -----------------------------------------------------------------

    /**
     * K3-2: desvincular es UNA llamada a `admin_unlink_question_from_exam`, que borra
     * el vínculo y recompacta las posiciones en la misma transacción.
     *
     * Era un DELETE + una lectura + un PATCH por fila restante: entre medias cabía
     * cualquier otra escritura, y un fallo a mitad dejaba el examen con huecos.
     */
    @Test
    fun unlinkingAQuestionCallsTheRpcAndNeverArchivesTheQuestion() {
        val seen = mutableListOf<String>()
        val gateway = gatewayWith(
            record = { seen += "${it.method()} ${it.uri()}" },
            responder = unlinkResponder(),
        )

        gateway.desvincularPreguntaExamen("EX1", "P2")

        assertEquals(
            1,
            seen.count { it.contains("/rest/v1/rpc/admin_unlink_question_from_exam") },
            "Desvincular tiene que ser UNA transacción: ${seen.joinToString("\n")}",
        )

        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-2. `DELETE /rest/v1/preguntas` (o
        // un PATCH activa=false) archivaría la pregunta en TODOS los exámenes
        // que la comparten. Desvincular no puede tocar la tabla global.
        assertFalse(
            seen.any { it.startsWith("DELETE") && it.contains("/rest/v1/preguntas?") },
            "Desvincular jamás borra de la tabla global de preguntas: ${seen.joinToString("\n")}",
        )
        assertFalse(
            seen.any { it.contains("/rest/v1/preguntas?") && it.startsWith("PATCH") },
            "Desvincular jamás pone activa=false: ${seen.joinToString("\n")}",
        )
    }

    /** La recompactación es de la base: Kotlin ya no escribe posiciones fila a fila. */
    @Test
    fun unlinkingDoesNotRewritePositionsFromKotlin() {
        val seen = mutableListOf<String>()
        val gateway = gatewayWith(
            record = { seen += "${it.method()} ${it.uri()}" },
            responder = unlinkResponder(),
        )

        gateway.desvincularPreguntaExamen("EX1", "P2")

        assertTrue(
            seen.none { it.startsWith("PATCH") && it.contains("/rest/v1/preguntas_examen") },
            "las posiciones las recompacta la RPC, no este bucle: ${seen.joinToString("\n")}",
        )
    }

    /** El código de dominio de la RPC llega entero: 404, no un éxito silencioso ni un 500. */
    @Test
    fun unlinkingAQuestionThatWasNeverLinkedFailsInsteadOfPretendingItWorked() {
        val gateway = gatewayWith(responder = { uri ->
            when {
                uri.contains("rpc/admin_unlink_question_from_exam") -> StubResponse(
                    400,
                    """{"code":"P0001","details":null,"hint":null,"message":"QUESTION_NOT_LINKED_TO_EXAM"}""",
                    null,
                )
                else -> StubResponse(200, "[]", null)
            }
        })

        val error = assertFailsWith<BackendDomainException> {
            gateway.desvincularPreguntaExamen("EX1", "FANTASMA")
        }
        assertEquals("QUESTION_NOT_LINKED_TO_EXAM", error.errorCode)
        assertEquals(404, error.status.value)
    }

    /**
     * Sin M4 aplicada la RPC no existe (PGRST202). La respuesta tiene que ser un error
     * de DOMINIO legible —nunca un 500 ni un falso éxito—, y con un 4xx para que el
     * cliente pueda enseñar su texto: `NetworkResultAdapter` descarta el cuerpo de
     * cualquier 5xx y pintaría «reinténtalo más tarde» de algo que no se arregla
     * reintentando.
     */
    @Test
    fun unlinkingWithoutTheProvisionedRpcIsAReadableDomainError() {
        val gateway = gatewayWith(responder = { uri ->
            when {
                uri.contains("rpc/admin_unlink_question_from_exam") -> StubResponse(
                    404,
                    """{"code":"PGRST202","details":null,"hint":null,"message":"Could not find the function"}""",
                    null,
                )
                else -> StubResponse(200, "[]", null)
            }
        })

        val error = assertFailsWith<BackendDomainException> {
            gateway.desvincularPreguntaExamen("EX1", "P2")
        }
        assertEquals("QUESTION_EXAM_UNLINK_UNAVAILABLE", error.errorCode)
        assertEquals(409, error.status.value)
    }

    /** K3-1: reordenar también es UNA sentencia, y su conjunto lo valida la base. */
    @Test
    fun reorderingIsASingleStatementAndNotALoopOfPatches() {
        val seen = mutableListOf<String>()
        val gateway = gatewayWith(
            record = { seen += "${it.method()} ${it.uri()}" },
            responder = unlinkResponder(),
        )

        gateway.reordenarPreguntasExamen("EX1", AdminPreguntaOrdenRequest(listOf("P3", "P1", "P2")))

        assertEquals(
            1,
            seen.count { it.contains("/rest/v1/rpc/admin_reorder_exam_questions") },
            seen.joinToString("\n"),
        )
        assertTrue(
            seen.none { it.startsWith("PATCH") && it.contains("/rest/v1/preguntas_examen") },
            "un intercambio de posiciones chocaba contra el índice único a mitad del bucle: " + seen.joinToString("\n"),
        )
    }

    // -----------------------------------------------------------------
    // total_preguntas: dato de la base, no del editor (K2, tras M1)
    // -----------------------------------------------------------------

    @Test
    fun updatingAnExamNeverRewritesTheQuestionCount() {
        val body = patchBodyFor(
            buildJsonObject {
                put("titulo", "Examen")
                put("totalPreguntas", 7)
            },
        )

        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-3 / C-05. La columna la mantiene la
        // base (M1) y su guarda neutraliza escrituras EN SILENCIO: si el PATCH la
        // mandara, respondería 200 con el valor viejo y nadie vería el fallo.
        assertFalse(body.contains("total_preguntas"), "Un PATCH de examen no puede enviar total_preguntas: $body")
        assertTrue(body.contains("\"titulo\":\"Examen\""), body)
    }

    // -----------------------------------------------------------------
    // Contrato del PATCH (C-01, K-C01): sólo lo que viene en el cuerpo
    // -----------------------------------------------------------------

    @Test
    fun updatingAnExamToNationalSendsExplicitNullCommunity() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
                put("comunidadId", JsonNull)
            },
        )

        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-IB-1: volver un examen a Nacional
        // tiene que llegar a la base como NULL explícito.
        assertTrue(body.contains("\"comunidad_id\":null"), body)
    }

    @Test
    fun clearingTheYearAndTheDurationSendsExplicitNulls() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
                put("anio", JsonNull)
                put("duracionMinutos", JsonNull)
            },
        )

        assertTrue(body.contains("\"anio\":null"), body)
        assertTrue(body.contains("\"duracion_minutos\":null"), body)
    }

    @Test
    fun aPatchWithoutTheNullableKeysLeavesThemUntouched() {
        // El cuerpo del guardado de ráfaga del móvil: no conoce comunidad, año
        // ni duración. Su AUSENCIA es lo que las protege.
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Ráfaga")
                put("tipoExamen", "rafaga")
                put("precioTickets", 0)
            },
        )

        listOf("comunidad_id", "anio", "duracion_minutos").forEach { column ->
            assertFalse(body.contains("\"$column\""), "Una clave ausente no puede escribirse ($column): $body")
        }
    }

    @Test
    fun aNullableKeyWithAValueIsWrittenAsBefore() {
        val body = patchBodyFor(
            buildJsonObject {
                put("especialidadId", especialidadId)
                put("titulo", "Examen")
                put("comunidadId", comunidadId)
                put("anio", 2026)
                put("duracionMinutos", 90)
            },
        )

        assertTrue(body.contains("\"comunidad_id\":\"$comunidadId\""), body)
        assertTrue(body.contains("\"anio\":2026"), body)
        assertTrue(body.contains("\"duracion_minutos\":90"), body)
    }

    /**
     * **La prueba de mutación de C-01.** Antes el PATCH decodificaba el cuerpo al
     * DTO y escribía la fila entera: una clave ausente se escribía con el DEFAULT
     * del DTO —código vacío, `es_oficial = false`, `tipo_examen = 'rafaga'`…—. El
     * guardado de ráfaga del móvil vaciaba así el código y chocaba con 409.
     */
    @Test
    fun anAbsentKeyIsNeverWritten() {
        val body = patchBodyFor(buildJsonObject { put("titulo", "Sólo el título") })

        assertTrue(body.contains("\"titulo\":\"Sólo el título\""), body)
        listOf("es_oficial", "codigo_examen", "tipo_examen", "tipo_acceso", "precio_tickets", "descripcion", "especialidad_id")
            .forEach { column -> assertFalse(body.contains("\"$column\""), "se escribió $column sin venir en el cuerpo: $body") }
    }

    /** Y el otro lado: lo que SÍ viene se escribe, `false` y `0` incluidos. */
    @Test
    fun anExplicitFalseAZeroAndTheDefaultVocabularyStillTravel() {
        val body = patchBodyFor(
            buildJsonObject {
                put("titulo", "Examen")
                put("esOficial", false)
                put("precioTickets", 0)
                put("tipoExamen", "rafaga")
                put("tipoAcceso", "gratuito")
                put("descripcion", "")
            },
        )

        assertTrue(body.contains("\"es_oficial\":false"), body)
        assertTrue(body.contains("\"precio_tickets\":0"), body)
        assertTrue(body.contains("\"tipo_examen\":\"rafaga\""), body)
        assertTrue(body.contains("\"tipo_acceso\":\"gratuito\""), body)
        assertTrue(body.contains("\"descripcion\":\"\""), body)
    }

    /** Visibilidad, recuento, portada y metadatos no se escriben NUNCA, ni con valor ni con `null`. */
    @Test
    fun keysOutsideTheWhitelistAreIgnoredWhateverTheirValue() {
        val body = patchBodyFor(
            buildJsonObject {
                put("titulo", "Examen")
                put("totalPreguntas", JsonNull)
                put("publicadoEn", JsonNull)
                put("activo", false)
                put("portadaStoragePath", "exam-covers/x/y.png")
                put("portadaUrl", "https://evil.test/x.png")
                put("creadoEn", "2020-01-01T00:00:00Z")
                put("claveInventada", "x")
            },
        )

        listOf("total_preguntas", "publicado_en", "activo", "portada", "creado_en", "claveInventada").forEach { leak ->
            assertFalse(body.contains(leak), "el PATCH escribió $leak: $body")
        }
    }

    /** `null` sólo vale en las tres anulables; en cualquier otra clave es un 400 ANTES de escribir. */
    @Test
    fun aNullOnANonClearableKeyIsABadRequestAndNothingIsWritten() {
        val seen = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = { seen += it }, responder = { StubResponse(200, "[${examenRowJson()}]", null) })

        assertFailsWith<BadRequestException> {
            gateway.updateExamen("EX1", buildJsonObject { put("titulo", "Examen"); put("esOficial", JsonNull) })
        }
        assertFailsWith<BadRequestException> {
            gateway.updateExamen("EX1", buildJsonObject { put("anio", "2026") })
        }
        assertTrue(seen.isEmpty(), "se llamó a Supabase con un cuerpo inválido: ${seen.map { it.uri() }}")
    }

    /** M5: un código en blanco tiene su propio 422, que la consola asocia al campo Código. */
    @Test
    fun aBlankCodeIsRejectedWithItsOwnCode() {
        val seen = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = { seen += it }, responder = { StubResponse(200, "[${examenRowJson()}]", null) })

        val error = assertFailsWith<BackendDomainException> {
            gateway.updateExamen("EX1", buildJsonObject { put("codigoExamen", "   ") })
        }

        assertEquals(EXAM_CODE_REQUIRED_CODE, error.errorCode)
        assertEquals(422, error.status.value)
        assertTrue(seen.isEmpty(), "se escribió un código en blanco: ${seen.map { it.uri() }}")
    }

    /** Un cuerpo sin ninguna clave admitida no es una escritura: 0 PATCH, el examen tal cual. */
    @Test
    fun aBodyWithoutAnyKnownKeyReturnsTheExamUnchanged() {
        val seen = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(record = { seen += it }, responder = { StubResponse(200, "[${examenRowJson()}]", null) })

        val examen = gateway.updateExamen("EX1", buildJsonObject { put("claveInventada", 1) })

        assertEquals("EX1", examen?.id)
        assertTrue(seen.none { it.method() == "PATCH" }, "salió un PATCH vacío: ${seen.map { "${it.method()} ${it.uri()}" }}")
        assertEquals(1, seen.count { it.method() == "GET" }, seen.map { "${it.method()} ${it.uri()}" }.toString())
    }

    /**
     * K2: crear un examen NO manda `total_preguntas`, diga lo que diga el cuerpo.
     *
     * Con M1 aplicada (`trg_examenes_total_preguntas_insert`) la base la fija a 0 al
     * insertar y su guarda neutraliza cualquier otro valor. Mandarla —el `1` que
     * exigía el CHECK antiguo, o el `7` del cuerpo— sólo serviría para que alguien
     * creyera que la API la controla.
     */
    @Test
    fun creatingAnExamNeverSendsTheQuestionCount() {
        val bodies = mutableListOf<String>()
        val gateway = gatewayWith(
            record = { request -> bodies += request.bodyText() },
            responder = { StubResponse(201, "[${examenRowJson(totalPreguntas = 0)}]", null) },
        )

        gateway.createExamen(AdminExamenDto(especialidadId = especialidadId, titulo = "Examen nuevo", totalPreguntas = 7))

        val insert = bodies.first { it.contains("titulo") }
        assertFalse(insert.contains("total_preguntas"), insert)
    }

    @Test
    fun anExamWithoutQuestionsIsReadAsZeroAndNotAsOne() {
        val gateway = gatewayWith(responder = {
            StubResponse(200, "[${examenRowJson(totalPreguntas = 0)}]", null)
        })

        val examen = gateway.getExamen("EX1")

        // El `if (totalPreguntas > 0) totalPreguntas else 1` anterior hacía
        // imposible distinguir un examen vacío de uno con una pregunta.
        assertEquals(0, examen?.totalPreguntas)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /** El cuerpo exacto del único PATCH que sale hacia `examenes`. */
    private fun patchBodyFor(raw: JsonObject): String {
        val bodies = mutableListOf<String>()
        val gateway = gatewayWith(
            record = { request -> if (request.method() == "PATCH") bodies += request.bodyText() },
            responder = { StubResponse(200, "[${examenRowJson()}]", null) },
        )
        gateway.updateExamen("EX1", raw)
        return bodies.single()
    }

    private fun requestsForListingQuestions(questionCount: Int): List<String> {
        val seen = mutableListOf<String>()
        val gateway = gatewayWith(
            record = { seen += "${it.method()} ${it.uri()}" },
            responder = examQuestionsResponder(questionCount),
        )
        gateway.listPreguntasExamen("EX1")
        return seen
    }

    /** Un examen con [questionCount] preguntas, cada una con opciones, solución y un recurso. */
    private fun examQuestionsResponder(questionCount: Int): (String) -> StubResponse {
        val ids = (1..questionCount).map { "P$it" }
        return { uri ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> StubResponse(
                    200,
                    ids.mapIndexed { index, id ->
                        """{"examen_id":"EX1","pregunta_id":"$id","posicion":${index + 1},"numero_en_examen":${index + 1}}"""
                    }.joinToString(",", "[", "]"),
                    null,
                )
                uri.contains("/rest/v1/preguntas?") -> StubResponse(
                    200,
                    ids.joinToString(",", "[", "]") { id ->
                        """{"id":"$id","codigo_pregunta":"C-$id",""" +
                            """"tipo_pregunta":"rafaga","titulo":"Titulo $id","fuente_texto":"","dificultad":"media","activa":true}"""
                    },
                    null,
                )
                uri.contains("/rest/v1/opciones_pregunta") -> StubResponse(
                    200,
                    ids.flatMap { id ->
                        listOf(
                            """{"id":"${id}A","pregunta_id":"$id","letra":"A","texto_opcion":"a","posicion":1}""",
                            """{"id":"${id}B","pregunta_id":"$id","letra":"B","texto_opcion":"b","posicion":2}""",
                        )
                    }.joinToString(",", "[", "]"),
                    null,
                )
                uri.contains("/rest/v1/soluciones_pregunta") -> StubResponse(
                    200,
                    ids.joinToString(",", "[", "]") { id ->
                        """{"id":"S$id","pregunta_id":"$id","opcion_correcta_id":"${id}A","razonamiento":"porque si"}"""
                    },
                    null,
                )
                uri.contains("/rest/v1/recursos_pregunta") -> StubResponse(
                    200,
                    ids.joinToString(",", "[", "]") { id ->
                        """{"id":"R$id","pregunta_id":"$id","codigo_recurso":"R-$id","tipo_recurso":"imagen",""" +
                            """"titulo":"","descripcion":"","posicion":1,"activo":true}"""
                    },
                    null,
                )
                else -> StubResponse(200, "[]", null)
            }
        }
    }

    /** EX1 tiene P1(1), P2(2) y P3(3); se desvincula P2 y queda un hueco. */
    /**
     * La RPC de M4 confirma, y lo que queda en el examen se LEE después.
     *
     * La versión anterior simulaba el bucle de DELETE + recompactación que ya no
     * existe: llevaba un `linkDeleted` para que la segunda lectura devolviera el hueco.
     */
    private fun unlinkResponder(): (String) -> StubResponse = { uri ->
        when {
            uri.contains("/rest/v1/rpc/") -> StubResponse(200, "", null)
            uri.contains("/rest/v1/preguntas_examen") -> StubResponse(
                200,
                """[{"examen_id":"EX1","pregunta_id":"P1","posicion":1,"numero_en_examen":1},""" +
                    """{"examen_id":"EX1","pregunta_id":"P3","posicion":2,"numero_en_examen":3}]""",
                null,
            )
            else -> StubResponse(200, "[]", null)
        }
    }

    private fun examenRowJson(totalPreguntas: Int = 12): String =
        """{"id":"EX1","codigo_examen":"EX-1","especialidad_id":"ESP","titulo":"Examen",""" +
            """"tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true,"total_preguntas":$totalPreguntas}"""

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> StubResponse,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = StubHttpClient(record, responder),
    )

    private fun HttpRequest.bodyText(): String {
        val publisher = bodyPublisher().orElse(null) ?: return ""
        val chunks = StringBuilder()
        publisher.subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(item: java.nio.ByteBuffer) {
                chunks.append(Charsets.UTF_8.decode(item))
            }

            override fun onError(throwable: Throwable) = Unit
            override fun onComplete() = Unit
        })
        return chunks.toString()
    }

    internal data class StubResponse(val status: Int, val body: String, val contentRange: String?)

    /**
     * `HttpClient` mínimo que responde según la URL pedida.
     *
     * A mano y no con un doble de librería porque el gateway usa el cliente de
     * la JDK, y lo que hay que observar es exactamente lo que sale por el cable:
     * método, URL y cuerpo.
     */
    private class StubHttpClient(
        private val record: (HttpRequest) -> Unit,
        private val responder: (String) -> StubResponse,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            record(request)
            return StubHttpResponse(request, responder(request.uri().toString())) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private class StubHttpResponse(
        private val request: HttpRequest,
        private val stub: StubResponse,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = stub.status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders {
            val headers = stub.contentRange
                ?.let { mapOf("content-range" to listOf(it)) }
                ?: emptyMap()
            return HttpHeaders.of(headers) { _, _ -> true }
        }

        override fun body(): String = stub.body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
