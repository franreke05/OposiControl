package com.oposicontrol.backend

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Analítica de producto (sólo lectura) sobre el esquema real de Supabase.
 *
 * Tres reglas gobiernan este fichero entero, y las tres existen porque su
 * incumplimiento ya rompió el dashboard una vez:
 *
 * 1. **Ninguna métrica se inventa.** Un número que no se ha podido calcular no
 *    llega como `0`: llega como `null` en su campo y con su clave nombrada en
 *    `unavailableMetrics`. El `catch { 0 }` que hacía indistinguible "no pude
 *    leer" de "hay cero" es exactamente el fallo que se corrigió en
 *    `getDashboardMetrics`, y no se reintroduce aquí.
 * 2. **Contar se cuenta en el servidor.** Nunca se traen filas para hacer
 *    `.size`: PostgREST recorta en silencio al llegar a su `max-rows`, así que
 *    ese número es falso en cuanto la tabla crece. Se usa `Prefer: count=exact`
 *    con `Range: 0-0`, que devuelve el total exacto sin transferir filas.
 * 3. **Lo que PostgREST no sabe hacer, no se finge.** DISTINCT, AVG, SUM y
 *    GROUP BY no se pueden expresar en PostgREST. Simularlos descargando filas
 *    tiene el mismo techo silencioso del punto 2, así que esas métricas se
 *    declaran NO INSTRUMENTADAS, con el read model/RPC concreto que haría falta
 *    escrito en la propia respuesta ([AnalyticsGapDto.reason]).
 *
 * ### Dos formas distintas de "no hay número"
 *
 * El precedente ([AdminDashboardDto.unavailableMetrics]) tiene una sola lista.
 * Aquí hacen falta dos, porque el operador necesita saber si debe reintentar:
 *
 * - **NO_DATA**: la consulta existe y falló (red, permisos, upstream caído).
 *   Reintentar puede arreglarlo.
 * - **NOT_INSTRUMENTED**: la consulta no existe y no puede existir con
 *   PostgREST. Reintentar no arregla nada; hace falta escribir una vista o un
 *   RPC.
 *
 * `unavailableMetrics` es la **unión** de ambas (contrato compatible con el
 * dashboard: "no pintes número para estas claves") y `notInstrumented` es el
 * subconjunto que además explica qué falta construir.
 *
 * ### Sobre `exactCount`
 *
 * `AdminV1Gateway` ya tiene un `exactCount` privado idéntico en intención. No se
 * reutiliza porque es privado de otra clase y ese fichero no se toca en este
 * trabajo; aquí se escribe el equivalente ([AdminAnalyticsGateway.exactCount]).
 * Lo que sí se reutiliza es [parseContentRangeTotal] (DashboardMetricsSupport.kt,
 * mismo paquete, `internal`): duplicar un parser ya probado sólo crearía dos
 * sitios donde equivocarse.
 */

private val AnalyticsLogger = LoggerFactory.getLogger("OC-KTOR-ANALYTICS")

// ---------------------------------------------------------------------
// Claves estables de métrica
// ---------------------------------------------------------------------

/**
 * Identificador estable de cada métrica de analítica.
 *
 * Igual que [DashboardMetricKey], cada constante coincide **exactamente** con el
 * nombre del campo en su DTO: la clave viaja al cliente para marcar una tarjeta
 * como NO_DATA/NOT_INSTRUMENTED, y si clave y campo se separan la UI marcaría la
 * tarjeta equivocada. Los tests lo comprueban campo a campo.
 */
object AnalyticsMetricKey {
    // -- Producto --
    const val TOTAL_USERS = "totalUsers"
    const val GUEST_USERS = "guestUsers"
    const val ONBOARDED_USERS = "onboardedUsers"
    const val ONBOARDING_COMPLETION_RATE = "onboardingCompletionRate"
    const val TOTAL_APP_EVENTS = "totalAppEvents"
    const val APP_EVENTS_LAST_30D = "appEventsLast30d"
    const val TOTAL_STUDY_SESSIONS = "totalStudySessions"
    const val STUDY_SESSIONS_LAST_30D = "studySessionsLast30d"
    const val FINISHED_STUDY_SESSIONS = "finishedStudySessions"
    const val ABANDONED_STUDY_SESSIONS = "abandonedStudySessions"
    const val STUDY_SESSION_COMPLETION_RATE = "studySessionCompletionRate"
    const val EVENTS_BY_NAME = "eventsByName"
    const val SESSIONS_BY_PLATFORM = "sessionsByPlatform"
    const val SESSIONS_BY_APP_VERSION = "sessionsByAppVersion"
    const val AVG_STUDY_SESSION_SECONDS = "avgStudySessionSeconds"

    // -- Estudio --
    const val TOTAL_ATTEMPTS = "totalAttempts"
    const val ATTEMPTS_COMPLETED = "attemptsCompleted"
    const val ATTEMPTS_IN_PROGRESS = "attemptsInProgress"
    const val ATTEMPTS_OTHER_STATE = "attemptsOtherState"
    const val ATTEMPTS_LAST_30D = "attemptsLast30d"
    const val ATTEMPTS_FIRST_TIME = "attemptsFirstTime"
    const val ATTEMPTS_RANKED = "attemptsRanked"
    const val ATTEMPTS_RAFAGA = "attemptsRafaga"
    const val ATTEMPTS_RAZONADO = "attemptsRazonado"
    const val ATTEMPTS_INMERSIVO = "attemptsInmersivo"
    const val ATTEMPTS_OTHER_MODE = "attemptsOtherMode"
    const val TOTAL_ANSWERS = "totalAnswers"
    const val CORRECT_ANSWERS = "correctAnswers"
    const val WRONG_ANSWERS = "wrongAnswers"
    const val UNANSWERED_ANSWERS = "unansweredAnswers"
    const val GLOBAL_ACCURACY_RATE = "globalAccuracyRate"
    const val GLOBAL_ERROR_RATE = "globalErrorRate"
    const val FIRST_TIME_ANSWERS = "firstTimeAnswers"
    const val RANDOM_ANSWERS = "randomAnswers"
    const val CHANGED_ANSWERS = "changedAnswers"
    const val MARKED_FOR_REVIEW_ANSWERS = "markedForReviewAnswers"
    const val HARDEST_QUESTIONS = "hardestQuestions"
    const val AVG_SCORE = "avgScore"
    const val AVG_ATTEMPT_DURATION_SECONDS = "avgAttemptDurationSeconds"
    const val AVG_ANSWER_TIME_SECONDS = "avgAnswerTimeSeconds"
    const val ATTEMPTS_PER_DAY = "attemptsPerDay"
    const val TAG_WEAKNESS_RANKING = "tagWeaknessRanking"

    // -- Retención --
    const val USERS_WITH_STREAK = "usersWithStreak"
    const val USERS_WITH_ACTIVE_STREAK = "usersWithActiveStreak"
    const val USERS_STREAK_7_PLUS = "usersStreak7Plus"
    const val USERS_STREAK_30_PLUS = "usersStreak30Plus"
    const val ACTIVE_USERS_1D = "activeUsers1d"
    const val ACTIVE_USERS_7D = "activeUsers7d"
    const val ACTIVE_USERS_30D = "activeUsers30d"
    const val USERS_WITH_COMPETITIVE_STATE = "usersWithCompetitiveState"
    const val LEAGUE_DISTRIBUTION = "leagueDistribution"
    const val AVG_CURRENT_STREAK_DAYS = "avgCurrentStreakDays"
    const val AVG_BEST_STREAK_DAYS = "avgBestStreakDays"
    const val AVG_COMPETITIVE_SCORE = "avgCompetitiveScore"
    const val RETENTION_COHORTS = "retentionCohorts"

    // -- Comunidad --
    const val POSTS = "posts"
    const val POSTS_PUBLISHED = "postsPublished"
    const val POSTS_PINNED = "postsPinned"
    const val COMMENTS = "comments"
    const val REPORTS = "reports"
    const val REPORTS_PENDING = "reportsPending"
    const val REPORTS_REVIEWED = "reportsReviewed"
    const val REPORTS_REWARDED = "reportsRewarded"
    const val QUESTION_FEEDBACK = "questionFeedback"
    const val QUESTION_FEEDBACK_PENDING = "questionFeedbackPending"
    const val POSTS_BY_CATEGORY = "postsByCategory"
    const val TOP_CONTRIBUTORS = "topContributors"
    const val AVG_COMMENTS_PER_POST = "avgCommentsPerPost"
    const val MEDIAN_REPORT_RESOLUTION_HOURS = "medianReportResolutionHours"

    // -- Economía --
    const val WALLETS = "wallets"
    const val WALLET_TRANSACTIONS = "walletTransactions"
    const val WALLET_CREDITS = "walletCredits"
    const val WALLET_DEBITS = "walletDebits"
    const val WALLET_NEUTRAL_MOVEMENTS = "walletNeutralMovements"
    const val PURCHASES = "purchases"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SUBSCRIPTIONS_ACTIVE = "subscriptionsActive"
    const val SUBSCRIPTIONS_TRIAL = "subscriptionsTrial"
    const val SUBSCRIPTIONS_PAUSED = "subscriptionsPaused"
    const val SUBSCRIPTIONS_CANCELLED = "subscriptionsCancelled"
    const val SUBSCRIPTIONS_EXPIRED = "subscriptionsExpired"
    const val SUBSCRIPTIONS_OTHER_STATE = "subscriptionsOtherState"
    const val SUBSCRIPTIONS_EXPIRING_30D = "subscriptionsExpiring30d"
    const val COMMERCIAL_PRODUCTS = "commercialProducts"
    const val COMMERCIAL_PRODUCTS_ACTIVE = "commercialProductsActive"
    const val GROSS_REVENUE_CENTS = "grossRevenueCents"
    const val AVG_ORDER_VALUE_CENTS = "avgOrderValueCents"
    const val TICKETS_IN_CIRCULATION = "ticketsInCirculation"
    const val REVENUE_BY_PRODUCT = "revenueByProduct"
    const val PURCHASES_BY_STATE = "purchasesByState"

    // -- Marketing --
    const val ATTRIBUTED_USERS = "attributedUsers"
    const val REFERRED_USERS = "referredUsers"
    const val USERS_WITH_REFERRAL_CODE = "usersWithReferralCode"
    const val UNATTRIBUTED_USERS = "unattributedUsers"
    const val ATTRIBUTION_COVERAGE_RATE = "attributionCoverageRate"
    const val USERS_BY_SOURCE = "usersBySource"
    const val USERS_BY_CHANNEL = "usersByChannel"
    const val USERS_BY_CAMPAIGN = "usersByCampaign"
    const val TOP_REFERRERS = "topReferrers"
}

// ---------------------------------------------------------------------
// Piezas puras: disponibilidad y derivaciones
// ---------------------------------------------------------------------

/**
 * Métrica que no se puede calcular con PostgREST, con lo que haría falta para
 * poder calcularla.
 *
 * La razón viaja en la respuesta y no en un documento aparte a propósito: un
 * hueco documentado sólo en `docs/` se convierte en un hueco olvidado, y quien
 * mira el panel es justo quien necesita saber por qué falta el número.
 */
@Serializable
data class AnalyticsGapDto(
    val key: String,
    val reason: String,
)

/**
 * Acumulador de disponibilidad de métricas.
 *
 * Existe para que registrar "esto no se pudo leer" sea **el mismo gesto** que
 * leer el valor, en vez de dos pasos que se pueden desincronizar. `reading()`
 * devuelve el valor tal cual (incluido un `0` legítimo) y sólo apunta la clave
 * cuando el valor es `null`, que es la única señal de fallo que produce
 * [AdminAnalyticsGateway.countOrNull].
 *
 * No es un detalle de estilo: la versión "a mano" de esto es la que dejó pasar
 * `catch { 0 }` en el dashboard.
 *
 * ### Cuatro estados, no tres (PC-04)
 *
 * Una métrica sin número puede estar así por dos motivos que el operador tiene que
 * poder distinguir: **la lectura FALLÓ** (reintentar puede servir) o **se leyó bien
 * y no hay nada que medir** (una tasa sin respuestas todavía, unos ingresos que la
 * propia base declara incalculables). Las dos van a [unavailable] —un cliente que
 * sólo mire esa lista pinta "sin dato", nunca un cero—, y la primera además a
 * [failed], que es lo que permite decir "no se pudo leer".
 */
internal class AnalyticsAvailability {
    private val absent = linkedSetOf<String>()
    private val failedReads = linkedSetOf<String>()
    private val structuralGaps = linkedMapOf<String, String>()

    /**
     * Registra la lectura de [key]. Devuelve [value] sin tocarlo; si es `null`
     * marca la métrica como no disponible Y como lectura fallida.
     *
     * `null` aquí significa "no se pudo leer", que es lo único que produce
     * `countOrNull`. Un valor derivado cuyo `null` puede significar otra cosa
     * —una tasa con denominador cero— se registra con [rate] o [noData], no aquí.
     */
    fun <T : Any> reading(key: String, value: T?): T? {
        if (value == null) {
            absent += key
            failedReads += key
        }
        return value
    }

    /**
     * [key] se leyó bien pero no produce número (NO_DATA, no FAILED): no hay nada
     * que medir todavía, o la fuente declara el valor incalculable a propósito.
     */
    fun noData(key: String) {
        absent += key
    }

    /**
     * Tasa honesta entre dos lecturas: la misma cuenta que [analyticsRate], pero
     * diciendo POR QUÉ no hay número. Si falta cualquiera de las dos lecturas, la
     * métrica FALLÓ; si las dos llegaron y el denominador es cero, NO HAY DATOS
     * (`0 %` de algo que no ha pasado sería inventar).
     */
    fun rate(key: String, numerator: Int?, denominator: Int?): Double? {
        if (numerator == null || denominator == null) {
            absent += key
            failedReads += key
            return null
        }
        val value = analyticsRate(numerator, denominator)
        if (value == null) absent += key
        return value
    }

    /**
     * Declara [key] como no instrumentada: no existe consulta PostgREST capaz de
     * calcularla. [reason] describe el read model o RPC concreto que haría falta.
     */
    fun gap(key: String, reason: String) {
        structuralGaps[key] = reason
    }

    /** Unión NO_DATA + FAILED + NOT_INSTRUMENTED: "no pintes número para estas claves". */
    fun unavailable(): List<String> = (absent + structuralGaps.keys).toList()

    /** Sólo las que no se pudieron LEER. Siempre subconjunto de [unavailable]. */
    fun failed(): List<String> = failedReads.toList()

    /** Sólo los huecos estructurales, con su razón. */
    fun gaps(): List<AnalyticsGapDto> = structuralGaps.map { (key, reason) -> AnalyticsGapDto(key, reason) }
}

/**
 * Porcentaje honesto entre dos conteos.
 *
 * Devuelve `null` —y no `0.0`— cuando falta cualquiera de los dos operandos o
 * cuando el denominador es cero. Un `0 %` inventado sobre un denominador vacío
 * se lee en pantalla como "nadie completa el onboarding", que es una afirmación
 * distinta de "todavía no hay a quién medir".
 */
internal fun analyticsRate(numerator: Int?, denominator: Int?): Double? {
    if (numerator == null || denominator == null || denominator <= 0) return null
    return numerator * 100.0 / denominator
}

/**
 * Resto de una partición: total menos las partes conocidas.
 *
 * Sirve para no descartar filas en silencio cuando sólo se cuentan los valores
 * canónicos de una columna de texto (por ejemplo `tipo_examen_snapshot`, donde
 * la base viva todavía admite alias legacy). Lo que no cae en ningún cubo
 * conocido aparece como "otros" en vez de desaparecer.
 *
 * Devuelve `null` si el total o cualquier parte falta: una resta con un hueco no
 * da un número, da una mentira. Se acota a 0 porque las partes se leen en
 * consultas separadas y una escritura concurrente entre ellas puede hacer que la
 * suma supere momentáneamente al total.
 */
internal fun analyticsRemainder(total: Int?, parts: List<Int?>): Int? {
    if (total == null || parts.any { it == null }) return null
    return (total - parts.sumOf { it!! }).coerceAtLeast(0)
}

/**
 * Filtro PostgREST de "suscripción activa AHORA" (PC-03): estado `activa` y sin
 * fin, o con fin posterior a [now].
 *
 * Compartido por el panel de Inicio y `/analytics/economia` para que las dos
 * pantallas no puedan divergir. Dos detalles son contrato:
 *
 * - `gt` y no `gte`: una suscripción que termina justo ahora ya no está activa.
 * - El instante va entre comillas codificadas (`%22`) y truncado a segundos con
 *   `Instant.toString()`, que produce `…Z` y nunca `+00:00`. Un `+` sin codificar
 *   llega a PostgREST como espacio, la consulta responde 400 y la métrica se
 *   quedaría "sin dato" para siempre; las comillas protegen además los `:` del
 *   valor dentro de la lista `or=(…)`.
 */
internal fun activeSubscriptionsFilter(now: Instant): String =
    "estado=eq.activa&or=(fin_en.is.null,fin_en.gt.%22${now.truncatedTo(ChronoUnit.SECONDS)}%22)"

// ---------------------------------------------------------------------
// DTOs de respuesta
// ---------------------------------------------------------------------

/*
 * Ningún campo numérico lleva valor por defecto: kotlinx omite los campos que
 * coinciden con su default cuando `encodeDefaults = false`, y un campo ausente
 * sería otra vez indistinguible de un cero para el cliente. Sin default, todos
 * se serializan siempre, y los no disponibles viajan explícitamente como `null`.
 */

@Serializable
data class AdminAnalyticsProductoDto(
    val totalUsers: Int?,
    val guestUsers: Int?,
    val onboardedUsers: Int?,
    val onboardingCompletionRate: Double?,
    val totalAppEvents: Int?,
    val appEventsLast30d: Int?,
    val totalStudySessions: Int?,
    val studySessionsLast30d: Int?,
    val finishedStudySessions: Int?,
    val abandonedStudySessions: Int?,
    val studySessionCompletionRate: Double?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

@Serializable
data class AdminAnalyticsEstudioDto(
    val totalAttempts: Int?,
    val attemptsCompleted: Int?,
    val attemptsInProgress: Int?,
    val attemptsOtherState: Int?,
    val attemptsLast30d: Int?,
    val attemptsFirstTime: Int?,
    val attemptsRanked: Int?,
    val attemptsRafaga: Int?,
    val attemptsRazonado: Int?,
    val attemptsInmersivo: Int?,
    val attemptsOtherMode: Int?,
    val totalAnswers: Int?,
    val correctAnswers: Int?,
    val wrongAnswers: Int?,
    val unansweredAnswers: Int?,
    val globalAccuracyRate: Double?,
    val globalErrorRate: Double?,
    val firstTimeAnswers: Int?,
    val randomAnswers: Int?,
    val changedAnswers: Int?,
    val markedForReviewAnswers: Int?,
    val hardestQuestions: List<AnalyticsHardQuestionDto>?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

/** Fila de `v_preguntas_dificultad_global`, ya agregada por la vista. */
@Serializable
data class AnalyticsHardQuestionDto(
    val questionId: String,
    val code: String?,
    val title: String?,
    val questionType: String?,
    val totalAnswers: Int?,
    val totalWrong: Int?,
    val errorRate: Double?,
    val difficulty1To10: Double?,
    val avgAnswerSeconds: Double?,
)

@Serializable
data class AdminAnalyticsRetencionDto(
    val usersWithStreak: Int?,
    val usersWithActiveStreak: Int?,
    val usersStreak7Plus: Int?,
    val usersStreak30Plus: Int?,
    val activeUsers1d: Int?,
    val activeUsers7d: Int?,
    val activeUsers30d: Int?,
    val usersWithCompetitiveState: Int?,
    val leagueDistribution: List<AnalyticsLeagueBucketDto>?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

/** Una liga del catálogo `ligas_nivel` con cuántos usuarios están en ella. */
@Serializable
data class AnalyticsLeagueBucketDto(
    val code: String,
    val name: String,
    val minimumQuestions: Int,
    val windowSize: Int,
    /** `null` si el conteo de esa liga concreta falló; nunca 0 por defecto. */
    val users: Int?,
)

@Serializable
data class AdminAnalyticsComunidadDto(
    val posts: Int?,
    val postsPublished: Int?,
    val postsPinned: Int?,
    val comments: Int?,
    val reports: Int?,
    val reportsPending: Int?,
    val reportsReviewed: Int?,
    val reportsRewarded: Int?,
    val questionFeedback: Int?,
    val questionFeedbackPending: Int?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

@Serializable
data class AdminAnalyticsEconomiaDto(
    val wallets: Int?,
    val walletTransactions: Int?,
    val walletCredits: Int?,
    val walletDebits: Int?,
    val walletNeutralMovements: Int?,
    val purchases: Int?,
    val subscriptions: Int?,
    val subscriptionsActive: Int?,
    val subscriptionsTrial: Int?,
    val subscriptionsPaused: Int?,
    val subscriptionsCancelled: Int?,
    val subscriptionsExpired: Int?,
    val subscriptionsOtherState: Int?,
    val subscriptionsExpiring30d: Int?,
    val commercialProducts: Int?,
    val commercialProductsActive: Int?,
    /**
     * Ingreso bruto en céntimos enteros — nunca `Double`, que redondearía el
     * dinero. Hoy es siempre `null`: sumar `precio_centimos` necesita SUM, que
     * PostgREST no expone (ver [AnalyticsMetricKey.GROSS_REVENUE_CENTS] en
     * `notInstrumented`).
     */
    val grossRevenueCents: Long?,
    val avgOrderValueCents: Long?,
    val ticketsInCirculation: Long?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

@Serializable
data class AdminAnalyticsMarketingDto(
    val attributedUsers: Int?,
    val referredUsers: Int?,
    val usersWithReferralCode: Int?,
    val unattributedUsers: Int?,
    val attributionCoverageRate: Double?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

// ---------------------------------------------------------------------
// Filas de entrada (Supabase -> backend)
// ---------------------------------------------------------------------

@Serializable
internal data class AnalyticsLigaNivelRow(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    @SerialName("preguntas_minimas") val preguntasMinimas: Int = 0,
    @SerialName("ventana_calculo") val ventanaCalculo: Int = 0,
)

@Serializable
internal data class AnalyticsPreguntaDificultadRow(
    @SerialName("pregunta_id") val preguntaId: String = "",
    @SerialName("codigo_pregunta") val codigoPregunta: String? = null,
    val titulo: String? = null,
    @SerialName("tipo_pregunta") val tipoPregunta: String? = null,
    @SerialName("total_respuestas") val totalRespuestas: Int? = null,
    @SerialName("total_fallos") val totalFallos: Int? = null,
    @SerialName("porcentaje_fallo") val porcentajeFallo: Double? = null,
    @SerialName("dificultad_calculada_1_10") val dificultad: Double? = null,
    @SerialName("tiempo_medio_respuesta_segundos") val tiempoMedioSegundos: Double? = null,
)

// ---------------------------------------------------------------------
// Gateway
// ---------------------------------------------------------------------

/**
 * Acceso propio a Supabase para analítica.
 *
 * Se copia el patrón de `AdminV1Gateway` (service role, `HttpClient` de la JDK,
 * `UPSTREAM_REQUEST_TIMEOUT` en cada petición) en vez de compartir instancia:
 * este gateway sólo lee y sólo hace conteos, así que tener su propia superficie
 * mínima evita que un cambio en analítica pueda tocar por accidente una ruta de
 * escritura.
 *
 * Todas las peticiones llevan `timeout(UPSTREAM_REQUEST_TIMEOUT)`: sin él, un
 * upstream que acepta la conexión y no responde deja el hilo colgado para
 * siempre (ver KDoc de `UPSTREAM_REQUEST_TIMEOUT` en Observability.kt).
 */
class AdminAnalyticsGateway internal constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    /**
     * `internal` y no privado, a diferencia de `AdminV1Gateway`, únicamente para
     * poder sustituir el upstream en pruebas. El fallo que se corrigió en el
     * dashboard (`catch { 0 }`) era invisible para los tests precisamente porque
     * no existía ninguna costura para preguntar "¿y si Supabase falla?"; sin
     * ella, la regla de honestidad sólo estaría escrita en un comentario.
     */
    private val client: HttpClient = newUpstreamHttpClient(),
) {

    // -- Producto ------------------------------------------------------

    fun producto(): AdminAnalyticsProductoDto {
        val availability = AnalyticsAvailability()
        val since30d = isoInstantDaysAgo(30)

        val totalUsers = availability.reading(
            AnalyticsMetricKey.TOTAL_USERS,
            countOrNull("perfiles_usuario", "usuario_id"),
        )
        val guestUsers = availability.reading(
            AnalyticsMetricKey.GUEST_USERS,
            countOrNull("perfiles_usuario", "usuario_id", "es_invitado=eq.true"),
        )
        val onboardedUsers = availability.reading(
            AnalyticsMetricKey.ONBOARDED_USERS,
            countOrNull("perfiles_usuario", "usuario_id", "onboarding_completado=eq.true"),
        )
        val totalAppEvents = availability.reading(
            AnalyticsMetricKey.TOTAL_APP_EVENTS,
            countOrNull("eventos_app", "id"),
        )
        val appEventsLast30d = availability.reading(
            AnalyticsMetricKey.APP_EVENTS_LAST_30D,
            countOrNull("eventos_app", "id", "creado_en=gte.$since30d"),
        )
        val totalSessions = availability.reading(
            AnalyticsMetricKey.TOTAL_STUDY_SESSIONS,
            countOrNull("sesiones_estudio", "id"),
        )
        val sessionsLast30d = availability.reading(
            AnalyticsMetricKey.STUDY_SESSIONS_LAST_30D,
            countOrNull("sesiones_estudio", "id", "iniciada_en=gte.$since30d"),
        )
        val finishedSessions = availability.reading(
            AnalyticsMetricKey.FINISHED_STUDY_SESSIONS,
            countOrNull("sesiones_estudio", "id", "finalizada_en=not.is.null"),
        )

        // Derivadas: sólo son fiables si todas sus fuentes lo son.
        val abandonedSessions = availability.reading(
            AnalyticsMetricKey.ABANDONED_STUDY_SESSIONS,
            analyticsRemainder(totalSessions, listOf(finishedSessions)),
        )
        val onboardingRate = availability.reading(
            AnalyticsMetricKey.ONBOARDING_COMPLETION_RATE,
            analyticsRate(onboardedUsers, totalUsers),
        )
        val sessionCompletionRate = availability.reading(
            AnalyticsMetricKey.STUDY_SESSION_COMPLETION_RATE,
            analyticsRate(finishedSessions, totalSessions),
        )

        availability.gap(
            AnalyticsMetricKey.EVENTS_BY_NAME,
            "Necesita GROUP BY nombre_evento sobre eventos_app: vista agregada " +
                "v_eventos_app_por_nombre (nombre_evento, total) o RPC.",
        )
        availability.gap(
            AnalyticsMetricKey.SESSIONS_BY_PLATFORM,
            "Necesita GROUP BY plataforma sobre sesiones_estudio. La columna es texto libre, " +
                "así que contar sólo un puñado de valores adivinados descartaría el resto en silencio.",
        )
        availability.gap(
            AnalyticsMetricKey.SESSIONS_BY_APP_VERSION,
            "Necesita GROUP BY version_app sobre sesiones_estudio (mismo motivo que plataforma).",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_STUDY_SESSION_SECONDS,
            "Necesita AVG(duracion_segundos) sobre sesiones_estudio; PostgREST no expone AVG.",
        )

        return AdminAnalyticsProductoDto(
            totalUsers = totalUsers,
            guestUsers = guestUsers,
            onboardedUsers = onboardedUsers,
            onboardingCompletionRate = onboardingRate,
            totalAppEvents = totalAppEvents,
            appEventsLast30d = appEventsLast30d,
            totalStudySessions = totalSessions,
            studySessionsLast30d = sessionsLast30d,
            finishedStudySessions = finishedSessions,
            abandonedStudySessions = abandonedSessions,
            studySessionCompletionRate = sessionCompletionRate,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Estudio -------------------------------------------------------

    fun estudio(): AdminAnalyticsEstudioDto {
        val availability = AnalyticsAvailability()
        val since30d = isoInstantDaysAgo(30)

        val totalAttempts = availability.reading(
            AnalyticsMetricKey.TOTAL_ATTEMPTS,
            countOrNull("intentos_examen", "id"),
        )
        val completed = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_COMPLETED,
            countOrNull("intentos_examen", "id", "estado=eq.completado"),
        )
        val inProgress = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_IN_PROGRESS,
            countOrNull("intentos_examen", "id", "estado=eq.en_progreso"),
        )
        // Cualquier otro `estado` (abandonado, caducado, lo que exista o se
        // añada) cae aquí en vez de desaparecer del reparto.
        val otherState = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_OTHER_STATE,
            analyticsRemainder(totalAttempts, listOf(completed, inProgress)),
        )
        val attemptsLast30d = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_LAST_30D,
            countOrNull("intentos_examen", "id", "iniciado_en=gte.$since30d"),
        )
        val firstTime = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_FIRST_TIME,
            countOrNull("intentos_examen", "id", "es_primer_intento_examen=eq.true"),
        )
        val ranked = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_RANKED,
            countOrNull("intentos_examen", "id", "cuenta_para_ranking=eq.true"),
        )

        // Vocabulario canónico rafaga/razonado/inmersivo (CANONICAL_EXAM_TYPES en
        // AdminMarket.kt). No se consulta ningún alias legacy: las filas que aún
        // lo lleven se ven en `attemptsOtherMode`, que es justo la señal de que
        // queda migración pendiente.
        val rafaga = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_RAFAGA,
            countOrNull("intentos_examen", "id", "tipo_examen_snapshot=eq.rafaga"),
        )
        val razonado = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_RAZONADO,
            countOrNull("intentos_examen", "id", "tipo_examen_snapshot=eq.razonado"),
        )
        val inmersivo = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_INMERSIVO,
            countOrNull("intentos_examen", "id", "tipo_examen_snapshot=eq.inmersivo"),
        )
        val otherMode = availability.reading(
            AnalyticsMetricKey.ATTEMPTS_OTHER_MODE,
            analyticsRemainder(totalAttempts, listOf(rafaga, razonado, inmersivo)),
        )

        val totalAnswers = availability.reading(
            AnalyticsMetricKey.TOTAL_ANSWERS,
            countOrNull("respuestas_intento", "id"),
        )
        val correct = availability.reading(
            AnalyticsMetricKey.CORRECT_ANSWERS,
            countOrNull("respuestas_intento", "id", "es_correcta=eq.true"),
        )
        val wrong = availability.reading(
            AnalyticsMetricKey.WRONG_ANSWERS,
            countOrNull("respuestas_intento", "id", "es_correcta=eq.false"),
        )
        val unanswered = availability.reading(
            AnalyticsMetricKey.UNANSWERED_ANSWERS,
            countOrNull("respuestas_intento", "id", "es_correcta=is.null"),
        )
        val accuracy = availability.reading(
            AnalyticsMetricKey.GLOBAL_ACCURACY_RATE,
            analyticsRate(correct, totalAnswers),
        )
        val errorRate = availability.reading(
            AnalyticsMetricKey.GLOBAL_ERROR_RATE,
            analyticsRate(wrong, totalAnswers),
        )
        val firstTimeAnswers = availability.reading(
            AnalyticsMetricKey.FIRST_TIME_ANSWERS,
            countOrNull("respuestas_intento", "id", "es_primer_intento_pregunta=eq.true"),
        )
        val randomAnswers = availability.reading(
            AnalyticsMetricKey.RANDOM_ANSWERS,
            countOrNull("respuestas_intento", "id", "respondida_al_azar=eq.true"),
        )
        val changedAnswers = availability.reading(
            AnalyticsMetricKey.CHANGED_ANSWERS,
            countOrNull("respuestas_intento", "id", "cambio_respuesta=eq.true"),
        )
        val markedAnswers = availability.reading(
            AnalyticsMetricKey.MARKED_FOR_REVIEW_ANSWERS,
            countOrNull("respuestas_intento", "id", "marcada_para_repasar=eq.true"),
        )

        // `v_preguntas_dificultad_global` ya viene agregada por la base. Leer sus
        // 10 primeras filas ordenadas NO es agregar en Kotlin: el `limit` es del
        // servidor y el orden también, así que el techo de `max-rows` no puede
        // falsear nada. Traer la vista entera para ordenarla aquí sí sería el bug.
        val hardest = availability.reading(
            AnalyticsMetricKey.HARDEST_QUESTIONS,
            runCatching {
                get(
                    "/rest/v1/v_preguntas_dificultad_global?select=pregunta_id,codigo_pregunta,titulo," +
                        "tipo_pregunta,total_respuestas,total_fallos,porcentaje_fallo," +
                        "dificultad_calculada_1_10,tiempo_medio_respuesta_segundos" +
                        "&order=porcentaje_fallo.desc.nullslast&limit=$HARDEST_QUESTIONS_LIMIT",
                ).decodeList<AnalyticsPreguntaDificultadRow>().map { it.toDto() }
            }.getOrNull(),
        )

        availability.gap(
            AnalyticsMetricKey.AVG_SCORE,
            "Necesita AVG(porcentaje_acierto) sobre intentos_examen completados; PostgREST no expone AVG.",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_ATTEMPT_DURATION_SECONDS,
            "Necesita AVG(duracion_segundos) sobre intentos_examen.",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_ANSWER_TIME_SECONDS,
            "Necesita AVG(tiempo_respuesta_segundos) sobre respuestas_intento.",
        )
        availability.gap(
            AnalyticsMetricKey.ATTEMPTS_PER_DAY,
            "Necesita GROUP BY date_trunc('day', iniciado_en) sobre intentos_examen: " +
                "vista v_intentos_por_dia (dia, total).",
        )
        availability.gap(
            AnalyticsMetricKey.TAG_WEAKNESS_RANKING,
            "v_usuario_debilidades_etiqueta es por usuario; el ranking global necesita " +
                "GROUP BY etiqueta_id sobre esa vista (vista v_debilidades_etiqueta_global).",
        )

        return AdminAnalyticsEstudioDto(
            totalAttempts = totalAttempts,
            attemptsCompleted = completed,
            attemptsInProgress = inProgress,
            attemptsOtherState = otherState,
            attemptsLast30d = attemptsLast30d,
            attemptsFirstTime = firstTime,
            attemptsRanked = ranked,
            attemptsRafaga = rafaga,
            attemptsRazonado = razonado,
            attemptsInmersivo = inmersivo,
            attemptsOtherMode = otherMode,
            totalAnswers = totalAnswers,
            correctAnswers = correct,
            wrongAnswers = wrong,
            unansweredAnswers = unanswered,
            globalAccuracyRate = accuracy,
            globalErrorRate = errorRate,
            firstTimeAnswers = firstTimeAnswers,
            randomAnswers = randomAnswers,
            changedAnswers = changedAnswers,
            markedForReviewAnswers = markedAnswers,
            hardestQuestions = hardest,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Retención -----------------------------------------------------

    fun retencion(): AdminAnalyticsRetencionDto {
        val availability = AnalyticsAvailability()
        // `rachas_usuario` NO TIENE ESCRITOR (ninguna función ni trigger la rellena;
        // ver 20260917084214_dashboard_v2_economy_and_active_users_rpcs.sql): contarla
        // daba un 0 estructural que nunca podía cambiar, pintado como dato real.
        // Rachas y usuarios activos quedan NOT_INSTRUMENTED hasta que exista un
        // escritor (los activos a 30 d ya los da el Dashboard por
        // `admin_dashboard_active_users`).
        val usersWithStreak: Int? = null
        val activeStreak: Int? = null
        val streak7: Int? = null
        val streak30: Int? = null
        val active1d: Int? = null
        val active7d: Int? = null
        val active30d: Int? = null
        listOf(
            AnalyticsMetricKey.USERS_WITH_STREAK,
            AnalyticsMetricKey.USERS_WITH_ACTIVE_STREAK,
            AnalyticsMetricKey.USERS_STREAK_7_PLUS,
            AnalyticsMetricKey.USERS_STREAK_30_PLUS,
            AnalyticsMetricKey.ACTIVE_USERS_1D,
            AnalyticsMetricKey.ACTIVE_USERS_7D,
            AnalyticsMetricKey.ACTIVE_USERS_30D,
        ).forEach { key ->
            availability.gap(key, "rachas_usuario no tiene escritor: el conteo sería siempre 0.")
        }

        val competitive = availability.reading(
            AnalyticsMetricKey.USERS_WITH_COMPETITIVE_STATE,
            countOrNull("estado_competitivo_usuario", "usuario_id"),
        )

        // Reparto por liga: un conteo exacto por cada fila de `ligas_nivel`. El
        // número de consultas está acotado por el catálogo (5 filas hoy), no por
        // el volumen de usuarios, así que esto no degenera al crecer la base.
        val leagues = availability.reading(
            AnalyticsMetricKey.LEAGUE_DISTRIBUTION,
            runCatching {
                get("/rest/v1/ligas_nivel?select=id,codigo,nombre,preguntas_minimas,ventana_calculo&order=posicion.asc")
                    .decodeList<AnalyticsLigaNivelRow>()
                    .map { liga ->
                        AnalyticsLeagueBucketDto(
                            code = liga.codigo,
                            name = liga.nombre,
                            minimumQuestions = liga.preguntasMinimas,
                            windowSize = liga.ventanaCalculo,
                            users = countOrNull(
                                "estado_competitivo_usuario",
                                "usuario_id",
                                "liga_nivel_id=eq.${liga.id}",
                            ),
                        )
                    }
            }.getOrNull(),
        )

        availability.gap(
            AnalyticsMetricKey.AVG_CURRENT_STREAK_DAYS,
            "Necesita AVG(racha_actual_dias) sobre rachas_usuario; PostgREST no expone AVG.",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_BEST_STREAK_DAYS,
            "Necesita AVG(mejor_racha_dias) sobre rachas_usuario.",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_COMPETITIVE_SCORE,
            "Necesita AVG(nota_media_actual) sobre estado_competitivo_usuario.",
        )
        availability.gap(
            AnalyticsMetricKey.RETENTION_COHORTS,
            "Retención D1/D7/D30 necesita cohortes por fecha de alta cruzadas con actividad: " +
                "vista v_retencion_cohortes (cohorte, dia_offset, usuarios) — no se deriva de ningún conteo actual.",
        )

        return AdminAnalyticsRetencionDto(
            usersWithStreak = usersWithStreak,
            usersWithActiveStreak = activeStreak,
            usersStreak7Plus = streak7,
            usersStreak30Plus = streak30,
            activeUsers1d = active1d,
            activeUsers7d = active7d,
            activeUsers30d = active30d,
            usersWithCompetitiveState = competitive,
            leagueDistribution = leagues,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Comunidad -----------------------------------------------------

    fun comunidad(): AdminAnalyticsComunidadDto {
        val availability = AnalyticsAvailability()

        val posts = availability.reading(AnalyticsMetricKey.POSTS, countOrNull("publicaciones_oposired", "id"))
        val postsPublished = availability.reading(
            AnalyticsMetricKey.POSTS_PUBLISHED,
            countOrNull("publicaciones_oposired", "id", "estado=eq.publicado"),
        )
        val postsPinned = availability.reading(
            AnalyticsMetricKey.POSTS_PINNED,
            countOrNull("publicaciones_oposired", "id", "fijada=eq.true"),
        )
        val comments = availability.reading(AnalyticsMetricKey.COMMENTS, countOrNull("comentarios_oposired", "id"))
        val reports = availability.reading(AnalyticsMetricKey.REPORTS, countOrNull("reportes_contenido", "id"))
        val reportsPending = availability.reading(
            AnalyticsMetricKey.REPORTS_PENDING,
            countOrNull("reportes_contenido", "id", "estado=eq.pendiente"),
        )
        // "Revisado" se mide por `revisado_en`, no por un valor de `estado`:
        // la marca de tiempo es un hecho, mientras que el vocabulario de estados
        // puede crecer y dejaría el conteo corto sin avisar.
        val reportsReviewed = availability.reading(
            AnalyticsMetricKey.REPORTS_REVIEWED,
            countOrNull("reportes_contenido", "id", "revisado_en=not.is.null"),
        )
        val reportsRewarded = availability.reading(
            AnalyticsMetricKey.REPORTS_REWARDED,
            countOrNull("reportes_contenido", "id", "recompensa_entregada=eq.true"),
        )
        val feedback = availability.reading(
            AnalyticsMetricKey.QUESTION_FEEDBACK,
            countOrNull("feedback_pregunta", "id"),
        )
        val feedbackPending = availability.reading(
            AnalyticsMetricKey.QUESTION_FEEDBACK_PENDING,
            countOrNull("feedback_pregunta", "id", "estado=eq.pendiente"),
        )

        availability.gap(
            AnalyticsMetricKey.POSTS_BY_CATEGORY,
            "Necesita GROUP BY categoria_id sobre publicaciones_oposired: vista " +
                "v_publicaciones_por_categoria (categoria_id, total).",
        )
        availability.gap(
            AnalyticsMetricKey.TOP_CONTRIBUTORS,
            "Necesita GROUP BY usuario_id + ORDER BY count DESC sobre publicaciones y comentarios.",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_COMMENTS_PER_POST,
            "Necesita AVG sobre un GROUP BY publicacion_id de comentarios_oposired.",
        )
        availability.gap(
            AnalyticsMetricKey.MEDIAN_REPORT_RESOLUTION_HOURS,
            "Necesita percentile_cont sobre (revisado_en - creado_en) en reportes_contenido; " +
                "ni la mediana ni la resta de timestamps son expresables en PostgREST.",
        )

        return AdminAnalyticsComunidadDto(
            posts = posts,
            postsPublished = postsPublished,
            postsPinned = postsPinned,
            comments = comments,
            reports = reports,
            reportsPending = reportsPending,
            reportsReviewed = reportsReviewed,
            reportsRewarded = reportsRewarded,
            questionFeedback = feedback,
            questionFeedbackPending = feedbackPending,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Economía ------------------------------------------------------

    fun economia(): AdminAnalyticsEconomiaDto {
        val availability = AnalyticsAvailability()
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val in30d = now.plus(30, ChronoUnit.DAYS)

        val wallets = availability.reading(AnalyticsMetricKey.WALLETS, countOrNull("carteras_usuario", "usuario_id"))
        val transactions = availability.reading(
            AnalyticsMetricKey.WALLET_TRANSACTIONS,
            countOrNull("transacciones_cartera", "id"),
        )
        // Partición por signo del importe, no por `tipo`: el signo es una
        // partición completa y verificable (positivo + negativo + cero = total),
        // mientras que `tipo` es texto libre cuyo dominio no se puede enumerar
        // desde aquí sin arriesgarse a perder movimientos.
        val credits = availability.reading(
            AnalyticsMetricKey.WALLET_CREDITS,
            countOrNull("transacciones_cartera", "id", "cantidad=gt.0"),
        )
        val debits = availability.reading(
            AnalyticsMetricKey.WALLET_DEBITS,
            countOrNull("transacciones_cartera", "id", "cantidad=lt.0"),
        )
        val neutral = availability.reading(
            AnalyticsMetricKey.WALLET_NEUTRAL_MOVEMENTS,
            countOrNull("transacciones_cartera", "id", "cantidad=eq.0"),
        )

        val purchases = availability.reading(AnalyticsMetricKey.PURCHASES, countOrNull("compras_usuario", "id"))

        val subscriptions = availability.reading(AnalyticsMetricKey.SUBSCRIPTIONS, countOrNull("suscripciones", "id"))
        // PC-03: "activas" publicadas = estado `activa` Y todavía vigentes. Una fila
        // `activa` con `fin_en` pasado es una suscripción vencida que nadie cerró
        // (en MAIN, la única "activa" venció hace 75 días): contarla decía que hay
        // un suscriptor que no hay. Mismo filtro que el panel de Inicio, para que la
        // misma cifra no signifique dos cosas en dos pantallas.
        val subsActive = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_ACTIVE,
            countOrNull("suscripciones", "id", activeSubscriptionsFilter(Instant.now())),
        )
        // La PARTICIÓN por estado sigue contando por `estado`, sin vigencia: si
        // "otros estados" restara las vigentes, cada `activa` vencida aparecería ahí
        // como si tuviera un estado desconocido. Es un insumo, no se publica.
        val subsActiveByState = countOrNull("suscripciones", "id", "estado=eq.activa")
        val subsTrial = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_TRIAL,
            countOrNull("suscripciones", "id", "estado=eq.periodo_prueba"),
        )
        val subsPaused = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_PAUSED,
            countOrNull("suscripciones", "id", "estado=eq.pausada"),
        )
        val subsCancelled = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_CANCELLED,
            countOrNull("suscripciones", "id", "estado=eq.cancelada"),
        )
        val subsExpired = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_EXPIRED,
            countOrNull("suscripciones", "id", "estado=eq.caducada"),
        )
        val subsOther = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_OTHER_STATE,
            analyticsRemainder(subscriptions, listOf(subsActiveByState, subsTrial, subsPaused, subsCancelled, subsExpired)),
        )
        // Renovaciones/caducidades inminentes: la ventana se acota por los dos
        // extremos para no contar como "vence pronto" lo que venció hace meses.
        val expiring = availability.reading(
            AnalyticsMetricKey.SUBSCRIPTIONS_EXPIRING_30D,
            countOrNull("suscripciones", "id", "estado=eq.activa&fin_en=gte.$now&fin_en=lt.$in30d"),
        )

        val products = availability.reading(
            AnalyticsMetricKey.COMMERCIAL_PRODUCTS,
            countOrNull("productos_comerciales", "id"),
        )
        val productsActive = availability.reading(
            AnalyticsMetricKey.COMMERCIAL_PRODUCTS_ACTIVE,
            countOrNull("productos_comerciales", "id", "activo=eq.true"),
        )

        availability.gap(
            AnalyticsMetricKey.GROSS_REVENUE_CENTS,
            "Necesita SUM(precio_centimos) sobre compras_usuario completadas: vista " +
                "v_ingresos_brutos (periodo, total_centimos) o RPC que devuelva entero en céntimos.",
        )
        availability.gap(
            AnalyticsMetricKey.AVG_ORDER_VALUE_CENTS,
            "Necesita SUM(precio_centimos)/COUNT(*) sobre compras_usuario; el ticket medio " +
                "sólo se publica como entero en céntimos, nunca como Double.",
        )
        availability.gap(
            AnalyticsMetricKey.TICKETS_IN_CIRCULATION,
            "Necesita SUM(saldo_tickets) sobre carteras_usuario: vista v_opositickets_en_circulacion.",
        )
        availability.gap(
            AnalyticsMetricKey.REVENUE_BY_PRODUCT,
            "Necesita GROUP BY producto_codigo + SUM(precio_centimos) sobre compras_usuario.",
        )
        availability.gap(
            AnalyticsMetricKey.PURCHASES_BY_STATE,
            "Necesita GROUP BY estado sobre compras_usuario; el dominio de estados no está " +
                "declarado en el esquema accesible desde PostgREST.",
        )

        return AdminAnalyticsEconomiaDto(
            wallets = wallets,
            walletTransactions = transactions,
            walletCredits = credits,
            walletDebits = debits,
            walletNeutralMovements = neutral,
            purchases = purchases,
            subscriptions = subscriptions,
            subscriptionsActive = subsActive,
            subscriptionsTrial = subsTrial,
            subscriptionsPaused = subsPaused,
            subscriptionsCancelled = subsCancelled,
            subscriptionsExpired = subsExpired,
            subscriptionsOtherState = subsOther,
            subscriptionsExpiring30d = expiring,
            commercialProducts = products,
            commercialProductsActive = productsActive,
            grossRevenueCents = null,
            avgOrderValueCents = null,
            ticketsInCirculation = null,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Marketing -----------------------------------------------------

    fun marketing(): AdminAnalyticsMarketingDto {
        val availability = AnalyticsAvailability()

        val totalUsers = countOrNull("perfiles_usuario", "usuario_id")
        val attributed = availability.reading(
            AnalyticsMetricKey.ATTRIBUTED_USERS,
            countOrNull("atribucion_usuario", "usuario_id"),
        )
        val referred = availability.reading(
            AnalyticsMetricKey.REFERRED_USERS,
            countOrNull("atribucion_usuario", "usuario_id", "referido_por=not.is.null"),
        )
        val withCode = availability.reading(
            AnalyticsMetricKey.USERS_WITH_REFERRAL_CODE,
            countOrNull("atribucion_usuario", "usuario_id", "codigo_referido=not.is.null"),
        )
        // `atribucion_usuario.usuario_id` es único por usuario, así que restar
        // contra el total de perfiles da los usuarios sin atribución. Si el total
        // de perfiles no se pudo leer, la resta no se publica.
        val unattributed = availability.reading(
            AnalyticsMetricKey.UNATTRIBUTED_USERS,
            analyticsRemainder(totalUsers, listOf(attributed)),
        )
        val coverage = availability.reading(
            AnalyticsMetricKey.ATTRIBUTION_COVERAGE_RATE,
            analyticsRate(attributed, totalUsers),
        )

        availability.gap(
            AnalyticsMetricKey.USERS_BY_SOURCE,
            "Necesita GROUP BY fuente sobre atribucion_usuario: vista v_atribucion_por_fuente " +
                "(fuente, usuarios). La columna es texto libre y no se puede enumerar desde PostgREST.",
        )
        availability.gap(
            AnalyticsMetricKey.USERS_BY_CHANNEL,
            "Necesita GROUP BY canal sobre atribucion_usuario (mismo motivo que fuente).",
        )
        availability.gap(
            AnalyticsMetricKey.USERS_BY_CAMPAIGN,
            "Necesita GROUP BY campania sobre atribucion_usuario (mismo motivo que fuente).",
        )
        availability.gap(
            AnalyticsMetricKey.TOP_REFERRERS,
            "Necesita GROUP BY referido_por + ORDER BY count DESC sobre atribucion_usuario.",
        )

        return AdminAnalyticsMarketingDto(
            attributedUsers = attributed,
            referredUsers = referred,
            usersWithReferralCode = withCode,
            unattributedUsers = unattributed,
            attributionCoverageRate = coverage,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Acceso HTTP ---------------------------------------------------

    /**
     * Conteo exacto que devuelve `null` en vez de lanzar.
     *
     * Es el único punto donde un fallo de lectura se convierte en ausencia de
     * dato, y por eso `null` significa siempre "no se pudo leer" y nunca "cero":
     * un cero real llega como `0`. Quien llama lo pasa por
     * [AnalyticsAvailability.reading], que apunta la clave sin más ceremonia.
     */
    private fun countOrNull(table: String, selectColumn: String, filter: String = ""): Int? = runCatching {
        val suffix = if (filter.isBlank()) "" else "&$filter"
        exactCount("/rest/v1/$table?select=$selectColumn$suffix")
    }.getOrElse { error ->
        logReadFailure(table, error)
        null
    }

    /**
     * F-12b: una lectura que falla deja rastro con el `request_id` (el MDC lo pone la
     * costura de IO) y un evento contable, no una línea en `System.err`. El mensaje
     * pasa por la redacción: el cuerpo de PostgREST puede traer `hint`s de esquema.
     * Mismo patrón que `AdminDashboardOverviewGateway.logReadFailure`.
     */
    private fun logReadFailure(source: String, error: Throwable) {
        AnalyticsLogger.warn(
            "analytics read {} failed: {}",
            source,
            error.redactedMessage("Fallo de lectura", 160),
        )
        logStrategicEvent(
            StrategicEvent.SUPABASE_REQUEST_FAILED,
            operation = "analytics_count_$source",
            result = "read_failed",
            errorType = error::class.simpleName,
        )
    }

    /**
     * Total exacto sin transferir filas.
     *
     * Equivalente al `exactCount` privado de `AdminV1Gateway`: se reescribe aquí
     * porque aquél es privado de otra clase y su fichero no se toca en este
     * trabajo. `Prefer: count=exact` hace que PostgREST publique el total en
     * `Content-Range`, y `Range: 0-0` recorta el cuerpo a una fila, de modo que
     * el conteo no depende del `max-rows` del servidor ni carga la tabla en
     * memoria. El parser del encabezado sí se reutiliza
     * ([parseContentRangeTotal]) para no tener dos implementaciones que puedan
     * discrepar.
     */
    private fun exactCount(path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
            .header("Content-Type", "application/json")
            .header("Prefer", "count=exact")
            .header("Range-Unit", "items")
            .header("Range", "0-0")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase network error on COUNT ${path.substringBefore('?')}: " +
                    "${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        // 206 Partial Content es la respuesta normal a un Range acotado.
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase COUNT ${path.substringBefore('?')} -> ${response.statusCode()}: " +
                    response.body().take(200),
            )
        }
        return parseContentRangeTotal(response.headers().firstValue("content-range").orElse(null))
            ?: throw IllegalStateException(
                "Supabase COUNT ${path.substringBefore('?')} no devolvió Content-Range utilizable.",
            )
    }

    /** GET de lectura acotada (catálogos pequeños y vistas ya agregadas). */
    private fun get(path: String): String {
        val request = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
            .header("Content-Type", "application/json")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase network error on GET ${path.substringBefore('?')}: " +
                    "${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase GET ${path.substringBefore('?')} -> ${response.statusCode()}: " +
                    response.body().take(200),
            )
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    private fun isoInstantDaysAgo(days: Long): String =
        Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()

    companion object {
        /**
         * Tope de preguntas difíciles devueltas. El `limit` viaja a PostgREST: la
         * vista se ordena y recorta en el servidor, no aquí.
         */
        private const val HARDEST_QUESTIONS_LIMIT = 10

        /**
         * F-01/F-02: clave por [SupabaseRequestAuthPolicy.secretFromEnvironment] y
         * cabeceras por [supabaseElevatedAuth]. Leía sólo `SUPABASE_SERVICE_ROLE_KEY`
         * (con la configuración canónica el backend no arrancaba) y firmaba `Bearer`
         * sin mirar la forma de la clave.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): AdminAnalyticsGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            return if (url.isBlank() || serviceRoleKey.isBlank()) {
                null
            } else {
                AdminAnalyticsGateway(url, serviceRoleKey, json)
            }
        }
    }
}

private fun AnalyticsPreguntaDificultadRow.toDto(): AnalyticsHardQuestionDto = AnalyticsHardQuestionDto(
    questionId = preguntaId,
    code = codigoPregunta,
    title = titulo,
    questionType = tipoPregunta,
    totalAnswers = totalRespuestas,
    totalWrong = totalFallos,
    errorRate = porcentajeFallo,
    difficulty1To10 = dificultad,
    avgAnswerSeconds = tiempoMedioSegundos,
)

// ---------------------------------------------------------------------
// Rutas
// ---------------------------------------------------------------------

/**
 * Analítica de producto, sólo lectura.
 *
 * Seis secciones y no una respuesta gigante: cada pantalla del panel pide su
 * sección, así que abrir "Producto" no dispara los conteos de economía. Todas
 * son GET puros — este fichero no escribe nada en ninguna tabla.
 *
 * Se monta dentro de `authenticate("gateway-auth")`, igual que el resto de
 * `/api/v1/admin`, de modo que hereda el guardia de autorización admin.
 */
fun Route.adminAnalyticsRoutes(gateway: AdminAnalyticsGateway) {
    route("/api/v1/admin/analytics") {
        get("/producto") { call.respond(gateway.producto()) }
        get("/estudio") { call.respond(gateway.estudio()) }
        get("/retencion") { call.respond(gateway.retencion()) }
        get("/comunidad") { call.respond(gateway.comunidad()) }
        get("/economia") { call.respond(gateway.economia()) }
        get("/marketing") { call.respond(gateway.marketing()) }
    }
}
