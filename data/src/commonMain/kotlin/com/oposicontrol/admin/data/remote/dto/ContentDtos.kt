package com.oposicontrol.admin.data.remote.dto

import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Specialty
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpecialtyRowDto(
    val id: Int? = null,
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class ExamRowDto(
    val id: Int? = null,
    @SerialName("especialidad_id")
    val specialtyId: Int,
    val titulo: String,
    @SerialName("precio_tickets")
    val priceTickets: Int = 0,
    val estado: String,
    @SerialName("publicar_desde")
    val publishFrom: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class QuestionCountRowDto(
    val id: Int? = null,
    @SerialName("examen_id")
    val examId: Int,
)

@Serializable
data class QuestionRowDto(
    val id: Int? = null,
    @SerialName("examen_id")
    val examId: Int,
    val enunciado: String,
    val orden: Int,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class OptionRowDto(
    val id: Int? = null,
    @SerialName("pregunta_id")
    val questionId: Int,
    val etiqueta: String,
    val contenido: String,
    @SerialName("es_correcta")
    val isCorrect: Boolean,
    val orden: Int,
)

@Serializable
data class ResourceRowDto(
    val id: Int? = null,
    @SerialName("pregunta_id")
    val questionId: Int,
    @SerialName("tipo_recurso")
    val type: String,
    val url: String,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

fun SpecialtyRowDto.toDomain(linkedExams: Int): Specialty {
    return Specialty(
        id = id?.toString().orEmpty(),
        name = nombre,
        description = slug,
        isActive = activa,
        sortOrder = id ?: 0,
        linkedExams = linkedExams,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )
}

fun Specialty.toRowDto(): SpecialtyRowDto {
    return SpecialtyRowDto(
        id = id.toIntOrNull(),
        nombre = name,
        slug = description.ifBlank { name.toSlug() },
        activa = isActive,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}

fun ExamRowDto.toDomain(
    specialtyName: String,
    questionCount: Int,
): Exam {
    return Exam(
        id = id?.toString().orEmpty(),
        specialtyId = specialtyId.toString(),
        specialtyName = specialtyName,
        title = titulo,
        description = "",
        status = estado.toExamStatus(),
        publishFrom = publishFrom?.let(Instant::parse),
        sortOrder = id ?: 0,
        questionCount = questionCount,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )
}

fun Exam.toRowDto(): ExamRowDto {
    return ExamRowDto(
        id = id.toIntOrNull(),
        specialtyId = specialtyId.toIntOrNull() ?: 0,
        titulo = title,
        priceTickets = 0,
        estado = status.name.lowercase(),
        publishFrom = publishFrom?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}

fun QuestionRowDto.toDomain(
    options: List<QuestionOption>,
    resources: List<QuestionResource>,
): ExamQuestion {
    return ExamQuestion(
        id = id?.toString().orEmpty(),
        examId = examId.toString(),
        title = "Pregunta ${orden + 1}",
        statement = enunciado,
        sortOrder = orden,
        options = options.sortedBy { it.sortOrder },
        resources = resources.sortedBy { it.sortOrder },
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )
}

fun ExamQuestion.toRowDto(): QuestionRowDto {
    return QuestionRowDto(
        id = id.toIntOrNull(),
        examId = examId.toIntOrNull() ?: 0,
        enunciado = statement,
        orden = sortOrder,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}

fun OptionRowDto.toDomain(): QuestionOption {
    return QuestionOption(
        id = id?.toString().orEmpty(),
        questionId = questionId.toString(),
        label = etiqueta,
        content = contenido,
        isCorrect = isCorrect,
        sortOrder = orden,
    )
}

fun QuestionOption.toRowDto(): OptionRowDto {
    return OptionRowDto(
        id = id.toIntOrNull(),
        questionId = questionId.toIntOrNull() ?: 0,
        etiqueta = label,
        contenido = content,
        isCorrect = isCorrect,
        orden = sortOrder,
    )
}

fun ResourceRowDto.toDomain(): QuestionResource {
    return QuestionResource(
        id = id?.toString().orEmpty(),
        questionId = questionId.toString(),
        type = type.toQuestionResourceType(),
        title = url.substringAfterLast('/').ifBlank { "Recurso" },
        textBody = if (type.toQuestionResourceType() == QuestionResourceType.TEXT) url else null,
        storagePath = url.takeUnless { it.startsWith("http://") || it.startsWith("https://") },
        publicUrl = url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        mimeType = null,
        sortOrder = id ?: 0,
    )
}

fun QuestionResource.toRowDto(): ResourceRowDto {
    return ResourceRowDto(
        id = id.toIntOrNull(),
        questionId = questionId.toIntOrNull() ?: 0,
        type = type.toStorageValue(),
        url = publicUrl ?: storagePath ?: textBody ?: "",
    )
}

private fun String.toSlug(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "especialidad" }
}

private fun String.toExamStatus(): ExamStatus {
    return when (lowercase()) {
        "published" -> ExamStatus.PUBLISHED
        "archived" -> ExamStatus.ARCHIVED
        else -> ExamStatus.DRAFT
    }
}

private fun String.toQuestionResourceType(): QuestionResourceType {
    return when (lowercase()) {
        "audio" -> QuestionResourceType.AUDIO
        "video" -> QuestionResourceType.VIDEO
        "text" -> QuestionResourceType.TEXT
        "document", "documento" -> QuestionResourceType.DOCUMENT
        "imagen", "image" -> QuestionResourceType.IMAGE
        else -> QuestionResourceType.IMAGE
    }
}

private fun QuestionResourceType.toStorageValue(): String {
    return when (this) {
        QuestionResourceType.IMAGE -> "image"
        QuestionResourceType.AUDIO -> "audio"
        QuestionResourceType.VIDEO -> "video"
        QuestionResourceType.TEXT -> "text"
        QuestionResourceType.DOCUMENT -> "document"
    }
}
