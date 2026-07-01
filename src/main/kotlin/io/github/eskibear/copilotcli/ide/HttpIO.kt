package io.github.eskibear.copilotcli.ide

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Minimal HTTP/1.1 request read from a socket / pipe stream. */
internal class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun bodyString(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * Just enough HTTP/1.1 to serve MCP over Streamable HTTP: request parsing, fixed-length JSON
 * responses, and chunked `text/event-stream` output for server -> client notifications.
 */
internal object HttpIO {

    fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return readRequest(input) // tolerate leading blank lines
        val parts = requestLine.split(" ")
        if (parts.size < 3) throw IOException("Malformed request line: $requestLine")
        val method = parts[0]
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val body = when {
            isChunked -> readChunkedBody(input)
            length > 0 -> readExactly(input, length)
            else -> EMPTY
        }
        val path = target.substringBefore('?')
        return HttpRequest(method, path, headers, body)
    }

    fun writeJson(output: OutputStream, status: Int, reason: String, json: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            for ((k, v) in extraHeaders) append(k).append(": ").append(v).append("\r\n")
            append("Connection: keep-alive\r\n\r\n")
        }
        synchronized(output) {
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    fun writeStatus(output: OutputStream, status: Int, reason: String, extraHeaders: Map<String, String> = emptyMap()) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Length: 0\r\n")
            for ((k, v) in extraHeaders) append(k).append(": ").append(v).append("\r\n")
            append("Connection: keep-alive\r\n\r\n")
        }
        synchronized(output) {
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }

    fun writeSseHeader(output: OutputStream) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Transfer-Encoding: chunked\r\n\r\n")
        }
        synchronized(output) {
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }

    fun writeChunk(output: OutputStream, data: String) {
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        synchronized(output) {
            output.write((Integer.toHexString(bytes.size) + "\r\n").toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        var sawByte = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (sawByte) buffer.asString() else null
            sawByte = true
            if (b == '\n'.code) break
            buffer.write(b)
        }
        val raw = buffer.toByteArray()
        var len = raw.size
        if (len > 0 && raw[len - 1] == '\r'.code.toByte()) len--
        return String(raw, 0, len, StandardCharsets.US_ASCII)
    }

    private fun ByteArrayOutputStream.asString(): String = toString(StandardCharsets.US_ASCII)

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n == -1) throw IOException("Unexpected EOF while reading body (got $read of $length)")
            read += n
        }
        return out
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            if (sizeLine.isEmpty()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // Consume optional trailers up to the terminating blank line.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactly(input, size))
            readLine(input) // trailing CRLF after each chunk
        }
        return out.toByteArray()
    }

    private val EMPTY = ByteArray(0)
}
