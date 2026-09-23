package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

/**
 * Dashboard V2: modelo de dominio.
 *
 * Sustituye al contrato de [DashboardMetrics], que era una data class de 28
 * enteros planos más un `Set<String>` de claves no disponibles. Esa forma
 * obligaba a cada tarjeta a cruzar el campo con la lista para saber si el número
 * que tenía delante era un dato o relleno de serialización, y bastaba olvidar el
 * cruce una vez para volver a enseñar un cero inventado.
 *
 * Aquí la unidad es [AnalyticsMetric] —clave, valor y estado juntos—, **la misma
 * que ya usa la pantalla de Analítica**. No se define un `DashboardMetric`
 * paralelo a propósito: la regla que decide si una métrica se puede pintar es
 * [AnalyticsMetricPolicy], vive en un único sitio y ya tiene pruebas. Duplicarla
 * aquí sería repetir exactamente el defecto que este rediseño viene a cerrar
 * (`DashboardMetricKey` llegó a existir dos veces, en dominio y en backend, sin
 * ninguna prueba que impidiera que divergieran).
 *
 * ### Bloques independientes
 *
 * El dashboard anterior era una sola petición que orquestaba cinco repositorios,
 * dos de ellos bloqueantes: si fallaba la lectura de especialidades o exámenes,
 * la pantalla entera quedaba en error. Aquí Ktor agrega y devuelve
 * [DashboardBlock]s, cada uno con su propio estado, de modo que un bloque caído
 * pierde su franja y no la pantalla.
 */

/**
 * Las seis franjas del panel, en el orden en que se leen.
 *
 * El orden no es estético: responde a qué necesita saber el operador y en qué
 * secuencia. Primero si el sistema está en pie, después qué trabajo le espera, y
 * sólo entonces el contexto.
 */
enum class DashboardBlock {
    /** ¿Está todo en pie? Ktor, Supabase y el agregado. */
    SYSTEM,

    /** ¿Qué trabajo me espera? Contadores accionables, cada uno navegable. */
    ATTENTION,

    /** ¿Cómo va el producto? Contexto de los últimos 30 días. */
    PULSE,

    /** ¿Cuánto contenido hay? Volumen del catálogo. */
    CONTENT,

    /** ¿Qué ha pasado? Cronología real desde `auditoria_log`. */
    ACTIVITY,

    /** ¿Cómo va la monetización? Sólo lo que hoy se puede contar de verdad. */
    ECONOMY,
    ;

    /** Valor que viaja en el parámetro `?sections=` del endpoint agregado. */
    val wire: String get() = name.lowercase()

    companion object {
        /** `null` si [value] no nombra un bloque conocido; quien llama decide si eso es un 400. */
        fun fromWire(value: String): DashboardBlock? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/**
 * Claves estables de métrica del dashboard.
 *
 * Son el contrato de cable entre el backend y esta pantalla: el backend las
 * nombra en su respuesta y la UI las busca por nombre.
 *
 * **El backend tiene su propia copia de estas cadenas y no puede compartirlas.**
 * `:backend:ktor` no depende de `:domain` —ni debe: sirve también a consumidores
 * externos— así que las 17 claves viven duplicadas y un renombrado en un solo
 * lado compila sin quejarse. El fallo resultante es silencioso: `metric(key)`
 * devuelve `null`, la tarjeta desaparece de la pantalla y nada falla ni se
 * registra.
 *
 * La red que sí existe es un par de tests espejo que fijan los mismos literales
 * desde cada lado —`DashboardOverviewContractTest` aquí y su equivalente en
 * `:backend:ktor`—, de modo que cualquier renombrado rompe uno de los dos. Es lo
 * máximo que permite la separación de módulos, y es suficiente.
 */
object DashboardMetricKeys {
    // --- ATTENTION: cada una debe poder abrir la pantalla que la resuelve ---

    /** Reportes de contenido sin resolver. */
    const val REPORTS_PENDING = "reportsPending"

    /** Dudas de usuarios sobre preguntas, sin responder (OposiTester). */
    const val QUESTION_FEEDBACK_PENDING = "questionFeedbackPending"

    /**
     * Exámenes ACTIVOS y sin publicar (PC-01). Los archivados no son borradores:
     * antes se contaban como "total menos publicados" y los incluían.
     */
    const val DRAFT_EXAMS = "draftExams"

    /**
     * Suscripciones activas que vencen en los próximos 7 días.
     *
     * Ventana corta a propósito: es la que cabe en una semana de trabajo y por
     * tanto la única accionable. El horizonte de 30 días vive en
     * [SUBSCRIPTIONS_EXPIRING_30D], dentro de ECONOMY, donde es contexto.
     */
    const val SUBSCRIPTIONS_EXPIRING_7D = "subscriptionsExpiring7d"

    // --- PULSE: contexto de 30 días ---

    /**
     * Usuarios DISTINTOS con un intento de examen iniciado o un evento de app
     * recibido por el servidor en los últimos 30 días (PC-02, RPC
     * `admin_dashboard_active_users`). La definición se enseña en la ayuda de la
     * tarjeta: "activo" no significa lo mismo en todos los productos.
     */
    const val ACTIVE_USERS_30D = "activeUsers30d"
    const val ATTEMPTS_LAST_30D = "attemptsLast30d"
    const val PUBLISHED_EXAMS = "publishedExams"
    const val GLOBAL_ERROR_RATE = "globalErrorRate"

    // --- CONTENT: volumen de catálogo ---

    /** Exámenes activos del catálogo, borradores incluidos; sin los archivados (PC-05). */
    const val TOTAL_EXAMS = "totalExams"

    /** Preguntas activas del banco; sin las archivadas (PC-05). */
    const val TOTAL_QUESTIONS = "totalQuestions"
    const val TOTAL_RESOURCES = "totalResources"
    const val IMPORT_BATCHES = "importBatches"

    // --- ECONOMY ---

    /** Suscripciones en estado activa Y vigentes: sin fin o con fin futuro (PC-03). */
    const val SUBSCRIPTIONS_ACTIVE = "subscriptionsActive"

    /** Horizonte de contexto. La alerta operativa es [SUBSCRIPTIONS_EXPIRING_7D]. */
    const val SUBSCRIPTIONS_EXPIRING_30D = "subscriptionsExpiring30d"

    const val COMMERCIAL_PRODUCTS_ACTIVE = "commercialProductsActive"

    /**
     * Ingresos en céntimos: suma HISTÓRICA de las compras en estado `pagada`, en EUR
     * (PC-11, RPC `admin_dashboard_economy_totals`). No descuenta nada porque las
     * reembolsadas o canceladas no están en `pagada`.
     *
     * Llega NO_DATA —nunca 0— cuando la base declara la suma incalculable: alguna
     * compra pagada sin precio o en otra moneda. Y NO_DATA con
     * [AnalyticsMetric.readFailed] cuando la llamada falló. Hasta PC-11 viajaba
     * siempre como `NOT_INSTRUMENTED`, porque PostgREST no expresa SUM.
     */
    const val GROSS_REVENUE_CENTS = "grossRevenueCents"

    /**
     * OposiTickets en circulación: suma de los saldos de todas las carteras (PC-11).
     * Es un CONTEO de moneda interna, nunca dinero.
     */
    const val TICKETS_IN_CIRCULATION = "ticketsInCirculation"
}

/**
 * Una entrada real de la cronología de auditoría.
 *
 * Todos sus campos vienen de una fila de `auditoria_log`, incluido
 * [occurredAt], que es `creado_en` y no el instante de la petición. La versión
 * anterior del dashboard construía tres "eventos recientes" derivados del estado
 * actual y les ponía `Clock.System.now()` a los tres, así que decían haber
 * ocurrido a la vez y se rejuvenecían solos cada cinco minutos.
 */
data class DashboardActivityEntry(
    val id: String,
    /** Acción cruda tal como la escribió el backend, p. ej. `examen_publicado`. */
    val action: String,
    /** Tabla afectada. `null` cuando la acción no apunta a una tabla concreta. */
    val targetTable: String?,
    /** Id del registro afectado. `null` cuando la acción no apunta a una fila. */
    val targetId: String?,
    /** `auditoria_log.creado_en`. Fecha real del hecho, nunca la de la lectura. */
    val occurredAt: Instant,
)

/**
 * Salud del sistema, tal como la resume Ktor.
 *
 * Reutiliza [SystemStatusLevel] —el mismo vocabulario Operativo/Degradado/Caído/
 * Desconocido que ya usa la pantalla de Estado del sistema— para que un
 * componente no esté "operativo" en una pantalla y "ok" en otra.
 *
 * [notInstrumented] nombra lo que todavía no tiene fuente (worker, CPU, latencia
 * general de la API, errores de la API, errores de app, cola). Se declara en vez de
 * omitirse porque un panel que pinta todo en verde por no saber nada es peor que uno
 * que admite no saberlo.
 *
 * PC-14: la clave `mobileCrashes24h` NO mide crashes. Lo único que existe para
 * aproximarla es `errores_app` (errores que la propia app reporta, con severidad), y
 * si algún día se pinta tiene que llamarse por lo que es: "errores críticos de app
 * en 24 h", no "crashes".
 */
data class DashboardHealth(
    val overall: SystemStatusLevel,
    val ktor: SystemStatusLevel,
    val supabase: SystemStatusLevel,
    /** Momento en que Ktor compuso el estado. `null` si no llegó. */
    val updatedAt: Instant?,
    /**
     * Milisegundos que tardó la sonda de Supabase, medidos por Ktor.
     *
     * Es un dato real y ya calculado: `getSystemStatus` cronometra su lectura
     * mínima y usa el resultado para decidir si Supabase está OPERATIVO o
     * DEGRADADO. La primera versión de este modelo lo descartaba, y descartar una
     * medición que el backend ya hace es la misma clase de pérdida que rellenar
     * un hueco con un cero, sólo que en la otra dirección.
     *
     * No es la latencia general de la API —eso sigue sin instrumentar y necesita
     * Prometheus o un contador propio—, así que quien lo pinte debe nombrarlo por
     * lo que es: el tiempo de respuesta de Supabase a una sonda.
     */
    val supabaseLatencyMs: Long? = null,
    val notInstrumented: List<String> = emptyList(),
)

/**
 * Lo que el panel enseña de una franja.
 *
 * [metrics] la usan todas; [activity] sólo [DashboardBlock.ACTIVITY]; [health]
 * sólo [DashboardBlock.SYSTEM]. Se modelan como campos opcionales de un mismo
 * tipo, y no como una jerarquía sellada por bloque, porque la pantalla trata a
 * los seis igual —cada uno es un `UiAsync` que se carga, falla y se reintenta por
 * separado— y una jerarquía obligaría a un `when` exhaustivo en cada punto del
 * ViewModel sin añadir ninguna garantía real.
 */
data class DashboardBlockReport(
    val block: DashboardBlock,
    val metrics: List<AnalyticsMetric> = emptyList(),
    val activity: List<DashboardActivityEntry> = emptyList(),
    val health: DashboardHealth? = null,
    /**
     * Cuántas filas de auditoría se leyeron pero no se pudieron entregar, por
     * fecha o identificador ilegibles. Sólo [DashboardBlock.ACTIVITY].
     *
     * Descartar esas filas es correcto —una entrada sin fecha no se puede situar
     * en una cronología, y sellarla con la hora de lectura sería inventar—, pero
     * descartarlas *en silencio* deja «leí ocho filas y ninguna era legible» y «no
     * hay nada registrado» indistinguibles en pantalla. Es la misma ambigüedad
     * que un cero por fallo de lectura, sólo que en la lista en vez de en la
     * cifra, así que se cuenta y se dice.
     *
     * Con este valor > 0, una lista vacía **no** puede rotularse «todavía no ha
     * pasado nada».
     */
    val droppedActivityEntries: Int = 0,
    /** Momento en que Ktor compuso este bloque. */
    val generatedAt: Instant,
) {
    fun metric(key: String): AnalyticsMetric? = metrics.firstOrNull { it.key == key }

    /**
     * Métricas que el dashboard debe pintar.
     *
     * Deja fuera las `NOT_INSTRUMENTED` y conserva las `NO_DATA`. La distinción es
     * deliberada y es una decisión de producto confirmada: una métrica que no se
     * pudo leer **sí** ocupa sitio, porque reintentar puede arreglarla y el
     * operador necesita saber que ese número le falta; una métrica que no existe
     * porque nadie ha construido el agregado no le sirve de nada mientras tanto y
     * sólo llenaría la pantalla de huecos con nombre.
     *
     * Que el hueco no se pinte no significa que se oculte: viaja en [metrics], se
     * puede inspeccionar y está registrado como deuda.
     */
    val renderable: List<AnalyticsMetric>
        get() = metrics.filter { it.status != AnalyticsMetricStatus.NOT_INSTRUMENTED }

    /** Cuántas métricas de la franja traen número, incluido un cero legítimo. */
    val availableCount: Int
        get() = metrics.count { it.status == AnalyticsMetricStatus.AVAILABLE }
}

/**
 * Respuesta completa del contrato agregado.
 *
 * [failedBlocks] no es lo mismo que un bloque ausente en [blocks]: un bloque que
 * no se pidió (`?sections=`) simplemente no viene, mientras que uno que se pidió
 * y Ktor no pudo componer aparece aquí nombrado. Sin esa distinción, "no lo
 * pediste" y "lo pedí y se rompió" llegarían a la pantalla idénticos, que es la
 * misma clase de ambigüedad que el cero por fallo de lectura.
 */
data class DashboardOverview(
    val blocks: Map<DashboardBlock, DashboardBlockReport>,
    val failedBlocks: Set<DashboardBlock> = emptySet(),
    val generatedAt: Instant,
)
