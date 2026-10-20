package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture
import com.oposicontrol.dbtest.asignar
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * RB-16 / SEC-V1-01c: migracion 20261023100100. En MAIN (catalogo GO-1, 2026-10-19; 2026-10-20) storage.objects tiene 14 politicas de
 * ESCRITURA con es_admin() o can_manage_content(): un JWT de admin sube, sobrescribe o borra objetos de exam-resources, news-assets (publico), news-images,
 * question-resources y recursos_pregunta por la API de Storage, saltandose Ktor. Forma de MAIN: stub
 * `supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql`.
 * Aqui (PG16 embebido, superusuario): control negativo, denegacion tras migrar, lecturas admin conservadas, service_role
 * intacto, ROLLBACK exacto desde la copia, idempotencia y PREFLIGHT cerrado. La puerta de PROPIEDAD (rol no miembro del
 * dueño) se prueba en PG17 con la imagen de Supabase, donde `postgres` no es superusuario.
 */
class StorageAdminWriteRevokeMigrationTest {
    private val migracion = "supabase/migrations/20261023100100_security_storage_admin_write_revoke.sql"
    private val stub = "supabase/canonical/sec_v1_01_main_intervention/storage_test/00_storage_objects_stub.sql"
    private val admin = "aaaaaaaa-0000-0000-0000-000000000001"
    private val usuario = "bbbbbbbb-0000-0000-0000-000000000002"
    private val buckets = listOf("exam-resources", "news-assets", "question-resources", "recursos-pregunta", "news-images")

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.aplicar(stub)
        c.asignar(admin, SupabaseFixture.ADMIN)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    /** Sentencia como la API de Storage (rol authenticated + claim sub). SQLSTATE o null + filas afectadas. */
    private fun Connection.comoCliente(sub: String?, sql: String, rol: String = "authenticated"): Pair<String?, Int> {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
            val n = createStatement().use { it.executeUpdate(sql) }
            ejecutar("commit")
            null to n
        } catch (e: SQLException) {
            runCatching { ejecutar("rollback") }
            e.sqlState to 0
        }
    }

    private fun Connection.lecturaComo(sub: String, sql: String): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role authenticated")
            ejecutar("select set_config('request.jwt.claim.sub', '$sub', true)")
            unico(sql)
        } finally {
            ejecutar("rollback")
        }
    }

    private fun Connection.politicas(): String = unico(
        "select string_agg(policyname || ':' || permissive || ':' || cmd || ':' || roles::text || ':' || coalesce(qual, '') || ':' || " +
            "coalesce(with_check, ''), E'\\n' order by policyname) from pg_policies where schemaname = 'storage' and tablename = 'objects'",
    )!!

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.objetos() = unico("select count(*)::text from storage.objects")!!

    private fun subir(bucket: String) = "insert into storage.objects (bucket_id, name) values ('$bucket', 'ataque/${bucket}.html')"
    private fun sobrescribir(bucket: String) = "update storage.objects set name = name || '.x' where bucket_id = '$bucket'"
    private fun borrar(bucket: String) = "delete from storage.objects where bucket_id = '$bucket'"

    @Test
    fun antesDeLaMigracionUnAdminEscribeYBorraObjetosDirectamente(): Unit = escenario(conMigracion = false) { c ->
        assertEquals(null to 1, c.comoCliente(admin, subir("news-assets")), "MAIN: el admin sube al bucket publico")
        assertEquals(null to 1, c.comoCliente(admin, sobrescribir("exam-resources")), "MAIN: el admin sobrescribe (bucket con SELECT admin)")
        assertEquals(null to 1, c.comoCliente(admin, borrar("exam-resources")), "MAIN: el admin borra objetos")
        assertEquals(null to 1, c.comoCliente(admin, sobrescribir("news-images")), "MAIN: el admin sobrescribe news-images via can_manage_content()")
        assertEquals(null to 1, c.comoCliente(admin, borrar("news-images")), "MAIN: el admin borra en news-images")
        assertEquals("42501", c.comoCliente(usuario, subir("news-assets")).first, "un usuario normal ya no podia")
        val v = c.verify()
        assertEquals(14L, v["storage_admin_write_policy"], v.toString())
        assertEquals(1L, v["storage_admin_write_backup_rows"], v.toString())
    }

    @Test
    fun trasLaMigracionNingunClienteEscribeEnLosCincoBuckets(): Unit = escenario { c ->
        val antes = c.objetos()
        for (b in buckets) {
            assertEquals("42501", c.comoCliente(admin, subir(b)).first, "admin INSERT $b")
            assertEquals(null to 0, c.comoCliente(admin, sobrescribir(b)), "admin UPDATE $b: ninguna fila")
            assertEquals(null to 0, c.comoCliente(admin, borrar(b)), "admin DELETE $b: ninguna fila")
            assertEquals("42501", c.comoCliente(usuario, subir(b)).first, "usuario INSERT $b")
            assertEquals("42501", c.comoCliente(null, subir(b), rol = "anon").first, "anon INSERT $b")
        }
        assertEquals(antes, c.objetos(), "ningun objeto cambio")
        // Lecturas admin conservadas (las 3 politicas SELECT no se tocan).
        assertEquals("1", c.lecturaComo(admin, "select count(*)::text from storage.objects where bucket_id = 'exam-resources'"))
        assertEquals("0", c.lecturaComo(usuario, "select count(*)::text from storage.objects where bucket_id = 'exam-resources'"))
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    /** Ktor escribe Storage con service_role (BYPASSRLS): sigue pudiendo. */
    @Test
    fun serviceRoleSigueEscribiendo(): Unit = escenario { c ->
        assertEquals(null to 1, c.comoCliente(null, subir("news-assets"), rol = "service_role"))
        assertEquals(null to 2, c.comoCliente(null, borrar("news-assets"), rol = "service_role"))
        assertEquals(null to 1, c.comoCliente(null, borrar("news-images"), rol = "service_role"))
    }

    @Test
    fun elRollbackRecreaLasPoliticasExactasDesdeLaCopiaYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val main = c.politicas()
        c.aplicar(migracion)
        val aplicada = c.politicas()
        assertNotEquals(main, aplicada)
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(aplicada, c.politicas())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.politicas(), "politicas recreadas byte a byte desde la copia")
        assertEquals("false", c.unico("select (to_regclass('private.sec_v1_storage_admin_policy_backup') is not null)::text"), "copia retirada")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(main, c.politicas())
        assertEquals(null to 1, c.comoCliente(admin, subir("news-assets")), "el ROLLBACK reabre (documentado) el camino")
        c.aplicar(migracion)
        assertEquals(aplicada, c.politicas())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun preflightFallaCerradoAnteEstadosInesperados(): Unit = escenario(conMigracion = false) { c ->
        fun rechaza(caso: String, sembrar: String, deshacer: String) {
            c.ejecutar(sembrar)
            val antes = c.politicas()
            val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
            runCatching { c.ejecutar("rollback") }
            c.autoCommit = true
            assertEquals("P0001", e?.sqlState, caso)
            assertEquals("STORAGE_ADMIN_WRITE_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(antes, c.politicas(), "nada cambiado ($caso)")
            c.ejecutar(deshacer)
        }
        rechaza(
            "estado parcial",
            "drop policy news_assets_storage_admin_delete on storage.objects",
            "create policy news_assets_storage_admin_delete on storage.objects for delete to authenticated using (bucket_id = 'news-assets' and public.es_admin())",
        )
        rechaza(
            "politica admin de escritura desconocida",
            "create policy otra_admin_insert on storage.objects for insert to authenticated with check (public.es_admin())",
            "drop policy otra_admin_insert on storage.objects",
        )
        rechaza(
            "una de las 12 ya no llama a es_admin()",
            "alter policy exam_resources_storage_admin_insert on storage.objects with check (bucket_id = 'exam-resources')",
            "alter policy exam_resources_storage_admin_insert on storage.objects with check (bucket_id = 'exam-resources' and public.es_admin())",
        )
        c.aplicar(migracion) // control: la forma de MAIN aplica
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }
}
