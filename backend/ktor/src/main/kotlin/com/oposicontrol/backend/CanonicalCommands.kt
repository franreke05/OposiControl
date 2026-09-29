package com.oposicontrol.backend

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

// =====================================================================
// AUTORIDAD DEL SERVIDOR (SLICE 0, `specs.serverAuthoritative`, FROZEN)
//
// Cada campo de un DTO de escritura pertenece a UNA categoria. En los DTO de
// comando de la app (cliente) solo cabe USER_INPUT; el resto —identidad, ids,
// marcas de tiempo, estados, revisiones, correccion, dinero, rankings— lo
// decide el servidor. Los DTO de admin pueden llevar ADMIN_DERIVED, pero nunca
// el id del actor (actor = JWT sub) ni un resultado FINANCIERO.
//
// Aplicacion: los DTO de comando canonicos se marcan con [CanonicalCommand],
// decodifican ESTRICTO ([receiveCanonicalCommand]: campo desconocido -> 400
// VALIDATION UNKNOWN_FIELD) y un test de arquitectura recorre todos los
// marcados y falla si alguno contiene un campo prohibido
// ([ServerAuthoritativeRules.violations]).
// =====================================================================

enum class FieldCategory { USER_INPUT, SERVER_DERIVED, ADMIN_DERIVED, FINANCIAL, RANKING_PERFORMANCE }

enum class CommandAudience { APP, ADMIN }

/** Marca un `@Serializable` como DTO de comando canonico (cuerpo de peticion de escritura). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CanonicalCommand(val audience: CommandAudience = CommandAudience.APP)

/**
 * La clasificacion por NOMBRE de campo (serial name). Se normaliza a minusculas
 * sin `_`, asi `usuario_id` y `usuarioId` son el mismo campo.
 */
object ServerAuthoritativeRules {

    /** Id del actor: nunca en un comando, ni de app ni de admin (actor = JWT sub). */
    private val ACTOR_IDS = setOf(
        "usuarioid", "userid", "actorid", "adminid", "moderadorid", "ejecutadopor", "creadopor",
        "actualizadopor", "executorid", "executedby", "createdby",
    )

    private val SERVER_DERIVED_EXACT = setOf(
        "id", "revision", "escorrecta", "iscorrect", "esprimerarespuesta", "deadline", "duracion",
        "duracionsegundos", "accessstate", "onboardingcompleted", "onboardingcompletado", "accountstatus",
        "codigo", "creado", "actualizado",
    )
    private val SERVER_DERIVED_PREFIXES = listOf("estado", "primera", "primer", "total")

    /** Lo que ni un admin puede fijar aunque no sea actor ni marca de tiempo. */
    private val ADMIN_FORBIDDEN_EXACT = setOf("revision", "escorrecta", "iscorrect", "esprimerarespuesta", "saldo", "balance")

    private val ADMIN_DERIVED_EXACT = setOf(
        "roles", "roleids", "rolids", "esstaff", "tipoacceso", "anulada", "motivoanulacion", "publicado",
        "diasacceso", "estadocuenta", "capabilities", "grants",
    )

    private val FINANCIAL_EXACT = setOf(
        "saldo", "balance", "cantidad", "tickets", "ispremium", "espremium", "descuento", "credits", "creditos",
        "importe", "amount",
    )
    private val FINANCIAL_PREFIXES = listOf("precio", "saldo", "price")

    private val RANKING_EXACT = setOf(
        "nota", "calificacion", "band", "notamedia", "evolucion", "liga", "posicion", "percentil", "racha",
        "dificultad", "stats", "puntuacion", "score",
    )

    /**
     * USER_INPUT que colisionaria con una regla por prefijo o sufijo:
     * `estadoLaboral` es dato de perfil del usuario; `occurredEn` es la hora
     * ADVISORY del cliente (SEC-010), que el servidor nunca toma por buena.
     */
    private val USER_INPUT_ALLOWLIST = setOf("estadolaboral", "occurreden", "ocurridoen", "occurredat")

    fun categoryOf(serialName: String): FieldCategory {
        val normalized = serialName.lowercase().replace("_", "")
        if (normalized in USER_INPUT_ALLOWLIST) return FieldCategory.USER_INPUT
        return when {
            normalized in ACTOR_IDS -> FieldCategory.SERVER_DERIVED
            normalized in FINANCIAL_EXACT || FINANCIAL_PREFIXES.any { normalized.startsWith(it) } -> FieldCategory.FINANCIAL
            normalized in RANKING_EXACT -> FieldCategory.RANKING_PERFORMANCE
            normalized in ADMIN_DERIVED_EXACT -> FieldCategory.ADMIN_DERIVED
            normalized in SERVER_DERIVED_EXACT -> FieldCategory.SERVER_DERIVED
            SERVER_DERIVED_PREFIXES.any { normalized.startsWith(it) } -> FieldCategory.SERVER_DERIVED
            isTimestampName(serialName) -> FieldCategory.SERVER_DERIVED
            else -> FieldCategory.USER_INPUT
        }
    }

    /** `creadoEn`, `iniciado_en`, `respondidaEn`: marcas de tiempo, siempre `now()` de la base. */
    private fun isTimestampName(name: String): Boolean =
        name.endsWith("_en") ||
            (name.length > 2 && name.endsWith("En") && name[name.length - 3].isLowerCase())

    /** ¿Puede [serialName] aparecer en un comando de [audience]? */
    fun isAllowed(serialName: String, audience: CommandAudience): Boolean {
        val normalized = serialName.lowercase().replace("_", "")
        return when (audience) {
            CommandAudience.APP -> categoryOf(serialName) == FieldCategory.USER_INPUT
            // Admin: puede fijar lo ADMIN_DERIVED (estado, publicacion, precios de
            // catalogo...), nunca el actor, marcas de tiempo, revision/correccion,
            // saldos ni rankings calculados.
            CommandAudience.ADMIN -> when {
                normalized in ACTOR_IDS -> false
                isTimestampName(serialName) && normalized !in USER_INPUT_ALLOWLIST -> false
                normalized in ADMIN_FORBIDDEN_EXACT -> false
                categoryOf(serialName) == FieldCategory.RANKING_PERFORMANCE -> false
                else -> true
            }
        }
    }

    /**
     * Los campos prohibidos de [descriptor] para [audience], recorriendo clases
     * anidadas y listas. Cada violacion es `ruta.campo (CATEGORIA)`.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun violations(descriptor: SerialDescriptor, audience: CommandAudience, path: String = ""): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun walk(current: SerialDescriptor, prefix: String) {
            if (!seen.add(current.serialName + "@" + prefix)) return
            if (current.kind != StructureKind.CLASS) return
            for (index in 0 until current.elementsCount) {
                val name = current.getElementName(index)
                val fieldPath = if (prefix.isEmpty()) name else "$prefix.$name"
                if (!isAllowed(name, audience)) out += "$fieldPath (${categoryOf(name)})"
                var child = current.getElementDescriptor(index)
                while (child.kind == StructureKind.LIST) child = child.getElementDescriptor(0)
                // RB-1-03: un Map / JsonObject / JsonElement / sealed / contextual es una bolsa
                // opaca: el escaneo por nombre no ve dentro, asi que un cliente podria colar
                // cualquier campo del servidor. Prohibido en un comando canonico.
                if (child.kind == StructureKind.MAP || child.kind is PolymorphicKind || child.kind == SerialKind.CONTEXTUAL) {
                    out += "$fieldPath (OPAQUE_STRUCTURE)"
                }
                walk(child, fieldPath)
            }
        }
        walk(descriptor, path)
        return out
    }
}

/** Json ESTRICTO de los comandos canonicos: campos desconocidos se rechazan (a mano, con detalle). */
val CanonicalCommandJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    explicitNulls = false
}

private val SAFE_FIELD_NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
private const val UNKNOWN_FIELD_PLACEHOLDER = "(desconocido)"

/**
 * Decodifica un comando canonico ESTRICTO desde el cuerpo crudo cacheado
 * ([canonicalRawBody], compatible con el plugin de idempotencia).
 *
 * - Content-Type no JSON con cuerpo -> 415.
 * - Cuerpo vacio -> `{}` (un comando con todos los campos opcionales es valido).
 * - JSON roto o no objeto -> 400 MALFORMED_BODY.
 * - Campo desconocido -> 400 VALIDATION `{field, UNKNOWN_FIELD}`.
 * - Campo obligatorio ausente -> 400 VALIDATION `{field, REQUIRED}`.
 * - Tipo o valor de enum incorrecto -> 400 VALIDATION `{field?, INVALID_FORMAT}`.
 *
 * Ningun mensaje repite la entrada del cliente.
 */
internal suspend inline fun <reified T> ApplicationCall.receiveCanonicalCommand(): T {
    requireCanonicalCommandType(T::class.java)
    return decodeCanonicalCommand(serializer<T>(), canonicalRawBody().also { requireJsonContentIfBody(it) })
}

/**
 * RC-1-02: solo un DTO marcado con [CanonicalCommand] se decodifica como comando
 * canonico. Asi ningun DTO puede usar la decodificacion estricta y escapar del
 * test de arquitectura (ServerAuthoritativeTest), que solo recorre los marcados.
 */
internal fun requireCanonicalCommandType(type: Class<*>) {
    check(type.isAnnotationPresent(CanonicalCommand::class.java)) { "${type.name} must be @CanonicalCommand" }
}

/**
 * Tope de `details.issues` en una respuesta 400 (RB-1-09): un cuerpo con miles de
 * campos desconocidos o ausentes no se amplifica en la respuesta. Igual que
 * `maxItems` de `ErrorDetails.issues` en `openapi/common/errors.yaml`.
 */
internal const val MAX_VALIDATION_ISSUES = 20

/**
 * `internal` (PF2-2-02): la unica entrada publica es [receiveCanonicalCommand],
 * que exige la marca [CanonicalCommand]; asi la decodificacion estricta no evita
 * el test de arquitectura.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> decodeCanonicalCommand(strategy: DeserializationStrategy<T>, rawBody: ByteArray): T {
    val text = String(rawBody, Charsets.UTF_8).ifBlank { "{}" }
    val element = try {
        CanonicalCommandJson.parseToJsonElement(text)
    } catch (_: SerializationException) {
        throw CanonicalErrorCode.MALFORMED_BODY.exception()
    } catch (_: IllegalArgumentException) {
        throw CanonicalErrorCode.MALFORMED_BODY.exception()
    }
    if (element !is JsonObject) throw CanonicalErrorCode.MALFORMED_BODY.exception()
    val unknown = unknownFields(strategy.descriptor, element, "")
    if (unknown.isNotEmpty()) {
        throw validationError(*unknown.take(MAX_VALIDATION_ISSUES).map { ErrorIssue(it, CanonicalIssue.UNKNOWN_FIELD) }.toTypedArray())
    }
    return try {
        CanonicalCommandJson.decodeFromJsonElement(strategy, element)
    } catch (missing: MissingFieldException) {
        throw validationError(*missing.missingFields.take(MAX_VALIDATION_ISSUES).map { ErrorIssue(it, CanonicalIssue.REQUIRED) }.toTypedArray())
    } catch (_: SerializationException) {
        throw validationError(ErrorIssue("body", CanonicalIssue.INVALID_FORMAT))
    } catch (_: IllegalArgumentException) {
        throw validationError(ErrorIssue("body", CanonicalIssue.INVALID_FORMAT))
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun unknownFields(descriptor: SerialDescriptor, value: JsonElement, prefix: String): List<String> {
    if (descriptor.kind == StructureKind.LIST && value is JsonArray) {
        val element = descriptor.getElementDescriptor(0)
        return value.flatMapIndexed { index, item -> unknownFields(element, item, "$prefix[$index]") }
    }
    if (descriptor.kind != StructureKind.CLASS || value !is JsonObject) return emptyList()
    val known = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
    return value.entries.flatMap { (key, child) ->
        val index = known[key]
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        when {
            index == null -> listOf(if (SAFE_FIELD_NAME.matches(key)) path else UNKNOWN_FIELD_PLACEHOLDER)
            else -> unknownFields(descriptor.getElementDescriptor(index), child, path)
        }
    }
}
