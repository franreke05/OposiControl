package com.oposicontrol.admin.features.examaccess.di

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import com.oposicontrol.admin.features.examaccess.presentation.AccessExamOption
import com.oposicontrol.admin.features.examaccess.presentation.AccessUserOption
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import org.koin.dsl.module

/** Filas de la búsqueda de personas: una página del censo, no el censo. */
internal const val ACCESS_PEOPLE_SEARCH_LIMIT = 20

/** Lo que admite `q` en `GET /api/v1/admin/usuarios` (D1-22): letras, dígitos, espacio y `._@+-`, de 1 a 100. */
private val PEOPLE_QUERY_ALLOWED = Regex("^[\\p{L}\\p{N} ._@+-]{1,100}$")

val examAccessFeatureModule = module {
    single {
        // La persona sale del censo de usuarios (`GET /usuarios?q=`), no de Competición: antes se buscaba en rankings y
        // quien no tenía clasificación no aparecía. El catálogo de exámenes, del contenido.
        val users: UsersRepository = get()
        val content: ContentRepository = get()
        ExamAccessViewModel(
            dispatcherProvider = get(),
            repository = get(),
            analyticsTracker = get(),
            searchPeople = { query -> searchAccessPeople(users, query) },
            loadExamCatalog = {
                when (val result = content.refreshAllExams()) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> AppResult.Success(result.value.map { it.toAccessOption() })
                }
            },
        )
    }
}

/** Valida la consulta como el servidor (un 400 se dice antes de enviarlo) y busca una página en el censo. */
internal suspend fun searchAccessPeople(users: UsersRepository, query: String): AppResult<List<AccessUserOption>> {
    val trimmed = query.trim()
    if (!PEOPLE_QUERY_ALLOWED.matches(trimmed)) {
        return AppResult.Failure(
            AppError.Validation("Busca por nombre o usuario: letras, números, espacios y . _ @ + - (máximo 100)."),
        )
    }
    return when (val result = users.searchUsers(trimmed, ACCESS_PEOPLE_SEARCH_LIMIT)) {
        is AppResult.Failure -> result
        is AppResult.Success -> AppResult.Success(result.value.map { it.toAccessOption() })
    }
}

internal fun User.toAccessOption(): AccessUserOption {
    val handle = username?.takeIf { it.isNotBlank() }?.let { "@$it" }
    return AccessUserOption(id = id, label = fullName.ifBlank { handle ?: id }, detail = handle ?: id)
}

internal fun Exam.toAccessOption() = AccessExamOption(
    id = id,
    title = title.ifBlank { "Examen sin título" },
    detail = listOf(specialtyName, typeAccess).filter { it.isNotBlank() }.joinToString(" · "),
)
