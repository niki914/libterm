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
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibTermRuntimeTest {

    @Test
    fun `runtime reuses one wrapper for the same terminal session id`() = runTest {
        val runtime = LibTermRuntime(createManager(BlockingBackend(TerminalIdentity.User)))

        val opened = assertIs<OpenResult.Success<LibTermSession>>(
            runtime.open { identity = TerminalIdentity.User },
        )

        assertSame(opened.value, runtime.getSession(opened.value.id))
        assertSame(opened.value, runtime.listSessions().single())
        runtime.close(opened.value.id)
    }

    @Test
    fun `session close does not wait behind blocked input writes`() = runTest {
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val backend = BlockingBackend(
            identity = TerminalIdentity.User,
            onSend = {
                sendEntered.complete(Unit)
                releaseSend.await()
            },
        )
        val terminalSession = TerminalSession(
            id = "session-1",
            backend = backend,
            clock = FixedClock,
            scope = backgroundScope,
        )
        assertIs<com.niki914.libterm.SessionState.Running>(terminalSession.start())
        val wrapper = LibTermSession(
            session = terminalSession,
            closeSession = { true },
        )

        val writing = async { wrapper.write("blocked") }
        runCurrent()
        sendEntered.await()

        val closing = async { wrapper.close() }
        runCurrent()

        assertTrue(closing.isCompleted)
        releaseSend.complete(Unit)
        writing.await()
        closing.await()
        terminalSession.close()
    }

    @Test
    fun `open does not convert caller illegal argument to ssh open failure`() = runTest {
        val runtime = LibTermRuntime(createManager(BlockingBackend(TerminalIdentity.User)))

        val error = assertFailsWith<IllegalArgumentException> {
            runtime.open {
                throw IllegalArgumentException("caller bug")
            }
        }

        assertEquals("caller bug", error.message)
    }

    private fun TestScope.createManager(backend: TerminalBackend): TerminalManager {
        return TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = SequentialIdGenerator(),
            clock = FixedClock,
            scope = backgroundScope,
            backendFactory = { _, _ -> backend },
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

    private class BlockingBackend(
        override val identity: TerminalIdentity,
        private val onSend: suspend () -> Unit = {},
    ) : TerminalBackend {
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult {
            return BackendStartResult.Started
        }

        override suspend fun send(input: TerminalBytes): SendResult {
            onSend()
            return SendResult.Sent
        }

        override suspend fun close() {
            exitResult.complete(null)
        }

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()
    }
}
