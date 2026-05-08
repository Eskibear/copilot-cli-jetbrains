package io.github.eskibear.copilotcli

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IconWidgetPresentation
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WidgetPresentation
import com.intellij.openapi.wm.WidgetPresentationDataContext
import com.intellij.openapi.wm.WidgetPresentationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.awt.event.MouseEvent
import javax.swing.Icon

private const val WIDGET_ID = "io.github.eskibear.copilotcli.StatusBarWidget"

/**
 * Status bar widget that exposes Copilot CLI launch as a single-click icon on the bottom-right
 * status bar. Click behavior is identical to the Tools | Launch GitHub Copilot CLI action.
 *
 * Uses the modern [WidgetPresentationFactory] / [IconWidgetPresentation] API so the platform owns
 * the widget lifecycle. This avoids the deprecated `StatusBarWidget.getPresentation(PlatformType)`
 * code path that the older `StatusBarWidget` + `IconPresentation` pattern surfaces in plugin
 * verification.
 */
class CopilotCliStatusBarWidgetFactory : StatusBarWidgetFactory, WidgetPresentationFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "GitHub Copilot CLI"
    override fun isAvailable(project: Project): Boolean = true
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun createPresentation(
        context: WidgetPresentationDataContext,
        scope: CoroutineScope,
    ): WidgetPresentation = CopilotCliPresentation(context.project)
}

private class CopilotCliPresentation(private val project: Project) : IconWidgetPresentation {
    override fun icon(): Flow<Icon?> = flowOf(CopilotCliIcons.COPILOT)
    override suspend fun getTooltipText(): String = "Launch GitHub Copilot CLI"
    override fun getClickConsumer(): (MouseEvent) -> Unit = {
        CopilotCliLauncher.launchOrInstall(project)
    }
}
