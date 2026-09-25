package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.ContentExamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExamCoverArtworkTest {

    private fun community(name: String, slug: String) = Community(
        id = slug, name = name, code = "", slug = slug, isActive = true, sortOrder = 0,
        createdAt = EPOCH, updatedAt = EPOCH,
    )

    /** Las 19 comunidades del seed canónico (`comunidades_autonomas`). */
    private val seed = listOf(
        "Andalucía" to "andalucia" to ExamCoverArtwork.ANDALUCIA,
        "Aragón" to "aragon" to ExamCoverArtwork.ARAGON,
        "Principado de Asturias" to "asturias" to ExamCoverArtwork.ASTURIAS,
        "Illes Balears" to "illes-balears" to ExamCoverArtwork.BALEARES,
        "Canarias" to "canarias" to ExamCoverArtwork.CANARIAS,
        "Cantabria" to "cantabria" to ExamCoverArtwork.CANTABRIA,
        "Castilla y León" to "castilla-y-leon" to ExamCoverArtwork.CASTILLA_Y_LEON,
        "Castilla-La Mancha" to "castilla-la-mancha" to ExamCoverArtwork.CASTILLA_LA_MANCHA,
        "Cataluña" to "cataluna" to ExamCoverArtwork.BARCELONA,
        "Comunitat Valenciana" to "comunitat-valenciana" to ExamCoverArtwork.VALENCIA,
        "Extremadura" to "extremadura" to ExamCoverArtwork.EXTREMADURA,
        "Galicia" to "galicia" to ExamCoverArtwork.GALICIA,
        "Comunidad de Madrid" to "madrid" to ExamCoverArtwork.MADRID,
        "Región de Murcia" to "murcia" to ExamCoverArtwork.MURCIA,
        "Comunidad Foral de Navarra" to "navarra" to ExamCoverArtwork.NAVARRA,
        "País Vasco" to "pais-vasco" to ExamCoverArtwork.PAIS_VASCO,
        "La Rioja" to "la-rioja" to ExamCoverArtwork.LA_RIOJA,
        "Ceuta" to "ceuta" to ExamCoverArtwork.CEUTA_MELILLA,
        "Melilla" to "melilla" to ExamCoverArtwork.CEUTA_MELILLA,
    )

    @Test
    fun `cada comunidad del seed canonico tiene su ilustracion`() {
        seed.forEach { (nameAndSlug, expected) ->
            val (name, slug) = nameAndSlug
            assertEquals(
                expected,
                coverArtworkFor(ContentExamType.INMERSIVO, community(name, slug)),
                "la comunidad $name",
            )
        }
    }

    @Test
    fun `castilla la mancha y castilla y leon no se confunden`() {
        assertEquals(
            ExamCoverArtwork.CASTILLA_LA_MANCHA,
            coverArtworkFor(ContentExamType.INMERSIVO, community("Castilla-La Mancha", "x")),
        )
        assertEquals(
            ExamCoverArtwork.CASTILLA_Y_LEON,
            coverArtworkFor(ContentExamType.INMERSIVO, community("Castilla y León", "x")),
        )
    }

    @Test
    fun `si el slug no vale se usa el nombre`() {
        assertEquals(
            ExamCoverArtwork.GALICIA,
            coverArtworkFor(ContentExamType.INMERSIVO, community("Galicia", "comunidad-7")),
        )
    }

    @Test
    fun `un inmersivo sin comunidad o con una que el diseno no cubre no inventa ilustracion`() {
        assertNull(coverArtworkFor(ContentExamType.INMERSIVO, null))
        assertNull(coverArtworkFor(ContentExamType.INMERSIVO, community("Atlantida", "atlantida")))
    }

    @Test
    fun `rafaga y razonado llevan la suya aunque tengan comunidad`() {
        val madrid = community("Comunidad de Madrid", "madrid")
        assertEquals(ExamCoverArtwork.RAFAGAS, coverArtworkFor(ContentExamType.RAFAGA, null))
        assertEquals(ExamCoverArtwork.RAFAGAS, coverArtworkFor(ContentExamType.RAFAGA, madrid))
        assertEquals(ExamCoverArtwork.RAZONADOS, coverArtworkFor(ContentExamType.RAZONADO, madrid))
    }

    @Test
    fun `normalizar quita tildes y deja guiones`() {
        assertEquals("castilla-y-leon", normalize("Castilla y León"))
        assertEquals("pais-vasco", normalize("  País   Vasco "))
        assertEquals("cataluna", normalize("Cataluña"))
    }
}
