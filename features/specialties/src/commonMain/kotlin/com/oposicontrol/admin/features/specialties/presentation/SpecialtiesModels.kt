package com.oposicontrol.admin.features.specialties.presentation

/** Stable specialty identifier used by presentation contracts. */
@JvmInline
value class SpecialtyId(val value: String)

data class SpecialtyItem(
    val id: SpecialtyId,
    val name: String,
    val slug: String,
    val status: String,
    val linkedExams: Int,
)
