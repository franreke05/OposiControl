package com.oposicontrol.backend

import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E 2026-10-20: tres escrituras de admin no dejaban fila en `auditoria_log`: editar un examen, crear un lote de
 * importación (JSON o PDF, que acaba en `crearLote`) y la revisión humana de una fila de staging. Ahora se auditan con el
 * nombre de la acción y SOLO metadatos (campos tocados, estado, contadores): nunca el texto de la pregunta.
 */
class AdminImportAndExamEditAuditTest {
    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val loteId = "11111111-1111-4111-8111-111111111111"
    private val filaId = "44444444-4444-4444-8444-444444444444"

    private val stagingRow =
        """{"id":"$filaId","lote_id":"$loteId","posicion":1,"bloque_original":"crudo","titulo":"Texto privado de la pregunta",""" +
            """"opcion_a":"a","opcion_b":"b","opcion_c":"c","opcion_d":"d","respuesta_correcta":"B","razonamiento":"","fuente_texto":"",""" +
            """"etiqueta_texto":"","estado_parseo":"aprobada","anulada":false,"motivo_anulacion":null}"""
    private val examenRow =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"33333333-3333-4333-8333-333333333333","titulo":"Examen",""" +
            """"tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true}"""

    private fun gateway(upstream: MutableList<HttpRequest>): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(200, "[$stagingRow]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[$examenRow]")
                uri.contains("/rest/v1/lotes_importacion_preguntas") ->
                    V1StubResponse(200, """[{"id":"$loteId","examen_id":"$examenId","nombre_archivo_original":"t.pdf","tipo_examen":"rafaga","estado":"parseado","total_detectadas":1,"total_importadas":0}]""")
                else -> V1StubResponse(201, "")
            }
        },
    )

    private fun auditBodies(upstream: List<HttpRequest>): List<String> =
        upstream.filter { it.method() == "POST" && it.uri().rawPath.endsWith("/auditoria_log") }.map { readRequestBody(it) }

    @Test
    fun reviewingAStagingRowIsAuditedWithoutTheQuestionText() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "Texto privado de la pregunta", estadoParseo = "aprobada"))
        val audit = auditBodies(upstream).single()
        assertTrue(audit.contains("\"accion\":\"staging_revisado\"") && audit.contains(filaId), audit)
        assertTrue(audit.contains("\"estado\":\"aprobada\"") && audit.contains("\"anulada\":false") && audit.contains("\"vinculada\":false"), audit)
        assertFalse(audit.contains("Texto privado"), "el contenido de la pregunta no va a la auditoría: $audit")
    }

    @Test
    fun editingAnExamIsAuditedWithTheChangedFieldNamesOnly() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gateway(upstream).updateExamen(examenId, buildJsonObject { put("titulo", JsonPrimitive("Nuevo título secreto")) })
        val audit = auditBodies(upstream).single()
        assertTrue(audit.contains("\"accion\":\"examen_actualizado\"") && audit.contains(examenId), audit)
        assertTrue(audit.contains("\"campos\":[\"titulo\"]"), audit)
        assertFalse(audit.contains("Nuevo título secreto"), audit)
    }

    @Test
    fun anEmptyExamPatchWritesNothingAndAuditsNothing() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gateway(upstream).updateExamen(examenId, buildJsonObject { })
        assertEquals(emptyList(), auditBodies(upstream))
    }
}
