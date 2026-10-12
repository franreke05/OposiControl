package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveTone
import com.oposicontrol.admin.domain.model.NewsValidationIssue
import com.oposicontrol.admin.domain.model.NewsValidationReport
import com.oposicontrol.admin.domain.model.NewsValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsPresentationTest {
    private val catalogs = NewsState(
        specialties = UiAsync.Success(listOf(NewsCatalogOption("esp", "TCAE"), NewsCatalogOption("old", "Antigua", active = false))),
        newsTypes = UiAsync.Success(listOf(NewsCatalogOption("tipo", "Convocatoria"))),
        recordState = UiAsync.Success(Unit),
    )

    private fun saved() = catalogs.copy(
        selectedId = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60",
        editorTitle = "Convocatoria",
        editorSpecialty = "esp",
        editorType = "tipo",
        editorVersion = "2026-10-08T12:00:00Z",
    )

    @Test
    fun unknownStateNeverClaimsPublished() {
        assertEquals("Sin identificar", newsStatusLabel("active"))
    }

    @Test
    fun catalogFailureNeverLeaksIdOrSelectsDefault() {
        assertEquals("No disponible", catalogName("123", UiAsync.Error(AppError.Validation("failure"))))
        assertEquals("Sin identificar", catalogName("123", UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE")))))
        assertEquals("TCAE", catalogName("1", UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE")))))
    }

    @Test
    fun scheduleFieldsRoundTripThroughIso() {
        assertNull(newsScheduleIso("31/02/2026", "09:00"))
        assertNull(newsScheduleIso("12/09/2026", "25:00"))
        assertEquals("2026-09-12T09:30:00Z", newsScheduleIso("12/09/2026", "09:30"))
        assertEquals("12/09/2026" to "09:30", newsScheduleFields("2026-09-12T09:30:00Z"))
        assertEquals("12/09/2026 · 09:30 UTC", newsDateLabel("2026-09-12T09:30:00.123456Z"))
        assertEquals("Sin fecha", newsDateLabel(null))
    }

    @Test
    fun localFileNameDoesNotExposeDirectories() {
        assertEquals("portada.png", newsAssetName("file://C:/Users/private/portada.png"))
        assertEquals("Imagen seleccionada", newsAssetName("news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/8f14e45fceea467a9a3e1b2c3d4e5f60"))
    }

    @Test
    fun saveProblemMirrorsWhatTheServerRequires() {
        assertEquals("Escribe un título para poder guardar.", catalogs.saveProblem())
        assertEquals("Elige una especialidad en Contenido.", catalogs.copy(editorTitle = "t").saveProblem())
        assertEquals("Elige un tipo de noticia en Contenido.", catalogs.copy(editorTitle = "t", editorSpecialty = "esp").saveProblem())
        assertNull(saved().saveProblem())
        val uploading = saved().copy(uploads = mapOf("background" to NewsUploadState("background", "file://a.png", "a.png", 0.4f)))
        assertEquals("Espera a que terminen las subidas de imagen.", uploading.saveProblem())
        assertEquals("El cuerpo no puede pasar de 200.000 caracteres.", saved().copy(editorText = "a".repeat(NEWS_TEXT_MAX + 1)).saveProblem())
        assertNull(saved().copy(editorText = "a".repeat(NEWS_TEXT_MAX)).saveProblem())
    }

    @Test
    fun contractRejectionsAreSaidWithWhatFixesThem() {
        fun rejected(code: String) = AppError.Validation("texto del servidor sin acentos", code).newsMessage()
        assertEquals("Otra sesión guardó esta noticia después de que la abrieras.", rejected("precondition_failed"))
        assertEquals("La noticia ya está publicada o archivada y no admite cambios.", rejected("NEWS_NOT_EDITABLE"))
        assertEquals("La noticia no tiene texto ni capas con contenido: no hay nada que publicar.", rejected("news_incomplete"))
        assertEquals("La fecha de publicación tiene que ser posterior a ahora.", rejected("not_future_instant"))
        assertEquals("La especialidad o el tipo de noticia ya no están activos. Elige otros en Contenido.", rejected("inactive_tipo_or_especialidad"))
        assertEquals("Una de las imágenes no corresponde a un archivo subido. Vuelve a subirla.", rejected("asset_path_unknown"))
        assertEquals("La imagen supera el límite de 10 MB.", rejected("payload_too_large"))
        // Un rechazo que este panel no conoce conserva el texto del servidor; un fallo de red nunca ensena el suyo.
        assertEquals("texto del servidor sin acentos", rejected("otro_codigo"))
        assertEquals("El servidor ha tardado demasiado en responder.", AppError.Network("Client request failed", 504).newsMessage())
        // En una subida, un 400 de validacion es el archivo, no la ficha.
        assertEquals("El archivo no es una imagen válida del formato que indica su extensión.", AppError.Validation("Algun dato de la peticion no es valido.", "validation").newsUploadMessage())
        assertEquals("La imagen supera el límite de 10 MB.", AppError.Validation("La peticion es demasiado grande.", "payload_too_large").newsUploadMessage())
    }

    @Test
    fun publishProblemAddsActiveCatalogsAndTheEditorRules() {
        assertEquals("La especialidad elegida no está activa.", saved().copy(editorSpecialty = "old").publishProblem())
        val blocked = saved().copy(
            validationReport = NewsValidationReport(
                canSave = true, canSchedule = false, canPublish = false,
                errors = listOf(NewsValidationIssue("layers_empty", "Debe haber al menos una capa visible.", NewsValidationSeverity.ERROR)),
                warnings = emptyList(),
            ),
        )
        assertEquals("Debe haber al menos una capa visible.", blocked.publishProblem())
    }

    @Test
    fun saveStatusTellsTheTruthInEveryState() {
        val clean = saved().saveStatus()
        assertEquals("Sin cambios pendientes.", clean.message)
        assertFalse(clean.canSave)

        val dirty = saved().copy(isDirty = true).saveStatus()
        assertEquals("Cambios sin guardar.", dirty.message)
        assertTrue(dirty.canSave && dirty.canDiscard && dirty.prominent)
        assertEquals("Guardar", dirty.primaryLabel)

        val fresh = catalogs.copy(selectedId = "local-1", editorTitle = "t", editorSpecialty = "esp", editorType = "tipo", isDirty = true).saveStatus()
        assertEquals("Crear borrador", fresh.primaryLabel)
        assertEquals("Lista para crear.", fresh.message)

        val saving = saved().copy(isDirty = true, actionState = UiAsync.Loading, actionKind = NewsAction.PUBLISH).saveStatus()
        assertEquals("Publicando…", saving.message)
        assertFalse(saving.canSave || saving.canDiscard)

        val failed = saved().copy(isDirty = true, actionState = UiAsync.Error(AppError.Network("x", 503)), actionKind = NewsAction.SAVE).saveStatus()
        assertEquals("No se pudo guardar.", failed.message)
        assertEquals(WorkspaceSaveTone.DANGER, failed.tone)
        assertEquals("El servidor no está respondiendo bien ahora mismo.", failed.detail)
        assertTrue(failed.canSave, "un fallo de red se puede reintentar")

        val conflict = saved().copy(isDirty = true, actionState = UiAsync.Error(AppError.Validation("El recurso ha cambiado.", "precondition_failed")), actionKind = NewsAction.SAVE, actionConflict = true).saveStatus()
        assertTrue(conflict.conflict)
        assertFalse(conflict.canSave, "con la version caducada no hay «guardar de todas formas»")

        val forbidden = saved().copy(isDirty = true, actionState = UiAsync.Error(AppError.Authorization("x")), actionKind = NewsAction.SAVE).saveStatus()
        assertEquals("Tu usuario no tiene permiso para ver esto.", forbidden.detail)

        val published = saved().copy(editorStatus = "published").saveStatus()
        assertEquals("Publicada: el contenido queda fijado.", published.message)
        assertFalse(published.canSave)

        val okMessage = saved().copy(actionState = UiAsync.Success(Unit), actionKind = NewsAction.SAVE, actionMessage = "Guardado en el servidor.").saveStatus()
        assertEquals("Guardado en el servidor.", okMessage.message)
        assertEquals(WorkspaceSaveTone.SUCCESS, okMessage.tone)
    }

    @Test
    fun recoveredDraftIsSaidBeforeAnythingElse() {
        val status = saved().copy(isDirty = true, recoveredDraft = true).saveStatus()
        assertEquals("Cambios recuperados de este equipo.", status.message)
        assertTrue(status.canSave && status.canDiscard)
    }

    @Test
    fun checklistNeverClaimsPassedWithoutAReport() {
        val rows = newsPublicationChecklist(null)
        assertTrue(rows.all { it.tone == NewsCheckTone.UNVERIFIED })
        assertEquals("Sin validar", newsChecklistSummary(rows))
        val report = NewsValidationReport(
            canSave = true, canSchedule = true, canPublish = false,
            errors = listOf(NewsValidationIssue("mystery_code", "Algo nuevo", NewsValidationSeverity.ERROR)),
            warnings = listOf(NewsValidationIssue("text_empty", "El cuerpo esta vacio", NewsValidationSeverity.WARNING)),
        )
        val checked = newsPublicationChecklist(report)
        assertEquals("Otras comprobaciones", checked.last().title, "un codigo nuevo no desaparece")
        assertEquals(NewsCheckTone.NOTICE, checked.first { it.title == "Contenido" }.tone)
        assertEquals("1 bloquean · 1 con avisos", newsChecklistSummary(checked))
    }

    @Test
    fun libraryMetaPrefersThePublicationDate() {
        val item = NewsItem("1", "t", "esp", "tipo", "", status = "published", publishFrom = "2026-09-15T09:00:00Z", text = "", publishedAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-20T10:00:00Z")
        assertEquals(listOf("Publicada 12/09/2026", "Convocatoria"), newsLibraryMeta(item, catalogs))
        assertEquals(listOf("Programada 15/09/2026", "Convocatoria"), newsLibraryMeta(item.copy(publishedAt = null), catalogs))
        assertEquals(listOf("Editada 20/09/2026", "Convocatoria"), newsLibraryMeta(item.copy(publishedAt = null, publishFrom = ""), catalogs))
    }

    @Test
    fun confirmationsSayWhatWillHappen() {
        val publish = newsConfirmation(NewsIntent.Publish, saved())
        assertFalse(publish.destructive)
        assertTrue(publish.message.contains("no se puede editar"))
        val delete = newsConfirmation(NewsIntent.DeleteSelected, saved())
        assertTrue(delete.destructive)
        assertTrue(delete.message.contains("No se puede deshacer"))
        val discard = newsConfirmation(NewsIntent.DeleteSelected, saved().copy(selectedId = "local-9"))
        assertEquals("Descartar borrador", discard.title)
    }
}
