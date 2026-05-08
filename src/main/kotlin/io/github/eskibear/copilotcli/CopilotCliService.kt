package io.github.eskibear.copilotcli

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<CopilotCliService>()

/**
 * Detects whether the GitHub Copilot CLI (`copilot` binary) is reachable from the IDE's PATH.
 *
 * Stateless on purpose: callers should re-query right before launching so that a newly completed
 * install is picked up without restarting the IDE.
 */
object CopilotCliService {

    private const val EXECUTABLE_NAME = "copilot"

    fun isInstalled(): Boolean {
        val found = PathEnvironmentVariableUtil.findInPath(EXECUTABLE_NAME)
        if (found != null) {
            if (LOG.isDebugEnabled) LOG.debug("Found copilot at ${found.absolutePath}")
            return true
        }
        LOG.debug("copilot not found on PATH")
        return false
    }
}
