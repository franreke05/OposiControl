package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// =====================================================================
// FA13 (`openapi/commerce/commerce.yaml`): lecturas de catalogo de administracion D7-17 (productos comerciales),
// D7-21 (planes), D6-18 (servicios), D6-20 (promociones) y D6-22 (reglas de popup).
//
// Las cinco viven en la MISMA ruta que ya llama el cliente OposiControl, que lee `{data: [...]}` sin parametros. El cable
// solo CRECE: pagina OFFSET canonica (`items`, `total`, `limit`, `offset`) y `data` duplica `items` hasta que el cliente migre.
// Los elementos conservan los nombres de campo que ese cliente ya consume. Aqui solo se sanea la peticion: todo literal del
// cliente que llega a un filtro PostgREST es un booleano, un valor de un CHECK cerrado o un texto acotado y codificado.
// =====================================================================

/** `{items, total, limit, offset}` + el duplicado legacy `data`. `total` es el conteo exacto del mismo filtro. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminCatalogPage<T>(
    @EncodeDefault val items: List<T> = emptyList(),
    /** DEPRECATED: duplicado de [items] hasta que el cliente OC lea `items`. */
    @EncodeDefault val data: List<T> = emptyList(),
    val total: Int? = null,
    @EncodeDefault val limit: Int = AdminCatalogQueries.DEFAULT_LIMIT,
    @EncodeDefault val offset: Int = 0,
) {
    companion object {
        internal fun <T> of(rows: List<T>, total: Int?, query: AdminCatalogQuery): AdminCatalogPage<T> =
            AdminCatalogPage(items = rows, data = rows, total = total, limit = query.limit, offset = query.offset)
    }
}

/** Peticion ya validada de una lectura de catalogo. [tipo] y [pantalla] solo se rellenan en las rutas que los admiten. */
internal data class AdminCatalogQuery(
    val limit: Int,
    val offset: Int,
    val activo: Boolean? = null,
    val tipo: String? = null,
    val pantalla: String? = null,
) {
    /** `&limit=..&offset=..` ya numericos. */
    val range: String get() = "&limit=$limit&offset=$offset"

    /** `&activo=eq.true|false` o vacio. */
    val activoFilter: String get() = activo?.let { "&activo=eq.$it" }.orEmpty()
}

/**
 * D7-22 `PUT /api/v1/admin/planes/{codigo}`: el codigo va en la ruta; el cuerpo no admite campos desconocidos.
 *
 * No es un `@CanonicalCommand`: la regla de arquitectura de esos DTO prohibe por NOMBRE el campo `posicion` (lo trata como
 * posicion de ranking, que es del servidor). Aqui `posicion` es el orden de presentacion del catalogo, que fija el
 * administrador y el contrato exige. El cuerpo se lee con [AdminCatalogCommands.plan], igual de estricto (mismo criterio que
 * los comandos de noticias, que tambien llevan `posicion`).
 */
internal data class PlanPutCommand(
    val nombre: String,
    val descripcion: String? = null,
    val nivel: Int,
    val activo: Boolean,
    val visible: Boolean,
    val posicion: Int,
)

/** D7-19 `PUT /api/v1/admin/comercial/productos/{codigo}/tarifas/{periodo}/{fase}`: producto, periodo y fase van en la ruta. */
internal data class TarifaPutCommand(
    val precioCentimos: Int,
    val moneda: String,
    val activo: Boolean,
    val visible: Boolean,
    val posicion: Int? = null,
)

/** El recurso guardado y si se CREO (201) o ya existia (200). */
internal data class CatalogUpsert<T>(val value: T, val created: Boolean)

internal object AdminCatalogCommands {
    /** CHECK `tarifas_producto_comercial_periodo_facturacion_check` y `..._fase_precio_check` de produccion. */
    val TARIFA_PERIODOS: Set<String> = setOf("mensual", "anual", "unitario")
    val TARIFA_FASES: Set<String> = setOf("first_year", "renewal", "standard")

    /** CHECK `planes_suscripcion_canonical_v2_nivel_check`. */
    const val PLAN_NIVEL_MAX = 3

    private val CODIGO = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    /** Codigo de la ruta: acotado y sin caracteres con significado en un filtro PostgREST, o 400 VALIDATION. */
    fun codigo(raw: String): String =
        raw.trim().takeIf { CODIGO.matches(it) } ?: throw validationError(ErrorIssue("codigo", CanonicalIssue.INVALID_FORMAT))

    fun periodo(raw: String): String =
        raw.takeIf { it in TARIFA_PERIODOS } ?: throw validationError(ErrorIssue("periodo", CanonicalIssue.INVALID_FORMAT))

    fun fase(raw: String): String =
        raw.takeIf { it in TARIFA_FASES } ?: throw validationError(ErrorIssue("fase", CanonicalIssue.INVALID_FORMAT))

    // ---------- cuerpo estricto: JSON roto -> 400 MALFORMED_BODY; campo desconocido, ausente o de otro tipo -> 400 VALIDATION ----------

    private val SAFE_FIELD_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,40}$")

    private class Body(raw: ByteArray, allowed: Set<String>) {
        val issues = mutableListOf<ErrorIssue>()
        private val obj: JsonObject = runCatching { Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()

        init {
            // El nombre de un campo desconocido solo se repite si es un identificador inocuo; nunca el valor.
            obj.keys.filter { it !in allowed }.forEach { issues += ErrorIssue(if (SAFE_FIELD_NAME.matches(it)) it else "(desconocido)", CanonicalIssue.UNKNOWN_FIELD) }
        }

        private fun primitive(name: String, required: Boolean): JsonPrimitive? {
            val value = obj[name]
            if (value == null || value is JsonNull) {
                if (required) issues += ErrorIssue(name, CanonicalIssue.REQUIRED)
                return null
            }
            return (value as? JsonPrimitive) ?: null.also { issues += ErrorIssue(name, CanonicalIssue.INVALID_FORMAT) }
        }

        fun string(name: String, required: Boolean = true): String? =
            primitive(name, required)?.let { p -> if (p.isString) p.content else null.also { issues += ErrorIssue(name, CanonicalIssue.INVALID_FORMAT) } }

        fun int(name: String, required: Boolean = true): Int? =
            primitive(name, required)?.let { p -> (if (p.isString) null else p.content.toIntOrNull()) ?: null.also { issues += ErrorIssue(name, CanonicalIssue.INVALID_FORMAT) } }

        fun bool(name: String): Boolean? =
            primitive(name, true)?.let { p -> (if (p.isString) null else p.content.toBooleanStrictOrNull()) ?: null.also { issues += ErrorIssue(name, CanonicalIssue.INVALID_FORMAT) } }

        fun <T> build(block: () -> T): T {
            if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
            return block()
        }
    }

    /** Cuerpo de D7-22. */
    fun plan(raw: ByteArray): PlanPutCommand {
        val body = Body(raw, setOf("nombre", "descripcion", "nivel", "activo", "visible", "posicion"))
        val nombre = body.string("nombre")
        val descripcion = body.string("descripcion", required = false)
        val nivel = body.int("nivel")
        val activo = body.bool("activo")
        val visible = body.bool("visible")
        val posicion = body.int("posicion")
        return body.build { PlanPutCommand(nombre!!, descripcion, nivel!!, activo!!, visible!!, posicion!!) }
    }

    /** Cuerpo de D7-19. El precio es un ENTERO de centimos: un decimal es 400, nunca se redondea. */
    fun tarifa(raw: ByteArray): TarifaPutCommand {
        val body = Body(raw, setOf("precioCentimos", "moneda", "activo", "visible", "posicion"))
        val precio = body.int("precioCentimos")
        val moneda = body.string("moneda")
        val activo = body.bool("activo")
        val visible = body.bool("visible")
        val posicion = body.int("posicion", required = false)
        return body.build { TarifaPutCommand(precio!!, moneda!!, activo!!, visible!!, posicion) }
    }
}

internal object AdminCatalogQueries {
    /**
     * El cliente actual pide el catalogo entero sin parametros, asi que el defecto es el maximo: una pagina de 100 sigue
     * devolviendo todo un catalogo que hoy cabe de sobra, y `total` delata el dia que no quepa.
     */
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 100
    private const val PANTALLA_MAX = 100

    /** CHECK `productos_comerciales_tipo_producto_check` de produccion. */
    val PRODUCT_TIPOS: Set<String> = setOf("suscripcion", "pack_tickets", "compra_examen", "servicio", "consumible")

    private fun invalidFilter(field: String): Nothing =
        throw CanonicalErrorCode.INVALID_FILTER.exception(details = ErrorDetails(issues = listOf(ErrorIssue(field, CanonicalIssue.INVALID_FORMAT))))

    /**
     * `limit` ausente -> 100; no entero o < 1 -> 400 VALIDATION (OUT_OF_RANGE); > 100 -> 100. `offset` no entero -> 400;
     * negativo -> 0. `activo` solo `true|false`. [withTipo] / [withPantalla] activan los filtros propios de cada ruta.
     */
    fun parse(params: Parameters, withTipo: Boolean = false, withPantalla: Boolean = false): AdminCatalogQuery {
        val limit = params["limit"]?.takeIf { it.isNotBlank() }?.let {
            it.trim().toIntOrNull()?.takeIf { n -> n >= 1 }?.coerceAtMost(MAX_LIMIT) ?: throw validationError(ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE))
        } ?: DEFAULT_LIMIT
        val offset = params["offset"]?.takeIf { it.isNotBlank() }?.let {
            it.trim().toIntOrNull()?.coerceIn(0, AdminPageRequest.MAX_OFFSET) ?: throw validationError(ErrorIssue("offset", CanonicalIssue.OUT_OF_RANGE))
        } ?: 0
        val activo = params["activo"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> invalidFilter("activo")
            }
        }
        val tipo = if (!withTipo) null else params["tipo"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it !in PRODUCT_TIPOS) invalidFilter("tipo") }
        val pantalla = if (!withPantalla) null else params["pantalla"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > PANTALLA_MAX) invalidFilter("pantalla") }
        return AdminCatalogQuery(limit, offset, activo, tipo, pantalla)
    }
}
