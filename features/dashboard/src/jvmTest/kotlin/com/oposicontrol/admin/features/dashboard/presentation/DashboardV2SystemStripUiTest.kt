package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardHealth
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * El único test de esta entrega que llega al píxel.
 *
 * Todo lo demás está probado una capa por debajo: que `renderable` filtra, no que
 * la tarjeta no se dibuja; que la celda es `NoData`, no que se lee «Sin dato». El
 * defecto que motivó este fichero —la franja de sistema expulsando su propio
 * enlace en ventana estrecha— vivía exactamente en ese hueco: ningún test podía
 * verlo porque ninguno medía nada.
 *
 * Va en `jvmTest` y no en `commonTest` a propósito: sólo el runtime de escritorio
 * necesita las dependencias de UI test, y en común arrastrarían `ui-test-junit4`
 * a los tests unitarios de Android.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardV2SystemStripUiTest {

    private val now = Instant.fromEpochSeconds(1_756_000_000L)

    /** Peor caso realista de la franja: los dos servicios en pie, con latencia y marca de tiempo. */
    private fun systemState(): DashboardV2State = DashboardV2State(
        blocks = mapOf(
            DashboardBlock.SYSTEM to UiAsync.Success(
                DashboardBlockReport(
                    block = DashboardBlock.SYSTEM,
                    health = DashboardHealth(
                        overall = SystemStatusLevel.OPERATIVE,
                        ktor = SystemStatusLevel.OPERATIVE,
                        supabase = SystemStatusLevel.OPERATIVE,
                        updatedAt = Instant.fromEpochSeconds(now.epochSeconds - 720),
                        supabaseLatencyMs = 128,
                    ),
                    generatedAt = now,
                ),
            ),
        ),
    )

    private fun assertVerEstadoIsDrawn(contentWidth: Int) = runComposeUiTest {
        setContent {
            Box(modifier = Modifier.size(width = contentWidth.dp, height = 1000.dp)) {
                AdminTheme {
                    DashboardV2Content(
                        state = systemState(),
                        now = now,
                        onRefreshBlock = {},
                        onOpen = {},
                    )
                }
            }
        }

        // `DashboardLink` publica su texto como contentDescription, así que este
        // nodo existe sólo si el enlace llegó a componerse Y a medirse.
        val bounds = onNodeWithContentDescription("Ver estado").getUnclippedBoundsInRoot()

        // Dos cosas distintas, y las dos hacen falta. Un `Row` desbordado NO deja
        // el hijo a cero: lo mide a su tamaño natural y lo dibuja FUERA. Medir sólo
        // el ancho da 87dp y parece correcto mientras el enlace está fuera de la
        // pantalla.
        assertTrue(
            bounds.width >= 60.dp,
            "«Ver estado» se ha quedado en ${bounds.width}: el grupo izquierdo lo está aplastando.",
        )
        assertTrue(
            bounds.right <= contentWidth.dp,
            "«Ver estado» se dibuja fuera de la franja (borde derecho ${bounds.right} en una " +
                "ventana de ${contentWidth}dp): el grupo izquierdo ha desbordado la fila.",
        )
    }

    /** Estado de error: además del enlace tiene que sobrevivir «Reintentar». */
    private fun assertBothActionsAreDrawn(contentWidth: Int) = runComposeUiTest {
        setContent {
            Box(modifier = Modifier.size(width = contentWidth.dp, height = 1000.dp)) {
                AdminTheme {
                    DashboardV2Content(
                        state = DashboardV2State(
                            blocks = mapOf(
                                DashboardBlock.SYSTEM to UiAsync.Error(AppError.Network("upstream caido", 503)),
                            ),
                        ),
                        now = now,
                        onRefreshBlock = {},
                        onOpen = {},
                    )
                }
            }
        }

        listOf("Ver estado", "Reintentar").forEach { etiqueta ->
            // «Ver estado» publica contentDescription (DashboardLink); el botón
            // sólo expone su texto, así que se busca por texto.
            val node = if (etiqueta == "Ver estado") onNodeWithContentDescription(etiqueta) else onNodeWithText(etiqueta)
            val b = node.getUnclippedBoundsInRoot()
            assertTrue(
                b.width >= 40.dp && b.right <= contentWidth.dp,
                "«$etiqueta» no sobrevive a ${contentWidth}dp: ancho ${b.width}, borde ${b.right}.",
            )
        }
    }

    @Test
    fun verEstadoSeDibujaEnLaVentanaMinimaDeEscritorioConSidebarDesplegada() {
        // 1040dp (donde arranca AppBreakpoint.EXPANDED, `AppRoot.kt:340`) menos
        // sidebar desplegada 244dp menos 2×20dp de screenPadding. Es el contenido
        // más estrecho que el panel llega a ver hoy.
        assertVerEstadoIsDrawn(756)
    }

    @Test
    fun verEstadoSeDibujaTambienEnVentanaHolgada() {
        // 1440dp con sidebar desplegada. Ya funcionaba; está para que el test
        // estrecho no pueda pasar por una razón equivocada.
        assertVerEstadoIsDrawn(1148)
    }

    @Test
    fun verEstadoSobreviveAlAnchoEnQueAntesDesaparecia() {
        // ESTOS son los que fallan sin la cota, medido: sin `widthIn` el enlace
        // baja a 9dp con 640 y a 0dp con 600 y 520. Hoy ese ancho no se alcanza
        // —el suelo son 756dp— pero la holgura es de sólo ~116dp y se la come
        // cualquier cadena más larga. El test fija la garantía, no la suerte.
        assertVerEstadoIsDrawn(640)
        assertVerEstadoIsDrawn(600)
        assertVerEstadoIsDrawn(520)
    }

    @Test
    fun enEstadoDeErrorSobrevivenLosDosBotones() {
        // «Reintentar» es el único reintento manual de esta franja; si lo expulsa
        // el mensaje de error, sólo queda esperar al refresco de 60s.
        assertBothActionsAreDrawn(756)
        assertBothActionsAreDrawn(600)
    }
}
