package com.niki914.libterm.backend.libsu

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LibsuTerminalBackendTest {

    @Test
    fun `user start opens shared pipeline with user identity`() = runTest {
        val adapterFactory = FakeLibsuShellAdapterFactory()
        val session = FakeLibsuShellSession()
        adapterFactory.nextSession = session
        val backend = createBackend(
            identity = TerminalIdentity.USER,
            adapterFactory = adapterFactory,
        )

        val result = backend.start()
        backend.close()

        assertEquals(BackendStartResult.Started, result)
        assertEquals(listOf(TerminalIdentity.USER), adapterFactory.openedIdentities)
        assertEquals(1, session.closeCallCount)
    }

    @Test
    fun `root startup failure maps to explicit startup failed`() = runTest {
        val startupError = IllegalStateException("su shell failed")
        val adapterFactory = FakeLibsuShellAdapterFactory(startupError = startupError)
        val backend = createBackend(
            identity = TerminalIdentity.ROOT,
            adapterFactory = adapterFactory,
        )

        val result = backend.start()

        val failed = assertIs<BackendStartResult.Failed>(result)
        val failure = assertIs<TerminalFailure.StartupFailed>(failed.failure)
        assertEquals(TerminalIdentity.ROOT, failure.identity)
        assertEquals("su shell failed", failure.message)
        assertEquals(startupError, failure.cause)
        assertEquals(listOf(TerminalIdentity.ROOT), adapterFactory.openedIdentities)
    }

    @Test
    fun `stdout and stderr map to output chunks`() = runTest {
        val clock = FakeClock(initialMillis = 100L)
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.USER,
            clock = clock,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )
        val collecting = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            backend.output.take(2).toList()
        }

        runCurrent()
        assertEquals(BackendStartResult.Started, backend.start())
        session.emitStdout("hello")
        clock.advanceBy(5L)
        session.emitStderr("oops")
        advanceUntilIdle()
        backend.close()

        assertEquals(
            listOf(
                OutputChunk(text = "hello", isStderr = false, timestampMillis = 100L),
                OutputChunk(text = "oops", isStderr = true, timestampMillis = 105L),
            ),
            collecting.await(),
        )
    }

    @Test
    fun `send forwards raw input without appending newline`() = runTest {
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.USER,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        backend.send("id")
        backend.send("pwd\n")
        backend.close()

        assertEquals(listOf("id", "pwd\n"), session.writes)
    }

    @Test
    fun `close is idempotent and releases session once`() = runTest {
        val session = FakeLibsuShellSession()
        val backend = createBackend(
            identity = TerminalIdentity.ROOT,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        backend.close()
        backend.close()

        assertEquals(1, session.closeCallCount)
    }

    @Test
    fun `await exit returns runtime failure from session`() = runTest {
        val failure = TerminalFailure.RuntimeTerminated(
            identity = TerminalIdentity.ROOT,
            message = "shell died",
        )
        val session = FakeLibsuShellSession(exitFailure = failure)
        val backend = createBackend(
            identity = TerminalIdentity.ROOT,
            adapterFactory = FakeLibsuShellAdapterFactory(nextSession = session),
        )

        assertEquals(BackendStartResult.Started, backend.start())
        val result = backend.awaitExit()
        backend.close()

        assertEquals(failure, result)
    }

    private fun TestScope.createBackend(
        identity: TerminalIdentity,
        clock: Clock = FakeClock(),
        adapterFactory: LibsuShellAdapterFactory,
    ): LibsuTerminalBackend {
        return LibsuTerminalBackend(
            identity = identity,
            clock = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            adapterFactory = adapterFactory,
        )
    }

    private class FakeClock(initialMillis: Long = 0L) : Clock {
        private var currentMillis: Long = initialMillis

        override fun nowMillis(): Long = currentMillis

        fun advanceBy(deltaMillis: Long) {
            currentMillis += deltaMillis
        }
    }

    private class FakeLibsuShellAdapterFactory(
        var nextSession: LibsuShellSession = FakeLibsuShellSession(),
        private val startupError: Throwable? = null,
    ) : LibsuShellAdapterFactory {
        val openedIdentities = mutableListOf<TerminalIdentity>()

        override suspend fun open(identity: TerminalIdentity): LibsuShellSession {
            openedIdentities += identity
            startupError?.let { throw it }
            return nextSession
        }
    }

    private class FakeLibsuShellSession(
        private val exitFailure: TerminalFailure? = null,
    ) : LibsuShellSession {
        private val outputEvents = MutableSharedFlow<LibsuOutputEvent>(
            replay = 2,
            extraBufferCapacity = 16,
        )

        override val output = outputEvents

        val writes = mutableListOf<String>()
        var closeCallCount: Int = 0
            private set

        override suspend fun write(input: String) {
            writes += input
        }

        override suspend fun close() {
            closeCallCount += 1
        }

        override suspend fun awaitExit(): TerminalFailure? = exitFailure

        suspend fun emitStdout(text: String) {
            outputEvents.emit(LibsuOutputEvent.Stdout(text))
        }

        suspend fun emitStderr(text: String) {
            outputEvents.emit(LibsuOutputEvent.Stderr(text))
        }
    }
}
