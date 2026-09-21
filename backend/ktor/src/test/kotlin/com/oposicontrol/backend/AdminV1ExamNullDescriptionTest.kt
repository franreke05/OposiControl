package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `examenes.descripcion` es NULL-able en la base. Un examen creado fuera de la API (SQL, otra
 * herramienta) llegaba con `"descripcion":null` y, como `AdminExamenRow.descripcion` era `String`
 * no nulo, el decode reventaba: el LISTADO y la IMPORTACIÓN entera (que lee el examen) salían como
 * 502 «upstream contract failure». Ahora la columna es `String?` y el DTO la sirve como `""`.
 */
class AdminV1ExamNullDescriptionTest {

    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val loteId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun listAndGetServeANullDescriptionAsEmptyString() {
        val gateway = gateway(CopyOnWriteArrayList())

        val listado = gateway.listExamenes()
        assertEquals(1, listado.size)
        assertEquals("", listado.single().descripcion)

        val uno = assertNotNull(gateway.getExamen(examenId))
        assertEquals("", uno.descripcion)
        assertEquals("Examen", uno.titulo)
    }

    @Test
    fun theListingAndDetailRoutesAnswer200ForAnExamWithNullDescription() = testApplication {
        val gateway = gateway(CopyOnWriteArrayList())
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { adminV1Routes(gateway) }
        }

        val listado = client.get("/api/v1/admin/examenes")
        assertEquals(HttpStatusCode.OK, listado.status, listado.bodyAsText())
        assertTrue(listado.bodyAsText().contains("\"titulo\":\"Examen\""), listado.bodyAsText())

        val detalle = client.get("/api/v1/admin/examenes/$examenId")
        assertEquals(HttpStatusCode.OK, detalle.status, detalle.bodyAsText())
    }

    @Test
    fun aPdfImportForAnExamWithNullDescriptionDoesNotFail() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val pdf = assertNotNull(javaClass.getResourceAsStream("/pdf/with_preamble.pdf"), "falta el fixture").use { it.readBytes() }

        val lote = gateway(upstream).crearLoteDesdePdf(examenId, "rafaga", "t.pdf", pdf)

        assertEquals(loteId, lote.id)
        assertTrue(
            upstream.any { it.method() == "POST" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") },
            "el staging tenía que escribirse: " + upstream.joinToString { "${it.method()} ${it.uri().rawPath}" },
        )
    }

    private fun examenRow() =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"33333333-3333-4333-8333-333333333333",""" +
            """"titulo":"Examen","descripcion":null,"tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true}"""

    private fun loteRow() =
        """{"id":"$loteId","examen_id":"$examenId","nombre_archivo_original":"t.pdf","tipo_examen":"rafaga",""" +
            """"estado":"parseado","total_detectadas":2,"total_importadas":0}"""

    private fun gateway(upstream: MutableList<HttpRequest>): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_duplicados") -> V1StubResponse(
                    404,
                    """{"code":"PGRST202","details":null,"hint":null,"message":"Could not find the function"}""",
                )
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow()}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") ->
                    if (upstream.lastOrNull()?.method() == "POST") V1StubResponse(201, "") else V1StubResponse(200, "[]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[${examenRow()}]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )
}
