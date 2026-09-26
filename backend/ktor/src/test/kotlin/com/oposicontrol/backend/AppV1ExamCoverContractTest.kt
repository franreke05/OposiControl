package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La portada de un examen NO se guarda: OposiMarket la resuelve con (tipo + comunidad autónoma).
 * El catálogo de la app ya manda `tipoExamen`; lo que faltaba era un identificador ESTABLE de la
 * comunidad, y es `comunidadCodigo` (ISO 3166-2:ES, `ES-MD`): el uuid cambia entre entornos.
 *
 * Contrato que fijan estos tests:
 *  - un examen con comunidad sale con su `comunidadCodigo`; sin comunidad (nacional), `null`;
 *  - un examen sin comunidad no provoca NI UNA lectura de `comunidades_autonomas`;
 *  - si esa lectura falla, el catálogo sigue saliendo (sin código): la portada cae a la genérica.
 */
class AppV1ExamCoverContractTest {

    private val examenMadrid = "22222222-2222-4222-8222-222222222222"
    private val examenNacional = "44444444-4444-4444-8444-444444444444"
    private val comunidadMadrid = "c0000000-0000-4000-8000-00000000000d"

    @Test
    fun theCatalogCarriesTheStableCodeOfTheExamCommunity() {
        val examenes = gateway().listExamenes().associateBy { it.id }

        assertEquals("ES-MD", examenes.getValue(examenMadrid).comunidadCodigo)
        assertEquals("inmersivo", examenes.getValue(examenMadrid).tipoExamen, "el tipo ya viajaba: es la otra mitad del resolver")
        assertNull(examenes.getValue(examenNacional).comunidadCodigo, "sin comunidad = nacional")
    }

    @Test
    fun theDetailCarriesTheSameCode() {
        val detalle = assertNotNull(gateway().examenDetalle(examenMadrid))

        assertEquals("ES-MD", detalle.examen.comunidadCodigo)
    }

    @Test
    fun aCatalogWithoutAnyCommunityNeverReadsTheCommunitiesTable() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val examenes = gateway(upstream, soloNacionales = true).listExamenes()

        assertNull(examenes.single().comunidadCodigo)
        assertTrue(upstream.none { it.uri().rawPath.contains("comunidades_autonomas") }, upstream.joinToString { it.uri().rawPath })
    }

    @Test
    fun aFailingCommunitiesLookupNeverBreaksTheCatalog() {
        val examenes = gateway(comunidadesStatus = 500).listExamenes()

        assertEquals(2, examenes.size)
        assertTrue(examenes.all { it.comunidadCodigo == null })
        assertEquals("inmersivo", examenes.first { it.id == examenMadrid }.tipoExamen)
    }

    private fun gateway(
        upstream: MutableList<HttpRequest> = CopyOnWriteArrayList(),
        soloNacionales: Boolean = false,
        comunidadesStatus: Int = 200,
    ): AppV1Gateway = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/comunidades_autonomas") ->
                    if (comunidadesStatus == 200) {
                        V1StubResponse(200, """[{"id":"$comunidadMadrid","codigo":"ES-MD"},{"id":"c0000000-0000-4000-8000-000000000001","codigo":"ES-AN"}]""")
                    } else {
                        V1StubResponse(comunidadesStatus, """{"message":"fallo de prueba"}""")
                    }
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/examenes") && soloNacionales -> V1StubResponse(200, "[${examen(examenNacional, null)}]")
                uri.contains("/rest/v1/examenes") && uri.contains("id=eq.") -> V1StubResponse(200, "[${examen(examenMadrid, comunidadMadrid)}]")
                uri.contains("/rest/v1/examenes") ->
                    V1StubResponse(200, "[${examen(examenMadrid, comunidadMadrid)},${examen(examenNacional, null)}]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun examen(id: String, comunidadId: String?): String {
        val comunidad = comunidadId?.let { "\"$it\"" } ?: "null"
        return """{"id":"$id","titulo":"Examen","tipo_examen":"inmersivo","tipo_acceso":"premium","total_preguntas":3,"comunidad_id":$comunidad}"""
    }
}
