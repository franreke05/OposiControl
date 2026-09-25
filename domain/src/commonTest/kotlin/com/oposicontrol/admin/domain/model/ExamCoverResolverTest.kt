package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExamCoverResolverTest {

    /** Los 19 códigos que siembra `20260917084439_seed_comunidades_autonomas`. */
    private val seededCodes = listOf(
        "ES-AN", "ES-AR", "ES-AS", "ES-IB", "ES-CN", "ES-CB", "ES-CL", "ES-CM", "ES-CT", "ES-VC",
        "ES-EX", "ES-GA", "ES-MD", "ES-MC", "ES-NC", "ES-PV", "ES-RI", "ES-CE", "ES-ML",
    )

    @Test
    fun rafagaUsesTheCommonCoverWhateverTheCommunity() {
        listOf(null, "ES-MD", "ES-AN", "ES-XX").forEach { community ->
            val cover = ExamCoverResolver.resolve("rafaga", community)

            assertEquals(ExamCoverKind.RAFAGA, cover.kind)
            assertEquals("cover_rafaga", cover.assetKey)
        }
    }

    @Test
    fun razonadoUsesTheCommonCoverWhateverTheCommunity() {
        listOf(null, "ES-MD", "ES-AN").forEach { community ->
            assertEquals("cover_razonado", ExamCoverResolver.resolve("razonado", community).assetKey)
        }
    }

    @Test
    fun theLegacyGuiadoAliasResolvesLikeRazonado() {
        assertEquals("cover_razonado", ExamCoverResolver.resolve("guiado", "ES-MD").assetKey)
    }

    @Test
    fun inmersivoUsesTheVectorOfItsCommunity() {
        assertEquals("vector_madrid", ExamCoverResolver.resolve("inmersivo", "ES-MD").assetKey)
        assertEquals("vector_andalucia", ExamCoverResolver.resolve("inmersivo", "ES-AN").assetKey)

        val cover = ExamCoverResolver.resolve("inmersivo", "ES-MD")
        assertEquals(ExamCoverKind.COMMUNITY_VECTOR, cover.kind)
        assertEquals("ES-MD", cover.communityCode)
        assertFalse(cover.isFallback)
    }

    @Test
    fun theSameCommunityGivesDifferentCoversPerType() {
        val covers = listOf("rafaga", "razonado", "inmersivo").map { ExamCoverResolver.resolve(it, "ES-MD").assetKey }

        assertEquals(listOf("cover_rafaga", "cover_razonado", "vector_madrid"), covers)
    }

    @Test
    fun everySeededCommunityHasItsOwnVector() {
        assertEquals(seededCodes.toSet(), ExamCoverResolver.COMMUNITY_VECTORS.keys)
        val assets = seededCodes.map { ExamCoverResolver.resolve("inmersivo", it).assetKey }
        assertEquals(19, assets.toSet().size, "dos comunidades comparten vector: $assets")
        assertTrue(assets.all { it.startsWith("vector_") })
    }

    @Test
    fun theCommunityCodeAndTheTypeAreNormalized() {
        assertEquals("vector_madrid", ExamCoverResolver.resolve("inmersivo", " es-md ").assetKey)
        assertEquals("vector_madrid", ExamCoverResolver.resolve(" Inmersivo ", "ES-MD").assetKey)
    }

    /** Provisional y sin decidir: nada de esto puede romper la pantalla. */
    @Test
    fun inmersivoWithoutAKnownCommunityAndUnknownTypesFallBackToTheGenericCover() {
        listOf(
            ExamCoverResolver.resolve("inmersivo", null),
            ExamCoverResolver.resolve("inmersivo", ""),
            ExamCoverResolver.resolve("inmersivo", "ES-XX"),
            ExamCoverResolver.resolve("marciano", "ES-MD"),
            ExamCoverResolver.resolve("", null),
        ).forEach { cover ->
            assertEquals(ExamCoverResolver.GENERIC_COVER, cover)
            assertTrue(cover.isFallback)
        }
    }
}
