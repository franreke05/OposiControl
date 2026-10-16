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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// =====================================================================
// SERVICIO CANONICO DE PERFIL (SLICE 1)
//
// Una unica implementacion para D1-11/12/13/16/17 y para los adaptadores legacy
// (/v1/auth/me, /v1/auth/register/profile). Toda escritura es UNA llamada a un
// RPC `app_*` SECURITY DEFINER con la clave de servicio (una transaccion en la
// base). La identidad es SIEMPRE el `sub` del JWT que resolvio el llamante:
// ningun metodo acepta un id que venga de un cuerpo.
// =====================================================================

/** Lo que Ktor necesita de Supabase Auth (GoTrue) y solo de el. Implementado por el gateway de auth. */
internal interface AuthProviderClient {
    /** `GET /auth/v1/user` con el bearer del llamante. 401 si GoTrue ya no reconoce la sesion, 503 si no responde. */
    suspend fun user(accessToken: String, context: OposiBotsAuthContext): AuthProviderUser

    /** Reautenticacion por contrasena (grant `password`); la sesion de verificacion se revoca. 401 REAUTH_FAILED si no coincide. */
    suspend fun verifyPassword(email: String, password: String, context: OposiBotsAuthContext)

    /** Logout GLOBAL con el bearer del llamante (mismo metodo que `POST /v1/auth/logout`). */
    suspend fun logout(accessToken: String, context: OposiBotsAuthContext)
}

/** [providers] crudos de GoTrue (`app_metadata.providers` + `identities[].provider`), sin filtrar. */
internal data class AuthProviderUser(
    val id: String,
    val email: String?,
    val providers: List<String>,
    val createdAt: String?,
) {
    /** `authProviders` del contrato: solo `email|apple|google`, sin repetidos; un proveedor desconocido se descarta. */
    fun canonicalProviders(): List<String> = providers.map { it.lowercase() }.filter { it in CANONICAL_PROVIDERS }.distinct()

    val hasPassword: Boolean get() = providers.any { it.equals("email", ignoreCase = true) }

    companion object {
        val CANONICAL_PROVIDERS = setOf("email", "apple", "google")
    }
}

/** Puerto bloqueante hacia Supabase con la clave de servicio: lecturas PostgREST y RPC. */
internal interface CanonicalUpstream {
    fun get(path: String): String
    fun rpc(function: String, body: String): SupabaseRawResponse
}

internal class TransportCanonicalUpstream(private val transport: SupabaseRequestTransport) : CanonicalUpstream {
    override fun get(path: String): String = transport.get(path)
    override fun rpc(function: String, body: String): SupabaseRawResponse = transport.rpcRaw(function, body)
}

/** `Me` + la version (ETag) del perfil. [replay] = repeticion identica de un onboarding ya completado. */
internal data class MeResult(val me: Me, val etag: String?, val replay: Boolean = false)

/** Documentos vigentes + ETag sobre el `publicado_en` mas reciente. */
internal data class LegalDocumentsResult(val items: List<LegalDocument>, val etag: String)

internal interface CanonicalProfileService {
    suspend fun me(identity: CanonicalUserIdentity, accessToken: String, context: OposiBotsAuthContext): MeResult

    suspend fun completeOnboarding(
        identity: CanonicalUserIdentity,
        accessToken: String,
        request: OnboardingRequest,
        context: OposiBotsAuthContext,
    ): MeResult

    suspend fun updateProfile(
        identity: CanonicalUserIdentity,
        accessToken: String,
        request: ProfilePatchRequest,
        ifMatch: String?,
        context: OposiBotsAuthContext,
    ): MeResult

    suspend fun listLegalDocuments(tipo: String?): LegalDocumentsResult

    suspend fun acceptLegalDocuments(identity: CanonicalUserIdentity, request: LegalAcceptanceRequest): LegalAcceptanceResponse
}

internal class SupabaseCanonicalProfileService(
    private val upstream: CanonicalUpstream,
    private val authProvider: AuthProviderClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CanonicalProfileService {

    override suspend fun me(identity: CanonicalUserIdentity, accessToken: String, context: OposiBotsAuthContext): MeResult {
        val row = canonicalUpstreamIo {
            upstream.get(
                "/rest/v1/perfiles_usuario?select=$PROFILE_COLUMNS&usuario_id=eq.${identity.userId.value}&limit=1",
            )
        }
        val snapshot = json.decodeFromString(ListSerializer(ProfileRowDto.serializer()), row).firstOrNull()
            ?.toSnapshot()
            ?: throw CanonicalErrorCode.PROFILE_MISSING.exception()
        // GET /me: sin GoTrue no hay email ni proveedores, y `authProviders` es obligatorio del contrato.
        val user = authProvider.user(accessToken, context)
        return buildMe(snapshot, user)
    }

    override suspend fun completeOnboarding(
        identity: CanonicalUserIdentity,
        accessToken: String,
        request: OnboardingRequest,
        context: OposiBotsAuthContext,
    ): MeResult {
        val issues = mutableListOf<ErrorIssue>()
        if (!USERNAME_PATTERN.matches(request.username)) issues += ErrorIssue("username", CanonicalIssue.INVALID_FORMAT)
        issues += nameIssues("nombre", request.nombre, required = true)
        issues += nameIssues("apellidos", request.apellidos, required = true)
        request.especialidadId?.let { if (!it.isUuid()) issues += ErrorIssue("especialidadId", CanonicalIssue.INVALID_FORMAT) }
        if (request.acceptedLegalDocumentIds.size > MAX_ONBOARDING_DOCUMENTS) {
            issues += ErrorIssue("acceptedLegalDocumentIds", CanonicalIssue.OUT_OF_RANGE)
        }
        request.acceptedLegalDocumentIds.forEachIndexed { index, id ->
            if (!id.isUuid()) issues += ErrorIssue("acceptedLegalDocumentIds[$index]", CanonicalIssue.INVALID_FORMAT)
        }
        request.attribution?.let { issues += attributionIssues(it) }
        request.referralCode?.let {
            // DEC-037 abierta: solo se valida el formato y se guarda write-once; ni validacion de existencia ni recompensa.
            if (!REFERRAL_PATTERN.matches(it)) issues += ErrorIssue("referralCode", CanonicalIssue.INVALID_FORMAT)
        }
        val platform = request.attribution?.plataforma?.takeIf { it == "android" || it == "ios" }
        if (request.acceptedLegalDocumentIds.isNotEmpty() && platform == null && issues.none { it.field == "attribution.plataforma" }) {
            // El contrato congelado no tiene `plataforma` propia en OnboardingRequest y la aceptacion la exige (android|ios).
            issues += ErrorIssue("attribution.plataforma", CanonicalIssue.REQUIRED)
        }
        if (issues.isNotEmpty()) throw validationError(*issues.take(MAX_VALIDATION_ISSUES).toTypedArray())

        val args = buildJsonObject {
            put("p_usuario_id", identity.userId.value)
            put("p_username", request.username)
            put("p_nombre", request.nombre.trim())
            put("p_apellidos", request.apellidos.trim())
            put("p_especialidad_id", request.especialidadId?.lowercase())
            put("p_documentos", JsonArray(request.acceptedLegalDocumentIds.distinct().map { JsonPrimitive(it.lowercase()) }))
            put("p_plataforma", platform)
            put("p_attribution", request.attribution?.let(::attributionJson) ?: JsonNull)
            put("p_referral_code", request.referralCode)
        }
        val snapshot = callProfileRpc(
            "app_completar_onboarding",
            args,
            documentNotActiveAs = CanonicalErrorCode.LEGAL_VERSION_NOT_ACTIVE,
        )
        return buildMe(snapshot, userOrNull(accessToken, context))
    }

    override suspend fun updateProfile(
        identity: CanonicalUserIdentity,
        accessToken: String,
        request: ProfilePatchRequest,
        ifMatch: String?,
        context: OposiBotsAuthContext,
    ): MeResult {
        // MASTER-ARCH-006: el DTO del cable se traduce una vez; validacion y RPC trabajan con el comando interno.
        val command = request.toCommand()
        val issues = mutableListOf<ErrorIssue>()
        command.username?.let { if (!USERNAME_PATTERN.matches(it)) issues += ErrorIssue("username", CanonicalIssue.INVALID_FORMAT) }
        command.nombre?.let { issues += nameIssues("nombre", it, required = true) }
        command.apellidos?.let { issues += nameIssues("apellidos", it, required = true) }
        command.especialidadId?.let { if (!it.isUuid()) issues += ErrorIssue("especialidadId", CanonicalIssue.INVALID_FORMAT) }
        command.comunidadPrincipalId?.let { if (!it.isUuid()) issues += ErrorIssue("comunidadPrincipalId", CanonicalIssue.INVALID_FORMAT) }
        command.targetCommunityIds?.let { ids ->
            if (ids.size > MAX_TARGET_COMMUNITIES) issues += ErrorIssue("targetCommunityIds", CanonicalIssue.OUT_OF_RANGE)
            ids.forEachIndexed { i, id -> if (!id.isUuid()) issues += ErrorIssue("targetCommunityIds[$i]", CanonicalIssue.INVALID_FORMAT) }
        }
        command.detail?.fechaExamenObjetivo?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                issues += ErrorIssue("detail.fechaExamenObjetivo", CanonicalIssue.INVALID_FORMAT)
            }
        }
        command.detail?.edad?.let { if (it < 0) issues += ErrorIssue("detail.edad", CanonicalIssue.OUT_OF_RANGE) }
        command.detail?.tiempoEstudioMin?.let { if (it < 0) issues += ErrorIssue("detail.tiempoEstudioMin", CanonicalIssue.OUT_OF_RANGE) }
        if (issues.isNotEmpty()) throw validationError(*issues.take(MAX_VALIDATION_ISSUES).toTypedArray())

        val patch = ProfileRpcContract.patch(command)
        val args = buildJsonObject {
            put("p_usuario_id", identity.userId.value)
            put("p_patch", patch)
            put("p_if_match", ifMatch)
        }
        val snapshot = callProfileRpc("app_actualizar_perfil", args)
        return buildMe(snapshot, userOrNull(accessToken, context))
    }

    override suspend fun listLegalDocuments(tipo: String?): LegalDocumentsResult {
        if (tipo != null && tipo !in LEGAL_TYPES) {
            throw validationError(ErrorIssue("tipo", CanonicalIssue.INVALID_FORMAT))
        }
        // Solo los tipos del contrato (`terminos|privacidad`): `cookies`/`aviso_legal` nunca salen por aqui.
        val filter = if (tipo != null) "tipo_documento=eq.$tipo" else "tipo_documento=in.(terminos,privacidad)"
        val body = canonicalUpstreamIo {
            upstream.get(
                "/rest/v1/documentos_legales?select=id,tipo_documento,version,titulo,contenido,publicado_en" +
                    "&activo=eq.true&publicado_en=not.is.null&$filter&order=publicado_en.desc&limit=$MAX_LEGAL_DOCUMENTS",
            )
        }
        val rows = json.decodeFromString(ListSerializer(LegalDocumentRow.serializer()), body)
        val items = rows.map {
            LegalDocument(
                id = it.id,
                tipo = it.tipoDocumento,
                version = it.version,
                titulo = it.titulo,
                contenido = it.contenido,
                publicadoEn = normalizeInstant(it.publicadoEn),
            )
        }
        return LegalDocumentsResult(items, etag = legalDocumentsEtag(items))
    }

    override suspend fun acceptLegalDocuments(
        identity: CanonicalUserIdentity,
        request: LegalAcceptanceRequest,
    ): LegalAcceptanceResponse {
        val issues = mutableListOf<ErrorIssue>()
        if (request.documentIds.isEmpty() || request.documentIds.size > MAX_ACCEPTANCE_DOCUMENTS) {
            issues += ErrorIssue("documentIds", CanonicalIssue.OUT_OF_RANGE)
        }
        request.documentIds.forEachIndexed { i, id -> if (!id.isUuid()) issues += ErrorIssue("documentIds[$i]", CanonicalIssue.INVALID_FORMAT) }
        if (request.plataforma != "android" && request.plataforma != "ios") issues += ErrorIssue("plataforma", CanonicalIssue.INVALID_FORMAT)
        if (issues.isNotEmpty()) throw validationError(*issues.take(MAX_VALIDATION_ISSUES).toTypedArray())

        val args = buildJsonObject {
            put("p_usuario_id", identity.userId.value)
            put("p_documentos", JsonArray(request.documentIds.distinct().map { JsonPrimitive(it.lowercase()) }))
            put("p_plataforma", request.plataforma)
        }
        val response = canonicalUpstreamIo { upstream.rpc("app_aceptar_documentos_legales", args.toString()) }
        if (!response.ok) throw ProfileRpcErrors.failure("app_aceptar_documentos_legales", response)
        return try {
            json.decodeFromString(LegalAcceptanceResponse.serializer(), response.body)
        } catch (e: IllegalArgumentException) {
            // MASTER-OBS-003: la causa de decodificacion se conserva (solo log; el cliente no la ve).
            throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = e)
        }
    }

    // ---------- internos ----------

    private suspend fun callProfileRpc(
        function: String,
        args: JsonObject,
        documentNotActiveAs: CanonicalErrorCode = CanonicalErrorCode.DOCUMENT_NOT_ACTIVE,
    ): ProfileSnapshot {
        val response = canonicalUpstreamIo { upstream.rpc(function, args.toString()) }
        if (!response.ok) throw ProfileRpcErrors.failure(function, response, documentNotActiveAs)
        return try {
            json.decodeFromString(ProfileSnapshot.serializer(), response.body)
        } catch (e: IllegalArgumentException) {
            // MASTER-OBS-003: idem; `function` identifica la RPC en el log.
            throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = IllegalStateException("decode $function failed", e))
        }
    }

    /**
     * Tras una escritura ya CONFIRMADA en la base, un GoTrue caido no debe convertir el exito en error:
     * se responde con email y proveedores vacios (la repeticion es idempotente). 401 si la sesion ya no existe.
     */
    private suspend fun userOrNull(accessToken: String, context: OposiBotsAuthContext): AuthProviderUser? = try {
        authProvider.user(accessToken, context)
    } catch (domain: BackendDomainException) {
        if (domain.status.value == 401) throw domain
        null
    }

    private fun buildMe(snapshot: ProfileSnapshot, user: AuthProviderUser?): MeResult {
        val status = AccountStatus.fromWire(snapshot.estadoCuenta)
        if (status == AccountStatus.UNRECOGNIZED) throw CanonicalErrorCode.FORBIDDEN.exception()
        val registeredAt = user?.createdAt ?: snapshot.creadoEn ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val me = Me(
            id = snapshot.usuarioId,
            email = user?.email,
            authProviders = user?.canonicalProviders().orEmpty(),
            registeredAt = normalizeInstant(registeredAt),
            profile = MeProfile(
                username = snapshot.username,
                nombreMostrar = snapshot.nombreMostrar,
                nombre = snapshot.nombre,
                apellidos = snapshot.apellidos,
                especialidadPrincipalId = snapshot.especialidadPrincipalId,
                comunidadPrincipalId = snapshot.comunidadPrincipalId,
                avatarUrl = null,
                onboardingCompleted = snapshot.onboardingCompleted,
                accountStatus = status.wire,
                introSeenAt = snapshot.introSeenAt?.let(::normalizeInstant),
            ),
        )
        val etag = snapshot.actualizadoEn?.let { "\"${normalizeInstant(it)}\"" }
        return MeResult(me, etag, replay = snapshot.replay)
    }

    private fun nameIssues(field: String, value: String, required: Boolean): List<ErrorIssue> {
        val trimmed = value.trim()
        // MASTER7-ARCH-001: misma regla de caracteres que el nombre visible admin (control/formato/bidi/sustitutos).
        return if (
            (required && trimmed.isEmpty()) ||
            trimmed.length > MAX_NAME_LENGTH ||
            (trimmed.isNotEmpty() && !isAcceptableDisplayName(trimmed))
        ) {
            listOf(ErrorIssue(field, CanonicalIssue.INVALID_FORMAT))
        } else {
            emptyList()
        }
    }

    private fun attributionIssues(a: AttributionHints): List<ErrorIssue> = buildList {
        a.plataforma?.let { if (it !in ATTRIBUTION_PLATFORMS) add(ErrorIssue("attribution.plataforma", CanonicalIssue.INVALID_FORMAT)) }
        mapOf("fuente" to a.fuente, "canal" to a.canal, "campania" to a.campania, "installReferrer" to a.installReferrer).forEach { (field, value) ->
            if (value != null && value.length > MAX_ATTRIBUTION_LENGTH) add(ErrorIssue("attribution.$field", CanonicalIssue.OUT_OF_RANGE))
        }
    }

    private fun attributionJson(a: AttributionHints): JsonObject = buildJsonObject {
        a.fuente?.let { put("fuente", it) }
        a.canal?.let { put("canal", it) }
        a.campania?.let { put("campania", it) }
        a.plataforma?.let { put("plataforma", it) }
        a.installReferrer?.let { put("installReferrer", it) }
    }

    @Serializable
    internal data class ProfileSnapshot(
        val usuarioId: String,
        val username: String? = null,
        val nombreMostrar: String? = null,
        val nombre: String? = null,
        val apellidos: String? = null,
        val especialidadPrincipalId: String? = null,
        val comunidadPrincipalId: String? = null,
        val onboardingCompleted: Boolean = false,
        val estadoCuenta: String? = null,
        val introSeenAt: String? = null,
        val creadoEn: String? = null,
        val actualizadoEn: String? = null,
        val replay: Boolean = false,
    )

    @Serializable
    internal data class ProfileRowDto(
        @SerialName("usuario_id") val usuarioId: String,
        val username: String? = null,
        @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
        val nombre: String? = null,
        val apellidos: String? = null,
        @SerialName("especialidad_principal_id") val especialidadPrincipalId: String? = null,
        @SerialName("comunidad_principal_id") val comunidadPrincipalId: String? = null,
        @SerialName("onboarding_completado") val onboardingCompletado: Boolean = false,
        @SerialName("estado_cuenta") val estadoCuenta: String? = null,
        @SerialName("perfil_intro_visto_en") val introSeenAt: String? = null,
        @SerialName("creado_en") val creadoEn: String? = null,
        @SerialName("actualizado_en") val actualizadoEn: String? = null,
    ) {
        fun toSnapshot() = ProfileSnapshot(
            usuarioId, username, nombreMostrar, nombre, apellidos, especialidadPrincipalId, comunidadPrincipalId,
            onboardingCompletado, estadoCuenta, introSeenAt, creadoEn, actualizadoEn,
        )
    }

    @Serializable
    private data class LegalDocumentRow(
        val id: String,
        @SerialName("tipo_documento") val tipoDocumento: String,
        val version: String,
        val titulo: String,
        val contenido: String,
        @SerialName("publicado_en") val publicadoEn: String,
    )

    companion object {
        private const val PROFILE_COLUMNS =
            "usuario_id,username,nombre_mostrar,nombre,apellidos,especialidad_principal_id,comunidad_principal_id," +
                "onboarding_completado,estado_cuenta,perfil_intro_visto_en,creado_en,actualizado_en"
        const val MAX_LEGAL_DOCUMENTS = 20
        private const val MAX_ACCEPTANCE_DOCUMENTS = 5
        private const val MAX_ONBOARDING_DOCUMENTS = 10
        private const val MAX_TARGET_COMMUNITIES = 20
        private const val MAX_NAME_LENGTH = 100
        private const val MAX_ATTRIBUTION_LENGTH = 200
        private val LEGAL_TYPES = setOf("terminos", "privacidad")
        private val ATTRIBUTION_PLATFORMS = setOf("android", "ios", "web")
        private val REFERRAL_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/** Traduccion de las claves estables de los RPC `app_*` (`message`, con el campo culpable en `hint`) al sobre canonico. */
internal object ProfileRpcErrors {
    private val SAFE_FIELD = Regex("^[A-Za-z][A-Za-z0-9_.]{0,63}$")

    fun failure(
        function: String,
        response: SupabaseRawResponse,
        documentNotActiveAs: CanonicalErrorCode = CanonicalErrorCode.DOCUMENT_NOT_ACTIVE,
    ): Exception {
        val error = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val key = error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            .takeWhile { it.isLetterOrDigit() || it == '_' }
        val hint = error?.get("hint")?.jsonPrimitive?.contentOrNull?.takeIf { SAFE_FIELD.matches(it) } ?: "body"
        val mapped: CanonicalErrorCode? = when (key) {
            "USERNAME_TAKEN" -> CanonicalErrorCode.USERNAME_TAKEN
            "UNKNOWN_SPECIALTY" -> CanonicalErrorCode.UNKNOWN_SPECIALTY
            "SPECIALTY_ALREADY_SET" -> CanonicalErrorCode.SPECIALTY_ALREADY_SET
            "UNKNOWN_COMMUNITY" -> CanonicalErrorCode.UNKNOWN_COMMUNITY
            "DOCUMENT_NOT_ACTIVE" -> documentNotActiveAs
            "ALREADY_ONBOARDED_CONFLICT" -> CanonicalErrorCode.ALREADY_ONBOARDED_CONFLICT
            "PRECONDITION_FAILED" -> CanonicalErrorCode.PRECONDITION_FAILED
            "PROFILE_MISSING" -> CanonicalErrorCode.PROFILE_MISSING
            "ACCOUNT_BLOCKED" -> CanonicalErrorCode.ACCOUNT_BLOCKED
            "ACCOUNT_DELETION_PENDING" -> CanonicalErrorCode.ACCOUNT_DELETION_PENDING
            "FORBIDDEN" -> CanonicalErrorCode.FORBIDDEN
            else -> null
        }
        if (mapped != null) return mapped.exception()
        if (key == "INVALID_ARGUMENT") return validationError(ErrorIssue(hint, CanonicalIssue.INVALID_FORMAT))
        if (key == "UNKNOWN_FIELD") return validationError(ErrorIssue(hint, CanonicalIssue.UNKNOWN_FIELD))
        // Bloqueo, deadlock, timeouts: 503 DB_BUSY con Retry-After, igual que el resto del backend.
        mapSupabaseDomainError(response.status, response.body)?.let { return it }
        val failure = IllegalStateException("Supabase RPC $function -> ${response.status}: ${response.body.take(300)}")
        // MASTER-API-003: una caida de PostgREST (5xx) en una ruta canonica es 503 UPSTREAM_UNAVAILABLE (contrato), no 502/504.
        if (response.status >= 500) return CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = failure)
        return failure
    }
}

/**
 * MASTER-API-003: `withUpstreamIo` para las rutas canonicas. Un fallo de red o un 5xx del transporte
 * (`IllegalStateException`) sale como 503 UPSTREAM_UNAVAILABLE (lo que documenta profiles.yaml) y conserva la causa
 * para el log; un error de dominio ya traducido pasa tal cual.
 */
internal suspend fun <T> canonicalUpstreamIo(block: () -> T): T = try {
    withUpstreamIo(block)
} catch (failure: IllegalStateException) {
    throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = failure)
}

// ---------- utilidades ----------

private val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun String.isUuid(): Boolean = UUID_TEXT.matches(this)

/**
 * MASTER-API-007: ETag FUERTE de la lista de documentos legales = sha256 de (id, version, publicadoEn, contenido) en
 * orden. Editar el contenido, sustituir o revertir un documento cambia el ETag aunque la fecha mas reciente y el
 * numero de documentos sean los mismos.
 */
internal fun legalDocumentsEtag(items: List<LegalDocument>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    items.forEach { doc ->
        listOf(doc.id, doc.version, doc.publicadoEn, doc.contenido).forEach {
            val bytes = it.toString().toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
    }
    return "\"legal-" + digest.digest().joinToString("") { "%02x".format(it) }.take(32) + "\""
}

/**
 * MASTER-API-007: `If-None-Match` como lo define RFC 9110: `*`, lista separada por comas, prefijo `W/` (comparacion
 * debil). Antes solo un valor exacto producia 304.
 */
internal fun ifNoneMatchMatches(header: String?, etag: String): Boolean {
    val value = header?.trim().orEmpty()
    if (value.isEmpty()) return false
    if (value == "*") return true
    fun opaque(tag: String) = tag.trim().removePrefix("W/").trim()
    val current = opaque(etag)
    return value.split(',').any { opaque(it) == current }
}

/** Un instante ISO cualquiera (`Z` u offset, con microsegundos) a `Instant.toString()`; lo ilegible se devuelve tal cual. */
internal fun normalizeInstant(value: String): String =
    try {
        OffsetDateTime.parse(value).toInstant().toString()
    } catch (_: DateTimeParseException) {
        value
    }

/** Si el cuerpo trae un `null` explicito en cualquier nivel: la ruta de la primera. Los `null` no significan "borrar" en estos comandos. */
internal fun firstExplicitNull(element: JsonElement, path: String = ""): String? = when (element) {
    is JsonNull -> path.ifEmpty { "body" }
    is JsonObject -> element.entries.firstNotNullOfOrNull { (key, child) -> firstExplicitNull(child, if (path.isEmpty()) key else "$path.$key") }
    is JsonArray -> element.withIndex().firstNotNullOfOrNull { (i, child) -> firstExplicitNull(child, "$path[$i]") }
    else -> null
}
