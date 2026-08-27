package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.domain.model.TesterQuestionContext
import com.oposicontrol.admin.domain.model.TesterReportDetail
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TesterDetailModelsTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")

    private fun detail(
        authorName: String = "Arturo García",
        type: String = "pregunta_incorrecta",
        description: String = "Al finalizar el examen la respuesta marcada como correcta no coincide con el temario.",
        status: String = "pendiente",
        createdAt: String = "2026-08-23T11:48:00Z",
        question: TesterQuestionContext? = null,
    ) = TesterReportDetail(
        id = "t1",
        authorName = authorName,
        type = type,
        description = description,
        status = status,
        createdAt = createdAt,
        question = question,
    )

    @Test
    fun toDetailUi_neverTruncatesTheFullDescription() {
        val longDescription = "b".repeat(2000)
        val ui = detail(description = longDescription).toDetailUi(now)

        assertEquals(longDescription, ui.description)
    }

    @Test
    fun toDetailUi_blankDescription_fallsBackToAnHonestPlaceholder() {
        assertEquals("Sin descripción.", detail(description = "").toDetailUi(now).description)
    }

    @Test
    fun toDetailUi_missingAuthor_fallsBackToUsuario_neverAUuid() {
        assertEquals("Usuario", detail(authorName = "").toDetailUi(now).authorName)
    }

    @Test
    fun toDetailUi_mapsTheRealStatus_sameCriteriaAsTheList() {
        assertEquals(TesterReportStatusKind.PENDING, detail(status = "pendiente").toDetailUi(now).statusKind)
        assertEquals(TesterReportStatusKind.RESOLVED, detail(status = "resuelto").toDetailUi(now).statusKind)
        assertEquals(TesterReportStatusKind.DISCARDED, detail(status = "descartado").toDetailUi(now).statusKind)
        assertEquals(TesterReportStatusKind.REVIEWED, detail(status = "revisado").toDetailUi(now).statusKind)
    }

    @Test
    fun toDetailUi_relativeTime_humanizesRecentTimestamps() {
        assertEquals("hace 12 min", detail(createdAt = "2026-08-23T11:48:00Z").toDetailUi(now).timeLabel)
    }

    @Test
    fun toDetailUi_relativeTime_neverFabricatesAValue_whenTimestampIsInvalid() {
        assertEquals("", detail(createdAt = "not-a-timestamp").toDetailUi(now).timeLabel)
    }

    @Test
    fun toDetailUi_missingQuestionContext_mapsToNull_neverFabricated() {
        assertNull(detail(question = null).toDetailUi(now).question)
    }

    @Test
    fun toDetailUi_presentQuestionContext_mapsAllRealFields() {
        val question = TesterQuestionContext(code = "RAF-0042", title = "¿Cuál es el plazo?", specialtyName = "Auxiliar Administrativo")

        val ui = detail(question = question).toDetailUi(now).question

        assertEquals("RAF-0042", ui?.code)
        assertEquals("¿Cuál es el plazo?", ui?.title)
        assertEquals("Auxiliar Administrativo", ui?.specialtyName)
    }

    @Test
    fun toDetailUi_questionContextWithoutASpecialty_keepsSpecialtyNullInsteadOfAUuid() {
        val question = TesterQuestionContext(code = "RAF-0042", title = "Título", specialtyName = null)

        assertNull(detail(question = question).toDetailUi(now).question?.specialtyName)
    }
}
