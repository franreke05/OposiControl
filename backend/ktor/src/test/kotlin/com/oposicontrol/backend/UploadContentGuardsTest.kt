package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lo que se entrega a ffmpeg tiene que EMPEZAR como el contenedor que dice ser, diga lo que diga el MIME declarado. */
class UploadContentGuardsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a playlist labelled as video is not a video container`() {
        assertFalse("#EXTM3U\n#EXTINF:1,\n/etc/passwd\n".toByteArray().looksLikeVideoContainer())
        assertFalse("ffconcat version 1.0\nfile '/app/secret.mp4'\n".toByteArray().looksLikeVideoContainer())
        assertFalse(ByteArray(0).looksLikeVideoContainer())
    }

    @Test
    fun `mp4, webm and avi headers are recognised`() {
        assertTrue((bytes(0, 0, 0, 0x18) + "ftypisom".toByteArray()).looksLikeVideoContainer())
        assertTrue(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01).looksLikeVideoContainer())
        assertTrue(("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "AVI LIST".toByteArray()).looksLikeVideoContainer())
    }

    @Test
    fun `only a RIFF WAVE file is treated as wav`() {
        assertTrue(("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WAVEfmt ".toByteArray()).looksLikeWav())
        assertFalse("#EXTM3U".toByteArray().looksLikeWav())
    }
}
