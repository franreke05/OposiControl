package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.PlanBenefit

/**
 * Lo que la pantalla de planes DICE y las reglas con las que valida, separado de cómo lo pinta. Las reglas son las del
 * backend (`AdminPlanDto.validated`, `AdminPlanBenefitDto.validated` y el CHECK de nivel de producción): la pantalla las
 * adelanta para no mandar un guardado que se sabe rechazado, y el servidor las vuelve a aplicar.
 */

/** CHECK `planes_suscripcion_canonical_v2_nivel_check`: 0..3. */
internal const val PLAN_LEVEL_MAX: Int = 3

internal val PLAN_LEVELS: List<Int> = (0..PLAN_LEVEL_MAX).toList()

internal fun planLevelLabel(level: Int): String = "Nivel $level"

internal const val PLAN_FILTER_ACTIVE: String = "active"
internal const val PLAN_FILTER_HIDDEN: String = "hidden"
internal const val PLAN_FILTER_INACTIVE: String = "inactive"

internal val PLAN_STATUS_FILTERS: List<CatalogFilterOption> = listOf(
    CatalogFilterOption(null, "Todos"),
    CatalogFilterOption(PLAN_FILTER_ACTIVE, "Activos"),
    CatalogFilterOption(PLAN_FILTER_HIDDEN, "Ocultos"),
    CatalogFilterOption(PLAN_FILTER_INACTIVE, "Inactivos"),
)

/** Pestañas de la ficha de un plan. */
enum class PlanTab(val id: String, val label: String) {
    PLAN("plan", "Plan"),
    BENEFITS("benefits", "Beneficios"),
    ;

    companion object {
        fun fromId(id: String): PlanTab = entries.firstOrNull { it.id == id } ?: PLAN
    }
}

/** Lo que hay escrito en la ficha de un plan. La posición va como texto: es lo que el administrador teclea. */
data class PlanDraft(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val level: Int = 0,
    val position: String = "1",
    val active: Boolean = true,
    val visible: Boolean = true,
)

internal fun CommercialPlan.toDraft() = PlanDraft(
    code = code,
    name = name,
    description = description.orEmpty(),
    level = level,
    position = position.toString(),
    active = active,
    visible = visible,
)

/** El código se guarda recortado y en minúsculas (así lo normaliza el servidor). */
internal fun normalisedPlanCode(code: String): String = code.trim().lowercase()

private val PLAN_CODE = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")

/**
 * Primer motivo por el que el plan no se puede guardar, o `null`.
 *
 * [existingCodes] son los códigos de los OTROS planes: el código es la clave natural del plan y uno repetido pisaría al
 * que ya existe en vez de crear otro.
 */
internal fun planProblem(draft: PlanDraft, isNew: Boolean, existingCodes: Set<String>): String? {
    if (isNew) {
        val code = normalisedPlanCode(draft.code)
        if (code.isEmpty()) return "El plan necesita un código."
        if (!PLAN_CODE.matches(code)) return "El código sólo admite letras, números, punto, guion y guion bajo (máximo 64)."
        if (code in existingCodes) return "Ya existe un plan con ese código."
    }
    if (draft.name.isBlank()) return "El plan necesita un nombre."
    if (draft.level !in 0..PLAN_LEVEL_MAX) return "El nivel debe estar entre 0 y $PLAN_LEVEL_MAX."
    if (parseNonNegativeInt(draft.position) == null) return "La posición debe ser un número entero no negativo."
    if (draft.visible && !draft.active) return "Un plan visible debe estar activo: si no, se enseña y no se puede contratar."
    return null
}

/** El plan que se enviaría al guardar, o `null` si el borrador no es válido (ver [planProblem]). */
internal fun PlanDraft.toPlan(base: CommercialPlan?): CommercialPlan? {
    val positionValue = parseNonNegativeInt(position) ?: return null
    return CommercialPlan(
        id = base?.id.orEmpty(),
        code = base?.code ?: normalisedPlanCode(code),
        name = name.trim(),
        description = description.trim().takeIf { it.isNotEmpty() },
        level = level,
        active = active,
        visible = visible,
        position = positionValue,
        benefits = base?.benefits.orEmpty(),
    )
}

/** Campos opcionales que se enviaron vacíos y el servidor devolvió con su valor anterior (ver [productKeptByServer]). */
internal fun planKeptByServer(sent: CommercialPlan, saved: CommercialPlan): List<String> =
    listOfNotNull("la descripción".takeIf { sent.description == null && saved.description != null })

/** De qué tipo es el único valor que lleva un beneficio. */
enum class BenefitKind(val id: String, val label: String) {
    BOOLEAN("boolean", "Sí / no"),
    INTEGER("integer", "Número entero"),
    NUMERIC("numeric", "Número decimal"),
    TEXT("text", "Texto"),
    ;

    companion object {
        fun fromId(id: String): BenefitKind = entries.firstOrNull { it.id == id } ?: BOOLEAN
    }
}

/** El tipo de un beneficio guardado, o `null` si no lleva exactamente un valor (un dato que el servidor ya no admite). */
internal fun PlanBenefit.kind(): BenefitKind? = if (filledValueCount != 1) {
    null
} else {
    when {
        booleanValue != null -> BenefitKind.BOOLEAN
        integerValue != null -> BenefitKind.INTEGER
        numericValue != null -> BenefitKind.NUMERIC
        else -> BenefitKind.TEXT
    }
}

/** Un decimal sin ceros de relleno y con coma: `10.0` → «10», `0.5` → «0,5». */
internal fun formatBenefitNumber(value: Double): String {
    val whole = value.toLong()
    return if (whole.toDouble() == value) whole.toString() else value.toString().replace('.', ',')
}

/** Lo que vale un beneficio, dicho para leerlo. */
internal fun benefitValueLabel(benefit: PlanBenefit): String = when (benefit.kind()) {
    BenefitKind.BOOLEAN -> if (benefit.booleanValue == true) "Sí" else "No"
    BenefitKind.INTEGER -> benefit.integerValue.toString()
    BenefitKind.NUMERIC -> formatBenefitNumber(benefit.numericValue ?: 0.0)
    BenefitKind.TEXT -> benefit.textValue.orEmpty()
    null -> if (benefit.filledValueCount == 0) "Sin valor" else "Valor ambiguo"
}

/**
 * Lo que hay escrito en el formulario de un beneficio. `id` en blanco = beneficio nuevo.
 *
 * [kindLocked]: en un beneficio guardado el tipo no se cambia. Un cambio de tipo exigiría vaciar la columna anterior, y el
 * guardado del servidor sólo escribe las que recibe: quedarían dos valores, que es justo lo que la regla prohíbe.
 */
data class BenefitDraft(
    val id: String = "",
    val code: String = "",
    val kind: BenefitKind = BenefitKind.BOOLEAN,
    val kindLocked: Boolean = false,
    val booleanValue: Boolean = true,
    /** Valor tecleado cuando el tipo es entero, decimal o texto. */
    val text: String = "",
) {
    val isNew: Boolean get() = id.isBlank()
}

internal fun PlanBenefit.toDraft(): BenefitDraft {
    val kind = kind()
    return BenefitDraft(
        id = id,
        code = code,
        kind = kind ?: BenefitKind.BOOLEAN,
        kindLocked = kind != null,
        booleanValue = booleanValue ?: true,
        text = when (kind) {
            BenefitKind.INTEGER -> integerValue.toString()
            BenefitKind.NUMERIC -> formatBenefitNumber(numericValue ?: 0.0)
            BenefitKind.TEXT -> textValue.orEmpty()
            else -> ""
        },
    )
}

private val BENEFIT_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$")

internal fun parseBenefitInteger(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || t.length > 10) return null
    return t.toIntOrNull()
}

internal fun parseBenefitNumeric(text: String): Double? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty() || t.length > 18) return null
    if (!t.all { it.isDigit() || it == '.' || it == '-' }) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** [others] son los demás beneficios del plan: el código no se puede repetir dentro de un mismo plan. */
internal fun benefitProblem(draft: BenefitDraft, others: List<PlanBenefit>): String? {
    val code = draft.code.trim()
    if (code.isEmpty()) return "El beneficio necesita un código."
    if (draft.isNew) {
        if (!BENEFIT_CODE.matches(code)) return "El código sólo admite letras, números, punto, guion y guion bajo."
        if (others.any { it.code == code }) return "Este plan ya tiene un beneficio con ese código."
    }
    return when (draft.kind) {
        BenefitKind.BOOLEAN -> null
        BenefitKind.INTEGER -> if (parseBenefitInteger(draft.text) == null) "El valor debe ser un número entero." else null
        BenefitKind.NUMERIC -> if (parseBenefitNumeric(draft.text) == null) "El valor debe ser un número, por ejemplo 0,5." else null
        BenefitKind.TEXT -> if (draft.text.isBlank()) "El beneficio necesita un valor." else null
    }
}

/** El beneficio que se enviaría: exactamente un valor relleno, el de su tipo. `null` si el borrador no es válido. */
internal fun BenefitDraft.toBenefit(planId: String): PlanBenefit? {
    val base = PlanBenefit(id = id, planId = planId, code = code.trim())
    return when (kind) {
        BenefitKind.BOOLEAN -> base.copy(booleanValue = booleanValue)
        BenefitKind.INTEGER -> base.copy(integerValue = parseBenefitInteger(text) ?: return null)
        BenefitKind.NUMERIC -> base.copy(numericValue = parseBenefitNumeric(text) ?: return null)
        BenefitKind.TEXT -> base.copy(textValue = text.trim().takeIf { it.isNotEmpty() } ?: return null)
    }
}

internal fun planMatches(plan: CommercialPlan, query: String, filter: String?): Boolean {
    val q = query.trim().lowercase()
    val matchesQuery = q.isEmpty() || plan.name.lowercase().contains(q) || plan.code.lowercase().contains(q)
    val matchesFilter = when (filter) {
        PLAN_FILTER_ACTIVE -> plan.active && plan.visible
        PLAN_FILTER_HIDDEN -> plan.active && !plan.visible
        PLAN_FILTER_INACTIVE -> !plan.active
        else -> true
    }
    return matchesQuery && matchesFilter
}

internal fun benefitCountLabel(count: Int): String = when (count) {
    0 -> "Sin beneficios"
    1 -> "1 beneficio"
    else -> "$count beneficios"
}

internal fun planRowSpec(plan: CommercialPlan): CatalogRowSpec {
    val status = commerceStatus(plan.active, plan.visible)
    return CatalogRowSpec(
        key = plan.id,
        title = plan.name.ifBlank { "Plan sin nombre" },
        eyebrow = plan.code,
        meta = listOf(planLevelLabel(plan.level), benefitCountLabel(plan.benefits.size)),
        status = status.label,
        statusTone = status.tone,
    )
}

internal fun planFacts(plan: CommercialPlan?): List<WorkspaceFact> {
    if (plan == null) return emptyList()
    return listOf(
        WorkspaceFact("Estado", commerceStatus(plan.active, plan.visible).label),
        WorkspaceFact("Nivel", plan.level.toString()),
        WorkspaceFact("Beneficios", plan.benefits.size.toString()),
        WorkspaceFact("Posición", plan.position.toString()),
    )
}

internal val planInformationLines: List<String> = listOf(
    "El plan no lleva precio: lo fijan las tarifas del producto de suscripción que apunta a él (pestaña Productos).",
    "El nivel ordena los planes: uno mayor incluye lo que ofrece el menor.",
    "Un plan visible debe estar activo; si no, se enseñaría en la app y no se podría contratar.",
    "Un beneficio se puede añadir o cambiar de valor, pero no borrar ni cambiar de tipo: el servidor no ofrece esas operaciones.",
)
