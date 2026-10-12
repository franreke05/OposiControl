package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.theme.Spacing
import kotlin.test.Test

/**
 * Evidencia del movimiento que las pantallas de Contenido reciben SIN tocarse: entra por el shell y por los componentes
 * compartidos (entrada de pagina, marcador del menu, paso de cargando a datos). El cuerpo es de ejemplo: los modulos de
 * examenes, preguntas, especialidades e importacion no se editan. Escribe en `core/ui/build/motion-evidence/`.
 */
class ShellMotionEvidence {
    private val routes = listOf("content.exams" to "Exámenes", "content.questions" to "Preguntas")

    @Test
    fun cambioDeSeccion() {
        motionFilmstrip("contenido-cambio-de-seccion", offsets = listOf(16, 48, 96, 160, 272)) { step ->
            val (route, title) = routes[step]
            DesktopShell(
                currentRouteId = route,
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = title,
                description = "Catálogo de $title.",
                breadcrumbs = listOf("Contenido", title),
                globalSearch = {},
            ) {
                AsyncStatePane(state = UiAsync.Success(title)) { name -> ExampleRows(name) }
            }
        }
    }

    @Test
    fun cargandoADatos() {
        val states = listOf<UiAsync<String>>(UiAsync.Loading, UiAsync.Success("Exámenes"))
        motionFilmstrip("contenido-cargando-a-datos") { step ->
            DesktopShell(
                currentRouteId = "content.exams",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Exámenes",
                description = "Catálogo de Exámenes.",
                breadcrumbs = listOf("Contenido", "Exámenes"),
                globalSearch = {},
            ) {
                AsyncStatePane(state = states[step], loadingRows = 6) { name -> ExampleRows(name) }
            }
        }
    }

    @Test
    fun rendimientoCambioRapidoDeSecciones() {
        // Cambio rapido de secciones con una lista poblada (60 filas) que pasa por cargando.
        val pages = listOf("content.exams" to "Exámenes", "content.questions" to "Preguntas", "users" to "Usuarios")
        motionPerformance("cambio-de-secciones", states = pages.size * 2) { step ->
            val (route, title) = pages[(step / 2) % pages.size]
            val state: UiAsync<String> = if (step % 2 == 0) UiAsync.Loading else UiAsync.Success(title)
            DesktopShell(currentRouteId = route, groups = EVIDENCE_SIDEBAR, onRouteSelected = {}, environmentLabel = "Entorno production", title = title, description = "Rendimiento.", globalSearch = {}) {
                AsyncStatePane(state = state, loadingRows = 6) { name ->
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        items(60) { index -> WorkspaceCatalogRow(title = "$name · registro ${index + 1}", selected = index == 0, onClick = {}, icon = AppIcon.EXAMS, description = "Fila de ejemplo") }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ExampleRows(name: String) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        repeat(6) { index ->
            WorkspaceCatalogRow(title = "$name · registro ${index + 1}", selected = index == 0, onClick = {}, icon = AppIcon.EXAMS, description = "Fila de ejemplo")
        }
        Text("Cuerpo de ejemplo: el movimiento es el del shell y los componentes compartidos.")
    }
}

private val EVIDENCE_SIDEBAR = listOf(
    ShellNavigationGroup("content", "Contenido", listOf(ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key), ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key))),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
)
