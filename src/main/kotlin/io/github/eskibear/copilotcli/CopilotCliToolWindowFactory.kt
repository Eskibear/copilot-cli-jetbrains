package io.github.eskibear.copilotcli

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Backs the dedicated Copilot CLI tool window (registered in plugin.xml, anchored to the right).
 *
 * When the tool window is first shown, we start a `copilot` session in it. The heavy lifting is
 * shared with the Tools menu / status bar entry points through [CopilotCliLauncher.ensureSession],
 * which is idempotent, so opening the panel from the stripe and from the menu never creates two
 * sessions.
 */
class CopilotCliToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        CopilotCliLauncher.ensureSession(project, toolWindow)
    }
}
