package io.github.eskibear.copilotcli.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Bridge selfcheck: starts a transport + dispatcher with stubbed tools and prints the
 * lock-file info so an external client (e.g. scripts/probe-bridge.js or `node -e ...`)
 * can connect. No IntelliJ project required.
 *
 * Run via: ./gradlew jvmRun --args="..." — easier to just invoke as java once classpath
 * is assembled. Here mostly so the bridge can be diagnosed end-to-end without a sandbox IDE.
 */
fun main() {
    val nonce = java.util.UUID.randomUUID().toString()
    val transport = Transport.create()
    transport.bind()
    println("Transport: scheme=${transport.scheme} socketPath=${transport.socketPath}")
    println("Auth header: Authorization: Nonce $nonce")
    println("Press Ctrl+C to stop.")

    val dispatcher = McpDispatcher(StubTools())
    val server = McpServer(nonce, dispatcher)

    Runtime.getRuntime().addShutdownHook(Thread { transport.close() })

    while (true) {
        val conn = transport.accept() ?: break
        Thread { server.handle(conn) }.apply { isDaemon = true; start() }
    }
}

private class StubTools : IdeToolHost {
    override fun listTools(): JsonArray = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("name", "echo")
            addProperty("description", "Echoes its input")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("text", JsonObject().apply { addProperty("type", "string") })
                })
            })
        })
    }
    override fun callTool(params: JsonObject): JsonObject {
        val name = params.get("name")?.asString
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        val text = if (name == "echo") (args.get("text")?.asString ?: "") else "unknown tool"
        return JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            })
            addProperty("isError", false)
        }
    }
}
