package com.niki914.libterm.shellsmoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandTerminationReason
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class AdbCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        var term: Term? = null
        try {
            if (intent.action != ACTION_EXEC) {
                resultCode = RESULT_INVALID_ARGUMENT
                resultData = errorJson("Unsupported action: ${intent.action}")
                return
            }

            val command = intent.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
            if (command == null) {
                resultCode = RESULT_INVALID_ARGUMENT
                resultData = errorJson("Missing extra: $EXTRA_COMMAND")
                return
            }

            val identityName =
                intent.getStringExtra(EXTRA_IDENTITY)?.uppercase() ?: IDENTITY_USER
            val timeoutMillis = intent.getIntExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
                .toLong()
                .coerceAtLeast(0L)

            term = createTerm(context, identityName)
            val execResult = runBlocking {
                term.exec(command = command, timeoutMillis = timeoutMillis)
            }
            when (execResult) {
                is TermResult.Success -> {
                    val value = execResult.value
                    resultCode = when (value.terminationReason) {
                        CommandTerminationReason.COMPLETED -> value.exitCode ?: RESULT_EXEC_FAILURE
                        CommandTerminationReason.TIMED_OUT -> RESULT_TIMED_OUT
                        CommandTerminationReason.SESSION_TERMINATED -> RESULT_SESSION_TERMINATED
                    }
                    resultData = JSONObject()
                        .put("ok", true)
                        .put("identity", identityName)
                        .put("command", command)
                        .put("stdout", value.stdout.toByteArray().decodeToString())
                        .put("stderr", value.stderr.toByteArray().decodeToString())
                        .put("exitCode", value.exitCode)
                        .put("timedOut", value.timedOut)
                        .put("terminationReason", value.terminationReason.name)
                        .toString()
                }

                is TermResult.Failure -> {
                    resultCode = RESULT_EXEC_FAILURE
                    resultData = errorJson(
                        message = execResult.failure.message
                            ?: execResult.failure.javaClass.simpleName,
                        failure = execResult.failure,
                        identity = identityName,
                        command = command,
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            resultCode = RESULT_INVALID_ARGUMENT
            resultData = errorJson(e.message ?: "Invalid argument")
        } catch (t: Throwable) {
            resultCode = RESULT_INTERNAL_ERROR
            resultData = errorJson(t.message ?: t.javaClass.simpleName)
        } finally {
            runBlocking {
                term?.close()
            }
        }
    }

    private fun createTerm(context: Context, identityName: String): Term {
        return when (identityName) {
            IDENTITY_USER -> RuntimeTermAdapter(
                context = context.applicationContext,
                scope = receiverScope,
                identity = TerminalIdentity.User,
            )

            IDENTITY_ROOT -> RuntimeTermAdapter(
                context = context.applicationContext,
                scope = receiverScope,
                identity = TerminalIdentity.Su,
            )

            IDENTITY_SHIZUKU -> RuntimeTermAdapter(
                context = context.applicationContext,
                scope = receiverScope,
                identity = TerminalIdentity.Shizuku,
            )

            else -> throw IllegalArgumentException("Unsupported identity: $identityName")
        }
    }

    private fun errorJson(
        message: String,
        failure: TerminalFailure? = null,
        identity: String? = null,
        command: String? = null,
    ): String {
        return JSONObject()
            .put("ok", false)
            .put("message", message)
            .put("failure", failure?.javaClass?.simpleName)
            .put("identity", identity)
            .put("command", command)
            .toString()
    }

    companion object {
        const val ACTION_EXEC = "com.niki914.libterm.shellsmoke.action.EXEC"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_IDENTITY = "identity"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"

        private const val DEFAULT_TIMEOUT_MS = 5_000
        private const val IDENTITY_USER = "USER"
        private const val IDENTITY_ROOT = "ROOT"
        private const val IDENTITY_SHIZUKU = "SHIZUKU"

        private const val RESULT_TIMED_OUT = -2
        private const val RESULT_INVALID_ARGUMENT = -3
        private const val RESULT_EXEC_FAILURE = -4
        private const val RESULT_INTERNAL_ERROR = -5
        private const val RESULT_SESSION_TERMINATED = -6

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
