package com.oposicontrol.admin.core.common.logging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class OperationContextElement(
    val operationContext: OperationContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationContextElement>
}

suspend fun currentOperationContextOrNull(): OperationContext? =
    currentCoroutineContext()[OperationContextElement]?.operationContext

suspend fun <T> withOperationContext(
    context: OperationContext,
    block: suspend () -> T,
): T = withContext(OperationContextElement(context)) {
    block()
}
