package com.oposicontrol.admin.core.common.config

enum class Environment {
    DEV,
    STAGING,
    PROD;

    companion object {
        fun from(rawValue: String?): Environment {
            return when (rawValue?.trim()?.lowercase()) {
                null, "" -> DEV
                "dev", "development", "local" -> DEV
                "prod", "production" -> PROD
                "staging", "stage" -> STAGING
                else -> throw IllegalArgumentException("APP_ENV no valido: $rawValue")
            }
        }
    }
}
