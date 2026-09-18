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
        return f.readText()
    }

    fun guard(): String = leer("supabase/canonical/role_sync_guard_candidate_v1/001_admin_sync_roles_guard.sql")

    fun rollback(): String = leer("supabase/canonical/role_sync_guard_candidate_v1/002_rollback_admin_sync_roles.sql")
}
