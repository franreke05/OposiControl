package com.oposicontrol.dbtest

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canonicalizacion probada EJECUTANDOLA, no leyendola.
 *
 * Sustituye a «el literal aparece en el SQL». La autoridad es el resultado que
 * devuelve PostgreSQL comparado con el que devuelve Kotlin.
 */
class RoleSyncCanonicalizationTest {

    /** Los codigos administrativos reales del dominio. */
    private val codigos = listOf("admin", "superadmin", "moderador", "usuario")

    /** Whitespace que Kotlin `trim()` SI recorta -- `Char.isWhitespace()`. */
    private val recortables = listOf(
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
        0x001C, 0x001D, 0x001E, 0x001F,
        0x0020, 0x00A0, 0x1680,
        0x2000, 0x2007, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    )

    /** Caracteres que NO son whitespace para Kotlin y no deben recortarse. */
    private val noRecortables = listOf(0x0085, 0x180E, 0x200B, 0xFEFF)

    /** El espejo Kotlin, tal y como vive en `RoleSyncInvariants`. */
    private fun canonicalKotlin(entrada: String?): String = (entrada ?: "").trim().lowercase()

    @Test
    fun laMatrizKotlinSqlCoincideParaLosCodigosDelDominio(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()

        val casos = buildList {
            codigos.forEach { codigo ->
                add(codigo)
                add(codigo.uppercase())
                add(codigo.replaceFirstChar { it.uppercase() })
                recortables.forEach { cp ->
                    val ws = String(Character.toChars(cp))
                    add(ws + codigo)
                    add(codigo + ws)
                    add(ws + codigo + ws)
                    add(ws + codigo.uppercase() + ws)
                }
                noRecortables.forEach { cp ->
                    val raro = String(Character.toChars(cp))
                    add(raro + codigo)
                    add(codigo + raro)
                }
            }
        }

        val divergencias = casos.filter { c.canonical(it) != canonicalKotlin(it) }
        assertTrue(
            divergencias.isEmpty(),
            "SQL y Kotlin canonicalizan distinto en ${divergencias.size} casos: " +
                divergencias.take(5).joinToString { "[${it.map { ch -> ch.code.toString(16) }}]" },
        )
        assertEquals(canonicalKotlin(null), c.canonical(null), "NULL debe canonicalizar igual en ambos lados.")
    }

    @Test
    fun losVeinteEspaciosSeRecortanRealmenteEnPostgres(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        recortables.forEach { cp ->
            val ws = String(Character.toChars(cp))
            assertEquals(
                "superadmin",
                c.canonical(ws + "superadmin" + ws),
                "U+%04X no se recorta en PostgreSQL.".format(cp),
            )
        }
    }

    @Test
    fun losQueNoSonEspacioNoSeRecortan(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        noRecortables.forEach { cp ->
            val raro = String(Character.toChars(cp))
            assertEquals(
                canonicalKotlin(raro + "superadmin"),
                c.canonical(raro + "superadmin"),
                "U+%04X diverge entre Kotlin y SQL.".format(cp),
            )
        }
    }

    @Test
    fun elCasingSeBajaIgualEnAmbosLados(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        listOf("SuperAdmin", "SUPERADMIN", "sUpErAdMiN", "Admin", "ADMIN").forEach {
            assertEquals(canonicalKotlin(it), c.canonical(it), "casing de '$it'")
        }
    }
}

/** Llama al canonicalizador instalado. Parametrizado: sin escapes ni literales. */
fun Connection.canonical(entrada: String?): String? =
    prepareStatement("select public.admin_role_code_canonical(?)").use { ps ->
        ps.setString(1, entrada)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
