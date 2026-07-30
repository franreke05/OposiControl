package com.oposicontrol.admin.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AdminApiRoutes {
    const val BASE = "/api/admin/v1"
    const val ME = "$BASE/auth/me"
    const val SPECIALTIES = "$BASE/specialties"
    const val EXAMS = "$BASE/exams"
    const val USERS = "$BASE/users"
    const val ROLES = "$BASE/roles"
    const val PERMISSIONS = "$BASE/permissions"
    const val EXAM_ACCESS = "$BASE/exam-access"
    const val WALLETS = "$BASE/wallets"
    const val STORE_ITEMS = "$BASE/commerce/items"
    const val STORE_OFFERS = "$BASE/commerce/offers"
    const val LIMITED_RESOURCES = "$BASE/commerce/limited-resources"
    const val BURST_CAMPAIGNS = "$BASE/commerce/burst-campaigns"
    const val SUPPORT_CONVERSATIONS = "$BASE/support/conversations"
    const val MODERATION_ACTIONS = "$BASE/moderation/actions"
    const val AUDIT = "$BASE/audit"
    const val PREFERENCES = "$BASE/settings/me"
    const val DASHBOARD_SUMMARY = "$BASE/dashboard/summary"
    const val SEARCH = "$BASE/search"
    const val HEALTH_LIVE = "/health/live"
    const val HEALTH_READY = "/health/ready"

    fun exam(examId: Long): String = "$EXAMS/$examId"

    fun examQuestions(examId: Long): String = "${exam(examId)}/questions"

    fun question(questionId: Long): String = "$BASE/questions/$questionId"

    fun questionOptions(questionId: Long): String = "${question(questionId)}/options"

    fun questionResources(questionId: Long): String = "${question(questionId)}/resources"

    fun user(userId: Long): String = "$USERS/$userId"

    fun role(roleId: Long): String = "$ROLES/$roleId"

    fun walletAdjustment(userId: Long): String = "$BASE/wallets/$userId/adjustments"

    fun walletTransactions(userId: Long): String = "$WALLETS/$userId/transactions"

    fun supportConversation(conversationId: Long): String =
        "$SUPPORT_CONVERSATIONS/$conversationId"

    fun supportMessages(conversationId: Long): String =
        "${supportConversation(conversationId)}/messages"

    fun storeItem(itemId: Long): String = "$STORE_ITEMS/$itemId"

    fun storeOffer(offerId: Long): String = "$STORE_OFFERS/$offerId"

    fun limitedResource(windowId: Long): String = "$LIMITED_RESOURCES/$windowId"

    fun burstCampaign(campaignId: Long): String = "$BURST_CAMPAIGNS/$campaignId"
}

object AdminApiHeaders {
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val REQUEST_ID = "X-Request-ID"
}

/**
 * Local-only credential used to keep the development application open.
 *
 * This value is intentionally public and is accepted only by a backend running
 * with `APP_ENV=dev`; staging and production still require a Supabase JWT.
 */
object AdminApiDevelopment {
    const val OPEN_ACCESS_TOKEN = "oposicontrol-local-open-access"
}

object AdminApiJson {
    val format: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = false
    }
}

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val requestId: String,
    val fieldErrors: List<ApiFieldError> = emptyList(),
    val retryable: Boolean = false,
)

@Serializable
data class ApiFieldError(
    val field: String,
    val code: String,
    val message: String,
)

@Serializable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
    val requestId: String? = null,
)
