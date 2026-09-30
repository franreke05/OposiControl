package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.Normalizer

/**
 * Texto legacy de especialidad -> `especialidades.id` (SLICE 1, DEC-002).
 *
 * Lo usa UNICAMENTE el adaptador deprecado `/v1/auth/register/profile`, que recibe
 * la especialidad como texto. Regla del backfill de SLICE 0: coincidencia EXACTA
 * (sin acentos ni mayusculas, espacios colapsados) contra `nombre` o `slug` de una
 * especialidad ACTIVA. Cero o mas de una coincidencia -> `null` (el llamante
 * responde `SPECIALTY_NOT_SELECTED`): nunca se adivina.
 */
internal fun interface SpecialtyResolver {
    suspend fun resolve(text: String): String?
}

internal class PostgrestSpecialtyResolver(
    private val upstream: CanonicalUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SpecialtyResolver {
    @Serializable
    private data class Row(val id: String, val nombre: String? = null, val slug: String? = null)

    override suspend fun resolve(text: String): String? {
        val wanted = normalizeSpecialtyText(text)
        if (wanted.isEmpty()) return null
        val body = canonicalUpstreamIo { upstream.get("/rest/v1/especialidades?select=id,nombre,slug&activa=eq.true&limit=${SPECIALTY_FETCH_LIMIT + 1}") }
        val rows = json.decodeFromString(ListSerializer(Row.serializer()), body)
        // MASTER-QUALITY-007: una lectura truncada podria ocultar la especialidad buscada (o una segunda coincidencia):
        // fail-closed en vez de resolver sobre datos incompletos. MASTER2-API-003: se piden LIMIT+1 filas, asi que solo
        // hay truncado si llegan MAS de LIMIT (con exactamente LIMIT la lectura es completa).
        if (rows.size > SPECIALTY_FETCH_LIMIT) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return matchSpecialty(wanted, rows.map { Triple(it.id, it.nombre, it.slug) })
    }
}

/** Puro y probado aparte: `id` si EXACTAMENTE una especialidad casa por nombre o slug, si no `null`. */
internal fun matchSpecialty(normalizedText: String, candidates: List<Triple<String, String?, String?>>): String? {
    val ids = candidates
        .filter { (_, nombre, slug) ->
            nombre?.let(::normalizeSpecialtyText) == normalizedText || slug?.let(::normalizeSpecialtyText) == normalizedText
        }
        .map { it.first }
        .distinct()
    return ids.singleOrNull()
}

internal const val SPECIALTY_FETCH_LIMIT = 500

// Hoisted (MASTER-QUALITY-007): antes se compilaban dos Regex por llamada, ~2000 por peticion.
private val COMBINING_MARKS = Regex("\\p{M}+")
private val WHITESPACE_RUN = Regex("\\s+")

internal fun normalizeSpecialtyText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .trim()
        .replace(WHITESPACE_RUN, " ")

/**
 * Los dos endpoints legacy de OB, reescritos como traduccion -> servicio canonico -> traduccion
 * (mismo espiritu que [LegacyAdapter], que es un patron de referencia aun NO cableado: esta clase
 * es una implementacion propia y no delega en el): sin reglas de negocio, sin tablas ni RPC propias, sin escribir `public.usuario`.
 */
internal class LegacyProfileAdapter(
    private val profiles: CanonicalProfileService,
    private val specialties: SpecialtyResolver,
) {
    /** `GET /v1/auth/me` (EP-124) sobre `GET /api/v1/app/me`. */
    suspend fun me(identity: CanonicalUserIdentity, accessToken: String, context: OposiBotsAuthContext): OposiBotsMeResponse {
        val me = profiles.me(identity, accessToken, context).me
        return OposiBotsMeResponse(
            OposiBotsAuthUserDto(
                id = me.id,
                email = me.email,
                profileComplete = me.profile.onboardingCompleted,
                onboardingCompleted = me.profile.onboardingCompleted,
                accountStatus = me.profile.accountStatus,
            ),
        )
    }

    /**
     * `POST /v1/auth/register/profile` (EP-218) sobre `POST /api/v1/app/me/onboarding`.
     *
     * - `specialty` (texto) -> id o `SPECIALTY_NOT_SELECTED`.
     * - `username` opcional en el cable legacy pero obligatorio en el canonico: si no viene se reutiliza el ya
     *   guardado; sin ninguno -> `INVALID_USERNAME` (decision de producto pendiente, BLOCKED_PRODUCT_DECISION).
     * - Sin aceptacion legal: el cable legacy no la lleva (decision de producto pendiente, R3).
     */
    suspend fun saveProfile(
        identity: CanonicalUserIdentity,
        accessToken: String,
        request: OposiBotsProfileRequest,
        context: OposiBotsAuthContext,
    ) {
        val specialtyId = specialties.resolve(request.specialty)
            ?: throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED)
        val username = request.username?.trim()?.takeIf(String::isNotEmpty)
            ?: profiles.me(identity, accessToken, context).me.profile.username
            ?: throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_USERNAME)
        profiles.completeOnboarding(
            identity,
            accessToken,
            OnboardingRequest(
                username = username,
                nombre = request.firstName,
                apellidos = request.lastName,
                especialidadId = specialtyId,
                acceptedLegalDocumentIds = emptyList(),
            ),
            context,
        )
    }
}
