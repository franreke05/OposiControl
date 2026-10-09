plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.oposicontrol.backend.ApplicationKt")
}

dependencies {
    implementation("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-java:${libs.versions.ktor.get()}")
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.statusPages)
    implementation("io.ktor:ktor-server-metrics-micrometer:${libs.versions.ktor.get()}")
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform("io.micrometer:micrometer-bom:1.15.12"))
    // CI-OC-DEPS: ktor-server-netty 3.2.3 trae netty 4.2.2.Final (~30 avisos OSV, uno CRITICAL: GHSA-c4c3-7fpv-j4q5);
    // 4.2.17.Final es el minimo sin avisos (Ktor 3.5.2 seguiria en 4.2.16). Parche dentro de la linea 4.2 de Ktor 3.2.x.
    implementation(platform("io.netty:netty-bom:4.2.17.Final"))
    // CI-OC-DEPS: java-jwt 4.4.0 declara jackson-databind 2.14.2 (avisos HIGH; jackson-core GHSA-h46c/GHSA-r7wm).
    // 2.18.11 es el minimo sin avisos de la linea 2.18; java-jwt solo usa ObjectMapper/JsonNode basicos.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.11"))
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.lettuce:lettuce-core:7.8.0.RELEASE")
    // Verificacion de JWT de Supabase (ES256). La clave EC se construye desde el JWKS publico.
    implementation("com.auth0:java-jwt:4.4.0")
    // Extraccion de texto de PDFs para importacion de rafagas.
    implementation("org.apache.pdfbox:pdfbox:3.0.2")
    // S1D1/DAY_02 (2026-08-13): sin binding SLF4J los logs (incluidos los
    // errores swallowed en OposiBotsAuthRoutes.authEndpoint) no se veian en
    // ningun lado -- ver auditoria 2026-08-08, riesgo #6 "sin observabilidad".
    implementation("io.sentry:sentry:8.57.0")
    implementation("io.sentry:sentry-logback:8.57.0")
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    // logback-classic ya esta en runtime (linea de arriba); en test scope
    // tambien para poder adjuntar un appender en memoria y verificar el
    // contenido real de los logs estrategicos (ObservabilityTest).
    testImplementation(libs.logback.classic)
}

tasks.named<JavaExec>("run") {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .forEach { line ->
                val eqIndex = line.indexOf('=')
                if (eqIndex > 0) {
                    val key = line.substring(0, eqIndex).trim()
                    val value = line.substring(eqIndex + 1).trim()
                    if (System.getenv(key).isNullOrBlank()) {
                        environment(key, value)
                    }
                }
            }
    }
}

tasks.withType<Test>().configureEach {
    // `-Dgenerate.pdf.fixtures=true` en la linea de comandos de Gradle no llega solo a la JVM de test:
    // se reenvia para que `GeneratePdfFixtures` pueda (re)escribir los PDF binarios de src/test/resources/pdf.
    System.getProperty("generate.pdf.fixtures")?.let { systemProperty("generate.pdf.fixtures", it) }
}
