package com.niki914.libterm

import kotlinx.coroutines.flow.Flow

interface TerminalBackend {
    val identity: TerminalIdentity
    val output: Flow<OutputChunk>

    suspend fun start(): BackendStartResult

    suspend fun send(input: String)

    suspend fun close()
}
