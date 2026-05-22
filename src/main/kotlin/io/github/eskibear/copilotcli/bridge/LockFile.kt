package io.github.eskibear.copilotcli.bridge

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val LOG = BridgeLog.forClass(LockFile::class.java)

/**
 * JSON contract consumed by Copilot CLI. Field names and types must match the schema
 * the CLI validates (see ZSs zod schema in @github/copilot app.js, and VS Code's
 * extensions/copilot/.../vscode-node/lockFile.ts).
 */
data class LockFileInfo(
    val socketPath: String,
    val scheme: String, // "unix" or "pipe"
    val headers: Map<String, String>,
    val pid: Long,
    val ideName: String,
    val timestamp: Long,
    val workspaceFolders: List<String>,
    val isTrusted: Boolean,
)

class LockFile(private val lockDir: Path, private val id: String) {

    val path: Path = lockDir.resolve("$id.lock")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun write(info: LockFileInfo) {
        Files.createDirectories(lockDir)
        Files.writeString(
            path,
            gson.toJson(info),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        tryRestrictPermissions(path)
        LOG.info("Wrote IDE bridge lock file: $path")
    }

    fun delete() {
        try {
            Files.deleteIfExists(path)
        } catch (t: Throwable) {
            LOG.warn("Failed to delete lock file $path", t)
        }
    }

    companion object {
        fun resolveLockDir(): Path {
            val xdg = System.getenv("XDG_STATE_HOME")
            val home = if (!xdg.isNullOrBlank()) Path.of(xdg) else Path.of(System.getProperty("user.home"))
            return home.resolve(".copilot").resolve("ide")
        }

        private fun tryRestrictPermissions(p: Path) {
            try {
                val view = Files.getFileAttributeView(
                    p,
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                ) ?: return
                view.setPermissions(PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
                // Windows: filesystem ACLs are inherited from user profile, good enough for a PoC.
            } catch (t: Throwable) {
                LOG.debug("Could not chmod 600 lock file", t)
            }
        }

        @Suppress("unused")
        private val ALL_PERMS = PosixFilePermission.values()
    }
}
