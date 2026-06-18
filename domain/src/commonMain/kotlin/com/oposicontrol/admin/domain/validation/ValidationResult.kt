package com.oposicontrol.admin.domain.validation

/**
 * Resultado de validar un formulario de dominio antes de enviarlo al backend.
 *
 * La capa de validacion vive en el dominio para que use cases, viewmodels y
 * tests compartan exactamente las mismas reglas. Cada feature aporta su propio
 * validador (ver [com.oposicontrol.admin.domain.validation.EtiquetaValidator],
 * [com.oposicontrol.admin.domain.validation.ComunidadValidator], ...).
 */
sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Invalid(
        val errors: List<FieldError>,
    ) : ValidationResult {
        val firstMessage: String
            get() = errors.firstOrNull()?.message ?: "Hay campos sin completar."
    }

    val isValid: Boolean get() = this is Valid
}

data class FieldError(
    val field: String,
    val message: String,
)

/** Convierte una lista de errores en [ValidationResult]; vacia => [ValidationResult.Valid]. */
fun List<FieldError>.toValidationResult(): ValidationResult =
    if (isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(this)

/**
 * Pequeno DSL para acumular errores de campo de forma legible:
 * ```
 * validate {
 *     require(name.isNotBlank()) { FieldError("nombre", "El nombre es obligatorio.") }
 * }
 * ```
 */
inline fun validate(block: ValidationScope.() -> Unit): ValidationResult {
    val scope = ValidationScope()
    scope.block()
    return scope.build()
}

class ValidationScope {
    private val errors = mutableListOf<FieldError>()

    inline fun require(condition: Boolean, error: () -> FieldError) {
        if (!condition) add(error())
    }

    fun add(error: FieldError) {
        errors += error
    }

    fun build(): ValidationResult = errors.toValidationResult()
}
