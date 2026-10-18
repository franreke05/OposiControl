package com.oposicontrol.admin.domain.model

/**
 * Modelos de Control V1: configuración numérica, invitaciones, foro, economía por examen, pedidos y alertas.
 *
 * Todo lo que llega de un usuario (texto del foro, handles, motivos) es TEXTO: el panel lo pinta con `Text()` y nunca lo
 * interpreta como marcado. Las validaciones de este fichero son ayudas de formulario; el servidor es la guarda.
 */

// ---------------------------------------------------------------- configuración numérica

data class ConfigEntry(
    val key: String,
    val group: String,
    val label: String,
    val help: String?,
    val min: Int,
    val max: Int,
    /** `null` = la fila no existe en el servidor: se dice, no se inventa un cero. */
    val value: Int?,
    val updatedAt: String?,
) {
    val isSwitch: Boolean get() = min == 0 && max == 1
}

object ConfigValidator {
    /** `null` = válido; si no, el mensaje para el campo. */
    fun check(entry: ConfigEntry, raw: String): String? {
        val v = raw.trim().toIntOrNull() ?: return "Escribe un número entero."
        if (v < entry.min || v > entry.max) return "Tiene que estar entre ${entry.min} y ${entry.max}."
        return null
    }
}

// ---------------------------------------------------------------- invitaciones

object ReferralStates {
    val ALL: List<String> = listOf("registrada", "email_verificado", "diferida", "premiada", "rechazada", "revocada", "error")

    fun label(state: String): String = when (state) {
        "registrada" -> "Registrada"
        "email_verificado" -> "Email verificado"
        "diferida" -> "Retenida"
        "premiada" -> "Premiada"
        "rechazada" -> "Rechazada"
        "revocada" -> "Revocada"
        "error" -> "Error de pago"
        else -> "Desconocido"
    }

    /** Solo se revoca lo que ya pagó o está a punto de pagar; revocar una rechazada o ya revocada no tiene efecto. */
    fun canRevoke(state: String): Boolean = state in setOf("premiada", "diferida", "email_verificado", "error", "registrada")

    fun flagLabel(flag: String): String = when (flag) {
        "mismo_dominio_raiz" -> "Mismo correo raíz"
        "ip_burst" -> "Ráfaga desde la misma IP"
        "inviter_cap" -> "Invitador en su tope"
        "dominio_desechable" -> "Correo desechable"
        else -> flag
    }
}

data class ReferralItem(
    val id: String,
    val state: String,
    val createdAt: String?,
    val inviterHandle: String?,
    val inviteeHandle: String?,
    val inviterPayment: String?,
    val inviteePayment: String?,
    val paymentAttempts: Int,
    val lastError: String?,
    val heldUntil: String?,
    val rewardedAt: String?,
    val reason: String?,
    val flags: List<String>,
)

data class ReferralPage(val items: List<ReferralItem>, val nextCursor: String?, val summary: Map<String, Int>)

data class ReferralRevokeResult(val reverted: Int, val notRevertible: Int)

object ReferralReason {
    const val MIN = 3
    const val MAX = 200
    fun check(raw: String): String? {
        val n = raw.trim().length
        return when {
            n < MIN -> "Indica el motivo (mínimo $MIN caracteres)."
            n > MAX -> "El motivo no puede superar $MAX caracteres."
            else -> null
        }
    }
}

// ---------------------------------------------------------------- foro

enum class ForumContentType(val wire: String, val label: String) {
    POST("publicacion", "Publicaciones"),
    COMMENT("comentario", "Comentarios"),
}

enum class ForumQueueState(val wire: String, val label: String) {
    PENDING("pendiente_moderacion", "Pendientes"),
    REPORTED("reportada", "Reportadas"),
    HIDDEN("oculta", "Ocultas"),
    ;

    /** El estado que usa la cola de comentarios para «ocultas». */
    fun wireFor(type: ForumContentType): String =
        if (this == HIDDEN && type == ForumContentType.COMMENT) "oculto" else wire
}

enum class ForumAction(val wire: String, val label: String, val needsReason: Boolean, val destructive: Boolean) {
    APPROVE("aprobar", "Aprobar", false, false),
    REJECT("rechazar", "Rechazar", true, true),
    HIDE("ocultar", "Ocultar", true, true),
    RESTORE("restaurar", "Restaurar", false, false),
    MUTE("silenciar", "Silenciar al autor", true, true),
}

object ForumModeration {
    const val MUTE_DAYS_MIN = 1
    const val MUTE_DAYS_MAX = 365
    const val MUTE_DAYS_DEFAULT = 7
    const val REASON_MIN = 3
    const val REASON_MAX = 200

    /** Acciones que tienen sentido para el estado en el que está el contenido. */
    fun actionsFor(contentState: String): List<ForumAction> = when (contentState) {
        "pendiente_moderacion" -> listOf(ForumAction.APPROVE, ForumAction.REJECT, ForumAction.MUTE)
        "publicada", "publicado" -> listOf(ForumAction.HIDE, ForumAction.MUTE)
        "oculta", "oculto" -> listOf(ForumAction.RESTORE)
        else -> emptyList()
    }

    /** `null` = válido. */
    fun check(action: ForumAction, reason: String, daysRaw: String): String? {
        val r = reason.trim()
        if (action.needsReason && r.length < REASON_MIN) return "Esta acción exige un motivo (mínimo $REASON_MIN caracteres)."
        if (r.length > REASON_MAX) return "El motivo no puede superar $REASON_MAX caracteres."
        if (action == ForumAction.MUTE) {
            val d = daysRaw.trim().toIntOrNull() ?: return "Los días tienen que ser un número entero."
            if (d < MUTE_DAYS_MIN || d > MUTE_DAYS_MAX) return "El silencio va de $MUTE_DAYS_MIN a $MUTE_DAYS_MAX días."
        }
        return null
    }

    fun reportReasonLabel(code: String): String = when (code) {
        "spam" -> "Spam"
        "ofensivo" -> "Ofensivo"
        "incorrecto" -> "Incorrecto"
        "otro" -> "Otro"
        else -> code
    }
}

data class ForumQueueItem(
    val type: ForumContentType,
    val id: String,
    val postId: String?,
    val channel: String?,
    val title: String?,
    val excerpt: String,
    val state: String,
    val authorHandle: String?,
    val createdAt: String?,
    val hasImage: Boolean,
    val pendingReports: Int,
    val reportReasons: List<String>,
)

data class ForumQueuePage(val items: List<ForumQueueItem>, val nextCursor: String?)

data class ForumChannel(
    val id: String,
    val name: String,
    val slug: String,
    val description: String?,
    val active: Boolean,
    val position: Int,
    val specialtyId: String?,
    val readOnly: Boolean,
)

data class ForumChannelDraft(
    val name: String,
    val slug: String,
    val description: String,
    val active: Boolean,
    val position: String,
    val specialtyId: String?,
    val readOnly: Boolean,
) {
    companion object {
        fun from(c: ForumChannel) = ForumChannelDraft(c.name, c.slug, c.description.orEmpty(), c.active, c.position.toString(), c.specialtyId, c.readOnly)
        val EMPTY = ForumChannelDraft("", "", "", true, "1", null, false)
    }
}

object ForumChannelValidator {
    private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    /** Mapa campo → mensaje; vacío = válido. */
    fun check(d: ForumChannelDraft): Map<String, String> = buildMap {
        if (d.name.trim().length !in 2..60) put("name", "El nombre tiene entre 2 y 60 caracteres.")
        if (d.slug.trim().length !in 2..60 || !SLUG.matches(d.slug.trim())) put("slug", "Minúsculas, números y guiones (2 a 60).")
        if (d.description.trim().length > 300) put("description", "Máximo 300 caracteres.")
        val p = d.position.trim().toIntOrNull()
        if (p == null || p < 1 || p > 10000) put("position", "La posición va de 1 a 10000.")
    }

    /** Sugiere un identificador a partir del nombre, solo con el alfabeto permitido. */
    fun suggestSlug(name: String): String {
        val folded = name.trim().lowercase()
            .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n')
        return folded.map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(60)
    }
}

data class ForumRestriction(val userId: String, val handle: String?, val until: String?, val reason: String?, val createdAt: String?)

object ForumBlockPattern {
    const val MIN = 2
    const val MAX = 100
    fun normalize(raw: String): String = raw.trim().lowercase()
    fun check(raw: String): String? {
        val n = normalize(raw).length
        return if (n < MIN || n > MAX) "El patrón tiene entre $MIN y $MAX caracteres." else null
    }
}

// ---------------------------------------------------------------- economía por examen

data class ExamEconomy(
    val id: String,
    val code: String,
    val title: String,
    val examType: String,
    val isOfficial: Boolean,
    val isPublished: Boolean,
    val communityId: String?,
    val communityName: String?,
    val sellableWithTickets: Boolean,
    val priceTickets: Int?,
    val accessDays: Int,
)

data class ExamEconomyPage(val items: List<ExamEconomy>, val total: Int, val limit: Int, val offset: Int)

data class ExamEconomyDraft(
    val sellable: Boolean,
    val price: String,
    val days: String,
    val communityId: String?,
) {
    companion object {
        fun from(e: ExamEconomy) = ExamEconomyDraft(e.sellableWithTickets, e.priceTickets?.toString().orEmpty(), e.accessDays.toString(), e.communityId)
    }
}

object ExamEconomyValidator {
    const val MAX_PRICE = 1_000_000
    const val MAX_DAYS = 3650

    /** Mapa campo → mensaje; vacío = válido. La comunidad es obligatoria. */
    fun check(d: ExamEconomyDraft): Map<String, String> = buildMap {
        val price = d.price.trim().ifEmpty { null }?.toIntOrNull()
        val days = d.days.trim().toIntOrNull()
        if (d.price.isNotBlank() && (price == null || price < 0 || price > MAX_PRICE)) put("price", "El precio va de 0 a $MAX_PRICE OT.")
        if (days == null || days < 1 || days > MAX_DAYS) put("days", "Los días de acceso van de 1 a $MAX_DAYS.")
        if (d.sellable && (price == null || price <= 0)) put("price", "Para venderlo con tickets necesita un precio mayor que 0.")
        if (d.communityId.isNullOrBlank()) put("community", "Todo examen necesita una comunidad.")
    }
}

// ---------------------------------------------------------------- pedidos

object OrderStates {
    val ALL: List<String> = listOf("en_revision", "pendiente", "pagada", "fallida", "cancelada", "expirada", "reembolsada")

    fun label(state: String): String = when (state) {
        "en_revision" -> "En revisión"
        "pendiente" -> "Pendiente"
        "pagada" -> "Pagada"
        "fallida" -> "Fallida"
        "cancelada" -> "Cancelada"
        "expirada" -> "Expirada"
        "reembolsada" -> "Reembolsada"
        else -> "Desconocido"
    }
}

data class RefundCase(
    val id: String,
    val state: String,
    val ticketsReverted: Int,
    val ticketsSpent: Int,
    val policy: String?,
    val createdAt: String?,
    val resolvedAt: String?,
    val detail: String?,
)

data class OrderItem(
    val id: String,
    val state: String,
    val provider: String,
    val productCode: String,
    val productType: String,
    val tickets: Int?,
    val priceCents: Int?,
    val currency: String,
    val transactionId: String?,
    val userId: String?,
    val userHandle: String?,
    val createdAt: String?,
    val paidAt: String?,
    val expiresAt: String?,
    val refund: RefundCase?,
)

data class OrderPage(val items: List<OrderItem>, val total: Int, val limit: Int, val offset: Int)

object OrderNote {
    const val MIN = 3
    const val MAX = 500
    fun check(raw: String): String? {
        val n = raw.trim().length
        return when {
            n < MIN -> "La nota necesita al menos $MIN caracteres."
            n > MAX -> "La nota no puede superar $MAX caracteres."
            else -> null
        }
    }
}

// ---------------------------------------------------------------- alertas

data class FastWindow(
    val userId: String,
    val handle: String?,
    val leagueName: String?,
    val window: Int?,
    val medianMs: Long?,
    val updatedAt: String?,
)

data class FastWindowPage(val items: List<FastWindow>, val total: Int, val limit: Int, val offset: Int, val thresholdMs: Int?)

data class ForumCounterDrift(val id: String, val at: String?, val rows: Int?)

// ---------------------------------------------------------------- marcas de las preguntas de un examen oficial

data class OfficialQuestion(
    val questionId: String,
    val position: Int,
    val title: String,
    val annulled: Boolean,
    val annulmentReason: String?,
    val isReserve: Boolean,
    val reserveOrder: Int?,
    val isDemo: Boolean,
)

data class OfficialQuestions(
    val examId: String,
    val questions: List<OfficialQuestion>,
    /** `false` = el servidor conectado aún no tiene las columnas de reserva: solo se pueden leer las anuladas. */
    val reservesAvailable: Boolean,
    val check: GradingCheck?,
) {
    val reserves: List<OfficialQuestion> get() = questions.filter { it.isReserve }.sortedBy { it.reserveOrder ?: Int.MAX_VALUE }
    val mainAnnulled: Int get() = questions.count { !it.isReserve && it.annulled }
}

data class OfficialQuestionDraft(
    val annulled: Boolean,
    val reason: String,
    val isReserve: Boolean,
    val order: String,
) {
    companion object {
        fun from(q: OfficialQuestion) = OfficialQuestionDraft(q.annulled, q.annulmentReason.orEmpty(), q.isReserve, q.reserveOrder?.toString().orEmpty())
    }
}

object OfficialQuestionValidator {
    const val REASON_MAX = 500
    const val ORDER_MAX = 10

    /** Mapa campo → mensaje; vacío = válido. Mismas reglas que el servidor (la guarda real es la base de datos). */
    fun check(draft: OfficialQuestionDraft, question: OfficialQuestion, all: List<OfficialQuestion>): Map<String, String> = buildMap {
        if (question.isDemo && (draft.annulled || draft.isReserve)) {
            put("demo", "Una pregunta de demostración no puede estar anulada ni ser reserva.")
        }
        if (draft.reason.isNotBlank() && !draft.annulled) put("reason", "El motivo solo se admite si la pregunta está anulada.")
        if (draft.reason.trim().length > REASON_MAX) put("reason", "El motivo no puede superar $REASON_MAX caracteres.")
        val order = draft.order.trim().toIntOrNull()
        if (draft.isReserve) {
            if (order == null || order < 1 || order > ORDER_MAX) {
                put("order", "Una reserva necesita un orden de 1 a $ORDER_MAX.")
            } else if (all.any { it.isReserve && it.questionId != question.questionId && it.reserveOrder == order }) {
                put("order", "Ya hay otra reserva con el orden $order.")
            }
        } else if (draft.order.isNotBlank()) {
            put("order", "Una pregunta principal no lleva orden de reserva.")
        }
    }

    /** El siguiente orden libre para una reserva nueva: el mayor actual + 1 (la base exige 1..R sin huecos). */
    fun nextOrder(all: List<OfficialQuestion>): Int = (all.filter { it.isReserve }.maxOfOrNull { it.reserveOrder ?: 0 } ?: 0) + 1
}
