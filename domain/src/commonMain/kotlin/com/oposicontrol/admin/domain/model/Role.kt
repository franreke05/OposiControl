package com.oposicontrol.admin.domain.model

data class Role(
    val id: String,
    val name: String,
    /**
     * Código canónico del rol (`superadmin`, `admin`, `moderador`, `usuario`).
     *
     * El backend ya lo devolvía y la capa de datos lo descartaba, así que hasta
     * ahora el cliente sólo veía [name] — una etiqueta humana que alguien puede
     * reescribir sin cambiar ningún permiso. Sin el código no hay forma fiable de
     * saber qué rol concede acceso administrativo, que es justo lo que necesita
     * la pantalla de Usuarios para distinguir a un opositor de un administrador.
     *
     * Vacío cuando el origen no lo informa: es un dato ausente, no un rol sin
     * privilegios.
     */
    val code: String = "",
    val permissions: List<Permission>,
)

data class Permission(
    val key: String,
    val label: String,
)
