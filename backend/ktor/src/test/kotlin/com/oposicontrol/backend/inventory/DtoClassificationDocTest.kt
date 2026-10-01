package com.oposicontrol.backend.inventory

import com.oposicontrol.backend.openapi.OpenApiDocs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DTO-CLASSIFY: `docs/integration/DTO_CLASSIFICATION.md` clasifica los DTOs duplicados (sin unificarlos). Cada DTO de backend
 * de sus tablas tiene que seguir declarado en `backend/ktor/src/main` y llevar una clase valida; si se renombra o retira, el
 * documento debe revisarse.
 */
class DtoClassificationDocTest {

    private val repoRoot = OpenApiDocs.locate().dir.parentFile
    private val classes = setOf("REAL_SEMANTIC_DUPLICATION", "LEGITIMATE_SEPARATION")

    private data class Entry(val dto: String, val cls: String)

    private fun entries(doc: String): List<Entry> = doc.lines()
        .filter { it.startsWith("| `") }
        .map { l -> l.split('|').map { it.trim() } }
        .map { Entry(it[1].trim('`'), it[3]) }

    private fun violations(entries: List<Entry>, mainSource: String): List<String> =
        entries.mapNotNull { e ->
            when {
                e.cls !in classes -> "${e.dto}: unknown class '${e.cls}'"
                !Regex("(data )?class ${e.dto}\\b").containsMatchIn(mainSource) -> "${e.dto}: not declared in backend/ktor/src/main (renamed or removed: review the document)"
                else -> null
            }
        }

    private val mainSource: String by lazy {
        File(repoRoot, "backend/ktor/src/main").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }
    private val doc: String by lazy { File(repoRoot, "docs/integration/DTO_CLASSIFICATION.md").readText() }

    @Test
    fun everyClassifiedBackendDtoStillExists() {
        val e = entries(doc)
        assertEquals(32, e.size, "26 REAL_SEMANTIC_DUPLICATION + 6 LEGITIMATE_SEPARATION DTO classes")
        assertEquals(26, e.count { it.cls == "REAL_SEMANTIC_DUPLICATION" })
        val v = violations(e, mainSource)
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun negativeControlAMissingDtoOrUnknownClassIsReported() {
        assertTrue(violations(listOf(Entry("GhostDtoThatDoesNotExist", "REAL_SEMANTIC_DUPLICATION")), mainSource).any { "not declared" in it })
        assertTrue(violations(listOf(Entry("NoticiaDto", "UNIFY_ME")), mainSource).any { "unknown class" in it })
        assertEquals(emptyList(), violations(listOf(Entry("NoticiaDto", "REAL_SEMANTIC_DUPLICATION")), mainSource))
    }
}
