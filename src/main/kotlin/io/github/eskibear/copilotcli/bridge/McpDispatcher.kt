package io.github.eskibear.copilotcli.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

private val LOG = BridgeLog.forClass(McpDispatcher::class.java)

/**
 * Routes JSON-RPC requests to MCP handlers.
 *
 * Implements the minimal MCP surface needed for Copilot CLI to recognize the IDE
 * as connected. Tools are read-only context providers; mutating tools (open_diff,
 * close_diff) are stubbed out for the PoC.
 */
class McpDispatcher(private val tools: IdeToolHost) {

    private val protocolVersion = "2025-06-18"
    private val supportedProtocolVersions = setOf(
        "2024-11-05",
        "2025-03-26",
        "2025-06-18",
    )
    private val serverInfo = JsonObject().apply {
        addProperty("name", "IntelliJ Platform Copilot CLI Bridge")
        addProperty("version", "0.1.0")
    }

    /** Returns null for JSON-RPC notifications (which have no id). */
    fun dispatch(request: JsonObject): JsonObject? {
        val method = request.get("method")?.asString
        val id: JsonElement? = request.get("id")
        val isNotification = id == null
        val params: JsonObject = request.getAsJsonObject("params") ?: JsonObject()

        if (method == null) return error(id, -32600, "Invalid Request: missing method")

        return try {
            when (method) {
                "initialize" -> ok(id, initializeResult(params))
                "notifications/initialized", "notifications/cancelled" -> null
                "ping" -> ok(id, JsonObject())
                "tools/list" -> ok(id, JsonObject().apply { add("tools", tools.listTools()) })
                "tools/call" -> ok(id, tools.callTool(params))
                "resources/list" -> ok(id, JsonObject().apply { add("resources", JsonArray()) })
                "prompts/list" -> ok(id, JsonObject().apply { add("prompts", JsonArray()) })
                else -> {
                    LOG.debug("Unhandled MCP method: $method")
                    if (isNotification) null else error(id, -32601, "Method not found: $method")
                }
            }
        } catch (t: Throwable) {
            LOG.warn("MCP dispatch failure for $method", t)
            if (isNotification) null else error(id, -32603, "Internal error: ${t.message}")
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject = JsonObject().apply {
        val clientVersion = params.get("protocolVersion")?.asString
        val negotiated = if (clientVersion != null && clientVersion in supportedProtocolVersions) {
            clientVersion
        } else {
            protocolVersion
        }
        addProperty("protocolVersion", negotiated)
        add("serverInfo", serverInfo)
        add("capabilities", JsonObject().apply {
            add("tools", JsonObject())
        })
    }

    private fun ok(id: JsonElement?, result: JsonObject): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("result", result)
    }

    private fun error(id: JsonElement?, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }
}
