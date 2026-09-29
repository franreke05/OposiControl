package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

class AppUnauthorized(message: String) : RuntimeException(message)

class AppForbidden(message: String) : RuntimeException(message)

/**
 * [identityResolver] (SLICE 1, obligacion de corte de SLICE 0): con el, toda ruta legacy de `/api/v1/app` con JWT pasa
 * por `activeIdentity` (deny-by-default): solo una cuenta `activa` explicita opera; bloqueada / eliminacion pendiente /
 * desconocida / sin perfil -> 403. Para una cuenta activa la respuesta no cambia. `null` solo en pruebas aisladas.
 */
fun Route.appV1Routes(
    gateway: AppV1Gateway,
    verifier: SupabaseJwtVerifier,
    allowDevUser: Boolean,
    identityResolver: CanonicalIdentityResolver? = null,
) {
    route("/api/v1/app") {
        get("/home") {
            call.respond(gateway.home(call.usuarioId(verifier, allowDevUser, identityResolver)))
        }
        get("/examenes") {
            call.usuarioId(verifier, allowDevUser, identityResolver) // requiere sesion
            call.respond(AppExamenesResponse(gateway.listExamenes()))
        }
        get("/examenes/{id}") {
            call.usuarioId(verifier, allowDevUser, identityResolver)
            val detalle = gateway.examenDetalle(call.requiredPath("id"))
                ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorDto("not_found", "Examen no encontrado."))
            call.respond(detalle)
        }
        post("/examenes/{id}/iniciar") {
            val uid = call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(io.ktor.http.HttpStatusCode.Created, AppIntentoResponse(gateway.iniciar(uid, call.requiredPath("id"))))
        }
        post("/intentos/{id}/responder") {
            val uid = call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(gateway.responder(uid, call.requiredPath("id"), call.receive()))
        }
        post("/intentos/{id}/finalizar") {
            val uid = call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(AppIntentoResponse(gateway.finalizar(uid, call.requiredPath("id"))))
        }
        get("/estadisticas") {
            call.respond(AppEstadisticasResponse(gateway.estadisticas(call.usuarioId(verifier, allowDevUser, identityResolver))))
        }
        get("/commercial/catalog") {
            call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(gateway.commercialCatalog())
        }
        get("/commercial/purchases") {
            call.respond(gateway.commercialPurchases(call.usuarioId(verifier, allowDevUser, identityResolver)))
        }
        get("/commercial/subscriptions") {
            call.respond(gateway.commercialSubscriptions(call.usuarioId(verifier, allowDevUser, identityResolver)))
        }
        get("/reto-diario") {
            call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(AppRetoResponse(gateway.retoDiario()))
        }
        post("/feedback/pregunta") {
            val uid = call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(io.ktor.http.HttpStatusCode.Created, OkResponse(id = gateway.crearFeedback(uid, call.receive())))
        }
        post("/eventos") {
            val uid = call.usuarioId(verifier, allowDevUser, identityResolver)
            call.respond(io.ktor.http.HttpStatusCode.Created, OkResponse(id = gateway.registrarEvento(uid, call.receive())))
        }
    }
}

/** Resuelve el usuario: JWT de Supabase (produccion) o cabecera X-Usuario-Id (solo dev). */
private suspend fun ApplicationCall.usuarioId(
    verifier: SupabaseJwtVerifier,
    allowDevUser: Boolean,
    identityResolver: CanonicalIdentityResolver?,
): String {
    val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
    if (!bearer.isNullOrBlank() && bearer.count { it == '.' } == 2) {
        if (identityResolver != null) return identityResolver.activeIdentity(this).userId.value
        return try {
            verifier.subFrom(bearer)
        } catch (error: Exception) {
            throw AppUnauthorized("JWT invalido: ${error.message?.take(120)}")
        }
    }
    if (allowDevUser) {
        request.headers["X-Usuario-Id"]?.takeIf { it.isNotBlank() }?.let { return it }
    }
    throw AppUnauthorized("Falta identidad: envia un JWT de Supabase (Authorization: Bearer) o, en dev, X-Usuario-Id.")
}

// ---------- Verificador JWT (ES256 contra el JWKS publico de Supabase) ----------

private const val JWKS_MIN_REFETCH_MS = 5_000L

/**
 * Verifica la firma ES256 contra el JWKS público y devuelve el `sub`.
 *
 * [subFrom] BLOQUEA cuando tiene que descargar el JWKS (arranque, `kid` nuevo o
 * rotación): en el pipeline se llama dentro de [withUpstreamIo] vía
 * [verifiedJwtPrincipalOrNull] (F-03), nunca en el hilo de Netty. Usa el cliente
 * compartido: un `HttpClient` propio era un pool y un executor más contra el
 * mismo host sin ninguna ganancia.
 */
class SupabaseJwtVerifier(
    supabaseUrl: String,
    private val json: Json,
    private val client: HttpClient = sharedUpstreamHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val issuer = "$supabaseUrl/auth/v1"
    private val jwksUrl = "$issuer/.well-known/jwks.json"

    @Volatile private var keys: Map<String, ECPublicKey> = emptyMap()
    @Volatile private var fetchedAt = 0L
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastFailure: java.io.IOException? = null

    /**
     * RB-1-06: un JWKS que no se puede obtener o leer (red, timeout, HTTP no 2xx, cuerpo que no es
     * un JWKS, sin claves) lanza [java.io.IOException]: el llamador canonico lo traduce a 503
     * reintentable. Un token que no verifica (firma, emisor, `kid` desconocido con un JWKS sano)
     * lanza otra excepcion: 401.
     */
    fun subFrom(token: String): String = claimsFrom(token).subject

    /**
     * Misma verificacion que [subFrom], devolviendo ademas `iat` y las marcas de
     * `amr` (SLICE 1, reautenticacion OAuth del borrado de cuenta).
     */
    fun claimsFrom(token: String): VerifiedJwtClaims {
        val decoded = JWT.decode(token)
        val kid = decoded.keyId ?: error("token sin kid")
        val key = keys[kid] ?: run { refresh(); keys[kid] } ?: run {
            // Sin ninguna clave no se puede afirmar que el kid sea desconocido: JWKS no disponible.
            if (keys.isEmpty()) throw java.io.IOException("JWKS sin claves")
            error("kid no encontrado en JWKS")
        }
        val verified = JWT.require(Algorithm.ECDSA256(key, null)).withIssuer(issuer).build().verify(token)
        val subject = verified.subject ?: error("token sin sub")
        return VerifiedJwtClaims(
            subject = subject,
            issuedAt = verified.issuedAt?.toInstant()?.epochSecond,
            authTimes = amrTimestamps(verified.payload),
        )
    }

    /** `amr: [{method, timestamp}]` del payload (base64url JSON). Cualquier forma inesperada = sin marcas. */
    private fun amrTimestamps(payloadB64: String): List<Long> = runCatching {
        val payload = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payloadB64), Charsets.UTF_8)).jsonObject
        payload["amr"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("timestamp")?.jsonPrimitive?.longOrNull }
    }.getOrDefault(emptyList())

    /**
     * PF3-3-04: el anti-tormenta cubre TAMBIEN los intentos fallidos (cache negativa). Con el JWKS caido
     * o la cache vacia, [fetchedAt] nunca avanzaba y cada peticion volvia a bloquear hasta
     * UPSTREAM_REQUEST_TIMEOUT dentro del lock, agotando el pool de IO. Dentro de la ventana se reutiliza
     * el ultimo fallo (un [java.io.IOException] -> 503) sin tocar la red.
     */
    @Synchronized
    private fun refresh() {
        // Anti-tormenta corto (no 60s): una rotacion de claves no debe dejar 401 durante un minuto.
        val now = clock()
        if (lastAttemptAt != 0L && now - lastAttemptAt < JWKS_MIN_REFETCH_MS) {
            lastFailure?.let { throw java.io.IOException(it.message, it) }
            return
        }
        lastAttemptAt = now
        val parsed = try {
            fetchJwks()
        } catch (io: java.io.IOException) {
            lastFailure = io
            throw io
        } catch (bad: Exception) {
            val wrapped = java.io.IOException(bad.message ?: "JWKS no disponible", bad)
            lastFailure = wrapped
            throw wrapped
        }
        lastFailure = null
        keys = parsed
        fetchedAt = clock()
    }

    private fun fetchJwks(): Map<String, ECPublicKey> {
        val response = client.send(
            HttpRequest.newBuilder(URI.create(jwksUrl)).timeout(UPSTREAM_REQUEST_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) throw java.io.IOException("JWKS HTTP ${response.statusCode()}")
        return try {
            val array = json.parseToJsonElement(response.body()).jsonObject["keys"]?.jsonArray
                ?: throw java.io.IOException("JWKS malformado")
            array.mapNotNull { el ->
                val k = el.jsonObject
                if (k["kty"]?.jsonPrimitive?.contentOrNull != "EC") return@mapNotNull null
                val kid = k["kid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                kid to ecPublicKey(
                    k["x"]!!.jsonPrimitive.content,
                    k["y"]!!.jsonPrimitive.content,
                )
            }.toMap()
        } catch (io: java.io.IOException) {
            throw io
        } catch (bad: Exception) {
            throw java.io.IOException("JWKS malformado", bad)
        }
    }

    private fun ecPublicKey(xB64Url: String, yB64Url: String): ECPublicKey {
        val x = BigInteger(1, Base64.getUrlDecoder().decode(xB64Url))
        val y = BigInteger(1, Base64.getUrlDecoder().decode(yB64Url))
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val spec = params.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }
}

// ---------- DTOs ----------

@Serializable data class AppPerfilDto(val usuarioId: String, val nombreMostrar: String? = null, val username: String? = null, val esInvitado: Boolean = false, val onboardingCompletado: Boolean = false)
@Serializable data class AppExamenDto(val id: String, val titulo: String, val descripcion: String = "", val tipoExamen: String = "", val tipoAcceso: String = "", val totalPreguntas: Int = 0, val anio: Int? = null)
@Serializable data class AppOpcionDto(val id: String, val letra: String, val texto: String)
/**
 * Una pregunta servida. [anulada] = el tribunal la anulo EN ESTE examen (propiedad de la relacion
 * pregunta-examen): se muestra en su posicion original pero NO puntua y no admite respuesta que cuente.
 * [motivoAnulacion] es opcional y solo viene con `anulada = true`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppPreguntaDto(
    val id: String,
    val titulo: String,
    val posicion: Int,
    val opciones: List<AppOpcionDto>,
    @EncodeDefault val anulada: Boolean = false,
    val motivoAnulacion: String? = null,
)
@Serializable data class AppRetoDto(val id: String, val fecha: String, val titulo: String, val descripcion: String = "", val objetivoPreguntas: Int = 0, val recompensaPuntos: Int = 0)
@Serializable data class AppEstadisticasDto(val usuarioId: String, val examenesFinalizados: Int = 0, val preguntasRespondidas: Int = 0, val respuestasCorrectas: Int = 0, val porcentajeAcierto: Double = 0.0, val puntosTotales: Int = 0, val nivel: Int = 1)
@Serializable data class AppIntentoDto(val id: String, val examenId: String, val estado: String, val totalPreguntas: Int = 0, val totalRespondidas: Int = 0, val totalCorrectas: Int = 0, val porcentajeAcierto: Double = 0.0)

@Serializable data class AppHomeResponse(val perfil: AppPerfilDto? = null, val retoDiario: AppRetoDto? = null, val examenes: List<AppExamenDto> = emptyList())
@Serializable data class AppExamenesResponse(val data: List<AppExamenDto>)
/**
 * [preguntas] son TODAS las activas en su posicion original (las anuladas incluidas, con `anulada = true`);
 * [totalEvaluables] = las servidas activas y NO anuladas: el denominador real del intento.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppExamenDetalleResponse(
    val examen: AppExamenDto,
    val preguntas: List<AppPreguntaDto>,
    @EncodeDefault val totalEvaluables: Int = 0,
)
@Serializable data class AppIntentoResponse(val data: AppIntentoDto)
@Serializable data class AppEstadisticasResponse(val data: AppEstadisticasDto? = null)
@Serializable data class AppRetoResponse(val data: AppRetoDto? = null)
@Serializable data class OkResponse(val ok: Boolean = true, val id: String? = null)

@Serializable
data class CommercialCatalogResponse(
    val products: List<CommercialProductDto> = emptyList(),
    val plans: List<CommercialPlanDto> = emptyList(),
)

@Serializable
data class CommercialProductDto(
    val id: String,
    val code: String,
    val productType: String,
    val name: String,
    val description: String? = null,
    val planId: String? = null,
    val tariffs: List<CommercialTariffDto> = emptyList(),
)

@Serializable
data class CommercialTariffDto(
    val id: String,
    val billingPeriod: String,
    val pricePhase: String,
    val priceCents: Int,
    val currency: String,
    val active: Boolean,
    val visible: Boolean,
    val position: Int,
)

@Serializable
data class CommercialPlanDto(
    val id: String,
    val code: String,
    val name: String,
    val description: String? = null,
    val level: Int,
    val benefits: List<CommercialBenefitDto> = emptyList(),
)

@Serializable
data class CommercialBenefitDto(
    val id: String,
    val code: String,
    val booleanValue: Boolean? = null,
    val integerValue: Int? = null,
    val numericValue: Double? = null,
    val textValue: String? = null,
)

@Serializable data class CommercialPurchasesResponse(val data: List<CommercialPurchaseDto>)
@Serializable data class CommercialSubscriptionsResponse(val data: List<CommercialSubscriptionDto>)
@Serializable
data class CommercialPurchaseDto(
    val id: String,
    val provider: String,
    val productCode: String,
    val productType: String,
    val status: String,
    val priceCents: Int? = null,
    val currency: String,
    val externalTransactionId: String? = null,
    val purchasedAt: String? = null,
)

@Serializable
data class CommercialSubscriptionDto(
    val id: String,
    val provider: String,
    val productCode: String,
    val status: String,
    val startedAt: String,
    val endedAt: String? = null,
    val renewsAt: String? = null,
    val externalId: String? = null,
)

@Serializable data class ResponderRequest(val preguntaId: String, val opcionSeleccionadaId: String)
/**
 * La respuesta a `POST /intentos/{id}/responder`.
 *
 * [anulada] = la pregunta esta anulada en el examen del intento: NO se guarda respuesta, [esCorrecta] es
 * `false` (sin significado: ni acierto ni fallo) y [opcionCorrectaId] viene igualmente para poder
 * ensenarla. Sin error: la app no puede tratarla como un fallo de red.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponderResponse(
    val esCorrecta: Boolean,
    val opcionCorrectaId: String? = null,
    @EncodeDefault val anulada: Boolean = false,
)
@Serializable data class AppFeedbackRequest(val preguntaId: String, val tipoFeedback: String = "otro", val descripcion: String = "", val intentoId: String? = null)
@Serializable data class AppEventoRequest(
    val nombreEvento: String,
    val pantalla: String? = null,
    val entidadTipo: String? = null,
    val entidadId: String? = null,
    val propiedades: JsonObject = JsonObject(emptyMap()),
    val eventVersion: Int = 1,
    val occurredEn: String? = null,
    val especialidadId: String? = null,
    val intentoId: String? = null,
    val preguntaId: String? = null,
    val idempotencyKey: String? = null,
)

// ---------- Gateway Supabase (service role; SIEMPRE filtra por usuarioId) ----------

class AppV1Gateway private constructor(
    supabaseUrl: String,
    serviceRoleKey: String,
    private val json: Json,
    client: HttpClient = sharedUpstreamHttpClient,
    /**
     * Reloj de la caché de rol. Parámetro y no `System.currentTimeMillis()` directo
     * sólo para poder probar el TTL sin dormir: "acierta a los 45 s y vuelve a
     * preguntar pasado el TTL" es contrato (F-17), no un detalle de implementación.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Migrado a [SupabaseRequestTransport] (F-02).
     *
     * Este gateway firmaba a mano `apikey` + `Authorization: Bearer <clave>` sin
     * mirar la forma de la clave, con un `HttpClient` propio y sin capturar los
     * fallos de red. Y no es un gateway cualquiera: su [hasAdminRole] decide CADA
     * petición a `/api/v1/admin/…`, así que las cuatro verticales ya migradas
     * seguían autorizándose por el camino legacy. Con una `sb_secret_...` eso era
     * mandarla como `Bearer`, y un `IOException` de red salía como 500
     * `UNCAUGHT_EXCEPTION` en vez de como fallo del servicio de datos.
     *
     * Ahora la credencial la decide [SupabaseRequestAuthPolicy], el cliente es el
     * compartido y cualquier fallo —de red o de PostgREST— pasa por el mismo
     * `failOnError` que el resto del backend.
     */
    private val transport = SupabaseRequestTransport(
        baseUrl = supabaseUrl,
        auth = SupabaseRequestAuthPolicy(serviceRoleKey),
        client = client,
    )

    /** Caché de decisiones de rol admin. Concurrente: la consultan varias peticiones a la vez. */
    private val adminRoleCache = java.util.concurrent.ConcurrentHashMap<String, CachedRoleDecision>()

    /**
     * ¿Tiene rol admin?
     *
     * Se consulta en cada petición administrativa (la autorización se aplica en
     * el hook `AuthenticationChecked`), así que sin caché cada carga de pantalla
     * costaba dos viajes secuenciales a Supabase, y cualquiera con un JWT válido
     * podía amplificar una petición en dos llamadas upstream indefinidamente.
     *
     * TTL corto a propósito: revocar un rol debe surtir efecto en segundos, no en
     * la siguiente sesión. Sólo se cachea el resultado positivo o negativo ya
     * calculado; un fallo de lectura no se cachea, para que un Supabase caído no
     * deje congelada una respuesta equivocada.
     *
     * Bloquea (lectura upstream en caso de fallo de caché): quien lo llame desde
     * el pipeline tiene que hacerlo dentro de [withUpstreamIo], como hace
     * [authorizeAdminCall] (F-03).
     */
    fun hasAdminRole(usuarioId: String): Boolean {
        usuarioId.validatedUuid("usuarioId")
        val now = clock()
        adminRoleCache[usuarioId]?.let { cached ->
            if (now < cached.expiresAtMillis) return cached.hasRole
        }
        val hasRole = hasAnyCanonicalRole(usuarioId, CanonicalAuthorization.adminRoleCodes)
        adminRoleCache[usuarioId] = CachedRoleDecision(hasRole, now + ADMIN_ROLE_CACHE_TTL_MILLIS)
        // Poda barata: sin ella el mapa crece con cada usuario que pasa por aquí.
        if (adminRoleCache.size > ADMIN_ROLE_CACHE_MAX_ENTRIES) {
            adminRoleCache.entries.removeAll { it.value.expiresAtMillis <= now }
        }
        return hasRole
    }

    private data class CachedRoleDecision(val hasRole: Boolean, val expiresAtMillis: Long)

    /**
     * ¿Tiene el usuario alguno de [roleCodes]? UNA lectura (F-17), no dos.
     *
     * Antes eran dos viajes secuenciales —`roles_usuario` y después `roles_app`—
     * por cada fallo de caché. Ahora es un embed de la FK
     * `roles_usuario_rol_id_fkey → roles_app(id)`, verificada en vivo.
     *
     * **`!inner` es la regla, no un detalle de sintaxis.** Sin él, PostgREST
     * aplica el filtro `roles_app.codigo=in.(…)` sólo al recurso EMBEBIDO: devuelve
     * igualmente la fila de `roles_usuario` con `"roles_app": null`, y una lista no
     * vacía se leería como "es admin" para cualquier usuario con cualquier rol. Por
     * eso, además, la decisión se toma leyendo el `codigo` que viene en la fila y no
     * sólo "¿hay filas?": si alguien quitara el `!inner`, la respuesta seguiría
     * siendo la correcta.
     */
    private fun hasAnyCanonicalRole(usuarioId: String, roleCodes: Set<String>): Boolean {
        val roleFilter = roleCodes.joinToString(",")
        val rows = get(
            "/rest/v1/roles_usuario?select=rol_id,roles_app!inner(codigo)" +
                "&usuario_id=eq.$usuarioId&roles_app.codigo=in.($roleFilter)&limit=1",
        )
        return rows.any { row ->
            val codigo = (row["roles_app"] as? JsonObject)?.str("codigo")?.trim()?.lowercase()
            codigo != null && codigo in roleCodes
        }
    }

    fun home(usuarioId: String): AppHomeResponse {
        usuarioId.validatedUuid("usuarioId")
        return AppHomeResponse(
            perfil = perfil(usuarioId),
            retoDiario = retoDiario(),
            examenes = listExamenes().take(5),
        )
    }

    fun listExamenes(): List<AppExamenDto> =
        get("/rest/v1/examenes?select=*&activo=eq.true&publicado_en=not.is.null&order=anio.desc.nullslast,titulo.asc")
            .map { it.toExamenDto() }

    /**
     * El examen con TODAS sus preguntas activas, cada una en su posición original.
     *
     * ### Contrato que consume la app móvil externa (M10, pregunta anulada)
     *
     * - Cada pregunta trae `anulada` (boolean, siempre presente) y, sólo si está anulada, un
     *   `motivoAnulacion` opcional. Es propiedad de la RELACIÓN pregunta↔examen: la misma pregunta
     *   puede estar anulada en este examen y ser normal en otro.
     * - Una pregunta anulada se ENSEÑA en su sitio (para que la numeración del examen oficial
     *   coincida con la del papel) pero NO puntúa: ni acierta, ni falla, ni cuenta como sin responder.
     * - `totalEvaluables` = preguntas servidas activas y NO anuladas. Es el denominador real del
     *   examen y coincide con `total_preguntas` del intento que abre [iniciar].
     * - `examen.totalPreguntas` NO cambia de significado: son las posiciones activas (visibles),
     *   anuladas incluidas.
     * - Al responder una anulada (`POST /intentos/{id}/responder`) la respuesta es
     *   `{ "esCorrecta": false, "anulada": true, "opcionCorrectaId": "…" }` sin error: la app
     *   enseña la opción correcta y no suma nada. Ver [responder].
     */
    fun examenDetalle(examenId: String): AppExamenDetalleResponse? {
        examenId.validatedUuid("examenId")
        val examen = get("/rest/v1/examenes?select=*&id=eq.$examenId&activo=eq.true&publicado_en=not.is.null&limit=1").firstOrNull() ?: return null
        val asociaciones = get("/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId&order=posicion.asc")
        val preguntaIds = asociaciones.mapNotNull { it.str("pregunta_id") }
        if (preguntaIds.isEmpty()) return AppExamenDetalleResponse(examen.toExamenDto(), emptyList())
        val inList = preguntaIds.joinToString(",")
        val preguntasById = get("/rest/v1/preguntas?select=*&id=in.($inList)&activa=eq.true").associateBy { it.str("id") }
        val opcionesByPregunta = get("/rest/v1/opciones_pregunta?select=*&pregunta_id=in.($inList)&order=posicion.asc")
            .groupBy { it.str("pregunta_id") }
        val preguntas = asociaciones.mapNotNull { a ->
            val pid = a.str("pregunta_id"); val p = preguntasById[pid] ?: return@mapNotNull null
            AppPreguntaDto(
                id = pid!!,
                titulo = p.str("titulo").orEmpty(),
                posicion = a.int("posicion") ?: 0,
                anulada = a.bool("anulada") == true,
                motivoAnulacion = a.str("motivo_anulacion")?.takeIf { a.bool("anulada") == true },
                opciones = opcionesByPregunta[pid].orEmpty().map {
                    AppOpcionDto(it.str("id")!!, it.str("letra").orEmpty(), it.str("texto_opcion").orEmpty())
                },
            )
        }
        return AppExamenDetalleResponse(examen.toExamenDto(), preguntas, totalEvaluables = preguntas.count { !it.anulada })
    }

    /**
     * Abre un intento y lo dimensiona CONTANDO las preguntas que la app va a
     * servir, no leyendo `examenes.total_preguntas`.
     *
     * ### Por qué se dejó de leer esa columna (EXAM-002)
     *
     * Cuando se escribió esto, `examenes.total_preguntas` **no tenía escritor**:
     * la cifra que se leía aquí era, para casi cualquier examen, falsa, y este era
     * el único lector que la convertía en daño real —un intento nace con esa cifra,
     * y es la fila de un usuario final—.
     *
     * Desde M1 (`exams_total_preguntas_maintained`) la mantiene la base con triggers
     * y significa exactamente lo mismo que este recuento (preguntas ACTIVAS
     * vinculadas). Se sigue contando en vivo igualmente: son las mismas dos
     * consultas que sirven las preguntas, así que no pueden discrepar de la entrega
     * aunque un trigger fallara o se desactivara.
     *
     * ### Por qué se cuenta con ESTAS dos consultas y no con otras
     *
     * Son exactamente las que usa [examenDetalle] para servir las preguntas del
     * examen, incluido el filtro `activa=eq.true`. Esa igualdad es el punto: si
     * el recuento usara otra definición —por ejemplo contar `preguntas_examen` a
     * secas—, un examen con preguntas archivadas crearía intentos que esperan más
     * preguntas de las que la app va a entregar, y el intento no podría
     * completarse nunca. El recuento tiene que significar lo mismo que la
     * entrega, y la única forma de garantizarlo es preguntarlo igual.
     *
     * Coste: +1 petición por `iniciar`. Es lo que vale no dimensionar el examen
     * de alguien con una columna que nadie mantiene.
     *
     * ### Lo que M1 cerró después
     *
     * Los DOS catálogos que exponen la columna (`AppV1Routes` y el público de
     * `SupabaseV1Gateway`) la servían podrida. M1 la mantiene con triggers sobre
     * `preguntas_examen` **y** sobre `preguntas.activa` —archivar una pregunta
     * cambia el recuento sin tocar la tabla de pertenencia—, así que esos catálogos
     * ya dicen la verdad sin tocar su código.
     *
     * ### Las anuladas no cuentan (M10)
     *
     * `total_preguntas` del intento = preguntas EVALUABLES: servidas (activas) y NO anuladas en
     * este examen (`preguntas_examen.anulada`). La anulada se enseña en su posición pero no puntúa,
     * así que no puede estar en el denominador: si lo estuviera, el intento esperaría respuestas
     * que `record_answer` no guarda y nunca cuadraría. Igual que con las archivadas, el recuento
     * usa la MISMA definición que [examenDetalle] (`totalEvaluables`).
     *
     * Techo conocido y heredado: `id=in.(…)` crece con el número de preguntas y
     * un examen muy largo puede topar con el límite de longitud de URL. Es el
     * mismo techo que ya tiene [examenDetalle]; no se introduce aquí.
     */
    fun iniciar(usuarioId: String, examenId: String): AppIntentoDto {
        usuarioId.validatedUuid("usuarioId")
        examenId.validatedUuid("examenId")
        // Precondición: el examen existe, está activo y está publicado. Sólo se
        // pide el `id` porque lo único que se necesita saber es si la fila está.
        val existe = get(
            "/rest/v1/examenes?select=id&id=eq.$examenId&activo=eq.true&publicado_en=not.is.null&limit=1",
        ).isNotEmpty()
        // K0-6 (EX-15): 404 y no 500. `error(...)` salía como `IllegalStateException`,
        // es decir, un 500 redactado indistinguible de un Supabase caído. Mismo
        // mensaje para «no existe» y «existe pero es un borrador»: la app no puede
        // saber qué borradores hay (EXAM-009).
        if (!existe) throw BackendDomainException("EXAM_NOT_FOUND", HttpStatusCode.NotFound, "Examen no encontrado.")
        val body = buildJsonObject {
            put("usuario_id", usuarioId)
            put("examen_id", examenId)
            put("estado", "en_progreso")
            put("iniciado_en", Instant.now().toString())
            put("total_preguntas", contarPreguntasEvaluables(examenId))
        }
        return post("/rest/v1/intentos_examen", body).first().toIntentoDto()
    }

    /**
     * Cuántas preguntas EVALUABLES va a entregar la app para este examen: servidas (activas) y no
     * anuladas. Ver [iniciar].
     *
     * Devuelve 0 sólo cuando el examen de verdad no tiene ninguna pregunta
     * evaluable: un fallo de lectura lanza y `iniciar` no llega a crear el
     * intento, que es lo correcto — un intento mal dimensionado es peor que un
     * intento que no se abre.
     *
     * Se lee `select=*` (y no `pregunta_id,anulada`) a propósito, igual que [examenDetalle]: si el
     * backend se despliega antes que la migración M10, una columna inexistente en el `select`
     * sería un 400 que impide iniciar CUALQUIER examen, mientras que con `*` la ausencia de
     * `anulada` se lee como «no anulada».
     */
    private fun contarPreguntasEvaluables(
        examenId: String,
        asociaciones: List<JsonObject> = get("/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId"),
    ): Int {
        val preguntaIds = asociaciones
            .filter { it.bool("anulada") != true }
            .mapNotNull { it.str("pregunta_id") }
        if (preguntaIds.isEmpty()) return 0
        return get("/rest/v1/preguntas?select=id&id=in.(${preguntaIds.joinToString(",")})&activa=eq.true").size
    }

    /**
     * Responde una pregunta del intento.
     *
     * ### Contrato que consume la app móvil externa (M10, pregunta anulada)
     *
     * Respuesta normal: `{ "esCorrecta": true|false, "opcionCorrectaId": "…", "anulada": false }`.
     *
     * Pregunta ANULADA por el tribunal en el examen del intento: `{ "esCorrecta": false,
     * "opcionCorrectaId": "…", "anulada": true }` con un 200, SIN error y sin guardar nada. La app
     * debe tratarla como «sin puntuar»: no marca acierto ni fallo y enseña la opción correcta como
     * cualquier corrección. `esCorrecta = false` con `anulada = true` NO es un fallo.
     *
     * Se decide como anulada si la RELACIÓN pregunta↔examen lo dice (`preguntas_examen.anulada`)
     * O si `record_answer` devuelve `es_correcta = NULL` (su señal de «no puntúa»). Con cualquiera de
     * las dos basta: si la relación está anulada pero una versión anterior de la RPC sí guardó la
     * respuesta, [finalizar] la excluye igualmente del total.
     *
     * Se conservan todas las validaciones: el intento existe, es del usuario y está en curso; la
     * pregunta pertenece al examen; la opción pertenece a la pregunta.
     */
    fun responder(usuarioId: String, intentoId: String, req: ResponderRequest): ResponderResponse {
        usuarioId.validatedUuid("usuarioId")
        intentoId.validatedUuid("intentoId")
        req.preguntaId.validatedUuid("preguntaId")
        req.opcionSeleccionadaId.validatedUuid("opcionSeleccionadaId")
        val intento = get("/rest/v1/intentos_examen?select=*&id=eq.$intentoId&usuario_id=eq.$usuarioId&limit=1").firstOrNull()
            ?: throw AppUnauthorized("El intento no pertenece al usuario.")
        require(intento.str("estado") == "en_progreso") { "El intento ya no admite respuestas." }
        val examenId = intento.str("examen_id") ?: error("El intento no tiene examen asociado.")
        val relacion = get("/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId&pregunta_id=eq.${req.preguntaId}&limit=1")
            .firstOrNull()
        require(relacion != null) { "La pregunta no pertenece al examen del intento." }
        require(
            get("/rest/v1/opciones_pregunta?select=id&id=eq.${req.opcionSeleccionadaId}&pregunta_id=eq.${req.preguntaId}&limit=1").isNotEmpty(),
        ) { "La opcion no pertenece a la pregunta." }
        val result = rpc("record_answer", buildJsonObject {
            put("p_usuario_id", usuarioId)
            put("p_intento_id", intentoId)
            put("p_pregunta_id", req.preguntaId)
            put("p_opcion_seleccionada_id", req.opcionSeleccionadaId)
        }).firstOrNull() ?: error("La respuesta no fue registrada.")
        val correcta = result.bool("es_correcta")
        val anulada = relacion.bool("anulada") == true || correcta == null
        return ResponderResponse(
            esCorrecta = !anulada && correcta == true,
            opcionCorrectaId = result.str("opcion_correcta_id"),
            anulada = anulada,
        )
    }

    /**
     * Cierra el intento y calcula sus totales.
     *
     * ### Fórmula (sin cambios) y qué pasa con las anuladas
     *
     * `total_respondidas` = respuestas EVALUABLES; `total_correctas` = las acertadas;
     * `total_falladas` = respondidas − correctas; `porcentaje_acierto` = correctas / respondidas;
     * `puntuacion` = correctas. Una pregunta ANULADA en el examen del intento ni suma ni resta ni
     * cuenta como «sin responder»: no está en `total_preguntas` (que son las evaluables, ver
     * [iniciar]) y sus respuestas se EXCLUYEN aquí aunque existan (defensa por si la anulación llegó
     * DESPUÉS de responder, o una versión anterior de `record_answer` la guardó).
     *
     * `total_sin_responder` = max(0, evaluables − respondidas), con las evaluables recalculadas al
     * cerrar (activas y no anuladas ahora mismo). Es una columna `integer not null default 0` de
     * `intentos_examen`.
     *
     * ### Contrato que consume la app móvil externa
     *
     * El intento devuelto lleva `totalPreguntas` (evaluables al abrir), `totalRespondidas`,
     * `totalCorrectas` y `porcentajeAcierto`, todos SIN anuladas. La app no debe restarlas ni
     * sumarlas por su cuenta: los totales ya vienen excluyéndolas.
     */
    fun finalizar(usuarioId: String, intentoId: String): AppIntentoDto {
        usuarioId.validatedUuid("usuarioId")
        intentoId.validatedUuid("intentoId")
        val intento = get("/rest/v1/intentos_examen?select=id,estado,examen_id&id=eq.$intentoId&usuario_id=eq.$usuarioId&limit=1").firstOrNull()
            ?: throw AppUnauthorized("El intento no pertenece al usuario.")
        require(intento.str("estado") == "en_progreso") { "El intento ya esta finalizado." }
        val examenId = intento.str("examen_id")?.validatedUuid("examenId")
        val asociaciones = examenId
            ?.let { get("/rest/v1/preguntas_examen?select=*&examen_id=eq.$it") }
            .orEmpty()
        val anuladas = asociaciones.filter { it.bool("anulada") == true }.mapNotNull { it.str("pregunta_id") }.toSet()
        val respuestas = get("/rest/v1/respuestas_intento?select=es_correcta,pregunta_id&intento_id=eq.$intentoId")
            .filter { it.str("pregunta_id") !in anuladas }
        val total = respuestas.size
        val correctas = respuestas.count { it.bool("es_correcta") == true }
        val porcentaje = if (total > 0) correctas * 100.0 / total else 0.0
        val sinResponder = if (examenId == null) 0 else maxOf(0, contarPreguntasEvaluables(examenId, asociaciones) - total)
        val body = buildJsonObject {
            put("estado", "finalizado")
            put("finalizado_en", Instant.now().toString())
            put("total_respondidas", total)
            put("total_correctas", correctas)
            put("total_falladas", total - correctas)
            put("total_sin_responder", sinResponder)
            put("porcentaje_acierto", porcentaje)
            put("puntuacion", correctas)
        }
        return patch("/rest/v1/intentos_examen?id=eq.$intentoId&usuario_id=eq.$usuarioId&estado=eq.en_progreso", body)
            .firstOrNull()?.toIntentoDto() ?: error("El intento cambio mientras se finalizaba.")
    }

    fun estadisticas(usuarioId: String): AppEstadisticasDto? {
        usuarioId.validatedUuid("usuarioId")
        return get("/rest/v1/estadisticas_usuario?select=*&usuario_id=eq.$usuarioId&limit=1").firstOrNull()?.let {
            AppEstadisticasDto(
                usuarioId = usuarioId,
                examenesFinalizados = it.int("examenes_finalizados") ?: 0,
                preguntasRespondidas = it.int("preguntas_respondidas") ?: 0,
                respuestasCorrectas = it.int("respuestas_correctas") ?: 0,
                porcentajeAcierto = it.dbl("porcentaje_acierto") ?: 0.0,
                puntosTotales = it.int("puntos_totales") ?: 0,
                nivel = it.int("nivel") ?: 1,
            )
        }
    }

    fun retoDiario(): AppRetoDto? =
        get("/rest/v1/retos_diarios?select=*&activo=eq.true&fecha=lte.${LocalDate.now()}&order=fecha.desc&limit=1")
            .firstOrNull()?.let {
                AppRetoDto(
                    id = it.str("id")!!,
                    fecha = it.str("fecha").orEmpty(),
                    titulo = it.str("titulo").orEmpty(),
                    descripcion = it.str("descripcion").orEmpty(),
                    objetivoPreguntas = it.int("objetivo_preguntas") ?: 0,
                    recompensaPuntos = it.int("recompensa_puntos") ?: 0,
                )
            }

    fun crearFeedback(usuarioId: String, req: AppFeedbackRequest): String {
        usuarioId.validatedUuid("usuarioId")
        req.preguntaId.validatedUuid("preguntaId")
        req.intentoId?.let { intentoId ->
            intentoId.validatedUuid("intentoId")
            val examenId = get("/rest/v1/intentos_examen?select=examen_id&id=eq.$intentoId&usuario_id=eq.$usuarioId&limit=1")
                .firstOrNull()?.str("examen_id")
                ?: throw AppUnauthorized("El intento no pertenece al usuario.")
            require(
                get("/rest/v1/preguntas_examen?select=pregunta_id&examen_id=eq.$examenId&pregunta_id=eq.${req.preguntaId}&limit=1").isNotEmpty(),
            ) { "La pregunta no pertenece al examen del intento." }
        }
        val body = buildJsonObject {
            put("usuario_id", usuarioId)
            put("pregunta_id", req.preguntaId)
            if (req.intentoId != null) put("intento_id", req.intentoId)
            put("tipo_feedback", req.tipoFeedback.ifBlank { "otro" })
            if (req.descripcion.isNotBlank()) put("descripcion", req.descripcion)
            put("estado", "pendiente")
        }
        return post("/rest/v1/feedback_pregunta", body).first().str("id").orEmpty()
    }

    fun registrarEvento(usuarioId: String, req: AppEventoRequest): String {
        usuarioId.validatedUuid("usuarioId")
        require(req.eventVersion > 0) { "eventVersion debe ser mayor que cero." }
        require(!req.idempotencyKey.isNullOrBlank()) { "idempotencyKey es obligatoria para evitar duplicados por retry." }
        val body = buildJsonObject {
            put("p_usuario_id", usuarioId)
            put("p_nombre_evento", req.nombreEvento)
            if (req.pantalla != null) put("p_pantalla", req.pantalla)
            if (req.entidadTipo != null) put("p_entidad_tipo", req.entidadTipo)
            if (req.entidadId != null) put("p_entidad_id", req.entidadId)
            put("p_propiedades", req.propiedades)
            put("p_event_version", req.eventVersion)
            put("p_occurred_en", req.occurredEn ?: Instant.now().toString())
            if (req.especialidadId != null) put("p_especialidad_id", req.especialidadId)
            if (req.intentoId != null) put("p_intento_id", req.intentoId)
            if (req.preguntaId != null) put("p_pregunta_id", req.preguntaId)
            put("p_idempotency_key", req.idempotencyKey.trim())
        }
        return rpc("record_event", body).firstOrNull()?.str("event_id")
            ?: error("El evento no fue registrado.")
    }

    fun commercialCatalog(): CommercialCatalogResponse {
        val products = get(
            "/rest/v1/productos_comerciales?select=id,codigo,tipo_producto,nombre,descripcion,plan_id&activo=eq.true&visible=eq.true&order=posicion.asc,codigo.asc",
        )
        val productIds = products.mapNotNull { it.str("id") }
        val tariffs = if (productIds.isEmpty()) {
            emptyList()
        } else {
            get("/rest/v1/tarifas_producto_comercial?select=*&producto_id=in.(${productIds.joinToString(",")})&activo=eq.true&visible=eq.true&order=posicion.asc")
        }
        val plans = get(
            "/rest/v1/planes_suscripcion?select=id,codigo,nombre,descripcion,nivel&activo=eq.true&visible=eq.true&order=posicion.asc,codigo.asc",
        )
        val planIds = plans.mapNotNull { it.str("id") }
        val benefits = if (planIds.isEmpty()) {
            emptyList()
        } else {
            get("/rest/v1/beneficios_plan?select=*&plan_id=in.(${planIds.joinToString(",")})&order=codigo_beneficio.asc")
        }
        val tariffsByProduct = tariffs.groupBy { it.str("producto_id") }
        val benefitsByPlan = benefits.groupBy { it.str("plan_id") }
        return CommercialCatalogResponse(
            products = products.map { product ->
                CommercialProductDto(
                    id = product.str("id")!!,
                    code = product.str("codigo").orEmpty(),
                    productType = product.str("tipo_producto").orEmpty(),
                    name = product.str("nombre").orEmpty(),
                    description = product.str("descripcion"),
                    planId = product.str("plan_id"),
                    tariffs = tariffsByProduct[product.str("id")].orEmpty().map { it.toCommercialTariff() },
                )
            },
            plans = plans.map { plan ->
                CommercialPlanDto(
                    id = plan.str("id")!!,
                    code = plan.str("codigo").orEmpty(),
                    name = plan.str("nombre").orEmpty(),
                    description = plan.str("descripcion"),
                    level = plan.int("nivel") ?: 0,
                    benefits = benefitsByPlan[plan.str("id")].orEmpty().map { it.toCommercialBenefit() },
                )
            },
        )
    }

    fun commercialPurchases(usuarioId: String): List<CommercialPurchaseDto> {
        usuarioId.validatedUuid("usuarioId")
        return get("/rest/v1/compras_usuario?select=*&usuario_id=eq.$usuarioId&order=comprada_en.desc.nullslast,creado_en.desc")
            .map { it.toCommercialPurchase() }
    }

    fun commercialSubscriptions(usuarioId: String): List<CommercialSubscriptionDto> {
        usuarioId.validatedUuid("usuarioId")
        return get("/rest/v1/suscripciones?select=*&usuario_id=eq.$usuarioId&order=inicio_en.desc")
            .map { it.toCommercialSubscription() }
    }

    private fun perfil(usuarioId: String): AppPerfilDto? =
        get("/rest/v1/perfiles_usuario?select=*&usuario_id=eq.$usuarioId&limit=1").firstOrNull()?.let {
            AppPerfilDto(
                usuarioId = usuarioId,
                nombreMostrar = it.str("nombre_mostrar"),
                username = it.str("username"),
                esInvitado = it.bool("es_invitado") ?: false,
                onboardingCompletado = it.bool("onboarding_completado") ?: false,
            )
        }

    private fun JsonObject.toExamenDto() = AppExamenDto(
        id = str("id")!!,
        titulo = str("titulo").orEmpty(),
        descripcion = str("descripcion").orEmpty(),
        tipoExamen = str("tipo_examen").orEmpty(),
        tipoAcceso = str("tipo_acceso").orEmpty(),
        totalPreguntas = int("total_preguntas") ?: 0,
        anio = int("anio"),
    )

    private fun JsonObject.toIntentoDto() = AppIntentoDto(
        id = str("id")!!,
        examenId = str("examen_id").orEmpty(),
        estado = str("estado").orEmpty(),
        totalPreguntas = int("total_preguntas") ?: 0,
        totalRespondidas = int("total_respondidas") ?: 0,
        totalCorrectas = int("total_correctas") ?: 0,
        porcentajeAcierto = dbl("porcentaje_acierto") ?: 0.0,
    )

    private fun JsonObject.toCommercialTariff() = CommercialTariffDto(
        id = str("id")!!,
        billingPeriod = str("periodo_facturacion").orEmpty(),
        pricePhase = str("fase_precio").orEmpty(),
        priceCents = int("precio_centimos") ?: 0,
        currency = str("moneda").orEmpty(),
        active = bool("activo") ?: false,
        visible = bool("visible") ?: false,
        position = int("posicion") ?: 0,
    )

    private fun JsonObject.toCommercialBenefit() = CommercialBenefitDto(
        id = str("id")!!,
        code = str("codigo_beneficio").orEmpty(),
        booleanValue = bool("valor_boolean"),
        integerValue = int("valor_integer"),
        numericValue = dbl("valor_numeric"),
        textValue = str("valor_text"),
    )

    private fun JsonObject.toCommercialPurchase() = CommercialPurchaseDto(
        id = str("id")!!,
        provider = str("proveedor").orEmpty(),
        productCode = str("producto_codigo").orEmpty(),
        productType = str("tipo_producto").orEmpty(),
        status = str("estado").orEmpty(),
        priceCents = int("precio_centimos"),
        currency = str("moneda").orEmpty(),
        externalTransactionId = str("identificador_transaccion"),
        purchasedAt = str("comprada_en"),
    )

    private fun JsonObject.toCommercialSubscription() = CommercialSubscriptionDto(
        id = str("id")!!,
        provider = str("proveedor").orEmpty(),
        productCode = str("producto_codigo").orEmpty(),
        status = str("estado").orEmpty(),
        startedAt = str("inicio_en").orEmpty(),
        endedAt = str("fin_en"),
        renewsAt = str("renovacion_en"),
        externalId = str("identificador_externo"),
    )

    // --- helpers REST ---
    // Todo sale por [transport]: credencial por la política, cliente compartido y
    // un único `failOnError` (dominio → [BackendDomainException]; el resto,
    // incluidos los fallos de red, → `IllegalStateException` que StatusPages redacta).
    private fun get(path: String): List<JsonObject> = transport.get(path).toJsonObjects()
    private fun rpc(function: String, body: JsonObject): List<JsonObject> =
        transport.rpc(function, json.encodeToString(body)).toJsonObjects()
    private fun post(path: String, body: JsonObject): List<JsonObject> =
        transport.send(
            method = "POST",
            path = path,
            body = json.encodeToString(buildJsonArray { add(body) }),
            prefer = "return=representation",
        ).toJsonObjects()
    private fun patch(path: String, body: JsonObject): List<JsonObject> =
        transport.send(
            method = "PATCH",
            path = path,
            body = json.encodeToString(body),
            prefer = "return=representation",
        ).toJsonObjects()

    /**
     * Objeto o array, porque PostgREST devuelve las dos formas: `record_event`
     * responde un objeto y `record_answer` un array. Un cuerpo vacío (un 201 sin
     * representación) es una lista vacía, no un error.
     */
    private fun String.toJsonObjects(): List<JsonObject> {
        if (isBlank()) return emptyList()
        val element = json.parseToJsonElement(this)
        return if (element is kotlinx.serialization.json.JsonArray) element.map { it.jsonObject } else listOf(element.jsonObject)
    }

    companion object {
        /**
         * Revocar un rol debe surtir efecto en segundos, no en la siguiente sesión.
         *
         * 90 s y no 30 s (F-17): el panel refresca la franja SYSTEM cada 60 s, y con
         * un TTL menor que ese refresco CADA refresco pagaba la lectura de rol. Con
         * 90 s, un administrador con el panel abierto cuesta como mucho una lectura
         * cada dos refrescos, y una revocación sigue tardando minuto y medio como
         * máximo en aplicarse.
         */
        internal const val ADMIN_ROLE_CACHE_TTL_MILLIS = 90_000L
        private const val ADMIN_ROLE_CACHE_MAX_ENTRIES = 1_000

        /**
         * `null` si falta `SUPABASE_URL` o la clave de servidor.
         *
         * La clave la resuelve [SupabaseRequestAuthPolicy.secretFromEnvironment]
         * (F-01): leía SÓLO `SUPABASE_SERVICE_ROLE_KEY`, así que con la
         * configuración canónica —sólo `SUPABASE_SECRET_KEY`— devolvía `null` y
         * `Application.module()` no arrancaba. [environment] existe para poder
         * probarlo sin tocar el entorno del proceso.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): AppV1Gateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val key = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            return if (url.isBlank() || key.isBlank()) null else AppV1Gateway(url, key, json)
        }

        /**
         * Sólo para tests: inyecta el `HttpClient` para poder observar lo que
         * sale de verdad hacia el upstream.
         *
         * Mismo patrón y mismo motivo que en `AdminV1Gateway.forTesting`. Se
         * añade ahora porque `iniciar` dimensiona la fila de un usuario final y
         * **qué consulta usa para contar** es parte del contrato, no un detalle:
         * sin esta costura no hay forma de fijar que el recuento significa lo
         * mismo que la entrega.
         */
        internal fun forTesting(
            supabaseUrl: String,
            serviceRoleKey: String,
            json: Json,
            client: HttpClient,
            clock: () -> Long = System::currentTimeMillis,
        ): AppV1Gateway = AppV1Gateway(supabaseUrl, serviceRoleKey, json, client, clock)
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
