package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice3.Slice3Db
import com.oposicontrol.dbtest.slice3.Slice3Db.EX
import com.oposicontrol.dbtest.slice3.Slice3Db.EX_DRAFT
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * SEC-V1-01: migracion 20261023100000. En MAIN (catalogo leido 2026-10-19) `authenticated` tiene INSERT/UPDATE/DELETE en
 * las tablas de contenido con `*_admin_all` (FOR ALL, es_admin()), y es_admin() ignora `estado_cuenta`: un admin, o un
 * admin BLOQUEADO, cambia precios, preguntas, soluciones y noticias por la Data API saltandose Ktor y su auditoria.
 *
 * Tres perfiles: UA admin ACTIVO, UC admin BLOQUEADO, UB usuario normal (+ anon). Control NEGATIVO del estado de MAIN,
 * despues 42501 para todo cliente en las 13 tablas, lecturas de admin activo intactas, admin bloqueado sin privilegio,
 * service_role (Ktor) conserva escritura, ROLLBACK exacto, idempotencia y PREFLIGHT cerrado.
 *
 * Forma de MAIN: `main_shape_subset.sql` (via Slice3Db). Tres tablas no estan en ese subconjunto (recursos_pregunta,
 * noticia, tipo_noticia) y `estado_cuenta` llega con 20260928100000: se crean aqui con la forma de MAIN que la migracion
 * exige (politica `*_admin_all` y grants), que es lo que su PREFLIGHT comprueba.
 */
class ContentAdminWriteRevokeMigrationTest {
    private val migracion = "supabase/migrations/20261023100000_security_content_admin_write_revoke.sql"
    private val uc = "a3000000-0000-0000-0000-00000000000c"
    private val rolAdmin = "d2000000-0000-0000-0000-0000000000a1"
    private val tablas = listOf(
        "examenes", "preguntas", "opciones_pregunta", "soluciones_pregunta", "preguntas_examen", "recursos_pregunta",
        "especialidades", "comunidades_autonomas", "etiquetas", "etiquetas_pregunta", "lotes_importacion_preguntas",
        "noticia", "tipo_noticia",
    )

    private val formaMainAusente = """
        alter table public.perfiles_usuario add column if not exists estado_cuenta text not null default 'activa';
        create table public.recursos_pregunta (id uuid primary key default gen_random_uuid(), pregunta_id uuid not null, url text);
        create table public.tipo_noticia (id uuid primary key default gen_random_uuid(), nombre text not null, activo boolean not null default true);
        create table public.noticia (id uuid primary key default gen_random_uuid(), titulo text not null, estado text not null default 'draft',
          publicar_desde timestamptz);
        alter table public.recursos_pregunta enable row level security;
        alter table public.tipo_noticia enable row level security;
        alter table public.noticia enable row level security;
        create policy recursos_pregunta_admin_all on public.recursos_pregunta as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy tipo_noticia_admin_all on public.tipo_noticia as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy tipo_noticia_select_publico on public.tipo_noticia for select to authenticated, anon using (activo = true or es_admin());
        create policy noticia_admin_all on public.noticia as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy noticia_select_publico on public.noticia for select to authenticated, anon
          using (es_admin() or (estado = 'published' and (publicar_desde is null or publicar_desde <= now())));
        revoke all on table public.recursos_pregunta, public.tipo_noticia, public.noticia from public, anon, authenticated, service_role;
        grant select on table public.recursos_pregunta, public.tipo_noticia, public.noticia to anon;
        grant select, insert, update, delete on table public.recursos_pregunta, public.tipo_noticia, public.noticia to authenticated, service_role;
    """.trimIndent()

    /** UA admin activo, UC admin bloqueado, UB usuario. Una noticia en borrador. */
    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T =
        Slice3Db.escenario(migraciones = false) { c ->
            c.ejecutar(formaMainAusente)
            c.ejecutar(
                """
                set session_replication_role = replica;
                insert into auth.users (id, email) values ('$uc', 'c@s3.local');
                insert into public.roles_app (id, codigo, nombre) values ('$rolAdmin', 'admin', 'Admin');
                insert into public.roles_usuario (usuario_id, rol_id) values ('$UA', '$rolAdmin'), ('$uc', '$rolAdmin');
                insert into public.perfiles_usuario (usuario_id, nombre_mostrar, estado_cuenta) values
                  ('$UA', 'admin activo', 'activa'), ('$uc', 'admin bloqueado', 'bloqueada'), ('$UB', 'usuario', 'activa');
                insert into public.noticia (titulo) values ('borrador');
                set session_replication_role = origin;
                """.trimIndent(),
            )
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    /** Sentencia como cliente de PostgREST (`set local role` + claim sub). Devuelve SQLSTATE, o null + filas afectadas. */
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

    private fun Connection.lecturaComo(sub: String?, sql: String, rol: String = "authenticated"): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
            unico(sql)
        } finally {
            ejecutar("rollback")
        }
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.primeraColumna(tabla: String): String =
        unico("select attname from pg_attribute where attrelid = 'public.$tabla'::regclass and attnum = 1")!!

    /** Las tres escrituras de PostgREST sobre [tabla]; ninguna depende de datos (el privilegio se comprueba antes). */
    private fun Connection.escrituras(tabla: String): List<String> {
        val col = primeraColumna(tabla)
        return listOf(
            "insert into public.$tabla select * from public.$tabla where false",
            "update public.$tabla set $col = $col where false",
            "delete from public.$tabla where false",
        )
    }

    private fun Connection.estado(): String = unico(
        """
        select string_agg(x, E'\n' order by x) from (
          select c.relname || ':' || coalesce(c.relacl::text, '') as x from pg_class c
          where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}])
          union all
          select c.relname || '.' || p.polname || ':' || p.polcmd::text || ':' || coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ':' ||
                 coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
          from pg_policy p join pg_class c on c.oid = p.polrelid
          where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}, 'perfiles_usuario'])
          union all
          select 'es_admin:' || btrim(regexp_replace(prosrc, '\s+', ' ', 'g')) from pg_proc where oid = 'public.es_admin()'::regprocedure
        ) s
        """.trimIndent(),
    )!!

    private val subirPrecio = "update public.examenes set precio_tickets = 0, tipo_acceso = 'gratuito' where id = '$EX_DRAFT'"
    private val editarPerfilAjeno = "update public.perfiles_usuario set nombre_mostrar = 'pwned' where usuario_id = '$UB'"
    private fun Connection.precio() = unico("select coalesce(precio_tickets::text, 'null') || ':' || tipo_acceso from public.examenes where id = '$EX_DRAFT'")

    /** Control NEGATIVO: en el estado de MAIN el admin activo Y el bloqueado escriben contenido con su JWT. */
    @Test
    fun antesDeLaMigracionAdminActivoYBloqueadoEscribenContenidoPorLaDataApi(): Unit = escenario(conMigracion = false) { c ->
        assertEquals(null to 1, c.comoCliente(UA, subirPrecio), "MAIN: admin activo cambia el precio")
        c.ejecutar("update public.examenes set precio_tickets = 50, tipo_acceso = 'premium' where id = '$EX_DRAFT'")
        assertEquals(null to 1, c.comoCliente(uc, subirPrecio), "MAIN: admin BLOQUEADO cambia el precio")
        assertEquals("0:gratuito", c.precio())
        assertEquals(null to 0, c.comoCliente(UB, subirPrecio), "MAIN: el usuario normal no ve la fila (RLS, 0 filas)")
        assertEquals(null to 1, c.comoCliente(uc, editarPerfilAjeno), "MAIN: admin bloqueado reescribe el perfil de otro")
        assertEquals("1", c.lecturaComo(uc, "select count(*)::text from public.examenes where id = '$EX_DRAFT'"),
            "MAIN: admin bloqueado lee borradores")
        val v = c.verify()
        assertEquals(tablas.size * 3L, v["content_client_write_privilege"], "VERIFY detecta MAIN: $v")
        assertEquals(tablas.size.toLong(), v["content_client_write_policy"], v.toString())
        assertEquals(1L, v["es_admin_ignores_account_state"], v.toString())
        assertEquals(1L, v["perfiles_update_admin_branch"], v.toString())
        assertEquals(0L, v["content_service_role_write_missing"], v.toString())
    }

    @Test
    fun trasLaMigracionNingunClienteEscribeNingunaDeLasTreceTablas(): Unit = escenario { c ->
        for (tabla in tablas) for (sql in c.escrituras(tabla)) {
            assertEquals("42501", c.comoCliente(UA, sql).first, "admin activo: $sql")
            assertEquals("42501", c.comoCliente(uc, sql).first, "admin bloqueado: $sql")
            assertEquals("42501", c.comoCliente(UB, sql).first, "usuario: $sql")
            assertEquals("42501", c.comoCliente(null, sql, rol = "anon").first, "anon: $sql")
        }
        assertEquals("42501", c.comoCliente(UA, subirPrecio).first)
        assertEquals("null:gratuito", c.precio(), "el precio sembrado no cambio")
        val v = c.verify()
        assertEquals(5, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
    }

    /** Lecturas: el admin activo conserva la vision de borradores; el bloqueado pasa a ser un usuario mas. */
    @Test
    fun adminActivoSigueLeyendoBorradoresYElBloqueadoYaNo(): Unit = escenario { c ->
        val borrador = "select count(*)::text from public.examenes where id = '$EX_DRAFT'"
        val publicado = "select count(*)::text from public.examenes where id = '$EX'"
        assertEquals("1", c.lecturaComo(UA, borrador), "admin activo ve el borrador")
        assertEquals("0", c.lecturaComo(uc, borrador), "admin bloqueado ya no ve el borrador")
        assertEquals("0", c.lecturaComo(UB, borrador), "usuario no ve el borrador")
        assertEquals("1", c.lecturaComo(UB, publicado), "el contenido publicado sigue siendo publico")
        assertEquals("1", c.lecturaComo(UA, "select count(*)::text from public.noticia"), "admin activo ve la noticia en borrador")
        assertEquals("0", c.lecturaComo(uc, "select count(*)::text from public.noticia"))
        assertEquals("3", c.lecturaComo(UA, "select count(*)::text from public.perfiles_usuario"), "admin activo lee perfiles")
        assertEquals("1", c.lecturaComo(uc, "select count(*)::text from public.perfiles_usuario"), "bloqueado: solo el suyo")
    }

    /**
     * PREEXISTENTE (no lo introduce ni lo arregla esta migracion): en MAIN `anon` no tiene EXECUTE sobre es_admin()
     * (baseline :1072-1073) y las politicas SELECT `... OR es_admin()` son TO anon, asi que una lectura anonima que
     * alcanza una fila no publica falla con 42501 en vez de filtrarla. Se fija que el comportamiento es identico antes y
     * despues: la migracion no cambia el ACL de es_admin() (CREATE OR REPLACE lo conserva).
     */
    @Test
    fun lecturaAnonimaDeBorradorFallaIgualAntesYDespues() {
        val borrador = "select count(*)::text from public.examenes where id = '$EX_DRAFT'"
        fun sqlStateAnon(c: Connection): String? = runCatching { c.lecturaComo(null, borrador, rol = "anon") }
            .exceptionOrNull()?.let { (it as SQLException).sqlState }
        val antes = escenario(conMigracion = false) { sqlStateAnon(it) }
        val despues = escenario { sqlStateAnon(it) }
        assertEquals("42501", antes, "MAIN: anon sin EXECUTE sobre es_admin()")
        assertEquals(antes, despues)
    }

    @Test
    fun perfilesSoloElPropio(): Unit = escenario { c ->
        assertEquals(null to 0, c.comoCliente(UA, editarPerfilAjeno), "admin activo ya no reescribe perfiles ajenos")
        assertEquals(null to 0, c.comoCliente(uc, editarPerfilAjeno))
        assertEquals("usuario", c.unico("select nombre_mostrar from public.perfiles_usuario where usuario_id = '$UB'"))
        assertEquals(null to 1, c.comoCliente(UB, "update public.perfiles_usuario set nombre_mostrar = 'yo' where usuario_id = '$UB'"),
            "el usuario sigue editando su propia fila (los guardas de columnas son de otras migraciones)")
    }

    /** El camino privilegiado (Ktor, service_role) conserva escritura en las 13 tablas. */
    @Test
    fun serviceRoleSigueEscribiendo(): Unit = escenario { c ->
        assertEquals(null to 1, c.comoCliente(null, subirPrecio, rol = "service_role"))
        assertEquals("0:gratuito", c.precio())
        for (tabla in tablas) for (sql in c.escrituras(tabla)) {
            assertNull(c.comoCliente(null, sql, rol = "service_role").first, "service_role: $sql")
        }
    }

    @Test
    fun elRollbackRestauraElEstadoExactoYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val main = c.estado()
        c.aplicar(migracion)
        val aplicada = c.estado()
        assertNotEquals(main, aplicada)
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(aplicada, c.estado())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.estado(), "ACL, politicas y es_admin() byte a byte")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(main, c.estado())
        assertEquals(null to 1, c.comoCliente(uc, subirPrecio), "el ROLLBACK reabre (documentado) el camino")
        c.aplicar(migracion)
        assertEquals(aplicada, c.estado())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun preflightFallaCerradoAnteEstadosQueNoSePuedenRestaurarExactos(): Unit = escenario(conMigracion = false) { c ->
        fun rechaza(caso: String, sembrar: String, deshacer: String) {
            c.ejecutar(sembrar)
            val antes = c.estado()
            val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
            runCatching { c.ejecutar("rollback") }
            c.autoCommit = true
            assertEquals("P0001", e?.sqlState, caso)
            assertEquals("CONTENT_ADMIN_WRITE_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(antes, c.estado(), "nada cambiado ($caso)")
            c.ejecutar(deshacer)
        }
        rechaza("estado parcial", "revoke delete on table public.preguntas from authenticated", "grant delete on table public.preguntas to authenticated")
        rechaza("anon con INSERT", "grant insert on table public.etiquetas to anon", "revoke insert on table public.etiquetas from anon")
        rechaza("grant option", "grant update on table public.noticia to authenticated with grant option",
            "revoke grant option for update on table public.noticia from authenticated")
        rechaza("politica admin_all distinta", "alter policy examenes_admin_all on public.examenes with check (true)",
            "alter policy examenes_admin_all on public.examenes with check (public.es_admin())")
        rechaza("es_admin() desconocida",
            "create or replace function public.es_admin() returns boolean language sql stable security definer set search_path to '' as 'select true'",
            Slice0Db.rollbackDe(migracion).substringAfter("end \$\$;").substringBefore("alter policy"))
        rechaza("politica de perfiles distinta", "alter policy perfiles_usuario_update_propio on public.perfiles_usuario using (true)",
            "alter policy perfiles_usuario_update_propio on public.perfiles_usuario using (((usuario_id = (select auth.uid())) or public.es_admin()))")
        c.aplicar(migracion) // control: la forma de MAIN aplica
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }
}
