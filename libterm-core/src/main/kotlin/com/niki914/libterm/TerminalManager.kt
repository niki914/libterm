package com.niki914.libterm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

class TerminalManager(
    private val privilegeProvider: PrivilegeProvider,
    private val privilegeAuthorizer: PrivilegeAuthorizer? = null,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val backendFactory: (TerminalIdentity) -> TerminalBackend,
    private val bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
) {
    private val registryLock = Any()
    private val sessionsById = LinkedHashMap<String, TerminalSession>()

    suspend fun open(
        identity: TerminalIdentity,
        authorizationMode: AuthorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
    ): OpenResult<TerminalSession> {
        return when (val availability = privilegeProvider.getAvailability(identity)) {
            is BackendAvailability.Unavailable -> OpenResult.Failure(availability.failure)
            is BackendAvailability.Unauthorized -> handleUnauthorized(
                identity = identity,
                authorizationMode = authorizationMode,
                failure = availability.failure,
            )

            BackendAvailability.Available -> openAvailableSession(identity)
        }
    }

    suspend fun close(id: String): Boolean {
        val session = synchronized(registryLock) {
            sessionsById[id]
        } ?: return false

        session.close()

        synchronized(registryLock) {
            sessionsById.remove(id)
        }
        return true
    }

    fun get(id: String): TerminalSession? {
        return synchronized(registryLock) {
            sessionsById[id]
        }
    }

    fun list(): List<TerminalSession> {
        return synchronized(registryLock) {
            sessionsById.values.toList()
        }
    }

    private suspend fun handleUnauthorized(
        identity: TerminalIdentity,
        authorizationMode: AuthorizationMode,
        failure: TerminalFailure,
    ): OpenResult<TerminalSession> {
        if (authorizationMode == AuthorizationMode.CHECK_ONLY) {
            return OpenResult.Failure(failure)
        }
        val authorizer = privilegeAuthorizer ?: return OpenResult.Failure(failure)

        return when (val result = authorizer.requestAuthorization(identity)) {
            AuthorizationResult.Granted -> openAvailableSession(identity)
            is AuthorizationResult.Denied -> OpenResult.Failure(result.failure)
            is AuthorizationResult.Unavailable -> OpenResult.Failure(result.failure)
            is AuthorizationResult.Failed -> OpenResult.Failure(result.failure)
        }
    }

    private suspend fun openAvailableSession(identity: TerminalIdentity): OpenResult<TerminalSession> {
        val sessionId = idGenerator.nextId()
        val backend = try {
            backendFactory(identity)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            return OpenResult.Failure(
                TerminalFailure.StartupFailed(
                    identity = identity,
                    message = error.message,
                    cause = error,
                ),
            )
        }
        val session = TerminalSession(
            id = sessionId,
            backend = backend,
            clock = clock,
            bufferConfig = bufferConfig,
            scope = scope,
        )

        return when (val state = session.start()) {
            SessionState.Running -> {
                synchronized(registryLock) {
                    sessionsById[session.id] = session
                }
                OpenResult.Success(session)
            }

            is SessionState.Failed -> OpenResult.Failure(state.failure)
            SessionState.Closed,
            SessionState.Starting,
                -> OpenResult.Failure(
                TerminalFailure.StartupFailed(
                    identity = identity,
                    message = "Session did not enter running state",
                ),
            )
        }
    }
}
