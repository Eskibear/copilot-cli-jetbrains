package io.github.eskibear.copilotcli.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.awt.Dimension
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

private val LOG = logger<IdeMcpProjectService>()

/**
 * Per-project owner of the Copilot CLI `/ide` integration.
 *
 * Starts a byte-stream MCP server (IDE = server), advertises it through a lock file the CLI
 * discovers, implements the five IDE tools against IntelliJ APIs, and pushes selection change
 * notifications. Everything is torn down and the lock file removed when the project closes.
 */
@Service(Service.Level.PROJECT)
internal class IdeMcpProjectService(private val project: Project) : IdeToolHost, Disposable {

    private val started = AtomicBoolean(false)
    private val openDiffs = ConcurrentHashMap<String, DialogWrapper>()

    @Volatile private var transport: IdeSocketServer? = null
    @Volatile private var mcpServer: McpServer? = null
    @Volatile private var lockFile: IdeLockFile? = null
    @Volatile private var sessionName: String? = null

    private val selectionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val socketServer = IdeSocketServer.create()
        val nonce = "Nonce " + UUID.randomUUID().toString().replace("-", "")
        val server = McpServer(this, nonce, SERVER_NAME, SERVER_VERSION)
        try {
            socketServer.start { input, output -> server.serve(input, output) }
            // Assign early so a later failure is still reachable from dispose().
            transport = socketServer
            mcpServer = server

            val lock = IdeLockFile("jetbrains-${shortId()}.lock")
            lock.write(
                IdeLockFileInfo(
                    socketPath = socketServer.socketPath,
                    scheme = socketServer.scheme,
                    headers = mapOf("Authorization" to nonce),
                    pid = ProcessHandle.current().pid(),
                    timestamp = System.currentTimeMillis(),
                    workspaceFolders = workspaceFolders(),
                    ideName = ApplicationNamesInfo.getInstance().fullProductName,
                    isTrusted = true,
                ),
            )
            lockFile = lock
            registerEditorListeners()
            LOG.info("Copilot CLI /ide integration started for project '${project.name}'")
        } catch (t: Throwable) {
            LOG.warn("Failed to start Copilot CLI /ide integration", t)
            runCatching { lockFile?.delete() }
            runCatching { server.stop() }
            runCatching { socketServer.stop() }
            lockFile = null
            mcpServer = null
            transport = null
            started.set(false)
        }
    }

    fun isRunning(): Boolean = mcpServer != null

    override fun dispose() {
        // Release any in-flight open_diff calls so their blocked handler threads unwind.
        val pending = openDiffs.values.toList()
        openDiffs.clear()
        if (pending.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater(
                { for (dialog in pending) if (dialog.isShowing) dialog.close(DialogWrapper.CANCEL_EXIT_CODE) },
                ModalityState.any(),
            )
        }
        try {
            lockFile?.delete()
        } catch (_: Throwable) {
        }
        try {
            mcpServer?.stop()
        } catch (_: Throwable) {
        }
        try {
            transport?.stop()
        } catch (_: Throwable) {
        }
        lockFile = null
        mcpServer = null
        transport = null
    }

    // region IdeToolHost

    override fun getDiagnostics(uri: String?): List<IdeDiagnosticFileEntry> =
        runReadActionBlocking {
            val editorManager = FileEditorManager.getInstance(project)
            val documentManager = FileDocumentManager.getInstance()
            val openFiles = editorManager.openFiles.toList()
            val targets = if (uri.isNullOrBlank()) {
                openFiles
            } else {
                openFiles.filter { urlOf(it) == uri || pathOf(it) == uri }
            }
            targets.mapNotNull { file ->
                val document = documentManager.getDocument(file) ?: return@mapNotNull null
                // Read diagnostics from the document's markup model (the same highlighters the
                // editor renders). This is the public entry point; DaemonCodeAnalyzerImpl is not.
                val markup = DocumentMarkupModel.forDocument(document, project, false)
                val highlights = markup?.allHighlighters
                    ?.mapNotNull { it.errorStripeTooltip as? HighlightInfo }
                    ?: emptyList()
                val diagnostics = highlights.mapNotNull { toDiagnostic(document, it) }
                IdeDiagnosticFileEntry(urlOf(file), pathOf(file), diagnostics)
            }
        }

    override fun getSelection(): IdeSelectionInfo? = computeOnEdt {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@computeOnEdt null
        if (!editor.selectionModel.hasSelection()) return@computeOnEdt null
        buildSelectionInfo(editor, current = true)
    }

    override fun openDiff(originalFilePath: String, newFileContents: String, tabName: String): IdeDiffResponse {
        // Read the original file off the EDT so a large file / slow VFS read cannot freeze the UI.
        val inputs = prepareDiffInputs(originalFilePath)
        val future = CompletableFuture<Boolean>()
        ApplicationManager.getApplication().invokeLater({
            try {
                val request = buildDiffRequest(inputs, newFileContents, tabName)
                val dialog = DiffApprovalDialog(project, request, tabName)
                openDiffs[tabName] = dialog
                val accepted = dialog.showAndGet()
                openDiffs.remove(tabName)
                future.complete(accepted)
            } catch (t: Throwable) {
                LOG.warn("open_diff failed for '$tabName'", t)
                openDiffs.remove(tabName)
                future.complete(false)
            }
        }, ModalityState.any())
        val accepted = try {
            future.get()
        } catch (t: Throwable) {
            false
        }
        return IdeDiffResponse(
            success = true,
            result = if (accepted) "SAVED" else "REJECTED",
            trigger = "user_action",
            message = if (accepted) "User accepted the changes" else "User rejected the changes",
        )
    }

    override fun closeDiff(tabName: String): IdeCloseDiffResponse {
        val dialog = openDiffs[tabName]
            ?: return IdeCloseDiffResponse(true, alreadyClosed = true, tabName = tabName, message = "No open diff named '$tabName'")
        // ModalityState.any() so this runs while the modal diff dialog's event loop is active.
        ApplicationManager.getApplication().invokeLater(
            { if (dialog.isShowing) dialog.close(DialogWrapper.CANCEL_EXIT_CODE) },
            ModalityState.any(),
        )
        return IdeCloseDiffResponse(true, alreadyClosed = false, tabName = tabName, message = "Closed diff '$tabName'")
    }

    override fun updateSessionName(name: String): Boolean {
        if (name.isBlank()) return false
        sessionName = name
        LOG.info("Copilot CLI session name for project '${project.name}': $name")
        return true
    }

    // endregion

    /** Emits an `add_file_reference` / `add_selection` notification for the active editor. */
    fun emitReference(method: String) {
        val params = computeOnEdt {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@computeOnEdt null
            buildFileReferenceParams(editor)
        } ?: return
        mcpServer?.broadcast(method, ideGson.toJsonTree(params))
    }

    private fun registerEditorListeners() {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = scheduleSelectionBroadcast(e.editor)
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) = scheduleSelectionBroadcast(e.editor)
        }, this)
    }

    private fun scheduleSelectionBroadcast(editor: Editor) {
        if (editor.project != project) return
        if (mcpServer == null) return
        selectionAlarm.cancelAllRequests()
        selectionAlarm.addRequest({ broadcastSelection(editor) }, SELECTION_DEBOUNCE_MS)
    }

    private fun broadcastSelection(editor: Editor) {
        if (editor.isDisposed) return
        // Only report the truly-current editor; the user may have switched during the debounce.
        if (FileEditorManager.getInstance(project).selectedTextEditor != editor) return
        val info = buildSelectionInfo(editor, current = true) ?: return
        mcpServer?.broadcast("selection_changed", ideGson.toJsonTree(info))
    }

    private fun buildSelectionInfo(editor: Editor, current: Boolean): IdeSelectionInfo? {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val model = editor.selectionModel
        return IdeSelectionInfo(
            text = model.selectedText ?: "",
            filePath = pathOf(file),
            fileUrl = urlOf(file),
            selection = IdeSelectionRange(
                start = offsetToPosition(document, model.selectionStart),
                end = offsetToPosition(document, model.selectionEnd),
                isEmpty = !model.hasSelection(),
            ),
            current = current,
        )
    }

    private fun buildFileReferenceParams(editor: Editor): IdeAddFileReferenceParams? {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val model = editor.selectionModel
        val hasSelection = model.hasSelection()
        val range = if (hasSelection) {
            IdeRange(offsetToPosition(document, model.selectionStart), offsetToPosition(document, model.selectionEnd))
        } else {
            null
        }
        return IdeAddFileReferenceParams(
            filePath = pathOf(file),
            fileUrl = urlOf(file),
            selection = range,
            selectedText = if (hasSelection) model.selectedText else null,
        )
    }

    private fun prepareDiffInputs(originalFilePath: String): DiffInputs {
        val file = LocalFileSystem.getInstance().findFileByPath(originalFilePath.replace('\\', '/'))
            ?: return DiffInputs("", null)
        return runReadActionBlocking {
            val text = FileDocumentManager.getInstance().getDocument(file)?.text
                ?: String(file.contentsToByteArray(), file.charset)
            DiffInputs(text, file.fileType)
        }
    }

    private fun buildDiffRequest(inputs: DiffInputs, newFileContents: String, tabName: String): DiffRequest {
        val factory = DiffContentFactory.getInstance()
        val fileType = inputs.fileType
        val left = if (fileType != null) factory.create(project, inputs.originalText, fileType) else factory.create(project, inputs.originalText)
        val right = if (fileType != null) factory.create(project, newFileContents, fileType) else factory.create(project, newFileContents)
        return SimpleDiffRequest(tabName, left, right, "Current", "Proposed (Copilot CLI)")
    }

    private fun toDiagnostic(document: Document, info: HighlightInfo): IdeDiagnostic? {
        val message = info.description ?: return null
        if (info.severity < HighlightSeverity.INFORMATION) return null
        return IdeDiagnostic(
            message = message,
            severity = mapSeverity(info.severity),
            range = IdeRange(
                start = offsetToPosition(document, info.startOffset),
                end = offsetToPosition(document, info.endOffset),
            ),
        )
    }

    private fun mapSeverity(severity: HighlightSeverity): String = when {
        severity >= HighlightSeverity.ERROR -> "Error"
        severity >= HighlightSeverity.WARNING -> "Warning"
        severity >= HighlightSeverity.INFORMATION -> "Information"
        else -> "Hint"
    }

    private fun offsetToPosition(document: Document, offset: Int): IdePosition {
        val clamped = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(clamped)
        val character = clamped - document.getLineStartOffset(line)
        return IdePosition(line, character)
    }

    private fun workspaceFolders(): List<String> {
        val base = project.basePath ?: return emptyList()
        val native = try {
            java.nio.file.Path.of(base).toString()
        } catch (_: Throwable) {
            base
        }
        return listOf(native)
    }

    private fun pathOf(file: VirtualFile): String = try {
        file.toNioPath().toString()
    } catch (_: Throwable) {
        file.path
    }

    private fun urlOf(file: VirtualFile): String = try {
        file.toNioPath().toUri().toString()
    } catch (_: Throwable) {
        val path = file.path
        "file://" + if (path.startsWith("/")) path else "/$path"
    }

    private fun <T> computeOnEdt(block: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return block()
        val holder = arrayOfNulls<Any>(1)
        // any() so read-only queries still run if a modal diff dialog is currently open.
        application.invokeAndWait({ holder[0] = block() }, ModalityState.any())
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    private class DiffInputs(val originalText: String, val fileType: FileType?)

    private class DiffApprovalDialog(
        project: Project,
        request: DiffRequest,
        dialogTitle: String,
    ) : DialogWrapper(project, true) {
        private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)

        init {
            title = dialogTitle
            setOKButtonText("Accept")
            setCancelButtonText("Reject")
            diffPanel.setRequest(request)
            init()
        }

        override fun createCenterPanel(): JComponent = diffPanel.component.apply {
            preferredSize = Dimension(1000, 650)
        }
    }

    companion object {
        private const val SERVER_NAME = "copilot-cli-jetbrains"
        private const val SERVER_VERSION = "1.0"
        private const val SELECTION_DEBOUNCE_MS = 120

        fun getInstance(project: Project): IdeMcpProjectService = project.getService(IdeMcpProjectService::class.java)
    }
}
