package io.github.eskibear.copilotcli

import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for CopilotCliLauncher internals.
 *
 * Verifies the reflection-based TerminalViewVirtualFile creation that is
 * central to the duplication fix.
 */
class CopilotCliLauncherTest {

    @Test
    fun `TerminalViewVirtualFile class is accessible and has expected constructor`() {
        val clazz = Class.forName("com.intellij.terminal.frontend.editor.TerminalViewVirtualFile")
        assertNotNull(clazz, "TerminalViewVirtualFile class should be loadable")
        assertTrue(
            LightVirtualFile::class.java.isAssignableFrom(clazz),
            "TerminalViewVirtualFile should extend LightVirtualFile"
        )

        // Verify the constructor signature matches what CopilotCliLauncher uses
        val ctor = assertDoesNotThrow {
            clazz.getDeclaredConstructor(TerminalView::class.java)
        }
        assertNotNull(ctor, "Should have a (TerminalView) constructor")
    }
}
