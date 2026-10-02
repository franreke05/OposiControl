package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.MODERADOR
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * DECISION DEL OWNER 4 (2026-10-02): migracion 20260930102100. Primero se PRUEBA (con SET ROLE, antes de la migracion)
 * que los grants de MAIN de TRUNCATE, TRIGGER y REFERENCES a `authenticated` (42 tablas) y a `anon` (noticia,
 * tipo_noticia) son EFECTIVOS (TRUNCATE salta la RLS); despues, que la migracion los revoca (42501), que los flujos
 * legitimos siguen verdes, que el ROLLBACK restaura las ACL byte a byte y que re-ejecutar es idempotente.
 *
 * Fixture: la forma de MAIN de `reportes_contenido`/`acciones_moderacion` (politicas, trigger y grants de MAIN, de
 * [Slice1ReportActionOnceMigrationTest]) + las otras 40 tablas con los grants de cliente de MAIN (baseline_main.sql
 * seccion 10: `REVOKE ALL` y despues anon SELECT, authenticated y service_role DELETE, INSERT, REFERENCES, SELECT,
 * TRIGGER, TRUNCATE, UPDATE; noticia/tipo_noticia tambien a anon). MAINTAIN (PG17) se omite: el PostgreSQL embebido es
 * 16 y la migracion no lo toca.
 */
class Slice1ClientDdlPrivilegesMigrationTest {
    private val migracion = "supabase/migrations/20260930102100_slice1_client_ddl_privileges_revoke.sql"
    private val reportAction = "supabase/migrations/20260930102000_slice1_report_action_once.sql"
    private val registro = "private.slice1_client_ddl_grants_revoked"

    companion object {
        /** Las 42 tablas de public en las que MAIN concede TRUNCATE/TRIGGER/REFERENCES a authenticated (baseline_main.sql). */
        val TABLAS_AUTHENTICATED = listOf(
            "abandonos_examen", "acciones_moderacion", "cola_repaso_usuario", "comentarios_oposired", "comunidades_autonomas",
            "configuraciones_usuario", "contenido_desbloqueado_usuario", "conversaciones_soporte", "errores_app", "especialidades",
            "etiquetas", "etiquetas_opositeca", "etiquetas_pregunta", "eventos_conversion_premium", "examenes", "feedback_examen",
            "feedback_pregunta", "invitaciones_referido", "lotes_actualizacion_ranking", "lotes_importacion_preguntas",
            "mensajes_soporte", "metricas_rendimiento_app", "noticia", "opciones_pregunta", "opositeca_items", "perfiles_usuario",
            "preguntas", "preguntas_examen", "preguntas_favoritas_usuario", "preguntas_parseadas_staging", "publicaciones_oposired",
            "reacciones_oposired", "recomendaciones_usuario", "recursos_pregunta", "registros_reto_diario", "reportes_contenido",
            "roles_usuario", "sesiones_estudio", "soluciones_pregunta", "tipo_noticia", "tokens_dispositivo", "uso_recursos_pregunta",
        )

        /** Las 2 tablas en las que MAIN concede ademas los mismos privilegios a anon (baseline_main.sql 3985, 4118). */
        val TABLAS_ANON = listOf("noticia", "tipo_noticia")
        val PRIVILEGIOS = listOf("TRUNCATE", "TRIGGER", "REFERENCES")

        /** Matriz esperada (tabla rol privilegio) de MAIN: 42 x 3 + 2 x 3 = 132 entradas. */
        val MATRIZ_MAIN: Set<String> = buildSet {
            TABLAS_AUTHENTICATED.forEach { t -> PRIVILEGIOS.forEach { add("public.$t authenticated $it") } }
            TABLAS_ANON.forEach { t -> PRIVILEGIOS.forEach { add("public.$t anon $it") } }
        }
    }

    /** Columnas de la clave de cada tabla para la prueba de REFERENCES (roles_usuario del fixture no tiene `id`). */
    private fun clave(t: String) = if (t == "roles_usuario") "usuario_id, rol_id" else "id"

    private fun fixture(c: Connection) {
        c.ejecutar(Slice1ReportActionOnceMigrationTest().mainShapes)
        c.ejecutar("create schema if not exists private")
        val sql = buildString {
            for (t in TABLAS_AUTHENTICATED) {
                if (t !in setOf("reportes_contenido", "acciones_moderacion", "roles_usuario")) {
                    append("create table public.$t (id uuid primary key default gen_random_uuid());\n")
                    append("alter table public.$t enable row level security;\n")
                }
                if (t == "reportes_contenido" || t == "acciones_moderacion") continue // grants de MAIN ya en mainShapes
                append("revoke all on table public.$t from public, anon, authenticated, service_role;\n")
                if (t in TABLAS_ANON) {
                    append("grant delete, insert, references, select, trigger, truncate, update on table public.$t to anon;\n")
                } else {
                    append("grant select on table public.$t to anon;\n")
                }
                append("grant delete, insert, references, select, trigger, truncate, update on table public.$t to authenticated, service_role;\n")
            }
            // Control: una tabla solo de servidor (como atribucion_usuario en MAIN) no se toca.
            append("create table public.hx_solo_servidor (id uuid primary key default gen_random_uuid());\n")
            append("alter table public.hx_solo_servidor enable row level security;\n")
            append("revoke all on table public.hx_solo_servidor from public, anon, authenticated, service_role;\n")
            append("grant delete, insert, references, select, trigger, truncate, update on table public.hx_solo_servidor to service_role;\n")
            // Funcion de trigger cualquiera (EXECUTE para PUBLIC por defecto) y esquemas propios de cada rol cliente para
            // declarar una FK (en MAIN no tienen CREATE en ningun esquema: REFERENCES es latente, pero el grant es efectivo).
            append("create function public.hx_noop() returns trigger language plpgsql as ${'$'}${'$'} begin return new; end ${'$'}${'$'};\n")
            append("create schema hx_authenticated authorization authenticated;\n")
            append("create schema hx_anon authorization anon;\n")
        }
        c.ejecutar(sql)
    }

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        fixture(c)
        c.aplicar(reportAction)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    /** Ejecuta [sql] como [rol] en su propia transaccion, SIEMPRE deshecha. Devuelve el SQLSTATE o null si fue bien. */
    private fun Connection.intento(rol: String, sql: String, sub: String? = null): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            if (sub != null) ejecutar("select set_config('request.jwt.claim.sub', '$sub', true)")
            ejecutar(sql)
            null
        } catch (e: SQLException) {
            e.sqlState
        } finally {
            runCatching { ejecutar("rollback") }
        }
    }

    /** Las tres operaciones que concede cada privilegio, como [rol] sobre public.[t]. */
    private fun Connection.operaciones(rol: String, t: String): Map<String, String?> = mapOf(
        "TRUNCATE" to intento(rol, "truncate table public.$t cascade"),
        "TRIGGER" to intento(rol, "create trigger hx_trg before insert on public.$t for each row execute function public.hx_noop()"),
        "REFERENCES" to intento(
            rol,
            "create table hx_$rol.ref_$t (" + clave(t).split(", ").joinToString(", ") { "$it uuid" } +
                ", foreign key (${clave(t)}) references public.$t (${clave(t)}))",
        ),
    )

    /** Matriz EFECTIVA (has_table_privilege) de TRUNCATE/TRIGGER/REFERENCES de anon/authenticated en public. */
    private fun Connection.matriz(): Set<String> = filas(
        "select format('%I.%I %s %s', n.nspname, c.relname, r.rol, p.priv) from pg_class c " +
            "join pg_namespace n on n.oid = c.relnamespace cross join unnest(array['anon', 'authenticated']) r(rol) " +
            "cross join unnest(array['TRUNCATE', 'TRIGGER', 'REFERENCES']) p(priv) " +
            "where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f') and has_table_privilege(r.rol::name, c.oid, p.priv)",
    ).map { it[0]!! }.toSet()

    /** ACL de cada relacion de public, como texto: el rollback debe dejarla byte a byte igual. */
    private fun Connection.acls(): String = unico(
        "select string_agg(format('%I.%I=%s', n.nspname, c.relname, coalesce(c.relacl::text, '')), E'\\n' order by c.relname) " +
            "from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')",
    )!!

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.registros(): Set<String> =
        if (unico("select to_regclass('$registro')::text") == null) emptySet()
        else filas("select relation_name || ' ' || grantee || ' ' || privilege from $registro").map { it[0]!! }.toSet()

    private fun Connection.fallo(sql: String): PSQLException? {
        val e = runCatching { ejecutar(sql) }.exceptionOrNull() as PSQLException?
        runCatching { ejecutar("rollback") }
        autoCommit = true
        return e
    }

    /**
     * Control NEGATIVO del estado previo (MAIN): sin la migracion los tres privilegios son EFECTIVOS para authenticated
     * en las 42 tablas y para anon en noticia/tipo_noticia, y TRUNCATE vacia filas que la RLS no deja ni ver.
     */
    @Test
    fun antesDeLaMigracionTruncateTriggerYReferencesSonEfectivosParaLosRolesCliente(): Unit = escenario(conMigracion = false) { c ->
        assertEquals(MATRIZ_MAIN, c.matriz())
        for (t in TABLAS_AUTHENTICATED) {
            assertEquals(PRIVILEGIOS.associateWith { null }, c.operaciones("authenticated", t), "authenticated en $t")
        }
        for (t in TABLAS_ANON) assertEquals(PRIVILEGIOS.associateWith { null }, c.operaciones("anon", t), "anon en $t")
        // Control: donde MAIN no se los da a anon, ya se deniegan.
        assertEquals(PRIVILEGIOS.associateWith { "42501" }, c.operaciones("anon", "especialidades"))

        // TRUNCATE salta la RLS: USER_A no ve el reporte de USER_B (solo staff tiene SELECT) pero lo borra.
        c.ejecutar("insert into public.reportes_contenido (tipo_entidad, entidad_id, motivo, reportado_por) values ('publicacion_oposired', gen_random_uuid(), 'spam', '$USER_B')")
        c.ejecutar("begin")
        c.ejecutar("set local role authenticated")
        c.ejecutar("select set_config('request.jwt.claim.sub', '$USER_A', true)")
        assertEquals("0", c.unico("select count(*)::text from public.reportes_contenido"), "la RLS oculta la fila")
        c.ejecutar("truncate table public.reportes_contenido cascade")
        c.ejecutar("reset role")
        assertEquals("0", c.unico("select count(*)::text from public.reportes_contenido"), "pero TRUNCATE la borra")
        c.ejecutar("rollback")
        assertEquals("1", c.unico("select count(*)::text from public.reportes_contenido"))
        assertEquals(MATRIZ_MAIN.size.toLong(), c.verify()["client_ddl_privilege_effective"], "el VERIFY detecta el estado de MAIN")
        assertEquals(1L, c.verify()["client_ddl_revocation_record_missing"])
    }

    @Test
    fun trasLaMigracionTruncateTriggerYReferencesDevuelven42501(): Unit = escenario { c ->
        assertEquals(emptySet(), c.matriz())
        for (t in TABLAS_AUTHENTICATED) {
            assertEquals(PRIVILEGIOS.associateWith { "42501" }, c.operaciones("authenticated", t), "authenticated en $t")
            assertEquals(PRIVILEGIOS.associateWith { "42501" }, c.operaciones("anon", t), "anon en $t")
        }
        val v = c.verify()
        assertEquals(3, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
        assertEquals(MATRIZ_MAIN, c.registros(), "el registro guarda exactamente lo revocado")
        assertEquals("true", c.unico("select relrowsecurity::text from pg_class where oid = '$registro'::regclass"))
        for (rol in listOf("anon", "authenticated", "service_role")) {
            assertEquals("false", c.unico("select has_table_privilege('$rol', '$registro', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')::text"), rol)
        }
    }

    /** Los flujos legitimos siguen verdes: reportante, politicas SELECT, FK al insertar, DML de clientes y service_role. */
    @Test
    fun losFlujosLegitimosSiguenVerdesTrasElRevoke(): Unit = escenario { c ->
        val insertReporte = "insert into public.reportes_contenido (tipo_entidad, entidad_id, motivo, reportado_por) values " +
            "('publicacion_oposired', gen_random_uuid(), 'spam', '$USER_A')"
        // Reportante: insercion PENDIENTE propia (politica endurecida de 102000), confirmada.
        c.ejecutar("begin")
        c.ejecutar("set local role authenticated")
        c.ejecutar("select set_config('request.jwt.claim.sub', '$USER_A', true)")
        c.ejecutar(insertReporte)
        c.ejecutar("commit")
        val id = c.unico("select id::text from public.reportes_contenido")!!
        // Politicas SELECT: el staff lo ve, el reportante no (select_staff), anon tampoco.
        c.ejecutar("insert into public.roles_usuario (usuario_id, rol_id) values ('$USER_B', '$MODERADOR')")
        fun cuenta(rol: String, sub: String?): String? {
            c.ejecutar("begin")
            return try {
                c.ejecutar("set local role $rol")
                c.ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
                c.unico("select count(*)::text from public.reportes_contenido")
            } finally {
                c.ejecutar("rollback")
            }
        }
        assertEquals("1", cuenta("authenticated", USER_B), "staff")
        assertEquals("0", cuenta("authenticated", USER_A), "no staff")
        assertEquals("0", cuenta("anon", null), "anon")
        // FK sin REFERENCES: la comprobacion de la FK corre con los derechos del dueno.
        assertNull(c.intento("authenticated", "insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion) values ('$id', '$USER_B', 'ocultar')", USER_B))
        assertEquals("23503", c.intento("authenticated", "insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion) values (gen_random_uuid(), '$USER_B', 'ocultar')", USER_B))
        // El trigger de MAIN sigue disparando para un escritor sin TRIGGER (aqui service_role, que bypassa RLS).
        assertNull(c.intento("service_role", "update public.reportes_contenido set descripcion = 'x' where id = '$id'"))
        // DML de los clientes intacto (detras de la RLS) en las 42 tablas; service_role conserva todo.
        for (t in TABLAS_AUTHENTICATED) {
            assertEquals("true", c.unico("select (has_table_privilege('authenticated', 'public.$t', 'SELECT') and has_table_privilege('authenticated', 'public.$t', 'INSERT') and has_table_privilege('authenticated', 'public.$t', 'UPDATE') and has_table_privilege('authenticated', 'public.$t', 'DELETE'))::text"), t)
            assertEquals("true", c.unico("select has_table_privilege('anon', 'public.$t', 'SELECT')::text"), t)
            for (p in PRIVILEGIOS) assertEquals("true", c.unico("select has_table_privilege('service_role', 'public.$t', '$p')::text"), "$t $p")
        }
        assertEquals("true", c.unico("select has_table_privilege('anon', 'public.noticia', 'INSERT,UPDATE,DELETE')::text"))
        assertNull(c.intento("service_role", "truncate table public.especialidades"), "service_role sigue pudiendo TRUNCATE")
        assertEquals("1", c.unico("select count(*)::text from public.reportes_contenido"))
    }

    @Test
    fun elRollbackRestauraLasAclByteAByteYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val main = c.acls()
        c.aplicar(migracion)
        val revocada = c.acls()
        assertNotEquals(main, revocada)
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.acls(), "el ROLLBACK restaura exactamente las ACL de MAIN")
        assertEquals(MATRIZ_MAIN, c.matriz())
        assertNull(c.unico("select to_regclass('$registro')::text"), "el ROLLBACK borra el registro")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable: sin registro no hace nada
        assertEquals(main, c.acls())
        c.aplicar(migracion)
        c.aplicar(migracion) // re-ejecucion: no-op, el registro no cambia
        assertEquals(revocada, c.acls())
        assertEquals(MATRIZ_MAIN, c.registros())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.acls())
    }

    /**
     * Residual de los privilegios por defecto: una tabla creada DESPUES hereda de nuevo los privilegios (aqui los
     * defaults del dueno, como `ALTER DEFAULT PRIVILEGES FOR ROLE postgres/supabase_admin` en MAIN). El VERIFY la senala,
     * re-ejecutar la migracion la revoca y la registra, y el ROLLBACK tambien la restaura.
     */
    @Test
    fun unaTablaNuevaConPrivilegiosPorDefectoLaSenalaElVerifyYUnaReejecucionLaRevoca(): Unit = escenario { c ->
        c.ejecutar("alter default privileges in schema public grant all on tables to anon, authenticated")
        c.ejecutar("create table public.hx_nueva (id uuid primary key)")
        c.ejecutar("alter table public.hx_nueva enable row level security")
        val aclNueva = c.unico("select relacl::text from pg_class where oid = 'public.hx_nueva'::regclass")
        assertEquals(6L, c.verify()["client_ddl_privilege_effective"], "anon y authenticated x 3 en la tabla nueva")
        c.aplicar(migracion) // re-ejecucion tras la primera aplicacion del escenario
        assertEquals(0L, c.verify()["client_ddl_privilege_effective"])
        assertEquals(MATRIZ_MAIN.size + 6, c.registros().size)
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals("true", c.unico("select has_table_privilege('anon', 'public.hx_nueva', 'TRUNCATE')::text"))
        assertEquals(aclNueva, c.unico("select relacl::text from pg_class where oid = 'public.hx_nueva'::regclass"), "el ROLLBACK restaura tambien la tabla nueva")
        assertEquals(MATRIZ_MAIN + PRIVILEGIOS.flatMap { p -> listOf("public.hx_nueva anon $p", "public.hx_nueva authenticated $p") }, c.matriz())
    }

    /** PREFLIGHT: un grant que el REVOKE del dueno no quitaria, o que no se podria restaurar exacto, aborta sin cambiar nada. */
    @Test
    fun preflightFallaCerradoAnteGrantsQueNoSePuedenRevocarNiRestaurarExactos(): Unit = escenario(conMigracion = false) { c ->
        val main = c.acls()
        fun rechaza(caso: String, sembrar: String, deshacer: String, registroSembrado: Boolean = false) {
            c.ejecutar(sembrar)
            val sembrado = c.acls()
            val e = c.fallo(Slice0Db.leer(migracion))
            assertEquals("CLIENT_DDL_PRIVILEGES_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(sembrado, c.acls(), "nada revocado ($caso)")
            if (registroSembrado) {
                assertEquals("x", c.unico("select string_agg(attname, ',') from pg_attribute where attrelid = '$registro'::regclass and attnum > 0"), "registro sin tocar")
            } else {
                assertNull(c.unico("select to_regclass('$registro')::text"), "nada creado ($caso)")
            }
            c.ejecutar(deshacer)
            assertEquals(main, c.acls(), "fixture restaurado ($caso)")
        }
        // Otro grantor (service_role con grant option concede TRUNCATE a anon): el REVOKE del dueno no lo quitaria.
        rechaza(
            "grantor distinto del dueno",
            "grant truncate on table public.especialidades to service_role with grant option; set role service_role; " +
                "grant truncate on table public.especialidades to anon; reset role",
            "revoke grant option for truncate on table public.especialidades from service_role cascade",
        )
        rechaza(
            "con grant option",
            "grant references on table public.especialidades to authenticated with grant option",
            "revoke grant option for references on table public.especialidades from authenticated",
        )
        rechaza(
            "concedido a PUBLIC",
            "grant trigger on table public.hx_solo_servidor to public",
            "revoke trigger on table public.hx_solo_servidor from public",
        )
        rechaza(
            "registro con otra forma",
            "create table $registro (x integer)",
            "drop table $registro",
            registroSembrado = true,
        )
        c.aplicar(migracion) // control: la forma limpia aplica
        assertEquals(emptySet(), c.matriz())
    }

    /**
     * LOCKING (cabecera): REVOKE es una actualizacion de catalogo; dentro de la transaccion abierta de la migracion no hay
     * ningun cerrojo sobre las tablas de negocio revocadas, y otra sesion lee y escribe sin esperar.
     */
    @Test
    fun elRevokeNoTomaCerrojoSobreLasTablasDeNegocio(): Unit = escenario(conMigracion = false) { c ->
        val sinTransaccion = Slice0Db.leer(migracion).replace(Regex("(?m)^begin;\\s*$"), "").replace(Regex("(?m)^commit;\\s*$"), "")
        assertNotEquals(Slice0Db.leer(migracion), sinTransaccion)
        c.autoCommit = false
        c.ejecutar(sinTransaccion)
        assertEquals(emptySet(), c.matriz(), "revocado dentro de la transaccion abierta")
        val cerrojos = c.filas(
            "select c.relname || ' ' || l.mode from pg_locks l join pg_class c on c.oid = l.relation " +
                "where l.pid = pg_backend_pid() and c.relnamespace = 'public'::regnamespace and c.relkind = 'r'",
        ).map { it[0]!! }
        assertEquals(emptyList(), cerrojos.filter { it.substringBefore(' ') in TABLAS_AUTHENTICATED })
        PostgresHarness.conexionExtra(c).use { otra ->
            otra.ejecutar("set lock_timeout = '2s'")
            otra.ejecutar("insert into public.especialidades default values")
            assertEquals("1", otra.unico("select count(*)::text from public.especialidades"))
        }
        c.rollback()
        c.autoCommit = true
        assertEquals(MATRIZ_MAIN, c.matriz())
    }
}
