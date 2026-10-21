package com.oposicontrol.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** CERT-LOAD-1000 P1: cache corta del estado de cuenta (CanonicalIdentity.kt). Aislamiento, TTL, invalidacion, solo activas. */
class CachingCanonicalProfileDirectoryTest {
    private val alice = CanonicalUserId.fromJwtSubject("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val bob = CanonicalUserId.fromJwtSubject("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

    private class Db : CanonicalProfileDirectory {
        val rows = ConcurrentHashMap<String, CanonicalUserIdentity>()
        val reads = CopyOnWriteArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun find(userId: CanonicalUserId): CanonicalUserIdentity? {
            reads += userId.value
            val row = rows[userId.value]
            gate?.await()
            return row
        }
    }

    private fun active(id: CanonicalUserId) = CanonicalUserIdentity(id, AccountStatus.ACTIVA, onboardingCompleted = true)

    private class Clock(var nanos: Long = 1_000_000_000L)

    private fun cache(db: Db, clock: Clock, ttl: Long = 30_000) =
        CachingCanonicalProfileDirectory(db, ttl, nanoTime = { clock.nanos }, onResult = {})

    @Test
    fun eachUserGetsOnlyHisOwnProfileFromTheCache() = runBlocking<Unit> {
        val db = Db().apply { rows[alice.value] = active(alice); rows[bob.value] = active(bob) }
        val c = cache(db, Clock())
        assertEquals(alice, c.find(alice)!!.userId)
        assertEquals(bob, c.find(bob)!!.userId)
        repeat(3) {
            assertEquals(alice, c.find(alice)!!.userId)
            assertEquals(bob, c.find(bob)!!.userId)
        }
        assertEquals(listOf(alice.value, bob.value), db.reads, "una lectura por usuario; el resto, cache por usuario")
    }

    @Test
    fun aRowOfAnotherUserIsNeverCached() = runBlocking<Unit> {
        val db = Db().apply { rows[alice.value] = active(bob) } // directorio defectuoso: devuelve la fila de otro
        val c = cache(db, Clock())
        c.find(alice); c.find(alice)
        assertEquals(2, db.reads.size, "una identidad cuyo usuario no es la clave no se guarda")
        assertEquals(0, c.size())
    }

    @Test
    fun entriesExpireAfterTheTtl() = runBlocking<Unit> {
        val clock = Clock()
        val db = Db().apply { rows[alice.value] = active(alice) }
        val c = cache(db, clock, ttl = 30_000)
        c.find(alice)
        clock.nanos += TimeUnit.MILLISECONDS.toNanos(29_999)
        c.find(alice)
        assertEquals(1, db.reads.size)
        // Bloqueada por control (otro proceso, sin invalidacion): se ve como mucho al vencer el TTL.
        db.rows[alice.value] = CanonicalUserIdentity(alice, AccountStatus.BLOQUEADA, true)
        clock.nanos += TimeUnit.MILLISECONDS.toNanos(2)
        assertEquals(AccountStatus.BLOQUEADA, c.find(alice)!!.accountStatus)
        assertEquals(2, db.reads.size)
    }

    @Test
    fun onlyActiveOnboardedAccountsAreCachedSoUnblockAndOnboardingAreSeenAtOnce() = runBlocking<Unit> {
        val db = Db()
        val c = cache(db, Clock())
        for (status in listOf(AccountStatus.BLOQUEADA, AccountStatus.ELIMINACION_PENDIENTE, AccountStatus.UNRECOGNIZED)) {
            db.rows[alice.value] = CanonicalUserIdentity(alice, status, true)
            c.find(alice); c.find(alice)
        }
        db.rows[alice.value] = CanonicalUserIdentity(alice, AccountStatus.ACTIVA, onboardingCompleted = false)
        c.find(alice); c.find(alice)
        db.rows.remove(alice.value)
        assertNull(c.find(alice)); assertNull(c.find(alice))
        assertEquals(10, db.reads.size, "ningun estado distinto de activa+onboarding se cachea (ni la ausencia de fila)")
        assertEquals(0, c.size())
    }

    @Test
    fun aProfileWriteInvalidatesAndTheBlockingGateDeniesAgain() = runBlocking<Unit> {
        val db = Db().apply { rows[alice.value] = active(alice); rows[bob.value] = active(bob) }
        val c = cache(db, Clock())
        c.find(alice); c.find(bob)
        db.rows[alice.value] = CanonicalUserIdentity(alice, AccountStatus.ELIMINACION_PENDIENTE, true)
        CanonicalProfileCacheInvalidation.invalidate(alice) // lo que hacen AccountDeletionService y las escrituras de perfil
        val resolved = c.find(alice)!!
        assertEquals(AccountStatus.ELIMINACION_PENDIENTE, resolved.accountStatus)
        assertFailsWith<BackendDomainException> { resolved.requireActive() }
        c.find(bob)
        assertEquals(listOf(alice.value, bob.value, alice.value), db.reads, "solo se invalida el usuario escrito")
    }

    @Test
    fun anInvalidationDuringAnInFlightReadPreventsCachingTheStaleRow() = runBlocking<Unit> {
        val db = Db().apply { rows[alice.value] = active(alice) }
        val c = cache(db, Clock())
        db.gate = CompletableDeferred()
        val inFlight = async { c.find(alice) } // lee "activa" y espera
        while (db.reads.isEmpty()) kotlinx.coroutines.yield()
        db.rows[alice.value] = CanonicalUserIdentity(alice, AccountStatus.BLOQUEADA, true)
        c.invalidate(alice) // la escritura llega mientras la lectura vieja sigue en vuelo
        db.gate!!.complete(Unit)
        inFlight.await()
        db.gate = null
        assertEquals(AccountStatus.BLOQUEADA, c.find(alice)!!.accountStatus, "la lectura anterior a la escritura no quedo guardada")
    }

    @Test
    fun ttlZeroDisablesTheCacheAndTheTtlIsBounded() = runBlocking<Unit> {
        val db = Db().apply { rows[alice.value] = active(alice) }
        val c = cache(db, Clock(), ttl = 0)
        c.find(alice); c.find(alice)
        assertEquals(2, db.reads.size)
        assertFailsWith<IllegalArgumentException> { CachingCanonicalProfileDirectory(db, 120_001) }
        assertEquals(30_000, CachingCanonicalProfileDirectory.ttlFromEnvironment { null })
        assertEquals(0, CachingCanonicalProfileDirectory.ttlFromEnvironment { "0" })
        assertFailsWith<IllegalStateException> { CachingCanonicalProfileDirectory.ttlFromEnvironment { "600000" } }
        assertFailsWith<IllegalStateException> { CachingCanonicalProfileDirectory.ttlFromEnvironment { "abc" } }
    }

    @Test
    fun theCacheIsBounded() = runBlocking<Unit> {
        val db = Db()
        val c = CachingCanonicalProfileDirectory(db, 30_000, maxEntries = 3, nanoTime = { 1L }, onResult = {})
        val users = (1..5).map { CanonicalUserId.fromJwtSubject("cccccccc-cccc-4ccc-8ccc-00000000000$it") }
        users.forEach { db.rows[it.value] = active(it); c.find(it) }
        assertEquals(3, c.size())
    }
}
