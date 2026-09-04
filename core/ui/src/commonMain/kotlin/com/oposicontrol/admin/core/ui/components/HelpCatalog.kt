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
        body = "Identificador interno para reconocer el examen de un vistazo. " +
            "Si lo dejas en blanco al crear el examen, se genera uno automáticamente.",
    )

    val comunidadAutonoma = HelpTopic(
        title = "Comunidad autónoma",
        body = "Limita el examen a una comunidad concreta. Déjalo sin elegir si el examen " +
            "vale para toda España.",
    )

    val tipoExamen = HelpTopic(
        title = "Tipo de examen",
        body = "Ráfaga son preguntas sueltas con la opción correcta marcada. Razonado añade una " +
            "explicación escrita. Inmersivo es el único que admite recursos multimedia. " +
            "Sólo se pueden elegir los modos que la especialidad tenga habilitados.",
    )

    val numeroPreguntas = HelpTopic(
        title = "Número de preguntas",
        body = "Se calcula solo a partir de las preguntas realmente asociadas al examen. " +
            "Añade o quita preguntas en la pestaña Preguntas para cambiarlo.",
    )

    val duracionExamen = HelpTopic(
        title = "Duración",
        body = "Minutos de los que dispone el usuario para completar el examen. " +
            "Déjalo en blanco si no quieres poner límite de tiempo.",
    )

    val examenOficial = HelpTopic(
        title = "Examen oficial",
        body = "Marca si el examen corresponde a una convocatoria real ya celebrada, " +
            "en vez de a un simulacro o material de práctica.",
    )

    val tipoAcceso = HelpTopic(
        title = "Tipo de acceso",
        body = "Gratuito: cualquier usuario puede abrirlo. Premium: hace falta comprarlo con " +
            "OposiTickets o tener una suscripción activa.",
    )

    val precioExamen = HelpTopic(
        title = "Precio en OposiTickets",
        body = "Lo que cuesta desbloquear este examen si un usuario lo compra suelto, " +
            "sin pasar por una suscripción.",
    )

    val demoEspecialidad = HelpTopic(
        title = "Demo",
        body = "Si los usuarios pueden probar contenido gratis antes de comprar se decide para " +
            "toda la especialidad, no examen a examen — se configura en Especialidades.",
    )

    val estadoExamen = HelpTopic(
        title = "Estado",
        body = "Borrador: sólo lo ves tú. Publicado: visible para los usuarios con acceso. " +
            "Archivado: deja de estar disponible sin borrar las preguntas.",
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
