package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.DashboardMetricKeys
import com.oposicontrol.admin.domain.model.format
import kotlin.time.Instant

/**
 * Reglas de presentación del panel, **sin Compose**.
 *
 * Están aquí y no dentro de los composables por una razón concreta: son las
 * reglas que deciden si una cifra se enseña o no, y ésas son justo las que hay
 * que poder probar. Un `@Composable` sólo se prueba con un arnés de UI; una
 * función pura se prueba con `assertEquals`.
 */

// ---------------------------------------------------------------------------
// Destinos
// ---------------------------------------------------------------------------

/**
 * A dónde puede llevar el panel.
 *
 * Es un enum del módulo de dashboard, no `AppRoute`, porque `AppRoute` vive en
 * `composeApp` y este módulo no puede —ni debe— depender de él. `AppRoot`
 * traduce cada valor a su ruta canónica, y ahí está la única lista de rutas.
 *
 * Nada de `AdminSection`: ese enum es el mapa de navegación anterior y el panel
 * nuevo no lo usa en ningún punto.
 */
enum class DashboardV2Destination {
    /** Estado del sistema en escritorio → `AppRoute.System.Diagnostics`. */
    SYSTEM_DIAGNOSTICS,

    /** Cola de moderación → `AppRoute.Moderation.Queue`. */
    MODERATION_QUEUE,

    /** OposiTester → `AppRoute.OposiTester.List`. */
    OPOSITESTER_LIST,

    /** Exámenes: crear uno nuevo y revisar borradores son la misma pantalla. */
    EXAMS,

    /** Banco de preguntas → `AppRoute.Content.Questions`. */
    QUESTIONS,

    /** Importación de preguntas → `AppRoute.Content.Imports`. */
    IMPORTS,

    /** Usuarios → `AppRoute.Users.List`. */
    USERS,

    /** Analítica de producto → `AppRoute.Analytics.Home`. */
    ANALYTICS,

    /** Suscripciones → `AppRoute.Commercial.Subscriptions`. */
    SUBSCRIPTIONS,

    /** Auditoría completa → `AppRoute.System.Audit`. */
    AUDIT,
}

// ---------------------------------------------------------------------------
// Regla de renderizado de métricas
// ---------------------------------------------------------------------------

/**
 * Qué se pinta en el hueco de la cifra.
 *
 * Los tres casos existen porque las tres situaciones son distintas para quien
 * mira: hay número, no se pudo leer, o nadie lo mide todavía. El error que este
 * rediseño cierra era colapsar los tres en «0».
 */
sealed interface DashboardMetricCell {
    /** Hay dato. Un cero también es dato y llega aquí. */
    data class Number(val text: String) : DashboardMetricCell

    /** No se pudo leer. Ocupa sitio y dice «Sin dato»: reintentar puede arreglarlo. */
    data object NoData : DashboardMetricCell

    /** No existe agregado que lo calcule. No se pinta. */
    data object Hidden : DashboardMetricCell
}

/**
 * Decide cómo se pinta una métrica.
 *
 * `AVAILABLE` sin valor cae a [DashboardMetricCell.NoData] en vez de reventar o
 * inventar un cero: la política del dominio garantiza que no ocurra, y si algún
 * día ocurriera, fallar cerrado es lo correcto.
 */
fun dashboardMetricCell(metric: AnalyticsMetric): DashboardMetricCell = when (metric.status) {
    AnalyticsMetricStatus.NOT_INSTRUMENTED -> DashboardMetricCell.Hidden
    AnalyticsMetricStatus.NO_DATA -> DashboardMetricCell.NoData
    AnalyticsMetricStatus.AVAILABLE -> metric.value
        ?.let { DashboardMetricCell.Number(it.format()) }
        ?: DashboardMetricCell.NoData
}

/** Texto de la cifra, o `null` si la métrica no debe ocupar sitio. */
fun dashboardMetricText(metric: AnalyticsMetric): String? = when (val cell = dashboardMetricCell(metric)) {
    is DashboardMetricCell.Number -> cell.text
    DashboardMetricCell.NoData -> SIN_DATO
    DashboardMetricCell.Hidden -> null
}

const val SIN_DATO: String = "Sin dato"

// ---------------------------------------------------------------------------
// Textos de métrica
// ---------------------------------------------------------------------------

data class DashboardMetricCopy(
    val title: String,
    val supporting: String,
)

/**
 * Nombre legible de cada clave del contrato.
 *
 * El `else` devuelve la clave cruda a propósito. Es fea, y esa es su virtud: si
 * el backend añade una métrica y aquí no se traduce, se ve el hueco en vez de
 * desaparecer la tarjeta o inventarle un nombre.
 */
fun dashboardMetricCopy(key: String): DashboardMetricCopy = when (key) {
    DashboardMetricKeys.REPORTS_PENDING ->
        DashboardMetricCopy("Reportes pendientes", "Contenido reportado sin resolver")
    // «OposiTester» y no «Dudas»: la métrica es un conteo de `feedback_pregunta`
    // con `estado=eq.pendiente` (`AdminDashboardOverview.kt:499`), que es
    // exactamente la bandeja de OposiTester. «Dudas sin responder» sugería un
    // canal de preguntas de usuario que no existe como tal.
    DashboardMetricKeys.QUESTION_FEEDBACK_PENDING ->
        DashboardMetricCopy("OposiTester sin resolver", "Feedback pendiente de revisión")
    DashboardMetricKeys.DRAFT_EXAMS ->
        DashboardMetricCopy("Exámenes en borrador", "Escritos y todavía sin publicar")
    DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D ->
        DashboardMetricCopy("Suscripciones que vencen", "En los próximos 7 días")

    DashboardMetricKeys.ACTIVE_USERS_30D ->
        DashboardMetricCopy("Usuarios activos", "Últimos 30 días")
    /*
     * «Registrados» y NO «Completados».
     *
     * Verificado en el backend antes de escribirlo: la consulta es
     * `countOrNull("intentos_examen", "id", "iniciado_en=gte.\$since30d")`
     * (`AdminDashboardOverview.kt:552`). Filtra por la fecha de INICIO y **no**
     * filtra por estado, así que cuenta también los intentos abandonados a
     * medias. Rotularlos «completados» convertiría un dato correcto en una
     * afirmación falsa sin cambiar ni una cifra — la peor clase de error de copy,
     * porque no se nota nunca.
     */
    DashboardMetricKeys.ATTEMPTS_LAST_30D ->
        DashboardMetricCopy("Intentos (30d)", "Registrados en los últimos 30 días")
    DashboardMetricKeys.PUBLISHED_EXAMS ->
        DashboardMetricCopy("Exámenes publicados", "Visibles para los opositores")
    // «Incorrectas» y no «falladas». La métrica es la proporción de respuestas con
    // `es_correcta=false` sobre el total: describe la RESPUESTA, no un fallo del
    // sistema. «Fallidas» habría sido lo segundo, y en un panel que justo encima
    // dice si Ktor está en pie, esa lectura no es rebuscada — es la primera.
    DashboardMetricKeys.GLOBAL_ERROR_RATE ->
        DashboardMetricCopy("Tasa de error global", "Respuestas incorrectas")

    DashboardMetricKeys.TOTAL_EXAMS ->
        DashboardMetricCopy("Exámenes", "En el catálogo")
    DashboardMetricKeys.TOTAL_QUESTIONS ->
        DashboardMetricCopy("Preguntas", "En el catálogo")
    DashboardMetricKeys.TOTAL_RESOURCES ->
        DashboardMetricCopy("Recursos", "Adjuntos a preguntas")
    DashboardMetricKeys.IMPORT_BATCHES ->
        DashboardMetricCopy("Lotes de importación", "Registrados")

    DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE ->
        DashboardMetricCopy("Suscripciones activas", "Ahora mismo")
    DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D ->
        DashboardMetricCopy("Vencen en 30 días", "Horizonte de contexto")
    DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE ->
        DashboardMetricCopy("Productos comerciales", "Activos en catálogo")

    // Las dos que hoy llegan siempre NOT_INSTRUMENTED y que `renderable` filtra.
    // Llevan copy igualmente porque son las dos con más probabilidad de pasar a
    // AVAILABLE —en cuanto exista `v_ingresos_brutos` o
    // `v_opositickets_en_circulacion`— y ese día aparecerían en pantalla con su
    // clave cruda.
    //
    // `format()` las pintará bien en cuanto traigan número: `grossRevenueCents`
    // como MoneyCents (euros, nunca céntimos crudos) y `ticketsInCirculation`
    // como Count. Ojo: HOY el mapeador manda las dos por `mapping.money`
    // (`KtorDashboardOverviewRepositoryImpl.kt:306`), así que los tickets saldrían
    // como importe; A lo está corrigiendo a `count` en su lado. Cuando llegue,
    // `lasDosMetricasDeDineroSePintaranBienElDiaQueTraiganNumero` lo comprueba.
    //
    // El texto de apoyo va vacío a propósito: el agregado que las calculará no
    // existe, así que **no se sabe qué ventana temporal cubrirán**. Escribir
    // «Últimos 30 días» aquí sería inventar hoy la respuesta a una pregunta que
    // decidirá quien construya la vista.
    DashboardMetricKeys.GROSS_REVENUE_CENTS ->
        DashboardMetricCopy("Ingresos brutos", "")
    DashboardMetricKeys.TICKETS_IN_CIRCULATION ->
        DashboardMetricCopy("OposiTickets en circulación", "")

    else -> DashboardMetricCopy(key, "")
}

// ---------------------------------------------------------------------------
// Franja «Necesita tu atención»
// ---------------------------------------------------------------------------

/**
 * Presentación de una tarjeta de atención: icono, tono y **su propio enlace**.
 *
 * Cada una lleva destino porque un contador de trabajo pendiente que no se puede
 * abrir no sirve de nada: enseña el problema y deja al operador buscándolo por
 * el menú.
 */
data class DashboardAttentionCopy(
    val title: String,
    val supporting: String,
    val icon: AppIcon,
    val tone: StatusTone,
    /**
     * Familia de color de la tarjeta.
     *
     * Va aparte de [tone] porque son dos preguntas distintas. [tone] contesta
     * «¿esto va bien?» y por eso cambia cuando el contador llega a cero;
     * [role] contesta «¿de qué es esto?» y no cambia nunca — moderación es
     * moderación con tres reportes y con ninguno.
     */
    val role: SemanticRole,
    /**
     * Qué se dice cuando el contador vale CERO.
     *
     * Sin punto final, como todos los textos de apoyo del panel: son etiquetas, no
     * frases. Antes unas lo llevaban y otras no, y en la misma fila de cuatro
     * tarjetas se veía.
     *
     * Un cero es un dato, no una ausencia de dato, y además es el caso NORMAL en
     * una instalación al día: hoy mismo tres de las cuatro tarjetas llegan a cero.
     * Con «Contenido reportado sin resolver» encima de un 0, la tarjeta se lee
     * como una advertencia apagada; con «Todo al día.» se lee como lo que es.
     */
    val clearedSupporting: String,
    val actionLabel: String?,
    val destination: DashboardV2Destination?,
)

fun dashboardAttentionCopy(key: String): DashboardAttentionCopy = when (key) {
    DashboardMetricKeys.REPORTS_PENDING -> DashboardAttentionCopy(
        title = "Reportes pendientes",
        supporting = "Contenido reportado sin resolver",
        clearedSupporting = "Todo al día",
        icon = AppIcon.REPORTS,
        tone = StatusTone.NEGATIVE,
        role = SemanticRole.DANGER,
        actionLabel = "Revisar reportes",
        destination = DashboardV2Destination.MODERATION_QUEUE,
    )
    DashboardMetricKeys.QUESTION_FEEDBACK_PENDING -> DashboardAttentionCopy(
        title = "OposiTester sin resolver",
        supporting = "Feedback pendiente de revisión",
        clearedSupporting = "Sin feedback pendiente",
        icon = AppIcon.TESTER,
        tone = StatusTone.ATTENTION,
        role = SemanticRole.WARNING,
        actionLabel = "Ver OposiTester",
        destination = DashboardV2Destination.OPOSITESTER_LIST,
    )
    DashboardMetricKeys.DRAFT_EXAMS -> DashboardAttentionCopy(
        title = "Exámenes en borrador",
        supporting = "Escritos y todavía sin publicar",
        clearedSupporting = "Nada sin publicar",
        icon = AppIcon.EXAMS,
        tone = StatusTone.ATTENTION,
        // Azul y no ámbar: un borrador no es una incidencia, es trabajo en curso.
        // Que se pinte como aviso al lado de un reporte de moderación iguala dos
        // cosas que no piden lo mismo.
        role = SemanticRole.INFO,
        actionLabel = "Revisar borradores",
        destination = DashboardV2Destination.EXAMS,
    )
    DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D -> DashboardAttentionCopy(
        // Ventana de 7 días, no de 30: es la que cabe en una semana de trabajo y
        // por tanto la única accionable. Los 30 días son contexto y viven abajo,
        // en Economía.
        title = "Suscripciones que vencen",
        supporting = "En los próximos 7 días",
        clearedSupporting = "Ninguna vence esta semana",
        icon = AppIcon.PLANS,
        tone = StatusTone.ATTENTION,
        // COMMERCE y no BRAND. Medido sobre el render: el coral de marca al 12%
        // daba (237,208,196) y el rojo de «Reportes pendientes» (237,202,199) —
        // dos tarjetas contiguas con el mismo tinte y significados opuestos. El
        // teal las separa sin acercarse al verde de «todo bien», que sería la
        // otra lectura equivocada para algo que está a punto de caducar.
        role = SemanticRole.COMMERCE,
        actionLabel = "Ver suscripciones",
        destination = DashboardV2Destination.SUBSCRIPTIONS,
    )
    // Clave nueva del backend todavía sin traducir: se enseña el contador con su
    // clave cruda y sin enlace, en vez de esconderlo o mandarlo a una pantalla
    // que a lo mejor no lo resuelve.
    else -> DashboardAttentionCopy(
        title = dashboardMetricCopy(key).title,
        supporting = dashboardMetricCopy(key).supporting,
        clearedSupporting = "Sin nada pendiente",
        icon = AppIcon.INFO,
        tone = StatusTone.NEUTRAL,
        role = SemanticRole.NEUTRAL,
        actionLabel = null,
        destination = null,
    )
}

/**
 * Una tarjeta de atención en CERO se queda, y se apaga.
 *
 * Es la regla que decide cómo se ve el panel un día normal, porque el día normal
 * es éste: hoy en vivo hay tres ceros de cuatro. Las dos alternativas que se
 * descartaron:
 *
 *  - **Ocultar la tarjeta.** La rejilla cambiaría de forma según cuánto trabajo
 *    haya, y «no hay reportes» dejaría de poder distinguirse de «la franja no
 *    trae esa métrica».
 *  - **Dejarla igual.** Cuatro tarjetas rojas y ámbar gritando sobre cuatro
 *    ceros; el color deja de significar urgencia en cuanto se enciende sin ella.
 *
 * Se conserva la tarjeta, su sitio y su altura; se apagan el color, la severidad
 * y la llamada a la acción. `Sin dato` **no** entra aquí: no saber cuántos
 * reportes hay no es lo mismo que saber que no hay ninguno.
 */
fun dashboardAttentionIsCleared(cell: DashboardMetricCell): Boolean =
    cell is DashboardMetricCell.Number && cell.text.trim() == "0"

// ---------------------------------------------------------------------------
// Iconografía y familia de color de cada métrica
// ---------------------------------------------------------------------------

/**
 * Icono de una fila o tarjeta de métrica.
 *
 * Está aquí, en una función pura, y no repartido por los composables: es la misma
 * decisión para «Exámenes» esté donde esté —fila de catálogo o KPI— y tenerla dos
 * veces es tenerla mal una de las dos.
 *
 * El `else` cae en [AppIcon.INFO] y no en un icono bonito por descarte: una clave
 * nueva sin icono se ve genérica, que es la señal correcta.
 */
fun dashboardMetricIcon(key: String): AppIcon = when (key) {
    DashboardMetricKeys.REPORTS_PENDING -> AppIcon.REPORTS
    DashboardMetricKeys.QUESTION_FEEDBACK_PENDING -> AppIcon.TESTER
    DashboardMetricKeys.DRAFT_EXAMS -> AppIcon.EXAMS
    DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D -> AppIcon.PLANS

    DashboardMetricKeys.ACTIVE_USERS_30D -> AppIcon.USERS
    DashboardMetricKeys.ATTEMPTS_LAST_30D -> AppIcon.PERFORMANCE
    DashboardMetricKeys.PUBLISHED_EXAMS -> AppIcon.EXAMS
    DashboardMetricKeys.GLOBAL_ERROR_RATE -> AppIcon.WARNING

    DashboardMetricKeys.TOTAL_EXAMS -> AppIcon.EXAMS
    DashboardMetricKeys.TOTAL_QUESTIONS -> AppIcon.QUESTIONS
    DashboardMetricKeys.TOTAL_RESOURCES -> AppIcon.RESOURCES
    DashboardMetricKeys.IMPORT_BATCHES -> AppIcon.IMPORT

    DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE -> AppIcon.PLANS
    DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D -> AppIcon.CLOCK
    DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE -> AppIcon.CATALOG
    DashboardMetricKeys.GROSS_REVENUE_CENTS -> AppIcon.MARKET
    DashboardMetricKeys.TICKETS_IN_CIRCULATION -> AppIcon.TICKETS

    else -> AppIcon.INFO
}

/**
 * Familia de color de una métrica.
 *
 * Sólo cuatro familias en juego y ninguna es un estado: personas y catálogo en
 * azul, rendimiento y análisis en violeta, comercial en el coral de marca,
 * publicado en verde. La tasa de error es la única que se pinta en rojo, y no
 * porque hoy valga mucho —hoy no vale nada— sino porque es la única métrica del
 * panel cuyo *significado* es un fallo.
 */
fun dashboardMetricRole(key: String): SemanticRole = when (key) {
    DashboardMetricKeys.ACTIVE_USERS_30D -> SemanticRole.INFO
    DashboardMetricKeys.ATTEMPTS_LAST_30D -> SemanticRole.INSIGHT
    DashboardMetricKeys.PUBLISHED_EXAMS -> SemanticRole.SUCCESS
    DashboardMetricKeys.GLOBAL_ERROR_RATE -> SemanticRole.DANGER

    DashboardMetricKeys.TOTAL_EXAMS,
    DashboardMetricKeys.TOTAL_QUESTIONS,
    DashboardMetricKeys.TOTAL_RESOURCES,
    DashboardMetricKeys.IMPORT_BATCHES,
    -> SemanticRole.INFO

    DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE,
    DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D,
    DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE,
    DashboardMetricKeys.GROSS_REVENUE_CENTS,
    DashboardMetricKeys.TICKETS_IN_CIRCULATION,
    -> SemanticRole.COMMERCE

    else -> dashboardAttentionCopy(key).role
}

/**
 * Destino de una fila de «Estado de contenido», o `null` si no hay ninguno
 * verificado.
 *
 * **Recursos se queda sin chevron a propósito.** Su ruta resuelve a la misma
 * pantalla que Preguntas, y dos enlaces contiguos que llevan al mismo sitio
 * enseñan una separación que no existe: quien pulse «Recursos» esperará una
 * lista de recursos y verá el banco de preguntas.
 *
 * Preguntas sí lo lleva: navegar a `QuestionsScreen` está verificado en
 * escritorio. Que *crear* una pregunta falle hoy en vivo —falta la RPC— es un
 * defecto de esa pantalla, no una razón para no poder abrirla.
 */
fun dashboardContentDestination(key: String): DashboardV2Destination? = when (key) {
    DashboardMetricKeys.TOTAL_EXAMS -> DashboardV2Destination.EXAMS
    DashboardMetricKeys.TOTAL_QUESTIONS -> DashboardV2Destination.QUESTIONS
    DashboardMetricKeys.IMPORT_BATCHES -> DashboardV2Destination.IMPORTS
    else -> null
}

// ---------------------------------------------------------------------------
// Actividad reciente
// ---------------------------------------------------------------------------

/**
 * «hace 12 min» a partir de un instante REAL.
 *
 * [occurredAt] es `auditoria_log.creado_en`. El panel anterior construía tres
 * eventos derivados del estado actual y le ponía `Clock.System.now()` a los
 * tres: decían haber ocurrido a la vez y se rejuvenecían solos en cada refresco.
 *
 * El futuro se colapsa en «ahora mismo» en vez de decir «dentro de 3 min»: un
 * hecho ya registrado no puede estar por ocurrir, así que un [occurredAt]
 * posterior a [now] sólo significa que el reloj del cliente y el del servidor no
 * coinciden — y esa diferencia no es información que darle a nadie.
 */
fun dashboardRelativeTime(occurredAt: Instant, now: Instant): String {
    val seconds = now.epochSeconds - occurredAt.epochSeconds
    return when {
        seconds < 0L -> "ahora mismo"
        seconds < 60L -> "hace un momento"
        seconds < 3_600L -> "hace ${seconds / 60L} min"
        seconds < 86_400L -> "hace ${seconds / 3_600L} h"
        else -> "hace ${seconds / 86_400L} d"
    }
}

/**
 * Lo que una acción de auditoría dice y con qué tono se lee.
 *
 * Van juntos y no en dos mapas paralelos porque etiqueta y tono se refieren al
 * mismo hecho: separarlos permitiría que una acción tuviera texto de «creado» y
 * color de «archivado» sin que nada lo impidiera.
 */
private data class ActivityCopy(val label: String, val tone: StatusTone)

/**
 * Vocabulario ESTÁTICO de `auditoria_log`: las 24 acciones que el backend
 * escribe con la cadena completa.
 *
 * Extraídas del repositorio, no de memoria ni por analogía:
 * `grep -rhoE 'registrarAuditoria\("[^"]+"' backend/`.
 *
 * La versión anterior de este fichero traducía cinco acciones que **ningún
 * camino del código produce** —`warning_usuario`, `mute_usuario`, `ban_usuario`,
 * `close_conversation_usuario`, `internal_note_usuario`—. Salían de
 * `VALID_MODERATION_ACTION_TYPES` (`AdminGateway.kt:568`), que gobierna las
 * escrituras a la tabla `moderation_action` y **no pasa por
 * `registrarAuditoria`**. El único escritor de `*_usuario` es `warnUser`
 * (`AdminV1Routes.kt:3945`), y su `actionType` lo fija el cliente de escritorio a
 * `"advertencia"` (`KtorModerationRepositoryImpl.kt:87`): la fila real es
 * `advertencia_usuario`, que no estaba traducida ni coloreada.
 *
 * Traducir una acción inexistente no se ve nunca en pantalla —esa fila no llega—
 * y por eso es peligroso: afirma un conocimiento del sistema que no se tiene, y
 * la afirmación sobrevive hasta que alguien la usa para decidir otra cosa.
 * [dashboardActivityVocabulary] existe para que un test pueda comprobar que aquí
 * no sobra ninguna, que es la comprobación que faltaba.
 */
private val STATIC_ACTIVITY: Map<String, ActivityCopy> = mapOf(
    // Contenido
    "examen_creado" to ActivityCopy("Examen creado", StatusTone.POSITIVE),
    "examen_publicado" to ActivityCopy("Examen publicado", StatusTone.POSITIVE),
    "examen_despublicado" to ActivityCopy("Examen despublicado", StatusTone.NEGATIVE),
    "examen_archivado" to ActivityCopy("Examen archivado", StatusTone.NEGATIVE),
    "pregunta_creada" to ActivityCopy("Pregunta creada", StatusTone.POSITIVE),
    "pregunta_archivada" to ActivityCopy("Pregunta archivada", StatusTone.NEGATIVE),
    "preguntas_examen_reordenadas" to ActivityCopy("Preguntas del examen reordenadas", StatusTone.NEUTRAL),
    "recurso_pregunta_creado" to ActivityCopy("Recurso de pregunta creado", StatusTone.POSITIVE),
    "recurso_pregunta_archivado" to ActivityCopy("Recurso de pregunta archivado", StatusTone.NEGATIVE),

    // Taxonomía
    "capacidades_especialidad_actualizadas" to
        ActivityCopy("Capacidades de especialidad actualizadas", StatusTone.NEUTRAL),
    "especialidad_archivada" to ActivityCopy("Especialidad archivada", StatusTone.NEGATIVE),
    "etiqueta_eliminada" to ActivityCopy("Etiqueta eliminada", StatusTone.NEGATIVE),
    "comunidad_eliminada" to ActivityCopy("Comunidad eliminada", StatusTone.NEGATIVE),

    // Usuarios
    "usuario_actualizado" to ActivityCopy("Usuario actualizado", StatusTone.NEUTRAL),
    "roles_sincronizados" to ActivityCopy("Roles de usuario sincronizados", StatusTone.NEUTRAL),
    "tickets_grant" to ActivityCopy("OposiTickets concedidos", StatusTone.POSITIVE),

    // Comercial
    "producto_comercial_guardado" to ActivityCopy("Producto comercial guardado", StatusTone.POSITIVE),
    "tarifa_producto_guardada" to ActivityCopy("Tarifa de producto guardada", StatusTone.POSITIVE),
    "plan_suscripcion_guardado" to ActivityCopy("Plan de suscripción guardado", StatusTone.POSITIVE),
    "beneficio_plan_guardado" to ActivityCopy("Beneficio de plan guardado", StatusTone.POSITIVE),
    "pack_opositickets_guardado" to ActivityCopy("Pack de OposiTickets guardado", StatusTone.POSITIVE),
    "servicio_market_guardado" to ActivityCopy("Servicio de OposiMarket guardado", StatusTone.POSITIVE),
    "promocion_guardada" to ActivityCopy("Promoción guardada", StatusTone.POSITIVE),
    "regla_popup_guardada" to ActivityCopy("Regla de pop-up guardada", StatusTone.POSITIVE),
)

/** Las 24 acciones estáticas conocidas. Sirve para probar que no sobra ninguna. */
val dashboardActivityVocabulary: Set<String> get() = STATIC_ACTIVITY.keys

private const val LOTE_PREFIX = "lote_"
private const val FEEDBACK_PREFIX = "feedback_"
private const val REPORTE_PREFIX = "reporte_"
private const val USUARIO_SUFFIX = "_usuario"

/**
 * Las cuatro familias cuyo sufijo el backend **interpola**, y por tanto no es una
 * cadena que se pueda leer del código de una vez:
 *
 *  - `lote_$estadoFinal`            (`AdminV1Routes.kt:2469`)
 *  - `feedback_$estado`             (`AdminV1Routes.kt:2552`)
 *  - `reporte_$estado`              (`AdminV1Routes.kt:3065`)
 *  - `${actionType}_usuario`        (`AdminV1Routes.kt:3947`)
 *
 * Se tratan por prefijo. Los sufijos que **sí** se pueden verificar leyendo el
 * literal Kotlin que los produce llevan su etiqueta exacta; cualquier otro cae a
 * la etiqueta de familia con el sufijo crudo pegado detrás (`Reporte · loQueSea`)
 * y a [StatusTone.NEUTRAL], nunca a un tono inventado.
 *
 * Esa caída no es teórica: `${actionType}_usuario` viene del cuerpo de una
 * petición HTTP, así que un cliente futuro puede mandar cualquier cosa; y las
 * otras tres podrían tener filas históricas escritas por versiones anteriores del
 * backend. Traducir sólo lo verificable y enseñar el resto en crudo es lo único
 * que no miente en ninguno de los dos casos.
 */
private fun interpolatedActivityCopy(action: String): ActivityCopy? = when {
    action.startsWith(LOTE_PREFIX) -> when (action) {
        // Los tres valores del `when` de AdminV1Routes.kt:2459-2463.
        "lote_importado" -> ActivityCopy("Lote de preguntas importado", StatusTone.POSITIVE)
        "lote_parcial" -> ActivityCopy("Lote de preguntas importado con incidencias", StatusTone.ATTENTION)
        "lote_revisar" -> ActivityCopy("Lote de preguntas pendiente de revisar", StatusTone.ATTENTION)
        else -> family("Importación de preguntas", action.removePrefix(LOTE_PREFIX), action)
    }

    action.startsWith(FEEDBACK_PREFIX) -> when (action) {
        // Los tres del `require(estado in setOf(...))` de AdminV1Routes.kt:2540.
        "feedback_resuelto" -> ActivityCopy("Duda de pregunta resuelta", StatusTone.POSITIVE)
        "feedback_descartado" -> ActivityCopy("Duda de pregunta descartada", StatusTone.NEGATIVE)
        "feedback_revisado" -> ActivityCopy("Duda de pregunta revisada", StatusTone.ATTENTION)
        else -> family("Duda de pregunta", action.removePrefix(FEEDBACK_PREFIX), action)
    }

    action.startsWith(REPORTE_PREFIX) -> when (action) {
        // Los dos del `if` de AdminV1Routes.kt:3044.
        "reporte_resuelto" -> ActivityCopy("Reporte resuelto", StatusTone.POSITIVE)
        "reporte_descartado" -> ActivityCopy("Reporte descartado", StatusTone.NEGATIVE)
        else -> family("Reporte", action.removePrefix(REPORTE_PREFIX), action)
    }

    action.endsWith(USUARIO_SUFFIX) -> when (action) {
        // Único valor que hoy produce un cliente real (KtorModerationRepositoryImpl.kt:87).
        "advertencia_usuario" -> ActivityCopy("Advertencia a usuario", StatusTone.ATTENTION)
        else -> family("Moderación de usuario", action.removeSuffix(USUARIO_SUFFIX), action)
    }

    else -> null
}

/**
 * Etiqueta de familia con el sufijo crudo detrás.
 *
 * Si el sufijo viene vacío se devuelve la acción tal cual: «Reporte · » no es más
 * informativo que `reporte_`, y sí más raro de leer.
 */
private fun family(label: String, suffix: String, raw: String): ActivityCopy =
    if (suffix.isBlank()) {
        ActivityCopy(raw, StatusTone.NEUTRAL)
    } else {
        ActivityCopy("$label · $suffix", StatusTone.NEUTRAL)
    }

private fun activityCopy(action: String): ActivityCopy? =
    STATIC_ACTIVITY[action] ?: interpolatedActivityCopy(action)

/**
 * Traducción de la acción cruda de auditoría.
 *
 * Una acción que no está en el vocabulario ni pertenece a una familia conocida se
 * devuelve **tal cual**. Es deliberado: se lee peor pero se lee, y quien la vea
 * sabrá que falta traducirla. Un texto genérico tipo «Actividad del sistema»
 * borraría esa señal, y una traducción inventada la sustituiría por una mentira.
 */
fun dashboardActivityLabel(action: String): String = activityCopy(action)?.label ?: action

/**
 * Tono del punto de la cronología.
 *
 * Es una lectura de la acción, no un dato: crear y publicar se leen en verde,
 * archivar y eliminar en rojo, lo que queda a medias en ámbar. Todo lo que no se
 * reconoce —acción desconocida o sufijo desconocido de una familia conocida— cae
 * en [StatusTone.NEUTRAL] y **nunca** en verde, la misma regla que `statusToneFor`
 * aplica a los chips de estado: un enum nuevo del backend no puede acabar
 * pintándose de «todo bien» por descarte.
 */
fun dashboardActivityTone(action: String): StatusTone =
    activityCopy(action)?.tone ?: StatusTone.NEUTRAL

/**
 * Qué se dice cuando la cronología llega vacía.
 *
 * «No ha pasado nada» y «no he podido leer lo que pasó» son frases distintas y
 * hasta ahora llegaban a pantalla iguales. Con [dropped] mayor que cero, decir
 * que no hay actividad sería falso: hubo filas, se leyeron, y se descartaron por
 * no traer fecha o identificador utilizables.
 *
 * Descartarlas es lo correcto —una entrada sin fecha no se puede situar en una
 * cronología y sellarla con la hora de lectura sería inventarla—, pero
 * descartarlas en silencio es la misma ambigüedad que un cero por fallo de
 * lectura, sólo que en la lista en vez de en la cifra.
 */
fun dashboardActivityEmptyMessage(dropped: Int): String = when {
    dropped <= 0 -> "No hay acciones registradas todavía."
    dropped == 1 -> "Se leyó una acción y no traía fecha legible, así que no se puede situar."
    else -> "Se leyeron $dropped acciones y ninguna traía fecha legible, así que no se pueden situar."
}

/**
 * Nota al pie cuando SÍ hay filas visibles pero alguna se quedó fuera.
 *
 * `null` cuando no se descartó nada: una nota que dice «0 descartadas» sólo
 * añade ruido a la pantalla que más lo nota.
 */
fun dashboardActivityDroppedNote(dropped: Int): String? = when {
    dropped <= 0 -> null
    dropped == 1 -> "1 acción más sin fecha legible, no se muestra."
    else -> "$dropped acciones más sin fecha legible, no se muestran."
}

/**
 * Icono de una fila de cronología, deducido de la tabla afectada.
 *
 * `targetTable` es un campo real de la fila de auditoría, así que el icono no se
 * está inventando: nombra lo que la acción tocó. Las tablas de este `when` se
 * reverificaron una a una contra las llamadas reales a `registrarAuditoria`; una
 * tabla sin mapear —o una acción que no apunta a ninguna— cae en el icono de
 * auditoría.
 */
private val ACTIVITY_ICONS: Map<String, AppIcon> = mapOf(
    "examenes" to AppIcon.EXAMS,
    "preguntas" to AppIcon.QUESTIONS,
    "preguntas_examen" to AppIcon.QUESTIONS,
    "feedback_pregunta" to AppIcon.TESTER,
    "recursos_pregunta" to AppIcon.RESOURCES,
    "lotes_importacion_preguntas" to AppIcon.IMPORT,
    "perfiles_usuario" to AppIcon.USERS,
    "roles_usuario" to AppIcon.USERS,
    "reportes_contenido" to AppIcon.REPORTS,
    "carteras_usuario" to AppIcon.TICKETS,
    "especialidades" to AppIcon.LAYERS,
    "capacidades_especialidad" to AppIcon.LAYERS,
    "etiquetas" to AppIcon.LAYERS,
    "comunidades_autonomas" to AppIcon.LAYERS,
    "productos_comerciales" to AppIcon.PLANS,
    "tarifas_producto_comercial" to AppIcon.PLANS,
    "planes_suscripcion" to AppIcon.PLANS,
    "beneficios_plan" to AppIcon.PLANS,
    "paquetes_opositickets" to AppIcon.MARKET,
    "servicios" to AppIcon.MARKET,
    "promociones" to AppIcon.MARKET,
    "reglas_popup_comercial" to AppIcon.MARKET,
)

/**
 * Las 22 tablas que el backend nombra en `tabla_afectada`.
 *
 * Se expone por la misma razón que [dashboardActivityVocabulary], y aprendida del
 * mismo modo: un `when` con ramas de cadena sólo se puede probar preguntando
 * «¿mapea X?», y esa pregunta no detecta jamás una rama de más. Aquí el contenido
 * hoy es correcto —las 22 se corresponden una a una con las llamadas reales a
 * `registrarAuditoria`— pero la forma era la misma que dejó pasar cinco acciones
 * fantasma, así que se cierra igual: conjunto expuesto y test de conjunto.
 */
val dashboardActivityIconTables: Set<String> get() = ACTIVITY_ICONS.keys

fun dashboardActivityIcon(targetTable: String?): AppIcon =
    ACTIVITY_ICONS[targetTable] ?: AppIcon.AUDIT
