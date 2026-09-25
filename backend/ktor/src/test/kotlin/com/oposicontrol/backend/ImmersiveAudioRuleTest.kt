package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Una pregunta inmersiva sin ningún audio activo se guarda, pero no se puede usar:
 * ni vincular a un examen ni publicar un examen que la lleve. Ráfaga y razonada no
 * piden nada.
 */
class ImmersiveAudioRuleTest {

    private val preguntaId = "f1f1f1f1-f1f1-4f1f-8f1f-f1f1f1f1f1f1"
    private val examenId = "e1e1e1e1-e1e1-4e1e-8e1e-e1e1e1e1e1e1"

    @Test fun anImmersiveWithoutAudioCannotBeLinkedToAnExam() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipo = "inmersiva", audio = false)

        val error = assertFailsWith<BackendDomainException> {
            gateway.vincularPreguntaExamen(examenId, preguntaId, posicion = null)
        }

        assertEquals("QUESTION_IMMERSIVE_REQUIRES_AUDIO", error.errorCode)
        assertEquals(422, error.status.value)
        assertTrue(upstream.none { it.uri().rawPath.contains("admin_link_question_to_exam") }, "vinculó igualmente")
    }

    @Test fun anImmersiveWithAudioIsLinked() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipo = "inmersiva", audio = true)

        gateway.vincularPreguntaExamen(examenId, preguntaId, posicion = null)

        assertTrue(upstream.any { it.uri().rawPath.contains("admin_link_question_to_exam") }, "no llegó la RPC")
    }

    @Test fun aRafagaNeedsNoAudioToBeLinked() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipo = "rafaga", audio = false)

        gateway.vincularPreguntaExamen(examenId, preguntaId, posicion = null)

        assertTrue(upstream.any { it.uri().rawPath.contains("admin_link_question_to_exam") }, "no llegó la RPC")
    }

    @Test fun anExamWithAnImmersiveWithoutAudioCannotBePublished() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipo = "inmersiva", audio = false)

        val error = assertFailsWith<BackendDomainException> { gateway.publicarExamen(examenId) }

        assertEquals("EXAM_PUBLISH_IMMERSIVE_WITHOUT_AUDIO", error.errorCode)
        assertTrue(
            upstream.none { it.method() == "PATCH" && it.uri().rawPath.contains("/rest/v1/examenes") },
            "publicó igualmente",
        )
    }

    @Test fun anExamWhoseImmersivesHaveAudioIsPublished() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, tipo = "inmersiva", audio = true)

        runCatching { gateway.publicarExamen(examenId) }

        assertTrue(
            upstream.any { it.method() == "PATCH" && it.uri().rawPath.contains("/rest/v1/examenes") },
            "no llegó el PATCH de publicación: " + describeRequests(upstream),
        )
    }

    /** `preguntas_examen` va ANTES que `preguntas`: la segunda es prefijo de la primera. */
    private fun gatewayWith(upstream: MutableList<HttpRequest>, tipo: String, audio: Boolean): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream += it }) { uri ->
                when {
                    uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                        200,
                        if (tipo == "inmersiva") """[{"pregunta_id":"$preguntaId"}]""" else "[]",
                    )
                    uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(
                        200,
                        if (audio) """[{"id":"r-1","pregunta_id":"$preguntaId"}]""" else "[]",
                    )
                    uri.contains("/rest/v1/preguntas") -> V1StubResponse(
                        200,
                        """[{"id":"$preguntaId","tipo_pregunta":"$tipo","activa":true}]""",
                    )
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
}
