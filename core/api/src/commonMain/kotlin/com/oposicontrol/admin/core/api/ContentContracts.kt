package com.oposicontrol.admin.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PublicationStatus {
    @SerialName("draft")
    DRAFT,

    @SerialName("published")
    PUBLISHED,

    @SerialName("archived")
    ARCHIVED,
}

@Serializable
enum class ExamType {
    @SerialName("inmersivo")
    IMMERSIVE,

    @SerialName("rafaga")
    BURST,
}

@Serializable
enum class ExamAccessType {
    @SerialName("gratuito")
    FREE,

    @SerialName("premium")
    PREMIUM,
}

@Serializable
enum class QuestionResourceType {
    @SerialName("image")
    IMAGE,

    @SerialName("audio")
    AUDIO,

    @SerialName("video")
    VIDEO,

    @SerialName("text")
    TEXT,

    @SerialName("document")
    DOCUMENT,
}

@Serializable
data class SpecialtyResponse(
    val id: Long,
    val name: String,
    val slug: String,
    val description: String,
    val active: Boolean,
    val sortOrder: Int,
    val linkedExamCount: Int,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpsertSpecialtyRequest(
    val name: String,
    val slug: String,
    val description: String = "",
    val active: Boolean = true,
    val sortOrder: Int = 0,
    val expectedVersion: Long? = null,
)

@Serializable
data class ExamResponse(
    val id: Long,
    val specialtyId: Long,
    val specialtyName: String,
    val code: String,
    val title: String,
    val description: String,
    val type: ExamType,
    val accessType: ExamAccessType,
    val priceTickets: Long,
    val status: PublicationStatus,
    val publishFrom: String? = null,
    val publishedAt: String? = null,
    val sortOrder: Int,
    val questionCount: Int,
    val durationMinutes: Int? = null,
    val year: Int? = null,
    val official: Boolean,
    val coverUrl: String? = null,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpsertExamRequest(
    val specialtyId: Long,
    val code: String,
    val title: String,
    val description: String = "",
    val type: ExamType,
    val accessType: ExamAccessType,
    val priceTickets: Long,
    val status: PublicationStatus,
    val publishFrom: String? = null,
    val sortOrder: Int = 0,
    val durationMinutes: Int? = null,
    val year: Int? = null,
    val official: Boolean = false,
    val expectedVersion: Long? = null,
)

@Serializable
data class ExamQuestionResponse(
    val id: Long,
    val examId: Long,
    val title: String,
    val statement: String,
    val sortOrder: Int,
    val options: List<QuestionOptionResponse>,
    val resources: List<QuestionResourceResponse>,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpsertQuestionRequest(
    val title: String,
    val statement: String,
    val sortOrder: Int,
    val options: List<QuestionOptionInput>,
    val expectedVersion: Long? = null,
)

@Serializable
data class QuestionOptionResponse(
    val id: Long,
    val label: String,
    val content: String,
    val correct: Boolean,
    val sortOrder: Int,
)

@Serializable
data class QuestionOptionInput(
    val id: Long? = null,
    val label: String,
    val content: String,
    val correct: Boolean,
    val sortOrder: Int,
)

@Serializable
data class QuestionResourceResponse(
    val id: Long,
    val type: QuestionResourceType,
    val title: String,
    val textBody: String? = null,
    val storagePath: String? = null,
    val downloadUrl: String? = null,
    val mimeType: String? = null,
    val sortOrder: Int,
    val version: Long,
)

@Serializable
data class UpsertQuestionResourceRequest(
    val type: QuestionResourceType,
    val title: String,
    val textBody: String? = null,
    val storagePath: String? = null,
    val mimeType: String? = null,
    val sortOrder: Int,
    val expectedVersion: Long? = null,
)

@Serializable
data class ImportQuestionsRequest(
    val questions: List<UpsertQuestionRequest>,
)

@Serializable
data class ReorderQuestionsRequest(
    val orderedQuestionIds: List<Long>,
)
