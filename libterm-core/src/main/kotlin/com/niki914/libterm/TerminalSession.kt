package com.niki914.libterm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TerminalSession(
    private val backend: TerminalBackend,
    private val clock: Clock,
    private val bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
    private val scope: CoroutineScope,
) {
    val identity: TerminalIdentity = backend.identity

    private val lifecycleLock = Any()
    private val bufferLock = Any()
    private val buffer = mutableListOf<OutputChunk>()

    private val _state = MutableStateFlow<SessionState>(SessionState.Closed)

    private var totalBufferedChars: Int = 0
    private var startDeferred: CompletableDeferred<SessionState>? = null
    private var closeDeferred: CompletableDeferred<SessionState>? = null
    private var outputCollectionJob: Job? = null
    private var exitAwaitJob: Job? = null
    private var startAttempted: Boolean = false
    private var closeRequested: Boolean = false
    private var closeInvoked: Boolean = false

    val state: StateFlow<SessionState> = _state.asStateFlow()

    val currentState: SessionState
        get() = state.value

    suspend fun start(): SessionState {
        val pendingStart = synchronized(lifecycleLock) {
            startDeferred?.let { return@synchronized it }

            if (startAttempted) {
                return@synchronized completedDeferred(_state.value)
            }

            startAttempted = true
            _state.value = SessionState.Starting
            CompletableDeferred<SessionState>().also { startDeferred = it }
        }

        if (pendingStart.isCompleted) {
            return pendingStart.await()
        }

        val startResult = try {
            backend.start()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            BackendStartResult.Failed(
                failure = TerminalFailure.StartupFailed(
                    identity = identity,
                    message = error.message,
                    cause = error,
                ),
            )
        }

        var closeToAwait: CompletableDeferred<SessionState>? = null
        var shouldInvokeCloseAfterStart = false
        var shouldLaunchBackgroundJobs = false
        val resolvedState: SessionState? = synchronized(lifecycleLock) {
            if (closeRequested) {
                return@synchronized when (startResult) {
                    BackendStartResult.Started -> {
                        closeToAwait = closeDeferred
                        shouldLaunchBackgroundJobs = true
                        shouldInvokeCloseAfterStart = true
                        null
                    }

                    is BackendStartResult.Failed -> {
                        val failedState = SessionState.Failed(startResult.failure)
                        _state.value = failedState
                        completeIfNeeded(startDeferred, failedState)
                        completeIfNeeded(closeDeferred, failedState)
                        closeToAwait = null
                        failedState
                    }
                }
            }

            closeToAwait = null
            val nextState = when (val existingState = _state.value) {
                is SessionState.Failed -> existingState
                SessionState.Closed -> existingState
                SessionState.Running -> existingState
                SessionState.Starting -> when (startResult) {
                    BackendStartResult.Started -> {
                        shouldLaunchBackgroundJobs = true
                        SessionState.Running
                    }

                    is BackendStartResult.Failed -> SessionState.Failed(startResult.failure)
                }
            }

            _state.value = nextState
            completeIfNeeded(startDeferred, nextState)
            if (nextState != SessionState.Running) {
                completeIfNeeded(closeDeferred, nextState)
            }
            nextState
        }

        if (shouldLaunchBackgroundJobs) {
            launchBackgroundJobs()
        }
        if (shouldInvokeCloseAfterStart) {
            invokeCloseIfNeeded()
        }
        closeToAwait?.let { return it.await() }
        return requireNotNull(resolvedState)
    }

    fun latest(limit: Int): List<OutputChunk> {
        if (limit <= 0) {
            return emptyList()
        }

        return synchronized(bufferLock) {
            buffer.takeLast(limit).toList()
        }
    }

    suspend fun send(input: String): TerminalFailure? {
        if (currentState != SessionState.Running) {
            return TerminalFailure.AlreadyClosed(
                identity = identity,
                message = "Session is not running",
            )
        }

        backend.send(input)
        return null
    }

    suspend fun close(): SessionState {
        val immediateState = synchronized(lifecycleLock) {
            if (!startAttempted && _state.value == SessionState.Closed) {
                return@synchronized SessionState.Closed
            }
            null
        }
        if (immediateState != null) {
            return immediateState
        }

        var shouldInvokeClose = false
        val pendingClose = synchronized(lifecycleLock) {
            closeDeferred?.let { return@synchronized it }

            closeRequested = true
            startAttempted = true
            when (val existingState = _state.value) {
                is SessionState.Failed -> {
                    return@synchronized completedAndRememberClose(existingState)
                }

                SessionState.Closed -> {
                    if (startDeferred != null) {
                        return@synchronized completedAndRememberClose(existingState)
                    }
                    return@synchronized completedAndRememberClose(existingState)
                }

                SessionState.Running -> {
                    shouldInvokeClose = true
                }

                SessionState.Starting -> Unit
            }

            CompletableDeferred<SessionState>().also { closeDeferred = it }
        }

        if (pendingClose.isCompleted) {
            return pendingClose.await()
        }

        if (shouldInvokeClose) {
            invokeCloseIfNeeded()
        }
        synchronized(lifecycleLock) {
            when (val existingState = _state.value) {
                is SessionState.Failed -> completeIfNeeded(pendingClose, existingState)
                SessionState.Closed -> completeIfNeeded(pendingClose, existingState)
                SessionState.Running,
                SessionState.Starting,
                -> Unit
            }
        }
        return pendingClose.await()
    }

    private fun launchBackgroundJobs() {
        synchronized(lifecycleLock) {
            if (outputCollectionJob == null) {
                outputCollectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        backend.output.collect { chunk ->
                            appendChunk(chunk)
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) {
                            throw error
                        }
                    }
                }
            }

            if (exitAwaitJob == null) {
                exitAwaitJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val exitFailure = awaitExitSafely()
                    applyExitResult(exitFailure)
                }
            }
        }
    }

    private fun appendChunk(chunk: OutputChunk) {
        synchronized(bufferLock) {
            buffer += chunk
            totalBufferedChars += chunk.text.length
            trimBufferLocked()
        }
    }

    private fun trimBufferLocked() {
        while (buffer.size > bufferConfig.maxChunkCount ||
            (totalBufferedChars > bufferConfig.maxCharCount && buffer.size > 1)
        ) {
            val removed = buffer.removeAt(0)
            totalBufferedChars -= removed.text.length
        }
    }

    private fun applyExitResult(exitFailure: TerminalFailure?): SessionState {
        return synchronized(lifecycleLock) {
            val resolvedState = when (val existingState = _state.value) {
                is SessionState.Failed -> existingState
                SessionState.Closed,
                SessionState.Starting,
                SessionState.Running,
                -> exitFailure?.let(SessionState::Failed) ?: SessionState.Closed
            }

            _state.value = resolvedState
            outputCollectionJob?.cancel()
            outputCollectionJob = null
            completeIfNeeded(startDeferred, resolvedState)
            completeIfNeeded(closeDeferred, resolvedState)
            resolvedState
        }
    }

    private suspend fun awaitExitSafely(): TerminalFailure? {
        return try {
            backend.awaitExit()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            runtimeFailure(error)
        }
    }

    private suspend fun invokeCloseIfNeeded() {
        val shouldClose = synchronized(lifecycleLock) {
            if (closeInvoked) {
                false
            } else {
                closeInvoked = true
                true
            }
        }
        if (!shouldClose) {
            return
        }

        try {
            backend.close()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            applyExitResult(runtimeFailure(error))
        }
    }

    private fun runtimeFailure(error: Throwable): TerminalFailure.RuntimeTerminated {
        return TerminalFailure.RuntimeTerminated(
            identity = identity,
            message = error.message,
            cause = error,
        )
    }

    private fun completedAndRememberClose(state: SessionState): CompletableDeferred<SessionState> {
        return completedDeferred(state).also { deferred ->
            closeDeferred = deferred
        }
    }

    private fun completeIfNeeded(
        deferred: CompletableDeferred<SessionState>?,
        state: SessionState,
    ) {
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(state)
        }
    }

    private fun completedDeferred(state: SessionState): CompletableDeferred<SessionState> {
        return CompletableDeferred<SessionState>().also { deferred ->
            deferred.complete(state)
        }
    }
}
