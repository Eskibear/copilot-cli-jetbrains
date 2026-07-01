package io.github.eskibear.copilotcli.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Sends the current editor selection to the connected Copilot CLI prompt, mirroring VS Code's
 * "Copilot CLI: Add Selection to Prompt" command.
 */
class AddSelectionToPromptAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            project != null &&
            editor != null &&
            editor.selectionModel.hasSelection() &&
            IdeMcpProjectService.getInstance(project).isRunning()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        IdeMcpProjectService.getInstance(project).emitReference("add_selection")
    }
}
