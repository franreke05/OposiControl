package com.oposicontrol.backend

import java.io.File
import kotlin.test.Test

/**
 * Escribe los PDF binarios de `src/test/resources/pdf/`.
 *
 * Es un no-op salvo con `-Dgenerate.pdf.fixtures=true`:
 *
 * ```
 * ./gradlew.bat :backend:ktor:test --tests "*GeneratePdfFixtures*" -Dgenerate.pdf.fixtures=true
 * ```
 *
 * Los ficheros generados SE VERSIONAN: los tests ([PdfImportFixturesTest]) los leen del
 * classpath y nunca los regeneran, así que una regresión del generador no puede esconder una del
 * parser. `build.gradle.kts` reenvía la propiedad a la JVM de test.
 */
class GeneratePdfFixtures {

    @Test
    fun writeFixturesWhenExplicitlyRequested() {
        if (System.getProperty("generate.pdf.fixtures") != "true") return
        // El directorio de trabajo de Gradle para `test` es la carpeta del módulo (backend/ktor).
        val target = File(System.getProperty("user.dir"), "src/test/resources/pdf")
        target.mkdirs()
        PdfFixtureFactory.allNames().forEach { name ->
            File(target, name).writeBytes(PdfFixtureFactory.build(name))
        }
    }
}
