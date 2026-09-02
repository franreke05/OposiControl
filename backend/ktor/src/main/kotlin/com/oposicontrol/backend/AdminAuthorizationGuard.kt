package com.oposicontrol.backend

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.path

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
 */
internal val AdminAuthorizationPlugin = createRouteScopedPlugin(
    name = "AdminAuthorization",
    createConfiguration = ::AdminAuthorizationConfig,
) {
    val appEnv = pluginConfig.appEnv
    val hasAdminRole = pluginConfig.hasAdminRole
    val isLocalPeer = pluginConfig.isLocalPeer
    on(AuthenticationChecked) { call ->
        // Falla cerrado, no abierto. Si algún día se añade otro proveedor a
        // `authenticate(...)` o cambia el tipo de principal, `return@on` dejaría
        // pasar cada llamada autenticada al handler sin error y sin traza.
        val principal = call.principal<UserIdPrincipal>()
            ?: throw AppUnauthorized("Principal no reconocido para la superficie de administración.")
        adminAuthorizationFailure(
            principalName = principal.name,
            path = call.request.path(),
            // remoteAddress, no remoteHost: el segundo hace DNS inverso sobre el peer
            // y ese nombre lo controla quien conecta (ver isLocalDevelopmentPeer).
            remoteAddress = call.request.origin.remoteAddress,
            appEnv = appEnv,
            hasAdminRole = hasAdminRole,
            isLocalPeer = isLocalPeer,
        )?.let { throw it }
    }
}

internal class AdminAuthorizationConfig {
    var appEnv: String = ""
    var hasAdminRole: (String) -> Boolean = { false }

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
): Exception? {
    if (principalName == STATIC_TOKEN_PRINCIPAL) {
        val allowed = isLocalPeer(appEnv, remoteAddress) && isLegacyStaticTokenPath(path)
        return if (allowed) {
            null
        } else {
            AppUnauthorized("El token estatico solo esta permitido en superficies legacy locales de desarrollo.")
        }
    }
    return if (hasAdminRole(principalName)) {
        null
    } else {
        AppForbidden("No tienes permisos para acceder al panel de administración.")
    }
}

/** Nombre del principal que representa al token estático, no a una persona. */
internal const val STATIC_TOKEN_PRINCIPAL = "oposicontrol-admin"

