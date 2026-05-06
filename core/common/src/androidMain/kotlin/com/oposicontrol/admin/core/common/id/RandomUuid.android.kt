package com.oposicontrol.admin.core.common.id

import java.util.UUID

actual fun randomUuid(): String = UUID.randomUUID().toString()
