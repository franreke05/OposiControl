package com.oposicontrol.backend

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

private val MediaLogger = LoggerFactory.getLogger("OC-KTOR-MEDIA")

/**
 * # Compresion de lo que se sube a Supabase Storage
 *
 * Un unico sitio por el que pasan los bytes ANTES de cruzar la red hacia el
 * bucket. Existe porque hoy se sube el original tal cual: una foto de movil de
 * 4 MB ocupa 4 MB para siempre, se paga cada mes y se descarga entera en cada
 * lectura de la app.
 *
 * ## La regla que manda sobre todas las demas
 *
 * **La optimizacion NUNCA puede perder el archivo del usuario.** Cada modo de
 * fallo -- formato que la JVM no sabe decodificar, `ffmpeg` que no esta
 * instalado, proceso que se cuelga, resultado que pesa mas que la entrada --
 * termina exactamente igual: se devuelven los bytes ORIGINALES con su MIME
 * original. No hay ninguna ruta de codigo que propague una excepcion de
 * compresion al llamante, y eso es deliberado: comprimir es una mejora de
 * coste, no un requisito funcional de la subida.
 *
 * ## El otro invariante: MIME == bytes
 *
 * [CompressedMedia.mimeType] describe SIEMPRE los bytes que viajan en
 * [CompressedMedia.bytes]. Si se recomprime un PNG a JPEG, el MIME cambia a
 * `image/jpeg` y el llamante tiene que renombrar la extension del objeto en
 * consecuencia. Cuando el punto de subida no puede garantizar ese renombrado,
 * llama con `allowFormatChange = false` y aqui no se cambia de formato: se
 * recomprime dentro del mismo, o no se toca.
 *
 * ## Lo que NO se toca, y por que
 *
 * - **PDF**: pasa byte a byte. Se sirve por descarga directa desde Storage y
 *   cualquier reenvasado (gzip incluido) rompe al visor del cliente.
 * - **GIF**: `ImageIO` solo lee el PRIMER fotograma. Recomprimir un GIF animado
 *   lo convertiria en una imagen fija: eso no es "menos del 15 % de perdida",
 *   es perder el contenido.
 * - **MP3 / M4A**: ya son lossy. Reencodificar a AAC 128k sobre algo que ya
 *   tiro informacion es perdida generacional a cambio de casi nada de tamano.
 *   Solo se transcodifica WAV, que es PCM sin comprimir y donde la ganancia es
 *   de un orden de magnitud.
 * - **Cualquier MIME desconocido**: si no se sabe lo que es, no se toca.
 */
object MediaCompression {

    /**
     * Lado maximo de una imagen, en pixeles.
     *
     * 2048 y no 4096: el material se mira en el movil y en el panel de
     * administracion, y 2048 px en el lado largo ya da margen para hacer zoom
     * sobre un esquema sin ver pixeles. Por encima de eso solo se paga
     * almacenamiento y descarga por informacion que ninguna pantalla del
     * producto llega a mostrar -- una foto de 12 MP cae a ~3 MP.
     */
    const val IMAGE_MAX_SIDE: Int = 2048

    /**
     * Calidad JPEG.
     *
     * 0.82 es el punto donde la diferencia con el original deja de ser visible
     * a tamano normal, tanto en fotografia como en esquemas con texto grande, y
     * donde la curva tamano/calidad todavia baja rapido. Subir a 0.90 engorda
     * ~40 % sin que nadie vea la diferencia; bajar a 0.70 empieza a ensuciar los
     * bordes de los diagramas, que es justo el contenido que mas hay aqui.
     */
    const val IMAGE_JPEG_QUALITY: Float = 0.82f

    /**
     * Techo de pixeles a decodificar (50 MP).
     *
     * Un PNG de 2 MB puede declarar 30000x30000: `ImageIO` intentaria reservar
     * ~3,6 GB de heap y tumbar el proceso entero. Las dimensiones se leen de la
     * cabecera ANTES de decodificar, y por encima del techo el archivo se sube
     * sin tocar en vez de convertirse en una denegacion de servicio.
     */
    const val IMAGE_MAX_PIXELS: Long = 25_000_000L

    /**
     * Por debajo de esto, un PNG que no hizo falta redimensionar NO pasa a JPEG.
     *
     * No mide "es line-art": eso no se sabe barato. Mide que no hay nada que
     * ganar. El material de oposicion esta lleno de bordes duros --esquemas,
     * tablas, capturas de temario, texto fino-- y ahi el JPEG con perdida
     * introduce ringing alrededor de cada borde. En ese contenido sale a la vez
     * mas pequeno y peor, asi que la regla de "no engordar" no lo protege.
     */
    const val PNG_KEEP_FORMAT_MAX_BYTES: Int = 300 * 1024

    /** 1080p como techo de video: el material se reproduce en la app, no en una sala de cine. */
    private const val VIDEO_MAX_WIDTH = 1920
    private const val VIDEO_MAX_HEIGHT = 1080

    /**
     * CRF 23 es el valor por defecto de x264 y el consenso de "visualmente
     * indistinguible en contenido normal". Cada +6 duplica el tamano y cada -6
     * lo divide; 23 se queda dentro del margen de perdida que se pide.
     */
    private const val VIDEO_CRF = "23"

    /**
     * `veryfast` y no `medium`.
     *
     * CRF fija la CALIDAD, no el tamano: el preset solo decide cuanto trabaja el
     * codificador para llegar a esa calidad. `veryfast` produce un archivo algo
     * mayor que `medium` a la misma calidad percibida, pero termina en un tercio
     * del tiempo, y eso es lo que hace que el peor caso admisible (50 MB) quepa
     * dentro del timeout en vez de expirar y subir el original. Un preset lento
     * que siempre expira no comprime nada.
     */
    private const val VIDEO_PRESET = "veryfast"

    /** 128 kbps AAC: transparente para voz y de sobra para el audio de un video didactico. */
    private const val AUDIO_BITRATE = "128k"

    /**
     * Timeouts duros. La subida es sincrona: el administrador esta esperando
     * delante de la pantalla, asi que el techo no lo pone lo que "podria" tardar
     * ffmpeg sino lo que un humano tolera. Al expirar se mata el proceso y se
     * sube el original.
     */
    private const val VIDEO_TIMEOUT_MS = 120_000L
    private const val AUDIO_TIMEOUT_MS = 30_000L

    /**
     * Comprime [bytes] si sabe y si merece la pena.
     *
     * @param mimeType MIME declarado por el punto de subida, ya validado por el.
     * @param allowFormatChange `true` solo si el llamante puede cambiar la
     *   extension del objeto y el MIME que persiste. Con `false` el formato de
     *   salida es siempre el de entrada.
     * @param ffmpeg costura inyectable: en test no hace falta binario.
     */
    fun compress(
        bytes: ByteArray,
        mimeType: String,
        allowFormatChange: Boolean,
        ffmpeg: FfmpegRunner = SystemFfmpegRunner,
    ): CompressedMedia {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        val original = CompressedMedia(bytes, mime, MediaCompressionOutcome.SKIPPED_UNSUPPORTED)
        // Todo el cuerpo va dentro del `runCatching`: la promesa de "nunca se
        // pierde el archivo" no puede depender de haber enumerado bien las
        // excepciones de ImageIO y de ProcessBuilder.
        val result = runCatching {
            when {
                mime == "application/pdf" -> original.copy(outcome = MediaCompressionOutcome.SKIPPED_PDF)
                mime == "image/gif" -> original.copy(outcome = MediaCompressionOutcome.SKIPPED_ANIMATED)
                mime.startsWith("image/") -> compressImage(bytes, mime, allowFormatChange)
                mime == "audio/wav" || mime == "audio/x-wav" -> transcodeAudio(bytes, mime, allowFormatChange, ffmpeg)
                mime.startsWith("audio/") -> original.copy(outcome = MediaCompressionOutcome.SKIPPED_ALREADY_LOSSY)
                mime.startsWith("video/") -> transcodeVideo(bytes, mime, allowFormatChange, ffmpeg)
                else -> original
            }
        }.getOrElse { error ->
            MediaLogger.warn("Compresion fallida para {} ({} bytes): {}", mime, bytes.size, error::class.simpleName)
            original.copy(outcome = MediaCompressionOutcome.FAILED)
        }
        // Ultimo filtro, y el unico que no se puede saltar nadie: comprimir no
        // puede EMPEORAR. Si el resultado pesa igual o mas, gana el original.
        if (result.applied && result.bytes.size >= bytes.size) {
            return original.copy(outcome = MediaCompressionOutcome.SKIPPED_NOT_SMALLER)
        }
        if (result.applied) {
            // Sin rutas de Storage ni nombres de fichero: solo el tipo y el ahorro.
            MediaLogger.info(
                "Comprimido {} -> {}: {} -> {} bytes",
                mime,
                result.mimeType,
                bytes.size,
                result.bytes.size,
            )
        }
        return result
    }

    // -----------------------------------------------------------------
    // Imagen: en proceso, con javax.imageio. Sin binarios ni librerias.
    // -----------------------------------------------------------------

    /**
     * WebP no aparece aqui por una razon que conviene dejar escrita: la JVM de
     * serie **ni lo lee ni lo escribe**. Un `image/webp` no encuentra lector,
     * sale por [MediaCompressionOutcome.SKIPPED_UNSUPPORTED] y se sube intacto.
     * Es el comportamiento correcto sin anadir dependencias, y un WebP ya viene
     * comprimido de origen.
     */
    private fun compressImage(bytes: ByteArray, mime: String, allowFormatChange: Boolean): CompressedMedia {
        val original = CompressedMedia(bytes, mime, MediaCompressionOutcome.SKIPPED_UNSUPPORTED)
        val reader = firstReaderFor(bytes) ?: return original
        val source = try {
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            // Bomba de descompresion: las dimensiones se leen de la cabecera, no
            // del heap ya reservado.
            if (width.toLong() * height.toLong() > IMAGE_MAX_PIXELS) {
                MediaLogger.warn("Imagen descartada por tamano declarado: {}x{}", width, height)
                return original.copy(outcome = MediaCompressionOutcome.SKIPPED_TOO_LARGE)
            }
            reader.read(0)
        } finally {
            reader.dispose()
        }

        // La orientacion EXIF solo la traen los JPEG de camara. `ImageIO` no la
        // aplica y al reencodificar se pierde la etiqueta, asi que se aplica a
        // los PIXELES: el resultado queda derecho sin depender de metadatos.
        val upright = applyExifOrientation(source, if (mime == "image/jpeg") exifOrientation(bytes) else 1)
        val scaled = downscale(upright, IMAGE_MAX_SIDE)

        // Transparencia: pasarla a JPEG pinta de blanco lo que era transparente.
        // Eso no es perdida de calidad, es romper la imagen. Se queda en PNG.
        val transparent = scaled.colorModel.hasAlpha() && hasTranslucentPixel(scaled)

        // Un PNG pequeno que no hizo falta redimensionar se queda en PNG.
        //
        // El JPEG con perdida produce "ringing" alrededor de los bordes duros, y
        // el material de oposicion esta lleno de ellos: esquemas, tablas,
        // capturas de temario, texto fino. En ese contenido el JPEG sale a la vez
        // MAS PEQUENO y PEOR, asi que la regla de "no engordar" no lo protege: el
        // resultado pasaria el filtro de tamano degradando la imagen.
        //
        // El umbral no mide "es line-art" --no se puede saber barato-- sino que
        // no hay nada que ganar: por debajo de 300 KB y sin redimensionar, el
        // ahorro no compensa arriesgar la legibilidad de un esquema.
        val alreadySmallPng = mime == "image/png" &&
            bytes.size <= PNG_KEEP_FORMAT_MAX_BYTES &&
            scaled === upright

        val targetMime = when {
            transparent || !allowFormatChange -> mime
            alreadySmallPng -> mime
            else -> "image/jpeg"
        }

        val encoded = when (targetMime) {
            "image/jpeg" -> encodeJpeg(scaled)
            "image/png" -> encodePng(scaled)
            // Formato que sabemos leer pero no reescribir con garantias (bmp,
            // tiff...) y sin permiso para cambiar de formato: se sube tal cual.
            else -> null
        } ?: return original

        return CompressedMedia(encoded, targetMime, MediaCompressionOutcome.COMPRESSED)
    }

    private fun firstReaderFor(bytes: ByteArray): ImageReader? {
        // `MemoryCacheImageInputStream` y no `ImageIO.read`: el cache en disco
        // por defecto de ImageIO crea ficheros temporales en cada subida.
        val input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        return readers.next().also { it.input = input }
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray? {
        // El escritor JPEG no admite canal alfa: se aplana sobre blanco.
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) {
            image
        } else {
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { target ->
                val graphics = target.createGraphics()
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, target.width, target.height)
                    graphics.drawImage(image, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        val out = ByteArrayOutputStream()
        val stream = MemoryCacheImageOutputStream(out)
        try {
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = IMAGE_JPEG_QUALITY
                // JPEG progresivo: mismos pixeles, un 3-5 % menos de bytes y la
                // imagen se insinua antes en una conexion lenta.
                progressiveMode = ImageWriteParam.MODE_DEFAULT
            }
            writer.write(null, IIOImage(rgb, null, null), params)
            stream.flush()
        } finally {
            runCatching { stream.close() }
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun encodePng(image: BufferedImage): ByteArray? {
        val out = ByteArrayOutputStream()
        val stream = MemoryCacheImageOutputStream(out)
        val written = try {
            ImageIO.write(image, "png", stream).also { stream.flush() }
        } finally {
            runCatching { stream.close() }
        }
        return if (written) out.toByteArray() else null
    }

    /**
     * Reduccion por mitades sucesivas antes del paso final.
     *
     * Un bilineal directo de 4000 px a 2048 px muestrea uno de cada dos pixeles
     * y produce aliasing visible en texto y en lineas finas -- exactamente el
     * contenido de un esquema de examen. Ir dividiendo por dos promedia en cada
     * paso y llega limpio, que es lo que permite mantenerse dentro del margen de
     * perdida perceptual pedido.
     */
    private fun downscale(image: BufferedImage, maxSide: Int): BufferedImage {
        val longest = maxOf(image.width, image.height)
        if (longest <= maxSide) return image
        val ratio = maxSide.toDouble() / longest.toDouble()
        val targetWidth = maxOf(1, Math.round(image.width * ratio).toInt())
        val targetHeight = maxOf(1, Math.round(image.height * ratio).toInt())
        var current = image
        while (current.width / 2 > targetWidth && current.height / 2 > targetHeight) {
            current = resample(current, current.width / 2, current.height / 2)
        }
        return resample(current, targetWidth, targetHeight)
    }

    private fun resample(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(width, height, type)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    /** Un solo pixel no opaco basta para que convertir a JPEG sea destructivo. */
    private fun hasTranslucentPixel(image: BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) ushr 24 != 0xFF) return true
            }
        }
        return false
    }

    // -----------------------------------------------------------------
    // Orientacion EXIF, a mano y sin dependencias
    // -----------------------------------------------------------------

    /**
     * Lee la etiqueta EXIF `Orientation` (0x0112) del segmento APP1 de un JPEG.
     *
     * Se recorre la cadena de segmentos, no se busca el patron a ciegas: la
     * cadena "Exif" puede aparecer por azar dentro de los datos comprimidos.
     * Devuelve 1 (derecha) ante cualquier duda.
     */
    internal fun exifOrientation(bytes: ByteArray): Int {
        if (bytes.size < 4) return 1
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return 1
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if ((bytes[offset].toInt() and 0xFF) != 0xFF) return 1
            val marker = bytes[offset + 1].toInt() and 0xFF
            // SOS (0xDA) o EOI (0xD9): a partir de aqui ya no hay cabeceras.
            if (marker == 0xDA || marker == 0xD9) return 1
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            if (length < 2 || offset + 2 + length > bytes.size) return 1
            val isExifApp1 = marker == 0xE1 &&
                length >= 14 &&
                String(bytes, offset + 4, 4, Charsets.US_ASCII) == "Exif"
            if (isExifApp1) return orientationFromTiff(bytes, offset + 10, offset + 2 + length)
            offset += 2 + length
        }
        return 1
    }

    private fun orientationFromTiff(bytes: ByteArray, start: Int, end: Int): Int {
        if (start + 8 > end) return 1
        val bigEndian = when (String(bytes, start, 2, Charsets.US_ASCII)) {
            "MM" -> true
            "II" -> false
            else -> return 1
        }

        fun u16(at: Int): Int {
            if (at < start || at + 2 > end) return -1
            val first = bytes[at].toInt() and 0xFF
            val second = bytes[at + 1].toInt() and 0xFF
            return if (bigEndian) (first shl 8) or second else (second shl 8) or first
        }

        fun u32(at: Int): Long {
            if (at < start || at + 4 > end) return -1L
            val b0 = bytes[at].toLong() and 0xFF
            val b1 = bytes[at + 1].toLong() and 0xFF
            val b2 = bytes[at + 2].toLong() and 0xFF
            val b3 = bytes[at + 3].toLong() and 0xFF
            return if (bigEndian) {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            } else {
                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            }
        }

        if (u16(start + 2) != 42) return 1
        val ifdOffset = u32(start + 4)
        if (ifdOffset < 8L || start + ifdOffset > end) return 1
        val ifd = start + ifdOffset.toInt()
        val entries = u16(ifd)
        if (entries <= 0) return 1
        for (index in 0 until entries) {
            val entry = ifd + 2 + index * 12
            if (entry + 12 > end) return 1
            if (u16(entry) == 0x0112) {
                val value = u16(entry + 8)
                return if (value in 1..8) value else 1
            }
        }
        return 1
    }

    /** Las ocho orientaciones del estandar EXIF, aplicadas a los pixeles. */
    private fun applyExifOrientation(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) return image
        val width = image.width
        val height = image.height
        // 5..8 giran 90 grados: el lienzo de destino intercambia lados.
        val swap = orientation >= 5
        val transform = AffineTransform()
        when (orientation) {
            2 -> {
                transform.scale(-1.0, 1.0)
                transform.translate(-width.toDouble(), 0.0)
            }
            3 -> {
                transform.translate(width.toDouble(), height.toDouble())
                transform.rotate(Math.PI)
            }
            4 -> {
                transform.scale(1.0, -1.0)
                transform.translate(0.0, -height.toDouble())
            }
            5 -> {
                transform.rotate(-Math.PI / 2)
                transform.scale(-1.0, 1.0)
            }
            6 -> {
                transform.translate(height.toDouble(), 0.0)
                transform.rotate(Math.PI / 2)
            }
            7 -> {
                transform.scale(-1.0, 1.0)
                transform.translate(-height.toDouble(), 0.0)
                transform.translate(0.0, width.toDouble())
                transform.rotate(-Math.PI / 2)
            }
            8 -> {
                transform.translate(0.0, width.toDouble())
                transform.rotate(-Math.PI / 2)
            }
            else -> return image
        }
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(if (swap) height else width, if (swap) width else height, type)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, transform, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    // -----------------------------------------------------------------
    // Video y audio: ffmpeg como proceso externo y OPCIONAL
    // -----------------------------------------------------------------

    private fun transcodeVideo(
        bytes: ByteArray,
        mime: String,
        allowFormatChange: Boolean,
        ffmpeg: FfmpegRunner,
    ): CompressedMedia {
        val original = CompressedMedia(bytes, mime, MediaCompressionOutcome.SKIPPED_UNSUPPORTED)
        // La salida es SIEMPRE MP4/H.264. Si el llamante no puede cambiar el MIME
        // y la entrada no era ya MP4, no hay forma honesta de etiquetar el
        // resultado: se sube el original.
        if (!allowFormatChange && mime != "video/mp4") return original
        // El MIME lo declara quien sube; ffmpeg sondea el CONTENIDO. Sin esta comprobacion, un fichero de texto etiquetado
        // como video (una lista HLS o concat) haria que ffmpeg abriese las rutas que nombra. Solo se transcodifica lo que
        // empieza como un contenedor de video; lo demas se sube sin tocar.
        if (!bytes.looksLikeVideoContainer()) return original
        if (!ffmpeg.isAvailable()) return original.copy(outcome = MediaCompressionOutcome.SKIPPED_NO_FFMPEG)
        val produced = runFfmpeg(bytes, ".bin", ".mp4", VIDEO_TIMEOUT_MS, ffmpeg) { input, output ->
            listOf(
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                // Solo ficheros locales: ningun protocolo de red ni de concatenacion, diga lo que diga la entrada.
                "-protocol_whitelist", "file",
                "-i", input.toString(),
                "-c:v", "libx264", "-preset", VIDEO_PRESET, "-crf", VIDEO_CRF,
                // yuv420p: sin esto un origen 4:2:2 o 4:4:4 produce un MP4 que
                // Safari y muchos reproductores de Android no abren.
                "-pix_fmt", "yuv420p",
                // Techo de 1920x1080 en el lado que toque (vertical incluido) y
                // sin ampliar nunca. `force_divisible_by=2` porque H.264 exige
                // dimensiones pares.
                "-vf", "scale=w=$VIDEO_MAX_WIDTH:h=$VIDEO_MAX_HEIGHT:force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ac", "2",
                // El indice al principio del fichero: sin esto la app tiene que
                // descargar el MP4 entero antes de pintar el primer fotograma.
                "-movflags", "+faststart",
                output.toString(),
            )
        } ?: return original.copy(outcome = MediaCompressionOutcome.FAILED)
        return CompressedMedia(produced, "video/mp4", MediaCompressionOutcome.COMPRESSED)
    }

    private fun transcodeAudio(
        bytes: ByteArray,
        mime: String,
        allowFormatChange: Boolean,
        ffmpeg: FfmpegRunner,
    ): CompressedMedia {
        val original = CompressedMedia(bytes, mime, MediaCompressionOutcome.SKIPPED_UNSUPPORTED)
        // WAV -> M4A cambia de contenedor por definicion: sin permiso, no se toca.
        if (!allowFormatChange) return original
        if (!bytes.looksLikeWav()) return original
        if (!ffmpeg.isAvailable()) return original.copy(outcome = MediaCompressionOutcome.SKIPPED_NO_FFMPEG)
        val produced = runFfmpeg(bytes, ".wav", ".m4a", AUDIO_TIMEOUT_MS, ffmpeg) { input, output ->
            listOf(
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-protocol_whitelist", "file",
                "-i", input.toString(),
                "-vn", "-c:a", "aac", "-b:a", AUDIO_BITRATE,
                "-movflags", "+faststart",
                output.toString(),
            )
        } ?: return original.copy(outcome = MediaCompressionOutcome.FAILED)
        return CompressedMedia(produced, "audio/mp4", MediaCompressionOutcome.COMPRESSED)
    }

    /**
     * Escribe la entrada a un temporal, ejecuta ffmpeg y lee la salida.
     *
     * Devuelve `null` en cualquier desenlace que no sea "ffmpeg termino bien y
     * escribio algo". Los temporales se borran SIEMPRE, incluido el caso en que
     * el proceso se mata por timeout.
     */
    private fun runFfmpeg(
        bytes: ByteArray,
        inputSuffix: String,
        outputSuffix: String,
        timeoutMs: Long,
        ffmpeg: FfmpegRunner,
        args: (Path, Path) -> List<String>,
    ): ByteArray? {
        var input: Path? = null
        var output: Path? = null
        return try {
            input = Files.createTempFile("oc-media-in-", inputSuffix)
            output = Files.createTempFile("oc-media-out-", outputSuffix)
            Files.write(input, bytes)
            // El temporal de salida ya existe y esta vacio; de ahi el `-y` en los
            // argumentos, sin el cual ffmpeg se niega a sobrescribirlo.
            if (!ffmpeg.run(args(input, output), timeoutMs)) return null
            Files.readAllBytes(output).takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            MediaLogger.warn("ffmpeg no pudo procesar el archivo: {}", error::class.simpleName)
            null
        } finally {
            input?.let { path -> runCatching { Files.deleteIfExists(path) } }
            output?.let { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }
}

/** Bytes listos para subir, con el MIME que de verdad les corresponde. */
data class CompressedMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val outcome: MediaCompressionOutcome,
) {
    /** `true` si los bytes cambiaron respecto a la entrada. */
    val applied: Boolean get() = outcome == MediaCompressionOutcome.COMPRESSED

    // `data class` con `ByteArray`: la igualdad generada compara por identidad
    // de array, que es una sorpresa esperando a pasar. Se compara el contenido.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressedMedia) return false
        return bytes.contentEquals(other.bytes) && mimeType == other.mimeType && outcome == other.outcome
    }

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + mimeType.hashCode()) + outcome.hashCode()
}

/** Por que los bytes salieron como salieron. Se usa en logs y en tests. */
enum class MediaCompressionOutcome {
    /** Comprimido de verdad y mas pequeno que el original. */
    COMPRESSED,

    /** Formato que no sabemos (o no debemos) recomprimir. */
    SKIPPED_UNSUPPORTED,

    /** PDF: pasa byte a byte. */
    SKIPPED_PDF,

    /** GIF: `ImageIO` solo veria el primer fotograma. */
    SKIPPED_ANIMATED,

    /** MP3/M4A: reencodificar lossy sobre lossy solo pierde. */
    SKIPPED_ALREADY_LOSSY,

    /** Dimensiones declaradas por encima del techo de pixeles. */
    SKIPPED_TOO_LARGE,

    /** El resultado pesaba igual o mas que la entrada. */
    SKIPPED_NOT_SMALLER,

    /** No hay `ffmpeg` en esta maquina. */
    SKIPPED_NO_FFMPEG,

    /** Se intento y salio mal: error, timeout o salida vacia. */
    FAILED,
}

/**
 * Costura sobre el binario `ffmpeg`.
 *
 * Existe para que los tests no necesiten tenerlo instalado: la CI inyecta una
 * implementacion que dice "no estoy" o que falla, y se verifica que el original
 * sube igual.
 *
 * Contrato: el ULTIMO argumento de [run] es siempre la ruta del fichero de
 * salida.
 */
interface FfmpegRunner {
    /** Se resuelve UNA vez por proceso; llamarlo en cada subida es un error de diseno. */
    fun isAvailable(): Boolean

    /** `true` solo si el proceso termino con codigo 0 antes de [timeoutMs]. */
    fun run(args: List<String>, timeoutMs: Long): Boolean
}

/**
 * El `ffmpeg` del sistema, si lo hay.
 *
 * ffmpeg es **opcional en tiempo de ejecucion**: el backend arranca y funciona
 * sin el, subiendo los originales de video y audio. La sonda se hace una sola
 * vez (`by lazy`) y el motivo se registra una sola vez: con un log por subida,
 * el primer video largo llenaria el fichero de logs con la misma linea.
 */
object SystemFfmpegRunner : FfmpegRunner {

    private const val PROBE_TIMEOUT_MS = 5_000L

    private val available: Boolean by lazy {
        val detected = runCatching { execute(listOf("-hide_banner", "-version"), PROBE_TIMEOUT_MS) }.getOrDefault(false)
        if (detected) {
            MediaLogger.info("ffmpeg disponible: video y audio se comprimiran antes de subir.")
        } else {
            MediaLogger.info("ffmpeg no disponible: video y audio se subiran sin comprimir (esperado, no es un error).")
        }
        detected
    }

    override fun isAvailable(): Boolean = available

    override fun run(args: List<String>, timeoutMs: Long): Boolean =
        runCatching { execute(args, timeoutMs) }.getOrElse { error ->
            MediaLogger.warn("ffmpeg fallo al ejecutarse: {}", error::class.simpleName)
            false
        }

    private fun execute(args: List<String>, timeoutMs: Long): Boolean {
        val process = ProcessBuilder(listOf("ffmpeg") + args)
            // DISCARD en los tres canales: sin nadie leyendo las tuberias, un
            // ffmpeg hablador se bloquearia al llenar el buffer del sistema
            // operativo y el timeout se comeria la peticion sin motivo.
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            // Timeout duro: se mata y se sube el original. La peticion no se cuelga.
            process.destroyForcibly()
            MediaLogger.warn("ffmpeg supero el timeout de {} ms; se sube el original.", timeoutMs)
            return false
        }
        return process.exitValue() == 0
    }

    private fun nullDevice(): File {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        return File(if (windows) "NUL" else "/dev/null")
    }
}

/** `true` si [offset] y los bytes siguientes son exactamente [ascii]. */
private fun ByteArray.hasAsciiAt(offset: Int, ascii: String): Boolean =
    size >= offset + ascii.length && ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }

/** MP4/MOV (`ftyp` en el byte 4), Matroska/WebM (cabecera EBML) o AVI (`RIFF....AVI `): lo unico que se entrega a ffmpeg como video. */
internal fun ByteArray.looksLikeVideoContainer(): Boolean =
    hasAsciiAt(4, "ftyp") ||
        (size >= 4 && this[0] == 0x1A.toByte() && this[1] == 0x45.toByte() && this[2] == 0xDF.toByte() && this[3] == 0xA3.toByte()) ||
        (hasAsciiAt(0, "RIFF") && hasAsciiAt(8, "AVI "))

internal fun ByteArray.looksLikeWav(): Boolean = hasAsciiAt(0, "RIFF") && hasAsciiAt(8, "WAVE")
