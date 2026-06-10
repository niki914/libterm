package com.niki914.libterm.testing

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FakeProviderTest {

    @Test
    fun `returns available by default for unconfigured identity`() = runTest {
        val provider = FakeProvider()

        val availability = provider.getAvailability(TerminalIdentity.USER)

        assertEquals(BackendAvailability.Available, availability)
    }

    @Test
    fun `set unavailable exposes backend unavailable failure`() = runTest {
        val provider = FakeProvider()
        provider.setUnavailable(TerminalIdentity.ROOT, message = "su missing")

        val availability = provider.getAvailability(TerminalIdentity.ROOT)

        val unavailable = assertIs<BackendAvailability.Unavailable>(availability)
        assertEquals(
            TerminalFailure.BackendUnavailable(
                identity = TerminalIdentity.ROOT,
                message = "su missing",
            ),
            unavailable.failure,
        )
    }

    @Test
    fun `set unauthorized exposes authorization denied failure`() = runTest {
        val provider = FakeProvider()
        provider.setUnauthorized(TerminalIdentity.SHIZUKU, message = "permission denied")

        val availability = provider.getAvailability(TerminalIdentity.SHIZUKU)

        val unauthorized = assertIs<BackendAvailability.Unauthorized>(availability)
        assertEquals(
            TerminalFailure.AuthorizationDenied(
                identity = TerminalIdentity.SHIZUKU,
                message = "permission denied",
            ),
            unauthorized.failure,
        )
    }

    @Test
    fun `set available overrides previous failure configuration`() = runTest {
        val provider = FakeProvider()
        provider.setUnavailable(TerminalIdentity.USER, message = "offline")
        provider.setAvailable(TerminalIdentity.USER)

        val availability = provider.getAvailability(TerminalIdentity.USER)

        assertEquals(BackendAvailability.Available, availability)
    }
}
