package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import kotlin.test.*

class NewsPresentationTest {
    @Test fun unknownStateNeverClaimsPublished() { assertEquals("Sin identificar", newsStatusLabel("active")) }
    @Test fun catalogFailureNeverLeaksIdOrSelectsDefault() {
        assertEquals("No disponible", catalogName("123", UiAsync.Error(AppError.Validation("failure"))))
        assertEquals("Sin identificar", catalogName("123", UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE")))))
    }
    @Test fun catalogDisplaysHumanName() { assertEquals("TCAE", catalogName("1", UiAsync.Success(listOf(NewsCatalogOption("1", "TCAE"))))) }
    @Test fun scheduleRejectsImpossibleDate() { assertNull(newsScheduleIso("31/02/2026", "09:00")); assertNull(newsScheduleIso("12/09/2026", "25:00")) }
    @Test fun scheduleConvertsHumanInputToUtc() { assertEquals("2026-09-12T09:30:00Z", newsScheduleIso("12/09/2026", "09:30")) }
    @Test fun panesFitAndLeaveReadableEditor() {
        listOf(1920, 1600, 1366, 1080).forEach { window ->
            val width = window - 292
            val sizes = newsPaneSizes(width)
            val gaps = if (sizes.rail > 0) 32 else if (sizes.library > 0) 16 else 0
            assertEquals(width, sizes.library + sizes.editor + sizes.rail + gaps)
            assertTrue(sizes.editor >= 500)
        }
    }
    @Test fun localFileNameDoesNotExposeDirectories() { assertEquals("portada.png", newsAssetName("file://C:/Users/private/portada.png")) }
    @Test fun editsDoNotClaimSaved() { assertEquals("Cambios sin guardar", NewsState(isDirty = true, actionState = UiAsync.Success(Unit)).saveLabel()) }
}
