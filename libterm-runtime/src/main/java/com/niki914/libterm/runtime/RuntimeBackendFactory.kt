package com.niki914.libterm.runtime

import android.content.Context
import com.niki914.libterm.Clock
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.libsu.LibsuTerminalBackend
import com.niki914.libterm.backend.shizuku.ShizukuTerminalBackend
import kotlinx.coroutines.CoroutineScope

internal class RuntimeBackendFactory(
    context: Context,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val appContext: Context = context.applicationContext ?: context

    internal fun create(identity: TerminalIdentity): TerminalBackend {
        return when (identity) {
            TerminalIdentity.USER -> LibsuTerminalBackend(
                identity = TerminalIdentity.USER,
                clock = clock,
                scope = scope,
            )

            TerminalIdentity.ROOT -> LibsuTerminalBackend(
                identity = TerminalIdentity.ROOT,
                clock = clock,
                scope = scope,
            )

            TerminalIdentity.SHIZUKU -> ShizukuTerminalBackend(
                context = appContext,
                clock = clock,
                scope = scope,
            )
        }
    }
}
