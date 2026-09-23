package com.oposicontrol.admin.core.ui.components

/**
 * Textos de ayuda del panel.
 *
 * Están centralizados por dos motivos. El primero es de tono: escritos juntos se
 * nota enseguida si uno se ha ido a la jerga, y aquí la regla es que se entienda
 * en la primera lectura sin saber cómo está construido el sistema. El segundo es
 * de mantenimiento: cuando cambie una regla de negocio hay un único sitio donde
 * corregir su explicación, en vez de una cadena repetida en cuatro pantallas.
 *
 * Ninguno de estos textos describe implementación. Describen qué significa el
 * dato para quien administra y qué pasa si lo toca.
 */
object Help {

    // --- Rendimiento y estudio ---------------------------------------------

    val notaMedia = HelpTopic(
        title = "Nota media",
        body = "Media de todas las preguntas que ha respondido el usuario, sobre 10. " +
            "Cuenta el histórico completo, no sólo el último examen.",
    )

    val evolucion = HelpTopic(
        title = "Evolución",
        body = "Compara la nota actual con la que tenía en el ciclo anterior. " +
            "En verde ha mejorado, en rojo ha empeorado.",
        example = "+0,8 significa que ha subido casi un punto desde la última referencia.",
    )

    val percentil = HelpTopic(
        title = "Percentil",
        body = "Qué porcentaje de usuarios está por debajo de éste.",
        example = "Percentil 90: va mejor que el 90% de los opositores de su especialidad.",
    )

    val racha = HelpTopic(
        title = "Racha",
        body = "Días seguidos que el usuario ha estudiado. Se pierde si deja pasar un día " +
            "entero sin responder ninguna pregunta.",
    )

    val liga = HelpTopic(
        title = "Liga",
        body = "Grupo en el que compite el usuario con opositores de nivel parecido. " +
            "Sube o baja de liga según su rendimiento.",
    )

    val ranking = HelpTopic(
        title = "Posición en el ranking",
        body = "Se calcula sola a partir de la liga y el rendimiento del usuario. " +
            "No se puede editar a mano desde aquí.",
    )

    // --- Economía -----------------------------------------------------------

    val oposiTickets = HelpTopic(
        title = "OposiTickets (OT)",
        body = "La moneda interna de la app. Los usuarios la ganan estudiando y la gastan " +
            "en el Market.",
    )

    val saldo = HelpTopic(
        title = "Saldo",
        body = "OposiTickets disponibles ahora mismo. El saldo no se edita directamente: " +
            "cambia sólo cuando se registra un movimiento.",
    )

    val ledger = HelpTopic(
        title = "Movimientos",
        body = "El historial completo de entradas y salidas de OT de este usuario. " +
            "Cada línea dice cuánto, cuándo y por qué.",
        example = "Si un saldo no cuadra, la respuesta siempre está en esta lista.",
    )

    val idempotencia = HelpTopic(
        title = "Clave de operación",
        body = "Identificador único de esta concesión. Sirve para que, si se envía dos veces " +
            "por error, los OT se entreguen una sola vez.",
    )

    val renovacion = HelpTopic(
        title = "Renovación",
        body = "Si el plan se vuelve a cobrar solo al terminar el periodo. " +
            "Si está desactivada, el acceso caduca en la fecha de fin.",
    )

    val consumibles = HelpTopic(
        title = "Consumibles",
        body = "Objetos de un solo uso que el usuario compra con OT. Al usarlos desaparecen " +
            "de su inventario.",
    )

    val potenciadores = HelpTopic(
        title = "Potenciadores",
        body = "Dan una ventaja temporal al usuario, como puntos extra durante un rato.",
    )

    val protectores = HelpTopic(
        title = "Protectores",
        body = "Evitan que el usuario pierda la racha un día que no estudia. " +
            "Se gastan automáticamente cuando hacen falta.",
    )

    val packs = HelpTopic(
        title = "Packs",
        body = "Varios productos vendidos juntos a un precio único, normalmente más barato " +
            "que comprarlos por separado.",
    )

    val promociones = HelpTopic(
        title = "Promociones",
        body = "Descuentos con fecha de inicio y de fin. Fuera de esas fechas el producto " +
            "vuelve solo a su precio normal.",
    )

    val reglasPopup = HelpTopic(
        title = "Reglas de aparición",
        body = "Determinan cuándo puede la app enseñar esta oferta a un usuario. " +
            "Sirven para no repetirle siempre lo mismo.",
    )

    // --- Marketing y analítica ----------------------------------------------

    val atribucion = HelpTopic(
        title = "Atribución",
        body = "De dónde vino el usuario cuando se registró: una campaña, una búsqueda " +
            "o directamente.",
    )

    val conversion = HelpTopic(
        title = "Conversión",
        body = "Porcentaje de usuarios que llegaron a hacer lo que se les pedía, " +
            "como suscribirse tras ver una oferta.",
    )

    // --- Contenido -----------------------------------------------------------

    val importacion = HelpTopic(
        title = "Importación de preguntas",
        body = "Sube un PDF, revisa lo que se ha detectado, corrige lo que haga falta " +
            "y publica. Nada llega a los usuarios hasta el último paso.",
    )

    val duplicados = HelpTopic(
        title = "Duplicados",
        body = "Preguntas del PDF que se parecen mucho a otras que ya existen. " +
            "Conviene revisarlas antes de publicar para no repetir contenido.",
    )

    val publicacionAtomica = HelpTopic(
        title = "Publicación completa",
        body = "Todas las preguntas del lote se publican juntas o no se publica ninguna. " +
            "Así un fallo a mitad no deja el examen incompleto.",
    )

    val capacidadesEspecialidad = HelpTopic(
        title = "Modos de la especialidad",
        body = "Qué tipos de examen se pueden crear en esta especialidad: ráfaga, razonado " +
            "o inmersivo.",
    )

    val recursosInmersiva = HelpTopic(
        title = "Recursos",
        body = "Material de apoyo (texto, imagen o audio) que acompaña a la pregunta. " +
            "Sólo las preguntas inmersivas pueden tener recursos.",
    )

    val codigoExamen = HelpTopic(
        title = "Código del examen",
        // Decía «Identificador interno», que es EXACTAMENTE lo contrario de lo que
        // este campo es. El identificador interno es `id`, un UUID que no se
        // enseña en ninguna pantalla; esto es el código humano, el que se dicta por
        // teléfono y se busca en el catálogo. Llamarlo interno invitaba a no
        // tocarlo por miedo a romper algo.
        body = "Código humano para reconocer el examen de un vistazo: es el que se busca y se " +
            "menciona. No es el identificador interno del sistema, que nunca se muestra. " +
            "Si lo dejas en blanco al crear el examen, se genera uno automáticamente.",
    )

    val comunidadAutonoma = HelpTopic(
        title = "Comunidad autónoma",
        body = "Limita el examen a una comunidad concreta. Déjalo sin elegir si el examen " +
            "vale para toda España.",
    )

    /**
     * Las tres descripciones están atadas a un comportamiento verificado y no a
     * una idea de producto: ráfaga es el único tipo con acceso sin sesión,
     * razonado es el que guarda el razonamiento de la respuesta e inmersivo es
     * el que se apoya en recursos.
     *
     * Los recursos cuelgan de la PREGUNTA, no del examen, pero SÍ hay regla que los
     * ata al tipo (K0-8, verificado en la base): los disparadores `validar_recurso_*`
     * sólo admiten recursos en preguntas inmersivas, y `validar_pregunta_examen`
     * sólo vincula preguntas inmersivas a exámenes inmersivos. Una versión anterior
     * de este comentario decía que ninguna regla los ataba, y no era cierto.
     */
    val tipoExamen = HelpTopic(
        title = "Tipo de examen",
        body = "Ráfaga son preguntas tipo test encadenadas; es el único tipo que puede servirse " +
            "gratis sin sesión. Razonado añade a cada pregunta la explicación de la respuesta " +
            "correcta. Inmersivo permite que las preguntas se apoyen en recursos. Sólo se " +
            "eligen los modos que la especialidad tenga habilitados.",
    )

    /**
     * Existe porque una vista de recursos de un examen enseña algo que NO le
     * pertenece: no hay ninguna relación entre un examen y un recurso, sólo
     * entre una pregunta y sus recursos. Sin decirlo, la pestaña parecería el
     * sitio donde se añaden.
     */
    val recursosExamen = HelpTopic(
        title = "Recursos de un examen",
        body = "Los recursos cuelgan de cada pregunta, no del examen. Aquí se ven juntos los de " +
            "todas sus preguntas vinculadas, para revisarlos de una vez; para añadir o cambiar " +
            "uno hay que ir a la pregunta.",
    )

    val numeroPreguntas = HelpTopic(
        title = "Número de preguntas",
        // K2-2: desde M1 `total_preguntas` lo mantiene la base al vincular, quitar o
        // archivar preguntas, con la misma definición que la lista del examen
        // (preguntas activas vinculadas). La ayuda anterior decía que la consola lo
        // contaba al abrir el examen porque entonces ningún escritor lo mantenía.
        body = "Es el número de preguntas activas vinculadas al examen. Lo mantiene la base de datos " +
            "al vincular, quitar o archivar preguntas. Para cambiarlo, vincula o quita preguntas " +
            "desde el banco de Preguntas.",
    )

    /**
     * Neutra a propósito (DEBT-034). Decía «Minutos de los que dispone el usuario»
     * y «poner límite de tiempo»: una afirmación de comportamiento que nada
     * respalda —ninguna ruta aplica la duración como límite y la app no la recibe—.
     */
    val duracionExamen = HelpTopic(
        title = "Duración",
        body = "Duración del examen en minutos. Déjala vacía si no quieres fijar una duración.",
    )

    val examenOficial = HelpTopic(
        title = "Examen oficial",
        body = "Marca si el examen corresponde a una convocatoria real ya celebrada, " +
            "en vez de a un simulacro o material de práctica.",
    )

    /**
     * Decía que premium «hace falta comprarlo con OposiTickets o tener una
     * suscripción activa». Ninguna de las dos cosas la sostiene el contrato: no hay
     * compra de examen suelto y la consola no evalúa suscripciones contra exámenes.
     * Lo único verificado es que sólo una ráfaga gratuita Y PUBLICADA se sirve sin
     * sesión (K0-8, EX-18): la ruta pública no sirve borradores.
     */
    val tipoAcceso = HelpTopic(
        title = "Tipo de acceso",
        body = "Gratuito o premium. Es un dato del examen: esta consola no calcula qué usuario " +
            "puede abrirlo. Sólo un examen Ráfaga gratuito y publicado se sirve también sin iniciar sesión.",
    )

    /**
     * El cuerpo dice explícitamente que hoy no desbloquea nada porque hoy no lo
     * desbloquea: `precio_tickets` es una columna real que ningún camino de
     * compra consulta. Callarlo dejaría creer que poner un precio pone un examen
     * a la venta. Tampoco lo compara con «una suscripción»: eso insinuaba que una
     * suscripción desbloquea exámenes, y el contrato no lo dice.
     */
    val precioExamen = HelpTopic(
        title = "Precio en OposiTickets",
        body = "Lo que costaría este examen comprado suelto. Hoy es sólo un dato guardado: " +
            "todavía no existe ninguna compra de examen individual que lo consulte.",
    )

    val demoEspecialidad = HelpTopic(
        title = "Demo",
        body = "Si los usuarios pueden probar contenido gratis antes de comprar se decide para " +
            "toda la especialidad, no examen a examen — se configura en Especialidades.",
    )

    /**
     * VISIBILIDAD ≠ ACCESO. Decía «Publicado: visible para los usuarios con
     * acceso», que mezclaba las dos cosas, y «Borrador: sólo lo ves tú», cuando lo
     * ve cualquier administrador. El catálogo de la app exige examen activo y con
     * fecha de publicación: eso es lo que distingue los tres estados.
     */
    val estadoExamen = HelpTopic(
        title = "Estado",
        body = "Borrador: no aparece en el catálogo de la app. Publicado: aparece en el catálogo; " +
            "quién puede abrirlo lo decide el tipo de acceso. Archivado: retirado del catálogo, " +
            "sin borrar sus preguntas.",
        example = "Publicar y despublicar quedan registrados en el historial del examen.",
    )

    // --- Sistema -------------------------------------------------------------

    val estadoSistema = HelpTopic(
        title = "Estado del sistema",
        body = "Si el panel puede hablar ahora mismo con el servidor y con la base de datos.",
        example = "«No conectado» no significa que haya un fallo: puede que esa medición " +
            "todavía no esté instalada.",
    )

    val entorno = HelpTopic(
        title = "Entorno",
        body = "Contra qué instalación está trabajando el panel. En producción los cambios " +
            "los ven los usuarios de inmediato.",
    )
}

/**
 * Índice del panel de ayuda global (icono `?` de la cabecera).
 *
 * Un resumen por área, no un manual. Lo justo para orientarse cuando se abre una
 * sección que no se toca a menudo.
 */
data class HelpSection(
    val title: String,
    val body: String,
)

val globalHelpSections: List<HelpSection> = listOf(
    HelpSection(
        "Contenido",
        "Especialidades, exámenes y preguntas. La importación de PDF deja las preguntas " +
            "en revisión: no llegan a los usuarios hasta que las publicas.",
    ),
    HelpSection(
        "Usuarios",
        "Perfil, rendimiento y actividad de cada opositor. Las posiciones de ranking se " +
            "calculan solas; desde aquí se consultan, no se editan.",
    ),
    HelpSection(
        "OposiTickets",
        "La moneda de la app. El saldo nunca se escribe a mano: cambia sólo al registrar " +
            "un movimiento, y todos quedan en el historial.",
    ),
    HelpSection(
        "Market",
        "Lo que los usuarios pueden comprar con OT. Cambiar el precio o la visibilidad " +
            "de un producto tiene efecto inmediato en la app.",
    ),
    HelpSection(
        "Analítica",
        "Uso real del producto. Cuando algo aparece como «sin datos» o «no conectado» es " +
            "que esa medición aún no está instalada, no que valga cero.",
    ),
)
