package com.niki914.libterm.runtime

import android.content.Context
import com.niki914.libterm.TerminalBufferConfig
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.backend.libsu.LibsuPrivilegeProvider
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeProvider
import kotlinx.coroutines.CoroutineScope

object LibTerm {
    @JvmStatic
    @JvmOverloads
    fun createDefaultManager(
        context: Context,
        scope: CoroutineScope,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
    ): TerminalManager {
        val appContext = context.applicationContext ?: context
        val clock = RuntimeClock
        val backendFactory = RuntimeBackendFactory(
            context = appContext,
            clock = clock,
            scope = scope,
        )
        val libsuProvider = LibsuPrivilegeProvider()
        val shizukuProvider = ShizukuPrivilegeProvider()

        return TerminalManager(
            privilegeProvider = RuntimePrivilegeProvider(
                libsuProvider = libsuProvider,
                shizukuProvider = shizukuProvider,
            ),
            privilegeAuthorizer = RuntimePrivilegeAuthorizer(
                libsuProvider = libsuProvider,
            ),
            idGenerator = RuntimeIdGenerator(),
            clock = clock,
            scope = scope,
            backendFactory = backendFactory::create,
            bufferConfig = bufferConfig,
        )
    }
}
