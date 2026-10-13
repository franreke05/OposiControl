package com.oposicontrol.admin.domain.model

/**
 * Un movimiento del registro de auditoría: quién hizo qué, sobre qué y cuándo.
 *
 * Los tres «no se sabe» son distintos y se conservan distintos, porque la pantalla los cuenta de forma distinta:
 * - [actorId] `null`: no lo hizo una persona, lo hizo el sistema (una llamada sin usuario detrás).
 * - [actorName] `null` con [actorId]: hay persona, pero no se encontró su perfil. Se enseña el identificador.
 * - [table] / [recordId] `null`: la acción no apunta a un registro concreto.
 */
data class AuditMovement(
    val id: String,
    val actorId: String?,
    val actorName: String?,
    /** Código de la acción tal cual lo escribió el servidor (`cuenta_dada_de_baja`). La etiqueta legible la pone la pantalla. */
    val action: String,
    val table: String?,
    val recordId: String?,
    /** Instante ISO-8601 con zona, tal cual llega. Se convierte a hora local al enseñarlo, no antes. */
    val occurredAt: String,
)

/**
 * Filtros que aplica el SERVIDOR. `null` = sin ese filtro.
 *
 * [from] es inclusivo y [until] exclusivo, los dos como instantes ISO-8601: quien los rellena ya ha decidido en qué zona
 * horaria empieza y acaba un día.
 */
data class AuditFilter(
    val action: String? = null,
    val actorId: String? = null,
    val from: String? = null,
    val until: String? = null,
)

/** Una página de movimientos, del más reciente al más antiguo. [nextCursor] `null` = no hay más. */
data class AuditPage(
    val items: List<AuditMovement>,
    val nextCursor: String?,
)

/** Un dato guardado en el «antes» o el «después» de un movimiento. [value] `null` = el dato estaba, vacío. */
data class AuditField(
    val key: String,
    val value: String?,
)

/**
 * El estado anterior y el resultante de un movimiento, aplanados a pares clave → valor en el orden en que se guardaron.
 *
 * `null` no es lo mismo que una lista vacía: `null` = el servidor no guardó ese lado (una creación no tiene «antes»).
 */
data class AuditDetail(
    val id: String,
    val before: List<AuditField>?,
    val after: List<AuditField>?,
)
