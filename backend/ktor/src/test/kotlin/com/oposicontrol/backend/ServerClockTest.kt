package com.oposicontrol.backend

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** El reloj del servidor: hora de la base en la ultima sincronizacion + tiempo monotono transcurrido; falla cerrado si no puede sincronizar. */
class ServerClockTest {
    private var nanos = 0L
    private var dbTime = Instant.parse("2031-03-01T12:00:00Z")
    private var calls = 0
    private var failing = false

    private val clock = ServerClock(
        source = DatabaseClockSource {
            calls++
            if (failing) throw IllegalStateException("db down")
            dbTime
        },
        nanoTime = { nanos },
    )

    private fun advanceSeconds(seconds: Long) {
        nanos += seconds * 1_000_000_000L
        dbTime = dbTime.plusSeconds(seconds)
    }

    @Test
    fun theFirstReadSynchronisesWithTheDatabaseAndDoesNotUseTheJvmClock() {
        assertEquals(Instant.parse("2031-03-01T12:00:00Z"), clock.now())
        assertEquals(1, calls)
    }

    @Test
    fun betweenSynchronisationsTimeAdvancesWithTheMonotonicClockWithoutCallingTheDatabase() {
        clock.now()
        nanos += 30_000_000_000L
        assertEquals(Instant.parse("2031-03-01T12:00:30Z"), clock.now())
        assertEquals(1, calls)
    }

    @Test
    fun itResynchronisesAfterAMinute() {
        clock.now()
        advanceSeconds(61)
        assertEquals(Instant.parse("2031-03-01T12:01:01Z"), clock.now())
        assertEquals(2, calls)
    }

    @Test
    fun aSkewedJvmWallClockCannotChangeTheAnswer() {
        // La fuente dice 12:00 aunque la JVM creyera otra cosa: el reloj nunca consulta Instant.now().
        assertTrue(kotlin.math.abs(clock.now().epochSecond - Instant.now().epochSecond) > 3600)
    }

    @Test
    fun aFailedResyncWithAFreshBaseKeepsExtrapolating() {
        clock.now()
        advanceSeconds(120)
        failing = true
        assertEquals(Instant.parse("2031-03-01T12:02:00Z"), clock.now())
    }

    @Test
    fun aFailedResyncWithAStaleBaseFailsClosedAs503() {
        clock.now()
        advanceSeconds(6 * 60)
        failing = true
        val error = assertFailsWith<BackendDomainException> { clock.now() }
        assertEquals("UPSTREAM_UNAVAILABLE", error.errorCode)
    }

    @Test
    fun withoutAnyBaseAndNoDatabaseItFailsClosed() {
        failing = true
        assertFailsWith<BackendDomainException> { clock.now() }
    }
}
