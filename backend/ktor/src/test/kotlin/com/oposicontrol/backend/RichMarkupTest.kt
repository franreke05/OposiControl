package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** FA38: golden vectors shared with PostgreSQL (supabase/baseline/assertions.sql), rejection cases and a fuzz/property test of the sanitizer. */
class RichMarkupTest {
    private val vectorsText: String by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "supabase/baseline/assertions.sql").isFile) dir = dir.parentFile
        File(requireNotNull(dir) { "supabase/baseline/assertions.sql not found" }, "supabase/baseline/assertions.sql").readText()
    }

    private fun section(name: String): String {
        val a = vectorsText.indexOf("-- MARKUP-VECTORS-$name-BEGIN")
        val b = vectorsText.indexOf("-- MARKUP-VECTORS-$name-END")
        require(a >= 0 && b > a) { "markers $name" }
        return vectorsText.substring(a, b)
    }

    private fun quoted(block: String): List<String> = Regex("\\\$v\\$(.*?)\\\$v\\$", RegexOption.DOT_MATCHES_ALL).findAll(block).map { it.groupValues[1] }.toList()

    private fun ok(source: String): RichMarkup.Doc = assertIs<RichMarkup.Result.Ok>(RichMarkup.parse(source), source).doc
    private fun codes(source: String): List<String> = assertIs<RichMarkup.Result.Rejected>(RichMarkup.parse(source), source).issues.map { it.code }

    @Test
    fun theSharedVectorsAreParsedToTheExactStructureAndPlainTextTheDatabaseAlsoChecks() {
        val q = quoted(section("OK")).map { it.replace("\r\n", "\n") }
        assertEquals(0, q.size % 3)
        assertTrue(q.size / 3 >= 8)
        for (i in q.indices step 3) {
            val source = q[i]
            val expected = Json.parseToJsonElement(q[i + 1].trim())
            val doc = ok(source)
            assertEquals(expected, RichMarkup.toJson(doc), "json of: $source")
            assertEquals(q[i + 2], RichMarkup.plain(doc), "plain of: $source")
            assertTrue(RichMarkup.validateJson(expected), "validator accepts: $source")
        }
    }

    @Test
    fun theSharedInvalidDocumentsAreRejectedByTheValidator() {
        val bad = quoted(section("BAD")).map { it.trim() }
        assertTrue(bad.size >= 14)
        bad.forEach { assertFalse(RichMarkup.validateJson(Json.parseToJsonElement(it)), it) }
    }

    @Test
    fun onlyTheDoubleMarkerIsSyntax() {
        assertEquals("a * b _ c + d ~ e", RichMarkup.plain(ok("a * b _ c + d ~ e")))
        assertEquals("a ***b*** c", RichMarkup.plain(ok("a ***b*** c")), "runs of three or more are text")
        assertTrue(ok("a **b** c").blocks.single().spans.any { it.bold })
    }

    @Test
    fun sizesAndColorsComeFromFixedSetsAndNothingElse() {
        for (z in listOf("xs", "sm", "md", "lg", "xl", "12", "14", "16", "18", "20")) assertTrue(ok("[size:$z]x[/size]").blocks.single().spans.single().size in RichMarkup.SIZES, z)
        for (z in listOf("17", "100", "huge", "", "18px", "-1", "1e2")) assertEquals(listOf("BAD_SIZE"), codes("[size:$z]x[/size]").take(1), z)
        for (c in RichMarkup.COLORS) assertEquals(c, ok("[color:$c]x[/color]").blocks.single().spans.single().color)
        assertEquals("#0A1B2C", ok("[color:#0a1b2c]x[/color]").blocks.single().spans.single().color)
        for (c in listOf("#abc", "#12345", "#1234567", "red;x", "url(x)", "RED", "javascript:1", "#GGGGGG", "rgb(1,2,3)")) assertEquals(listOf("BAD_COLOR"), codes("[color:$c]x[/color]").take(1), c)
    }

    @Test
    fun malformedAndUnknownTagsAreRejectedNeverPassedThrough() {
        val cases = mapOf(
            "[colour:red]x[/colour]" to "UNKNOWN_TAG", "[script:alert(1)]" to "UNKNOWN_TAG", "[img:http://x]" to "UNKNOWN_TAG", "[a:b]" to "UNKNOWN_TAG",
            "[P]" to "MALFORMED_TAG", "[Color:red]x[/color]" to "MALFORMED_TAG", "[color=red]x" to "MALFORMED_TAG", "[/p]" to "MALFORMED_TAG", "[p:1]" to "MALFORMED_TAG",
            "[color]x[/color]" to "MALFORMED_TAG", "x [-] y" to "BULLET_NOT_AT_LINE_START", "[align:middle]x[/align]" to "BAD_ALIGN",
            "**x" to "UNCLOSED_MARKER", "[color:red]x" to "UNCLOSED_MARKER", "[/color]" to "UNMATCHED_CLOSE", "[/align]" to "UNMATCHED_CLOSE",
            "**a __b** c__" to "MISALIGNED_NESTING", "[align:left]a[align:right]b[/align][/align]" to "ALIGN_NESTED", "**[align:left]a[/align]**" to "ALIGN_INSIDE_INLINE",
            "[align:left]x" to "UNCLOSED_ALIGN",
        )
        for ((src, code) in cases) assertTrue(code in codes(src), "$src -> $code, got ${codes(src)}")
        // plain brackets without a colon are text
        assertEquals("[sic] [1] [a]", RichMarkup.plain(ok("[sic] [1] [a]")))
        assertEquals("<b>x</b> http://a.b/c?d=1&e=2", RichMarkup.plain(ok("<b>x</b> http://a.b/c?d=1&e=2")), "no HTML, no links: text stays text")
    }

    @Test
    fun limitsAreEnforced() {
        assertEquals(listOf("TOO_LONG"), codes("a".repeat(RichMarkup.MAX_SOURCE + 1)))
        assertTrue("TOO_DEEP" in codes("**__++~~[color:red]x"), "depth > ${RichMarkup.MAX_DEPTH}")
        ok("**__++~~x~~++__**")
        assertTrue("TOO_MANY_BLOCKS" in codes((1..201).joinToString("[p]") { "p$it" }))
    }

    @Test
    fun hostileCharactersNeverSurvive() {
        val dirty = "a\u0000b\u0007c‮d⁦e​f‍g﻿h\u0085i j"
        val doc = ok(dirty)
        assertEquals("abcdefghij", RichMarkup.plain(doc))
        assertEquals("a b", RichMarkup.plain(ok("a   \t b")), "nbsp/tab/runs of blanks collapse to one space")
        assertEquals("😀", RichMarkup.plain(ok("😀")), "a well-formed emoji pair is kept")
        assertEquals("ab", RichMarkup.plain(ok("a\uD83Db")), "a lone surrogate is dropped")
    }

    @Test
    fun anEscapedTextRoundTripsExactly() {
        val rnd = Random(7)
        val alphabet = "ab *_+~[]\\-:#\n0123456789pP"
        repeat(2000) {
            val raw = (1..rnd.nextInt(1, 40)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            val expected = raw.replace(Regex("\\s+"), " ").trim()
            val doc = ok(RichMarkup.escape(raw))
            assertEquals(if (expected.isEmpty()) "" else expected, RichMarkup.plain(doc), "round trip of ${raw.replace("\n", "\\n")}")
        }
    }

    /** Whatever bytes arrive, the output is either a rejection or a document the SQL-mirrored validator accepts, with no raw markup tokens left in a span other than literals. */
    @Test
    fun fuzzNoOutputEverLeavesTheGrammar() {
        val rnd = Random(2026_10_18)
        val pieces = listOf("**", "__", "++", "~~", "*", "_", "[p]", "[-]", "[/color]", "[/size]", "[/align]", "[color:red]", "[color:#12ab9F]", "[color:", "[size:18]", "[size:xl]", "[size:99]",
            "[align:center]", "[align:justify]", "\\", "\\*", "[", "]", "\n", " ", "texto", "ñ", "‮", "\u0000", "<script>", "http://x", "[x:y]", "[sic]", "#", ":", "é​")
        var accepted = 0
        var rejected = 0
        repeat(20_000) {
            val src = (1..rnd.nextInt(0, 14)).joinToString("") { pieces[rnd.nextInt(pieces.size)] }
            when (val r = RichMarkup.parse(src)) {
                is RichMarkup.Result.Rejected -> { rejected++; assertTrue(r.issues.isNotEmpty() && r.issues.size <= RichMarkup.MAX_ISSUES, src) }
                is RichMarkup.Result.Ok -> {
                    accepted++
                    val json = RichMarkup.toJson(r.doc)
                    assertTrue(RichMarkup.validateJson(json), "validator rejects the parser's own output for: $src -> $json")
                    // structure checks independent of the validator
                    val root = json as JsonObject
                    assertEquals(setOf("v", "blocks"), root.keys)
                    for (b in root["blocks"] as JsonArray) for (s in ((b as JsonObject)["spans"] as JsonArray)) {
                        val span = s as JsonObject
                        assertTrue(span.keys.all { it in setOf("text", "bold", "italic", "underline", "strike", "color", "size") }, src)
                        val text = (span["text"] as JsonPrimitive).content
                        assertTrue(text.none { it.code < 0x20 || it.code in 0x7F..0xA0 || it.code in 0x202A..0x202E || it.code in 0x200B..0x200F || it.code == 0xFEFF }, "control char in span for: $src")
                        assertFalse(text.contains("\n"), src)
                    }
                }
            }
        }
        assertTrue(accepted > 500 && rejected > 500, "the fuzz exercises both outcomes (accepted=$accepted rejected=$rejected)")
    }

    @Test
    fun theStoredFormIsPlainOnlyWhenThereIsNoStyle() {
        val (plainOnly, issues) = RichMarkup.storeFor("Un texto sin estilo")
        assertTrue(issues.isEmpty())
        assertEquals("Un texto sin estilo", plainOnly!!.plain)
        assertEquals(null, plainOnly.rico)
        val (styled, _) = RichMarkup.storeFor("Con **negrita**")
        assertEquals("Con negrita", styled!!.plain)
        assertTrue(styled.rico != null)
        val (none, bad) = RichMarkup.storeFor("**sin cerrar")
        assertEquals(null, none)
        assertEquals("UNCLOSED_MARKER", bad.first().code)
    }

    @Suppress("unused")
    private fun keepImports(e: JsonElement) = e
}
