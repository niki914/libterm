package com.niki914.libterm.testing

import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeBackend(
    override val identity: TerminalIdentity,
    private val clock: Clock = FakeClock(),
) : TerminalBackend {
    private val outputEvents = Channel<OutputChunk>(capacity = Channel.UNLIMITED)
    private val recordedWrites = mutableListOf<String>()
    private val exitResult = CompletableDeferred<TerminalFailure?>()

    private var startFailure: TerminalFailure? = null
    private var closeGate: CompletableDeferred<Unit>? = null
    private var shouldHoldClose: Boolean = false

    override val output: Flow<OutputChunk> = outputEvents.receiveAsFlow()

    val writes: List<String>
        get() = recordedWrites

    var closeCallCount: Int = 0
        private set

    var isClosed: Boolean = false
        private set

    var startCallCount: Int = 0
        private set

    override suspend fun start(): BackendStartResult {
        startCallCount += 1
        val failure = startFailure
        return if (failure == null) {
            BackendStartResult.Started
        } else {
            BackendStartResult.Failed(failure)
        }
    }

    override suspend fun send(input: String) {
        check(!isClosed) { "FakeBackend is closed" }
        recordedWrites += input
    }

    override suspend fun close() {
        closeCallCount += 1
        isClosed = true

        val gate = if (shouldHoldClose) {
            closeGate ?: CompletableDeferred<Unit>().also { closeGate = it }
        } else {
            null
        }

        gate?.await()
    }

    override suspend fun awaitExit(): TerminalFailure? = exitResult.await()

    fun emitOutput(chunk: OutputChunk): Boolean = outputEvents.trySend(chunk).isSuccess

    fun emitStdout(text: String): Boolean {
        return emitOutput(
            OutputChunk(
                text = text,
                isStderr = false,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }

    fun emitStderr(text: String): Boolean {
        return emitOutput(
            OutputChunk(
                text = text,
                isStderr = true,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }

    fun failOnStart(failure: TerminalFailure) {
        startFailure = failure
    }

    fun clearStartFailure() {
        startFailure = null
    }

    fun holdCloseUntilCompleted() {
        shouldHoldClose = true
        val gate = closeGate
        if (gate == null || gate.isCompleted) {
            closeGate = CompletableDeferred()
        }
    }

    fun completeClose() {
        shouldHoldClose = false
        closeGate?.complete(Unit)
    }

    fun finishNormally() {
        completeExit(null)
    }

    fun terminateWithFailure(failure: TerminalFailure) {
        completeExit(failure)
    }

    private fun completeExit(result: TerminalFailure?) {
        if (!exitResult.isCompleted) {
            exitResult.complete(result)
        }
    }
}
