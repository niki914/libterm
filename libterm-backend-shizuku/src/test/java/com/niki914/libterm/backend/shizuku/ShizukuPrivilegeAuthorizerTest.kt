package com.niki914.libterm.backend.shizuku

import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.shizuku.internal.ShizukuPermissionRequester
import com.niki914.libterm.backend.shizuku.internal.ShizukuPermissionResultListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuPrivilegeAuthorizerTest {

    @Test
    fun `already granted returns granted without requesting permission`() = runTest {
        val requester = FakeShizukuPermissionRequester(permissionGranted = true)
        val authorizer = createAuthorizer(requester)

        val result = authorizer.requestAuthorization(TerminalIdentity.SHIZUKU)

        assertEquals(AuthorizationResult.Granted, result)
        assertTrue(requester.requestedCodes.isEmpty())
        assertTrue(requester.listeners.isEmpty())
    }

    @Test
    fun `binder unavailable returns unavailable`() = runTest {
        val requester = FakeShizukuPermissionRequester(binderAlive = false)
        val authorizer = createAuthorizer(requester)

        val result = authorizer.requestAuthorization(TerminalIdentity.SHIZUKU)

        val unavailable = assertIs<AuthorizationResult.Unavailable>(result)
        assertEquals(TerminalIdentity.SHIZUKU, unavailable.failure.identity)
        assertEquals("Shizuku is not installed or not running", unavailable.failure.message)
        assertTrue(requester.requestedCodes.isEmpty())
    }

    @Test
    fun `user grants permission resumes granted and removes listener`() = runTest {
        val requester = FakeShizukuPermissionRequester()
        val authorizer = createAuthorizer(requester)

        val deferred = async { authorizer.requestAuthorization(TerminalIdentity.SHIZUKU) }
        runCurrent()

        assertEquals(listOf(42), requester.requestedCodes)
        assertEquals(1, requester.listeners.size)

        requester.dispatchResult(requestCode = 42, granted = true)
        runCurrent()

        assertEquals(AuthorizationResult.Granted, deferred.await())
        assertTrue(requester.listeners.isEmpty())
    }

    @Test
    fun `user denies permission resumes denied and removes listener`() = runTest {
        val requester = FakeShizukuPermissionRequester()
        val authorizer = createAuthorizer(requester)

        val deferred = async { authorizer.requestAuthorization(TerminalIdentity.SHIZUKU) }
        runCurrent()

        requester.dispatchResult(requestCode = 42, granted = false)
        runCurrent()

        val denied = assertIs<AuthorizationResult.Denied>(deferred.await())
        assertEquals(TerminalIdentity.SHIZUKU, denied.failure.identity)
        assertEquals("Shizuku authorization was denied", denied.failure.message)
        assertTrue(requester.listeners.isEmpty())
    }

    @Test
    fun `cancelling authorization removes listener`() = runTest {
        val requester = FakeShizukuPermissionRequester()
        val authorizer = createAuthorizer(requester)

        val deferred = async { authorizer.requestAuthorization(TerminalIdentity.SHIZUKU) }
        runCurrent()

        assertEquals(1, requester.listeners.size)

        deferred.cancel()
        runCurrent()

        assertTrue(requester.listeners.isEmpty())
    }

    @Test
    fun `unsupported identity returns unavailable`() = runTest {
        val requester = FakeShizukuPermissionRequester(permissionGranted = true)
        val authorizer = createAuthorizer(requester)

        val result = authorizer.requestAuthorization(TerminalIdentity.USER)

        val unavailable = assertIs<AuthorizationResult.Unavailable>(result)
        assertEquals(TerminalIdentity.USER, unavailable.failure.identity)
        assertEquals("Shizuku authorizer only supports SHIZUKU", unavailable.failure.message)
        assertTrue(requester.requestedCodes.isEmpty())
    }

    @Test
    fun `request exception maps to authorization failed and removes listener`() = runTest {
        val requester = FakeShizukuPermissionRequester(requestError = IllegalStateException("boom"))
        val authorizer = createAuthorizer(requester)

        val result = authorizer.requestAuthorization(TerminalIdentity.SHIZUKU)

        val failed = assertIs<AuthorizationResult.Failed>(result)
        val failure = assertIs<TerminalFailure.AuthorizationFailed>(failed.failure)
        assertEquals(TerminalIdentity.SHIZUKU, failure.identity)
        assertEquals("boom", failure.message)
        assertTrue(requester.listeners.isEmpty())
    }

    private fun createAuthorizer(
        requester: FakeShizukuPermissionRequester,
    ): ShizukuPrivilegeAuthorizer {
        return ShizukuPrivilegeAuthorizer(
            permissionRequester = requester,
            requestCodeGenerator = { 42 },
        )
    }

    private class FakeShizukuPermissionRequester(
        private val binderAlive: Boolean = true,
        private val permissionGranted: Boolean = false,
        private val requestError: Throwable? = null,
    ) : ShizukuPermissionRequester {
        val requestedCodes = mutableListOf<Int>()
        val listeners = mutableListOf<ShizukuPermissionResultListener>()

        override fun isBinderAlive(): Boolean = binderAlive

        override fun isPermissionGranted(): Boolean = permissionGranted

        override fun requestPermission(requestCode: Int) {
            requestError?.let { throw it }
            requestedCodes += requestCode
        }

        override fun addRequestPermissionResultListener(listener: ShizukuPermissionResultListener) {
            listeners += listener
        }

        override fun removeRequestPermissionResultListener(listener: ShizukuPermissionResultListener) {
            listeners -= listener
        }

        fun dispatchResult(requestCode: Int, granted: Boolean) {
            listeners.toList().forEach { listener ->
                listener.onRequestPermissionResult(requestCode, granted)
            }
        }
    }
}
