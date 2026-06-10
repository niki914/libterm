package com.niki914.libterm.backend.libsu

import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.topjohnwu.superuser.Shell
import java.io.InputStream
import java.io.OutputStream as JavaOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RealLibsuShellAdapterFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LibsuShellAdapterFactory {
    override suspend fun open(identity: TerminalIdentity): LibsuShellSession {
        val shell = withContext(ioDispatcher) {
            val builder = Shell.Builder.create()
            when (identity) {
                TerminalIdentity.USER -> builder.setFlags(Shell.FLAG_NON_ROOT_SHELL)
                TerminalIdentity.ROOT -> Unit
                TerminalIdentity.SHIZUKU -> throw IllegalArgumentException(
                    "libsu backend does not support SHIZUKU",
                )
            }
            builder.build().also { shell ->
                if (identity == TerminalIdentity.ROOT && !shell.isRoot) {
                    runCatching { shell.close() }
                    throw IllegalStateException("libsu returned a non-root shell for ROOT identity")
                }
            }
        }
        return RealLibsuShellSession(identity, shell, ioDispatcher)
    }
}

internal class RealLibsuShellSession(
    private val identity: TerminalIdentity,
    private val shell: Shell,
    private val ioDispatcher: CoroutineDispatcher,
) : LibsuShellSession {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val closed = AtomicBoolean(false)
    private val closeSignal = CountDownLatch(1)
    private val stdinReady = CompletableDeferred<JavaOutputStream>()
    private val exitSignal = CompletableDeferred<TerminalFailure?>()
    private val outputEvents = MutableSharedFlow<LibsuOutputEvent>(
        extraBufferCapacity = OUTPUT_BUFFER_CAPACITY,
    )

    @Volatile
    private var stdin: JavaOutputStream? = null

    override val output: Flow<LibsuOutputEvent> = outputEvents

    init {
        shell.submitTask(object : Shell.Task {
            override fun run(stdin: JavaOutputStream, stdout: InputStream, stderr: InputStream) {
                this@RealLibsuShellSession.stdin = stdin
                stdinReady.complete(stdin)

                scope.launch { pump(stdout) { LibsuOutputEvent.Stdout(it) } }
                scope.launch { pump(stderr) { LibsuOutputEvent.Stderr(it) } }

                closeSignal.await()
                completeExit(null)
            }

            override fun shellDied() {
                completeExit(
                    TerminalFailure.RuntimeTerminated(
                        identity = identity,
                        message = "libsu shell terminated",
                    ),
                )
            }
        })
    }

    override suspend fun write(input: TerminalBytes): SendResult {
        if (closed.get()) {
            return SendResult.Failed(
                TerminalFailure.RuntimeTerminated(
                    identity = identity,
                    message = "libsu shell session is closed",
                ),
            )
        }

        return try {
            val target = stdinReady.await()
            withContext(ioDispatcher) {
                target.write(input.toByteArray())
                target.flush()
            }
            SendResult.Sent
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            SendResult.Failed(
                TerminalFailure.RuntimeTerminated(
                    identity = identity,
                    message = error.message,
                    cause = error,
                ),
            )
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        closeSignal.countDown()
        withContext(ioDispatcher) {
            runCatching { stdin?.close() }
            val closedGracefully = runCatching {
                shell.waitAndClose(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (!closedGracefully) {
                runCatching { shell.close() }
            }
        }
        completeExit(null)
    }

    override suspend fun awaitExit(): TerminalFailure? = exitSignal.await()

    private suspend fun pump(
        input: InputStream,
        toEvent: (TerminalBytes) -> LibsuOutputEvent,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (!closed.get()) {
                val read = withContext(ioDispatcher) { input.read(buffer) }
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    outputEvents.emit(toEvent(TerminalBytes.of(buffer.copyOf(read))))
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) {
                completeExit(
                    TerminalFailure.RuntimeTerminated(
                        identity = identity,
                        message = throwable.message,
                        cause = throwable,
                    ),
                )
            }
        }
    }

    private fun completeExit(failure: TerminalFailure?) {
        if (exitSignal.complete(failure)) {
            closed.set(true)
            closeSignal.countDown()
            scope.cancel()
        }
    }

    private companion object {
        private const val OUTPUT_BUFFER_CAPACITY = 64
        private const val CLOSE_TIMEOUT_SECONDS = 1L
    }
}
