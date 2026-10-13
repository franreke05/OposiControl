package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * «Quién puede entrar» (Sistema > Administradores): qué cuentas salen, y que un fallo —de la lista de usuarios O del
 * catálogo de roles— nunca se pinta como «No hay administradores».
 */
class AdminsViewModelTest {
    private val roles = listOf(
        Role(id = "r-user", name = "Usuario", code = "usuario", permissions = emptyList()),
        // El nombre es una etiqueta que alguien puede reescribir: lo que cuenta es el código.
        Role(id = "r-admin", name = "Equipo", code = " Admin ", permissions = emptyList()),
        Role(id = "r-super", name = "Dirección", code = "superadmin", permissions = emptyList()),
        Role(id = "r-mod", name = "Moderador", code = "moderador", permissions = emptyList()),
    )

    private fun user(id: String, name: String, roleIds: List<String>, status: AccountStatus = AccountStatus.ACTIVE) = User(
        id = id,
        fullName = name,
        email = "$id@example.com",
        roleIds = roleIds,
        isRegistered = true,
        createdAt = null,
        accountStatus = status,
        username = id,
    )

    private val census = listOf(
        user("zoe", "Zoe Admin", listOf("r-admin")),
        user("opo", "Opositora", listOf("r-user")),
        user("mod", "Moderadora", listOf("r-mod")),
        user("both", "Ana Doble", listOf("r-admin", "r-super")),
        user("baja", "Bea Baja", listOf("r-admin"), AccountStatus.BLOCKED),
        user("none", "Sin Roles", emptyList()),
    )

    /**
     * Cada CARGA devuelve la siguiente respuesta preparada (la última se repite). Una carga son dos lecturas, una por rol,
     * y cada una trae solo a quien tiene ese rol, como hace el servidor con `roleCode`: [reads] cuenta cargas y
     * [roleCodes] lo que se pidió. El censo entero no se pide nunca.
     */
    private class Users(private vararg val answers: AppResult<List<User>>) : UsersRepository {
        var reads = 0
        val roleCodes = mutableListOf<String>()
        private val roleIdByCode = mapOf("admin" to "r-admin", "superadmin" to "r-super")
        override fun observeUsers(): Flow<AppResult<List<User>>> = error("Administradores no descarga el censo entero")
        override fun observeUsersWithRole(roleCode: String): Flow<AppResult<List<User>>> = flow {
            roleCodes += roleCode
            if (roleCode == AdminLevel.entries.first().code) reads++
            emit(
                when (val answer = answers[minOf(reads - 1, answers.lastIndex)]) {
                    is AppResult.Failure -> answer
                    is AppResult.Success -> AppResult.Success(answer.value.filter { roleIdByCode.getValue(roleCode) in it.roleIds })
                },
            )
        }
        override suspend fun getUser(userId: String): AppResult<User> = error("unused")
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = error("unused")
        override suspend fun deleteUser(userId: String): AppResult<Unit> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> = error("unused")
    }

    private class Roles(private val answer: AppResult<List<Role>>) : RolesRepository {
        override fun observeRoles(): Flow<AppResult<List<Role>>> = flow { emit(answer) }
    }

    private fun TestScope.viewModel(users: UsersRepository, roles: RolesRepository) =
        AdminsViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)), users, roles)

    @Test
    fun `admins and superadmins are listed and moderators and users are not`() = runTest {
        val vm = viewModel(Users(AppResult.Success(census)), Roles(AppResult.Success(roles)))
        advanceUntilIdle()

        val accounts = assertIs<UiAsync.Success<List<AdminAccount>>>(vm.state.value.accounts).value
        // Superadministradores primero; dentro de cada grupo, por nombre.
        assertEquals(listOf("both", "baja", "zoe"), accounts.map { it.userId })
        assertFalse(accounts.any { it.userId in setOf("opo", "mod", "none") })
    }

    @Test
    fun `an account with both roles is listed once as superadmin`() = runTest {
        val vm = viewModel(Users(AppResult.Success(census)), Roles(AppResult.Success(roles)))
        advanceUntilIdle()

        val both = vm.state.value.loadedAccounts.filter { it.userId == "both" }
        assertEquals(1, both.size)
        assertEquals(AdminLevel.SUPERADMIN, both.single().level)
        assertEquals("Superadministrador", both.single().level.label)
        assertEquals("Administrador", vm.state.value.loadedAccounts.single { it.userId == "zoe" }.level.label)
    }

    @Test
    fun `a blocked admin keeps the row but is not counted as able to enter`() = runTest {
        val vm = viewModel(Users(AppResult.Success(census)), Roles(AppResult.Success(roles)))
        advanceUntilIdle()

        val blocked = vm.state.value.loadedAccounts.single { it.userId == "baja" }
        assertEquals("Dada de baja", adminAccountStatusLabel(blocked.accountStatus))
        assertFalse(adminAccountCanEnter(blocked))
        assertEquals(
            listOf("1", "2", "2", "1"),
            adminSummaryFacts(vm.state.value.loadedAccounts).map { it.value },
            "superadministradores, administradores, con la cuenta activa, sin la cuenta activa",
        )
    }

    @Test
    fun `a network failure reading users is an error and never an empty list`() = runTest {
        val vm = viewModel(Users(AppResult.Failure(AppError.Network("timeout"))), Roles(AppResult.Success(roles)))
        advanceUntilIdle()

        assertIs<UiAsync.Error>(vm.state.value.accounts)
    }

    @Test
    fun `a network failure reading the role catalogue is an error and never an empty list`() = runTest {
        // Sin catálogo no se sabe qué rol abre el panel: filtrar contra un catálogo vacío daría «No hay administradores».
        val vm = viewModel(Users(AppResult.Success(census)), Roles(AppResult.Failure(AppError.Network("timeout"))))
        advanceUntilIdle()

        val error = assertIs<UiAsync.Error>(vm.state.value.accounts)
        assertIs<AppError.Network>(error.error)
    }

    @Test
    fun `a catalogue without the admin roles cannot claim that nobody is an admin`() = runTest {
        val onlyUserRoles = roles.filter { it.code == "usuario" || it.code == "moderador" }
        val vm = viewModel(Users(AppResult.Success(census)), Roles(AppResult.Success(onlyUserRoles)))
        advanceUntilIdle()

        val error = assertIs<UiAsync.Error>(vm.state.value.accounts)
        assertEquals(ADMIN_ROLES_MISSING_MESSAGE, error.error.message)
    }

    @Test
    fun `empty only when both reads answered and nobody holds an admin role`() = runTest {
        val nobody = census.filter { it.id in setOf("opo", "mod", "none") }
        val vm = viewModel(Users(AppResult.Success(nobody)), Roles(AppResult.Success(roles)))
        advanceUntilIdle()

        assertEquals(UiAsync.Empty, vm.state.value.accounts)
    }

    @Test
    fun `a failed refresh keeps the list on screen and says so`() = runTest {
        val users = Users(AppResult.Success(census), AppResult.Failure(AppError.Network("timeout")))
        val vm = viewModel(users, Roles(AppResult.Success(roles)))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.loadedAccounts.size)

        vm.dispatch(AdminsIntent.Refresh)
        advanceUntilIdle()

        assertEquals(2, users.reads)
        assertEquals(listOf("admin", "superadmin"), users.roleCodes.distinct().sorted(), "se pide por rol, no el censo")
        assertEquals(3, vm.state.value.loadedAccounts.size, "lo ya leído no se cambia por un error a pantalla completa")
        assertTrue(vm.state.value.refreshFailed)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `a retry after an error loads the list`() = runTest {
        val users = Users(AppResult.Failure(AppError.Network("timeout")), AppResult.Success(census))
        val vm = viewModel(users, Roles(AppResult.Success(roles)))
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.accounts)

        vm.dispatch(AdminsIntent.Refresh)
        advanceUntilIdle()

        assertEquals(3, vm.state.value.loadedAccounts.size)
        assertFalse(vm.state.value.refreshFailed)
    }
}
