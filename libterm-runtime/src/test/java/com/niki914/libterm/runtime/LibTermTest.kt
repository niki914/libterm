package com.niki914.libterm.runtime

import android.content.Context
import android.content.ContextWrapper
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.OutputStream
import com.niki914.libterm.TerminalBytes
import kotlinx.coroutines.test.TestScope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LibTermTest {

    @Test
    fun `runtime returns runtime facade`() {
        val runtime: Any = LibTerm.runtime(scope = TestScope())

        assertIs<LibTermRuntime>(runtime)
    }

    @Test
    fun `runtime does not expose libterm facade production type`() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.niki914.libterm.runtime.LibTermFacade")
        }
    }

    @Test
    fun `legacy facade entries are removed from public api`() {
        val declaredMethods = LibTerm::class.java.declaredMethods
        val removedNames = listOf(
            "createDefaultManager",
            "newUserTerm",
            "newSuTerm",
            "newShizukuTerm",
            "term",
        )

        removedNames.forEach { methodName ->
            assertEquals(
                expected = false,
                actual = declaredMethods.any { method -> method.name == methodName },
                message = "$methodName should not stay in public LibTerm API",
            )
        }
    }

    @Test
    fun `runtime production source does not add text state boundary`() {
        val productionSource = runtimeProductionSource()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }

        val forbiddenTokens = listOf(
            "LibTermFacade",
            "TerminalText",
            "bytesToText",
            "decodeToString",
            "textState",
        )
        assertEquals(emptyList(), forbiddenTokens.filter { it in productionSource })
    }

    @Test
    fun `output chunk keeps raw terminal bytes`() {
        val rawBytes = byteArrayOf(0x00, 0xC3.toByte(), 0x0A)
        val chunk = OutputChunk(
            stream = OutputStream.STDOUT,
            bytes = TerminalBytes.of(rawBytes),
            timestampMillis = 100L,
        )

        rawBytes[0] = 0x7F

        assertContentEquals(byteArrayOf(0x00, 0xC3.toByte(), 0x0A), chunk.bytes.toByteArray())
    }

    private fun runtimeProductionSource(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir")))
        while (current != null) {
            val candidate =
                File(current, "libterm-runtime/src/main/java/com/niki914/libterm/runtime")
            if (candidate.isDirectory) {
                return candidate
            }
            current = current.parentFile
        }
        error("Could not find libterm-runtime production source")
    }

    private class RuntimeTestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
