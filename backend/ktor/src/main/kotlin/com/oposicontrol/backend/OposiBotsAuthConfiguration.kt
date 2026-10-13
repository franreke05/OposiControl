package com.oposicontrol.backend

import kotlinx.serialization.json.Json

internal data class OposiBotsAuthConfiguration(
    val supabaseUrl: String,
    val publishableKey: String,
    val secretKey: String,
    val appEnvironment: String,
    val passwordRecoveryRedirectUrl: String?,
    val appleClientId: String?,
    val appleClientSecret: String?,
    val captchaRequired: Boolean,
    val trustProxyHeaders: Boolean,
    val trustedProxyHosts: Set<String>,
    val forwardClientIpToSupabase: Boolean,
    val rateLimitMode: String,
    val rateLimitHmacSecret: String,
    val valkeyUrl: String?,
    /**
     * B2 (2026-10-13): `redirect_to` del correo de CONFIRMACION (signup y resend), como [passwordRecoveryRedirectUrl] lo es
     * del de recuperacion. `null` (variable ausente o vacia) = no se envia `redirect_to`, el comportamiento de siempre.
     */
    val emailConfirmRedirectUrl: String? = null,
) {
    val development: Boolean
        get() = AppEnvironment.isDevelopment(appEnvironment)

    companion object {
        fun fromEnvironment(required: Boolean): OposiBotsAuthConfiguration? {
            val enabled = required ||
                System.getenv("OPOSIBOTS_AUTH_ENABLED")?.toBooleanStrictOrNull() == true
            if (!enabled) return null

            // MASTER2-SEC-001: el mismo resolvedor fail-closed que el resto (vacio/ausente -> production).
            val environment = AppEnvironment.fromSystemEnvironment()
            val serviceKey = System.getenv("SUPABASE_SECRET_KEY")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("SUPABASE_SERVICE_ROLE_KEY")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: error("SUPABASE_SECRET_KEY es obligatorio para Auth consumer.")
            val publishableKey = System.getenv("SUPABASE_PUBLISHABLE_KEY")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: System.getenv("SUPABASE_ANON_KEY")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: if (AppEnvironment.isDevelopment(environment)) {
                    serviceKey
                } else {
                    error("SUPABASE_PUBLISHABLE_KEY es obligatorio fuera de desarrollo.")
                }
            val rateLimitMode = System.getenv("AUTH_RATE_LIMIT_MODE")
                ?.trim()
                ?.lowercase()
                ?.takeIf(String::isNotBlank)
                ?: error("AUTH_RATE_LIMIT_MODE debe ser 'valkey' o, solo en desarrollo, 'memory'.")
            val hmacSecret = System.getenv("AUTH_RATE_LIMIT_HMAC_SECRET")
                ?.takeIf { it.length >= 32 }
                ?: error("AUTH_RATE_LIMIT_HMAC_SECRET debe tener al menos 32 caracteres.")
            val configuration = OposiBotsAuthConfiguration(
                supabaseUrl = requiredEnvironment("SUPABASE_URL").trimEnd('/'),
                publishableKey = publishableKey,
                secretKey = serviceKey,
                appEnvironment = environment,
                passwordRecoveryRedirectUrl = optionalEnvironment("OPOSIBOTS_PASSWORD_RECOVERY_REDIRECT_URL"),
                appleClientId = optionalEnvironment("APPLE_CLIENT_ID"),
                appleClientSecret = optionalEnvironment("APPLE_CLIENT_SECRET"),
                captchaRequired = System.getenv("AUTH_CAPTCHA_REQUIRED")
                    ?.toBooleanStrictOrNull()
                    ?: !AppEnvironment.isDevelopment(environment),
                trustProxyHeaders = System.getenv("TRUST_PROXY_HEADERS")
                    ?.toBooleanStrictOrNull()
                    ?: false,
                trustedProxyHosts = optionalEnvironment("TRUSTED_PROXY_HOSTS")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    .orEmpty(),
                forwardClientIpToSupabase = System.getenv("SUPABASE_AUTH_FORWARD_CLIENT_IP")
                    ?.toBooleanStrictOrNull()
                    ?: false,
                rateLimitMode = rateLimitMode,
                rateLimitHmacSecret = hmacSecret,
                valkeyUrl = optionalEnvironment("VALKEY_URL"),
                emailConfirmRedirectUrl = optionalEnvironment("OPOSIBOTS_EMAIL_CONFIRM_REDIRECT_URL"),
            )
            configuration.requireDeployable()
            return configuration
        }

        /** Invariantes de arranque (fallo cerrado). Separadas de la lectura del entorno para poder probarlas. */
        internal fun OposiBotsAuthConfiguration.requireDeployable() {
            require(supabaseUrl.startsWith("https://") || development) {
                "SUPABASE_URL debe usar HTTPS fuera de desarrollo."
            }
            require(rateLimitMode == "valkey" || (rateLimitMode == "memory" && development)) {
                "El rate limiter en memoria solo está permitido en desarrollo."
            }
            // MASTER-SEC-001: la comparacion con el peer es solo por literal IP; un nombre de host nunca coincidiria.
            require(trustedProxyHosts.all { isIpLiteral(normalizePeerAddress(it)) }) {
                "TRUSTED_PROXY_HOSTS solo admite literales IP (no nombres de host)."
            }
            require(!trustProxyHeaders || trustedProxyHosts.isNotEmpty()) {
                "TRUSTED_PROXY_HOSTS es obligatorio cuando TRUST_PROXY_HEADERS=true."
            }
            // MASTER2-AUTH-002: fuera de desarrollo el backend corre SIEMPRE detras de Caddy (ops/caddy, DEPLOYMENT.md).
            // Sin confiar en su X-Forwarded-For, la "IP de cliente" es la del proxy: todos los usuarios comparten un
            // unico cupo por IP (auto-DoS) y la clave (email, IP) del login se degrada a (email, proxy).
            require(development || trustProxyHeaders) {
                "Fuera de desarrollo TRUST_PROXY_HEADERS=true es obligatorio (el backend corre detras de Caddy), " +
                    "con TRUSTED_PROXY_HOSTS = IP literal del proxy."
            }
            if (rateLimitMode == "valkey") require(!valkeyUrl.isNullOrBlank()) {
                "VALKEY_URL es obligatorio cuando AUTH_RATE_LIMIT_MODE=valkey."
            }
        }

        private fun optionalEnvironment(name: String): String? =
            System.getenv(name)?.trim()?.takeIf(String::isNotBlank)

        private fun requiredEnvironment(name: String): String =
            optionalEnvironment(name) ?: error("$name es obligatorio.")
    }
}

internal class OposiBotsAuthRuntime(
    val configuration: OposiBotsAuthConfiguration,
    val gateway: OposiBotsAuthGateway,
    val rateLimiter: OposiBotsAuthRateLimiter,
    val json: Json,
    /** SLICE 1: perfil canonico, borrado de cuenta y adaptadores legacy. Obligatorio (MASTER-ARCH-005): sin costura nula. */
    val canonical: CanonicalProfileRuntime,
    /**
     * B1 (2026-10-13): retiene el correo de register/resend/recover si el email tiene una solicitud de acceso de
     * administrador no aprobada (AccessRequestMailGuard.kt). OBLIGATORIO y sin valor por defecto: un runtime no puede
     * quedarse sin guarda por omision. [fromConfiguration] cablea la real; las pruebas pasan su doble de forma explicita.
     */
    val accessRequestMailGuard: AccessRequestMailGuard,
) : AutoCloseable {
    /**
     * MASTER-ARCH-008: cierre ordenado (cliente Valkey del limitador y `HttpClient` del gateway) al parar la
     * aplicacion. Cada recurso se cierra aunque el anterior falle.
     */
    override fun close() {
        // MASTER2-OBS-004: un cierre fallido se registra (redactado) en vez de tragarse en silencio.
        runCatching { rateLimiter.close() }.onFailure { logCloseFailure("rateLimiter", it) }
        runCatching { (gateway as? AutoCloseable)?.close() }.onFailure { logCloseFailure("gateway", it) }
    }

    private fun logCloseFailure(resource: String, failure: Throwable) {
        org.slf4j.LoggerFactory.getLogger("OC-KTOR-AUTH")
            .warn("runtime close failed resource={}: {}", resource, failure.redactedMessage("close failed"))
    }

    companion object {
        fun fromEnvironment(json: Json, required: Boolean, jwtVerifier: SupabaseJwtVerifier? = null): OposiBotsAuthRuntime? {
            val configuration = OposiBotsAuthConfiguration.fromEnvironment(required) ?: return null
            return fromConfiguration(configuration, json, jwtVerifier)
        }

        /**
         * Todo el cableado de [fromEnvironment] salvo la lectura del entorno, para poder probarlo: UNA sola guarda real
         * ([PostgrestAccessRequestMailGuard]) hace de guarda de correo del runtime y de puerta de sesion del gateway.
         * No abre ninguna conexion al construirse.
         */
        internal fun fromConfiguration(
            configuration: OposiBotsAuthConfiguration,
            json: Json,
            jwtVerifier: SupabaseJwtVerifier? = null,
        ): OposiBotsAuthRuntime {
            val accessRequests = accessRequestMailGuard(configuration)
            val gateway = SupabaseOposiBotsAuthGateway(configuration, json, accessRequestSessionGate = accessRequests)
            return OposiBotsAuthRuntime(
                configuration = configuration,
                gateway = gateway,
                rateLimiter = OposiBotsAuthRateLimiterFactory.create(configuration),
                json = json,
                canonical = canonicalProfileRuntime(
                    configuration = configuration,
                    authProvider = gateway,
                    verifier = jwtVerifier ?: SupabaseJwtVerifier(configuration.supabaseUrl, json),
                    json = json,
                ),
                accessRequestMailGuard = accessRequests,
            )
        }

        /**
         * La guarda real: misma URL y misma clave de servicio que el resto del runtime de auth, y el seudonimo con clave
         * del correo para sus logs. Devuelve el tipo concreto porque es a la vez guarda de correo y puerta de sesion.
         */
        internal fun accessRequestMailGuard(configuration: OposiBotsAuthConfiguration): PostgrestAccessRequestMailGuard {
            val transport = SupabaseRequestTransport(configuration.supabaseUrl, SupabaseRequestAuthPolicy(configuration.secretKey))
            return PostgrestAccessRequestMailGuard(
                upstream = { path -> transport.get(path) },
                emailRef = configuration::authLogEmailRef,
            )
        }

        /**
         * El servicio canonico de perfil con la clave de servicio (`SupabaseRequestTransport`) y el mismo
         * `SupabaseJwtVerifier` que el resto del backend. Identidad = solo el `sub` del JWT.
         */
        internal fun canonicalProfileRuntime(
            configuration: OposiBotsAuthConfiguration,
            authProvider: AuthProviderClient,
            verifier: SupabaseJwtVerifier,
            json: Json,
        ): CanonicalProfileRuntime {
            val transport = SupabaseRequestTransport(configuration.supabaseUrl, SupabaseRequestAuthPolicy(configuration.secretKey))
            val upstream = TransportCanonicalUpstream(transport)
            val profiles = SupabaseCanonicalProfileService(upstream, authProvider, json)
            return CanonicalProfileRuntime(
                resolver = CanonicalIdentityResolver(
                    verifySubject = verifier::subFrom,
                    directory = PostgrestCanonicalProfileDirectory(transport),
                    verifyClaims = verifier::claimsFrom,
                ),
                profiles = profiles,
                deletion = AccountDeletionService(upstream, authProvider, json = json),
                legacy = LegacyProfileAdapter(profiles, PostgrestSpecialtyResolver(upstream, json)),
            )
        }
    }
}
