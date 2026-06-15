package com.niki914.libterm.shellsmoke

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.SessionState
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.Term
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val uiScope = MainScope()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sessions: List<DemoSessionViewState>
    private var activeSessionIndex: Int = 0
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var inputView: EditText
    private lateinit var sshConfigRow: LinearLayout
    private lateinit var sshHostInput: EditText
    private lateinit var sshPortInput: EditText
    private lateinit var sshUsernameInput: EditText
    private lateinit var sshPasswordInput: EditText
    private val actionButtons: MutableList<Button> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = listOf(
            DemoSessionViewState(
                label = "USER",
                termIdentity = TerminalIdentity.User,
                term = RuntimeTermAdapter(
                    context = applicationContext,
                    scope = uiScope,
                    identity = TerminalIdentity.User,
                ),
            ),
            DemoSessionViewState(
                label = "ROOT",
                termIdentity = TerminalIdentity.Su,
                term = RuntimeTermAdapter(
                    context = applicationContext,
                    scope = uiScope,
                    identity = TerminalIdentity.Su,
                ),
            ),
            DemoSessionViewState(
                label = "SHIZUKU",
                termIdentity = TerminalIdentity.Shizuku,
                term = RuntimeTermAdapter(
                    context = applicationContext,
                    scope = uiScope,
                    identity = TerminalIdentity.Shizuku,
                ),
            ),
            DemoSessionViewState(
                label = "SSH",
                termIdentity = TerminalIdentity.Ssh,
                term = LazyTermAdapter(
                    scope = uiScope,
                    createTerm = {
                        LibTerm.openSshTerm(
                            host = sshHostText(),
                            port = sshPortText(),
                            username = sshUsernameInput.text.toString(),
                            password = sshPasswordInput.text.toString(),
                        )
                    },
                ),
            ),
        )
        setContentView(createContentView())
        renderUi("Tap a session to open")
    }

    override fun onDestroy() {
        sessions.forEach { state ->
            state.renderJob?.cancel()
            state.stateJob?.cancel()
        }
        cleanupScope.launch {
            sessions.forEach { state ->
                state.term.close()
            }
            cleanupScope.cancel()
        }
        uiScope.cancel()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
            setBackgroundColor(Color.BLACK)
        }

        createSessionButtons(root)
        createToolbar(root)
        createSshConfig(root)

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        root.addView(statusView)

        outputView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setTextIsSelectable(true)
        }
        root.addView(
            outputView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        inputRow.addView(
            TextView(this).apply {
                text = "$"
                textSize = 16f
                typeface = Typeface.MONOSPACE
                setTextColor(HACKER_GREEN)
            },
        )
        inputView = EditText(this).apply {
            setSingleLine(false)
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.BLACK)
            hint = "command"
            setOnEditorActionListener { _, actionId, event ->
                if (shouldSendFromEditorAction(actionId, event)) {
                    sendInput()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(
            inputView,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        root.addView(inputRow)

        return root
    }

    private fun createSessionButtons(root: LinearLayout) {
        val sessionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        sessions.forEachIndexed { index, state ->
            sessionRow.addView(
                button(state.label) { selectSession(index) }.trackAction(),
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
        }
        root.addView(sessionRow)
    }

    private fun shouldSendFromEditorAction(actionId: Int, event: KeyEvent?): Boolean {
        if (actionId == EditorInfo.IME_ACTION_SEND) {
            return true
        }
        return event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
    }

    private fun createToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        toolbar.addView(toolbarButton("up") { recallPreviousCommand() })
        toolbar.addView(toolbarButton("tab") { insertInputText("\t") })
        toolbar.addView(toolbarButton("../") { insertInputText("../") })
        toolbar.addView(toolbarButton("\\n") { insertInputText("\n") })
        root.addView(toolbar)
    }

    private fun createSshConfig(root: LinearLayout) {
        sshConfigRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        sshHostInput = sshConfigEditText(
            hint = "host",
            text = DEFAULT_SSH_HOST,
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        sshPortInput = sshConfigEditText(
            hint = "port",
            text = DEFAULT_SSH_PORT.toString(),
            inputType = InputType.TYPE_CLASS_NUMBER,
        )
        sshUsernameInput = sshConfigEditText(
            hint = "user",
            text = "",
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        sshPasswordInput = sshConfigEditText(
            hint = "password",
            text = "",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        listOf(sshHostInput, sshPortInput, sshUsernameInput, sshPasswordInput).forEach { input ->
            sshConfigRow.addView(
                input,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
        }
        root.addView(sshConfigRow)
    }

    private fun sshConfigEditText(
        hint: String,
        text: String,
        inputType: Int,
    ): EditText {
        return EditText(this).apply {
            setSingleLine(true)
            this.hint = hint
            setText(text)
            this.inputType = inputType
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.BLACK)
        }
    }

    private fun toolbarButton(text: String, onClick: () -> Unit): Button {
        return button(text, onClick).trackAction().apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
        }
    }

    private fun openSession(state: DemoSessionViewState) {
        setActionsEnabled(false)
        setStatus("OPENING: ${state.label}")
        uiScope.launch {
            when (val result = state.term.open()) {
                is TermResult.Success -> registerSession(state)
                is TermResult.Failure -> renderUi("OPEN FAILED: ${result.failure.describeForDemo()}")
            }
            setActionsEnabled(true)
        }
    }

    private fun registerSession(state: DemoSessionViewState) {
        if (state.renderJob == null) {
            state.renderJob = startRenderJob(state)
        }
        if (state.stateJob == null) {
            state.stateJob = startStateJob(state)
        }
        renderUi("OPENED: ${state.label}")
    }

    private fun selectSession(index: Int) {
        activeSessionIndex = index
        val state = sessions[index]
        renderUi("SELECTED: ${state.label}")
        if (state.renderJob == null && state.termIdentity != TerminalIdentity.Ssh) {
            openSession(state)
        }
    }

    private fun sendInput() {
        setActionsEnabled(false)
        uiScope.launch {
            val state = activeState()
            when (val openResult = state.term.open()) {
                is TermResult.Failure -> {
                    renderUi("OPEN FAILED: ${openResult.failure.describeForDemo()}")
                    setActionsEnabled(true)
                    return@launch
                }

                is TermResult.Success -> {
                    if (state.renderJob == null) {
                        state.renderJob = startRenderJob(state)
                    }
                    if (state.stateJob == null) {
                        state.stateJob = startStateJob(state)
                    }
                }
            }
            val command = inputView.text.toString()
            recordCommand(command)
            inputView.text.clear()
            val result = state.term.write(command + "\n")
            renderUi(handleSendResult(result))
            setActionsEnabled(true)
        }
    }

    private fun handleSendResult(result: TermResult<Unit>): String {
        return when (result) {
            is TermResult.Success -> "SENT"
            is TermResult.Failure -> "SEND FAILED: ${result.failure.describeForDemo()}"
        }
    }

    private fun activeState(): DemoSessionViewState {
        return sessions[activeSessionIndex]
    }

    private fun insertInputText(text: String) {
        val start = inputView.selectionStart.coerceAtLeast(0)
        val end = inputView.selectionEnd.coerceAtLeast(0)
        inputView.text.replace(minOf(start, end), maxOf(start, end), text)
    }

    private fun recallPreviousCommand() {
        val state = activeState()
        if (state.commandHistory.isEmpty()) {
            return
        }
        val cursor =
            state.historyCursor?.let { (it - 1).coerceAtLeast(0) } ?: state.commandHistory.lastIndex
        state.historyCursor = cursor
        inputView.setText(state.commandHistory[cursor])
        inputView.setSelection(inputView.text.length)
    }

    private fun recordCommand(command: String) {
        if (command.isBlank()) {
            return
        }
        val state = activeState()
        state.commandHistory += command
        if (state.commandHistory.size > MAX_COMMAND_HISTORY) {
            state.commandHistory.removeAt(0)
        }
        state.historyCursor = null
    }

    private fun startRenderJob(state: DemoSessionViewState): Job {
        return uiScope.launch {
            state.term.stream.collect { chunk ->
                appendOutput(state, chunk)
            }
        }
    }

    private fun startStateJob(state: DemoSessionViewState): Job {
        return uiScope.launch {
            state.term.state.collect { sessionState ->
                if (state == sessions[activeSessionIndex]) {
                    setStatus("${state.label}: ${sessionState.describeForDemo()}")
                }
            }
        }
    }

    private fun appendOutput(state: DemoSessionViewState, chunk: OutputChunk) {
        state.renderedText = (state.renderedText + chunk.decodeForDemo())
            .takeLast(MAX_RENDER_CHARS)
        if (state == sessions[activeSessionIndex]) {
            outputView.text = state.renderedText
        }
    }

    private fun renderUi(status: String? = null) {
        if (status != null) {
            statusView.text = status
        }
        sshConfigRow.visibility = if (activeState().termIdentity == TerminalIdentity.Ssh) {
            View.VISIBLE
        } else {
            View.GONE
        }
        outputView.text = sessions[activeSessionIndex].renderedText
    }

    private fun sshHostText(): String {
        return sshHostInput.text.toString().ifBlank { DEFAULT_SSH_HOST }
    }

    private fun sshPortText(): Int {
        return sshPortInput.text.toString().toIntOrNull() ?: DEFAULT_SSH_PORT
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun Button.trackAction(): Button {
        actionButtons += this
        return this
    }

    private fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }

    private fun setStatus(status: String) {
        statusView.text = status
    }

    private fun TerminalFailure.describeForDemo(): String {
        return "${this::class.simpleName}(identity=${identity?.name ?: "NONE"}, message=${message ?: "no message"})"
    }

    private fun SessionState.describeForDemo(): String {
        return when (this) {
            SessionState.Closed -> "Closed"
            is SessionState.Failed -> "Failed(${failure.describeForDemo()})"
            SessionState.Running -> "Running"
            SessionState.Starting -> "Starting"
        }
    }

    private fun OutputChunk.decodeForDemo(): String {
        return bytes.toByteArray().decodeToString()
    }

    private data class DemoSessionViewState(
        val label: String,
        val term: Term,
        val termIdentity: TerminalIdentity,
        var renderJob: Job? = null,
        var stateJob: Job? = null,
        var renderedText: String = "",
        val commandHistory: MutableList<String> = mutableListOf(),
        var historyCursor: Int? = null,
    )

    private companion object {
        private const val MAX_RENDER_CHARS = 12_000
        private const val MAX_COMMAND_HISTORY = 20
        private const val DEFAULT_SSH_HOST = "127.0.0.1"
        private const val DEFAULT_SSH_PORT = 2222
        private val HACKER_GREEN = Color.rgb(57, 255, 20)
    }
}
