package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.GlassBackground
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import kotlinx.datetime.Instant
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Banco visual del escaparate de exámenes del Market.
 *
 * Renderiza la sección REAL (`PublishedExamsSection`) a PNG, en claro y oscuro,
 * con densidad 1 para que 1 dp sea 1 px. Salida en `build/visual-harness` o en
 * la ruta de `OPOSICONTROL_HARNESS_OUT`. No compara contra ninguna imagen de
 * referencia: sirve para MIRAR, no para fallar por un píxel.
 */
@OptIn(ExperimentalTestApi::class)
class PublishedExamsVisualHarness {

    private data class Capture(
        val fileName: String,
        val state: MarketState,
        val dark: Boolean = false,
        val width: Int = 1440,
        val height: Int = 900,
    )

    @Test
    fun capturaElEscaparate() {
        val published = publishedExams(CATALOGO)
        val loaded = MarketState(exams = UiAsync.Success(published), examCommunities = COMUNIDADES)
        listOf(
            Capture("market-examenes-claro.png", loaded),
            Capture("market-examenes-oscuro.png", loaded, dark = true),
            Capture("market-examenes-estrecho.png", loaded, width = 900, height = 800),
            Capture("market-examenes-filtrado.png", loaded.copy(examType = ExamTypeFilter.RAZONADO)),
            Capture("market-examenes-sin-coincidencias.png", loaded.copy(examQuery = "zzz")),
            Capture("market-examenes-vacio.png", MarketState(exams = UiAsync.Empty)),
            Capture("market-examenes-error.png", MarketState(exams = UiAsync.Error(AppError.Network("sin red")))),
        ).forEach(::render)
    }

    /**
     * Galería: una portada por cada ilustración del diseño de Figma, en la
     * misma cuadrícula 4 columnas del lienzo «icono/card». Sirve para comparar a
     * simple vista cada vector contra el diseño.
     */
    @Test
    fun capturaLaGaleriaDePortadas() {
        runDesktopComposeUiTest(width = 1200, height = 900) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) {
                        GlassBackground(modifier = Modifier.fillMaxSize())
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ExamCoverArtwork.entries.forEach { artwork ->
                                val type = when (artwork) {
                                    ExamCoverArtwork.RAFAGAS -> "rafaga"
                                    ExamCoverArtwork.RAZONADOS -> "razonado"
                                    else -> "inmersivo"
                                }
                                ExamCoverArt(
                                    exam = ex(artwork.name, artwork.name.replace('_', ' '), "Examen de ejemplo", type, year = 2026),
                                    artwork = artwork,
                                )
                            }
                            // Un inmersivo sin ilustracion: el icono generico.
                            ExamCoverArt(ex("sin", "Sin comunidad", "Nacional", "inmersivo"), artwork = null)
                        }
                    }
                }
            }
            waitForIdle()
            Thread.sleep(SETTLE_MILLIS)
            waitForIdle()
            val file = File(outputDir, "market-portadas-galeria.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 0)
        }
    }

    private fun render(capture: Capture) {
        runDesktopComposeUiTest(width = capture.width, height = capture.height) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = capture.dark) {
                        GlassBackground(modifier = Modifier.fillMaxSize())
                        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                            PublishedExamsSection(
                                state = capture.state,
                                onIntent = {},
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            // Las fuentes de compose-resources cargan en corrutinas que el reloj de
            // test no rastrea: sin esta espera la primera captura sale con la
            // tipografía del sistema.
            Thread.sleep(SETTLE_MILLIS)
            waitForIdle()
            val file = File(outputDir, capture.fileName)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 0, "La captura ${capture.fileName} salió vacía")
        }
    }

    private val outputDir: File by lazy {
        val override = System.getenv("OPOSICONTROL_HARNESS_OUT")?.takeIf { it.isNotBlank() }
        File(override ?: "build/visual-harness").also { it.mkdirs() }
    }
}

private const val SETTLE_MILLIS = 2_000L
private val AHORA = Instant.parse("2026-04-15T09:00:00Z")

private fun ex(
    id: String,
    specialty: String,
    title: String,
    type: String,
    access: String = "premium",
    tickets: Int = 0,
    questions: Int = 100,
    duration: Int? = null,
    year: Int? = null,
    official: Boolean = false,
    status: ExamStatus = ExamStatus.PUBLISHED,
    community: String? = null,
) = Exam(
    id = id,
    code = "EX-$id",
    specialtyId = "esp-$specialty",
    specialtyName = specialty,
    communityId = community,
    title = title,
    description = "",
    typeExam = type,
    typeAccess = access,
    priceTickets = tickets,
    status = status,
    publishFrom = null,
    publishedAt = AHORA,
    sortOrder = id.hashCode() and 0xFF,
    questionCount = questions,
    durationMinutes = duration,
    year = year,
    isOfficial = official,
    createdAt = AHORA,
    updatedAt = AHORA,
)

/** Catálogo de demostración: los tres tipos, títulos cortos y muy largos, gratis y de pago. */
private val CATALOGO = listOf(
    ex("1", "TCAE", "Simulacro oficial Mayo 2026", "inmersivo", community = "madrid", tickets = 12, questions = 120, duration = 90, year = 2026, official = true),
    ex("2", "TCAE", "Bloque 1 · Constitución y organización del Estado", "razonado", access = "gratuito", questions = 40, duration = 45),
    ex("3", "TCAE", "Ráfaga diaria", "rafaga", access = "gratuito", questions = 10),
    ex("4", "Auxiliar Administrativo", "Simulacro completo con tribunal, supuesto práctico y temario específico de la convocatoria", "inmersivo", community = "andalucia", tickets = 20, questions = 150, duration = 120, year = 2025),
    ex("5", "Auxiliar Administrativo", "Ofimática", "razonado", tickets = 6, questions = 60, duration = 60),
    ex("6", "Auxiliar Administrativo", "Ráfaga Procedimiento", "rafaga", tickets = 2, questions = 15),
    ex("7", "Celador", "Simulacro Servicio de Salud", "inmersivo", community = "galicia", tickets = 15, questions = 100, duration = 100, year = 2026),
    ex("8", "Celador", "Test guiado de funciones", "guiado", access = "gratuito", questions = 30),
    ex("9", "Celador", "Ráfaga Movilización", "rafaga", access = "gratuito", questions = 12),
    ex("10", "Enfermería", "Simulacro OPE 2026", "inmersivo", community = "valencia", tickets = 25, questions = 200, duration = 150, year = 2026, official = true),
    ex("11", "Enfermería", "Farmacología", "razonado", tickets = 8, questions = 80, duration = 70),
    ex("12", "Enfermería", "Borrador sin publicar", "inmersivo", status = ExamStatus.DRAFT),
)

private fun comunidad(slug: String, nombre: String) = Community(
    id = slug, name = nombre, code = "", slug = slug, isActive = true, sortOrder = 0,
    createdAt = AHORA, updatedAt = AHORA,
)

private val COMUNIDADES = listOf(
    comunidad("madrid", "Comunidad de Madrid"),
    comunidad("andalucia", "Andalucía"),
    comunidad("galicia", "Galicia"),
    comunidad("valencia", "Comunitat Valenciana"),
)
