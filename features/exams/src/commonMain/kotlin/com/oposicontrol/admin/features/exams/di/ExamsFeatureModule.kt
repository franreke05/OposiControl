package com.oposicontrol.admin.features.exams.di

import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.ClearExamCoverUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetRafagaQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaUseCase
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
    factory { ObserveRafagasUseCase(get()) }
    factory { RefreshExamsUseCase(get()) }
    factory { RefreshRafagasUseCase(get()) }
    factory { GetExamQuestionsUseCase(get()) }
    factory { GetRafagaQuestionsUseCase(get()) }
    factory { GetExamResourceAuditUseCase(get()) }
    factory { UpsertExamUseCase(get()) }
    factory { DeleteExamUseCase(get()) }
    factory { UpsertRafagaUseCase(get()) }
    factory { DeleteRafagaUseCase(get()) }
    factory { UpsertQuestionUseCase(get()) }
    factory { ImportQuestionsUseCase(get()) }
    factory { DeleteQuestionUseCase(get()) }
    factory { MoveQuestionUseCase(get()) }
    factory { UpsertRafagaQuestionUseCase(get()) }
    factory { DeleteRafagaQuestionUseCase(get()) }
    factory { MoveRafagaQuestionUseCase(get()) }
    factory { UploadExamCoverUseCase(get()) }
    factory { ClearExamCoverUseCase(get()) }
    factory { UploadQuestionResourceUseCase(get()) }
    factory { DeleteQuestionResourceUseCase(get()) }
    single {
        ExamsViewModel(
            dispatcherProvider = get(),
            observeSpecialtiesUseCase = get(),
            observeExamsUseCase = get(),
            observeRafagasUseCase = get(),
            refreshSpecialtiesUseCase = get(),
            upsertSpecialtyUseCase = get(),
            deleteSpecialtyUseCase = get(),
            refreshExamsUseCase = get(),
            refreshRafagasUseCase = get(),
            getExamQuestionsUseCase = get(),
            getRafagaQuestionsUseCase = get(),
            getExamResourceAuditUseCase = get(),
            upsertExamUseCase = get(),
            deleteExamUseCase = get(),
            upsertRafagaUseCase = get(),
            deleteRafagaUseCase = get(),
            upsertQuestionUseCase = get(),
            importQuestionsUseCase = get(),
            deleteQuestionUseCase = get(),
            moveQuestionUseCase = get(),
            upsertRafagaQuestionUseCase = get(),
            deleteRafagaQuestionUseCase = get(),
            moveRafagaQuestionUseCase = get(),
            uploadExamCoverUseCase = get(),
            clearExamCoverUseCase = get(),
            uploadQuestionResourceUseCase = get(),
            deleteQuestionResourceUseCase = get(),
            analyticsTracker = get(),
        )
    }
}
