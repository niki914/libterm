package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuntimePrivilegeProviderTest {

    @Test
    fun `user delegates to libsu provider only`() = runTest {
        val libsu = RecordingPrivilegeProvider(BackendAvailability.Available)
        val shizuku = RecordingPrivilegeProvider(shizukuUnavailable())
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.USER)

        assertSame(BackendAvailability.Available, result)
        assertEquals(listOf(TerminalIdentity.USER), libsu.calls)
        assertEquals(emptyList(), shizuku.calls)
    }

    @Test
    fun `root delegates to libsu provider only`() = runTest {
        val libsu = RecordingPrivilegeProvider(BackendAvailability.Available)
        val shizuku = RecordingPrivilegeProvider(shizukuUnavailable())
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.ROOT)

        assertSame(BackendAvailability.Available, result)
        assertEquals(listOf(TerminalIdentity.ROOT), libsu.calls)
        assertEquals(emptyList(), shizuku.calls)
    }

    @Test
    fun `shizuku delegates to shizuku provider only`() = runTest {
        val libsu = RecordingPrivilegeProvider(shizukuUnavailable())
        val shizukuFailure = TerminalFailure.AuthorizationDenied(
            identity = TerminalIdentity.SHIZUKU,
            message = "permission denied",
        )
        val shizukuResult = BackendAvailability.Unauthorized(shizukuFailure)
        val shizuku = RecordingPrivilegeProvider(shizukuResult)
        val provider = RuntimePrivilegeProvider(
            libsuProvider = libsu,
            shizukuProvider = shizuku,
        )

        val result = provider.getAvailability(TerminalIdentity.SHIZUKU)

        assertSame(shizukuResult, result)
        assertEquals(emptyList(), libsu.calls)
        assertEquals(listOf(TerminalIdentity.SHIZUKU), shizuku.calls)
    }

    @Test
    fun `delegated failure object is returned unchanged`() = runTest {
        val failure = TerminalFailure.BackendUnavailable(
            identity = TerminalIdentity.ROOT,
            message = "root unavailable",
        )
        val unavailable = BackendAvailability.Unavailable(failure)
        val provider = RuntimePrivilegeProvider(
            libsuProvider = RecordingPrivilegeProvider(unavailable),
            shizukuProvider = RecordingPrivilegeProvider(shizukuUnavailable()),
        )

        val result = provider.getAvailability(TerminalIdentity.ROOT)

        assertSame(unavailable, result)
    }

    private class RecordingPrivilegeProvider(
        private val result: BackendAvailability,
    ) : PrivilegeProvider {
        val calls = mutableListOf<TerminalIdentity>()

        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            calls += identity
            return result
        }
    }

    private fun shizukuUnavailable(): BackendAvailability {
        return BackendAvailability.Unavailable(
            TerminalFailure.BackendUnavailable(
                identity = TerminalIdentity.SHIZUKU,
                message = "not used",
            ),
        )
    }
}
