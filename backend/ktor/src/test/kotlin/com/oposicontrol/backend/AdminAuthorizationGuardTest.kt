package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Autorización de las superficies admin.
 *
 * Existía ya una prueba de que `isLegacyStaticTokenPath` clasifica bien las rutas,
 * y pasaba, pero comprobaba sólo el predicado: la comprobación real vivía en un
 * interceptor colocado en una fase anterior a la autenticación, así que leía un
 * principal nulo y no rechazaba nada. Con el servidor en marcha, el token
 * estático llegaba a las rutas canónicas de admin y devolvía datos reales.
 *
 * Estas pruebas atacan la decisión completa (principal + ruta + par + entorno),
 * que es lo que de verdad decide si una llamada entra.
 */
class AdminAuthorizationGuardTest {

    private fun failureFor(
        principalName: String,
        path: String,
        remoteHost: String = "127.0.0.1",
        appEnv: String = "dev",
        adminRoles: Set<String> = emptySet(),
    ) = adminAuthorizationFailure(
        principalName = principalName,
        path = path,
        remoteHost = remoteHost,
        appEnv = appEnv,
        hasAdminRole = { it in adminRoles },
    )

    // --- token estático ---

    @Test
    fun theStaticTokenCannotReachCanonicalAdminRoutes() {
        // El caso que se observó en ejecución devolviendo 200 con datos reales.
        val failure = failureFor(STATIC_TOKEN_PRINCIPAL, "/api/v1/admin/comercial/productos")

        assertTrue(failure is AppUnauthorized, "El token estatico no debe abrir el panel canonico.")
    }

    @Test
    fun theStaticTokenCannotReachOtherCanonicalSurfacesEither() {
        assertTrue(failureFor(STATIC_TOKEN_PRINCIPAL, "/api/v1/admin/usuarios") is AppUnauthorized)
        assertTrue(failureFor(STATIC_TOKEN_PRINCIPAL, "/api/v1/admin/tickets") is AppUnauthorized)
        assertTrue(failureFor(STATIC_TOKEN_PRINCIPAL, "/api/v1/app/home") is AppUnauthorized)
    }

    @Test
    fun theStaticTokenStillWorksOnLegacyLocalSurfaces() {
        // Es su único uso admitido: compatibilidad legacy en desarrollo local.
        assertNull(failureFor(STATIC_TOKEN_PRINCIPAL, "/admin/backend/restart"))
        assertNull(failureFor(STATIC_TOKEN_PRINCIPAL, "/content/noticias"))
    }

    @Test
    fun theStaticTokenIsRejectedFromANonLocalPeerEvenOnLegacyPaths() {
        val failure = failureFor(
            STATIC_TOKEN_PRINCIPAL,
            "/admin/backend/restart",
            remoteHost = "203.0.113.10",
        )

        assertTrue(failure is AppUnauthorized)
    }

    @Test
    fun theStaticTokenIsRejectedOutsideDevelopmentEnvironments() {
        // En producción no debe abrir nada, ni siquiera desde localhost.
        val failure = failureFor(
            STATIC_TOKEN_PRINCIPAL,
            "/admin/backend/restart",
            appEnv = "prod",
        )

        assertTrue(failure is AppUnauthorized)
    }

    // --- principales JWT ---

    @Test
    fun aVerifiedUserWithoutAdminRoleIsForbidden() {
        // Un JWT válido demuestra identidad, no autorización. Sin esto, cualquier
        // cuenta con sesión leería y escribiría el panel.
        val failure = failureFor("user-sin-rol", "/api/v1/admin/usuarios", adminRoles = emptySet())

        assertTrue(failure is AppForbidden)
    }

    @Test
    fun aVerifiedAdminIsAllowed() {
        assertNull(
            failureFor("admin-1", "/api/v1/admin/usuarios", adminRoles = setOf("admin-1")),
        )
    }

    @Test
    fun theAdminRoleIsCheckedForLegacySurfacesToo() {
        // Las rutas legacy escriben datos reales: no pueden quedar fuera del control.
        val failure = failureFor("user-sin-rol", "/admin/ticket-wallets", adminRoles = emptySet())

        assertTrue(failure is AppForbidden)
    }

    @Test
    fun theRoleLookupReceivesThePrincipalName() {
        // Si se consultase otro identificador, el permiso se decidiría sobre el
        // usuario equivocado.
        val asked = mutableListOf<String>()
        adminAuthorizationFailure(
            principalName = "usuario-objetivo",
            path = "/api/v1/admin/usuarios",
            remoteHost = "127.0.0.1",
            appEnv = "dev",
            hasAdminRole = { asked += it; true },
        )

        assertEquals(listOf("usuario-objetivo"), asked)
    }

    @Test
    fun theStaticTokenNeverConsultsTheRoleLookup() {
        // No representa a una persona: preguntar por su rol no tiene sentido y
        // abriría la puerta si algún día existiese un usuario con ese nombre.
        var consulted = false
        adminAuthorizationFailure(
            principalName = STATIC_TOKEN_PRINCIPAL,
            path = "/api/v1/admin/usuarios",
            remoteHost = "127.0.0.1",
            appEnv = "dev",
            hasAdminRole = { consulted = true; true },
        )

        assertEquals(false, consulted)
    }
}
