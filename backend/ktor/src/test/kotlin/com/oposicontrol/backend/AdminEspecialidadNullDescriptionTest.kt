package com.oposicontrol.backend

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E 2026-10-20 (stack aislado oposi-control-e2e): `especialidades.descripcion` es nullable y PostgREST devuelve
 * `"descripcion":null`; el DTO exigía String y `GET /api/v1/admin/especialidades` daba 502, lo que hacía fallar en el panel
 * el guardado de un examen que el servidor YA había creado (el operador reintenta y duplica).
 */
class AdminEspecialidadNullDescriptionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aNullDescriptionFromTheDatabaseIsReadAsEmpty() {
        val rows = json.decodeFromString<List<AdminEspecialidadDto>>(
            """[{"id":"e1","codigo":"LOC","nombre":"Local","slug":"local","descripcion":null,"activa":true,"posicion":1}]""",
        )
        assertEquals("", rows.single().descripcion)
    }

    @Test
    fun theContractTowardsThePanelStaysAString() {
        val dto = json.decodeFromString<AdminEspecialidadDto>("""{"nombre":"Local","descripcion":"Texto"}""")
        assertEquals("Texto", dto.descripcion)
        val out = Json.encodeToString(AdminEspecialidadDto(nombre = "Local"))
        assertTrue(out.contains("\"descripcion\":\"\"") || !out.contains("descripcion"), out)
        assertTrue(!out.contains("null"), out)
    }
}
