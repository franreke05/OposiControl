package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.domain.model.ExamStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedExamsPresentationTest {

    @Test
    fun `solo se ensenan los publicados y no borrados`() {
        val all = listOf(
            exam("pub"),
            exam("draft", status = ExamStatus.DRAFT),
            exam("arch", status = ExamStatus.ARCHIVED),
            exam("gone", deleted = true),
        )
        assertEquals(listOf("pub"), publishedExams(all).map { it.id })
    }

    @Test
    fun `se ordena por especialidad, orden manual y titulo`() {
        val all = listOf(
            exam("c", specialty = "Celador", title = "B", sortOrder = 0),
            exam("a2", specialty = "Administrativo", title = "Z", sortOrder = 2),
            exam("a1", specialty = "Administrativo", title = "Y", sortOrder = 1),
            exam("a1b", specialty = "administrativo", title = "A", sortOrder = 1),
        )
        // La especialidad se compara sin distinguir mayusculas.
        assertEquals(listOf("a1b", "a1", "a2", "c"), publishedExams(all).map { it.id })
    }

    @Test
    fun `el filtro por tipo usa el tipo canonico y el legacy guiado es razonado`() {
        val all = listOf(
            exam("i", type = "inmersivo"),
            exam("r", type = "rafaga"),
            exam("z", type = "razonado"),
            exam("g", type = "guiado"),
        )
        assertEquals(listOf("z", "g"), filterPublishedExams(all, ExamTypeFilter.RAZONADO, "").map { it.id })
        assertEquals(listOf("i"), filterPublishedExams(all, ExamTypeFilter.INMERSIVO, "").map { it.id })
        assertEquals(4, filterPublishedExams(all, ExamTypeFilter.ALL, "").size)
    }

    @Test
    fun `la busqueda mira titulo, especialidad y codigo sin distinguir mayusculas ni espacios`() {
        val all = listOf(
            exam("1", title = "Simulacro Mayo", specialty = "TCAE", code = "EX-1"),
            exam("2", title = "Otro", specialty = "Celador", code = "EX-2"),
        )
        assertEquals(listOf("1"), filterPublishedExams(all, ExamTypeFilter.ALL, "  simulacro ").map { it.id })
        assertEquals(listOf("2"), filterPublishedExams(all, ExamTypeFilter.ALL, "CELADOR").map { it.id })
        assertEquals(listOf("2"), filterPublishedExams(all, ExamTypeFilter.ALL, "ex-2").map { it.id })
        assertEquals(emptyList(), filterPublishedExams(all, ExamTypeFilter.ALL, "nada").map { it.id })
    }

    @Test
    fun `los recuentos son del total publicado y no dependen de la busqueda`() {
        val all = listOf(exam("1", type = "rafaga"), exam("2", type = "inmersivo"), exam("3", type = "inmersivo"))
        val counts = publishedExamCounts(all)
        assertEquals(3, counts[ExamTypeFilter.ALL])
        assertEquals(1, counts[ExamTypeFilter.RAFAGA])
        assertEquals(0, counts[ExamTypeFilter.RAZONADO])
        assertEquals(2, counts[ExamTypeFilter.INMERSIVO])
    }

    @Test
    fun `el acceso distingue gratuito, premium con precio y premium sin precio`() {
        assertEquals("Gratuito", exam("a", access = "gratuito", tickets = 5).accessLabel())
        assertEquals("Premium · 12 OT", exam("b", access = "premium", tickets = 12).accessLabel())
        assertEquals("Premium", exam("c", access = "premium", tickets = 0).accessLabel())
    }

    @Test
    fun `la linea de datos solo incluye lo que se sabe`() {
        assertEquals("1 pregunta", exam("a", questions = 1).factsLine())
        assertEquals(
            "120 preguntas · 90 min · 2026 · Oficial",
            exam("b", questions = 120, duration = 90, year = 2026, official = true).factsLine(),
        )
        // Duracion 0 no es "0 min": es que no se sabe.
        assertEquals("50 preguntas", exam("c", questions = 50, duration = 0).factsLine())
    }

    @Test
    fun `la portada solo enseña convocatoria y oficial si se saben`() {
        assertEquals("", coverDetailLine(exam("a")))
        assertEquals("Convocatoria 2026", coverDetailLine(exam("b", year = 2026)))
        assertEquals("Convocatoria 2025 · Oficial", coverDetailLine(exam("c", year = 2025, official = true)))
        assertEquals("Oficial", coverDetailLine(exam("d", official = true)))
    }
}
