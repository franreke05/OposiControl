package com.oposicontrol.admin.features.newstypes.presentation

data class NewsTypeItem(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    /** Clave natural del backend (`PUT /tipos-noticia/{codigo}`); fija una vez creado el tipo. */
    val code: String = "",
    /** Se reenvían tal cual al guardar: el PUT sustituye la fila y no hay campo para editarlos en esta pantalla. */
    val description: String? = null,
    val position: Int = 1,
)

private val ACCENTS = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n', 'ç' to 'c')

/**
 * Código de un tipo NUEVO a partir de su slug (o nombre): minúsculas ASCII, dígitos y guiones, como exige el backend
 * (`^[A-Za-z0-9_-]{1,64}$`). Vacío si no queda ningún carácter válido.
 */
internal fun newsTypeCodeFrom(source: String): String =
    source.trim().lowercase()
        .map { ACCENTS[it] ?: it }
        .joinToString("") { if (it in 'a'..'z' || it in '0'..'9') it.toString() else "-" }
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(64)
        .trimEnd('-')
