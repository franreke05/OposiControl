# Client Decisions Required

Solo bloqueos que cambian el comportamiento o el modelo de datos. No se incluyen decisiones resolubles mediante código, lectura del esquema o configuración ya existente.

## DAILY_CHALLENGE_REWARD_POLICY

Los documentos contienen dos reglas incompatibles:

- recompensa por plan: FREE 5 OT, RAFAGAS 10 OT, RAZONADOS 20 OT, INMERSIVO 40 OT;
- recompensa universal: 40 OT para cualquier usuario.

Decisión necesaria: elegir política por plan o universal. La arquitectura dejará la recompensa configurable por plan y no fijará ninguna de las dos.

## COMMUNITY_INACTIVE_THRESHOLD

El cliente exige excluir usuarios inactivos de algunas métricas comunitarias, pero no define `X` días.

Decisión necesaria: número de días, alcance exacto de las métricas y si el umbral es configurable por entorno.

## INMERSIVO_PLAN_QUOTA

El plan INMERSIVO ofrece “1 o 2” exámenes/accesos inmersivos, mientras que el catálogo actual solo expresa el beneficio booleano `acceso_inmersivo`.

Decisión necesaria: cantidad exacta y periodicidad de reposición. Hasta entonces no se tratará como acceso ilimitado ni se sembrará una cuota arbitraria.

## RESOURCE_SAVE_ANALYTICS

El código tiene recursos y favoritos, pero los PDFs no fijan un contrato de producto para guardar/desguardar recursos.

Decisión necesaria: si `resource_save`, `resource_unsave` y `resource_revisit` son métricas oficiales, y qué significa “guardado” frente a favorito.

## PREDICTIVE_COMPARABLE_COHORT

La fórmula del percentil está definida conceptualmente, pero no el volumen/nivel comparable, mínimo de muestra, ventana temporal ni regla de empate.

Decisión necesaria antes de publicarlo como métrica visible. Hasta entonces solo puede existir como read model interno etiquetado como experimental.

## DIFFICULTY_TIME_QUINTILES

La dificultad exige quintiles de tiempo mediano, pero el texto no define si el quintil más lento siempre recibe más puntos ni cómo resolver empates y muestras insuficientes.

Decisión necesaria para cerrar el algoritmo 1–5. No bloquea la persistencia de la mediana ni el cálculo de fallos.

## INACTIVITY_AND_SESSION_SEMANTICS

Los documentos separan cierre, timeout, abandono explícito e inactividad prolongada, pero no fijan timeout ni la definición de “actividad válida”.

Decisión necesaria para métricas de abandono y rachas; no se inventa un timeout en esta fase.

## POPUP_FREQUENCY

El segundo PDF usa “cada X preguntas” sin definir X.

Decisión necesaria para una regla de popup concreta. El catálogo seguirá almacenando la frecuencia como configuración, sin hardcodearla.
