package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.BurstCampaignResponse
import com.oposicontrol.admin.core.api.BurstCampaignType
import com.oposicontrol.admin.core.api.CommerceStatus
import com.oposicontrol.admin.core.api.CursorPage
import com.oposicontrol.admin.core.api.ExamAccessType
import com.oposicontrol.admin.core.api.ExamQuestionResponse
import com.oposicontrol.admin.core.api.ExamResponse
import com.oposicontrol.admin.core.api.ExamType
import com.oposicontrol.admin.core.api.LimitedResourceResponse
import com.oposicontrol.admin.core.api.PublicationStatus
import com.oposicontrol.admin.core.api.QuestionOptionInput
import com.oposicontrol.admin.core.api.QuestionOptionResponse
import com.oposicontrol.admin.core.api.QuestionResourceResponse
import com.oposicontrol.admin.core.api.QuestionResourceType
import com.oposicontrol.admin.core.api.SpecialtyResponse
import com.oposicontrol.admin.core.api.StoreItemResponse
import com.oposicontrol.admin.core.api.StoreItemType
import com.oposicontrol.admin.core.api.StoreOfferResponse
import com.oposicontrol.admin.core.api.UpsertBurstCampaignRequest
import com.oposicontrol.admin.core.api.UpsertExamRequest
import com.oposicontrol.admin.core.api.UpsertLimitedResourceRequest
import com.oposicontrol.admin.core.api.UpsertQuestionRequest
import com.oposicontrol.admin.core.api.UpsertQuestionResourceRequest
import com.oposicontrol.admin.core.api.UpsertSpecialtyRequest
import com.oposicontrol.admin.core.api.UpsertStoreItemRequest
import com.oposicontrol.admin.core.api.UpsertStoreOfferRequest
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal open class CanonicalAdminGateway {
    open suspend fun listSpecialties(
        actor: AdminPrincipal,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): CursorPage<SpecialtyResponse> = unsupported()
    open suspend fun saveSpecialty(
        id: Long?,
        request: UpsertSpecialtyRequest,
        actor: AdminPrincipal,
    ): SpecialtyResponse = unsupported()

    open suspend fun listExams(
        type: ExamType?,
        actor: AdminPrincipal,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): CursorPage<ExamResponse> = unsupported()
    open suspend fun saveExam(id: Long?, request: UpsertExamRequest, actor: AdminPrincipal): ExamResponse = unsupported()
    open suspend fun listQuestions(examId: Long, actor: AdminPrincipal): List<ExamQuestionResponse> = unsupported()
    open suspend fun saveQuestion(
        examId: Long,
        questionId: Long?,
        request: UpsertQuestionRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): ExamQuestionResponse = unsupported()

    open suspend fun importQuestions(
        examId: Long,
        questions: List<UpsertQuestionRequest>,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): List<ExamQuestionResponse> = unsupported()

    open suspend fun reorderQuestions(
        examId: Long,
        questionIds: List<Long>,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): List<ExamQuestionResponse> = unsupported()

    open suspend fun listOptions(questionId: Long, actor: AdminPrincipal): List<QuestionOptionResponse> = unsupported()
    open suspend fun replaceOptions(
        questionId: Long,
        options: List<QuestionOptionInput>,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): List<QuestionOptionResponse> = unsupported()

    open suspend fun listResources(questionId: Long, actor: AdminPrincipal): List<QuestionResourceResponse> = unsupported()
    open suspend fun saveResource(
        questionId: Long,
        resourceId: Long?,
        request: UpsertQuestionResourceRequest,
        actor: AdminPrincipal,
    ): QuestionResourceResponse = unsupported()

    open suspend fun softDeleteContent(
        resourceType: String,
        resourceId: Long,
        expectedVersion: Long?,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): Unit = unsupported()

    open suspend fun currentContentVersion(
        resourceType: String,
        resourceId: Long,
        actor: AdminPrincipal,
    ): Long = unsupported()

    open suspend fun uploadExamCover(
        examId: Long,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        actor: AdminPrincipal,
    ): ExamResponse = unsupported()

    open suspend fun clearExamCover(
        examId: Long,
        actor: AdminPrincipal,
    ): ExamResponse = unsupported()

    open suspend fun uploadQuestionResource(
        questionId: Long,
        resourceId: Long,
        type: QuestionResourceType,
        title: String,
        sortOrder: Int,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        actor: AdminPrincipal,
    ): QuestionResourceResponse = unsupported()

    open suspend fun listStoreItems(
        actor: AdminPrincipal,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): CursorPage<StoreItemResponse> = unsupported()
    open suspend fun saveStoreItem(
        id: Long?,
        request: UpsertStoreItemRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): StoreItemResponse = unsupported()

    open suspend fun listStoreOffers(
        actor: AdminPrincipal,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): CursorPage<StoreOfferResponse> = unsupported()
    open suspend fun saveStoreOffer(
        id: Long?,
        request: UpsertStoreOfferRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): StoreOfferResponse = unsupported()

    open suspend fun listLimitedResources(
        actor: AdminPrincipal,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): CursorPage<LimitedResourceResponse> = unsupported()
    open suspend fun saveLimitedResource(
        id: Long?,
        request: UpsertLimitedResourceRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): LimitedResourceResponse = unsupported()

    open suspend fun listBurstCampaigns(
        actor: AdminPrincipal,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): CursorPage<BurstCampaignResponse> = unsupported()
    open suspend fun saveBurstCampaign(
        id: Long?,
        request: UpsertBurstCampaignRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): BurstCampaignResponse = unsupported()

    private fun unsupported(): Nothing = throw NotImplementedError("Gateway operation not configured.")

    companion object {
        const val DEFAULT_PAGE_LIMIT = 50
    }
}

internal class SupabaseCanonicalAdminGateway(
    private val configuration: BackendConfiguration,
    private val supabaseHttp: SupabaseHttp,
) : CanonicalAdminGateway() {
    override suspend fun listSpecialties(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<SpecialtyResponse> {
        validatePage(cursor, limit)
        val rows = getRows<SpecialtyRow>(
            "/rest/v1/especialidad?select=*&deleted_at=is.null${cursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        )
        val counts = getRows<ExamSpecialtyRow>(
            "/rest/v1/examen?select=especialidad_id&deleted_at=is.null",
            actor,
        ).groupingBy(ExamSpecialtyRow::specialtyId).eachCount()
        return rows.toCursorPage(limit, SpecialtyRow::id) { it.toResponse(counts[it.id] ?: 0) }
    }

    override suspend fun saveSpecialty(
        id: Long?,
        request: UpsertSpecialtyRequest,
        actor: AdminPrincipal,
    ): SpecialtyResponse {
        require(request.name.isNotBlank()) { "La especialidad necesita nombre." }
        require(request.slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Slug no válido." }
        val payload = buildJsonObject {
            put("nombre", request.name.trim())
            put("slug", request.slug)
            put("descripcion", request.description.trim())
            put("activa", request.active)
            put("orden", request.sortOrder)
        }
        val row = saveVersioned<SpecialtyRow>(
            table = "especialidad",
            id = id,
            expectedVersion = request.expectedVersion,
            payload = payload,
            actor = actor,
        )
        return row.toResponse(linkedExamCount = 0)
    }

    override suspend fun listExams(
        type: ExamType?,
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<ExamResponse> {
        validatePage(cursor, limit)
        val typeFilter = type?.databaseValue()?.let { "&tipo_examen=eq.${it.postgrest()}" }.orEmpty()
        val rows = getRows<CanonicalExamRow>(
            "/rest/v1/examen?select=*&deleted_at=is.null$typeFilter${cursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        )
        val specialties = getRows<SpecialtyNameRow>(
            "/rest/v1/especialidad?select=id,nombre&deleted_at=is.null",
            actor,
        ).associate { it.id to it.name }
        val counts = getRows<QuestionExamRow>(
            "/rest/v1/pregunta?select=examen_id&deleted_at=is.null",
            actor,
        ).groupingBy(QuestionExamRow::examId).eachCount()
        return rows.toCursorPage(limit, CanonicalExamRow::id) { row ->
            row.toResponse(
                specialtyName = specialties[row.specialtyId].orEmpty(),
                questionCount = counts[row.id] ?: 0,
                coverUrl = row.coverStoragePath?.let { signStorage(COVER_BUCKET, it, actor) }
                    ?: row.coverUrl,
            )
        }
    }

    override suspend fun saveExam(
        id: Long?,
        request: UpsertExamRequest,
        actor: AdminPrincipal,
    ): ExamResponse {
        validateExam(request)
        val payload = buildJsonObject {
            put("especialidad_id", request.specialtyId)
            put("codigo", request.code.trim())
            put("titulo", request.title.trim())
            put("descripcion", request.description.trim())
            put("tipo_examen", request.type.databaseValue())
            put("tipo_acceso", request.accessType.databaseValue())
            put("precio_tickets", request.priceTickets)
            put("estado", request.status.databaseValue())
            putNullable("publicar_desde", request.publishFrom)
            put("orden", request.sortOrder)
            putNullable("duracion_minutos", request.durationMinutes)
            putNullable("anio", request.year)
            put("oficial", request.official)
        }
        val row = saveVersioned<CanonicalExamRow>(
            table = "examen",
            id = id,
            expectedVersion = request.expectedVersion,
            payload = payload,
            actor = actor,
        )
        val specialtyName = getRows<SpecialtyNameRow>(
            "/rest/v1/especialidad?select=id,nombre&id=eq.${row.specialtyId}&limit=1",
            actor,
        ).firstOrNull()?.name.orEmpty()
        return row.toResponse(
            specialtyName = specialtyName,
            questionCount = 0,
            coverUrl = row.coverStoragePath?.let { signStorage(COVER_BUCKET, it, actor) } ?: row.coverUrl,
        )
    }

    override suspend fun listQuestions(examId: Long, actor: AdminPrincipal): List<ExamQuestionResponse> {
        val questions = getRows<CanonicalQuestionRow>(
            "/rest/v1/pregunta?select=*&examen_id=eq.$examId&deleted_at=is.null&order=orden.asc,id.asc&limit=$MAX_COLLECTION_ITEMS",
            actor,
        )
        if (questions.isEmpty()) return emptyList()
        val questionIds = questions.joinToString(",") { it.id.toString() }
        val options = getRows<CanonicalOptionRow>(
            "/rest/v1/opcion_pregunta?select=*&pregunta_id=in.($questionIds)&deleted_at=is.null&order=orden.asc,id.asc&limit=${MAX_COLLECTION_ITEMS * OPTIONS_PER_QUESTION}",
            actor,
        ).groupBy(CanonicalOptionRow::questionId)
        val resources = getRows<CanonicalResourceRow>(
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=in.($questionIds)&deleted_at=is.null&order=orden.asc,id.asc&limit=${MAX_COLLECTION_ITEMS * MAX_RESOURCES_PER_QUESTION}",
            actor,
        ).groupBy(CanonicalResourceRow::questionId)
        return questions.map { question ->
            question.toResponse(
                options = options[question.id].orEmpty().map(CanonicalOptionRow::toResponse),
                resources = resources[question.id].orEmpty().map { it.toResponse(actor) },
            )
        }
    }

    override suspend fun saveQuestion(
        examId: Long,
        questionId: Long?,
        request: UpsertQuestionRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): ExamQuestionResponse {
        validateQuestion(request)
        val mutation = supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_save_exam_question",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_exam_id", examId)
                putNullable("p_question_id", questionId)
                put("p_title", request.title.trim())
                put("p_statement", request.statement.trim())
                put("p_sort_order", request.sortOrder)
                put("p_options", request.options.toRpcOptions())
                putNullable("p_expected_version", request.expectedVersion)
                put("p_idempotency_key", idempotencyKey)
            },
        ).body.let { supabaseHttp.decode<RpcMutationResult>(it) }
        return getQuestion(examId, mutation.questionId ?: throw UpstreamProblem(), actor)
    }

    override suspend fun importQuestions(
        examId: Long,
        questions: List<UpsertQuestionRequest>,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): List<ExamQuestionResponse> {
        require(questions.size in 1..MAX_IMPORT_QUESTIONS) { "La importación admite entre 1 y 50 preguntas." }
        questions.forEach(::validateImportQuestion)
        val normalized = buildJsonArray {
            questions.forEach { question ->
                add(
                    buildJsonObject {
                        put("title", question.title.trim())
                        put("statement", question.statement.trim())
                        put("sortOrder", question.sortOrder)
                        put(
                            "options",
                            buildJsonArray {
                                question.options.forEach { option ->
                                    add(
                                        buildJsonObject {
                                            put("label", option.label.trim())
                                            put("content", option.content.trim())
                                            put("correct", option.correct)
                                            put("sortOrder", option.sortOrder)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
        supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_import_exam_questions",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_exam_id", examId)
                put("p_questions", normalized)
                put("p_idempotency_key", idempotencyKey)
            },
        )
        return listQuestions(examId, actor)
    }

    override suspend fun reorderQuestions(
        examId: Long,
        questionIds: List<Long>,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): List<ExamQuestionResponse> {
        require(questionIds.isNotEmpty()) { "El orden de preguntas no puede estar vacío." }
        require(questionIds.distinct().size == questionIds.size) { "El orden contiene preguntas duplicadas." }
        supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_reorder_exam_questions",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_exam_id", examId)
                put("p_question_ids", JsonArray(questionIds.map(::JsonPrimitive)))
                put("p_idempotency_key", idempotencyKey)
            },
        )
        return listQuestions(examId, actor)
    }

    override suspend fun listOptions(
        questionId: Long,
        actor: AdminPrincipal,
    ): List<QuestionOptionResponse> = getRows<CanonicalOptionRow>(
        "/rest/v1/opcion_pregunta?select=*&pregunta_id=eq.$questionId&deleted_at=is.null&order=orden.asc,id.asc&limit=$OPTIONS_PER_QUESTION",
        actor,
    ).map(CanonicalOptionRow::toResponse)

    override suspend fun replaceOptions(
        questionId: Long,
        options: List<QuestionOptionInput>,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): List<QuestionOptionResponse> {
        validateOptions(options)
        val question = getRows<CanonicalQuestionRow>(
            "/rest/v1/pregunta?select=*&id=eq.$questionId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull() ?: throw NotFoundProblem("Pregunta")
        supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_save_exam_question",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_exam_id", question.examId)
                put("p_question_id", question.id)
                put("p_title", question.titulo)
                put("p_statement", question.enunciado)
                put("p_sort_order", question.orden)
                put("p_options", options.toRpcOptions())
                put("p_expected_version", question.version)
                put("p_idempotency_key", idempotencyKey)
            },
        )
        return listOptions(questionId, actor)
    }

    override suspend fun listResources(
        questionId: Long,
        actor: AdminPrincipal,
    ): List<QuestionResourceResponse> = getRows<CanonicalResourceRow>(
        "/rest/v1/recurso_pregunta?select=*&pregunta_id=eq.$questionId&deleted_at=is.null&order=orden.asc,id.asc&limit=$MAX_RESOURCES_PER_QUESTION",
        actor,
    ).map { it.toResponse(actor) }

    override suspend fun saveResource(
        questionId: Long,
        resourceId: Long?,
        request: UpsertQuestionResourceRequest,
        actor: AdminPrincipal,
    ): QuestionResourceResponse {
        validateResource(request)
        val storagePath = request.storagePath?.normalizeStoragePath(RESOURCE_BUCKET)
        val payload = buildJsonObject {
            put("pregunta_id", questionId)
            put("tipo_recurso", request.type.databaseValue())
            put("titulo", request.title.trim())
            putNullable("texto", request.textBody)
            put("url", "")
            putNullable("storage_path", storagePath)
            put("public_url", JsonNull)
            putNullable("mime_type", request.mimeType)
            put("orden", request.sortOrder)
        }
        val row = saveVersioned<CanonicalResourceRow>(
            table = "recurso_pregunta",
            id = resourceId,
            expectedVersion = request.expectedVersion,
            payload = payload,
            actor = actor,
        )
        return row.toResponse(actor)
    }

    override suspend fun softDeleteContent(
        resourceType: String,
        resourceId: Long,
        expectedVersion: Long?,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ) {
        val currentVersion = expectedVersion ?: currentContentVersion(resourceType, resourceId, actor)
        supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_soft_delete_content",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                put("p_resource_type", resourceType)
                put("p_resource_id", resourceId)
                put("p_expected_version", currentVersion)
                put("p_idempotency_key", idempotencyKey)
            },
        )
    }

    override suspend fun currentContentVersion(
        resourceType: String,
        resourceId: Long,
        actor: AdminPrincipal,
    ): Long {
        val table = when (resourceType) {
            "specialty" -> "especialidad"
            "exam" -> "examen"
            "question" -> "pregunta"
            "resource" -> "recurso_pregunta"
            else -> throw BadRequestProblem("Tipo de contenido no válido.")
        }
        return getRows<VersionRow>(
            "/rest/v1/$table?select=version&id=eq.$resourceId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull()?.version ?: throw NotFoundProblem("Contenido")
    }

    override suspend fun uploadExamCover(
        examId: Long,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        actor: AdminPrincipal,
    ): ExamResponse {
        require(mimeType in COVER_MIME_TYPES) { "Formato de portada no permitido." }
        require(bytes.isNotEmpty() && bytes.size <= COVER_MAX_BYTES) { "Tamaño de portada no válido." }
        val current = getExamRow(examId, actor)
        val objectPath = "exams/$examId/covers/${UUID.randomUUID()}-${fileName.sanitizeStorageFileName()}"
        uploadStorageObject(COVER_BUCKET, objectPath, mimeType, bytes, actor)
        val saved = try {
            saveVersioned<CanonicalExamRow>(
                table = "examen",
                id = examId,
                expectedVersion = current.version,
                payload = buildJsonObject {
                    put("portada_url", JsonNull)
                    put("portada_storage_path", objectPath)
                    put("portada_mime_type", mimeType)
                },
                actor = actor,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                cleanupStorageObject(COVER_BUCKET, objectPath, "cover_db_write_failed", actor)
            }
            throw error
        }
        current.coverStoragePath
            ?.takeIf { it != objectPath }
            ?.let { enqueueStorageCleanup(COVER_BUCKET, it, "cover_replaced") }
        return saved.toFullResponse(actor)
    }

    override suspend fun clearExamCover(
        examId: Long,
        actor: AdminPrincipal,
    ): ExamResponse {
        val current = getExamRow(examId, actor)
        val saved = saveVersioned<CanonicalExamRow>(
            table = "examen",
            id = examId,
            expectedVersion = current.version,
            payload = buildJsonObject {
                put("portada_url", JsonNull)
                put("portada_storage_path", JsonNull)
                put("portada_mime_type", JsonNull)
            },
            actor = actor,
        )
        current.coverStoragePath?.let { enqueueStorageCleanup(COVER_BUCKET, it, "cover_cleared") }
        return saved.toFullResponse(actor)
    }

    override suspend fun uploadQuestionResource(
        questionId: Long,
        resourceId: Long,
        type: QuestionResourceType,
        title: String,
        sortOrder: Int,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        actor: AdminPrincipal,
    ): QuestionResourceResponse {
        require(mimeType in RESOURCE_MIME_TYPES) { "Formato de recurso no permitido." }
        require(bytes.isNotEmpty() && bytes.size <= RESOURCE_MAX_BYTES) { "Tamaño de recurso no válido." }
        require(type != QuestionResourceType.TEXT) { "Los recursos de texto no se suben como archivo." }
        val current = getRows<CanonicalResourceRow>(
            "/rest/v1/recurso_pregunta?select=*&id=eq.$resourceId&pregunta_id=eq.$questionId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull() ?: throw NotFoundProblem("Recurso")
        val objectPath =
            "questions/$questionId/resources/${UUID.randomUUID()}-${fileName.sanitizeStorageFileName()}"
        uploadStorageObject(RESOURCE_BUCKET, objectPath, mimeType, bytes, actor)
        val saved = try {
            saveResource(
                questionId = questionId,
                resourceId = resourceId,
                request = UpsertQuestionResourceRequest(
                    type = type,
                    title = title.ifBlank { fileName },
                    storagePath = objectPath,
                    mimeType = mimeType,
                    sortOrder = sortOrder,
                    expectedVersion = current.version,
                ),
                actor = actor,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                cleanupStorageObject(RESOURCE_BUCKET, objectPath, "resource_db_write_failed", actor)
            }
            throw error
        }
        current.storagePath
            ?.takeIf { it != objectPath }
            ?.let { enqueueStorageCleanup(RESOURCE_BUCKET, it, "resource_replaced") }
        return saved
    }

    override suspend fun listStoreItems(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<StoreItemResponse> {
        validatePage(cursor, limit)
        return getRows<StoreItemRow>(
            "/rest/v1/articulo_tienda?select=*&deleted_at=is.null${cursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        ).toCursorPage(limit, StoreItemRow::id, StoreItemRow::toResponse)
    }

    override suspend fun saveStoreItem(
        id: Long?,
        request: UpsertStoreItemRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): StoreItemResponse {
        require(request.name.isNotBlank()) { "El artículo necesita nombre." }
        require(request.priceTickets >= 0) { "El precio no puede ser negativo." }
        val resourceId = executeCommerceRpc(
            rpc = "admin_upsert_store_item",
            id = id,
            expectedVersion = request.expectedVersion,
            payload = buildJsonObject {
                put("name", request.name.trim())
                put("description", request.description.trim())
                put("type", request.type.databaseValue())
                put("priceTickets", request.priceTickets)
                put("status", request.status.databaseValue())
                put("sortOrder", request.sortOrder)
            },
            idempotencyKey = idempotencyKey,
            actor = actor,
        )
        return getRows<StoreItemRow>(
            "/rest/v1/articulo_tienda?select=*&id=eq.$resourceId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull()?.toResponse() ?: throw UpstreamProblem()
    }

    override suspend fun listStoreOffers(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<StoreOfferResponse> {
        validatePage(cursor, limit)
        return getRows<StoreOfferRow>(
            "/rest/v1/oferta_tienda?select=*&deleted_at=is.null${cursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        ).toCursorPage(limit, StoreOfferRow::id, StoreOfferRow::toResponse)
    }

    override suspend fun saveStoreOffer(
        id: Long?,
        request: UpsertStoreOfferRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): StoreOfferResponse {
        require(request.itemId > 0) { "Artículo no válido." }
        require(request.title.isNotBlank()) { "La oferta necesita título." }
        require(request.discountPercent != null || request.specialPriceTickets != null) {
            "La oferta necesita descuento o precio especial."
        }
        require(request.discountPercent == null || request.discountPercent in 1..100) { "Descuento no válido." }
        request.specialPriceTickets?.let { require(it >= 0) { "Precio especial no válido." } }
        val resourceId = executeCommerceRpc(
            rpc = "admin_upsert_store_offer",
            id = id,
            expectedVersion = request.expectedVersion,
            payload = buildJsonObject {
                put("itemId", request.itemId)
                put("title", request.title.trim())
                putNullable("discountPercent", request.discountPercent)
                putNullable("specialPriceTickets", request.specialPriceTickets)
                putNullable("startsAt", request.startsAt)
                putNullable("endsAt", request.endsAt)
                put("status", request.status.databaseValue())
            },
            idempotencyKey = idempotencyKey,
            actor = actor,
        )
        return getRows<StoreOfferRow>(
            "/rest/v1/oferta_tienda?select=*&id=eq.$resourceId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull()?.toResponse() ?: throw UpstreamProblem()
    }

    override suspend fun listLimitedResources(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<LimitedResourceResponse> {
        validatePage(cursor, limit)
        return getRows<LimitedResourceRow>(
            "/rest/v1/recurso_temporal_tienda?select=*&deleted_at=is.null${cursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        ).toCursorPage(limit, LimitedResourceRow::id, LimitedResourceRow::toResponse)
    }

    override suspend fun saveLimitedResource(
        id: Long?,
        request: UpsertLimitedResourceRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): LimitedResourceResponse {
        require(request.itemId > 0 && request.resourceId > 0) { "Artículo o recurso no válido." }
        require(request.title.isNotBlank()) { "El recurso limitado necesita título." }
        require(request.accessDurationHours > 0) { "La duración de acceso debe ser positiva." }
        val savedResourceId = executeCommerceRpc(
            rpc = "admin_upsert_limited_resource",
            id = id,
            expectedVersion = request.expectedVersion,
            payload = buildJsonObject {
                put("itemId", request.itemId)
                put("resourceId", request.resourceId)
                put("title", request.title.trim())
                putNullable("startsAt", request.startsAt)
                putNullable("endsAt", request.endsAt)
                put("accessDurationHours", request.accessDurationHours)
                put("status", request.status.databaseValue())
            },
            idempotencyKey = idempotencyKey,
            actor = actor,
        )
        return getRows<LimitedResourceRow>(
            "/rest/v1/recurso_temporal_tienda?select=*&id=eq.$savedResourceId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull()?.toResponse() ?: throw UpstreamProblem()
    }

    override suspend fun listBurstCampaigns(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<BurstCampaignResponse> {
        validatePage(cursor, limit)
        return getRows<BurstCampaignRow>(
            "/rest/v1/campana_rafaga?select=*&deleted_at=is.null${cursorFilter(cursor)}&order=id.desc&limit=${limit + 1}",
            actor,
        ).toCursorPage(limit, BurstCampaignRow::id, BurstCampaignRow::toResponse)
    }

    override suspend fun saveBurstCampaign(
        id: Long?,
        request: UpsertBurstCampaignRequest,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): BurstCampaignResponse {
        require(request.title.isNotBlank()) { "La campaña necesita título." }
        require(request.multiplier >= 1.0) { "El multiplicador debe ser al menos 1." }
        require(request.rewardTickets >= 0) { "La recompensa no puede ser negativa." }
        val resourceId = executeCommerceRpc(
            rpc = "admin_upsert_burst_campaign",
            id = id,
            expectedVersion = request.expectedVersion,
            payload = buildJsonObject {
                put("title", request.title.trim())
                put("description", request.description.trim())
                put("type", request.type.databaseValue())
                putNullable("startsAt", request.startsAt)
                putNullable("endsAt", request.endsAt)
                put("multiplier", request.multiplier)
                put("rewardTickets", request.rewardTickets)
                putNullable("linkedExamId", request.linkedExamId)
                putNullable("linkedStoreItemId", request.linkedStoreItemId)
                put("status", request.status.databaseValue())
            },
            idempotencyKey = idempotencyKey,
            actor = actor,
        )
        return getRows<BurstCampaignRow>(
            "/rest/v1/campana_rafaga?select=*&id=eq.$resourceId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull()?.toResponse() ?: throw UpstreamProblem()
    }

    private suspend fun getExamRow(examId: Long, actor: AdminPrincipal): CanonicalExamRow =
        getRows<CanonicalExamRow>(
            "/rest/v1/examen?select=*&id=eq.$examId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull() ?: throw NotFoundProblem("Examen")

    private suspend fun CanonicalExamRow.toFullResponse(actor: AdminPrincipal): ExamResponse {
        val specialtyName = getRows<SpecialtyNameRow>(
            "/rest/v1/especialidad?select=id,nombre&id=eq.$specialtyId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull()?.name.orEmpty()
        val questionCount = getRows<QuestionIdRow>(
            "/rest/v1/pregunta?select=id&examen_id=eq.$id&deleted_at=is.null&limit=$MAX_COLLECTION_ITEMS",
            actor,
        ).size
        return toResponse(
            specialtyName = specialtyName,
            questionCount = questionCount,
            coverUrl = coverStoragePath?.let { signStorage(COVER_BUCKET, it, actor) } ?: coverUrl,
        )
    }

    private suspend fun uploadStorageObject(
        bucket: String,
        objectPath: String,
        mimeType: String,
        bytes: ByteArray,
        actor: AdminPrincipal,
    ) {
        val encodedPath = objectPath.split('/').joinToString("/") { it.postgrest() }
        supabaseHttp.actorBinaryRequest(
            method = HttpMethod.Post,
            path = "/storage/v1/object/$bucket/$encodedPath",
            actorAccessToken = actor.accessToken,
            bytes = bytes,
            mimeType = mimeType,
        )
    }

    private suspend fun cleanupStorageObject(
        bucket: String,
        objectPath: String,
        reason: String,
        actor: AdminPrincipal,
    ) {
        val encodedPath = objectPath.split('/').joinToString("/") { it.postgrest() }
        try {
            supabaseHttp.actorRequest(
                method = HttpMethod.Delete,
                path = "/storage/v1/object/$bucket/$encodedPath",
                actorAccessToken = actor.accessToken,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            enqueueStorageCleanup(bucket, objectPath, reason)
        }
    }

    private suspend fun enqueueStorageCleanup(
        bucket: String,
        objectPath: String,
        reason: String,
    ) {
        try {
            supabaseHttp.internalRequest(
                method = HttpMethod.Post,
                path = "/rest/v1/rpc/admin_enqueue_storage_cleanup",
                body = buildJsonObject {
                    put("p_bucket", bucket)
                    put("p_object_path", objectPath)
                    put("p_reason", reason.take(200))
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            System.err.println("[backend-storage] cleanup enqueue failed bucket=$bucket")
        }
    }

    private suspend fun getQuestion(
        examId: Long,
        questionId: Long,
        actor: AdminPrincipal,
    ): ExamQuestionResponse {
        val question = getRows<CanonicalQuestionRow>(
            "/rest/v1/pregunta?select=*&id=eq.$questionId&examen_id=eq.$examId&deleted_at=is.null&limit=1",
            actor,
        ).firstOrNull() ?: throw UpstreamProblem("No se pudo recuperar la pregunta guardada.")
        return question.toResponse(
            options = listOptions(questionId, actor),
            resources = listResources(questionId, actor),
        )
    }

    private suspend fun executeCommerceRpc(
        rpc: String,
        id: Long?,
        expectedVersion: Long?,
        payload: JsonObject,
        idempotencyKey: String,
        actor: AdminPrincipal,
    ): Long {
        val result = supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/$rpc",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject {
                putNullable("p_id", id)
                put("p_payload", payload)
                putNullable("p_expected_version", expectedVersion)
                put("p_idempotency_key", idempotencyKey)
            },
        ).body.let { supabaseHttp.decode<RpcMutationResult>(it) }
        return result.resourceId ?: throw UpstreamProblem()
    }

    private suspend inline fun <reified T> getRows(path: String, actor: AdminPrincipal): List<T> {
        return supabaseHttp.actorRequest(
            method = HttpMethod.Get,
            path = path,
            actorAccessToken = actor.accessToken,
        ).body.let(supabaseHttp::decodeList)
    }

    private suspend inline fun <reified T> saveVersioned(
        table: String,
        id: Long?,
        expectedVersion: Long?,
        payload: JsonObject,
        actor: AdminPrincipal,
    ): T {
        val response = if (id == null) {
            supabaseHttp.actorRequest(
                method = HttpMethod.Post,
                path = "/rest/v1/$table",
                actorAccessToken = actor.accessToken,
                body = payload,
                prefer = "return=representation",
            )
        } else {
            val version = expectedVersion ?: throw ConflictProblem("Falta expectedVersion para actualizar.")
            supabaseHttp.actorRequest(
                method = HttpMethod.Patch,
                path = "/rest/v1/$table?id=eq.$id&version=eq.$version",
                actorAccessToken = actor.accessToken,
                body = payload,
                prefer = "return=representation",
            )
        }
        val saved = supabaseHttp.decodeList<T>(response.body).firstOrNull()
        if (id != null && saved == null) throw ConflictProblem()
        return saved ?: throw UpstreamProblem("Supabase no devolvió el recurso guardado.")
    }

    private suspend fun signStorage(
        bucket: String,
        rawPath: String,
        actor: AdminPrincipal,
    ): String {
        val path = rawPath.normalizeStoragePath(bucket)
        val encodedPath = path.split('/').joinToString("/") { it.postgrest() }
        val response = supabaseHttp.actorRequest(
            method = HttpMethod.Post,
            path = "/storage/v1/object/sign/$bucket/$encodedPath",
            actorAccessToken = actor.accessToken,
            body = buildJsonObject { put("expiresIn", SIGNED_URL_SECONDS) },
        ).body.let { supabaseHttp.decode<SignedStorageUrl>(it) }
        return if (response.signedUrl.startsWith("http")) {
            response.signedUrl
        } else {
            "${configuration.supabaseUrl}/storage/v1${response.signedUrl}"
        }
    }

    private suspend fun CanonicalResourceRow.toResponse(actor: AdminPrincipal): QuestionResourceResponse {
        val normalizedPath = storagePath?.normalizeStoragePath(RESOURCE_BUCKET)
        return QuestionResourceResponse(
            id = id,
            type = type.toResourceType(),
            title = title,
            textBody = textBody,
            storagePath = normalizedPath,
            downloadUrl = normalizedPath?.let { signStorage(RESOURCE_BUCKET, it, actor) }
                ?: url.takeIf(String::isNotBlank),
            mimeType = mimeType,
            sortOrder = sortOrder,
            version = version,
        )
    }

    companion object {
        private const val COVER_BUCKET = "exam-covers"
        private const val RESOURCE_BUCKET = "exam-resources"
        private const val SIGNED_URL_SECONDS = 600
        private const val MAX_IMPORT_QUESTIONS = 50
        private const val MAX_COLLECTION_ITEMS = 100
        private const val OPTIONS_PER_QUESTION = 4
        private const val MAX_RESOURCES_PER_QUESTION = 100
        private const val COVER_MAX_BYTES = 10 * 1024 * 1024
        private const val RESOURCE_MAX_BYTES = 50 * 1024 * 1024
        private val COVER_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val RESOURCE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "video/mp4",
            "application/pdf",
            "text/plain",
        )
    }
}

private fun validatePage(cursor: Long?, limit: Int) {
    require(cursor == null || cursor > 0) { "Cursor no válido." }
    require(limit in 1..100) { "El límite debe estar entre 1 y 100." }
}

private fun cursorFilter(cursor: Long?): String = cursor?.let { "&id=lt.$it" }.orEmpty()

private inline fun <T, R> List<T>.toCursorPage(
    limit: Int,
    id: (T) -> Long,
    transform: (T) -> R,
): CursorPage<R> {
    val visible = take(limit)
    return CursorPage(
        items = visible.map(transform),
        nextCursor = if (size > limit) visible.lastOrNull()?.let(id)?.toString() else null,
    )
}

private fun List<QuestionOptionInput>.toRpcOptions(): JsonArray = buildJsonArray {
    forEach { option ->
        add(
            buildJsonObject {
                put("label", option.label.trim())
                put("content", option.content.trim())
                put("correct", option.correct)
                put("sortOrder", option.sortOrder)
            },
        )
    }
}

private fun validateExam(request: UpsertExamRequest) {
    require(request.specialtyId > 0) { "Especialidad no válida." }
    require(request.code.isNotBlank()) { "El examen necesita código." }
    require(request.title.isNotBlank()) { "El examen necesita título." }
    require(request.priceTickets >= 0) { "El precio no puede ser negativo." }
    request.durationMinutes?.let { require(it > 0) { "Duración no válida." } }
    request.year?.let { require(it in 1900..2200) { "Año no válido." } }
}

private fun validateQuestion(request: UpsertQuestionRequest) {
    require(request.statement.isNotBlank()) { "La pregunta necesita enunciado." }
    validateOptions(request.options)
}

private fun validateImportQuestion(request: UpsertQuestionRequest) {
    validateQuestion(request)
    require(request.options.size == 4) { "Cada pregunta importada necesita exactamente 4 opciones." }
    require(request.options.map { it.label.trim().lowercase() }.distinct().size == 4) {
        "Las etiquetas de las opciones importadas deben ser únicas."
    }
}

private fun validateOptions(options: List<QuestionOptionInput>) {
    require(options.size == 4) { "Una pregunta tipo test necesita exactamente 4 opciones." }
    require(options.count(QuestionOptionInput::correct) == 1) { "Debe existir exactamente una opción correcta." }
    require(options.all { it.label.isNotBlank() && it.content.isNotBlank() }) { "Las opciones no pueden estar vacías." }
    require(options.map { it.label.trim().lowercase() }.distinct().size == 4) {
        "Las etiquetas de las opciones deben ser únicas."
    }
}

private fun validateResource(request: UpsertQuestionResourceRequest) {
    require(request.title.isNotBlank()) { "El recurso necesita título." }
    if (request.type == QuestionResourceType.TEXT) {
        require(!request.textBody.isNullOrBlank()) { "El recurso de texto necesita contenido." }
    } else {
        require(!request.storagePath.isNullOrBlank()) { "El recurso necesita una ruta privada de Storage." }
    }
}

@Serializable
private data class SpecialtyRow(
    val id: Long,
    val nombre: String,
    val slug: String,
    val descripcion: String = "",
    val activa: Boolean = true,
    val orden: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Long = 1,
) {
    fun toResponse(linkedExamCount: Int) = SpecialtyResponse(
        id = id,
        name = nombre,
        slug = slug,
        description = descripcion,
        active = activa,
        sortOrder = orden,
        linkedExamCount = linkedExamCount,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class ExamSpecialtyRow(
    @SerialName("especialidad_id") val specialtyId: Long,
)

@Serializable
private data class SpecialtyNameRow(
    val id: Long,
    @SerialName("nombre") val name: String,
)

@Serializable
private data class QuestionExamRow(
    @SerialName("examen_id") val examId: Long,
)

@Serializable
private data class QuestionIdRow(val id: Long)

@Serializable
private data class CanonicalExamRow(
    val id: Long,
    @SerialName("especialidad_id") val specialtyId: Long,
    val codigo: String,
    val titulo: String,
    val descripcion: String = "",
    @SerialName("tipo_examen") val type: String = "inmersivo",
    @SerialName("tipo_acceso") val accessType: String = "premium",
    @SerialName("precio_tickets") val priceTickets: Long = 0,
    val estado: String = "draft",
    @SerialName("publicar_desde") val publishFrom: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val orden: Int = 0,
    @SerialName("duracion_minutos") val durationMinutes: Int? = null,
    val anio: Int? = null,
    val oficial: Boolean = false,
    @SerialName("portada_url") val coverUrl: String? = null,
    @SerialName("portada_storage_path") val coverStoragePath: String? = null,
    val version: Long = 1,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    fun toResponse(
        specialtyName: String,
        questionCount: Int,
        coverUrl: String?,
    ) = ExamResponse(
        id = id,
        specialtyId = specialtyId,
        specialtyName = specialtyName,
        code = codigo,
        title = titulo,
        description = descripcion,
        type = type.toExamType(),
        accessType = accessType.toExamAccessType(),
        priceTickets = priceTickets,
        status = estado.toPublicationStatus(),
        publishFrom = publishFrom,
        publishedAt = publishedAt,
        sortOrder = orden,
        questionCount = questionCount,
        durationMinutes = durationMinutes,
        year = anio,
        official = oficial,
        coverUrl = coverUrl,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class CanonicalQuestionRow(
    val id: Long,
    @SerialName("examen_id") val examId: Long,
    val titulo: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val version: Long = 1,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    fun toResponse(
        options: List<QuestionOptionResponse>,
        resources: List<QuestionResourceResponse>,
    ) = ExamQuestionResponse(
        id = id,
        examId = examId,
        title = titulo,
        statement = enunciado,
        sortOrder = orden,
        options = options,
        resources = resources,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class CanonicalOptionRow(
    val id: Long,
    @SerialName("pregunta_id") val questionId: Long,
    @SerialName("etiqueta") val label: String,
    @SerialName("contenido") val content: String,
    @SerialName("es_correcta") val correct: Boolean,
    @SerialName("orden") val sortOrder: Int = 0,
) {
    fun toResponse() = QuestionOptionResponse(id, label, content, correct, sortOrder)
}

@Serializable
private data class CanonicalResourceRow(
    val id: Long,
    @SerialName("pregunta_id") val questionId: Long,
    @SerialName("tipo_recurso") val type: String,
    @SerialName("titulo") val title: String = "",
    @SerialName("texto") val textBody: String? = null,
    val url: String = "",
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("orden") val sortOrder: Int = 0,
    val version: Long = 1,
)

@Serializable
private data class StoreItemRow(
    val id: Long,
    val nombre: String,
    val descripcion: String = "",
    @SerialName("tipo_articulo") val type: String,
    @SerialName("precio_tickets") val priceTickets: Long = 0,
    val estado: String = "draft",
    val orden: Int = 0,
    val version: Long = 1,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    fun toResponse() = StoreItemResponse(
        id = id,
        name = nombre,
        description = descripcion,
        type = type.toStoreItemType(),
        priceTickets = priceTickets,
        status = estado.toCommerceStatus(),
        sortOrder = orden,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class StoreOfferRow(
    val id: Long,
    @SerialName("articulo_tienda_id") val itemId: Long,
    val titulo: String,
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("special_price_tickets") val specialPriceTickets: Long? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val status: String = "draft",
    val version: Long = 1,
) {
    fun toResponse() = StoreOfferResponse(
        id = id,
        itemId = itemId,
        title = titulo,
        discountPercent = discountPercent,
        specialPriceTickets = specialPriceTickets,
        startsAt = startsAt,
        endsAt = endsAt,
        status = status.toCommerceStatus(),
        version = version,
    )
}

@Serializable
private data class LimitedResourceRow(
    val id: Long,
    @SerialName("articulo_tienda_id") val itemId: Long,
    @SerialName("recurso_id") val resourceId: Long,
    val titulo: String,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("access_duration_hours") val accessDurationHours: Int = 24,
    val status: String = "draft",
    val version: Long = 1,
) {
    fun toResponse() = LimitedResourceResponse(
        id = id,
        itemId = itemId,
        resourceId = resourceId,
        title = titulo,
        startsAt = startsAt,
        endsAt = endsAt,
        accessDurationHours = accessDurationHours,
        status = status.toCommerceStatus(),
        version = version,
    )
}

@Serializable
private data class BurstCampaignRow(
    val id: Long,
    val titulo: String,
    val descripcion: String = "",
    val tipo: String,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val multiplier: Double = 1.0,
    @SerialName("reward_tickets") val rewardTickets: Long = 0,
    @SerialName("linked_exam_id") val linkedExamId: Long? = null,
    @SerialName("linked_store_item_id") val linkedStoreItemId: Long? = null,
    val status: String = "draft",
    val version: Long = 1,
) {
    fun toResponse() = BurstCampaignResponse(
        id = id,
        title = titulo,
        description = descripcion,
        type = tipo.toBurstCampaignType(),
        startsAt = startsAt,
        endsAt = endsAt,
        multiplier = multiplier,
        rewardTickets = rewardTickets,
        linkedExamId = linkedExamId,
        linkedStoreItemId = linkedStoreItemId,
        status = status.toCommerceStatus(),
        version = version,
    )
}

@Serializable
private data class SignedStorageUrl(
    @SerialName("signedURL") val signedUrl: String,
)

@Serializable
private data class VersionRow(val version: Long)

@Serializable
private data class RpcMutationResult(
    val questionId: Long? = null,
    val resourceId: Long? = null,
    val version: Long,
    val auditId: Long,
)

private fun PublicationStatus.databaseValue(): String = when (this) {
    PublicationStatus.DRAFT -> "draft"
    PublicationStatus.PUBLISHED -> "published"
    PublicationStatus.ARCHIVED -> "archived"
}

private fun ExamType.databaseValue(): String = when (this) {
    ExamType.IMMERSIVE -> "inmersivo"
    ExamType.BURST -> "rafaga"
}

private fun ExamAccessType.databaseValue(): String = when (this) {
    ExamAccessType.FREE -> "gratuito"
    ExamAccessType.PREMIUM -> "premium"
}

private fun QuestionResourceType.databaseValue(): String = when (this) {
    QuestionResourceType.IMAGE -> "imagen"
    QuestionResourceType.AUDIO -> "audio"
    QuestionResourceType.VIDEO -> "video"
    QuestionResourceType.TEXT -> "texto"
    QuestionResourceType.DOCUMENT -> "documento"
}

private fun StoreItemType.databaseValue(): String = when (this) {
    StoreItemType.CONSUMABLE -> "consumable"
    StoreItemType.PROTECTOR -> "protector"
    StoreItemType.STREAK -> "streak"
    StoreItemType.POTENTIATOR -> "potentiator"
    StoreItemType.EXAM_ACCESS -> "exam_access"
    StoreItemType.RESOURCE -> "resource"
}

private fun BurstCampaignType.databaseValue(): String = when (this) {
    BurstCampaignType.DAILY_STREAK -> "daily_streak"
    BurstCampaignType.EXAM_BURST -> "exam_burst"
    BurstCampaignType.TICKET_MULTIPLIER -> "ticket_multiplier"
    BurstCampaignType.RESOURCE_UNLOCK -> "resource_unlock"
}

private fun CommerceStatus.databaseValue(): String = when (this) {
    CommerceStatus.DRAFT -> "draft"
    CommerceStatus.ACTIVE -> "active"
    CommerceStatus.SCHEDULED -> "scheduled"
    CommerceStatus.EXPIRED -> "expired"
    CommerceStatus.ARCHIVED -> "archived"
}

private fun String.toPublicationStatus(): PublicationStatus = when (this) {
    "published" -> PublicationStatus.PUBLISHED
    "archived" -> PublicationStatus.ARCHIVED
    else -> PublicationStatus.DRAFT
}

private fun String.toExamType(): ExamType = if (this == "rafaga") ExamType.BURST else ExamType.IMMERSIVE

private fun String.toExamAccessType(): ExamAccessType =
    if (this == "gratuito") ExamAccessType.FREE else ExamAccessType.PREMIUM

private fun String.toResourceType(): QuestionResourceType = when (this) {
    "imagen", "image" -> QuestionResourceType.IMAGE
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "texto", "text" -> QuestionResourceType.TEXT
    else -> QuestionResourceType.DOCUMENT
}

private fun String.toStoreItemType(): StoreItemType = when (this) {
    "protector" -> StoreItemType.PROTECTOR
    "streak" -> StoreItemType.STREAK
    "potentiator" -> StoreItemType.POTENTIATOR
    "exam_access" -> StoreItemType.EXAM_ACCESS
    "resource" -> StoreItemType.RESOURCE
    else -> StoreItemType.CONSUMABLE
}

private fun String.toBurstCampaignType(): BurstCampaignType = when (this) {
    "exam_burst" -> BurstCampaignType.EXAM_BURST
    "ticket_multiplier" -> BurstCampaignType.TICKET_MULTIPLIER
    "resource_unlock" -> BurstCampaignType.RESOURCE_UNLOCK
    else -> BurstCampaignType.DAILY_STREAK
}

private fun String.toCommerceStatus(): CommerceStatus = when (this) {
    "active" -> CommerceStatus.ACTIVE
    "scheduled" -> CommerceStatus.SCHEDULED
    "expired" -> CommerceStatus.EXPIRED
    "archived" -> CommerceStatus.ARCHIVED
    else -> CommerceStatus.DRAFT
}

private fun JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullable(name: String, value: Int?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullable(name: String, value: Long?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder

private fun String.postgrest(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun String.normalizeStoragePath(bucket: String): String {
    val normalized = trim().removePrefix("/").removePrefix("$bucket/")
    require(normalized.isNotBlank() && normalized.length <= 512) { "Ruta Storage no válida." }
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Ruta Storage no válida." }
    return normalized
}

private fun String.sanitizeStorageFileName(): String =
    lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(120)
        .ifBlank { "file" }
