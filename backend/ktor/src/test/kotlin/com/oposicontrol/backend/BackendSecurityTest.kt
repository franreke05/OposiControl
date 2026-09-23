package com.oposicontrol.backend

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendSecurityTest {
    @Test
    fun developmentBypassesAreNeverAllowedInProduction() {
        assertEquals(true, allowsDevBypass("dev", enabled = true))
        assertEquals(true, allowsDevBypass("test", enabled = true))
        assertEquals(false, allowsDevBypass("prod", enabled = true))
        assertEquals(false, allowsDevBypass("production", enabled = true))
        assertEquals(false, allowsDevBypass("", enabled = true))
        assertEquals(false, allowsDevBypass("dev", enabled = false))
    }

    @Test
    fun staticTokenIsLimitedToLegacyDevelopmentSurfaces() {
        assertEquals(true, isLegacyStaticTokenPath("/content/noticias"))
        assertEquals(true, isLegacyStaticTokenPath("/admin/backend/restart"))
        assertEquals(false, isLegacyStaticTokenPath("/api/v1/admin/examenes"))
        assertEquals(false, isLegacyStaticTokenPath("/api/v1/app/home"))
    }

    @Test
    fun postgrestIdentifiersMustBeCanonicalUuids() {
        assertEquals(
            "123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174000".validatedUuid("id"),
        )
        assertFailsWith<IllegalArgumentException> { "not-a-uuid".validatedUuid("id") }
    }

    @Test
    fun newsKeepsBackgroundImageAndScheduleIndependent() {
        val input = NoticiaDto(
            id = NOTICIA_UUID,
            titulo = "Noticia",
            especialidad = ESPECIALIDAD_UUID,
            tipo = TIPO_UUID,
            fondoPath = "background.png",
            publicarDesde = "2026-07-01T08:00:00Z",
            texto = "Texto",
            imagenPath = "cover.png",
        )

        val row = input.toSupabaseRow({ it }, { it })
        assertEquals("background.png", row.backgroundPath)
        assertEquals("cover.png", row.imagenPath)
        assertEquals("2026-07-01T08:00:00Z", row.publicarDesde)

        val restored = row.toDto()
        assertEquals(input.fondoPath, restored.fondoPath)
        assertEquals(input.imagenPath, restored.imagenPath)
        assertEquals(input.publicarDesde, restored.publicarDesde)
    }

    @Test
    fun newsCatalogIdsAreUuidsAndSurviveTheRoundTrip() {
        // La fila declaraba `Int` contra una base cuyas claves son `uuid`:
        // `especialidad_id` no podia resolver y guardar fallaba siempre.
        val row = NoticiaDto(
            titulo = "Noticia",
            especialidad = ESPECIALIDAD_UUID,
            tipo = TIPO_UUID,
            texto = "Texto",
        ).toSupabaseRow({ it }, { it })

        assertEquals(ESPECIALIDAD_UUID, row.especialidadId)
        assertEquals(TIPO_UUID, row.tipoNoticiaId)
        // Sin id, PostgREST lo omite y Postgres aplica `gen_random_uuid()`.
        assertNull(row.id)
        assertEquals(ESPECIALIDAD_UUID, row.toDto().especialidad)
    }

    @Test
    fun newsLayersTravelInTheirOwnColumnWithNormalizedOrder() {
        val input = NoticiaDto(
            titulo = "Noticia",
            especialidad = ESPECIALIDAD_UUID,
            tipo = TIPO_UUID,
            texto = "Cuerpo editorial",
            capas = listOf(
                NoticiaLayerDto(id = "b", nombre = "Fondo", contenido = "img", x = 0, y = 0, orden = 5),
                NoticiaLayerDto(id = "a", nombre = "Titulo", contenido = "Hola", x = 48, y = 142, width = 360, height = 44, orden = 2),
            ),
        )

        val row = input.toSupabaseRow({ it }, { it })

        // El cuerpo es solo el cuerpo: la composicion ya no se serializa dentro
        // de `texto` con un prefijo, vive en la columna `capas`.
        assertEquals("Cuerpo editorial", row.texto)
        assertEquals(2, row.capas.size)
        // Los huecos de orden (2, 5) se normalizan a indices contiguos.
        assertEquals(listOf(0, 1), row.capas.map { it.orden })
        assertEquals("Titulo", row.capas.first().nombre)

        // El frame sobrevive el ida y vuelta, que es lo que la pantalla edita.
        val restored = row.toDto().capas.first()
        assertEquals(48, restored.x)
        assertEquals(142, restored.y)
        assertEquals(360, restored.width)
        assertEquals(44, restored.height)
    }

    @Test
    fun newsLayerTypographyTravelsInsideTheLayersColumn() {
        val input = NoticiaDto(
            titulo = "Noticia",
            especialidad = ESPECIALIDAD_UUID,
            tipo = TIPO_UUID,
            texto = "Cuerpo editorial",
            capas = listOf(
                NoticiaLayerDto(id = "a", nombre = "Titulo", contenido = "Hola", orden = 0, fontSizeSp = 42, fontWeight = "bold"),
                NoticiaLayerDto(id = "b", nombre = "Texto", contenido = "Cuerpo", orden = 1),
            ),
        )

        val row = input.toSupabaseRow({ it }, { it })
        assertEquals(42, row.capas.first().fontSizeSp)
        assertEquals("bold", row.capas.first().fontWeight)

        val restored = row.toDto().capas
        assertEquals(42, restored.first().fontSizeSp)
        assertEquals("bold", restored.first().fontWeight)
        // `0` no es un tamano, es «derivado del tipo de capa»: el lienzo lo
        // resuelve por `tipo`. Debe sobrevivir el viaje SIN convertirse en un
        // numero, o la capa se congelaria con el tamano de hoy.
        assertEquals(0, restored.last().fontSizeSp)
        // `""` es el centinela de «derivado»: «normal» es una elección explícita.
        assertEquals("", restored.last().fontWeight)
    }

    @Test
    fun newsLayersSavedBeforeTypographyDeserializeAsDerived() {
        // Una fila tal y como esta HOY en `capas jsonb`: sin `fontSizeSp` ni
        // `fontWeight`. `capas` es jsonb, asi que los campos nuevos entran sin
        // migracion, pero solo si lo ya guardado se sigue leyendo sin fallar.
        val legacyRow = """
            {
              "id": "$NOTICIA_UUID",
              "titulo": "Noticia",
              "especialidad_id": "$ESPECIALIDAD_UUID",
              "tipo_noticia_id": "$TIPO_UUID",
              "texto": "Cuerpo editorial",
              "capas": [
                {"id":"a","nombre":"Titulo","tipo":"titulo","contenido":"Hola","x":48,"y":142,"width":360,"height":44,"opacity":100,"visible":true,"orden":0}
              ]
            }
        """.trimIndent()

        val layer = newsGatewayJson.decodeFromString<SupabaseNoticiaRow>(legacyRow).toDto().capas.single()

        // Derivada, que es exactamente como se pintaba antes de existir el campo.
        assertEquals(0, layer.fontSizeSp)
        assertEquals("", layer.fontWeight)
        // Y el resto de la capa intacto: el cambio es estrictamente aditivo.
        assertEquals(48, layer.x)
        assertEquals(142, layer.y)
        assertEquals(360, layer.width)
        assertEquals(44, layer.height)
        assertEquals("Titulo", layer.nombre)
    }

    @Test
    fun newsListLimitIsBoundedInsteadOfReadingTheWholeTable() {
        // Sin parametro, un techo honesto en vez de "todo".
        assertEquals(DEFAULT_NEWS_PAGE_SIZE, sanitizeNewsLimit(null))
        assertEquals(DEFAULT_NEWS_PAGE_SIZE, sanitizeNewsLimit("no-es-un-numero"))
        assertEquals(50, sanitizeNewsLimit("50"))
        // Un `limit=0` devolveria una lista vacia sin decir por que: se corrige a 1.
        assertEquals(1, sanitizeNewsLimit("0"))
        assertEquals(1, sanitizeNewsLimit("-10"))
        // Y nadie puede pedir la tabla entera disfrazandolo de limite.
        assertEquals(500, sanitizeNewsLimit("100000"))

        assertEquals(0, sanitizeNewsOffset(null))
        assertEquals(0, sanitizeNewsOffset("-5"))
        assertEquals(40, sanitizeNewsOffset("40"))
    }

    @Test
    fun onlyCanonicalUuidsAreAcceptedAsIdentifiers() {
        assertTrue(ESPECIALIDAD_UUID.isCanonicalUuid())
        // "sin-seleccionar" llegaria a la FK como error de base de datos en vez
        // de como un mensaje que el panel pueda mostrar.
        assertFalse("sin-seleccionar".isCanonicalUuid())
        assertFalse("7".isCanonicalUuid())
        assertFalse("".isCanonicalUuid())
    }
}

/** El mismo `Json` que `Application.module()` le entrega a `SupabaseNewsGateway`. */
private val newsGatewayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    prettyPrint = false
}

private const val NOTICIA_UUID = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"
private const val ESPECIALIDAD_UUID = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
private const val TIPO_UUID = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
