package io.github.eskibear.copilotcli.bridge

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val LOG = BridgeLog.forClass(IdeBridgeService::class.java)

/**
 * Per-project service that owns the lifetime of one MCP server + lock file.
 *
 * Started by IdeBridgeStartupActivity, disposed when the project is closed
 * (registered with Disposer parented to the project-scoped service).
 */
@Service(Service.Level.PROJECT)
class IdeBridgeService(private val project: Project) : Disposable {

    private val started = AtomicBoolean(false)
    private var transport: Transport? = null
    private var lockFile: LockFile? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        var phase = "init"
        try {
            phase = "construct-transport"
            val nonce = UUID.randomUUID().toString()
            val tx = Transport.create()
            transport = tx
            LOG.info("Transport constructed: scheme=${tx.scheme} socketPath=${tx.socketPath}")

            phase = "bind"
            tx.bind()

            phase = "build-server"
            val tools = IdeTools(project)
            val dispatcher = McpDispatcher(tools)
            val server = McpServer(nonce, dispatcher)

            phase = "write-lock"
            val id = UUID.randomUUID().toString()
            val lock = LockFile(LockFile.resolveLockDir(), id)
            lockFile = lock
            lock.write(
                LockFileInfo(
                    socketPath = tx.socketPath,
                    scheme = tx.scheme,
                    headers = mapOf("Authorization" to "Nonce $nonce"),
                    pid = ProcessHandle.current().pid(),
                    ideName = ApplicationInfo.getInstance().fullApplicationName,
                    timestamp = System.currentTimeMillis(),
                    workspaceFolders = listOfNotNull(project.basePath),
                    isTrusted = true,
                )
            )

            phase = "start-accept"
            acceptThread = thread(
                name = "copilot-ide-bridge-${ManagementFactory.getRuntimeMXBean().name}",
                isDaemon = true,
            ) {
                acceptLoop(tx, server)
            }
            LOG.info("IDE bridge started: scheme=${tx.scheme} socketPath=${tx.socketPath}")
        } catch (t: Throwable) {
            LOG.warn("Failed to start IDE bridge (phase=$phase)", t)
            notifyStartupFailure(phase, t)
            cleanupSilently()
        }
    }

    private fun notifyStartupFailure(phase: String, t: Throwable) {
        try {
            val message = "Copilot CLI IDE bridge failed to start at phase '$phase': ${t.javaClass.simpleName}: ${t.message}"
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Copilot CLI Bridge")
                ?: NotificationGroup.balloonGroup("Copilot CLI Bridge")
            group.createNotification(message, NotificationType.WARNING).notify(project)
        } catch (_: Throwable) {
            // Don't let notification failure mask the original error.
        }
    }

    private fun acceptLoop(tx: Transport, server: McpServer) {
        while (true) {
            val conn = try {
                tx.accept()
            } catch (t: Throwable) {
                LOG.warn("Transport accept failure; bridge will stop accepting", t)
                null
            } ?: break

            thread(name = "copilot-ide-bridge-handler", isDaemon = true) {
                server.handle(conn)
            }
        }
        LOG.info("IDE bridge accept loop exited")
    }

    override fun dispose() {
        cleanupSilently()
    }

    private fun cleanupSilently() {
        try { lockFile?.delete() } catch (_: Throwable) {}
        try { transport?.close() } catch (_: Throwable) {}
        lockFile = null
        transport = null
    }

    companion object {
        fun getInstance(project: Project): IdeBridgeService = project.getService(IdeBridgeService::class.java)
    }
}
