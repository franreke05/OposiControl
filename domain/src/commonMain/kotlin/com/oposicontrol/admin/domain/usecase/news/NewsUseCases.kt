package com.oposicontrol.admin.domain.usecase.news

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetStatus
import com.oposicontrol.admin.domain.model.NewsCanvasLayer
import com.oposicontrol.admin.domain.model.NewsDesignDraft
import com.oposicontrol.admin.domain.model.NewsLayerKind
import com.oposicontrol.admin.domain.model.NewsListFilter
import com.oposicontrol.admin.domain.model.NewsValidationIssue
import com.oposicontrol.admin.domain.model.NewsValidationReport
import com.oposicontrol.admin.domain.model.NewsValidationSeverity
import com.oposicontrol.admin.domain.repository.NewsRepository

class ObserveNewsUseCase(private val repository: NewsRepository) {
    operator fun invoke() = repository.observeNews()
}

class RefreshNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(filter: NewsListFilter = NewsListFilter()) = repository.refreshNews(filter)
}

class LoadMoreNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(filter: NewsListFilter, cursor: String) = repository.loadMoreNews(filter, cursor)
}

class LoadNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(newsId: String) = repository.getNews(newsId)
}

/**
 * Guarda el CONTENIDO: crea el borrador si la noticia no existe todavia (sin id ni version) o edita la existente
 * condicionado a su version. Las reglas que el servidor exige para aceptar el cuerpo se comprueban antes de enviar
 * para que el rechazo llegue con su campo y no como un 400 generico.
 */
class SaveNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(article: NewsArticle, idempotencyKey: String): AppResult<NewsArticle> {
        if (article.title.isBlank()) return AppResult.Failure(AppError.Validation("La noticia necesita título.", "title_required"))
        if (article.specialty.isBlank()) return AppResult.Failure(AppError.Validation("La noticia necesita especialidad.", "specialty_required"))
        if (article.type.isBlank()) return AppResult.Failure(AppError.Validation("La noticia necesita tipo.", "type_required"))
        return if (article.id.isBlank()) repository.createNews(article, idempotencyKey) else repository.updateNews(article)
    }
}

class UploadNewsAssetUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(localPathOrUri: String, idempotencyKey: String, onProgress: (Float) -> Unit = {}) =
        repository.uploadNewsAsset(localPathOrUri, idempotencyKey, onProgress)
}

class DeleteNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(newsId: String) = repository.deleteNews(newsId)
}

/**
 * Comprobaciones del editor antes de publicar o programar. El servidor solo exige que haya contenido (D4-05); estas
 * reglas evitan publicar una composicion rota. No hay reglas por tipo de noticia: el contrato no guarda ningun dato
 * propio de un tipo (ni enlace ni boton), asi que exigirlo aqui bloquearia una publicacion que el servidor acepta.
 */
class ValidateNewsDraftUseCase {
    operator fun invoke(draft: NewsDesignDraft): NewsValidationReport {
        val errors = mutableListOf<NewsValidationIssue>()
        val warnings = mutableListOf<NewsValidationIssue>()
        val article = draft.article
        val canvas = draft.canvas
        val visibleLayers = canvas.layers.filter { it.visible }
        val hasTemplateBackground = canvas.backgroundStyle.toNewsTemplateStyleOrNull() != null

        if (article.title.isBlank()) errors += issue("title_required", "La noticia necesita título.")
        if (article.text.isBlank()) warnings += warning("text_empty", "El cuerpo está vacío: la noticia solo tendrá lo que pinte el banner.")
        if (article.specialty.isBlank()) errors += issue("specialty_required", "La noticia necesita especialidad.")
        if (canvas.width <= 0 || canvas.height <= 0) errors += issue("canvas_invalid", "El lienzo no tiene medidas válidas.")
        if (!hasTemplateBackground && (canvas.background == null || canvas.background.path.isBlank())) {
            errors += issue("background_missing", "No hay fondo definido para el lienzo.")
        }
        if (!hasTemplateBackground) canvas.background?.let { asset ->
            if (asset.status == NewsAssetStatus.LOCAL || asset.status == NewsAssetStatus.UPLOADING || asset.status == NewsAssetStatus.ERROR) {
                errors += issue("background_not_ready", "El fondo no está subido y enlazado.")
            }
        }
        if (visibleLayers.isEmpty()) errors += issue("layers_empty", "Tiene que haber al menos una capa visible.")
        if (visibleLayers.none { it.kind == NewsLayerKind.TITLE && it.content.isNotBlank() }) {
            errors += issue("title_layer_required", "Hace falta una capa de título visible y con texto.")
        }
        visibleLayers.forEach { layer ->
            validateLayer(layer, canvas.width, canvas.height, errors, warnings)
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
            errors += issue("layer_too_small", "La capa «${layer.name}» mide menos de 24 px de lado.", layer.id)
        }
        if (layer.opacity !in 0..100) {
            errors += issue("layer_opacity_invalid", "La opacidad de «${layer.name}» tiene que estar entre 0 y 100.", layer.id)
        }
        if (layer.x < 0 || layer.y < 0 || layer.x + layer.width > canvasWidth || layer.y + layer.height > canvasHeight) {
            errors += issue("layer_outside_canvas", "La capa «${layer.name}» se sale del lienzo.", layer.id)
        }
        // Una capa vacia no rompe nada: el banner no la pinta. Se avisa porque suele ser un olvido, no se bloquea.
        if (layer.content.isBlank()) {
            if (layer.kind == NewsLayerKind.IMAGE) {
                warnings += warning("layer_image_missing", "La capa «${layer.name}» no tiene imagen: no se pintará.", layer.id)
            } else if (layer.kind != NewsLayerKind.TITLE) {
                warnings += warning("layer_empty", "La capa «${layer.name}» está vacía: no se pintará.", layer.id)
            }
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

/**
 * Los comandos de estado (D4-05..D4-07) actuan sobre una noticia YA GUARDADA: el contenido viaja con [SaveNewsUseCase]
 * y aqui solo va el identificador. Publicar exige ademas que el borrador pase las reglas del editor, porque el servidor
 * solo comprueba que haya contenido y una composicion vacia saldria publicada.
 */
class PublishNewsUseCase(
    private val repository: NewsRepository,
    private val validateNewsDraftUseCase: ValidateNewsDraftUseCase,
) {
    suspend operator fun invoke(newsId: String, draft: NewsDesignDraft): AppResult<NewsArticle> {
        if (newsId.isBlank()) return AppResult.Failure(AppError.Validation("Guarda la noticia antes de publicarla."))
        val report = validateNewsDraftUseCase(draft)
        if (!report.canPublish) {
            return AppResult.Failure(AppError.Validation(report.errors.firstOrNull()?.message ?: "La noticia no se puede publicar."))
        }
        return repository.publishNews(newsId)
    }
}

class ScheduleNewsUseCase(
    private val repository: NewsRepository,
    private val validateNewsDraftUseCase: ValidateNewsDraftUseCase,
) {
    suspend operator fun invoke(newsId: String, publishFromIsoUtc: String, draft: NewsDesignDraft): AppResult<NewsArticle> {
        if (newsId.isBlank()) return AppResult.Failure(AppError.Validation("Guarda la noticia antes de programarla."))
        if (publishFromIsoUtc.isBlank()) return AppResult.Failure(AppError.Validation("La programación necesita fecha.", "schedule_date_required"))
        val report = validateNewsDraftUseCase(draft)
        if (!report.canPublish) {
            return AppResult.Failure(AppError.Validation(report.errors.firstOrNull()?.message ?: "La noticia no se puede programar."))
        }
        return repository.scheduleNews(newsId, publishFromIsoUtc)
    }
}

class ArchiveNewsUseCase(private val repository: NewsRepository) {
    suspend operator fun invoke(newsId: String): AppResult<NewsArticle> {
        if (newsId.isBlank()) return AppResult.Failure(AppError.Validation("Selecciona una noticia guardada para archivarla."))
        return repository.archiveNews(newsId)
    }
}
