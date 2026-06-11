package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.IdGenerator
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalBytes
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultTermTest {

    @Test
    fun `exec returns exit code and strips internal marker`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.USER)
        val lastWrite = AtomicReference<String>("")
        backend.onSend = { input ->
            val payload = input.toByteArray().decodeToString()
            lastWrite.set(payload)
            val execId = Regex("__LIBTERM_EXIT_([^:]+):%s__")
                .find(payload)
                ?.groupValues
                ?.get(1)
                ?: error("Missing exec marker")
            backend.emitStdout("hello\n")
            backend.emitStdout("\n__LIBTERM_EXIT_${execId}:7__\n")
        }
        val term = createTerm(backend)

        val result = term.exec("echo hello")

        val success = assertIs<TermResult.Success<CommandResult>>(result)
        assertEquals("echo hello", success.value.command)
        assertEquals("hello\n", success.value.stdout.toByteArray().decodeToString())
        assertEquals("", success.value.stderr.toByteArray().decodeToString())
        assertEquals(7, success.value.exitCode)
        assertFalse(success.value.timedOut)
        assertTrue(lastWrite.get().contains("__libterm_exec_status=$?"))
        term.close()
    }

    @Test
    fun `exec timeout returns partial streamed output`() = runTest {
        val backend = RecordingBackend(TerminalIdentity.USER)
        backend.onSend = {
            backend.emitStdout("partial-output")
        }
        val term = createTerm(backend)

        val deferred = async {
            term.exec(
                command = "cat",
                timeoutMillis = 50L,
            )
        }
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        val result = deferred.await()

        val success = assertIs<TermResult.Success<CommandResult>>(result)
        assertTrue(success.value.timedOut)
        assertNull(success.value.exitCode)
        assertEquals("partial-output", success.value.stdout.toByteArray().decodeToString())
        assertEquals("", success.value.stderr.toByteArray().decodeToString())
        term.close()
    }

    private fun kotlinx.coroutines.test.TestScope.createTerm(backend: RecordingBackend): Term {
        val manager = TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = FixedIdGenerator,
            clock = FixedClock,
            scope = backgroundScope,
            backendFactory = { backend },
        )
        return DefaultTerm(
            manager = manager,
            identity = backend.identity,
            scope = backgroundScope,
            ownsScope = false,
        )
    }

    private object AvailableProvider : PrivilegeProvider {
        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            return BackendAvailability.Available
        }
    }

    private object FixedIdGenerator : IdGenerator {
        private var next = 0

        override fun nextId(): String {
            next += 1
            return "session-$next"
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class RecordingBackend(
        override val identity: TerminalIdentity,
    ) : TerminalBackend {
        private val outputEvents = MutableSharedFlow<OutputChunk>(
            replay = 0,
            extraBufferCapacity = 16,
        )
        private val exitResult = CompletableDeferred<TerminalFailure?>()

        var onSend: (suspend (TerminalBytes) -> Unit)? = null

        override val output: Flow<OutputChunk> = outputEvents

        override suspend fun start(): BackendStartResult = BackendStartResult.Started

        override suspend fun send(input: TerminalBytes): SendResult {
            onSend?.invoke(input)
            return SendResult.Sent
        }

        override suspend fun close() {
            if (!exitResult.isCompleted) {
                exitResult.complete(null)
            }
        }

        override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

        fun emitStdout(text: String) {
            outputEvents.tryEmit(
                OutputChunk(
                    stream = OutputStream.STDOUT,
                    bytes = TerminalBytes.of(text.encodeToByteArray()),
                    timestampMillis = 100L,
                ),
            )
        }
    }
}
