package com.niki914.libterm.runtime

import android.content.Context
import android.content.ContextWrapper
import com.niki914.libterm.Clock
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeBackendFactoryTest {

    @Test
    fun `user create returns user backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(TerminalIdentity.USER)

        assertEquals(TerminalIdentity.USER, backend.identity)
    }

    @Test
    fun `root create returns root backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(TerminalIdentity.ROOT)

        assertEquals(TerminalIdentity.ROOT, backend.identity)
    }

    @Test
    fun `shizuku create returns shizuku backend without starting it`() {
        val factory = createFactory()

        val backend = factory.create(TerminalIdentity.SHIZUKU)

        assertEquals(TerminalIdentity.SHIZUKU, backend.identity)
    }

    private fun createFactory(): RuntimeBackendFactory {
        return RuntimeBackendFactory(
            context = RuntimeTestContext(),
            clock = FixedClock,
            scope = TestScope(),
        )
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class RuntimeTestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
