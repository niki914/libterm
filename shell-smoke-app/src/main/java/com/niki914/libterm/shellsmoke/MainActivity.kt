package com.niki914.libterm.shellsmoke

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.niki914.libterm.AuthorizationResult
import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.libsu.LibsuPrivilegeProvider
import com.niki914.libterm.backend.libsu.LibsuTerminalBackend
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeAuthorizer
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeProvider
import com.niki914.libterm.backend.shizuku.ShizukuTerminalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MainActivity : Activity() {
    private val uiScope = MainScope()
    private val buttons = mutableListOf<Button>()
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        appendLog("Shell Smoke ready. 点击按钮执行 echo/id/pwd。")
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        root.addView(button("Run USER shell") { runSmoke(ShellMode.USER) })
        root.addView(button("Run ROOT shell") { runSmoke(ShellMode.ROOT) })
        root.addView(button("Run SHIZUKU shell") { runSmoke(ShellMode.SHIZUKU) })
        root.addView(button("Clear log") { logView.text = "" })

        logView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView = ScrollView(this).apply {
            addView(logView)
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            buttons += this
        }
    }

    private fun runSmoke(mode: ShellMode) {
        uiScope.launch {
            setButtonsEnabled(false)
            appendLog("")
            appendLog("===== ${mode.label} smoke start =====")
            try {
                val result = withContext(Dispatchers.IO) {
                    ShellSmokeRunner(applicationContext, mode).run()
                }
                appendLog(result)
            } catch (error: Throwable) {
                appendLog("FAILED: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                appendLog("===== ${mode.label} smoke end =====")
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled }
    }

    private fun appendLog(message: String) {
        android.util.Log.d(TAG, message)
        logView.append(message)
        logView.append("\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private enum class ShellMode(
        val label: String,
        val identity: TerminalIdentity,
    ) {
        USER("USER", TerminalIdentity.USER),
        ROOT("ROOT", TerminalIdentity.ROOT),
        SHIZUKU("SHIZUKU", TerminalIdentity.SHIZUKU),
    }

    private class ShellSmokeRunner(
        private val context: android.content.Context,
        private val mode: ShellMode,
    ) {
        private val libsuProvider = LibsuPrivilegeProvider()
        private val shizukuProvider = ShizukuPrivilegeProvider()
        private val shizukuAuthorizer = ShizukuPrivilegeAuthorizer()

        suspend fun run(): String {
            val availability = availability()
            val authorized = authorizeIfNeeded(availability)
            if (!authorized) {
                return "UNAVAILABLE: ${availability.describe()}"
            }

            val backendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val backend = createBackend(backendScope)
            val doneMarker = "__LIBTERM_SMOKE_DONE_${mode.label}_${System.currentTimeMillis()}__"
            val captured = StringBuilder()

            try {
                val collecting = backendScope.async {
                    withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                        backend.output.first { chunk ->
                            captured.append(chunk.format())
                            doneMarker in captured.toString()
                        }
                    }
                }

                when (val start = backend.start()) {
                    BackendStartResult.Started -> Unit
                    is BackendStartResult.Failed -> return "START FAILED: ${start.failure.describe()}"
                }

                val command = """
                    echo "mode=${mode.label}"
                    id
                    pwd
                    echo "$doneMarker"
                """.trimIndent() + "\n"

                when (val sent = backend.send(TerminalBytes.of(command.encodeToByteArray()))) {
                    SendResult.Sent -> Unit
                    is SendResult.Failed -> return "SEND FAILED: ${sent.failure.describe()}"
                }

                collecting.await()
                return captured.toString().replace(doneMarker, "").trimEnd()
            } finally {
                backend.close()
                backendScope.cancel()
            }
        }

        private suspend fun availability(): BackendAvailability {
            return when (mode) {
                ShellMode.USER,
                ShellMode.ROOT,
                -> libsuProvider.getAvailability(mode.identity)

                ShellMode.SHIZUKU -> shizukuProvider.getAvailability(mode.identity)
            }
        }

        private suspend fun authorizeIfNeeded(availability: BackendAvailability): Boolean {
            return when (availability) {
                BackendAvailability.Available -> true
                is BackendAvailability.Unavailable -> false
                is BackendAvailability.Unauthorized -> when (mode) {
                    ShellMode.SHIZUKU -> shizukuAuthorizer.requestAuthorization(mode.identity) == AuthorizationResult.Granted
                    ShellMode.USER,
                    ShellMode.ROOT,
                    -> false
                }
            }
        }

        private fun createBackend(scope: CoroutineScope): TerminalBackend {
            return when (mode) {
                ShellMode.USER,
                ShellMode.ROOT,
                -> LibsuTerminalBackend(
                    identity = mode.identity,
                    clock = SystemClock,
                    scope = scope,
                )

                ShellMode.SHIZUKU -> ShizukuTerminalBackend(
                    context = context,
                    clock = SystemClock,
                    scope = scope,
                )
            }
        }

        private fun BackendAvailability.describe(): String {
            return when (this) {
                BackendAvailability.Available -> "available"
                is BackendAvailability.Unavailable -> failure.describe()
                is BackendAvailability.Unauthorized -> failure.describe()
            }
        }

        private fun TerminalFailure.describe(): String {
            return listOfNotNull(
                identity?.name,
                message,
            ).joinToString(separator = ": ").ifBlank { toString() }
        }

        private fun OutputChunk.format(): String {
            return "[${stream.name}] ${bytes.toByteArray().decodeToString()}"
        }

        private object SystemClock : Clock {
            override fun nowMillis(): Long = System.currentTimeMillis()
        }
    }

    private companion object {
        private const val TAG = "qwerqwer"
        private const val OUTPUT_TIMEOUT_MILLIS = 5_000L
    }
}
