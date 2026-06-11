package com.niki914.libterm.runtime

import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalSession
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class DefaultTerm(
    private val manager: TerminalManager,
    private val identity: TerminalIdentity,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
) : Term {
    private val lifecycleMutex = Mutex()
    private val inputMutex = Mutex()
    private val stateEvents = MutableStateFlow<SessionState>(SessionState.Closed)
    private val outputEvents = MutableSharedFlow<OutputChunk>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val execIdGenerator = RuntimeIdGenerator()

    private var session: TerminalSession? = null
    private var stateJob: Job? = null
    private var outputJob: Job? = null

    override val state: StateFlow<SessionState> = stateEvents.asStateFlow()
    override val stream: Flow<OutputChunk> = outputEvents.asSharedFlow()

    override suspend fun open(): TermResult<Unit> {
        return when (val ensured = ensureSession()) {
            is TermResult.Success -> TermResult.Success(Unit)
            is TermResult.Failure -> TermResult.Failure(ensured.failure)
        }
    }

    override suspend fun exec(
        command: String,
        timeoutMillis: Long,
    ): TermResult<CommandResult> {
        return inputMutex.withLock {
            val session = when (val ensured = ensureSession()) {
                is TermResult.Success -> ensured.value
                is TermResult.Failure -> return TermResult.Failure(ensured.failure)
            }

            val execId = execIdGenerator.nextId()
            val prefixText = "\n$EXEC_MARKER_PREFIX$execId:"
            val suffixText = "$EXEC_MARKER_SUFFIX\n"
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val completed = CompletableDeferred<CommandResult>()

            return coroutineScope {
                val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.output.collect { chunk ->
                        when (chunk.stream) {
                            OutputStream.STDOUT -> {
                                stdout.write(chunk.bytes.toByteArray())
                                parseExecResult(
                                    command = command,
                                    stdoutBytes = stdout.toByteArray(),
                                    stderrBytes = stderr.toByteArray(),
                                    prefixText = prefixText,
                                    suffixText = suffixText,
                                )?.let { result ->
                                    if (!completed.isCompleted) {
                                        completed.complete(result)
                                    }
                                }
                            }

                            OutputStream.STDERR -> stderr.write(chunk.bytes.toByteArray())
                        }
                    }
                }

                when (val sendResult = session.send(buildExecPayload(command, execId).encodeToByteArray())) {
                    SendResult.Sent -> Unit
                    is SendResult.Failed -> {
                        collectorJob.cancel()
                        return@coroutineScope TermResult.Failure(sendResult.failure)
                    }
                }

                val result = if (timeoutMillis > 0) {
                    withTimeoutOrNull(timeoutMillis) {
                        completed.await()
                    }
                } else {
                    completed.await()
                }

                collectorJob.cancel()
                return@coroutineScope TermResult.Success(
                    result ?: CommandResult(
                        command = command,
                        stdout = TerminalBytes.of(stdout.toByteArray()),
                        stderr = TerminalBytes.of(stderr.toByteArray()),
                        exitCode = null,
                        timedOut = true,
                    ),
                )
            }
        }
    }

    override suspend fun write(text: String): TermResult<Unit> {
        return write(text.encodeToByteArray())
    }

    override suspend fun write(bytes: ByteArray): TermResult<Unit> {
        return inputMutex.withLock {
            val session = when (val ensured = ensureSession()) {
                is TermResult.Success -> ensured.value
                is TermResult.Failure -> return TermResult.Failure(ensured.failure)
            }

            return when (val result = session.send(bytes)) {
                SendResult.Sent -> TermResult.Success(Unit)
                is SendResult.Failed -> TermResult.Failure(result.failure)
            }
        }
    }

    override suspend fun close(): TermResult<Unit> {
        val existing = lifecycleMutex.withLock { session }
        if (existing != null) {
            manager.close(existing.id)
        }
        clearSession(existing)
        if (ownsScope) {
            scope.cancel()
        }
        stateEvents.value = SessionState.Closed
        return TermResult.Success(Unit)
    }

    private suspend fun ensureSession(): TermResult<TerminalSession> {
        val existing = lifecycleMutex.withLock { session }
        if (existing != null && existing.currentState == SessionState.Running) {
            return TermResult.Success(existing)
        }
        if (existing != null) {
            manager.close(existing.id)
            clearSession(existing)
        }

        return when (val opened = manager.open(identity)) {
            is OpenResult.Success -> {
                bindSession(opened.value)
                TermResult.Success(opened.value)
            }

            is OpenResult.Failure -> {
                stateEvents.value = SessionState.Failed(opened.failure)
                TermResult.Failure(opened.failure)
            }
        }
    }

    private suspend fun bindSession(newSession: TerminalSession) {
        lifecycleMutex.withLock {
            session = newSession
            stateEvents.value = newSession.currentState
            stateJob?.cancel()
            outputJob?.cancel()
            stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                newSession.state.collect { state ->
                    stateEvents.value = state
                }
            }
            outputJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                newSession.output.collect { chunk ->
                    outputEvents.emit(chunk)
                }
            }
        }
    }

    private suspend fun clearSession(existing: TerminalSession?) {
        lifecycleMutex.withLock {
            if (existing == null || session == existing) {
                session = null
            }
            stateJob?.cancel()
            stateJob = null
            outputJob?.cancel()
            outputJob = null
        }
    }

    private fun buildExecPayload(command: String, execId: String): String {
        val normalizedCommand = if (command.endsWith('\n')) command else "$command\n"
        return buildString {
            append(normalizedCommand)
            append("__libterm_exec_status=$?; printf '\\n")
            append(EXEC_MARKER_PREFIX)
            append(execId)
            append(":%s")
            append(EXEC_MARKER_SUFFIX)
            append("\\n' \"\$__libterm_exec_status\"")
            append('\n')
        }
    }

    private fun parseExecResult(
        command: String,
        stdoutBytes: ByteArray,
        stderrBytes: ByteArray,
        prefixText: String,
        suffixText: String,
    ): CommandResult? {
        val prefixBytes = prefixText.encodeToByteArray()
        val suffixBytes = suffixText.encodeToByteArray()
        val markerStart = stdoutBytes.indexOf(prefixBytes)
        if (markerStart < 0) {
            return null
        }
        val markerEnd = stdoutBytes.indexOf(suffixBytes, markerStart + prefixBytes.size)
        if (markerEnd < 0) {
            return null
        }
        val exitCodeBytes = stdoutBytes.copyOfRange(markerStart + prefixBytes.size, markerEnd)
        val exitCode = exitCodeBytes.parseAsciiInt() ?: return null
        return CommandResult(
            command = command,
            stdout = TerminalBytes.of(stdoutBytes.copyOfRange(0, markerStart)),
            stderr = TerminalBytes.of(stderrBytes),
            exitCode = exitCode,
            timedOut = false,
        )
    }

    private fun ByteArray.indexOf(target: ByteArray, startIndex: Int = 0): Int {
        if (target.isEmpty()) {
            return startIndex.coerceAtMost(size)
        }
        val lastStart = size - target.size
        for (index in startIndex..lastStart) {
            var matched = true
            for (targetIndex in target.indices) {
                if (this[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return index
            }
        }
        return -1
    }

    private fun ByteArray.parseAsciiInt(): Int? {
        if (isEmpty()) {
            return null
        }
        var value = 0
        for (byte in this) {
            if (byte < '0'.code.toByte() || byte > '9'.code.toByte()) {
                return null
            }
            value = (value * 10) + (byte - '0'.code.toByte())
        }
        return value
    }

    private companion object {
        private const val EXEC_MARKER_PREFIX = "__LIBTERM_EXIT_"
        private const val EXEC_MARKER_SUFFIX = "__"
    }
}
