package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.ExamRowDto
import com.oposicontrol.admin.data.remote.dto.OptionRowDto
import com.oposicontrol.admin.data.remote.dto.QuestionCountRowDto
import com.oposicontrol.admin.data.remote.dto.QuestionRowDto
import com.oposicontrol.admin.data.remote.dto.ResourceRowDto
import com.oposicontrol.admin.data.remote.dto.SpecialtyRowDto
import com.oposicontrol.admin.data.remote.dto.toDomain
import com.oposicontrol.admin.data.remote.dto.toRowDto
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamResourceSummary
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionResourceAudit
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.UnlinkedResource
import com.oposicontrol.admin.domain.model.UnlinkedResourceReason
import com.oposicontrol.admin.domain.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class SupabaseContentRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val fileReader: PlatformFileReader,
    private val adapter: NetworkResultAdapter,
) : ContentRepository {
    private val specialtiesFlow = MutableStateFlow<List<Specialty>>(emptyList())
    private val examsFlow = MutableStateFlow<List<Exam>>(emptyList())
    private val rafagasFlow = MutableStateFlow<List<Rafaga>>(emptyList())
    private var specialtyRowsCache: List<SpecialtyRowDto> = emptyList()

    override fun observeSpecialties(): Flow<List<Specialty>> = specialtiesFlow.asStateFlow()

    override fun observeExams(): Flow<List<Exam>> = examsFlow.asStateFlow()

    override fun observeRafagas(): Flow<List<Rafaga>> = rafagasFlow.asStateFlow()

    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> {
        val accessToken = requireAccessToken("cargar especialidades") ?: return authFailure("cargar especialidades")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            val rows = fetchSpecialtyRows(accessToken)
            specialtyRowsCache = rows
            val linkedCounts = examsFlow.value.groupingBy { it.specialtyId }.eachCount()
            rows.map { it.toDomain(linkedCounts[it.id?.toString().orEmpty()] ?: 0) }.also { specialtiesFlow.value = it }
        }
    }

    override suspend fun refreshExams(): AppResult<List<Exam>> {
        val accessToken = requireAccessToken("cargar examenes") ?: return authFailure("cargar examenes")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            if (specialtyRowsCache.isEmpty()) specialtyRowsCache = fetchSpecialtyRows(accessToken)
            val questionCounts = fetchQuestionCounts(accessToken).groupingBy { it.examId.toString() }.eachCount()
            val specialtyNames = specialtyRowsCache.associateBy({ it.id?.toString().orEmpty() }, { it.nombre })
            val rows: List<ExamRowDto> = client.get("${appConfig.supabaseUrl}/rest/v1/examen?select=*&deleted_at=is.null&order=created_at.desc") {
                supabaseHeaders(accessToken)
            }.body()
            rows.map { row ->
                row.toDomain(
                    specialtyName = specialtyNames[row.specialtyId.toString()] ?: "Sin especialidad",
                    questionCount = questionCounts[row.id?.toString().orEmpty()] ?: 0,
                )
            }.also {
                examsFlow.value = it
                syncSpecialtiesWithLinkedCounts()
            }
        }
    }

    override suspend fun refreshRafagas(): AppResult<List<Rafaga>> {
        val accessToken = requireAccessToken("cargar rafagas") ?: return authFailure("cargar rafagas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            if (specialtyRowsCache.isEmpty()) specialtyRowsCache = fetchSpecialtyRows(accessToken)
            val questionCounts = fetchRafagaQuestionCounts(accessToken).groupingBy { it.rafagaId.toString() }.eachCount()
            val specialtyNames = specialtyRowsCache.associateBy({ it.id?.toString().orEmpty() }, { it.nombre })
            val rows: List<RafagaRowDto> = client.get("${appConfig.supabaseUrl}/rest/v1/rafaga?select=*&deleted_at=is.null&order=created_at.desc") {
                supabaseHeaders(accessToken)
            }.body()
            rows.map { row ->
                row.toDomain(
                    specialtyName = specialtyNames[row.specialtyId.toString()] ?: "Sin especialidad",
                    questionCount = questionCounts[row.id?.toString().orEmpty()] ?: 0,
                )
            }.also { rafagasFlow.value = it }
        }
    }

    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> {
        val accessToken = requireAccessToken("cargar preguntas") ?: return authFailure("cargar preguntas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            val numericExamId = examId.toIntId()
            val questions: List<QuestionRowDto> = client.get(
                "${appConfig.supabaseUrl}/rest/v1/pregunta?select=*&examen_id=eq.$numericExamId&deleted_at=is.null&order=orden.asc",
            ) { supabaseHeaders(accessToken) }.body()
            if (questions.isEmpty()) {
                emptyList()
            } else {
                val ids = questions.mapNotNull { it.id }.joinToString(",")
                val options: List<OptionRowDto> = client.get(
                    "${appConfig.supabaseUrl}/rest/v1/opcion_pregunta?select=*&pregunta_id=in.($ids)&deleted_at=is.null&order=orden.asc",
                ) { supabaseHeaders(accessToken) }.body()
                val resources: List<ResourceRowDto> = client.get(
                    "${appConfig.supabaseUrl}/rest/v1/recurso_pregunta?select=*&pregunta_id=in.($ids)&deleted_at=is.null&order=id.asc",
                ) { supabaseHeaders(accessToken) }.body()
                val optionsByQuestion = options.groupBy { it.questionId.toString() }.mapValues { it.value.map(OptionRowDto::toDomain) }
                val resourcesByQuestion = resources.groupBy { it.questionId.toString() }.mapValues { it.value.map(ResourceRowDto::toDomain) }
                questions.map { question ->
                    question.toDomain(
                        options = optionsByQuestion[question.id?.toString().orEmpty()].orEmpty(),
                        resources = resourcesByQuestion[question.id?.toString().orEmpty()].orEmpty(),
                    )
                }
            }
        }
    }

    override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> {
        val accessToken = requireAccessToken("cargar preguntas de rafaga") ?: return authFailure("cargar preguntas de rafaga")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            val numericRafagaId = rafagaId.toIntId()
            val questions: List<RafagaQuestionRowDto> = client.get(
                "${appConfig.supabaseUrl}/rest/v1/rafaga_pregunta?select=*&rafaga_id=eq.$numericRafagaId&deleted_at=is.null&order=orden.asc",
            ) { supabaseHeaders(accessToken) }.body()
            questions.map { it.toDomain() }
        }
    }

    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> {
        val accessToken = requireAccessToken("auditar recursos") ?: return authFailure("auditar recursos")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return adapter.safeCall {
            val questions = when (val result = getExamQuestions(examId)) {
                is AppResult.Failure -> throw IllegalStateException(result.error.message)
                is AppResult.Success -> result.value
            }
            val linkedStoragePaths = questions.flatMap { question ->
                question.resources.mapNotNull { it.storagePath }
            }.toSet()
            val questionIds = questions.map { it.id }.toSet()
            val audits = questions.map { question ->
                val pending = question.resources.count { resource ->
                    resource.type != QuestionResourceType.TEXT && resource.publicUrl.isNullOrBlank()
                }
                QuestionResourceAudit(
                    questionId = question.id,
                    questionTitle = question.title,
                    resourceCount = question.resources.size,
                    pendingResourceCount = pending,
                    hasLinkedResources = question.resources.isNotEmpty(),
                )
            }
            val storageObjects = fetchStorageObjects(accessToken)
            val unlinkedFromStorage = storageObjects
                .filter { it.name !in linkedStoragePaths }
                .map { row ->
                    UnlinkedResource(
                        id = row.name,
                        path = row.name,
                        type = row.name.toResourceTypeFromPath(),
                        reason = UnlinkedResourceReason.STORAGE_WITHOUT_DATABASE_ROW,
                        suggestedQuestionId = questionIds.firstOrNull { row.name.contains(it) },
                        updatedAt = row.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    )
                }
            val pendingLocal = questions.flatMap { question ->
                question.resources.filter { resource ->
                    resource.type != QuestionResourceType.TEXT && resource.publicUrl.isNullOrBlank()
                }.map { resource ->
                    UnlinkedResource(
                        id = resource.id,
                        path = resource.storagePath ?: resource.title,
                        type = resource.type,
                        reason = UnlinkedResourceReason.LOCAL_PENDING_NOT_SAVED,
                        suggestedQuestionId = question.id,
                        updatedAt = null,
                    )
                }
            }
            ExamResourceAudit(
                summary = ExamResourceSummary(
                    totalQuestions = questions.size,
                    questionsWithResources = audits.count { it.hasLinkedResources },
                    questionsWithoutResources = audits.count { !it.hasLinkedResources },
                    linkedResources = questions.sumOf { it.resources.size },
                    pendingResources = audits.sumOf { it.pendingResourceCount },
                    unlinkedResources = unlinkedFromStorage.size + pendingLocal.size,
                ),
                questions = audits,
                unlinkedResources = unlinkedFromStorage + pendingLocal,
            )
        }
    }

    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> {
        val accessToken = requireAccessToken("guardar especialidades") ?: return authFailure("guardar especialidades")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val saved: List<SpecialtyRowDto> = client.post("${appConfig.supabaseUrl}/rest/v1/especialidad?on_conflict=id") {
                supabaseHeaders(accessToken)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(specialty.toRowDto()))
            }.body()
            saved.first().toDomain(specialty.linkedExams)
        }
        if (result is AppResult.Success) {
            refreshSpecialties()
            refreshExams()
        }
        return result
    }

    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> {
        if (examsFlow.value.any { it.specialtyId == specialtyId }) {
            return AppResult.Failure(AppError.Validation("No puedes borrar una especialidad con examenes asociados."))
        }
        val accessToken = requireAccessToken("borrar especialidades") ?: return authFailure("borrar especialidades")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val numericSpecialtyId = specialtyId.toIntId()
            client.patch("${appConfig.supabaseUrl}/rest/v1/especialidad?id=eq.$numericSpecialtyId") {
                supabaseHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(mapOf("deleted_at" to Clock.System.now().toString()))
            }
            Unit
        }
        if (result is AppResult.Success) refreshSpecialties()
        return result
    }

    override suspend fun upsertExam(exam: Exam): AppResult<Exam> {
        val accessToken = requireAccessToken("guardar examenes") ?: return authFailure("guardar examenes")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val examToSave = exam
        val result = adapter.safeCall {
            val saved: List<ExamRowDto> = client.post("${appConfig.supabaseUrl}/rest/v1/examen?on_conflict=id") {
                supabaseHeaders(accessToken)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(examToSave.toRowDto()))
            }.body()
            val specialtyName = specialtiesFlow.value.firstOrNull { it.id == examToSave.specialtyId }?.name ?: exam.specialtyName
            saved.first().toDomain(specialtyName, exam.questionCount)
        }
        if (result is AppResult.Success) {
            refreshExams()
            refreshSpecialties()
        }
        return result
    }

    override suspend fun deleteExam(examId: String): AppResult<Unit> {
        val accessToken = requireAccessToken("borrar examenes") ?: return authFailure("borrar examenes")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val numericExamId = examId.toIntId()
            client.patch("${appConfig.supabaseUrl}/rest/v1/examen?id=eq.$numericExamId") {
                supabaseHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(mapOf("deleted_at" to Clock.System.now().toString()))
            }
            Unit
        }
        if (result is AppResult.Success) {
            refreshExams()
            refreshSpecialties()
        }
        return result
    }

    override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> {
        val accessToken = requireAccessToken("guardar rafagas") ?: return authFailure("guardar rafagas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val saved: List<RafagaRowDto> = client.post("${appConfig.supabaseUrl}/rest/v1/rafaga?on_conflict=id") {
                supabaseHeaders(accessToken)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(rafaga.toRowDto()))
            }.body()
            val specialtyName = specialtiesFlow.value.firstOrNull { it.id == rafaga.specialtyId }?.name ?: rafaga.specialtyName
            saved.first().toDomain(specialtyName, rafaga.questionCount)
        }
        if (result is AppResult.Success) refreshRafagas()
        return result
    }

    override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> {
        val accessToken = requireAccessToken("borrar rafagas") ?: return authFailure("borrar rafagas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val numericRafagaId = rafagaId.toIntId()
            client.patch("${appConfig.supabaseUrl}/rest/v1/rafaga?id=eq.$numericRafagaId") {
                supabaseHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(mapOf("deleted_at" to Clock.System.now().toString()))
            }
            Unit
        }
        if (result is AppResult.Success) refreshRafagas()
        return result
    }

    override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> {
        val accessToken = requireAccessToken("guardar preguntas") ?: return authFailure("guardar preguntas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val questionToSave = question
        val result = adapter.safeCall {
            val savedRows: List<QuestionRowDto> = client.post("${appConfig.supabaseUrl}/rest/v1/pregunta?on_conflict=id") {
                supabaseHeaders(accessToken)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(questionToSave.toRowDto()))
            }.body()
            val savedQuestionId = savedRows.first().id?.toString().orEmpty()
            client.delete("${appConfig.supabaseUrl}/rest/v1/opcion_pregunta?pregunta_id=eq.$savedQuestionId") {
                supabaseHeaders(accessToken)
            }
            if (questionToSave.options.isNotEmpty()) {
                client.post("${appConfig.supabaseUrl}/rest/v1/opcion_pregunta") {
                    supabaseHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(questionToSave.options.map { it.copy(questionId = savedQuestionId).toRowDto() })
                }
            }
            client.delete("${appConfig.supabaseUrl}/rest/v1/recurso_pregunta?pregunta_id=eq.$savedQuestionId") {
                supabaseHeaders(accessToken)
            }
            if (questionToSave.resources.isNotEmpty()) {
                client.post("${appConfig.supabaseUrl}/rest/v1/recurso_pregunta") {
                    supabaseHeaders(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(questionToSave.resources.map { it.copy(questionId = savedQuestionId).toRowDto() })
                }
            }
            savedRows.first().toDomain(
                questionToSave.options.map { it.copy(questionId = savedQuestionId) },
                questionToSave.resources.map { it.copy(questionId = savedQuestionId) },
            )
        }
        if (result is AppResult.Success) refreshExams()
        return result
    }

    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> {
        val accessToken = requireAccessToken("importar preguntas") ?: return authFailure("importar preguntas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val normalized = questions.mapIndexed { index, question ->
            question.copy(
                examId = examId,
                sortOrder = question.sortOrder.takeIf { it >= 0 } ?: index,
            )
        }
        val result = adapter.safeCall {
            val savedRows: List<QuestionRowDto> = client.post("${appConfig.supabaseUrl}/rest/v1/pregunta?on_conflict=id") {
                supabaseHeaders(accessToken)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(normalized.map { it.toRowDto() })
            }.body()
            val savedByIndex = savedRows.mapIndexed { index, row -> index to row.id?.toString().orEmpty() }.toMap()
            val options = normalized.flatMapIndexed { index, question ->
                question.options.map { it.copy(questionId = savedByIndex.getValue(index)).toRowDto() }
            }
            if (options.isNotEmpty()) {
                client.post("${appConfig.supabaseUrl}/rest/v1/opcion_pregunta?on_conflict=id") {
                    supabaseHeaders(accessToken)
                    header("Prefer", "resolution=merge-duplicates,return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(options)
                }
            }
            val resources = normalized.flatMapIndexed { index, question ->
                question.resources.map { it.copy(questionId = savedByIndex.getValue(index)).toRowDto() }
            }
            if (resources.isNotEmpty()) {
                client.post("${appConfig.supabaseUrl}/rest/v1/recurso_pregunta?on_conflict=id") {
                    supabaseHeaders(accessToken)
                    header("Prefer", "resolution=merge-duplicates,return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(resources)
                }
            }
            savedRows.mapIndexed { index, row ->
                val savedQuestionId = row.id?.toString().orEmpty()
                val source = normalized[index]
                row.toDomain(
                    source.options.map { it.copy(questionId = savedQuestionId) },
                    source.resources.map { it.copy(questionId = savedQuestionId) },
                )
            }
        }
        if (result is AppResult.Success) refreshExams()
        return result
    }

    override suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit> {
        val accessToken = requireAccessToken("borrar preguntas") ?: return authFailure("borrar preguntas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val numericQuestionId = questionId.toIntId()
            client.patch("${appConfig.supabaseUrl}/rest/v1/pregunta?id=eq.$numericQuestionId") {
                supabaseHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(mapOf("deleted_at" to Clock.System.now().toString()))
            }
            Unit
        }
        if (result is AppResult.Success) refreshExams()
        return result
    }

    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>> {
        val accessToken = requireAccessToken("reordenar preguntas") ?: return authFailure("reordenar preguntas")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return when (val current = getExamQuestions(examId)) {
            is AppResult.Failure -> current
            is AppResult.Success -> {
                val questions = current.value.sortedBy { it.sortOrder }.toMutableList()
                val index = questions.indexOfFirst { it.id == questionId }
                val target = when (direction) {
                    QuestionMoveDirection.UP -> index - 1
                    QuestionMoveDirection.DOWN -> index + 1
                }
                if (index == -1 || target !in questions.indices) {
                    AppResult.Success(questions)
                } else {
                    val swap = questions[index]
                    questions[index] = questions[target]
                    questions[target] = swap
                    val normalized = questions.mapIndexed { order, item -> item.copy(sortOrder = order) }
                    val result = adapter.safeCall {
                        client.post("${appConfig.supabaseUrl}/rest/v1/pregunta?on_conflict=id") {
                            supabaseHeaders(accessToken)
                            header("Prefer", "resolution=merge-duplicates,return=minimal")
                            contentType(ContentType.Application.Json)
                            setBody(normalized.map { it.toRowDto() })
                        }
                        normalized
                    }
                    if (result is AppResult.Success) refreshExams()
                    result
                }
            }
        }
    }

    override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> {
        val accessToken = requireAccessToken("guardar preguntas de rafaga") ?: return authFailure("guardar preguntas de rafaga")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            val savedRows: List<RafagaQuestionRowDto> = client.post("${appConfig.supabaseUrl}/rest/v1/rafaga_pregunta?on_conflict=id") {
                supabaseHeaders(accessToken)
                header("Prefer", "resolution=merge-duplicates,return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(question.toRowDto()))
            }.body()
            savedRows.first().toDomain()
        }
        if (result is AppResult.Success) refreshRafagas()
        return result
    }

    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> {
        val accessToken = requireAccessToken("borrar preguntas de rafaga") ?: return authFailure("borrar preguntas de rafaga")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val result = adapter.safeCall {
            client.patch("${appConfig.supabaseUrl}/rest/v1/rafaga_pregunta?id=eq.${questionId.toIntId()}&rafaga_id=eq.${rafagaId.toIntId()}") {
                supabaseHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(mapOf("deleted_at" to Clock.System.now().toString()))
            }
            Unit
        }
        if (result is AppResult.Success) refreshRafagas()
        return result
    }

    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>> {
        val accessToken = requireAccessToken("reordenar preguntas de rafaga") ?: return authFailure("reordenar preguntas de rafaga")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        return when (val current = getRafagaQuestions(rafagaId)) {
            is AppResult.Failure -> current
            is AppResult.Success -> {
                val questions = current.value.sortedBy { it.sortOrder }.toMutableList()
                val index = questions.indexOfFirst { it.id == questionId }
                val target = when (direction) {
                    QuestionMoveDirection.UP -> index - 1
                    QuestionMoveDirection.DOWN -> index + 1
                }
                if (index == -1 || target !in questions.indices) {
                    AppResult.Success(questions)
                } else {
                    val swap = questions[index]
                    questions[index] = questions[target]
                    questions[target] = swap
                    val normalized = questions.mapIndexed { order, item -> item.copy(sortOrder = order) }
                    val result = adapter.safeCall {
                        client.post("${appConfig.supabaseUrl}/rest/v1/rafaga_pregunta?on_conflict=id") {
                            supabaseHeaders(accessToken)
                            header("Prefer", "resolution=merge-duplicates,return=minimal")
                            contentType(ContentType.Application.Json)
                            setBody(normalized.map { it.toRowDto() })
                        }
                        normalized
                    }
                    if (result is AppResult.Success) refreshRafagas()
                    result
                }
            }
        }
    }

    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource> {
        val accessToken = requireAccessToken("subir recursos") ?: return authFailure("subir recursos")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        if (localPathOrUrl.startsWith("http://") || localPathOrUrl.startsWith("https://")) {
            return AppResult.Success(
                QuestionResource(
                    id = resourceId,
                    questionId = questionId,
                    type = type,
                    title = title.ifBlank { "Recurso" },
                    textBody = null,
                    storagePath = null,
                    publicUrl = localPathOrUrl,
                    mimeType = guessMime(type),
                    sortOrder = 0,
                ),
            )
        }
        return when (val asset = fileReader.read(localPathOrUrl)) {
            is AppResult.Failure -> asset
            is AppResult.Success -> adapter.safeCall {
                val objectPath = "questions/$questionId/$resourceId-${sanitizeSegment(asset.value.fileName)}"
                client.post("${appConfig.supabaseUrl}/storage/v1/object/${appConfig.supabaseBucketExamResources}/$objectPath") {
                    header("apikey", appConfig.supabaseAnonKey)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("x-upsert", "true")
                    contentType(ContentType.parse(asset.value.mimeType ?: guessMime(type)))
                    setBody(asset.value.bytes)
                }
                QuestionResource(
                    id = resourceId,
                    questionId = questionId,
                    type = type,
                    title = title.ifBlank { asset.value.fileName },
                    textBody = null,
                    storagePath = objectPath,
                    publicUrl = "${appConfig.supabaseUrl}/storage/v1/object/public/${appConfig.supabaseBucketExamResources}/$objectPath",
                    mimeType = asset.value.mimeType ?: guessMime(type),
                    sortOrder = 0,
                )
            }
        }
    }

    override suspend fun uploadExamCover(
        examId: String,
        localPathOrUri: String,
    ): AppResult<Exam> {
        val accessToken = requireAccessToken("subir portada") ?: return authFailure("subir portada")
        ensureConfigured()?.let { return AppResult.Failure(it) }
        val asset = when (val read = fileReader.read(localPathOrUri)) {
            is AppResult.Failure -> return read
            is AppResult.Success -> read.value
        }
        if (asset.bytes.size > COVER_MAX_BYTES) {
            return AppResult.Failure(AppError.Validation("La portada supera el limite de 10 MB."))
        }
        if (asset.mimeType?.startsWith("image/") != true) {
            return AppResult.Failure(AppError.Validation("La portada debe ser una imagen."))
        }
        val result = adapter.safeCall {
            val numericExamId = examId.toIntId()
            val objectPath = "exam-covers/$examId/${sanitizeSegment(asset.fileName)}"
            client.post("${appConfig.supabaseUrl}/storage/v1/object/${appConfig.supabaseBucketExamResources}/$objectPath") {
                header("apikey", appConfig.supabaseAnonKey)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("x-upsert", "true")
                contentType(ContentType.parse(asset.mimeType))
                setBody(asset.bytes)
            }
            val publicUrl = "${appConfig.supabaseUrl}/storage/v1/object/public/${appConfig.supabaseBucketExamResources}/$objectPath"
            val saved: List<ExamRowDto> = client.patch("${appConfig.supabaseUrl}/rest/v1/examen?id=eq.$numericExamId") {
                supabaseHeaders(accessToken)
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "portada_url" to publicUrl,
                        "portada_storage_path" to objectPath,
                        "portada_mime_type" to asset.mimeType,
                        "updated_at" to Clock.System.now().toString(),
                    ),
                )
            }.body()
            val specialtyName = examsFlow.value.firstOrNull { it.id == examId }?.specialtyName.orEmpty()
            saved.first().toDomain(specialtyName, examsFlow.value.firstOrNull { it.id == examId }?.questionCount ?: 0)
        }
        if (result is AppResult.Success) refreshExams()
        return result
    }

    private suspend fun fetchSpecialtyRows(accessToken: String): List<SpecialtyRowDto> {
        return client.get("${appConfig.supabaseUrl}/rest/v1/especialidad?select=*&deleted_at=is.null&order=nombre.asc") {
            supabaseHeaders(accessToken)
        }.body()
    }

    private suspend fun fetchQuestionCounts(accessToken: String): List<QuestionCountRowDto> {
        return client.get("${appConfig.supabaseUrl}/rest/v1/pregunta?select=id,examen_id&deleted_at=is.null") {
            supabaseHeaders(accessToken)
        }.body()
    }

    private suspend fun fetchRafagaQuestionCounts(accessToken: String): List<RafagaQuestionCountRowDto> {
        return client.get("${appConfig.supabaseUrl}/rest/v1/rafaga_pregunta?select=id,rafaga_id&deleted_at=is.null") {
            supabaseHeaders(accessToken)
        }.body()
    }

    private suspend fun fetchStorageObjects(accessToken: String): List<StorageObjectRowDto> {
        return runCatching {
            val bucket = appConfig.supabaseBucketExamResources.toSafePostgrestValue()
            client.get(
                "${appConfig.supabaseUrl}/rest/v1/objects?select=name,updated_at&bucket_id=eq.$bucket",
            ) {
                supabaseHeaders(accessToken)
            }.body<List<StorageObjectRowDto>>()
        }.getOrElse { emptyList() }
    }

    private fun syncSpecialtiesWithLinkedCounts() {
        if (specialtyRowsCache.isEmpty()) return
        val linkedCounts = examsFlow.value.groupingBy { it.specialtyId }.eachCount()
        specialtiesFlow.value = specialtyRowsCache.map { it.toDomain(linkedCounts[it.id?.toString().orEmpty()] ?: 0) }
    }

    private fun HttpRequestBuilder.supabaseHeaders(accessToken: String) {
        header("apikey", appConfig.supabaseAnonKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun requireAccessToken(action: String): String? = sessionLocalDataSource.currentSession()?.accessToken

    private fun authFailure(action: String): AppResult.Failure {
        return AppResult.Failure(AppError.Authentication("Necesitas iniciar sesion para $action."))
    }

    private fun ensureConfigured(): AppError.Validation? {
        if (appConfig.supabaseUrl.isBlank()) return AppError.Validation("SUPABASE_URL no esta configurado.")
        if (appConfig.supabaseAnonKey.isBlank()) return AppError.Validation("SUPABASE_ANON_KEY no esta configurado.")
        return null
    }

    private fun sanitizeSegment(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "resource.bin" }
    }

    private fun String.toIntId(): Int = toIntOrNull() ?: throw IllegalArgumentException("Id no valido.")

    private fun String.toSafePostgrestValue(): String {
        require(matches(Regex("[A-Za-z0-9._-]+"))) { "Valor de filtro no valido." }
        return this
    }

    private fun guessMime(type: QuestionResourceType): String {
        return when (type) {
            QuestionResourceType.AUDIO -> "audio/mpeg"
            QuestionResourceType.VIDEO -> "video/mp4"
            QuestionResourceType.TEXT -> "text/plain"
            QuestionResourceType.DOCUMENT -> "application/pdf"
            QuestionResourceType.IMAGE -> "image/png"
        }
    }

    private companion object {
        const val COVER_MAX_BYTES = 10 * 1024 * 1024
    }

    private fun String.toResourceTypeFromPath(): QuestionResourceType {
        val extension = substringAfterLast('.', "").lowercase()
        return when (extension) {
            "png", "jpg", "jpeg", "webp" -> QuestionResourceType.IMAGE
            "mp3", "wav", "m4a" -> QuestionResourceType.AUDIO
            "mp4", "mov", "webm" -> QuestionResourceType.VIDEO
            "txt", "md" -> QuestionResourceType.TEXT
            else -> QuestionResourceType.DOCUMENT
        }
    }
}

@Serializable
private data class StorageObjectRowDto(
    val name: String,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

@Serializable
private data class RafagaRowDto(
    val id: Int? = null,
    val titulo: String,
    @SerialName("precio_tickets") val priceTickets: Int = 0,
    @SerialName("especialidad_id") val specialtyId: Int,
    val estado: String = "draft",
    @SerialName("publicar_desde") val publishFrom: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class RafagaQuestionRowDto(
    val id: Int? = null,
    @SerialName("rafaga_id") val rafagaId: Int,
    val enunciado: String,
    val orden: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class RafagaQuestionCountRowDto(
    val id: Int? = null,
    @SerialName("rafaga_id") val rafagaId: Int,
)

private fun RafagaRowDto.toDomain(specialtyName: String, questionCount: Int) = Rafaga(
    id = id?.toString().orEmpty(),
    specialtyId = specialtyId.toString(),
    specialtyName = specialtyName,
    title = titulo,
    priceTickets = priceTickets,
    status = estado.toExamStatus(),
    publishFrom = publishFrom.toInstantOrNull(),
    publishedAt = publishedAt.toInstantOrNull(),
    questionCount = questionCount,
    createdAt = createdAt.toInstantOrNow(),
    updatedAt = updatedAt.toInstantOrNow(),
    deletedAt = deletedAt.toInstantOrNull(),
)

private fun Rafaga.toRowDto() = RafagaRowDto(
    id = id.toIntOrNull(),
    titulo = title,
    priceTickets = priceTickets,
    specialtyId = specialtyId.toIntOrNull() ?: 1,
    estado = status.name.lowercase(),
    publishFrom = publishFrom?.toString(),
    publishedAt = publishedAt?.toString(),
    updatedAt = Clock.System.now().toString(),
)

private fun RafagaQuestionRowDto.toDomain() = RafagaQuestion(
    id = id?.toString().orEmpty(),
    rafagaId = rafagaId.toString(),
    statement = enunciado,
    sortOrder = orden,
    createdAt = createdAt.toInstantOrNow(),
    updatedAt = updatedAt.toInstantOrNow(),
    deletedAt = deletedAt.toInstantOrNull(),
)

private fun RafagaQuestion.toRowDto() = RafagaQuestionRowDto(
    id = id.toIntOrNull(),
    rafagaId = rafagaId.toIntOrNull() ?: 1,
    enunciado = statement,
    orden = sortOrder,
    updatedAt = Clock.System.now().toString(),
)

private fun String.toExamStatus() = when (lowercase()) {
    "published" -> ExamStatus.PUBLISHED
    "archived" -> ExamStatus.ARCHIVED
    else -> ExamStatus.DRAFT
}

private fun String?.toInstantOrNull(): Instant? = this?.let { value ->
    runCatching { Instant.parse(value) }.getOrNull()
}

private fun String?.toInstantOrNow(): Instant {
    return this?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
        ?: Instant.parse(Clock.System.now().toString())
}
