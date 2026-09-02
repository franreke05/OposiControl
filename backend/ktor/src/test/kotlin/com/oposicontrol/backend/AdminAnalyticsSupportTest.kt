package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contrato de honestidad de la analítica, sin red.
 *
 * Lo que se prueba aquí no es que las piezas existan, sino que un cero real y un
 * "no se pudo leer" nunca se puedan confundir: ni en el acumulador, ni en las
 * derivadas, ni en el JSON que sale por el cable.
 */
class AdminAnalyticsSupportTest {

    private val json = Json { encodeDefaults = false }

    // -----------------------------------------------------------------
    // Disponibilidad
    // -----------------------------------------------------------------

    @Test
    fun aRealZeroIsAValueAndIsNeverReportedAsUnavailable() {
        val availability = AnalyticsAvailability()

        val value = availability.reading(AnalyticsMetricKey.GUEST_USERS, 0)

        assertEquals(0, value, "Un cero leído de la base debe llegar tal cual.")
        assertTrue(
            AnalyticsMetricKey.GUEST_USERS !in availability.unavailable(),
            "Cero usuarios invitados es un dato, no un fallo: no puede marcarse como no disponible.",
        )
    }

    @Test
    fun aFailedReadIsReportedAsUnavailableAndNeverAsZero() {
        val availability = AnalyticsAvailability()

        val value = availability.reading<Int>(AnalyticsMetricKey.TOTAL_USERS, null)

        assertNull(value, "Un fallo de lectura no puede convertirse en 0.")
        assertTrue(AnalyticsMetricKey.TOTAL_USERS in availability.unavailable())
        assertTrue(
            availability.gaps().none { it.key == AnalyticsMetricKey.TOTAL_USERS },
            "Un fallo de lectura es NO_DATA (reintentable), no un hueco estructural.",
        )
    }

    @Test
    fun zeroAndFailureCoexistInTheSameReportWithoutContaminatingEachOther() {
        // El caso que rompía el dashboard: dos métricas en la misma respuesta,
        // una con cero real y otra ilegible. Si ambas salieran como 0, el panel
        // mentiría sobre una de las dos y no habría forma de saber cuál.
        val availability = AnalyticsAvailability()

        val zero = availability.reading(AnalyticsMetricKey.POSTS, 0)
        val failed = availability.reading<Int>(AnalyticsMetricKey.COMMENTS, null)

        assertEquals(0, zero)
        assertNull(failed)
        assertEquals(listOf(AnalyticsMetricKey.COMMENTS), availability.unavailable())
    }

    @Test
    fun aStructuralGapIsAlsoUnavailableSoNoClientEverPrintsANumberForIt() {
        // `notInstrumented` es un subconjunto de `unavailableMetrics`: un cliente
        // que siga el contrato del dashboard (mirar sólo unavailableMetrics) debe
        // ocultar igualmente la tarjeta, aunque no entienda la lista nueva.
        val availability = AnalyticsAvailability()

        availability.gap(AnalyticsMetricKey.GROSS_REVENUE_CENTS, "Necesita SUM(precio_centimos).")

        assertTrue(AnalyticsMetricKey.GROSS_REVENUE_CENTS in availability.unavailable())
        val gap = availability.gaps().single()
        assertEquals(AnalyticsMetricKey.GROSS_REVENUE_CENTS, gap.key)
        assertTrue(gap.reason.isNotBlank(), "Un hueco sin razón no le dice a nadie qué construir.")
    }

    @Test
    fun theSameFailingMetricIsReportedOnceEvenIfItIsReadTwice() {
        val availability = AnalyticsAvailability()

        availability.reading<Int>(AnalyticsMetricKey.TOTAL_ANSWERS, null)
        availability.reading<Int>(AnalyticsMetricKey.TOTAL_ANSWERS, null)

        assertEquals(listOf(AnalyticsMetricKey.TOTAL_ANSWERS), availability.unavailable())
    }

    @Test
    fun noDataAndNotInstrumentedKeysAppearTogetherInTheUnion() {
        val availability = AnalyticsAvailability()

        availability.reading<Int>(AnalyticsMetricKey.TOTAL_ATTEMPTS, null)
        availability.gap(AnalyticsMetricKey.AVG_SCORE, "Necesita AVG(porcentaje_acierto).")

        val unavailable = availability.unavailable()
        assertTrue(AnalyticsMetricKey.TOTAL_ATTEMPTS in unavailable)
        assertTrue(AnalyticsMetricKey.AVG_SCORE in unavailable)
        assertEquals(2, unavailable.size)
        assertEquals(listOf(AnalyticsMetricKey.AVG_SCORE), availability.gaps().map { it.key })
    }

    // -----------------------------------------------------------------
    // Derivadas
    // -----------------------------------------------------------------

    @Test
    fun aRateOverAnEmptyPopulationIsUnknownAndNotZeroPercent() {
        // "0 % completa el onboarding" y "todavía no hay usuarios" son
        // afirmaciones distintas; devolver 0.0 aquí publicaría la primera.
        assertNull(analyticsRate(numerator = 0, denominator = 0))
        assertNull(analyticsRate(numerator = 5, denominator = 0))
        assertNull(analyticsRate(numerator = 5, denominator = -1))
    }

    @Test
    fun aRateOfZeroOverARealPopulationIsARealZero() {
        assertEquals(0.0, analyticsRate(numerator = 0, denominator = 3))
    }

    @Test
    fun aRateNeedsBothOperandsToBeReadable() {
        assertNull(analyticsRate(numerator = null, denominator = 10))
        assertNull(analyticsRate(numerator = 3, denominator = null))
    }

    @Test
    fun aRateIsAPercentageOfTheDenominator() {
        assertEquals(25.0, analyticsRate(numerator = 1, denominator = 4))
        assertEquals(100.0, analyticsRate(numerator = 4, denominator = 4))
    }

    @Test
    fun aRemainderIsTheTotalMinusEveryKnownBucket() {
        assertEquals(3, analyticsRemainder(total = 10, parts = listOf(3, 4)))
        assertEquals(10, analyticsRemainder(total = 10, parts = emptyList()))
        assertEquals(0, analyticsRemainder(total = 10, parts = listOf(10)))
    }

    @Test
    fun aRemainderWithAnUnreadableBucketIsUnknownAndNotTheWholeTotal() {
        // Si un cubo no se pudo leer, el resto no es "todo lo demás": es
        // desconocido. Publicar el total entero como "otros" sería inventar.
        assertNull(analyticsRemainder(total = 10, parts = listOf(3, null)))
        assertNull(analyticsRemainder(total = null, parts = listOf(3)))
    }

    @Test
    fun aRemainderNeverGoesNegativeWhenBucketsRaceAheadOfTheTotal() {
        // Los conteos son peticiones separadas; una escritura entre ellas puede
        // dejar las partes por encima del total leído antes.
        assertEquals(0, analyticsRemainder(total = 3, parts = listOf(5)))
    }

    // -----------------------------------------------------------------
    // Contrato de cable: clave de métrica == nombre de campo
    // -----------------------------------------------------------------

    @Test
    fun everyMetricKeyNamedAsUnavailableMatchesAFieldOfItsOwnDto() {
        // La clave es lo único que tiene el cliente para tapar la tarjeta
        // correcta. Si alguien renombra un campo del DTO y olvida la clave, la UI
        // ocultaría la tarjeta equivocada y enseñaría el número inválido de al
        // lado; este test lo detiene en compilación de contrato, no en producción.
        assertKeysAreFieldsOf(productoDto(unavailable = PRODUCTO_KEYS))
        assertKeysAreFieldsOf(estudioDto(unavailable = ESTUDIO_KEYS))
        assertKeysAreFieldsOf(retencionDto(unavailable = RETENCION_KEYS))
        assertKeysAreFieldsOf(comunidadDto(unavailable = COMUNIDAD_KEYS))
        assertKeysAreFieldsOf(economiaDto(unavailable = ECONOMIA_KEYS))
        assertKeysAreFieldsOf(marketingDto(unavailable = MARKETING_KEYS))
    }

    @Test
    fun anUnavailableMetricTravelsAsAnExplicitNullAndNotAsAMissingField() {
        // Un campo ausente vuelve a ser ambiguo en el cliente (¿ausente o cero?).
        // Debe viajar `null` explícito.
        val encoded = json.encodeToJsonElement(productoDto(unavailable = listOf(AnalyticsMetricKey.TOTAL_USERS)))
            .let { it as JsonObject }

        assertEquals(JsonNull, encoded[AnalyticsMetricKey.TOTAL_USERS])
    }

    @Test
    fun aRealZeroTravelsAsZeroAndIsAbsentFromTheUnavailableList() {
        val dto = productoDto(unavailable = emptyList()).copy(guestUsers = 0)

        val encoded = json.encodeToJsonElement(dto) as JsonObject

        assertEquals(JsonPrimitive(0), encoded[AnalyticsMetricKey.GUEST_USERS])
        assertTrue(dto.unavailableMetrics.isEmpty())
    }

    @Test
    fun moneyTravelsAsWholeCentsAndNeverAsADecimal() {
        // 99,99 € son 9999 céntimos. Un Double aquí acabaría cobrando 99,989999.
        val dto = economiaDto(unavailable = emptyList()).copy(grossRevenueCents = 9_999L)

        val encoded = json.encodeToJsonElement(dto) as JsonObject
        val raw = encoded[AnalyticsMetricKey.GROSS_REVENUE_CENTS]?.toString()

        assertEquals("9999", raw, "El dinero se serializa como entero de céntimos.")
        assertTrue(raw?.contains('.') == false, "Ningún importe puede llevar parte decimal.")
    }

    @Test
    fun theExamModeBreakdownUsesOnlyTheCanonicalVocabulary() {
        // rafaga / razonado / inmersivo. `guiado`/`guiada` son alias legacy: las
        // filas que aún los lleven se ven en `attemptsOtherMode`, pero ninguna
        // clave ni campo nuevo puede nombrarlos.
        assertEquals(setOf("rafaga", "razonado", "inmersivo"), CANONICAL_EXAM_TYPES)

        val encoded = json.encodeToJsonElement(estudioDto(unavailable = emptyList())) as JsonObject
        listOf(
            AnalyticsMetricKey.ATTEMPTS_RAFAGA,
            AnalyticsMetricKey.ATTEMPTS_RAZONADO,
            AnalyticsMetricKey.ATTEMPTS_INMERSIVO,
            AnalyticsMetricKey.ATTEMPTS_OTHER_MODE,
        ).forEach { key -> assertTrue(key in encoded.keys, "Falta el cubo $key en el DTO de estudio.") }

        val allKeys = PRODUCTO_KEYS + ESTUDIO_KEYS + RETENCION_KEYS + COMUNIDAD_KEYS + ECONOMIA_KEYS + MARKETING_KEYS
        assertTrue(
            allKeys.none { it.contains("guiad", ignoreCase = true) },
            "Ninguna métrica nueva puede usar el vocabulario legacy guiado/guiada.",
        )
    }

    // -----------------------------------------------------------------
    // Utilidades del test
    // -----------------------------------------------------------------

    private inline fun <reified T> assertKeysAreFieldsOf(dto: T) {
        val encoded = json.encodeToJsonElement(dto) as JsonObject
        val declared = assertNotNull(encoded["unavailableMetrics"]).toString()
            .removeSurrounding("[", "]")
            .split(',')
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
        declared.forEach { key ->
            assertTrue(
                key in encoded.keys,
                "La clave '$key' se anuncia como no disponible pero no existe como campo de ${T::class.simpleName}.",
            )
        }
    }

    private fun productoDto(unavailable: List<String>) = AdminAnalyticsProductoDto(
        totalUsers = null,
        guestUsers = null,
        onboardedUsers = null,
        onboardingCompletionRate = null,
        totalAppEvents = null,
        appEventsLast30d = null,
        totalStudySessions = null,
        studySessionsLast30d = null,
        finishedStudySessions = null,
        abandonedStudySessions = null,
        studySessionCompletionRate = null,
        generatedAt = GENERATED_AT,
        unavailableMetrics = unavailable,
        notInstrumented = emptyList(),
    )

    private fun estudioDto(unavailable: List<String>) = AdminAnalyticsEstudioDto(
        totalAttempts = null,
        attemptsCompleted = null,
        attemptsInProgress = null,
        attemptsOtherState = null,
        attemptsLast30d = null,
        attemptsFirstTime = null,
        attemptsRanked = null,
        attemptsRafaga = null,
        attemptsRazonado = null,
        attemptsInmersivo = null,
        attemptsOtherMode = null,
        totalAnswers = null,
        correctAnswers = null,
        wrongAnswers = null,
        unansweredAnswers = null,
        globalAccuracyRate = null,
        globalErrorRate = null,
        firstTimeAnswers = null,
        randomAnswers = null,
        changedAnswers = null,
        markedForReviewAnswers = null,
        hardestQuestions = null,
        generatedAt = GENERATED_AT,
        unavailableMetrics = unavailable,
        notInstrumented = emptyList(),
    )

    private fun retencionDto(unavailable: List<String>) = AdminAnalyticsRetencionDto(
        usersWithStreak = null,
        usersWithActiveStreak = null,
        usersStreak7Plus = null,
        usersStreak30Plus = null,
        activeUsers1d = null,
        activeUsers7d = null,
        activeUsers30d = null,
        usersWithCompetitiveState = null,
        leagueDistribution = null,
        generatedAt = GENERATED_AT,
        unavailableMetrics = unavailable,
        notInstrumented = emptyList(),
    )

    private fun comunidadDto(unavailable: List<String>) = AdminAnalyticsComunidadDto(
        posts = null,
        postsPublished = null,
        postsPinned = null,
        comments = null,
        reports = null,
        reportsPending = null,
        reportsReviewed = null,
        reportsRewarded = null,
        questionFeedback = null,
        questionFeedbackPending = null,
        generatedAt = GENERATED_AT,
        unavailableMetrics = unavailable,
        notInstrumented = emptyList(),
    )

    private fun economiaDto(unavailable: List<String>) = AdminAnalyticsEconomiaDto(
        wallets = null,
        walletTransactions = null,
        walletCredits = null,
        walletDebits = null,
        walletNeutralMovements = null,
        purchases = null,
        subscriptions = null,
        subscriptionsActive = null,
        subscriptionsTrial = null,
        subscriptionsPaused = null,
        subscriptionsCancelled = null,
        subscriptionsExpired = null,
        subscriptionsOtherState = null,
        subscriptionsExpiring30d = null,
        commercialProducts = null,
        commercialProductsActive = null,
        grossRevenueCents = null,
        avgOrderValueCents = null,
        ticketsInCirculation = null,
        generatedAt = GENERATED_AT,
        unavailableMetrics = unavailable,
        notInstrumented = emptyList(),
    )

    private fun marketingDto(unavailable: List<String>) = AdminAnalyticsMarketingDto(
        attributedUsers = null,
        referredUsers = null,
        usersWithReferralCode = null,
        unattributedUsers = null,
        attributionCoverageRate = null,
        generatedAt = GENERATED_AT,
        unavailableMetrics = unavailable,
        notInstrumented = emptyList(),
    )

    private companion object {
        const val GENERATED_AT = "2026-09-02T00:00:00Z"

        val PRODUCTO_KEYS = listOf(
            AnalyticsMetricKey.TOTAL_USERS,
            AnalyticsMetricKey.GUEST_USERS,
            AnalyticsMetricKey.ONBOARDED_USERS,
            AnalyticsMetricKey.ONBOARDING_COMPLETION_RATE,
            AnalyticsMetricKey.TOTAL_APP_EVENTS,
            AnalyticsMetricKey.APP_EVENTS_LAST_30D,
            AnalyticsMetricKey.TOTAL_STUDY_SESSIONS,
            AnalyticsMetricKey.STUDY_SESSIONS_LAST_30D,
            AnalyticsMetricKey.FINISHED_STUDY_SESSIONS,
            AnalyticsMetricKey.ABANDONED_STUDY_SESSIONS,
            AnalyticsMetricKey.STUDY_SESSION_COMPLETION_RATE,
        )

        val ESTUDIO_KEYS = listOf(
            AnalyticsMetricKey.TOTAL_ATTEMPTS,
            AnalyticsMetricKey.ATTEMPTS_COMPLETED,
            AnalyticsMetricKey.ATTEMPTS_IN_PROGRESS,
            AnalyticsMetricKey.ATTEMPTS_OTHER_STATE,
            AnalyticsMetricKey.ATTEMPTS_LAST_30D,
            AnalyticsMetricKey.ATTEMPTS_FIRST_TIME,
            AnalyticsMetricKey.ATTEMPTS_RANKED,
            AnalyticsMetricKey.ATTEMPTS_RAFAGA,
            AnalyticsMetricKey.ATTEMPTS_RAZONADO,
            AnalyticsMetricKey.ATTEMPTS_INMERSIVO,
            AnalyticsMetricKey.ATTEMPTS_OTHER_MODE,
            AnalyticsMetricKey.TOTAL_ANSWERS,
            AnalyticsMetricKey.CORRECT_ANSWERS,
            AnalyticsMetricKey.WRONG_ANSWERS,
            AnalyticsMetricKey.UNANSWERED_ANSWERS,
            AnalyticsMetricKey.GLOBAL_ACCURACY_RATE,
            AnalyticsMetricKey.GLOBAL_ERROR_RATE,
            AnalyticsMetricKey.FIRST_TIME_ANSWERS,
            AnalyticsMetricKey.RANDOM_ANSWERS,
            AnalyticsMetricKey.CHANGED_ANSWERS,
            AnalyticsMetricKey.MARKED_FOR_REVIEW_ANSWERS,
            AnalyticsMetricKey.HARDEST_QUESTIONS,
        )

        val RETENCION_KEYS = listOf(
            AnalyticsMetricKey.USERS_WITH_STREAK,
            AnalyticsMetricKey.USERS_WITH_ACTIVE_STREAK,
            AnalyticsMetricKey.USERS_STREAK_7_PLUS,
            AnalyticsMetricKey.USERS_STREAK_30_PLUS,
            AnalyticsMetricKey.ACTIVE_USERS_1D,
            AnalyticsMetricKey.ACTIVE_USERS_7D,
            AnalyticsMetricKey.ACTIVE_USERS_30D,
            AnalyticsMetricKey.USERS_WITH_COMPETITIVE_STATE,
            AnalyticsMetricKey.LEAGUE_DISTRIBUTION,
        )

        val COMUNIDAD_KEYS = listOf(
            AnalyticsMetricKey.POSTS,
            AnalyticsMetricKey.POSTS_PUBLISHED,
            AnalyticsMetricKey.POSTS_PINNED,
            AnalyticsMetricKey.COMMENTS,
            AnalyticsMetricKey.REPORTS,
            AnalyticsMetricKey.REPORTS_PENDING,
            AnalyticsMetricKey.REPORTS_REVIEWED,
            AnalyticsMetricKey.REPORTS_REWARDED,
            AnalyticsMetricKey.QUESTION_FEEDBACK,
            AnalyticsMetricKey.QUESTION_FEEDBACK_PENDING,
        )

        val ECONOMIA_KEYS = listOf(
            AnalyticsMetricKey.WALLETS,
            AnalyticsMetricKey.WALLET_TRANSACTIONS,
            AnalyticsMetricKey.WALLET_CREDITS,
            AnalyticsMetricKey.WALLET_DEBITS,
            AnalyticsMetricKey.WALLET_NEUTRAL_MOVEMENTS,
            AnalyticsMetricKey.PURCHASES,
            AnalyticsMetricKey.SUBSCRIPTIONS,
            AnalyticsMetricKey.SUBSCRIPTIONS_ACTIVE,
            AnalyticsMetricKey.SUBSCRIPTIONS_TRIAL,
            AnalyticsMetricKey.SUBSCRIPTIONS_PAUSED,
            AnalyticsMetricKey.SUBSCRIPTIONS_CANCELLED,
            AnalyticsMetricKey.SUBSCRIPTIONS_EXPIRED,
            AnalyticsMetricKey.SUBSCRIPTIONS_OTHER_STATE,
            AnalyticsMetricKey.SUBSCRIPTIONS_EXPIRING_30D,
            AnalyticsMetricKey.COMMERCIAL_PRODUCTS,
            AnalyticsMetricKey.COMMERCIAL_PRODUCTS_ACTIVE,
            AnalyticsMetricKey.GROSS_REVENUE_CENTS,
            AnalyticsMetricKey.AVG_ORDER_VALUE_CENTS,
            AnalyticsMetricKey.TICKETS_IN_CIRCULATION,
        )

        val MARKETING_KEYS = listOf(
            AnalyticsMetricKey.ATTRIBUTED_USERS,
            AnalyticsMetricKey.REFERRED_USERS,
            AnalyticsMetricKey.USERS_WITH_REFERRAL_CODE,
            AnalyticsMetricKey.UNATTRIBUTED_USERS,
            AnalyticsMetricKey.ATTRIBUTION_COVERAGE_RATE,
        )
    }
}
