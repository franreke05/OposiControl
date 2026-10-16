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
    /** V1: nombre legible de la comunidad (p. ej. "Comunidad de Madrid"). Null solo en examenes heredados sin comunidad. */
    val comunidadNombre: String? = null,
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

/** Veredicto de acceso + fin del derecho (solo GRANTED con caducidad). */
internal data class AccessInfo(val verdict: AccessVerdict, val validUntil: Instant?)

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
    val comunidadNombre: String? = null,
    val duracionMinutos: Int? = null,
    val totalPreguntas: Int,
    val anio: Int? = null,
    val portadaUrl: String? = null,
    val accessState: AccessState,
    val intentoAbiertoId: String? = null,
    /** FA19 (PERF01): el ultimo intento FINALIZADO del usuario en este examen (resultado / revision sin recorrer D2-13). */
    val ultimoIntentoFinalizadoId: String? = null,
    /** V1: el precio en OposiTickets y los dias de acceso, TAL COMO estan en la base; `null` si el examen no es comprable con OT (nada se inventa). */
    val precio: ExamPrice? = null,
    /** V1: hasta cuando vale el derecho (decidido por el servidor) cuando `accessState` es GRANTED con caducidad; `null` si no hay derecho o no caduca. */
    val accesoValidoHasta: String? = null,
)

@Serializable
internal data class AppExamResponse(val examen: AppExamDetail)

/** D2-17 `PublicSpecialty.capacidades`. */
@Serializable
internal data class PublicSpecialtyCapabilities(val permiteRafagas: Boolean, val permiteRazonados: Boolean, val permiteInmersivos: Boolean)

/** D2-17 `PublicSpecialty`: id REAL de `especialidades` (el que llevan el perfil y `examenes.especialidad_id`). */
@Serializable
internal data class PublicSpecialty(val id: String, val codigo: String, val nombre: String, val slug: String, val capacidades: PublicSpecialtyCapabilities)

@Serializable
internal data class PublicSpecialtiesResponse(val items: List<PublicSpecialty>)

@Serializable
internal data class MyExamRef(
    val id: String,
    val titulo: String,
    val tipoExamen: String,
    val comunidadCodigo: String? = null,
    val comunidadNombre: String? = null,
    val portadaUrl: String? = null,
)

@Serializable
internal data class MyExamItem(
    val examen: MyExamRef,
    val accessState: AccessState,
    /** V1: fin del derecho vigente (GRANTED con caducidad), decidido por el servidor; `null` si no aplica. */
    val accesoValidoHasta: String? = null,
    val intentoId: String? = null,
    val estadoIntento: String? = null,
    val progreso: Double? = null,
    /** DEC-013 (SLICE 3): sin formula de nota, `null`. */
    val nota: Double? = null,
    val calificacion: String? = null,
)

@Serializable
internal data class MyExamGroup(val comunidadCodigo: String, val total: Int, val comunidadNombre: String? = null)

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

/** Politica uniforme (V1): un parametro de consulta desconocido es 400 UNKNOWN_FIELD en TODAS las lecturas del consumidor. */
internal fun requireOnlyKnownQueryParams(params: Parameters, allowed: Set<String>) {
    val issues = mutableListOf<ErrorIssue>()
    rejectUnknownParams(params, allowed, issues)
    if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
}

private val EXAM_LIST_PARAMS = setOf("tipoExamen", "especialidadId", "comunidadId", "anio", "includeFacets", "cursor", "limit")
private val MY_EXAMS_PARAMS = setOf("vista", "tipoExamen", "especialidadId", "comunidadId", "anio", "cursor", "limit")

/** E5 (V1): un parametro de consulta desconocido (p. ej. `especialidad=Local`) ya no se ignora en silencio: 400 UNKNOWN_FIELD. */
private fun rejectUnknownParams(params: Parameters, allowed: Set<String>, issues: MutableList<ErrorIssue>) {
    params.names().filter { it !in allowed }.sorted().forEach { name ->
        issues += ErrorIssue(if (name.length <= 40 && name.all { it.isLetterOrDigit() || it == '_' }) name else "(desconocido)", CanonicalIssue.UNKNOWN_FIELD)
    }
}

private fun scopeFilters(filter: CatalogFilter, extra: Map<String, String?> = emptyMap()): Map<String, String?> = extra + mapOf(
    "tipoExamen" to filter.kind?.wire,
    "especialidadId" to filter.specialtyId,
    "comunidadId" to filter.communityId,
    "anio" to filter.year?.toString(),
)

/** D2-01. Todo parametro invalido se acumula y sale en UN 400 VALIDATION; un cursor ajeno/corrupto es 400 INVALID_CURSOR. */
internal fun parseExamListQuery(params: Parameters): ExamListQuery {
    val issues = mutableListOf<ErrorIssue>()
    rejectUnknownParams(params, EXAM_LIST_PARAMS, issues)
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
    rejectUnknownParams(params, MY_EXAMS_PARAMS, issues)
    val view = when (val raw = params["vista"]) {
        null -> { issues += invalid("vista", CanonicalIssue.REQUIRED); null }
        else -> MyExamsView.entries.firstOrNull { it.wire == raw.trim() } ?: run { issues += invalid("vista"); null }
    }
    val filter = catalogFilterOf(params.withoutKey("especialidadId"), issues)
    if (issues.isNotEmpty() || view == null) throw validationError(*issues.toTypedArray())
    val sort = when (view) {
        MyExamsView.EN_CURSO -> listOf("actualizado_en desc", "id desc")
        MyExamsView.ADQUIRIDOS -> listOf("valido_desde desc", "id desc")
        // La union concesion/intento se ordena por su ultima actividad (`app_my_exams_collection`). Huella propia: un cursor
        // de COLECCION emitido con el orden anterior (por concesion) deja de valer en vez de paginar con otra clave.
        MyExamsView.COLECCION -> listOf("orden_en desc", "id desc")
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
    /**
     * V1: nombres legibles de las comunidades (codigo -> nombre), cacheados 5 min. Si la lectura falla el catalogo NO falla:
     * los nombres salen vacios (el cliente no muestra cabecera, nunca el codigo crudo) y el fallo no se cachea.
     */
    private val namesCache = java.util.concurrent.atomic.AtomicReference<Pair<Long, Map<String, String>>?>(null)

    internal fun communityNames(): Map<String, String> {
        val now = System.currentTimeMillis()
        namesCache.get()?.takeIf { now - it.first < COMMUNITY_NAMES_CACHE_MS }?.let { return it.second }
        return runCatching { gateway.communityNames() }.getOrNull()?.also { namesCache.set(now to it) } ?: emptyMap()
    }

    // ----- D2-17 -----
    /** Catalogo acotado de especialidades activas con sus capacidades (sin fila de capacidades = abierto, como la politica). */
    suspend fun publicSpecialties(): PublicSpecialtiesResponse = withUpstreamIo {
        val rows = gateway.activeSpecialties(MAX_PUBLIC_SPECIALTIES)
        // Mas que el tope no se trunca en silencio: falla cerrado.
        if (rows.size > MAX_PUBLIC_SPECIALTIES) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val capabilities = gateway.capabilitiesFor(rows.map { it.id })
        PublicSpecialtiesResponse(
            rows.map { row ->
                val c = capabilities[row.id] ?: SpecialtyAccessCapabilities.OPEN
                PublicSpecialty(row.id, row.codigo, row.nombre, row.slug, PublicSpecialtyCapabilities(c.allowsRafagas, c.allowsRazonados, c.allowsInmersivos))
            },
        )
    }

    // ----- D2-01 -----
    suspend fun listExams(userId: CanonicalUserId, query: ExamListQuery): AppExamPage = withUpstreamIo {
        val now = clock()
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val filter = specialtyScoped(userId.value, query.filter) ?: return@withUpstreamIo AppExamPage(items = emptyList(), nextCursor = null, facets = null)
        val rows = gateway.listPublished(filter, after, query.page.fetchLimit, now)
        val page = query.page.page(rows) { KeysetPosition(it.publicadoEnOrFail(), it.id).toKeys() }
        val verdicts = accessFor(userId.value, page.items, now)
        val covers = signedCovers(page.items)
        val names = communityNames()
        val items = page.items.map { row ->
            AppExamSummary(
                id = row.id,
                titulo = row.titulo,
                tipoExamen = row.kind().wire,
                tipoAcceso = row.accessType(),
                especialidadId = row.especialidadId,
                comunidadCodigo = row.comunidad?.codigo,
                comunidadNombre = row.comunidad?.codigo?.let(names::get),
                totalPreguntas = row.totalPreguntas,
                anio = row.anio,
                duracionMinutos = row.duracionMinutos,
                portadaUrl = row.portadaStoragePath?.let(covers::get),
                accessState = verdicts.getValue(row.id).state,
            )
        }
        AppExamPage(items = items, nextCursor = page.nextCursor, facets = if (query.includeFacets) facets(filter, now) else null)
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

    /**
     * V1 (owner 2026-10-16): la especialidad es una PUERTA. Con especialidad elegida, las listas se acotan a ella (aunque el cliente pida otra o ninguna);
     * pedir OTRA especialidad devuelve `null` = lista vacia. Sin especialidad elegida: lista vacia. Un derecho comprado de otra especialidad sigue guardado, pero no sale.
     */
    private fun specialtyScoped(userId: String, filter: CatalogFilter): CatalogFilter? {
        val own = gateway.userSpecialty(userId) ?: return null // sin especialidad: lista vacia (E2-V1-E06)
        if (filter.specialtyId != null && filter.specialtyId != own) return null
        return filter.copy(specialtyId = own)
    }

    // ----- D2-02 -----
    suspend fun getExam(userId: CanonicalUserId, examId: String): AppExamResponse = withUpstreamIo {
        val now = clock()
        // Borrador, archivado e inexistente son indistinguibles: 404 sin filtrar la existencia.
        val row = gateway.getPublished(examId, now) ?: throw CanonicalErrorCode.EXAM_NOT_FOUND.exception()
        val info = accessInfoFor(userId.value, listOf(row), now).getValue(row.id)
        val verdict = info.verdict
        if (verdict.denyReason == AccessDenyReason.SPECIALTY_MISMATCH) throw CanonicalErrorCode.EXAM_NOT_FOUND.exception()
        val covers = signedCovers(listOf(row))
        val names = communityNames()
        val attempts = gateway.attemptRefs(userId.value, row.id)
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
                comunidadNombre = row.comunidad?.codigo?.let(names::get),
                duracionMinutos = row.duracionMinutos,
                totalPreguntas = row.totalPreguntas,
                anio = row.anio,
                portadaUrl = row.portadaStoragePath?.let(covers::get),
                accessState = verdict.state,
                intentoAbiertoId = attempts.openId,
                ultimoIntentoFinalizadoId = attempts.lastFinishedId,
                precio = row.purchasePrice(),
                accesoValidoHasta = info.validUntil?.toString(),
            ),
        )
    }

    // ----- D2-13 -----
    suspend fun listMyExams(userId: CanonicalUserId, query: MyExamsQuery): MyExamsPage = withUpstreamIo {
        val now = clock()
        val after = KeysetPosition.fromCursorKeys(query.page.after)
        val uid = userId.value
        val filter = specialtyScoped(uid, query.filter) ?: return@withUpstreamIo MyExamsPage(items = emptyList())
        when (query.view) {
            MyExamsView.EN_CURSO -> {
                val rows = gateway.myOpenAttempts(uid, filter, after, query.page.fetchLimit, now)
                val page = query.page.page(rows) { KeysetPosition(it.actualizadoEn, it.id).toKeys() }
                val exams = page.items.map { it.examenes }
                val verdicts = accessInfoFor(uid, exams, now)
                val covers = signedCovers(exams)
                MyExamsPage(
                    items = page.items.map { attempt ->
                        myItem(attempt.examenes, verdicts, covers, attempt.id, attempt.estado, attempt.totalRespondidas, attempt.totalPreguntas)
                    },
                    nextCursor = page.nextCursor,
                )
            }
            MyExamsView.ADQUIRIDOS -> {
                val rows = gateway.myActiveGrants(uid, filter, after, query.page.fetchLimit, now)
                val page = query.page.page(rows) { KeysetPosition(it.validoDesde, it.id).toKeys() }
                val exams = page.items.map { it.examenes }
                val verdicts = accessInfoFor(uid, exams, now)
                val covers = signedCovers(exams)
                val latest = gateway.latestAttempts(uid, exams.map { it.id }.distinct())
                MyExamsPage(
                    items = page.items.map { grant ->
                        val attempt = latest[grant.examenes.id]
                        myItem(grant.examenes, verdicts, covers, attempt?.id, attempt?.estado, attempt?.totalRespondidas ?: 0, attempt?.totalPreguntas ?: 0)
                    },
                    nextCursor = page.nextCursor,
                )
            }
            // COLECCION = examenes con concesion VIGENTE UNION examenes intentados (Q-07), un elemento por examen. La union, su
            // orden estable y los grupos (conteo EXACTO por comunidad de todo el conjunto filtrado, Q-06) los calcula la funcion
            // de lectura; aqui solo se anade lo que es de Ktor: la MISMA politica de acceso y la firma de portadas. Los grupos
            // se piden con la primera pagina (sin cursor); en las siguientes van a `null`, que el contrato admite.
            MyExamsView.COLECCION -> {
                val collection = gateway.myCollection(uid, filter, after, query.page.fetchLimit, withGroups = after == null)
                val page = query.page.page(collection.items) { KeysetPosition(it.ordenEn, it.examenes.id).toKeys() }
                val exams = page.items.map { it.examenes }
                val verdicts = accessInfoFor(uid, exams, now)
                val covers = signedCovers(exams)
                MyExamsPage(
                    items = page.items.map { row ->
                        val attempt = row.intento
                        myItem(row.examenes, verdicts, covers, attempt?.id, attempt?.estado, attempt?.totalRespondidas ?: 0, attempt?.totalPreguntas ?: 0)
                    },
                    nextCursor = page.nextCursor,
                    grupos = collection.grupos?.map { MyExamGroup(it.comunidadCodigo, it.total, communityNames()[it.comunidadCodigo]) },
                )
            }
        }
    }

    private fun myItem(
        exam: CatalogExamRow,
        verdicts: Map<String, AccessInfo>,
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
            comunidadNombre = exam.comunidad?.codigo?.let(communityNames()::get),
            portadaUrl = exam.portadaStoragePath?.let(covers::get),
        ),
        accessState = verdicts.getValue(exam.id).verdict.state,
        accesoValidoHasta = verdicts.getValue(exam.id).validUntil?.toString(),
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
    private fun accessFor(userId: String, exams: List<CatalogExamRow>, now: Instant): Map<String, AccessVerdict> =
        accessInfoFor(userId, exams, now).mapValues { it.value.verdict }

    /** El veredicto de [EntitlementPolicy] y, si es GRANTED con caducidad, el fin del derecho (el servidor decide cuando acaba). */
    private fun accessInfoFor(userId: String, exams: List<CatalogExamRow>, now: Instant): Map<String, AccessInfo> {
        if (exams.isEmpty()) return emptyMap()
        val examIds = exams.map { it.id }.distinct()
        val premiumRow = gateway.premiumRow(userId)
        val premium = premiumRow?.toFact()?.let { fact ->
            // Access v2: el nivel del plan solo se lee cuando la suscripcion da acceso ahora (una peticion mas, no por examen)
            if (fact.activeAt(now)) fact.copy(planLevel = premiumRow.productoCodigo?.takeIf(String::isNotBlank)?.let { gateway.planOf(it)?.nivel }) else fact
        }
        val grants = gateway.grantsFor(userId, examIds)
        val capabilities = gateway.capabilitiesFor(exams.map { it.especialidadId }.distinct())
        val userSpecialty = gateway.userSpecialty(userId)
        return exams.associate { exam ->
            val examGrants = grants[exam.id].orEmpty()
            val verdict = EntitlementPolicy.check(
                exam = ExamAccessSubject(exam.id, exam.kind(), free = exam.accessType() == "gratuito", demoAvailable = exam.demoDisponible, specialtyId = exam.especialidadId),
                capabilities = capabilities[exam.especialidadId] ?: SpecialtyAccessCapabilities.OPEN,
                user = UserAccessFacts(premium = premium, grants = examGrants, specialtyId = userSpecialty),
                now = now,
            )
            exam.id to AccessInfo(verdict, if (verdict.state == AccessState.GRANTED) grantedUntil(examGrants, now) else null)
        }
    }

    /** Fin del derecho vigente: el MAYOR `valido_hasta` entre las concesiones vigentes; `null` si alguna no caduca (o ninguna). */
    private fun grantedUntil(grants: List<ExamGrantFact>, now: Instant): Instant? {
        val active = grants.filter { it.activeAt(now) }
        if (active.isEmpty() || active.any { it.validUntil == null }) return null
        return active.maxOf { it.validUntil!! }
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

    /** Precio en OT y dias de acceso, solo si el examen es de pago, comprable y la base tiene AMBOS valores; si no, `null` (nada se inventa). */
    private fun CatalogExamRow.purchasePrice(): ExamPrice? =
        if (tipoAcceso == "premium" && comprableConTickets && (precioTickets ?: 0) > 0 && (diasAccesoCompra ?: 0) > 0) ExamPrice(precioTickets!!, diasAccesoCompra!!) else null

    private fun CatalogExamRow.kind(): ExamKind =
        ExamKind.fromWireOrNull(tipoExamen) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun CatalogExamRow.accessType(): String =
        tipoAcceso.takeIf { it == "gratuito" || it == "premium" } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun CatalogExamRow.publicadoEnOrFail(): String =
        publicadoEn ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    companion object {
        const val COVER_URL_TTL_SECONDS = 3600
        const val FACET_ROWS_MAX = 2000

        /** D2-17: tope del catalogo de especialidades (hoy 3 en MAIN). */
        const val MAX_PUBLIC_SPECIALTIES = 100
        const val COMMUNITY_NAMES_CACHE_MS = 300_000L
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
