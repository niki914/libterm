package com.niki914.libterm

data class OutputChunk(
    val text: String,
    val isStderr: Boolean,
    val timestampMillis: Long,
)
