package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

class PublicReadCacheTest {
    @Test
    fun `a repeated public read reaches the database once per window`() {
        var now = 0L
        var loads = 0
        val cache = PublicReadCache(ttlMillis = 30_000, clock = { now })
        repeat(1_000) { assertEquals("catalogo", cache.get("examenes") { loads++; "catalogo" }) }
        assertEquals(1, loads, "mil peticiones iguales son una lectura")

        now = 29_999
        cache.get("examenes") { loads++; "catalogo" }
        assertEquals(1, loads)

        now = 30_000
        assertEquals("nuevo", cache.get("examenes") { loads++; "nuevo" })
        assertEquals(2, loads, "al caducar se vuelve a leer")
    }

    @Test
    fun `different keys are independent and a null result is cached too`() {
        var loads = 0
        val cache = PublicReadCache(clock = { 0L })
        assertEquals(null, cache.get<String?>("examen:a") { loads++; null })
        assertEquals(null, cache.get<String?>("examen:a") { loads++; null })
        assertEquals("b", cache.get("examen:b") { loads++; "b" })
        assertEquals(2, loads, "un examen inexistente tampoco se vuelve a buscar en cada peticion")
    }

    @Test
    fun `random identifiers cannot grow the cache without bound`() {
        var loads = 0
        val cache = PublicReadCache(maxEntries = 8, clock = { 0L })
        repeat(100) { index -> cache.get("examen:$index") { loads++; index } }
        assertEquals(100, loads)
        // Sigue funcionando tras vaciarse: la entrada mas reciente esta.
        cache.get("examen:99") { loads++; 99 }
        assertEquals(100, loads)
    }
}
