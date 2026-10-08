package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** El lector legacy de noticias no cae por una fila con `capas` de forma nueva (escrita por la ruta canonica). */
class NewsLegacyTolerantReadTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun row(id: String, capas: String) =
        """{"id":"$id","titulo":"T","tipo_fondo":"A","especialidad_id":"e","texto":"x","capas":$capas,"tipo_noticia_id":"t","estado":"draft"}"""

    @Test
    fun aRowWithMistypedLayersIsReadWithoutLayersAndTheRestOfThePageSurvives() {
        val page = "[" + row("1", """[{"x":"no-es-un-numero","tipo":{"anidado":true}}]""") + "," + row("2", "[]") + "]"
        val rows = decodeNoticiaRowsTolerant(json, page)
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertEquals(0, rows[0].capas.size)
        assertEquals("T", rows[0].titulo)
    }

    @Test
    fun aRowBrokenOutsideLayersStillFails() {
        val page = """[{"id":"1","tipo_fondo":"A","capas":[]}]"""
        assertFailsWith<Exception> { decodeNoticiaRowsTolerant(json, page) }
    }
}
