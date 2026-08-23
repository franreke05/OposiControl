package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
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

fun Route.appV1Routes(gateway: AppV1Gateway, verifier: SupabaseJwtVerifier, allowDevUser: Boolean) {
    route("/api/v1/app") {
        get("/home") {
            call.respond(gateway.home(call.usuarioId(verifier, allowDevUser)))
        }
        get("/examenes") {
            call.usuarioId(verifier, allowDevUser) // requiere sesion
            call.respond(AppExamenesResponse(gateway.listExamenes()))
        }
        get("/examenes/{id}") {
            call.usuarioId(verifier, allowDevUser)
            val detalle = gateway.examenDetalle(call.requiredPath("id"))
                ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorDto("not_found", "Examen no encontrado."))
            call.respond(detalle)
        }
        post("/examenes/{id}/iniciar") {
            val uid = call.usuarioId(verifier, allowDevUser)
            call.respond(io.ktor.http.HttpStatusCode.Created, AppIntentoResponse(gateway.iniciar(uid, call.requiredPath("id"))))
        }
        post("/intentos/{id}/responder") {
            val uid = call.usuarioId(verifier, allowDevUser)
            call.respond(gateway.responder(uid, call.requiredPath("id"), call.receive()))
        }
        post("/intentos/{id}/finalizar") {
            val uid = call.usuarioId(verifier, allowDevUser)
            call.respond(AppIntentoResponse(gateway.finalizar(uid, call.requiredPath("id"))))
        }
        get("/estadisticas") {
            call.respond(AppEstadisticasResponse(gateway.estadisticas(call.usuarioId(verifier, allowDevUser))))
        }
        get("/commercial/catalog") {
            call.usuarioId(verifier, allowDevUser)
            call.respond(gateway.commercialCatalog())
        }
        get("/commercial/purchases") {
            call.respond(gateway.commercialPurchases(call.usuarioId(verifier, allowDevUser)))
        }
        get("/commercial/subscriptions") {
            call.respond(gateway.commercialSubscriptions(call.usuarioId(verifier, allowDevUser)))
        }
        get("/reto-diario") {
            call.usuarioId(verifier, allowDevUser)
            call.respond(AppRetoResponse(gateway.retoDiario()))
        }
        post("/feedback/pregunta") {
            val uid = call.usuarioId(verifier, allowDevUser)
            call.respond(io.ktor.http.HttpStatusCode.Created, OkResponse(id = gateway.crearFeedback(uid, call.receive())))
        }
        post("/eventos") {
            val uid = call.usuarioId(verifier, allowDevUser)
            call.respond(io.ktor.http.HttpStatusCode.Created, OkResponse(id = gateway.registrarEvento(uid, call.receive())))
        }
    }
}

/** Resuelve el usuario: JWT de Supabase (produccion) o cabecera X-Usuario-Id (solo dev). */
private fun ApplicationCall.usuarioId(verifier: SupabaseJwtVerifier, allowDevUser: Boolean): String {
    val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
    if (!bearer.isNullOrBlank() && bearer.count { it == '.' } == 2) {
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

class SupabaseJwtVerifier(supabaseUrl: String, private val json: Json) {
    private val issuer = "$supabaseUrl/auth/v1"
    private val jwksUrl = "$issuer/.well-known/jwks.json"
    private val client = HttpClient.newHttpClient()

    @Volatile private var keys: Map<String, ECPublicKey> = emptyMap()
    @Volatile private var fetchedAt = 0L

    fun subFrom(token: String): String {
        val decoded = JWT.decode(token)
        val kid = decoded.keyId ?: error("token sin kid")
        val key = keys[kid] ?: run { refresh(); keys[kid] } ?: error("kid no encontrado en JWKS")
        val verified = JWT.require(Algorithm.ECDSA256(key, null)).withIssuer(issuer).build().verify(token)
        return verified.subject ?: error("token sin sub")
    }

    @Synchronized
    private fun refresh() {
        if (keys.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 60_000) return
        val body = client.send(
            HttpRequest.newBuilder(URI.create(jwksUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        keys = json.parseToJsonElement(body).jsonObject["keys"]!!.jsonArray
            .mapNotNull { el ->
                val k = el.jsonObject
                if (k["kty"]?.jsonPrimitive?.contentOrNull != "EC") return@mapNotNull null
                val kid = k["kid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                kid to ecPublicKey(
                    k["x"]!!.jsonPrimitive.content,
                    k["y"]!!.jsonPrimitive.content,
                )
            }
            .toMap()
        fetchedAt = System.currentTimeMillis()
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
@Serializable data class AppPreguntaDto(val id: String, val titulo: String, val posicion: Int, val opciones: List<AppOpcionDto>)
@Serializable data class AppRetoDto(val id: String, val fecha: String, val titulo: String, val descripcion: String = "", val objetivoPreguntas: Int = 0, val recompensaPuntos: Int = 0)
@Serializable data class AppEstadisticasDto(val usuarioId: String, val examenesFinalizados: Int = 0, val preguntasRespondidas: Int = 0, val respuestasCorrectas: Int = 0, val porcentajeAcierto: Double = 0.0, val puntosTotales: Int = 0, val nivel: Int = 1)
@Serializable data class AppIntentoDto(val id: String, val examenId: String, val estado: String, val totalPreguntas: Int = 0, val totalRespondidas: Int = 0, val totalCorrectas: Int = 0, val porcentajeAcierto: Double = 0.0)

@Serializable data class AppHomeResponse(val perfil: AppPerfilDto? = null, val retoDiario: AppRetoDto? = null, val examenes: List<AppExamenDto> = emptyList())
@Serializable data class AppExamenesResponse(val data: List<AppExamenDto>)
@Serializable data class AppExamenDetalleResponse(val examen: AppExamenDto, val preguntas: List<AppPreguntaDto>)
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
@Serializable data class ResponderResponse(val esCorrecta: Boolean, val opcionCorrectaId: String? = null)
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
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun hasAdminRole(usuarioId: String): Boolean {
        usuarioId.validatedUuid("usuarioId")
        return hasAnyCanonicalRole(usuarioId, CanonicalAuthorization.adminRoleCodes)
    }

    fun hasModeratorOrAdminRole(usuarioId: String): Boolean {
        usuarioId.validatedUuid("usuarioId")
        return hasAnyCanonicalRole(usuarioId, CanonicalAuthorization.moderatorOrAdminRoleCodes)
    }

    private fun hasAnyCanonicalRole(usuarioId: String, roleCodes: Set<String>): Boolean {
        val roleIds = get("/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$usuarioId")
            .mapNotNull { it.str("rol_id") }
            .distinct()
        if (roleIds.isEmpty()) return false
        val roleFilter = roleCodes.joinToString(",")
        val idFilter = roleIds.joinToString(",")
        return get("/rest/v1/roles_app?select=id&codigo=in.($roleFilter)&id=in.($idFilter)&limit=1").isNotEmpty()
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
                opciones = opcionesByPregunta[pid].orEmpty().map {
                    AppOpcionDto(it.str("id")!!, it.str("letra").orEmpty(), it.str("texto_opcion").orEmpty())
                },
            )
        }
        return AppExamenDetalleResponse(examen.toExamenDto(), preguntas)
    }

    fun iniciar(usuarioId: String, examenId: String): AppIntentoDto {
        usuarioId.validatedUuid("usuarioId")
        examenId.validatedUuid("examenId")
        val examen = get("/rest/v1/examenes?select=total_preguntas&id=eq.$examenId&activo=eq.true&publicado_en=not.is.null&limit=1").firstOrNull()
            ?: error("Examen no encontrado.")
        val body = buildJsonObject {
            put("usuario_id", usuarioId)
            put("examen_id", examenId)
            put("estado", "en_progreso")
            put("iniciado_en", Instant.now().toString())
            put("total_preguntas", examen.int("total_preguntas") ?: 0)
        }
        return post("/rest/v1/intentos_examen", body).first().toIntentoDto()
    }

    fun responder(usuarioId: String, intentoId: String, req: ResponderRequest): ResponderResponse {
        usuarioId.validatedUuid("usuarioId")
        intentoId.validatedUuid("intentoId")
        req.preguntaId.validatedUuid("preguntaId")
        req.opcionSeleccionadaId.validatedUuid("opcionSeleccionadaId")
        val intento = get("/rest/v1/intentos_examen?select=*&id=eq.$intentoId&usuario_id=eq.$usuarioId&limit=1").firstOrNull()
            ?: throw AppUnauthorized("El intento no pertenece al usuario.")
        require(intento.str("estado") == "en_progreso") { "El intento ya no admite respuestas." }
        val examenId = intento.str("examen_id") ?: error("El intento no tiene examen asociado.")
        require(
            get("/rest/v1/preguntas_examen?select=pregunta_id&examen_id=eq.$examenId&pregunta_id=eq.${req.preguntaId}&limit=1").isNotEmpty(),
        ) { "La pregunta no pertenece al examen del intento." }
        require(
            get("/rest/v1/opciones_pregunta?select=id&id=eq.${req.opcionSeleccionadaId}&pregunta_id=eq.${req.preguntaId}&limit=1").isNotEmpty(),
        ) { "La opcion no pertenece a la pregunta." }
        val result = rpc("/rest/v1/rpc/record_answer", buildJsonObject {
            put("p_usuario_id", usuarioId)
            put("p_intento_id", intentoId)
            put("p_pregunta_id", req.preguntaId)
            put("p_opcion_seleccionada_id", req.opcionSeleccionadaId)
        }).firstOrNull() ?: error("La respuesta no fue registrada.")
        return ResponderResponse(result.bool("es_correcta") ?: false, result.str("opcion_correcta_id"))
    }

    fun finalizar(usuarioId: String, intentoId: String): AppIntentoDto {
        usuarioId.validatedUuid("usuarioId")
        intentoId.validatedUuid("intentoId")
        val intento = get("/rest/v1/intentos_examen?select=id,estado&id=eq.$intentoId&usuario_id=eq.$usuarioId&limit=1").firstOrNull()
            ?: throw AppUnauthorized("El intento no pertenece al usuario.")
        require(intento.str("estado") == "en_progreso") { "El intento ya esta finalizado." }
        val respuestas = get("/rest/v1/respuestas_intento?select=es_correcta&intento_id=eq.$intentoId")
        val total = respuestas.size
        val correctas = respuestas.count { it.bool("es_correcta") == true }
        val porcentaje = if (total > 0) correctas * 100.0 / total else 0.0
        val body = buildJsonObject {
            put("estado", "finalizado")
            put("finalizado_en", Instant.now().toString())
            put("total_respondidas", total)
            put("total_correctas", correctas)
            put("total_falladas", total - correctas)
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
        return rpc("/rest/v1/rpc/record_event", body).firstOrNull()?.str("event_id")
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
    private fun get(path: String): List<JsonObject> = request("GET", path, null)
    private fun rpc(path: String, body: JsonObject): List<JsonObject> =
        request("POST", path, json.encodeToString(body))
    private fun post(path: String, body: JsonObject): List<JsonObject> =
        request("POST", path, json.encodeToString(buildJsonArray { add(body) }), prefer = "return=representation")
    private fun patch(path: String, body: JsonObject): List<JsonObject> =
        request("PATCH", path, json.encodeToString(body), prefer = "return=representation")

    private fun request(method: String, path: String, body: String?, prefer: String? = null): List<JsonObject> {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            mapSupabaseDomainError(response.statusCode(), response.body())?.let { throw it }
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: ${response.body().take(300)}")
        }
        val text = response.body()
        if (text.isBlank()) return emptyList()
        val element = json.parseToJsonElement(text)
        return if (element is kotlinx.serialization.json.JsonArray) element.map { it.jsonObject } else listOf(element.jsonObject)
    }

    companion object {
        fun fromEnvironment(json: Json): AppV1Gateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val key = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || key.isBlank()) null else AppV1Gateway(url, key, json)
        }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
