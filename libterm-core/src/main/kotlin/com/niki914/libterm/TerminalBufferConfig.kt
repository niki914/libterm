package com.niki914.libterm

data class TerminalBufferConfig(
    val maxChunkCount: Int = 256,
    val maxCharCount: Int = 65_536,
) {
    init {
        require(maxChunkCount > 0) { "maxChunkCount must be greater than 0" }
        require(maxCharCount > 0) { "maxCharCount must be greater than 0" }
    }
}
