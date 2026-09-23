package com.oposicontrol.admin.domain.model

data class NewsArticle(
    val id: String,
    val title: String,
    val specialty: String,
    val type: String,
    val backgroundType: String,
    val backgroundPath: String?,
    val status: String,
    val publishFrom: String,
    val text: String,
    val imagePath: String?,
    val layers: List<NewsLayer>,
)

data class NewsAssetUpload(
    val path: String,
    val publicUrl: String,
    val mimeType: String?,
)

data class NewsDesignDraft(
    val article: NewsArticleDraft,
    val canvas: NewsCanvasDraft,
)

data class NewsArticleDraft(
    val id: String,
    val title: String,
    val specialty: String,
    val type: NewsArticleType,
    val status: NewsEditorialStatus,
    val publishFrom: String,
    val text: String,
    val linkedExamId: String? = null,
    val linkedStoreItemId: String? = null,
    val linkedOfferId: String? = null,
    val linkedBurstCampaignId: String? = null,
    val priority: Int = 0,
)

data class NewsCanvasDraft(
    val width: Int,
    val height: Int,
    val backgroundStyle: String = "SKY",
    val background: NewsAssetDraft?,
    val imageAsset: NewsAssetDraft?,
    val layers: List<NewsCanvasLayer>,
)

data class NewsCanvasLayer(
    val id: String,
    val name: String,
    val kind: NewsLayerKind,
    val content: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val fontFamily: String,
    val textColor: String,
    val visible: Boolean,
    val locked: Boolean = false,
    val sortOrder: Int,
    val rotationDegrees: Int = 0,
)

data class NewsAssetDraft(
    val path: String,
    val status: NewsAssetStatus,
    val mimeType: String? = null,
)

data class NewsValidationReport(
    val canSave: Boolean,
    val canSchedule: Boolean,
    val canPublish: Boolean,
    val errors: List<NewsValidationIssue>,
    val warnings: List<NewsValidationIssue>,
)

data class NewsValidationIssue(
    val code: String,
    val message: String,
    val severity: NewsValidationSeverity,
    val targetId: String? = null,
)

enum class NewsEditorialStatus {
    DRAFT,
    SCHEDULED,
    PUBLISHED,
    ARCHIVED,
}

enum class NewsArticleType {
    CONVOCATORIA,
    AVISO,
    OFERTA,
    RECURSO,
}

enum class NewsLayerKind {
    TITLE,
    TEXT,
    IMAGE,
    BADGE,
    CTA,
    BACKGROUND,
}

enum class NewsAssetStatus {
    LOCAL,
    UPLOADING,
    UPLOADED,
    LINKED,
    ERROR,
}

enum class NewsValidationSeverity {
    ERROR,
    WARNING,
}

data class NewsLayer(
    val id: String,
    val name: String,
    val kind: String,
    val content: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val fontFamily: String,
    val textColor: String,
    val visible: Boolean,
    val sortOrder: Int,
    val locked: Boolean = false,
    val rotationDegrees: Int = 0,
    /**
     * `0` = derivado del tipo de capa, que es lo que el lienzo hace hoy
     * (titulo grande, texto normal). Ver [NewsLayer.fontWeight] y el borrador
     * de presentacion para la justificacion completa del centinela.
     */
    val fontSizeSp: Int = 0,
    val fontWeight: String = "",
)
