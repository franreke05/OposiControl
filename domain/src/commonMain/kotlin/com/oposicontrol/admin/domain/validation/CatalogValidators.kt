package com.oposicontrol.admin.domain.validation

import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Tag

/**
 * Validadores de formularios de catalogo. Reglas centralizadas y testeables,
 * reutilizadas por use cases y viewmodels para no duplicar logica de UI.
 */
object EtiquetaValidator {
    const val MAX_NAME = 80

    fun validate(tag: Tag): ValidationResult = validate {
        require(tag.name.isNotBlank()) {
            FieldError("nombre", "La etiqueta necesita un nombre.")
        }
        require(tag.name.length <= MAX_NAME) {
            FieldError("nombre", "El nombre no puede superar $MAX_NAME caracteres.")
        }
    }
}

object ComunidadValidator {
    const val MAX_NAME = 120
    const val MAX_CODE = 10

    fun validate(community: Community): ValidationResult = validate {
        require(community.name.isNotBlank()) {
            FieldError("nombre", "La comunidad necesita un nombre.")
        }
        require(community.name.length <= MAX_NAME) {
            FieldError("nombre", "El nombre no puede superar $MAX_NAME caracteres.")
        }
        require(community.code.length <= MAX_CODE) {
            FieldError("codigo", "El codigo no puede superar $MAX_CODE caracteres.")
        }
        require(community.sortOrder >= 0) {
            FieldError("orden", "El orden no puede ser negativo.")
        }
    }
}
