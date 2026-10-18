package com.oposicontrol.admin.features.exams.di

import com.oposicontrol.admin.features.exams.presentation.ExamGradingRulesViewModel
import com.oposicontrol.admin.domain.usecase.communities.ObserveCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.RefreshCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.CreateContentDraftUseCase
import com.oposicontrol.admin.domain.usecase.content.GetContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.ListContentImportBatchesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishContentImportBatchUseCase
import com.oposicontrol.admin.domain.usecase.content.SkipContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UpdateContentImportStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadPdfToStagingUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetRafagaQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UnpublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.UnlinkQuestionFromExamUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import com.oposicontrol.admin.features.exams.presentation.ContentUploadViewModel
import org.koin.dsl.module

val examsFeatureModule = module {
    factory { ObserveSpecialtiesUseCase(get()) }
    factory { RefreshSpecialtiesUseCase(get()) }
    factory { UpsertSpecialtyUseCase(get()) }
    factory { DeleteSpecialtyUseCase(get()) }
    factory { ObserveAllExamsUseCase(get()) }
    factory { RefreshAllExamsUseCase(get()) }
    factory { UnlinkQuestionFromExamUseCase(get()) }
    factory { ArchiveQuestionUseCase(get()) }
    // Siguen registrados: los consume `ContentUploadViewModel`, que trabaja
    // sobre la lista de solucionarios del camino móvil.
    factory { ObserveExamsUseCase(get()) }
    factory { ObserveRafagasUseCase(get()) }
    factory { RefreshExamsUseCase(get()) }
    factory { RefreshRafagasUseCase(get()) }
    factory { GetExamQuestionsUseCase(get()) }
    factory { GetRafagaQuestionsUseCase(get()) }
    factory { GetExamResourceAuditUseCase(get()) }
    factory { UpsertExamUseCase(get()) }
    factory { DeleteExamUseCase(get()) }
    factory { ArchiveExamUseCase(get()) }
    factory { PublishExamUseCase(get()) }
    factory { UnpublishExamUseCase(get()) }
    factory { GetSpecialtyCapabilitiesUseCase(get()) }
    factory { ObserveCommunitiesUseCase(get()) }
    factory { RefreshCommunitiesUseCase(get()) }
    factory { UpsertRafagaUseCase(get()) }
    factory { DeleteRafagaUseCase(get()) }
    factory { UpsertQuestionUseCase(get()) }
    factory { ImportQuestionsUseCase(get()) }

    factory { MoveQuestionUseCase(get()) }
    factory { UpsertRafagaQuestionUseCase(get()) }
    factory { DeleteRafagaQuestionUseCase(get()) }
    factory { MoveRafagaQuestionUseCase(get()) }
    factory { ListContentImportBatchesUseCase(get()) }
    factory { GetContentImportBatchUseCase(get()) }
    factory { UploadPdfToStagingUseCase(get()) }
    factory { UpdateContentImportStagingUseCase(get()) }
    factory { PublishContentImportBatchUseCase(get()) }
    factory { CreateContentDraftUseCase(get()) }
    factory { UploadQuestionResourceUseCase(get()) }
    factory { DeleteQuestionResourceUseCase(get()) }
    single {
        ExamsViewModel(
            dispatcherProvider = get(),
            observeSpecialtiesUseCase = get(),
            observeAllExamsUseCase = get(),
            observeRafagasUseCase = get(),
            refreshSpecialtiesUseCase = get(),
            upsertSpecialtyUseCase = get(),
            deleteSpecialtyUseCase = get(),
            refreshAllExamsUseCase = get(),
            refreshRafagasUseCase = get(),
            getExamQuestionsUseCase = get(),
            getRafagaQuestionsUseCase = get(),
            getExamResourceAuditUseCase = get(),
            upsertExamUseCase = get(),
            deleteExamUseCase = get(),
            archiveExamUseCase = get(),
            publishExamUseCase = get(),
            unpublishExamUseCase = get(),
            getSpecialtyCapabilitiesUseCase = get(),
            observeCommunitiesUseCase = get(),
            refreshCommunitiesUseCase = get(),
            upsertRafagaUseCase = get(),
            deleteRafagaUseCase = get(),
            upsertQuestionUseCase = get(),
            importQuestionsUseCase = get(),
            unlinkQuestionFromExamUseCase = get(),
            archiveQuestionUseCase = get(),
            moveQuestionUseCase = get(),
            upsertRafagaQuestionUseCase = get(),
            deleteRafagaQuestionUseCase = get(),
            moveRafagaQuestionUseCase = get(),
            uploadQuestionResourceUseCase = get(),
            deleteQuestionResourceUseCase = get(),
            analyticsTracker = get(),
        )
    }
    factory {
        ContentUploadViewModel(
            dispatcherProvider = get(),
            observeSpecialtiesUseCase = get(),
            observeExamsUseCase = get(),
            refreshSpecialtiesUseCase = get(),
            refreshExamsUseCase = get(),
            listBatchesUseCase = get(),
            getBatchUseCase = get(),
            uploadPdfUseCase = get(),
            updateStagingUseCase = get(),
            skipStagingUseCase = SkipContentImportStagingUseCase(get()),
            publishBatchUseCase = get(),
            createDraftUseCase = get(),
            analyticsTracker = get(),
        )
    }
    // Reglas de puntuación de un examen (Control V1). `single`: el editor lo recarga al cambiar de examen.
    single { ExamGradingRulesViewModel(dispatcherProvider = get(), repository = get()) }
}
