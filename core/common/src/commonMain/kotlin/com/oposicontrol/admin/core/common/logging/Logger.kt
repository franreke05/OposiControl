package com.oposicontrol.admin.core.common.logging

interface Logger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

class ConsoleLogger : Logger {
    override fun debug(message: String) = println("DEBUG | $message")
    override fun info(message: String) = println("INFO  | $message")
    override fun warn(message: String) = println("WARN  | $message")

    override fun error(message: String, throwable: Throwable?) {
        println("ERROR | $message")
        throwable?.printStackTrace()
    }
}

data object NoOpLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, throwable: Throwable?) = Unit
}
