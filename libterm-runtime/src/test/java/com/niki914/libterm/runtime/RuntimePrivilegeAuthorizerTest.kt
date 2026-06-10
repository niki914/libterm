package com.niki914.libterm.runtime

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeAuthorizer
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuntimePrivilegeAuthorizerTest {

    @Test
    fun `root authorization denied is preserved and does not call shizuku authorizer`() = runTest {
        val rootFailure = TerminalFailure.AuthorizationDenied(
            identity = TerminalIdentity.ROOT,
            message = "root denied",
        )
        val libsuProvider = RecordingPrivilegeProvider(
            BackendAvailability.Unauthorized(rootFailure),
        )
        val shizukuAuthorizer = RecordingAuthorizer(AuthorizationResult.Granted)
        val authorizer = RuntimePrivilegeAuthorizer(
            libsuProvider = libsuProvider,
            shizukuAuthorizer = shizukuAuthorizer,
        )

        val result = authorizer.requestAuthorization(TerminalIdentity.ROOT)

        val denied = result as AuthorizationResult.Denied
        assertSame(rootFailure, denied.failure)
        assertEquals(listOf(TerminalIdentity.ROOT), libsuProvider.calls)
        assertEquals(emptyList(), shizukuAuthorizer.calls)
    }

    @Test
    fun `shizuku authorization delegates to shizuku authorizer only`() = runTest {
        val shizukuFailure = TerminalFailure.AuthorizationDenied(
            identity = TerminalIdentity.SHIZUKU,
            message = "shizuku denied",
        )
        val shizukuResult = AuthorizationResult.Denied(shizukuFailure)
        val libsuProvider = RecordingPrivilegeProvider(BackendAvailability.Available)
        val shizukuAuthorizer = RecordingAuthorizer(shizukuResult)
        val authorizer = RuntimePrivilegeAuthorizer(
            libsuProvider = libsuProvider,
            shizukuAuthorizer = shizukuAuthorizer,
        )

        val result = authorizer.requestAuthorization(TerminalIdentity.SHIZUKU)

        assertSame(shizukuResult, result)
        assertEquals(emptyList(), libsuProvider.calls)
        assertEquals(listOf(TerminalIdentity.SHIZUKU), shizukuAuthorizer.calls)
    }

    private class RecordingPrivilegeProvider(
        private val availability: BackendAvailability,
    ) : PrivilegeProvider {
        val calls = mutableListOf<TerminalIdentity>()

        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            calls += identity
            return availability
        }
    }

    private class RecordingAuthorizer(
        private val result: AuthorizationResult,
    ) : PrivilegeAuthorizer {
        val calls = mutableListOf<TerminalIdentity>()

        override suspend fun requestAuthorization(identity: TerminalIdentity): AuthorizationResult {
            calls += identity
            return result
        }
    }
}
