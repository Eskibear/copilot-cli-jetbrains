package io.github.eskibear.copilotcli.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Abstraction over the IDE-side tool registry so McpDispatcher can be used both
 * from inside an IntelliJ project (`IdeTools`) and from a standalone selfcheck
 * harness that has no `Project`.
 */
interface IdeToolHost {
    fun listTools(): JsonArray
    fun callTool(params: JsonObject): JsonObject
}
