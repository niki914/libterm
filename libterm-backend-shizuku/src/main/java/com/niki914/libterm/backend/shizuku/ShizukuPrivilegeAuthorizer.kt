package com.niki914.libterm.backend.shizuku

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.PrivilegeAuthorizer
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.shizuku.internal.RealShizukuPermissionRequester
import com.niki914.libterm.backend.shizuku.internal.ShizukuPermissionRequester
import com.niki914.libterm.backend.shizuku.internal.ShizukuPermissionResultListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class ShizukuPrivilegeAuthorizer internal constructor(
    private val permissionRequester: ShizukuPermissionRequester,
    private val requestCodeGenerator: () -> Int,
) : PrivilegeAuthorizer {
    public constructor() : this(
        permissionRequester = RealShizukuPermissionRequester(),
        requestCodeGenerator = { nextRequestCode.getAndIncrement() },
    )

    override suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult {
        if (identity != TerminalIdentity.SHIZUKU) {
            return AuthorizationResult.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = identity,
                    message = "Shizuku authorizer only supports SHIZUKU",
                ),
            )
        }

        return try {
            requestShizukuAuthorization()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            AuthorizationResult.Failed(
                TerminalFailure.AuthorizationFailed(
                    identity = TerminalIdentity.SHIZUKU,
                    message = error.message,
                    cause = error,
                ),
            )
        }
    }

    private suspend fun requestShizukuAuthorization(): AuthorizationResult {
        if (!permissionRequester.isBinderAlive()) {
            return AuthorizationResult.Unavailable(
                TerminalFailure.BackendUnavailable(
                    identity = TerminalIdentity.SHIZUKU,
                    message = "Shizuku is not installed or not running",
                ),
            )
        }
        if (permissionRequester.isPermissionGranted()) {
            return AuthorizationResult.Granted
        }

        val requestCode = requestCodeGenerator()
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: ShizukuPermissionResultListener
            listener = ShizukuPermissionResultListener { callbackRequestCode, granted ->
                if (callbackRequestCode != requestCode || !continuation.isActive) {
                    return@ShizukuPermissionResultListener
                }
                permissionRequester.removeRequestPermissionResultListener(listener)
                continuation.resume(
                    if (granted) {
                        AuthorizationResult.Granted
                    } else {
                        AuthorizationResult.Denied(
                            TerminalFailure.AuthorizationDenied(
                                identity = TerminalIdentity.SHIZUKU,
                                message = "Shizuku authorization was denied",
                            ),
                        )
                    },
                )
            }

            continuation.invokeOnCancellation {
                permissionRequester.removeRequestPermissionResultListener(listener)
            }

            try {
                permissionRequester.addRequestPermissionResultListener(listener)
                permissionRequester.requestPermission(requestCode)
            } catch (error: Throwable) {
                permissionRequester.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) {
                    continuation.resume(
                        AuthorizationResult.Failed(
                            TerminalFailure.AuthorizationFailed(
                                identity = TerminalIdentity.SHIZUKU,
                                message = error.message,
                                cause = error,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        private val nextRequestCode = AtomicInteger(1)
    }
}
