package com.niki914.libterm

import com.niki914.libterm.testing.FakeBackend
import com.niki914.libterm.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {

    @Test
    fun `start success moves session to running`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        val state = session.start()

        assertEquals(SessionState.Running, state)
        assertEquals(SessionState.Running, session.currentState)
        assertEquals("session-test", session.id)
        assertEquals(1, backend.startCallCount)

        backend.finishNormally()
    }

    @Test
    fun `start failure moves session to failed`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.ROOT)
        val failure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.ROOT,
            message = "boom",
        )
        backend.failOnStart(failure)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        val state = session.start()

        val failed = assertIs<SessionState.Failed>(state)
        assertEquals(failure, failed.failure)
        assertEquals(failed, session.currentState)
    }

    @Test
    fun `latest trims old chunks by chunk count and char count`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            bufferConfig = TerminalBufferConfig(
                maxChunkCount = 3,
                maxCharCount = 4,
            ),
            scheduler = testScheduler,
        )

        session.start()
        runCurrent()
        backend.emitStdout("a")
        backend.emitStdout("b")
        backend.emitStdout("c")
        backend.emitStdout("wxyz")
        advanceUntilIdle()

        assertEquals(
            listOf(
                OutputChunk(text = "wxyz", isStderr = false, timestampMillis = 0L),
            ),
            session.latest(limit = 10),
        )

        backend.finishNormally()
    }

    @Test
    fun `stderr chunks stay marked after buffering`() = runTest {
        val clock = FakeClock(initialMillis = 10L)
        val backend = FakeBackend(identity = TerminalIdentity.USER, clock = clock)
        val session = createSession(
            backend = backend,
            clock = clock,
            scheduler = testScheduler,
        )

        session.start()
        runCurrent()
        backend.emitStdout("out")
        clock.advanceBy(5L)
        backend.emitStderr("err")
        advanceUntilIdle()

        val latest = session.latest(limit = 2)
        assertEquals(2, latest.size)
        assertEquals(OutputChunk(text = "out", isStderr = false, timestampMillis = 10L), latest[0])
        assertEquals(OutputChunk(text = "err", isStderr = true, timestampMillis = 15L), latest[1])

        backend.finishNormally()
    }

    @Test
    fun `send after close returns already closed failure`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.SHIZUKU)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        session.start()
        val closeResult = backgroundScope.async { session.close() }
        runCurrent()
        backend.finishNormally()
        advanceUntilIdle()

        assertEquals(SessionState.Closed, closeResult.await())
        assertEquals(SessionState.Closed, session.currentState)
        assertEquals(1, backend.closeCallCount)

        val failure = assertIs<TerminalFailure.AlreadyClosed>(session.send("id\n"))
        assertEquals(TerminalIdentity.SHIZUKU, failure.identity)
        assertEquals("Session is not running", failure.message)
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun `close before start is a no-op and does not block later start`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Closed, session.close())
        assertEquals(SessionState.Closed, session.currentState)
        assertEquals(0, backend.closeCallCount)

        val started = session.start()
        assertEquals(SessionState.Running, started)
        assertEquals(1, backend.startCallCount)

        backend.finishNormally()
    }

    @Test
    fun `await exit failure moves session to failed`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.ROOT)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.ROOT,
            message = "pty died",
        )

        session.start()
        runCurrent()
        backend.terminateWithFailure(failure)
        advanceUntilIdle()

        val failed = assertIs<SessionState.Failed>(session.currentState)
        assertEquals(failure, failed.failure)
    }

    @Test
    fun `close requested while start is pending never transitions back to running`() = runTest {
        val backend = StartControlledBackend(identity = TerminalIdentity.USER)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        val startResult = backgroundScope.async { session.start() }
        runCurrent()
        assertEquals(SessionState.Starting, session.currentState)

        val closeResult = backgroundScope.async { session.close() }
        runCurrent()
        assertEquals(0, backend.closeCallCount)

        backend.completeStart(BackendStartResult.Started)
        runCurrent()
        assertEquals(SessionState.Starting, session.currentState)
        assertEquals(1, backend.closeCallCount)

        backend.completeExit(null)
        advanceUntilIdle()

        assertEquals(SessionState.Closed, startResult.await())
        assertEquals(SessionState.Closed, closeResult.await())
        assertEquals(SessionState.Closed, session.currentState)
    }

    @Test
    fun `close maps await exit exception to runtime terminated failure`() = runTest {
        val backend = ThrowingExitBackend(identity = TerminalIdentity.ROOT)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            scheduler = testScheduler,
        )

        assertEquals(SessionState.Running, session.start())

        val failed = assertIs<SessionState.Failed>(session.close())
        val failure = assertIs<TerminalFailure.RuntimeTerminated>(failed.failure)
        assertEquals(TerminalIdentity.ROOT, failure.identity)
        assertEquals("await exit failed", failure.message)
        assertEquals(SessionState.Failed(failure), session.currentState)
    }

    @Test
    fun `concurrent output keeps deterministic buffer invariants`() = runTest {
        val backend = FakeBackend(identity = TerminalIdentity.USER)
        val session = createSession(
            backend = backend,
            clock = FakeClock(),
            bufferConfig = TerminalBufferConfig(
                maxChunkCount = 5,
                maxCharCount = 5,
            ),
            scheduler = testScheduler,
        )
        val emitted = ('a'..'t').map(Char::toString).toSet()

        session.start()
        runCurrent()
        ('a'..'t').forEach { value ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                backend.emitStdout(value.toString())
            }
        }
        advanceUntilIdle()

        val latest = session.latest(limit = 100)
        assertEquals(5, latest.size)
        assertEquals(5, latest.sumOf { it.text.length })
        assertTrue(latest.all { !it.isStderr })
        assertTrue(latest.all { it.text in emitted })
        assertEquals(latest.map { it.text }.toSet().size, latest.size)

        backend.finishNormally()
    }

    private fun createSession(
        backend: TerminalBackend,
        clock: FakeClock,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): TerminalSession {
        return TerminalSession(
            id = "session-test",
            backend = backend,
            clock = clock,
            bufferConfig = bufferConfig,
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        )
    }

    private class StartControlledBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        private val startResult = CompletableDeferred<BackendStartResult>()
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        override val output: Flow<OutputChunk> = emptyFlow()

        var closeCallCount: Int = 0
            private set

        override suspend fun start(): BackendStartResult = startResult.await()

        override suspend fun send(input: String) = Unit

        override suspend fun close() {
            closeCallCount += 1
        }

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun completeStart(result: BackendStartResult) {
            if (!startResult.isCompleted) {
                startResult.complete(result)
            }
        }

        fun completeExit(result: TerminalFailure?) {
            if (!exitResult.isCompleted) {
                exitResult.complete(result)
            }
        }
    }

    private class ThrowingExitBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(): BackendStartResult = BackendStartResult.Started

        override suspend fun send(input: String) = Unit

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? {
            throw IllegalStateException("await exit failed")
        }
    }
}
