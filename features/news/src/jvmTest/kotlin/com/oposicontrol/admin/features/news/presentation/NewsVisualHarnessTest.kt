package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.GlassBackground
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Color
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class NewsVisualHarnessTest {
    @Test fun newsWorkspaceEvidence() {
        val asset = File("build/reports/news-ui/fixture-cover.png")
        asset.parentFile.mkdirs()
        val bitmap = BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB)
        bitmap.createGraphics().apply {
            color = Color(26, 51, 67); fillRect(0, 0, 800, 500)
            color = Color(57, 115, 140); fillOval(430, -90, 460, 460)
            color = Color(103, 168, 183); fillRoundRect(580, 160, 130, 340, 24, 24)
            color = Color(38, 75, 94); fillRoundRect(460, 280, 100, 240, 20, 20)
            dispose()
        }
        ImageIO.write(bitmap, "png", asset)
        val cases = listOf(Triple(1920, false, "existing"), Triple(1920, true, "existing"), Triple(1920, false, "new"), Triple(1600, false, "scheduled"), Triple(1366, false, "existing"), Triple(1080, false, "error"))
        cases.forEach { (width, dark, scenario) ->
            runDesktopComposeUiTest(width = width, height = if (width == 1366) 768 else 1080) {
                setContent {
                    AdminTheme(darkTheme = dark) {
                        GlassBackground(Modifier.fillMaxSize())
                        Column(Modifier.fillMaxSize().padding(start = 244.dp, top = 28.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Contenido / Noticias", style = MaterialTheme.typography.labelMedium)
                            Text("Noticias", style = MaterialTheme.typography.headlineLarge)
                            NewsContent(fixture(scenario, "file://" + asset.absolutePath.replace('\\', '/')), {})
                        }
                    }
                }
                waitForIdle()
                if (scenario == "existing") waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Imagen de noticia").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Mesa de edición").assertExists()
                onNodeWithText("Guardar borrador local").assertIsDisplayed()
                onNodeWithText(if (scenario == "new") "Publicar ahora" else if (scenario == "scheduled") "Publicar ahora" else "Publicar cambios").assertIsDisplayed()
                val output = File("build/reports/news-ui/$width-${if (dark) "dark" else "light"}-$scenario.png")
                output.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output)
                if (scenario == "error") {
                    onNodeWithText("Abrir biblioteca").performClick()
                    waitForIdle()
                    onNodeWithText("Reintentar").assertIsDisplayed()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output.parentFile, "$width-light-library-error.png"))
                }
                if (width == 1920 && scenario == "existing") {
                    onNodeWithText("Capas visuales").performClick()
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output.parentFile, "$width-${if (dark) "dark" else "light"}-layers.png"))
                    onNodeWithText("Imagen y estilo").performClick()
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output.parentFile, "$width-${if (dark) "dark" else "light"}-image.png"))
                }
            }
        }
    }
    private fun fixture(scenario: String, image: String): NewsState {
        val items = listOf(
            NewsItem("1", "Nueva convocatoria TCAE 2026", "1", "1", "SKY", status = "published", publishFrom = "2026-09-12T10:00:00Z", text = "Consulta la convocatoria y prepara la documentación para el próximo proceso selectivo."),
            NewsItem("2", "Consejos para el último mes", "1", "2", "MINT", status = "scheduled", publishFrom = "2026-09-15T09:00:00Z", text = "Planifica tus repasos y reserva tiempo para descansar."),
            NewsItem("3", "Novedades de septiembre", "1", "2", "GOLD", status = "draft", publishFrom = "", text = "Preparamos las novedades para el nuevo curso."),
            NewsItem("4", "Calendario del proceso anterior", "1", "1", "SKY", status = "archived", publishFrom = "", text = "Información del proceso anterior."),
        )
        val item = if (scenario == "scheduled") items[1] else items[0]
        return NewsState(news = UiAsync.Success(items.mapIndexed { index, news -> if (index == 0) news.copy(backgroundPath = image) else news }), libraryState = if (scenario == "error") UiAsync.Error(AppError.Validation("Error")) else UiAsync.Success(Unit),
            specialties = UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE"))), newsTypes = UiAsync.Success(listOf(NewsCatalogOption("1", "Convocatoria"), NewsCatalogOption("2", "Consejos"))),
            selectedId = if (scenario == "new") "local-new" else item.id,
            editorTitle = if (scenario == "new") "" else item.title, editorText = if (scenario == "new") "" else item.text + "\n\nRevisa los requisitos y las fechas antes de presentar tu solicitud. Conserva una copia de la documentación enviada.",
            editorSpecialty = "1", editorType = item.type, editorStatus = if (scenario == "new") "draft" else item.status, editorPublishFrom = item.publishFrom,
            editorBackgroundPath = if (scenario == "existing") image else "", selectedLayerId = "title",
            editorLayers = if (scenario == "new") emptyList() else listOf(NewsLayerDraft("title", "Título", "titulo", item.title, 48, 80, 680, 120, 100, visible = true, sortOrder = 0), NewsLayerDraft("body", "Descripción", "texto", item.text, 48, 230, 650, 150, 100, visible = true, sortOrder = 1)),
            actionState = if (scenario == "error") UiAsync.Error(AppError.Validation("Error")) else UiAsync.Idle)
    }
}
