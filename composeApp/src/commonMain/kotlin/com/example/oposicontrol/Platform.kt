package com.example.oposicontrol

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform