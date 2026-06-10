package com.niki914.libterm.backend.libsu

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity

class LibsuPrivilegeProvider internal constructor(
    private val rootAccessChecker: LibsuRootAccessChecker,
) : PrivilegeProvider {
    public constructor() : this(RealLibsuRootAccessChecker())

    override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
        return when (identity) {
            TerminalIdentity.USER -> BackendAvailability.Available
            TerminalIdentity.ROOT -> getRootAvailability()
            TerminalIdentity.SHIZUKU -> BackendAvailability.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = TerminalIdentity.SHIZUKU,
                    message = "libsu backend does not support SHIZUKU",
                ),
            )
        }
    }

    private suspend fun getRootAvailability(): BackendAvailability {
        return when (val result = rootAccessChecker.checkRootAccess()) {
            LibsuRootAccessResult.Available -> BackendAvailability.Available
            is LibsuRootAccessResult.Unavailable -> BackendAvailability.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = TerminalIdentity.ROOT,
                    message = result.message ?: "Root access is not available",
                ),
            )

            is LibsuRootAccessResult.Unauthorized -> BackendAvailability.Unauthorized(
                TerminalFailure.AuthorizationDenied(
                    identity = TerminalIdentity.ROOT,
                    message = result.message ?: "Root authorization was denied",
                ),
            )
        }
    }
}
