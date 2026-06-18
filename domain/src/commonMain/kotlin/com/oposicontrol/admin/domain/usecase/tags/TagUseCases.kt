package com.oposicontrol.admin.domain.usecase.tags

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.repository.TagsRepository
import com.oposicontrol.admin.domain.validation.ValidationResult
import com.oposicontrol.admin.domain.validation.EtiquetaValidator

class ObserveTagsUseCase(
    private val repository: TagsRepository,
) {
    operator fun invoke() = repository.observeTags()
}

class RefreshTagsUseCase(
    private val repository: TagsRepository,
) {
    suspend operator fun invoke() = repository.refreshTags()
}

class UpsertTagUseCase(
    private val repository: TagsRepository,
) {
    suspend operator fun invoke(tag: Tag): AppResult<Tag> {
        val validation = EtiquetaValidator.validate(tag)
        if (validation is ValidationResult.Invalid) {
            return AppResult.Failure(AppError.Validation(validation.firstMessage))
        }
        return repository.upsertTag(tag)
    }
}

class DeleteTagUseCase(
    private val repository: TagsRepository,
) {
    suspend operator fun invoke(tagId: String) = repository.deleteTag(tagId)
}
