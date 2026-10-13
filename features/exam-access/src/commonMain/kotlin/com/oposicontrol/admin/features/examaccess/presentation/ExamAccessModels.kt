package com.oposicontrol.admin.features.examaccess.presentation

data class ExamAccessItem(
    val id: String,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val status: String,
    val userId: String? = null,
    val examId: String? = null,
    /** Fechas tal como las da el servidor (ISO); `null` si no las informa. */
    val grantedAt: String? = null,
    val validUntil: String? = null,
) {
    val isActive: Boolean get() = status == STATUS_ACTIVE

    /** Una compra no la concede ni la retira el panel: aquí es de solo lectura. */
    val isPurchase: Boolean get() = ExamAccessTypes.normalize(accessType) == ExamAccessTypes.PURCHASE

    companion object {
        const val STATUS_ACTIVE: String = "active"
        const val STATUS_INACTIVE: String = "inactive"
    }
}

/** Filtro del catálogo. Se aplica en el cliente sobre la lista descargada: `GET /accesos` no acepta filtros. */
enum class AccessFilter(val label: String) {
    ALL("Todos"),
    ACTIVE("Vigentes"),
    REVOKED("Retirados"),
}

/** Una persona encontrada por el buscador: se concede a su `id`, nunca a un nombre tecleado. */
data class AccessUserOption(val id: String, val label: String, val detail: String)

/** Un examen del catálogo, elegible por `id`. */
data class AccessExamOption(val id: String, val title: String, val detail: String)

/**
 * Vocabulario cerrado del motivo de un acceso (CHECK vivo de
 * `contenido_desbloqueado_usuario.motivo`). El valor que viaja es el código; la
 * etiqueta en español es sólo presentación.
 *
 * `manual` fue el valor por defecto del cliente antiguo y el servidor lo trata
 * como alias de `admin`; aquí se muestra igual que `admin`.
 */
object ExamAccessTypes {
    const val DEFAULT: String = "admin"
    const val PURCHASE: String = "compra"
    private const val LEGACY_MANUAL: String = "manual"

    /** Todos los códigos con su etiqueta: para LEER una fila, venga de donde venga. */
    val options: List<Pair<String, String>> = listOf(
        "admin" to "Administración",
        "premium" to "Premium",
        "compra" to "Compra",
        "regalo" to "Regalo",
        "promocion" to "Promoción",
    )

    /**
     * Los que se pueden CONCEDER a mano. «Compra» no está: el servidor la rechaza, porque una compra sólo nace de una
     * compra. «Premium» tampoco: lo da una suscripción, y marcar así una concesión manual falsearía su origen.
     */
    val grantable: List<Pair<String, String>> = options.filter { it.first in setOf("admin", "regalo", "promocion") }

    /** Etiqueta en español; un valor desconocido se muestra tal cual. */
    fun label(code: String): String {
        val normalized = normalize(code)
        return options.firstOrNull { it.first == normalized }?.second ?: code
    }

    /** `manual` (legado) y vacío pasan a [DEFAULT]; lo demás queda como llegó. */
    fun normalize(code: String): String {
        val trimmed = code.trim().lowercase()
        return if (trimmed.isEmpty() || trimmed == LEGACY_MANUAL) DEFAULT else trimmed
    }
}
