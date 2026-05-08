package io.github.eskibear.copilotcli

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView

private val LOG = logger<CopilotCliLauncher>()

/**
 * Shared entry point used by both the Tools menu action and the status bar widget.
 *
 * Uses the Reworked Terminal API (TerminalToolWindowTabsManager) so the launched terminal
 * tab supports being moved into the editor zone (which is what we do by default, to give
 * the Copilot CLI as much screen real estate as possible).
 */
object CopilotCliLauncher {

    private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
    private const val MOVE_TERMINAL_TO_EDITOR_ACTION_ID = "Terminal.MoveToEditor"

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
            openInEditor = true,
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
            openInEditor = false,
        )
    }

    private fun runInTerminal(
        project: Project,
        tabName: String,
        command: String,
        errorTitle: String,
        openInEditor: Boolean,
    ) {
        try {
            val tab = TerminalToolWindowTabsManager.getInstance(project)
                .createTabBuilder()
                .workingDirectory(project.basePath)
                .tabName(tabName)
                .requestFocus(true)
                .createTab()
            sendCommand(tab.view, command)
            if (openInEditor) {
                moveTabToEditor(project, tab)
            }
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

    /**
     * Detaches the freshly created terminal tab from the Terminal tool window and reopens it
     * as a regular editor tab, by invoking the platform action `Terminal.MoveToEditor`.
     *
     * We delegate to the action rather than calling the underlying `TerminalViewVirtualFile`
     * code path directly, because that class is `internal` to the terminal plugin module.
     */
    private fun moveTabToEditor(project: Project, tab: TerminalToolWindowTab) {
        val action = ActionManager.getInstance().getAction(MOVE_TERMINAL_TO_EDITOR_ACTION_ID)
        if (action == null) {
            LOG.info("Action $MOVE_TERMINAL_TO_EDITOR_ACTION_ID not found, leaving terminal tab in tool window")
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
        if (toolWindow == null) {
            LOG.info("Terminal tool window not found, leaving terminal tab in tool window")
            return
        }
        // Run on EDT so action infrastructure and content selection happen on the UI thread.
        invokeLater {
            try {
                toolWindow.contentManager.setSelectedContent(tab.content)
                val dataContext = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(PlatformDataKeys.TOOL_WINDOW, toolWindow)
                    .add(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER, toolWindow.contentManager)
                    .build()
                val event = AnActionEvent.createEvent(action, dataContext, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
                action.actionPerformed(event)
            } catch (t: Throwable) {
                LOG.warn("Failed to move Copilot CLI terminal tab to editor", t)
            }
        }
    }
}
