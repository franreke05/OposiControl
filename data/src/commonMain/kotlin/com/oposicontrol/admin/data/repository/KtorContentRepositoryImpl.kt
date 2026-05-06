package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
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
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class KtorContentRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : ContentRepository {
    private val specialtiesFlow = MutableStateFlow<List<Specialty>>(emptyList())
    private val examsFlow = MutableStateFlow<List<Exam>>(emptyList())

    override fun observeSpecialties(): Flow<List<Specialty>> = specialtiesFlow.asStateFlow()

    override fun observeExams(): Flow<List<Exam>> = examsFlow.asStateFlow()

    override suspend fun refreshSpecialties() = adapter.safeCall {
        val response = client.get("${contentUrl()}/especialidades") {
            expectSuccess = false
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value} /content/especialidades: ${response.bodyAsText().take(200)}")
        }
        response.body<List<EspecialidadDto>>()
            .map { it.toDomain() }
            .also { specialtiesFlow.value = it }
    }

    override suspend fun refreshExams() = adapter.safeCall {
        val specialtiesById = specialtiesFlow.value.associateBy { it.id }
        val response = client.get("${contentUrl()}/solucionarios") {
            expectSuccess = false
            backendHeaders()
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value} /content/solucionarios: ${response.bodyAsText().take(200)}")
        }
        response.body<List<SolucionarioDto>>()
            .map { it.toDomain(specialtiesById[it.especialidadId]?.name ?: it.descripcion) }
            .also { examsFlow.value = it }
    }

    override suspend fun getExamQuestions(examId: String) = adapter.safeCall {
        client.get("${contentUrl()}/solucionarios/$examId/preguntas") { backendHeaders() }
            .body<List<PreguntaDto>>()
            .map { it.toDomain() }
    }

    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> {
        return when (val result = getExamQuestions(examId)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(result.value.toAudit())
        }
    }

    override suspend fun upsertSpecialty(specialty: Specialty) = adapter.safeCall {
        val saved: EspecialidadDto = client.requestWithBody(
            url = "${contentUrl()}/especialidades",
            create = specialty.id.isBlank(),
            body = specialty.toDto(),
        )
        saved.toDomain().also { refreshSpecialties() }
    }

    override suspend fun deleteSpecialty(specialtyId: String) = adapter.safeCall {
        client.delete("${contentUrl()}/especialidades/$specialtyId") { backendHeaders() }
        refreshSpecialties()
        Unit
    }

    override suspend fun upsertExam(exam: Exam) = adapter.safeCall {
        val saved: SolucionarioDto = client.requestWithBody(
            url = "${contentUrl()}/solucionarios",
            create = exam.id.isBlank(),
            body = exam.toDto(),
        )
        saved.toDomain(exam.specialtyName).also {
            refreshExams()
            refreshSpecialties()
        }
    }

    override suspend fun deleteExam(examId: String) = adapter.safeCall {
        client.delete("${contentUrl()}/solucionarios/$examId") { backendHeaders() }
        refreshExams()
        refreshSpecialties()
        Unit
    }

    override suspend fun upsertQuestion(question: ExamQuestion) = adapter.safeCall {
        val saved: PreguntaDto = client.requestWithBody(
            url = "${contentUrl()}/solucionarios/${question.examId}/preguntas",
            create = question.id.isBlank(),
            body = question.toDto(),
        )
        saved.toDomain().also { refreshExams() }
    }

    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> {
        val saved = mutableListOf<ExamQuestion>()
        for (question in questions) {
            when (val result = upsertQuestion(question.copy(examId = examId))) {
                is AppResult.Failure -> return result
                is AppResult.Success -> saved += result.value
            }
        }
        return AppResult.Success(saved)
    }

    override suspend fun deleteQuestion(examId: String, questionId: String) = adapter.safeCall {
        client.delete("${contentUrl()}/solucionarios/$examId/preguntas/$questionId") { backendHeaders() }
        refreshExams()
        Unit
    }

    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ) = adapter.safeCall {
        val current = when (val result = getExamQuestions(examId)) {
            is AppResult.Failure -> throw IllegalStateException(result.error.message)
            is AppResult.Success -> result.value.sortedBy { it.sortOrder }
        }
        val index = current.indexOfFirst { it.id == questionId }
        val target = when (direction) {
            QuestionMoveDirection.UP -> index - 1
            QuestionMoveDirection.DOWN -> index + 1
        }
        if (index == -1 || target !in current.indices) return@safeCall current
        val ids = current.toMutableList().also {
            val item = it[index]
            it[index] = it[target]
            it[target] = item
        }.map { it.id }
        client.put("${contentUrl()}/solucionarios/$examId/preguntas/order") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(OrderDto(ids))
        }.body<List<PreguntaDto>>().map { it.toDomain() }
    }

    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = adapter.safeCall {
        client.post("${contentUrl()}/preguntas/$questionId/recursos") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(
                RecursoPreguntaDto(
                    id = resourceId,
                    preguntaId = questionId,
                    tipoRecurso = type.toDtoType(),
                    titulo = title.ifBlank { "Recurso" },
                    url = localPathOrUrl,
                    storagePath = localPathOrUrl.takeUnless { it.startsWith("http://") || it.startsWith("https://") },
                    publicUrl = localPathOrUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                ),
            )
        }.body<RecursoPreguntaDto>().toDomain()
    }

    private suspend inline fun <reified T, reified B> HttpClient.requestWithBody(
        url: String,
        create: Boolean,
        body: B,
    ): T {
        return if (create) {
            post(url) {
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        } else {
            put(url) {
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        }
    }

    private fun contentUrl(): String = "${appConfig.baseUrl.trimEnd('/')}/content"

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() }
            ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

@Serializable
private data class EspecialidadDto(
    val id: String = "",
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
    val linkedExams: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
private data class SolucionarioDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val descripcion: String = "",
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val orden: Int = 0,
)

@Serializable
private data class PreguntaDto(
    val id: String = "",
    val solucionarioId: String = "",
    val titulo: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val opciones: List<OpcionDto> = emptyList(),
    val recursos: List<RecursoPreguntaDto> = emptyList(),
)

@Serializable
private data class OpcionDto(
    val id: String = "",
    val label: String,
    val contenido: String,
    val esCorrecta: Boolean,
    val orden: Int,
)

@Serializable
private data class RecursoPreguntaDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String,
    val titulo: String = "",
    val url: String = "",
    val storagePath: String? = null,
    val publicUrl: String? = null,
    val orden: Int = 0,
)

@Serializable
private data class OrderDto(val ids: List<String>)

private fun EspecialidadDto.toDomain() = Specialty(
    id = id,
    name = nombre,
    description = slug,
    isActive = activa,
    sortOrder = id.toIntOrNull() ?: 0,
    linkedExams = linkedExams,
    createdAt = createdAt.toInstantOrNow(),
    updatedAt = updatedAt.toInstantOrNow(),
)

private fun Specialty.toDto() = EspecialidadDto(
    id = id,
    nombre = name,
    slug = description.ifBlank { name.toSlug() },
    activa = isActive,
    linkedExams = linkedExams,
)

private fun SolucionarioDto.toDomain(specialtyName: String) = Exam(
    id = id,
    specialtyId = especialidadId,
    specialtyName = specialtyName.ifBlank { descripcion },
    title = titulo,
    description = descripcion,
    status = estado.toExamStatus(),
    publishFrom = null,
    sortOrder = orden,
    questionCount = 0,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun Exam.toDto() = SolucionarioDto(
    id = id,
    titulo = title,
    especialidadId = specialtyId,
    descripcion = description,
    precioTickets = 0,
    estado = status.name.lowercase(),
    orden = sortOrder,
)

private fun PreguntaDto.toDomain() = ExamQuestion(
    id = id,
    examId = solucionarioId,
    title = titulo,
    statement = enunciado,
    sortOrder = orden,
    options = opciones.map { it.toDomain(id) }.sortedBy { it.sortOrder },
    resources = recursos.map { it.toDomain() }.sortedBy { it.sortOrder },
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun ExamQuestion.toDto() = PreguntaDto(
    id = id,
    solucionarioId = examId,
    titulo = title,
    enunciado = statement,
    orden = sortOrder,
    opciones = options.map { it.toDto() },
    recursos = resources.map { it.toDto() },
)

private fun OpcionDto.toDomain(questionId: String) = QuestionOption(
    id = id,
    questionId = questionId,
    label = label,
    content = contenido,
    isCorrect = esCorrecta,
    sortOrder = orden,
)

private fun QuestionOption.toDto() = OpcionDto(
    id = id,
    label = label,
    contenido = content,
    esCorrecta = isCorrect,
    orden = sortOrder,
)

private fun RecursoPreguntaDto.toDomain() = QuestionResource(
    id = id,
    questionId = preguntaId,
    type = tipoRecurso.toResourceType(),
    title = titulo.ifBlank { url.substringAfterLast('/') },
    textBody = if (tipoRecurso.toResourceType() == QuestionResourceType.TEXT) url else null,
    storagePath = storagePath,
    publicUrl = publicUrl ?: url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
    mimeType = null,
    sortOrder = orden,
)

private fun QuestionResource.toDto() = RecursoPreguntaDto(
    id = id,
    preguntaId = questionId,
    tipoRecurso = type.toDtoType(),
    titulo = title,
    url = publicUrl ?: storagePath ?: textBody.orEmpty(),
    storagePath = storagePath,
    publicUrl = publicUrl,
    orden = sortOrder,
)

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

private fun String.toExamStatus() = when (lowercase()) {
    "published" -> ExamStatus.PUBLISHED
    "archived" -> ExamStatus.ARCHIVED
    else -> ExamStatus.DRAFT
}

private fun String.toResourceType() = when (lowercase()) {
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "documento", "document" -> QuestionResourceType.DOCUMENT
    "text" -> QuestionResourceType.TEXT
    else -> QuestionResourceType.IMAGE
}

private fun QuestionResourceType.toDtoType() = when (this) {
    QuestionResourceType.IMAGE -> "imagen"
    QuestionResourceType.AUDIO -> "audio"
    QuestionResourceType.VIDEO -> "video"
    QuestionResourceType.TEXT -> "documento"
    QuestionResourceType.DOCUMENT -> "documento"
}

private fun String.toSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "item" }

private fun String.toInstantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrElse { nowInstant() }

private fun nowInstant(): Instant = Instant.parse(Clock.System.now().toString())
