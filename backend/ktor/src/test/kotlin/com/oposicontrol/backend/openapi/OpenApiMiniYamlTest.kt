package com.oposicontrol.backend.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Fija el subconjunto YAML que entiende [OpenApiMiniYaml], para que los tests de contrato no mientan. */
class OpenApiMiniYamlTest {

    @Test
    fun parsesBlockAndFlowCollections() {
        val doc = OpenApiMiniYaml.parse(
            """
            # comment
            a: 1
            b: 'x: y # not a comment'
            c: [one, 'two', null, true]
            d:
              - k: v
                z: { ${'$'}ref: '#/p', n: 2 }
              - plain
              - []
            '200':
              description: ok # trailing comment
            """.trimIndent(),
            "inline",
        )
        val expected = mapOf(
            "a" to 1L,
            "b" to "x: y # not a comment",
            "c" to listOf("one", "two", null, true),
            "d" to listOf(mapOf("k" to "v", "z" to mapOf("\$ref" to "#/p", "n" to 2L)), "plain", emptyList<Any?>()),
            "200" to mapOf("description" to "ok"),
        )
        assertEquals(expected, doc)
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertFailsWith<OpenApiMiniYaml.YamlError> { OpenApiMiniYaml.parse("a: 1\na: 2", "dup") }
        assertFailsWith<OpenApiMiniYaml.YamlError> { OpenApiMiniYaml.parse("a: { b: 1, b: 2 }", "dup-flow") }
    }

    @Test
    fun rejectsUnsupportedConstructs() {
        assertFailsWith<OpenApiMiniYaml.YamlError> { OpenApiMiniYaml.parse("a: |\n  text", "block-scalar") }
        assertFailsWith<OpenApiMiniYaml.YamlError> { OpenApiMiniYaml.parse("a: &x 1", "anchor") }
        assertFailsWith<OpenApiMiniYaml.YamlError> { OpenApiMiniYaml.parse("a:\n    b: 1\n  c: 2", "indent") }
    }
}
