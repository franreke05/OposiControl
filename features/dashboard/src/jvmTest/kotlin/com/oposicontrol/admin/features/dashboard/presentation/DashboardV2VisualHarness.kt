package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.model.DashboardActivityEntry
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardHealth
import com.oposicontrol.admin.domain.model.DashboardMetricKeys
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Banco de pruebas VISUAL del panel de escritorio.
 *
 * **Qué es y qué no es.** Renderiza el panel real —el mismo `DashboardV2Content`
 * que corre en producción, dentro del mismo `DesktopShell` y del mismo
 * `AdminTheme`— contra un estado fabricado, y escribe PNG. Sirve para revisar
 * cristal, fondo, jerarquía, tamaños, color, densidad y responsive **sin
 * levantar Ktor**, que es lo que este equipo no puede sostener a la vez que la
 * ventana de escritorio.
 *
 * **NO valida datos.** Ni una línea de este fichero toca un repositorio, una
 * ruta o un contrato. Que una captura se vea bien no dice absolutamente nada
 * sobre si el panel lee bien de Supabase: eso sigue sin verificar y hay que
 * decirlo así.
 *
 * **Por qué vive en `jvmTest`.** Porque las fuentes de test no se empaquetan.
 * Ésa es la única garantía *por construcción* de que estos datos no pueden
 * alcanzar la aplicación: no hay flag que desactivar, ni `if (debug)` que
 * alguien pueda invertir, ni un `catch { datosDeMentira }` que un fallo de red
 * pueda disparar. Si Ktor no está en pie, el panel real sigue enseñando su
 * error, y eso es correcto.
 *
 * Las capturas se escriben en `features/dashboard/build/visual-harness/`.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardV2VisualHarness {

    // -----------------------------------------------------------------------
    // Capturas
    // -----------------------------------------------------------------------

    @Test
    fun capturaElPanelEnLasCuatroAnchurasObjetivo() {
        // 1920 es la principal. 1600 y 1366 son los dos portátiles reales del
        // objetivo; 1080 baja del umbral de 1100dp y por tanto ejercita el
        // régimen COMPACT (sidebar plegada, 2 columnas de atención, 3 de
        // atajos, rejilla inferior apilada).
        listOf(
            Capture("dashboard-1920.png", 1920, 1080, dark = false),
            Capture("dashboard-1600.png", 1600, 900, dark = false),
            Capture("dashboard-1366.png", 1366, 768, dark = false),
            Capture("dashboard-1080.png", 1080, 900, dark = false),
        ).forEach { capture ->
            render(capture, poblado())
        }
    }

    /**
     * El panel en el tema OSCURO.
     *
     * El panel se certificó sólo en claro, cuando el oscuro no existía. Se captura
     * la vista de 1920 y la página entera, que es la que enseña la rejilla
     * inferior con sus filas de catálogo y economía.
     */
    @Test
    fun capturaElPanelEnOscuro() {
        render(Capture("dashboard-1920-dark.png", 1920, 1080, dark = true), poblado())
        render(Capture("dashboard-1920-completo-dark.png", 1920, 1500, dark = true), poblado())
    }

    @Test
    fun capturaLaPaginaEnteraParaPoderRevisarLaRejillaInferior() {
        // Una pantalla de 1080 no llega a enseñar la zona inferior: las cuatro
        // franjas de arriba suman 776dp de los 875 disponibles, así que la
        // rejilla asoma ~100dp y se alcanza con un gesto de rueda. Eso es una
        // decisión de densidad, no un defecto — pero deja la cronología, las
        // filas de catálogo y las de economía sin poder revisarse en una captura.
        // Ésta las enseña enteras, a la misma anchura de 1920.
        render(Capture("dashboard-1920-completo.png", 1920, 1500, dark = false), poblado())
        render(Capture("dashboard-1920-completo-ceros.png", 1920, 1500, dark = false), enCero())
    }

    @Test
    fun capturaElCasoRealDeHoyConCeros() {
        // El caso NORMAL, no el excepcional: hoy en vivo hay tres contadores de
        // atención a cero y la tasa de error llega sin dato. Si el diseño sólo
        // se comprueba con la instalación llena, no se ha comprobado lo que el
        // usuario ve todos los días.
        render(Capture("dashboard-1920-ceros.png", 1920, 1080, dark = false), enCero())
        render(Capture("dashboard-1366-ceros.png", 1366, 768, dark = false), enCero())
    }

    // -----------------------------------------------------------------------
    // Motor de captura
    // -----------------------------------------------------------------------

    /**
     * Una captura y su tema, SIEMPRE explícito.
     *
     * `dark` no tiene valor por defecto a propósito. Con `AdminTheme` sin
     * parámetro, el tema lo decidía `isSystemInDarkTheme()`: en una máquina con el
     * sistema en oscuro, las capturas «certificadas en claro» salían oscuras sin
     * que nada lo dijera, y el mismo test daba PNG distintos en dos portátiles.
     */
    private data class Capture(val fileName: String, val width: Int, val height: Int, val dark: Boolean)

    private fun render(capture: Capture, state: DashboardV2State) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                // Densidad 1: así 1 dp es 1 px y el PNG mide exactamente lo que
                // dice su nombre. Sin esto la captura dependería del escalado de
                // la pantalla de quien la genere.
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    HarnessShell(state = state, dark = capture.dark)
                }
            }
            waitForIdle()
            // Las fuentes de `compose-resources` se cargan en una corrutina que
            // el reloj de test no rastrea. Sin esta espera, la primera captura
            // sale con la tipografía de sistema en vez de con Manrope — y una
            // captura que miente sobre la tipografía no vale para revisar
            // jerarquía tipográfica, que es justamente lo que hay que revisar.
            Thread.sleep(FONT_SETTLE_MILLIS)
            waitForIdle()

            val bitmap = onRoot().captureToImage()
            val file = File(outputDir, capture.fileName)
            ImageIO.write(bitmap.toAwtImage(), "png", file)

            assertTrue(
                file.exists() && file.length() > 0L,
                "No se escribió ${file.absolutePath}",
            )
        }
    }

    private val outputDir: File by lazy {
        File("build/visual-harness").also { it.mkdirs() }
    }

    // -----------------------------------------------------------------------
    // Marco: el shell de escritorio real
    // -----------------------------------------------------------------------

    /**
     * El panel dentro de su marco.
     *
     * Se usa `DesktopShell` de verdad —no una imitación— para que la captura
     * enseñe el ancho de contenido correcto: el panel recibe la ventana menos la
     * sidebar menos dos márgenes, y ese ancho es el que decide cuántas columnas
     * caben. Un render sin sidebar daría 244dp de más y las medidas de la
     * captura no serían las de la aplicación.
     *
     * `globalSearch = {}` es exactamente lo que `AppRoot` pasa ahora en la ruta
     * de Inicio en escritorio, y `primaryActionLabel = "Actualizar"` con el icono
     * de refresco también: así la captura enseña las dos correcciones de esta
     * pasada, la retirada del buscador y la acción primaria que faltaba.
     */
    @Composable
    private fun HarnessShell(state: DashboardV2State, dark: Boolean) {
        AdminTheme(darkTheme = dark) {
            DesktopShell(
                currentRouteId = "home",
                groups = HARNESS_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno dev | vista frontend",
                title = "Dashboard",
                description = "Resumen operativo del panel.",
                breadcrumbs = listOf("Inicio", "Dashboard"),
                primaryActionLabel = "Actualizar",
                onPrimaryAction = {},
                primaryActionIcon = AppIcon.REFRESH,
                globalSearch = {},
            ) {
                DashboardV2Content(
                    state = state,
                    now = NOW,
                    onRefreshBlock = {},
                    onOpen = {},
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sidebar del harness
// ---------------------------------------------------------------------------

/**
 * Los catorce items que hoy son visibles en la sidebar, transcritos de
 * `AppNavigationCatalog.kt`.
 *
 * Se transcriben porque el catálogo vive en `composeApp` y este módulo no puede
 * —ni debe— depender de él. **No es el catálogo en vivo**: si alguien renombra
 * una sección allí, esta lista se queda vieja y la captura enseñará el nombre
 * antiguo. Es una limitación conocida y sin consecuencia para lo que estas
 * capturas certifican, que es el panel, no el menú.
 */
private val HARNESS_SIDEBAR: List<ShellNavigationGroup> = listOf(
    ShellNavigationGroup(
        id = "home",
        label = "Inicio",
        items = listOf(ShellNavigationItem("home", "Dashboard", AppIcon.HOME.key)),
    ),
    ShellNavigationGroup(
        id = "content",
        label = "Contenido",
        items = listOf(
            ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key),
            ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key),
            ShellNavigationItem("content.imports", "Importación", AppIcon.IMPORT.key),
        ),
    ),
    ShellNavigationGroup(
        id = "users",
        label = "Usuarios",
        items = listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key)),
    ),
    ShellNavigationGroup(
        id = "economy",
        label = "Monetización",
        items = listOf(
            ShellNavigationItem("commercial.catalog", "Catálogo", AppIcon.CATALOG.key),
            ShellNavigationItem("commercial.market", "OposiMarket", AppIcon.MARKET.key),
            ShellNavigationItem("commercial.tickets", "OposiTickets", AppIcon.TICKETS.key),
        ),
    ),
    ShellNavigationGroup(
        id = "community",
        label = "Comunidad",
        items = listOf(
            ShellNavigationItem("community.oposired", "OposiRed", AppIcon.FORUM.key),
            ShellNavigationItem("moderation", "Moderación", AppIcon.MODERATION.key),
            ShellNavigationItem("support", "Soporte", AppIcon.SUPPORT.key),
        ),
    ),
    ShellNavigationGroup(
        id = "analytics",
        label = "Analítica",
        items = listOf(ShellNavigationItem("analytics", "Analytics", AppIcon.ANALYTICS.key)),
    ),
    ShellNavigationGroup(
        id = "operations",
        label = "Publicación",
        items = listOf(ShellNavigationItem("publication.news", "Noticias", AppIcon.NEWS.key)),
    ),
    ShellNavigationGroup(
        id = "system",
        label = "Sistema",
        items = listOf(ShellNavigationItem("system.settings", "Sistema", AppIcon.SETTINGS.key)),
    ),
)

// ---------------------------------------------------------------------------
// Estados de demostración
// ---------------------------------------------------------------------------

private val NOW = Instant.fromEpochSeconds(1_756_000_000L)
private const val FONT_SETTLE_MILLIS = 2_000L

private fun count(key: String, value: Int) = AnalyticsMetric(
    key = key,
    value = AnalyticsValue.Count(value),
    status = AnalyticsMetricStatus.AVAILABLE,
)

private fun percentage(key: String, value: Double) = AnalyticsMetric(
    key = key,
    value = AnalyticsValue.Percentage(value),
    status = AnalyticsMetricStatus.AVAILABLE,
)

private fun noData(key: String) = AnalyticsMetric(
    key = key,
    value = null,
    status = AnalyticsMetricStatus.NO_DATA,
)

private fun notInstrumented(key: String) = AnalyticsMetric(
    key = key,
    value = null,
    status = AnalyticsMetricStatus.NOT_INSTRUMENTED,
    reason = "No existe el agregado que lo calcula.",
)

private fun report(
    block: DashboardBlock,
    metrics: List<AnalyticsMetric> = emptyList(),
    activity: List<DashboardActivityEntry> = emptyList(),
    health: DashboardHealth? = null,
) = UiAsync.Success(
    DashboardBlockReport(
        block = block,
        metrics = metrics,
        activity = activity,
        health = health,
        generatedAt = NOW,
    ),
)

private fun event(minutesAgo: Long, action: String, table: String) = DashboardActivityEntry(
    id = "$action-$minutesAgo",
    action = action,
    targetTable = table,
    targetId = "00000000-0000-0000-0000-0000000000${minutesAgo.toString().padStart(2, '0')}",
    occurredAt = Instant.fromEpochSeconds(NOW.epochSeconds - minutesAgo * 60),
)

private val HEALTHY = DashboardHealth(
    overall = SystemStatusLevel.OPERATIVE,
    ktor = SystemStatusLevel.OPERATIVE,
    supabase = SystemStatusLevel.OPERATIVE,
    updatedAt = Instant.fromEpochSeconds(NOW.epochSeconds - 720),
    supabaseLatencyMs = 128,
)

/**
 * Instalación con trabajo dentro: es el caso que enseña la jerarquía de color y
 * la cifra dominante con números de varias longitudes (1, 2, 3 y 4 dígitos).
 */
private fun poblado(): DashboardV2State = DashboardV2State(
    blocks = mapOf(
        DashboardBlock.SYSTEM to report(DashboardBlock.SYSTEM, health = HEALTHY),
        DashboardBlock.ATTENTION to report(
            DashboardBlock.ATTENTION,
            metrics = listOf(
                count(DashboardMetricKeys.REPORTS_PENDING, 3),
                count(DashboardMetricKeys.QUESTION_FEEDBACK_PENDING, 7),
                count(DashboardMetricKeys.DRAFT_EXAMS, 2),
                count(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D, 1),
            ),
        ),
        DashboardBlock.PULSE to report(
            DashboardBlock.PULSE,
            metrics = listOf(
                count(DashboardMetricKeys.ACTIVE_USERS_30D, 1284),
                count(DashboardMetricKeys.ATTEMPTS_LAST_30D, 4892),
                count(DashboardMetricKeys.PUBLISHED_EXAMS, 152),
                percentage(DashboardMetricKeys.GLOBAL_ERROR_RATE, 0.8),
            ),
        ),
        DashboardBlock.CONTENT to report(
            DashboardBlock.CONTENT,
            metrics = listOf(
                count(DashboardMetricKeys.TOTAL_EXAMS, 152),
                count(DashboardMetricKeys.TOTAL_QUESTIONS, 8437),
                count(DashboardMetricKeys.TOTAL_RESOURCES, 1206),
                count(DashboardMetricKeys.IMPORT_BATCHES, 37),
            ),
        ),
        DashboardBlock.ACTIVITY to report(
            DashboardBlock.ACTIVITY,
            // Vocabulario REAL de `auditoria_log`: las cinco acciones existen en
            // STATIC_ACTIVITY y sus tablas en ACTIVITY_ICONS. Una acción
            // inventada saldría con su cadena cruda y en gris, que es
            // exactamente lo que no queremos comprobar aquí.
            activity = listOf(
                event(4, "examen_publicado", "examenes"),
                event(22, "pregunta_creada", "preguntas"),
                event(96, "usuario_actualizado", "perfiles_usuario"),
                event(310, "examen_archivado", "examenes"),
                event(1_500, "producto_comercial_guardado", "productos_comerciales"),
            ),
        ),
        DashboardBlock.ECONOMY to report(
            DashboardBlock.ECONOMY,
            metrics = listOf(
                count(DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE, 428),
                count(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D, 36),
                count(DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE, 12),
                // Las dos que el contrato declara sin instrumentar. Se meten a
                // propósito: `renderable` tiene que seguir dejándolas fuera y la
                // captura tiene que enseñar tres filas, no cinco.
                notInstrumented(DashboardMetricKeys.GROSS_REVENUE_CENTS),
                notInstrumented(DashboardMetricKeys.TICKETS_IN_CIRCULATION),
            ),
        ),
    ),
)

/**
 * La instalación de hoy: casi todo a cero y una métrica sin dato.
 *
 * Es el caso que de verdad hay que aprobar. Comprueba tres cosas que el estado
 * poblado no puede comprobar: que una tarjeta de atención en cero conserva su
 * sitio y se apaga, que «Sin dato» no grita más que una cifra real, y que las
 * cifras de una sola cifra no dejan la tarjeta con aspecto de vacía.
 */
private fun enCero(): DashboardV2State = DashboardV2State(
    blocks = mapOf(
        DashboardBlock.SYSTEM to report(DashboardBlock.SYSTEM, health = HEALTHY),
        DashboardBlock.ATTENTION to report(
            DashboardBlock.ATTENTION,
            metrics = listOf(
                count(DashboardMetricKeys.REPORTS_PENDING, 0),
                count(DashboardMetricKeys.QUESTION_FEEDBACK_PENDING, 0),
                count(DashboardMetricKeys.DRAFT_EXAMS, 6),
                count(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D, 0),
            ),
        ),
        DashboardBlock.PULSE to report(
            DashboardBlock.PULSE,
            metrics = listOf(
                count(DashboardMetricKeys.ACTIVE_USERS_30D, 1),
                count(DashboardMetricKeys.ATTEMPTS_LAST_30D, 3),
                count(DashboardMetricKeys.PUBLISHED_EXAMS, 6),
                noData(DashboardMetricKeys.GLOBAL_ERROR_RATE),
            ),
        ),
        DashboardBlock.CONTENT to report(
            DashboardBlock.CONTENT,
            metrics = listOf(
                count(DashboardMetricKeys.TOTAL_EXAMS, 6),
                count(DashboardMetricKeys.TOTAL_QUESTIONS, 3),
                count(DashboardMetricKeys.TOTAL_RESOURCES, 0),
                count(DashboardMetricKeys.IMPORT_BATCHES, 1),
            ),
        ),
        DashboardBlock.ACTIVITY to report(
            DashboardBlock.ACTIVITY,
            activity = listOf(
                event(35, "pregunta_creada", "preguntas"),
                event(180, "examen_creado", "examenes"),
            ),
        ),
        DashboardBlock.ECONOMY to report(
            DashboardBlock.ECONOMY,
            metrics = listOf(
                count(DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE, 0),
                count(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D, 0),
                count(DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE, 1),
            ),
        ),
    ),
)
