package com.oposicontrol.admin.core.database

import com.oposicontrol.admin.core.common.platform.AppPlatformContext

class DatabaseFactory(
    platformContext: AppPlatformContext,
) {
    private val driverFactory = DatabaseDriverFactory(platformContext)

    fun create(): AdminDatabase = AdminDatabase(driverFactory.createDriver())
}
