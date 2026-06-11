package com.niki914.libterm.shellsmoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class AdbCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        receiverScope.launch {
            var term: Term? = null
            try {
                if (intent.action != ACTION_EXEC) {
                    pendingResult.resultCode = RESULT_INVALID_ARGUMENT
                    pendingResult.resultData = errorJson("Unsupported action: ${intent.action}")
                    return@launch
                }

                val command = intent.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
                if (command == null) {
                    pendingResult.resultCode = RESULT_INVALID_ARGUMENT
                    pendingResult.resultData = errorJson("Missing extra: $EXTRA_COMMAND")
                    return@launch
                }

                val identityName = intent.getStringExtra(EXTRA_IDENTITY)?.uppercase() ?: IDENTITY_USER
                val timeoutMillis = intent.getIntExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
                    .toLong()
                    .coerceAtLeast(0L)

                term = createTerm(context, identityName)
                val execResult = term.exec(command = command, timeoutMillis = timeoutMillis)
                when (execResult) {
                    is TermResult.Success -> {
                        val value = execResult.value
                        pendingResult.resultCode = value.exitCode ?: RESULT_TIMED_OUT
                        pendingResult.resultData = JSONObject()
                            .put("ok", true)
                            .put("identity", identityName)
                            .put("command", command)
                            .put("stdout", value.stdout.toByteArray().decodeToString())
                            .put("stderr", value.stderr.toByteArray().decodeToString())
                            .put("exitCode", value.exitCode)
                            .put("timedOut", value.timedOut)
                            .toString()
                    }

                    is TermResult.Failure -> {
                        pendingResult.resultCode = RESULT_EXEC_FAILURE
                        pendingResult.resultData = errorJson(
                            message = execResult.failure.message ?: execResult.failure.javaClass.simpleName,
                            failure = execResult.failure,
                            identity = identityName,
                            command = command,
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                pendingResult.resultCode = RESULT_INVALID_ARGUMENT
                pendingResult.resultData = errorJson(e.message ?: "Invalid argument")
            } catch (t: Throwable) {
                pendingResult.resultCode = RESULT_INTERNAL_ERROR
                pendingResult.resultData = errorJson(t.message ?: t.javaClass.simpleName)
            } finally {
                term?.close()
                pendingResult.finish()
            }
        }
    }

    private fun createTerm(context: Context, identityName: String): Term {
        return when (identityName) {
            IDENTITY_USER -> LibTerm.newUserTerm()
            IDENTITY_ROOT -> LibTerm.newSuTerm()
            IDENTITY_SHIZUKU -> LibTerm.newShizukuTerm(context.applicationContext)
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

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
