package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decisión del propietario 2026-10-20: anular una pregunta oficial exige el motivo del tribunal. Además de la ruta directa
 * (OfficialQuestionFlags, en AdminControlOfficialQuestionsTest), la importación: el lote se puede CREAR con filas anuladas
 * sin motivo (el parser detecta ANULADA aunque el PDF no diga por qué), pero el revisor no puede APROBAR una fila así y
 * la PUBLICACIÓN del lote se para y dice qué posiciones lo necesitan, sin llamar a la RPC.
 */
class AdminAnnulmentReasonRequiredTest {
    private val loteId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val filaId = "44444444-4444-4444-8444-444444444444"

    private fun gateway(upstream: MutableList<HttpRequest>, staging: String = "[]"): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_preguntas") ->
                    V1StubResponse(200, """{"lote_id":"$loteId","estado":"importado","ejecutado":true,"nuevas":1,"vinculadas":0,"omitidas":0,"total_importadas":1}""")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(200, staging)
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    @Test
    fun theReviewerCannotApproveAnAnnulledRowWithoutAReason() {
        for (motivo in listOf(null, "", "   ")) {
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            val e = assertFailsWith<BackendDomainException> {
                gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "aprobada", anulada = true, motivoAnulacion = motivo))
            }
            assertEquals("ANNULMENT_REASON_REQUIRED", e.errorCode)
            assertEquals(HttpStatusCode.UnprocessableEntity, e.status)
            assertTrue(upstream.none { it.method() == "PATCH" }, "nada escrito: ${describeRequests(upstream)}")
        }
    }

    /** Lo que no es aprobar (corregir la fila, omitirla) sigue permitido: el motivo se puede añadir después. */
    @Test
    fun editingOrSkippingAnAnnulledRowWithoutAReasonIsStillAllowed() {
        for (estado in listOf("ok", "advertencia", "omitida")) {
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            runCatching { gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = estado, anulada = true)) }
                .exceptionOrNull()?.let { assertTrue(it !is BackendDomainException || it.errorCode != "ANNULMENT_REASON_REQUIRED", "$estado: $it") }
            assertTrue(upstream.any { it.method() == "PATCH" }, "$estado escribe: ${describeRequests(upstream)}")
        }
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        runCatching { gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "aprobada", anulada = true, motivoAnulacion = "Error en el enunciado")) }
        assertTrue(upstream.any { it.method() == "PATCH" }, "con motivo se aprueba: ${describeRequests(upstream)}")
    }

    @Test
    fun publishingABatchWithAnnulledRowsWithoutAReasonStopsBeforeTheRpcAndNamesThePositions() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val e = assertFailsWith<BackendDomainException> {
            gateway(upstream, staging = """[{"posicion":3},{"posicion":7}]""").importarLote(loteId)
        }
        assertEquals("ANNULMENT_REASON_REQUIRED", e.errorCode)
        assertTrue(e.message.contains("3, 7"), e.message)
        assertTrue(upstream.none { it.uri().rawPath.endsWith("/rpc/admin_import_lote_preguntas") }, "la RPC no se llama: ${describeRequests(upstream)}")
        val gate = upstream.single { it.uri().rawPath.endsWith("/preguntas_parseadas_staging") }.uri().rawQuery
        assertTrue(gate.contains("anulada=is.true") && gate.contains("estado_parseo=neq.omitida") && gate.contains("lote_id=eq.$loteId"), gate)
    }

    @Test
    fun aBatchWhoseAnnulledRowsAllHaveAReasonIsPublished() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        runCatching { gateway(upstream).importarLote(loteId) }
        assertEquals(1, upstream.count { it.uri().rawPath.endsWith("/rpc/admin_import_lote_preguntas") }, describeRequests(upstream))
    }
}
