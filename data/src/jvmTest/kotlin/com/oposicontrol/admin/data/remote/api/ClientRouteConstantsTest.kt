package com.oposicontrol.admin.data.remote.api

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * INV-NETWORK-COMPLETE (parte cliente): toda ruta central de [BackendRoutes] (constantes y funciones con sus parametros
 * sustituidos por centinelas) tiene que existir en el arbol REAL de rutas del backend (`ops/inventory/routes.combined.txt`,
 * generado por `RouteInventoryTest`) o estar en el registro revisado `ops/inventory/client-route-exceptions.tsv`.
 * Sin parser del codigo: se recorre por reflexion el propio objeto. Una constante nueva que apunte a una ruta inexistente
 * (o una ruta retirada del backend) falla; una excepcion que ya se sirve o que ya no existe tambien (la lista solo encoge).
 */
class ClientRouteConstantsTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "ops/inventory/routes.combined.txt").isFile }
    private val inventory = File(repoRoot, "ops/inventory")

    private fun clientTemplates(): Set<String> {
        val cls = BackendRoutes::class.java
        val out = sortedSetOf<String>()
        cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == String::class.java }
            .forEach { out += it.get(null) as String }
        cls.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.returnType == String::class.java }
            .filter { it.name != "url" && it.parameterTypes.all { p -> p == String::class.java } }
            .forEach { m -> out += m.invoke(BackendRoutes, *Array(m.parameterCount) { "ID$it" }) as String }
        return out.map { toTemplate(it) }.toSortedSet()
    }

    private fun toTemplate(path: String) = path.substringBefore('?').replace(Regex("ID[0-9]+"), "{}")

    private fun servedPaths(): Set<String> = inventory.resolve("routes.combined.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfter(' ') }.toSet()

    private fun exceptions(): Map<String, String> = inventory.resolve("client-route-exceptions.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { l -> l.split('\t').let { require(it.size == 2 && it[1].isNotBlank()) { "path<TAB>reason: $l" }; it[0] to it[1] } }

    private fun violations(client: Set<String>, served: Set<String>, exceptions: Map<String, String>): List<String> =
        client.filter { it !in served && it !in exceptions }.map { "client route not served by the backend and not a reviewed exception: $it" } +
            exceptions.keys.filter { it in served }.map { "stale client-route exception (the backend serves it now): $it" } +
            exceptions.keys.filter { it !in client }.map { "stale client-route exception (BackendRoutes no longer has it): $it" }

    @Test
    fun theReflectionReallyFindsTheCentralRoutes() {
        val t = clientTemplates()
        assertTrue(t.size >= 40, "BackendRoutes reflection found only ${t.size} routes")
        assertTrue("/api/v1/admin/examenes/{}/preguntas" in t && "/health" in t && "/api/v1/app/home" in t)
    }

    @Test
    fun everyCentralClientRouteIsServedByTheBackendOrAReviewedException() {
        val v = violations(clientTemplates(), servedPaths(), exceptions())
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun negativeControlAnUnknownClientRouteAndStaleExceptionsAreReported() {
        val served = servedPaths()
        assertTrue(violations(clientTemplates() + "/api/v1/admin/__inventory_probe", served, exceptions())
            .any { "__inventory_probe" in it })
        assertTrue(violations(clientTemplates(), served, exceptions() + ("/api/v1/admin/examenes" to "r"))
            .any { "stale client-route exception (the backend serves it now)" in it })
        assertTrue(violations(clientTemplates(), served, exceptions() + ("/gone" to "r"))
            .any { "no longer has it" in it })
        assertEquals(emptyList(), violations(setOf("/a"), setOf("/a"), emptyMap()))
    }
}
