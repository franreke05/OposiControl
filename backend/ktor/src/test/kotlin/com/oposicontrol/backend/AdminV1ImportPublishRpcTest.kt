package com.oposicontrol.backend

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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KI3-1: publicar un lote es UNA llamada a `admin_import_lote_preguntas`.
 *
 * Lo que se fija es **qué sale y qué no sale hacia Supabase**. La versión anterior
 * era un bucle por fila que Kotlin gobernaba —creaba preguntas una a una, marcaba
 * filas, calculaba un estado de lote que el CHECK ni admitía— y todo lo que este
 * fichero afirma es que ese bucle ya no existe: cero escrituras desde Kotlin a
 * `preguntas_parseadas_staging` y a `lotes_importacion_preguntas`.
 */
class AdminV1ImportPublishRpcTest {

    private val loteId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val examenId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val actorId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    @Test fun publicarEsUnaSolaLlamadaConElCuerpoExacto() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream)

        val respuesta = gateway.importarLote(loteId, confirmarExamenPublicado = false, actorId = actorId)

        val rpc = upstream.filter { it.uri().rawPath.endsWith("/rpc/admin_import_lote_preguntas") }
        assertEquals(1, rpc.size, upstream.describe())
        assertEquals(
            """{"p_lote_id":"$loteId","p_confirmar_examen_publicado":false,"p_actor_id":"$actorId"}""",
            readRequestBody(rpc.single()),
        )
        assertTrue(respuesta.resultado.ejecutado)
        assertEquals(3, respuesta.resultado.nuevas)
        assertEquals(1, respuesta.resultado.vinculadas)
        assertEquals("importado", respuesta.data.estado)
        assertNoKotlinWritesToImportTables(upstream)
    }

    /** Un reintento (dos clics, un timeout) no escribe nada y NO se audita otra vez. */
    @Test fun unReintentoNoEjecutadoNoAudita() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream, rpc = V1StubResponse(200, resultado(ejecutado = false)))

        val respuesta = gateway.importarLote(loteId)

        assertFalse(respuesta.resultado.ejecutado)
        assertTrue(
            upstream.none { it.uri().rawPath.endsWith("/auditoria_log") },
            "un reintento que no escribió nada no es otra publicación: " + upstream.describe(),
        )
        assertNoKotlinWritesToImportTables(upstream)
    }

    /** El código de dominio de la RPC llega entero, con su 409. */
    @Test fun lasFilasSinRevisarSonUn409Legible() {
        val gateway = gatewayWith(
            CopyOnWriteArrayList(),
            rpc = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"IMPORT_PENDING_REVIEW"}"""),
        )

        val error = assertFailsWith<BackendDomainException> { gateway.importarLote(loteId) }

        assertEquals("IMPORT_PENDING_REVIEW", error.errorCode)
        assertEquals(409, error.status.value)
    }

    /**
     * Sin IM3 la RPC no existe (PGRST202): error de dominio legible, sin escribir
     * nada y con un 4xx para que el cliente pueda enseñar el texto (el adaptador de
     * red descarta el cuerpo de cualquier 5xx).
     */
    @Test fun sinLaRpcAprovisionadaEsUnErrorDeDominioSinEscribirNada() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(
            upstream,
            rpc = V1StubResponse(404, """{"code":"PGRST202","details":null,"hint":null,"message":"Could not find the function"}"""),
        )

        val error = assertFailsWith<BackendDomainException> { gateway.importarLote(loteId) }

        assertEquals("IMPORT_WRITE_RPC_NOT_PROVISIONED", error.errorCode)
        assertEquals(409, error.status.value)
        assertTrue(upstream.none { it.method() in setOf("POST", "PATCH") && !it.uri().rawPath.contains("/rpc/") }, upstream.describe())
    }

    /** Por la ruta: sin cuerpo se asume `confirmarExamenPublicado = false`, y la respuesta lleva `data` + `resultado`. */
    @Test fun laRutaAceptaCuerpoVacioYRespondeDataYResultado() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { publishModule(gatewayWith(upstream)) }

        val sinCuerpo = client.post("/api/v1/admin/importaciones/$loteId/importar")
        assertEquals(HttpStatusCode.OK, sinCuerpo.status, sinCuerpo.bodyAsText())
        val body = sinCuerpo.bodyAsText()
        assertTrue(body.contains("\"resultado\""), body)
        assertTrue(body.contains("\"data\""), body)
        assertTrue(readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_import_lote_preguntas") }).contains("\"p_confirmar_examen_publicado\":false"))

        upstream.clear()
        val confirmado = client.post("/api/v1/admin/importaciones/$loteId/importar") {
            contentType(ContentType.Application.Json)
            setBody("""{"confirmarExamenPublicado":true}""")
        }
        assertEquals(HttpStatusCode.OK, confirmado.status)
        assertTrue(readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_import_lote_preguntas") }).contains("\"p_confirmar_examen_publicado\":true"))
    }

    /** DEC-IMP-05: la ruta que publicaba sin revisión ya no existe. */
    @Test fun laRutaDeImportacionDirectaDeRafagasEstaRetirada() = testApplication {
        application { publishModule(gatewayWith(CopyOnWriteArrayList())) }

        val response = client.post("/api/v1/admin/rafagas/import?especialidadId=$examenId")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun assertNoKotlinWritesToImportTables(upstream: List<HttpRequest>) {
        val escrituras = upstream.filter {
            it.method() in setOf("POST", "PATCH", "DELETE") &&
                (it.uri().rawPath.endsWith("/preguntas_parseadas_staging") || it.uri().rawPath.endsWith("/lotes_importacion_preguntas"))
        }
        assertTrue(escrituras.isEmpty(), "Kotlin volvió a escribir en las tablas de importación: " + escrituras.describe())
    }

    private fun resultado(ejecutado: Boolean): String =
        """{"lote_id":"$loteId","estado":"importado","total_detectadas":4,"total_importadas":4,""" +
            """"ejecutado":$ejecutado,"nuevas":3,"vinculadas":1,"omitidas":0}"""

    private fun loteRowJson(estado: String = "importado"): String =
        """{"id":"$loteId","examen_id":"$examenId","nombre_archivo_original":"t1.pdf",""" +
            """"tipo_examen":"rafaga","estado":"$estado","total_detectadas":4,"total_importadas":4}"""

    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        rpc: V1StubResponse = V1StubResponse(200, resultado(ejecutado = true)),
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_preguntas") -> rpc
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRowJson()}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(200, "[]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun Application.publishModule(gateway: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { adminV1Routes(gateway) }
    }

    private fun List<HttpRequest>.describe(): String =
        "(${size}) " + joinToString(", ") { "${it.method()} ${it.uri().rawPath}?${it.uri().rawQuery.orEmpty()}" }
}
