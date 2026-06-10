package com.niki914.libterm.backend.libsu

import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import kotlinx.coroutines.flow.Flow

internal interface LibsuShellAdapterFactory {
    suspend fun open(identity: TerminalIdentity): LibsuShellSession
}

internal interface LibsuShellSession {
    val output: Flow<LibsuOutputEvent>

    suspend fun write(input: TerminalBytes): SendResult

    suspend fun close()

    suspend fun awaitExit(): TerminalFailure?
}

internal sealed interface LibsuOutputEvent {
    data class Stdout(val bytes: TerminalBytes) : LibsuOutputEvent

    data class Stderr(val bytes: TerminalBytes) : LibsuOutputEvent
}
