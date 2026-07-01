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
 * Opens the Copilot CLI in a Reworked Terminal tab (TerminalToolWindowTabsManager) inside the
 * Terminal tool window.
 *
 * We intentionally do NOT move the tab into the editor area anymore. A Reworked Terminal that
 * lives in the editor cannot be dragged into a split: the platform re-opens the terminal view
 * file in the new split without the transient `CLOSING_TO_REOPEN` flag, so
 * `TerminalViewFileEditorProvider` spins up a brand new shell (without `copilot`) while the
 * original editor is disposed and its session is cancelled. The result is a fresh terminal in
 * the split and the running Copilot session gone (see issue #2).
 *
 * A terminal that stays in the Terminal tool window does not have this problem: it can be
 * split inside the tool window and the whole tool window can be docked to any side of the IDE
 * without recreating the session.
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
