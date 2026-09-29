package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.domain.model.AccountStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsersWireShapesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = "11111111-1111-4111-8111-111111111111"
    private val id2 = "22222222-2222-4222-8222-222222222222"

    private fun list(body: String) = json.decodeFromString<AdminUsersV1Response>(body).toUserDtos()
    private fun one(body: String) = json.decodeFromString<AdminUserV1Dto>(body).toUserDto()

    @Test fun `list merges canonical items with legacy data`() {
        val users = list(
            """{"data":[{"id":"$id","fullName":"Ana","email":"a@x.es","roleIds":[],"isActive":true,"createdAt":"2026-01-01T00:00:00Z","ticketsBalance":3}],
               "items":[{"id":"$id","username":"ana","fullName":"Ana","email":"a@x.es","roleIds":[],"accountStatus":"eliminacion_pendiente","createdAt":"2026-01-01T00:00:00Z","ticketsBalance":3}],
               "total":1,"limit":20,"offset":0}""",
        ).map { it.toDomain() }
        val u = users.single()
        assertEquals(AccountStatus.DELETION_PENDING, u.accountStatus)
        assertEquals("ana", u.username)
        assertTrue(u.isActive, "isActive keeps its legacy (!es_invitado) meaning")
        assertEquals(3, u.ticketsBalance)
    }

    @Test fun `legacy only list never reports active`() {
        val u = list("""{"data":[{"id":"$id","fullName":"Ana","email":"a@x.es","roleIds":[],"isActive":true,"createdAt":"2026-01-01T00:00:00Z"}]}""").single().toDomain()
        assertEquals(AccountStatus.UNKNOWN, u.accountStatus)
        assertTrue(u.isActive)
    }

    @Test fun `items only list survives data removal`() {
        val u = list("""{"items":[{"id":"$id","email":null,"roleIds":[],"accountStatus":"bloqueada","createdAt":"2026-01-01T00:00:00Z","ticketsBalance":null}]}""").single().toDomain()
        assertEquals(AccountStatus.BLOCKED, u.accountStatus)
        assertFalse(u.isActive)
        assertEquals("", u.email)
        assertEquals(0, u.ticketsBalance)
    }

    @Test fun `row present only in data stays unknown next to a listed one`() {
        val users = list(
            """{"data":[{"id":"$id","isActive":true},{"id":"$id2","isActive":true}],
               "items":[{"id":"$id","roleIds":[],"accountStatus":"activa","createdAt":"x"}]}""",
        )
        assertEquals(AccountStatus.ACTIVE.wire, users[0].accountStatus)
        assertEquals(AccountStatus.UNKNOWN, users[1].toDomain().accountStatus)
    }

    @Test fun `unrecognised status is unknown not active`() {
        assertEquals(AccountStatus.UNKNOWN, AccountStatus.fromWire("suspendida"))
        assertEquals(AccountStatus.UNKNOWN, AccountStatus.fromWire(null))
        assertEquals(AccountStatus.UNKNOWN, AccountStatus.fromWire("ACTIVA"))
    }

    @Test fun `detail canonical with legacy data wrapper`() {
        val u = one(
            """{"id":"$id","username":"ana","email":null,"roleIds":["r"],"accountStatus":"activa","onboardingCompleted":true,"createdAt":"2026-01-01T00:00:00Z","authProviders":["google"],
               "data":{"id":"$id","fullName":"Ana","email":"a@x.es","roleIds":["r"],"isActive":false,"createdAt":"2026-01-01T00:00:00Z","ticketsBalance":5}}""",
        ).toDomain()
        assertEquals(AccountStatus.ACTIVE, u.accountStatus)
        assertEquals(listOf("google"), u.authProviders)
        assertEquals(true, u.onboardingCompleted)
        assertEquals("a@x.es", u.email, "null canonical email falls back to legacy")
        assertEquals("Ana", u.fullName)
        assertFalse(u.isActive)
        assertEquals(5, u.ticketsBalance)
    }

    @Test fun `detail legacy only wrapper`() {
        val u = one("""{"data":{"id":"$id","fullName":"Ana","email":"a@x.es","roleIds":[],"isActive":true,"createdAt":"2026-01-01T00:00:00Z"}}""").toDomain()
        assertEquals(id, u.id)
        assertEquals(AccountStatus.UNKNOWN, u.accountStatus)
        assertNull(u.onboardingCompleted)
    }

    @Test fun `detail canonical without data wrapper`() {
        val u = one("""{"id":"$id","roleIds":[],"accountStatus":"bloqueada","onboardingCompleted":false,"createdAt":"2026-01-01T00:00:00Z","authProviders":[]}""").toDomain()
        assertEquals(AccountStatus.BLOCKED, u.accountStatus)
        assertFalse(u.isActive)
    }

    @Test fun `detail dossier profile carries account state`() {
        val env = json.decodeFromString<UserDetailEnvelope>(
            """{"data":{"perfil":{"usuario_id":"$id","id":"$id","accountStatus":"eliminacion_pendiente","estado_cuenta":"activa","authProviders":["password"],"roleIds":[],"onboardingCompleted":true,"createdAt":"x"},"seccionesNoDisponibles":[]}}""",
        )
        val p = env.data.toDomain().profile
        assertEquals(AccountStatus.DELETION_PENDING, p.accountStatus)
        assertEquals(listOf("password"), p.authProviders)
        val legacy = json.decodeFromString<UserDetailEnvelope>("""{"data":{"perfil":{"usuario_id":"$id","estado_cuenta":"bloqueada"}}}""").data.toDomain().profile
        assertEquals(AccountStatus.BLOCKED, legacy.accountStatus)
        val none = json.decodeFromString<UserDetailEnvelope>("""{"data":{"perfil":{"usuario_id":"$id"}}}""").data.toDomain().profile
        assertEquals(AccountStatus.UNKNOWN, none.accountStatus)
    }
}
