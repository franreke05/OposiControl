package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import kotlin.random.Random
import kotlinx.coroutines.delay

class FakeBehaviorPolicy(
    private val appConfig: AppConfig,
    private val logger: Logger,
) {
    suspend fun <T> execute(
        operation: String,
        block: () -> T,
    ): AppResult<T> {
        delay(appConfig.featureFlags.fakeLatencyMs)
        if (Random.nextDouble() < appConfig.featureFlags.fakeErrorRate) {
            logger.log(
                LogEvent(
                    level = LogLevel.WARN,
                    tag = LogTag.REPOSITORY,
                    event = "fake.failure",
                    layer = LogLayer.REPOSITORY,
                    operation = operation,
                ),
            )
            return AppResult.Failure(AppError.Network("Fallo fake simulado en $operation"))
        }
        return AppResult.Success(block())
    }
}
