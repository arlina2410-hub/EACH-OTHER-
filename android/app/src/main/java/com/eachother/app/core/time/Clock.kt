package com.eachother.app.core.time

interface Clock {
    fun nowMs(): Long
}

class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
