package com.niki914.libterm.shellsmoke

import android.content.Context
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OpenResult
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.LibTermRuntime
import com.niki914.libterm.runtime.LibTermSession
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.TerminalOpenSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RuntimeTermAdapter(
    context: Context,
    scope: CoroutineScope,
    private val identity: TerminalIdentity,
    private val configureOpen: TerminalOpenSpec.() -> Unit = {},
) : Term {
    private val runtime: LibTermRuntime = LibTerm.runtime(
        context = context.applicationContext,
        scope = scope,
    )
    private val lifecycleMutex = Mutex()
    private val stateEvents = MutableStateFlow<SessionState>(SessionState.Closed)
    private val outputEvents = MutableSharedFlow<OutputChunk>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var session: LibTermSession? = null
    private var stateJob: Job? = null
    private var outputJob: Job? = null

    override val state: StateFlow<SessionState> = stateEvents.asStateFlow()
    override val stream: Flow<OutputChunk> = outputEvents.asSharedFlow()

    override suspend fun open(): TermResult<Unit> {
        return when (val result = ensureSession()) {
            is TermResult.Success -> TermResult.Success(Unit)
            is TermResult.Failure -> TermResult.Failure(result.failure)
        }
    }

    override suspend fun exec(
        command: String,
        timeoutMillis: Long,
    ): TermResult<CommandResult> {
        val activeSession = when (val result = ensureSession()) {
            is TermResult.Success -> result.value
            is TermResult.Failure -> return TermResult.Failure(result.failure)
        }
        return activeSession.exec(command = command, timeoutMillis = timeoutMillis)
    }

    override suspend fun write(text: String): TermResult<Unit> {
        return write(text.encodeToByteArray())
    }

    override suspend fun write(bytes: ByteArray): TermResult<Unit> {
        val activeSession = when (val result = ensureSession()) {
            is TermResult.Success -> result.value
            is TermResult.Failure -> return TermResult.Failure(result.failure)
        }
        return activeSession.write(bytes)
    }

    override suspend fun close(): TermResult<Unit> {
        val existing = lifecycleMutex.withLock {
            val current = session
            session = null
            stateJob?.cancel()
            stateJob = null
            outputJob?.cancel()
            outputJob = null
            current
        }
        val result = existing?.close()
        stateEvents.value = SessionState.Closed
        return result ?: TermResult.Success(Unit)
    }

    private suspend fun ensureSession(): TermResult<LibTermSession> {
        return lifecycleMutex.withLock {
            val existing = session
            if (existing != null && existing.state.value == SessionState.Running) {
                return@withLock TermResult.Success(existing)
            }

            when (val opened = runtime.open {
                identity = this@RuntimeTermAdapter.identity
                configureOpen()
            }) {
                is OpenResult.Success -> {
                    bindSessionLocked(opened.value)
                    TermResult.Success(opened.value)
                }

                is OpenResult.Failure -> {
                    stateEvents.value = SessionState.Failed(opened.failure)
                    TermResult.Failure(opened.failure)
                }
            }
        }
    }

    private fun bindSessionLocked(newSession: LibTermSession) {
        session = newSession
        stateEvents.value = newSession.state.value
        stateJob?.cancel()
        outputJob?.cancel()
        stateJob = streamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            newSession.state.collect { stateEvents.value = it }
        }
        outputJob = streamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            newSession.stream.collect { outputEvents.emit(it) }
        }
    }

    private val streamScope: CoroutineScope = scope
}

internal class LazyTermAdapter(
    scope: CoroutineScope,
    private val createTerm: () -> Term,
) : Term {
    private val lifecycleMutex = Mutex()
    private val stateEvents = MutableStateFlow<SessionState>(SessionState.Closed)
    private val outputEvents = MutableSharedFlow<OutputChunk>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var term: Term? = null
    private var stateJob: Job? = null
    private var outputJob: Job? = null

    override val state: StateFlow<SessionState> = stateEvents.asStateFlow()
    override val stream: Flow<OutputChunk> = outputEvents.asSharedFlow()

    override suspend fun open(): TermResult<Unit> {
        val activeTerm = ensureTerm()
        return activeTerm.open().also { result ->
            if (result is TermResult.Failure) {
                stateEvents.value = SessionState.Failed(result.failure)
            }
        }
    }

    override suspend fun exec(
        command: String,
        timeoutMillis: Long,
    ): TermResult<CommandResult> {
        return ensureTerm().exec(command, timeoutMillis)
    }

    override suspend fun write(text: String): TermResult<Unit> {
        return ensureTerm().write(text)
    }

    override suspend fun write(bytes: ByteArray): TermResult<Unit> {
        return ensureTerm().write(bytes)
    }

    override suspend fun close(): TermResult<Unit> {
        val existing = lifecycleMutex.withLock {
            val current = term
            clearTermLocked()
            current
        }
        val result = existing?.close()
        stateEvents.value = SessionState.Closed
        return result ?: TermResult.Success(Unit)
    }

    private suspend fun ensureTerm(): Term {
        return lifecycleMutex.withLock {
            val existing = term
            if (existing != null && existing.state.value == SessionState.Running) {
                return@withLock existing
            }
            existing?.close()
            clearTermLocked()

            val newTerm = createTerm()
            term = newTerm
            stateEvents.value = newTerm.state.value
            stateJob = streamScope.launch(start = CoroutineStart.UNDISPATCHED) {
                newTerm.state.collect { stateEvents.value = it }
            }
            outputJob = streamScope.launch(start = CoroutineStart.UNDISPATCHED) {
                newTerm.stream.collect { outputEvents.emit(it) }
            }
            newTerm
        }
    }

    private fun clearTermLocked() {
        term = null
        stateJob?.cancel()
        stateJob = null
        outputJob?.cancel()
        outputJob = null
    }

    private val streamScope: CoroutineScope = scope
}
