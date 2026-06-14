package com.niki914.libterm.runtime

import com.niki914.libterm.OpenResult
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import com.niki914.libterm.TerminalSession

class LibTermRuntime internal constructor(
    private val manager: TerminalManager,
    private val config: LibTermRuntimeConfig = LibTermRuntimeConfig(),
) {
    private val sessionLock = Any()
    private val sessionsById = LinkedHashMap<String, LibTermSession>()

    suspend fun open(configure: TerminalOpenSpec.() -> Unit): OpenResult<LibTermSession> {
        val spec = TerminalOpenSpec().apply(configure)
        if (!spec.hasIdentity()) {
            return OpenResult.Failure(
                TerminalFailure.InvalidOpenOptions(
                    identity = null,
                    message = "identity is required",
                ),
            )
        }

        return when (val result = manager.open(
            identity = spec.identity,
            authorizationMode = spec.authorizationMode ?: config.defaultAuthorizationMode,
            openOptions = TerminalOpenOptions(
                cwd = spec.cwd ?: config.defaultCwd,
            ),
        )) {
            is OpenResult.Success -> OpenResult.Success(wrapSession(result.value))
            is OpenResult.Failure -> result
        }
    }

    fun getSession(id: String): LibTermSession? {
        return manager.get(id)?.let(::wrapSession)
    }

    fun listSessions(): List<LibTermSession> {
        return manager.list().map(::wrapSession)
    }

    suspend fun close(sessionId: String): Boolean {
        val closed = manager.close(sessionId)
        if (closed) {
            synchronized(sessionLock) {
                sessionsById.remove(sessionId)
            }
        }
        return closed
    }

    suspend fun closeAll(): Int {
        var closedCount = 0
        for (session in manager.list()) {
            if (manager.close(session.id)) {
                closedCount += 1
            }
        }
        synchronized(sessionLock) {
            sessionsById.clear()
        }
        return closedCount
    }

    private fun wrapSession(session: TerminalSession): LibTermSession {
        return synchronized(sessionLock) {
            sessionsById.getOrPut(session.id) {
                LibTermSession(
                    session = session,
                    closeSession = ::close,
                )
            }
        }
    }
}
