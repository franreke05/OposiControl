package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RazonadoMarkupTest {
    private fun parse(s: String) = RazonadoMarkup.parse(s)
    private fun codes(r: RazonadoMarkup.Result) = r.issues.map { it.code }

    @Test
    fun plainTextIsOneParagraph() {
        val r = parse("Hola mundo")
        assertEquals(1, r.document.size)
        assertEquals("Hola mundo", r.plainText)
        assertFalse(r.hasErrors)
    }

    @Test
    fun theFourDoubleMarkersMapToTheirStyles() {
        val doc = parse("**a** __b__ ++c++ ~~d~~").document.single().inlines
        val styles = doc.filterIsInstance<MarkupInline.Styled>().map { it.style }
        assertEquals(listOf(MarkupStyle.BOLD, MarkupStyle.ITALIC, MarkupStyle.UNDERLINE, MarkupStyle.STRIKE), styles)
    }

    @Test
    fun singleMarkersAreLiteralTextSoCodesAndMathSurvive() {
        val r = parse("codigo_examen = a*b + c~d")
        assertEquals("codigo_examen = a*b + c~d", r.plainText)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun stylesNestWhenProperlyClosed() {
        val r = parse("**a __b__ c**")
        val bold = assertIs<MarkupInline.Styled>(r.document.single().inlines.single())
        assertEquals(MarkupStyle.BOLD, bold.style)
        assertTrue(bold.children.any { it is MarkupInline.Styled && it.style == MarkupStyle.ITALIC })
        assertFalse(r.hasErrors)
    }

    @Test
    fun crossedMarkersAreAnErrorAndTheTextSurvives() {
        val r = parse("**a __b** c__")
        assertTrue(r.hasErrors)
        assertTrue("BAD_NESTING" in codes(r))
        assertTrue(r.plainText.contains("a") && r.plainText.contains("c"))
    }

    @Test
    fun anUnclosedMarkerIsAnErrorForTheHumanReview() {
        val r = parse("**sin cerrar")
        assertTrue("UNCLOSED" in codes(r))
        assertEquals("sin cerrar", r.plainText)
    }

    @Test
    fun colorAndSizeComeFromClosedSets() {
        val r = parse("[color:red]x[/color] [size:large]y[/size]")
        assertFalse(r.hasErrors)
        val inlines = r.document.single().inlines
        assertEquals(MarkupColor.RED, inlines.filterIsInstance<MarkupInline.Colored>().single().color)
        assertEquals(MarkupSize.LARGE, inlines.filterIsInstance<MarkupInline.Sized>().single().size)
    }

    @Test
    fun hexColorsAndNumericSizesAreRejectedButTheTextIsKept() {
        val r = parse("[color:#ff0000]rojo[/color] [size:18]grande[/size]")
        assertEquals(listOf("VALUE_NOT_ALLOWED", "VALUE_NOT_ALLOWED"), codes(r))
        assertEquals("rojo grande", r.plainText)
        assertTrue(r.document.single().inlines.none { it is MarkupInline.Colored || it is MarkupInline.Sized })
    }

    @Test
    fun alignAppliesToItsBlocksAndResetsAfterwards() {
        val r = parse("[align:center]titulo[/align][p]texto")
        assertFalse(r.hasErrors)
        assertEquals(listOf(MarkupAlign.CENTER, MarkupAlign.LEFT), r.document.map { it.align })
    }

    @Test
    fun nestedOrUnknownAlignIsRejected() {
        assertTrue("BAD_NESTING" in codes(parse("[align:left]a[align:right]b[/align][/align]")))
        assertTrue("VALUE_NOT_ALLOWED" in codes(parse("[align:diagonal]a[/align]")))
        assertTrue("UNCLOSED" in codes(parse("[align:center]a")))
        assertTrue("BAD_NESTING" in codes(parse("a[/align]")))
    }

    @Test
    fun paragraphBreaksAndBulletsAreExplicit() {
        val r = parse("uno\ndos[p]tres\n[-]a\n[-]b")
        assertEquals(listOf("uno dos", "tres", "• a", "• b"), r.document.map { it.plainText() })
        assertEquals(listOf(false, false, true, true), r.document.map { it.bullet })
    }

    @Test
    fun aBulletMustStartALine() {
        val r = parse("texto [-] no es viñeta")
        assertTrue("BULLET_NOT_AT_LINE_START" in codes(r))
    }

    @Test
    fun escapesProduceLiteralMarkers() {
        val r = parse("\\*\\*no negrita\\*\\* y \\[p]")
        assertEquals("**no negrita** y [p]", r.plainText)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun htmlAndScriptsAreLiteralText() {
        val r = parse("<script>alert(1)</script> <b>x</b> javascript:alert(1)")
        assertEquals("<script>alert(1)</script> <b>x</b> javascript:alert(1)", r.plainText)
        assertTrue(r.document.single().inlines.single() is MarkupInline.Text)
    }

    @Test
    fun controlBidiAndZeroWidthCharactersAreStripped() {
        val r = parse("a‮b​c\u0007d e")
        assertEquals("abcde", r.plainText)
    }

    @Test
    fun unknownTagsStayTextWithAWarning() {
        val r = parse("[bold]x[/bold]")
        assertEquals("[bold]x[/bold]", r.plainText)
        assertTrue(r.issues.all { it.severity == RazonadoMarkup.Severity.WARNING })
        assertFalse(r.hasErrors)
    }

    @Test
    fun hostileInputIsBoundedNotProcessedWhole() {
        val long = parse("a".repeat(RazonadoMarkup.MAX_LENGTH + 10))
        assertTrue("TOO_LONG" in codes(long))
        assertTrue(long.plainText.length <= RazonadoMarkup.MAX_LENGTH)
        val deep = parse("**".padStart(1) + "[color:red]".repeat(50) + "x" + "[/color]".repeat(50) + "**")
        assertTrue("TOO_DEEP" in codes(deep))
        val many = parse("**a** ".repeat(4000))
        assertTrue("TOO_MANY_NODES" in codes(many) || many.document.isNotEmpty())
    }

    @Test
    fun normalizationRoundTrips() {
        listOf(
            "**a** __b__ ++c++ ~~d~~",
            "[color:green]x **y**[/color] [size:small]z[/size]",
            "[align:justify]uno[/align][p][align:right][-]dos[/align]",
            "\\*\\* literal \\[p] \\\\",
            "[-]a[p][-]b",
        ).forEach { input ->
            val first = parse(input)
            assertFalse(first.hasErrors, input + " -> " + first.issues)
            val text = RazonadoMarkup.normalize(first.document)
            val second = parse(text)
            assertEquals(first.document, second.document, "$input -> $text")
            assertEquals(text, RazonadoMarkup.normalize(second.document), "estable: $input")
        }
    }

    @Test
    fun theOutputIsAlwaysATypedTreeNeverMarkup() {
        val r = parse("<img src=x onerror=alert(1)> **ok**")
        r.document.forEach { block ->
            block.inlines.forEach { node ->
                assertTrue(node is MarkupInline.Text || node is MarkupInline.Styled)
            }
        }
    }
}
