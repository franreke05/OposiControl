package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.data.storage.PlatformFileReader
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportDuplicate
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.canonicalExamType
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamType
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.model.toAudit
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.ContentImportRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

class KtorAdminV1ContentRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
    private val fileReader: PlatformFileReader,
) : ContentRepository, ContentImportRepository {
    private val specialtiesFlow = MutableStateFlow<List<Specialty>>(emptyList())
    private val allExamsFlow = MutableStateFlow<List<Exam>>(emptyList())
    private val examsFlow = MutableStateFlow<List<Exam>>(emptyList())
    private val rafagasFlow = MutableStateFlow<List<Rafaga>>(emptyList())

    override fun observeSpecialties(): Flow<List<Specialty>> = specialtiesFlow.asStateFlow()

    override fun observeAllExams(): Flow<List<Exam>> = allExamsFlow.asStateFlow()

    override fun observeExams(): Flow<List<Exam>> = examsFlow.asStateFlow()

    override fun observeRafagas(): Flow<List<Rafaga>> = rafagasFlow.asStateFlow()

    override suspend fun refreshAllExams() = adapter.safeCall {
        refreshExamCaches().all
    }

    /**
     * K0-5 (EX-13): los recuentos por especialidad salen de la caché de exámenes si
     * ya está llena, sin un `GET /examenes` propio. Abrir la pantalla pedía el
     * catálogo entero CUATRO veces; ahora una.
     */
    override suspend fun refreshSpecialties() = adapter.safeCall {
        val specialties = getEspecialidades()
        val exams = allExamsFlow.value.ifEmpty {
            // Caché vacía: la descarga de exámenes necesita los nombres de las
            // especialidades, y acaban de llegar. Sin esto los pediría otra vez.
            specialtiesFlow.value = specialties.map { it.toDomain() }
            refreshExamCaches().all
        }
        val linkedCounts = exams.groupingBy { it.specialtyId }.eachCount()
        specialties.map { it.toDomain(linkedExams = linkedCounts[it.id] ?: 0) }
            .also { specialtiesFlow.value = it }
    }

    override suspend fun refreshExams() = adapter.safeCall {
        refreshExamCaches().exams
    }

    override suspend fun refreshRafagas() = adapter.safeCall {
        refreshExamCaches().rafagas
    }

    override suspend fun getExamQuestions(examId: String) = adapter.safeCall {
        getAdminQuestions(examId).map { it.toExamQuestion(examId) }
            .sortedBy { it.sortOrder }
            .also { questions -> updateExamQuestionCount(examId, questions.size) }
    }

    override suspend fun getRafagaQuestions(rafagaId: String) = adapter.safeCall {
        getAdminQuestions(rafagaId).map { it.toRafagaQuestion(rafagaId) }
            .sortedBy { it.sortOrder }
            .also { questions -> updateRafagaQuestionCount(rafagaId, questions.size) }
    }

    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> = adapter.safeCall {
        getAdminQuestions(examId).map { it.toExamQuestion(examId) }.toAudit()
    }

    override suspend fun upsertSpecialty(specialty: Specialty) = adapter.safeCall {
        val endpoint = if (specialty.id.isBlank()) {
            BackendRoutes.ADMIN_V1_ESPECIALIDADES
        } else {
            "${BackendRoutes.ADMIN_V1_ESPECIALIDADES}/${specialty.id}"
        }
        val response = if (specialty.id.isBlank()) {
            client.post(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(specialty.toDto())
                expectSuccess = false
            }
        } else {
            client.patch(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(specialty.toDto())
                expectSuccess = false
            }
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminEspecialidadResponse>().data
            .toDomain(linkedExams = specialty.linkedExams)
            .also { refreshSpecialties() }
    }

    override suspend fun deleteSpecialty(specialtyId: String) = adapter.safeCall {
        val endpoint = "${BackendRoutes.ADMIN_V1_ESPECIALIDADES}/$specialtyId"
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        refreshSpecialties()
        Unit
    }

    override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> =
        adapter.safeCall {
            val endpoint = "${BackendRoutes.ADMIN_V1_ESPECIALIDADES}/$specialtyId/capacidades"
            val response = client.get(adminUrl(endpoint)) {
                adminHeaders()
                expectSuccess = false
            }
            response.requireBackendSuccess(endpoint)
            response.body<AdminEspecialidadCapacidadesResponse>().data.toDomain()
        }

    override suspend fun saveSpecialtyCapabilities(
        capabilities: SpecialtyCapabilities,
    ): AppResult<SpecialtyCapabilities> = adapter.safeCall {
        val endpoint = "${BackendRoutes.ADMIN_V1_ESPECIALIDADES}/${capabilities.specialtyId}/capacidades"
        // PUT: el cuerpo lleva el estado completo de los flags, así que una petición
        // parcial no puede dejar media configuración aplicada.
        val response = client.put(adminUrl(endpoint)) {
            adminHeaders()
            contentType(ContentType.Application.Json)
            setBody(capabilities.toDto())
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminEspecialidadCapacidadesResponse>().data.toDomain()
    }

    /**
     * Guardar el examen del escritorio: PATCH con sus claves explícitas (K-C01a) y
     * NADA más. No encadena publicar/despublicar/archivar (K0-2, DEBT-026): guardar
     * una errata de un examen publicado lo re-publicaba y re-sellaba la fecha, y el
     * «último que guarda gana» podía despublicar lo que otro acababa de publicar. La
     * visibilidad se cambia sólo con sus botones.
     *
     * El examen guardado se fusiona en las cachés sin volver a descargar el catálogo
     * (K0-5).
     */
    override suspend fun upsertExam(exam: Exam) = adapter.safeCall {
        upsertExamDto(
            exam.toDto(typeExam = canonicalExamType(exam.typeExam)),
            exam.status,
            patchBody = { it.toExamPatchBody() },
            chainLifecycle = false,
        ).toExam(findSpecialtyName(exam.specialtyId))
            .also { applySavedExam(it) }
    }

    override suspend fun publishExam(examId: String) = adapter.safeCall {
        val dto = postExamState(examId, BackendRoutes.adminV1ExamenPublicar(examId))
        dto.toExam(findSpecialtyName(dto.especialidadId)).also { applySavedExam(it) }
    }

    override suspend fun unpublishExam(examId: String) = adapter.safeCall {
        val dto = postExamState(examId, BackendRoutes.adminV1ExamenDespublicar(examId))
        dto.toExam(findSpecialtyName(dto.especialidadId)).also { applySavedExam(it) }
    }

    /**
     * Archiva por la ruta dedicada. Mismo patrón que [publishExam]/[unpublishExam]
     * porque es la misma clase de operación: un POST sin cuerpo que devuelve el
     * examen ya guardado.
     */
    override suspend fun archiveExam(examId: String) = adapter.safeCall {
        val dto = postExamState(examId, BackendRoutes.adminV1ExamenArchivar(examId))
        dto.toExam(findSpecialtyName(dto.especialidadId)).also { applySavedExam(it) }
    }

    // BORRA. El comentario que había aquí —«el nombre viene de la interfaz
    // histórica; el endpoint real archiva»— dejó de ser cierto: el DELETE borra
    // la fila, con la guarda de datos de usuario del servidor delante, y archivar
    // se pide por `archiveExam`.
    //
    // Un 409 `EXAM_HAS_USER_DATA` sale por `requireBackendSuccess` como
    // `IllegalStateException`, y `NetworkResultAdapter` lo convierte en
    // `AppError.Validation` con el código de dominio dentro: eso es lo que la
    // pantalla necesita para distinguir «no se puede» de «ha fallado».
    override suspend fun deleteExam(examId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Examen(examId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        // K0-5: se quita de las tres cachés en memoria, sin descargar el catálogo.
        allExamsFlow.value = allExamsFlow.value.filterNot { it.id == examId }
        examsFlow.value = examsFlow.value.filterNot { it.id == examId }
        rafagasFlow.value = rafagasFlow.value.filterNot { it.id == examId }
        Unit
    }

    /**
     * Guardado de ráfaga del MÓVIL. Con K-C01 el servidor sólo escribe las claves
     * presentes, así que este cuerpo manda las suyas explícitas (K-C01b) —sin ellas,
     * volver a precio 0 o a gratuito dejaría de guardarse—. Conserva el encadenado
     * del ciclo de vida (su pantalla no tiene botones propios) y la relectura del
     * catálogo: el camino móvil no se reescribe aquí.
     */
    override suspend fun upsertRafaga(rafaga: Rafaga) = adapter.safeCall {
        upsertExamDto(
            rafaga.toDto(),
            rafaga.status,
            patchBody = { it.toRafagaPatchBody() },
            chainLifecycle = true,
        ).toRafaga(findSpecialtyName(rafaga.specialtyId))
            .also { refreshExamCaches() }
    }

    override suspend fun deleteRafaga(rafagaId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Examen(rafagaId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        refreshExamCaches()
        Unit
    }

    override suspend fun upsertQuestion(question: ExamQuestion) = adapter.safeCall {
        val endpoint = if (isExistingQuestion(question.examId, question.id)) {
            BackendRoutes.adminV1Pregunta(question.id)
        } else {
            BackendRoutes.ADMIN_V1_PREGUNTAS
        }
        // K0-1 (EX-10): UN examen, por su ruta, y el tipo de pregunta que ese examen
        // admite de verdad. Antes eran dos `GET /examenes` completos por pregunta
        // guardada y un mapeo que convertía Razonado en `rafaga`, que la base rechaza.
        val exam = getExamen(question.examId)
        val body = question.toDto(typePregunta = questionTypeFor(exam.tipoExamen))
            .copy(especialidadId = exam.especialidadId)
        val response = if (endpoint == BackendRoutes.ADMIN_V1_PREGUNTAS) {
            client.post(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
                expectSuccess = false
            }
        } else {
            client.patch(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
                expectSuccess = false
            }
        }
        response.requireBackendSuccess(endpoint)
        // Sin relectura de la lista: el ViewModel ya recarga las preguntas tras guardar.
        response.body<AdminPreguntaResponse>().data.toExamQuestion(question.examId)
    }

    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> = adapter.safeCall {
        val examType = getExamen(examId).tipoExamen
        val createResponse = client.post(adminUrl(BackendRoutes.ADMIN_V1_IMPORTACIONES_PREGUNTAS)) {
            adminHeaders()
            contentType(ContentType.Application.Json)
            setBody(
                CrearLoteRequestDto(
                    examenId = examId,
                    nombreArchivo = "importacion-admin.txt",
                    tipoExamen = examType,
                    preguntas = questions.mapIndexed { index, question -> question.toStagingDto(index + 1) },
                ),
            )
            expectSuccess = false
        }
        createResponse.requireBackendSuccess(BackendRoutes.ADMIN_V1_IMPORTACIONES_PREGUNTAS)
        val loteId = createResponse.body<AdminLoteResponse>().data.id
        val importEndpoint = "/api/v1/admin/importaciones/$loteId/importar"
        client.post(adminUrl(importEndpoint)) {
            adminHeaders()
            expectSuccess = false
            // F-04: la misma publicación de lote que `publishImportBatch`. El de socket
            // se amplía también: el servidor no manda nada mientras publica, y un
            // `timeout { }` sólo con el de petición heredaría los 30 s de silencio del
            // cliente y cortaría igual.
            timeout {
                requestTimeoutMillis = ADMIN_V1_BATCH_PUBLISH_TIMEOUT_MILLIS
                socketTimeoutMillis = ADMIN_V1_BATCH_PUBLISH_TIMEOUT_MILLIS
            }
        }.requireBackendSuccess(importEndpoint)
        getAdminQuestions(examId).map { it.toExamQuestion(examId) }
    }

    override suspend fun listImportBatches(): AppResult<List<ContentImportBatch>> = adapter.safeCall {
        val response = client.get(adminUrl(BackendRoutes.ADMIN_V1_IMPORTACIONES_PREGUNTAS)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(BackendRoutes.ADMIN_V1_IMPORTACIONES_PREGUNTAS)
        response.body<AdminLotesResponse>().data.map { it.toDomain() }
    }

    override suspend fun getImportBatch(batchId: String): AppResult<ContentImportBatch> = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Importacion(batchId)
        val response = client.get(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminLoteResponse>().data.toDomain()
    }

    override suspend fun uploadPdfToStaging(
        examId: String,
        examType: String,
        fileUri: String,
        fileName: String,
    ): AppResult<ContentImportBatch> = adapter.safeCall {
        val path = fileUri.removePrefix("file://")
        val asset = when (val result = fileReader.read(path)) {
            is AppResult.Failure -> throw IllegalStateException(result.error.message)
            is AppResult.Success -> result.value
        }
        require(asset.bytes.size <= ADMIN_V1_IMPORT_MAX_BYTES) { "El PDF supera el límite de 20 MB." }
        val endpoint = BackendRoutes.ADMIN_V1_IMPORTACIONES_PREGUNTAS_PDF
        val response = client.post(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
            timeout {
                requestTimeoutMillis = ADMIN_V1_PDF_IMPORT_TIMEOUT_MILLIS
                // Subir 20 MB por una línea lenta puede dejar el socket sin
                // actividad más de 30 s entre paquetes: el de socket se amplía igual.
                socketTimeoutMillis = ADMIN_V1_PDF_IMPORT_TIMEOUT_MILLIS
            }
            setBody(MultiPartFormDataContent(formData {
                append("examenId", examId)
                append("tipoExamen", canonicalExamType(examType))
                append("file", asset.bytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${fileName.sanitizeMultipartFilename()}\"")
                    append(HttpHeaders.ContentType, "application/pdf")
                })
            }))
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminLoteResponse>().data.toDomain()
    }

    override suspend fun updateStaging(item: ContentImportStagingItem): AppResult<ContentImportStagingItem> = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1ImportacionStaging(item.id)
        val response = client.patch(adminUrl(endpoint)) {
            adminHeaders()
            contentType(ContentType.Application.Json)
            setBody(item.toDto())
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminStagingResponse>().data.toDomain()
    }

    /**
     * Publica con UNA llamada (KI3-1) y relee el lote de la respuesta.
     *
     * La respuesta trae `data` (el lote, misma forma que siempre) y `resultado`
     * (qué hizo esta llamada). Si la petición se corta por timeout, el lote pudo
     * publicarse igualmente: quien reintente tiene que RELEER el lote antes
     * (`getImportBatch`) y no volver a publicar a ciegas; el ViewModel lo hace.
     */
    override suspend fun publishImportBatch(batchId: String, confirmExamPublished: Boolean): AppResult<ContentImportBatch> =
        adapter.safeCall {
            val endpoint = BackendRoutes.adminV1ImportacionPublish(batchId)
            val response = client.post(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(AdminImportarLoteRequestDto(confirmarExamenPublicado = confirmExamPublished))
                expectSuccess = false
                // F-04: con 6 s el cliente abandonaba y un segundo clic publicaba otra vez.
                timeout {
                    requestTimeoutMillis = ADMIN_V1_BATCH_PUBLISH_TIMEOUT_MILLIS
                    socketTimeoutMillis = ADMIN_V1_BATCH_PUBLISH_TIMEOUT_MILLIS
                }
            }
            response.requireBackendSuccess(endpoint)
            response.body<AdminLoteImportResponse>().data.toDomain()
        }

    /**
     * Quita la pregunta de ESTE examen y la deja intacta en el banco.
     *
     * Llama a `DELETE /examenes/{examId}/preguntas/{questionId}`. Nunca puede
     * llamar a `DELETE /preguntas/{id}`: ése archiva la fila global y, siendo
     * `preguntas_examen` una M:N, borraría la pregunta de todos los exámenes
     * que la compartan. Hay un test que fija exactamente esa URL.
     */
    override suspend fun unlinkQuestionFromExam(examId: String, questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1ExamenPregunta(examId, questionId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        getExamQuestions(examId)
        Unit
    }

    /**
     * Archiva la pregunta en el banco (`activa=false`), en TODOS los exámenes.
     *
     * No recibe `examId` a propósito: el efecto no está acotado a un examen y la
     * firma no debe sugerir que sí.
     */
    override suspend fun archiveQuestion(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        Unit
    }

    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ) = adapter.safeCall {
        val current = getAdminQuestions(examId)
        val reordered = current.reorder(questionId, direction)
        postQuestionOrder(examId, reordered.map { it.id })
        reordered.map { it.toExamQuestion(examId) }
    }

    override suspend fun upsertRafagaQuestion(question: RafagaQuestion) = adapter.safeCall {
        val options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
            AdminOpcionDto(
                id = "",
                letra = label,
                texto = if (index == 0) question.statement else "Opcion $label",
                posicion = index + 1,
                esCorrecta = index == 0,
            )
        }
        val dto = AdminPreguntaDto(
            id = question.id,
            especialidadId = getExamen(question.rafagaId).especialidadId,
            tipoPregunta = "rafaga",
            titulo = question.statement,
            fuenteTexto = question.statement,
            razonamiento = "",
            opciones = options,
            examenId = question.rafagaId,
            posicion = question.sortOrder + 1,
        )
        val endpoint = if (isExistingQuestion(question.rafagaId, question.id)) {
            BackendRoutes.adminV1Pregunta(question.id)
        } else {
            BackendRoutes.ADMIN_V1_PREGUNTAS
        }
        val response = if (endpoint == BackendRoutes.ADMIN_V1_PREGUNTAS) {
            client.post(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(dto)
                expectSuccess = false
            }
        } else {
            client.patch(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(dto)
                expectSuccess = false
            }
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminPreguntaResponse>().data.toRafagaQuestion(question.rafagaId)
            .also { getRafagaQuestions(question.rafagaId) }
    }

    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        getRafagaQuestions(rafagaId)
        Unit
    }

    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ) = adapter.safeCall {
        val current = getAdminQuestions(rafagaId)
        val reordered = current.reorder(questionId, direction)
        postQuestionOrder(rafagaId, reordered.map { it.id })
        reordered.map { it.toRafagaQuestion(rafagaId) }
    }

    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = adapter.safeCall {
        val endpoint = if (isExistingResource(questionId, resourceId)) {
            BackendRoutes.adminV1PreguntaRecurso(questionId, resourceId)
        } else {
            BackendRoutes.adminV1PreguntaRecursos(questionId)
        }
        val dto = AdminRecursoDto(
            id = resourceId,
            preguntaId = questionId,
            tipoRecurso = type.toDtoType(),
            titulo = title,
            urlExterna = localPathOrUrl.takeIf { type != QuestionResourceType.TEXT },
            contenidoTexto = localPathOrUrl.takeIf { type == QuestionResourceType.TEXT },
            posicion = 1,
        )
        val response = if (endpoint.endsWith("/recursos")) {
            client.post(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(dto)
                expectSuccess = false
            }
        } else {
            client.patch(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(dto)
                expectSuccess = false
            }
        }
        response.requireBackendSuccess(endpoint)
        response.body<AdminRecursoResponse>().data.toDomain()
    }

    override suspend fun deleteResource(questionId: String, resourceId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaRecurso(questionId, resourceId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        Unit
    }

    override suspend fun uploadExamCover(examId: String, localPathOrUri: String): AppResult<Exam> =
        adapter.safeCall {
            val path = localPathOrUri.removePrefix("file://")
            val asset = when (val r = fileReader.read(path)) {
                is AppResult.Failure -> throw IllegalStateException(r.error.message)
                is AppResult.Success -> r.value
            }
            require(asset.bytes.size <= ADMIN_V1_COVER_MAX_BYTES) { "La portada supera el límite de 10 MB." }
            require(asset.mimeType?.startsWith("image/") == true) { "La portada debe ser una imagen." }
            val endpoint = BackendRoutes.adminV1ExamenPortada(examId)
            val response = client.post(adminUrl(endpoint)) {
                adminHeaders()
                expectSuccess = false
                // F-04: subida de hasta 10 MB más la escritura en Storage.
                timeout {
                    requestTimeoutMillis = ADMIN_V1_COVER_UPLOAD_TIMEOUT_MILLIS
                    socketTimeoutMillis = ADMIN_V1_COVER_UPLOAD_TIMEOUT_MILLIS
                }
                setBody(MultiPartFormDataContent(formData {
                    append("file", asset.bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${asset.fileName.sanitizeMultipartFilename()}\"")
                        append(HttpHeaders.ContentType, asset.mimeType)
                    })
                }))
            }
            response.requireBackendSuccess(endpoint)
            val dto = response.body<AdminExamenResponse>().data
            val specialty = specialtiesFlow.value.firstOrNull { it.id == dto.especialidadId }
            applyCoverChange(dto.toExam(specialty?.name.orEmpty()))
        }

    override suspend fun clearExamCover(examId: String): AppResult<Exam> =
        adapter.safeCall {
            val endpoint = "${BackendRoutes.adminV1ExamenPortada(examId)}"
            val response = client.delete(adminUrl(endpoint)) {
                adminHeaders()
                expectSuccess = false
            }
            response.requireBackendSuccess(endpoint)
            val dto = response.body<AdminExamenResponse>().data
            val specialty = specialtiesFlow.value.firstOrNull { it.id == dto.especialidadId }
            applyCoverChange(dto.toExam(specialty?.name.orEmpty()))
        }

    private suspend fun getEspecialidades(): List<AdminEspecialidadDto> {
        val endpoint = BackendRoutes.ADMIN_V1_ESPECIALIDADES
        val response = client.get(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        return response.body<AdminEspecialidadesResponse>().data
    }

    private suspend fun getExamenes(): List<AdminExamenDto> {
        val endpoint = BackendRoutes.ADMIN_V1_EXAMENES
        val response = client.get(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        return response.body<AdminExamenesResponse>().data
    }

    private suspend fun getAdminQuestions(examId: String): List<AdminPreguntaDto> {
        val endpoint = BackendRoutes.adminV1ExamenPreguntas(examId)
        val response = client.get(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        return response.body<AdminPreguntasResponse>().data
    }

    private data class ExamCaches(
        val all: List<Exam>,
        val exams: List<Exam>,
        val rafagas: List<Rafaga>,
    )

    /**
     * Rellena las tres cachés de examen a partir de una sola descarga.
     *
     * El reparto anterior era `filter { it.tipoExamen == "inmersivo" }` y
     * `filter { it.tipoExamen == "rafaga" }`: **un examen `razonado` no caía en
     * ningún cubo y desaparecía del panel** aunque estuviera bien guardado en
     * la base. Lo mismo le pasaba al alias legacy `guiado`.
     *
     * Ahora [allExamsFlow] es la fuente única y contiene TODO. Los dos cubos
     * legacy siguen existiendo porque el camino móvil los consume, pero ya no
     * pueden perder nada: `rafaga` a un lado y **todo lo demás** al otro, de
     * modo que cada examen aparece exactamente en uno.
     *
     * El tipo se canoniza con [ExamType.fromExternal], que devuelve null para lo
     * que no reconoce. Ese null se resuelve conservando el valor crudo en vez de
     * forzarlo a `rafaga` (que es lo que haría `canonicalExamType`): un tipo
     * desconocido debe verse tal cual y quedar del lado visible, nunca
     * reetiquetarse en silencio como algo que no es.
     */
    private suspend fun refreshExamCaches(): ExamCaches {
        val specialties = specialtiesFlow.value.ifEmpty {
            getEspecialidades().map { it.toDomain() }.also { specialtiesFlow.value = it }
        }
        val specialtyNames = specialties.associate { it.id to it.name }
        val canonical = getExamenes().map { dto ->
            dto.copy(tipoExamen = ExamType.fromExternal(dto.tipoExamen)?.wireValue ?: dto.tipoExamen)
        }
        val all = canonical.map { it.toExam(specialtyNames[it.especialidadId].orEmpty()) }
        val rafagas = canonical.filter { it.tipoExamen == ExamType.RAFAGA.wireValue }
            .map { it.toRafaga(specialtyNames[it.especialidadId].orEmpty()) }
        val exams = all.filterNot { it.typeExam == ExamType.RAFAGA.wireValue }

        allExamsFlow.value = all
        examsFlow.value = exams
        rafagasFlow.value = rafagas
        return ExamCaches(all = all, exams = exams, rafagas = rafagas)
    }

    /**
     * Crear (POST con el DTO) o guardar (PATCH con [patchBody]).
     *
     * [patchBody] decide QUÉ claves viajan en el PATCH. Desde C-01 el servidor sólo
     * escribe las presentes, así que cada editor manda EXPLÍCITAS las que posee —con
     * su valor, `false`, `0` o `null` incluidos— y ninguna más: el escritorio las
     * once de su formulario ([toExamPatchBody]) y la ráfaga del móvil sus cinco
     * ([toRafagaPatchBody]). Omitir una clave ya no escribe su default: la deja como
     * estaba.
     *
     * [chainLifecycle] encadena después la acción de ciclo de vida que corresponde a
     * [status]. Sólo lo pide el camino móvil de ráfagas; el escritorio no (K0-2,
     * DEBT-026): guardar no publica, no despublica y no archiva.
     */
    private suspend fun upsertExamDto(
        dto: AdminExamenDto,
        status: ExamStatus,
        patchBody: (AdminExamenDto) -> JsonObject,
        chainLifecycle: Boolean,
    ): AdminExamenDto {
        val endpoint = if (dto.id.isBlank()) BackendRoutes.ADMIN_V1_EXAMENES else BackendRoutes.adminV1Examen(dto.id)
        val response = if (dto.id.isBlank()) {
            client.post(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(dto)
                expectSuccess = false
            }
        } else {
            client.patch(adminUrl(endpoint)) {
                adminHeaders()
                contentType(ContentType.Application.Json)
                setBody(patchBody(dto))
                expectSuccess = false
            }
        }
        response.requireBackendSuccess(endpoint)
        val saved = response.body<AdminExamenResponse>().data
        if (!chainLifecycle) return saved
        return when (status) {
            ExamStatus.PUBLISHED -> postExamState(saved.id, BackendRoutes.adminV1ExamenPublicar(saved.id))
            ExamStatus.DRAFT -> if (saved.publicadoEn != null) {
                postExamState(saved.id, BackendRoutes.adminV1ExamenDespublicar(saved.id))
            } else {
                saved
            }
            // ARCHIVAR, y por la ruta de archivar.
            //
            // Esto llamaba a `DELETE /examenes/{id}`, que entonces archivaba. En
            // cuanto ese DELETE pasó a borrar de verdad, esta rama se convirtió en
            // «guardar un examen archivado lo borra»: un guardado rutinario sobre
            // una pestaña abierta en un archivado, sin confirmación y sin que
            // nadie hubiera pedido borrar. Lo fija
            // `savingAnArchivedExamArchivesItAndNeverDeletesIt`.
            ExamStatus.ARCHIVED -> {
                val archiveEndpoint = BackendRoutes.adminV1ExamenArchivar(saved.id)
                client.post(adminUrl(archiveEndpoint)) {
                    adminHeaders()
                    expectSuccess = false
                }.requireBackendSuccess(archiveEndpoint)
                saved.copy(activo = false, publicadoEn = null)
            }
        }
    }

    /**
     * Mete en las cachés el examen que devuelve el servidor, sin volver a descargar
     * el catálogo (K0-5, EX-13). Antes cada guardado y cada publicación pedían
     * `GET /examenes` entero —publicar, dos veces—.
     *
     * Toca las dos cachés que usa el escritorio. La de ráfagas del móvil no: su
     * camino conserva la relectura completa (ver [upsertRafaga]).
     */
    private fun applySavedExam(saved: Exam) {
        fun List<Exam>.merged(): List<Exam> =
            if (any { it.id == saved.id }) map { if (it.id == saved.id) saved else it } else listOf(saved) + this
        allExamsFlow.value = allExamsFlow.value.merged()
        examsFlow.value = allExamsFlow.value.filterNot { it.typeExam == ExamType.RAFAGA.wireValue }
    }

    private suspend fun postExamState(examId: String, endpoint: String): AdminExamenDto {
        val response = client.post(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        return response.body<AdminExamenResponse>().data
    }

    private suspend fun postQuestionOrder(examId: String, ids: List<String>) {
        val endpoint = BackendRoutes.adminV1ExamenPreguntasOrden(examId)
        client.patch(adminUrl(endpoint)) {
            adminHeaders()
            contentType(ContentType.Application.Json)
            setBody(AdminPreguntaOrdenRequestDto(ids))
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
    }

    private suspend fun isExistingQuestion(examId: String, questionId: String): Boolean =
        questionId.isNotBlank() && getAdminQuestions(examId).any { it.id == questionId }

    private suspend fun isExistingResource(questionId: String, resourceId: String): Boolean {
        if (resourceId.isBlank()) return false
        val endpoint = BackendRoutes.adminV1PreguntaRecursos(questionId)
        val response = client.get(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        return response.body<AdminRecursosResponse>().data.any { it.id == resourceId }
    }

    private suspend fun findSpecialtyName(specialtyId: String): String {
        return specialtiesFlow.value.firstOrNull { it.id == specialtyId }?.name
            ?: getEspecialidades().firstOrNull { it.id == specialtyId }?.nombre.orEmpty()
    }

    /** Un solo examen, por su ruta. Sustituye a dos `GET /examenes` completos por pregunta guardada (K0-1). */
    private suspend fun getExamen(examId: String): AdminExamenDto {
        val endpoint = BackendRoutes.adminV1Examen(examId)
        val response = client.get(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }
        response.requireBackendSuccess(endpoint)
        return response.body<AdminExamenResponse>().data
    }

    /**
     * El tipo de pregunta que admite un examen: el mismo emparejamiento que impone
     * `validar_pregunta_examen` en la base. Un tipo que no se reconoce NO se adivina
     * (EX-10): antes un examen no encontrado o desconocido caía a `inmersiva` y un
     * Razonado a `rafaga`, y la base lo rechazaba con un 500.
     */
    private fun questionTypeFor(examType: String): String = when (ExamType.fromExternal(examType)) {
        ExamType.RAFAGA -> QuestionType.RAFAGA.wireValue
        ExamType.RAZONADO -> QuestionType.RAZONADA.wireValue
        ExamType.INMERSIVO -> QuestionType.INMERSIVA.wireValue
        null -> error("El examen tiene un tipo que no se reconoce.")
    }

    /**
     * Refleja en caché el número de preguntas que se acaba de leer.
     *
     * Es sólo memoria del cliente: nunca se persiste. Desde M1 la base mantiene
     * `examenes.total_preguntas` con la MISMA definición (preguntas activas
     * vinculadas), así que esta cifra y la servida coinciden; se conserva para que
     * la tarjeta no parpadee entre la lectura del catálogo y la de la lista.
     */
    private fun updateExamQuestionCount(examId: String, count: Int) {
        fun List<Exam>.withCount() = map { exam ->
            if (exam.id == examId) exam.copy(questionCount = count) else exam
        }
        allExamsFlow.value = allExamsFlow.value.withCount()
        examsFlow.value = examsFlow.value.withCount()
    }

    /**
     * Refleja en caché un cambio de portada, en LAS DOS fuentes.
     *
     * Antes sólo tocaba [examsFlow], y el workspace no observa ése: observa
     * [allExamsFlow] (`observeAllExams`). El examen abierto seguía con la portada
     * anterior en el catálogo mientras el editor ya tenía la nueva, así que
     * `hasUnsavedExamChanges` anunciaba «Hay cambios sin guardar» justo después
     * de una subida correcta, y «Descartar» resucitaba (o hacía desaparecer) una
     * portada que el servidor ya había cambiado.
     *
     * Se copian SÓLO los tres campos de portada sobre el examen que ya estaba en
     * caché, en vez de sustituirlo entero por la respuesta. Cuando se escribió, la
     * respuesta traía un `total_preguntas` que nadie mantenía; desde M1 ya es dato,
     * pero cambiar una imagen sigue sin ser motivo para tocar nada más del examen.
     * Lo que se devuelve es ese mismo examen de caché, para que quien lo fusione
     * aguas arriba no reintroduzca nada viejo.
     */
    private fun applyCoverChange(saved: Exam): Exam {
        fun List<Exam>.withCover() = map { exam ->
            if (exam.id == saved.id) {
                exam.copy(
                    coverUrl = saved.coverUrl,
                    coverStoragePath = saved.coverStoragePath,
                    coverMimeType = saved.coverMimeType,
                )
            } else {
                exam
            }
        }
        allExamsFlow.value = allExamsFlow.value.withCover()
        examsFlow.value = examsFlow.value.withCover()
        return allExamsFlow.value.firstOrNull { it.id == saved.id } ?: saved
    }

    private fun updateRafagaQuestionCount(rafagaId: String, count: Int) {
        rafagasFlow.value = rafagasFlow.value.map { rafaga ->
            if (rafaga.id == rafagaId) rafaga.copy(questionCount = count) else rafaga
        }
    }

    private fun adminUrl(path: String): String = BackendRoutes.url(appConfig.baseUrl, path)

    private fun io.ktor.client.request.HttpRequestBuilder.adminHeaders() {
        adminAuthHeaders(appConfig, sessionLocalDataSource)
    }

    private suspend fun HttpResponse.requireBackendSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }
}

@Serializable
private data class AdminEspecialidadesResponse(val data: List<AdminEspecialidadDto>)

@Serializable
private data class AdminEspecialidadResponse(val data: AdminEspecialidadDto)

/** Contrato de `/api/v1/admin/especialidades/{id}/capacidades`. */
@Serializable
private data class AdminEspecialidadCapacidadesResponse(val data: AdminEspecialidadCapacidadesDto)

/**
 * Capacidades por especialidad, lado cliente.
 *
 * Dos decisiones que van juntas y que ya se rompieron una vez (ver
 * `KtorAdminV1ContentRepositoryImplTest.turningAModeOffTravelsExplicitlyInsteadOfBeingOmitted`):
 *
 * 1. **`@EncodeDefault(ALWAYS)` en los cinco flags.** El `Json` del cliente
 *    (`ApiClientFactory.kt`) no pone `encodeDefaults`, así que un flag que valiera
 *    igual que su default se omitía del PUT y el servidor lo rellenaba con el suyo.
 *    Apagar Razonados escribía `true`: el interruptor volvía solo.
 * 2. **Los defaults valen lo mismo que en el servidor** (`SpecialtyCapabilities.kt`):
 *    abierto. Así, si alguna vez llega un cuerpo sin la clave, se lee "abierto"
 *    igual que lo entiende el backend, en vez de pintar un modo cerrado que en
 *    realidad está abierto.
 *
 * Nada sincroniza automáticamente los defaults de los dos DTO, y por eso el
 * contrato se fija con un test a cada lado del cable en vez de con un comentario.
 */
@Serializable
private data class AdminEspecialidadCapacidadesDto(
    val especialidadId: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteRafagas: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteRazonados: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteInmersivos: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteDemos: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteOposimarket: Boolean = true,
    val mensajeLimitacion: String? = null,
    val actualizadoEn: String? = null,
    val configurado: Boolean = true,
)

private fun AdminEspecialidadCapacidadesDto.toDomain() = SpecialtyCapabilities(
    permiteRafagas = permiteRafagas,
    permiteRazonados = permiteRazonados,
    permiteInmersivos = permiteInmersivos,
    permiteDemos = permiteDemos,
    permiteOposimarket = permiteOposimarket,
    specialtyId = especialidadId,
    limitationMessage = mensajeLimitacion,
    configured = configurado,
)

private fun SpecialtyCapabilities.toDto() = AdminEspecialidadCapacidadesDto(
    especialidadId = specialtyId,
    permiteRafagas = permiteRafagas,
    permiteRazonados = permiteRazonados,
    permiteInmersivos = permiteInmersivos,
    permiteDemos = permiteDemos,
    permiteOposimarket = permiteOposimarket,
    mensajeLimitacion = limitationMessage?.takeIf { it.isNotBlank() },
)

@Serializable
private data class AdminExamenesResponse(val data: List<AdminExamenDto>)

@Serializable
private data class AdminExamenResponse(val data: AdminExamenDto)

@Serializable
private data class AdminPreguntasResponse(val data: List<AdminPreguntaDto>)

@Serializable
private data class AdminPreguntaResponse(val data: AdminPreguntaDto)

@Serializable
private data class AdminRecursoResponse(val data: AdminRecursoDto)

@Serializable
private data class AdminRecursosResponse(val data: List<AdminRecursoDto>)

@Serializable
private data class AdminLoteResponse(val data: AdminLoteDto)

/** `POST /importaciones/{loteId}/importar`: `data` conserva la forma del lote; `resultado` dice qué hizo ESTA llamada. */
@Serializable
private data class AdminLoteImportResponse(val data: AdminLoteDto, val resultado: AdminImportResultadoDto? = null)

@Serializable
private data class AdminImportResultadoDto(
    val ejecutado: Boolean = false,
    val nuevas: Int = 0,
    val vinculadas: Int = 0,
    val omitidas: Int = 0,
    val totalImportadas: Int = 0,
)

/** Con el `Json` del cliente (`encodeDefaults = false`) un `false` se omitiría: se fuerza para que la decisión viaje. */
@Serializable
private data class AdminImportarLoteRequestDto(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val confirmarExamenPublicado: Boolean = false,
)

@Serializable
private data class AdminLotesResponse(val data: List<AdminLoteDto>)

@Serializable
private data class AdminStagingResponse(val data: AdminStagingItemDto)

@Serializable
private data class AdminEspecialidadDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val slug: String = "",
    val descripcion: String = "",
    val activa: Boolean = true,
    val posicion: Int = 0,
)

@Serializable
private data class AdminExamenDto(
    val id: String = "",
    val codigoExamen: String = "",
    val especialidadId: String = "",
    val comunidadId: String? = null,
    val titulo: String = "",
    val descripcion: String = "",
    val tipoExamen: String = "rafaga",
    val tipoAcceso: String = "gratuito",
    val anio: Int? = null,
    val esOficial: Boolean = false,
    val activo: Boolean = true,
    val publicadoEn: String? = null,
    val precioTickets: Int = 0,
    /**
     * `null` = «no lo cambies». El panel NUNCA escribe este campo: ver
     * [toDto]. El default era `1`, que en una escritura convertía cualquier
     * examen en "1 pregunta".
     */
    val totalPreguntas: Int? = null,
    val duracionMinutos: Int? = null,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
    /**
     * Metadatos de sólo lectura. Backend `AdminExamenDto` los añade con default
     * `null`; en escritura `Exam.toDto()` no los asigna, así que
     * `encodeDefaults=false` los omite del JSON — nunca se envían, nunca pueden
     * sobreescribir la fecha real a null.
     */
    val creadoEn: String? = null,
    val actualizadoEn: String? = null,
)

@Serializable
private data class AdminPreguntaDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val especialidadId: String = "",
    /**
     * Siempre en el cable (K0-1). Con el default `"rafaga"` y el `Json` del cliente
     * sin `encodeDefaults`, una pregunta Ráfaga salía SIN tipo y sólo se guardaba bien
     * porque el default del servidor coincidía por casualidad: el tipo es lo que la
     * base valida contra el examen, y no puede depender de dos defaults sincronizados.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val tipoPregunta: String = "rafaga",
    val titulo: String = "",
    /**
     * `null` = no viaja, y el servidor lo lee como «no toques la fuente» (QN-17). Con
     * `String = ""` dependería de que el `Json` del cliente no codifique defaults: con
     * `encodeDefaults` un `""` vaciaría la fuente real en cada guardado.
     */
    val fuenteTexto: String? = null,
    /** Mismo default que el servidor (`""`, KQ0-2): un «media» inventado no puede leerse ni escribirse. */
    val dificultad: String = "",
    val activa: Boolean = true,
    val razonamiento: String = "",
    val opciones: List<AdminOpcionDto> = emptyList(),
    val recursos: List<AdminRecursoDto> = emptyList(),
    val examenId: String? = null,
    val posicion: Int? = null,
)

@Serializable
private data class AdminOpcionDto(
    val id: String = "",
    val letra: String = "",
    val texto: String = "",
    val posicion: Int = 0,
    val esCorrecta: Boolean = false,
)

@Serializable
private data class AdminRecursoDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String = "",
    val titulo: String = "",
    val descripcion: String = "",
    val urlExterna: String? = null,
    val contenidoTexto: String? = null,
    val posicion: Int = 0,
)

@Serializable
private data class AdminPreguntaOrdenRequestDto(val ids: List<String> = emptyList())

@Serializable
private data class CrearLoteRequestDto(
    val examenId: String,
    val nombreArchivo: String = "",
    val tipoExamen: String = "rafaga",
    val preguntas: List<AdminStagingItemDto> = emptyList(),
)

@Serializable
private data class AdminLoteDto(
    val id: String = "",
    val examenId: String = "",
    val nombreArchivo: String = "",
    val tipoExamen: String = "rafaga",
    val estado: String = "parseado",
    val totalDetectadas: Int = 0,
    val totalImportadas: Int = 0,
    val staging: List<AdminStagingItemDto> = emptyList(),
    /** `false` = la detección de duplicados no pudo ejecutarse (falta IM3); nunca se lee como «sin duplicados». */
    val duplicadosComprobados: Boolean = true,
)

@Serializable
private data class AdminStagingItemDto(
    val id: String = "",
    val posicion: Int = 0,
    val titulo: String = "",
    val opcionA: String = "",
    val opcionB: String = "",
    val opcionC: String = "",
    val opcionD: String = "",
    val respuestaCorrecta: String = "",
    val razonamiento: String = "",
    val fuenteTexto: String = "",
    val etiquetaTexto: String = "",
    val bloqueOriginal: String = "",
    val estadoParseo: String = "ok",
    val errorParseo: String? = null,
    val preguntaId: String? = null,
    /** La decisión «vincular con la pregunta X» (IM2, KI2). Sólo tiene sentido con `aprobada`. */
    val vincularPreguntaId: String? = null,
    val duplicado: AdminImportDuplicadoDto? = null,
)

@Serializable
private data class AdminImportDuplicadoDto(
    val origen: String = "banco",
    val preguntaId: String? = null,
    val codigoPregunta: String = "",
    val stagingOriginalId: String? = null,
)

private fun AdminLoteDto.toDomain() = ContentImportBatch(
    id = id,
    examId = examenId,
    fileName = nombreArchivo,
    examType = canonicalExamType(tipoExamen),
    status = estado,
    detectedCount = totalDetectadas,
    importedCount = totalImportadas,
    staging = staging.map { it.toDomain() },
    duplicatesChecked = duplicadosComprobados,
)

private fun AdminStagingItemDto.toDomain() = ContentImportStagingItem(
    id = id,
    position = posicion,
    title = titulo,
    optionA = opcionA,
    optionB = opcionB,
    optionC = opcionC,
    optionD = opcionD,
    correctAnswer = respuestaCorrecta,
    reasoning = razonamiento,
    sourceText = fuenteTexto,
    tagText = etiquetaTexto,
    originalBlock = bloqueOriginal,
    parseStatus = estadoParseo,
    parseError = errorParseo,
    questionId = preguntaId,
    linkQuestionId = vincularPreguntaId,
    duplicate = duplicado?.let {
        ContentImportDuplicate(
            origin = it.origen,
            questionId = it.preguntaId,
            questionCode = it.codigoPregunta,
            stagingOriginalId = it.stagingOriginalId,
        )
    },
)

private fun ContentImportStagingItem.toDto() = AdminStagingItemDto(
    id = id,
    posicion = position,
    titulo = title,
    opcionA = optionA,
    opcionB = optionB,
    opcionC = optionC,
    opcionD = optionD,
    respuestaCorrecta = correctAnswer,
    razonamiento = reasoning,
    fuenteTexto = sourceText,
    etiquetaTexto = tagText,
    bloqueOriginal = originalBlock,
    estadoParseo = parseStatus,
    errorParseo = parseError,
    preguntaId = questionId,
    vincularPreguntaId = linkQuestionId,
)

private fun AdminEspecialidadDto.toDomain(linkedExams: Int = 0) = Specialty(
    id = id,
    name = nombre,
    description = descripcion.ifBlank { slug },
    isActive = activa,
    sortOrder = posicion,
    linkedExams = linkedExams,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun Specialty.toDto() = AdminEspecialidadDto(
    id = id,
    nombre = name,
    descripcion = description,
    activa = isActive,
    posicion = sortOrder,
)

private fun AdminExamenDto.toExam(specialtyName: String) = Exam(
    id = id,
    code = codigoExamen,
    specialtyId = especialidadId,
    specialtyName = specialtyName,
    communityId = comunidadId,
    title = titulo,
    description = descripcion,
    // Canónico en TODOS los caminos, no sólo en `refreshExamCaches`. Guardar,
    // publicar, despublicar y cambiar la portada devuelven también un `Exam`, y el
    // VM lo fusiona en el catálogo: un `guiado` crudo ahí, frente al `razonado`
    // que el editor sembró, se leía como un cambio sin guardar que nadie hizo. Lo
    // desconocido se conserva tal cual, igual que en la caché.
    typeExam = ExamType.fromExternal(tipoExamen)?.wireValue ?: tipoExamen,
    typeAccess = tipoAcceso,
    priceTickets = precioTickets,
    status = toStatus(),
    publishFrom = null,
    publishedAt = publicadoEn.toInstantOrNull(),
    sortOrder = anio ?: 0,
    questionCount = totalPreguntas ?: 0,
    durationMinutes = duracionMinutos,
    year = anio,
    isOfficial = esOficial,
    coverUrl = portadaUrl,
    coverStoragePath = portadaStoragePath,
    coverMimeType = portadaMimeType,
    // El backend ya trae la fecha real (ver AdminExamenDto.creadoEn/actualizadoEn
    // en Ktor). Antes de exponerlos aquí, este mapper rellenaba los dos con
    // "ahora" incondicionalmente — así que "Creado" en la ficha de un examen de
    // hace un año habría mostrado la hora en la que alguien abrió la pantalla.
    // El fallback a nowInstant() sólo cubre el examen recién creado en esta
    // misma sesión, cuya respuesta aún podría no traer el valor si el backend
    // desplegado no tiene este cambio.
    createdAt = creadoEn.toInstantOrNull() ?: nowInstant(),
    updatedAt = actualizadoEn.toInstantOrNull() ?: nowInstant(),
    deletedAt = if (activo) null else nowInstant(),
)

private fun AdminExamenDto.toRafaga(specialtyName: String) = Rafaga(
    id = id,
    specialtyId = especialidadId,
    specialtyName = specialtyName,
    title = titulo,
    priceTickets = precioTickets,
    status = toStatus(),
    publishFrom = null,
    publishedAt = publicadoEn.toInstantOrNull(),
    questionCount = totalPreguntas ?: 0,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
    deletedAt = if (activo) null else nowInstant(),
)

/**
 * Examen de dominio → DTO de escritura.
 *
 * **`totalPreguntas` va siempre a null a propósito.** Guardar el examen enviaba
 * `questionCount.coerceAtLeast(1)`, y `questionCount` sale de la lista de
 * preguntas que el editor tenga cargada en memoria — que puede estar vacía o a
 * medias. Esa cifra no se quedaba en el panel: `AppV1Routes.iniciar` lee
 * `examenes.total_preguntas` para dimensionar `intentos_examen.total_preguntas`
 * de un usuario final, así que abrir un examen y pulsar Guardar podía
 * redimensionar los intentos de gente que está examinándose.
 *
 * De las opciones posibles —recalcular server-side, exigir confirmación, o no
 * escribir— se eligió **no escribir**, y M1 cerró la otra mitad: la base mantiene
 * la columna con triggers y neutraliza cualquier escritura desde la API. El
 * recuento es un hecho derivado de `preguntas_examen`, y el editor no es su
 * fuente de verdad. Si algún día hace falta un objetivo editable, es una columna
 * nueva (`preguntas_objetivo`), no ésta.
 */
private fun Exam.toDto(typeExam: String) = AdminExamenDto(
    id = id,
    codigoExamen = code,
    especialidadId = specialtyId,
    comunidadId = communityId,
    titulo = title,
    descripcion = description,
    tipoExamen = typeExam,
    tipoAcceso = typeAccess,
    anio = year,
    esOficial = isOfficial,
    activo = status != ExamStatus.ARCHIVED,
    publicadoEn = publishedAt?.toString(),
    precioTickets = priceTickets,
    totalPreguntas = null,
    duracionMinutos = durationMinutes,
)

/**
 * Cuerpo del PATCH de examen del ESCRITORIO con TODAS las claves que posee el
 * editor, explícitas (K-C01a).
 *
 * El servidor sólo escribe lo que viene (C-01): un `false`, un `0` o un `"rafaga"`
 * omitidos por valer su default —que es lo que hace el `Json` del cliente, sin
 * `encodeDefaults`— dejarían de guardarse. Las tres anulables viajan con su valor o
 * con `null` explícito (merge-patch: `null` = borrar). Nunca lleva visibilidad,
 * `totalPreguntas`, portada ni metadatos: el servidor los ignoraría, y mandarlos
 * sólo invitaría a creer que se guardan.
 */
private fun AdminExamenDto.toExamPatchBody(): JsonObject = buildJsonObject {
    put("codigoExamen", JsonPrimitive(codigoExamen))
    put("especialidadId", JsonPrimitive(especialidadId))
    put("comunidadId", JsonPrimitive(comunidadId))
    put("titulo", JsonPrimitive(titulo))
    put("descripcion", JsonPrimitive(descripcion))
    put("tipoExamen", JsonPrimitive(tipoExamen))
    put("tipoAcceso", JsonPrimitive(tipoAcceso))
    put("anio", JsonPrimitive(anio))
    put("esOficial", JsonPrimitive(esOficial))
    put("precioTickets", JsonPrimitive(precioTickets))
    put("duracionMinutos", JsonPrimitive(duracionMinutos))
}

/**
 * Las claves que posee el editor de ráfaga del MÓVIL, explícitas (K-C01b). Nunca las
 * tres anulables ni el código: ese editor no los conoce, y su AUSENCIA es lo que los
 * protege —con C-01, ausente = intacta—.
 */
private fun AdminExamenDto.toRafagaPatchBody(): JsonObject = buildJsonObject {
    put("especialidadId", JsonPrimitive(especialidadId))
    put("titulo", JsonPrimitive(titulo))
    put("tipoExamen", JsonPrimitive(tipoExamen))
    put("tipoAcceso", JsonPrimitive(tipoAcceso))
    put("precioTickets", JsonPrimitive(precioTickets))
}

/** Misma regla que [toDto]: el panel no escribe `total_preguntas`. */
private fun Rafaga.toDto() = AdminExamenDto(
    id = id,
    especialidadId = specialtyId,
    titulo = title,
    tipoExamen = "rafaga",
    tipoAcceso = if (priceTickets > 0) "premium" else "gratuito",
    activo = status != ExamStatus.ARCHIVED,
    publicadoEn = publishedAt?.toString(),
    precioTickets = priceTickets,
    totalPreguntas = null,
)

private fun AdminExamenDto.toStatus(): ExamStatus = when {
    !activo -> ExamStatus.ARCHIVED
    publicadoEn != null -> ExamStatus.PUBLISHED
    else -> ExamStatus.DRAFT
}

/**
 * Una pregunta del examen, con su tipo canonizado.
 *
 * [QuestionType.fromExternal] acepta el alias legacy (`guiada` → `RAZONADA`) y
 * devuelve `null` para cualquier otro valor: un tipo que nadie reconoce no se
 * reetiqueta como Ráfaga, que es la mentira fácil aquí.
 *
 * **Lo que este mapeo NO puede hacer, y no es un descuido:** distinguir una
 * pregunta que de verdad es Ráfaga de una que no trae el campo. [AdminPreguntaDto]
 * declara `tipoPregunta = "rafaga"` por defecto y el JSON del servidor no fija
 * `encodeDefaults`, así que el campo ausente llega como `"rafaga"` y es
 * indistinguible del valor real. Detectarlo pide un cambio de contrato; hasta
 * entonces, `null` significa «valor no reconocido» y nada más.
 */
private fun AdminPreguntaDto.toExamQuestion(examId: String) = ExamQuestion(
    id = id,
    examId = examenId ?: examId,
    title = codigoPregunta.ifBlank { "Pregunta ${posicion ?: 0}" },
    statement = titulo,
    sortOrder = (posicion ?: 1) - 1,
    type = QuestionType.fromExternal(tipoPregunta),
    options = opciones.mapIndexed { index, option -> option.toDomain(id, index) }.sortedBy { it.sortOrder },
    resources = recursos.map { it.toDomain() }.sortedBy { it.sortOrder },
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun AdminPreguntaDto.toRafagaQuestion(rafagaId: String) = RafagaQuestion(
    id = id,
    rafagaId = examenId ?: rafagaId,
    statement = fuenteTexto.orEmpty().ifBlank { titulo },
    sortOrder = (posicion ?: 1) - 1,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
    deletedAt = if (activa) null else nowInstant(),
)

/**
 * Pregunta del editor de Exámenes → cuerpo de escritura (QN-17).
 *
 * Sólo lo que ese editor EDITA de verdad: enunciado, tipo, opciones y vínculo con
 * el examen. Mandaba además `fuenteTexto = enunciado`, `razonamiento = texto de la
 * opción correcta` y `recursos[]` con `urlExterna = publicUrl ?: storagePath`, y
 * así corrompía en cada guardado campos que comparte con el banco de Preguntas: la
 * fuente real se sustituía por el enunciado, la explicación por la respuesta y, con
 * URL firmada, se habría persistido una URL que caduca.
 *
 * Ahora `fuenteTexto` no viaja (el servidor lee su ausencia como «no la toques»),
 * `razonamiento` vacío tampoco se escribe y `recursos[]` lo ignora el servidor
 * (KQ0-8): los recursos se gestionan por sus rutas.
 */
private fun ExamQuestion.toDto(typePregunta: String) = AdminPreguntaDto(
    id = id,
    especialidadId = "",
    tipoPregunta = typePregunta,
    titulo = statement,
    opciones = options.sortedBy { it.sortOrder }.map { it.toDto() },
    examenId = examId,
    posicion = sortOrder + 1,
)

private fun QuestionOption.toDto() = AdminOpcionDto(
    id = id,
    letra = label,
    texto = content,
    posicion = sortOrder + 1,
    esCorrecta = isCorrect,
)

private fun AdminOpcionDto.toDomain(questionId: String, index: Int) = QuestionOption(
    id = id,
    questionId = questionId,
    label = letra,
    content = texto,
    isCorrect = esCorrecta,
    sortOrder = if (posicion > 0) posicion - 1 else index,
)

private fun AdminRecursoDto.toDomain() = QuestionResource(
    id = id,
    questionId = preguntaId,
    type = tipoRecurso.toResourceType(),
    title = titulo,
    textBody = contenidoTexto,
    storagePath = null,
    publicUrl = urlExterna,
    mimeType = tipoRecurso.toResourceType().guessMime(),
    sortOrder = (posicion - 1).coerceAtLeast(0),
)

private fun ExamQuestion.toStagingDto(position: Int): AdminStagingItemDto {
    val options = options.sortedBy { it.sortOrder }
    return AdminStagingItemDto(
        posicion = position,
        titulo = statement,
        opcionA = options.getOrNull(0)?.content.orEmpty(),
        opcionB = options.getOrNull(1)?.content.orEmpty(),
        opcionC = options.getOrNull(2)?.content.orEmpty(),
        opcionD = options.getOrNull(3)?.content.orEmpty(),
        respuestaCorrecta = options.firstOrNull { it.isCorrect }?.label.orEmpty(),
        razonamiento = options.firstOrNull { it.isCorrect }?.content.orEmpty(),
        fuenteTexto = statement,
        bloqueOriginal = statement,
    )
}

private fun List<AdminPreguntaDto>.reorder(questionId: String, direction: QuestionMoveDirection): List<AdminPreguntaDto> {
    val mutable = sortedBy { it.posicion ?: Int.MAX_VALUE }.toMutableList()
    val index = mutable.indexOfFirst { it.id == questionId }
    if (index == -1) return mutable
    val target = when (direction) {
        QuestionMoveDirection.UP -> (index - 1).takeIf { it >= 0 }
        QuestionMoveDirection.DOWN -> (index + 1).takeIf { it < mutable.size }
    } ?: return mutable
    val item = mutable.removeAt(index)
    mutable.add(target, item)
    return mutable.mapIndexed { newIndex, question -> question.copy(posicion = newIndex + 1) }
}

/**
 * `recursos_pregunta.tipo_recurso` → tipo de dominio (KQ0-6, QN-01).
 *
 * El vocabulario vivo es `imagen, audio, video, texto, pdf, enlace` (CHECK de la
 * base). Antes `pdf` y `enlace` caían al `else` y se pintaban como IMAGEN. Un PDF y
 * un enlace se abren por URL, que es lo que hace DOCUMENT; lo que no se reconozca
 * también, porque abrirlo como imagen es lo único seguro que NO se puede hacer.
 */
private fun String.toResourceType() = when (trim().lowercase()) {
    "imagen" -> QuestionResourceType.IMAGE
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "texto" -> QuestionResourceType.TEXT
    "pdf", "enlace" -> QuestionResourceType.DOCUMENT
    else -> QuestionResourceType.DOCUMENT
}

/**
 * Tipo de dominio → `tipo_recurso` (KQ0-6). DOCUMENT es `pdf`: `documento` no está en
 * el CHECK de la base y cada alta de un documento fallaba con 23514.
 */
private fun QuestionResourceType.toDtoType() = when (this) {
    QuestionResourceType.IMAGE -> "imagen"
    QuestionResourceType.AUDIO -> "audio"
    QuestionResourceType.VIDEO -> "video"
    QuestionResourceType.TEXT -> "texto"
    QuestionResourceType.DOCUMENT -> "pdf"
}

private fun QuestionResourceType.guessMime() = when (this) {
    QuestionResourceType.IMAGE -> "image/png"
    QuestionResourceType.AUDIO -> "audio/mpeg"
    QuestionResourceType.VIDEO -> "video/mp4"
    QuestionResourceType.TEXT -> "text/plain"
    QuestionResourceType.DOCUMENT -> "application/pdf"
}

private fun String?.toInstantOrNull(): Instant? = this?.let { value ->
    runCatching { Instant.parse(value) }.getOrNull()
}

private fun nowInstant(): Instant = Instant.parse(Clock.System.now().toString())

private fun String.sanitizeMultipartFilename(): String = replace("\"", "").ifBlank { "portada.jpg" }

private const val ADMIN_V1_COVER_MAX_BYTES = 10 * 1024 * 1024
private const val ADMIN_V1_IMPORT_MAX_BYTES = 20 * 1024 * 1024

/*
 * F-04: presupuestos POR PETICIÓN de las operaciones que legítimamente tardan más
 * que el defecto del cliente (30 s). Con 6 s para todo, el cliente se rendía
 * mientras Ktor seguía trabajando, y el reintento del administrador creaba el lote
 * o las preguntas por segunda vez. Cada cifra cubre lo que el servidor puede
 * tardar de verdad, no un número redondo.
 */

/** PDF de hasta 20 MB: subida + PDFBox + creación del lote en staging. */
internal const val ADMIN_V1_PDF_IMPORT_TIMEOUT_MILLIS: Long = 180_000

/**
 * Publicar un lote: una escritura y cuatro lecturas POR FILA, secuenciales en el
 * servidor. Hasta que exista la RPC atómica, un lote grande tarda minutos.
 */
internal const val ADMIN_V1_BATCH_PUBLISH_TIMEOUT_MILLIS: Long = 300_000

/** Portada de hasta 10 MB: subida a Storage + actualización del examen. */
internal const val ADMIN_V1_COVER_UPLOAD_TIMEOUT_MILLIS: Long = 60_000
