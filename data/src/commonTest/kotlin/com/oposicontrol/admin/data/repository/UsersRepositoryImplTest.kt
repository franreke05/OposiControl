package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.fake.FakeAdminStore
import com.oposicontrol.admin.data.fake.FakeAuditDataSource
import com.oposicontrol.admin.data.fake.FakeBehaviorPolicy
import com.oposicontrol.admin.data.fake.FakeUsersDataSource
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.AuditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsersRepositoryImplTest {
    @Test
    fun `upsert user appends the new user to the fake stream`() = runTest {
        val store = FakeAdminStore()
        val behavior = FakeBehaviorPolicy(appConfig = testConfig(), logger = NoOpLogger)
        val repository = UsersRepositoryImpl(
            remote = FakeUsersDataSource(store, behavior),
            auditRepository = object : AuditRepository {
                override fun observeEvents(): Flow<List<AuditEvent>> = FakeAuditDataSource(store, behavior).observeEvents()
                override suspend fun appendEvent(event: AuditEvent): AppResult<Unit> = AppResult.Success(Unit)
            },
        )

        repository.upsertUser(
            User(
                id = "usr-900",
                fullName = "Nuevo Admin",
                email = "nuevo@oposicontrol.local",
                roleIds = listOf("support", "auditor"),
                isActive = false,
                createdAt = Clock.System.now(),
            ),
        )

        val users = repository.observeUsers().first()
        val saved = users.first { it.email == "nuevo@oposicontrol.local" }
        assertTrue(users.any { it.id == "usr-900" })
        assertEquals(listOf("support", "auditor"), saved.roleIds)
        assertFalse(saved.isActive)
    }

    private fun testConfig(): AppConfig {
        return AppConfig(
            environment = Environment.DEV,
            baseUrl = "https://dev.api.oposicontrol.local",
            supabaseUrl = "",
            supabasePublishableKey = "",
            supabaseBucketExamResources = "exam-resources",
            featureFlags = FeatureFlags(
                useFakeData = true,
                enableLogs = false,
                enableAnalytics = false,
                enableVerboseNetworkLogs = false,
                fakeLatencyMs = 0L,
                fakeErrorRate = 0.0,
            ),
            appName = "Test",
            appVersion = "1.0.0",
        )
    }
}
