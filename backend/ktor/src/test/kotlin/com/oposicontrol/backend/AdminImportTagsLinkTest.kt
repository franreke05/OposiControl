package com.oposicontrol.backend

import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Petición del propietario 2026-10-22: al publicar un lote, el `ETIQUETA:` del PDF se casa con el catálogo de Control y se
 * enlaza a cada pregunta (también a la existente en las filas vinculadas). Las que no existen no se crean: se avisan.
 */
class AdminImportTagsLinkTest {
    private val loteId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val p1 = "11111111-1111-4111-8111-111111111111"
    private val p2 = "22222222-2222-4222-8222-222222222222"
    private val tConst = "c0000000-0000-4000-8000-00000000000c"
    private val tLey = "c0000000-0000-4000-8000-00000000000d"

    private fun gateway(upstream: MutableList<HttpRequest>, filas: String, catalogo: String): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_preguntas") ->
                    V1StubResponse(200, """{"lote_id":"$loteId","estado":"importado","ejecutado":true,"nuevas":1,"vinculadas":1,"omitidas":0,"total_importadas":2}""")
                uri.contains("/rest/v1/preguntas_parseadas_staging") && uri.contains("etiqueta_texto=neq.") -> V1StubResponse(200, filas)
                uri.contains("/rest/v1/etiquetas?") -> V1StubResponse(200, catalogo)
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private val catalogo = """[{"id":"$tConst","nombre":"Constitución"},{"id":"$tLey","nombre":"Ley 39/2015"}]"""

    @Test
    fun publishingLinksKnownTagsIgnoringCaseAccentsAndSpacesAndReportsUnknownOnes() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val filas = """[{"pregunta_id":"$p1","etiqueta_texto":"  constitucion , Inventado"},{"pregunta_id":null,"vincular_pregunta_id":"$p2","etiqueta_texto":"LEY 39/2015,Constitución"}]"""
        runCatching { gateway(upstream, filas, catalogo).importarLote(loteId) }
        val post = upstream.single { it.method() == "POST" && it.uri().rawPath.endsWith("/etiquetas_pregunta") }
        val body = post.bodyText()
        for (par in listOf(p1 to tConst, p2 to tLey, p2 to tConst)) {
            assertTrue(body.contains(par.first) && body.contains(par.second), body)
        }
        assertEquals(3, Regex("\"etiqueta_id\"").findAll(body).count(), "tres enlaces, sin duplicados: $body")
        assertTrue(post.headers().firstValue("Prefer").orElse("").contains("ignore-duplicates"))
        assertTrue(upstream.any { it.method() == "POST" && it.uri().rawPath.endsWith("/auditoria_log") && it.bodyText().contains("lote_etiquetas_enlazadas") },
            describeRequests(upstream))
    }

    @Test
    fun withoutTagsNothingIsWritten() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        runCatching { gateway(upstream, "[]", catalogo).importarLote(loteId) }
        assertTrue(upstream.none { it.uri().rawPath.endsWith("/etiquetas_pregunta") }, describeRequests(upstream))
    }

    @Test
    fun unknownTagsAreNotCreated() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        runCatching { gateway(upstream, """[{"pregunta_id":"$p1","etiqueta_texto":"Errata"}]""", catalogo).importarLote(loteId) }
        assertTrue(upstream.none { it.method() == "POST" && (it.uri().rawPath.endsWith("/etiquetas") || it.uri().rawPath.endsWith("/etiquetas_pregunta")) },
            describeRequests(upstream))
    }

    private fun bloque(etiqueta: String) = """
        --- PREGUNTA ---
        TITULO: ¿Pregunta?
        A: uno
        B: dos
        C: tres
        D: cuatro
        RESPUESTA: B
        ETIQUETA: $etiqueta
    """.trimIndent()

    @Test
    fun parserRuleSplitsDedupesAndWarnsAboutSemicolonsAndTooMany() {
        val p = parseQuestionBlocks(bloque("Constitución; constitucion ,  Ley   39/2015,"), "rafaga").preguntas.single()
        assertEquals(listOf("Constitución", "Ley 39/2015"), p.etiquetas)
        assertTrue(p.advertencia.orEmpty().contains(ETIQUETA_SEPARATOR_WARNING), p.advertencia)

        val muchas = parseQuestionBlocks(bloque((1..21).joinToString(",") { "Tema $it" }), "rafaga").preguntas.single()
        assertTrue(muchas.advertencia.orEmpty().contains("solo se enlazan los 20"), muchas.advertencia)

        val limpia = parseQuestionBlocks(bloque("Constitución, Ley 39/2015"), "rafaga").preguntas.single()
        assertEquals(null, limpia.advertencia)
    }

    @Test
    fun reviewRuleWarnsAboutTagsMissingFromTheCatalog() {
        val catalogo = mapOf(normalizarNombreEtiqueta("Constitución") to tConst)
        val p = parseQuestionBlocks(bloque("constitucion, Inventado"), "rafaga").preguntas.single()
        val aviso = advertenciaConEtiquetas(p, catalogo).orEmpty()
        assertTrue(aviso.contains("Inventado") && !aviso.contains("constitucion"), aviso)
        assertEquals(null, advertenciaConEtiquetas(parseQuestionBlocks(bloque("Constitución"), "rafaga").preguntas.single(), catalogo))
        assertTrue(advertenciaConEtiquetas(p, null).orEmpty().contains("no se ha podido comprobar"))
    }

    @Test
    fun tagNamesAreNormalized() {
        assertEquals("constitucion espanola", normalizarNombreEtiqueta("  Constitución   Española "))
        assertEquals(normalizarNombreEtiqueta("LEY 39/2015"), normalizarNombreEtiqueta("ley 39/2015"))
    }
}
