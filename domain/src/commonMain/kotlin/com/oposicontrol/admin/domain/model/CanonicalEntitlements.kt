package com.oposicontrol.admin.domain.model

/** DB-backed plan snapshot. Prices are intentionally not part of this policy model. */
data class CanonicalPlanEntitlements(
    val planCode: String,
    val benefitCodes: Set<String>,
)

object CanonicalEntitlementPolicy {
    const val RAFAGA_BENEFIT = "acceso_rafaga"
    const val RAZONADO_BENEFIT = "acceso_razonado"
    const val INMERSIVO_BENEFIT = "acceso_inmersivo"

    fun canAccess(plan: CanonicalPlanEntitlements, externalExamType: String): Boolean {
        val examType = ExamType.fromExternal(externalExamType) ?: return false
        val requiredBenefit = when (examType) {
            ExamType.RAFAGA -> RAFAGA_BENEFIT
            ExamType.RAZONADO -> RAZONADO_BENEFIT
            ExamType.INMERSIVO -> INMERSIVO_BENEFIT
        }
        return requiredBenefit in plan.benefitCodes
    }
}
