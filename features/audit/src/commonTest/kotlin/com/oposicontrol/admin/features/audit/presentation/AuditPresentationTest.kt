package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.domain.model.AuditField
import com.oposicontrol.admin.domain.model.AuditMovement
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditPresentationTest {
    private val madrid = TimeZone.of("Europe/Madrid")

    /**
     * Los códigos de acción que el servidor escribe en `auditoria_log.accion`, copiados de su código el 2026-10-15.
     *
     * De dónde sale cada grupo (rutas bajo `backend/ktor/src/main/kotlin/com/oposicontrol/backend/`):
     * - `AdminV1Routes.kt`: el primer argumento de cada `registrarAuditoria(` / `registrarAuditoriaResultado(`, las
     *   constantes `ACTION_ROLES_*`, y los tres compuestos: `feedback_$estado` (resuelto, descartado, revisado),
     *   `reporte_$estado` (resuelto, descartado) y `${actionType}_usuario` (hoy solo se admite `advertencia`).
     * - `AdminAccessRequests.kt`: las constantes `ACTION_ACCESS_REQUEST_*`.
     * - `OposiBotsUserAdminRoutes.kt`: las nueve constantes `ACTION_*` (solicitud, resultado y fallo de alta, cambio y baja).
     * - `NewsService.kt`: el primer argumento de cada `record(` y de cada `transition(`.
     * - `supabase/migrations/20260930100300_slice1_account_deletion_requests.sql`: `cuenta_eliminacion_solicitada`, que
     *   escribe la propia base.
     *
     * Para repetir la búsqueda:
     * `git grep -n "registrarAuditoria(\|registrarAuditoriaResultado(\|ACTION_[A-Z_]* = \|record(\"\|transition(id" -- backend/ktor/src/main`.
     * Si el servidor gana un código, hay que añadirlo AQUÍ y en `auditActionLabels`; esta prueba falla mientras falte.
     */
    private val backendActionCodes = listOf(
        // AdminV1Routes.kt
        "pregunta_vinculada", "capacidades_especialidad_actualizadas", "especialidad_archivada",
        "examen_creado", "examen_publicado", "examen_despublicado", "examen_archivado", "examen_eliminado",
        "pregunta_creada", "pregunta_actualizada", "pregunta_archivada", "preguntas_examen_reordenadas", "pregunta_desvinculada",
        "recurso_pregunta_creado", "recurso_pregunta_archivado", "lote_importado",
        "feedback_resuelto", "feedback_descartado", "feedback_revisado",
        "usuario_actualizado", "cuenta_dada_de_baja", "cuenta_reactivada",
        "roles_sincronizacion_solicitada", "roles_sincronizados", "roles_sincronizacion_fallida",
        "reporte_resuelto", "reporte_descartado",
        "tickets_grant", "soporte_respuesta_enviada", "acceso_examen_concedido", "acceso_examen_retirado",
        "etiqueta_eliminada", "comunidad_eliminada",
        "producto_comercial_guardado", "producto_comercial_eliminado", "tarifa_producto_guardada", "tarifa_producto_eliminada",
        "plan_suscripcion_guardado", "plan_eliminado", "beneficio_plan_guardado", "beneficio_plan_eliminado",
        "pack_opositickets_guardado", "servicio_market_guardado", "promocion_guardada", "regla_popup_guardada",
        "advertencia_usuario",
        // AdminAccessRequests.kt
        "solicitud_acceso_aprobada", "solicitud_acceso_rechazada", "solicitud_acceso_cuenta_eliminada", "solicitud_acceso_email_confirmado",
        "solicitud_acceso_email_verificado", "solicitud_acceso_caducada", "solicitud_acceso_aprobacion_revertida",
        // OposiBotsUserAdminRoutes.kt
        "oposibots_usuario_creacion_solicitada", "oposibots_usuario_creado", "oposibots_usuario_creacion_fallida",
        "oposibots_usuario_actualizacion_solicitada", "oposibots_usuario_actualizado", "oposibots_usuario_actualizacion_fallida",
        "oposibots_usuario_eliminacion_solicitada", "oposibots_usuario_eliminado", "oposibots_usuario_eliminacion_fallida",
        // NewsService.kt
        "noticia_creada", "noticia_actualizada", "noticia_eliminada", "noticia_publicada", "noticia_programada", "noticia_archivada",
        "noticia_asset_subido", "noticia_guardada_legacy", "noticia_eliminada_legacy", "tipo_noticia_guardado",
        // La base (migración de solicitudes de eliminación de cuenta)
        "cuenta_eliminacion_solicitada",
    )

    @Test
    fun `every action code the backend writes has a label of its own`() {
        assertEquals(backendActionCodes.size, backendActionCodes.toSet().size, "la lista de la prueba tiene un código repetido")
        val unlabelled = backendActionCodes.filterNot(::auditActionIsKnown)
        assertTrue(unlabelled.isEmpty(), "códigos del servidor sin etiqueta: $unlabelled")
        backendActionCodes.forEach { code ->
            val label = auditActionLabel(code)
            assertTrue(label.isNotBlank() && label != code && '_' !in label, "«$code» se enseñaría en crudo: «$label»")
        }
        // Dos acciones distintas no pueden leerse igual: en el desplegable serían indistinguibles.
        val repeated = auditActionLabels.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(repeated.isEmpty(), "etiquetas repetidas: $repeated")
    }

    @Test
    fun `the dictionary offers no action the backend does not write`() {
        // El desplegable de filtro sale del diccionario: una etiqueta de más sería un filtro que nunca devuelve nada.
        assertEquals(emptySet(), auditActionLabels.keys - backendActionCodes.toSet())
    }

    @Test
    fun `an unknown code is shown as it is and an unknown table too`() {
        assertEquals("accion_del_futuro", auditActionLabel("accion_del_futuro"))
        assertFalse(auditActionIsKnown("accion_del_futuro"))
        assertEquals("Cuenta dada de baja", auditActionLabel("cuenta_dada_de_baja"))

        assertEquals("Usuarios", auditTableLabel("perfiles_usuario"))
        assertEquals("tabla_nueva", auditTableLabel("tabla_nueva"))
        assertNull(auditTableLabel(null))
        assertNull(auditTableLabel(" "))
    }

    @Test
    fun `who did it is the name, then the identifier, and the system when there is nobody`() {
        assertEquals("Ana García", auditActorLabel(movement("m1", actorId = "u1", actorName = "Ana García")))
        assertEquals("u2", auditActorLabel(movement("m2", actorId = "u2", actorName = null)))
        assertEquals("Sistema", auditActorLabel(movement("m3", actorId = null, actorName = null)))
        assertEquals("Sin registro asociado", auditTargetLabel(movement("m4", table = null)))
    }

    @Test
    fun `the people to filter by are the ones seen, once each, and a name replaces an identifier`() {
        val first = auditActorOptions(
            listOf(
                movement("m1", actorId = "u2", actorName = null),
                movement("m2", actorId = "u1", actorName = "Ana García"),
                movement("m3", actorId = "u1", actorName = "Ana García"),
                movement("m4", actorId = null, actorName = null),
            ),
        )
        assertEquals(listOf(AuditActorOption("u1", "Ana García"), AuditActorOption("u2", "u2")), first)

        // Otra página trae el nombre que faltaba y una persona nueva; las de antes no se pierden.
        val second = auditActorOptions(listOf(movement("m5", actorId = "u2", actorName = "Zoe"), movement("m6", actorId = "u3", actorName = "Bea")), first)
        assertEquals(listOf("Ana García", "Bea", "Zoe"), second.map { it.label })
    }

    @Test
    fun `the local search looks at labels, codes and identifiers`() {
        val m = movement("m1", actorId = "u-ana", actorName = "Ana García", action = "cuenta_dada_de_baja", table = "perfiles_usuario", recordId = "r-77")
        listOf("", "  ", "baja", "CUENTA_DADA", "garcía", "u-ana", "usuarios", "perfiles", "r-77").forEach {
            assertTrue(auditMatches(m, it), "debería coincidir con «$it»")
        }
        assertFalse(auditMatches(m, "noticia"))
    }

    // ------------------------------------------------------------------------------------------------ cuándo

    @Test
    fun `a moment is shown complete and in the local time of the machine`() {
        // 12:34:56 UTC es 14:34:56 en Madrid en octubre (horario de verano).
        assertEquals("13/10/2026, 14:34:56", formatAuditMoment("2026-10-13T12:34:56.123456+00:00", madrid))
        assertEquals("13/10/2026 14:34", formatAuditMoment("2026-10-13T12:34:56.123456+00:00", madrid, withSeconds = false))
        // En invierno, una hora menos de diferencia.
        assertEquals("05/01/2026, 09:05:07", formatAuditMoment("2026-01-05T08:05:07Z", madrid))
        // La hora local puede caer en OTRO día que la UTC: se enseña el de aquí.
        assertEquals("14/10/2026, 00:30:00", formatAuditMoment("2026-10-13T22:30:00Z", madrid))
        assertEquals("13/10/2026, 22:30:00", formatAuditMoment("2026-10-13T22:30:00Z", TimeZone.UTC))
    }

    @Test
    fun `a moment that cannot be read is shown as it came`() {
        assertEquals("ayer por la tarde", formatAuditMoment("ayer por la tarde", madrid))
        assertEquals("", formatAuditMoment("", madrid))
    }

    @Test
    fun `a date range is whole local days turned into instants`() {
        // «Desde el 13» empieza a las 00:00 de Madrid (22:00 UTC del 12); «hasta el 13» incluye el 13 entero.
        assertEquals(
            AuditDateRange(from = "2026-10-12T22:00:00Z", until = "2026-10-13T22:00:00Z"),
            auditDateRange("2026-10-13", "2026-10-13", madrid),
        )
        assertEquals(AuditDateRange(from = "2026-10-01T00:00:00Z"), auditDateRange(" 2026-10-01 ", "", TimeZone.UTC))
        assertEquals(AuditDateRange(until = "2026-10-14T00:00:00Z"), auditDateRange("", "2026-10-13", TimeZone.UTC))
        assertEquals(AuditDateRange(), auditDateRange("", "", madrid))
    }

    @Test
    fun `a date being typed, a date that does not exist and a reversed range are not a question yet`() {
        assertNull(auditDateRange("2026-10", "", madrid))
        assertNull(auditDateRange("", "2026-02-30", madrid))
        assertNull(auditDateRange("13/10/2026", "", madrid))
        assertNull(auditDateRange("2026-10-13", "2026-10-01", madrid))

        assertEquals(AuditDateInput.EMPTY, auditDateInput("  "))
        assertEquals(AuditDateInput.INCOMPLETE, auditDateInput("2026-1"))
        assertEquals(AuditDateInput.INVALID, auditDateInput("2026-13-01"))
        assertEquals(AuditDateInput.INVALID, auditDateInput("2026-10-131"))
        assertEquals(AuditDateInput.VALID, auditDateInput("2026-10-13"))

        // Mientras se escribe se orienta; solo una fecha imposible es un error.
        assertNull(auditDateHint(""))
        assertNull(auditDateHint("2026-10-13"))
        assertTrue(auditDateHint("2026-1")!!.isNotBlank())
        assertTrue("no existe" in auditDateHint("2026-02-30")!!)
        assertTrue(auditDateRangeIsReversed("2026-10-13", "2026-10-01"))
        assertFalse(auditDateRangeIsReversed("2026-10-13", "2026-10-13"))
        assertFalse(auditDateRangeIsReversed("2026-10-13", "2026-1"))
    }

    // ------------------------------------------------------------------------------------------------ qué cambió

    @Test
    fun `before and after become one readable line per piece of data`() {
        val rows = auditChangeRows(
            before = listOf(AuditField("estado_cuenta", "activa"), AuditField("nivel", "2")),
            after = listOf(AuditField("estado_cuenta", "bloqueada"), AuditField("motivo", "Uso indebido."), AuditField("nivel", "2")),
        )
        assertEquals(listOf("Estado de la cuenta", "Motivo", "Nivel"), rows.map { it.label })
        assertEquals(listOf("activa → bloqueada", "Uso indebido.", "2"), rows.map { it.text })
        // Lo que no cambió se dice; lo que cambió y lo que es nuevo no necesitan nota.
        assertEquals(listOf(null, null, "Sin cambio"), rows.map { it.note })
    }

    @Test
    fun `a value that only existed before is shown as the previous value`() {
        val rows = auditChangeRows(before = listOf(AuditField("codigo", "pack_500"), AuditField("activo", "true")), after = null)
        assertEquals(listOf("Código" to "pack_500", "Activo" to "Sí"), rows.map { it.label to it.text })
        assertTrue(rows.all { it.note == "Valor anterior" })
    }

    @Test
    fun `values and keys are made readable without inventing anything`() {
        assertEquals("Sí", auditFieldValue("true"))
        assertEquals("No", auditFieldValue("false"))
        assertEquals("Sin valor", auditFieldValue(null))
        assertEquals("Vacío", auditFieldValue(" "))
        assertEquals("a, b", auditFieldValue("a, b"))

        assertEquals("Roles", auditFieldLabel("roleIds"))
        // Una clave que nadie ha traducido sigue siendo la clave real, solo sin guiones bajos.
        assertEquals("campo nuevo", auditFieldLabel("campo_nuevo"))
        assertEquals("Tarifas · n.º 1 · Precio (céntimos)", auditFieldLabel("tarifas.1.precio_centimos"))
        assertEquals("Valor", auditFieldLabel(""))
    }

    @Test
    fun `a movement with neither before nor after has no lines`() {
        assertTrue(auditChangeRows(null, null).isEmpty())
        assertTrue(auditChangeRows(emptyList(), null).isEmpty())
    }

    @Test
    fun `the foot of the list counts what is loaded and says whether there is more`() {
        assertEquals("1 movimiento cargado. No hay más.", auditLoadedSummary(1, hasMore = false))
        assertEquals("50 movimientos cargados. Quedan más por cargar.", auditLoadedSummary(50, hasMore = true))
    }

    private fun movement(
        id: String,
        actorId: String? = "u1",
        actorName: String? = "Ana García",
        action: String = "cuenta_dada_de_baja",
        table: String? = "perfiles_usuario",
        recordId: String? = "r1",
    ) = AuditMovement(id, actorId, actorName, action, table, recordId, "2026-10-13T12:34:56+00:00")
}
