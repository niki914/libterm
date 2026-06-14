package com.niki914.libterm.runtime.internal

import com.niki914.libterm.OutputStream
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalSession
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

internal object TerminalCommandExecutor {
    suspend fun exec(
        session: TerminalSession,
        command: String,
        timeoutMillis: Long = Term.DEFAULT_EXEC_TIMEOUT_MILLIS,
    ): TermResult<CommandResult> {
        val execId = RuntimeIdGenerator().nextId()
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
            TermResult.Success(
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

    private const val EXEC_MARKER_PREFIX = "__LIBTERM_EXIT_"
    private const val EXEC_MARKER_SUFFIX = "__"
}
