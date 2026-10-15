package com.oposicontrol.admin.features.examaccess.di

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.repository.UsersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** La persona a la que se concede acceso sale del censo (`/usuarios?q=`), no de las clasificaciones. */
class ExamAccessPeopleSearchTest {
    private class Users(private val rows: List<User>) : UsersRepository {
        val queries = mutableListOf<Pair<String, Int>>()
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(rows))
        override suspend fun getUser(userId: String): AppResult<User> = error("unused")
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> = error("unused")
        override suspend fun searchUsers(query: String, limit: Int): AppResult<List<User>> {
            queries += query to limit
            return AppResult.Success(rows)
        }
    }

    private fun user(id: String, name: String, username: String?) =
        User(id = id, fullName = name, email = "", roleIds = emptyList(), isRegistered = true, createdAt = null, username = username)

    @Test fun searchesTheUserCensusWithAPageLimitAndMapsIdentity() = runTest {
        val users = Users(listOf(user("u-1", "Ana Ruiz", "ana"), user("u-2", "", null)))
        val result = searchAccessPeople(users, "  ana ")
        assertEquals(listOf("ana" to ACCESS_PEOPLE_SEARCH_LIMIT), users.queries)
        val options = (result as AppResult.Success).value
        assertEquals("Ana Ruiz", options[0].label)
        assertEquals("@ana", options[0].detail)
        assertEquals("u-2", options[1].label, "sin nombre ni usuario se enseña el id, no se inventa")
    }

    @Test fun aQueryTheServerWouldRejectIsRefusedWithoutCallingIt() = runTest {
        val users = Users(emptyList())
        val result = searchAccessPeople(users, "ana%")
        assertIs<AppError.Validation>((result as AppResult.Failure).error)
        assertTrue(users.queries.isEmpty())
    }
}
