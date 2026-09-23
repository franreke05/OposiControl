package com.oposicontrol.admin.features.news.presentation

sealed interface NewsIntent {
    data object Refresh : NewsIntent
    data object CreateNew : NewsIntent
    data object SaveDraft : NewsIntent
    data class SaveDraftFor(val id: String) : NewsIntent
    data object Publish : NewsIntent
    data class PublishFor(val id: String) : NewsIntent
    data object Schedule : NewsIntent
    data class ScheduleAt(val isoUtc: String) : NewsIntent
    data object Archive : NewsIntent
    data class ArchiveFor(val id: String) : NewsIntent
    data object ValidateDraft : NewsIntent
    data object DeleteSelected : NewsIntent
    data object SelectBackground : NewsIntent
    data class Select(val id: String) : NewsIntent
    data class UpdateSearch(val value: String) : NewsIntent
    data class UpdateStatusFilter(val value: String) : NewsIntent
    data class UpdateSpecialtyFilter(val value: String) : NewsIntent
    data class UpdateTypeFilter(val value: String) : NewsIntent
    data class UpdateTitle(val value: String) : NewsIntent
    data class UpdateBackgroundType(val value: String) : NewsIntent
    data class UpdateBackgroundPath(val value: String) : NewsIntent
    data class SelectBackgroundFile(val uri: String, val suggestedName: String, val mimeType: String?) : NewsIntent
    data class UpdateSpecialty(val value: String) : NewsIntent
    data class UpdateType(val value: String) : NewsIntent
    data class UpdateText(val value: String) : NewsIntent
    data class UpdateImagePath(val value: String) : NewsIntent
    data class UpdateStatus(val value: String) : NewsIntent
    data class UpdatePublishFrom(val value: String) : NewsIntent
    data class UpdateCanvasWidth(val value: String) : NewsIntent
    data class UpdateCanvasHeight(val value: String) : NewsIntent
    data class UpdateBackgroundAssetStatus(val value: String) : NewsIntent
    data class UpdateImageAssetStatus(val value: String) : NewsIntent
    data object ToggleCanvasFullscreen : NewsIntent
    data object AddTitleLayer : NewsIntent
    data object AddLayer : NewsIntent
    data class AddLayerWithImage(val uri: String) : NewsIntent
    data class SelectLayer(val id: String) : NewsIntent
    data class RemoveLayer(val id: String) : NewsIntent
    data class MoveLayerUp(val id: String) : NewsIntent
    data class MoveLayerDown(val id: String) : NewsIntent
    data class BringLayerToFront(val id: String) : NewsIntent
    data class SendLayerToBack(val id: String) : NewsIntent
    data class CenterLayer(val id: String) : NewsIntent
    data class DuplicateLayer(val id: String) : NewsIntent
    data class UpdateLayerName(val id: String, val value: String) : NewsIntent
    data class UpdateLayerKind(val id: String, val value: String) : NewsIntent
    data class UpdateLayerContent(val id: String, val value: String) : NewsIntent
    data class UpdateLayerX(val id: String, val value: String) : NewsIntent
    data class UpdateLayerY(val id: String, val value: String) : NewsIntent
    data class UpdateLayerWidth(val id: String, val value: String) : NewsIntent
    data class UpdateLayerHeight(val id: String, val value: String) : NewsIntent
    data class UpdateLayerFrame(val id: String, val x: Int, val y: Int, val width: Int, val height: Int) : NewsIntent
    data class UpdateLayerOpacity(val id: String, val value: String) : NewsIntent
    data class UpdateLayerRotation(val id: String, val degrees: Int) : NewsIntent
    data class UpdateLayerFontFamily(val id: String, val value: String) : NewsIntent
    data class UpdateLayerFontSize(val id: String, val value: String) : NewsIntent
    data class UpdateLayerFontWeight(val id: String, val value: String) : NewsIntent
    data class UpdateLayerTextColor(val id: String, val value: String) : NewsIntent
    data class ToggleLayerVisible(val id: String) : NewsIntent
    data class ToggleLayerLocked(val id: String) : NewsIntent
}
