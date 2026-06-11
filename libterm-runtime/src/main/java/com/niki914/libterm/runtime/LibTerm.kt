package com.niki914.libterm.runtime

import android.content.Context
import com.niki914.libterm.AuthorizationMode
import com.niki914.libterm.TerminalBufferConfig
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.backend.libsu.LibsuTerminalBackend
import com.niki914.libterm.backend.libsu.LibsuPrivilegeProvider
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeAuthorizer
import com.niki914.libterm.backend.shizuku.ShizukuTerminalBackend
import com.niki914.libterm.backend.shizuku.ShizukuPrivilegeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    @JvmStatic
    @JvmOverloads
    fun newUserTerm(
        scope: CoroutineScope? = null,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
    ): Term {
        val termScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return DefaultTerm(
            manager = createUserManager(
                scope = termScope,
                bufferConfig = bufferConfig,
            ),
            identity = TerminalIdentity.USER,
            scope = termScope,
            ownsScope = scope == null,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun newSuTerm(
        scope: CoroutineScope? = null,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
    ): Term {
        val termScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return DefaultTerm(
            manager = createSuManager(
                scope = termScope,
                bufferConfig = bufferConfig,
            ),
            identity = TerminalIdentity.ROOT,
            scope = termScope,
            ownsScope = scope == null,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun newShizukuTerm(
        context: Context,
        scope: CoroutineScope? = null,
        bufferConfig: TerminalBufferConfig = TerminalBufferConfig(),
    ): Term {
        val termScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return DefaultTerm(
            manager = createShizukuManager(
                context = context,
                scope = termScope,
                bufferConfig = bufferConfig,
            ),
            identity = TerminalIdentity.SHIZUKU,
            scope = termScope,
            ownsScope = scope == null,
        )
    }

    private fun createUserManager(
        scope: CoroutineScope,
        bufferConfig: TerminalBufferConfig,
    ): TerminalManager {
        val clock = RuntimeClock
        return TerminalManager(
            privilegeProvider = LibsuPrivilegeProvider(),
            idGenerator = RuntimeIdGenerator(),
            clock = clock,
            scope = scope,
            backendFactory = {
                LibsuTerminalBackend(
                    identity = TerminalIdentity.USER,
                    clock = clock,
                    scope = scope,
                )
            },
            bufferConfig = bufferConfig,
        )
    }

    private fun createSuManager(
        scope: CoroutineScope,
        bufferConfig: TerminalBufferConfig,
    ): TerminalManager {
        val clock = RuntimeClock
        val libsuProvider = LibsuPrivilegeProvider()
        return TerminalManager(
            privilegeProvider = libsuProvider,
            privilegeAuthorizer = RuntimePrivilegeAuthorizer(
                libsuProvider = libsuProvider,
            ),
            idGenerator = RuntimeIdGenerator(),
            clock = clock,
            scope = scope,
            backendFactory = {
                LibsuTerminalBackend(
                    identity = TerminalIdentity.ROOT,
                    clock = clock,
                    scope = scope,
                )
            },
            bufferConfig = bufferConfig,
        )
    }

    private fun createShizukuManager(
        context: Context,
        scope: CoroutineScope,
        bufferConfig: TerminalBufferConfig,
    ): TerminalManager {
        val clock = RuntimeClock
        return TerminalManager(
            privilegeProvider = ShizukuPrivilegeProvider(),
            privilegeAuthorizer = ShizukuPrivilegeAuthorizer(),
            idGenerator = RuntimeIdGenerator(),
            clock = clock,
            scope = scope,
            backendFactory = {
                ShizukuTerminalBackend(
                    context = context.applicationContext ?: context,
                    clock = clock,
                    scope = scope,
                )
            },
            bufferConfig = bufferConfig,
        )
    }
}
