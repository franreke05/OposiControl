package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamResourceSummary
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionResourceAudit
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.UnlinkedResource
import com.oposicontrol.admin.domain.model.UnlinkedResourceReason
import com.oposicontrol.admin.domain.repository.ContentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

class FakeContentRepositoryImpl : ContentRepository {
    private val specialtiesFlow = MutableStateFlow(seedSpecialties())
    private val examsFlow = MutableStateFlow(seedExams())
    private val rafagasFlow = MutableStateFlow(seedRafagas())
    private val storageObjects = mutableMapOf(
        "questions/question-tcae-2/resource-tcae-2-movilizacion.mp4" to Instant.parse("2026-04-12T09:00:00Z"),
        "questions/question-enf-1/resource-enf-1-prioridad.png" to Instant.parse("2026-04-12T09:10:00Z"),
        "questions/question-tcae-1/orphan-audio.mp3" to Instant.parse("2026-04-14T08:00:00Z"),
    )
    private val questionsByExam = mutableMapOf(
        "exam-tcae-marzo" to mutableListOf(
            seedQuestion(
                id = "question-tcae-1",
                examId = "exam-tcae-marzo",
                title = "Asepsia basica",
                statement = "Cual es la medida inicial correcta antes de preparar una cura aseptica?",
                order = 0,
                correctLabel = "B",
                resource = QuestionResource(
                    id = "resource-tcae-1",
                    questionId = "question-tcae-1",
                    type = QuestionResourceType.TEXT,
                    title = "Explicacion",
                    textBody = "La higiene de manos y la preparacion del campo esteril son el paso previo obligatorio.",
                    storagePath = null,
                    publicUrl = null,
                    mimeType = "text/plain",
                    sortOrder = 0,
                ),
            ),
            seedQuestion(
                id = "question-tcae-2",
                examId = "exam-tcae-marzo",
                title = "Movilizacion",
                statement = "Que recurso debes revisar para la movilizacion segura del paciente encamado?",
                order = 1,
                correctLabel = "C",
                resource = QuestionResource(
                    id = "resource-tcae-2",
                    questionId = "question-tcae-2",
                    type = QuestionResourceType.VIDEO,
                    title = "Video guia",
                    textBody = null,
                    storagePath = "questions/question-tcae-2/resource-tcae-2-movilizacion.mp4",
                    publicUrl = "https://fake.storage.oposicontrol.local/questions/question-tcae-2/resource-tcae-2-movilizacion.mp4",
                    mimeType = "video/mp4",
                    sortOrder = 0,
                ),
            ),
        ),
        "exam-enf-simulacro" to mutableListOf(
            seedQuestion(
                id = "question-enf-1",
                examId = "exam-enf-simulacro",
                title = "Prioridad asistencial",
                statement = "En una valoracion inicial, que indicador sugiere prioridad clinica inmediata?",
                order = 0,
                correctLabel = "A",
                resource = QuestionResource(
                    id = "resource-enf-1",
                    questionId = "question-enf-1",
                    type = QuestionResourceType.IMAGE,
                    title = "Infografia",
                    textBody = null,
                    storagePath = "questions/question-enf-1/resource-enf-1-prioridad.png",
                    publicUrl = "https://fake.storage.oposicontrol.local/questions/question-enf-1/resource-enf-1-prioridad.png",
                    mimeType = "image/png",
                    sortOrder = 0,
                ),
            ),
        ),
    )
    private val questionsByRafaga = mutableMapOf(
        "rafaga-tcae-1" to mutableListOf(
            RafagaQuestion(
                id = "rafaga-question-1",
                rafagaId = "rafaga-tcae-1",
                statement = "Resuelve esta situacion breve de higiene de manos en menos de 30 segundos.",
                sortOrder = 0,
                createdAt = Instant.parse("2026-04-12T08:00:00Z"),
                updatedAt = Instant.parse("2026-04-12T08:00:00Z"),
            ),
        ),
    )

    init {
        syncCounts()
    }

    override fun observeSpecialties(): Flow<List<Specialty>> = specialtiesFlow.asStateFlow()

    override fun observeExams(): Flow<List<Exam>> = examsFlow.asStateFlow()

    override fun observeRafagas(): Flow<List<Rafaga>> = rafagasFlow.asStateFlow()

    override suspend fun refreshSpecialties(): AppResult<List<Specialty>> = AppResult.Success(specialtiesFlow.value)

    override suspend fun refreshExams(): AppResult<List<Exam>> = AppResult.Success(examsFlow.value)

    override suspend fun refreshRafagas(): AppResult<List<Rafaga>> = AppResult.Success(rafagasFlow.value)

    override suspend fun getExamQuestions(examId: String): AppResult<List<ExamQuestion>> {
        return AppResult.Success(questionsByExam[examId].orEmpty().sortedBy { it.sortOrder })
    }

    override suspend fun getRafagaQuestions(rafagaId: String): AppResult<List<RafagaQuestion>> {
        return AppResult.Success(questionsByRafaga[rafagaId].orEmpty().sortedBy { it.sortOrder })
    }

    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> {
        val questions = questionsByExam[examId].orEmpty().sortedBy { it.sortOrder }
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
        val unlinkedFromStorage = storageObjects
            .filterKeys { it !in linkedStoragePaths }
            .map { (path, updatedAt) ->
                UnlinkedResource(
                    id = path,
                    path = path,
                    type = path.toResourceTypeFromPath(),
                    reason = UnlinkedResourceReason.STORAGE_WITHOUT_DATABASE_ROW,
                    suggestedQuestionId = questionIds.firstOrNull { path.contains(it) },
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
                )
            }
        }
        return AppResult.Success(
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
            ),
        )
    }

    override suspend fun upsertSpecialty(specialty: Specialty): AppResult<Specialty> {
        val now = Instant.parse("2026-04-15T09:00:00Z")
        val existing = specialtiesFlow.value.firstOrNull { it.id == specialty.id }
        val saved = specialty.copy(
            id = specialty.id.ifBlank { randomUuid() },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        specialtiesFlow.value = specialtiesFlow.value
            .filterNot { it.id == saved.id } + saved.copy(linkedExams = existing?.linkedExams ?: saved.linkedExams)
        syncCounts()
        return AppResult.Success(specialtiesFlow.value.first { it.id == saved.id })
    }

    override suspend fun deleteSpecialty(specialtyId: String): AppResult<Unit> {
        if (examsFlow.value.any { it.specialtyId == specialtyId }) {
            return AppResult.Failure(AppError.Validation("No puedes borrar una especialidad con examenes asociados."))
        }
        specialtiesFlow.value = specialtiesFlow.value.filterNot { it.id == specialtyId }
        return AppResult.Success(Unit)
    }

    override suspend fun upsertExam(exam: Exam): AppResult<Exam> {
        val specialty = specialtiesFlow.value.firstOrNull { it.id == exam.specialtyId }
            ?: return AppResult.Failure(AppError.Validation("La especialidad seleccionada no existe."))
        val now = Instant.parse("2026-04-15T09:00:00Z")
        val existing = examsFlow.value.firstOrNull { it.id == exam.id }
        val generatedId = exam.id.ifBlank { randomUuid() }
        val saved = exam.copy(
            id = generatedId,
            specialtyName = specialty.name,
            questionCount = questionsByExam[generatedId].orEmpty().size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        examsFlow.value = examsFlow.value.filterNot { it.id == saved.id } + saved
        questionsByExam.getOrPut(saved.id) { mutableListOf() }
        syncCounts()
        return AppResult.Success(examsFlow.value.first { it.id == saved.id })
    }

    override suspend fun deleteExam(examId: String): AppResult<Unit> {
        examsFlow.value = examsFlow.value.filterNot { it.id == examId }
        questionsByExam.remove(examId)
        syncCounts()
        return AppResult.Success(Unit)
    }

    override suspend fun upsertRafaga(rafaga: Rafaga): AppResult<Rafaga> {
        val specialty = specialtiesFlow.value.firstOrNull { it.id == rafaga.specialtyId }
            ?: return AppResult.Failure(AppError.Validation("La especialidad seleccionada no existe."))
        val now = Instant.parse("2026-04-15T09:00:00Z")
        val existing = rafagasFlow.value.firstOrNull { it.id == rafaga.id }
        val generatedId = rafaga.id.ifBlank { randomUuid() }
        val saved = rafaga.copy(
            id = generatedId,
            specialtyName = specialty.name,
            questionCount = questionsByRafaga[generatedId].orEmpty().size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        rafagasFlow.value = rafagasFlow.value.filterNot { it.id == saved.id } + saved
        questionsByRafaga.getOrPut(saved.id) { mutableListOf() }
        syncCounts()
        return AppResult.Success(rafagasFlow.value.first { it.id == saved.id })
    }

    override suspend fun deleteRafaga(rafagaId: String): AppResult<Unit> {
        rafagasFlow.value = rafagasFlow.value.filterNot { it.id == rafagaId }
        questionsByRafaga.remove(rafagaId)
        syncCounts()
        return AppResult.Success(Unit)
    }

    override suspend fun upsertQuestion(question: ExamQuestion): AppResult<ExamQuestion> {
        val existing = questionsByExam.getOrPut(question.examId) { mutableListOf() }
        val merged = (existing.filterNot { it.id == question.id } + question)
            .sortedBy { it.sortOrder }
            .mapIndexed { index, item -> item.copy(sortOrder = index) }
            .toMutableList()
        questionsByExam[question.examId] = merged
        syncCounts()
        return AppResult.Success(merged.first { it.id == question.id })
    }

    override suspend fun importQuestions(examId: String, questions: List<ExamQuestion>): AppResult<List<ExamQuestion>> {
        val existing = questionsByExam.getOrPut(examId) { mutableListOf() }
        val merged = (existing + questions)
            .distinctBy { it.id }
            .sortedBy { it.sortOrder }
            .mapIndexed { index, item -> item.copy(examId = examId, sortOrder = index) }
            .toMutableList()
        questionsByExam[examId] = merged
        syncCounts()
        return AppResult.Success(questions.mapNotNull { imported -> merged.firstOrNull { it.id == imported.id } })
    }

    override suspend fun deleteQuestion(examId: String, questionId: String): AppResult<Unit> {
        val bucket = questionsByExam.getOrPut(examId) { mutableListOf() }
        questionsByExam[examId] = bucket.filterNot { it.id == questionId }
            .mapIndexed { index, item -> item.copy(sortOrder = index) }
            .toMutableList()
        syncCounts()
        return AppResult.Success(Unit)
    }

    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<ExamQuestion>> {
        val bucket = questionsByExam[examId].orEmpty().sortedBy { it.sortOrder }.toMutableList()
        val index = bucket.indexOfFirst { it.id == questionId }
        if (index == -1) return AppResult.Success(bucket)
        val target = when (direction) {
            QuestionMoveDirection.UP -> index - 1
            QuestionMoveDirection.DOWN -> index + 1
        }
        if (target !in bucket.indices) return AppResult.Success(bucket)
        val current = bucket[index]
        bucket[index] = bucket[target]
        bucket[target] = current
        val reordered = bucket.mapIndexed { order, item -> item.copy(sortOrder = order) }.toMutableList()
        questionsByExam[examId] = reordered
        syncCounts()
        return AppResult.Success(reordered)
    }

    override suspend fun upsertRafagaQuestion(question: RafagaQuestion): AppResult<RafagaQuestion> {
        val existing = questionsByRafaga.getOrPut(question.rafagaId) { mutableListOf() }
        val saved = question.copy(id = question.id.ifBlank { randomUuid() })
        val merged = (existing.filterNot { it.id == saved.id } + saved)
            .sortedBy { it.sortOrder }
            .mapIndexed { index, item -> item.copy(sortOrder = index) }
            .toMutableList()
        questionsByRafaga[question.rafagaId] = merged
        syncCounts()
        return AppResult.Success(merged.first { it.id == saved.id })
    }

    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String): AppResult<Unit> {
        val bucket = questionsByRafaga.getOrPut(rafagaId) { mutableListOf() }
        questionsByRafaga[rafagaId] = bucket.filterNot { it.id == questionId }
            .mapIndexed { index, item -> item.copy(sortOrder = index) }
            .toMutableList()
        syncCounts()
        return AppResult.Success(Unit)
    }

    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ): AppResult<List<RafagaQuestion>> {
        val bucket = questionsByRafaga[rafagaId].orEmpty().sortedBy { it.sortOrder }.toMutableList()
        val index = bucket.indexOfFirst { it.id == questionId }
        if (index == -1) return AppResult.Success(bucket)
        val target = when (direction) {
            QuestionMoveDirection.UP -> index - 1
            QuestionMoveDirection.DOWN -> index + 1
        }
        if (target !in bucket.indices) return AppResult.Success(bucket)
        val current = bucket[index]
        bucket[index] = bucket[target]
        bucket[target] = current
        val reordered = bucket.mapIndexed { order, item -> item.copy(sortOrder = order) }.toMutableList()
        questionsByRafaga[rafagaId] = reordered
        syncCounts()
        return AppResult.Success(reordered)
    }

    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ): AppResult<QuestionResource> {
        val normalizedTitle = title.ifBlank { "Recurso" }
        if (localPathOrUrl.startsWith("http://") || localPathOrUrl.startsWith("https://")) {
            return AppResult.Success(
                QuestionResource(
                    id = resourceId,
                    questionId = questionId,
                    type = type,
                    title = normalizedTitle,
                    textBody = null,
                    storagePath = null,
                    publicUrl = localPathOrUrl,
                    mimeType = guessMime(type),
                    sortOrder = 0,
                ),
            )
        }
        val fileName = sanitizeSegment(localPathOrUrl.substringAfterLast('/').substringAfterLast('\\'))
        val storagePath = "questions/$questionId/$resourceId-$fileName"
        storageObjects[storagePath] = Instant.parse("2026-04-15T09:00:00Z")
        return AppResult.Success(
            QuestionResource(
                id = resourceId,
                questionId = questionId,
                type = type,
                title = normalizedTitle,
                textBody = null,
                storagePath = storagePath,
                publicUrl = "https://fake.storage.oposicontrol.local/$storagePath",
                mimeType = guessMime(type),
                sortOrder = 0,
            ),
        )
    }

    override suspend fun deleteResource(questionId: String, resourceId: String): AppResult<Unit> {
        questionsByExam.keys.forEach { examId ->
            questionsByExam[examId] = questionsByExam[examId].orEmpty()
                .map { question ->
                    if (question.id == questionId) {
                        question.resources
                            .firstOrNull { it.id == resourceId }
                            ?.storagePath
                            ?.let { storageObjects.remove(it) }
                        question.copy(resources = question.resources.filterNot { it.id == resourceId })
                    } else {
                        question
                    }
                }
                .toMutableList()
        }
        syncCounts()
        return AppResult.Success(Unit)
    }

    override suspend fun uploadExamCover(
        examId: String,
        localPathOrUri: String,
    ): AppResult<Exam> {
        val exam = examsFlow.value.firstOrNull { it.id == examId }
            ?: return AppResult.Failure(AppError.NotFound("No se encontro el examen."))
        val fileName = sanitizeSegment(localPathOrUri.substringAfterLast('/').substringAfterLast('\\')).ifBlank { "cover.png" }
        val storagePath = "exam-covers/$examId/$fileName"
        storageObjects[storagePath] = Instant.parse("2026-04-15T09:00:00Z")
        val saved = exam.copy(
            coverUrl = "https://fake.storage.oposicontrol.local/$storagePath",
            coverStoragePath = storagePath,
            coverMimeType = "image/png",
            updatedAt = Instant.parse("2026-04-15T09:00:00Z"),
        )
        examsFlow.value = examsFlow.value.filterNot { it.id == examId } + saved
        syncCounts()
        return AppResult.Success(examsFlow.value.first { it.id == examId })
    }

    override suspend fun clearExamCover(examId: String): AppResult<Exam> {
        val exam = examsFlow.value.firstOrNull { it.id == examId }
            ?: return AppResult.Failure(AppError.NotFound("No se encontro el examen."))
        exam.coverStoragePath?.let { storageObjects.remove(it) }
        val saved = exam.copy(
            coverUrl = null,
            coverStoragePath = null,
            coverMimeType = null,
            updatedAt = Instant.parse("2026-04-15T09:00:00Z"),
        )
        examsFlow.value = examsFlow.value.filterNot { it.id == examId } + saved
        syncCounts()
        return AppResult.Success(examsFlow.value.first { it.id == examId })
    }

    private fun syncCounts() {
        val questionCounts = questionsByExam.mapValues { (_, questions) -> questions.size }
        val rafagaQuestionCounts = questionsByRafaga.mapValues { (_, questions) -> questions.size }
        val specialtyNameById = specialtiesFlow.value.associateBy({ it.id }, { it.name })
        examsFlow.value = examsFlow.value
            .map { exam ->
                exam.copy(
                    specialtyName = specialtyNameById[exam.specialtyId] ?: exam.specialtyName,
                    questionCount = questionCounts[exam.id] ?: 0,
                )
            }
            .sortedWith(compareBy<Exam> { it.sortOrder }.thenBy { it.title })
        rafagasFlow.value = rafagasFlow.value
            .map { rafaga ->
                rafaga.copy(
                    specialtyName = specialtyNameById[rafaga.specialtyId] ?: rafaga.specialtyName,
                    questionCount = rafagaQuestionCounts[rafaga.id] ?: 0,
                )
            }
            .sortedWith(compareBy<Rafaga> { it.createdAt }.thenBy { it.title })

        val linkedCounts = (examsFlow.value.map { it.specialtyId } + rafagasFlow.value.map { it.specialtyId })
            .groupingBy { it }
            .eachCount()
        specialtiesFlow.value = specialtiesFlow.value
            .map { specialty -> specialty.copy(linkedExams = linkedCounts[specialty.id] ?: 0) }
            .sortedWith(compareBy<Specialty> { it.sortOrder }.thenBy { it.name })
    }

    private fun seedSpecialties(): List<Specialty> {
        return listOf(
            Specialty(
                id = "specialty-tcae",
                name = "TCAE",
                description = "Contenido base de tecnico en cuidados auxiliares.",
                isActive = true,
                sortOrder = 0,
                linkedExams = 0,
                createdAt = Instant.parse("2026-04-10T08:00:00Z"),
                updatedAt = Instant.parse("2026-04-10T08:00:00Z"),
            ),
            Specialty(
                id = "specialty-enfermeria",
                name = "Enfermeria",
                description = "Simulacros y recursos clinicos.",
                isActive = true,
                sortOrder = 1,
                linkedExams = 0,
                createdAt = Instant.parse("2026-04-10T08:10:00Z"),
                updatedAt = Instant.parse("2026-04-10T08:10:00Z"),
            ),
        )
    }

    private fun seedExams(): List<Exam> {
        return listOf(
            Exam(
                id = "exam-tcae-marzo",
                specialtyId = "specialty-tcae",
                specialtyName = "TCAE",
                title = "TCAE Marzo",
                description = "Bloque inicial de marzo con recursos de apoyo.",
                priceTickets = 0,
                status = ExamStatus.PUBLISHED,
                publishFrom = Instant.parse("2026-04-10T08:00:00Z"),
                publishedAt = Instant.parse("2026-04-10T08:00:00Z"),
                sortOrder = 0,
                questionCount = 0,
                coverUrl = "https://fake.storage.oposicontrol.local/exam-covers/exam-tcae-marzo/cover.png",
                coverStoragePath = "exam-covers/exam-tcae-marzo/cover.png",
                coverMimeType = "image/png",
                createdAt = Instant.parse("2026-04-10T08:00:00Z"),
                updatedAt = Instant.parse("2026-04-10T08:00:00Z"),
            ),
            Exam(
                id = "exam-enf-simulacro",
                specialtyId = "specialty-enfermeria",
                specialtyName = "Enfermeria",
                title = "Simulacro Enfermeria 1",
                description = "Simulacro corto con explicaciones ligadas a recurso.",
                priceTickets = 0,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                sortOrder = 1,
                questionCount = 0,
                createdAt = Instant.parse("2026-04-11T08:00:00Z"),
                updatedAt = Instant.parse("2026-04-11T08:00:00Z"),
            ),
        )
    }

    private fun seedRafagas(): List<Rafaga> {
        return listOf(
            Rafaga(
                id = "rafaga-tcae-1",
                specialtyId = "specialty-tcae",
                specialtyName = "TCAE",
                title = "Rafaga TCAE",
                priceTickets = 2,
                status = ExamStatus.DRAFT,
                publishFrom = null,
                publishedAt = null,
                questionCount = 0,
                createdAt = Instant.parse("2026-04-12T08:00:00Z"),
                updatedAt = Instant.parse("2026-04-12T08:00:00Z"),
            ),
        )
    }

    private fun seedQuestion(
        id: String,
        examId: String,
        title: String,
        statement: String,
        order: Int,
        correctLabel: String,
        resource: QuestionResource,
    ): ExamQuestion {
        val options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
            QuestionOption(
                id = "$id-option-$label",
                questionId = id,
                label = label,
                content = "Opcion $label para $title",
                isCorrect = label == correctLabel,
                sortOrder = index,
            )
        }
        return ExamQuestion(
            id = id,
            examId = examId,
            title = title,
            statement = statement,
            sortOrder = order,
            options = options,
            resources = listOf(resource),
            createdAt = Instant.parse("2026-04-12T08:00:00Z"),
            updatedAt = Instant.parse("2026-04-12T08:00:00Z"),
        )
    }

    private fun sanitizeSegment(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "resource.bin" }
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
