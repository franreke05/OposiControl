package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice1.Slice1Db.catalogo
import com.oposicontrol.dbtest.slice1.Slice1Db.estadoComo
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import com.oposicontrol.dbtest.slice1.Slice1Db.sembrar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migracion 2 (legal/atribucion/detalle) y propiedades transversales de las cuatro migraciones de Slice 1:
 * diff de esquema declarado, reconstruccion identica, reaplicacion y rollback completo en orden inverso.
 */
class Slice1SchemaMigrationTest {

    private fun Connection.columnas(tabla: String): List<String> = filas(
        "select column_name || ':' || data_type || ':' || is_nullable from information_schema.columns " +
            "where table_schema = 'public' and table_name = '$tabla' order by column_name",
    ).map { it[0]!! }

    @Test
    fun legalAtribucionDetalleAnadeSoloLoDeclaradoSobreLaFormaDeMain(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        val tablas = listOf("documentos_legales", "atribucion_usuario", "perfiles_usuario_detalle", "comunidades_objetivo_usuario")
        val antes = tablas.associateWith { c.columnas(it) }
        c.aplicar(Slice1Db.LEGAL)
        val despues = tablas.associateWith { c.columnas(it) }
        assertEquals(antes["documentos_legales"], despues["documentos_legales"])
        assertEquals(antes["perfiles_usuario_detalle"], despues["perfiles_usuario_detalle"])
        assertEquals(antes["comunidades_objetivo_usuario"], despues["comunidades_objetivo_usuario"])
        assertEquals(listOf("install_referrer:text:YES"), despues["atribucion_usuario"]!! - antes["atribucion_usuario"]!!.toSet())
        assertEquals(emptyList(), antes["atribucion_usuario"]!! - despues["atribucion_usuario"]!!.toSet())
        assertEquals(
            listOf("id:uuid:NO", "usuario_id:uuid:NO", "documento_id:uuid:NO", "tipo_documento:text:NO", "version:text:NO", "plataforma:text:NO", "aceptado_en:timestamp with time zone:NO").sorted(),
            c.columnas("aceptaciones_legales"),
        )
        // Privilegios de MAIN de las tablas existentes: intactos (anon/authenticated sin acceso salvo documentos_legales SELECT).
        assertEquals(
            "anon:SELECT,authenticated:SELECT",
            c.unico("select string_agg(grantee || ':' || privilege_type, ',' order by grantee) from information_schema.role_table_grants where table_schema = 'public' and table_name = 'documentos_legales' and grantee in ('anon','authenticated')"),
        )
        val v = c.filas(Slice0Db.verifyDe(Slice1Db.LEGAL)).associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(8, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
    }

    @Test
    fun enUnaBaseNuevaSinLasTablasDeMainConvergeALaMismaForma(): Unit {
        val tablas = listOf("documentos_legales", "atribucion_usuario", "perfiles_usuario_detalle", "comunidades_objetivo_usuario")
        val conMain = Slice1Db.escenario { c -> tablas.associateWith { c.columnas(it) } }
        val desdeCero = Slice0Db.escenario { c ->
            c.aplicar(Slice0Db.IDENTITY)
            c.aplicar(Slice1Db.LEGAL)
            tablas.associateWith { c.columnas(it) }
        }
        assertEquals(conMain, desdeCero)
    }

    @Test
    fun atribucionEsWriteOnceYLosChecksRechazanValoresInvalidos(): Unit = Slice1Db.escenario { c ->
        c.sembrar()
        assertEquals(null, c.estadoComo("service_role", "insert into public.atribucion_usuario (usuario_id, fuente, plataforma) values ('$USER_A', 'ads', 'ios')"))
        assertEquals("23505", c.estadoComo("service_role", "insert into public.atribucion_usuario (usuario_id, fuente) values ('$USER_A', 'otra')"))
        assertEquals("23514", c.estadoComo("service_role", "insert into public.atribucion_usuario (usuario_id, plataforma) values ('$USER_B', 'symbian')"))
        assertEquals("23514", c.estadoComo("service_role", "insert into public.atribucion_usuario (usuario_id, fuente) values ('$USER_B', repeat('x', 501))"))
        assertEquals("23514", c.estadoComo("service_role", "insert into public.perfiles_usuario_detalle (usuario_id, edad) values ('$USER_B', 10)"))
        assertEquals("23505", c.estadoComo("service_role", "insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id) select '$USER_B', id from public.comunidades_autonomas limit 1; insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id) select '$USER_B', id from public.comunidades_autonomas limit 1"))
        // Sin cliente: ni lectura ni escritura.
        for (t in listOf("atribucion_usuario", "perfiles_usuario_detalle", "comunidades_objetivo_usuario")) {
            assertEquals("42501", c.estadoComo("authenticated", "select * from public.$t", USER_A), t)
            assertEquals("42501", c.estadoComo("anon", "select * from public.$t", USER_A), t)
        }
    }

    @Test
    fun elDiffDeEsquemaContieneSoloLasAdicionesDeclaradas(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        val antes = c.catalogo()
        Slice1Db.TODAS.forEach { c.aplicar(it) }
        val despues = c.catalogo()
        val anadido = (despues - antes).sorted()
        val quitado = (antes - despues).sorted()
        println("SCHEMA_DIFF_ADDED=" + anadido.size + " REMOVED=" + quitado.size)
        anadido.forEach { println("  + $it") }
        quitado.forEach { println("  - $it") }

        // Cada elemento anadido pertenece a un objeto declarado; nada existente cambia salvo lo declarado.
        val declarados = listOf(
            "aceptaciones_legales", "solicitudes_eliminacion_cuenta", "install_referrer", "atribucion_usuario_plataforma_chk",
            "atribucion_usuario_texto_len_chk", "perfiles_usuario_username_lower_uq", "server_owned_guard", "validar_referencias",
            "guard_perfiles_server_owned", "validate_perfiles_refs", "guard_aceptaciones_append_only", "slice1_", "app_completar_onboarding",
            "app_actualizar_perfil", "app_aceptar_documentos_legales", "app_solicitar_eliminacion_cuenta", "app_registrar_revocacion_sesiones",
            "idx_aceptaciones_legales_documento_id",
        )
        val inesperados = anadido.filter { linea -> declarados.none { linea.contains(it) } }
        assertEquals(emptyList(), inesperados, "UNEXPECTED_SCHEMA_DIFF (anadido)")
        assertEquals(emptyList(), quitado, "UNEXPECTED_SCHEMA_DIFF (quitado)")
    }

    @Test
    fun reconstruccionDesdeCeroYReaplicacionDanElMismoCatalogo(): Unit {
        val a = Slice1Db.escenario { it.catalogo() }
        val b = Slice1Db.escenario { it.catalogo() }
        assertEquals(a, b, "reconstruccion no determinista")
        val reaplicado = Slice1Db.escenario { c ->
            Slice1Db.TODAS.forEach { c.aplicar(it) }
            Slice1Db.TODAS.forEach { c.aplicar(it) }
            c.catalogo()
        }
        assertEquals(a, reaplicado, "reaplicar cambia el catalogo")
    }

    @Test
    fun rollbackCompletoEnOrdenInversoDevuelveElCatalogoAnteriorYReaplicaSinPerdidaDePrivilegios(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        val base = c.catalogo()
        Slice1Db.TODAS.forEach { c.aplicar(it) }
        val completo = c.catalogo()
        Slice1Db.TODAS.reversed().forEach { c.ejecutar(Slice0Db.rollbackDe(it)) }
        assertEquals(base, c.catalogo(), "el rollback en orden inverso debe restaurar el esquema")
        Slice1Db.TODAS.forEach { c.aplicar(it) }
        assertEquals(completo, c.catalogo())
        // Todas las cabeceras documentan las secciones exigidas.
        for (f in Slice1Db.TODAS) {
            val texto = Slice0Db.leer(f)
            for (seccion in listOf("FORWARD PLAN", "ROLLBACK", "COMPATIBILITY WITH OLD KTOR", "LOCKING", "DATA LOSS", "INDEXES", "RLS AND GRANTS", "ROLLBACK-BEGIN", "VERIFY-BEGIN", "lock_timeout = '5s'")) {
                assertTrue(texto.contains(seccion), "$f sin '$seccion'")
            }
        }
    }

    @Test
    fun ningunaMigracionDeSlice1TocaMainNiGrantsDeAuthUsers(): Unit {
        for (f in Slice1Db.TODAS) {
            val texto = Slice0Db.leer(f).lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").lowercase()
            assertTrue(!Regex("grant\\s+[a-z, ]+\\s+on\\s+(table\\s+)?auth\\.").containsMatchIn(texto), "$f concede privilegios sobre auth.*")
            assertTrue(!texto.contains("drop table public.perfiles_usuario") && !texto.contains("delete from auth.users"), f)
            assertTrue(!texto.contains("pg_cron") && !texto.contains("cron.schedule"), "$f: cron prohibido")
            assertTrue(!Regex("delete\\s+from\\s+public\\.(perfiles_usuario|solicitudes|aceptaciones)").containsMatchIn(texto), "$f: borrado fisico")
        }
    }
}
