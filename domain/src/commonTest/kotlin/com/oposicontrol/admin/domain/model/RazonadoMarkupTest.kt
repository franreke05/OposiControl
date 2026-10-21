package com.oposicontrol.admin.domain.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Paridad con el analizador del SERVIDOR (`RichMarkup.kt`, FA38). Los ocho vectores válidos son los de
 * `supabase/baseline/assertions.sql` (bloque MARKUP-VECTORS-OK), copiados tal cual: misma fuente, mismo JSON, mismo texto
 * plano. Si el servidor cambia la gramática, estos vectores y [RazonadoMarkup] se cambian a la vez.
 */
class RazonadoMarkupTest {
    private fun ok(source: String): RazonadoMarkup.Doc = assertIs<RazonadoMarkup.Result.Ok>(RazonadoMarkup.parse(source), source).doc
    private fun codes(source: String): List<String> =
        assertIs<RazonadoMarkup.Result.Rejected>(RazonadoMarkup.parse(source), source).issues.map { it.code }

    private data class Vector(val source: String, val json: String, val plain: String)

    private val vectors = listOf(
        Vector(
            "Hola **mundo**",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"Hola "},{"text":"mundo","bold":true}]}]}""",
            "Hola mundo",
        ),
        Vector(
            "__a__ ++b++ ~~c~~",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"a","italic":true},{"text":" "},{"text":"b","underline":true},{"text":" "},{"text":"c","strike":true}]}]}""",
            "a b c",
        ),
        Vector(
            "[color:red]rojo[/color] [size:18]grande[/size]",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"rojo","color":"red"},{"text":" "},{"text":"grande","size":"lg"}]}]}""",
            "rojo grande",
        ),
        Vector(
            "[align:center]Centrado[p]Otro[/align]",
            """{"v":1,"blocks":[{"k":"p","a":"center","spans":[{"text":"Centrado"}]},{"k":"p","a":"center","spans":[{"text":"Otro"}]}]}""",
            "Centrado\nOtro",
        ),
        Vector(
            "Intro\n[-] uno\n[-] dos",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"Intro"}]},{"k":"li","spans":[{"text":"uno"}]},{"k":"li","spans":[{"text":"dos"}]}]}""",
            "Intro\n• uno\n• dos",
        ),
        Vector(
            "**negrita [color:#aa00ff]y color[/color]**",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"negrita ","bold":true},{"text":"y color","bold":true,"color":"#AA00FF"}]}]}""",
            "negrita y color",
        ),
        Vector(
            "5 \\* 3 y [sic] y 1 * 2 *** 3",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"5 * 3 y [sic] y 1 * 2 *** 3"}]}]}""",
            "5 * 3 y [sic] y 1 * 2 *** 3",
        ),
        Vector(
            "linea uno\nlinea dos [p] tres",
            """{"v":1,"blocks":[{"k":"p","spans":[{"text":"linea uno linea dos"}]},{"k":"p","spans":[{"text":"tres"}]}]}""",
            "linea uno linea dos\ntres",
        ),
    )

    @Test
    fun theVectorsSharedWithTheServerAndTheDatabaseGiveTheSameStructureAndPlainText() {
        vectors.forEach { v ->
            val doc = ok(v.source)
            assertEquals(v.json, RazonadoMarkup.toWireJson(doc), "json de: ${v.source}")
            assertEquals(v.plain, RazonadoMarkup.plain(doc), "texto plano de: ${v.source}")
        }
    }

    @Test
    fun onlyTheDoubleMarkerIsSyntax() {
        assertEquals("a * b _ c + d ~ e", RazonadoMarkup.plain(ok("a * b _ c + d ~ e")))
        assertEquals("a ***b*** c", RazonadoMarkup.plain(ok("a ***b*** c")), "una racha de tres o más es texto")
        assertTrue(ok("a **b** c").blocks.single().spans.any { it.bold })
        assertEquals("codigo_examen = a*b", RazonadoMarkup.plain(ok("codigo_examen = a*b")))
    }

    @Test
    fun sizesAndColorsComeFromFixedSetsAndNothingElse() {
        for (z in listOf("xs", "sm", "md", "lg", "xl", "12", "14", "16", "18", "20")) {
            assertTrue(ok("[size:$z]x[/size]").blocks.single().spans.single().size in RazonadoMarkup.SIZES, z)
        }
        for (z in listOf("17", "100", "huge", "", "18px", "-1", "1e2")) assertEquals("BAD_SIZE", codes("[size:$z]x[/size]").first(), z)
        for (c in RazonadoMarkup.COLORS) assertEquals(c, ok("[color:$c]x[/color]").blocks.single().spans.single().color)
        assertEquals("#0A1B2C", ok("[color:#0a1b2c]x[/color]").blocks.single().spans.single().color)
        for (c in listOf("#abc", "#12345", "#1234567", "red;x", "url(x)", "RED", "javascript:1", "#GGGGGG", "rgb(1,2,3)")) {
            assertEquals("BAD_COLOR", codes("[color:$c]x[/color]").first(), c)
        }
    }

    @Test
    fun malformedAndUnknownTagsAreRejectedNeverPassedThrough() {
        val cases = mapOf(
            "[colour:red]x[/colour]" to "UNKNOWN_TAG", "[script:alert(1)]" to "UNKNOWN_TAG", "[img:http://x]" to "UNKNOWN_TAG", "[a:b]" to "UNKNOWN_TAG",
            "[P]" to "MALFORMED_TAG", "[Color:red]x[/color]" to "MALFORMED_TAG", "[color=red]x" to "MALFORMED_TAG", "[/p]" to "MALFORMED_TAG",
            "[p:1]" to "MALFORMED_TAG", "[color]x[/color]" to "MALFORMED_TAG", "x [-] y" to "BULLET_NOT_AT_LINE_START",
            "[align:middle]x[/align]" to "BAD_ALIGN", "**x" to "UNCLOSED_MARKER", "[color:red]x" to "UNCLOSED_MARKER",
            "[/color]" to "UNMATCHED_CLOSE", "[/align]" to "UNMATCHED_CLOSE", "**a __b** c__" to "MISALIGNED_NESTING",
            "[align:left]a[align:right]b[/align][/align]" to "ALIGN_NESTED", "**[align:left]a[/align]**" to "ALIGN_INSIDE_INLINE",
            "[align:left]x" to "UNCLOSED_ALIGN",
        )
        for ((src, code) in cases) assertTrue(code in codes(src), "$src -> $code, obtenido ${codes(src)}")
        // corchetes sin dos puntos: texto
        assertEquals("[sic] [1] [a]", RazonadoMarkup.plain(ok("[sic] [1] [a]")))
        assertEquals("[p. 5]", RazonadoMarkup.plain(ok("[p. 5]")))
        assertEquals("<b>x</b> http://a.b/c?d=1&e=2", RazonadoMarkup.plain(ok("<b>x</b> http://a.b/c?d=1&e=2")), "sin HTML ni enlaces")
    }

    @Test
    fun limitsAreEnforced() {
        assertEquals(listOf("TOO_LONG"), codes("a".repeat(RazonadoMarkup.MAX_SOURCE + 1)))
        assertTrue("TOO_DEEP" in codes("**__++~~[color:red]x"))
        ok("**__++~~x~~++__**")
        assertTrue("TOO_MANY_BLOCKS" in codes((1..201).joinToString("[p]") { "p$it" }))
    }

    @Test
    fun hostileCharactersNeverSurvive() {
        val dirty = "a\u0000b\u0007c\u202Ed\u2066e\u200Bf\u200Dg\uFEFFh\u0085i\u2060j"
        assertEquals("abcdefghij", RazonadoMarkup.plain(ok(dirty)))
        assertEquals("a b", RazonadoMarkup.plain(ok("a   \t b")), "nbsp, tab y rachas de blancos son un espacio")
        assertEquals("😀", RazonadoMarkup.plain(ok("😀")), "un par sustituto bien formado se conserva")
        assertEquals("ab", RazonadoMarkup.plain(ok("a\uD83Db")), "un sustituto suelto se descarta")
    }

    @Test
    fun anEscapedTextRoundTripsExactly() {
        val rnd = Random(7)
        val alphabet = "ab *_+~[]\\-:#\n0123456789pP"
        repeat(2000) {
            val raw = (1..rnd.nextInt(1, 40)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            val expected = raw.replace(Regex("\\s+"), " ").trim()
            assertEquals(expected, RazonadoMarkup.plain(ok(RazonadoMarkup.escape(raw))), "ida y vuelta de ${raw.replace("\n", "\\n")}")
        }
    }

    @Test
    fun fuzzNoOutputEverLeavesTheGrammar() {
        val rnd = Random(2026_10_18)
        val pieces = listOf(
            "**", "__", "++", "~~", "*", "_", "[p]", "[-]", "[/color]", "[/size]", "[/align]", "[color:red]", "[color:#12ab9F]", "[color:",
            "[size:18]", "[size:xl]", "[size:99]", "[align:center]", "[align:justify]", "\\", "\\*", "[", "]", "\n", " ", "texto", "ñ", "‮",
            "\u0000", "<script>", "http://x", "[x:y]", "[sic]", "#", ":", "é​",
        )
        var accepted = 0
        var rejected = 0
        repeat(20_000) {
            val src = (1..rnd.nextInt(0, 14)).joinToString("") { pieces[rnd.nextInt(pieces.size)] }
            when (val r = RazonadoMarkup.parse(src)) {
                is RazonadoMarkup.Result.Rejected -> {
                    rejected++
                    assertTrue(r.issues.isNotEmpty() && r.issues.size <= RazonadoMarkup.MAX_ISSUES, src)
                }
                is RazonadoMarkup.Result.Ok -> {
                    accepted++
                    r.doc.blocks.forEach { b ->
                        assertTrue(b.spans.isNotEmpty(), src)
                        assertTrue(b.align == null || b.align in RazonadoMarkup.ALIGNS, src)
                        b.spans.forEach { s ->
                            assertTrue(s.text.isNotEmpty() && !s.text.contains('\n'), src)
                            assertTrue(s.text.none { it.code < 0x20 || it.code in 0x7F..0x9F || it.code in 0x202A..0x202E || it.code in 0x200B..0x200F || it.code == 0xFEFF }, src)
                            assertTrue(s.size == null || s.size in RazonadoMarkup.SIZES, src)
                            assertTrue(s.color == null || s.color in RazonadoMarkup.COLORS || Regex("^#[0-9A-F]{6}$").matches(s.color), src)
                        }
                    }
                }
            }
        }
        assertTrue(accepted > 500 && rejected > 500, "el fuzz recorre las dos salidas (aceptados=$accepted rechazados=$rejected)")
    }

    @Test
    fun theStoredFormIsPlainOnlyWhenThereIsNoStyle() {
        assertTrue(RazonadoMarkup.isPlain(ok("Un texto sin estilo")))
        assertFalse(RazonadoMarkup.isPlain(ok("Con **negrita**")))
        assertFalse(RazonadoMarkup.isPlain(ok("uno[p]dos")))
    }

    @Test
    fun everyIssueCodeHasAnOperatorMessage() {
        listOf(
            "UNCLOSED_MARKER", "MISALIGNED_NESTING", "UNMATCHED_CLOSE", "UNKNOWN_TAG", "MALFORMED_TAG", "BAD_COLOR", "BAD_SIZE", "BAD_ALIGN",
            "ALIGN_NESTED", "ALIGN_INSIDE_INLINE", "UNCLOSED_ALIGN", "BULLET_NOT_AT_LINE_START", "TOO_DEEP", "TOO_LONG", "TOO_MANY_BLOCKS", "TOO_MANY_SPANS",
        ).forEach { assertFalse(RazonadoMarkupMessages.describe(it).startsWith("El servidor no admite"), it) }
        assertTrue(RazonadoMarkupMessages.describe("NUEVO").startsWith("El servidor no admite"))
    }
}
