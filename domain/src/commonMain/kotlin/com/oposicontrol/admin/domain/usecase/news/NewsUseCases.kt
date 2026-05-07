package com.oposicontrol.admin.domain.usecase.news

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsArticleType
import com.oposicontrol.admin.domain.model.NewsAssetStatus
import com.oposicontrol.admin.domain.model.NewsCanvasLayer
import com.oposicontrol.admin.domain.model.NewsDesignDraft
import com.oposicontrol.admin.domain.model.NewsEditorialStatus
import com.oposicontrol.admin.domain.model.NewsLayerKind
import com.oposicontrol.admin.domain.model.NewsValidationIssue
import com.oposicontrol.admin.domain.model.NewsValidationReport
import com.oposicontrol.admin.domain.model.NewsValidationSeverity
import com.oposicontrol.admin.domain.repository.NewsRepository

class ObserveNewsUseCase(private val repository: NewsRepository) {
    operator fun invoke() = repository.observeNews()
}

class RefreshNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke() = repository.refreshNews()
}

class UpsertNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(article: NewsArticle): AppResult<NewsArticle> {
        if (article.title.isBlank()) return AppResult.Failure(AppError.Validation("La noticia necesita titulo."))
        return repository.upsertNews(article)
    }
}

class DeleteNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(newsId: String) = repository.deleteNews(newsId)
}

class ValidateNewsDraftUseCase {
    operator fun invoke(draft: NewsDesignDraft): NewsValidationReport {
        val errors = mutableListOf<NewsValidationIssue>()
        val warnings = mutableListOf<NewsValidationIssue>()
        val article = draft.article
        val canvas = draft.canvas
        val visibleLayers = canvas.layers.filter { it.visible }
        val hasTemplateBackground = canvas.backgroundStyle.toNewsTemplateStyleOrNull() != null

        if (article.title.isBlank()) errors += issue("title_required", "La noticia necesita titulo.")
        if (article.text.isBlank()) warnings += warning("text_empty", "El cuerpo esta vacio; revisa si la noticia depende solo del canvas.")
        if (article.specialty.isBlank()) errors += issue("specialty_required", "La noticia necesita especialidad.")
        if (canvas.width <= 0 || canvas.height <= 0) errors += issue("canvas_invalid", "El lienzo no tiene medidas validas.")
        if (!hasTemplateBackground && (canvas.background == null || canvas.background.path.isBlank())) {
            errors += issue("background_missing", "No hay fondo definido para el canvas.")
        }
        if (!hasTemplateBackground) canvas.background?.let { asset ->
            if (asset.status == NewsAssetStatus.LOCAL || asset.status == NewsAssetStatus.UPLOADING || asset.status == NewsAssetStatus.ERROR) {
                errors += issue("background_not_ready", "El fondo no esta subido y enlazado.")
            }
        }
        if (visibleLayers.isEmpty()) errors += issue("layers_empty", "Debe haber al menos una capa visible.")
        if (visibleLayers.none { it.kind == NewsLayerKind.TITLE && it.content.isNotBlank() }) {
            errors += issue("title_layer_required", "Debe existir una capa de titulo visible.")
        }
        visibleLayers.forEach { layer ->
            validateLayer(layer, canvas.width, canvas.height, errors, warnings)
        }
        when (article.type) {
            NewsArticleType.CONVOCATORIA -> {
                if (article.publishFrom.isBlank() && article.status == NewsEditorialStatus.SCHEDULED) {
                    errors += issue("schedule_date_required", "La convocatoria programada necesita fecha.")
                }
            }
            NewsArticleType.OFERTA -> {
                if (article.linkedOfferId.isNullOrBlank() && article.linkedStoreItemId.isNullOrBlank()) {
                    errors += issue("offer_link_required", "La noticia de oferta necesita oferta o item vinculado.")
                }
                if (visibleLayers.none { it.kind == NewsLayerKind.CTA && it.content.isNotBlank() }) {
                    errors += issue("offer_cta_required", "La oferta necesita una capa CTA visible.")
                }
            }
            NewsArticleType.RECURSO -> {
                if (visibleLayers.none { it.kind == NewsLayerKind.IMAGE }) {
                    warnings += warning("resource_image_missing", "La noticia de recurso deberia incluir una capa de imagen.")
                }
            }
            NewsArticleType.AVISO -> Unit
        }

        val hasBlockingErrors = errors.isNotEmpty()
        val canSchedule = !hasBlockingErrors && article.publishFrom.isNotBlank()
        return NewsValidationReport(
            canSave = article.title.isNotBlank() || visibleLayers.isNotEmpty(),
            canSchedule = canSchedule,
            canPublish = !hasBlockingErrors,
            errors = errors,
            warnings = warnings,
        )
    }

    private fun validateLayer(
        layer: NewsCanvasLayer,
        canvasWidth: Int,
        canvasHeight: Int,
        errors: MutableList<NewsValidationIssue>,
        warnings: MutableList<NewsValidationIssue>,
    ) {
        if (layer.width < 24 || layer.height < 24) {
            errors += issue("layer_too_small", "La capa ${layer.name} tiene un tamano minimo invalido.", layer.id)
        }
        if (layer.opacity !in 0..100) {
            errors += issue("layer_opacity_invalid", "La opacidad de ${layer.name} debe estar entre 0 y 100.", layer.id)
        }
        if (layer.x < 0 || layer.y < 0 || layer.x + layer.width > canvasWidth || layer.y + layer.height > canvasHeight) {
            errors += issue("layer_outside_canvas", "La capa ${layer.name} esta fuera del lienzo.", layer.id)
        }
        if ((layer.kind == NewsLayerKind.TITLE || layer.kind == NewsLayerKind.TEXT || layer.kind == NewsLayerKind.CTA) && layer.content.isBlank()) {
            errors += issue("layer_text_required", "La capa ${layer.name} necesita contenido.", layer.id)
        }
        if (layer.kind == NewsLayerKind.CTA && layer.content.length > 24) {
            warnings += warning("cta_too_long", "El CTA ${layer.name} deberia ser mas corto.", layer.id)
        }
    }

    private fun issue(
        code: String,
        message: String,
        targetId: String? = null,
    ) = NewsValidationIssue(code, message, NewsValidationSeverity.ERROR, targetId)

    private fun warning(
        code: String,
        message: String,
        targetId: String? = null,
    ) = NewsValidationIssue(code, message, NewsValidationSeverity.WARNING, targetId)
}

private fun String.toNewsTemplateStyleOrNull(): String? {
    return when (trim().uppercase()) {
        "A", "SKY" -> "SKY"
        "B", "MINT" -> "MINT"
        "C", "GOLD" -> "GOLD"
        else -> null
    }
}

class PublishNewsUseCase(
    private val repository: NewsRepository,
    private val validateNewsDraftUseCase: ValidateNewsDraftUseCase,
) {
    suspend operator fun invoke(article: NewsArticle, draft: NewsDesignDraft): AppResult<NewsArticle> {
        val report = validateNewsDraftUseCase(draft)
        if (!report.canPublish) {
            return AppResult.Failure(AppError.Validation(report.errors.firstOrNull()?.message ?: "La noticia no se puede publicar."))
        }
        return repository.upsertNews(article.copy(status = "published"))
    }
}

class ScheduleNewsUseCase(
    private val repository: NewsRepository,
    private val validateNewsDraftUseCase: ValidateNewsDraftUseCase,
) {
    suspend operator fun invoke(article: NewsArticle, draft: NewsDesignDraft): AppResult<NewsArticle> {
        val report = validateNewsDraftUseCase(draft)
        if (!report.canSchedule) {
            return AppResult.Failure(AppError.Validation(report.errors.firstOrNull()?.message ?: "La noticia no se puede programar."))
        }
        return repository.upsertNews(article.copy(status = "scheduled"))
    }
}

class ArchiveNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(article: NewsArticle): AppResult<NewsArticle> {
        if (article.id.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona una noticia para archivar."))
        return repository.upsertNews(article.copy(status = "archived"))
    }
}
