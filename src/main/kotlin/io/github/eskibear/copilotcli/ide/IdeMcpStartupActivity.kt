package io.github.eskibear.copilotcli.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the Copilot CLI `/ide` MCP server for each opened project so the CLI can discover this
 * IDE via its lock file. The per-project [IdeMcpProjectService] owns teardown on project close.
 */
internal class IdeMcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        IdeMcpProjectService.getInstance(project).start()
    }
}
