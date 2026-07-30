package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.api.AdminApiRoutes

object BackendRoutes {
    const val HEALTH_LIVE = AdminApiRoutes.HEALTH_LIVE
    const val AUTH_ME = AdminApiRoutes.ME
    const val SPECIALTIES = AdminApiRoutes.SPECIALTIES
    const val EXAMS = AdminApiRoutes.EXAMS

    fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path
}
