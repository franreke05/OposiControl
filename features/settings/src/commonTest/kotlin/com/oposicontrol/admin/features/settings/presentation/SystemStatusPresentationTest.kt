package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.SupabaseProjectionBasis
import com.oposicontrol.admin.domain.model.SupabaseQuota
import com.oposicontrol.admin.domain.model.SystemAlert
import com.oposicontrol.admin.domain.model.SystemMetric
import com.oposicontrol.admin.domain.model.SystemReplica
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Los textos y cálculos de la pestaña Estado.
 *
 * Lo que protegen: que un dato que no se ha podido leer se DIGA, y que nunca se convierta en un cero, en una barra vacía
 * o en un «todo funciona».
 */
class SystemStatusPresentationTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")

    private fun replica(name: String, up: Boolean, profile: String? = "bot") = SystemReplica(name = name, profile = profile, up = up)

    private val allUp = listOf(replica("bot-1", true), replica("bot-2", true), replica("control-1", true, "control"))

    private val healthy = SystemStatus(
        overallStatus = SystemStatusLevel.OPERATIVE,
        ktorStatus = SystemStatusLevel.OPERATIVE,
        supabaseStatus = SystemStatusLevel.OPERATIVE,
        replicas = allUp,
        alerts = emptyList(),
        prometheusResponding = true,
    )

    // --- formato de bytes ---

    @Test
    fun bytesUseDecimalUnitsACommaAndNoUselessDecimals() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1 kB", formatBytes(1_000))
        assertEquals("1,5 kB", formatBytes(1_500))
        assertEquals("41,2 MB", formatBytes(41_234_568))
        assertEquals("150 MB", formatBytes(150_000_000))
        assertEquals("3 GB", formatBytes(3_000_000_000))
        assertEquals("4,05 GB", formatBytes(4_050_000_000))
        assertEquals("4,5 GB", formatBytes(4_500_000_000))
        assertEquals("5 GB", formatBytes(5_000_000_000))
    }

    @Test
    fun aValueThatRoundsUpToTheNextUnitChangesUnitInsteadOfReadingAThousand() {
        assertEquals("1 MB", formatBytes(999_980))
        assertEquals("1 GB", formatBytes(999_990_000))
        assertEquals("999,9 kB", formatBytes(999_900))
    }

    @Test
    fun aMissingByteCountIsSaidAndAMeasuredZeroIsShown() {
        assertEquals("No disponible", null.toBytesLabel())
        assertEquals("0 B", 0L.toBytesLabel())
        assertEquals("0 B", formatBytes(-5), "un contador no puede ser negativo: no se pinta un signo menos")
    }

    // --- proyección frente al límite ---

    @Test
    fun theProjectionIsComparedWithTheMonthlyLimit() {
        assertEquals(QuotaUsage(percent = 60, barFraction = 0.6f, tone = QuotaTone.OK), quotaUsage(3_000_000_000, 5_000_000_000))
        assertEquals(QuotaTone.OK, quotaUsage(3_950_000_000, 5_000_000_000)?.tone, "79 % todavía no avisa")
        assertEquals(QuotaUsage(percent = 80, barFraction = 0.8f, tone = QuotaTone.WARNING), quotaUsage(4_000_000_000, 5_000_000_000))
        assertEquals(QuotaTone.OVER, quotaUsage(5_000_000_000, 5_000_000_000)?.tone)
        // Por encima del límite el porcentaje dice la verdad y la barra se queda llena, no se desborda.
        assertEquals(QuotaUsage(percent = 150, barFraction = 1f, tone = QuotaTone.OVER), quotaUsage(7_500_000_000, 5_000_000_000))
        assertEquals(QuotaUsage(percent = 0, barFraction = 0f, tone = QuotaTone.OK), quotaUsage(0, 5_000_000_000), "cero medido sí es una barra vacía")
    }

    @Test
    fun withoutAProjectionOrALimitThereIsNoBar() {
        assertNull(quotaUsage(null, 5_000_000_000))
        assertNull(quotaUsage(3_000_000_000, null))
        assertNull(quotaUsage(3_000_000_000, 0))
    }

    @Test
    fun theProjectionSentenceSaysBothFiguresAndThePercentage() {
        val quota = SupabaseQuota(projectedMonthlyBytes = 3_000_000_000, monthlyLimitBytes = 5_000_000_000)

        assertEquals("3 GB de 5 GB (60 %)", quotaProjectionLabel(quota))
        assertEquals("3 GB", quotaProjectionLabel(quota.copy(monthlyLimitBytes = null)))
        assertEquals("Sin proyección: aún no hay consumo medido.", quotaProjectionLabel(SupabaseQuota(monthlyLimitBytes = 5_000_000_000)))
    }

    @Test
    fun theProjectionSaysWhichWindowItComesFrom() {
        assertTrue("7 días" in quotaBasisLabel(SupabaseProjectionBasis.SEVEN_DAYS))
        assertTrue("24 horas" in quotaBasisLabel(SupabaseProjectionBasis.LAST_DAY))
        assertEquals("Sin datos suficientes para proyectar.", quotaBasisLabel(null))
    }

    @Test
    fun targetsHaveANameAndAnUnknownOneKeepsItsOwn() {
        assertEquals("Base de datos", quotaTargetLabel("rest"))
        assertEquals("Archivos", quotaTargetLabel("storage"))
        assertEquals("Inicio de sesión", quotaTargetLabel("auth"))
        assertEquals("realtime", quotaTargetLabel("realtime"))
    }

    // --- texto del estado global ---

    @Test
    fun everythingUpAndNoAlertsIsTheOnlyGreenSentence() {
        val overview = systemOverview(healthy)

        assertEquals(SystemStatusLevel.OPERATIVE, overview.level)
        assertEquals("Operativo", overview.label)
        assertEquals("Todo funciona con normalidad.", overview.headline)
        assertEquals("Las 3 réplicas están en pie y Supabase responde.", overview.detail)
    }

    @Test
    fun alertsThatCouldNotBeReadAreNotTheSameAsNoAlerts() {
        val overview = systemOverview(healthy.copy(alerts = null))

        assertEquals(SystemStatusLevel.UNKNOWN, overview.level)
        assertEquals("Todo responde, pero no se han podido leer las alertas.", overview.headline)
    }

    @Test
    fun anActiveAlertTakesTheGreenAwayEvenWithEverythingUp() {
        val one = systemOverview(healthy.copy(alerts = listOf(SystemAlert("SupabaseEgressHigh", "warning"))))
        val two = systemOverview(healthy.copy(alerts = listOf(SystemAlert("A"), SystemAlert("B"))))

        assertEquals(SystemStatusLevel.DEGRADED, one.level)
        assertEquals("Con alertas", one.label)
        assertEquals("Todo responde, pero hay una alerta activa.", one.headline)
        assertEquals("Todo responde, pero hay 2 alertas activas.", two.headline)
    }

    @Test
    fun aDownReplicaIsNamed() {
        val overview = systemOverview(
            healthy.copy(
                overallStatus = SystemStatusLevel.DEGRADED,
                ktorStatus = SystemStatusLevel.DEGRADED,
                replicas = listOf(replica("bot-1", true), replica("bot-2", false), replica("control-1", true, "control")),
            ),
        )

        assertEquals(SystemStatusLevel.DEGRADED, overview.level)
        assertEquals("El servicio funciona, pero con menos margen.", overview.headline)
        assertEquals("Réplica caída: bot-2.", overview.detail)
    }

    @Test
    fun noAppReplicaUpIsSaidAsWhatItMeansForTheApp() {
        val overview = systemOverview(
            healthy.copy(
                overallStatus = SystemStatusLevel.DOWN,
                ktorStatus = SystemStatusLevel.DOWN,
                replicas = listOf(replica("bot-1", false), replica("bot-2", false), replica("control-1", true, "control")),
            ),
        )

        assertEquals(SystemStatusLevel.DOWN, overview.level)
        assertEquals("La app se ha quedado sin servidor.", overview.headline)
        assertEquals("No hay ninguna réplica de la app en pie. Réplicas caídas: bot-1, bot-2.", overview.detail)
    }

    @Test
    fun supabaseDownComesFirstBecauseNothingWorksWithoutIt() {
        val overview = systemOverview(
            healthy.copy(overallStatus = SystemStatusLevel.DOWN, supabaseStatus = SystemStatusLevel.DOWN, prometheusResponding = false),
        )

        assertEquals(SystemStatusLevel.DOWN, overview.level)
        assertEquals("Supabase no responde.", overview.headline)
    }

    @Test
    fun aSilentPrometheusIsNeverGreenAndSaysWhatIsStillKnown() {
        val overview = systemOverview(
            SystemStatus(
                overallStatus = SystemStatusLevel.UNKNOWN,
                ktorStatus = SystemStatusLevel.UNKNOWN,
                supabaseStatus = SystemStatusLevel.OPERATIVE,
                prometheusResponding = false,
            ),
        )

        assertEquals(SystemStatusLevel.UNKNOWN, overview.level)
        assertEquals("Sin datos", overview.label)
        assertEquals("No se puede comprobar el estado del servidor.", overview.headline)
        assertTrue("Prometheus no responde" in overview.detail)
        assertTrue(overview.detail.endsWith("Supabase sí responde."))
    }

    @Test
    fun anEmptyReadingIsUnknownNotOperative() {
        val overview = systemOverview(SystemStatus())

        assertEquals(SystemStatusLevel.UNKNOWN, overview.level)
        assertEquals("No hay datos suficientes para saber cómo está el sistema.", overview.headline)
    }

    // --- «hace cuánto» ---

    @Test
    fun elapsedTimeUsesTheLargestUnitThatFits() {
        assertEquals("hace 0 s", elapsedLabel(now, now))
        assertEquals("hace 20 s", elapsedLabel(now - 20.seconds, now))
        assertEquals("hace 1 min", elapsedLabel(now - 60.seconds, now))
        assertEquals("hace 59 min", elapsedLabel(now - 59.minutes - 59.seconds, now))
        assertEquals("hace 3 h", elapsedLabel(now - 3.hours - 10.minutes, now))
        assertEquals("hace 1 día", elapsedLabel(now - 30.hours, now))
        assertEquals("hace 12 días", elapsedLabel(now - 12.days, now))
    }

    @Test
    fun aMissingInstantHasNoLabelAndAFutureOneIsNow() {
        assertNull(elapsedLabel(null, now), "sin instante no se inventa un «hace…»")
        assertEquals("hace 0 s", elapsedLabel(now + 5.minutes, now), "un reloj adelantado no produce tiempos negativos")
    }

    // --- rendimiento: dato real, Prometheus mudo, sin instrumentar ---

    @Test
    fun aMeasuredMetricIsShownAndAZeroIsAMeasurement() {
        val rows = performanceRows(
            healthy.copy(cpuPercent = 0.0, ramPercent = 41.25, supabaseDiskPercent = 3.5632, apiLatencyMs = 86, errors24h = 0),
        )

        assertEquals(listOf("0 %", "41,3 %", "3,6 %", "86 ms", "0"), rows.map { it.value })
        assertTrue(rows.all { it.measured && it.detail == null })
    }

    @Test
    fun theSameGapIsNamedByItsCause() {
        val missing = SystemStatus(notInstrumented = SystemMetric.entries.toSet())

        // Prometheus no contestó: todo lo que sale de él falta por eso.
        assertEquals(
            listOf(PROMETHEUS_SILENT, PROMETHEUS_SILENT, UNAVAILABLE, PROMETHEUS_SILENT, PROMETHEUS_SILENT),
            performanceRows(missing.copy(prometheusResponding = false)).map { it.value },
        )
        // Prometheus contesta y no tiene la serie: este despliegue no la mide.
        assertEquals(
            listOf(NOT_INSTRUMENTED, NOT_INSTRUMENTED, UNAVAILABLE, NOT_INSTRUMENTED, NOT_INSTRUMENTED),
            performanceRows(missing.copy(prometheusResponding = true)).map { it.value },
        )
        // Un backend anterior no dice cuál de las dos: no se adivina.
        assertTrue(performanceRows(missing.copy(prometheusResponding = null)).all { it.value == UNAVAILABLE })
        assertFalse(performanceRows(missing).any { it.measured })
    }

    @Test
    fun theDiskDoesNotComeFromPrometheusSoItsSilenceDoesNotExplainIt() {
        assertEquals(UNAVAILABLE, metricGapLabel(SystemMetric.SUPABASE_DISK, SystemStatus(prometheusResponding = false)))
    }

    @Test
    fun percentagesKeepNullAsAbsenceAndUseADecimalComma() {
        assertEquals("0 %", 0.0.toPercentLabel())
        assertEquals("12,5 %", 12.54.toPercentLabel())
        assertEquals("100 %", 99.96.toPercentLabel())
        assertNull(null.toPercentLabel())
    }

    // --- réplicas, alertas y sesión ---

    @Test
    fun aReplicaSaysWhatIsKnownAboutItAndNothingElse() {
        val up = SystemReplica("bot-1", "bot", up = true, seesSupabase = true, version = "886dac044b6e", startedAt = now - 3.hours)
        val blind = up.copy(seesSupabase = false)
        val down = SystemReplica("bot-2", "bot", up = false)

        assertEquals(listOf("En pie", "Ve Supabase", "886dac044b6e", "Arrancada hace 3 h"), up.labels())
        assertEquals("No llega a Supabase", replicaSupabaseLabel(blind))
        // De una réplica caída no se afirma ni que ve Supabase ni que no, ni una versión, ni un arranque.
        assertEquals(listOf("Caída", "Sin dato mientras esté caída", "Versión no publicada", "Arranque no disponible"), down.labels())
        assertEquals("Sin dato de Supabase", replicaSupabaseLabel(up.copy(seesSupabase = null)))
    }

    private fun SystemReplica.labels() =
        listOf(replicaStateLabel(this), replicaSupabaseLabel(this), replicaVersionLabel(this), replicaStartedLabel(this, now))

    @Test
    fun profilesAndSeveritiesAreTranslatedAndUnknownOnesKeptAsTheyCome() {
        assertEquals("App", replicaProfileLabel("bot"))
        assertEquals("Panel", replicaProfileLabel("control"))
        assertEquals("worker", replicaProfileLabel("worker"))
        assertNull(replicaProfileLabel(null))
        assertEquals("Crítica", alertSeverityLabel(SystemAlert("A", "critical")))
        assertEquals("Aviso", alertSeverityLabel(SystemAlert("A", "warning")))
        assertEquals("page", alertSeverityLabel(SystemAlert("A", "page")))
        assertEquals("Sin severidad", alertSeverityLabel(SystemAlert("A")))
        assertTrue(alertIsCritical(SystemAlert("A", "CRITICAL")))
        assertFalse(alertIsCritical(SystemAlert("A", "warning")))
    }

    @Test
    fun anAlertShowsItsSummaryAndReplicaOnlyWhenTheyExist() {
        assertEquals("Instancia caída · Réplica bot-2", alertDetail(SystemAlert("KtorInstanceDown", "critical", "Instancia caída", "bot-2")))
        assertEquals("Réplica bot-2", alertDetail(SystemAlert("KtorInstanceDown", "critical", replica = "bot-2")))
        assertNull(alertDetail(SystemAlert("SupabaseEgressHigh", "warning")))
    }

    @Test
    fun sessionRolesAreNamedAndNoRolesIsSaid() {
        assertEquals(
            "Administrador, Superadministrador",
            sessionRolesLabel(AdminSessionInfo("u", roles = listOf("admin", "superadmin"))),
        )
        assertEquals("soporte", sessionRolesLabel(AdminSessionInfo("u", roles = listOf("soporte"))))
        assertEquals("Ningún rol asignado", sessionRolesLabel(AdminSessionInfo("u")))
    }

    @Test
    fun theEnvironmentHasAName() {
        assertEquals("Producción", environmentDisplayName(Environment.PROD))
        assertEquals("Preproducción", environmentDisplayName(Environment.STAGING))
        assertEquals("Desarrollo", environmentDisplayName(Environment.DEV))
    }

    @Test
    fun theGuidanceIsShortAndNeverPromisesAnActionFromThePanel() {
        assertTrue(systemStatusGuidance.size in 4..5)
        assertTrue(systemStatusGuidance.all { it.startsWith("Si ") })
    }
}
