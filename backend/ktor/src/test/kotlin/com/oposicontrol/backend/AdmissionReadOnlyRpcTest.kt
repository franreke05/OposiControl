package com.oposicontrol.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Revision OposiControl 2026-10-22: cada RPC que la admision trata como LECTURA (no espera al presupuesto de escritura y no marca
 * el punto sin retorno) tiene que serlo de verdad en su ULTIMA definicion de supabase/migrations: `stable` (Postgres no deja
 * escribir en ella) o, para las excepciones documentadas, sin INSERT/UPDATE/DELETE (salvo el codigo de invitacion idempotente).
 */
class AdmissionReadOnlyRpcTest {
    private val migrations: List<File> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "supabase/migrations").isDirectory } ?: fail("supabase/migrations not found")
        File(root, "supabase/migrations").listFiles { f -> f.name.endsWith(".sql") }!!.sortedBy { it.name }
    }

    /** Cuerpo de la ULTIMA `create ... function public.<name>(` hasta su `$$;` de cierre. */
    private fun latestDefinition(name: String): String {
        val header = Regex("""^create (or replace )?function public\.$name\(""", RegexOption.IGNORE_CASE)
        for (file in migrations.asReversed()) {
            val lines = file.readLines()
            val start = lines.indexOfLast { header.containsMatchIn(it) }
            if (start < 0) continue
            val end = (start + 1 until lines.size).firstOrNull { lines[it].trimEnd().endsWith("$$;") || lines[it].trim() == "$$" }
                ?: fail("$name: definition in ${file.name} not closed")
            return lines.subList(start, end + 1).joinToString("\n")
        }
        fail("$name: no definition in supabase/migrations")
    }

    private val writes = Regex("""(?im)^\s*(insert\s+into|update\s+public\.|delete\s+from)\s+(public\.)?(\w+)""")

    @Test
    fun everyReadOnlyRpcIsStableOrADocumentedExceptionWithoutBusinessWrites() {
        val ids = UpstreamDeadlineGuard.READ_ONLY_RPCS
        assertTrue(UpstreamDeadlineGuard.READ_ONLY_RPC_EXCEPTIONS.all { it in ids })
        for (name in ids) {
            val body = latestDefinition(name)
            if (name !in UpstreamDeadlineGuard.READ_ONLY_RPC_EXCEPTIONS) {
                assertTrue(Regex("""(?i)\bstable\b""").containsMatchIn(body), "$name tiene que ser STABLE para contar como lectura")
                continue
            }
            val written = writes.findAll(body).map { it.groupValues[3].lowercase() }.toSet()
            val allowed = if (name == "app_referral_get") setOf("codigos_invitacion") else emptySet()
            assertEquals(allowed, written, "$name: escrituras no previstas en una RPC tratada como lectura")
        }
    }

    @Test
    fun writeRpcsAreNotReadOnly() {
        listOf("app_record_attempt_answer", "app_foro_image_commit", "app_foro_create_post", "app_save_item", "idem_begin")
            .forEach { assertTrue(!UpstreamDeadlineGuard.isRead("POST", "/rest/v1/rpc/$it"), it) }
        UpstreamDeadlineGuard.READ_ONLY_RPCS.forEach { assertTrue(UpstreamDeadlineGuard.isRead("POST", "/rest/v1/rpc/$it"), it) }
    }

    @Test
    fun onlyTheLiteralForoImageContentPathSkipsTheGlobalBodyCap() {
        assertTrue(isForoImageContentPath("/api/v1/app/oposired/imagenes/0f8e2c7a-1b2c-4d5e-8f90-123456789abc/contenido"))
        listOf(
            "/api/v1/app/oposired/imagenes/x/../contenido",
            "/api/v1/app/oposired/imagenes/a%2Fb/contenido",
            "/api/v1/app/oposired/imagenes//contenido",
            "/api/v1/app/oposired/imagenes/x/contenido/",
            "/api/v1/app/oposired/posts",
        ).forEach { assertTrue(!isForoImageContentPath(it), it) }
    }
}
