package com.oposicontrol.admin.domain.model

/**
 * Fila RESUMEN de la biblioteca de noticias (D4-01): lo que el listado canonico devuelve, sin texto ni capas.
 * Los instantes son cadenas ISO-8601 en UTC tal como las emite el backend; `null` cuando la columna esta vacia.
 */
data class NewsSummary(
    val id: String,
    val title: String,
    val status: String,
    val specialtyId: String,
    val typeId: String,
    val publishFrom: String? = null,
    val publishedAt: String? = null,
    val imageUrl: String? = null,
    val updatedAt: String = "",
)

/** Una pagina del listado canonico: filas mas el cursor de la siguiente (`null` = no hay mas). */
data class NewsPage(
    val items: List<NewsSummary>,
    val nextCursor: String? = null,
)

/** Filtros del listado canonico (D4-01). `null` = sin filtrar. */
data class NewsListFilter(
    val status: String? = null,
    val specialtyId: String? = null,
    val typeId: String? = null,
    val query: String? = null,
)

/**
 * Ficha completa de una noticia (D4-02). [version] es la version de concurrencia optimista (el ETag) que el
 * cliente devuelve como `If-Match` al editar; vacia en una noticia que todavia no existe en el servidor.
 * [backgroundType] viaja en el vocabulario del editor (`SKY`, `MINT`, `GOLD`); el cable usa `A`, `B`, `C`.
 */
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
    val version: String = "",
    val publishedAt: String? = null,
    /** URL publica del fondo en `news-assets`, resuelta por el backend. `null` sin fondo. */
    val backgroundUrl: String? = null,
    val imageUrl: String? = null,
    /** Capas del servidor que no se pudieron leer y se omitieron de [layers]: el editor debe avisar. */
    val droppedLayerCount: Int = 0,
)

data class NewsAssetUpload(
    val path: String,
    val publicUrl: String,
    val mimeType: String?,
    val bytes: Int = 0,
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
