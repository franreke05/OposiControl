package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** Fallos de lectura y de franja del panel (F-12b): con el `request_id` del MDC. */
private val DashboardOverviewLogger = LoggerFactory.getLogger("OC-KTOR-DASHBOARD")

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
 * sus piezas ([AnalyticsAvailability], [analyticsRate], [activeSubscriptionsFilter],
 * [AnalyticsGapDto]) en vez de reescribirlas:
 *
 * 1. **Ninguna métrica se inventa.** Lo que no tiene número viaja como `null` con
 *    su clave en `unavailableMetrics`, nunca como `0`. Si además la LECTURA FALLÓ,
 *    la clave va también en `failedMetrics` (PC-04): "no se pudo leer" y "no hay
 *    nada que medir" no pueden llegar iguales a la pantalla.
 * 2. **Contar se cuenta en el servidor**, con `Prefer: count=exact` y `Range: 0-0`.
 * 3. **Lo que PostgREST no sabe hacer, no se finge**: SUM no existe en PostgREST,
 *    así que las dos sumas del panel (ingresos y tickets) y el recuento de usuarios
 *    distintos se piden a RPC de sólo lectura (`admin_dashboard_economy_totals`,
 *    `admin_dashboard_active_users`, sólo `service_role`) en vez de aproximarse.
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
 *
 * Los cuatro estados de una métrica en el cable (PC-04):
 *
 * | Estado            | campo    | unavailableMetrics | failedMetrics | notInstrumented |
 * |-------------------|----------|--------------------|---------------|-----------------|
 * | cero real / dato  | número   | no                 | no            | no              |
 * | NO_DATA           | ausente  | sí                 | no            | no              |
 * | FAILED            | ausente  | sí                 | sí            | no              |
 * | NOT_INSTRUMENTED  | ausente  | sí                 | no            | sí, con motivo  |
 *
 * `failedMetrics` es siempre un SUBCONJUNTO de `unavailableMetrics`, a propósito:
 * un cliente que todavía no conozca la lista nueva pinta "sin dato", nunca un cero.
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
    /** Claves cuya LECTURA falló. Subconjunto de [unavailableMetrics] (PC-04). */
    val failedMetrics: List<String>,
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
    /** Claves cuya LECTURA falló. Subconjunto de [unavailableMetrics] (PC-04). */
    val failedMetrics: List<String>,
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
    /** Claves cuya LECTURA falló. Subconjunto de [unavailableMetrics] (PC-04). */
    val failedMetrics: List<String>,
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
     * Céntimos enteros — nunca `Double`, que redondearía el dinero. Suma HISTÓRICA
     * de compras en estado `pagada` en EUR, leída de `admin_dashboard_economy_totals`
     * (PC-11). La RPC la devuelve `NULL` a propósito si alguna compra pagada no
     * tiene precio o no es en EUR: esa suma se quedaría corta sin avisar, y aquí
     * viaja como NO_DATA, nunca como `0`.
     */
    val grossRevenueCents: Long?,
    /** Suma de `carteras_usuario.saldo_tickets`. Es un CONTEO de OposiTickets, no dinero. */
    val ticketsInCirculation: Long?,
    val generatedAt: String,
    val unavailableMetrics: List<String>,
    /** Claves cuya LECTURA falló. Subconjunto de [unavailableMetrics] (PC-04). */
    val failedMetrics: List<String>,
    val notInstrumented: List<AnalyticsGapDto>,
)

// ---------------------------------------------------------------------
// Filas de entrada (Supabase -> backend)
// ---------------------------------------------------------------------

/**
 * La fila de `admin_dashboard_economy_totals()` (PC-11).
 *
 * Sin valores por defecto a propósito: si falta una columna, decodificar FALLA y la
 * métrica queda como lectura fallida, en vez de rellenarse con un default que
 * parezca un dato. `grossRevenueCents` admite `null` porque la RPC lo devuelve así
 * cuando la suma no es fiable; `paid_purchases` viaja pero el panel no lo usa.
 */
@Serializable
internal data class DashboardEconomyTotalsRow(
    @SerialName("gross_revenue_cents") val grossRevenueCents: Long?,
    @SerialName("tickets_in_circulation") val ticketsInCirculation: Long,
)

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
    supabaseUrl: String,
    serviceRoleKey: String,
    private val json: Json,
    /**
     * `internal` sólo para poder sustituir el upstream en pruebas: sin esa
     * costura, "¿y si Supabase falla?" volvería a ser una regla escrita
     * únicamente en un comentario (misma decisión que en [AdminAnalyticsGateway]).
     */
    client: HttpClient = sharedUpstreamHttpClient,
) {
    /**
     * Migrado a [SupabaseRequestTransport] (FASE 1).
     *
     * Sus dos funciones de transporte eran copia literal de las de otros seis
     * ficheros, incluidas las dos líneas de credencial concatenadas a mano.
     * Al pasar por el transporte, además, este gateway gana la traducción de
     * errores de dominio que no llamaba desde ningún sitio.
     */
    private val transport = SupabaseRequestTransport(
        baseUrl = supabaseUrl,
        auth = SupabaseRequestAuthPolicy(serviceRoleKey),
        client = client,
    )

    /**
     * Decodificador PROPIO de las respuestas de RPC, con reglas fijas: ignora
     * columnas nuevas (añadir una a la función no puede tumbar el panel) pero una
     * columna AUSENTE falla la lectura en vez de leerse como `null`. No depende de
     * la configuración del `json` que reciba el gateway, que en el servidor lleva
     * `explicitNulls = false` y convertiría un campo desaparecido en un NO_DATA
     * silencioso.
     */
    private val rpcJson = Json { ignoreUnknownKeys = true }

    /**
     * Compone las franjas pedidas, tolerando que alguna no se pueda armar.
     *
     * `sections` vacío significa todas. Cada franja se compone dentro de
     * [block]: un fallo la nombra en `failedBlocks` y deja intactas las demás.
     *
     * ### La garantía que ninguna optimización puede tocar
     *
     * **Cada franja degrada por separado.** Un `try/catch` por franja; si una
     * lanza, se nombra en `failedBlocks` y **las otras cinco se sirven igual**.
     * Nunca un 500 porque una franja falló. Esa garantía atraviesa las tres
     * capas —`failedBlocks` sin default aquí, `AppResult.Failure` reservado al
     * fallo de la petición entera en el repositorio, y un `UiAsync` POR FRANJA
     * en la pantalla— y cualquier cambio que acople dos franjas la rompe, por
     * rápido que sea.
     *
     * ### Qué se paraleliza, y qué NO, y por qué
     *
     * **Sí: las cinco franjas no-SYSTEM entre sí.** Son independientes por
     * construcción —ningún valor de una entra en otra—, así que lanzarlas a la
     * vez no cambia ni un campo del resultado. El peor caso teórico pasa de la
     * suma de 19 peticiones (~380 s de hilo) al máximo de los cinco grupos
     * (~100 s). Cada `try/catch` sigue aislando su franja exactamente igual.
     *
     * **No: SYSTEM. Se ejecuta SOLA, antes que las otras cinco.** Y no es
     * prudencia, es que **su latencia medida es el dato que publica**
     * ([AdminDashboardHealthDto.supabaseLatencyMs]). Lanzada junto a las otras
     * 18 peticiones contra el mismo `HttpClient`, esa cifra deja de medir
     * «cuánto tarda Supabase» y pasa a medir «cuánto tarda Supabase con 18
     * peticiones encima» — una métrica que dice una cosa y mide otra es peor
     * que no tenerla. Es una franja de UNA petición: serializarla no cuesta
     * nada. (Es además cómo ya se ejecuta en el refresco de 60 s del panel,
     * `?sections=system`.)
     *
     * **No: compartir lecturas entre franjas.** Si alguna vez dos franjas
     * necesitan la misma cifra, se pide dos veces: compartir el resultado cuesta
     * la garantía de arriba, porque en cuanto dos franjas comparten una petición
     * el fallo de esa petición tumba las dos. (Hoy ya no hay repetidas: PC-01
     * cuenta los borradores con su propio filtro y PC-05 filtra el catálogo por
     * activos, así que las dos lecturas que antes se duplicaban dejaron de ser la
     * misma consulta.)
     *
     * Fan-out de un refresco completo: **19** peticiones — SYSTEM 1, ATTENTION 4,
     * PULSE 5 (una es la RPC de usuarios activos), CONTENT 4, ACTIVITY 1 y
     * ECONOMY 4 (una es la RPC de totales). El número coincide con el de antes con
     * otra composición, y por eso `IndependentDashboardFanoutContractTest` fija
     * el conjunto de rutas y no sólo la cuenta.
     *
     * **No: cachear**, ni entre franjas ni entre peticiones. `generatedAt`
     * significa «cuándo compuso Ktor este bloque», y de ahí cuelga toda la
     * honestidad temporal del panel.
     */
    suspend fun overview(sections: Set<String> = DashboardOverviewBlock.ALL): AdminDashboardOverviewDto {
        val requested = sections.ifEmpty { DashboardOverviewBlock.ALL }
        // Set concurrente: ahora hay cinco franjas escribiendo aquí a la vez.
        // Un `linkedSetOf` compartido entre cinco corrutinas podría perder un
        // nombre, y perder un nombre significa servir una franja nula que el
        // cliente no sabe que falló — el silencio exacto que `failedBlocks`
        // existe para eliminar. El orden no se pierde: se reordena por ALL.
        val failed = ConcurrentHashMap.newKeySet<String>()

        /**
         * Compone una franja; si lanza, la marca y devuelve null en vez de
         * tumbar la respuesta.
         *
         * `try/catch` con re-lanzado explícito y NO `runCatching`:
         * `runCatching` captura `Throwable`, cancelación incluida. Si el
         * cliente se va a mitad del refresco, tragarse la `CancellationException`
         * convertiría la cancelación en una franja «fallida» —falseando
         * `failedBlocks`— y dejaría las otras cuatro corriendo contra una
         * respuesta que ya no escucha nadie. La cancelación es del llamante, no
         * un fallo de la franja.
         */
        fun <T : Any> block(name: String, compose: () -> T): T? {
            if (name !in requested) return null
            return try {
                compose()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // F-12b: logger + evento, no `System.err`. Esto corre dentro de
                // `withUpstreamIo`/`asyncUpstreamIo`, así que el MDC ya lleva el
                // `request_id` de la petición y la línea se puede correlacionar con
                // la franja fallida que ve el operador.
                DashboardOverviewLogger.warn(
                    "dashboard overview block {} failed: {}",
                    name,
                    error.redactedMessage("Fallo al componer la franja", 160),
                )
                logStrategicEvent(
                    StrategicEvent.SUPABASE_REQUEST_FAILED,
                    operation = "dashboard_overview_$name",
                    result = "block_failed",
                    errorType = error::class.simpleName,
                )
                failed += name
                null
            }
        }

        /** Una franja no pedida no se lanza: «no se pidió» y «falló» no pueden llegar iguales. */
        fun <T : Any> UpstreamIoScope.franja(name: String, compose: () -> T): Deferred<T?>? =
            if (name in requested) asyncUpstreamIo { block(name, compose) } else null

        // SYSTEM, sola y antes que nadie. El `await` implícito de esta línea es
        // lo que garantiza que su cronómetro no comparta el cliente HTTP con
        // las 18 peticiones de las otras cinco franjas.
        val system = if (DashboardOverviewBlock.SYSTEM in requested) {
            withUpstreamIo { block(DashboardOverviewBlock.SYSTEM) { systemBlock() } }
        } else {
            null
        }

        return withUpstreamIoParallel {
            val attention = franja(DashboardOverviewBlock.ATTENTION) { attentionBlock() }
            val pulse = franja(DashboardOverviewBlock.PULSE) { pulseBlock() }
            val content = franja(DashboardOverviewBlock.CONTENT) { contentBlock() }
            val activity = franja(DashboardOverviewBlock.ACTIVITY) { activityBlock() }
            val economy = franja(DashboardOverviewBlock.ECONOMY) { economyBlock() }

            // Los `await` van a vals y no dentro del constructor: así no
            // dependemos del orden de evaluación de los argumentos para que
            // `failedBlocks` se lea DESPUÉS de que las cinco hayan terminado.
            val attentionDto = attention?.await()
            val pulseDto = pulse?.await()
            val contentDto = content?.await()
            val activityDto = activity?.await()
            val economyDto = economy?.await()

            AdminDashboardOverviewDto(
                system = system,
                attention = attentionDto,
                pulse = pulseDto,
                content = contentDto,
                activity = activityDto,
                economy = economyDto,
                // Se ordena como ALL para que el orden no dependa de en qué franja falló primero
                // — que ahora, además, sería no determinista.
                failedBlocks = DashboardOverviewBlock.ALL.filter { it in failed },
                // Se sella al final, cuando el bloque está compuesto de verdad.
                generatedAt = Instant.now().toString(),
            )
        }
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
                    // PC-14 (sólo texto): la clave se llama `mobileCrashes24h`, pero
                    // lo único que hoy existe para medirlo es `errores_app`, y eso NO
                    // son crashes: son errores que la app decide reportar, con su
                    // severidad. Si algún día se instrumenta, lo honesto es "errores
                    // de app de severidad crítica en las últimas 24 h"
                    // (`errores_app?severidad=eq.critica&creado_en=gte.<ahora-24h>`),
                    // y la pantalla tendrá que llamarlo así, no "crashes". Mientras,
                    // sigue declarada sin fuente y sin lectura.
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

        // PC-01. Borrador = ACTIVO y SIN publicar, contado directamente. Se
        // calculaba como "total menos publicados", y esa resta metía en
        // "borradores" los ARCHIVADOS (`activo=false`, que es lo que escribe
        // archivar): en MAIN la tarjeta decía 6 borradores y había 0 — los seis
        // eran exámenes archivados. Misma definición que la pantalla de Exámenes
        // (activo y sin `publicado_en`), y una sola lectura en vez de dos.
        val draftExams = availability.reading(
            DashboardOverviewMetricKey.DRAFT_EXAMS,
            countOrNull("examenes", "id", "activo=eq.true&publicado_en=is.null"),
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
            failedMetrics = availability.failed(),
            notInstrumented = availability.gaps(),
        )
    }

    // -- PULSE ---------------------------------------------------------

    /** Contexto de 30 días. Ninguna cifra de aquí es accionable por sí sola. */
    private fun pulseBlock(): AdminDashboardPulseDto {
        val availability = AnalyticsAvailability()
        val since30d = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()

        // PC-02. Se contaba `rachas_usuario`, una tabla SIN ESCRITOR (ninguna
        // función ni trigger la rellena): la cifra era un 0 estructural que no
        // podía cambiar nunca, pintado como dato. Ahora la cuenta la RPC
        // `admin_dashboard_active_users`: usuarios DISTINTOS con un intento de
        // examen iniciado o un evento de app recibido en los últimos 30 días.
        // Cualquier fallo de la llamada es FAILED, nunca 0.
        val activeUsers30d = availability.reading(
            DashboardOverviewMetricKey.ACTIVE_USERS_30D,
            activeUsers30dOrNull(),
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
        //
        // PC-04: `rate` y no `reading(analyticsRate(...))`. Con cero respuestas
        // la tasa no existe todavía (NO_DATA), y eso es distinto de que una de
        // las dos lecturas haya fallado (FAILED). Antes las dos cosas llegaban
        // iguales y la tarjeta no podía decir cuál de las dos pasaba.
        val totalAnswers = countOrNull("respuestas_intento", "id")
        val wrongAnswers = countOrNull("respuestas_intento", "id", "es_correcta=eq.false")
        val globalErrorRate = availability.rate(
            DashboardOverviewMetricKey.GLOBAL_ERROR_RATE,
            numerator = wrongAnswers,
            denominator = totalAnswers,
        )

        return AdminDashboardPulseDto(
            activeUsers30d = activeUsers30d,
            attemptsLast30d = attemptsLast30d,
            publishedExams = publishedExams,
            globalErrorRate = globalErrorRate,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            failedMetrics = availability.failed(),
            notInstrumented = availability.gaps(),
        )
    }

    /**
     * `admin_dashboard_active_users(30)`, o `null` si la llamada no devuelve un entero.
     *
     * La RPC devuelve un ESCALAR (`0`), no una fila ni un array: cualquier otra forma
     * —un array, un texto, `null`— es un contrato roto y cuenta como fallo, igual que
     * un error HTTP (función inexistente, sin permiso, timeout). `p_days` va fijo a
     * 30: la función acota en silencio los valores fuera de 1..366, así que un
     * error de este lado no se vería allí.
     */
    private fun activeUsers30dOrNull(): Int? = try {
        val body = transport.rpc(ACTIVE_USERS_RPC, """{"p_days":$ACTIVE_USERS_WINDOW_DAYS}""")
        val scalar = json.parseToJsonElement(body) as? JsonPrimitive
        scalar?.takeUnless { it.isString }?.intOrNull
            ?: error("$ACTIVE_USERS_RPC no devolvió un entero")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logReadFailure(ACTIVE_USERS_RPC, error)
        null
    }

    // -- CONTENT -------------------------------------------------------

    /** Volumen de catálogo. */
    private fun contentBlock(): AdminDashboardContentDto {
        val availability = AnalyticsAvailability()

        // PC-05: el catálogo cuenta lo ACTIVO, como ya hacía `totalResources`.
        // Sin filtro, "Exámenes · En el catálogo" decía 7 cuando 6 estaban
        // archivados y no aparecen en la pantalla a la que lleva la fila. Ojo:
        // `examenes` se filtra por `activo` y `preguntas` por `activa` — el
        // nombre de la columna no es el mismo, y un filtro con el nombre
        // equivocado da 400 y dejaría la cifra en "sin dato" para siempre.
        // Los borradores (activos sin publicar) SÍ cuentan: están en el catálogo
        // aunque todavía no los vea nadie.
        val totalExams = availability.reading(
            DashboardOverviewMetricKey.TOTAL_EXAMS,
            countOrNull("examenes", "id", "activo=eq.true"),
        )
        val totalQuestions = availability.reading(
            DashboardOverviewMetricKey.TOTAL_QUESTIONS,
            countOrNull("preguntas", "id", "activa=eq.true"),
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
            failedMetrics = availability.failed(),
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
        // PC-06: sólo las columnas que se entregan. `select=*` traía además
        // `antes`/`despues` (jsonb con filas completas), `ip`, `user_agent` y
        // `usuario_id`, que la franja no usa y que no tienen por qué salir de
        // la base para pintar cinco líneas de cronología.
        val rows = get(
            "/rest/v1/auditoria_log?select=$ACTIVITY_COLUMNS&order=creado_en.desc&limit=$ACTIVITY_LIMIT",
        ).decodeList<DashboardAuditoriaRow>()
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

    /** Monetización: sólo lo que hoy se puede contar de verdad. */
    private fun economyBlock(): AdminDashboardEconomyDto {
        val availability = AnalyticsAvailability()
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val in30d = now.plus(30, ChronoUnit.DAYS)

        // Filtros idénticos a `/analytics/economia`.
        //
        // PC-03: "activas" = estado `activa` Y vigentes (sin fin o con fin futuro).
        // Con sólo `estado=eq.activa`, la tarjeta contaba en MAIN una suscripción
        // que venció hace 75 días y que nadie cerró.
        val subscriptionsActive = availability.reading(
            DashboardOverviewMetricKey.SUBSCRIPTIONS_ACTIVE,
            countOrNull("suscripciones", "id", activeSubscriptionsFilter(now)),
        )
        val subscriptionsExpiring30d = availability.reading(
            DashboardOverviewMetricKey.SUBSCRIPTIONS_EXPIRING_30D,
            countOrNull("suscripciones", "id", "estado=eq.activa&fin_en=gte.$now&fin_en=lt.$in30d"),
        )
        val commercialProductsActive = availability.reading(
            DashboardOverviewMetricKey.COMMERCIAL_PRODUCTS_ACTIVE,
            countOrNull("productos_comerciales", "id", "activo=eq.true"),
        )

        // PC-11. Las dos sumas que eran huecos declarados salen ahora de UNA
        // llamada a `admin_dashboard_economy_totals`. Tres salidas, y cada una
        // con su estado:
        //
        //   · La llamada falla (función inexistente, permiso, red, forma
        //     inesperada) → las DOS métricas FAILED. Nunca 0.
        //   · `gross_revenue_cents` llega `null` → NO_DATA. La RPC lo devuelve
        //     así A PROPÓSITO cuando alguna compra pagada no tiene precio o no es
        //     en EUR: la suma existiría, pero se quedaría corta sin avisar.
        //   · Llega un número → dato, incluido un 0 legítimo.
        //
        // Los tickets son un CONTEO de moneda interna, no dinero: el DTO los
        // lleva en `Long` y el cliente los pinta como número, nunca con `€`.
        val totals: DashboardEconomyTotalsRow? = economyTotalsOrNull()
        val grossRevenueCents: Long? = if (totals == null) {
            availability.reading<Long>(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS, null)
        } else {
            val revenue: Long? = totals.grossRevenueCents
            if (revenue == null) availability.noData(DashboardOverviewMetricKey.GROSS_REVENUE_CENTS)
            revenue
        }
        val ticketsInCirculation: Long? = availability.reading(
            DashboardOverviewMetricKey.TICKETS_IN_CIRCULATION,
            totals?.ticketsInCirculation,
        )

        return AdminDashboardEconomyDto(
            subscriptionsActive = subscriptionsActive,
            subscriptionsExpiring30d = subscriptionsExpiring30d,
            commercialProductsActive = commercialProductsActive,
            grossRevenueCents = grossRevenueCents,
            ticketsInCirculation = ticketsInCirculation,
            generatedAt = Instant.now().toString(),
            unavailableMetrics = availability.unavailable(),
            failedMetrics = availability.failed(),
            notInstrumented = availability.gaps(),
        )
    }

    /**
     * La única fila de `admin_dashboard_economy_totals()`, o `null` si la llamada falla.
     *
     * La RPC es `RETURNS TABLE`, así que PostgREST responde un ARRAY de una fila. Cero
     * filas, más de una, o una fila sin `tickets_in_circulation` no son "sin datos":
     * son un contrato roto, y cuentan como fallo.
     */
    private fun economyTotalsOrNull(): DashboardEconomyTotalsRow? = try {
        rpcJson.decodeFromString<List<DashboardEconomyTotalsRow>>(transport.rpc(ECONOMY_TOTALS_RPC, "{}"))
            .singleOrNull()
            ?: error("$ECONOMY_TOTALS_RPC no devolvió exactamente una fila")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logReadFailure(ECONOMY_TOTALS_RPC, error)
        null
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
        logReadFailure(table, error)
        null
    }

    /**
     * F-12b: una lectura que falla deja rastro con el `request_id` (el MDC lo pone la
     * costura de IO) y un evento contable, no una línea en `System.err`. El mensaje
     * pasa por la redacción: el cuerpo de PostgREST puede traer `hint`s de esquema.
     */
    private fun logReadFailure(source: String, error: Throwable) {
        DashboardOverviewLogger.warn(
            "dashboard read {} failed: {}",
            source,
            error.redactedMessage("Fallo de lectura", 160),
        )
        logStrategicEvent(
            StrategicEvent.SUPABASE_REQUEST_FAILED,
            operation = "dashboard_read_$source",
            result = "read_failed",
            errorType = error::class.simpleName,
        )
    }

    /**
     * Total exacto sin transferir filas: `Prefer: count=exact` publica el total en
     * `Content-Range` y `Range: 0-0` recorta el cuerpo, de modo que el conteo no
     * depende del `max-rows` de PostgREST. El parser del encabezado se reutiliza
     * ([parseContentRangeTotal]) para no tener dos que puedan discrepar.
     */
    private fun exactCount(path: String): Int = transport.count(path)

    /** GET de lectura acotada. La única de este fichero es la cronología, con `limit` del servidor. */
    private fun get(path: String): String = transport.get(path)

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

        /** Columnas de la cronología (PC-06): exactamente las que se entregan. */
        internal const val ACTIVITY_COLUMNS = "id,accion,tabla_afectada,registro_id,creado_en"

        /** RPC de usuarios activos (PC-02). Sólo `service_role` puede ejecutarla. */
        internal const val ACTIVE_USERS_RPC = "admin_dashboard_active_users"

        /** Ventana de "usuarios activos". Coincide con la tarjeta: "Últimos 30 días". */
        internal const val ACTIVE_USERS_WINDOW_DAYS = 30

        /** RPC de totales económicos (PC-11). Sólo `service_role` puede ejecutarla. */
        internal const val ECONOMY_TOTALS_RPC = "admin_dashboard_economy_totals"

        /**
         * [environment] existe para probar el arranque con un entorno inyectado
         * (`unconfiguredSupabaseGateways`, F-01) sin tocar el del proceso.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): AdminDashboardOverviewGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            // Misma firma y mismo contrato de `null`: `Application.kt` no cambia.
            // Lo único que cambia es QUÉ variable se lee, y eso lo decide un
            // solo sitio (`SUPABASE_SECRET_KEY`, con la legacy como fallback).
            val secretKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            return if (url.isBlank() || secretKey.isBlank()) {
                null
            } else {
                AdminDashboardOverviewGateway(url, secretKey, json)
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
                // PC-08 / F-09: el mensaje nombra las franjas VÁLIDAS y nunca repite
                // lo que escribió el cliente. `?sections=` es texto libre de la URL y
                // volvía entero en el cuerpo del 400.
                is DashboardSectionsSelection.Unknown -> call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(
                        error = "unknown_dashboard_section",
                        message = "Franja desconocida. Válidas: ${DashboardOverviewBlock.ALL.joinToString(", ")}.",
                    ),
                )
                // El salto al pool IO ya NO se hace aquí: lo hace `overview`
                // por dentro, con `withUpstreamIo` para la franja SYSTEM y
                // `withUpstreamIoParallel` para las otras cinco.
                //
                // Un refresco completo hace 19 peticiones upstream BLOQUEANTES.
                // Ejecutadas en el hilo de request de Netty —cuyo pool está
                // dimensionado para trabajo no bloqueante— el peor caso teórico
                // eran 19 × 20 s de hilo ocupado. El riesgo no es la lentitud:
                // es la inanición del pool, que deja a Ktor sin aceptar
                // peticiones nuevas, `/health/ready` incluida.
                //
                // Envolver aquí ya no valdría: un solo `withContext` no puede
                // repartir las cinco franjas, y el reparto tiene que ocurrir
                // donde se sabe qué franja es cuál. `overview` es la ÚNICA
                // función `suspend` del gateway, y lo es exactamente por eso —
                // no es el principio de convertir los 13 gateways a `suspend`.
                is DashboardSectionsSelection.Selected -> {
                    val overview = gateway.overview(selection.blocks)
                    // D3-15 (FA13): el resumen es del instante en que se pide; nunca se cachea.
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(overview)
                }
            }
        }
    }
}
