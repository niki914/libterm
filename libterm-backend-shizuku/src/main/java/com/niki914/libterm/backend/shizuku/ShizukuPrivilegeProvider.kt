package com.niki914.libterm.backend.shizuku

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.shizuku.internal.RealShizukuAccessChecker
import com.niki914.libterm.backend.shizuku.internal.ShizukuAccessChecker
import com.niki914.libterm.backend.shizuku.internal.ShizukuAccessState

class ShizukuPrivilegeProvider internal constructor(
    private val accessChecker: ShizukuAccessChecker,
) : PrivilegeProvider {
    public constructor() : this(RealShizukuAccessChecker())

    override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
        return when (identity) {
            TerminalIdentity.SHIZUKU -> getShizukuAvailability()
            TerminalIdentity.USER,
            TerminalIdentity.ROOT -> BackendAvailability.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = identity,
                    message = "Shizuku backend only supports SHIZUKU",
                ),
            )
        }
    }

    private suspend fun getShizukuAvailability(): BackendAvailability {
        return when (accessChecker.checkAccess()) {
            ShizukuAccessState.NotInstalledOrNotRunning -> BackendAvailability.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = TerminalIdentity.SHIZUKU,
                    message = "Shizuku is not installed or not running",
                ),
            )

            ShizukuAccessState.Unauthorized -> BackendAvailability.Unauthorized(
                TerminalFailure.AuthorizationDenied(
                    identity = TerminalIdentity.SHIZUKU,
                    message = "Shizuku authorization was denied",
                ),
            )

            ShizukuAccessState.Authorized -> BackendAvailability.Available
        }
    }
}
