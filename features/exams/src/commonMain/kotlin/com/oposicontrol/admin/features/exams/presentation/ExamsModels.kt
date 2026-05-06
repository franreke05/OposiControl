package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionResourceType

data class QuestionOptionDraft(
    val id: String,
    val label: String,
    val content: String,
    val isCorrect: Boolean,
    val sortOrder: Int,
)

data class QuestionResourceDraft(
    val id: String,
    val type: String,
    val title: String,
    val inputValue: String,
    val publicUrl: String? = null,
    val storagePath: String? = null,
    val mimeType: String? = null,
    val sortOrder: Int = 0,
)

data class SolucionarioUi(
    val title: String,
    val specialty: String,
    val status: String,
    val questionCount: Int,
    val hasSelection: Boolean,
)

data class QuestionUi(
    val title: String,
    val statement: String,
    val correctOptionLabel: String,
    val resourceCount: Int,
    val hasResources: Boolean,
    val selected: Boolean,
)

data class ResourceTileUi(
    val type: String,
    val title: String,
    val valuePreview: String,
    val linked: Boolean,
)

enum class DropTargetState {
    Idle,
    Ready,
    Linked,
}

internal fun blankQuestionId(): String = randomUuid()

internal fun blankOptionDrafts(questionId: String): List<QuestionOptionDraft> {
    return listOf("A", "B", "C", "D").mapIndexed { index, label ->
        QuestionOptionDraft(
            id = randomUuid(),
            label = label,
            content = "",
            isCorrect = index == 0,
            sortOrder = index,
        )
    }
}

internal fun blankResourceDraft(): QuestionResourceDraft {
    return QuestionResourceDraft(
        id = randomUuid(),
        type = "document",
        title = "Recurso 1",
        inputValue = "",
        sortOrder = 0,
    )
}

internal fun String.toExamStatus(): ExamStatus {
    return when (lowercase()) {
        "published" -> ExamStatus.PUBLISHED
        "archived" -> ExamStatus.ARCHIVED
        else -> ExamStatus.DRAFT
    }
}

internal fun String.toResourceType(): QuestionResourceType {
    return when (lowercase()) {
        "audio" -> QuestionResourceType.AUDIO
        "video" -> QuestionResourceType.VIDEO
        "texto", "text" -> QuestionResourceType.TEXT
        "document", "documento" -> QuestionResourceType.DOCUMENT
        "image", "imagen" -> QuestionResourceType.IMAGE
        else -> QuestionResourceType.DOCUMENT
    }
}

internal fun QuestionResourceType.toResourceInputValue(): String {
    return when (this) {
        QuestionResourceType.IMAGE -> "image"
        QuestionResourceType.AUDIO -> "audio"
        QuestionResourceType.VIDEO -> "video"
        QuestionResourceType.TEXT -> "text"
        QuestionResourceType.DOCUMENT -> "document"
    }
}

data class ImportedQuestionDraft(
    val title: String,
    val statement: String,
    val options: List<String>,
    val correctLabel: String,
)

data class ImportParseIssue(
    val index: Int,
    val message: String,
)

data class ImportParseReport(
    val questions: List<ImportedQuestionDraft>,
    val issues: List<ImportParseIssue>,
    val duplicateCount: Int,
    val skippedOverLimit: Int,
) {
    val validCount: Int get() = questions.size
    val hasErrors: Boolean get() = issues.isNotEmpty()
}

object QuestionImportParser {
    private const val MaxQuestionsPerImport = 50

    fun parse(text: String): List<ImportedQuestionDraft> {
        return parseReport(text).questions
    }

    fun parseReport(text: String, limit: Int = MaxQuestionsPerImport): ImportParseReport {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) {
            return ImportParseReport(emptyList(), listOf(ImportParseIssue(0, "El archivo esta vacio.")), 0, 0)
        }
        val giftQuestions = parseGift(normalized)
        val parsed = if (giftQuestions.isNotEmpty()) {
            ParsedImport(giftQuestions, emptyList())
        } else {
            parseAikenDetailed(normalized)
        }
        val deduplicated = parsed.questions.distinctBy { it.statement.trim().lowercase() }
        val duplicateCount = parsed.questions.size - deduplicated.size
        val limited = deduplicated.take(limit)
        return ImportParseReport(
            questions = limited,
            issues = parsed.issues,
            duplicateCount = duplicateCount,
            skippedOverLimit = (deduplicated.size - limited.size).coerceAtLeast(0),
        )
    }

    private fun parseAiken(text: String): List<ImportedQuestionDraft> {
        return parseAikenDetailed(text).questions
    }

    private fun parseAikenDetailed(text: String): ParsedImport {
        val questions = mutableListOf<ImportedQuestionDraft>()
        val issues = mutableListOf<ImportParseIssue>()
        text.split(Regex("\n\\s*\n")).forEachIndexed { blockIndex, block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            val answerIndex = lines.indexOfFirst { it.startsWith("ANSWER:", ignoreCase = true) }
            val number = blockIndex + 1
            if (answerIndex == -1) {
                issues += ImportParseIssue(number, "Falta ANSWER en la pregunta $number.")
                return@forEachIndexed
            }
            if (answerIndex <= 4) {
                issues += ImportParseIssue(number, "La pregunta $number necesita enunciado y 4 opciones.")
                return@forEachIndexed
            }
            val answer = lines[answerIndex].substringAfter(':').trim().take(1).uppercase()
            val optionLines = lines.subList(answerIndex - 4, answerIndex)
            val options = optionLines.mapNotNull { line ->
                Regex("^[A-Da-d][).]\\s*(.+)$").find(line)?.groupValues?.get(1)?.trim()
            }
            if (options.size != 4) {
                issues += ImportParseIssue(number, "La pregunta $number debe tener exactamente 4 opciones A-D.")
                return@forEachIndexed
            }
            if (answer !in listOf("A", "B", "C", "D")) {
                issues += ImportParseIssue(number, "La respuesta correcta de la pregunta $number no es A-D.")
                return@forEachIndexed
            }
            questions += ImportedQuestionDraft(
                title = lines.first().take(64),
                statement = lines.subList(0, answerIndex - 4).joinToString("\n"),
                options = options,
                correctLabel = answer,
            )
        }
        return ParsedImport(questions, issues)
    }

    private fun parseGift(text: String): List<ImportedQuestionDraft> {
        return Regex("(?s)(::(.*?)::)?(.*?)\\{(.*?)\\}")
            .findAll(text)
            .mapNotNull { match ->
                val explicitTitle = match.groupValues.getOrNull(2).orEmpty().trim()
                val statement = match.groupValues.getOrNull(3).orEmpty().trim()
                val answers = match.groupValues.getOrNull(4).orEmpty()
                val tokens = Regex("([~=])([^~=]+)").findAll(answers).map { answer ->
                    answer.groupValues[1] to answer.groupValues[2].substringBefore('#').trim()
                }.filter { it.second.isNotBlank() }.toList()
                val options = tokens.map { it.second }.take(4)
                val correctIndex = tokens.indexOfFirst { it.first == "=" }
                if (statement.isBlank() || options.size != 4 || correctIndex !in 0..3) return@mapNotNull null
                ImportedQuestionDraft(
                    title = explicitTitle.ifBlank { statement.take(64) },
                    statement = statement,
                    options = options,
                    correctLabel = listOf("A", "B", "C", "D")[correctIndex],
                )
            }
            .toList()
    }

    private data class ParsedImport(
        val questions: List<ImportedQuestionDraft>,
        val issues: List<ImportParseIssue>,
    )
}
