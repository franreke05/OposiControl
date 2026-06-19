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
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamResourceSummary
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceAudit
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class KtorAdminV1ContentRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
    private val fileReader: PlatformFileReader,
) : ContentRepository {
    private val specialtiesFlow = MutableStateFlow<List<Specialty>>(emptyList())
    private val examsFlow = MutableStateFlow<List<Exam>>(emptyList())
    private val rafagasFlow = MutableStateFlow<List<Rafaga>>(emptyList())

    override fun observeSpecialties(): Flow<List<Specialty>> = specialtiesFlow.asStateFlow()

    override fun observeExams(): Flow<List<Exam>> = examsFlow.asStateFlow()

    override fun observeRafagas(): Flow<List<Rafaga>> = rafagasFlow.asStateFlow()

    override suspend fun refreshSpecialties() = adapter.safeCall {
        val specialties = getEspecialidades()
        val exams = getExamenes()
        val linkedCounts = exams.groupingBy { it.especialidadId }.eachCount()
        specialties.map { it.toDomain(linkedExams = linkedCounts[it.id] ?: 0) }
            .also { specialtiesFlow.value = it }
    }

    override suspend fun refreshExams() = adapter.safeCall {
        refreshExamCaches().first
    }

    override suspend fun refreshRafagas() = adapter.safeCall {
        refreshExamCaches().second
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

    override suspend fun upsertExam(exam: Exam) = adapter.safeCall {
        upsertExamDto(exam.toDto(typeExam = "inmersivo"), exam.status).toExam(findSpecialtyName(exam.specialtyId))
            .also { refreshExamCaches() }
    }

    override suspend fun deleteExam(examId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Examen(examId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        refreshExamCaches()
        Unit
    }

    override suspend fun upsertRafaga(rafaga: Rafaga) = adapter.safeCall {
        upsertExamDto(rafaga.toDto(), rafaga.status).toRafaga(findSpecialtyName(rafaga.specialtyId))
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
        val body = question.toDto(typePregunta = questionTypeForExam(question.examId))
            .copy(especialidadId = findExamSpecialtyId(question.examId))
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
        response.body<AdminPreguntaResponse>().data.toExamQuestion(question.examId)
            .also { getExamQuestions(question.examId) }
    }

    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> = adapter.safeCall {
        val examType = findExamType(examId)
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
        }.requireBackendSuccess(importEndpoint)
        getAdminQuestions(examId).map { it.toExamQuestion(examId) }
    }

    override suspend fun deleteQuestion(examId: String, questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        client.delete(adminUrl(endpoint)) {
            adminHeaders()
            expectSuccess = false
        }.requireBackendSuccess(endpoint)
        getExamQuestions(examId)
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
            especialidadId = findExamSpecialtyId(question.rafagaId),
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
            require(asset.bytes.size <= ADMIN_V1_COVER_MAX_BYTES) { "La portada supera el limite de 10 MB." }
            require(asset.mimeType?.startsWith("image/") == true) { "La portada debe ser una imagen." }
            val endpoint = BackendRoutes.adminV1ExamenPortada(examId)
            val response = client.post(adminUrl(endpoint)) {
                adminHeaders()
                expectSuccess = false
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
            dto.toExam(specialty?.name.orEmpty()).also { exam ->
                examsFlow.value = examsFlow.value.map { if (it.id == exam.id) exam else it }
            }
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
            dto.toExam(specialty?.name.orEmpty()).also { exam ->
                examsFlow.value = examsFlow.value.map { if (it.id == exam.id) exam else it }
            }
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

    private suspend fun refreshExamCaches(): Pair<List<Exam>, List<Rafaga>> {
        val specialties = specialtiesFlow.value.ifEmpty {
            getEspecialidades().map { it.toDomain() }.also { specialtiesFlow.value = it }
        }
        val specialtyNames = specialties.associate { it.id to it.name }
        val dtos = getExamenes()
        val exams = dtos.filter { it.tipoExamen == "inmersivo" }
            .map { it.toExam(specialtyNames[it.especialidadId].orEmpty()) }
        val rafagas = dtos.filter { it.tipoExamen == "rafaga" }
            .map { it.toRafaga(specialtyNames[it.especialidadId].orEmpty()) }
        examsFlow.value = exams
        rafagasFlow.value = rafagas
        return exams to rafagas
    }

    private suspend fun upsertExamDto(dto: AdminExamenDto, status: ExamStatus): AdminExamenDto {
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
                setBody(dto)
                expectSuccess = false
            }
        }
        response.requireBackendSuccess(endpoint)
        val saved = response.body<AdminExamenResponse>().data
        return when (status) {
            ExamStatus.PUBLISHED -> postExamState(saved.id, BackendRoutes.adminV1ExamenPublicar(saved.id))
            ExamStatus.DRAFT -> if (saved.publicadoEn != null) {
                postExamState(saved.id, BackendRoutes.adminV1ExamenDespublicar(saved.id))
            } else {
                saved
            }
            ExamStatus.ARCHIVED -> {
                val archiveEndpoint = BackendRoutes.adminV1Examen(saved.id)
                client.delete(adminUrl(archiveEndpoint)) {
                    adminHeaders()
                    expectSuccess = false
                }.requireBackendSuccess(archiveEndpoint)
                saved.copy(activo = false, publicadoEn = null)
            }
        }
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

    private suspend fun findExamType(examId: String): String =
        getExamenes().firstOrNull { it.id == examId }?.tipoExamen ?: "inmersivo"

    private suspend fun findExamSpecialtyId(examId: String): String =
        getExamenes().firstOrNull { it.id == examId }?.especialidadId.orEmpty()

    private suspend fun questionTypeForExam(examId: String): String =
        if (findExamType(examId) == "inmersivo") "inmersiva" else "rafaga"

    private fun updateExamQuestionCount(examId: String, count: Int) {
        examsFlow.value = examsFlow.value.map { exam ->
            if (exam.id == examId) exam.copy(questionCount = count) else exam
        }
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
    val totalPreguntas: Int = 1,
    val duracionMinutos: Int? = null,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
)

@Serializable
private data class AdminPreguntaDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val especialidadId: String = "",
    val tipoPregunta: String = "rafaga",
    val titulo: String = "",
    val fuenteTexto: String = "",
    val dificultad: String = "media",
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
    val preguntaId: String? = null,
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
    typeExam = tipoExamen,
    typeAccess = tipoAcceso,
    priceTickets = precioTickets,
    status = toStatus(),
    publishFrom = null,
    publishedAt = publicadoEn.toInstantOrNull(),
    sortOrder = anio ?: 0,
    questionCount = totalPreguntas,
    durationMinutes = duracionMinutos,
    year = anio,
    isOfficial = esOficial,
    coverUrl = portadaUrl,
    coverStoragePath = portadaStoragePath,
    coverMimeType = portadaMimeType,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
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
    questionCount = totalPreguntas,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
    deletedAt = if (activo) null else nowInstant(),
)

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
    totalPreguntas = questionCount.coerceAtLeast(1),
    duracionMinutos = durationMinutes,
)

private fun Rafaga.toDto() = AdminExamenDto(
    id = id,
    especialidadId = specialtyId,
    titulo = title,
    tipoExamen = "rafaga",
    tipoAcceso = if (priceTickets > 0) "premium" else "gratuito",
    activo = status != ExamStatus.ARCHIVED,
    publicadoEn = publishedAt?.toString(),
    precioTickets = priceTickets,
    totalPreguntas = questionCount.coerceAtLeast(1),
)

private fun AdminExamenDto.toStatus(): ExamStatus = when {
    !activo -> ExamStatus.ARCHIVED
    publicadoEn != null -> ExamStatus.PUBLISHED
    else -> ExamStatus.DRAFT
}

private fun AdminPreguntaDto.toExamQuestion(examId: String) = ExamQuestion(
    id = id,
    examId = examenId ?: examId,
    title = codigoPregunta.ifBlank { "Pregunta ${posicion ?: 0}" },
    statement = titulo,
    sortOrder = (posicion ?: 1) - 1,
    options = opciones.mapIndexed { index, option -> option.toDomain(id, index) }.sortedBy { it.sortOrder },
    resources = recursos.map { it.toDomain() }.sortedBy { it.sortOrder },
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun AdminPreguntaDto.toRafagaQuestion(rafagaId: String) = RafagaQuestion(
    id = id,
    rafagaId = examenId ?: rafagaId,
    statement = fuenteTexto.ifBlank { titulo },
    sortOrder = (posicion ?: 1) - 1,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
    deletedAt = if (activa) null else nowInstant(),
)

private fun ExamQuestion.toDto(typePregunta: String) = AdminPreguntaDto(
    id = id,
    especialidadId = "",
    tipoPregunta = typePregunta,
    titulo = statement,
    fuenteTexto = statement,
    razonamiento = options.firstOrNull { it.isCorrect }?.content.orEmpty(),
    opciones = options.sortedBy { it.sortOrder }.map { it.toDto() },
    recursos = resources.sortedBy { it.sortOrder }.map { it.toDto() },
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

private fun QuestionResource.toDto() = AdminRecursoDto(
    id = id,
    preguntaId = questionId,
    tipoRecurso = type.toDtoType(),
    titulo = title,
    urlExterna = publicUrl ?: storagePath,
    contenidoTexto = textBody,
    posicion = sortOrder + 1,
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

private fun List<ExamQuestion>.toAudit(): ExamResourceAudit {
    val questionAudits = map { question ->
        QuestionResourceAudit(
            questionId = question.id,
            questionTitle = question.title,
            resourceCount = question.resources.size,
            pendingResourceCount = question.resources.count { it.type != QuestionResourceType.TEXT && it.publicUrl.isNullOrBlank() },
            hasLinkedResources = question.resources.isNotEmpty(),
        )
    }
    return ExamResourceAudit(
        summary = ExamResourceSummary(
            totalQuestions = size,
            questionsWithResources = questionAudits.count { it.hasLinkedResources },
            questionsWithoutResources = questionAudits.count { !it.hasLinkedResources },
            linkedResources = sumOf { it.resources.size },
            pendingResources = questionAudits.sumOf { it.pendingResourceCount },
            unlinkedResources = 0,
        ),
        questions = questionAudits,
        unlinkedResources = emptyList(),
    )
}

private fun String.toResourceType() = when (lowercase()) {
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "texto", "text" -> QuestionResourceType.TEXT
    "documento", "document" -> QuestionResourceType.DOCUMENT
    else -> QuestionResourceType.IMAGE
}

private fun QuestionResourceType.toDtoType() = when (this) {
    QuestionResourceType.IMAGE -> "imagen"
    QuestionResourceType.AUDIO -> "audio"
    QuestionResourceType.VIDEO -> "video"
    QuestionResourceType.TEXT -> "texto"
    QuestionResourceType.DOCUMENT -> "documento"
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
