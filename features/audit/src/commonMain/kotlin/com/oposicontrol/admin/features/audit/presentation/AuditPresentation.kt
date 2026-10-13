package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.domain.model.AuditField
import com.oposicontrol.admin.domain.model.AuditMovement
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// ---------------------------------------------------------------------------------------------------------------------
// Diccionario de acciones
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Cada código de acción que el servidor escribe en el registro, con el texto que lee una persona.
 *
 * El registro guarda códigos (`cuenta_dada_de_baja`); enseñarlos tal cual obligaba a quien audita a traducirlos de cabeza.
 * La lista sale del código del servidor —las llamadas a `registrarAuditoria` y las constantes `ACTION_*`— y
 * `AuditPresentationTest` la repite: un código nuevo sin etiqueta hace fallar la prueba en vez de aparecer en crudo.
 *
 * El orden es el del desplegable de filtro: por tema, y dentro de cada tema como ocurren las cosas.
 */
internal val auditActionLabels: Map<String, String> = linkedMapOf(
    // Cuentas y personas
    "usuario_actualizado" to "Perfil de usuario actualizado",
    "advertencia_usuario" to "Advertencia enviada a un usuario",
    "cuenta_dada_de_baja" to "Cuenta dada de baja",
    "cuenta_reactivada" to "Cuenta reactivada",
    "cuenta_eliminacion_solicitada" to "Eliminación de cuenta solicitada",
    "roles_sincronizacion_solicitada" to "Cambio de roles solicitado",
    "roles_sincronizados" to "Roles cambiados",
    "roles_sincronizacion_fallida" to "Cambio de roles fallido",
    "solicitud_acceso_aprobada" to "Solicitud de acceso aprobada",
    "solicitud_acceso_rechazada" to "Solicitud de acceso rechazada",
    "solicitud_acceso_email_confirmado" to "Correo de una solicitud de acceso confirmado",
    "solicitud_acceso_cuenta_eliminada" to "Cuenta de una solicitud de acceso eliminada",
    "oposibots_usuario_creacion_solicitada" to "Alta de usuario de OposiBots solicitada",
    "oposibots_usuario_creado" to "Usuario de OposiBots creado",
    "oposibots_usuario_creacion_fallida" to "Alta de usuario de OposiBots fallida",
    "oposibots_usuario_actualizacion_solicitada" to "Cambio de usuario de OposiBots solicitado",
    "oposibots_usuario_actualizado" to "Usuario de OposiBots actualizado",
    "oposibots_usuario_actualizacion_fallida" to "Cambio de usuario de OposiBots fallido",
    "oposibots_usuario_eliminacion_solicitada" to "Baja de usuario de OposiBots solicitada",
    "oposibots_usuario_eliminado" to "Usuario de OposiBots eliminado",
    "oposibots_usuario_eliminacion_fallida" to "Baja de usuario de OposiBots fallida",
    // Accesos, OposiTickets y soporte
    "acceso_examen_concedido" to "Acceso a un examen concedido",
    "acceso_examen_retirado" to "Acceso a un examen retirado",
    "tickets_grant" to "Ajuste manual de OposiTickets",
    "soporte_respuesta_enviada" to "Respuesta de soporte enviada",
    // Moderación
    "reporte_resuelto" to "Reporte resuelto",
    "reporte_descartado" to "Reporte descartado",
    "feedback_resuelto" to "Aviso sobre una pregunta resuelto",
    "feedback_revisado" to "Aviso sobre una pregunta revisado",
    "feedback_descartado" to "Aviso sobre una pregunta descartado",
    // Exámenes y preguntas
    "examen_creado" to "Examen creado",
    "examen_publicado" to "Examen publicado",
    "examen_despublicado" to "Examen retirado de publicación",
    "examen_archivado" to "Examen archivado",
    "examen_eliminado" to "Examen eliminado",
    "pregunta_creada" to "Pregunta creada",
    "pregunta_actualizada" to "Pregunta actualizada",
    "pregunta_archivada" to "Pregunta archivada",
    "pregunta_vinculada" to "Pregunta añadida a un examen",
    "pregunta_desvinculada" to "Pregunta quitada de un examen",
    "preguntas_examen_reordenadas" to "Preguntas de un examen reordenadas",
    "recurso_pregunta_creado" to "Recurso añadido a una pregunta",
    "recurso_pregunta_archivado" to "Recurso de una pregunta archivado",
    "lote_importado" to "Lote de preguntas importado",
    // Catálogos
    "especialidad_archivada" to "Especialidad archivada",
    "capacidades_especialidad_actualizadas" to "Capacidades de una especialidad actualizadas",
    "etiqueta_eliminada" to "Etiqueta eliminada",
    "comunidad_eliminada" to "Comunidad autónoma eliminada",
    // Noticias
    "noticia_creada" to "Noticia creada",
    "noticia_actualizada" to "Noticia actualizada",
    "noticia_publicada" to "Noticia publicada",
    "noticia_programada" to "Noticia programada",
    "noticia_archivada" to "Noticia archivada",
    "noticia_eliminada" to "Noticia eliminada",
    "noticia_asset_subido" to "Archivo de una noticia subido",
    "noticia_guardada_legacy" to "Noticia guardada (versión anterior del panel)",
    "noticia_eliminada_legacy" to "Noticia eliminada (versión anterior del panel)",
    "tipo_noticia_guardado" to "Tipo de noticia guardado",
    // Catálogo comercial y OposiMarket
    "producto_comercial_guardado" to "Producto del catálogo guardado",
    "producto_comercial_eliminado" to "Producto del catálogo eliminado",
    "tarifa_producto_guardada" to "Tarifa de un producto guardada",
    "tarifa_producto_eliminada" to "Tarifa de un producto eliminada",
    "plan_suscripcion_guardado" to "Plan de suscripción guardado",
    "plan_eliminado" to "Plan de suscripción eliminado",
    "beneficio_plan_guardado" to "Beneficio de un plan guardado",
    "beneficio_plan_eliminado" to "Beneficio de un plan eliminado",
    "pack_opositickets_guardado" to "Pack de OposiTickets guardado",
    "servicio_market_guardado" to "Servicio de OposiMarket guardado",
    "promocion_guardada" to "Promoción guardada",
    "regla_popup_guardada" to "Regla de ventana promocional guardada",
)

/** La etiqueta de [code]. Un código que el diccionario no conoce se enseña TAL CUAL: nunca se oculta ni se adivina. */
internal fun auditActionLabel(code: String): String = auditActionLabels[code] ?: code

/** ¿[code] tiene etiqueta propia? Cuando no la tiene, la ficha no repite el código dos veces. */
internal fun auditActionIsKnown(code: String): Boolean = code in auditActionLabels

/** Las tablas sobre las que el servidor registra movimientos, con su nombre en el panel. */
internal val auditTableLabels: Map<String, String> = mapOf(
    "perfiles_usuario" to "Usuarios",
    "usuario" to "Usuarios de OposiBots",
    "roles_usuario" to "Roles de usuario",
    "solicitudes_acceso_admin" to "Solicitudes de acceso al panel",
    "contenido_desbloqueado_usuario" to "Accesos a exámenes",
    "carteras_usuario" to "Carteras de OposiTickets",
    "mensajes_soporte" to "Mensajes de soporte",
    "reportes_contenido" to "Reportes",
    "feedback_pregunta" to "Avisos sobre preguntas",
    "examenes" to "Exámenes",
    "preguntas" to "Preguntas",
    "preguntas_examen" to "Preguntas de un examen",
    "recursos_pregunta" to "Recursos de preguntas",
    "lotes_importacion_preguntas" to "Importaciones de preguntas",
    "especialidades" to "Especialidades",
    "capacidades_especialidad" to "Capacidades de especialidad",
    "etiquetas" to "Etiquetas",
    "comunidades_autonomas" to "Comunidades autónomas",
    "noticia" to "Noticias",
    "tipo_noticia" to "Tipos de noticia",
    "productos_comerciales" to "Productos del catálogo",
    "tarifas_producto_comercial" to "Tarifas de productos",
    "planes_suscripcion" to "Planes de suscripción",
    "beneficios_plan" to "Beneficios de planes",
    "paquetes_opositickets" to "Packs de OposiTickets",
    "servicios" to "Servicios de OposiMarket",
    "promociones" to "Promociones",
    "reglas_popup_comercial" to "Ventanas promocionales",
)

/** El nombre de [table] en el panel; una tabla desconocida se enseña tal cual. `null` si el movimiento no apunta a ninguna. */
internal fun auditTableLabel(table: String?): String? = table?.takeIf { it.isNotBlank() }?.let { auditTableLabels[it] ?: it }

// ---------------------------------------------------------------------------------------------------------------------
// Quién y sobre qué
// ---------------------------------------------------------------------------------------------------------------------

internal const val AUDIT_SYSTEM_ACTOR: String = "Sistema"

/** Quién lo hizo: su nombre; si no hay perfil, su identificador; si no hay persona, «Sistema». Nunca un nombre inventado. */
internal fun auditActorLabel(movement: AuditMovement): String = when {
    movement.actorId == null -> AUDIT_SYSTEM_ACTOR
    !movement.actorName.isNullOrBlank() -> movement.actorName.orEmpty()
    else -> movement.actorId.orEmpty()
}

/** Sobre qué, en una línea para el catálogo: la tabla legible. Sin tabla se dice, no se deja el hueco. */
internal fun auditTargetLabel(movement: AuditMovement): String = auditTableLabel(movement.table) ?: "Sin registro asociado"

/** Una persona entre las que se puede filtrar: las que aparecen en lo ya cargado. */
data class AuditActorOption(val id: String, val label: String)

/** Las personas de [movements], una vez cada una y por orden alfabético. Las acciones del sistema no son una persona. */
internal fun auditActorOptions(movements: List<AuditMovement>, known: List<AuditActorOption> = emptyList()): List<AuditActorOption> {
    val byId = LinkedHashMap<String, AuditActorOption>()
    known.forEach { byId[it.id] = it }
    movements.forEach { movement ->
        val id = movement.actorId ?: return@forEach
        // Un nombre recién leído sustituye al identificador que se enseñaba mientras no se conocía.
        if (byId[id] == null || !movement.actorName.isNullOrBlank()) byId[id] = AuditActorOption(id, auditActorLabel(movement))
    }
    return byId.values.sortedBy { it.label.lowercase() }
}

/** ¿[movement] pasa el buscador local? Busca en lo que se lee en pantalla (etiquetas) y en lo que se copia (códigos e ids). */
internal fun auditMatches(movement: AuditMovement, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOfNotNull(
        auditActionLabel(movement.action),
        movement.action,
        auditActorLabel(movement),
        movement.actorId,
        auditTableLabel(movement.table),
        movement.table,
        movement.recordId,
    ).any { it.lowercase().contains(q) }
}

// ---------------------------------------------------------------------------------------------------------------------
// Cuándo
// ---------------------------------------------------------------------------------------------------------------------

private fun two(value: Int): String = value.toString().padStart(2, '0')

/**
 * El instante [iso] en la hora de [zone]: «13/10/2026, 14:34:56» ([withSeconds]) o «13/10/2026 14:34».
 *
 * El servidor guarda instantes en UTC; quien audita piensa en la hora de su reloj. Si [iso] no es un instante válido se
 * devuelve tal cual: una fecha que no se entiende se enseña como llegó, no se sustituye por otra.
 */
internal fun formatAuditMoment(iso: String, zone: TimeZone, withSeconds: Boolean = true): String {
    val instant = runCatching { Instant.parse(iso.trim()) }.getOrNull() ?: return iso
    val local = instant.toLocalDateTime(zone)
    val date = "${two(local.day)}/${two(local.month.number)}/${local.year}"
    return if (withSeconds) {
        "$date, ${two(local.hour)}:${two(local.minute)}:${two(local.second)}"
    } else {
        "$date ${two(local.hour)}:${two(local.minute)}"
    }
}

/** El rango de fechas del filtro, ya convertido a los instantes que entiende el servidor. `null` = sin ese extremo. */
data class AuditDateRange(val from: String? = null, val until: String? = null)

/** Qué le pasa a lo tecleado en un campo de fecha. */
internal enum class AuditDateInput { EMPTY, INCOMPLETE, INVALID, VALID }

internal fun auditDateInput(text: String): AuditDateInput {
    val value = text.trim()
    return when {
        value.isEmpty() -> AuditDateInput.EMPTY
        // Mientras se escribe no es un error: todavía no es una fecha.
        value.length < AUDIT_DATE_LENGTH -> AuditDateInput.INCOMPLETE
        parseAuditDate(value) == null -> AuditDateInput.INVALID
        else -> AuditDateInput.VALID
    }
}

private const val AUDIT_DATE_LENGTH: Int = 10

private fun parseAuditDate(value: String): LocalDate? {
    if (value.length != AUDIT_DATE_LENGTH || value[4] != '-' || value[7] != '-') return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
}

/**
 * Los dos campos `AAAA-MM-DD` convertidos a instantes, o `null` si todavía no se puede buscar con ellos (uno a medio
 * escribir, una fecha que no existe o un rango al revés).
 *
 * Un día es un día de [zone], la del equipo: «desde el 13» empieza a las 00:00 de aquí, no de UTC, y «hasta el 13» incluye
 * el día entero (el límite que viaja es el primer instante del 14, exclusivo). Así el filtro casa con las horas que se leen.
 */
internal fun auditDateRange(fromText: String, untilText: String, zone: TimeZone): AuditDateRange? {
    val states = listOf(auditDateInput(fromText), auditDateInput(untilText))
    if (states.any { it == AuditDateInput.INCOMPLETE || it == AuditDateInput.INVALID }) return null
    val from = parseAuditDate(fromText.trim())
    val until = parseAuditDate(untilText.trim())
    if (from != null && until != null && until < from) return null
    return AuditDateRange(
        from = from?.atStartOfDayIn(zone)?.toString(),
        until = until?.plus(1, DateTimeUnit.DAY)?.atStartOfDayIn(zone)?.toString(),
    )
}

/** Lo que se le dice a quien escribe bajo el campo de fecha, o `null` si no hay nada que decir. */
internal fun auditDateHint(text: String): String? = when (auditDateInput(text)) {
    AuditDateInput.EMPTY, AuditDateInput.VALID -> null
    AuditDateInput.INCOMPLETE -> "Escribe la fecha completa: año, mes y día."
    AuditDateInput.INVALID -> "Esa fecha no existe. El formato es AAAA-MM-DD."
}

/** El rango está al revés: las dos fechas valen, pero «hasta» es anterior a «desde». */
internal fun auditDateRangeIsReversed(fromText: String, untilText: String): Boolean {
    val from = parseAuditDate(fromText.trim()) ?: return false
    val until = parseAuditDate(untilText.trim()) ?: return false
    return until < from
}

// ---------------------------------------------------------------------------------------------------------------------
// Qué cambió
// ---------------------------------------------------------------------------------------------------------------------

/** Una línea de «Qué cambió»: el dato, lo que valía y lo que vale. `null` en un lado = ese lado no lo tenía. */
data class AuditChangeRow(
    val label: String,
    val before: String?,
    val after: String?,
) {
    /** Lo que se lee: «activa → bloqueada», o solo el valor cuando no hay con qué compararlo o no cambió. */
    val text: String
        get() = when {
            before != null && after != null && before != after -> "$before → $after"
            after != null -> after
            else -> before.orEmpty()
        }

    /** Dónde estaba el dato, para decirlo cuando solo está en un lado. */
    val note: String?
        get() = when {
            before != null && after == null -> "Valor anterior"
            before != null && after != null && before == after -> "Sin cambio"
            else -> null
        }
}

/** Nombres legibles de los datos que el servidor guarda en el antes y el después. Sale de sus llamadas a `registrarAuditoria`. */
private val auditFieldLabels: Map<String, String> = mapOf(
    "estado" to "Estado",
    "estado_cuenta" to "Estado de la cuenta",
    "motivo" to "Motivo",
    "motivos" to "Motivos",
    "codigo" to "Código",
    "nombre" to "Nombre",
    "nombreMostrar" to "Nombre visible",
    "titulo" to "Título",
    "slug" to "Identificador en la dirección",
    "tipo" to "Tipo",
    "tipo_producto" to "Tipo de producto",
    "activo" to "Activo",
    "nivel" to "Nivel",
    "moneda" to "Moneda",
    "precio_centimos" to "Precio (céntimos)",
    "periodo_facturacion" to "Periodo de facturación",
    "fase_precio" to "Fase de precio",
    "cantidad" to "Cantidad",
    "cantidad_tickets" to "OposiTickets",
    "saldoResultante" to "Saldo resultante",
    "transactionId" to "Identificador de la transacción",
    "roleIds" to "Roles",
    "roleIdsSolicitados" to "Roles solicitados",
    "roleIdsEsperados" to "Roles que se esperaban",
    "resultado" to "Resultado",
    "error" to "Error",
    "campos" to "Campos cambiados",
    "publicadoEn" to "Publicación",
    "valido_hasta" to "Válido hasta",
    "usuario_id" to "Usuario",
    "usuarioId" to "Usuario",
    "authUserId" to "Usuario",
    "usuarioLegacyId" to "Usuario (identificador antiguo)",
    "remitente_id" to "Remitente",
    "examen_id" to "Examen",
    "plan_id" to "Plan",
    "producto_id" to "Producto",
    "conversacion_id" to "Conversación",
    "requestId" to "Referencia de la petición",
    "cuentaEliminada" to "Cuenta eliminada",
    "emailConfirmado" to "Correo confirmado",
    "precondicionViolada" to "Los roles habían cambiado entre tanto",
    "bajoCerrojo" to "Comprobado con la cuenta bloqueada para otros cambios",
    "concesiones" to "Concesiones cerradas",
    "tarifas" to "Tarifas",
    "path" to "Ruta del archivo",
    "mime" to "Tipo de archivo",
    "bytes" to "Tamaño (bytes)",
    "id" to "Identificador",
)

/**
 * El nombre legible de [key]. Una clave compuesta (`tarifas.1.precio_centimos`) se traduce tramo a tramo; un tramo que no
 * se conoce se deja como está, con los guiones bajos como espacios: sigue siendo el dato real, no uno inventado.
 */
internal fun auditFieldLabel(key: String): String {
    if (key.isBlank()) return "Valor"
    return key.split('.').joinToString(" · ") { part ->
        auditFieldLabels[part] ?: if (part.all { it.isDigit() }) "n.º $part" else part.replace('_', ' ')
    }
}

/** Un valor guardado, como se lee: sí/no en vez de `true`/`false`, y el vacío dicho con palabras. */
internal fun auditFieldValue(value: String?): String = when {
    value == null -> "Sin valor"
    value.isBlank() -> "Vacío"
    value == "true" -> "Sí"
    value == "false" -> "No"
    else -> value
}

/**
 * El antes y el después de un movimiento como líneas clave → valor.
 *
 * Cada dato sale una vez, con sus dos valores cuando está en los dos lados. El orden es el del «después» (lo que quedó) y
 * luego lo que solo estaba en el «antes». Lista vacía = el servidor no guardó ni lo uno ni lo otro.
 */
internal fun auditChangeRows(before: List<AuditField>?, after: List<AuditField>?): List<AuditChangeRow> {
    val beforeByKey = before.orEmpty().associateBy { it.key }
    val afterByKey = after.orEmpty().associateBy { it.key }
    val keys = LinkedHashSet<String>().apply {
        after.orEmpty().forEach { add(it.key) }
        before.orEmpty().forEach { add(it.key) }
    }
    return keys.map { key ->
        AuditChangeRow(
            label = auditFieldLabel(key),
            before = beforeByKey[key]?.let { auditFieldValue(it.value) },
            after = afterByKey[key]?.let { auditFieldValue(it.value) },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------------------------------------------------

// Estas líneas describen lo que hace el servidor hoy (qué llamadas escriben en el registro). Si eso cambia, cambian ellas.
internal val auditRecordedLines: List<String> = listOf(
    "Las acciones del panel sobre cuentas, roles, accesos, OposiTickets, exámenes, preguntas, noticias y catálogo comercial que el servidor anota al hacerlas.",
    "De cada una: quién la hizo, sobre qué registro y cuándo. Algunas guardan además lo que había antes y lo que quedó.",
    "Los cambios de roles y las altas, cambios y bajas de usuarios de OposiBots dejan dos movimientos: la solicitud y su resultado.",
)

internal val auditNotRecordedLines: List<String> = listOf(
    "Las consultas: abrir una ficha o una lista no deja movimiento.",
    "No todas las ediciones del panel se anotan. Las que sí, son las que aparecen en el filtro «Acción».",
    "La actividad de los opositores en la aplicación, salvo cuando piden eliminar su cuenta.",
    "Los cambios hechos directamente en la base de datos, sin pasar por el servidor.",
)

/** El recuento del rail. Cuenta lo CARGADO, no lo que hay en el registro: el servidor pagina y no da un total. */
internal fun auditOverviewFacts(state: AuditState): List<WorkspaceFact> {
    val shown = (state.movements as? UiAsync.Success)?.value?.size ?: 0
    return listOf(
        WorkspaceFact("Cargados", state.allMovements.size.toString()),
        WorkspaceFact("A la vista", shown.toString()),
        WorkspaceFact("Personas distintas", state.allMovements.mapNotNull { it.actorId }.distinct().size.toString()),
        WorkspaceFact("Más por cargar", if (state.nextCursor != null) "Sí" else "No"),
    )
}

/** El pie de la lista: cuántos movimientos hay cargados y si el servidor tiene más. */
internal fun auditLoadedSummary(loaded: Int, hasMore: Boolean): String {
    val count = if (loaded == 1) "1 movimiento cargado" else "$loaded movimientos cargados"
    return if (hasMore) "$count. Quedan más por cargar." else "$count. No hay más."
}
