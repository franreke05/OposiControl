package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Dashboard V2: UN contrato agregado, compuesto en Ktor.
 *
 * Sustituye a la orquestación que hacía el cliente, donde el panel encadenaba
 * cinco repositorios —dos de ellos bloqueantes— y lanzaba además **una petición
 * HTTP por examen** para auditar recursos: un coste que crecía con el catálogo y
 * que además componía datos de negocio en la capa de datos del escritorio, no en
 * el único sitio con `service_role`.
 *
 * Hereda entero el contrato de honestidad de [AdminAnalyticsGateway] y reutiliza
 * sus piezas ([AnalyticsAvailability], [analyticsRate], [analyticsRemainder],
 * [AnalyticsGapDto]) en vez de reescribirlas:
 *
 * 1. **Ninguna métrica se inventa.** Lo que no se pudo leer viaja como `null` con
 *    su clave en `unavailableMetrics`, nunca como `0`.
 * 2. **Contar se cuenta en el servidor**, con `Prefer: count=exact` y `Range: 0-0`.
 * 3. **Lo que PostgREST no sabe hacer, no se finge**: SUM no existe, así que
 *    dinero y tickets se declaran NOT_INSTRUMENTED con la vista que haría falta.
 *
 * ### Lo que este fichero añade sobre el precedente: la franja como unidad de fallo
 *
 * La analítica sirve una sección por petición, así que un fallo tumba justo la
 * pantalla que lo pidió. Aquí las seis franjas viajan juntas, y por eso cada una
 * se compone de forma tolerante: si una no se puede armar, se nombra en
 * `failedBlocks` y **las otras cinco se sirven igual**. Es el mismo patrón que
 * `getUsuarioDetalle` usa para las secciones de la ficha de usuario. Nunca un 500
 * porque una franja falló.
 *
 * Una franja ausente del cuerpo y una franja en `failedBlocks` son cosas
 * distintas a propósito: la primera es "no la pediste" (`?sections=`) y la
 * segunda "la pedí y se rompió". Sin esa distinción el panel volvería a la misma
 * ambigüedad que el cero por fallo de lectura.
 *
 * ### Por qué gateway propio y no `AdminV1Gateway`
 *
 * Mismo argumento que en analítica y rankings: este agregado **sólo lee**, y
 * tener su propia superficie mínima hace imposible que un cambio aquí toque por
 * accidente una ruta de escritura. Por eso tampoco se reutiliza
 * `AdminV1Gateway.listAuditoria`, que vive en una clase con ~100 métodos de
 * escritura: la consulta es una línea y traerse la clase entera para ahorrarla
 * saldría mucho más caro de lo que ahorra.
 */

// ---------------------------------------------------------------------
// Franjas y claves de métrica
// ---------------------------------------------------------------------

/**
 * Las seis franjas, con el nombre exacto que viaja en `?sections=` y en
 * `failedBlocks`.
 *
 * Coinciden con `DashboardBlock.wire` del dominio del cliente. El backend no
 * puede importar el dominio (es un módulo JVM independiente), así que la
 * coincidencia se sostiene con el test de contrato de cada lado, igual que el
 * resto de claves de cable de este backend.
 */
object DashboardOverviewBlock {
    const val SYSTEM = "system"
    const val ATTENTION = "attention"
    const val PULSE = "pulse"
    const val CONTENT = "content"
    const val ACTIVITY = "activity"
    const val ECONOMY = "economy"

    /** Orden de lectura del panel: primero si está en pie, luego qué trabajo espera. */
    val ALL: Set<String> = linkedSetOf(SYSTEM, ATTENTION, PULSE, CONTENT, ACTIVITY, ECONOMY)
}

/**
 * Claves estables de métrica del dashboard.
 *
 * Cada constante coincide **exactamente** con el nombre de su campo en el DTO,
 * por la misma razón que en [AnalyticsMetricKey]: la clave viaja al cliente para
 * marcar la tarjeta como NO_DATA/NOT_INSTRUMENTED, y si clave y campo se separan
 * la UI marcaría la tarjeta equivocada. `AdminDashboardOverviewTest` lo comprueba
 * campo a campo.
 */
object DashboardOverviewMetricKey {
    // -- ATTENTION --
    const val REPORTS_PENDING = "reportsPending"
    const val QUESTION_FEEDBACK_PENDING = "questionFeedbackPending"
    const val DRAFT_EXAMS = "draftExams"
    const val SUBSCRIPTIONS_EXPIRING_7D = "subscriptionsExpiring7d"

    // -- PULSE --
    const val ACTIVE_USERS_30D = "activeUsers30d"
    const val ATTEMPTS_LAST_30D = "attemptsLast30d"
    const val PUBLISHED_EXAMS = "publishedExams"
    const val GLOBAL_ERROR_RATE = "globalErrorRate"

    // -- CONTENT --
    const val TOTAL_EXAMS = "totalExams"
    const val TOTAL_QUESTIONS = "totalQuestions"
    const val TOTAL_RESOURCES = "totalResources"
    const val IMPORT_BATCHES = "importBatches"

    // -- ECONOMY --
    const val SUBSCRIPTIONS_ACTIVE = "subscriptionsActive"
    const val SUBSCRIPTIONS_EXPIRING_30D = "subscriptionsExpiring30d"
    const val COMMERCIAL_PRODUCTS_ACTIVE = "commercialProductsActive"
    const val GROSS_REVENUE_CENTS = "grossRevenueCents"
    const val TICKETS_IN_CIRCULATION = "ticketsInCirculation"
}

// ---------------------------------------------------------------------
// Parseo de ?sections=
// ---------------------------------------------------------------------

/** Resultado de leer `?sections=`: o hay franjas válidas, o hay nombres que no existen. */
internal sealed interface DashboardSectionsSelection {
    /** Franjas a componer. Nunca vacío: omitir el parámetro selecciona todas. */
    data class Selected(val blocks: Set<String>) : DashboardSectionsSelection

    /** Al menos un nombre no corresponde a ninguna franja. Se responde 400. */
    data class Unknown(val values: List<String>) : DashboardSectionsSelection
}

/**
 * CSV opcional e insensible a mayúsculas.
 *
 * Omitido o en blanco significa **todas** las franjas, que es la carga inicial
 * del panel. Un nombre desconocido **no** se ignora: se responde 400 nombrando
 * los válidos. Descartarlo en silencio devolvería una respuesta que parece
 * correcta pero le falta justo la franja que el cliente pidió, y ese es el
 * modo de fallo más caro de diagnosticar.
 */
internal fun parseDashboardSections(raw: String?): DashboardSectionsSelection {
    val tokens = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return DashboardSectionsSelection.Selected(DashboardOverviewBlock.ALL)
    val normalized = tokens.map { it.lowercase() }
    val unknown = tokens.filterIndexed { index, _ -> normalized[index] !in DashboardOverviewBlock.ALL }
    if (unknown.isNotEmpty()) return DashboardSectionsSelection.Unknown(unknown)
    return DashboardSectionsSelection.Selected(normalized.toCollection(linkedSetOf()))
}

// ---------------------------------------------------------------------
// DTOs de respuesta
// ---------------------------------------------------------------------

/*
 * Ningún campo numérico lleva valor por defecto, por la misma razón que en
 * `AdminAnalytics.kt`: un campo ausente por coincidir con su default volvería a
 * ser indistinguible de un cero. La ausencia de dato viaja en
 * `unavailableMetrics`, que es siempre la autoridad (el Json del servidor usa
 * `explicitNulls = false`, así que un `null` no llega al cable y la lista es la
 * única señal fiable).
 */

@Serializable
data class AdminDashboardOverviewDto(
    val system: AdminDashboardSystemDto? = null,
    val attention: AdminDashboardAttentionDto? = null,
    val pulse: AdminDashboardPulseDto? = null,
    val content: AdminDashboardContentDto? = null,
    val activity: AdminDashboardActivityDto? = null,
    val economy: AdminDashboardEconomyDto? = null,
    /**
     * Franjas que se pidieron y no se pudieron componer. Sin default: viaja
     * siempre, aunque esté vacía, para que "no hubo fallos" sea una afirmación
     * explícita y no la ausencia de una clave.
     */
    val failedBlocks: List<String>,
    val generatedAt: String,
)

/** SYSTEM: salud de los servicios. Sin métricas: es un estado, no un número. */
@Serializable
data class AdminDashboardSystemDto(
    val health: AdminDashboardHealthDto,
    val generatedAt: String,
)

/**
 * Igual que [AdminSystemStatusDto] pero recortado a lo que el panel pinta en su
 * franja de salud. No se reutiliza aquél entero porque arrastra siete campos que
 * hoy son siempre nulos y que aquí sólo servirían para que el cliente tuviese que
 * ignorarlos uno a uno; lo que sí se reutiliza es la lógica que los decide
 * ([aggregateSystemStatus], [supabaseLevelFrom]) y su vocabulario de niveles.
 */
@Serializable
data class AdminDashboardHealthDto(
    val overallStatus: String,
    val ktorStatus: String,
    val supabaseStatus: String,
    val updatedAt: String,
    /**
     * Milisegundos que tardó **la sonda a Supabase**, no la latencia general de
     * la API.
     *
     * Es la misma medición con la que [supabaseLevelFrom] decide OPERATIVO vs
     * DEGRADADO, así que ya está hecha: descartarla obligaría al panel a enseñar
     * "degradado" sin poder decir cuánto, que es información perdida sin
     * ganar nada. `null` si la sonda no llegó a completarse.
     *
     * La latencia general de la API sigue sin instrumentar y se declara como tal
     * en [notInstrumented]: necesita Prometheus o un contador propio de
     * peticiones, y no se deduce de esta sonda.
     */
    val supabaseLatencyMs: Long?,
    /** Lo que no tiene fuente todavía. Declarado, no omitido: un verde por ignorancia se cree. */
    val notInstrumented: List<String>,
)

@Serializable
data class AdminDashboardAttentionDto(
    val reportsPending: Int?,
    val questionFeedbackPending: Int?,
    val draftExams: Int?,
    val subscriptionsExpiring7d: Int?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

@Serializable
data class AdminDashboardPulseDto(
    val activeUsers30d: Int?,
    val attemptsLast30d: Int?,
    val publishedExams: Int?,
    val globalErrorRate: Double?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

@Serializable
data class AdminDashboardContentDto(
    val totalExams: Int?,
    val totalQuestions: Int?,
    val totalResources: Int?,
    val importBatches: Int?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

/** ACTIVITY: cronología real. Sin métricas y sin huecos: o hay filas, o la franja falla. */
@Serializable
data class AdminDashboardActivityDto(
    val entries: List<AdminDashboardActivityEntryDto>,
    /**
     * Filas leídas de `auditoria_log` que **no** se pudieron entregar por venir
     * sin `creado_en` interpretable o sin `id`.
     *
     * Existe porque sin él "he leído ocho filas y ninguna era legible" y "no hay
     * nada en `auditoria_log`" llegan al cliente **idénticas**: una lista vacía.
     * El panel pintaría "No hay acciones registradas todavía", que en el primer
     * caso es sencillamente falso. Es la misma ambigüedad que este contrato
     * elimina en todas partes —el cero por fallo de lectura, la franja fallida
     * frente a la no pedida—, y se le había escapado justo a la cronología.
     *
     * Sin valor por defecto, igual que los campos numéricos de las métricas: un
     * `0` ausente por coincidir con su default sería otra vez indistinguible de
     * "no lo sé".
     */
    val droppedEntries: Int,
    val generatedAt: String,
)

/**
 * Un hecho de `auditoria_log`.
 *
 * [occurredAt] es `creado_en` de la fila, nunca el instante de la petición. El
 * dashboard anterior fabricaba tres "eventos recientes" derivados del estado
 * actual y les ponía `now()` a los tres: decían haber ocurrido a la vez y se
 * rejuvenecían solos en cada refresco. Una fila cuyo `creado_en` no se puede
 * interpretar **se descarta**; rellenarla con la hora de lectura sería volver
 * exactamente a ese fallo.
 */
@Serializable
data class AdminDashboardActivityEntryDto(
    val id: String,
    val action: String,
    val targetTable: String?,
    val targetId: String?,
    val occurredAt: String,
)

@Serializable
data class AdminDashboardEconomyDto(
    val subscriptionsActive: Int?,
    val subscriptionsExpiring30d: Int?,
    val commercialProductsActive: Int?,
    /**
     * Céntimos enteros — nunca `Double`, que redondearía el dinero. Hoy siempre
     * `null` y declarado en `notInstrumented`: sumar `precio_centimos` necesita
     * SUM y PostgREST no lo expone.
     */
    val grossRevenueCents: Long?,
    val ticketsInCirculation: Long?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

// ---------------------------------------------------------------------
// Filas de entrada (Supabase -> backend)
// ---------------------------------------------------------------------

@Serializable
internal data class DashboardAuditoriaRow(
    val id: String? = null,
    val accion: String = "",
    @SerialName("tabla_afectada") val tablaAfectada: String? = null,
    @SerialName("registro_id") val registroId: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

/**
 * Instante real de una fila de auditoría, o `null` si no lo hay.
 *
 * Se aceptan las dos formas en que `timestamptz` sale de PostgREST: la ISO con
 * `Z` y la que lleva desplazamiento explícito (`+00:00`), que en algunas
 * versiones de la JDK `Instant.parse` no admite por sí sola. Cualquier otra cosa
 * —vacío, nulo, texto— no es una fecha y se dice que no lo es.
 */
internal fun parseAuditTimestamp(raw: String?): Instant? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return runCatching { Instant.parse(value) }
        .recoverCatching { OffsetDateTime.parse(value).toInstant() }
        .getOrNull()
}

/**
 * Convierte la fila, o la descarta.
 *
 * Se descarta por dos motivos, y los dos son el mismo criterio: una fila que no
 * se puede fechar y una fila que no se puede identificar son igual de
 * inservibles. `id.orEmpty()` fabricaba una cadena vacía para la segunda, y una
 * entrada con id `""` es una entrada que nadie puede señalar, deduplicar ni
 * abrir — el mismo defecto que rellenar un conteo con cero, en otro campo.
 *
 * En la práctica no debería ocurrir: `registrarAuditoria` escribe siempre un
 * `UUID.randomUUID()` (`AdminV1Routes.kt`). El valor de descartarla está en que
 * el contrato no entrega nunca algo que no cumple su propia forma.
 *
 * Lo descartado se cuenta en [AdminDashboardActivityDto.droppedEntries]: se
 * descarta, pero no en silencio.
 */
internal fun DashboardAuditoriaRow.toActivityEntryOrNull(): AdminDashboardActivityEntryDto? {
    val identifier = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val occurredAt = parseAuditTimestamp(creadoEn) ?: return null
    return AdminDashboardActivityEntryDto(
        id = identifier,
        action = accion,
        targetTable = tablaAfectada,
        targetId = registroId,
        occurredAt = occurredAt.toString(),
    )
}

// ---------------------------------------------------------------------
// Gateway
// ---------------------------------------------------------------------

class AdminDashboardOverviewGateway internal constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    /**
     * `internal` sólo para poder sustituir el upstream en pruebas: sin esa
     * costura, "¿y si Supabase falla?" volvería a ser una regla escrita
     * únicamente en un comentario (misma decisión que en [AdminAnalyticsGateway]).
     */
    private val client: HttpClient = newUpstreamHttpClient(),
) {

    /**
     * Compone las franjas pedidas, tolerando que alguna no se pueda armar.
     *
     * `sections` vacío significa todas. Cada franja se compone dentro de
     * [block]: un fallo la nombra en `failedBlocks` y deja intactas las demás.
     */
    fun overview(sections: Set<String> = DashboardOverviewBlock.ALL): AdminDashboardOverviewDto {
        val requested = sections.ifEmpty { DashboardOverviewBlock.ALL }
        val failed = linkedSetOf<String>()

        /** Compone una franja; si lanza, la marca y devuelve null en vez de tumbar la respuesta. */
        fun <T : Any> block(name: String, compose: () -> T): T? {
            if (name !in requested) return null
            return runCatching(compose).getOrElse { error ->
                System.err.println("[ktor] dashboard overview $name -> ${error.message?.take(160)}")
                failed += name
                null
            }
        }

        val system = block(DashboardOverviewBlock.SYSTEM) { systemBlock() }
        val attention = block(DashboardOverviewBlock.ATTENTION) { attentionBlock() }
        val pulse = block(DashboardOverviewBlock.PULSE) { pulseBlock() }
        val content = block(DashboardOverviewBlock.CONTENT) { contentBlock() }
        val activity = block(DashboardOverviewBlock.ACTIVITY) { activityBlock() }
        val economy = block(DashboardOverviewBlock.ECONOMY) { economyBlock() }

        return AdminDashboardOverviewDto(
            system = system,
            attention = attention,
            pulse = pulse,
            content = content,
            activity = activity,
            economy = economy,
            // Se ordena como ALL para que el orden no dependa de en qué franja falló primero.
            failedBlocks = DashboardOverviewBlock.ALL.filter { it in failed },
            generatedAt = Instant.now().toString(),
        )
    }

    // -- SYSTEM --------------------------------------------------------

    /**
     * Misma comprobación que `/api/v1/admin/estado-sistema`: Ktor está en pie por
     * definición si esta línea se ejecuta, y Supabase se mide con una lectura
     * mínima real y cronometrada. Las reglas que traducen eso a niveles se
     * reutilizan tal cual ([supabaseLevelFrom], [aggregateSystemStatus]).
     */
    private fun systemBlock(): AdminDashboardSystemDto {
        val startedAt = System.nanoTime()
        val supabaseReachable = runCatching {
            exactCount("/rest/v1/especialidades?select=id")
            true
        }.getOrDefault(false)
        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000

        val supabaseLevel = supabaseLevelFrom(
            reachable = supabaseReachable,
            latencyMs = latencyMs,
            slowThresholdMs = SUPABASE_SLOW_THRESHOLD_MS,
        )
        val ktorLevel = SystemStatusLevelWire.OPERATIVE
        val now = Instant.now().toString()

        return AdminDashboardSystemDto(
            health = AdminDashboardHealthDto(
                overallStatus = aggregateSystemStatus(listOf(ktorLevel, supabaseLevel)),
                ktorStatus = ktorLevel,
                supabaseStatus = supabaseLevel,
                updatedAt = now,
                // Sólo se publica si la sonda llegó a completarse: el cronómetro
                // de una lectura que falló mide cuánto tardó en romperse, no
                // cuánto tarda Supabase en responder, y presentarlo como latencia
                // sería inventar un dato con forma de medición.
                supabaseLatencyMs = latencyMs.takeIf { supabaseReachable },
                // API_LATENCY sigue siendo un hueco declarado: la latencia
                // general de la API no es esta sonda. Mismo criterio que
                // `/estado-sistema`, que también publica la medición Y declara
                // que no tiene la latencia general.
                notInstrumented = listOf(
                    SystemStatusNotInstrumented.WORKER,
                    SystemStatusNotInstrumented.CPU,
                    SystemStatusNotInstrumented.MEMORY,
                    SystemStatusNotInstrumented.API_LATENCY,
                    SystemStatusNotInstrumented.API_ERRORS,
                    SystemStatusNotInstrumented.MOBILE_CRASHES,
                    SystemStatusNotInstrumented.QUEUE_DEPTH,
                ),
            ),
            generatedAt = now,
        )
    }

    // -- ATTENTION -----------------------------------------------------

    /** Trabajo pendiente. Cada cifra debe poder abrir la pantalla que la resuelve. */
    private fun attentionBlock(): AdminDashboardAttentionDto {
        val availability = AnalyticsAvailability()
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val in7d = now.plus(7, ChronoUnit.DAYS)

        // Mismos filtros que `/analytics/comunidad`: una misma cifra no puede
        // significar cosas distintas en dos pantallas del mismo panel.
        val reportsPending = availability.reading(
            DashboardOverviewMetricKey.REPORTS_PENDING,
            countOrNull("reportes_contenido", "id", "estado=eq.pendiente"),
        )
        val questionFeedbackPending = availability.reading(
            DashboardOverviewMetricKey.QUESTION_FEEDBACK_PENDING,
            countOrNull("feedback_pregunta", "id", "estado=eq.pendiente"),
        )

        // Borradores = exámenes totales menos publicados, igual que
        // `getDashboardMetrics`. Los dos conteos NO se publican aquí (viven en
        // CONTENT y PULSE): son insumos. Si cualquiera falla, `analyticsRemainder`
        // devuelve null y la métrica queda NO_DATA — una resta con un hueco no da
        // un número, da una mentira.
        val totalExams = countOrNull("examenes", "id")
        val publishedExams = countOrNull("examenes", "id", "publicado_en=not.is.null&activo=eq.true")
        val draftExams = availability.reading(
            DashboardOverviewMetricKey.DRAFT_EXAMS,
            analyticsRemainder(totalExams, listOf(publishedExams)),
        )

        // Ventana de 7 días: la que cabe en una semana de trabajo y por tanto la
        // única accionable. Mismo patrón que `subscriptionsExpiring30d` en
        // `/analytics/economia`, acotada por los DOS extremos para no contar como
        // "vence pronto" lo que venció hace meses.
        val subscriptionsExpiring7d = availability.reading(
            DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_7D,
            countOrNull("suscripciones", "id", "estado=eq.activa&fin_en=gte.$now&fin_en=lt.$in7d"),
        )

        return AdminDashboardAttentionDto(
            reportsPending = reportsPending,
            questionFeedbackPending = questionFeedbackPending,
            draftExams = draftExams,
            subscriptionsExpiring7d = subscriptionsExpiring7d,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- PULSE ---------------------------------------------------------

    /** Contexto de 30 días. Ninguna cifra de aquí es accionable por sí sola. */
    private fun pulseBlock(): AdminDashboardPulseDto {
        val availability = AnalyticsAvailability()
        val today = LocalDate.now(ZoneOffset.UTC)
        val since30d = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()

        // Activos SIN DISTINCT: `rachas_usuario.usuario_id` es la PK, así que hay
        // exactamente una fila por usuario y contar filas ya es contar usuarios.
        // Es el mismo filtro de `/analytics/retencion` (ventana de 30 días =
        // hoy incluido más los 29 anteriores).
        val activeUsers30d = availability.reading(
            DashboardOverviewMetricKey.ACTIVE_USERS_30D,
            countOrNull("rachas_usuario", "usuario_id", "ultima_actividad_fecha=gte.${today.minusDays(29)}"),
        )
        val attemptsLast30d = availability.reading(
            DashboardOverviewMetricKey.ATTEMPTS_LAST_30D,
            countOrNull("intentos_examen", "id", "iniciado_en=gte.$since30d"),
        )
        val publishedExams = availability.reading(
            DashboardOverviewMetricKey.PUBLISHED_EXAMS,
            countOrNull("examenes", "id", "publicado_en=not.is.null&activo=eq.true"),
        )

        // Tasa de error global: dos conteos exactos, no una muestra. Insumos, no
        // métricas publicadas de esta franja.
        val totalAnswers = countOrNull("respuestas_intento", "id")
        val wrongAnswers = countOrNull("respuestas_intento", "id", "es_correcta=eq.false")
        val globalErrorRate = availability.reading(
            DashboardOverviewMetricKey.GLOBAL_ERROR_RATE,
            analyticsRate(wrongAnswers, totalAnswers),
        )

        return AdminDashboardPulseDto(
            activeUsers30d = activeUsers30d,
            attemptsLast30d = attemptsLast30d,
            publishedExams = publishedExams,
            globalErrorRate = globalErrorRate,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- CONTENT -------------------------------------------------------

    /** Volumen de catálogo. */
    private fun contentBlock(): AdminDashboardContentDto {
        val availability = AnalyticsAvailability()

        val totalExams = availability.reading(
            DashboardOverviewMetricKey.TOTAL_EXAMS,
            countOrNull("examenes", "id"),
        )
        val totalQuestions = availability.reading(
            DashboardOverviewMetricKey.TOTAL_QUESTIONS,
            countOrNull("preguntas", "id"),
        )
        // `recursos_pregunta.activo` existe y es el borrado lógico real de la
        // tabla (`archiveRecurso` escribe `activo=false`, y la lectura del panel
        // filtra `activo=eq.true`). Contar sin ese filtro incluiría los
        // archivados, que para el catálogo no existen.
        val totalResources = availability.reading(
            DashboardOverviewMetricKey.TOTAL_RESOURCES,
            countOrNull("recursos_pregunta", "id", "activo=eq.true"),
        )
        val importBatches = availability.reading(
            DashboardOverviewMetricKey.IMPORT_BATCHES,
            countOrNull("lotes_importacion_preguntas", "id"),
        )

        return AdminDashboardContentDto(
            totalExams = totalExams,
            totalQuestions = totalQuestions,
            totalResources = totalResources,
            importBatches = importBatches,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- ACTIVITY ------------------------------------------------------

    /**
     * Cronología real, ordenada y recortada por la base.
     *
     * Si la lectura falla, esta función lanza y la franja entera se declara
     * fallida. Devolver una lista vacía sería afirmar que no ha pasado nada,
     * que es una frase distinta de "no he podido mirar".
     */
    private fun activityBlock(): AdminDashboardActivityDto {
        val rows = get("/rest/v1/auditoria_log?select=*&order=creado_en.desc&limit=$ACTIVITY_LIMIT")
            .decodeList<DashboardAuditoriaRow>()
        val entries = rows.mapNotNull { it.toActivityEntryOrNull() }
        return AdminDashboardActivityDto(
            entries = entries,
            // Lo que se cayó por el camino se cuenta. Una lista corta puede
            // significar "hay poca actividad" o "leí filas que no pude usar", y
            // esas dos frases no pueden llegar iguales.
            droppedEntries = rows.size - entries.size,
            generatedAt = Instant.now().toString(),
        )
    }

    // -- ECONOMY -------------------------------------------------------

    /** Monetización: sólo lo que hoy se puede contar de verdad, y el hueco con nombre. */
    private fun economyBlock(): AdminDashboardEconomyDto {
        val availability = AnalyticsAvailability()
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val in30d = now.plus(30, ChronoUnit.DAYS)

        // Filtros idénticos a `/analytics/economia`.
        val subscriptionsActive = availability.reading(
            DashboardOverviewMetricKey.SUBSCRIPTIONS_ACTIVE,
            countOrNull("suscripciones", "id", "estado=eq.activa"),
        )
        val subscriptionsExpiring30d = availability.reading(
            DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_30D,
            countOrNull("suscripciones", "id", "estado=eq.activa&fin_en=gte.$now&fin_en=lt.$in30d"),
        )
        val commercialProductsActive = availability.reading(
            DashboardOverviewMetricKey.COMMERCIAL_PRODUCTS_ACTIVE,
            countOrNull("productos_comerciales", "id", "activo=eq.true"),
        )

        // Viajan en el contrato aunque el panel no las pinte: un hueco con nombre
        // es deuda registrada; un hueco omitido es deuda olvidada.
        availability.gap(
            DashboardOverviewMetricKey.GROSS_REVENUE_CENTS,
            "Necesita SUM(precio_centimos) sobre compras_usuario completadas: vista " +
                "v_ingresos_brutos (periodo, total_centimos) o RPC que devuelva entero en céntimos.",
        )
        availability.gap(
            DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION,
            "Necesita SUM(saldo_tickets) sobre carteras_usuario: vista v_opositickets_en_circulacion.",
        )

        return AdminDashboardEconomyDto(
            subscriptionsActive = subscriptionsActive,
            subscriptionsExpiring30d = subscriptionsExpiring30d,
            commercialProductsActive = commercialProductsActive,
            grossRevenueCents = null,
            ticketsInCirculation = null,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- Acceso HTTP ---------------------------------------------------

    /**
     * Conteo exacto que devuelve `null` en vez de lanzar. Único punto donde un
     * fallo de lectura se convierte en ausencia de dato: `null` significa
     * siempre "no se pudo leer" y jamás "cero", que llega como `0`.
     */
    private fun countOrNull(table: String, selectColumn: String, filter: String = ""): Int? = runCatching {
        val suffix = if (filter.isBlank()) "" else "&$filter"
        exactCount("/rest/v1/$table?select=$selectColumn$suffix")
    }.getOrElse { error ->
        System.err.println("[ktor] dashboard count $table -> ${error.message?.take(160)}")
        null
    }

    /**
     * Total exacto sin transferir filas: `Prefer: count=exact` publica el total en
     * `Content-Range` y `Range: 0-0` recorta el cuerpo, de modo que el conteo no
     * depende del `max-rows` de PostgREST. El parser del encabezado se reutiliza
     * ([parseContentRangeTotal]) para no tener dos que puedan discrepar.
     */
    private fun exactCount(path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
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

    /** GET de lectura acotada. La única de este fichero es la cronología, con `limit` del servidor. */
    private fun get(path: String): String {
        val request = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
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

    companion object {
        /**
         * Cuántos hechos de auditoría trae la franja. El `limit` viaja a
         * PostgREST: la tabla se ordena y recorta en el servidor, no aquí.
         *
         * ### No lo bajes a 5
         *
         * El panel sólo pinta cinco filas (`ACTIVITY_ROWS` en `DashboardV2Screen`),
         * así que pedir ocho parece desperdicio. No lo es: **el descarte ocurre
         * después del recorte del servidor**. Una fila sin `creado_en`
         * interpretable o sin `id` se cae en
         * [DashboardAuditoriaRow.toActivityEntryOrNull], que corre aquí, ya con
         * las filas traídas. Si se pidieran cinco y dos fueran ilegibles, la
         * pantalla enseñaría tres y no habría forma de recuperar las que faltan
         * sin otra petición.
         *
         * Los tres de más son exactamente esa holgura: absorben los descartes
         * para que el `take(5)` del cliente siga llenándose. Tres filas de
         * auditoría no pesan nada; una cronología incompleta sí se nota.
         */
        internal const val ACTIVITY_LIMIT = 8

        fun fromEnvironment(json: Json): AdminDashboardOverviewGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) {
                null
            } else {
                AdminDashboardOverviewGateway(url, serviceRoleKey, json)
            }
        }
    }
}

// ---------------------------------------------------------------------
// Rutas
// ---------------------------------------------------------------------

/**
 * Contrato agregado del dashboard. Un GET, una petición por refresco.
 *
 * Se monta dentro de `authenticate("gateway-auth")`, igual que el resto de
 * `/api/v1/admin`, de modo que hereda el guardia de autorización admin.
 *
 * Convive con el `GET /api/v1/admin/dashboard` anterior sin sustituirlo: aquél
 * sigue alimentando el Home móvil hasta que tenga contrato propio.
 */
fun Route.adminDashboardOverviewRoutes(gateway: AdminDashboardOverviewGateway) {
    route("/api/v1/admin/dashboard") {
        get("/overview") {
            when (val selection = parseDashboardSections(call.request.queryParameters["sections"])) {
                is DashboardSectionsSelection.Unknown -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(
                        error = "unknown_dashboard_section",
                        message = "Franja desconocida: ${selection.values.joinToString(", ")}. " +
                            "Válidas: ${DashboardOverviewBlock.ALL.joinToString(", ")}.",
                    ),
                )
                is DashboardSectionsSelection.Selected -> call.respond(gateway.overview(selection.blocks))
            }
        }
    }
}
