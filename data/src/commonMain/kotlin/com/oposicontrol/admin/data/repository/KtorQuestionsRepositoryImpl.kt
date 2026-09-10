package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLinkRequest
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionsRepository
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
import kotlinx.serialization.Serializable

/**
 * Adapter Ktor propio del banco global de preguntas (pantalla Preguntas,
 * JVM/Desktop).
 *
 * A propósito NO reutiliza los DTOs/mappers privados de
 * [KtorAdminV1ContentRepositoryImpl] — ese fichero queda intacto (0 líneas
 * tocadas; ver historial) para no arriesgar el flujo que usa Exámenes. Este
 * adapter declara sus propios DTOs mínimos con el mismo contrato real
 * (`/api/v1/admin/preguntas`), y su propio mapeo hacia
 * [QuestionListItem]/[QuestionDetail] — tipos que tampoco comparte con
 * `ExamQuestion`.
 */
class KtorQuestionsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : QuestionsRepository {

    override suspend fun listQuestions() = adapter.safeCall {
        val endpoint = BackendRoutes.ADMIN_V1_PREGUNTAS
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntasResponse>().data.map { it.toListItem() }
    }

    override suspend fun listQuestionsForExam(examId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1ExamenPreguntas(examId)
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntasResponse>().data.map { it.toListItem() }
    }

    override suspend fun getQuestionDetail(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaResponse>().data.toDetail()
    }

    override suspend fun createQuestion(draft: QuestionDraft, examId: String, position: Int) = adapter.safeCall {
        // especialidad_id es obligatorio en backend y hoy no hay "Tema"
        // independiente: se deriva del examen elegido, igual que hacía el
        // flujo legacy (sin tocarlo — esto es una llamada propia).
        val examEndpoint = BackendRoutes.adminV1Examen(examId)
        val examResponse = client.get(url(examEndpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        examResponse.requireSuccess(examEndpoint)
        val especialidadId = examResponse.body<QExamenResponse>().data.especialidadId

        val endpoint = BackendRoutes.ADMIN_V1_PREGUNTAS
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(draft.toDto(especialidadId = especialidadId, examenId = examId, posicion = position))
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaResponse>().data.toDetail()
    }

    override suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
        linkToExam: QuestionExamLinkRequest?,
    ) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        val body = draft.toDto(
            especialidadId = "",
            examenId = linkToExam?.examId,
            posicion = linkToExam?.position,
            includeOptions = optionsChanged,
        )
        val response = client.patch(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(body)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaResponse>().data.toDetail()
    }

    override suspend fun archiveQuestion(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        val response = client.delete(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
    }

    private fun url(path: String): String = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }
}

@Serializable
private data class QPreguntasResponse(val data: List<QPreguntaDto> = emptyList())

@Serializable
private data class QPreguntaResponse(val data: QPreguntaDto)

@Serializable
private data class QExamenResponse(val data: QExamenDto)

@Serializable
private data class QExamenDto(
    val especialidadId: String = "",
)

@Serializable
private data class QPreguntaDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val tipoPregunta: String = "rafaga",
    val titulo: String = "",
    val fuenteTexto: String = "",
    val dificultad: String = "media",
    val activa: Boolean = true,
    val razonamiento: String = "",
    val opciones: List<QOpcionDto> = emptyList(),
    val recursos: List<QRecursoDto> = emptyList(),
)

@Serializable
private data class QOpcionDto(
    val id: String = "",
    val letra: String = "",
    val texto: String = "",
    val posicion: Int = 0,
    val esCorrecta: Boolean = false,
)

@Serializable
private data class QRecursoDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String = "",
    val titulo: String = "",
    val urlExterna: String? = null,
    val contenidoTexto: String? = null,
    val posicion: Int = 0,
)

@Serializable
private data class QPreguntaWriteDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val especialidadId: String = "",
    val tipoPregunta: String,
    val titulo: String,
    val fuenteTexto: String = "",
    val dificultad: String = "media",
    val activa: Boolean = true,
    val razonamiento: String = "",
    val opciones: List<QOpcionDto> = emptyList(),
    val recursos: List<QRecursoDto> = emptyList(),
    val examenId: String? = null,
    val posicion: Int? = null,
)

private fun QPreguntaDto.toListItem() = QuestionListItem(
    id = id,
    code = codigoPregunta,
    type = QuestionType.fromExternal(tipoPregunta),
    statement = titulo,
    active = activa,
)

private fun QPreguntaDto.toDetail() = QuestionDetail(
    id = id,
    code = codigoPregunta,
    type = QuestionType.fromExternal(tipoPregunta),
    statement = titulo,
    options = opciones.mapIndexed { index, option ->
        QuestionOption(
            id = option.id,
            questionId = id,
            label = option.letra,
            content = option.texto,
            isCorrect = option.esCorrecta,
            sortOrder = if (option.posicion > 0) option.posicion - 1 else index,
        )
    }.sortedBy { it.sortOrder },
    explanation = razonamiento,
    source = fuenteTexto,
    active = activa,
    difficulty = dificultad,
    resources = recursos.map { resource ->
        QuestionResource(
            id = resource.id,
            questionId = resource.preguntaId,
            type = resource.tipoRecurso.toQuestionResourceType(),
            title = resource.titulo,
            textBody = resource.contenidoTexto,
            storagePath = null,
            publicUrl = resource.urlExterna,
            mimeType = null,
            sortOrder = (resource.posicion - 1).coerceAtLeast(0),
        )
    }.sortedBy { it.sortOrder },
    // El contrato V1 de preguntas no trae fecha de creación/actualización —
    // se deja null a propósito, nunca "ahora mismo" como si fuera real.
    createdAt = null,
    updatedAt = null,
)

private fun QuestionDraft.toDto(
    especialidadId: String,
    examenId: String?,
    posicion: Int?,
    includeOptions: Boolean = true,
) = QPreguntaWriteDto(
    especialidadId = especialidadId,
    tipoPregunta = type.wireValue,
    titulo = statement,
    fuenteTexto = source,
    dificultad = difficulty,
    activa = active,
    razonamiento = explanation,
    opciones = if (includeOptions) {
        options.mapIndexed { index, option ->
            QOpcionDto(
                letra = option.label,
                texto = option.content,
                posicion = index + 1,
                esCorrecta = option.isCorrect,
            )
        }
    } else {
        emptyList()
    },
    examenId = examenId,
    posicion = posicion,
)

private fun String.toQuestionResourceType() = when (lowercase()) {
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "texto", "text" -> QuestionResourceType.TEXT
    "documento", "document" -> QuestionResourceType.DOCUMENT
    else -> QuestionResourceType.IMAGE
}
