package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.result.AppError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El texto del error de la pantalla de acceso. Un rechazo del inicio de sesion ya trae su mensaje redactado (contrasena
 * incorrecta, cuenta sin activar porque la solicitud de acceso sigue pendiente, demasiados intentos): se ensena ese y no
 * «Tu sesión ha caducado», que aqui seria falso.
 */
class LoginErrorMessageTest {
    private val pendingApproval =
        "Esta cuenta todavía no está activada. Si has solicitado acceso, un superadministrador tiene que aprobarlo antes de que puedas entrar."

    @Test
    fun anAuthenticationErrorShowsItsOwnMessage() {
        assertEquals(pendingApproval, loginErrorMessage(AppError.Authentication(pendingApproval)))
        assertEquals("Usuario o contraseña incorrectos.", loginErrorMessage(AppError.Authentication("Usuario o contraseña incorrectos.")))
    }

    @Test
    fun aBlankAuthenticationMessageFallsBackToTheGenericOne() {
        assertEquals("Tu sesión ha caducado. Vuelve a iniciar sesión.", loginErrorMessage(AppError.Authentication(" ".trim())))
    }

    @Test
    fun everyOtherErrorKeepsItsHumanTranslation() {
        // Los mensajes tecnicos del transporte siguen sin llegar a la pantalla.
        assertEquals("No hemos podido conectar con el servidor. Comprueba tu conexión.", loginErrorMessage(AppError.Network("Unable to reach the server")))
        assertEquals("El servidor no está respondiendo bien ahora mismo.", loginErrorMessage(AppError.Network("Server response failed", 503)))
        assertEquals("El email es obligatorio.", loginErrorMessage(AppError.Validation("El email es obligatorio.")))
    }
}
