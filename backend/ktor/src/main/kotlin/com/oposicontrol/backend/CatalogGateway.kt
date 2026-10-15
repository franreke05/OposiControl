package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// Pasarela de catalogo y derechos de SLICE 2 (D2-01, D2-02, D2-13, D7-02): SOLO lecturas PostgREST con la clave de
// servicio sobre tablas MAIN EXISTENTES, mas UNA funcion de lectura (`app_my_exams_collection`, migracion
// 20261008100300: la union de D2-13 COLECCION, que PostgREST no sabe paginar). Ninguna regla de negocio: eso es de
// [CatalogService] / [EntitlementPolicy].
//
// - Solo se leen columnas de METADATOS del examen (nunca `preguntas*`, `opciones*`, soluciones: SEC-004).
// - "Publicado" = `activo AND publicado_en IS NOT NULL AND publicado_en <= now` (RLS `examenes_select_activos` +
//   diseno 03); nunca un borrador ni un archivado.
// - Todos los literales que llegan del cliente (cursor, filtros) se validan ANTES de componer la ruta; el `sub` sale del JWT.
// =====================================================================

/** Puerto bloqueante hacia Supabase (PostgREST + firma de portadas en Storage). */
internal interface CatalogUpstream {
    fun get(path: String): String

    /** Funcion de LECTURA de Postgres (nombre desnudo). La unica que usa esta pasarela es [CatalogGateway.MY_COLLECTION_RPC]. */
    fun rpc(function: String, body: String): String

    /** Firma por LOTES (una peticion a Storage). Un fallo NO rompe la lectura: el llamante lo trata como "sin portada". */
    fun signCovers(paths: Collection<String>, ttlSeconds: Int): Map<String, String?>
}

internal class TransportCatalogUpstream(
    private val transport: SupabaseRequestTransport,
    private val bucket: String = DEFAULT_BUCKET,
) : CatalogUpstream {
    override fun get(path: String): String = transport.get(path)

    override fun rpc(function: String, body: String): String = transport.rpc(function, body)

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
internal data class CommunityNameRow(val codigo: String, val nombre: String)

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
    /** V1: el precio en OposiTickets SALE de la base (nunca se inventa). Sin valor fijado, el examen no es comprable. */
    @SerialName("comprable_con_tickets") val comprableConTickets: Boolean = false,
    @SerialName("precio_tickets") val precioTickets: Int? = null,
    @SerialName("dias_acceso_compra") val diasAccesoCompra: Int? = null,
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

/** El intento mas reciente del usuario en un examen de su coleccion (solo intentos creados por el comando canonico). */
@Serializable
internal data class CollectionAttemptRef(
    val id: String,
    val estado: String,
    @SerialName("total_respondidas") val totalRespondidas: Int = 0,
    @SerialName("total_preguntas") val totalPreguntas: Int = 0,
)

/** Una fila de `app_my_exams_collection`: UN examen de la coleccion, su clave de orden y su intento mas reciente. */
@Serializable
internal data class MyCollectionRow(
    @SerialName("orden_en") val ordenEn: String,
    val examenes: CatalogExamRow,
    val intento: CollectionAttemptRef? = null,
)

@Serializable
internal data class CollectionGroupRow(@SerialName("comunidad_codigo") val comunidadCodigo: String, val total: Int)

/** Lo que devuelve `app_my_exams_collection`: la pagina (ya ordenada) y, si se pidieron, los grupos de TODO el conjunto filtrado. */
@Serializable
internal data class MyCollectionPage(val items: List<MyCollectionRow>, val grupos: List<CollectionGroupRow>? = null)

@Serializable
private data class ExamAttemptRow(val id: String, val estado: String)

/** D2-02: el intento abierto (como mucho uno: ux_intentos_examen_abierto) y el ULTIMO finalizado del usuario en un examen. */
internal data class ExamAttemptRefs(val openId: String?, val lastFinishedId: String?)

@Serializable
internal data class SpecialtyRow(val id: String, val codigo: String, val nombre: String, val slug: String)

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

/**
 * E2-P03: predicado de examen PUBLICADO YA (`publicado_en` no nulo y no futuro) para las rutas /v1 heredadas, el mismo que
 * aplica el catalogo canonico ([CatalogGateway] publishedPredicate). Un examen programado no se sirve antes de su hora.
 */
internal fun publishedByNow(now: java.time.Instant = java.time.Instant.now()): String =
    "&publicado_en=not.is.null&publicado_en=lte.${encodeQuery(now.toString())}"

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

    /**
     * D2-02 (PERF01, FA18): en UNA lectura acotada (limit 2) el intento abierto y el ultimo FINALIZADO del usuario en ESTE
     * examen. `estado` ordena en_progreso antes que finalizado y, dentro de los finalizados, el mas reciente primero: la
     * fila 1 es el abierto si existe (como mucho uno) y la siguiente finalizada es la ultima. Solo intentos del comando
     * canonico (los demas no existen para D2-07 / D2-12). Indice (usuario_id, examen_id): no crece con el historial del
     * usuario en otros examenes.
     */
    fun attemptRefs(userId: String, examId: String): ExamAttemptRefs {
        val query = "/rest/v1/intentos_examen?select=id,estado&usuario_id=eq.$userId&examen_id=eq.$examId" +
            "&origen_intento=eq.examen&creado_por_comando=not.is.null&estado=in.(en_progreso,finalizado)" +
            "&order=estado.asc,finalizado_en.desc.nullslast,id.desc&limit=2"
        val rows = decodeList<ExamAttemptRow>(upstream.get(query))
        return ExamAttemptRefs(
            openId = rows.firstOrNull { it.estado == "en_progreso" }?.id,
            lastFinishedId = rows.firstOrNull { it.estado == "finalizado" }?.id,
        )
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

    /** ADQUIRIDOS: concesiones VIGENTES (cualquier motivo, Q-07) sobre examenes publicados, `valido_desde DESC, id DESC`. */
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

    /**
     * COLECCION (D2-13): UNA pagina de la union "examenes con concesion vigente" + "examenes con un intento del comando
     * canonico", un elemento por examen, `orden_en DESC, id DESC`; con [withGroups], ademas el conteo por comunidad de TODO
     * el conjunto filtrado. La calcula entera `public.app_my_exams_collection` (20261008100300): solo examenes publicados,
     * solo filas de [userId], limite acotado dentro de la funcion. Pide [fetchLimit] (= limit + 1).
     *
     * Si la funcion NO esta instalada (PostgREST `PGRST202`: build desplegado antes que su migracion) es 503
     * UPSTREAM_UNAVAILABLE. NO hay respaldo a la respuesta parcial basada en concesiones: una coleccion a la que le faltan
     * los examenes intentados seria un dato falso servido como bueno.
     */
    fun myCollection(userId: String, filter: CatalogFilter, after: KeysetPosition?, fetchLimit: Int, withGroups: Boolean): MyCollectionPage {
        require(userId.isUuid()) { "userId" }
        val body = buildJsonObject {
            put("p_usuario_id", userId)
            put("p_tipo_examen", filter.kind?.wire?.let(::JsonPrimitive) ?: JsonNull)
            put("p_comunidad_id", filter.communityId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_anio", filter.year?.let(::JsonPrimitive) ?: JsonNull)
            put("p_despues_en", after?.timestamp?.let(::JsonPrimitive) ?: JsonNull)
            put("p_despues_id", after?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("p_limite", fetchLimit)
            put("p_con_grupos", withGroups)
        }
        val raw = try {
            upstream.rpc(MY_COLLECTION_RPC, body.toString())
        } catch (failure: IllegalStateException) {
            // Se mira el `code` que pone PostgREST, nunca el texto del mensaje (ver [supabaseErrorCode]).
            if (supabaseErrorCode(failure.message.orEmpty()) == RPC_NOT_FOUND) {
                throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = failure)
            }
            throw failure
        }
        // PostgREST devuelve el jsonb de la funcion tal cual (objeto); se admite tambien la lista de una fila.
        val row = when (val element = json.parseToJsonElement(raw)) {
            is JsonObject -> element
            is JsonArray -> element.singleOrNull() as? JsonObject
            else -> null
        } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val page = runCatching { json.decodeFromJsonElement<MyCollectionPage>(row) }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
        // La clave de orden viaja en el cursor: una que no sea [instante, uuid] no puede salir de aqui.
        if (page.items.any { parseInstantOrNull(it.ordenEn) == null || !it.examenes.id.isUuid() }) {
            throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
        return page
    }

    /** El intento mas reciente por examen entre [examIds] (para ADQUIRIDOS). Acotado: sin el no se inventa progreso. */
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

    /** V1: nombre legible de cada comunidad por codigo (tabla pequena y acotada). Nunca se muestra el codigo crudo (ES-MD). */
    fun communityNames(): Map<String, String> =
        decodeList<CommunityNameRow>(upstream.get("/rest/v1/comunidades_autonomas?select=codigo,nombre&order=codigo.asc&limit=200"))
            .associate { it.codigo to it.nombre }

    /** `capacidades_especialidad` por especialidad; sin fila = abierto (default de la tabla). */
    /** D2-17: especialidades ACTIVAS (catalogo acotado). Pide [max] + 1 para que el llamante falle cerrado si hay mas. */
    fun activeSpecialties(max: Int): List<SpecialtyRow> =
        decodeList(upstream.get("/rest/v1/especialidades?select=id,codigo,nombre,slug&activa=eq.true&order=posicion.asc,nombre.asc,id.asc&limit=${max + 1}"))

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
                "portada_storage_path,publicado_en,demo_disponible,comprable_con_tickets,precio_tickets,dias_acceso_compra,comunidades_autonomas(codigo)"
        internal const val LATEST_ATTEMPTS_FETCH_LIMIT = 500

        /** `supabase/migrations/20261008100300_slice2_my_exams_read_rpc.sql` (STABLE, EXECUTE solo `service_role`). */
        internal const val MY_COLLECTION_RPC = "app_my_exams_collection"

        /** Codigo de PostgREST para "no encuentro la funcion" (falta una migracion): infraestructura, no dominio. */
        private const val RPC_NOT_FOUND = "PGRST202"
        internal const val GRANT_FACTS_FETCH_LIMIT = 500
    }
}
