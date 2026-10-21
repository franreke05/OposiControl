package com.oposicontrol.backend

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import kotlinx.coroutines.CancellationException

/**
 * Aplica [adminAuthorizationFailure] a cada llamada ya autenticada.
 *
 * Se engancha a `AuthenticationChecked`, el punto que Ktor expone justo después
 * de resolver la autenticación y precisamente para autorizar. El intento
 * anterior — `intercept(ApplicationCallPipeline.Plugins)` dentro del bloque
 * `authenticate` — no llegaba a ejecutarse, así que las dos reglas estaban
 * escritas pero no se aplicaban: con el servidor en marcha, el token estático
 * alcanzaba las rutas canónicas de admin y devolvía datos reales.
 *
 * Lanzar aquí permite además conservar la distinción del contrato: 401 cuando la
 * credencial no vale para esa superficie y 403 cuando la identidad es válida pero
 * no tiene rol admin.
 *
 * La consulta de rol corre en el pool IO ([authorizeAdminCall], F-03): este hook
 * se ejecuta ANTES de cada handler administrativo, y un fallo de caché son
 * lecturas bloqueantes a Supabase.
 */
internal val AdminAuthorizationPlugin = createRouteScopedPlugin(
    name = "AdminAuthorization",
    createConfiguration = ::AdminAuthorizationConfig,
) {
    val appEnv = pluginConfig.appEnv
    val hasAdminRole = pluginConfig.hasAdminRole
    val hasModeratorRole = pluginConfig.hasModeratorRole
    val isLocalPeer = pluginConfig.isLocalPeer
    on(AuthenticationChecked) { call ->
        // Falla cerrado, no abierto. Si algún día se añade otro proveedor a
        // `authenticate(...)` o cambia el tipo de principal, `return@on` dejaría
        // pasar cada llamada autenticada al handler sin error y sin traza.
        val principal = call.principal<UserIdPrincipal>()
            ?: throw AppUnauthorized("Principal no reconocido para la superficie de administración.")
        authorizeAdminCall(
            principalName = principal.name,
            path = call.request.path(),
            // remoteAddress, no remoteHost: el segundo hace DNS inverso sobre el peer
            // y ese nombre lo controla quien conecta (ver isLocalDevelopmentPeer).
            remoteAddress = call.request.origin.remoteAddress,
            appEnv = appEnv,
            hasAdminRole = hasAdminRole,
            isLocalPeer = isLocalPeer,
            hasModeratorRole = hasModeratorRole,
        )?.let { throw it }
    }
}

internal class AdminAuthorizationConfig {
    var appEnv: String = ""
    var hasAdminRole: (String) -> Boolean = { false }

    /** Rol `moderador` con cuenta activa. Solo se consulta en [isModeratorAdminPath]; por defecto nadie es moderador. */
    var hasModeratorRole: (String) -> Boolean = { false }

    /**
     * Cómo se decide que el par es local. El valor por defecto es la comprobación
     * real y estricta sobre la IP del socket.
     *
     * Es configurable únicamente porque el motor de pruebas de Ktor reporta
     * `localhost` como dirección del par, y aceptar nombres de host en producción
     * es justo el agujero que se cerró (ver `isLocalDevelopmentPeer`). El seam
     * permite ejercitar el camino permitido sin relajar la regla real.
     */
    var isLocalPeer: (appEnv: String, remoteAddress: String) -> Boolean = ::isLocalDevelopmentPeer
}

/**
 * Autorización de las superficies administrativas.
 *
 * Impone dos reglas:
 *
 * 1. El token estático sólo vale en superficies legacy y desde un par local de
 *    desarrollo. No es una credencial de usuario y no debe abrir el panel canónico.
 * 2. Un JWT válido no basta: hace falta rol admin. Sin esto, cualquier cuenta con
 *    sesión podría leer y escribir el panel.
 *
 * **Dónde se ejecuta importa tanto como qué comprueba.** Esta lógica vivía en un
 * `intercept(ApplicationCallPipeline.Plugins)`, fase que corre *antes* de que el
 * plugin de autenticación rellene el principal: ambas comprobaciones leían
 * `principal == null`, no rechazaban nada, y la llamada continuaba. Las reglas
 * estaban escritas pero no se aplicaban — con el servidor en marcha, el token
 * estático llegaba a las rutas canónicas de admin y devolvía datos reales. Ahora
 * se aplica desde [AdminAuthorizationPlugin], en el hook `AuthenticationChecked`.
 *
 * (Ojo al escribir aquí una ruta con comodín: Kotlin anida comentarios de bloque,
 * así que un `/` seguido de `*` dentro del KDoc lo deja sin cerrar.)
 *
 * La decisión se aísla aquí para poder probarla entera (principal + ruta + par +
 * entorno). Probar sólo el predicado de rutas es justo lo que dejó pasar el fallo.
 *
 * Devuelve la excepción a lanzar, o null si la llamada puede continuar.
 */
internal fun adminAuthorizationFailure(
    principalName: String,
    path: String,
    remoteAddress: String,
    appEnv: String,
    hasAdminRole: (String) -> Boolean,
    isLocalPeer: (String, String) -> Boolean = ::isLocalDevelopmentPeer,
    hasModeratorRole: (String) -> Boolean = { false },
): Exception? {
    if (principalName == STATIC_TOKEN_PRINCIPAL) {
        val allowed = isLocalPeer(appEnv, remoteAddress) && isLegacyStaticTokenPath(path)
        return if (allowed) {
            null
        } else {
            AppUnauthorized("El token estatico solo esta permitido en superficies legacy locales de desarrollo.")
        }
    }
    return if (hasAdminRole(principalName) || (isModeratorAdminPath(path) && hasModeratorRole(principalName))) {
        null
    } else {
        AppForbidden("No tienes permisos para acceder al panel de administración.")
    }
}

/**
 * Decisión del propietario 2026-10-20: el rol `moderador` entra en el panel SOLO para moderar el foro. Lista CERRADA y
 * exacta (sin prefijos): la sesión, la cola, la acción de moderar y la lectura de restricciones. Canales, bloqueos,
 * levantar una restricción (la RPC exige admin en SQL) y todo lo demás siguen siendo solo de admin.
 */
internal val MODERATOR_ADMIN_PATHS: Set<String> = setOf(
    "/api/v1/admin/sesion",
    "/api/v1/admin/foro/cola",
    "/api/v1/admin/foro/moderar",
    "/api/v1/admin/foro/restricciones",
)

internal fun isModeratorAdminPath(path: String): Boolean = path.trimEnd('/') in MODERATOR_ADMIN_PATHS

/**
 * [adminAuthorizationFailure] con la lectura de rol FUERA del hilo de la petición (F-03).
 *
 * `hasAdminRole` de producción ([AppV1Gateway.hasAdminRole]) hace lecturas
 * bloqueantes a Supabase en cada fallo de caché, y este código corre en el hook
 * `AuthenticationChecked`, delante de CADA handler administrativo: en el hilo de
 * Netty era exactamente la inanición del pool que [withUpstreamIo] vino a cerrar,
 * pagada antes de que el handler llegara a saltar al pool IO.
 *
 * Dos reglas que no cambian respecto a la decisión pura:
 *
 * - El token estático no es una persona: su rol **no se consulta**, ni aquí ni
 *   dentro de la decisión. No hay salto de hilo que pagar para él.
 * - Una lectura de rol que FALLA propaga su excepción. Convertirla en `false`
 *   devolvería 403 —"no eres admin"— por un Supabase caído, que es mentir sobre
 *   el permiso; la excepción llega a StatusPages como 500 con referencia.
 *
 * La firma de [adminAuthorizationFailure] se conserva: la usan sus propias pruebas
 * y las de sincronización de roles.
 */
internal suspend fun authorizeAdminCall(
    principalName: String,
    path: String,
    remoteAddress: String,
    appEnv: String,
    hasAdminRole: (String) -> Boolean,
    isLocalPeer: (String, String) -> Boolean = ::isLocalDevelopmentPeer,
    hasModeratorRole: (String) -> Boolean = { false },
): Exception? {
    if (principalName == STATIC_TOKEN_PRINCIPAL) {
        return adminAuthorizationFailure(
            principalName = principalName,
            path = path,
            remoteAddress = remoteAddress,
            appEnv = appEnv,
            hasAdminRole = { error("El rol del token estático no se consulta.") },
            isLocalPeer = isLocalPeer,
        )
    }
    val hasRole = withUpstreamIo { hasAdminRole(principalName) }
    // El rol moderador solo se lee si hace falta: no admin y ruta de la lista cerrada.
    val hasModerator = !hasRole && isModeratorAdminPath(path) && withUpstreamIo { hasModeratorRole(principalName) }
    return adminAuthorizationFailure(
        principalName = principalName,
        path = path,
        remoteAddress = remoteAddress,
        appEnv = appEnv,
        hasAdminRole = { hasRole },
        isLocalPeer = isLocalPeer,
        hasModeratorRole = { hasModerator },
    )
}

/**
 * El principal de un JWT verificado, o `null` si no verifica (→ 401).
 *
 * La verificación va al pool IO (F-03): la primera vez, con un `kid` nuevo o tras
 * una rotación, [SupabaseJwtVerifier.subFrom] descarga el JWKS con una petición
 * bloqueante, y el bloque `authenticate {}` corre en el hilo de la petición.
 *
 * Un fallo de verificación —firma, emisor, `kid` desconocido con un JWKS sano— es
 * "credencial no válida" y acaba en 401 (sobre UNAUTHENTICATED, ver
 * `installBackendStatusPages`). La cancelación NO es un fallo de verificación: se
 * relanza, porque tragársela convertiría "el cliente se fue" en un 401 y dejaría la
 * corrutina sin cancelar.
 *
 * MASTER-API-018: un JWKS NO DISPONIBLE ([java.io.IOException] de [SupabaseJwtVerifier],
 * RB-1-06) ya no es un 401: decía "tu sesión no vale" por una caída de infraestructura
 * (el cliente cerraba sesión) y no dejaba traza. Ahora es 503 UPSTREAM_UNAVAILABLE con
 * `Retry-After`, que StatusPages publica con el sobre canónico y registra como
 * SUPABASE_REQUEST_FAILED — el mismo criterio que ya aplican las rutas canónicas.
 */
internal suspend fun verifiedJwtPrincipalOrNull(
    token: String,
    verify: (String) -> String,
): UserIdPrincipal? =
    try {
        UserIdPrincipal(withUpstreamIo { verify(token) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: java.io.IOException) {
        // MASTER5-OBS-002: la causa viaja para que StatusPages registre el motivo real del 503 (redactado).
        throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(
            retryAfterSeconds = JWKS_UNAVAILABLE_RETRY_AFTER_SECONDS,
            cause = unavailable,
        )
    } catch (rejected: Exception) {
        logJwtRejected("gateway-auth", rejected)
        null
    }

/** Segundos de `Retry-After` cuando el JWKS no responde (la ventana anti-tormenta del verificador es de 5 s). */
internal const val JWKS_UNAVAILABLE_RETRY_AFTER_SECONDS = 5

/** Nombre del principal que representa al token estático, no a una persona. */
internal const val STATIC_TOKEN_PRINCIPAL = "oposicontrol-admin"

