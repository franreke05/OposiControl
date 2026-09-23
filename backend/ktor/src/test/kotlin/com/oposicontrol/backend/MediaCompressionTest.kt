package com.oposicontrol.backend

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Lo que se fija aquí no es "que comprima": es que **nunca se pierda el archivo
 * del usuario** y que el MIME que se devuelve describa de verdad los bytes que
 * se devuelven.
 *
 * Ninguna prueba necesita `ffmpeg` instalado: el binario entra por
 * [FfmpegRunner], que es la razón de que esa costura exista. En una CI sin
 * ffmpeg estas pruebas pasan igual, y eso es exactamente el comportamiento que
 * tendrá el servidor si alguien despliega una imagen sin él.
 */
class MediaCompressionTest {

    // -----------------------------------------------------------------
    // Imagen
    // -----------------------------------------------------------------

    @Test fun unaImagenRealSeComprimeYPesaMenos() {
        val original = pngBytes(width = 1600, height = 1200)

        val result = MediaCompression.compress(original, "image/png", allowFormatChange = true)

        assertEquals(MediaCompressionOutcome.COMPRESSED, result.outcome)
        assertEquals("image/jpeg", result.mimeType)
        assertTrue(
            result.bytes.size < original.size,
            "el resultado (${result.bytes.size} B) tiene que pesar menos que el original (${original.size} B)",
        )
        assertMimeMatchesBytes(result)
        // Y sigue siendo la misma imagen, no un fichero roto más pequeño.
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        assertEquals(1600, decoded.width)
        assertEquals(1200, decoded.height)
    }

    @Test fun unaImagenQueSuperaElLadoMaximoSeRedimensiona() {
        val original = pngBytes(width = 4000, height = 3000)

        val result = MediaCompression.compress(original, "image/png", allowFormatChange = true)

        assertEquals(MediaCompressionOutcome.COMPRESSED, result.outcome)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        assertEquals(MediaCompression.IMAGE_MAX_SIDE, decoded.width)
        assertEquals(1536, decoded.height, "la proporción se conserva: 4000x3000 -> 2048x1536")
        assertMimeMatchesBytes(result)
    }

    /**
     * El caso que convierte una "optimización" en un problema: una imagen ya
     * diminuta cabe entera en las tablas de cuantización de un JPEG, así que
     * recomprimirla la ENGORDA. Tiene que ganar el original, byte a byte.
     */
    @Test fun unaImagenDiminutaNoSeHincha() {
        val original = pngBytes(width = 8, height = 8, noise = false)

        val result = MediaCompression.compress(original, "image/png", allowFormatChange = true)

        assertEquals(MediaCompressionOutcome.SKIPPED_NOT_SMALLER, result.outcome)
        assertEquals("image/png", result.mimeType)
        assertContentEquals(original, result.bytes)
        assertMimeMatchesBytes(result)
    }

    /** Transparencia a JPEG = fondo blanco donde no había nada. Eso no es comprimir, es romper. */
    @Test fun unaImagenConTransparenciaNuncaAcabaEnJpeg() {
        val original = pngBytes(width = 2600, height = 1800, translucent = true)

        val result = MediaCompression.compress(original, "image/png", allowFormatChange = true)

        assertNotEquals("image/jpeg", result.mimeType)
        assertEquals("image/png", result.mimeType)
        assertMimeMatchesBytes(result)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        assertTrue(decoded.colorModel.hasAlpha(), "el canal alfa sigue ahí")
    }

    /** Si el punto de subida no puede renombrar el objeto, el formato no se toca. */
    @Test fun sinPermisoParaCambiarDeFormatoUnPngSigueSiendoPng() {
        val original = pngBytes(width = 4000, height = 3000)

        val result = MediaCompression.compress(original, "image/png", allowFormatChange = false)

        assertEquals("image/png", result.mimeType)
        assertMimeMatchesBytes(result)
    }

    /** La JVM no codifica WebP y no se va a añadir una librería para ello: pasa intacto. */
    @Test fun unWebpPasaIntactoPorqueLaJvmNoSabeLeerlo() {
        val original = "RIFF....WEBPVP8 ".toByteArray()

        val result = MediaCompression.compress(original, "image/webp", allowFormatChange = true)

        assertEquals("image/webp", result.mimeType)
        assertContentEquals(original, result.bytes)
    }

    /** Un GIF animado reencodado con `ImageIO` sería una foto fija. No se toca. */
    @Test fun unGifPasaIntacto() {
        val original = "GIF89a".toByteArray() + ByteArray(64) { it.toByte() }

        val result = MediaCompression.compress(original, "image/gif", allowFormatChange = true)

        assertEquals(MediaCompressionOutcome.SKIPPED_ANIMATED, result.outcome)
        assertContentEquals(original, result.bytes)
        assertMimeMatchesBytes(result)
    }

    /**
     * La orientación EXIF se aplica a los PÍXELES: al reencodificar se pierde la
     * etiqueta, así que si no se aplicara, toda foto vertical de móvil se
     * publicaría tumbada.
     */
    @Test fun laOrientacionExifSeConservaGirandoLosPixeles() {
        val apaisada = jpegWithExifOrientation(width = 600, height = 400, orientation = 6)
        assertEquals(6, MediaCompression.exifOrientation(apaisada), "la etiqueta EXIF se lee del APP1")

        val result = MediaCompression.compress(apaisada, "image/jpeg", allowFormatChange = true)

        assertEquals(MediaCompressionOutcome.COMPRESSED, result.outcome)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        assertEquals(400, decoded.width, "600x400 con orientación 6 se muestra como 400x600")
        assertEquals(600, decoded.height)
        assertMimeMatchesBytes(result)
    }

    @Test fun unJpegSinExifSeQuedaComoEsta() {
        assertEquals(1, MediaCompression.exifOrientation(jpegBytes(64, 64)))
        assertEquals(1, MediaCompression.exifOrientation(byteArrayOf(1, 2, 3)))
        assertEquals(1, MediaCompression.exifOrientation(ByteArray(0)))
    }

    // -----------------------------------------------------------------
    // PDF
    // -----------------------------------------------------------------

    /** Se descarga directo desde Storage: cualquier reenvasado rompe al visor. */
    @Test fun unPdfPasaByteAByte() {
        val original = ("%PDF-1.4\n%âãÏÓ\n" + "1 0 obj\n<<>>\nendobj\n".repeat(400) + "%%EOF")
            .toByteArray(Charsets.ISO_8859_1)

        val result = MediaCompression.compress(original, "application/pdf", allowFormatChange = true)

        assertEquals(MediaCompressionOutcome.SKIPPED_PDF, result.outcome)
        assertEquals("application/pdf", result.mimeType)
        assertContentEquals(original, result.bytes)
        assertTrue(result.bytes === original, "ni siquiera se copia: son los mismos bytes")
        assertMimeMatchesBytes(result)
    }

    // -----------------------------------------------------------------
    // Vídeo y audio: ffmpeg inyectado, sin binarios en la CI
    // -----------------------------------------------------------------

    /**
     * El modo de fallo más probable en producción: se despliega una imagen de
     * Docker sin ffmpeg. El vídeo tiene que subir intacto y sin excepciones.
     */
    @Test fun sinFfmpegElVideoVuelveIntactoYNoLanza() {
        val original = fakeMp4()
        val ffmpeg = FakeFfmpeg(available = false)

        val result = MediaCompression.compress(original, "video/mp4", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.SKIPPED_NO_FFMPEG, result.outcome)
        assertEquals("video/mp4", result.mimeType)
        assertContentEquals(original, result.bytes)
        assertEquals(0, ffmpeg.calls, "si no está disponible, ni se intenta ejecutar")
        assertMimeMatchesBytes(result)
    }

    @Test fun sinFfmpegElAudioVuelveIntactoYNoLanza() {
        val original = fakeWav()
        val ffmpeg = FakeFfmpeg(available = false)

        val result = MediaCompression.compress(original, "audio/wav", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.SKIPPED_NO_FFMPEG, result.outcome)
        assertEquals("audio/wav", result.mimeType)
        assertContentEquals(original, result.bytes)
        assertMimeMatchesBytes(result)
    }

    /** ffmpeg está, pero sale mal (código != 0, o se mató por timeout): gana el original. */
    @Test fun unFfmpegQueFallaDevuelveElOriginal() {
        val original = fakeMp4()
        val ffmpeg = FakeFfmpeg(available = true, succeeds = false)

        val result = MediaCompression.compress(original, "video/mp4", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.FAILED, result.outcome)
        assertContentEquals(original, result.bytes)
        assertEquals("video/mp4", result.mimeType)
        assertEquals(1, ffmpeg.calls)
    }

    /** ffmpeg termina bien pero deja el fichero vacío: eso tampoco es un vídeo. */
    @Test fun unFfmpegQueNoEscribeNadaDevuelveElOriginal() {
        val original = fakeMp4()
        val ffmpeg = FakeFfmpeg(available = true, output = ByteArray(0))

        val result = MediaCompression.compress(original, "video/mp4", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.FAILED, result.outcome)
        assertContentEquals(original, result.bytes)
    }

    /** Transcodificar no puede EMPEORAR: si el resultado pesa más, se sube el original. */
    @Test fun unFfmpegQueEngordaElVideoDevuelveElOriginal() {
        val original = fakeMp4()
        val ffmpeg = FakeFfmpeg(available = true, output = fakeMp4(payload = 8_000))

        val result = MediaCompression.compress(original, "video/mp4", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.SKIPPED_NOT_SMALLER, result.outcome)
        assertContentEquals(original, result.bytes)
        assertMimeMatchesBytes(result)
    }

    @Test fun unFfmpegQueComprimeDevuelveMp4ConLosBytesNuevos() {
        val original = fakeMp4(payload = 4_000)
        val comprimido = fakeMp4(payload = 500)
        val ffmpeg = FakeFfmpeg(available = true, output = comprimido)

        val result = MediaCompression.compress(original, "video/mp4", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.COMPRESSED, result.outcome)
        assertEquals("video/mp4", result.mimeType)
        assertContentEquals(comprimido, result.bytes)
        assertMimeMatchesBytes(result)
        // Los argumentos que de verdad se le pasan al binario.
        val args = ffmpeg.lastArgs.joinToString(" ")
        assertTrue(args.contains("-crf 23"), args)
        assertTrue(args.contains("libx264"), args)
        assertTrue(args.contains("-movflags +faststart"), args)
        assertTrue(args.contains("-b:a 128k"), args)
        assertTrue(args.contains("force_original_aspect_ratio=decrease"), args)
    }

    @Test fun unWavSeTranscodificaAAacYElMimeCambiaConLosBytes() {
        val original = fakeWav(payload = 40_000)
        val comprimido = fakeM4a()
        val ffmpeg = FakeFfmpeg(available = true, output = comprimido)

        val result = MediaCompression.compress(original, "audio/wav", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.COMPRESSED, result.outcome)
        assertEquals("audio/mp4", result.mimeType)
        assertContentEquals(comprimido, result.bytes)
        assertMimeMatchesBytes(result)
    }

    /** Un MP3 ya es lossy: reencodarlo a AAC pierde calidad para no ganar tamaño. */
    @Test fun unMp3NoSeReencoda() {
        val original = ByteArray(9_000) { (it % 251).toByte() }
        val ffmpeg = FakeFfmpeg(available = true, output = ByteArray(10))

        val result = MediaCompression.compress(original, "audio/mpeg", allowFormatChange = true, ffmpeg = ffmpeg)

        assertEquals(MediaCompressionOutcome.SKIPPED_ALREADY_LOSSY, result.outcome)
        assertContentEquals(original, result.bytes)
        assertEquals(0, ffmpeg.calls)
    }

    /** Sin permiso para cambiar de contenedor, un WAV no se puede volver M4A. */
    @Test fun sinPermisoParaCambiarDeFormatoUnWavNoSeToca() {
        val original = fakeWav()
        val ffmpeg = FakeFfmpeg(available = true, output = fakeM4a())

        val result = MediaCompression.compress(original, "audio/wav", allowFormatChange = false, ffmpeg = ffmpeg)

        assertEquals("audio/wav", result.mimeType)
        assertContentEquals(original, result.bytes)
        assertEquals(0, ffmpeg.calls)
    }

    // -----------------------------------------------------------------
    // El invariante transversal
    // -----------------------------------------------------------------

    /**
     * El MIME devuelto describe SIEMPRE los bytes devueltos, pase lo que pase.
     * Si esto se rompe, Storage sirve un `.png` que en realidad es un JPEG y la
     * mitad de los visores no lo abren.
     */
    @Test fun elMimeDevueltoSiempreCorrespondeALosBytesDevueltos() {
        // Imágenes pequeñas a propósito: el invariante no depende del tamaño y
        // esta prueba recorre dieciocho combinaciones.
        val casos = listOf(
            pngBytes(800, 600) to "image/png",
            pngBytes(8, 8, noise = false) to "image/png",
            pngBytes(900, 700, translucent = true) to "image/png",
            jpegBytes(400, 300) to "image/jpeg",
            jpegWithExifOrientation(300, 200, orientation = 8) to "image/jpeg",
            "GIF89a".toByteArray() to "image/gif",
            "%PDF-1.4\n%%EOF".toByteArray() to "application/pdf",
            fakeWav() to "audio/wav",
            fakeMp4() to "video/mp4",
        )
        for ((bytes, mime) in casos) {
            for (permitirCambioDeFormato in listOf(true, false)) {
                val result = MediaCompression.compress(
                    bytes,
                    mime,
                    allowFormatChange = permitirCambioDeFormato,
                    ffmpeg = FakeFfmpeg(available = false),
                )
                assertMimeMatchesBytes(result)
            }
        }
    }

    /** Basura con MIME de cualquier familia: se devuelve tal cual y NO se lanza. */
    @Test fun laBasuraNoRompeNadaYVuelveIntacta() {
        val basura = byteArrayOf(1, 2, 3, 4, 5)
        for (mime in listOf("image/png", "image/jpeg", "video/mp4", "audio/wav", "application/pdf", "text/plain", "")) {
            val result = MediaCompression.compress(
                basura,
                mime,
                allowFormatChange = true,
                ffmpeg = FakeFfmpeg(available = false),
            )
            assertContentEquals(basura, result.bytes, "MIME $mime")
            assertNotEquals(MediaCompressionOutcome.COMPRESSED, result.outcome, "MIME $mime")
        }
    }

    /** El `; charset=...` que puede traer una parte multipart no debe confundir a nadie. */
    @Test fun elMimeConParametrosSeNormaliza() {
        val result = MediaCompression.compress(
            "%PDF-1.4\n%%EOF".toByteArray(),
            "APPLICATION/PDF; charset=binary",
            allowFormatChange = true,
        )

        assertEquals("application/pdf", result.mimeType)
        assertEquals(MediaCompressionOutcome.SKIPPED_PDF, result.outcome)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /**
     * `ffmpeg` de mentira. El contrato de [FfmpegRunner] dice que el ÚLTIMO
     * argumento es la ruta de salida, y esto lo aprovecha para dejar ahí los
     * bytes que la prueba quiera.
     */
    private class FakeFfmpeg(
        private val available: Boolean,
        private val output: ByteArray? = null,
        private val succeeds: Boolean = true,
    ) : FfmpegRunner {
        var calls: Int = 0
            private set
        var lastArgs: List<String> = emptyList()
            private set

        override fun isAvailable(): Boolean = available

        override fun run(args: List<String>, timeoutMs: Long): Boolean {
            check(available) { "no se puede ejecutar un ffmpeg que no está disponible" }
            calls++
            lastArgs = args
            if (!succeeds) return false
            output?.let { Files.write(Path.of(args.last()), it) }
            return true
        }
    }

    private fun assertMimeMatchesBytes(result: CompressedMedia) {
        val bytes = result.bytes
        fun starts(vararg prefix: Int): Boolean =
            bytes.size >= prefix.size && prefix.withIndex().all { (index, value) -> (bytes[index].toInt() and 0xFF) == value }

        fun brandAt4(): Boolean =
            bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"

        val ok = when (result.mimeType) {
            "image/jpeg" -> starts(0xFF, 0xD8, 0xFF)
            "image/png" -> starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            "image/gif" -> String(bytes, 0, minOf(4, bytes.size), Charsets.US_ASCII) == "GIF8"
            "image/webp" -> String(bytes, 0, minOf(4, bytes.size), Charsets.US_ASCII) == "RIFF"
            "application/pdf" -> String(bytes, 0, minOf(4, bytes.size), Charsets.US_ASCII) == "%PDF"
            "audio/wav" -> String(bytes, 0, minOf(4, bytes.size), Charsets.US_ASCII) == "RIFF"
            "video/mp4", "audio/mp4" -> brandAt4()
            // Los bytes basura de otras pruebas no tienen firma que comprobar.
            else -> true
        }
        assertTrue(ok, "el MIME ${result.mimeType} no corresponde a los bytes devueltos (${result.outcome})")
    }

    /**
     * Imagen con degradado y ruido de baja amplitud: se parece a una foto real,
     * que es el caso que importa. Un color plano comprimiría a nada en PNG y la
     * prueba no diría nada de lo que pasa en producción.
     */
    private fun image(width: Int, height: Int, noise: Boolean, translucent: Boolean): BufferedImage {
        val type = if (translucent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        var seed = 0x2545F491
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 1_103_515_245 + 12_345
                val jitter = if (noise) (seed ushr 24) % 16 - 8 else 0
                val red = ((x * 255 / width) + jitter).coerceIn(0, 255)
                val green = ((y * 255 / height) + jitter).coerceIn(0, 255)
                val blue = (((x + y) * 255 / (width + height)) - jitter).coerceIn(0, 255)
                val alpha = if (translucent && x < width / 2) 0x40 else 0xFF
                image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
        return image
    }

    private fun pngBytes(width: Int, height: Int, noise: Boolean = true, translucent: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image(width, height, noise, translucent), "png", out)
        return out.toByteArray()
    }

    /**
     * Calidad 0.95 a propósito: el original tiene que venir de una cámara
     * generosa para que recomprimir a 0.82 sea una mejora. Con el 0.75 por
     * defecto de `ImageIO` el resultado pesaría más y ganaría —con razón— el
     * original, que es otro caso y ya tiene su propia prueba.
     */
    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        val stream = MemoryCacheImageOutputStream(out)
        writer.output = stream
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.95f
        }
        writer.write(null, IIOImage(image(width, height, noise = true, translucent = false), null, null), params)
        stream.flush()
        stream.close()
        writer.dispose()
        return out.toByteArray()
    }

    /**
     * Inserta un APP1 EXIF con la etiqueta `Orientation` justo detrás del SOI.
     *
     * A mano y sin librería: es exactamente la cabecera que escribe un móvil y
     * la única forma de comprobar que el analizador la recorre bien.
     */
    private fun jpegWithExifOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val base = jpegBytes(width, height)
        val tiff = byteArrayOf(
            0x4D, 0x4D, // "MM": big endian
            0x00, 0x2A, // 42
            0x00, 0x00, 0x00, 0x08, // offset del IFD0
            0x00, 0x01, // una entrada
            0x01, 0x12, // tag 0x0112 = Orientation
            0x00, 0x03, // tipo SHORT
            0x00, 0x00, 0x00, 0x01, // count 1
            0x00, orientation.toByte(), 0x00, 0x00, // valor + relleno
            0x00, 0x00, 0x00, 0x00, // no hay IFD siguiente
        )
        val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x00) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte(),
        ) + payload
        return base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)
    }

    /** Un MP4 sólo lo bastante real para que la firma `ftyp` cuadre con el MIME. */
    private fun fakeMp4(payload: Int = 2_000): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x18) +
            "ftypisom".toByteArray(Charsets.US_ASCII) +
            ByteArray(payload) { (it % 253).toByte() }

    private fun fakeM4a(payload: Int = 400): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x18) +
            "ftypM4A ".toByteArray(Charsets.US_ASCII) +
            ByteArray(payload) { (it % 251).toByte() }

    private fun fakeWav(payload: Int = 4_000): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) +
            ByteArray(4) +
            "WAVEfmt ".toByteArray(Charsets.US_ASCII) +
            ByteArray(payload) { (it % 249).toByte() }

    // -----------------------------------------------------------------------
    // Line-art: el PNG pequeño no se convierte a JPEG
    // -----------------------------------------------------------------------

    /**
     * Un esquema con bordes duros no debe acabar en JPEG.
     *
     * El material de oposición está lleno de ellos —tablas, diagramas, capturas
     * de temario, texto fino— y ahí el JPEG con pérdida introduce ringing
     * alrededor de cada borde: sale a la vez MÁS PEQUEÑO y PEOR, así que la
     * regla de «no engordar» no lo protege. Sin esta prueba, la conversión
     * vuelve sola en cuanto alguien optimice por bytes.
     */
    @Test
    fun `un png pequeno que no hizo falta redimensionar se queda en png`() {
        val lineArt = lineArtPngBytes(800, 600)
        assertTrue(
            lineArt.size <= MediaCompression.PNG_KEEP_FORMAT_MAX_BYTES,
            "la muestra debe caer bajo el umbral para probar la regla: ${lineArt.size} bytes",
        )

        val result = MediaCompression.compress(lineArt, "image/png", allowFormatChange = true)

        assertEquals("image/png", result.mimeType)
        assertMimeMatchesBytes(result)
    }

    /**
     * Un esquema: fondo plano con bordes duros. Es lo que PNG comprime a casi
     * nada y lo que JPEG estropea, al revés que la foto de [image].
     */
    private fun lineArtPngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = java.awt.Color.BLACK
            // Rejilla de celdas y diagonales: bordes duros, sin degradado.
            var x = 0
            while (x < width) {
                graphics.drawLine(x, 0, x, height)
                x += 40
            }
            var y = 0
            while (y < height) {
                graphics.drawLine(0, y, width, y)
                y += 40
            }
            graphics.drawLine(0, 0, width, height)
            graphics.drawLine(width, 0, 0, height)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * La regla anterior NO es «los PNG nunca se convierten»: uno grande que hay
     * que redimensionar sí pasa a JPEG, que es donde está el ahorro de verdad.
     */
    @Test
    fun `un png grande sigue convirtiendose a jpeg`() {
        val photo = pngBytes(3000, 2200)

        val result = MediaCompression.compress(photo, "image/png", allowFormatChange = true)

        assertEquals("image/jpeg", result.mimeType)
        assertTrue(result.bytes.size < photo.size, "no comprimió: ${result.bytes.size} vs ${photo.size}")
        assertMimeMatchesBytes(result)
    }
}
