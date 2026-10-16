plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
}

// Suite semantica del candidate de Supabase. Arranca un PostgreSQL REAL y
// efimero (binarios propios, sin Docker ni daemon), aplica el SQL del candidate
// tal cual esta en el repositorio y comprueba COMPORTAMIENTO. No sustituye al
// verificador estatico: lo releva como autoridad sobre las invariantes.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testImplementation("org.postgresql:postgresql:42.7.4")
}
