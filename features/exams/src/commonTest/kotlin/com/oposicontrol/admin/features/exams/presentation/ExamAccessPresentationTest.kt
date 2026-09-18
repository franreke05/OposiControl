package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que la pestaña Acceso afirma, comprobado sin pintar.
 *
 * VISIBILIDAD ≠ ACCESO, sólo lo que el contrato sostiene (nada de suscripciones ni
 * compras que desbloqueen) y el precio sólo donde tiene sentido.
 */
class ExamAccessPresentationTest {

    private val now = Instant.parse("2026-04-15T09:00:00Z")

    private fun exam(status: ExamStatus) = Exam(
        id = "ex-1",
        code = "EX-2026-0001",
        specialtyId = "esp-1",
        specialtyName = "TCAE",
        title = "TCAE · Simulacro",
        description = "",
        typeExam = "rafaga",
        typeAccess = "gratuito",
        priceTickets = 0,
        status = status,
        publishFrom = null,
        publishedAt = if (status == ExamStatus.PUBLISHED) now else null,
        sortOrder = 0,
        questionCount = 5,
        createdAt = now,
        updatedAt = now,
    )

    private fun state(access: String, type: String = "rafaga", price: String = "0") =
        ExamsState(editorTypeAccess = access, editorTypeExam = type, editorPriceTickets = price)

    // -----------------------------------------------------------------------
    // Tipo de acceso
    // -----------------------------------------------------------------------

    @Test
    fun `gratuito y premium se reconocen y un valor desconocido no se disfraza de gratuito`() {
        assertEquals(ExamAccessKind.FREE, examAccessKind("gratuito"))
        assertEquals(ExamAccessKind.PREMIUM, examAccessKind(" Premium "))
        assertEquals(ExamAccessKind.UNKNOWN, examAccessKind(""))
        assertEquals(ExamAccessKind.UNKNOWN, examAccessKind("suscripcion"))
    }

    /** El acceso es un dato del examen: nunca se presenta como el acceso calculado de un usuario. */
    @Test
    fun `el acceso nunca se presenta como calculado para un usuario`() {
        listOf(state("gratuito"), state("premium"), state("gratuito", type = "inmersivo")).forEach { estado ->
            assertTrue(EXAM_ACCESS_NOT_CALCULATED in examAccessNotes(estado), "Falta la advertencia en $estado")
        }
    }

    /**
     * Sólo un Ráfaga gratuito se sirve sin sesión (`SupabaseV1Gateway`), y sólo
     * una vez publicado (K0-8, EX-18): la nota no puede prometer que un borrador
     * sale sin sesión.
     */
    @Test
    fun `solo un rafaga gratuito se sirve sin sesion`() {
        val rafagaGratis = examAccessNotes(state("gratuito", type = "rafaga")).joinToString(" ")
        assertTrue(rafagaGratis.contains("puede servir sus preguntas sin iniciar sesión"), rafagaGratis)
        assertTrue(
            examAccessNotes(state("gratuito", type = "rafaga")).contains(
                "Al ser Ráfaga y gratuito, una vez publicado la app puede servir sus preguntas sin iniciar sesión.",
            ),
            rafagaGratis,
        )

        val razonadoGratis = examAccessNotes(state("gratuito", type = "razonado")).joinToString(" ")
        assertTrue(razonadoGratis.contains("no se sirve sin iniciar sesión"), razonadoGratis)

        val premium = examAccessNotes(state("premium", type = "rafaga")).joinToString(" ")
        assertTrue(premium.contains("no se sirve sin iniciar sesión"), premium)
    }

    /**
     * Ni suscripciones ni compras con OposiTickets desbloquean un examen: el contrato
     * no lo sostiene. Si alguien vuelve a escribirlo en la pestaña o en las ayudas,
     * esto cae.
     */
    @Test
    fun `ni la pestana ni sus ayudas prometen desbloqueos que el contrato no tiene`() {
        val textos = listOf(state("gratuito"), state("premium"), state("x"))
            .flatMap { examAccessNotes(it) } +
            listOf(state("gratuito"), state("premium"), state("x")).map { examPriceEmptyNote(it) } +
            examAccessChoices(state("premium")).map { "${it.label} ${it.summary}" } +
            EXAM_PRICE_INERT_NOTICE +
            EXAM_PRICE_WITHOUT_EFFECT_TITLE +
            EXAM_VISIBILITY_HEADLINE +
            examPriceWithoutEffect("500") +
            Help.tipoAcceso.body +
            Help.precioExamen.body
        textos.forEach { texto ->
            val t = texto.lowercase()
            assertFalse(t.contains("suscripción"), "Promete una suscripción: «$texto»")
            assertFalse(t.contains("comprarlo"), "Promete una compra: «$texto»")
            assertFalse(t.contains("cualquier usuario puede"), "Afirma un acceso calculado: «$texto»")
            assertFalse(t.contains("desbloquea el"), "Afirma un desbloqueo funcional: «$texto»")
            assertFalse(t.contains("podrán acceder"), "Promete un acceso que no existe: «$texto»")
        }
    }

    @Test
    fun `un acceso desconocido lo dice en vez de marcar gratuito`() {
        assertEquals(listOf("Tipo de acceso sin identificar. Elige Gratuito o Premium."), examAccessNotes(state("")))
    }

    // -----------------------------------------------------------------------
    // Las dos tarjetas de acceso
    // -----------------------------------------------------------------------

    @Test
    fun `gratuito marca su tarjeta y solo la otra se puede pulsar`() {
        val choices = examAccessChoices(state("gratuito"))
        assertEquals(listOf("Gratuito", "Premium"), choices.map { it.label })
        val gratuito = choices.single { it.kind == ExamAccessKind.FREE }
        val premium = choices.single { it.kind == ExamAccessKind.PREMIUM }
        assertTrue(gratuito.selected)
        assertFalse(gratuito.selectable)
        assertFalse(premium.selected)
        assertTrue(premium.selectable)
        assertEquals("Acceso sin coste.", gratuito.summary)
    }

    @Test
    fun `premium marca su tarjeta y solo la otra se puede pulsar`() {
        val choices = examAccessChoices(state("premium"))
        val premium = choices.single { it.kind == ExamAccessKind.PREMIUM }
        assertTrue(premium.selected)
        assertFalse(premium.selectable)
        assertTrue(choices.single { it.kind == ExamAccessKind.FREE }.selectable)
        assertEquals("Acceso restringido.", premium.summary)
    }

    /** Un `tipo_acceso` que no es ninguno de los dos no marca ninguna tarjeta. */
    @Test
    fun `un acceso desconocido no marca ninguna tarjeta y las dos se pueden elegir`() {
        val choices = examAccessChoices(state("suscripcion"))
        assertTrue(choices.none { it.selected })
        assertTrue(choices.all { it.selectable })
    }

    /** El estado va en el texto, no sólo en el color. */
    @Test
    fun `la tarjeta dice si esta elegida ademas de pintarlo`() {
        val choices = examAccessChoices(state("premium"))
        assertEquals(
            "Premium. Acceso restringido. Elegido.",
            examAccessCardDescription(choices.single { it.kind == ExamAccessKind.PREMIUM }),
        )
        assertEquals(
            "Gratuito. Acceso sin coste. No elegido.",
            examAccessCardDescription(choices.single { it.kind == ExamAccessKind.FREE }),
        )
    }

    /**
     * Un examen todavía sin crear conserva lo que se ha elegido en el editor: esta
     * pestaña configura el borrador que viajará al pulsar «Crear examen».
     */
    @Test
    fun `un examen nuevo conserva el acceso y el precio del editor`() {
        val nuevo = ExamsState(selectedExamId = null, editorTypeAccess = "premium", editorPriceTickets = "1577")
        assertTrue(examAccessChoices(nuevo).single { it.kind == ExamAccessKind.PREMIUM }.selected)
        assertEquals(ExamPriceMode.PREMIUM, examPriceMode(nuevo))
        assertTrue(examPriceEnabled(nuevo.editorPriceTickets))
        assertEquals("1.577 OposiTickets", examPriceReading(nuevo.editorPriceTickets))
    }

    // -----------------------------------------------------------------------
    // Visibilidad ≠ acceso
    // -----------------------------------------------------------------------

    @Test
    fun `publicado es visible y no gratuito`() {
        val publicado = examVisibilitySummary(exam(ExamStatus.PUBLISHED))
        assertEquals("Publicado", publicado.statusLabel)
        assertTrue(publicado.text.contains("Aparece en el catálogo"))
        // La distinción «verse no es abrirse» sigue en pantalla, pero una sola vez:
        // la lleva el titular de la banda, que se ve en TODOS los estados. Dicha
        // además en esta línea, un publicado leía la misma idea dos frases seguidas.
        assertTrue(
            EXAM_VISIBILITY_HEADLINE.contains("no decide si el examen aparece en el catálogo"),
            EXAM_VISIBILITY_HEADLINE,
        )
        assertFalse(publicado.text.contains("no lo hace gratuito"), publicado.text)
    }

    @Test
    fun `borrador archivado y nuevo no aparecen en el catalogo`() {
        assertEquals("No aparece en el catálogo de la app.", examVisibilitySummary(exam(ExamStatus.DRAFT)).text)
        assertTrue(examVisibilitySummary(exam(ExamStatus.ARCHIVED)).text.contains("no aparece en el catálogo"))
        val nuevo = examVisibilitySummary(null)
        assertEquals("Sin guardar", nuevo.statusLabel)
        assertTrue(nuevo.text.contains("no aparece en ningún catálogo"))
    }

    /** La banda separa los dos conceptos y manda al único sitio donde se decide la visibilidad. */
    @Test
    fun `la banda de visibilidad separa acceso de catalogo y enlaza a publicacion`() {
        assertEquals("El tipo de acceso no decide si el examen aparece en el catálogo.", EXAM_VISIBILITY_HEADLINE)
        assertEquals("Ir a Publicación", EXAM_VISIBILITY_LINK)
        assertFalse(EXAM_VISIBILITY_HEADLINE.lowercase().contains("premium"))
    }

    // -----------------------------------------------------------------------
    // Precio sólo donde tiene sentido
    // -----------------------------------------------------------------------

    @Test
    fun `el precio solo se ofrece en premium`() {
        assertEquals(ExamPriceMode.PREMIUM, examPriceMode(state("premium", price = "1300")))
        assertEquals(ExamPriceMode.PREMIUM, examPriceMode(state("premium", price = "0")))
        assertEquals(ExamPriceMode.HIDDEN, examPriceMode(state("gratuito", price = "0")))
        assertEquals(ExamPriceMode.HIDDEN, examPriceMode(state("desconocido", price = "500")))
    }

    /** Premium con precio: se guarda, y el desbloqueo se dice que no está conectado. */
    @Test
    fun `premium con precio lo guarda y no promete desbloqueo`() {
        val premium = state("premium", price = "1577")
        assertEquals(ExamPriceMode.PREMIUM, examPriceMode(premium))
        assertEquals("1.577 OposiTickets", examPriceReading(premium.editorPriceTickets))
        assertTrue(EXAM_PRICE_INERT_NOTICE.contains("no desbloquea nada"))
    }

    /** Un precio guardado en un gratuito no se esconde: se dice que no hace nada. */
    @Test
    fun `un gratuito con precio guardado lo ensena como sin efecto`() {
        assertEquals(ExamPriceMode.FREE_WITH_PRICE, examPriceMode(state("gratuito", price = "500")))
        assertEquals(
            "El examen es gratuito: su precio guardado de 500 OposiTickets no tiene ningún efecto.",
            examPriceWithoutEffect("500"),
        )
        assertEquals("Precio guardado · sin efecto", EXAM_PRICE_WITHOUT_EFFECT_TITLE)
        // Formateado igual que en todas partes.
        assertTrue(examPriceWithoutEffect("1577").contains("1.577 OposiTickets"), examPriceWithoutEffect("1577"))
    }

    /** Sin precio que pedir, el bloque no desaparece: dice por qué no aplica. */
    @Test
    fun `sin precio el bloque explica por que en vez de esfumarse`() {
        assertEquals(
            "El examen es gratuito: no tiene precio en OposiTickets.",
            examPriceEmptyNote(state("gratuito")),
        )
        assertEquals("Elige antes el tipo de acceso.", examPriceEmptyNote(state("")))
    }

    /** Borrar el número para escribir otro no apaga el precio: sólo el cero lo apaga. */
    @Test
    fun `el interruptor del precio solo se apaga con cero`() {
        assertFalse(examPriceEnabled("0"))
        assertFalse(examPriceEnabled(" 00 "))
        assertTrue(examPriceEnabled(""))
        assertTrue(examPriceEnabled("1300"))
        assertTrue(examPriceEnabled("mil"))
    }

    @Test
    fun `el apoyo del precio dice que escribir y por que no se guardaria`() {
        assertEquals("Escribe cuántos OposiTickets cuesta, o apaga el precio.", examPriceSupport(""))
        assertEquals("Escribe el precio con cifras.", examPriceSupport("mil"))
        assertEquals("El precio no puede ser negativo.", examPriceSupport("-3"))
        assertEquals("En OposiTickets, sin decimales.", examPriceSupport("1300"))
    }

    @Test
    fun `el precio de hoy no desbloquea nada y se dice`() {
        assertTrue(EXAM_PRICE_INERT_NOTICE.contains("no desbloquea nada"))
        assertNull(Regex("\\d").find(EXAM_PRICE_INERT_NOTICE), "El aviso no puede inventar una cifra")
    }

    // -----------------------------------------------------------------------
    // La cifra: sólo presentación
    // -----------------------------------------------------------------------

    /**
     * El separador de millar es SÓLO presentación: lo que se escribe y lo que
     * viaja al guardar siguen siendo los dígitos sin puntuar.
     */
    @Test
    fun `los tickets se agrupan de tres en tres sin tocar el valor`() {
        assertEquals("0", formatTickets(0))
        assertEquals("999", formatTickets(999))
        assertEquals("1.577", formatTickets(1577))
        assertEquals("10.000", formatTickets(10_000))
        assertEquals("1.234.567", formatTickets(1_234_567))
        assertEquals("-1.577", formatTickets(-1577))
    }

    /** Sin cifra legible no se inventa un cero: no hay lectura. */
    @Test
    fun `la lectura del precio solo existe con una cantidad real`() {
        assertEquals("1.577 OposiTickets", examPriceReading("1577"))
        assertEquals("1.577 OposiTickets", examPriceReading(" 1577 "))
        assertNull(examPriceReading(""))
        assertNull(examPriceReading("mil"))
        assertNull(examPriceReading("0"))
        assertNull(examPriceReading("-5"))
    }

    /** No hay símbolo monetario inventado: son tickets, no euros. */
    @Test
    fun `la unidad es oposi tickets y no una moneda`() {
        assertEquals("OposiTickets", EXAM_TICKETS_UNIT)
        listOf(examPriceReading("1577"), examPriceSupport("1300"), examPriceWithoutEffect("500")).forEach { texto ->
            assertFalse(texto!!.contains("€"), texto)
            assertFalse(texto.contains("EUR"), texto)
        }
    }

    // -----------------------------------------------------------------------
    // Ayuda de duración (DEBT-034), que vive en el mismo catálogo
    // -----------------------------------------------------------------------

    @Test
    fun `la ayuda de duracion no afirma un limite ni un comportamiento`() {
        val body = Help.duracionExamen.body.lowercase()
        assertFalse(body.contains("límite"), Help.duracionExamen.body)
        assertFalse(body.contains("dispone el usuario"), Help.duracionExamen.body)
        assertEquals("Duración del examen en minutos. Déjala vacía si no quieres fijar una duración.", Help.duracionExamen.body)
    }
}
