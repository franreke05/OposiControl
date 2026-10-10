package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.NewsValidationIssue
import com.oposicontrol.admin.domain.model.NewsValidationReport
import com.oposicontrol.admin.domain.model.NewsValidationSeverity
import kotlin.test.Test

/**
 * Banco de pruebas VISUAL de Noticias.
 *
 * Renderiza la pantalla real —el mismo [NewsContent] de produccion— contra estados fabricados y escribe PNG en
 * `features/news/build/visual-harness/`. Se ejecuta con `--rerun`; sin el Gradle da la tarea por hecha.
 */
class NewsVisualHarness {
    private val fx = NewsFixtures

    @Test
    fun capturaLaFicha() {
        news("news-1920-light-contenido.png", fx.contenido())
        news("news-1920-dark-contenido.png", fx.contenido(), dark = true)
        news("news-1920-light-diseno.png", fx.diseno())
        news("news-1920-dark-diseno.png", fx.diseno(), dark = true)
        news("news-1920-light-diseno-sin-seleccion.png", fx.disenoSinSeleccion())
        news("news-1920-light-publicacion.png", fx.publicacion(), height = 1300)
        news("news-1920-light-nueva.png", fx.nueva())
        news("news-1920-light-nueva-lista.png", fx.nuevaLista())
    }

    @Test
    fun capturaLosEstadosEditoriales() {
        news("news-1920-light-programada.png", fx.programada(), height = 1300)
        news("news-1920-light-publicada.png", fx.publicada())
        news("news-1920-light-publicada-publicacion.png", fx.publicada().copy(editorTab = NewsEditorTab.PUBLICATION), height = 1300)
        news("news-1920-light-archivada.png", fx.archivada())
    }

    @Test
    fun capturaLosEstadosDeRed() {
        news("news-1920-light-cargando.png", fx.cargando())
        news("news-1920-light-vacia.png", fx.vacia())
        news("news-1920-light-error-biblioteca.png", fx.errorBiblioteca())
        news("news-1920-light-error-ficha.png", fx.errorFicha())
        news("news-1920-light-refresco-fallido.png", fx.refrescoFallido())
        news("news-1920-light-sin-resultados.png", fx.sinResultados())
        news("news-1920-light-leyendo-ficha.png", fx.leyendoFicha())
    }

    @Test
    fun capturaElGuardado() {
        news("news-1920-light-guardando.png", fx.guardando())
        news("news-1920-light-guardada.png", fx.guardada())
        news("news-1920-light-fallo-guardar.png", fx.falloGuardar())
        news("news-1920-light-conflicto.png", fx.conflicto())
        news("news-1920-light-prohibido.png", fx.prohibido())
        news("news-1920-light-validacion.png", fx.validacion(), height = 1300)
        news("news-1920-light-recuperada.png", fx.recuperada())
    }

    @Test
    fun capturaLasSubidasYLosDialogos() {
        news("news-1920-light-subiendo.png", fx.subiendo(), height = 1500)
        news("news-1920-light-subida-fallida.png", fx.subidaFallida(), height = 1500)
        news("news-1920-light-diseno-capa-imagen.png", fx.capaImagenSubiendo(), height = 1500)
        news("news-1920-light-salir-con-cambios.png", fx.salirConCambios())
        news("news-1920-light-confirmar-publicar.png", fx.confirmarPublicar())
        news("news-1920-light-confirmar-eliminar.png", fx.confirmarEliminar())
        news("news-1920-light-ampliada.png", fx.ampliada())
    }

    @Test
    fun capturaLosAnchos() {
        news("news-1600-light-diseno.png", fx.diseno(), width = 1600, height = 900)
        news("news-1366-light-diseno.png", fx.diseno(), width = 1366, height = 768)
        news("news-1366-light-contenido.png", fx.contenido(), width = 1366, height = 768)
        news("news-1080-light-diseno.png", fx.diseno(), width = 1080, height = 2400)
    }

    private fun news(file: String, state: NewsState, width: Int = 1920, height: Int = 1080, dark: Boolean = false) {
        renderNewsCapture(NewsCapture(file, width, height, dark)) {
            NewsContent(state = state, onIntent = {})
        }
    }
}

/** Estados fabricados. Nombres, textos y fechas son de ejemplo: nada de esto sale de un backend. */
internal object NewsFixtures {
    private const val ESP = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    private const val ESP2 = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5e"
    private const val TIPO = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
    private const val TIPO2 = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6e"
    private const val ID1 = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"

    private val cover: String get() = newsFixtureCover

    private val rows = listOf(
        NewsItem(ID1, "Nueva convocatoria TCAE 2026: plazas y plazos", ESP, TIPO, "", status = "draft", publishFrom = "", text = "", updatedAt = "2026-10-10T09:12:00Z"),
        NewsItem("n2", "Consejos para el último mes de preparación", ESP, TIPO2, "", status = "scheduled", publishFrom = "2026-10-20T09:00:00Z", text = "", updatedAt = "2026-10-09T18:40:00Z"),
        NewsItem("n3", "Resultados provisionales de Enfermería", ESP2, TIPO, "", status = "published", publishFrom = "", text = "", publishedAt = "2026-10-05T10:00:00Z", updatedAt = "2026-10-05T10:00:00Z", imageUrl = cover),
        NewsItem("n4", "Calendario del proceso anterior", ESP2, TIPO2, "", status = "archived", publishFrom = "", text = "", publishedAt = "2026-06-01T08:00:00Z", updatedAt = "2026-09-01T08:00:00Z"),
        NewsItem("n5", "Novedades de septiembre en OposiBot", ESP, TIPO2, "", status = "draft", publishFrom = "", text = "", updatedAt = "2026-09-28T12:00:00Z"),
    )

    private val layers = listOf(
        NewsLayerDraft("l-bg", "Fondo", "imagen", cover, 0, 0, 800, 500, 86, visible = true, sortOrder = 0),
        NewsLayerDraft("l-title", "Título", "titulo", "Nueva convocatoria TCAE 2026", 48, 42, 520, 92, 100, textColor = "#FFFFFF", visible = true, sortOrder = 1, fontSizeSp = 34, fontWeight = "bold"),
        NewsLayerDraft("l-sub", "Subtítulo", "texto", "Plazas, plazos y requisitos", 48, 150, 420, 44, 92, textColor = "#E6EEF2", visible = true, sortOrder = 2),
        NewsLayerDraft("l-body", "Texto", "texto", "Consulta la convocatoria y prepara la documentación.", 48, 220, 430, 112, 88, textColor = "#FFFFFF", visible = true, sortOrder = 3, fontFamily = "serif"),
        NewsLayerDraft("l-badge", "Etiqueta", "texto", "NUEVO", 640, 36, 112, 40, 100, textColor = "#FFFFFF", visible = true, locked = true, sortOrder = 4, fontWeight = "semibold"),
        NewsLayerDraft("l-hidden", "Nota interna", "texto", "Revisar fechas", 48, 400, 300, 40, 100, visible = false, sortOrder = 5),
    )

    private fun base() = NewsState(
        specialties = UiAsync.Success(listOf(NewsCatalogOption(ESP, "TCAE"), NewsCatalogOption(ESP2, "Enfermería"), NewsCatalogOption("old", "Celador (antigua)", active = false))),
        newsTypes = UiAsync.Success(listOf(NewsCatalogOption(TIPO, "Convocatoria"), NewsCatalogOption(TIPO2, "Consejos"))),
        libraryState = UiAsync.Success(Unit),
        news = UiAsync.Success(rows),
        selectedId = ID1,
        recordState = UiAsync.Success(Unit),
        editorTitle = "Nueva convocatoria TCAE 2026: plazas y plazos",
        editorText = "Consulta la convocatoria y prepara la documentación para el próximo proceso selectivo.\n\nRevisa los requisitos y las fechas antes de presentar tu solicitud. Conserva una copia de la documentación enviada.",
        editorSpecialty = ESP,
        editorType = TIPO,
        editorStatus = "draft",
        editorVersion = "2026-10-10T09:12:00Z",
        editorBackgroundPath = "news/$ID1/portada.png",
        editorBackgroundUrl = cover,
        editorBackgroundAssetStatus = "uploaded",
        editorLayers = layers,
        selectedLayerId = "l-title",
        selectedTarget = NewsEditorSelection.LAYER,
        validationReport = NewsValidationReport(canSave = true, canSchedule = false, canPublish = true, errors = emptyList(), warnings = emptyList()),
    )

    fun contenido() = base().copy(editorTab = NewsEditorTab.CONTENT, selectedLayerId = null, selectedTarget = NewsEditorSelection.ARTICLE)

    fun diseno() = base().copy(editorTab = NewsEditorTab.DESIGN, isDirty = true, canUndo = true)

    fun disenoSinSeleccion() = diseno().copy(selectedLayerId = null, selectedTarget = NewsEditorSelection.ARTICLE, isDirty = false, canUndo = false)

    fun publicacion() = base().copy(
        editorTab = NewsEditorTab.PUBLICATION,
        validationReport = NewsValidationReport(
            canSave = true, canSchedule = false, canPublish = true, errors = emptyList(),
            warnings = listOf(NewsValidationIssue("text_empty", "El cuerpo esta vacio; revisa si la noticia depende solo del canvas.", NewsValidationSeverity.WARNING)),
        ),
    )

    fun nueva() = base().copy(
        selectedId = "local-1",
        editorTitle = "",
        editorText = "",
        editorSpecialty = "",
        editorType = "",
        editorVersion = "",
        editorBackgroundPath = "",
        editorBackgroundUrl = "",
        editorLayers = listOf(
            NewsLayerDraft("t", "Título", "titulo", "Título de noticia", 48, 42, 430, 82, 100, visible = true, sortOrder = 0),
            NewsLayerDraft("s", "Subtítulo", "texto", "Especialidad - Tipo", 48, 142, 360, 44, 92, visible = true, sortOrder = 1),
        ),
        selectedLayerId = null,
        selectedTarget = NewsEditorSelection.ARTICLE,
        news = UiAsync.Success(listOf(NewsItem("local-1", "", "", "", "SKY", status = "draft", publishFrom = "", text = "", isLocalDraft = true)) + rows),
        validationReport = NewsValidationReport(
            canSave = false, canSchedule = false, canPublish = false,
            errors = listOf(NewsValidationIssue("title_required", "La noticia necesita titulo.", NewsValidationSeverity.ERROR), NewsValidationIssue("specialty_required", "La noticia necesita especialidad.", NewsValidationSeverity.ERROR)),
            warnings = emptyList(),
        ),
    )

    fun nuevaLista() = nueva().copy(
        editorTitle = "Aviso: cambio de sede del examen",
        editorSpecialty = ESP,
        editorType = TIPO,
        editorText = "El examen del 14 de noviembre se traslada al pabellón norte.",
        isDirty = true,
        canUndo = true,
        news = UiAsync.Success(listOf(NewsItem("local-1", "Aviso: cambio de sede del examen", ESP, TIPO, "SKY", status = "draft", publishFrom = "", text = "", isLocalDraft = true)) + rows),
        validationReport = NewsValidationReport(canSave = true, canSchedule = false, canPublish = true, errors = emptyList(), warnings = emptyList()),
    )

    fun programada() = base().copy(selectedId = "n2", editorTitle = "Consejos para el último mes de preparación", editorType = TIPO2, editorStatus = "scheduled", editorPublishFrom = "2026-10-20T09:00:00Z", editorTab = NewsEditorTab.PUBLICATION)

    fun publicada() = base().copy(
        selectedId = "n3", editorTitle = "Resultados provisionales de Enfermería", editorSpecialty = ESP2, editorStatus = "published",
        editorPublishedAt = "2026-10-05T10:00:00Z", editorVersion = "2026-10-05T10:00:00Z", editorTab = NewsEditorTab.CONTENT, selectedLayerId = null,
    )

    fun archivada() = publicada().copy(selectedId = "n4", editorTitle = "Calendario del proceso anterior", editorStatus = "archived", editorTab = NewsEditorTab.DESIGN)

    fun cargando() = NewsState()

    fun vacia() = NewsState(
        specialties = UiAsync.Success(emptyList()),
        newsTypes = UiAsync.Success(emptyList()),
        libraryState = UiAsync.Success(Unit),
        news = UiAsync.Empty,
    )

    fun errorBiblioteca() = NewsState(
        specialties = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")),
        newsTypes = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")),
        libraryState = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.", 503)),
        news = UiAsync.Empty,
    )

    fun errorFicha() = base().copy(recordState = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")), editorTitle = "")

    fun leyendoFicha() = base().copy(selectedId = "n2", recordState = UiAsync.Loading)

    fun refrescoFallido() = contenido().copy(libraryRefreshFailed = true)

    fun sinResultados() = contenido().copy(news = UiAsync.Empty, searchQuery = "zaragoza", statusFilter = "published")

    fun guardando() = diseno().copy(editorTab = NewsEditorTab.CONTENT, actionState = UiAsync.Loading, actionKind = NewsAction.SAVE)

    fun guardada() = contenido().copy(actionState = UiAsync.Success(Unit), actionKind = NewsAction.SAVE, actionMessage = "Guardado en el servidor.")

    fun falloGuardar() = contenido().copy(isDirty = true, canUndo = true, actionState = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.", 503)), actionKind = NewsAction.SAVE)

    fun conflicto() = contenido().copy(
        isDirty = true, canUndo = true, actionConflict = true, actionKind = NewsAction.SAVE,
        actionState = UiAsync.Error(AppError.Validation("El recurso ha cambiado desde la última lectura.", "precondition_failed")),
    )

    fun prohibido() = contenido().copy(isDirty = true, canUndo = true, actionState = UiAsync.Error(AppError.Authorization("No tienes permisos para realizar esta acción.")), actionKind = NewsAction.PUBLISH)

    fun validacion() = publicacion().copy(
        editorSpecialty = "old",
        validationReport = NewsValidationReport(
            canSave = true, canSchedule = false, canPublish = false,
            errors = listOf(
                NewsValidationIssue("title_layer_required", "Debe existir una capa de titulo visible.", NewsValidationSeverity.ERROR),
                NewsValidationIssue("layer_outside_canvas", "La capa Etiqueta esta fuera del lienzo.", NewsValidationSeverity.ERROR),
            ),
            warnings = listOf(NewsValidationIssue("text_empty", "El cuerpo esta vacio.", NewsValidationSeverity.WARNING)),
        ),
    )

    fun recuperada() = contenido().copy(isDirty = true, canUndo = true, recoveredDraft = true, editorText = "Texto recuperado tras cerrar OposiControl a medias.")

    fun subiendo() = contenido().copy(uploads = mapOf(NEWS_UPLOAD_BACKGROUND to NewsUploadState(NEWS_UPLOAD_BACKGROUND, "file:///tmp/portada-nueva.png", "portada-nueva.png", progress = 0.62f)))

    fun subidaFallida() = contenido().copy(
        uploads = mapOf(NEWS_UPLOAD_IMAGE to NewsUploadState(NEWS_UPLOAD_IMAGE, "file:///tmp/tarjeta.png", "tarjeta.png", progress = 0.3f, error = "La imagen supera el límite de 10 MB.")),
    )

    fun capaImagenSubiendo() = diseno().copy(
        selectedLayerId = "l-bg",
        uploads = mapOf(newsLayerUploadTarget("l-bg") to NewsUploadState(newsLayerUploadTarget("l-bg"), "file:///tmp/fondo-v2.png", "fondo-v2.png", progress = 0.45f)),
    )

    fun salirConCambios() = diseno().copy(pendingIntent = NewsIntent.Select("n2"))

    fun confirmarPublicar() = publicacion().copy(confirmIntent = NewsIntent.Publish)

    fun confirmarEliminar() = publicacion().copy(confirmIntent = NewsIntent.DeleteSelected)

    fun ampliada() = diseno().copy(canvasFullscreen = true)
}
