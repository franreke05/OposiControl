package com.oposicontrol.backend.openapi

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * MASTER3-OPENAPI-001: el tripwire `Slice1ContractConformanceTest.openApiIsFrozen...` solo ve cambios SIN commitear (y
 * vuelve en silencio sin git). Este control es independiente de git: compara el sha256 (con saltos LF) de cada fichero de
 * `openapi/` con un manifiesto fijado (`contract-freeze/freeze-manifest.sha256`). Una edicion de contrato, ya commiteada o
 * no, cambia el hash y falla aqui hasta que se registre en CONTRACT_FREEZE.md (repo OposiBot, "Freeze amendments") y se
 * actualice el manifiesto en el mismo cambio.
 */
class OpenApiFreezeManifestTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "openapi/root.yaml").isFile } ?: fail("repo root not found")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun pinned(): Map<String, String> {
        val text = OpenApiFreezeManifestTest::class.java.getResourceAsStream("/contract-freeze/freeze-manifest.sha256")
            ?.bufferedReader(Charsets.UTF_8)?.readText() ?: fail("freeze-manifest.sha256 missing from test resources")
        return text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }
    }

    @Test
    fun everyOpenApiFileMatchesThePinnedFreezeManifest() {
        val actual = File(root, "openapi").walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it) }
        val expected = pinned()
        assertEquals(expected.keys, actual.keys, "openapi/ file set differs from the freeze manifest (added or removed file)")
        val changed = expected.filter { (path, hash) -> actual[path] != hash }.keys
        assertEquals(emptySet<String>(), changed, "openapi/ is FROZEN: these files differ from the manifest; record a CONTRACT_FREEZE amendment and update the manifest")
    }

    @Test
    fun theManifestDetectsAChangedFile() {
        val victim = File(root, "openapi/root.yaml")
        val tampered = MessageDigest.getInstance("SHA-256")
            .digest((victim.readText(Charsets.UTF_8).replace("\r\n", "\n") + "# x\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(false, pinned()["openapi/root.yaml"] == tampered)
    }
}
