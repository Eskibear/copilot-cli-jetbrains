package io.github.eskibear.copilotcli

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.LightVirtualFile

private val LOG = logger<CopilotCliLauncher>()

/**
 * Shared entry point used by both the Tools menu action and the status bar widget.
 *
 * Uses the Reworked Terminal API (TerminalToolWindowTabsManager) so the launched terminal
 * tab supports being moved into the editor zone (which is what we do by default, to give
 * the Copilot CLI as much screen real estate as possible).
 */
object CopilotCliLauncher {

    /**
     * Opens a terminal tab and moves it to the editor area.
     * Exposed for integration testing (avoids CLI-installed check).
     */
    fun openTerminalInEditor(project: Project, tabName: String = "Copilot CLI", command: String = "echo test") {
        runInTerminal(project, tabName, command, "Test", openInEditor = true)
    }

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
            val builder = TerminalToolWindowTabsManager.getInstance(project)
                .createTabBuilder()
                .workingDirectory(project.basePath)
                .tabName(tabName)

            if (openInEditor) {
                // Create the tab without adding it to the Terminal tool window.
                // This avoids the duplication bug where, on first launch (before the Terminal
                // tool window has been initialized), the same session appeared in both the
                // tool window panel and the editor area.
                builder.shouldAddToToolWindow(false)
                    .requestFocus(false)
            }

            val tab = builder.createTab()
            sendCommand(tab.view, command)
            if (openInEditor) {
                openInEditorDirectly(project, tab)
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
     * Opens a detached terminal tab directly in the editor area, bypassing the tool window.
     *
     * Creates a TerminalViewVirtualFile via reflection (the class is internal to the terminal
     * plugin) and opens it with FileEditorManager. The tab must have been created with
     * shouldAddToToolWindow(false) so it is not attached to the Terminal tool window.
     */
    private fun openInEditorDirectly(project: Project, tab: TerminalToolWindowTab) {
        try {
            val file = createTerminalViewVirtualFile(tab.view)
            FileEditorManager.getInstance(project).openFile(file, true)
        } catch (t: Throwable) {
            LOG.warn("Failed to open terminal in editor area, falling back to tool window tab", t)
        }
    }

    /**
     * Creates a TerminalViewVirtualFile via reflection.
     * The class is internal to the terminal plugin module but we need it to
     * open a terminal session as an editor tab.
     */
    private fun createTerminalViewVirtualFile(view: TerminalView): LightVirtualFile {
        val clazz = Class.forName("com.intellij.terminal.frontend.editor.TerminalViewVirtualFile")
        val ctor = clazz.getDeclaredConstructor(TerminalView::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(view) as LightVirtualFile
    }
}

