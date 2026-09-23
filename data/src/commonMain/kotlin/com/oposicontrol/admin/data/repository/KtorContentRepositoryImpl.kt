package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.storage.LocalBinaryAsset
import com.oposicontrol.admin.data.storage.PlatformFileReader
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
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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
    private val fileReader: PlatformFileReader,
    private val adapter: NetworkResultAdapter,
) : ContentRepository {
    private val specialtiesFlow = MutableStateFlow<List<Specialty>>(emptyList())
    private val examsFlow = MutableStateFlow<List<Exam>>(emptyList())
    private val rafagasFlow = MutableStateFlow<List<Rafaga>>(emptyList())

    override fun observeSpecialties(): Flow<List<Specialty>> = specialtiesFlow.asStateFlow()

    override fun observeExams(): Flow<List<Exam>> = examsFlow.asStateFlow()

    override fun observeRafagas(): Flow<List<Rafaga>> = rafagasFlow.asStateFlow()

    override suspend fun refreshSpecialties() = adapter.safeCall {
        val endpoint = BackendRoutes.V1_ESPECIALIDADES
        val url = BackendRoutes.url(appConfig.baseUrl, endpoint)
        val response = client.get(url) {
            expectSuccess = false
        }
        response.requireBackendSuccess(url)
        response.body<V1DataResponse<V1EspecialidadDto>>()
            .data
            .map { it.toDomain() }
            .also { specialtiesFlow.value = it }
    }

    override suspend fun refreshExams() = adapter.safeCall {
        val specialtiesById = specialtiesFlow.value.associateBy { it.id }
        val endpoint = BackendRoutes.V1_EXAMENES
        val url = BackendRoutes.url(appConfig.baseUrl, endpoint)
        val response = client.get(url) {
            expectSuccess = false
        }
        response.requireBackendSuccess(url)
        response.body<V1DataResponse<V1ExamenDto>>()
            .data
            .map { it.toDomain(specialtiesById[it.especialidadId]?.name.orEmpty()) }
            .also { examsFlow.value = it }
    }

    override suspend fun refreshRafagas() = adapter.safeCall {
        // Legacy endpoint disabled until rafagas are migrated to /v1.
        emptyList<Rafaga>().also { rafagasFlow.value = it }
    }

    override suspend fun getExamQuestions(examId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.v1ExamenPreguntas(examId)
        val url = BackendRoutes.url(appConfig.baseUrl, endpoint)
        val response = client.get(url) {
            expectSuccess = false
        }
        response.requireBackendSuccess(url)
        response.body<V1SingleDataResponse<V1PreguntasExamenDto>>()
            .data
            .preguntas
            .map { it.toDomain(examId) }
    }

    override suspend fun getRafagaQuestions(rafagaId: String) = adapter.safeCall {
        // Legacy endpoint disabled until rafaga questions are migrated to /v1.
        emptyList<RafagaQuestion>()
    }

    override suspend fun getExamResourceAudit(examId: String): AppResult<ExamResourceAudit> {
        return when (val result = getExamQuestions(examId)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(result.value.toAudit())
        }
    }

    // Legacy write endpoints: left unchanged until equivalent /v1 admin routes exist.
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
            .requireBackendSuccess("/content/especialidades/$specialtyId")
        refreshSpecialties()
        Unit
    }

    override suspend fun getSpecialtyCapabilities(specialtyId: String): AppResult<SpecialtyCapabilities> =
        adapter.safeCall {
            val endpoint = "${BackendRoutes.ADMIN_V1_ESPECIALIDADES}/$specialtyId/capacidades"
            val response = client.get(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
                expectSuccess = false
                backendHeaders()
            }
            response.requireBackendSuccess(endpoint)
            response.body<V1SingleDataResponse<SpecialtyCapabilitiesDto>>().data.toDomain()
        }

    override suspend fun saveSpecialtyCapabilities(
        capabilities: SpecialtyCapabilities,
    ): AppResult<SpecialtyCapabilities> = adapter.safeCall {
        val endpoint = "${BackendRoutes.ADMIN_V1_ESPECIALIDADES}/${capabilities.specialtyId}/capacidades"
        // PUT: el cuerpo lleva el estado completo de los flags, así que una petición
        // parcial no puede dejar media configuración aplicada.
        val response = client.put(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            expectSuccess = false
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(capabilities.toDto())
        }
        response.requireBackendSuccess(endpoint)
        response.body<V1SingleDataResponse<SpecialtyCapabilitiesDto>>().data.toDomain()
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
            .requireBackendSuccess("/content/solucionarios/$examId")
        refreshExams()
        refreshSpecialties()
        Unit
    }

    // Este repositorio habla con el contrato `/content/solucionarios` anterior a
    // los endpoints `/admin/v1/examenes/{id}/publicar|despublicar` reales, que no
    // tienen equivalente aquí. No está cableado en DI (ver DataModule.kt): nada
    // llega a ejecutar esto en producción.
    override suspend fun publishExam(examId: String): AppResult<Exam> =
        AppResult.Failure(AppError.Unknown("Publicar examen no está soportado en este repositorio legacy."))

    override suspend fun unpublishExam(examId: String): AppResult<Exam> =
        AppResult.Failure(AppError.Unknown("Despublicar examen no está soportado en este repositorio legacy."))

    override suspend fun upsertRafaga(rafaga: Rafaga) = adapter.safeCall {
        val saved: RafagaDto = client.requestWithBody(
            url = "${contentUrl()}/rafaga",
            create = rafaga.id.isBlank(),
            body = rafaga.toDto(),
        )
        saved.toDomain(rafaga.specialtyName).also {
            refreshRafagas()
            refreshSpecialties()
        }
    }

    override suspend fun deleteRafaga(rafagaId: String) = adapter.safeCall {
        client.delete("${contentUrl()}/rafaga/$rafagaId") { backendHeaders() }
            .requireBackendSuccess("/content/rafaga/$rafagaId")
        refreshRafagas()
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

    override suspend fun upsertRafagaQuestion(question: RafagaQuestion) = adapter.safeCall {
        val saved: RafagaPreguntaDto = client.requestWithBody(
            url = "${contentUrl()}/rafaga/${question.rafagaId}/preguntas",
            create = question.id.isBlank(),
            body = question.toDto(),
        )
        saved.toDomain().also { refreshRafagas() }
    }

    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String) = adapter.safeCall {
        client.delete("${contentUrl()}/rafaga/$rafagaId/preguntas/$questionId") { backendHeaders() }
            .requireBackendSuccess("/content/rafaga/$rafagaId/preguntas/$questionId")
        refreshRafagas()
        Unit
    }

    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: QuestionMoveDirection,
    ) = adapter.safeCall {
        val current = when (val result = getRafagaQuestions(rafagaId)) {
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
        client.put("${contentUrl()}/rafaga/$rafagaId/preguntas/order") {
            backendHeaders()
            contentType(ContentType.Application.Json)
            setBody(OrderDto(ids))
        }.body<List<RafagaPreguntaDto>>().map { it.toDomain() }
    }

    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = adapter.safeCall {
        if (!localPathOrUrl.startsWith("http://") && !localPathOrUrl.startsWith("https://")) {
            val asset = when (val read = fileReader.read(localPathOrUrl)) {
                is AppResult.Failure -> throw IllegalStateException(read.error.message)
                is AppResult.Success -> read.value
            }
            require(asset.bytes.size <= RESOURCE_MAX_BYTES) { "El recurso supera el limite de 50 MB." }
            return@safeCall client.post("${contentUrl()}/preguntas/$questionId/recursos/$resourceId/upload") {
                backendHeaders()
                parameter("tipoRecurso", type.toDtoType())
                parameter("titulo", title.ifBlank { asset.fileName })
                setBody(asset.toMultipartBody())
            }.body<RecursoPreguntaDto>().toDomain()
        }
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
                    mimeType = type.guessMime(),
                ),
            )
        }.body<RecursoPreguntaDto>().toDomain()
    }

    override suspend fun deleteResource(questionId: String, resourceId: String) = adapter.safeCall {
        client.delete("${contentUrl()}/preguntas/$questionId/recursos/$resourceId") { backendHeaders() }
            .requireBackendSuccess("/content/preguntas/$questionId/recursos/$resourceId")
        Unit
    }

    override suspend fun uploadExamCover(
        examId: String,
        localPathOrUri: String,
    ) = adapter.safeCall {
        val asset = when (val read = fileReader.read(localPathOrUri)) {
            is AppResult.Failure -> throw IllegalStateException(read.error.message)
            is AppResult.Success -> read.value
        }
        require(asset.bytes.size <= COVER_MAX_BYTES) { "La portada supera el limite de 10 MB." }
        require(asset.mimeType?.startsWith("image/") == true) { "La portada debe ser una imagen." }
        val response = client.post("${contentUrl()}/solucionarios/$examId/portada") {
            expectSuccess = false
            backendHeaders()
            setBody(asset.toMultipartBody())
        }
        response.requireBackendSuccess("/content/solucionarios/$examId/portada")
        response.body<SolucionarioDto>().toDomain(
            specialtiesFlow.value.firstOrNull { it.id == examsFlow.value.firstOrNull { exam -> exam.id == examId }?.specialtyId }?.name.orEmpty(),
        ).also { saved ->
            examsFlow.value = examsFlow.value.map { if (it.id == saved.id) saved else it }
            refreshExams()
        }
    }

    override suspend fun clearExamCover(examId: String) = adapter.safeCall {
        val response = client.delete("${contentUrl()}/solucionarios/$examId/portada") {
            expectSuccess = false
            backendHeaders()
        }
        response.requireBackendSuccess("/content/solucionarios/$examId/portada")
        response.body<SolucionarioDto>().toDomain(
            specialtiesFlow.value.firstOrNull { it.id == examsFlow.value.firstOrNull { exam -> exam.id == examId }?.specialtyId }?.name.orEmpty(),
        ).also { saved ->
            examsFlow.value = examsFlow.value.map { if (it.id == saved.id) saved else it }
            refreshExams()
        }
    }

    private suspend inline fun <reified T, reified B> HttpClient.requestWithBody(
        url: String,
        create: Boolean,
        body: B,
    ): T {
        val response = if (create) {
            post(url) {
                expectSuccess = false
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            put(url) {
                expectSuccess = false
                backendHeaders()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        response.requireBackendSuccess(url.substringAfter(contentUrl(), "/content"))
        return response.body()
    }

    private suspend fun HttpResponse.requireBackendSuccess(endpoint: String) {
        println("backend ${status.value} $endpoint")
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(200)}")
        }
    }

    private fun contentUrl(): String = "${appConfig.baseUrl.trimEnd('/')}/content"

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() }
            ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun LocalBinaryAsset.toMultipartBody(): MultiPartFormDataContent {
        return MultiPartFormDataContent(
            formData {
                append(
                    key = "file",
                    value = bytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${fileName.sanitizeFilename()}\"")
                        append(HttpHeaders.ContentType, mimeType ?: ContentType.Application.OctetStream.toString())
                    },
                )
            },
        )
    }

    private fun String.sanitizeFilename(): String = replace("\"", "").ifBlank { "upload.bin" }

    private companion object {
        const val COVER_MAX_BYTES = 10 * 1024 * 1024
        const val RESOURCE_MAX_BYTES = 50 * 1024 * 1024
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
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val orden: Int = 0,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
)

@Serializable
private data class RafagaDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val questionCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
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
private data class RafagaPreguntaDto(
    val id: String = "",
    val rafagaId: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
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
    val mimeType: String? = null,
    val orden: Int = 0,
)

@Serializable
private data class OrderDto(val ids: List<String>)

@Serializable
private data class V1DataResponse<T>(
    val data: List<T> = emptyList(),
)

@Serializable
private data class V1SingleDataResponse<T>(
    val data: T,
)

/** Contrato de `/api/v1/admin/especialidades/{id}/capacidades`. */
@Serializable
private data class SpecialtyCapabilitiesDto(
    val especialidadId: String = "",
    val permiteRafagas: Boolean = true,
    val permiteRazonados: Boolean = false,
    val permiteInmersivos: Boolean = false,
    val permiteDemos: Boolean = true,
    val permiteOposimarket: Boolean = true,
    val mensajeLimitacion: String? = null,
    val actualizadoEn: String? = null,
    val configurado: Boolean = true,
)

private fun SpecialtyCapabilitiesDto.toDomain() = SpecialtyCapabilities(
    permiteRafagas = permiteRafagas,
    permiteRazonados = permiteRazonados,
    permiteInmersivos = permiteInmersivos,
    permiteDemos = permiteDemos,
    permiteOposimarket = permiteOposimarket,
    specialtyId = especialidadId,
    limitationMessage = mensajeLimitacion,
    configured = configurado,
)

private fun SpecialtyCapabilities.toDto() = SpecialtyCapabilitiesDto(
    especialidadId = specialtyId,
    permiteRafagas = permiteRafagas,
    permiteRazonados = permiteRazonados,
    permiteInmersivos = permiteInmersivos,
    permiteDemos = permiteDemos,
    permiteOposimarket = permiteOposimarket,
    mensajeLimitacion = limitationMessage?.takeIf { it.isNotBlank() },
)

@Serializable
private data class V1EspecialidadDto(
    val id: String = "",
    val nombre: String = "",
    val slug: String = "",
)

@Serializable
private data class V1ExamenDto(
    val id: String = "",
    val codigoExamen: String = "",
    val especialidadId: String = "",
    val comunidadId: String? = null,
    val titulo: String = "",
    val descripcion: String = "",
    val tipoExamen: String = "",
    val tipoAcceso: String = "",
    val totalPreguntas: Int = 0,
    val duracionMinutos: Int? = null,
    val anio: Int? = null,
    val esOficial: Boolean = false,
    val publicadoEn: String? = null,
)

@Serializable
private data class V1PreguntasExamenDto(
    val examenId: String = "",
    val preguntas: List<V1PreguntaExamenDto> = emptyList(),
)

@Serializable
private data class V1PreguntaExamenDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val titulo: String = "",
    val tipoPregunta: String = "",
    val posicion: Int = 0,
    val opciones: List<V1OpcionPreguntaDto> = emptyList(),
)

@Serializable
private data class V1OpcionPreguntaDto(
    val id: String = "",
    val letra: String = "",
    val texto: String = "",
)

private fun V1EspecialidadDto.toDomain() = Specialty(
    id = id,
    name = nombre,
    description = slug,
    isActive = true,
    sortOrder = 0,
    linkedExams = 0,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun V1ExamenDto.toDomain(specialtyName: String) = Exam(
    id = id,
    code = codigoExamen,
    specialtyId = especialidadId,
    communityId = comunidadId,
    specialtyName = specialtyName,
    title = titulo,
    description = descripcion,
    typeExam = tipoExamen,
    typeAccess = tipoAcceso,
    priceTickets = if (tipoAcceso == "premium") 1 else 0,
    status = ExamStatus.PUBLISHED,
    publishFrom = null,
    publishedAt = publicadoEn.toInstantOrNull(),
    sortOrder = anio ?: 0,
    questionCount = totalPreguntas,
    durationMinutes = duracionMinutos,
    year = anio,
    isOfficial = esOficial,
    coverUrl = null,
    coverStoragePath = null,
    coverMimeType = null,
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
    deletedAt = null,
)

private fun V1PreguntaExamenDto.toDomain(examId: String) = ExamQuestion(
    id = id,
    examId = examId,
    title = codigoPregunta.ifBlank { "Pregunta $posicion" },
    statement = titulo,
    sortOrder = posicion,
    options = opciones.mapIndexed { index, option -> option.toDomain(questionId = id, index = index) }
        .sortedBy { it.sortOrder },
    resources = emptyList(),
    createdAt = nowInstant(),
    updatedAt = nowInstant(),
)

private fun V1OpcionPreguntaDto.toDomain(questionId: String, index: Int) = QuestionOption(
    id = id,
    questionId = questionId,
    label = letra,
    content = texto,
    isCorrect = false,
    sortOrder = index,
)

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
    code = "",
    specialtyId = especialidadId,
    specialtyName = specialtyName.ifBlank { descripcion },
    communityId = null,
    title = titulo,
    description = descripcion,
    typeExam = "inmersivo",
    typeAccess = if (precioTickets > 0) "premium" else "gratuito",
    priceTickets = precioTickets,
    status = estado.toExamStatus(),
    publishFrom = publicarDesde.toInstantOrNull(),
    publishedAt = publishedAt.toInstantOrNull(),
    sortOrder = orden,
    questionCount = 0,
    durationMinutes = null,
    year = null,
    isOfficial = false,
    coverUrl = portadaUrl,
    coverStoragePath = portadaStoragePath,
    coverMimeType = portadaMimeType,
    createdAt = createdAt.toInstantOrNow(),
    updatedAt = updatedAt.toInstantOrNow(),
    deletedAt = deletedAt.toInstantOrNull(),
)

private fun RafagaDto.toDomain(specialtyName: String) = Rafaga(
    id = id,
    specialtyId = especialidadId,
    specialtyName = specialtyName,
    title = titulo,
    priceTickets = precioTickets,
    status = estado.toExamStatus(),
    publishFrom = publicarDesde.toInstantOrNull(),
    publishedAt = publishedAt.toInstantOrNull(),
    questionCount = questionCount,
    createdAt = createdAt.toInstantOrNow(),
    updatedAt = updatedAt.toInstantOrNow(),
    deletedAt = deletedAt.toInstantOrNull(),
)

private fun Exam.toDto() = SolucionarioDto(
    id = id,
    titulo = title,
    especialidadId = specialtyId,
    descripcion = description,
    precioTickets = priceTickets,
    estado = status.name.lowercase(),
    publicarDesde = publishFrom?.toString(),
    publishedAt = publishedAt?.toString(),
    orden = sortOrder,
    portadaUrl = coverUrl,
    portadaStoragePath = coverStoragePath,
    portadaMimeType = coverMimeType,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

private fun Rafaga.toDto() = RafagaDto(
    id = id,
    titulo = title,
    especialidadId = specialtyId,
    precioTickets = priceTickets,
    estado = status.name.lowercase(),
    publicarDesde = publishFrom?.toString(),
    publishedAt = publishedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
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

private fun RafagaPreguntaDto.toDomain() = RafagaQuestion(
    id = id,
    rafagaId = rafagaId,
    statement = enunciado,
    sortOrder = orden,
    createdAt = createdAt.toInstantOrNow(),
    updatedAt = updatedAt.toInstantOrNow(),
    deletedAt = deletedAt.toInstantOrNull(),
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

private fun RafagaQuestion.toDto() = RafagaPreguntaDto(
    id = id,
    rafagaId = rafagaId,
    enunciado = statement,
    orden = sortOrder,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
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
    mimeType = mimeType,
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
    mimeType = mimeType,
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

private fun String.toSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "item" }

private fun String.toInstantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrElse { nowInstant() }

private fun String?.toInstantOrNull(): Instant? = this?.let { value ->
    runCatching { Instant.parse(value) }.getOrNull()
}

private fun nowInstant(): Instant = Instant.parse(Clock.System.now().toString())
