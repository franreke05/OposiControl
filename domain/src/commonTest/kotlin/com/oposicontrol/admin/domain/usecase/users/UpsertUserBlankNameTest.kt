package com.oposicontrol.admin.domain.usecase.users

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.repository.UsersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** MASTER4-API-001: edicion "solo roles" de un usuario sin nombre; vaciar un nombre existente sigue siendo error. */
class UpsertUserBlankNameTest {
    private class Repo : UsersRepository {
        val saved = mutableListOf<User>()
        override fun observeUsers(): Flow<AppResult<List<User>>> = emptyFlow()
        override suspend fun getUser(userId: String): AppResult<User> = error("unused")
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> { saved += user; return AppResult.Success(user) }
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> = error("unused")
    }

    private fun user(name: String) = User("u", name, "x", listOf("r"), isRegistered = true, createdAt = null)

    @Test fun blankNameStaysBlankWhenTheUserAlreadyHadNoName() = runTest {
        val repo = Repo()
        assertIs<AppResult.Success<User>>(UpsertUserUseCase(repo)(user(""), previous = user("")))
        assertEquals(1, repo.saved.size)
    }

    @Test fun blankingAnExistingNameIsRejectedAndNeverReachesTheRepository() = runTest {
        val repo = Repo()
        val r = UpsertUserUseCase(repo)(user("  "), previous = user("Ana"))
        assertIs<AppResult.Failure>(r)
        assertIs<AppError.Validation>(r.error)
        assertEquals(0, repo.saved.size)
    }

    @Test fun unknownPreviousNameKeepsRequiringAName() = runTest {
        val repo = Repo()
        assertIs<AppResult.Failure>(UpsertUserUseCase(repo)(user("")))
        assertEquals(0, repo.saved.size)
    }

    @Test fun aNonBlankNameIsAlwaysAccepted() = runTest {
        val repo = Repo()
        assertIs<AppResult.Success<User>>(UpsertUserUseCase(repo)(user("Ana B"), previous = user("Ana")))
    }
}
