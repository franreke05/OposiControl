package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult

/**
 * El motor que comparten los cuatro catálogos editables de OposiMarket —packs, servicios, promociones y pop-ups—.
 *
 * Los cuatro hacen exactamente lo mismo con datos distintos: una lista descargada, una ficha abierta con su borrador, la
 * pregunta antes de tirar cambios y un guardado. Escribirlo cuatro veces serían cuatro sitios donde olvidar una regla;
 * aquí está una vez, sin Compose y sin red, y cada catálogo aporta sólo su [MarketEditorSpec].
 */

/** Lo que el motor necesita saber de una entidad. Funciones puras: se prueban sin ViewModel. */
internal interface MarketEditorSpec<T, D> {
    val emptyDraft: D

    fun id(item: T): String

    fun toDraft(item: T): D

    /** Primer motivo por el que el borrador no se puede guardar, o `null`. [others] no incluye a la entidad abierta. */
    fun problem(draft: D, isNew: Boolean, others: List<T>): String?

    /** La entidad que se enviaría, o `null` si el borrador no es válido. [base] es la entidad abierta (`null` si es nueva). */
    fun toEntity(draft: D, base: T?): T?

    fun matches(item: T, query: String, filter: String?): Boolean

    /**
     * Nombres de los campos opcionales que se enviaron VACÍOS y el servidor devolvió con su valor anterior.
     *
     * El guardado del servidor sólo escribe las columnas que recibe, y un campo vacío no viaja: vaciar un dato opcional de
     * una ficha ya guardada no se persiste. Antes que decir «guardado» sobre algo que no cambió, la ficha lo cuenta.
     */
    fun keptByServer(sent: T, saved: T): List<String> = emptyList()
}

/** Lo que se le pide a un catálogo editable. [D] es el tipo de su borrador. */
sealed interface EditorAction<out D> {
    /** Abre una ficha (o, con `null`, un borrador nuevo). Si hay cambios sin guardar, pide confirmación antes. */
    data class Open(val id: String?) : EditorAction<Nothing>
    data object ConfirmOpen : EditorAction<Nothing>
    data object DismissOpen : EditorAction<Nothing>
    data class UpdateSearch(val value: String) : EditorAction<Nothing>
    data class UpdateFilter(val filter: String?) : EditorAction<Nothing>
    data class SetPage(val pageIndex: Int) : EditorAction<Nothing>
    data class UpdateDraft<D>(val draft: D) : EditorAction<D>
    data object Save : EditorAction<Nothing>
    data object Discard : EditorAction<Nothing>
}

data class PendingEditorOpen(val id: String?)

/** Estado de un catálogo editable: la lista descargada y la ficha abierta. */
data class MarketEditor<T, D>(
    /** `Idle` = todavía no se ha pedido: cada sección se carga la primera vez que se abre. */
    val items: UiAsync<List<T>> = UiAsync.Idle,
    val searchQuery: String = "",
    val filter: String? = null,
    val pageIndex: Int = 0,
    val selectedId: String? = null,
    val creatingNew: Boolean = false,
    val draft: D,
    /** Destino pendiente de confirmar cuando se intenta salir de una ficha con cambios. */
    val pendingOpen: PendingEditorOpen? = null,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    /** Aviso que deja el último guardado (ver [MarketEditorSpec.keptByServer]). */
    val notice: String? = null,
    /** Se está releyendo una lista que ya estaba en pantalla: se sigue viendo mientras tanto. */
    val refreshing: Boolean = false,
    /** La última relectura falló: se conserva lo que ya se había leído, y se dice. */
    val refreshFailed: Boolean = false,
) {
    val all: List<T> get() = (items as? UiAsync.Success)?.value.orEmpty()
    val isLoaded: Boolean get() = items is UiAsync.Success || items is UiAsync.Empty
    val isOpen: Boolean get() = selectedId != null || creatingNew
}

internal fun <T, D> MarketEditor<T, D>.selected(spec: MarketEditorSpec<T, D>): T? =
    selectedId?.let { id -> all.firstOrNull { spec.id(it) == id } }

internal fun <T, D> MarketEditor<T, D>.visible(spec: MarketEditorSpec<T, D>): List<T> =
    all.filter { spec.matches(it, searchQuery, filter) }

/** Hay algo en la ficha que no está en el servidor. */
internal fun <T, D> MarketEditor<T, D>.dirty(spec: MarketEditorSpec<T, D>): Boolean = when {
    creatingNew -> draft != spec.emptyDraft
    else -> selected(spec)?.let { spec.toDraft(it) != draft } ?: false
}

internal fun <T, D> MarketEditor<T, D>.problem(spec: MarketEditorSpec<T, D>): String? =
    if (!isOpen) null else spec.problem(draft, creatingNew, all.filter { spec.id(it) != selectedId })

private fun <T, D> MarketEditor<T, D>.opened(spec: MarketEditorSpec<T, D>, id: String?): MarketEditor<T, D> {
    val item = id?.let { wanted -> all.firstOrNull { spec.id(it) == wanted } }
    return copy(
        selectedId = item?.let(spec::id),
        creatingNew = id == null,
        draft = item?.let(spec::toDraft) ?: spec.emptyDraft,
        pendingOpen = null,
        actionState = UiAsync.Idle,
        notice = null,
    )
}

/** Todo lo que no toca la red. `Save` no pasa por aquí: lo resuelve el ViewModel con [saved] y [saveFailed]. */
internal fun <T, D> MarketEditor<T, D>.reduce(spec: MarketEditorSpec<T, D>, action: EditorAction<D>): MarketEditor<T, D> = when (action) {
    is EditorAction.Open -> when {
        action.id != null && action.id == selectedId && !creatingNew -> this
        dirty(spec) -> copy(pendingOpen = PendingEditorOpen(action.id))
        else -> opened(spec, action.id)
    }
    EditorAction.ConfirmOpen -> pendingOpen?.let { opened(spec, it.id) } ?: this
    EditorAction.DismissOpen -> copy(pendingOpen = null)
    is EditorAction.UpdateSearch -> copy(searchQuery = action.value, pageIndex = 0)
    is EditorAction.UpdateFilter -> copy(filter = action.filter, pageIndex = 0)
    is EditorAction.SetPage -> copy(pageIndex = action.pageIndex)
    is EditorAction.UpdateDraft -> copy(draft = action.draft, actionState = UiAsync.Idle, notice = null)
    EditorAction.Discard -> copy(draft = selected(spec)?.let(spec::toDraft) ?: spec.emptyDraft, actionState = UiAsync.Idle, notice = null)
    EditorAction.Save -> this
}

/**
 * Empieza una lectura. La primera vez no hay nada que enseñar y la lista pasa a «cargando»; después, lo ya leído se queda
 * en pantalla mientras se relee: un refresco no debe vaciar la ficha que se está mirando.
 */
internal fun <T, D> MarketEditor<T, D>.loading(): MarketEditor<T, D> =
    if (isLoaded) copy(refreshing = true, refreshFailed = false) else copy(items = UiAsync.Loading, refreshFailed = false)

/**
 * Aplica una lectura. Un refresco nunca tira lo que se está escribiendo: si la ficha abierta tenía cambios se conserva, y
 * si no, se relee del servidor. Si la entidad abierta ya no existe, se abre la primera. Si la relectura falla habiendo
 * datos, se conservan y se avisa; sólo una primera lectura fallida es un error de pantalla.
 *
 * «Tenía cambios» se mide contra la lista ANTERIOR, no contra la que llega: medirlo contra la nueva confundiría un
 * cambio hecho por otro administrador con algo tecleado aquí, y guardar después lo pisaría sin que nadie lo hubiera pedido.
 */
internal fun <T, D> MarketEditor<T, D>.loaded(spec: MarketEditorSpec<T, D>, result: AppResult<List<T>>): MarketEditor<T, D> = when (result) {
    is AppResult.Failure ->
        if (isLoaded) copy(refreshing = false, refreshFailed = true) else copy(items = UiAsync.Error(result.error), refreshing = false)
    is AppResult.Success -> {
        val wasDirty = dirty(spec)
        val next = copy(
            items = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
            refreshing = false,
            refreshFailed = false,
        )
        val first = result.value.firstOrNull()?.let(spec::id)
        when {
            creatingNew -> next
            selectedId != null && next.selected(spec) == null ->
                if (first != null) next.opened(spec, first) else next.copy(selectedId = null, draft = spec.emptyDraft)
            selectedId != null -> if (wasDirty) next else next.opened(spec, selectedId)
            first != null -> next.opened(spec, first)
            else -> next
        }
    }
}

/** Aplica un guardado correcto: la ficha queda abierta sobre lo que devolvió el servidor, que es lo que de verdad hay. */
internal fun <T, D> MarketEditor<T, D>.saved(spec: MarketEditorSpec<T, D>, sent: T, saved: T): MarketEditor<T, D> {
    val savedId = spec.id(saved)
    val merged = if (all.any { spec.id(it) == savedId }) all.map { if (spec.id(it) == savedId) saved else it } else all + saved
    val kept = spec.keptByServer(sent, saved)
    return copy(
        items = UiAsync.Success(merged),
        selectedId = savedId,
        creatingNew = false,
        draft = spec.toDraft(saved),
        actionState = UiAsync.Success(Unit),
        notice = keptByServerNotice(kept),
    )
}

internal fun <T, D> MarketEditor<T, D>.saveFailed(error: AppError): MarketEditor<T, D> = copy(actionState = UiAsync.Error(error))

/** «El servidor conservó…» dicho para leerlo; `null` si no se conservó nada. */
internal fun keptByServerNotice(fields: List<String>): String? = when (fields.size) {
    0 -> null
    1 -> "El servidor conservó el valor anterior de ${fields[0]}: vaciar ese campo todavía no se guarda."
    else -> "El servidor conservó el valor anterior de ${fields.dropLast(1).joinToString(", ")} y ${fields.last()}: vaciar esos campos todavía no se guarda."
}
