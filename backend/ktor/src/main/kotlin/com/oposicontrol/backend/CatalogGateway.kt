package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// Pasarela de catalogo y derechos de SLICE 2 (D2-01, D2-02, D2-13, D7-02): SOLO lecturas PostgREST con la clave de
// servicio sobre tablas MAIN EXISTENTES. Ninguna regla de negocio: eso es de [CatalogService] / [EntitlementPolicy].
//
// - Solo se leen columnas de METADATOS del examen (nunca `preguntas*`, `opciones*`, soluciones: SEC-004).
// - "Publicado" = `activo AND publicado_en IS NOT NULL AND publicado_en <= now` (RLS `examenes_select_activos` +
//   diseno 03); nunca un borrador ni un archivado.
// - Todos los literales que llegan del cliente (cursor, filtros) se validan ANTES de componer la ruta; el `sub` sale del JWT.
// =====================================================================

/** Puerto bloqueante hacia Supabase (PostgREST + firma de portadas en Storage). */
internal interface CatalogUpstream {
    fun get(path: String): String

    /** Firma por LOTES (una peticion a Storage). Un fallo NO rompe la lectura: el llamante lo trata como "sin portada". */
    fun signCovers(paths: Collection<String>, ttlSeconds: Int): Map<String, String?>
}

internal class TransportCatalogUpstream(
    private val transport: SupabaseRequestTransport,
    private val bucket: String = DEFAULT_BUCKET,
) : CatalogUpstream {
    override fun get(path: String): String = transport.get(path)

    override fun signCovers(paths: Collection<String>, ttlSeconds: Int): Map<String, String?> =
        transport.storageSignedUrls(bucket, paths, ttlSeconds)

    companion object {
        const val DEFAULT_BUCKET = "exam-resources"

        fun fromEnvironment(environment: (String) -> String? = System::getenv): TransportCatalogUpstream? =
            SupabaseRequestTransport.fromEnvironment(environment)?.let {
                val configured = environment("SUPABASE_BUCKET_EXAM_RESOURCES")
                    ?.trim()
                    ?.replace(Regex("[^A-Za-z0-9._-]+"), "-")
                    ?.trim('-', '.', '_')
                    ?.takeIf(String::isNotEmpty)
                TransportCatalogUpstream(it, configured ?: DEFAULT_BUCKET)
            }
    }
}

// ---------- filas (snake_case, tal cual vive en la base) ----------

@Serializable
internal data class CommunityRef(val codigo: String? = null)

@Serializable
internal data class CatalogExamRow(
    val id: String,
    @SerialName("codigo_examen") val codigoExamen: String? = null,
    val titulo: String,
    val descripcion: String? = null,
    @SerialName("tipo_examen") val tipoExamen: String,
    @SerialName("tipo_acceso") val tipoAcceso: String,
    @SerialName("especialidad_id") val especialidadId: String,
    @SerialName("total_preguntas") val totalPreguntas: Int = 0,
    @SerialName("duracion_minutos") val duracionMinutos: Int? = null,
    val anio: Int? = null,
    @SerialName("portada_storage_path") val portadaStoragePath: String? = null,
    @SerialName("publicado_en") val publicadoEn: String? = null,
    @SerialName("demo_disponible") val demoDisponible: Boolean = false,
    @SerialName("comunidades_autonomas") val comunidad: CommunityRef? = null,
)

@Serializable
internal data class MyAttemptRow(
    val id: String,
    val estado: String,
    @SerialName("total_respondidas") val totalRespondidas: Int = 0,
    @SerialName("total_preguntas") val totalPreguntas: Int = 0,
    @SerialName("actualizado_en") val actualizadoEn: String,
    val examenes: CatalogExamRow,
)

@Serializable
internal data class MyGrantRow(
    val id: String,
    @SerialName("valido_desde") val validoDesde: String,
    val examenes: CatalogExamRow,
)

@Serializable
internal data class AttemptBriefRow(
    val id: String,
    @SerialName("examen_id") val examenId: String,
    val estado: String,
    @SerialName("total_respondidas") val totalRespondidas: Int = 0,
    @SerialName("total_preguntas") val totalPreguntas: Int = 0,
)

@Serializable
internal data class ExamRefRow(
    val id: String? = null,
    @SerialName("comunidades_autonomas") val comunidad: CommunityRef? = null,
)

@Serializable
private data class GrantCommunityRow(@SerialName("examen_id") val examenId: String, val examenes: ExamRefRow)

@Serializable
private data class OpenAttemptRow(val id: String)

@Serializable
private data class GrantFactRow(
    @SerialName("examen_id") val examenId: String,
    val motivo: String,
    @SerialName("valido_desde") val validoDesde: String,
    @SerialName("valido_hasta") val validoHasta: String? = null,
)

@Serializable
private data class CapabilitiesRow(
    @SerialName("especialidad_id") val especialidadId: String,
    @SerialName("permite_rafagas") val permiteRafagas: Boolean = true,
    @SerialName("permite_razonados") val permiteRazonados: Boolean = true,
    @SerialName("permite_inmersivos") val permiteInmersivos: Boolean = true,
    @SerialName("permite_demos") val permiteDemos: Boolean = true,
)

@Serializable
internal data class PremiumRow(
    @SerialName("es_premium") val esPremium: Boolean = false,
    @SerialName("estado_suscripcion") val estadoSuscripcion: String = "sin_suscripcion",
    @SerialName("producto_codigo") val productoCodigo: String? = null,
    val proveedor: String? = null,
    @SerialName("inicio_en") val inicioEn: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
)

@Serializable
internal data class PlanRef(val codigo: String? = null, val nivel: Int? = null)

@Serializable
private data class ProductPlanRow(@SerialName("planes_suscripcion") val plan: PlanRef? = null)

@Serializable
internal data class CreditRow(
    @SerialName("tipo_examen") val tipoExamen: String,
    @SerialName("cantidad_total") val cantidadTotal: Int = 0,
    @SerialName("cantidad_usada") val cantidadUsada: Int = 0,
    @SerialName("valido_hasta") val validoHasta: String? = null,
)

@Serializable
internal data class FacetRow(
    @SerialName("tipo_examen") val tipoExamen: String? = null,
    val anio: Int? = null,
    @SerialName("comunidades_autonomas") val comunidad: CommunityRef? = null,
)

// ---------- filtros y cursor ----------

/** Filtros ya validados de D2-01 / D2-13. */
internal data class CatalogFilter(
    val kind: ExamKind? = null,
    val specialtyId: String? = null,
    val communityId: String? = null,
    val year: Int? = null,
)

/** Clave de keyset ya validada: instante tal cual lo devolvio PostgREST (microsegundos) + id. */
internal data class KeysetPosition(val timestamp: String, val id: String) {
    companion object {
        private val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$")

        /** Las claves de un cursor `[instante, id]`; cualquier otra forma es 400 INVALID_CURSOR (nunca llega a la ruta SQL). */
        fun fromCursorKeys(keys: List<JsonElement>?): KeysetPosition? {
            if (keys == null) return null
            val invalid = { CanonicalErrorCode.INVALID_CURSOR.exception() }
            if (keys.size != 2) throw invalid()
            val ts = (keys[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid()
            val id = (keys[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid()
            if (!TIMESTAMP.matches(ts) || !id.isUuid()) throw invalid()
            if (runCatching { OffsetDateTime.parse(ts) }.isFailure) throw invalid()
            return KeysetPosition(ts, id.lowercase())
        }
    }

    fun toKeys(): List<JsonElement> = listOf(JsonPrimitive(timestamp), JsonPrimitive(id))
}

internal fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun parseInstantOrNull(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

internal class CatalogGateway(
    private val upstream: CatalogUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private inline fun <reified T> decodeList(body: String): List<T> =
        json.decodeFromString(ListSerializer(serializer<T>()), body)

    // ---------- catalogo (D2-01 / D2-02) ----------

    /** Una pagina de examenes publicados, `publicado_en DESC, id DESC`. Pide [fetchLimit] (= limit + 1). */
    fun listPublished(filter: CatalogFilter, after: KeysetPosition?, fetchLimit: Int, now: Instant): List<CatalogExamRow> {
        val query = StringBuilder("/rest/v1/examenes?select=$EXAM_COLUMNS")
        query.append(publishedPredicate("", now))
        query.append(filterPredicate("", filter))
        if (after != null) query.append("&or=(").append(keysetOr("publicado_en", after)).append(")")
        query.append("&order=publicado_en.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** El examen PUBLICADO con ese id, o `null` (borrador, archivado o inexistente: indistinguibles a proposito). */
    fun getPublished(examId: String, now: Instant): CatalogExamRow? {
        require(examId.isUuid()) { "examId" }
        val query = "/rest/v1/examenes?select=$EXAM_COLUMNS&id=eq.${examId.lowercase()}" + publishedPredicate("", now) + "&limit=1"
        return decodeList<CatalogExamRow>(upstream.get(query)).firstOrNull()
    }

    /** Filas minimas para las facetas. Pide `max + 1` para detectar el desbordamiento (el llamante decide). */
    fun facetRows(specialtyId: String?, now: Instant, max: Int): List<FacetRow> {
        val query = StringBuilder("/rest/v1/examenes?select=tipo_examen,anio,comunidades_autonomas(codigo)")
        query.append(publishedPredicate("", now))
        specialtyId?.let { query.append("&especialidad_id=eq.$it") }
        query.append("&order=publicado_en.desc,id.desc&limit=").append(max + 1)
        return decodeList(upstream.get(query.toString()))
    }

    /** El intento abierto mas reciente del usuario en el examen (MAIN no tiene indice unico de intento abierto). */
    fun openAttemptId(userId: String, examId: String): String? {
        val query = "/rest/v1/intentos_examen?select=id&usuario_id=eq.$userId&examen_id=eq.$examId&estado=eq.en_progreso" +
            "&order=iniciado_en.desc,id.desc&limit=1"
        return decodeList<OpenAttemptRow>(upstream.get(query)).firstOrNull()?.id
    }

    // ---------- "mis examenes" (D2-13) ----------

    /** EN_CURSO: intentos abiertos del usuario sobre examenes publicados, `actualizado_en DESC, id DESC`. */
    fun myOpenAttempts(userId: String, filter: CatalogFilter, after: KeysetPosition?, fetchLimit: Int, now: Instant): List<MyAttemptRow> {
        val query = StringBuilder("/rest/v1/intentos_examen?select=id,estado,total_respondidas,total_preguntas,actualizado_en,examenes!inner($EXAM_COLUMNS)")
        query.append("&usuario_id=eq.$userId&estado=eq.en_progreso&origen_intento=eq.examen")
        query.append(publishedPredicate("examenes.", now))
        query.append(filterPredicate("examenes.", filter))
        if (after != null) query.append("&or=(").append(keysetOr("actualizado_en", after)).append(")")
        query.append("&order=actualizado_en.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** ADQUIRIDOS / COLECCION: concesiones VIGENTES (cualquier motivo, Q-07) sobre examenes publicados, `valido_desde DESC, id DESC`. */
    fun myActiveGrants(userId: String, filter: CatalogFilter, after: KeysetPosition?, fetchLimit: Int, now: Instant): List<MyGrantRow> {
        val query = StringBuilder("/rest/v1/contenido_desbloqueado_usuario?select=id,valido_desde,examenes!inner($EXAM_COLUMNS)")
        query.append("&usuario_id=eq.$userId&examen_id=not.is.null")
        query.append(publishedPredicate("examenes.", now))
        query.append(filterPredicate("examenes.", filter))
        val nowText = encodeQuery(now.toString())
        val active = "or(valido_hasta.is.null,valido_hasta.gt.$nowText)"
        val keyset = after?.let { ",or(" + keysetOr("valido_desde", it) + ")" }.orEmpty()
        query.append("&valido_desde=lte.$nowText&and=($active$keyset)")
        query.append("&order=valido_desde.desc,id.desc&limit=").append(fetchLimit)
        return decodeList(upstream.get(query.toString()))
    }

    /** Examenes distintos con concesion vigente (para los grupos por comunidad). Pide `max + 1`. */
    fun myActiveGrantCommunities(userId: String, filter: CatalogFilter, now: Instant, max: Int): List<Pair<String, String?>> {
        val query = StringBuilder("/rest/v1/contenido_desbloqueado_usuario?select=examen_id,examenes!inner(id,comunidades_autonomas(codigo))")
        query.append("&usuario_id=eq.$userId&examen_id=not.is.null")
        query.append(publishedPredicate("examenes.", now))
        query.append(filterPredicate("examenes.", filter))
        val nowText = encodeQuery(now.toString())
        query.append("&valido_desde=lte.$nowText&or=(valido_hasta.is.null,valido_hasta.gt.$nowText)")
        query.append("&order=valido_desde.desc,id.desc&limit=").append(max + 1)
        return decodeList<GrantCommunityRow>(upstream.get(query.toString())).map { it.examenId to it.examenes.comunidad?.codigo }
    }

    /** El intento mas reciente por examen entre [examIds] (para ADQUIRIDOS / COLECCION). Acotado: sin el no se inventa progreso. */
    fun latestAttempts(userId: String, examIds: Collection<String>): Map<String, AttemptBriefRow> {
        if (examIds.isEmpty()) return emptyMap()
        val query = "/rest/v1/intentos_examen?select=id,examen_id,estado,total_respondidas,total_preguntas" +
            "&usuario_id=eq.$userId&examen_id=in.(${examIds.joinToString(",")})&origen_intento=eq.examen" +
            "&order=actualizado_en.desc,id.desc&limit=$LATEST_ATTEMPTS_FETCH_LIMIT"
        val latest = LinkedHashMap<String, AttemptBriefRow>()
        decodeList<AttemptBriefRow>(upstream.get(query)).forEach { latest.putIfAbsent(it.examenId, it) }
        return latest
    }

    // ---------- hechos de acceso (D7-25), por LOTES ----------

    /** Concesiones (de cualquier vigencia: la politica filtra con su reloj) del usuario sobre [examIds]. */
    fun grantsFor(userId: String, examIds: Collection<String>): Map<String, List<ExamGrantFact>> {
        if (examIds.isEmpty()) return emptyMap()
        val query = "/rest/v1/contenido_desbloqueado_usuario?select=examen_id,motivo,valido_desde,valido_hasta" +
            "&usuario_id=eq.$userId&examen_id=in.(${examIds.joinToString(",")})&limit=$GRANT_FACTS_FETCH_LIMIT"
        return decodeList<GrantFactRow>(upstream.get(query)).mapNotNull { row ->
            // Una fecha ilegible NO concede: la fila se descarta (deny-by-default).
            val from = parseInstantOrNull(row.validoDesde) ?: return@mapNotNull null
            val until = row.validoHasta?.let { parseInstantOrNull(it) ?: return@mapNotNull null }
            row.examenId to ExamGrantFact(row.motivo, from, until)
        }.groupBy({ it.first }, { it.second })
    }

    /** `capacidades_especialidad` por especialidad; sin fila = abierto (default de la tabla). */
    fun capabilitiesFor(specialtyIds: Collection<String>): Map<String, SpecialtyAccessCapabilities> {
        if (specialtyIds.isEmpty()) return emptyMap()
        val query = "/rest/v1/capacidades_especialidad?select=especialidad_id,permite_rafagas,permite_razonados,permite_inmersivos,permite_demos" +
            "&especialidad_id=in.(${specialtyIds.joinToString(",")})&limit=${specialtyIds.size}"
        return decodeList<CapabilitiesRow>(upstream.get(query)).associate {
            it.especialidadId to SpecialtyAccessCapabilities(it.permiteRafagas, it.permiteRazonados, it.permiteInmersivos, it.permiteDemos)
        }
    }

    // ---------- derechos (D7-02) ----------

    fun premiumRow(userId: String): PremiumRow? =
        decodeList<PremiumRow>(
            upstream.get(
                "/rest/v1/estado_premium_usuario?select=es_premium,estado_suscripcion,producto_codigo,proveedor,inicio_en,fin_en,renovacion_en" +
                    "&usuario_id=eq.$userId&limit=1",
            ),
        ).firstOrNull()

    /** Plan del producto: `productos_comerciales.codigo -> plan_id -> planes_suscripcion(codigo, nivel)`. */
    fun planOf(productCode: String): PlanRef? {
        val query = "/rest/v1/productos_comerciales?select=planes_suscripcion(codigo,nivel)&codigo=eq.${encodeQuery(productCode)}&limit=1"
        return decodeList<ProductPlanRow>(upstream.get(query)).firstOrNull()?.plan
    }

    /** Creditos de contenido vigentes. Pide `max + 1` para detectar el desbordamiento. */
    fun activeCredits(userId: String, now: Instant, max: Int): List<CreditRow> {
        val nowText = encodeQuery(now.toString())
        val query = "/rest/v1/creditos_contenido_usuario?select=tipo_examen,cantidad_total,cantidad_usada,valido_hasta" +
            "&usuario_id=eq.$userId&valido_desde=lte.$nowText&valido_hasta=gt.$nowText&order=valido_hasta.asc,id.asc&limit=${max + 1}"
        return decodeList(upstream.get(query))
    }

    // ---------- portadas ----------

    fun signCovers(paths: Collection<String>, ttlSeconds: Int): Map<String, String?> = upstream.signCovers(paths, ttlSeconds)

    // ---------- composicion de rutas ----------

    private fun publishedPredicate(prefix: String, now: Instant): String =
        "&${prefix}activo=eq.true&${prefix}publicado_en=not.is.null&${prefix}publicado_en=lte.${encodeQuery(now.toString())}"

    private fun filterPredicate(prefix: String, filter: CatalogFilter): String = buildString {
        filter.kind?.let { append("&${prefix}tipo_examen=eq.${it.wire}") }
        filter.specialtyId?.let { append("&${prefix}especialidad_id=eq.$it") }
        filter.communityId?.let { append("&${prefix}comunidad_id=eq.$it") }
        filter.year?.let { append("&${prefix}anio=eq.$it") }
    }

    /** Keyset descendente: `(col, id) < (X, Y)` como `or(col.lt.X, and(col.eq.X, id.lt.Y))`. [after] ya esta validado. */
    private fun keysetOr(column: String, after: KeysetPosition): String {
        val ts = encodeQuery(after.timestamp)
        return "$column.lt.$ts,and($column.eq.$ts,id.lt.${after.id})"
    }

    companion object {
        /** Solo metadatos de examen. NUNCA preguntas, opciones ni soluciones (SEC-004). */
        internal const val EXAM_COLUMNS =
            "id,codigo_examen,titulo,descripcion,tipo_examen,tipo_acceso,especialidad_id,total_preguntas,duracion_minutos,anio," +
                "portada_storage_path,publicado_en,demo_disponible,comunidades_autonomas(codigo)"
        internal const val LATEST_ATTEMPTS_FETCH_LIMIT = 500
        internal const val GRANT_FACTS_FETCH_LIMIT = 500
    }
}
