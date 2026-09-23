package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Con cuántas preguntas nace el intento de un usuario final.
 *
 * Esto no es una cifra de pantalla: es la columna `intentos_examen.total_preguntas`
 * de una persona que se está examinando. Si nace mal, el intento no cuadra nunca
 * — ni se completa, ni su porcentaje significa nada.
 *
 * ### Lo que se arregló (EXAM-002)
 *
 * `iniciar` leía `examenes.total_preguntas`, una columna **sin escritor**:
 * `createExamen` la fija a 0 y nadie la vuelve a tocar (el panel manda `null` a
 * propósito, y en el repositorio no hay ningún trigger). Así que casi todos los
 * intentos nacían declarando cero preguntas.
 *
 * ### Por qué los asserts miran las CONSULTAS y no sólo el número
 *
 * El recuento tiene que significar **lo mismo que la entrega**. Contar
 * `preguntas_examen` a secas daría un número más alto que el de preguntas que la
 * app sirve, porque la entrega filtra `activa=eq.true`; el intento esperaría
 * preguntas que nunca van a llegar. Un test que sólo comprobara «sale 2» pasaría
 * con las dos definiciones en cuanto el fixture no tuviera archivadas.
 */
class AppV1AttemptSizingTest {

    private val usuarioId = "77777777-7777-4777-8777-777777777777"
    private val examenId = "88888888-8888-4888-8888-888888888888"

    /**
     * Tres preguntas vinculadas, una archivada: el intento nace con DOS.
     *
     * La columna del examen dice 50 en el doble, a propósito: es el valor que el
     * fixture declara como default y el que haría que esto pasara desapercibido
     * si alguien volviera a leerla — un 0 se ve, un 50 parece un dato.
     */
    @Test
    fun anAttemptIsSizedByCountingTheQuestionsTheAppWillActuallyServe() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { bodies += it.bodyText() }, responder = ::examWithOneArchivedQuestion)

        gateway.iniciar(usuarioId, examenId)

        val insert = bodies.first { it.contains("total_preguntas") }
        assertTrue(
            insert.contains("\"total_preguntas\":2"),
            "el intento no se dimensionó con las preguntas servibles (2 de 3): $insert",
        )
    }

    /**
     * **La prueba de mutación.** Nadie vuelve a preguntar por la columna podrida.
     *
     * Si alguien restaura el `select=total_preguntas`, el caso de arriba podría
     * seguir verde con un doble que devolviera la cifra «correcta» por
     * casualidad; éste no.
     */
    @Test
    fun theStaleColumnIsNeverReadAgain() {
        val seen = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { seen += it.uri().toString() }, responder = ::examWithOneArchivedQuestion)

        gateway.iniciar(usuarioId, examenId)

        assertFalse(
            seen.any { it.contains("total_preguntas") },
            "se volvió a leer `examenes.total_preguntas`, que no tiene escritor: $seen",
        )
    }

    /** Y el recuento usa el MISMO filtro que la entrega: `activa=eq.true`. */
    @Test
    fun theCountUsesTheSameActiveFilterAsTheDelivery() {
        val seen = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { seen += it.uri().toString() }, responder = ::examWithOneArchivedQuestion)

        gateway.iniciar(usuarioId, examenId)

        assertTrue(
            seen.any { it.contains("/rest/v1/preguntas?") && it.contains("activa=eq.true") },
            "el recuento no filtra las preguntas archivadas, así que puede prometer más preguntas de " +
                "las que la app va a entregar: $seen",
        )
    }

    /** Un examen sin preguntas vinculadas se dimensiona a 0 sin preguntar por preguntas. */
    @Test
    fun anExamWithNoLinkedQuestionsSizesToZeroWithoutAskingForQuestions() {
        val seen = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(
            record = { seen += it.uri().toString(); bodies += it.bodyText() },
            responder = { uri ->
                when {
                    uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, "[]")
                    uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(200, "[${intentoRowJson(0)}]")
                    uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId"}]""")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )

        gateway.iniciar(usuarioId, examenId)

        assertTrue(bodies.any { it.contains("\"total_preguntas\":0") }, "cuerpos: $bodies")
        assertFalse(
            seen.any { it.contains("/rest/v1/preguntas?") },
            "sin vínculos no hay nada que contar: la petición sobra. $seen",
        )
    }

    /**
     * K0-6 (EX-15): iniciar un borrador —o un examen que no existe— es un 404 de
     * dominio y NO crea intento. Antes era `error("Examen no encontrado.")`, que sale
     * como 500 redactado: la app no podía distinguir «ese examen no está disponible»
     * de «el servidor está caído».
     *
     * El doble sólo devuelve la fila si la consulta NO exige publicación, así que el
     * caso también se pone rojo si alguien quita `publicado_en=not.is.null`.
     */
    @Test
    fun startingADraftOrMissingExamIsNotFoundAndCreatesNoAttempt() {
        val seen = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(
            record = { seen += it },
            responder = { uri ->
                when {
                    uri.contains("/rest/v1/examenes") && uri.contains("publicado_en=not.is.null") -> V1StubResponse(200, "[]")
                    uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId"}]""")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )

        val error = assertFailsWith<BackendDomainException> { gateway.iniciar(usuarioId, examenId) }

        assertEquals("EXAM_NOT_FOUND", error.errorCode)
        assertEquals(HttpStatusCode.NotFound, error.status)
        assertEquals("Examen no encontrado.", error.message)
        assertTrue(
            seen.none { it.method() == "POST" && it.uri().rawPath.contains("intentos_examen") },
            "se creó un intento de un examen no disponible: ${seen.map { "${it.method()} ${it.uri()}" }}",
        )
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /** EX con P1, P2 y P3 vinculadas; P3 está archivada y no la sirve la app. */
    private fun examWithOneArchivedQuestion(uri: String): V1StubResponse = when {
        // `preguntas_examen` ANTES que `preguntas`: la segunda es prefijo de la primera.
        uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
            200,
            """[{"pregunta_id":"P1"},{"pregunta_id":"P2"},{"pregunta_id":"P3"}]""",
        )
        uri.contains("/rest/v1/preguntas") -> V1StubResponse(200, """[{"id":"P1"},{"id":"P2"}]""")
        uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(200, "[${intentoRowJson(2)}]")
        // La columna miente a propósito: ver el KDoc del primer caso.
        uri.contains("/rest/v1/examenes") -> V1StubResponse(
            200,
            """[{"id":"$examenId","total_preguntas":50}]""",
        )
        else -> V1StubResponse(200, "[]")
    }

    private fun intentoRowJson(totalPreguntas: Int): String =
        """{"id":"99999999-9999-4999-8999-999999999999","examen_id":"$examenId","estado":"en_progreso",""" +
            """"total_preguntas":$totalPreguntas,"total_respondidas":0,"total_correctas":0,"porcentaje_acierto":0.0}"""

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): AppV1Gateway = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
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
}
