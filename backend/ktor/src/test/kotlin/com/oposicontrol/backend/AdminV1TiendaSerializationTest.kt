package com.oposicontrol.backend

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the mechanism behind the final whole-branch review's
 * Store DTO wire-contract defect: the response [Json] configured in
 * Application.kt's `Application.module()` does not set `encodeDefaults =
 * true`, so any field whose value equals its Kotlin default is silently
 * OMITTED from the JSON response -- even when that value is not null and
 * was explicitly set. Task 6's 4 Store DTOs (AdminArticuloTiendaDto and
 * siblings, all in AdminV1Routes.kt) default every field, so an ordinary
 * row where a field happens to equal its default triggers this.
 *
 * This is a pure serialization test: no Ktor server, no [testApplication].
 * It exists so a future change to `encodeDefaults` (in either direction)
 * cannot silently regress this mechanism again without a test noticing.
 */
class AdminV1TiendaSerializationTest {
    // Exact same Json config as Application.kt's `Application.module()`
    // response Json (see the `val json = Json { ... }` at the top of
    // `fun Application.module()`).
    private val responseJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun responseJson_omitsADefaultedFieldEvenWhenItsValueWasExplicitlySet() {
        val dto = AdminArticuloTiendaDto(
            id = 1,
            nombre = "X",
            tipoArticulo = "protector",
            precioTickets = 50,
            activo = true,
        )

        val encoded = responseJson.encodeToString(dto)

        // The mechanism: activo == true is the field's default, so it is
        // dropped from the wire payload entirely -- not even `"activo":true`
        // is present. A client-side DTO field with no default for `activo`
        // would fail to deserialize this exact payload.
        assertFalse(
            encoded.contains("\"activo\""),
            "Expected encodeDefaults=false to omit the defaulted 'activo' field, but it was present: $encoded",
        )
        // The other, non-defaulted-value fields are still present as a sanity check.
        assertTrue(encoded.contains("\"nombre\":\"X\""), "Expected 'nombre' to be present: $encoded")
        assertTrue(encoded.contains("\"precio_tickets\":50"), "Expected 'precio_tickets' to be present: $encoded")
    }
}
