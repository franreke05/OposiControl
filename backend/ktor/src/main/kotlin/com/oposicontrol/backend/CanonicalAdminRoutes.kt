package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.AdminApiHeaders
import com.oposicontrol.admin.core.api.AdminPrincipalResponse
import com.oposicontrol.admin.core.api.ApiFieldError
import com.oposicontrol.admin.core.api.ExamType
import com.oposicontrol.admin.core.api.ImportQuestionsRequest
import com.oposicontrol.admin.core.api.PublicationStatus
import com.oposicontrol.admin.core.api.QuestionOptionInput
import com.oposicontrol.admin.core.api.ReorderQuestionsRequest
import com.oposicontrol.admin.core.api.UpsertBurstCampaignRequest
import com.oposicontrol.admin.core.api.UpsertExamRequest
import com.oposicontrol.admin.core.api.UpsertLimitedResourceRequest
import com.oposicontrol.admin.core.api.UpsertQuestionRequest
import com.oposicontrol.admin.core.api.UpsertQuestionResourceRequest
import com.oposicontrol.admin.core.api.UpsertSpecialtyRequest
import com.oposicontrol.admin.core.api.UpsertStoreItemRequest
import com.oposicontrol.admin.core.api.UpsertStoreOfferRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun Route.canonicalAdminRoutes(gateway: CanonicalAdminGateway) {
    route("/api/admin/v1") {
        get("/auth/me") {
            val principal = call.adminPrincipal()
            call.respond(
                AdminPrincipalResponse(
                    authUserId = principal.authUserId.toString(),
                    userId = principal.userId,
                    displayName = principal.displayName,
                    email = principal.email,
                    roleIds = principal.roleIds,
                    permissions = principal.permissions,
                ),
            )
        }
        route("/specialties") {
            get {
                val actor = call.requirePermission("content.read")
                val page = call.adminPageRequest()
                call.respond(gateway.listSpecialties(actor, page.cursor, page.limit))
            }
            post {
                val actor = call.requirePermission("content.write")
                call.respond(
                    HttpStatusCode.Created,
                    gateway.saveSpecialty(id = null, request = call.receive<UpsertSpecialtyRequest>(), actor = actor),
                )
            }
            patch("/{specialtyId}") {
                val actor = call.requirePermission("content.write")
                call.respond(
                    gateway.saveSpecialty(
                        id = call.requiredLongPath("specialtyId"),
                        request = call.receive(),
                        actor = actor,
                    ),
                )
            }
        }
        route("/exams") {
            get {
                val actor = call.requirePermission("content.read")
                val type = when (call.request.queryParameters["type"]?.trim()?.lowercase()) {
                    null, "" -> null
                    "inmersivo" -> ExamType.IMMERSIVE
                    "rafaga" -> ExamType.BURST
                    else -> throw BadRequestProblem("Filtro type no válido.")
                }
                val page = call.adminPageRequest()
                call.respond(gateway.listExams(type, actor, page.cursor, page.limit))
            }
            post {
                val request = call.receive<UpsertExamRequest>()
                val actor = call.requireExamWritePermission(request)
                call.respond(HttpStatusCode.Created, gateway.saveExam(null, request, actor))
            }
            patch("/{examId}") {
                val request = call.receive<UpsertExamRequest>()
                val actor = call.requireExamWritePermission(request)
                call.respond(gateway.saveExam(call.requiredLongPath("examId"), request, actor))
            }
            route("/{examId}/questions") {
                get {
                    val actor = call.requirePermission("content.read")
                    call.respond(gateway.listQuestions(call.requiredLongPath("examId"), actor))
                }
                post {
                    val actor = call.requirePermission("content.write")
                    call.respond(
                        HttpStatusCode.Created,
                        gateway.saveQuestion(
                            examId = call.requiredLongPath("examId"),
                            questionId = null,
                            request = call.receive<UpsertQuestionRequest>(),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        ),
                    )
                }
                patch("/{questionId}") {
                    val actor = call.requirePermission("content.write")
                    call.respond(
                        gateway.saveQuestion(
                            examId = call.requiredLongPath("examId"),
                            questionId = call.requiredLongPath("questionId"),
                            request = call.receive(),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        ),
                    )
                }
                post("/import") {
                    val actor = call.requirePermission("content.write")
                    val idempotencyKey = call.requiredIdempotencyKey()
                    val request = call.receive<ImportQuestionsRequest>()
                    require(request.questions.size in 1..50) { "La importación admite entre 1 y 50 preguntas." }
                    call.respond(
                        HttpStatusCode.Created,
                        gateway.importQuestions(
                            examId = call.requiredLongPath("examId"),
                            questions = request.questions,
                            idempotencyKey = idempotencyKey,
                            actor = actor,
                        ),
                    )
                }
                put("/order") {
                    val actor = call.requirePermission("content.write")
                    val idempotencyKey = call.requiredIdempotencyKey()
                    val request = call.receive<ReorderQuestionsRequest>()
                    call.respond(
                        gateway.reorderQuestions(
                            examId = call.requiredLongPath("examId"),
                            questionIds = request.orderedQuestionIds,
                            idempotencyKey = idempotencyKey,
                            actor = actor,
                        ),
                    )
                }
            }
        }
        route("/questions/{questionId}") {
            route("/options") {
                get {
                    val actor = call.requirePermission("content.read")
                    call.respond(gateway.listOptions(call.requiredLongPath("questionId"), actor))
                }
                put {
                    val actor = call.requirePermission("content.write")
                    call.respond(
                        gateway.replaceOptions(
                            questionId = call.requiredLongPath("questionId"),
                            options = call.receive<List<QuestionOptionInput>>(),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        ),
                    )
                }
            }
            route("/resources") {
                get {
                    val actor = call.requirePermission("content.read")
                    call.respond(gateway.listResources(call.requiredLongPath("questionId"), actor))
                }
                post {
                    val actor = call.requirePermission("content.write")
                    call.respond(
                        HttpStatusCode.Created,
                        gateway.saveResource(
                            questionId = call.requiredLongPath("questionId"),
                            resourceId = null,
                            request = call.receive<UpsertQuestionResourceRequest>(),
                            actor = actor,
                        ),
                    )
                }
                patch("/{resourceId}") {
                    val actor = call.requirePermission("content.write")
                    call.respond(
                        gateway.saveResource(
                            questionId = call.requiredLongPath("questionId"),
                            resourceId = call.requiredLongPath("resourceId"),
                            request = call.receive(),
                            actor = actor,
                        ),
                    )
                }
            }
        }
        commerceRoutes(gateway)
    }
}

private fun Route.commerceRoutes(gateway: CanonicalAdminGateway) {
    route("/commerce/items") {
        get {
            val actor = call.requirePermission("commerce.read")
            val page = call.adminPageRequest()
            call.respond(gateway.listStoreItems(actor, page.cursor, page.limit))
        }
        post {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                HttpStatusCode.Created,
                gateway.saveStoreItem(null, call.receive<UpsertStoreItemRequest>(), call.requiredIdempotencyKey(), actor),
            )
        }
        patch("/{itemId}") {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                gateway.saveStoreItem(
                    call.requiredLongPath("itemId"),
                    call.receive(),
                    call.requiredIdempotencyKey(),
                    actor,
                ),
            )
        }
    }
    route("/commerce/offers") {
        get {
            val actor = call.requirePermission("commerce.read")
            val page = call.adminPageRequest()
            call.respond(gateway.listStoreOffers(actor, page.cursor, page.limit))
        }
        post {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                HttpStatusCode.Created,
                gateway.saveStoreOffer(null, call.receive<UpsertStoreOfferRequest>(), call.requiredIdempotencyKey(), actor),
            )
        }
        patch("/{offerId}") {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                gateway.saveStoreOffer(
                    call.requiredLongPath("offerId"),
                    call.receive(),
                    call.requiredIdempotencyKey(),
                    actor,
                ),
            )
        }
    }
    route("/commerce/limited-resources") {
        get {
            val actor = call.requirePermission("commerce.read")
            val page = call.adminPageRequest()
            call.respond(gateway.listLimitedResources(actor, page.cursor, page.limit))
        }
        post {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                HttpStatusCode.Created,
                gateway.saveLimitedResource(
                    null,
                    call.receive<UpsertLimitedResourceRequest>(),
                    call.requiredIdempotencyKey(),
                    actor,
                ),
            )
        }
        patch("/{resourceWindowId}") {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                gateway.saveLimitedResource(
                    call.requiredLongPath("resourceWindowId"),
                    call.receive(),
                    call.requiredIdempotencyKey(),
                    actor,
                ),
            )
        }
    }
    route("/commerce/burst-campaigns") {
        get {
            val actor = call.requirePermission("commerce.read")
            val page = call.adminPageRequest()
            call.respond(gateway.listBurstCampaigns(actor, page.cursor, page.limit))
        }
        post {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                HttpStatusCode.Created,
                gateway.saveBurstCampaign(
                    null,
                    call.receive<UpsertBurstCampaignRequest>(),
                    call.requiredIdempotencyKey(),
                    actor,
                ),
            )
        }
        patch("/{campaignId}") {
            val actor = call.requirePermission("commerce.write")
            call.respond(
                gateway.saveBurstCampaign(
                    call.requiredLongPath("campaignId"),
                    call.receive(),
                    call.requiredIdempotencyKey(),
                    actor,
                ),
            )
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireExamWritePermission(
    request: UpsertExamRequest,
): AdminPrincipal {
    val actor = requirePermission("content.write")
    if (request.status == PublicationStatus.PUBLISHED && "content.publish" !in actor.permissions) {
        throw ForbiddenProblem("content.publish")
    }
    return actor
}

private fun io.ktor.server.application.ApplicationCall.requiredLongPath(name: String): Long {
    return parameters[name]?.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BadRequestProblem("Parámetro $name no válido.")
}

internal data class AdminPageRequest(
    val cursor: Long?,
    val limit: Int,
)

internal fun io.ktor.server.application.ApplicationCall.adminPageRequest(): AdminPageRequest {
    val rawCursor = request.queryParameters["cursor"]?.trim()
    val cursor = rawCursor?.takeIf(String::isNotEmpty)?.toLongOrNull()
    if (!rawCursor.isNullOrEmpty() && (cursor == null || cursor <= 0)) {
        throw BadRequestProblem("Cursor no válido.")
    }
    val rawLimit = request.queryParameters["limit"]?.trim()
    val limit = rawLimit?.takeIf(String::isNotEmpty)?.toIntOrNull()
        ?: CanonicalAdminGateway.DEFAULT_PAGE_LIMIT
    if ((!rawLimit.isNullOrEmpty() && rawLimit.toIntOrNull() == null) || limit !in 1..100) {
        throw BadRequestProblem("El límite debe estar entre 1 y 100.")
    }
    return AdminPageRequest(cursor = cursor, limit = limit)
}

internal fun io.ktor.server.application.ApplicationCall.requiredIdempotencyKey(): String {
    val value = request.headers[AdminApiHeaders.IDEMPOTENCY_KEY]?.trim().orEmpty()
    if (!value.matches(Regex("[A-Za-z0-9._:-]{8,128}"))) {
        throw BadRequestProblem(
            message = "Falta una clave de idempotencia válida.",
            fieldErrors = listOf(
                ApiFieldError(
                    field = AdminApiHeaders.IDEMPOTENCY_KEY,
                    code = "required",
                    message = "Debe tener entre 8 y 128 caracteres seguros.",
                ),
            ),
        )
    }
    return value
}
