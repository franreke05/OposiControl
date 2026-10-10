package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.features.news.editor.NewsAlignment

sealed interface NewsIntent {
    // ---- biblioteca --------------------------------------------------------------------------------------------
    data object Refresh : NewsIntent
    data object LoadMore : NewsIntent
    data class UpdateSearch(val value: String) : NewsIntent
    data class UpdateStatusFilter(val value: String) : NewsIntent
    data class UpdateSpecialtyFilter(val value: String) : NewsIntent
    data class UpdateTypeFilter(val value: String) : NewsIntent
    data object ClearFilters : NewsIntent

    // ---- ficha: abrir, crear, guardar, estado ----------------------------------------------------------------------
    data object CreateNew : NewsIntent
    data class Select(val id: String) : NewsIntent
    data object RetryRecord : NewsIntent
    data class SelectTab(val tab: NewsEditorTab) : NewsIntent

    /** Guarda el contenido en el servidor (crea el borrador o edita el existente). El nombre se conserva por el editor movil. */
    data object SaveDraft : NewsIntent

    /** Variantes por id: las usa el editor MOVIL, que actua sobre una fila de la lista sin seleccionarla antes. */
    data class SaveDraftFor(val id: String) : NewsIntent
    data object Publish : NewsIntent
    data class PublishFor(val id: String) : NewsIntent
    data object Schedule : NewsIntent
    data class ScheduleAt(val isoUtc: String) : NewsIntent
    data object Archive : NewsIntent
    data class ArchiveFor(val id: String) : NewsIntent

    /** Validacion explicita: el editor movil la pide al cambiar de paso. */
    data object ValidateDraft : NewsIntent
    data object DeleteSelected : NewsIntent
    data object Discard : NewsIntent
    data object Undo : NewsIntent
    data object Redo : NewsIntent

    /** Navegacion retenida por cambios sin guardar: continuar (se pierden) o quedarse. */
    data object ConfirmPending : NewsIntent
    data object DismissPending : NewsIntent

    /** Pide confirmacion antes de ejecutar [intent] (publicar, archivar, eliminar). */
    data class RequestConfirm(val intent: NewsIntent) : NewsIntent
    data object DismissConfirm : NewsIntent
    data object DismissRecoveredDraft : NewsIntent

    // ---- contenido ---------------------------------------------------------------------------------------------
    data class UpdateTitle(val value: String) : NewsIntent
    data class UpdateSpecialty(val value: String) : NewsIntent
    data class UpdateType(val value: String) : NewsIntent
    data class UpdateText(val value: String) : NewsIntent
    data class UpdateStatus(val value: String) : NewsIntent
    data class UpdateBackgroundType(val value: String) : NewsIntent

    /** Ruta del fondo escrita a mano (vacia = quitar). Las imagenes locales van por [UploadBackground]. */
    data class UpdateBackgroundPath(val value: String) : NewsIntent
    data class UpdateImagePath(val value: String) : NewsIntent
    data class SelectBackgroundFile(val uri: String, val suggestedName: String, val mimeType: String?) : NewsIntent
    data class UploadBackground(val uri: String) : NewsIntent
    data class UploadImage(val uri: String) : NewsIntent
    data class UploadLayerImage(val id: String, val uri: String) : NewsIntent
    data class RetryUpload(val target: String) : NewsIntent
    data class DismissUpload(val target: String) : NewsIntent

    // ---- lienzo y capas --------------------------------------------------------------------------------------------
    data class UpdateCanvasWidth(val value: String) : NewsIntent
    data class UpdateCanvasHeight(val value: String) : NewsIntent
    data object ToggleCanvasFullscreen : NewsIntent
    data object SelectBackground : NewsIntent
    data object AddTitleLayer : NewsIntent
    data object AddLayer : NewsIntent
    data class AddLayerWithImage(val uri: String) : NewsIntent
    data class SelectLayer(val id: String) : NewsIntent
    data object DeselectLayer : NewsIntent
    data class SelectAdjacentLayer(val backwards: Boolean) : NewsIntent
    data class RemoveLayer(val id: String) : NewsIntent
    data class MoveLayerUp(val id: String) : NewsIntent
    data class MoveLayerDown(val id: String) : NewsIntent
    data class BringLayerToFront(val id: String) : NewsIntent
    data class SendLayerToBack(val id: String) : NewsIntent
    data class CenterLayer(val id: String) : NewsIntent
    data class AlignLayer(val id: String, val alignment: NewsAlignment) : NewsIntent
    data class NudgeLayer(val id: String, val dx: Int, val dy: Int) : NewsIntent
    data class DuplicateLayer(val id: String) : NewsIntent
    data class UpdateLayerName(val id: String, val value: String) : NewsIntent
    data class UpdateLayerKind(val id: String, val value: String) : NewsIntent
    data class UpdateLayerContent(val id: String, val value: String) : NewsIntent
    data class UpdateLayerX(val id: String, val value: String) : NewsIntent
    data class UpdateLayerY(val id: String, val value: String) : NewsIntent
    data class UpdateLayerWidth(val id: String, val value: String) : NewsIntent
    data class UpdateLayerHeight(val id: String, val value: String) : NewsIntent

    /** [gesture] = la actualizacion viene de un arrastre: se funde en un solo paso de deshacer por capa. */
    data class UpdateLayerFrame(val id: String, val x: Int, val y: Int, val width: Int, val height: Int, val gesture: Boolean = false) : NewsIntent
    data class UpdateLayerOpacity(val id: String, val value: String) : NewsIntent
    data class UpdateLayerRotation(val id: String, val degrees: Int) : NewsIntent
    data class UpdateLayerFontFamily(val id: String, val value: String) : NewsIntent
    data class UpdateLayerFontSize(val id: String, val value: String) : NewsIntent
    data class UpdateLayerFontWeight(val id: String, val value: String) : NewsIntent
    data class UpdateLayerTextColor(val id: String, val value: String) : NewsIntent
    data class ToggleLayerVisible(val id: String) : NewsIntent
    data class ToggleLayerLocked(val id: String) : NewsIntent
}
