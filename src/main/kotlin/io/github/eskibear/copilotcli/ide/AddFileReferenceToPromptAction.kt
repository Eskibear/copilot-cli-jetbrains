package io.github.eskibear.copilotcli.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Sends the active file (and any selection) to the connected Copilot CLI prompt as a reference,
 * mirroring VS Code's "Copilot CLI: Add File Reference to Prompt" command.
 */
class AddFileReferenceToPromptAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null &&
            e.getData(CommonDataKeys.EDITOR) != null &&
            IdeMcpProjectService.getInstance(project).isRunning()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        IdeMcpProjectService.getInstance(project).emitReference("add_file_reference")
    }
}
