package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

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
    val localFileName: String = "",
    val sortOrder: Int = 0,
)

data class QuestionUi(
    val id: String,
    val title: String,
    val statement: String,
    val correctOptionLabel: String,
    val resourceCount: Int,
    val hasResources: Boolean,
    val selected: Boolean,
)

data class ContentExamUi(
    val id: String,
    val code: String,
    val title: String,
    val specialty: String,
    val typeExam: String,
    val typeAccess: String,
    val year: Int?,
    val questionCount: Int,
    val durationMinutes: Int?,
    val isOfficial: Boolean,
    val selected: Boolean,
)

data class RafagaQuestionUi(
    val id: String,
    val statement: String,
    val selected: Boolean,
)

data class ResourceTileUi(
    val type: String,
    val title: String,
    val valuePreview: String,
    val fileName: String,
    val mimeType: String,
    val linked: Boolean,
)

enum class DropTargetState {
    Idle,
    Ready,
    Linked,
}

enum class ExamEditorMode {
    Solucionario,
    Rafaga,
}

sealed interface PendingDeleteTarget {
    /**
     * ARCHIVAR el examen abierto: reversible.
     *
     * Se llamaba `Exam`, sin más, cuando archivar era lo único que la pantalla
     * sabía hacer con un examen y además se ofrecía bajo el rótulo «Eliminar».
     * Ahora hay dos objetivos y ninguno de los dos puede llamarse por el nombre
     * del otro ni por un nombre que valga para los dos.
     */
    data object ArchiveExam : PendingDeleteTarget

    /**
     * **BORRAR el examen abierto: irreversible.**
     *
     * El «Forever» del nombre no es énfasis: es la única diferencia con
     * [ArchiveExam] en el sitio donde se decide qué hacer
     * (`confirmPendingDelete`), y un `Exam` a secas junto a un `ArchiveExam` se
     * lee como «el genérico» — que es exactamente el error que hay que hacer
     * imposible.
     *
     * El servidor puede negarse: si del examen cuelgan datos de usuarios responde
     * 409 y no borra nada. Eso llega como aviso de la acción, no como error de
     * campo (ver `examDeletionNotice`).
     */
    data object DeleteExamForever : PendingDeleteTarget
    data object Rafaga : PendingDeleteTarget

    /**
     * Archivar la pregunta SELECCIONADA en el editor.
     *
     * Es el objetivo del camino móvil, que trabaja sobre `selectedQuestionId`.
     * Conserva su comportamiento —la pregunta sale del banco y por tanto de
     * todos los exámenes—; lo que cambia es que ahora lo ejecuta el caso de uso
     * que dice eso en su nombre.
     */
    data object Question : PendingDeleteTarget
    data object RafagaQuestion : PendingDeleteTarget
    data object Specialty : PendingDeleteTarget
    data object ExamCover : PendingDeleteTarget
    data class Resource(val resourceId: String) : PendingDeleteTarget

    /** Quitar el vínculo de esta pregunta con el examen abierto. La pregunta sigue en el banco. */
    data class UnlinkQuestion(val questionId: String) : PendingDeleteTarget

    /** Archivar esta pregunta del banco: sale de todos los exámenes en los que esté. */
    data class ArchiveQuestion(val questionId: String) : PendingDeleteTarget

    /**
     * Abrir otro examen —o empezar uno nuevo, con [examId] a `null`— descartando
     * los cambios del editor.
     *
     * No borra nada del servidor, y aun así vive aquí. El nombre del contenedor
     * dice «delete» pero lo que agrupa es «acciones con una consecuencia
     * irreversible que hay que confirmar antes», y perder media hora de
     * formulario lo es. La alternativa era montar un segundo mecanismo de
     * confirmación en paralelo al que la pantalla ya tiene, y dos formas de
     * preguntar lo mismo acaban divergiendo.
     */
    data class SwitchExam(val examId: String?) : PendingDeleteTarget
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

/**
 * Si esta especialidad permite el tipo de examen dado (§capacidades_especialidad).
 *
 * La restricción es sólo de UI: el backend de `updateExamen`/`createExamen` no
 * valida `tipoExamen` contra las capacidades de la especialidad server-side, así
 * que esto es lo único que impide guardar un examen Inmersivo en una
 * especialidad que sólo tiene Ráfaga habilitada.
 */
internal fun SpecialtyCapabilities.allows(typeExam: String): Boolean = when (typeExam.trim().lowercase()) {
    "rafaga" -> permiteRafagas
    "razonado", "guiado" -> permiteRazonados
    "inmersivo" -> permiteInmersivos
    else -> false
}

/** El primer modo habilitado, en el orden habitual de presentación. Null si la especialidad no permite ninguno. */
internal fun SpecialtyCapabilities.firstAllowedType(): String? = when {
    permiteRafagas -> "rafaga"
    permiteRazonados -> "razonado"
    permiteInmersivos -> "inmersivo"
    else -> null
}

/**
 * Si tiene sentido ofrecer la compra individual con OposiTickets para este
 * `tipo_acceso`.
 *
 * Auditoría real (no una regla inventada): `SupabaseV1Gateway.kt` sirve las
 * preguntas de un examen ráfaga sin ninguna comprobación de compra en cuanto
 * `tipo_acceso == "gratuito"` — el examen ya está abierto para cualquiera. No
 * hay ningún endpoint de "comprar examen" en este backend que consulte
 * `precio_tickets`, así que un precio en un examen gratuito no desbloquea nada
 * que no estuviera ya desbloqueado: es dato inerte, no una función real.
 * `premium` sí es el único punto real de esta pantalla donde OT tiene efecto,
 * a falta de un sistema de planes por examen.
 */
internal fun String.allowsOposiTicketsPurchase(): Boolean = trim().lowercase() != "gratuito"

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
