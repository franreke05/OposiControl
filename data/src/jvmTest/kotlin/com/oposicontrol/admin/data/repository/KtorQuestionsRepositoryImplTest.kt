package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionListSort
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * El banco de Preguntas contra su contrato real: listado paginado en el servidor
 * (KQ0-4), lecturas sin valores inventados (KQ0-2), tipos de recurso (KQ0-6) y un
 * cuerpo de escritura que no puede mandar lo que no le toca (KQ0-8, KQ1).
 */
class KtorQuestionsRepositoryImplTest {

    // -----------------------------------------------------------------
    // Listado en el servidor (KQ0-4)
    // -----------------------------------------------------------------

    /**
     * La consulta por defecto manda explícitos `activa` y `orden` y la página, y
     * NADA de lo que no tiene valor. Mutación: mandar `q=` vacío o no mandar
     * `activa` pone este test en rojo.
     */
    @Test
    fun theDefaultListingAsksForTheFirstPageOfActiveQuestionsExplicitly() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { PAGINA }

        val page = assertIsSuccess(repository.listQuestions(QuestionListQuery()))

        val request = seen.single()
        assertEquals("/api/v1/admin/preguntas", request.url.encodedPath)
        assertEquals(setOf("activa", "orden", "limit", "offset"), request.url.parameters.names())
        assertEquals("true", request.url.parameters["activa"])
        assertEquals("recientes", request.url.parameters["orden"])
        assertEquals("25", request.url.parameters["limit"])
        assertEquals("0", request.url.parameters["offset"])
        assertEquals(137, page.total)
        assertEquals(listOf("PREG-000001", "PREG-000002"), page.items.map { it.code })
    }

    /**
     * Cada filtro viaja con su nombre de contrato, y la búsqueda codificada: un `&`
     * o un `#` del texto no pueden partir la URL ni añadir parámetros.
     */
    @Test
    fun everyFilterTravelsWithItsContractNameAndTheSearchIsEncoded() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { PAGINA }

        assertIsSuccess(
            repository.listQuestions(
                QuestionListQuery(
                    search = "  art. 14 & #CE  ",
                    type = QuestionType.RAZONADA,
                    active = QuestionActiveFilter.ALL,
                    examId = "9b2e4c61-7d3a-4f08-8e5b-1c6a0d9f3e27",
                    sort = QuestionListSort.BY_CODE,
                    offset = 100,
                    limit = 50,
                ),
            ),
        )

        val parameters = seen.single().url.parameters
        assertEquals("art. 14 & #CE", parameters["q"])
        assertEquals("razonada", parameters["tipo"])
        assertEquals("todas", parameters["activa"])
        assertEquals("9b2e4c61-7d3a-4f08-8e5b-1c6a0d9f3e27", parameters["examenId"])
        assertEquals("codigo", parameters["orden"])
        assertEquals("50", parameters["limit"])
        assertEquals("100", parameters["offset"])
        assertNull(parameters["especialidadId"], "la pregunta no tiene especialidad: no hay filtro por ella")
        assertEquals(7, parameters.names().size, "Se ha colado un parámetro: ${parameters.names()}")
    }

    /** Más de 100 caracteres sería un 400 del servidor: se recorta antes de salir. */
    @Test
    fun aSearchLongerThanTheServerAcceptsIsTrimmedToItsMaximum() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { PAGINA }

        assertIsSuccess(repository.listQuestions(QuestionListQuery(search = "x".repeat(140))))

        assertEquals(100, seen.single().url.parameters["q"]?.length)
    }

    // -----------------------------------------------------------------
    // Lecturas sin valores inventados (KQ0-2, KQ0-5, KQ0-6)
    // -----------------------------------------------------------------

    /**
     * Una dificultad NULL llega como clave ausente (el servidor omite los valores
     * iguales a su default) y se lee `""`, «sin definir». Con `"media"` de default
     * se leía —y se reenviaba al guardar— una dificultad que nadie puso.
     */
    @Test
    fun aMissingDifficultyIsReadAsUndefinedAndNeverAsMedia() = runTest {
        val repository = repository(null) { DETALLE }

        val detail = assertIsSuccess(repository.getQuestionDetail("P-1"))

        assertEquals("", detail.difficulty)
        assertEquals("", detail.source)
    }

    /** KQ0-6: el mismo mapeo que Exámenes; `pdf`, `enlace` y lo desconocido nunca son una imagen. */
    @Test
    fun everyLiveResourceTypeIsReadAndPdfOrLinkAreNeverAnImage() = runTest {
        val repository = repository(null) { DETALLE }

        val types = assertIsSuccess(repository.getQuestionDetail("P-1")).resources.associate { it.id to it.type }

        assertEquals(QuestionResourceType.IMAGE, types.getValue("R-IMG"))
        assertEquals(QuestionResourceType.AUDIO, types.getValue("R-AUD"))
        assertEquals(QuestionResourceType.VIDEO, types.getValue("R-VID"))
        assertEquals(QuestionResourceType.TEXT, types.getValue("R-TXT"))
        assertEquals(QuestionResourceType.DOCUMENT, types.getValue("R-PDF"))
        assertEquals(QuestionResourceType.DOCUMENT, types.getValue("R-ENL"))
        assertEquals(QuestionResourceType.DOCUMENT, types.getValue("R-RARO"))
    }

    // -----------------------------------------------------------------
    // Escritura (KQ0-8, KQ1, QN-17)
    // -----------------------------------------------------------------

    /**
     * Vaciar la fuente es una edición y tiene que viajar: el servidor lee su
     * AUSENCIA como «no la toques» (QN-17). Y el cuerpo no lleva código —lo genera la
     * base— ni recursos —el servidor los ignora—. Mutación: quitar
     * `@EncodeDefault` de `fuenteTexto` pone este test en rojo.
     */
    @Test
    fun anEmptySourceTravelsAndTheBodyNeverCarriesCodeOrResources() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { DETALLE }

        assertIsSuccess(
            repository.updateQuestion(
                questionId = "P-1",
                draft = QuestionDraft(
                    statement = "Enunciado",
                    type = QuestionType.RAZONADA,
                    options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
                        QuestionOption(id = "", questionId = "P-1", label = label, content = "Opción $label", isCorrect = index == 0, sortOrder = index)
                    },
                    explanation = "Porque sí.",
                    source = "",
                    active = true,
                    difficulty = "",
                ),
                optionsChanged = false,
            ),
        )

        val body = Json.parseToJsonElement((seen.single { it.method.value == "PATCH" }.body as TextContent).text).jsonObject
        assertEquals("\"\"", body["fuenteTexto"]?.toString(), "vaciar la fuente no viaja: $body")
        listOf("codigoPregunta", "recursos", "dificultad").forEach { key ->
            assertFalse(key in body, "El PATCH del banco no puede llevar $key: $body")
        }
        assertEquals("\"razonada\"", body["tipoPregunta"]?.toString())
        assertNull(body["opciones"], "sin tocar las opciones no se mandan: $body")
    }

    /**
     * KQ2-4 + M8: el alta manda el id que generó la pantalla y NINGUNA especialidad (la pregunta
     * no la tiene), sin leer antes el examen; el examen inicial viaja sólo si se eligió.
     * Mutación: volver a mandar o derivar una especialidad pone esto en rojo.
     */
    @Test
    fun aCreateSendsTheClientIdAndNoSpecialtyWithoutReadingTheExamFirst() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { DETALLE }
        val draft = QuestionDraft(
            statement = "Enunciado",
            type = QuestionType.RAZONADA,
            options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
                QuestionOption(id = "", questionId = "", label = label, content = "Opción $label", isCorrect = index == 0, sortOrder = index)
            },
            explanation = "Porque sí.",
            source = "",
            active = true,
            difficulty = "",
        )

        assertIsSuccess(repository.createQuestion("11111111-1111-4111-8111-111111111111", draft, examId = null))
        assertIsSuccess(repository.createQuestion("11111111-1111-4111-8111-111111111111", draft, examId = "EX-1"))

        val posts = seen.filter { it.method.value == "POST" }
        assertEquals(2, posts.size, "sin lectura previa del examen: ${seen.map { "${it.method.value} ${it.url.encodedPath}" }}")
        assertEquals(seen.size, posts.size)
        val sinExamen = Json.parseToJsonElement((posts[0].body as TextContent).text).jsonObject
        assertEquals("\"11111111-1111-4111-8111-111111111111\"", sinExamen["id"]?.toString())
        assertNull(sinExamen["especialidadId"], "la pregunta no tiene especialidad y el alta no la manda (M8): $sinExamen")
        assertNull(sinExamen["examenId"], "sin examen inicial no viaja ninguno: $sinExamen")
        assertFalse("dificultad" in sinExamen, "una dificultad vacía no viaja: $sinExamen")
        val conExamen = Json.parseToJsonElement((posts[1].body as TextContent).text).jsonObject
        assertEquals("\"EX-1\"", conExamen["examenId"]?.toString())
    }

    /** KQ3-2: los vínculos usan su propia ruta y devuelven la lista completa que responde el servidor. */
    @Test
    fun linksUseTheirOwnRouteAndReturnTheServerList() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { VINCULOS }

        val listed = assertIsSuccess(repository.listQuestionExams("P-1"))
        val linked = assertIsSuccess(repository.linkQuestionToExam("P-1", "EX-1", position = null))
        val unlinked = assertIsSuccess(repository.unlinkQuestionFromExam("P-1", "EX-1"))

        assertEquals(listOf("GET", "POST", "DELETE"), seen.map { it.method.value })
        assertEquals("/api/v1/admin/preguntas/P-1/examenes", seen[0].url.encodedPath)
        assertEquals("/api/v1/admin/preguntas/P-1/examenes", seen[1].url.encodedPath)
        assertEquals("/api/v1/admin/preguntas/P-1/examenes/EX-1", seen[2].url.encodedPath)
        val body = Json.parseToJsonElement((seen[1].body as TextContent).text).jsonObject
        assertEquals("\"EX-1\"", body["examenId"]?.toString())
        assertNull(body["posicion"], "posición ausente = al final: $body")
        listOf(listed, linked, unlinked).forEach { links ->
            assertEquals("EX-1", links.single().examId)
            assertEquals("publicado", links.single().status)
            assertEquals("rafaga", links.single().examType)
            assertEquals(3, links.single().position)
        }
    }

    /** Etiquetas (temas): GET lista, PUT manda el conjunto COMPLETO (`[]` incluido) y ambas leen la respuesta. */
    @Test
    fun tagsUseTheirOwnRouteAndThePutSendsTheWholeSet() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(seen) { ETIQUETAS }

        val listed = assertIsSuccess(repository.getQuestionTags("P-1"))
        val saved = assertIsSuccess(repository.setQuestionTags("P-1", listOf("T-1", "T-2")))
        assertIsSuccess(repository.setQuestionTags("P-1", emptyList()))

        assertEquals(listOf("GET", "PUT", "PUT"), seen.map { it.method.value })
        seen.forEach { assertEquals("/api/v1/admin/preguntas/P-1/etiquetas", it.url.encodedPath) }
        val body = Json.parseToJsonElement((seen[1].body as TextContent).text).jsonObject
        assertEquals("[\"T-1\",\"T-2\"]", body["etiquetaIds"]?.toString())
        assertEquals(setOf("etiquetaIds"), body.keys)
        val empty = Json.parseToJsonElement((seen[2].body as TextContent).text).jsonObject
        assertEquals("[]", empty["etiquetaIds"]?.toString(), "quitar todas viaja como lista vacía")
        listOf(listed, saved).forEach { tags ->
            assertEquals(listOf("T-1", "T-2"), tags.map { it.id })
            assertEquals("Constitución", tags[0].name)
            assertEquals(true, tags[0].active)
            assertFalse(tags[1].active)
        }
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun <T> assertIsSuccess(result: AppResult<T>): T = when (result) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> error("Se esperaba éxito, llegó ${result.error}")
    }

    /** [responder] contesta cada petición con el cuerpo JSON que devuelva. */
    private fun repository(
        record: MutableList<HttpRequestData>?,
        responder: (HttpRequestData) -> String,
    ): KtorQuestionsRepositoryImpl {
        val client = HttpClient(
            MockEngine { request ->
                record?.add(request)
                respond(
                    content = ByteReadChannel(responder(request)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorQuestionsRepositoryImpl(
            client,
            AppConfig(
                environment = Environment.DEV,
                baseUrl = "http://localhost:8080",
                backendToken = "test-token",
                supabaseUrl = "https://example.supabase.co",
                supabaseAnonKey = "anon-key",
                supabaseBucketExamResources = "exam-resources",
                featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
                appName = "OposiControl Admin",
                appVersion = "test",
            ),
            SessionLocalDataSource(
                AdminDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AdminDatabase.Schema.create(it) }),
            ),
            NetworkResultAdapter(NoOpLogger),
        )
    }

    private companion object {
        /** Lo que responden `GET`/`PUT /preguntas/{id}/etiquetas`: la segunda, desactivada en el catálogo. */
        val ETIQUETAS = """
        {"data":[{"id":"T-1","nombre":"Constitución"},{"id":"T-2","nombre":"Antigua","colorHex":"#ff0000","activa":false}]}
        """.trimIndent()

        /** Un vínculo tal y como lo responde `/preguntas/{id}/examenes` (K3-4). */
        val VINCULOS = """
        {"data":[{"examenId":"EX-1","codigoExamen":"EX-0001","titulo":"Examen","tipoExamen":"rafaga","estado":"publicado","posicion":3}]}
        """.trimIndent()

        /** Lo que devuelve `GET /preguntas` desde KQ0-3: la página y el total de la consulta. */
        val PAGINA = """
        {"data":[
          {"id":"P-1","codigoPregunta":"PREG-000001","tipoPregunta":"razonada","titulo":"Uno"},
          {"id":"P-2","codigoPregunta":"PREG-000002","tipoPregunta":"rafaga","titulo":"Dos","activa":false}
        ],"total":137,"limit":25,"offset":0}
        """.trimIndent()

        /**
         * Un detalle con `fuente_texto` y `dificultad` NULL —claves ausentes, como las
         * omite el servidor— y un recurso de cada tipo vivo del CHECK más uno raro.
         */
        val DETALLE = """
        {"data":{"id":"P-1","codigoPregunta":"PREG-000001","tipoPregunta":"inmersiva",
          "titulo":"Caso clínico","razonamiento":"","recursos":[
          {"id":"R-IMG","preguntaId":"P-1","tipoRecurso":"imagen","titulo":"Imagen","urlExterna":"https://x/i.png","posicion":1},
          {"id":"R-AUD","preguntaId":"P-1","tipoRecurso":"audio","titulo":"Audio","urlExterna":"https://x/a.mp3","posicion":2},
          {"id":"R-VID","preguntaId":"P-1","tipoRecurso":"video","titulo":"Vídeo","urlExterna":"https://x/v.mp4","posicion":3},
          {"id":"R-TXT","preguntaId":"P-1","tipoRecurso":"texto","titulo":"Texto","contenidoTexto":"Lee","posicion":4},
          {"id":"R-PDF","preguntaId":"P-1","tipoRecurso":"pdf","titulo":"Temario","urlExterna":"https://x/t.pdf","posicion":5},
          {"id":"R-ENL","preguntaId":"P-1","tipoRecurso":"enlace","titulo":"Enlace","urlExterna":"https://x/","posicion":6},
          {"id":"R-RARO","preguntaId":"P-1","tipoRecurso":"holograma","titulo":"Raro","urlExterna":"https://x/h","posicion":7}
        ]}}
        """.trimIndent()
    }
}
