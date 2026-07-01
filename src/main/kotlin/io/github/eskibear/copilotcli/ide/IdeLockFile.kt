package io.github.eskibear.copilotcli.ide

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<IdeLockFile>()

/**
 * Writes and removes the discovery lock file the Copilot CLI scans to find this IDE's MCP server.
 *
 * Directory: `$COPILOT_HOME/ide/` (default `~/.copilot/ide/`).
 */
internal class IdeLockFile(private val fileName: String) {

    private val lockPath: Path = lockDirectory().resolve(fileName)

    val path: Path get() = lockPath

    fun write(info: IdeLockFileInfo) {
        Files.createDirectories(lockPath.parent)
        val tmp = lockPath.resolveSibling("$fileName.tmp")
        Files.write(tmp, ideGson.toJson(info).toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp, lockPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Throwable) {
            // Atomic move can fail across some filesystems; fall back to a plain replace.
            Files.move(tmp, lockPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        LOG.info("Wrote Copilot IDE lock file $lockPath")
    }

    fun delete() {
        try {
            Files.deleteIfExists(lockPath)
            Files.deleteIfExists(lockPath.resolveSibling("$fileName.tmp"))
        } catch (t: Throwable) {
            LOG.debug("Failed to delete Copilot IDE lock file $lockPath", t)
        }
    }

    private companion object {
        fun lockDirectory(): Path {
            val base = System.getenv("COPILOT_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + File.separator + ".copilot")
            return Path.of(base, "ide")
        }
    }
}
