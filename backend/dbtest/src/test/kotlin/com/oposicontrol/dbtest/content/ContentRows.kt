package com.oposicontrol.dbtest.content

import java.sql.Connection
import java.util.UUID

/*
 * Filas minimas VALIDAS contra la forma viva, creadas por los propios tests.
 *
 * El fixture no trae ni una fila (ni de usuarios ni de negocio). Cada helper
 * rellena solo lo que los NOT NULL y CHECK vivos exigen y devuelve el id, para
 * que los asserts nombren filas concretas. Se ejecutan como el superusuario del
 * escenario: la RLS no aplica, los triggers SI (igual que para service_role).
 */

fun nuevoId(): String = UUID.randomUUID().toString()

fun Connection.crearUsuario(id: String = nuevoId()): String {
    ejecutar("insert into auth.users (id, email) values ('$id', '$id@test.local')")
    return id
}

fun Connection.crearEspecialidad(id: String = nuevoId()): String {
    ejecutar(
        "insert into public.especialidades (id, codigo, nombre, slug) " +
            "values ('$id', 'ESP-$id', 'Especialidad $id', 'especialidad-$id')",
    )
    return id
}

fun Connection.crearExamen(
    especialidadId: String,
    tipoExamen: String = "rafaga",
    tipoAcceso: String = "gratuito",
    activo: Boolean = false,
    totalPreguntas: Int = 1,
    id: String = nuevoId(),
): String {
    ejecutar(
        "insert into public.examenes " +
            "(id, codigo_examen, especialidad_id, titulo, tipo_examen, tipo_acceso, activo, total_preguntas) " +
            "values ('$id', 'EX-$id', '$especialidadId', 'Examen de prueba', " +
            "'$tipoExamen', '$tipoAcceso', $activo, $totalPreguntas)",
    )
    return id
}

fun Connection.crearPregunta(
    especialidadId: String,
    tipoPregunta: String = "rafaga",
    activa: Boolean = true,
    id: String = nuevoId(),
): String {
    ejecutar(
        "insert into public.preguntas (id, codigo_pregunta, especialidad_id, tipo_pregunta, titulo, activa) " +
            "values ('$id', 'P-$id', '$especialidadId', '$tipoPregunta', 'Enunciado de prueba', $activa)",
    )
    return id
}

fun Connection.crearOpcion(preguntaId: String, letra: String, posicion: Int, id: String = nuevoId()): String {
    ejecutar(
        "insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) " +
            "values ('$id', '$preguntaId', '$letra', 'Opcion $letra', $posicion)",
    )
    return id
}

fun Connection.crearSolucion(preguntaId: String, opcionCorrectaId: String, id: String = nuevoId()): String {
    ejecutar(
        "insert into public.soluciones_pregunta (id, pregunta_id, opcion_correcta_id, razonamiento) " +
            "values ('$id', '$preguntaId', '$opcionCorrectaId', 'Razonamiento de prueba.')",
    )
    return id
}

/** `numero_en_examen` es NOT NULL sin DEFAULT en vivo: siempre hay que mandarlo. */
fun Connection.vincular(examenId: String, preguntaId: String, posicion: Int, numero: Int = posicion) {
    ejecutar(
        "insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) " +
            "values ('$examenId', '$preguntaId', $posicion, $numero)",
    )
}

fun Connection.crearLote(examenId: String, tipoExamen: String = "rafaga", id: String = nuevoId()): String {
    ejecutar(
        "insert into public.lotes_importacion_preguntas (id, examen_id, nombre_archivo_original, tipo_examen) " +
            "values ('$id', '$examenId', 'lote.pdf', '$tipoExamen')",
    )
    return id
}

/** `trg_preparar_intento_examen` completa snapshot, numero de intento y ranking. */
fun Connection.crearIntento(usuarioId: String, examenId: String, id: String = nuevoId()): String {
    ejecutar(
        "insert into public.intentos_examen (id, usuario_id, examen_id) " +
            "values ('$id', '$usuarioId', '$examenId')",
    )
    return id
}

/**
 * `trg_validar_y_calcular_respuesta_intento` completa usuario, examen, posicion,
 * `opcion_correcta_id` y `es_correcta` a partir de la solucion vigente.
 */
fun Connection.crearRespuesta(
    intentoId: String,
    preguntaId: String,
    opcionSeleccionadaId: String,
    id: String = nuevoId(),
): String {
    ejecutar(
        "insert into public.respuestas_intento (id, intento_id, pregunta_id, opcion_seleccionada_id) " +
            "values ('$id', '$intentoId', '$preguntaId', '$opcionSeleccionadaId')",
    )
    return id
}
