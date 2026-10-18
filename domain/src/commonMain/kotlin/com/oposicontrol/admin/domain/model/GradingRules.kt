package com.oposicontrol.admin.domain.model

/**
 * Reglas de puntuación de un examen (V1): qué pasa con las preguntas anuladas y si cada fallo penaliza.
 *
 * Contrato de servidor: `E1-V1-CONTROL-SPEC-OFFICIAL-EXAMS.md` y `E1-V1-PENALTY-CONTRACT.md`. Las reglas se congelan al
 * EMPEZAR cada intento: cambiarlas solo afecta a los intentos que empiecen después.
 *
 * Esta validación existe para dar un mensaje inmediato en el formulario. **No es la guarda**: el servidor (Ktor) vuelve a
 * validar y la base de datos (CHECK `examenes_penalizacion_chk`, trigger de reglas oficiales) lo hace una tercera vez.
 */
data class GradingRules(
    val examId: String,
    /** `null` = derivada (con reservas reemplaza, sin ellas no puntúa). */
    val annulledRule: String?,
    val rationalPenalty: Boolean,
    val fixedNumerator: Int,
    val fixedDenominator: Int,
    val isOfficial: Boolean,
    val isPublished: Boolean,
    val reserves: Int,
    val annulledMain: Int,
    /** Chequeo del servidor (`admin_official_rules_check`); `null` = no se pudo comprobar. */
    val check: GradingCheck?,
)

data class GradingCheck(val problem: String?, val warnings: List<String>, val effectiveRule: String?)

data class GradingSnapshot(
    val annulledRule: String?,
    val rationalPenalty: Boolean,
    val fixedNumerator: Int,
    val fixedDenominator: Int,
)

data class GradingAuditEntry(
    val id: String,
    val at: String?,
    val actorId: String?,
    val reason: String?,
    val before: GradingSnapshot?,
    val after: GradingSnapshot?,
)

/** Borrador del formulario. Las cifras viajan como texto hasta validar: un campo a medio teclear no es un número. */
data class GradingRulesDraft(
    val annulledRule: String?,
    val rationalPenalty: Boolean,
    val numerator: String,
    val denominator: String,
    val reason: String,
) {
    companion object {
        fun from(rules: GradingRules) = GradingRulesDraft(
            annulledRule = rules.annulledRule,
            rationalPenalty = rules.rationalPenalty,
            numerator = rules.fixedNumerator.toString(),
            denominator = rules.fixedDenominator.toString(),
            reason = "",
        )

        /**
         * Valor inicial de la casilla al CREAR un examen: activada si es oficial, desactivada si no
         * (E1-V1-PENALTY-CONTRACT, sección «Control spec»). Se guarda explícito; el panel no lo deja implícito.
         */
        fun rationalDefaultFor(isOfficial: Boolean): Boolean = isOfficial
    }
}

/** Una regla elegible en el formulario, con el texto que lee el administrador. */
enum class AnnulledRuleOption(val wire: String?, val label: String, val help: String) {
    DERIVED(null, "Automática", "Con reservas, la primera reserva sustituye a cada anulada; sin reservas, la anulada no cuenta."),
    REPLACE("REEMPLAZO_POR_RESERVA", "Reemplazar por reservas", "Cada anulada principal se sustituye por la siguiente reserva, en orden."),
    NO_SCORE("NO_PUNTUA", "No puntúa", "La anulada ni suma ni resta; el examen se puntúa sobre las demás."),
    ;

    companion object {
        fun fromWire(value: String?): AnnulledRuleOption = entries.firstOrNull { it.wire == value } ?: DERIVED
    }
}

sealed interface GradingValidation {
    data class Valid(val numerator: Int, val denominator: Int, val reason: String?) : GradingValidation
    data class Invalid(val field: Field, val message: String) : GradingValidation

    enum class Field { FRACTION, EXCLUSION, REASON }
}

object GradingRulesValidator {
    const val MAX_DENOMINATOR = 100
    const val REASON_MIN = 3
    const val REASON_MAX = 300

    /**
     * Misma regla que el servidor:
     * - racional y fracción fija son excluyentes (con racional la fracción es 0/1);
     * - `1 <= den <= 100` y `0 <= num <= den`;
     * - sobre un examen PUBLICADO el motivo es obligatorio.
     */
    fun validate(draft: GradingRulesDraft, published: Boolean): GradingValidation {
        val num = draft.numerator.trim().toIntOrNull()
        val den = draft.denominator.trim().toIntOrNull()
        if (draft.rationalPenalty) {
            if (num != 0 || den != 1) {
                return GradingValidation.Invalid(
                    GradingValidation.Field.EXCLUSION,
                    "La penalización racional y la fracción fija son excluyentes: con racional la fracción tiene que ser 0/1.",
                )
            }
        } else if (num == null || den == null || den < 1 || den > MAX_DENOMINATOR || num < 0 || num > den) {
            return GradingValidation.Invalid(
                GradingValidation.Field.FRACTION,
                "Fracción no válida: el denominador va de 1 a $MAX_DENOMINATOR y el numerador de 0 al denominador.",
            )
        }
        val reason = draft.reason.trim().ifEmpty { null }
        if (published && (reason == null || reason.length < REASON_MIN)) {
            return GradingValidation.Invalid(
                GradingValidation.Field.REASON,
                "El examen está publicado: explica el cambio (mínimo $REASON_MIN caracteres).",
            )
        }
        if (reason != null && reason.length > REASON_MAX) {
            return GradingValidation.Invalid(GradingValidation.Field.REASON, "El motivo no puede superar $REASON_MAX caracteres.")
        }
        return GradingValidation.Valid(num ?: 0, den ?: 1, reason)
    }

    /** Pasar a racional limpia la fracción; pasar a fracción deja la casilla racional apagada. Nunca quedan las dos. */
    fun withRational(draft: GradingRulesDraft, rational: Boolean): GradingRulesDraft =
        if (rational) draft.copy(rationalPenalty = true, numerator = "0", denominator = "1") else draft.copy(rationalPenalty = false)

    /** Texto legible de lo que hace la penalización con la configuración dada. */
    fun penaltySummary(rational: Boolean, num: Int, den: Int): String = when {
        rational -> "Cada fallo resta 1/(n−1) de un acierto, con n = opciones de esa pregunta (1/3 en preguntas de 4 opciones). Sin responder no penaliza."
        num == 0 -> "Sin penalización: los fallos no restan."
        else -> "Cada fallo resta $num/$den de un acierto. Sin responder no penaliza."
    }
}

/** Códigos de aviso/problema que devuelve `admin_official_rules_check`, con su explicación en lenguaje de operador. */
object GradingCheckTexts {
    fun problem(code: String): String = when (code) {
        "REEMPLAZO_NOT_ENOUGH_RESERVES" -> "Hay más preguntas anuladas que reservas utilizables: el examen no se puede publicar así."
        "RESERVE_ORDER_GAPS" -> "El orden de las reservas tiene huecos: tiene que ser 1, 2, 3…"
        "RESERVE_WITHOUT_MAIN" -> "Hay reservas pero ninguna pregunta principal."
        "RESERVE_BEFORE_MAIN" -> "Una reserva está colocada antes de la última pregunta principal."
        "DEMO_CONFLICT" -> "Una pregunta de demostración está anulada o es reserva."
        else -> "El servidor ha detectado un problema en las reglas de este examen."
    }

    fun warning(code: String): String = when (code) {
        "MORE_RESERVES_THAN_MAX" -> "Hay más reservas de las habituales (normalmente 3). No bloquea."
        "ANNULLED_RESERVE_SKIPPED" -> "Una reserva está anulada: se salta y se usa la siguiente."
        else -> "Aviso del servidor sin descripción."
    }
}
