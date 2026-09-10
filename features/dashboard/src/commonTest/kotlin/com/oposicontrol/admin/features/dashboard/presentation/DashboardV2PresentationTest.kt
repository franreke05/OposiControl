package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardMetricKeys
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW = Instant.fromEpochSeconds(1_756_000_000L)

private fun metric(
    key: String,
    value: AnalyticsValue?,
    status: AnalyticsMetricStatus,
) = AnalyticsMetric(key = key, value = value, status = status)

class DashboardMetricRenderingTest {

    @Test
    fun unCeroEsUnDatoYSePinta() {
        // El defecto que este rediseño cierra era exactamente el contrario:
        // enseñar 0 cuando no se sabía. Aquí el 0 medido se enseña como 0.
        val cell = dashboardMetricCell(
            metric(DashboardMetricKeys.REPORTS_PENDING, AnalyticsValue.Count(0), AnalyticsMetricStatus.AVAILABLE),
        )

        assertIs<DashboardMetricCell.Number>(cell)
        assertEquals("0", cell.text)
    }

    @Test
    fun sinDatoOcupaSitioYLoDice() {
        val cell = dashboardMetricCell(
            metric(DashboardMetricKeys.DRAFT_EXAMS, null, AnalyticsMetricStatus.NO_DATA),
        )

        assertEquals(DashboardMetricCell.NoData, cell)
        assertEquals(
            "Sin dato",
            dashboardMetricText(metric(DashboardMetricKeys.DRAFT_EXAMS, null, AnalyticsMetricStatus.NO_DATA)),
            "Reintentar puede arreglarlo, así que la tarjeta se queda.",
        )
    }

    @Test
    fun unaMetricaSinInstrumentarNoSePinta() {
        val notInstrumented = metric(
            DashboardMetricKeys.GROSS_REVENUE_CENTS,
            null,
            AnalyticsMetricStatus.NOT_INSTRUMENTED,
        )

        assertEquals(DashboardMetricCell.Hidden, dashboardMetricCell(notInstrumented))
        assertNull(
            dashboardMetricText(notInstrumented),
            "Un hueco con nombre no le sirve de nada a quien mira mientras nadie construya el agregado.",
        )
    }

    @Test
    fun renderableDejaFueraLasNoInstrumentadasYConservaLasSinDato() {
        val report = DashboardBlockReport(
            block = DashboardBlock.ECONOMY,
            metrics = listOf(
                metric(DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE, AnalyticsValue.Count(0), AnalyticsMetricStatus.AVAILABLE),
                metric(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D, null, AnalyticsMetricStatus.NO_DATA),
                metric(DashboardMetricKeys.GROSS_REVENUE_CENTS, null, AnalyticsMetricStatus.NOT_INSTRUMENTED),
                metric(DashboardMetricKeys.TICKETS_IN_CIRCULATION, null, AnalyticsMetricStatus.NOT_INSTRUMENTED),
            ),
            generatedAt = NOW,
        )

        assertEquals(
            listOf(DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE, DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D),
            report.renderable.map { it.key },
        )
    }

    @Test
    fun unaMetricaMarcadaDisponibleSinValorFallaCerrada() {
        // No debería ocurrir —la política del dominio lo garantiza— pero si
        // ocurriera, «Sin dato» es mejor que un cero inventado.
        assertEquals(
            DashboardMetricCell.NoData,
            dashboardMetricCell(metric("loQueSea", null, AnalyticsMetricStatus.AVAILABLE)),
        )
    }

    @Test
    fun unPorcentajeSeFormateaConSuUnidad() {
        val cell = dashboardMetricCell(
            metric(DashboardMetricKeys.GLOBAL_ERROR_RATE, AnalyticsValue.Percentage(12.34), AnalyticsMetricStatus.AVAILABLE),
        )

        assertIs<DashboardMetricCell.Number>(cell)
        assertTrue(cell.text.endsWith("%"), "Un porcentaje sin unidad se lee como un contador.")
    }
}

class DashboardRelativeTimeTest {

    @Test
    fun menosDeUnMinutoNoFingePrecision() {
        assertEquals("hace un momento", dashboardRelativeTime(NOW.minusSeconds(18), NOW))
    }

    @Test
    fun minutos() {
        assertEquals("hace 12 min", dashboardRelativeTime(NOW.minusSeconds(12 * 60), NOW))
        assertEquals("hace 59 min", dashboardRelativeTime(NOW.minusSeconds(59 * 60 + 59), NOW))
    }

    @Test
    fun horas() {
        assertEquals("hace 1 h", dashboardRelativeTime(NOW.minusSeconds(60 * 60), NOW))
        assertEquals("hace 23 h", dashboardRelativeTime(NOW.minusSeconds(23 * 3_600), NOW))
    }

    @Test
    fun dias() {
        assertEquals("hace 1 d", dashboardRelativeTime(NOW.minusSeconds(24 * 3_600), NOW))
        assertEquals("hace 9 d", dashboardRelativeTime(NOW.minusSeconds(9 * 24 * 3_600), NOW))
    }

    @Test
    fun elFuturoNoSeAnuncia() {
        // Un hecho ya registrado no puede estar por ocurrir: si `occurredAt` va
        // por delante del reloj local, la diferencia es desfase de relojes y no
        // información que darle a nadie.
        assertEquals("ahora mismo", dashboardRelativeTime(NOW.plusSeconds(180), NOW))
    }
}

private fun Instant.minusSeconds(seconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds - seconds)
private fun Instant.minusSeconds(seconds: Int): Instant = minusSeconds(seconds.toLong())
private fun Instant.plusSeconds(seconds: Long): Instant = Instant.fromEpochSeconds(epochSeconds + seconds)

/**
 * Las 24 acciones que el backend escribe con la cadena completa.
 *
 * Extraídas del repositorio, no de memoria:
 * `grep -rhoE 'registrarAuditoria\("[^"]+"' backend/`, quitando las cuatro con
 * sufijo interpolado. Escritas a mano aquí a propósito: derivarlas de
 * `dashboardActivityVocabulary` haría que el test comparase el mapa consigo
 * mismo y pasara siempre.
 */
private val ACCIONES_REALES = setOf(
    "beneficio_plan_guardado",
    "capacidades_especialidad_actualizadas",
    "comunidad_eliminada",
    "especialidad_archivada",
    "etiqueta_eliminada",
    "examen_archivado",
    "examen_creado",
    "examen_despublicado",
    "examen_publicado",
    "pack_opositickets_guardado",
    "plan_suscripcion_guardado",
    "pregunta_archivada",
    "pregunta_creada",
    "preguntas_examen_reordenadas",
    "producto_comercial_guardado",
    "promocion_guardada",
    "recurso_pregunta_archivado",
    "recurso_pregunta_creado",
    "regla_popup_guardada",
    "roles_sincronizados",
    "servicio_market_guardado",
    "tarifa_producto_guardada",
    "tickets_grant",
    "usuario_actualizado",
)

class DashboardActivityCopyTest {

    @Test
    fun elVocabularioEstaticoEsExactamenteElQueElBackendEscribe() {
        // ESTE es el test que faltaba. El anterior preguntaba «¿traduce
        // ban_usuario?» y pasaba tan campante — porque sí lo traducía, sólo que
        // ningún camino del código escribe jamás esa fila. Cinco etiquetas
        // salieron de VALID_MODERATION_ACTION_TYPES, que gobierna la tabla
        // moderation_action y no pasa por registrarAuditoria.
        //
        // Comparar el conjunto entero es lo único que detecta una traducción de
        // más. Preguntar por una clave sólo detecta una de menos.
        assertEquals(
            ACCIONES_REALES,
            dashboardActivityVocabulary,
            "Sobra o falta alguna acción. Si el backend añadió una, tradúcela; si aquí hay una " +
                "que ningún registrarAuditoria produce, bórrala: traducir lo inexistente afirma " +
                "un conocimiento del sistema que no se tiene.",
        )
    }

    @Test
    fun todaAccionRealSeTraduceAAlgoDistintoDeSuCadenaCruda() {
        // El efecto, no el predicado: no importa CUÁL sea la etiqueta, importa
        // que ninguna acción real se quede sin traducir.
        ACCIONES_REALES.forEach { accion ->
            assertNotEquals(
                accion,
                dashboardActivityLabel(accion),
                "La acción «$accion» llega a pantalla sin traducir.",
            )
        }
    }

    @Test
    fun laAdvertenciaRealEsAdvertenciaUsuarioYNoLasCincoQueSeInventaron() {
        // Regresión del defecto. El único escritor de `*_usuario` es warnUser, y
        // el escritorio fija actionType = "advertencia"
        // (KtorModerationRepositoryImpl.kt:87), así que la fila real es ésta.
        assertEquals("Advertencia a usuario", dashboardActivityLabel("advertencia_usuario"))
        assertEquals(StatusTone.ATTENTION, dashboardActivityTone("advertencia_usuario"))

        // Y las cinco fantasma ya no tienen etiqueta propia: caen por familia,
        // enseñando su sufijo crudo, que es lo honesto para algo que nadie escribe.
        listOf("ban_usuario", "mute_usuario", "warning_usuario", "internal_note_usuario").forEach { fantasma ->
            assertTrue(
                fantasma !in dashboardActivityVocabulary,
                "«$fantasma» volvió al vocabulario y ningún camino del código la produce.",
            )
            assertEquals(StatusTone.NEUTRAL, dashboardActivityTone(fantasma))
        }
        assertEquals("Moderación de usuario · ban", dashboardActivityLabel("ban_usuario"))
    }

    @Test
    fun lasFamiliasInterpoladasTraducenSoloElSufijoVerificable() {
        // Los sufijos que sí se pueden leer del literal Kotlin que los produce.
        assertEquals("Lote de preguntas importado", dashboardActivityLabel("lote_importado"))
        assertEquals("Reporte resuelto", dashboardActivityLabel("reporte_resuelto"))
        assertEquals("Duda de pregunta descartada", dashboardActivityLabel("feedback_descartado"))

        // Y el sufijo que no: etiqueta de familia + el crudo detrás, nunca una
        // traducción inventada. Vale tanto para una fila histórica escrita por una
        // versión anterior como para un actionType nuevo que entre por HTTP.
        assertEquals("Reporte · escalado", dashboardActivityLabel("reporte_escalado"))
        assertEquals("Importación de preguntas · cancelado", dashboardActivityLabel("lote_cancelado"))
        assertEquals("Duda de pregunta · reabierta", dashboardActivityLabel("feedback_reabierta"))
    }

    @Test
    fun unSufijoDesconocidoNoHeredaElTonoDeSuFamilia() {
        // `lote_importado` es verde; eso no autoriza a pintar verde cualquier
        // `lote_*`. Lo desconocido es NEUTRAL, nunca «todo bien» por descarte.
        assertEquals(StatusTone.POSITIVE, dashboardActivityTone("lote_importado"))
        assertEquals(StatusTone.NEUTRAL, dashboardActivityTone("lote_cancelado"))
        assertEquals(StatusTone.NEGATIVE, dashboardActivityTone("reporte_descartado"))
        assertEquals(StatusTone.NEUTRAL, dashboardActivityTone("reporte_escalado"))
    }

    @Test
    fun unaAccionDesconocidaCaeAlTextoCrudoYNoSePintaEnVerde() {
        // Se lee peor, y esa es la idea: se ve que falta traducirla en vez de
        // esconderla detrás de un «Actividad del sistema» genérico.
        assertEquals("accion_que_nadie_ha_traducido", dashboardActivityLabel("accion_que_nadie_ha_traducido"))
        assertEquals(StatusTone.NEUTRAL, dashboardActivityTone("accion_que_nadie_ha_traducido"))
    }

    @Test
    fun unaFamiliaSinSufijoSeEnsenaEnCrudoEnVezDeDejarUnSeparadorColgando() {
        assertEquals("reporte_", dashboardActivityLabel("reporte_"))
        assertEquals(StatusTone.NEUTRAL, dashboardActivityTone("reporte_"))
    }
}

/**
 * Las 22 tablas que el backend nombra en `tabla_afectada`, extraídas de las
 * llamadas reales a `registrarAuditoria`. Escritas a mano por el mismo motivo
 * que [ACCIONES_REALES]: derivarlas del mapa lo compararía consigo mismo.
 */
private val TABLAS_REALES = setOf(
    "beneficios_plan",
    "capacidades_especialidad",
    "carteras_usuario",
    "comunidades_autonomas",
    "especialidades",
    "etiquetas",
    "examenes",
    "feedback_pregunta",
    "lotes_importacion_preguntas",
    "paquetes_opositickets",
    "perfiles_usuario",
    "planes_suscripcion",
    "preguntas",
    "preguntas_examen",
    "productos_comerciales",
    "promociones",
    "recursos_pregunta",
    "reglas_popup_comercial",
    "reportes_contenido",
    "roles_usuario",
    "servicios",
    "tarifas_producto_comercial",
)

class DashboardActivityIconTest {

    @Test
    fun laTablaDeIconosEsExactamenteLaQueElBackendEscribe() {
        // Mismo cierre que `elVocabularioEstaticoEsExactamenteElQueElBackendEscribe`,
        // aplicado a la otra superficie con la misma forma. Aquí el contenido ya
        // era correcto; lo que faltaba era el test que impide que deje de serlo.
        assertEquals(
            TABLAS_REALES,
            dashboardActivityIconTables,
            "Sobra o falta una tabla. Un icono para una tabla que nadie escribe es la misma " +
                "clase de afirmación sin respaldo que una acción inventada.",
        )
    }

    @Test
    fun unaTablaDesconocidaCaeAlIconoDeAuditoriaYNoAlDeSuVecina() {
        assertEquals(AppIcon.AUDIT, dashboardActivityIcon("tabla_que_no_existe"))
        assertEquals(AppIcon.AUDIT, dashboardActivityIcon(null))
    }

    @Test
    fun todaTablaRealTieneUnIconoQueNoEsElDeDescarte() {
        TABLAS_REALES.forEach { tabla ->
            assertNotEquals(
                AppIcon.AUDIT,
                dashboardActivityIcon(tabla),
                "La tabla «$tabla» cae al icono genérico teniendo uno propio disponible.",
            )
        }
    }
}

/**
 * Aritmética de la franja de sistema.
 *
 * `SpaceBetween` mide al primer hijo con TODO el ancho y al segundo con lo que
 * sobre, así que sin un máximo sobre el grupo izquierdo el grupo de acciones
 * puede quedarse en cero. Esto no reemplaza a un test de UI —no dibuja nada—
 * pero sí fija la cuenta de la que depende que «Ver estado» y «Reintentar»
 * tengan sitio.
 */
class SystemStripWidthTest {

    @Test
    fun elGrupoIzquierdoNuncaInvadeLaReservaDeLasAcciones() {
        // Anchos de contenido reales: ventana mínima de escritorio (1040dp, que es
        // donde arranca AppBreakpoint.EXPANDED) con la sidebar desplegada y
        // plegada, una ventana holgada, y dos por debajo del suelo real para que
        // la cuenta siga siendo correcta si algún día baja el umbral.
        listOf(
            756.dp to "1040dp con sidebar desplegada (suelo real del panel)",
            932.dp to "1040dp con sidebar plegada",
            1148.dp to "1440dp con sidebar desplegada",
            640.dp to "por debajo del suelo: donde el enlace se quedaba en 9dp",
            600.dp to "por debajo del suelo: donde el enlace desaparecía",
        ).forEach { (contentWidth, caso) ->
            val padding = 16.dp * 2
            listOf(false to 104.dp, true to 240.dp).forEach { (showsRetry, reserve) ->
                val left = systemStripLeftMaxWidth(contentWidth, showsRetry)
                assertTrue(
                    left + padding + reserve <= contentWidth,
                    "En «$caso» (showsRetry=$showsRetry) el grupo izquierdo ($left) más el padding " +
                        "y la reserva se salen de $contentWidth: las acciones dejarían de dibujarse.",
                )
            }
        }
    }

    @Test
    fun enEstadoNormalNoSeReservaSitioParaUnBotonQueNoEstaDibujado() {
        // Reservar siempre lo del estado de error apretaría el grupo izquierdo
        // 136dp de más el 99% del tiempo, y elidiría «Actualizado hace…» sin
        // ninguna necesidad. Medido: el grupo izquierdo pide ~570dp y a 756dp de
        // contenido hay 620dp disponibles, así que entra sin recortes.
        val normal = systemStripLeftMaxWidth(756.dp, showsRetry = false)
        val conError = systemStripLeftMaxWidth(756.dp, showsRetry = true)

        assertTrue(normal > conError, "El estado normal tiene que disponer de más sitio que el de error.")
        assertTrue(
            normal >= 600.dp,
            "A 756dp el grupo izquierdo pide ~570dp; con $normal disponibles se elidiría sin motivo.",
        )
    }

    @Test
    fun conVentanaAbsurdamenteEstrechaElGrupoIzquierdoNoColapsaANada() {
        // Defensivo: no debería alcanzarse nunca, pero un máximo de 0 dejaría la
        // franja sin icono ni título, que se lee como un fallo de render.
        assertTrue(systemStripLeftMaxWidth(200.dp, showsRetry = false) >= 120.dp)
        assertTrue(systemStripLeftMaxWidth(200.dp, showsRetry = true) >= 120.dp)
    }
}

class DashboardActivityDroppedTest {

    @Test
    fun sinDescartesLaListaVaciaSiPuedeDecirQueNoHaPasadoNada() {
        assertEquals("No hay acciones registradas todavía.", dashboardActivityEmptyMessage(0))
        assertNull(dashboardActivityDroppedNote(0))
    }

    @Test
    fun conDescartesLaListaVaciaNoPuedeAfirmarQueNoHaPasadoNada() {
        // Es la frase que A rechaza en su propio test del bloque fallido, aplicada
        // al caso de las filas descartadas: hubo actividad, no se pudo situar.
        val mensaje = dashboardActivityEmptyMessage(8)

        assertTrue(
            "No hay acciones registradas" !in mensaje,
            "Con 8 filas descartadas, decir que no hay actividad es falso: la hubo y no se pudo leer.",
        )
        assertTrue("8" in mensaje, "El número de descartes es el dato útil y tiene que verse.")
    }

    @Test
    fun conFilasVisiblesLosDescartesSeDicenAlPie() {
        assertEquals("1 acción más sin fecha legible, no se muestra.", dashboardActivityDroppedNote(1))
        assertEquals("3 acciones más sin fecha legible, no se muestran.", dashboardActivityDroppedNote(3))
    }

    @Test
    fun unContadorNegativoSeTrataComoAusenciaDeDescartes() {
        // Defensivo: el contrato dice Int y no puede ser negativo, pero un valor
        // imposible no debe producir una frase imposible.
        assertNull(dashboardActivityDroppedNote(-1))
        assertEquals("No hay acciones registradas todavía.", dashboardActivityEmptyMessage(-1))
    }
}

class DashboardAttentionCopyTest {

    @Test
    fun lasSuscripcionesQueVencenUsanLaVentanaDeSieteDias() {
        val copy = dashboardAttentionCopy(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D)

        assertEquals("En los próximos 7 días", copy.supporting)
        assertEquals(DashboardV2Destination.SUBSCRIPTIONS, copy.destination)
    }

    @Test
    fun cadaContadorDeAtencionSabeAbrirLaPantallaQueLoResuelve() {
        assertEquals(
            DashboardV2Destination.MODERATION_QUEUE,
            dashboardAttentionCopy(DashboardMetricKeys.REPORTS_PENDING).destination,
        )
        assertEquals(
            DashboardV2Destination.OPOSITESTER_LIST,
            dashboardAttentionCopy(DashboardMetricKeys.QUESTION_FEEDBACK_PENDING).destination,
        )
        assertEquals(
            DashboardV2Destination.EXAMS,
            dashboardAttentionCopy(DashboardMetricKeys.DRAFT_EXAMS).destination,
        )
    }

    @Test
    fun unaClaveNuevaSeEnsenaSinEnlaceEnVezDeMandarAUnaPantallaCualquiera() {
        val copy = dashboardAttentionCopy("metricaQueNadieHaMapeado")

        assertNull(copy.destination)
        assertNull(copy.actionLabel)
        assertEquals("metricaQueNadieHaMapeado", copy.title)
    }

    @Test
    fun soloLasFilasDeContenidoConDestinoVerificadoOfrecenChevron() {
        assertEquals(DashboardV2Destination.EXAMS, dashboardContentDestination(DashboardMetricKeys.TOTAL_EXAMS))
        assertEquals(DashboardV2Destination.QUESTIONS, dashboardContentDestination(DashboardMetricKeys.TOTAL_QUESTIONS))
        assertEquals(DashboardV2Destination.IMPORTS, dashboardContentDestination(DashboardMetricKeys.IMPORT_BATCHES))
    }

    @Test
    fun recursosNoDuplicaElEnlaceDePreguntas() {
        // Recursos resuelve a la MISMA pantalla que Preguntas. Un segundo enlace
        // al mismo sitio, en la fila de al lado, promete una separación que no
        // existe: se pulsaría esperando recursos y se vería el banco de preguntas.
        assertNull(dashboardContentDestination(DashboardMetricKeys.TOTAL_RESOURCES))
    }

    /**
     * Las **17** claves del contrato tienen nombre legible.
     *
     * Antes esta lista tenía 15: faltaban `grossRevenueCents` y
     * `ticketsInCirculation`, precisamente las dos con más papeletas de pasar a
     * `AVAILABLE` en cuanto exista su vista, y por tanto las dos que aparecerían
     * en pantalla con la clave cruda.
     *
     * **Qué NO promete este test.** No detecta una clave *nueva* del contrato: la
     * lista está escrita a mano y una decimoctava clave no aparecería aquí sola.
     * Esa exhaustividad la garantiza `DashboardOverviewContractTest` en `:domain`,
     * que fija las 17 por su literal y afirma el tamaño, así que una clave nueva
     * rompe allí primero y obliga a pasar por aquí. Lo que este test sí garantiza
     * es que ninguna de las 17 declaradas hoy llega a pantalla sin traducir.
     */
    @Test
    fun lasDiecisieteClavesDeclaradasHoyTienenNombreLegible() {
        val keys = listOf(
            DashboardMetricKeys.REPORTS_PENDING,
            DashboardMetricKeys.QUESTION_FEEDBACK_PENDING,
            DashboardMetricKeys.DRAFT_EXAMS,
            DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D,
            DashboardMetricKeys.ACTIVE_USERS_30D,
            DashboardMetricKeys.ATTEMPTS_LAST_30D,
            DashboardMetricKeys.PUBLISHED_EXAMS,
            DashboardMetricKeys.GLOBAL_ERROR_RATE,
            DashboardMetricKeys.TOTAL_EXAMS,
            DashboardMetricKeys.TOTAL_QUESTIONS,
            DashboardMetricKeys.TOTAL_RESOURCES,
            DashboardMetricKeys.IMPORT_BATCHES,
            DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE,
            DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D,
            DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE,
            // Hoy siempre NOT_INSTRUMENTED y filtradas por `renderable`; en cuanto
            // exista `v_ingresos_brutos` o `v_opositickets_en_circulacion` pasan a
            // AVAILABLE y salen a pantalla.
            DashboardMetricKeys.GROSS_REVENUE_CENTS,
            DashboardMetricKeys.TICKETS_IN_CIRCULATION,
        )

        assertEquals(17, keys.size, "El contrato declara 17 claves; esta lista tiene que cubrirlas todas.")
        assertEquals(keys.size, keys.distinct().size, "Hay una clave repetida en la lista.")

        keys.forEach { key ->
            val copy = dashboardMetricCopy(key)
            assertNotNull(copy.title)
            assertTrue(copy.title != key, "La clave «$key» llega a pantalla sin traducir.")
        }
    }

    @Test
    fun lasDosMetricasDeDineroSePintaranBienElDiaQueTraiganNumero() {
        // No basta con que tengan nombre: cuando el agregado exista llegarán como
        // MoneyCents y Count, y `format()` tiene que darles su unidad. Si esto
        // fallara, el día del cambio saldrían céntimos crudos en pantalla.
        val ingresos = dashboardMetricCell(
            metric(DashboardMetricKeys.GROSS_REVENUE_CENTS, AnalyticsValue.MoneyCents(184_250), AnalyticsMetricStatus.AVAILABLE),
        )
        assertIs<DashboardMetricCell.Number>(ingresos)
        assertEquals("1842,50 EUR", ingresos.text, "Tiene que salir en euros, no en céntimos crudos.")

        val tickets = dashboardMetricCell(
            metric(DashboardMetricKeys.TICKETS_IN_CIRCULATION, AnalyticsValue.Count(9_120), AnalyticsMetricStatus.AVAILABLE),
        )
        assertIs<DashboardMetricCell.Number>(tickets)
        assertEquals("9120", tickets.text)
    }
}
