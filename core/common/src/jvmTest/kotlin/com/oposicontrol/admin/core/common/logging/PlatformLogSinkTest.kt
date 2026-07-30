package com.oposicontrol.admin.core.common.logging

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformLogSinkTest {
    @Test
    fun `safe stacktrace keeps type and frames but drops messages`() {
        val cause = IllegalArgumentException(
            "Bearer token.value.signature user@example.com password=private",
        )
        val failure = IllegalStateException(
            "response body with private data",
            cause,
        )

        val rendered = failure.toSafeStackTrace()

        assertEquals(1, "stacktrace=".toRegex().findAll(rendered).count())
        assertTrue("java.lang.IllegalStateException" in rendered)
        assertTrue("causedBy=java.lang.IllegalArgumentException" in rendered)
        assertTrue("\n\tat " in rendered)
        assertFalse("response body" in rendered)
        assertFalse("token.value.signature" in rendered)
        assertFalse("user@example.com" in rendered)
        assertFalse("private" in rendered)
    }

    @Test
    fun `jvm sink emits a single safe stacktrace block`() {
        val originalError = System.err
        val output = ByteArrayOutputStream()
        System.setErr(PrintStream(output))
        try {
            PlatformLogSink.write(
                level = LogLevel.ERROR,
                tag = LogTag.NETWORK.value,
                message = "event=request.failure layer=network errorType=IllegalStateException",
                throwable = IllegalStateException("secret=must-not-appear"),
            )
        } finally {
            System.setErr(originalError)
        }

        val rendered = output.toString()
        assertEquals(1, "stacktrace=".toRegex().findAll(rendered).count())
        assertEquals(1, "event=request.failure".toRegex().findAll(rendered).count())
        assertFalse("must-not-appear" in rendered)
    }
}
