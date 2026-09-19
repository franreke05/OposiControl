package com.oposicontrol.dbtest

import java.io.File

/**
 * Los ficheros del candidate, leidos SIEMPRE del repositorio.
 *
 * Nunca se copia el cuerpo de la funcion a un literal de test: si existiera una
 * segunda implementacion aqui, una mutacion en el candidate real no cambiaria lo
 * que se prueba, que es justo el fallo que esta suite viene a cerrar.
 */
object CandidateFiles {
    val raiz: File by lazy {
        var d = File(".").absoluteFile
        while (d.parentFile != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        check(File(d, "settings.gradle.kts").isFile) { "No encuentro la raiz del repositorio." }
        d
    }

    private fun leer(ruta: String): String {
        val f = File(raiz, ruta)
        check(f.isFile) { "Falta $ruta" }
        // `core.autocrlf=true` entrega CRLF en un checkout fresco (el blob es LF)
        // pero no en un working tree donde el fichero nunca pasó por un checkout
        // real. RoleSyncMutationTest busca patrones multilinea con `\n` puro
        // (`Char(10)`): sin normalizar aquí, esas mutaciones no encuentran nada
        // que reemplazar en CRLF y fallan solo fuera de ese working tree concreto.
        return f.readText().replace("\r\n", "\n")
    }

    fun guard(): String = leer("supabase/canonical/role_sync_guard_candidate_v1/001_admin_sync_roles_guard.sql")

    fun rollback(): String = leer("supabase/canonical/role_sync_guard_candidate_v1/002_rollback_admin_sync_roles.sql")
}
