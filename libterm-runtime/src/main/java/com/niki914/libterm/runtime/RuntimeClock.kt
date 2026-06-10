package com.niki914.libterm.runtime

import com.niki914.libterm.Clock

internal object RuntimeClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
