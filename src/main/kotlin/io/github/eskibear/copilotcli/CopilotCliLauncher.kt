package io.github.eskibear.copilotcli

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.ContentManager

private val LOG = logger<CopilotCliLauncher>()

/** Id of the dedicated Copilot CLI tool window, kept in sync with plugin.xml. */
const val COPILOT_CLI_TOOL_WINDOW_ID = "Copilot CLI"

/**
 * Shared entry point used by both the Tools menu action and the status bar widget.
 *
 * When the CLI is installed we open it in the dedicated Copilot CLI tool window, a Reworked
 * Terminal tab (TerminalToolWindowTabsManager) hosted in that tool window's content manager.
 * The install flow still runs in the regular Terminal tool window.
 *
 * We intentionally do NOT move the tab into the editor area. A Reworked Terminal that lives in
 * the editor cannot be dragged into a split: the platform re-opens the terminal view file in
 * the new split without the transient `CLOSING_TO_REOPEN` flag, so
 * `TerminalViewFileEditorProvider` spins up a brand new shell (without `copilot`) while the
 * original editor is disposed and its session is cancelled. The result is a fresh terminal in
 * the split and the running Copilot session gone (see issue #2).
 *
 * A terminal that stays in a tool window does not have this problem: it can be split inside the
 * tool window and the whole tool window can be docked to any side of the IDE without recreating
 * the session.
 */
object CopilotCliLauncher {

    fun launchOrInstall(project: Project) {
        if (CopilotCliService.isInstalled()) {
            openSidePanel(project)
            return
        }
        promptAndInstall(project)
    }

    /**
     * Opens (and focuses) the dedicated Copilot CLI tool window, starting a `copilot` session in
     * it if one is not already running.
     */
    private fun openSidePanel(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(COPILOT_CLI_TOOL_WINDOW_ID)
        if (toolWindow == null) {
            // Tool window not registered for some reason; fall back to the Terminal tool window
            // so the user still gets a working session.
            LOG.warn("Tool window '$COPILOT_CLI_TOOL_WINDOW_ID' not found; falling back to Terminal tool window")
            runInTerminal(
                project,
                tabName = "Copilot CLI",
                command = "copilot",
                errorTitle = "Launch GitHub Copilot CLI",
            )
            return
        }
        toolWindow.activate({ ensureSession(project, toolWindow) }, true)
    }

    /**
     * Ensures a `copilot` session tab exists in [toolWindow]. Idempotent: does nothing when the
     * tool window already hosts a tab. Called both when the panel is opened from the Tools menu /
     * status bar and when it is opened from the tool window stripe (via the factory).
     */
    fun ensureSession(project: Project, toolWindow: ToolWindow) {
        if (!CopilotCliService.isInstalled()) return
        val contentManager = toolWindow.contentManager
        if (contentManager.contentCount > 0) return
        runInTerminal(
            project,
            tabName = "Copilot CLI",
            command = "copilot",
            errorTitle = "Launch GitHub Copilot CLI",
            contentManager = contentManager,
        )
    }

    private fun promptAndInstall(project: Project) {
        val choice = Messages.showYesNoDialog(
            project,
            "GitHub Copilot CLI ('copilot') was not found on your PATH.\n\n" +
                "Install it now using the official installer? A terminal will open and run:\n\n" +
                "    ${CopilotCliInstaller.installCommand()}",
            "Install GitHub Copilot CLI",
            "Install",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (choice != Messages.YES) return

        runInTerminal(
            project,
            tabName = CopilotCliInstaller.terminalTabName(),
            command = CopilotCliInstaller.installCommand(),
            errorTitle = "Install GitHub Copilot CLI",
        )
    }

    private fun runInTerminal(
        project: Project,
        tabName: String,
        command: String,
        errorTitle: String,
        contentManager: ContentManager? = null,
    ) {
        try {
            val builder = TerminalToolWindowTabsManager.getInstance(project)
                .createTabBuilder()
                .workingDirectory(project.basePath)
                .tabName(tabName)
            if (contentManager != null) {
                // Host the tab in our own tool window's content manager. requestFocus must stay
                // false here: the builder's focus path targets the built-in Terminal tool window,
                // not ours. We activate our tool window ourselves in openSidePanel().
                builder.contentManager(contentManager).requestFocus(false)
            } else {
                builder.requestFocus(true)
            }
            val tab = builder.createTab()
            sendCommand(tab.view, command)
        } catch (t: Throwable) {
            LOG.warn("Failed to open integrated terminal for: $command", t)
            Messages.showErrorDialog(
                project,
                "Failed to open the integrated terminal: ${t.message}",
                errorTitle,
            )
        }
    }

    private fun sendCommand(view: TerminalView, command: String) {
        view.createSendTextBuilder()
            .shouldExecute()
            .send(command)
    }
}
