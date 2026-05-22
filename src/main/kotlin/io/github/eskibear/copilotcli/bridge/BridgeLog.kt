package io.github.eskibear.copilotcli.bridge

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Tiny wrapper around `java.util.logging.Logger`. Avoids depending on IntelliJ's
 * `com.intellij.openapi.diagnostic.Logger` so the bridge classes are usable from
 * the standalone selfcheck harness as well as from inside the IDE.
 */
internal class BridgeLog(private val jul: Logger) {
    fun info(msg: String) { jul.info(msg) }
    fun warn(msg: String, t: Throwable? = null) { if (t != null) jul.log(Level.WARNING, msg, t) else jul.warning(msg) }
    fun debug(msg: String, t: Throwable? = null) { if (t != null) jul.log(Level.FINE, msg, t) else jul.fine(msg) }
    val isDebugEnabled: Boolean get() = jul.isLoggable(Level.FINE)

    companion object {
        fun forClass(cls: Class<*>): BridgeLog = BridgeLog(Logger.getLogger(cls.name))
    }
}
