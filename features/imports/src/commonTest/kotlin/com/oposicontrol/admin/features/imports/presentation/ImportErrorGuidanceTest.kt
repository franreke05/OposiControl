package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.result.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportErrorGuidanceTest {
    private val codes = listOf(
        "IMPORT_EXAM_CODE_MISMATCH", "IMPORT_LOT_DUPLICATE", "IMPORT_RESERVE_REQUIRES_OFFICIAL_EXAM", "IMPORT_MAIN_AFTER_RESERVE",
        "IMPORT_BLOCK_CONFLICT", "IMPORT_DEMO_CONFLICT", "IMPORT_OFFICIAL_RULES_INVALID",
    )

    @Test
    fun everyFa37RejectionHasItsOwnOperatorMessageAndKeepsTheCode() {
        codes.forEach { code ->
            val mapped = AppError.Validation("mensaje genérico del servidor", code = code).withImportGuidance() as AppError.Validation
            assertEquals(code, mapped.code)
            assertTrue(mapped.message != "mensaje genérico del servidor" && mapped.message.length > 40, code)
        }
        assertEquals(codes.size, IMPORT_ERROR_GUIDANCE.size)
    }

    @Test
    fun theCodeMatchIsCaseInsensitive() {
        val mapped = AppError.Validation("x", code = "import_lot_duplicate").withImportGuidance() as AppError.Validation
        assertTrue(mapped.message.contains("ya se importó"))
    }

    @Test
    fun unknownCodesAndOtherErrorKindsPassThroughUntouched() {
        val unknown = AppError.Validation("otro", code = "IMPORT_PENDING_REVIEW")
        assertSame(unknown, unknown.withImportGuidance())
        val noCode = AppError.Validation("sin código")
        assertSame(noCode, noCode.withImportGuidance())
        val network = AppError.Unknown("red")
        assertSame(network, network.withImportGuidance())
    }
}
