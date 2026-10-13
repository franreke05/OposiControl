package com.oposicontrol.admin.features.examaccess.di

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.RankingEntry
import com.oposicontrol.admin.domain.model.RankingPageRequest
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.RankingsRepository
import com.oposicontrol.admin.features.examaccess.presentation.AccessExamOption
import com.oposicontrol.admin.features.examaccess.presentation.AccessUserOption
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import org.koin.dsl.module

val examAccessFeatureModule = module {
    single {
        // Las dos fuentes de los selectores ya existen: la búsqueda de personas del servidor (la misma de Competición)
        // y el catálogo de exámenes. Se pasan como funciones para que la pantalla no dependa de dos repositorios enteros.
        val people: RankingsRepository = get()
        val content: ContentRepository = get()
        ExamAccessViewModel(
            dispatcherProvider = get(),
            repository = get(),
            analyticsTracker = get(),
            searchPeople = { query ->
                when (val result = people.searchUsers(query, RankingPageRequest.of())) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> AppResult.Success(result.value.entries.map { it.toAccessOption() })
                }
            },
            loadExamCatalog = {
                when (val result = content.refreshAllExams()) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> AppResult.Success(result.value.map { it.toAccessOption() })
                }
            },
        )
    }
}

internal fun RankingEntry.toAccessOption(): AccessUserOption {
    val handle = username?.takeIf { it.isNotBlank() }?.let { "@$it" }
    return AccessUserOption(id = userId, label = label ?: userId, detail = handle ?: userId)
}

internal fun Exam.toAccessOption() = AccessExamOption(
    id = id,
    title = title.ifBlank { "Examen sin título" },
    detail = listOf(specialtyName, typeAccess).filter { it.isNotBlank() }.joinToString(" · "),
)
