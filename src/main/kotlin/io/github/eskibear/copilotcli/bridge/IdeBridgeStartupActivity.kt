package io.github.eskibear.copilotcli.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Boots the IDE bridge for each project as soon as it finishes opening.
 */
class IdeBridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        IdeBridgeService.getInstance(project).start()
    }
}
