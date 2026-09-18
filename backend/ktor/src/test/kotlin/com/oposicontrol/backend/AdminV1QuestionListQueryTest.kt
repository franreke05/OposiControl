package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KQ0-3 · El listado del banco de preguntas se pagina, filtra y ordena EN EL SERVIDOR.
 *
 * Antes era `select=*` sin cota y todo lo demás ocurría en el cliente sobre lo
 * cargado —que además eran sólo las activas, así que «Inactivas» no podía encontrar
 * nada (QN-07)—. Se afirma la URL EXACTA que sale hacia PostgREST: cada filtro es un
 * literal, un UUID canónico o la búsqueda saneada, y ningún valor del cliente puede
 * cambiar la estructura de la consulta.
 */
class AdminV1QuestionListQueryTest {

    private val especialidadId = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1"
    private val examenId = "b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2"
    private val columnas = "id,codigo_pregunta,especialidad_id,tipo_pregunta,titulo,fuente_texto,dificultad,activa"

    @Test
    fun theDefaultQueryIsOnePageOfActiveQuestionsNewestFirstWithTheExactCount() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gatewayWith(upstream).listPreguntasPagina(AdminPreguntaListQuery())

        val request = upstream.single()
        assertEquals("/rest/v1/preguntas", request.uri().rawPath)
        assertEquals(
            "select=$columnas&activa=eq.true&order=creado_en.desc,id.desc&limit=25&offset=0",
            request.uri().rawQuery,
        )
        assertEquals("count=exact", request.headers().firstValue("Prefer").orElse(null))
    }

    @Test
    fun everyFilterTravelsAsALiteralOrACanonicalId() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gatewayWith(upstream).listPreguntasPagina(
            AdminPreguntaListQuery(
                tipo = "razonada",
                especialidadId = especialidadId,
                activa = false,
                orden = PreguntaListOrden.CODIGO,
                limit = 10,
                offset = 20,
            ),
        )

        assertEquals(
            "select=$columnas&activa=eq.false&tipo_pregunta=eq.razonada&especialidad_id=eq.$especialidadId" +
                "&order=codigo_pregunta.asc,id.asc&limit=10&offset=20",
            upstream.single().uri().rawQuery,
        )
    }

    /** «Todas» no es un tercer valor del filtro: es no filtrar por estado. */
    @Test
    fun allStatesMeansNoStateFilter() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gatewayWith(upstream).listPreguntasPagina(AdminPreguntaListQuery(activa = null))

        assertFalse(upstream.single().uri().rawQuery.orEmpty().contains("activa="), upstream.single().uri().toString())
    }

    /**
     * Con examen la raíz es la asociación, los filtros de la pregunta van
     * PREFIJADOS con `preguntas.` y el orden es la posición: el total cuenta los
     * vínculos que cumplen los filtros.
     */
    @Test
    fun filteringByExamUsesTheLinkTableAndPrefixesTheQuestionFilters() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val page = gatewayWith(
            upstream,
            body = """[{"posicion":3,"preguntas":${preguntaJson("P1")}}]""",
        ).listPreguntasPagina(AdminPreguntaListQuery(examenId = examenId, tipo = "rafaga"))

        val request = upstream.single()
        assertEquals("/rest/v1/preguntas_examen", request.uri().rawPath)
        assertEquals(
            "select=posicion,preguntas!inner($columnas)&examen_id=eq.$examenId" +
                "&preguntas.activa=eq.true&preguntas.tipo_pregunta=eq.rafaga&order=posicion.asc&limit=25&offset=0",
            request.uri().rawQuery,
        )
        assertEquals(examenId, page.data.single().examenId)
        assertEquals(3, page.data.single().posicion)
    }

    /**
     * **La prueba de mutación de la búsqueda.** Dentro de `or=(…)`, una coma
     * separa condiciones y un paréntesis cierra el grupo: sin sanear, la búsqueda
     * `a,b(c)*` reescribiría la consulta. Tampoco puede viajar un `#` crudo.
     */
    @Test
    fun theSearchIsSanitisedSoItCannotChangeTheStructureOfTheFilter() {
        val filtro = preguntaSearchFilter("a,b(c)*\"d\\e#f&g")

        val condiciones = requireNotNull(filtro).split(",")
        assertEquals(2, condiciones.size, filtro)
        assertTrue(condiciones[0].startsWith("codigo_pregunta.ilike.") && condiciones[1].startsWith("titulo.ilike."), filtro)
        val valor = URLDecoder.decode(condiciones[0].removePrefix("codigo_pregunta.ilike."), StandardCharsets.UTF_8)
        assertEquals("*a b c d e#f&g*", valor)
        assertFalse(filtro.contains("#") || filtro.contains("&"), "la búsqueda viaja sin codificar: $filtro")
        assertNull(preguntaSearchFilter(" ,()* "), "una búsqueda que sólo tiene separadores no busca nada")
    }

    @Test
    fun theSearchReachesTheQueryAsOneOrGroup() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gatewayWith(upstream).listPreguntasPagina(AdminPreguntaListQuery(texto = "plazo"))

        val query = upstream.single().uri().rawQuery.orEmpty()
        assertTrue(query.contains("&or=(codigo_pregunta.ilike.*plazo*,titulo.ilike.*plazo*)"), query)
    }

    @Test
    fun theTotalComesFromContentRangeAndAnOffsetPastTheEndIsAnEmptyPage() {
        val page = gatewayWith(CopyOnWriteArrayList(), contentRange = "0-1/3", body = "[${preguntaJson("P1")},${preguntaJson("P2")}]")
            .listPreguntasPagina(AdminPreguntaListQuery(limit = 2))
        assertEquals(3, page.total)
        assertEquals(listOf("P1", "P2"), page.data.map { it.id })

        val vacia = gatewayWith(CopyOnWriteArrayList(), status = 416, contentRange = "*/3", body = "{}")
            .listPreguntasPagina(AdminPreguntaListQuery(offset = 50))
        assertEquals(emptyList(), vacia.data)
        assertEquals(3, vacia.total)
    }

    // -----------------------------------------------------------------
    // Parámetros inválidos: 400 y NI UNA petición
    // -----------------------------------------------------------------

    @Test
    fun invalidParametersAreRejectedBeforeAnyQuery() {
        listOf(
            "limit" to "0",
            "limit" to "101",
            "limit" to "diez",
            "offset" to "-1",
            "offset" to "100001",
            "tipo" to "x",
            "especialidadId" to "x",
            "examenId" to "x#",
            "orden" to "x",
            "activa" to "quizas",
            "q" to "a".repeat(101),
        ).forEach { (nombre, valor) ->
            assertFailsWith<IllegalArgumentException>("$nombre=$valor") {
                parametersOf(nombre, valor).toPreguntaListQuery()
            }
        }
    }

    @Test
    fun theRouteAnswers400ForAnInvalidParameterWithoutCallingSupabase() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installBackendStatusPages()
            routing { adminV1Routes(gatewayWith(upstream)) }
        }

        val response = client.get("/api/v1/admin/preguntas?limit=0")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(upstream.isEmpty(), upstream.map { it.uri() }.toString())
    }

    @Test
    fun theRouteAnswersAPageWithItsTotal() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installBackendStatusPages()
            routing { adminV1Routes(gatewayWith(upstream, body = "[${preguntaJson("P1")}]", contentRange = "0-0/7")) }
        }

        val response = client.get("/api/v1/admin/preguntas?q=plazo&limit=1&offset=0&activa=todas")

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertTrue(body.contains("\"total\":7") && body.contains("\"limit\":1") && body.contains("\"offset\":0"), body)
    }

    // -----------------------------------------------------------------

    private fun preguntaJson(id: String): String =
        """{"id":"$id","codigo_pregunta":"PREG-000001","especialidad_id":"$especialidadId",""" +
            """"tipo_pregunta":"rafaga","titulo":"Titulo","fuente_texto":null,"dificultad":null,"activa":true}"""

    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        status: Int = 200,
        body: String = "[]",
        contentRange: String = "*/0",
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { V1StubResponse(status, body, contentRange) },
    )
}
