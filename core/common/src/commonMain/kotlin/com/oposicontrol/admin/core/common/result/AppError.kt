package com.oposicontrol.admin.core.common.result

sealed interface AppError {
    val message: String

    data class Network(
        override val message: String,
        val code: Int? = null,
    ) : AppError

    data class Authentication(
        override val message: String,
    ) : AppError

    data class Authorization(
        override val message: String,
    ) : AppError

    /**
     * Un rechazo de negocio con un mensaje ya redactado para quien usa la pantalla.
     *
     * [code] es el código de dominio que el backend pone en `ErrorDto.error`
     * —`EXAM_QUESTION_COUNT_INVALID`, `EXAM_CODE_DUPLICATE`—, cuando la respuesta
     * lo trae. Existe porque [message] dice QUÉ pasa y el código dice DE QUÉ
     * CAMPO: sin él, la única forma de señalar el campo culpable en un formulario
     * sería reconocer el texto del mensaje, que es acoplar la pantalla a una
     * redacción. `null` cuando el error no viene de una respuesta HTTP con cuerpo
     * (una validación del propio cliente) o cuando el cuerpo no trae la clave,
     * así que nada de lo que ya existía se rompe: los sitios que sólo pintan el
     * mensaje siguen igual.
     */
    data class Validation(
        override val message: String,
        val code: String? = null,
    ) : AppError

    data class NotFound(
        override val message: String,
    ) : AppError

    data class Unknown(
        override val message: String,
        val cause: Throwable? = null,
    ) : AppError
}
