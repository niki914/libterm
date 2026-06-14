package com.niki914.libterm.runtime

import com.niki914.libterm.OutputChunk
import com.niki914.libterm.SendResult
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalSession
import com.niki914.libterm.runtime.internal.TerminalCommandExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibTermSession internal constructor(
    private val session: TerminalSession,
    private val closeSession: suspend (String) -> Boolean,
) {
    private val inputMutex = Mutex()

    val id: String = session.id
    val identity: TerminalIdentity = session.identity
    val state: StateFlow<SessionState> = session.state
    val stream: Flow<OutputChunk> = session.output

    fun latest(limit: Int): List<OutputChunk> = session.latest(limit)

    suspend fun exec(
        command: String,
        timeoutMillis: Long = Term.DEFAULT_EXEC_TIMEOUT_MILLIS,
    ): TermResult<CommandResult> {
        return inputMutex.withLock {
            TerminalCommandExecutor.exec(
                session = session,
                command = command,
                timeoutMillis = timeoutMillis,
            )
        }
    }

    suspend fun write(text: String): TermResult<Unit> {
        return write(text.encodeToByteArray())
    }

    suspend fun write(bytes: ByteArray): TermResult<Unit> {
        return inputMutex.withLock {
            when (val result = session.send(bytes)) {
                SendResult.Sent -> TermResult.Success(Unit)
                is SendResult.Failed -> TermResult.Failure(result.failure)
            }
        }
    }

    suspend fun close(): TermResult<Unit> {
        return if (closeSession(id)) {
            TermResult.Success(Unit)
        } else {
            TermResult.Failure(
                TerminalFailure.AlreadyClosed(
                    identity = identity,
                    message = "Session is already closed",
                ),
            )
        }
    }
}
