package com.niki914.libterm.testing

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBackendTest {

    @Test
    fun `emit output keeps order stderr marker and clock timestamp`() = runTest {
        val clock = FakeClock(initialMillis = 100L)
        val backend = FakeBackend(identity = TerminalIdentity.USER, clock = clock)
        val outputs = mutableListOf<OutputChunk>()

        backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(3).toList(outputs)
        }
        runCurrent()

        assertTrue(backend.emitStdout("hello"))
        clock.advanceBy(5L)
        assertTrue(backend.emitStderr("oops"))
        clock.advanceBy(7L)
        assertTrue(
            backend.emitOutput(
                OutputChunk(
                    text = "done",
                    isStderr = false,
                    timestampMillis = clock.nowMillis(),
                ),
            ),
        )

        advanceUntilIdle()

        assertEquals(
            listOf(
                OutputChunk(text = "hello", isStderr = false, timestampMillis = 100L),
                OutputChunk(text = "oops", isStderr = true, timestampMillis = 105L),
                OutputChunk(text = "done", isStderr = false, timestampMillis = 112L),
            ),
            outputs,
        )
    }

    @Test
    fun `emit before collect is preserved for later subscriber`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)

        assertTrue(backend.emitStdout("queued-1"))
        assertTrue(backend.emitStderr("queued-2"))

        val collector = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(2).toList()
        }

        advanceUntilIdle()

        assertEquals(
            listOf(
                OutputChunk(text = "queued-1", isStderr = false, timestampMillis = 0L),
                OutputChunk(text = "queued-2", isStderr = true, timestampMillis = 0L),
            ),
            collector.await(),
        )
    }

    @Test
    fun `start and send record observable behavior for tests`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.ROOT)

        val result = backend.start()
        backend.send("pwd\n")
        backend.send("exit\n")

        assertEquals(BackendStartResult.Started, result)
        assertEquals(1, backend.startCallCount)
        assertEquals(listOf("pwd\n", "exit\n"), backend.writes)
    }

    @Test
    fun `fail on start returns configured failure until cleared`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.SHIZUKU)
        val failure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.SHIZUKU,
            message = "binder missing",
        )

        backend.failOnStart(failure)

        val failedResult = backend.start()
        backend.clearStartFailure()
        val recoveredResult = backend.start()

        assertEquals(2, backend.startCallCount)
        assertEquals(BackendStartResult.Failed(failure), failedResult)
        assertEquals(BackendStartResult.Started, recoveredResult)
    }

    @Test
    fun `close without hold completes immediately and send after close fails`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)

        backend.close()

        assertEquals(1, backend.closeCallCount)
        assertTrue(backend.isClosed)

        val error = assertFailsWith<IllegalStateException> {
            backend.send("whoami\n")
        }

        assertEquals("FakeBackend is closed", error.message)
    }

    @Test
    fun `move to records explicit state history for assertions`() {
        val backend = FakeBackend(identity = TerminalIdentity.ROOT)

        backend.moveTo(SessionState.Starting)
        backend.moveTo(SessionState.Running)
        backend.moveTo(SessionState.Closed)

        assertEquals(
            listOf(SessionState.Starting, SessionState.Running, SessionState.Closed),
            backend.stateHistory,
        )
    }

    @Test
    fun `close can be held and repeated close stays safe`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)
        backend.holdCloseUntilCompleted()

        val firstClose = backgroundScope.async { backend.close() }
        runCurrent()

        assertEquals(1, backend.closeCallCount)
        assertTrue(backend.isClosed)
        assertFalse(firstClose.isCompleted)

        backend.completeClose()
        firstClose.await()

        backend.close()

        assertEquals(2, backend.closeCallCount)
        assertTrue(backend.isClosed)
    }
}
