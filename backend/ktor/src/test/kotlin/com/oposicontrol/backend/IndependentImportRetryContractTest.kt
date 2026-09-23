package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HASHIRA C — verificación independiente de la FASE 5 (Importaciones), reescrita
 * como contrato de UNA llamada (KI3-1).
 *
 * La versión anterior atacaba el bucle por fila: que una fila marcada por el parser
 * se publicara sin revisión, que un fallo del intento borrara el diagnóstico, que el
 * id creado se anotara aunque fallara la relectura. Ese bucle ya no existe: publicar
 * es `admin_import_lote_preguntas`, y las tres propiedades pasan a ser de la base
 * (`IMPORT_PENDING_REVIEW`, staging intacto, todo-o-nada). Lo que queda de contrato
 * en Kotlin —y se fija aquí— es que Kotlin **no vuelva a hacer nada de eso por su
 * cuenta**: ni una escritura a las tablas de importación, ni una segunda auditoría
 * en un reintento.
 */
class IndependentImportRetryContractTest {

    private val loteId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val examenId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    // -----------------------------------------------------------------
    // 1 · I-2 puro, sin red: qué devuelve el parser para un bloque malo
    // -----------------------------------------------------------------

    /**
     * Fija el contrato de I-2: `bloque_original` guarda el bloque CRUDO y no una
     * copia del título, y el bloque parcial llega con lo que sí se pudo leer.
     */
    @Test fun aBlockMissingOneOptionReachesStagingWithItsRawTextAndItsPartialContent() {
        val parsed = parseRafagaText(
            """
            --- PREGUNTA ---
            TITULO: Cual es el plazo de alegaciones
            A: Diez dias
            B: Quince dias
            C: Un mes
            RESPUESTA: B
            RAZONAMIENTO: Articulo 82
            """.trimIndent(),
        )

        assertTrue(parsed.preguntas.isEmpty(), "el bloque no es importable tal cual: ${parsed.preguntas}")
        val bloque = assertNotNull(parsed.bloquesConError.singleOrNull(), "${parsed.bloquesConError}")

        assertTrue(
            bloque.bloqueOriginal.contains("TITULO: Cual es el plazo de alegaciones"),
            "`bloque_original` tiene que ser el bloque CRUDO del PDF, no una copia del titulo: " +
                bloque.bloqueOriginal,
        )
        assertTrue(bloque.detalle.contains("D"), "el diagnostico no nombra lo que falta: ${bloque.detalle}")
        assertEquals("B", bloque.respuesta, "la respuesta parcial viaja tal cual")
        assertTrue(bloque.opcionA.isNotBlank() && bloque.opcionB.isNotBlank(), "opciones parciales")
        assertTrue(bloque.opcionD.isBlank(), "la opcion que falta sigue faltando")
    }

    // -----------------------------------------------------------------
    // 2 · Idempotencia: dos publicaciones seguidas, una sola ejecución
    // -----------------------------------------------------------------

    /**
     * El segundo clic (o el reintento tras un timeout) recibe `ejecutado = false` de
     * la RPC. Kotlin no debe compensar nada ni auditar dos veces: la pista de
     * auditoría con dos «lote importado» para una publicación es una pista falsa.
     */
    @Test fun aSecondPublishIsNotASecondExecutionAndIsNotAuditedTwice() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        var llamadas = 0
        val gateway = gatewayWith(upstream) {
            llamadas++
            V1StubResponse(200, resultado(ejecutado = llamadas == 1))
        }

        val primera = gateway.importarLote(loteId)
        val segunda = gateway.importarLote(loteId)

        assertTrue(primera.resultado.ejecutado)
        assertFalse(segunda.resultado.ejecutado)
        assertEquals(1, upstream.count { it.uri().rawPath.endsWith("/auditoria_log") }, upstream.describe())
        assertTrue(
            upstream.none {
                it.method() in setOf("POST", "PATCH", "DELETE") &&
                    (it.uri().rawPath.endsWith("/preguntas_parseadas_staging") || it.uri().rawPath.endsWith("/lotes_importacion_preguntas"))
            },
            "Kotlin escribió en las tablas de importación: " + upstream.describe(),
        )
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun resultado(ejecutado: Boolean): String =
        """{"lote_id":"$loteId","estado":"importado","total_detectadas":1,"total_importadas":1,""" +
            """"ejecutado":$ejecutado,"nuevas":1,"vinculadas":0,"omitidas":0}"""

    private fun loteRowJson(): String =
        """{"id":"$loteId","examen_id":"$examenId","nombre_archivo_original":"t1.pdf",""" +
            """"tipo_examen":"rafaga","estado":"importado","total_detectadas":1,"total_importadas":1}"""

    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        rpc: () -> V1StubResponse,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_preguntas") -> rpc()
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRowJson()}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(200, "[]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun List<HttpRequest>.describe(): String = describeRequests(this)
}
