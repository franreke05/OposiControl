package com.oposicontrol.admin.features.examaccess.di

import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import org.koin.dsl.module

val examAccessFeatureModule = module {
    factory {
        val usersRepository = get<UsersRepository>()
        val contentRepository = get<ContentRepository>()
        ExamAccessViewModel(
            dispatcherProvider = get(),
            repository = get(),
            analyticsTracker = get(),
            logger = get(),
            users = usersRepository.observeUsers(),
            exams = contentRepository.observeExams(),
            refreshExamCatalog = contentRepository::refreshExams,
        )
    }
}
