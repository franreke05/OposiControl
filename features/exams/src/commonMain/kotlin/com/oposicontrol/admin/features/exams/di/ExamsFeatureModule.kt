package com.oposicontrol.admin.features.exams.di

import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadExamCoverUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import org.koin.dsl.module

val examsFeatureModule = module {
    factory { ObserveSpecialtiesUseCase(get()) }
    factory { RefreshSpecialtiesUseCase(get()) }
    factory { UpsertSpecialtyUseCase(get()) }
    factory { DeleteSpecialtyUseCase(get()) }
    factory { ObserveExamsUseCase(get()) }
    factory { RefreshExamsUseCase(get()) }
    factory { GetExamQuestionsUseCase(get()) }
    factory { GetExamResourceAuditUseCase(get()) }
    factory { UpsertExamUseCase(get()) }
    factory { DeleteExamUseCase(get()) }
    factory { UpsertQuestionUseCase(get()) }
    factory { ImportQuestionsUseCase(get()) }
    factory { DeleteQuestionUseCase(get()) }
    factory { MoveQuestionUseCase(get()) }
    factory { UploadExamCoverUseCase(get()) }
    factory { UploadQuestionResourceUseCase(get()) }
    factory {
        ExamsViewModel(
            dispatcherProvider = get(),
            observeSpecialtiesUseCase = get(),
            observeExamsUseCase = get(),
            refreshSpecialtiesUseCase = get(),
            upsertSpecialtyUseCase = get(),
            deleteSpecialtyUseCase = get(),
            refreshExamsUseCase = get(),
            getExamQuestionsUseCase = get(),
            getExamResourceAuditUseCase = get(),
            upsertExamUseCase = get(),
            deleteExamUseCase = get(),
            upsertQuestionUseCase = get(),
            importQuestionsUseCase = get(),
            deleteQuestionUseCase = get(),
            moveQuestionUseCase = get(),
            uploadExamCoverUseCase = get(),
            uploadQuestionResourceUseCase = get(),
            analyticsTracker = get(),
        )
    }
}
