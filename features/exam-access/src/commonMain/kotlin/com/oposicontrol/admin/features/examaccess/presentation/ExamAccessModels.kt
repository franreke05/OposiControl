package com.oposicontrol.admin.features.examaccess.presentation

data class ExamAccessItem(
    val id: String,
    val userName: String,
    val examTitle: String,
    val accessType: String,
    val status: String,
    val userId: String? = null,
    val examId: String? = null,
)

/**
 * Vocabulario cerrado del motivo de un acceso (CHECK vivo de
 * `contenido_desbloqueado_usuario.motivo`). El valor que viaja es el código; la
 * etiqueta en español es sólo presentación.
 *
 * `manual` fue el valor por defecto del cliente antiguo y el servidor lo trata
 * como alias de `admin`; aquí se muestra igual que `admin` y se normaliza al
 * editarlo, para que guardar un acceso legado no dependa del alias.
 */
object ExamAccessTypes {
    const val DEFAULT: String = "admin"
    private const val LEGACY_MANUAL: String = "manual"

    /** Códigos admitidos con su etiqueta, en el orden en que los ofrece el selector. */
    val options: List<Pair<String, String>> = listOf(
        "admin" to "Administración",
        "premium" to "Premium",
        "compra" to "Compra",
        "regalo" to "Regalo",
        "promocion" to "Promoción",
    )

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
