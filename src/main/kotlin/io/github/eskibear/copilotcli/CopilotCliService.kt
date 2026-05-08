package io.github.eskibear.copilotcli

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

private val LOG = logger<CopilotCliService>()

/**
 * Detects whether the GitHub Copilot CLI (`copilot` binary) is reachable.
 *
 * Stateless on purpose: callers should re-query right before launching so that a newly completed
 * install is picked up without restarting the IDE.
 *
 * Detection strategy (each step is cheap and runs on EDT):
 *  1. `PathEnvironmentVariableUtil.findInPath` — checks the IDE process PATH (with PATHEXT
 *     handling on Windows). This works for the common "system-wide install" case and for
 *     anything in the IDE's login-shell PATH on macOS/Linux.
 *  2. Well-known npm-global locations — covers the case where the user installed via
 *     `npm install -g @github/copilot` but the IDE's PATH does not include the npm-global bin.
 *     This is common when:
 *       - On Windows, the package was installed AFTER the IDE was started, so the IDE process
 *         env is stale.
 *       - On macOS/Linux, the user manages Node via nvm/fnm/volta which usually only adjusts
 *         PATH inside interactive shells (~/.bashrc, ~/.zshrc). The integrated terminal (which
 *         IS interactive) sees the binary, but the IDE process does not.
 */
object CopilotCliService {

    private const val EXECUTABLE_NAME = "copilot"

    fun isInstalled(): Boolean {
        PathEnvironmentVariableUtil.findInPath(EXECUTABLE_NAME)?.let {
            if (LOG.isDebugEnabled) LOG.debug("Found copilot via PATH at ${it.absolutePath}")
            return true
        }
        findInWellKnownLocations()?.let {
            if (LOG.isDebugEnabled) LOG.debug("Found copilot via well-known location at ${it.absolutePath}")
            return true
        }
        LOG.debug("copilot not found on PATH or in well-known locations")
        return false
    }

    private fun findInWellKnownLocations(): File? {
        val home = System.getProperty("user.home") ?: return null
        val candidates = mutableListOf<File>()
        if (SystemInfo.isWindows) {
            // npm global on Windows installs into %APPDATA%\npm by default. Check the .cmd
            // shim (most common) plus alternates that npm has used over time.
            val appData = System.getenv("APPDATA")
            if (appData != null) {
                val npmDir = File(appData, "npm")
                candidates += File(npmDir, "copilot.cmd")
                candidates += File(npmDir, "copilot.ps1")
                candidates += File(npmDir, "copilot.bat")
                candidates += File(npmDir, "copilot.exe")
            }
            // Some Node installations (Volta, fnm, scoop) put shims elsewhere.
            System.getenv("VOLTA_HOME")?.let { candidates += File("$it\\bin\\copilot.cmd") }
        } else {
            // POSIX npm-global / Homebrew / per-user bin defaults.
            candidates += File("$home/.npm-global/bin/copilot")
            candidates += File("$home/.local/bin/copilot")
            candidates += File("$home/bin/copilot")
            candidates += File("/usr/local/bin/copilot")
            candidates += File("/opt/homebrew/bin/copilot")
            // Volta / fnm shims that don't always make it into the IDE's PATH.
            candidates += File("$home/.volta/bin/copilot")
            candidates += File("$home/.fnm/aliases/default/bin/copilot")
            // nvm: walk the installed Node versions and accept any that has a copilot symlink.
            val nvmVersionsDir = File("$home/.nvm/versions/node")
            if (nvmVersionsDir.isDirectory) {
                nvmVersionsDir.listFiles()?.forEach { versionDir ->
                    candidates += File(versionDir, "bin/copilot")
                }
            }
        }
        return candidates.firstOrNull {
            try {
                it.isFile && it.canExecute()
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
