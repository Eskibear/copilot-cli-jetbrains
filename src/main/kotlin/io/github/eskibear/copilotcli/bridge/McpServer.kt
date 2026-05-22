package io.github.eskibear.copilotcli.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private val LOG = logger<McpServer>()

/**
 * Minimal HTTP/1.1 + MCP JSON-RPC server bound to a per-connection byte stream.
 *
 * Handles only what the Copilot CLI actually sends:
 *   POST   /mcp  -> single JSON-RPC request, single JSON-RPC response
 *   GET    /mcp  -> 405 (SSE push not implemented in this PoC)
 *   DELETE /mcp  -> 200 (session teardown)
 *
 * Authorization is checked against the nonce baked into the lock file.
 */
class McpServer(
    private val nonce: String,
    private val dispatcher: McpDispatcher,
) {

    fun handle(conn: Connection) {
        try {
            val request = parseRequest(conn.input) ?: return
            if (LOG.isDebugEnabled) {
                LOG.debug("${request.method} ${request.path} (auth=${request.headers["authorization"] != null}, body=${request.body.size}b)")
            }
            if (request.headers["authorization"]?.equals("Nonce $nonce", ignoreCase = false) != true) {
                writeResponse(conn.output, 401, "Unauthorized", "text/plain", "Unauthorized".toByteArray())
                return
            }
            when {
                request.method == "POST" && request.pathIs("/mcp") -> handlePost(conn.output, request)
                request.method == "GET" && request.pathIs("/mcp") ->
                    writeResponse(conn.output, 405, "Method Not Allowed", "text/plain", ByteArray(0))
                request.method == "DELETE" && request.pathIs("/mcp") ->
                    writeResponse(conn.output, 200, "OK", "text/plain", ByteArray(0))
                else -> writeResponse(conn.output, 404, "Not Found", "text/plain", ByteArray(0))
            }
        } catch (t: Throwable) {
            LOG.warn("HTTP handler error", t)
        } finally {
            conn.close()
        }
    }

    private fun handlePost(out: OutputStream, req: HttpRequest) {
        val sessionId = req.headers["mcp-session-id"] ?: java.util.UUID.randomUUID().toString()
        val payload = String(req.body, StandardCharsets.UTF_8)
        val parsed = try {
            JsonParser.parseString(payload)
        } catch (t: Throwable) {
            writeJsonRpcError(out, sessionId, null, -32700, "Parse error")
            return
        }
        if (parsed is com.google.gson.JsonArray) {
            // Batched requests; rare for our use, but handle.
            val responses = com.google.gson.JsonArray()
            for (el in parsed) {
                dispatcher.dispatch(el.asJsonObject)?.let(responses::add)
            }
            val body = responses.toString().toByteArray(StandardCharsets.UTF_8)
            writeResponse(out, 200, "OK", "application/json", body, mapOf("mcp-session-id" to sessionId))
            return
        }
        val req2 = parsed as JsonObject
        val response = dispatcher.dispatch(req2)
        if (response == null) {
            // Notification (no id) -> 202 Accepted, no body, per MCP Streamable HTTP.
            writeResponse(out, 202, "Accepted", "text/plain", ByteArray(0), mapOf("mcp-session-id" to sessionId))
            return
        }
        val body = response.toString().toByteArray(StandardCharsets.UTF_8)
        writeResponse(out, 200, "OK", "application/json", body, mapOf("mcp-session-id" to sessionId))
    }

    private fun writeJsonRpcError(out: OutputStream, sessionId: String, id: JsonElement?, code: Int, message: String) {
        val obj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id ?: com.google.gson.JsonNull.INSTANCE)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
        val body = obj.toString().toByteArray(StandardCharsets.UTF_8)
        writeResponse(out, 200, "OK", "application/json", body, mapOf("mcp-session-id" to sessionId))
    }
}

private data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun pathIs(p: String): Boolean = path == p || path.startsWith("$p?")
}

private fun parseRequest(rawIn: InputStream): HttpRequest? {
    val input = if (rawIn is BufferedInputStream) rawIn else BufferedInputStream(rawIn)
    val line = readLine(input) ?: return null
    val parts = line.split(' ')
    if (parts.size < 3) return null
    val method = parts[0]
    val path = parts[1]
    val headers = mutableMapOf<String, String>()
    while (true) {
        val h = readLine(input) ?: return null
        if (h.isEmpty()) break
        val idx = h.indexOf(':')
        if (idx <= 0) continue
        headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
    }
    val len = headers["content-length"]?.toIntOrNull() ?: 0
    val body = if (len > 0) input.readNBytesSafe(len) else ByteArray(0)
    return HttpRequest(method, path, headers, body)
}

private fun InputStream.readNBytesSafe(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = this.read(buf, off, n - off)
        if (r < 0) throw IOException("Unexpected EOF while reading body: got $off of $n")
        off += r
    }
    return buf
}

private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val c = input.read()
        if (c == -1) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\r'.code) {
            val next = input.read()
            if (next == '\n'.code) return sb.toString()
            if (next == -1) return sb.toString()
            sb.append('\r').append(next.toChar())
        } else if (c == '\n'.code) {
            return sb.toString()
        } else {
            sb.append(c.toChar())
        }
    }
}

private fun writeResponse(
    out: OutputStream,
    status: Int,
    reason: String,
    contentType: String,
    body: ByteArray,
    extraHeaders: Map<String, String> = emptyMap(),
) {
    val sb = StringBuilder()
    sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
    sb.append("Content-Type: ").append(contentType).append("\r\n")
    sb.append("Content-Length: ").append(body.size).append("\r\n")
    sb.append("Connection: close\r\n")
    for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
    sb.append("\r\n")
    val headerBytes = sb.toString().toByteArray(StandardCharsets.ISO_8859_1)
    // Single WriteFile is friendlier to named pipes than two separate writes; the client
    // reads response headers and body in one go without an intermediate buffering gap.
    val combined = ByteArray(headerBytes.size + body.size)
    System.arraycopy(headerBytes, 0, combined, 0, headerBytes.size)
    if (body.isNotEmpty()) System.arraycopy(body, 0, combined, headerBytes.size, body.size)
    out.write(combined)
    out.flush()
}
