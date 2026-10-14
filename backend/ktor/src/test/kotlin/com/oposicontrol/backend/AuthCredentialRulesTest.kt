package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las reglas UNICAS de correo y contrasena del plano de auth (plan del propietario 2026-10-14, puntos 5 y 6): una sola
 * funcion de correo canonico ([canonicalAuthEmail]) y una sola regla de contrasena nueva ([isAcceptableNewPassword]).
 */
class AuthCredentialRulesTest {

    // ------------------------------------------------------------------------------------------------ correo canonico

    @Test
    fun asciiLettersAreLowercasedAndNothingElseChanges() {
        assertEquals("ada@example.com", canonicalAuthEmail("ada@example.com"))
        assertEquals("ada.lovelace+opos@example.com", canonicalAuthEmail("Ada.Lovelace+OPOS@Example.COM"))
        assertEquals("a_b-c.9@sub.example.io", canonicalAuthEmail("A_B-C.9@SUB.example.IO"))
    }

    @Test
    fun unicodeWhitespaceAtTheEndsIsStripped() {
        assertEquals("ada@example.com", canonicalAuthEmail("  ada@example.com  "))
        assertEquals("ada@example.com", canonicalAuthEmail(" Ada@Example.com "))
        assertEquals("ada@example.com", canonicalAuthEmail(" ada@example.com "))
        assertEquals("ada@example.com", canonicalAuthEmail("\tADA@example.com\t\n"))
        assertEquals("ada@example.com", canonicalAuthEmail("　ada@example.com "))
    }

    @Test
    fun anyWhitespaceOrControlInsideIsRejected() {
        for (raw in listOf(
            "a da@example.com",
            "a da@example.com",
            "a da@example.com",
            "a\tda@example.com",
            "ada@exa\nmple.com",
            "ada\u0000@example.com",
            "ada@example.com\u0007",
            "\u001Bada@example.com",
            "ada@example.com\u0085x",
        )) {
            assertNull(canonicalAuthEmail(raw), raw.map { it.code }.toString())
        }
    }

    /** `İ` (U+0130): `String.lowercase()` de Kotlin la convierte en `i̇` (dos puntos de codigo). Aqui se queda tal cual. */
    @Test
    fun nonAsciiLettersAreKeptAsTheyAre() {
        assertEquals("İnfo@example.com", canonicalAuthEmail("İnfo@Example.com"))
        assertFalse(canonicalAuthEmail("İnfo@example.com") == "İnfo@example.com".lowercase(), "control: el minusculado Unicode SI la cambia")
        assertEquals("ñandú@example.es", canonicalAuthEmail("ñandú@EXAMPLE.es"))
        assertEquals("ÉMILE@example.fr".replace("MILE", "mile"), canonicalAuthEmail("ÉMILE@example.fr"))
        assertEquals("ß@example.de", canonicalAuthEmail("ß@example.de"))
    }

    @Test
    fun blankBecomesEmptyAndIsReportedAsBlank() {
        assertEquals("", canonicalAuthEmail("   "))
        assertEquals("", canonicalAuthEmail("  "))
        assertEquals(OposiBotsValidationIssue.BLANK_IDENTIFIER, authEmailIssue("   "))
        val blank = assertFailsWith<OposiBotsAuthProblem> { requireCanonicalAuthEmail("") }
        assertEquals(setOf(OposiBotsValidationIssue.BLANK_IDENTIFIER), blank.issues)
    }

    @Test
    fun theShapeIsCheckedOnTheCanonicalValue() {
        assertEquals("ada@example.com", requireCanonicalAuthEmail(" Ada@Example.com "))
        assertNull(authEmailIssue(" Ada@Example.com"))
        for (raw in listOf("sin-arroba", "a@b", "@example.com", "ada@", "a b@example.com", "a".repeat(315) + "@x.com")) {
            assertEquals(OposiBotsValidationIssue.INVALID_IDENTIFIER, authEmailIssue(raw), raw)
            val problem = assertFailsWith<OposiBotsAuthProblem> { requireCanonicalAuthEmail(raw) }
            assertEquals(setOf(OposiBotsValidationIssue.INVALID_IDENTIFIER), problem.issues, raw)
        }
        assertTrue(isValidAuthEmailShape("a@b.c"))
    }

    /** La misma funcion alimenta los seudonimos de log: dos escrituras del mismo correo dan la misma referencia. */
    @Test
    fun theLogReferencesFollowTheCanonicalForm() {
        val configuration = testCanonicalConfiguration()
        assertEquals(configuration.authLogEmailRef("ada@example.com"), configuration.authLogEmailRef(" ADA@example.com "))
        assertEquals(adminAccessEmailRef("ada@example.com"), adminAccessEmailRef(" Ada@EXAMPLE.com\t"))
        assertFalse(configuration.authLogEmailRef("İnfo@example.com") == configuration.authLogEmailRef("i̇nfo@example.com"))
    }

    // ------------------------------------------------------------------------------------------------ contrasena nueva

    @Test
    fun newPasswordsAreTwelveToSeventyTwoPrintableAsciiCharacters() {
        for (good in listOf(
            "a".repeat(12),
            "a".repeat(72),
            " ".repeat(11) + "~",
            "~".repeat(72),
            "S3creta-Larga-9731",
            "!\"#$%&'()*+,-./0123456789:;<=>?@AZ[\\]^_`az{|}~",
        )) {
            assertTrue(good.isAcceptableNewPassword(), "${good.length}: $good")
        }
        for (bad in listOf(
            "",
            "a".repeat(11),
            "a".repeat(73),
            "a".repeat(128),
            "ñ".repeat(12),
            "S3creta-Larga-ñ",
            "S3creta-Larga-😀",
            "S3creta-Larga- x",
            "S3creta\tLarga-9",
            "S3creta-Larga-9\n",
            "S3creta-Larga-\u007F",
            "S3creta-Larga-\u0000",
        )) {
            assertFalse(bad.isAcceptableNewPassword(), "${bad.length}: ${bad.map { it.code }}")
        }
        assertEquals(72, AUTH_NEW_PASSWORD_MAX_LENGTH)
        assertEquals(12, AUTH_NEW_PASSWORD_MIN_LENGTH)
    }
}
