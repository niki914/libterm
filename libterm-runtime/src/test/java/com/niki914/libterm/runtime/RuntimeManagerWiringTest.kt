package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.IdGenerator
import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeManagerWiringTest {

    @Test
    fun `backend start failure is returned without wrapping`() = runTest {
        val startupFailure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.ROOT,
            message = "boom",
        )
        val factory = RecordingBackendFactory(startupFailure)
        val manager = createManager(factory)

        val result = manager.open(TerminalIdentity.ROOT)

        val failure = assertIs<OpenResult.Failure>(result)
        assertSame(startupFailure, failure.failure)
        assertEquals(mapOf(TerminalIdentity.ROOT to 1), factory.createCounts)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `shizuku startup failure does not create user or root fallback backends`() = runTest {
        val startupFailure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.SHIZUKU,
            message = "shizuku failed",
        )
        val factory = RecordingBackendFactory(startupFailure)
        val manager = createManager(factory)

        val result = manager.open(TerminalIdentity.SHIZUKU)

        val failure = assertIs<OpenResult.Failure>(result)
        assertSame(startupFailure, failure.failure)
        assertEquals(1, factory.createCount(TerminalIdentity.SHIZUKU))
        assertEquals(0, factory.createCount(TerminalIdentity.USER))
        assertEquals(0, factory.createCount(TerminalIdentity.ROOT))
        assertTrue(manager.list().isEmpty())
    }

    private fun TestScope.createManager(factory: RecordingBackendFactory): TerminalManager {
        return TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = SequentialIdGenerator(),
            clock = FixedClock,
            scope = this,
            backendFactory = factory::create,
        )
    }

    private object AvailableProvider : PrivilegeProvider {
        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            return BackendAvailability.Available
        }
    }

    private class SequentialIdGenerator : IdGenerator {
        private var next = 0

        override fun nextId(): String {
            next += 1
            return "session-$next"
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class RecordingBackendFactory(
        private val startupFailure: TerminalFailure,
    ) {
        val createCounts = mutableMapOf<TerminalIdentity, Int>()

        fun createCount(identity: TerminalIdentity): Int {
            return createCounts[identity] ?: 0
        }

        fun create(identity: TerminalIdentity): TerminalBackend {
            createCounts[identity] = createCount(identity) + 1
            return FailingBackend(
                identity = identity,
                startupFailure = startupFailure,
            )
        }
    }

    private class FailingBackend(
        override val identity: TerminalIdentity,
        private val startupFailure: TerminalFailure,
    ) : TerminalBackend {
        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(): BackendStartResult {
            return BackendStartResult.Failed(startupFailure)
        }

        override suspend fun send(input: TerminalBytes): SendResult {
            return SendResult.Failed(
                TerminalFailure.AlreadyClosed(
                    identity = identity,
                    message = "not started",
                ),
            )
        }

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? = startupFailure
    }
}
