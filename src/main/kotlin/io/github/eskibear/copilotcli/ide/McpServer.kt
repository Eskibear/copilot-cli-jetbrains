package io.github.eskibear.copilotcli.ide

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val LOG = logger<McpServer>()

/**
 * MCP server (IDE = server, CLI = client) over Streamable HTTP.
 *
 * Handles `initialize`, `tools/list`, `tools/call`, session id issuance, the auth nonce, the
 * long-lived `GET /mcp` SSE stream used for server -> client notifications, and `DELETE`
 * teardown. One instance serves a single project.
 */
internal class McpServer(
    private val host: IdeToolHost,
    private val authNonce: String,
    private val serverName: String,
    private val serverVersion: String,
) {
    @Volatile private var running = true
    private val sseChannels = CopyOnWriteArrayList<SseChannel>()

    fun stop() {
        running = false
        for (channel in sseChannels) channel.close()
        sseChannels.clear()
    }

    /** Serves one accepted connection until the client disconnects. */
    fun serve(input: InputStream, output: OutputStream) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        while (running) {
            val request = try {
                HttpIO.readRequest(buffered)
            } catch (t: Throwable) {
                LOG.debug("Failed to read IDE MCP request", t)
                null
            } ?: return

            if (!running) return

            if (request.path != MCP_PATH) {
                HttpIO.writeStatus(output, 404, "Not Found")
                continue
            }
            if (!authorized(request)) {
                HttpIO.writeStatus(output, 401, "Unauthorized")
                continue
            }

            when (request.method) {
                "POST" -> handlePost(request, output)
                "GET" -> {
                    handleSse(output)
                    return // the SSE stream owns the connection for its lifetime
                }
                "DELETE" -> HttpIO.writeStatus(output, 200, "OK")
                "OPTIONS" -> HttpIO.writeStatus(output, 200, "OK")
                else -> HttpIO.writeStatus(output, 405, "Method Not Allowed")
            }
        }
    }

    /** Pushes a JSON-RPC notification to every connected SSE stream. */
    fun broadcast(method: String, params: JsonElement) {
        if (sseChannels.isEmpty()) return
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
        val serialized = ideGson.toJson(message)
        for (channel in sseChannels) channel.enqueue(serialized)
    }

    private fun authorized(request: HttpRequest): Boolean {
        val provided = request.header("authorization") ?: return false
        return provided == authNonce
    }

    private fun handlePost(request: HttpRequest, output: OutputStream) {
        val parsed = try {
            JsonParser.parseString(request.bodyString())
        } catch (t: Throwable) {
            HttpIO.writeJson(output, 400, "Bad Request", errorEnvelope(null, -32700, "Parse error"))
            return
        }
        when {
            parsed.isJsonObject -> handleRpc(parsed.asJsonObject, output)
            else -> {
                // The MCP client sends one JSON-RPC message per POST; batches are not used here.
                // Acknowledge without writing multiple responses onto the same connection.
                HttpIO.writeStatus(output, 202, "Accepted")
            }
        }
    }

    private fun handleRpc(message: JsonObject, output: OutputStream) {
        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = message.get("id")
        val hasId = id != null && !id.isJsonNull

        if (method == null) {
            if (hasId) HttpIO.writeJson(output, 200, "OK", errorEnvelope(id, -32600, "Invalid Request"))
            else HttpIO.writeStatus(output, 202, "Accepted")
            return
        }

        when (method) {
            "initialize" -> {
                val sessionId = shortId()
                HttpIO.writeJson(
                    output,
                    200,
                    "OK",
                    resultEnvelope(id, initializeResult(message)),
                    extraHeaders = mapOf("mcp-session-id" to sessionId),
                )
            }

            "notifications/initialized", "notifications/cancelled" -> HttpIO.writeStatus(output, 202, "Accepted")

            "ping" -> HttpIO.writeJson(output, 200, "OK", resultEnvelope(id, JsonObject()))

            "tools/list" -> HttpIO.writeJson(output, 200, "OK", resultEnvelope(id, toolsListResult()))

            "tools/call" -> {
                val result = try {
                    callTool(message.getAsJsonObject("params"))
                } catch (t: Throwable) {
                    LOG.warn("IDE tool call failed", t)
                    toolTextResult("Tool call failed: ${t.message}", isError = true)
                }
                HttpIO.writeJson(output, 200, "OK", resultEnvelope(id, result))
            }

            else -> {
                if (hasId) HttpIO.writeJson(output, 200, "OK", errorEnvelope(id, -32601, "Method not found: $method"))
                else HttpIO.writeStatus(output, 202, "Accepted")
            }
        }
    }

    private fun callTool(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.asString ?: return toolTextResult("Missing tool name", isError = true)
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        return when (name) {
            "get_diagnostics" -> {
                val uri = args.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
                toolTextResult(ideGson.toJson(host.getDiagnostics(uri)))
            }

            "get_selection" -> {
                val selection = host.getSelection()
                toolTextResult(if (selection == null) "null" else ideGson.toJson(selection))
            }

            "open_diff" -> {
                val original = args.get("original_file_path")?.asString ?: ""
                val contents = args.get("new_file_contents")?.asString ?: ""
                val tabName = args.get("tab_name")?.asString ?: ""
                toolTextResult(ideGson.toJson(host.openDiff(original, contents, tabName)))
            }

            "close_diff" -> {
                val tabName = args.get("tab_name")?.asString ?: ""
                toolTextResult(ideGson.toJson(host.closeDiff(tabName)))
            }

            "update_session_name" -> {
                val newName = args.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val ok = host.updateSessionName(newName)
                val payload = JsonObject().apply {
                    addProperty("success", ok)
                    if (!ok) addProperty("error", "Session name cannot be empty")
                }
                toolTextResult(ideGson.toJson(payload))
            }

            else -> toolTextResult("Unknown tool: $name", isError = true)
        }
    }

    private fun initializeResult(request: JsonObject): JsonObject {
        val requestedVersion = request.getAsJsonObject("params")
            ?.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
            ?: PROTOCOL_VERSION
        return JsonObject().apply {
            addProperty("protocolVersion", requestedVersion)
            add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
            add("serverInfo", JsonObject().apply {
                addProperty("name", serverName)
                addProperty("version", serverVersion)
            })
        }
    }

    private fun toolsListResult(): JsonObject {
        val tools = JsonArray()
        tools.add(tool("get_diagnostics", "Get language-server diagnostics for open files.", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("uri", stringProp("Optional file URI to filter diagnostics to a single file."))
            })
        }))
        tools.add(tool("get_selection", "Get the current selection in the active editor.", emptyObjectSchema()))
        tools.add(tool("open_diff", "Open a diff for the given file and block until the user accepts or rejects it.", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("original_file_path", stringProp("Absolute path of the file being edited."))
                add("new_file_contents", stringProp("Proposed new contents of the file."))
                add("tab_name", stringProp("Title for the diff tab."))
            })
            add("required", JsonArray().apply {
                add("original_file_path"); add("new_file_contents"); add("tab_name")
            })
        }))
        tools.add(tool("close_diff", "Close a previously opened diff.", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { add("tab_name", stringProp("Title of the diff tab to close.")) })
            add("required", JsonArray().apply { add("tab_name") })
        }))
        tools.add(tool("update_session_name", "Update the displayed Copilot CLI session name.", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { add("name", stringProp("The session name to display.")) })
            add("required", JsonArray().apply { add("name") })
        }))
        return JsonObject().apply { add("tools", tools) }
    }

    private fun handleSse(output: OutputStream) {
        val channel = SseChannel()
        sseChannels.add(channel)
        try {
            HttpIO.writeSseHeader(output)
            while (running && channel.open) {
                val message = channel.queue.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS)
                if (!running || !channel.open) break
                when {
                    message == null -> HttpIO.writeChunk(output, ": keepalive\n\n")
                    message.isNotEmpty() -> HttpIO.writeChunk(output, "event: message\ndata: $message\n\n")
                }
            }
        } catch (t: Throwable) {
            LOG.debug("IDE MCP SSE stream closed", t)
        } finally {
            channel.close()
            sseChannels.remove(channel)
        }
    }

    private fun tool(name: String, description: String, inputSchema: JsonObject): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("inputSchema", inputSchema)
        }

    private fun emptyObjectSchema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    private fun stringProp(description: String): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    }

    private fun toolTextResult(text: String, isError: Boolean = false): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        if (isError) addProperty("isError", true)
    }

    private fun resultEnvelope(id: JsonElement?, result: JsonElement): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("result", result)
    }.let { ideGson.toJson(it) }

    private fun errorEnvelope(id: JsonElement?, code: Int, message: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }.let { ideGson.toJson(it) }

    private class SseChannel {
        val queue = LinkedBlockingQueue<String>()
        @Volatile var open = true
            private set

        fun enqueue(message: String) {
            if (open) queue.offer(message)
        }

        fun close() {
            open = false
            queue.offer("") // wake the poll so the SSE loop notices promptly
        }
    }

    private companion object {
        const val MCP_PATH = "/mcp"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val KEEPALIVE_SECONDS = 15L
    }
}
