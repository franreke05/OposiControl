package com.oposicontrol.admin.domain.usecase.communities

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import com.oposicontrol.admin.domain.validation.ComunidadValidator
import com.oposicontrol.admin.domain.validation.ValidationResult

class ObserveCommunitiesUseCase(
    private val repository: CommunitiesRepository,
) {
    operator fun invoke() = repository.observeCommunities()
}

class RefreshCommunitiesUseCase(
    private val repository: CommunitiesRepository,
) {
    suspend operator fun invoke() = repository.refreshCommunities()
}

class UpsertCommunityUseCase(
    private val repository: CommunitiesRepository,
) {
    suspend operator fun invoke(community: Community): AppResult<Community> {
        val validation = ComunidadValidator.validate(community)
        if (validation is ValidationResult.Invalid) {
            return AppResult.Failure(AppError.Validation(validation.firstMessage))
        }
        return repository.upsertCommunity(community)
    }
}

class DeleteCommunityUseCase(
    private val repository: CommunitiesRepository,
) {
    suspend operator fun invoke(communityId: String) = repository.deleteCommunity(communityId)
}
