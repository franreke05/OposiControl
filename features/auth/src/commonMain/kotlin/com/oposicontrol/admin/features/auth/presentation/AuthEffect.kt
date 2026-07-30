package com.oposicontrol.admin.features.auth.presentation

/** One-shot authentication outcomes consumed by the route. */
sealed interface AuthEffect {
    data object Authenticated : AuthEffect
}
