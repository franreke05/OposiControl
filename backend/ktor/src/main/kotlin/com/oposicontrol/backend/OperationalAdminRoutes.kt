package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.CreateModerationActionRequest
import com.oposicontrol.admin.core.api.SendSupportMessageRequest
import com.oposicontrol.admin.core.api.SetExamAccessRequest
import com.oposicontrol.admin.core.api.TicketAdjustmentRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.operationalAdminRoutes(gateway: OperationalAdminGateway) {
    route("/api/admin/v1") {
        route("/exam-access") {
            get {
                val actor = call.requirePermission("exam_access.read")
                val page = call.adminPageRequest()
                call.respond(gateway.listExamAccess(actor, page.cursor, page.limit))
            }
            post {
                val actor = call.requirePermission("exam_access.write")
                call.respond(
                    gateway.setExamAccess(
                        request = call.receive<SetExamAccessRequest>(),
                        idempotencyKey = call.requiredIdempotencyKey(),
                        actor = actor,
                    ),
                )
            }
        }
        route("/wallets") {
            get {
                val actor = call.requirePermission("tickets.read")
                val page = call.adminPageRequest()
                call.respond(gateway.listWallets(actor, page.cursor, page.limit))
            }
            get("/{userId}/transactions") {
                val actor = call.requirePermission("tickets.read")
                val page = call.adminPageRequest()
                call.respond(
                    gateway.listTicketTransactions(
                        call.requiredOperationalId("userId"),
                        actor,
                        page.cursor,
                        page.limit,
                    ),
                )
            }
            post("/{userId}/adjustments") {
                val actor = call.requirePermission("tickets.adjust")
                call.respond(
                    gateway.adjustTickets(
                        userId = call.requiredOperationalId("userId"),
                        request = call.receive<TicketAdjustmentRequest>(),
                        idempotencyKey = call.requiredIdempotencyKey(),
                        actor = actor,
                    ),
                )
            }
        }
        route("/support/conversations") {
            get {
                val actor = call.requirePermission("support.read")
                val page = call.adminPageRequest()
                call.respond(gateway.listSupportConversations(actor, page.cursor, page.limit))
            }
            route("/{conversationId}/messages") {
                get {
                    val actor = call.requirePermission("support.read")
                    val page = call.adminPageRequest()
                    call.respond(
                        gateway.listSupportMessages(
                            call.requiredOperationalId("conversationId"),
                            actor,
                            page.cursor,
                            page.limit,
                        ),
                    )
                }
                post {
                    val request = call.receive<SendSupportMessageRequest>()
                    val actor = call.requirePermission("support.reply")
                    if (request.internalNote && "support.internal_note" !in actor.permissions) {
                        throw ForbiddenProblem("support.internal_note")
                    }
                    call.respond(
                        HttpStatusCode.Created,
                        gateway.sendSupportMessage(
                            conversationId = call.requiredOperationalId("conversationId"),
                            request = request,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        ),
                    )
                }
            }
        }
        route("/moderation/actions") {
            get {
                val actor = call.requirePermission("moderation.read")
                val page = call.adminPageRequest()
                call.respond(gateway.listModerationActions(actor, page.cursor, page.limit))
            }
            post {
                val actor = call.requirePermission("moderation.write")
                call.respond(
                    HttpStatusCode.Created,
                    gateway.createModerationAction(
                        request = call.receive<CreateModerationActionRequest>(),
                        idempotencyKey = call.requiredIdempotencyKey(),
                        actor = actor,
                    ),
                )
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requiredOperationalId(name: String): Long {
    return parameters[name]?.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE }
        ?: throw BadRequestProblem("Parámetro $name no válido.")
}
