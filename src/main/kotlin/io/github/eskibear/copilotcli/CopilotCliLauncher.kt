package io.github.eskibear.copilotcli

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView

private val LOG = logger<CopilotCliLauncher>()

/**
 * Shared entry point used by both the Tools menu action and the status bar widget.
 *
 * Uses the Reworked Terminal API (TerminalToolWindowTabsManager) so the launched terminal
 * tab supports being dragged into the editor zone, matching the behavior of tabs created via
 * the terminal tool window's "+" button.
 */
object CopilotCliLauncher {

    fun launchOrInstall(project: Project) {
        if (CopilotCliService.isInstalled()) {
            launch(project)
            return
        }
        promptAndInstall(project)
    }

    private fun launch(project: Project) {
        runInTerminal(
            project,
            tabName = "Copilot CLI",
            command = "copilot",
            errorTitle = "Launch GitHub Copilot CLI",
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
    ) {
        try {
            val tab = TerminalToolWindowTabsManager.getInstance(project)
                .createTabBuilder()
                .workingDirectory(project.basePath)
                .tabName(tabName)
                .requestFocus(true)
                .createTab()
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
