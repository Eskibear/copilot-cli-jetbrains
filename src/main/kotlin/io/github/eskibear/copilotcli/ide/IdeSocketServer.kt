package io.github.eskibear.copilotcli.ide

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private val LOG = logger<IdeSocketServer>()

/**
 * A byte-stream server the Copilot CLI connects to. The CLI speaks MCP over Streamable HTTP
 * to whatever path we advertise in the lock file:
 *  - Unix domain socket on macOS / Linux,
 *  - Windows named pipe on Windows.
 *
 * [handler] is invoked once per accepted connection on a dedicated thread and owns the streams
 * until the client disconnects.
 */
internal interface IdeSocketServer {
    /** Path advertised in the lock file (`socketPath`). */
    val socketPath: String

    /** `"unix"` or `"pipe"`. */
    val scheme: String

    fun start(handler: (InputStream, OutputStream) -> Unit)

    fun stop()

    companion object {
        fun create(): IdeSocketServer =
            if (SystemInfo.isWindows) WindowsNamedPipeServer() else UnixDomainSocketServer()
    }
}

/** Unix domain socket transport (Java NIO, blocking mode). */
private class UnixDomainSocketServer : IdeSocketServer {
    override val scheme: String = "unix"

    private val path: Path = run {
        val dir = Files.createTempDirectory("copilot-ide-${shortId()}")
        dir.toFile().deleteOnExit()
        dir.resolve("mcp.sock")
    }
    override val socketPath: String = path.toString()

    @Volatile private var running = false
    private var serverChannel: ServerSocketChannel? = null
    private val connections = ConcurrentHashMap.newKeySet<SocketChannel>()

    override fun start(handler: (InputStream, OutputStream) -> Unit) {
        Files.deleteIfExists(path)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(path))
        serverChannel = channel
        running = true
        thread(name = "copilot-ide-mcp-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    channel.accept()
                } catch (t: Throwable) {
                    if (running) LOG.debug("Unix socket accept stopped", t)
                    break
                }
                connections.add(client)
                thread(name = "copilot-ide-mcp-conn", isDaemon = true) {
                    try {
                        handler(Channels.newInputStream(client), Channels.newOutputStream(client))
                    } catch (t: Throwable) {
                        LOG.debug("IDE MCP connection ended", t)
                    } finally {
                        if (connections.remove(client)) {
                            try {
                                client.close()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
        LOG.info("Copilot IDE MCP server listening on unix socket $socketPath")
    }

    override fun stop() {
        running = false
        try {
            serverChannel?.close()
        } catch (_: Throwable) {
        }
        for (client in connections) {
            if (connections.remove(client)) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
        try {
            Files.deleteIfExists(path)
            path.parent?.let { Files.deleteIfExists(it) }
        } catch (_: Throwable) {
        }
    }
}

/**
 * Windows named pipe transport. The JVM has no native named-pipe server support, so we call the
 * Win32 pipe APIs through the JNA build that ships with the IntelliJ platform.
 *
 * Classic multi-instance server loop: create a pipe instance, block in [Kernel32.ConnectNamedPipe]
 * until a client connects, hand the instance to a worker thread, then create the next instance.
 */
private class WindowsNamedPipeServer : IdeSocketServer {
    override val scheme: String = "pipe"
    override val socketPath: String = """\\.\pipe\copilot-cli-ide-${shortId()}"""

    @Volatile private var running = false
    private val handles = ConcurrentHashMap.newKeySet<HANDLE>()

    override fun start(handler: (InputStream, OutputStream) -> Unit) {
        running = true
        thread(name = "copilot-ide-pipe-accept", isDaemon = true) {
            while (running) {
                val pipe = Kernel32.INSTANCE.CreateNamedPipe(
                    socketPath,
                    WinBase.PIPE_ACCESS_DUPLEX,
                    WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT,
                    WinBase.PIPE_UNLIMITED_INSTANCES,
                    BUFFER_SIZE,
                    BUFFER_SIZE,
                    0,
                    null,
                )
                if (pipe == WinBase.INVALID_HANDLE_VALUE) {
                    if (running) LOG.warn("CreateNamedPipe failed err=${Kernel32.INSTANCE.GetLastError()}")
                    break
                }
                val connected = Kernel32.INSTANCE.ConnectNamedPipe(pipe, null)
                if (!running) {
                    Kernel32.INSTANCE.CloseHandle(pipe)
                    break
                }
                val ok = connected || Kernel32.INSTANCE.GetLastError() == WinError.ERROR_PIPE_CONNECTED
                if (!ok) {
                    Kernel32.INSTANCE.CloseHandle(pipe)
                    continue
                }
                handles.add(pipe)
                thread(name = "copilot-ide-pipe-conn", isDaemon = true) {
                    try {
                        handler(PipeInputStream(pipe), PipeOutputStream(pipe))
                    } catch (t: Throwable) {
                        LOG.debug("IDE MCP pipe connection ended", t)
                    } finally {
                        if (handles.remove(pipe)) closePipe(pipe)
                    }
                }
            }
        }
        LOG.info("Copilot IDE MCP server listening on named pipe $socketPath")
    }

    override fun stop() {
        running = false
        // Unblock the pending ConnectNamedPipe by connecting to our own pipe as a client.
        try {
            val client = Kernel32.INSTANCE.CreateFile(
                socketPath,
                WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
                0,
                null,
                WinNT.OPEN_EXISTING,
                0,
                null,
            )
            if (client != WinBase.INVALID_HANDLE_VALUE) {
                Kernel32.INSTANCE.CloseHandle(client)
            }
        } catch (_: Throwable) {
        }
        // Close any live connections so their blocked worker threads unwind.
        for (pipe in handles) {
            if (handles.remove(pipe)) closePipe(pipe)
        }
    }

    private fun closePipe(pipe: HANDLE) {
        try {
            Kernel32.INSTANCE.DisconnectNamedPipe(pipe)
        } catch (_: Throwable) {
        }
        try {
            Kernel32.INSTANCE.CloseHandle(pipe)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

private class PipeInputStream(private val handle: HANDLE) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val buffer = if (off == 0 && len == b.size) b else ByteArray(len)
        val readCount = IntByReference()
        val ok = Kernel32.INSTANCE.ReadFile(handle, buffer, len, readCount, null)
        if (!ok) {
            val err = Kernel32.INSTANCE.GetLastError()
            if (err == WinError.ERROR_BROKEN_PIPE || err == ERROR_PIPE_NOT_CONNECTED || err == ERROR_NO_DATA) {
                return -1
            }
            throw IOException("ReadFile failed err=$err")
        }
        val n = readCount.value
        if (n <= 0) return -1
        if (buffer !== b) System.arraycopy(buffer, 0, b, off, n)
        return n
    }

    private companion object {
        const val ERROR_PIPE_NOT_CONNECTED = 233
        const val ERROR_NO_DATA = 232
    }
}

private class PipeOutputStream(private val handle: HANDLE) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val payload = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
        var written = 0
        while (written < len) {
            val chunk = if (written == 0) payload else payload.copyOfRange(written, len)
            val writeCount = IntByReference()
            val ok = Kernel32.INSTANCE.WriteFile(handle, chunk, len - written, writeCount, null)
            if (!ok) throw IOException("WriteFile failed err=${Kernel32.INSTANCE.GetLastError()}")
            if (writeCount.value <= 0) throw IOException("WriteFile wrote 0 bytes")
            written += writeCount.value
        }
    }

    // FlushFileBuffers on a pipe blocks until the peer drains everything, which would stall the
    // long-lived SSE stream. WriteFile already delivers the bytes, so flush is a no-op.
    override fun flush() {}
}

internal fun shortId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
