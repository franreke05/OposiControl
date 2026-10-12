package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import kotlin.test.Test

/** Evidencia del movimiento de Usuarios. Se ejecuta con `--rerun`; escribe en `features/users/build/motion-evidence/`. */
class UsersMotionEvidence {
    private val fx = UsersFixtures

    @Test
    fun abrirOtraPersona() {
        // La ficha entra al abrir a otra persona; la lista no se mueve y su seleccion cambia con un fundido.
        val states = listOf(fx.opositoraSeleccionada(), fx.administradoraSeleccionada())
        motionFilmstrip("usuarios-otra-persona") { step ->
            DesktopShell(
                currentRouteId = "users",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Usuarios",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                UsersContent(state = states[step], onIntent = {}, onOpenTickets = {})
            }
        }
    }

    @Test
    fun deListaAFicha() {
        val states = listOf(fx.listado(), fx.opositoraSeleccionada())
        motionFilmstrip("usuarios-lista-a-ficha") { step ->
            DesktopShell(
                currentRouteId = "users",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Usuarios",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                UsersContent(state = states[step], onIntent = {}, onOpenTickets = {})
            }
        }
    }

    @Test
    fun dialogo() {
        // El dialogo entra con escala y fundido, y el velo de fondo aparece con el.
        val states = listOf(fx.conCambiosSinGuardar(), fx.confirmandoDescarte())
        motionFilmstrip("usuarios-dialogo", offsets = listOf(16, 64, 128, 208, 288)) { step ->
            DesktopShell(
                currentRouteId = "users",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Usuarios",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                UsersContent(state = states[step], onIntent = {}, onOpenTickets = {})
            }
        }
    }
}

private val EVIDENCE_SIDEBAR = listOf(
    ShellNavigationGroup("content", "Contenido", listOf(ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key), ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key))),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
    ShellNavigationGroup("economy", "Monetización", listOf(ShellNavigationItem("commercial.catalog", "Catálogo", AppIcon.CATALOG.key), ShellNavigationItem("commercial.market", "OposiMarket", AppIcon.MARKET.key))),
)
