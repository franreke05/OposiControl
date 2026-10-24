# Formato del PDF de importación de preguntas (OposiControl)

Versión 2026-10-22. Para quien prepara los PDF (Arturo). El panel lee el texto del PDF, así que **el PDF tiene que tener texto seleccionable**: un escaneado no sirve (no hay OCR).

## Reglas generales

- El **examen destino se elige en el panel**, no en el PDF.
- Cada pregunta empieza con una línea que diga **exactamente** `--- PREGUNTA ---` (en mayúsculas, sola en la línea). Variantes como `--- PREGUNTA 1 ---` o `--- pregunta ---` se marcan como error.
- Cada campo va en su propia línea, en MAYÚSCULAS y con dos puntos: `CAMPO: texto`. Si un texto ocupa varias líneas, las siguientes líneas (sin `CAMPO:`) se juntan con el campo anterior.
- Respuesta correcta: solo `A`, `B`, `C` o `D`.

## Campos de cada pregunta

| Campo | Obligatorio | Qué es |
|---|---|---|
| `TITULO:` | Sí | El enunciado de la pregunta. |
| `A:` `B:` `C:` `D:` | Sí, las cuatro | Las opciones. Ninguna puede ir vacía. |
| `RESPUESTA:` | Sí | `A`, `B`, `C` o `D`. |
| `ETIQUETA:` | Recomendado | **Los temas de la pregunta, separados por comas**. Ej.: `ETIQUETA: Constitución, Derechos fundamentales`. Máximo 20 por pregunta. |
| `RAZONAMIENTO:` | Solo Razonada e Inmersiva | La explicación que se ve tras responder. En Ráfaga se ignora (aviso). |
| `FUENTE:` | Solo Razonada e Inmersiva | Referencia: `BOE núm. 122`, artículo, libro… En Ráfaga se ignora (aviso). |
| `ESTADO: ANULADA` | Solo si el tribunal la anuló | Marca la pregunta como anulada en ese examen. |
| `MOTIVO_ANULACION:` | **Obligatorio si va `ESTADO: ANULADA`** | El motivo del tribunal (máx. 500 caracteres). |

### Etiquetas (temas)

- Escribe el **nombre del tema tal como está en el catálogo** de etiquetas de Control (no distingue mayúsculas, acentos ni espacios de más: `constitucion` = `Constitución`).
- Sepáralos **con comas**. Si pones `;` se acepta como coma pero la fila sale con aviso; los temas repetidos se quitan solos; con más de 20 solo se enlazan los 20 primeros (aviso).
- **Al subir el PDF**, la revisión ya marca en amarillo (advertencia) cada fila con un tema que no está en el catálogo, diciendo cuál.
- Al **publicar el lote**, cada tema que exista en el catálogo se **enlaza** a la pregunta y aparece en el editor de preguntas, en «Etiquetas (temas)».
- Un tema que **no exista** NO se crea: la revisión del lote lo avisa en esa fila, y la pregunta se publica sin ese tema. Si es un tema nuevo de verdad, créalo antes en el editor de preguntas («Crear etiqueta») o pide que lo creen, y vuelve a subir el PDF; si es una errata, corrígela en el PDF.

## Exámenes oficiales (bloques de principales y reservas)

Si el examen tiene preguntas de reserva, sepáralas con estas líneas exactas:

```
--- BLOQUE: PRINCIPAL ---
(preguntas principales)
--- BLOQUE: RESERVA ---
(preguntas de reserva)
```

Sin marcador, todas son principales. Basta con una sola línea `--- BLOQUE: RESERVA ---` justo antes de la primera reserva: todo lo anterior cuenta como principal.

> **Desde el 22 de octubre de 2026 (versión V1) las reservas se importan como reservas.** En la revisión del lote cada pregunta muestra si es principal o reserva, y al publicar quedan marcadas así en el examen. En un examen oficial, cuando una pregunta se anula, la primera reserva ocupa su lugar (según las reglas de puntuación del examen).

## Formato del razonamiento (negrita, colores, listas…)

En `RAZONAMIENTO:` (Razonada e Inmersiva) se puede dar formato con estas marcas. Es la lista que propuso Arturo, con estos ajustes:

| Formato | Cómo se escribe | Ojo |
|---|---|---|
| Negrita | `**texto**` | Siempre **dos** símbolos: `*texto*` con uno solo sale como texto normal |
| Cursiva | `__texto__` | Dos guiones bajos |
| Subrayado | `++texto++` | Dos signos más |
| Tachado | `~~texto~~` | Dos tildes |
| Color | `[color:red]texto[/color]` | Solo `black`, `gray`, `red`, `orange`, `yellow`, `green`, `blue`, `purple` (en inglés) o un código `#RRGGBB` (p. ej. `#1E88E5`) |
| Tamaño | `[size:18]texto[/size]` | Solo `12`, `14`, `16`, `18` o `20` |
| Párrafo nuevo | `[p]` | Un salto de línea normal del PDF **no** separa: se convierte en un espacio. Para separar, `[p]` |
| Lista | `[-]` al principio de cada línea | Cada `[-]` va **al principio de su propia línea** (nunca en medio, ni pegado detrás de un `[p]` en la misma línea); el punto termina con el siguiente `[-]`, un `[p]` o el final |
| Alineación | `[align:center]texto[/align]` | `left`, `center`, `right` o `justify`; no se mete una alineación dentro de otra |

- Las marcas se pueden combinar (`**[color:red]importante[/color]**`), pero cada una se cierra **antes** que la que la contiene y dentro del mismo párrafo.
- Una marca mal escrita (`[color=red]`, `[P]`, `[size:15]`, un color que no está en la lista) hace que esa pregunta salga en **rojo** en la revisión del lote, diciendo qué falla. Se corrige en el PDF o en la propia revisión.
- Para escribir el símbolo tal cual, sin que sea marca, se pone una barra delante: `\*`, `\_`, `\[`.
- No hay enlaces ni imágenes dentro del texto (las imágenes van como recursos de la pregunta).

Ejemplo (comprobado con el analizador real del panel):
```
RAZONAMIENTO: El **artículo 27** de la Constitución reconoce el derecho a la educación.[p]
[-] Es un [color:red]derecho fundamental[/color].
[-] Lo desarrolla la __LOE__.[p]
[align:center][size:12]Fuente: CE, art. 27[/size][/align]
```

## Ejemplo completo

```
--- PREGUNTA ---
TITULO: ¿Qué artículo de la Constitución reconoce el derecho a la educación?
A: Artículo 14
B: Artículo 27
C: Artículo 35
D: Artículo 43
RESPUESTA: B
ETIQUETA: Constitución, Derechos fundamentales
RAZONAMIENTO: El artículo 27 CE reconoce el derecho a la educación y la libertad de enseñanza.
FUENTE: Constitución Española, art. 27

--- PREGUNTA ---
TITULO: ¿Cuál es el plazo general para interponer un recurso de alzada contra un acto expreso?
A: Quince días
B: Un mes
C: Dos meses
D: Tres meses
RESPUESTA: B
ETIQUETA: Procedimiento administrativo, Ley 39/2015
ESTADO: ANULADA
MOTIVO_ANULACION: El tribunal la anuló por error en el enunciado.
```

(En una Ráfaga se pueden quitar `RAZONAMIENTO:` y `FUENTE:`.)

## Qué pasa al subirlo

1. El panel analiza el PDF y crea un **lote en revisión**: cada pregunta aparece con su estado (lista, advertencia, duplicada o error).
2. Se revisan, se corrigen o se vinculan las duplicadas, y se pulsa **Publicar lote**.
3. Al publicar, las preguntas entran en el examen **con sus etiquetas**.
