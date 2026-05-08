package io.github.eskibear.copilotcli

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.LightVirtualFile

private val LOG = logger<RenameCopilotCliTabAction>()

private const val TERMINAL_VIEW_FILE_TYPE_NAME = "Terminal View"

/**
 * Right-click | Rename... on a terminal-view editor tab (the tab a Copilot CLI session opens
 * into). Pops a small input dialog and updates the tab title.
 *
 * Implementation notes:
 *  - We rename the underlying [LightVirtualFile] (`TerminalViewVirtualFile` extends it). That
 *    class is `internal` to the terminal plugin, so we cannot reference it directly; we
 *    detect it by file type name (`Terminal View`).
 *  - `LightVirtualFileBase.rename` only updates the in-memory name field and does NOT fire any
 *    VFS event, so the editor tab title would not refresh. We therefore wrap the rename in a
 *    manually published `VFilePropertyChangeEvent(PROP_NAME, ...)`, which is the same pattern
 *    used by the platform's `moveContentBackToTab` helper.
 */
class RenameCopilotCliTabAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = currentFile(e)
        e.presentation.isEnabledAndVisible = file != null && isRenamableTerminalFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = currentFile(e) ?: return
        if (!isRenamableTerminalFile(file)) return
        if (file !is LightVirtualFile) {
            LOG.warn("Skip rename: file is not a LightVirtualFile (got ${file.javaClass.name})")
            return
        }

        val oldName = file.name
        val input = Messages.showInputDialog(
            project,
            "New tab name:",
            "Rename Terminal Tab",
            null,
            oldName,
            object : InputValidatorEx {
                override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
                override fun canClose(inputString: String?): Boolean = checkInput(inputString)
                override fun getErrorText(inputString: String?): String? =
                    if (checkInput(inputString)) null else "Name cannot be empty"
            },
        )?.trim()
        if (input.isNullOrEmpty() || input == oldName) return

        try {
            renameLightVirtualFile(file, oldName, input)
        } catch (t: Throwable) {
            LOG.warn("Failed to rename terminal tab to '$input'", t)
        }
    }

    private fun renameLightVirtualFile(file: LightVirtualFile, oldName: String, newName: String) {
        val app = ApplicationManager.getApplication()
        runWriteAction {
            val event = VFilePropertyChangeEvent(this, file, VirtualFile.PROP_NAME, oldName, newName)
            val publisher = app.messageBus.syncPublisher(VirtualFileManager.VFS_CHANGES)
            val events = listOf(event)
            publisher.before(events)
            file.rename(this, newName)
            publisher.after(events)
        }
    }

    private fun currentFile(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(PlatformDataKeys.FILE_EDITOR)?.file

    private fun isRenamableTerminalFile(file: VirtualFile): Boolean {
        if (file !is LightVirtualFile) return false
        return try {
            file.fileType.name == TERMINAL_VIEW_FILE_TYPE_NAME
        } catch (_: Throwable) {
            false
        }
    }
}
