package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KQ3-1 / K3-4 / D-03: la ÚNICA puerta para vincular, quitar y ver los exámenes de
 * una pregunta —`POST`, `DELETE` y `GET /preguntas/{id}/examenes`—, sobre las RPC de
 * M4.
 *
 * Mientras M4 no esté aplicada las RPC no existen (PGRST202): lo que se fija es que
 * la respuesta es un error de DOMINIO legible, con un 4xx, y nunca un 500 ni un
 * falso éxito.
 */
class AdminV1QuestionRelationsTest {

    private val preguntaId = "77777777-7777-4777-8777-777777777777"
    private val examenId = "99999999-9999-4999-8999-999999999999"

    @Test fun vincularEsUnaLlamadaALaRpcYRespondeLaListaReleida() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { relationsModule(gatewayWith(upstream)) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/examenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"examenId":"$examenId"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val rpc = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_link_question_to_exam") }
        assertEquals(
            """{"p_examen_id":"$examenId","p_pregunta_id":"$preguntaId","p_posicion":null}""",
            readRequestBody(rpc),
        )
        val body = response.bodyAsText()
        assertTrue(body.contains("\"examenId\":\"$examenId\""), body)
        assertTrue(body.contains("\"estado\":\"publicado\""), body)
        assertTrue(body.contains("\"posicion\":3"), body)
    }

    @Test fun unaPosicionExplicitaViajaYUnaInvalidaEsUn400() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { relationsModule(gatewayWith(upstream)) }

        val invalida = client.post("/api/v1/admin/preguntas/$preguntaId/examenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"examenId":"$examenId","posicion":0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalida.status)
        assertTrue(upstream.isEmpty(), upstream.describe())

        val valida = client.post("/api/v1/admin/preguntas/$preguntaId/examenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"examenId":"$examenId","posicion":2}""")
        }
        assertEquals(HttpStatusCode.OK, valida.status)
        assertTrue(readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_link_question_to_exam") }).contains("\"p_posicion\":2"))
    }

    @Test fun unExamenIdConFragmentoEsUn400SinPeticiones() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { relationsModule(gatewayWith(upstream)) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/examenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"examenId":"$examenId#"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), upstream.describe())
    }

    @Test fun elCodigoDeDominioDeLaRpcLlegaEntero() = testApplication {
        application {
            relationsModule(
                gatewayWith(
                    CopyOnWriteArrayList(),
                    linkRpc = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"QUESTION_EXAM_TYPE_MISMATCH"}"""),
                ),
            )
        }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/examenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"examenId":"$examenId"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("question_exam_type_mismatch"), response.bodyAsText())
    }

    /** RPC ausente (PGRST202): error de dominio, 4xx, y ningún intento de escribir por otra vía. */
    @Test fun sinM4VincularEsUnErrorDeDominioLegibleYNoUn500() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application {
            relationsModule(
                gatewayWith(
                    upstream,
                    linkRpc = V1StubResponse(404, """{"code":"PGRST202","details":null,"hint":null,"message":"Could not find the function"}"""),
                ),
            )
        }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/examenes") {
            contentType(ContentType.Application.Json)
            setBody("""{"examenId":"$examenId"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue(body.contains("question_exam_link_unavailable"), body)
        assertTrue(!body.contains("PGRST202") && !body.contains("Could not find"), "el cuerpo de PostgREST no viaja: $body")
        assertTrue(
            upstream.none { it.method() in setOf("POST", "PATCH") && it.uri().rawPath.contains("/rest/v1/preguntas_examen") },
            "sin RPC no hay escritura alternativa: " + upstream.describe(),
        )
    }

    @Test fun quitarEsUnaLlamadaALaRpcYRespondeLaListaReleida() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { relationsModule(gatewayWith(upstream, vinculos = "[]")) }

        val response = client.delete("/api/v1/admin/preguntas/$preguntaId/examenes/$examenId")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val rpc = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_unlink_question_from_exam") }
        assertEquals("""{"p_examen_id":"$examenId","p_pregunta_id":"$preguntaId"}""", readRequestBody(rpc))
        assertEquals("""{"data":[]}""", response.bodyAsText())
    }

    @Test fun quitarUnVinculoQueNoExisteEsUn404DeDominio() = testApplication {
        application {
            relationsModule(
                gatewayWith(
                    CopyOnWriteArrayList(),
                    unlinkRpc = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"QUESTION_NOT_LINKED_TO_EXAM"}"""),
                ),
            )
        }

        val response = client.delete("/api/v1/admin/preguntas/$preguntaId/examenes/$examenId")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("question_not_linked_to_exam"))
    }

    /** La lectura inversa es UNA petición embebida y deriva el estado del examen. */
    @Test fun losExamenesDeUnaPreguntaSalenDeUnaSolaConsultaEmbebida() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { relationsModule(gatewayWith(upstream)) }

        val response = client.get("/api/v1/admin/preguntas/$preguntaId/examenes")

        assertEquals(HttpStatusCode.OK, response.status)
        val lectura = upstream.single()
        assertTrue(lectura.uri().rawPath.endsWith("/rest/v1/preguntas_examen"), lectura.uri().toString())
        val query = lectura.uri().rawQuery.orEmpty()
        assertTrue(query.contains("examenes(") && query.contains("pregunta_id=eq.$preguntaId"), query)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"codigoExamen\":\"EX-1\"") && body.contains("\"tipoExamen\":\"rafaga\""), body)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun vinculoJson(): String =
        """[{"posicion":3,"examenes":{"id":"$examenId","codigo_examen":"EX-1","titulo":"Examen","tipo_examen":"rafaga",""" +
            """"activo":true,"publicado_en":"2026-09-01T00:00:00Z","total_preguntas":12}}]"""

    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        linkRpc: V1StubResponse = V1StubResponse(200, """[{"posicion_asignada":3,"numero_asignado":3,"ya_vinculada":false}]"""),
        unlinkRpc: V1StubResponse = V1StubResponse(200, "2"),
        vinculos: String = vinculoJson(),
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_link_question_to_exam") -> linkRpc
                uri.contains("/rest/v1/rpc/admin_unlink_question_from_exam") -> unlinkRpc
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, vinculos)
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun Application.relationsModule(gateway: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { adminV1Routes(gateway) }
    }

    private fun List<HttpRequest>.describe(): String = describeRequests(this)
}
