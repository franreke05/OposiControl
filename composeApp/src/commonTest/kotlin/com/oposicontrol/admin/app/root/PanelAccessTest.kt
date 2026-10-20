package com.oposicontrol.admin.app.root

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** Decisión del propietario 2026-10-20: el moderador entra solo a moderar el foro; lo decide `GET /sesion` del servidor. */
class PanelAccessTest {
    private fun ok(vararg roles: String, admin: Boolean = false, superAdmin: Boolean = false) =
        AppResult.Success(AdminSessionInfo(userId = "u", roles = roles.toList(), isAdmin = admin, isSuperAdmin = superAdmin))

    @Test
    fun adminsAndSuperadminsGetTheWholePanel() {
        assertEquals(PanelAccess.Full, panelAccessFor(ok("admin", admin = true)))
        assertEquals(PanelAccess.Full, panelAccessFor(ok("superadmin", admin = true, superAdmin = true)))
        assertEquals(PanelAccess.Full, panelAccessFor(ok("admin", "moderador", admin = true)), "admin y moderador: manda admin")
    }

    @Test
    fun aModeratorWithoutAdminGetsOnlyForumModeration() {
        assertEquals(PanelAccess.ForumModerator, panelAccessFor(ok("moderador")))
        assertEquals(PanelAccess.ForumModerator, panelAccessFor(ok(" Moderador ")))
    }

    @Test
    fun aForbiddenSessionIsDeniedWithTheServerText() {
        val text = "No tienes permisos para acceder al panel de administración."
        assertEquals(PanelAccess.Denied(text), panelAccessFor(AppResult.Failure(AppError.Authorization(text))))
        assertEquals(PanelAccess.Denied(PANEL_ACCESS_DENIED_DEFAULT), panelAccessFor(AppResult.Failure(AppError.Authorization(""))))
        assertEquals(PanelAccess.Denied(PANEL_ACCESS_DENIED_DEFAULT), panelAccessFor(ok("usuario")))
    }

    /** Un fallo transitorio no deja fuera a un admin: se abre el panel y el servidor sigue rechazando lo que no toca. */
    @Test
    fun aTransientFailureKeepsTheFullPanel() {
        assertEquals(PanelAccess.Full, panelAccessFor(AppResult.Failure(AppError.Network("Unable to reach the server"))))
    }
}
