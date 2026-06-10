package com.niki914.libterm

import com.niki914.libterm.testing.FakeBackend
import com.niki914.libterm.testing.FakeAuthorizer
import com.niki914.libterm.testing.FakeClock
import com.niki914.libterm.testing.FakeProvider
import com.niki914.libterm.testing.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalManagerTest {

    @Test
    fun `open user success returns registered session`() = runTest {
        val fixture = createFixture()

        val result = fixture.manager.open(TerminalIdentity.USER)

        val success = assertIs<OpenResult.Success<TerminalSession>>(result)
        val session = success.value
        assertEquals("session-1", session.id)
        assertEquals(TerminalIdentity.USER, session.identity)
        assertEquals(SessionState.Running, session.currentState)
        assertSame(session, fixture.manager.get(session.id))
        assertEquals(listOf(session), fixture.manager.list())
        assertEquals(listOf(TerminalIdentity.USER), fixture.requestedIdentities)
        assertEquals(1, fixture.backend(0).startCallCount)

        fixture.finishAllBackends()
        advanceUntilIdle()
    }

    @Test
    fun `open root unavailable returns backend unavailable failure`() = runTest {
        val fixture = createFixture()
        fixture.provider.setUnavailable(TerminalIdentity.ROOT, message = "root missing")

        val result = fixture.manager.open(TerminalIdentity.ROOT)

        val failure = assertIs<OpenResult.Failure>(result)
        assertEquals(
            TerminalFailure.BackendUnavailable(
                identity = TerminalIdentity.ROOT,
                message = "root missing",
            ),
            failure.failure,
        )
        assertTrue(fixture.requestedIdentities.isEmpty())
        assertNull(fixture.manager.get("session-1"))
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `open unauthorized returns authorization denied failure`() = runTest {
        val fixture = createFixture()
        fixture.provider.setUnauthorized(TerminalIdentity.SHIZUKU, message = "permission denied")

        val result = fixture.manager.open(
            identity = TerminalIdentity.SHIZUKU,
            authorizationMode = AuthorizationMode.CHECK_ONLY,
        )

        val failure = assertIs<OpenResult.Failure>(result)
        assertEquals(
            TerminalFailure.AuthorizationDenied(
                identity = TerminalIdentity.SHIZUKU,
                message = "permission denied",
            ),
            failure.failure,
        )
        assertTrue(fixture.requestedIdentities.isEmpty())
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `open check only does not request authorization`() = runTest {
        val fixture = createFixture()
        fixture.provider.setUnauthorized(TerminalIdentity.SHIZUKU, message = "permission denied")

        val result = fixture.manager.open(
            identity = TerminalIdentity.SHIZUKU,
            authorizationMode = AuthorizationMode.CHECK_ONLY,
        )

        val failure = assertIs<OpenResult.Failure>(result)
        assertEquals(
            TerminalFailure.AuthorizationDenied(
                identity = TerminalIdentity.SHIZUKU,
                message = "permission denied",
            ),
            failure.failure,
        )
        assertTrue(fixture.authorizer.requestedIdentities.isEmpty())
        assertTrue(fixture.requestedIdentities.isEmpty())
    }

    @Test
    fun `open defaults to request authorization and opens after grant`() = runTest {
        val fixture = createFixture()
        fixture.provider.setUnauthorized(TerminalIdentity.SHIZUKU, message = "permission denied")
        fixture.authorizer.setGranted(TerminalIdentity.SHIZUKU)

        val result = fixture.manager.open(TerminalIdentity.SHIZUKU)

        val session = assertSuccess(result)
        assertEquals(TerminalIdentity.SHIZUKU, session.identity)
        assertEquals(listOf(TerminalIdentity.SHIZUKU), fixture.authorizer.requestedIdentities)
        assertEquals(listOf(TerminalIdentity.SHIZUKU), fixture.requestedIdentities)

        fixture.finishAllBackends()
        advanceUntilIdle()
    }

    @Test
    fun `open request if needed returns denied result without creating session`() = runTest {
        val fixture = createFixture()
        fixture.provider.setUnauthorized(TerminalIdentity.SHIZUKU, message = "permission denied")
        fixture.authorizer.setDenied(TerminalIdentity.SHIZUKU, message = "user denied")

        val result = fixture.manager.open(
            identity = TerminalIdentity.SHIZUKU,
            authorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
        )

        val failure = assertIs<OpenResult.Failure>(result)
        assertEquals(
            TerminalFailure.AuthorizationDenied(
                identity = TerminalIdentity.SHIZUKU,
                message = "user denied",
            ),
            failure.failure,
        )
        assertEquals(listOf(TerminalIdentity.SHIZUKU), fixture.authorizer.requestedIdentities)
        assertTrue(fixture.requestedIdentities.isEmpty())
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `open available does not request authorization`() = runTest {
        val fixture = createFixture()
        fixture.provider.setAvailable(TerminalIdentity.SHIZUKU)

        val result = fixture.manager.open(
            identity = TerminalIdentity.SHIZUKU,
            authorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
        )

        assertSuccess(result)
        assertTrue(fixture.authorizer.requestedIdentities.isEmpty())
        assertEquals(listOf(TerminalIdentity.SHIZUKU), fixture.requestedIdentities)

        fixture.finishAllBackends()
        advanceUntilIdle()
    }

    @Test
    fun `open backend startup failure returns explicit failure and does not register session`() = runTest {
        val startupFailure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.ROOT,
            message = "boom",
        )
        val fixture = createFixture { identity, clock ->
            FakeBackend(identity = identity, clock = clock).apply {
                failOnStart(startupFailure)
            }
        }

        val result = fixture.manager.open(TerminalIdentity.ROOT)

        val failure = assertIs<OpenResult.Failure>(result)
        assertEquals(startupFailure, failure.failure)
        assertEquals(listOf(TerminalIdentity.ROOT), fixture.requestedIdentities)
        assertEquals(1, fixture.backend(0).startCallCount)
        assertTrue(fixture.manager.list().isEmpty())
        assertNull(fixture.manager.get("session-1"))
    }

    @Test
    fun `list preserves registration order and get returns matching session`() = runTest {
        val fixture = createFixture()

        val first = assertSuccess(fixture.manager.open(TerminalIdentity.USER))
        val second = assertSuccess(fixture.manager.open(TerminalIdentity.ROOT))

        assertEquals(listOf(first, second), fixture.manager.list())
        assertSame(first, fixture.manager.get(first.id))
        assertSame(second, fixture.manager.get(second.id))
        assertEquals(listOf("session-1", "session-2"), fixture.manager.list().map { it.id })
        assertEquals(
            listOf(TerminalIdentity.USER, TerminalIdentity.ROOT),
            fixture.requestedIdentities,
        )

        fixture.finishAllBackends()
        advanceUntilIdle()
    }

    @Test
    fun `close existing removes session after closing backend`() = runTest {
        val fixture = createFixture()
        val session = assertSuccess(fixture.manager.open(TerminalIdentity.USER))
        val backend = fixture.backend(0)

        val closeResult = backgroundScope.async { fixture.manager.close(session.id) }
        runCurrent()

        assertEquals(1, backend.closeCallCount)
        assertSame(session, fixture.manager.get(session.id))

        backend.finishNormally()
        advanceUntilIdle()

        assertTrue(closeResult.await())
        assertNull(fixture.manager.get(session.id))
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `close missing id returns false without side effects`() = runTest {
        val fixture = createFixture()

        val result = fixture.manager.close("missing")

        assertFalse(result)
        assertTrue(fixture.requestedIdentities.isEmpty())
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `repeated close is safe after session removal`() = runTest {
        val fixture = createFixture()
        val session = assertSuccess(fixture.manager.open(TerminalIdentity.USER))
        val backend = fixture.backend(0)

        val firstClose = backgroundScope.async { fixture.manager.close(session.id) }
        runCurrent()
        backend.finishNormally()
        advanceUntilIdle()

        assertTrue(firstClose.await())
        assertFalse(fixture.manager.close(session.id))
        assertNull(fixture.manager.get(session.id))
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `open failure does not fallback to another identity`() = runTest {
        val startupFailure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.ROOT,
            message = "root boot failed",
        )
        val fixture = createFixture { identity, clock ->
            FakeBackend(identity = identity, clock = clock).apply {
                if (identity == TerminalIdentity.ROOT) {
                    failOnStart(startupFailure)
                }
            }
        }

        val result = fixture.manager.open(TerminalIdentity.ROOT)

        val failure = assertIs<OpenResult.Failure>(result)
        assertEquals(startupFailure, failure.failure)
        assertEquals(listOf(TerminalIdentity.ROOT), fixture.requestedIdentities)
        assertTrue(fixture.manager.list().isEmpty())
    }

    @Test
    fun `different sessions do not share output buffers`() = runTest {
        val fixture = createFixture()
        val first = assertSuccess(fixture.manager.open(TerminalIdentity.USER))
        val second = assertSuccess(fixture.manager.open(TerminalIdentity.USER))
        val firstBackend = fixture.backend(0)
        val secondBackend = fixture.backend(1)

        runCurrent()
        assertFalse(firstBackend === secondBackend)

        firstBackend.emitStdout(bytesOf("first"))
        secondBackend.emitStdout(bytesOf("second"))
        advanceUntilIdle()

        assertEquals(listOf(bytesOf("first")), first.latest(limit = 10).map { it.bytes })
        assertEquals(listOf(bytesOf("second")), second.latest(limit = 10).map { it.bytes })

        fixture.finishAllBackends()
        advanceUntilIdle()
    }

    private fun assertSuccess(result: OpenResult<TerminalSession>): TerminalSession {
        return assertIs<OpenResult.Success<TerminalSession>>(result).value
    }

    private fun bytesOf(text: String): TerminalBytes = TerminalBytes.of(text.encodeToByteArray())

    private fun TestScope.createFixture(
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
        backendFactory: ((TerminalIdentity, FakeClock) -> FakeBackend)? = null,
    ): ManagerFixture {
        val provider = FakeProvider()
        val authorizer = FakeAuthorizer()
        val clock = FakeClock()
        val requestedIdentities = mutableListOf<TerminalIdentity>()
        val createdBackends = mutableListOf<FakeBackend>()
        val manager = TerminalManager(
            privilegeProvider = provider,
            privilegeAuthorizer = authorizer,
            idGenerator = SequentialIdGenerator(),
            clock = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            backendFactory = { identity ->
                requestedIdentities += identity
                val backend = backendFactory?.invoke(identity, clock)
                    ?: FakeBackend(identity = identity, clock = clock)
                createdBackends += backend
                backend
            },
            bufferConfig = bufferConfig,
        )
        return ManagerFixture(
            provider = provider,
            authorizer = authorizer,
            requestedIdentities = requestedIdentities,
            createdBackends = createdBackends,
            manager = manager,
        )
    }

    private class ManagerFixture(
        val provider: FakeProvider,
        val authorizer: FakeAuthorizer,
        val requestedIdentities: MutableList<TerminalIdentity>,
        private val createdBackends: MutableList<FakeBackend>,
        val manager: TerminalManager,
    ) {
        fun backend(index: Int): FakeBackend = createdBackends[index]

        fun finishAllBackends() {
            createdBackends.forEach(FakeBackend::finishNormally)
        }
    }
}
