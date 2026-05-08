package io.github.eskibear.copilotcli

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

private const val WIDGET_ID = "io.github.eskibear.copilotcli.StatusBarWidget"

/**
 * Status bar widget that exposes Copilot CLI launch as a single-click icon on the bottom-right
 * status bar. Click behavior is identical to the Tools | Launch GitHub Copilot CLI action.
 */
class CopilotCliStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "GitHub Copilot CLI"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = CopilotCliStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) { Disposer.dispose(widget) }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

private class CopilotCliStatusBarWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.IconPresentation, Disposable {

    override fun ID(): String = WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) { /* no-op */ }
    override fun dispose() { /* no-op */ }

    override fun getIcon(): Icon = CopilotCliIcons.COPILOT
    override fun getTooltipText(): String = "Launch GitHub Copilot CLI"

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        CopilotCliLauncher.launchOrInstall(project)
    }
}
