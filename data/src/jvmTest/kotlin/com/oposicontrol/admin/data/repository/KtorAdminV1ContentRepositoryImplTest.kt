package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las tres reglas de la vertical de Exámenes que ya se rompieron una vez.
 *
 * No es un test de mapeo: cada caso fija una regla que un refactor razonable
 * volvería a romper — que ningún tipo de examen se pierda por el camino, que
 * guardar no reescriba un recuento que dimensiona exámenes de usuarios reales, y
 * que "quitar del examen" no signifique "archivar en todos".
 */
class KtorAdminV1ContentRepositoryImplTest {

    // -----------------------------------------------------------------
    // Ningún tipo de examen puede perderse
    // -----------------------------------------------------------------

    @Test
    fun everyExamTypeSurvivesTheRefreshIncludingRazonado() = runTest {
        val repository = repository()

        val refreshed = assertIsSuccess(repository.refreshAllExams())

        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-1. El reparto era
        // `filter { it.tipoExamen == "inmersivo" }` y `filter { == "rafaga" }`:
        // un examen `razonado` no caía en ningún cubo y desaparecía del panel
        // aunque estuviera perfectamente guardado en la base. Si alguien vuelve
        // a repartir por tipo literal, aquí faltan exámenes.
        assertEquals(
            listOf("EX-RAF", "EX-RAZ", "EX-INM", "EX-GUI", "EX-RARO"),
            refreshed.map { it.id },
            "refreshAllExams debe devolver TODOS los exámenes, sea cual sea su tipo.",
        )
        assertEquals(refreshed, repository.observeAllExams().first())
    }

    @Test
    fun theLegacyExamTypeAliasIsCanonicalisedAndAnUnknownTypeIsLeftAsItIs() = runTest {
        val repository = repository()

        val byId = assertIsSuccess(repository.refreshAllExams()).associateBy { it.id }

        // `guiado` es alias legacy de `razonado` y debe llegar ya canonizado:
        // ninguna superficie nueva puede tener que conocer el alias.
        assertEquals("razonado", byId.getValue("EX-GUI").typeExam)
        assertEquals("razonado", byId.getValue("EX-RAZ").typeExam)
        // Un tipo desconocido se conserva tal cual en vez de reetiquetarse como
        // `rafaga` (que es lo que haría `canonicalExamType`): mentir sobre el
        // tipo es peor que mostrar uno raro.
        assertEquals("marciano", byId.getValue("EX-RARO").typeExam)
    }

    @Test
    fun theTwoLegacyBucketsTogetherStillAccountForEveryExam() = runTest {
        val repository = repository()

        repository.refreshAllExams()
        val all = repository.observeAllExams().first().map { it.id }.toSet()
        val exams = repository.observeExams().first().map { it.id }
        val rafagas = repository.observeRafagas().first().map { it.id }

        // Los dos cubos siguen existiendo para el camino móvil, pero su unión ya
        // no puede dejarse nada fuera y su intersección debe ser vacía.
        assertEquals(all, (exams + rafagas).toSet(), "Todo examen cae en exactamente un cubo legacy.")
        assertTrue(exams.intersect(rafagas.toSet()).isEmpty(), "Ningún examen puede estar en los dos.")
        assertEquals(listOf("EX-RAF"), rafagas)
    }

    // -----------------------------------------------------------------
    // Guardar no puede reescribir total_preguntas
    // -----------------------------------------------------------------

    @Test
    fun savingAnExamNeverSendsTheQuestionCount() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.upsertExam(exam(id = "EX-RAF", questionCount = 7))

        val writes = seen.filter { it.method.value in setOf("POST", "PATCH") }.map { it.bodyText() }
        assertTrue(writes.isNotEmpty(), "No se envió ninguna escritura.")
        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-3. `questionCount` sale de la
        // lista que el editor tenga cargada, que puede estar vacía o a medias, y
        // `AppV1Routes.iniciar` lee `examenes.total_preguntas` para dimensionar
        // `intentos_examen` de un usuario final. Reintroducir
        // `totalPreguntas = questionCount.coerceAtLeast(1)` pone esto en rojo.
        assertFalse(
            writes.any { it.contains("totalPreguntas") },
            "El panel no escribe total_preguntas: ${writes.joinToString("\n")}",
        )
    }

    @Test
    fun anExamWithoutQuestionsIsReadAsZeroAndNotAsOne() = runTest {
        val repository = repository()

        val vacio = assertIsSuccess(repository.refreshAllExams()).first { it.id == "EX-RAZ" }

        // El default del DTO era 1: un examen sin preguntas se leía como si
        // tuviera una, y era imposible detectar exámenes vacíos desde el panel.
        assertEquals(0, vacio.questionCount)
    }

    // -----------------------------------------------------------------
    // Desvincular no es archivar
    // -----------------------------------------------------------------

    @Test
    fun unlinkingAQuestionUsesTheExamScopedRouteAndNeverTheGlobalOne() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.unlinkQuestionFromExam(examId = "EX-RAF", questionId = "P1")

        val deletes = seen.filter { it.method.value == "DELETE" }.map { it.url.encodedPath }
        assertEquals(listOf("/api/v1/admin/examenes/EX-RAF/preguntas/P1"), deletes)
        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-2. `DELETE /api/v1/admin/preguntas/P1`
        // archiva la pregunta en el banco y, siendo `preguntas_examen` una M:N,
        // la borra de TODOS los exámenes que la compartan. Desvincular de un
        // examen no puede acabar ahí nunca.
        assertFalse(
            deletes.any { it == "/api/v1/admin/preguntas/P1" },
            "Desvincular jamás puede llamar al DELETE global de preguntas: $deletes",
        )
    }

    @Test
    fun archivingAQuestionDoesNotPretendToBeScopedToAnExam() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.archiveQuestion(questionId = "P1")

        // Sí llama al DELETE global — es literalmente lo que hace — y su firma no
        // recibe examId para que nadie lo confunda con "quitar de este examen".
        assertEquals(
            listOf("/api/v1/admin/preguntas/P1"),
            seen.filter { it.method.value == "DELETE" }.map { it.url.encodedPath },
        )
    }

    // -----------------------------------------------------------------
    // El PATCH de examen manda siempre las tres claves anulables (P0-IB-1)
    // -----------------------------------------------------------------

    @Test
    fun savingAnExamAlwaysSendsTheThreeNullableKeysEvenWhenTheyAreNull() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        // Sin comunidad, sin año y sin duración: Nacional, «Sin año», «Sin duración».
        repository.upsertExam(exam(id = "EX-RAF", questionCount = 0))

        val patch = seen.single { it.method.value == "PATCH" }.bodyText()
        // ESTA ES LA PRUEBA DE MUTACIÓN de P0-IB-1 en el cliente. Con
        // `explicitNulls = false` el null se omitía, el backend leía «no tocar» y
        // volver un examen a Nacional no se guardaba nunca.
        assertTrue(patch.contains("\"comunidadId\":null"), patch)
        assertTrue(patch.contains("\"anio\":null"), patch)
        assertTrue(patch.contains("\"duracionMinutos\":null"), patch)
    }

    @Test
    fun savingAnExamSendsTheNullableKeysWithTheirValues() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.upsertExam(
            exam(id = "EX-RAF", questionCount = 0).copy(communityId = "COM-AN", year = 2026, durationMinutes = 90),
        )

        val patch = seen.single { it.method.value == "PATCH" }.bodyText()
        assertTrue(patch.contains("\"comunidadId\":\"COM-AN\""), patch)
        assertTrue(patch.contains("\"anio\":2026"), patch)
        assertTrue(patch.contains("\"duracionMinutos\":90"), patch)
    }

    @Test
    fun creatingAnExamAndSavingARafagaDoNotSendTheNullableKeysAsNull() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.upsertExam(exam(id = "", questionCount = 0))
        repository.upsertRafaga(
            Rafaga(
                id = "EX-RAF",
                specialtyId = "ESP",
                specialtyName = "Enfermería",
                title = "Ráfaga",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                questionCount = 10,
                createdAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )

        // El POST no cambia: un null omitido en un INSERT ya es NULL.
        val post = seen.single { it.method.value == "POST" }.bodyText()
        // La ráfaga del móvil no conoce esas columnas: su AUSENCIA es la que las
        // protege. Si este PATCH las mandara a null, las borraría en cada guardado.
        val rafagaPatch = seen.single { it.method.value == "PATCH" }.bodyText()
        listOf("comunidadId", "anio", "duracionMinutos").forEach { key ->
            assertFalse(post.contains("\"$key\":null"), "El POST no manda $key a null: $post")
            assertFalse(rafagaPatch.contains("\"$key\""), "La ráfaga no puede mandar $key: $rafagaPatch")
        }
    }

    // -----------------------------------------------------------------
    // La portada tiene que llegar a la fuente que observa el workspace
    // -----------------------------------------------------------------

    @Test
    fun uploadingACoverUpdatesTheSourceTheWorkspaceObserves() = runTest {
        val repository = repository(respondWith = { request ->
            if (request.method.value == "POST" && request.url.encodedPath.endsWith("/EX-INM/portada")) {
                """{"data":{"id":"EX-INM","especialidadId":"ESP","titulo":"Inmersivo","tipoExamen":"inmersivo",""" +
                    """"totalPreguntas":0,"portadaUrl":"$COVER_URL","portadaStoragePath":"$COVER_PATH",""" +
                    """"portadaMimeType":"image/png"}}"""
            } else {
                null
            }
        })
        assertIsSuccess(repository.refreshAllExams())

        val saved = assertIsSuccess(repository.uploadExamCover("EX-INM", tempCover().absolutePath))

        // ESTA ES LA PRUEBA DE MUTACIÓN de P1-IB-2. Sólo se actualizaba
        // `examsFlow`, y el workspace observa `observeAllExams`: tras una subida
        // correcta el catálogo seguía sin portada, el editor ya la tenía y la
        // barra anunciaba «Hay cambios sin guardar» que nadie había hecho.
        val cached = repository.observeAllExams().first().first { it.id == "EX-INM" }
        assertEquals(COVER_URL, cached.coverUrl)
        assertEquals(COVER_URL, saved.coverUrl)
        assertEquals(COVER_URL, repository.observeExams().first().first { it.id == "EX-INM" }.coverUrl)
        // La respuesta trae el `total_preguntas` de la base (0). El recuento que
        // ya había en caché no puede pisarse por cambiar una imagen.
        assertEquals(5, cached.questionCount)
    }

    @Test
    fun clearingACoverUpdatesTheSourceTheWorkspaceObserves() = runTest {
        val repository = repository(respondWith = { request ->
            val path = request.url.encodedPath
            when {
                request.method.value == "GET" && path.endsWith("/examenes") -> EXAMENES_CON_PORTADA
                request.method.value == "DELETE" && path.endsWith("/EX-INM/portada") ->
                    """{"data":{"id":"EX-INM","especialidadId":"ESP","titulo":"Inmersivo","tipoExamen":"inmersivo"}}"""
                else -> null
            }
        })
        assertIsSuccess(repository.refreshAllExams())
        assertEquals(COVER_URL, repository.observeAllExams().first().first { it.id == "EX-INM" }.coverUrl)

        assertIsSuccess(repository.clearExamCover("EX-INM"))

        // Misma mutación, al revés: sin esto «Descartar» volvía a sembrar una
        // portada que el servidor ya había quitado.
        assertNull(repository.observeAllExams().first().first { it.id == "EX-INM" }.coverUrl)
    }

    @Test
    fun everyExamTheRepositoryHandsBackIsCanonicalNotOnlyTheCachedOnes() = runTest {
        val repository = repository(respondWith = { request ->
            if (request.method.value == "POST" && request.url.encodedPath.endsWith("/EX-GUI/publicar")) {
                """{"data":{"id":"EX-GUI","especialidadId":"ESP","titulo":"Guiado legacy","tipoExamen":"guiado",""" +
                    """"publicadoEn":"2026-01-01T00:00:00Z"}}"""
            } else {
                null
            }
        })

        val published = assertIsSuccess(repository.publishExam("EX-GUI"))

        // El VM fusiona este `Exam` en el catálogo. Un `guiado` crudo frente al
        // `razonado` que sembró el editor se leía como un cambio sin guardar.
        assertEquals("razonado", published.typeExam)
    }

    // -----------------------------------------------------------------
    // El tipo de la pregunta: canónico, alias legacy, y `null` para lo que no
    // se reconoce
    // -----------------------------------------------------------------

    @Test
    fun theQuestionTypeIsCanonicalisedAndAnUnknownOneIsNullInsteadOfRafaga() = runTest {
        val repository = repository(respondWith = { request ->
            if (request.method.value == "GET" && request.url.encodedPath.endsWith("/EX-RAF/preguntas")) PREGUNTAS else null
        })

        val byId = assertIsSuccess(repository.getExamQuestions("EX-RAF")).associateBy { it.id }

        assertEquals(QuestionType.RAFAGA, byId.getValue("P-RAF").type)
        assertEquals(QuestionType.INMERSIVA, byId.getValue("P-INM").type)
        // `guiada` es el alias legacy de `razonada`: se lee, nunca se escribe.
        assertEquals(QuestionType.RAZONADA, byId.getValue("P-GUI").type)
        // Lo que nadie reconoce es `null`. Reetiquetarlo como Ráfaga —que es lo
        // que haría un `?: QuestionType.RAFAGA`— es mentir sobre el tipo, y la
        // pestaña lo enseñaría como una Ráfaga perfectamente normal.
        assertNull(byId.getValue("P-RARO").type)
    }

    // -----------------------------------------------------------------
    // Guardar una pregunta desde Exámenes (K0-1, QN-17)
    // -----------------------------------------------------------------

    /**
     * K0-1 (EX-10): el tipo de la pregunta sale del examen, leído por SU ruta, con el
     * mismo emparejamiento que impone `validar_pregunta_examen`. Antes eran dos
     * `GET /examenes` completos y un mapeo que mandaba `rafaga` para un Razonado,
     * que la base rechaza. Mutación: volver a mapear Razonado a `rafaga`, o a leer el
     * catálogo entero, pone este test en rojo.
     */
    @Test
    fun savingAQuestionOfAReasonedExamSendsRazonada() = runTest {
        listOf("rafaga" to "rafaga", "razonado" to "razonada", "guiado" to "razonada", "inmersivo" to "inmersiva")
            .forEach { (tipoExamen, tipoPregunta) ->
                val seen = mutableListOf<HttpRequestData>()
                val repository = repository(record = seen, respondWith = questionSaveResponder(tipoExamen))

                assertIsSuccess(repository.upsertQuestion(examQuestion(examId = "EX-X")))

                val post = seen.single { it.method.value == "POST" && it.url.encodedPath == "/api/v1/admin/preguntas" }
                val body = Json.parseToJsonElement(post.bodyText()).jsonObject
                assertEquals("\"$tipoPregunta\"", body.getValue("tipoPregunta").toString(), "examen $tipoExamen")
                assertFalse("especialidadId" in body, "la pregunta no tiene especialidad (M8): $body")
                assertEquals(
                    1,
                    seen.count { it.method.value == "GET" && it.url.encodedPath == "/api/v1/admin/examenes/EX-X" },
                    "el examen se lee UNA vez, por su ruta",
                )
                assertFalse(
                    seen.any { it.method.value == "GET" && it.url.encodedPath == "/api/v1/admin/examenes" },
                    "guardar una pregunta no descarga el catálogo: ${seen.map { "${it.method.value} ${it.url.encodedPath}" }}",
                )
            }
    }

    /** Un examen de tipo desconocido no se adivina: falla sin escribir nada. */
    @Test
    fun aQuestionOfAnExamWithAnUnknownTypeIsNotWritten() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen, respondWith = questionSaveResponder("marciano"))

        val result = repository.upsertQuestion(examQuestion(examId = "EX-X"))

        assertTrue(result is AppResult.Failure, "un tipo desconocido no puede guardarse como otro: $result")
        assertFalse(seen.any { it.method.value in setOf("POST", "PATCH") }, "se escribió igualmente")
    }

    /**
     * QN-17: el editor de Exámenes manda SÓLO lo que edita. Antes mandaba
     * `fuenteTexto = enunciado`, `razonamiento = texto de la correcta` y los
     * recursos con su URL, y en cada guardado corrompía la fuente y la explicación
     * reales que se editan en el banco de Preguntas. Mutación: volver a rellenar
     * `fuenteTexto` o `razonamiento` en `ExamQuestion.toDto` pone este test en rojo.
     */
    @Test
    fun savingAQuestionFromTheExamEditorNeverOverwritesItsSourceOrExplanation() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen, respondWith = questionSaveResponder("razonado"))

        assertIsSuccess(repository.upsertQuestion(examQuestion(examId = "EX-X")))

        val body = Json.parseToJsonElement(
            seen.single { it.method.value == "POST" && it.url.encodedPath == "/api/v1/admin/preguntas" }.bodyText(),
        ).jsonObject
        listOf("fuenteTexto", "razonamiento", "recursos", "dificultad", "codigoPregunta").forEach { key ->
            assertFalse(key in body, "El editor de Exámenes no puede mandar $key: $body")
        }
        assertEquals("\"Enunciado de la pregunta\"", body.getValue("titulo").toString())
        assertTrue(body.toString().contains("Respuesta correcta"), "las opciones sí viajan: $body")
        assertEquals("\"EX-X\"", body.getValue("examenId").toString())
    }

    // -----------------------------------------------------------------
    // Tipos de recurso (KQ0-6)
    // -----------------------------------------------------------------

    /**
     * KQ0-6: `pdf` y `enlace` —vocabulario real del CHECK— caían al `else` y se
     * pintaban como imagen. Se leen como DOCUMENT, igual que un tipo desconocido.
     */
    @Test
    fun everyLiveResourceTypeIsReadAndPdfOrLinkAreNeverAnImage() = runTest {
        val repository = repository(respondWith = { request ->
            if (request.method.value == "GET" && request.url.encodedPath.endsWith("/EX-INM/preguntas")) PREGUNTA_CON_RECURSOS else null
        })

        val recursos = assertIsSuccess(repository.getExamQuestions("EX-INM")).single().resources.associateBy { it.id }

        assertEquals(QuestionResourceType.IMAGE, recursos.getValue("R-IMG").type)
        assertEquals(QuestionResourceType.AUDIO, recursos.getValue("R-AUD").type)
        assertEquals(QuestionResourceType.VIDEO, recursos.getValue("R-VID").type)
        assertEquals(QuestionResourceType.TEXT, recursos.getValue("R-TXT").type)
        assertEquals(QuestionResourceType.DOCUMENT, recursos.getValue("R-PDF").type)
        assertEquals(QuestionResourceType.DOCUMENT, recursos.getValue("R-ENL").type)
        assertEquals(QuestionResourceType.DOCUMENT, recursos.getValue("R-RARO").type)
    }

    /** KQ0-6: un documento se escribe como `pdf`; `documento` no está en el CHECK y fallaba con 23514. */
    @Test
    fun aDocumentResourceIsWrittenAsPdf() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen, respondWith = { request ->
            if (request.method.value == "POST" && request.url.encodedPath.endsWith("/preguntas/P-1/recursos")) {
                """{"data":{"id":"R-1","preguntaId":"P-1","tipoRecurso":"pdf","titulo":"Temario","urlExterna":"https://x/t.pdf","posicion":1}}"""
            } else {
                null
            }
        })

        val saved = assertIsSuccess(
            repository.uploadResource("P-1", "R-1", QuestionResourceType.DOCUMENT, "https://x/t.pdf", "Temario"),
        )

        val post = seen.single { it.method.value == "POST" && it.url.encodedPath.endsWith("/recursos") }.bodyText()
        assertTrue(post.contains("\"tipoRecurso\":\"pdf\""), post)
        assertEquals(QuestionResourceType.DOCUMENT, saved.type)
    }

    // -----------------------------------------------------------------
    // Sin descargas repetidas del catálogo (K0-5)
    // -----------------------------------------------------------------

    /**
     * K0-5 (EX-13): los recuentos por especialidad salen del catálogo en caché. Con
     * la caché llena, refrescar especialidades no vuelve a pedir `GET /examenes`; con
     * la caché vacía lo pide UNA vez y no repite `GET /especialidades`.
     */
    @Test
    fun refreshingSpecialtiesReusesTheCachedCatalog() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)
        fun gets(path: String) = seen.count { it.method.value == "GET" && it.url.encodedPath == path }

        val primero = assertIsSuccess(repository.refreshSpecialties())
        assertEquals(1, gets("/api/v1/admin/examenes"), "caché vacía: una descarga")
        assertEquals(1, gets("/api/v1/admin/especialidades"), "las especialidades no se piden dos veces")
        assertEquals(5, primero.single().linkedExams)

        assertIsSuccess(repository.refreshSpecialties())
        assertEquals(1, gets("/api/v1/admin/examenes"), "caché llena: ninguna descarga más")
    }

    /**
     * K0-5: publicar, despublicar, archivar y borrar funden el resultado en las
     * cachés sin volver a descargar el catálogo. Mutación: devolver
     * `refreshExamCaches()` a cualquiera de los cuatro pone este test en rojo.
     */
    @Test
    fun lifecycleActionsMergeTheReturnedExamWithoutDownloadingTheCatalog() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen, respondWith = { request ->
            val path = request.url.encodedPath
            when {
                request.method.value == "POST" && path.endsWith("/EX-INM/publicar") ->
                    """{"data":{"id":"EX-INM","especialidadId":"ESP","titulo":"Inmersivo","tipoExamen":"inmersivo",""" +
                        """"totalPreguntas":5,"publicadoEn":"2026-02-01T00:00:00Z"}}"""
                request.method.value == "POST" && path.endsWith("/EX-RAZ/archivar") ->
                    """{"data":{"id":"EX-RAZ","especialidadId":"ESP","titulo":"Razonado","tipoExamen":"razonado","activo":false}}"""
                else -> null
            }
        })
        assertIsSuccess(repository.refreshAllExams())
        val descargas = seen.count { it.method.value == "GET" && it.url.encodedPath == "/api/v1/admin/examenes" }

        assertIsSuccess(repository.publishExam("EX-INM"))
        assertIsSuccess(repository.archiveExam("EX-RAZ"))
        assertIsSuccess(repository.deleteExam("EX-RARO"))

        assertEquals(descargas, seen.count { it.method.value == "GET" && it.url.encodedPath == "/api/v1/admin/examenes" })
        val all = repository.observeAllExams().first().associateBy { it.id }
        assertEquals(ExamStatus.PUBLISHED, all.getValue("EX-INM").status)
        assertEquals(ExamStatus.ARCHIVED, all.getValue("EX-RAZ").status)
        assertFalse("EX-RARO" in all, "el borrado sigue en la caché")
        assertEquals(ExamStatus.PUBLISHED, repository.observeExams().first().first { it.id == "EX-INM" }.status)
        assertFalse(repository.observeExams().first().any { it.id == "EX-RARO" })
    }

    // -----------------------------------------------------------------
    // Borrar ≠ archivar: dos rutas, y ninguna de las dos puede hacer lo de la
    // otra
    // -----------------------------------------------------------------

    /**
     * `deleteExam` BORRA. Su nombre ya lo decía y el endpoint no lo hacía.
     *
     * `DELETE /api/v1/admin/examenes/{id}` archivaba (`activo=false`), así que el
     * comentario de esta función advertía de que «el nombre viene de la interfaz
     * histórica; el endpoint real archiva». Ya no: el DELETE borra la fila con la
     * guarda de datos de usuario del servidor delante.
     */
    @Test
    fun deletingAnExamHitsTheDeleteEndpointOfTheExam() = runTest {
        val seen = mutableListOf<HttpRequestData>()

        assertIsSuccess(repository(record = seen).deleteExam("EX-RAF"))

        assertTrue(
            seen.any { it.method.value == "DELETE" && it.url.encodedPath == "/api/v1/admin/examenes/EX-RAF" },
            "no salió el DELETE del examen: ${seen.map { "${it.method.value} ${it.url.encodedPath}" }}",
        )
    }

    /** Y `archiveExam` archiva, por la ruta nueva, sin un solo DELETE. */
    @Test
    fun archivingAnExamPostsToTheArchiveRouteAndNeverDeletes() = runTest {
        val seen = mutableListOf<HttpRequestData>()

        assertIsSuccess(
            repository(record = seen, respondWith = { request -> UN_EXAMEN.takeIf { request.esEscrituraDeExamen() } })
                .archiveExam("EX-RAF"),
        )

        assertTrue(
            seen.any { it.method.value == "POST" && it.url.encodedPath == "/api/v1/admin/examenes/EX-RAF/archivar" },
            "no salió el POST de archivar: ${seen.map { "${it.method.value} ${it.url.encodedPath}" }}",
        )
        assertFalse(
            seen.any { it.method.value == "DELETE" },
            "archivar borró: ${seen.map { "${it.method.value} ${it.url.encodedPath}" }}",
        )
    }

    /**
     * **Guardar no toca el ciclo de vida (K0-2, DEBT-026).**
     *
     * `upsertExamDto` encadenaba tras el PATCH la acción del estado del editor:
     * guardar una errata de un publicado lo re-publicaba y re-sellaba la fecha, y el
     * «último que guarda gana» podía despublicar lo que otro acababa de publicar.
     * Antes aún, la rama `ARCHIVED` llamaba a un DELETE que ya borraba de verdad.
     * Ahora el escritorio publica, despublica y archiva SÓLO con sus botones.
     *
     * Los tres estados, y el borrador con `publicadoEn` en la respuesta —el caso que
     * despublicaba—: un único PATCH y ni un POST de ciclo de vida ni un DELETE.
     * Tampoco vuelve a descargar el catálogo (K0-5). Mutación: `chainLifecycle = true`
     * en `upsertExam` pone este test en rojo.
     */
    @Test
    fun savingAnExamNeverTouchesItsLifecycle() = runTest {
        listOf(ExamStatus.PUBLISHED, ExamStatus.DRAFT, ExamStatus.ARCHIVED).forEach { status ->
            val seen = mutableListOf<HttpRequestData>()
            val publicado = """{"data":{"id":"EX-RAF","especialidadId":"ESP","titulo":"Ráfaga","tipoExamen":"rafaga",""" +
                """"totalPreguntas":10,"publicadoEn":"2026-01-01T00:00:00Z"}}"""

            assertIsSuccess(
                repository(record = seen, respondWith = { request -> publicado.takeIf { request.esEscrituraDeExamen() } })
                    .upsertExam(exam(id = "EX-RAF", questionCount = 3, status = status)),
            )

            val trazas = seen.map { "${it.method.value} ${it.url.encodedPath}" }
            assertEquals(1, seen.count { it.method.value == "PATCH" }, "$status: $trazas")
            listOf("/publicar", "/despublicar", "/archivar").forEach { ruta ->
                assertFalse(seen.any { it.url.encodedPath.endsWith(ruta) }, "$status: guardar llamó a $ruta: $trazas")
            }
            assertFalse(seen.any { it.method.value == "DELETE" }, "$status: GUARDAR BORRÓ EL EXAMEN: $trazas")
            assertFalse(
                seen.any { it.method.value == "GET" && it.url.encodedPath == "/api/v1/admin/examenes" },
                "$status: guardar volvió a descargar el catálogo: $trazas",
            )
        }
    }

    /**
     * K-C01a: el PATCH del escritorio lleva EXACTAMENTE las once claves que posee el
     * editor, con su valor aunque sea el default (`false`, `0`, `"rafaga"`, `""`).
     * Desde C-01 el servidor sólo escribe lo presente: un `esOficial=false` omitido
     * por valer su default ya no volvería a guardarse. Y ninguna clave que no posee:
     * visibilidad, `totalPreguntas`, portada o metadatos.
     */
    @Test
    fun theDesktopExamPatchCarriesEveryKeyItOwnsAndNothingElse() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.upsertExam(exam(id = "EX-RAF", questionCount = 3, status = ExamStatus.PUBLISHED))

        val patch = Json.parseToJsonElement(seen.single { it.method.value == "PATCH" }.bodyText()).jsonObject
        assertEquals(
            setOf(
                "codigoExamen", "especialidadId", "comunidadId", "titulo", "descripcion", "tipoExamen",
                "tipoAcceso", "anio", "esOficial", "precioTickets", "duracionMinutos",
            ),
            patch.keys,
        )
        assertEquals("false", patch.getValue("esOficial").toString())
        assertEquals("0", patch.getValue("precioTickets").toString())
        assertEquals("\"rafaga\"", patch.getValue("tipoExamen").toString())
        assertEquals("\"gratuito\"", patch.getValue("tipoAcceso").toString())
        assertEquals("\"\"", patch.getValue("descripcion").toString())
        assertEquals("\"EX-1\"", patch.getValue("codigoExamen").toString())
        listOf(
            "activo", "publicadoEn", "totalPreguntas", "portadaUrl", "portadaStoragePath",
            "portadaMimeType", "creadoEn", "actualizadoEn", "id",
        ).forEach { key -> assertFalse(key in patch, "El PATCH no puede llevar $key: $patch") }
    }

    /**
     * K-C01b: la ráfaga del móvil manda EXPLÍCITAS sus cinco claves —volver a precio
     * 0 o a gratuito tiene que viajar— y ninguna de las que no conoce: ni el código
     * ni las tres anulables, cuya ausencia es lo que las protege.
     */
    @Test
    fun theMobileRafagaPatchCarriesOnlyItsOwnKeys() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen)

        repository.upsertRafaga(
            Rafaga(
                id = "EX-RAF",
                specialtyId = "ESP",
                specialtyName = "Enfermería",
                title = "Ráfaga",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                questionCount = 10,
                createdAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )

        val patch = Json.parseToJsonElement(seen.single { it.method.value == "PATCH" }.bodyText()).jsonObject
        assertEquals(setOf("especialidadId", "titulo", "tipoExamen", "tipoAcceso", "precioTickets"), patch.keys)
        assertEquals("0", patch.getValue("precioTickets").toString())
        assertEquals("\"gratuito\"", patch.getValue("tipoAcceso").toString())
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------

    /**
     * Un `{"data":{…}}` de un solo examen, que es lo que devuelven el PATCH, el
     * POST de archivar y los de publicar/despublicar. El responder por defecto de
     * [repository] sólo sabe fabricar la LISTA.
     */
    private fun HttpRequestData.esEscrituraDeExamen(): Boolean =
        method.value in setOf("POST", "PATCH") && url.encodedPath.contains("/examenes/")

    /**
     * Responde lo que `upsertQuestion` pide para una pregunta NUEVA del examen `EX-X`:
     * su lista de preguntas (vacía), el examen por su ruta con [tipoExamen] y el alta.
     */
    private fun questionSaveResponder(tipoExamen: String): (HttpRequestData) -> String? = { request ->
        val path = request.url.encodedPath
        when {
            request.method.value == "GET" && path == "/api/v1/admin/examenes/EX-X" ->
                """{"data":{"id":"EX-X","especialidadId":"ESP-X","titulo":"Examen","tipoExamen":"$tipoExamen"}}"""
            request.method.value == "GET" && path == "/api/v1/admin/examenes/EX-X/preguntas" -> """{"data":[]}"""
            request.method.value == "POST" && path == "/api/v1/admin/preguntas" ->
                """{"data":{"id":"P-NEW","titulo":"Enunciado de la pregunta","tipoPregunta":"razonada","examenId":"EX-X","posicion":1}}"""
            else -> null
        }
    }

    /**
     * Una pregunta del editor de Exámenes con todo lo que ese editor NO debe mandar
     * a mano: una opción correcta con texto (el antiguo «razonamiento») y un recurso
     * con URL firmada (lo que se habría persistido y caducado).
     */
    private fun examQuestion(examId: String): ExamQuestion {
        val now = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z")
        return ExamQuestion(
            id = "P-NEW",
            examId = examId,
            title = "Pregunta 1",
            statement = "Enunciado de la pregunta",
            sortOrder = 0,
            options = listOf(
                QuestionOption(id = "O-A", questionId = "P-NEW", label = "A", content = "Respuesta correcta", isCorrect = true, sortOrder = 0),
                QuestionOption(id = "O-B", questionId = "P-NEW", label = "B", content = "Otra respuesta", isCorrect = false, sortOrder = 1),
            ),
            resources = listOf(
                QuestionResource(
                    id = "R-1",
                    questionId = "P-NEW",
                    type = QuestionResourceType.IMAGE,
                    title = "Imagen",
                    textBody = null,
                    storagePath = "question-resources/P-NEW/imagen.png",
                    publicUrl = "https://stub.supabase.test/storage/v1/object/sign/exam-resources/imagen.png?token=caduca",
                    mimeType = "image/png",
                    sortOrder = 0,
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun tempCover(): java.io.File =
        java.io.File.createTempFile("portada", ".png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            deleteOnExit()
        }

    private fun exam(
        id: String,
        questionCount: Int,
        status: ExamStatus = ExamStatus.DRAFT,
    ) = Exam(
        id = id,
        code = "EX-1",
        specialtyId = "ESP",
        specialtyName = "Enfermería",
        title = "Examen",
        description = "",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = status,
        publishFrom = null,
        publishedAt = null,
        sortOrder = 0,
        questionCount = questionCount,
        createdAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = kotlinx.datetime.Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun <T> assertIsSuccess(result: AppResult<T>): T = when (result) {
        is AppResult.Success -> result.value
        is AppResult.Failure -> error("Se esperaba éxito, llegó ${result.error}")
    }

    // -----------------------------------------------------------------
    // Capacidades por especialidad: el cuerpo tiene que ser explícito
    // -----------------------------------------------------------------

    /**
     * Apagar un modo tiene que poder viajar por el cable.
     *
     * El DTO del cliente declara `permiteRazonados = false` y
     * `permiteInmersivos = false` como default, y el `Json` del cliente
     * (`ApiClientFactory.kt`) no pone `encodeDefaults`. Sin anotación, poner uno de
     * esos flags en `false` coincidía con su default, kotlinx omitía la clave, y el
     * servidor rellenaba el hueco con SU default —que es `true`—. Resultado: el
     * administrador apagaba Razonados, el PUT escribía `true`, y el interruptor
     * volvía solo.
     *
     * Es el gemelo exacto del defecto de lectura que cubre
     * `SpecialtyCapabilitiesTest.capabilitiesPayloadAlwaysCarriesEveryFlagExplicitly`
     * en el backend, y por eso se fija en los dos lados: los dos DTO son distintos
     * y nada sincroniza sus defaults.
     */
    @Test
    fun turningAModeOffTravelsExplicitlyInsteadOfBeingOmitted() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen) { request ->
            if (request.url.encodedPath.endsWith("/capacidades")) CAPACIDADES_TODO_ABIERTO else null
        }

        assertIsSuccess(
            repository.saveSpecialtyCapabilities(
                SpecialtyCapabilities(
                    permiteRafagas = true,
                    permiteRazonados = false,
                    permiteInmersivos = false,
                    permiteDemos = true,
                    permiteOposimarket = true,
                    specialtyId = "ESP",
                    limitationMessage = "Contenido en preparación",
                ),
            ),
        )

        val put = seen.single { it.method.value == "PUT" }.bodyText()
        assertTrue(put.contains("\"permiteRazonados\":false"), "razonados apagado no viaja: $put")
        assertTrue(put.contains("\"permiteInmersivos\":false"), "inmersivos apagado no viaja: $put")
    }

    /**
     * Y encenderlo también, por el otro extremo del mismo problema: `permiteRafagas`
     * y los dos flags comerciales tienen `true` por default en el DTO del cliente,
     * así que era el valor `true` el que se omitía en su caso.
     */
    @Test
    fun everyCapabilityFlagTravelsWhateverItsValue() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repository = repository(record = seen) { request ->
            if (request.url.encodedPath.endsWith("/capacidades")) CAPACIDADES_TODO_ABIERTO else null
        }

        assertIsSuccess(
            repository.saveSpecialtyCapabilities(
                SpecialtyCapabilities(
                    permiteRafagas = true,
                    permiteRazonados = true,
                    permiteInmersivos = true,
                    permiteDemos = true,
                    permiteOposimarket = true,
                    specialtyId = "ESP",
                ),
            ),
        )

        val put = seen.single { it.method.value == "PUT" }.bodyText()
        listOf(
            "permiteRafagas",
            "permiteRazonados",
            "permiteInmersivos",
            "permiteDemos",
            "permiteOposimarket",
        ).forEach { flag ->
            assertTrue(put.contains("\"$flag\""), "$flag no viaja en el PUT: $put")
        }
    }

    /**
     * Lectura: un flag que el servidor NO manda no puede leerse como "cerrado".
     *
     * El backend ya lo manda siempre, pero el cliente no debe apoyarse en eso: si
     * la clave falta, el default del cliente tiene que coincidir con el del
     * servidor, que es abierto. Lo contrario es el defecto original —Razonados e
     * Inmersivos pintados en OFF para todas las especialidades— reaparecido por un
     * backend antiguo o una respuesta recortada.
     */
    @Test
    fun aMissingCapabilityFlagDefaultsToOpenNotClosed() = runTest {
        val repository = repository { request ->
            if (request.url.encodedPath.endsWith("/capacidades")) {
                """{"data":{"especialidadId":"ESP"}}"""
            } else {
                null
            }
        }

        val capabilities = assertIsSuccess(repository.getSpecialtyCapabilities("ESP"))

        assertTrue(capabilities.permiteRazonados, "un razonados ausente se leyó como cerrado")
        assertTrue(capabilities.permiteInmersivos, "un inmersivos ausente se leyó como cerrado")
        assertEquals(listOf("rafaga", "razonado", "inmersivo"), capabilities.enabledExamModes)
    }

    private fun HttpRequestData.bodyText(): String = (body as? TextContent)?.text.orEmpty()

    private fun appConfig() = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "test-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private fun sessionLocalDataSource(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    /**
     * [respondWith] deja que un test conteste una petición concreta; si devuelve
     * `null`, se usa la respuesta por defecto.
     */
    private fun repository(
        record: MutableList<HttpRequestData>? = null,
        respondWith: (HttpRequestData) -> String? = { null },
    ): KtorAdminV1ContentRepositoryImpl {
        val client = HttpClient(
            MockEngine { request ->
                record?.add(request)
                val path = request.url.encodedPath
                val body = respondWith(request) ?: when {
                    path.endsWith("/especialidades") -> ESPECIALIDADES
                    path.endsWith("/examenes") && request.method.value == "GET" -> EXAMENES
                    path.contains("/preguntas") -> """{"data":[]}"""
                    else -> """{"data":${EXAMENES.substringAfter("[").substringBeforeLast("]").let { "{$it}" }}}"""
                }
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return KtorAdminV1ContentRepositoryImpl(
            client,
            appConfig(),
            sessionLocalDataSource(),
            NetworkResultAdapter(NoOpLogger),
            PlatformFileReader(AppPlatformContext()),
        )
    }

    private companion object {
        /** La ruta con la forma que exige M6: `exam-covers/<examen_id>/<uuid>.<ext>`. */
        const val COVER_PATH = "exam-covers/EX-INM/1b4e28ba-2fa1-4d3b-a3f5-ef19b5a7633b.png"

        /** Desde PW-8 el backend sirve una URL FIRMADA del bucket privado (K5-3), no una pública. */
        const val COVER_URL = "https://stub.supabase.test/storage/v1/object/sign/exam-resources/$COVER_PATH?token=firma"

        /** Una pregunta inmersiva con un recurso de cada tipo vivo del CHECK y uno desconocido (KQ0-6). */
        val PREGUNTA_CON_RECURSOS = """
        {"data":[{"id":"P-INM","titulo":"Inmersiva","tipoPregunta":"inmersiva","posicion":1,"recursos":[
          {"id":"R-IMG","preguntaId":"P-INM","tipoRecurso":"imagen","titulo":"Imagen","urlExterna":"https://x/i.png","posicion":1},
          {"id":"R-AUD","preguntaId":"P-INM","tipoRecurso":"audio","titulo":"Audio","urlExterna":"https://x/a.mp3","posicion":2},
          {"id":"R-VID","preguntaId":"P-INM","tipoRecurso":"video","titulo":"Vídeo","urlExterna":"https://x/v.mp4","posicion":3},
          {"id":"R-TXT","preguntaId":"P-INM","tipoRecurso":"texto","titulo":"Texto","contenidoTexto":"Lee esto","posicion":4},
          {"id":"R-PDF","preguntaId":"P-INM","tipoRecurso":"pdf","titulo":"Temario","urlExterna":"https://x/t.pdf","posicion":5},
          {"id":"R-ENL","preguntaId":"P-INM","tipoRecurso":"enlace","titulo":"Enlace","urlExterna":"https://x/","posicion":6},
          {"id":"R-RARO","preguntaId":"P-INM","tipoRecurso":"holograma","titulo":"Raro","urlExterna":"https://x/h","posicion":7}
        ]}]}
        """.trimIndent()

        val ESPECIALIDADES = """{"data":[{"id":"ESP","nombre":"Enfermería","activa":true}]}"""

        /** Respuesta de `/capacidades` con los cinco flags EXPLÍCITOS, como los manda el backend. */
        val CAPACIDADES_TODO_ABIERTO = """
        {"data":{"especialidadId":"ESP","permiteRafagas":true,"permiteRazonados":true,
         "permiteInmersivos":true,"permiteDemos":true,"permiteOposimarket":true,"configurado":true}}
        """.trimIndent()

        /**
         * Cuatro preguntas: un tipo canónico, el alias legacy, otro canónico y
         * uno que nadie reconoce.
         *
         * **No hay un caso «sin campo», y no es un olvido**: el DTO declara
         * `tipoPregunta = "rafaga"` por defecto, así que una pregunta sin el
         * campo llega exactamente igual que `P-RAF`. Un test que lo «comprobara»
         * sólo estaría comprobando el valor por defecto del DTO.
         */
        val PREGUNTAS = """
        {"data":[
          {"id":"P-RAF","titulo":"Ráfaga","tipoPregunta":"rafaga","posicion":1},
          {"id":"P-GUI","titulo":"Guiada legacy","tipoPregunta":"guiada","posicion":2},
          {"id":"P-INM","titulo":"Inmersiva","tipoPregunta":"inmersiva","posicion":3},
          {"id":"P-RARO","titulo":"Desconocida","tipoPregunta":"marciana","posicion":4}
        ]}
        """.trimIndent()

        val EXAMENES_CON_PORTADA = """
        {"data":[
          {"id":"EX-INM","especialidadId":"ESP","titulo":"Inmersivo","tipoExamen":"inmersivo","totalPreguntas":5,
           "portadaUrl":"$COVER_URL","portadaStoragePath":"$COVER_PATH","portadaMimeType":"image/png"}
        ]}
        """.trimIndent()

        /**
         * Los cinco casos que importan: el tipo que sí se veía, el que
         * desaparecía, el otro que sí se veía, el alias legacy y un tipo que
         * nadie reconoce.
         */
        /** Un examen archivado, tal y como lo devuelve el backend tras archivar. */
        val UN_EXAMEN = """
        {"data":{"id":"EX-RAF","especialidadId":"ESP","titulo":"Ráfaga","tipoExamen":"rafaga",
          "totalPreguntas":10,"activo":false,"publicadoEn":null}}
        """.trimIndent()

        val EXAMENES = """
        {"data":[
          {"id":"EX-RAF","especialidadId":"ESP","titulo":"Ráfaga","tipoExamen":"rafaga","totalPreguntas":10},
          {"id":"EX-RAZ","especialidadId":"ESP","titulo":"Razonado","tipoExamen":"razonado"},
          {"id":"EX-INM","especialidadId":"ESP","titulo":"Inmersivo","tipoExamen":"inmersivo","totalPreguntas":5},
          {"id":"EX-GUI","especialidadId":"ESP","titulo":"Guiado legacy","tipoExamen":"guiado","totalPreguntas":3},
          {"id":"EX-RARO","especialidadId":"ESP","titulo":"Desconocido","tipoExamen":"marciano","totalPreguntas":1}
        ]}
        """.trimIndent()
    }
}
