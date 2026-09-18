package com.oposicontrol.admin.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * El catálogo de ayuda tiene una sola regla y es fácil de romper sin darse
 * cuenta: se escribe para quien administra el producto, no para quien lo
 * programó. Estas pruebas la fijan mecánicamente en lo que sí se puede medir —
 * longitud, jerga y forma— para que un texto nuevo no se cuele por descuido.
 */
class HelpCatalogTest {

    private val topics: List<HelpTopic> = listOf(
        Help.notaMedia, Help.evolucion, Help.percentil, Help.racha, Help.liga, Help.ranking,
        Help.oposiTickets, Help.saldo, Help.ledger, Help.idempotencia, Help.renovacion,
        Help.consumibles, Help.potenciadores, Help.protectores, Help.packs, Help.promociones,
        Help.reglasPopup, Help.atribucion, Help.conversion, Help.importacion, Help.duplicados,
        Help.publicacionAtomica, Help.capacidadesEspecialidad, Help.recursosInmersiva,
        Help.estadoSistema, Help.entorno,
        // Las cuatro de Exámenes que el workspace reescribió para no afirmar
        // comportamientos sin verificar (DEBT-034, acceso y estado): entran en la
        // misma regla que el resto para que un texto nuevo no se cuele sin ella.
        Help.duracionExamen, Help.tipoAcceso, Help.precioExamen, Help.estadoExamen,
        // Reescrita en la ronda 3a (K2-2): entra en la misma regla.
        Help.numeroPreguntas,
    )

    /**
     * K2-2: desde M1 el número de preguntas lo mantiene la base. La ayuda no puede
     * seguir diciendo que la consola lo cuenta al abrir el examen, ni dejar de decir
     * qué preguntas cuentan (las activas vinculadas).
     */
    @Test
    fun `el numero de preguntas dice quien lo mantiene y que preguntas cuenta`() {
        val body = Help.numeroPreguntas.body
        assertTrue(body.contains("preguntas activas vinculadas"), body)
        assertTrue(body.contains("Lo mantiene la base de datos"), body)
        assertTrue(!body.contains("lo cuenta cada vez"), body)
    }

    /** K0-8 (EX-18): sin sesión sólo se sirve un Ráfaga gratuito YA publicado. */
    @Test
    fun `el acceso sin sesion exige que el examen este publicado`() {
        assertTrue(Help.tipoAcceso.body.contains("gratuito y publicado"), Help.tipoAcceso.body)
    }

    @Test
    fun `ninguna ayuda usa jerga tecnica`() {
        // §18: si aparece una de estas palabras, el texto está describiendo la
        // implementación en vez del significado.
        val jerga = listOf(
            "endpoint", "api", "rpc", "backend", "query", "json", "payload",
            "token", "cache", "hash", "índice", "indice", "tabla sql", "schema",
            "idempotente", "transacción", "commit", "rollback", "nullable",
            "boolean", "enum", "timestamp", "uuid", "supabase", "ktor",
        )
        topics.forEach { topic ->
            val texto = (topic.title + " " + topic.body + " " + topic.example.orEmpty()).lowercase()
            jerga.forEach { termino ->
                assertTrue(
                    !texto.contains(termino),
                    "«${topic.title}» usa el tecnicismo '$termino': ${topic.body}",
                )
            }
        }
    }

    @Test
    fun `cada ayuda cabe en un popover sin convertirse en un manual`() {
        // §16 y §21: una explicación de nivel 2 son un par de frases. Si no cabe,
        // el problema está en la pantalla, no en el texto.
        topics.forEach { topic ->
            assertTrue(
                topic.body.length in 40..320,
                "«${topic.title}» mide ${topic.body.length} caracteres; se espera entre 40 y 320",
            )
        }
    }

    @Test
    fun `los titulos son cortos y no son frases`() {
        topics.forEach { topic ->
            assertTrue(topic.title.length <= 40, "Título demasiado largo: «${topic.title}»")
            assertTrue(!topic.title.endsWith("."), "El título no lleva punto: «${topic.title}»")
            assertTrue(topic.title.isNotBlank(), "Título vacío")
        }
    }

    @Test
    fun `los cuerpos son frases completas`() {
        topics.forEach { topic ->
            val ultimo = topic.body.trimEnd().lastOrNull()
            if (ultimo != '.') {
                fail("«${topic.title}» no termina en punto: ...${topic.body.takeLast(30)}")
            }
        }
    }

    @Test
    fun `no hay dos ayudas con el mismo titulo`() {
        val duplicados = topics.groupBy { it.title }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet(), duplicados, "Títulos de ayuda repetidos")
    }

    @Test
    fun `la ayuda global cubre las areas que se nombran en el menu`() {
        val titulos = globalHelpSections.map { it.title }
        listOf("Contenido", "Usuarios", "OposiTickets", "Market", "Analítica").forEach { area ->
            assertTrue(titulos.contains(area), "Falta el área '$area' en la ayuda global")
        }
    }

    @Test
    fun `la ayuda de datos ausentes no promete un cero`() {
        // §62: «no conectado» nunca puede leerse como «cero».
        val estado = Help.estadoSistema.title + " " + Help.estadoSistema.body +
            " " + Help.estadoSistema.example.orEmpty()
        assertTrue(
            estado.contains("No conectado", ignoreCase = true),
            "La ayuda de estado del sistema debe explicar qué significa «no conectado»",
        )
    }
}
