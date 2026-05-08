package io.github.eskibear.copilotcli

import com.intellij.openapi.util.SystemInfo

/**
 * Platform-specific install commands for the GitHub Copilot CLI.
 *
 * Mirrors the official installer at https://gh.io/copilot-install:
 *   - Windows: `winget install --id GitHub.Copilot -e`
 *   - macOS / Linux: `curl -fsSL https://gh.io/copilot-install | bash`
 *
 * The command is meant to be executed inside the IDE's integrated terminal so the user can
 * watch progress, respond to prompts (e.g. winget license acceptance), and see errors directly.
 */
object CopilotCliInstaller {

    fun installCommand(): String = when {
        SystemInfo.isWindows -> "winget install --id GitHub.Copilot -e"
        else -> "curl -fsSL https://gh.io/copilot-install | bash"
    }

    fun terminalTabName(): String = "Install Copilot CLI"
}
