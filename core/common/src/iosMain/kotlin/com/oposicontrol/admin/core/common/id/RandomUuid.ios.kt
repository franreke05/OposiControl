package com.oposicontrol.admin.core.common.id

import platform.Foundation.NSUUID

actual fun randomUuid(): String = NSUUID().UUIDString()
