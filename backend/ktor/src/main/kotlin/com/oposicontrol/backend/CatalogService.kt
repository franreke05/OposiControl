package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// Servicio de catalogo y derechos (SLICE 2): D2-01, D2-02, D2-13, D7-02. Reglas de negocio aqui; la ruta solo hace HTTP
// y la pasarela solo lee. UNA politica ([EntitlementPolicy]) decide el acceso de las tres lecturas de examenes, con los
// hechos cargados POR LOTES para la pagina (nada de N+1).
//
// Vocabulario del cable: `openapi/catalog/catalog.yaml` y `openapi/entitlements/entitlements.yaml` (CONGELADOS).
// =====================================================================

private val CatalogLogger = LoggerFactory.getLogger("OC-KTOR-CATALOG")

// ---------- cable (respuestas) ----------

@Serializable
internal data class AppExamSummary(
    val id: String,
    val titulo: String,
    val tipoExamen: String,
    val tipoAcceso: String,
    val especialidadId: String,
    val comunidadCodigo: String? = null,
    val totalPreguntas: Int,
    val anio: Int? = null,
    val duracionMinutos: Int? = null,
    val portadaUrl: String? = null,
    val accessState: AccessState,
)

@Serializable
internal data class AppExamFacets(val comunidades: List<String>, val anios: List<Int>, val tipos: List<String>)

@Serializable
internal data class AppExamPage(
    val items: List<AppExamSummary>,
    val nextCursor: String? = null,
    val facets: AppExamFacets? = null,
)

@Serializable
internal data class ExamPrice(val tickets: Int, val diasAcceso: Int)

@Serializable
internal data class AppExamDetail(
    val id: String,
    val codigoExamen: String,
    val titulo: String,
    val descripcion: String? = null,
    val tipoExamen: String,
    val tipoAcceso: String,
    val especialidadId: String,
    val comunidadCodigo: String? = null,
    val duracionMinutos: Int? = null,
    val totalPreguntas: Int,
    val anio: Int? = null,
    val portadaUrl: String? = null,
    val accessState: AccessState,
    val intentoAbiertoId: String? = null,
    /** DEC-035 abierta: SIEMPRE `null` en SLICE 2 (los valores guardados no son fiables). */
    val precio: ExamPrice? = null,
)

@Serializable
internal data class AppExamResponse(val examen: AppExamDetail)

@Serializable
internal data class MyExamRef(
    val id: String,
    val titulo: String,
    val tipoExamen: String,
    val comunidadCodigo: String? = null,
    val portadaUrl: String? = null,
)

@Serializable
internal data class MyExamItem(
    val examen: MyExamRef,
    val accessState: AccessState,
    val intentoId: String? = null,
    val estadoIntento: String? = null,
    val progreso: Double? = null,
    /** DEC-013 (SLICE 3): sin formula de nota, `null`. */
    val nota: Double? = null,
    val calificacion: String? = null,
)

@Serializable
internal data class MyExamGroup(val comunidadCodigo: String, val total: Int)

@Serializable
internal data class MyExamsPage(
    val items: List<MyExamItem>,
    val nextCursor: String? = null,
    val grupos: List<MyExamGroup>? = null,
)

@Serializable
internal data class ContentCredit(
    val tipoExamen: String,
    val total: Int,
    val usados: Int,
    val restantes: Int,
    val validoHasta: String? = null,
)

@Serializable
internal data class Entitlement(
    val isPremium: Boolean,
    val status: String,
    val planCodigo: String? = null,
    val planNivel: Int? = null,
    val proveedor: String? = null,
    val startsAt: String? = null,
    val renewsAt: String? = null,
    val endsAt: String? = null,
    val credits: List<ContentCredit>,
)

/** Lo que D2-03 necesita saber antes de abrir un intento: el examen publicado y la decision de D7-25. */
internal data class ExamStartAccess(val examId: String, val kind: ExamKind, val verdict: AccessVerdict)

// ---------- peticiones ya validadas ----------

internal data class ExamListQuery(val filter: CatalogFilter, val includeFacets: Boolean, val page: CursorPageRequest)

internal enum class MyExamsView(val wire: String) {
    EN_CURSO("EN_CURSO"),
    ADQUIRIDOS("ADQUIRIDOS"),
    COLECCION("COLECCION"),
}

internal data class MyExamsQuery(val view: MyExamsView, val filter: CatalogFilter, val page: CursorPageRequest)

private fun invalid(field: String, issue: String = CanonicalIssue.INVALID_FORMAT): ErrorIssue = ErrorIssue(field, issue)

private fun catalogFilterOf(params: Parameters, issues: MutableList<ErrorIssue>): CatalogFilter {
    val kind = params["tipoExamen"]?.let { raw ->
        ExamKind.fromWireOrNull(raw.trim()) ?: run { issues += invalid("tipoExamen"); null }
    }
    val specialty = params["especialidadId"]?.let { raw ->
        raw.trim().takeIf { it.isUuid() }?.lowercase() ?: run { issues += invalid("especialidadId"); null }
    }
    val community = params["comunidadId"]?.let { raw ->
        raw.trim().takeIf { it.isUuid() }?.lowercase() ?: run { issues += invalid("comunidadId"); null }
    }
    val year = params["anio"]?.let { raw ->
        raw.trim().toIntOrNull()?.takeIf { it in YEAR_RANGE } ?: run { issues += invalid("anio"); null }
    }
    return CatalogFilter(kind, specialty, community, year)
}

private val YEAR_RANGE = 1900..2200

private fun scopeFilters(filter: CatalogFilter, extra: Map<String, String?> = emptyMap()): Map<String, String?> = extra + mapOf(
    "tipoExamen" to filter.kind?.wire,
    "especialidadId" to filter.specialtyId,
    "comunidadId" to filter.communityId,
    "anio" to filter.year?.toString(),
)

/** D2-01. Todo parametro invalido se acumula y sale en UN 400 VALIDATION; un cursor ajeno/corrupto es 400 INVALID_CURSOR. */
internal fun parseExamListQuery(params: Parameters): ExamListQuery {
    val issues = mutableListOf<ErrorIssue>()
    val filter = catalogFilterOf(params, issues)
    val includeFacets = params["includeFacets"]?.let { raw ->
        when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> { issues += invalid("includeFacets"); false }
        }
    } ?: false
    if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
    val page = CursorPageRequest.of(
        cursor = params["cursor"],
        limit = params["limit"],
        default = CursorPageRequest.APP_DEFAULT_LIMIT,
        max = CursorPageRequest.APP_MAX_LIMIT,
        scope = CursorScope(sort = listOf("publicado_en desc", "id desc"), filters = scopeFilters(filter)),
    )
    return ExamListQuery(filter, includeFacets, page)
}

/** D2-13. `vista` es obligatoria; el cursor lleva la vista en su huella. */
internal fun parseMyExamsQuery(params: Parameters): MyExamsQuery {
    val issues = mutableListOf<ErrorIssue>()
    val view = when (val raw = params["vista"]) {
        null -> { issues += invalid("vista", CanonicalIssue.REQUIRED); null }
        else -> MyExamsView.entries.firstOrNull { it.wire == raw.trim() } ?: run { issues += invalid("vista"); null }
    }
    val filter = catalogFilterOf(params.withoutKey("especialidadId"), issues)
    if (issues.isNotEmpty() || view == null) throw validationError(*issues.toTypedArray())
    val sort = when (view) {
        MyExamsView.EN_CURSO -> listOf("actualizado_en desc", "id desc")
        MyExamsView.ADQUIRIDOS, MyExamsView.COLECCION -> listOf("valido_desde desc", "id desc")
    }
    val page = CursorPageRequest.of(
        cursor = params["cursor"],
        limit = params["limit"],
        default = CursorPageRequest.APP_DEFAULT_LIMIT,
        max = CursorPageRequest.APP_MAX_LIMIT,
        scope = CursorScope(sort = sort, filters = scopeFilters(filter, mapOf("vista" to view.wire))),
    )
    return MyExamsQuery(view, filter, page)
}

private fun Parameters.withoutKey(key: String): Parameters = io.ktor.http.Parameters.build {
    this@withoutKey.entries().filter { it.key != key }.forEach { appendAll(it.key, it.value) }
}

// ---------- servicio ----------

internal class CatalogService(
    private val gateway: CatalogGateway,
    private val clock: () -> Instant = Instant::now,
) {
    // ----- D2-01 -----
    suspend fun listExams(userId: CanonicalUserId, query: ExamListQuery): AppExamPage = withUpstreamIo {
        val now = clock()
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val rows = gateway.listPublished(query.filter, after, query.page.fetchLimit, now)
        val page = query.page.page(rows) { KeysetPosition(it.publicadoEnOrFail(), it.id).toKeys() }
        val verdicts = accessFor(userId.value, page.items, now)
        val covers = signedCovers(page.items)
        val items = page.items.map { row ->
            AppExamSummary(
                id = row.id,
                titulo = row.titulo,
                tipoExamen = row.kind().wire,
                tipoAcceso = row.accessType(),
                especialidadId = row.especialidadId,
                comunidadCodigo = row.comunidad?.codigo,
                totalPreguntas = row.totalPreguntas,
                anio = row.anio,
                duracionMinutos = row.duracionMinutos,
                portadaUrl = row.portadaStoragePath?.let(covers::get),
                accessState = verdicts.getValue(row.id).state,
            )
        }
        AppExamPage(items = items, nextCursor = page.nextCursor, facets = if (query.includeFacets) facets(query.filter, now) else null)
    }

    /** Facetas sobre el catalogo publicado (acotadas por `especialidadId` si viene). Desbordadas = `null` (nunca truncadas en silencio). */
    private fun facets(filter: CatalogFilter, now: Instant): AppExamFacets? {
        val rows = gateway.facetRows(filter.specialtyId, now, FACET_ROWS_MAX)
        if (rows.size > FACET_ROWS_MAX) {
            CatalogLogger.warn("facetas omitidas: el catalogo publicado supera {} examenes", FACET_ROWS_MAX)
            return null
        }
        return AppExamFacets(
            comunidades = rows.mapNotNull { it.comunidad?.codigo }.distinct().sorted(),
            anios = rows.mapNotNull { it.anio }.distinct().sortedDescending(),
            tipos = rows.mapNotNull { ExamKind.fromWireOrNull(it.tipoExamen) }.distinct().sortedBy { it.ordinal }.map { it.wire },
        )
    }

    // ----- D2-02 -----
    suspend fun getExam(userId: CanonicalUserId, examId: String): AppExamResponse = withUpstreamIo {
        val now = clock()
        // Borrador, archivado e inexistente son indistinguibles: 404 sin filtrar la existencia.
        val row = gateway.getPublished(examId, now) ?: throw CanonicalErrorCode.EXAM_NOT_FOUND.exception()
        val verdict = accessFor(userId.value, listOf(row), now).getValue(row.id)
        val covers = signedCovers(listOf(row))
        AppExamResponse(
            AppExamDetail(
                id = row.id,
                codigoExamen = row.codigoExamen.orEmpty(),
                titulo = row.titulo,
                descripcion = row.descripcion,
                tipoExamen = row.kind().wire,
                tipoAcceso = row.accessType(),
                especialidadId = row.especialidadId,
                comunidadCodigo = row.comunidad?.codigo,
                duracionMinutos = row.duracionMinutos,
                totalPreguntas = row.totalPreguntas,
                anio = row.anio,
                portadaUrl = row.portadaStoragePath?.let(covers::get),
                accessState = verdict.state,
                intentoAbiertoId = gateway.openAttemptId(userId.value, row.id),
                precio = null,
            ),
        )
    }

    // ----- D2-13 -----
    suspend fun listMyExams(userId: CanonicalUserId, query: MyExamsQuery): MyExamsPage = withUpstreamIo {
        val now = clock()
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val uid = userId.value
        when (query.view) {
            MyExamsView.EN_CURSO -> {
                val rows = gateway.myOpenAttempts(uid, query.filter, after, query.page.fetchLimit, now)
                val page = query.page.page(rows) { KeysetPosition(it.actualizadoEn, it.id).toKeys() }
                val exams = page.items.map { it.examenes }
                val verdicts = accessFor(uid, exams, now)
                val covers = signedCovers(exams)
                MyExamsPage(
                    items = page.items.map { attempt ->
                        myItem(attempt.examenes, verdicts, covers, attempt.id, attempt.estado, attempt.totalRespondidas, attempt.totalPreguntas)
                    },
                    nextCursor = page.nextCursor,
                )
            }
            MyExamsView.ADQUIRIDOS, MyExamsView.COLECCION -> {
                val rows = gateway.myActiveGrants(uid, query.filter, after, query.page.fetchLimit, now)
                val page = query.page.page(rows) { KeysetPosition(it.validoDesde, it.id).toKeys() }
                val exams = page.items.map { it.examenes }
                val verdicts = accessFor(uid, exams, now)
                val covers = signedCovers(exams)
                val latest = gateway.latestAttempts(uid, exams.map { it.id }.distinct())
                MyExamsPage(
                    items = page.items.map { grant ->
                        val attempt = latest[grant.examenes.id]
                        myItem(grant.examenes, verdicts, covers, attempt?.id, attempt?.estado, attempt?.totalRespondidas ?: 0, attempt?.totalPreguntas ?: 0)
                    },
                    nextCursor = page.nextCursor,
                    grupos = if (query.view == MyExamsView.COLECCION && query.page.after == null) groups(uid, query.filter, now) else null,
                )
            }
        }
    }

    /** Conteo por comunidad sobre TODO el conjunto filtrado; si no cabe en el tope, `null` (el contrato lo admite) y nunca un parcial. */
    private fun groups(userId: String, filter: CatalogFilter, now: Instant): List<MyExamGroup>? {
        val rows = gateway.myActiveGrantCommunities(userId, filter, now, GROUP_ROWS_MAX)
        if (rows.size > GROUP_ROWS_MAX) return null
        return rows.distinctBy { it.first }
            .mapNotNull { it.second }
            .groupingBy { it }.eachCount()
            .map { MyExamGroup(it.key, it.value) }
            .sortedBy { it.comunidadCodigo }
    }

    private fun myItem(
        exam: CatalogExamRow,
        verdicts: Map<String, AccessVerdict>,
        covers: Map<String, String?>,
        attemptId: String?,
        attemptState: String?,
        answered: Int,
        total: Int,
    ): MyExamItem = MyExamItem(
        examen = MyExamRef(
            id = exam.id,
            titulo = exam.titulo,
            tipoExamen = exam.kind().wire,
            comunidadCodigo = exam.comunidad?.codigo,
            portadaUrl = exam.portadaStoragePath?.let(covers::get),
        ),
        accessState = verdicts.getValue(exam.id).state,
        intentoId = attemptId,
        estadoIntento = attemptState?.takeIf { it in ATTEMPT_STATES },
        progreso = if (attemptId != null && total > 0) (answered.toDouble() / total).coerceIn(0.0, 1.0) else null,
    )

    // ----- D7-25 para el arranque de intento (SLICE 3, D2-03) -----

    /**
     * El examen PUBLICADO y el veredicto de [EntitlementPolicy] para ese usuario, o `null` si el examen no existe, es un
     * borrador o esta archivado (indistinguibles). Es la MISMA politica y los mismos hechos que D2-01/D2-02: el arranque
     * de intento no tiene reglas de acceso propias. Bloqueante: el llamante ya esta en el pool de IO.
     */
    internal fun startAccess(userId: String, examId: String, now: Instant): ExamStartAccess? {
        val row = gateway.getPublished(examId, now) ?: return null
        return ExamStartAccess(row.id, row.kind(), accessFor(userId, listOf(row), now).getValue(row.id))
    }

    // ----- D7-25 por lotes: los hechos del usuario se leen UNA vez por peticion -----
    private fun accessFor(userId: String, exams: List<CatalogExamRow>, now: Instant): Map<String, AccessVerdict> {
        if (exams.isEmpty()) return emptyMap()
        val examIds = exams.map { it.id }.distinct()
        val premium = gateway.premiumRow(userId)?.toFact()
        val grants = gateway.grantsFor(userId, examIds)
        val capabilities = gateway.capabilitiesFor(exams.map { it.especialidadId }.distinct())
        return exams.associate { exam ->
            exam.id to EntitlementPolicy.check(
                exam = ExamAccessSubject(exam.id, exam.kind(), free = exam.accessType() == "gratuito", demoAvailable = exam.demoDisponible),
                capabilities = capabilities[exam.especialidadId] ?: SpecialtyAccessCapabilities.OPEN,
                user = UserAccessFacts(premium = premium, grants = grants[exam.id].orEmpty()),
                now = now,
            )
        }
    }

    /** UNA peticion a Storage para toda la pagina; un fallo deja las portadas en `null` y la lectura sigue. */
    private fun signedCovers(exams: List<CatalogExamRow>): Map<String, String?> {
        val paths = exams.mapNotNull { it.portadaStoragePath?.takeIf(String::isNotBlank) }.distinct()
        if (paths.isEmpty()) return emptyMap()
        return try {
            gateway.signCovers(paths, COVER_URL_TTL_SECONDS)
        } catch (error: Exception) {
            CatalogLogger.warn("firma de portadas fallida ({})", error::class.simpleName)
            emptyMap()
        }
    }

    private fun CatalogExamRow.kind(): ExamKind =
        ExamKind.fromWireOrNull(tipoExamen) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun CatalogExamRow.accessType(): String =
        tipoAcceso.takeIf { it == "gratuito" || it == "premium" } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun CatalogExamRow.publicadoEnOrFail(): String =
        publicadoEn ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    companion object {
        const val COVER_URL_TTL_SECONDS = 3600
        const val FACET_ROWS_MAX = 2000
        const val GROUP_ROWS_MAX = 500
        private val ATTEMPT_STATES = setOf("en_progreso", "finalizado", "abandonado")
    }
}

/** Una fecha de la base ilegible NO da acceso: sin `fin_en` interpretable el premium se descarta. */
internal fun PremiumRow.toFact(): PremiumFact? {
    val end = finEn?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: return null }
    return PremiumFact(esPremium, estadoSuscripcion, end)
}

// ---------- D7-02 ----------

internal class EntitlementService(
    private val gateway: CatalogGateway,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun entitlement(userId: CanonicalUserId): Entitlement = withUpstreamIo {
        val now = clock()
        val uid = userId.value
        val row = gateway.premiumRow(uid)
        val credits = credits(uid, now)
        if (row == null) {
            return@withUpstreamIo Entitlement(isPremium = false, status = "sin_suscripcion", credits = credits)
        }
        if (row.estadoSuscripcion !in STATUSES) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val endsAt = row.finEn?.let { instantOrFail(it) }
        val fact = row.toFact()
        val premiumActive = fact?.activeAt(now) == true
        // Q-04: ninguna fila caduca sola. Un fin_en vencido sobre un estado "vivo" se presenta como caducada.
        val lapsed = endsAt != null && !endsAt.isAfter(now) && row.estadoSuscripcion in LIVE_STATUSES
        val status = if (lapsed) "caducada" else row.estadoSuscripcion
        val plan = row.productoCodigo?.takeIf(String::isNotBlank)?.let { gateway.planOf(it) }
        Entitlement(
            isPremium = premiumActive,
            status = status,
            planCodigo = plan?.codigo,
            planNivel = plan?.nivel,
            proveedor = row.proveedor,
            startsAt = row.inicioEn?.let { instantOrFail(it).toString() },
            renewsAt = row.renovacionEn?.takeUnless { lapsed }?.let { instantOrFail(it).toString() },
            endsAt = endsAt?.toString(),
            credits = credits,
        )
    }

    private fun credits(userId: String, now: Instant): List<ContentCredit> {
        val rows = gateway.activeCredits(userId, now, CREDIT_ROWS_MAX)
        // Una suma truncada mostraria menos creditos de los que hay: falla cerrado.
        if (rows.size > CREDIT_ROWS_MAX) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return rows.groupBy { row -> ExamKind.fromWireOrNull(row.tipoExamen) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() }
            .toSortedMap(compareBy { it.ordinal })
            .map { (kind, group) ->
                val total = group.sumOf { it.cantidadTotal.coerceAtLeast(0) }
                val used = group.sumOf { it.cantidadUsada.coerceAtLeast(0) }.coerceAtMost(total)
                ContentCredit(
                    tipoExamen = kind.wire,
                    total = total,
                    usados = used,
                    restantes = total - used,
                    // El vencimiento mas proximo de los lotes vigentes (las filas vienen por valido_hasta ascendente).
                    validoHasta = group.firstNotNullOfOrNull { it.validoHasta }?.let { instantOrFail(it).toString() },
                )
            }
    }

    private fun instantOrFail(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    companion object {
        const val CREDIT_ROWS_MAX = 200
        private val STATUSES = setOf("activa", "pausada", "cancelada", "caducada", "periodo_prueba", "sin_suscripcion")
        private val LIVE_STATUSES = setOf("activa", "periodo_prueba", "cancelada")
    }
}
