package com.oposicontrol.admin.features.specialties.presentation

/** One-shot specialty operation outcomes consumed by the route. */
sealed interface SpecialtiesEffect {
    data class Saved(val id: SpecialtyId) : SpecialtiesEffect
    data class Deleted(val id: SpecialtyId) : SpecialtiesEffect
}
