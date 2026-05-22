package io.github.eskibear.copilotcli.bridge

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private val LOG = BridgeLog.forClass(Transport::class.java)

/**
 * Per-connection byte channel handed to the request handler thread.
 * Owner must close it when the connection is done.
 */
class Connection(
    val input: InputStream,
    val output: OutputStream,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() {
        try { input.close() } catch (_: Throwable) {}
        try { output.close() } catch (_: Throwable) {}
        try { onClose() } catch (_: Throwable) {}
    }
}

/**
 * Two-flavored transport. Mac/Linux uses AF_UNIX (NIO built in).
 * Windows uses a Named Pipe (JNA Kernel32). Both expose the same accept() API.
 */
sealed interface Transport : Closeable {
    /** Path as the CLI will see it in the lock file. */
    val socketPath: String

    /** Scheme as written to the lock file. */
    val scheme: String

    /**
     * Ensures the OS-level endpoint exists (named pipe instance / bound socket).
     * Idempotent. Throws if the endpoint cannot be bound, in which case the bridge
     * should refuse to publish a lock file.
     */
    fun bind()

    /** Blocks until a client connects. Returns null when the transport is closed. */
    fun accept(): Connection?

    companion object {
        fun create(): Transport = if (isWindows()) NamedPipeTransport() else UnixSocketTransport()

        private fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("win")
    }
}

private class UnixSocketTransport : Transport {
    private val tmpDir: Path = Files.createTempDirectory("copilot-mcp-")
    override val socketPath: String = tmpDir.resolve("mcp.sock").toString()
    override val scheme: String = "unix"

    private val channel: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
        it.bind(UnixDomainSocketAddress.of(socketPath))
    }

    init {
        runCatching { Files.setPosixFilePermissions(tmpDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
    }

    override fun bind() {
        // Already bound in `channel` property initializer.
    }

    override fun accept(): Connection? {
        val sock = try {
            channel.accept()
        } catch (e: java.nio.channels.AsynchronousCloseException) {
            return null
        } catch (e: java.nio.channels.ClosedChannelException) {
            return null
        }
        val input = Channels.newInputStream(sock)
        val output = Channels.newOutputStream(sock)
        return Connection(input, output) {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    override fun close() {
        try { channel.close() } catch (_: Throwable) {}
        try {
            Files.deleteIfExists(Path.of(socketPath))
            Files.deleteIfExists(tmpDir)
        } catch (_: Throwable) {}
    }
}

private class NamedPipeTransport : Transport {
    override val socketPath: String = "\\\\.\\pipe\\copilot-mcp-${UUID.randomUUID()}"
    override val scheme: String = "pipe"

    @Volatile private var closed = false
    // The first pipe instance is created in bind() (not in the property initializer)
    // so any JNA / classloader trouble surfaces as an exception from a method call we
    // can catch and report cleanly, rather than from constructor side-effects.
    @Volatile private var preBound: HANDLE? = null

    override fun bind() {
        if (preBound != null) return
        ensureJnaLoaded()
        preBound = createPipeInstance()
        LOG.info("NamedPipe bound: $socketPath")
    }

    /**
     * Forces JNA's native dispatch library to load via IntelliJ's own `JnaLoader`.
     * The IntelliJ Platform extracts and loads `jnidispatch.<ext>` to a known
     * location at startup, but plugin classloaders don't trigger this on their own:
     * first call to `Kernel32.INSTANCE` from a plugin throws
     * `UnsatisfiedLinkError: Unable to locate JNA native support library` because
     * the bundled JNA jar's resource lookup runs in the plugin's classloader scope
     * where the native lib isn't visible.
     */
    private fun ensureJnaLoaded() {
        // Reflective call so this file still compiles if the symbol moves; on a
        // healthy IntelliJ runtime this resolves and loads the native lib.
        try {
            val klass = Class.forName("com.intellij.jna.JnaLoader")
            val loaded = klass.getMethod("load").invoke(null) as? Boolean ?: false
            if (!loaded) {
                throw IllegalStateException("com.intellij.jna.JnaLoader.load() returned false")
            }
        } catch (e: ClassNotFoundException) {
            LOG.warn("com.intellij.jna.JnaLoader not found; trying direct JNA init", e)
        }
    }

    override fun accept(): Connection? {
        if (closed) return null
        val k = Kernel32.INSTANCE
        val pipe: HANDLE = preBound?.also { preBound = null } ?: createPipeInstance()
        val connected = k.ConnectNamedPipe(pipe, null)
        if (!connected) {
            val err = k.GetLastError()
            // ERROR_PIPE_CONNECTED (535) means a client connected between CreateNamedPipe and ConnectNamedPipe.
            // ERROR_NO_DATA (232) and ERROR_BROKEN_PIPE (109) also indicate "ready to use" or "client closed".
            if (err != 535 && err != 232) {
                k.CloseHandle(pipe)
                if (closed) return null
                throw IOException("ConnectNamedPipe failed: error=$err")
            }
        }
        val input = NamedPipeInputStream(pipe)
        val output = NamedPipeOutputStream(pipe)
        return Connection(input, output) {
            // Critical close sequence on Windows named pipes:
            // 1) FlushFileBuffers waits until the client has drained the pipe buffer.
            // 2) Read into a discard buffer until the client closes its end. This lets
            //    the HTTP client (undici / libuv) fully process the response and signal
            //    EOF itself, instead of us forcing it. Without this, the client often
            //    sees "socket hang up" because our CloseHandle races its response parse.
            // 3) Only then CloseHandle the server-side instance.
            // The drain read is done on a separate thread with a 5s timeout so a misbehaving
            // client cannot hang our accept loop's handler thread forever.
            try { k.FlushFileBuffers(pipe) } catch (_: Throwable) {}
            val drainer = Thread {
                try {
                    val drain = ByteArray(256)
                    val read = IntByReference()
                    k.ReadFile(pipe, drain, drain.size, read, null)
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            drainer.join(5_000)
            try { k.CloseHandle(pipe) } catch (_: Throwable) {}
            // CloseHandle should unblock any pending ReadFile; give the drainer thread a
            // brief moment to exit cleanly.
            if (drainer.isAlive) drainer.join(500)
        }
    }

    private fun createPipeInstance(): HANDLE {
        val k = Kernel32.INSTANCE
        // PIPE_TYPE_BYTE + PIPE_UNLIMITED_INSTANCES so we can keep accepting after each client.
        val pipe: HANDLE = k.CreateNamedPipe(
            socketPath,
            WinBase.PIPE_ACCESS_DUPLEX,
            WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT,
            WinBase.PIPE_UNLIMITED_INSTANCES,
            64 * 1024,
            64 * 1024,
            0,
            null,
        )
        if (WinBase.INVALID_HANDLE_VALUE == pipe) {
            val err = k.GetLastError()
            throw IOException("CreateNamedPipe failed: error=$err path=$socketPath")
        }
        return pipe
    }

    override fun close() {
        closed = true
        preBound?.let { try { Kernel32.INSTANCE.CloseHandle(it) } catch (_: Throwable) {} }
        preBound = null
        // Unblock pending ConnectNamedPipe by opening + immediately closing a client handle.
        try {
            val k = Kernel32.INSTANCE
            val client = k.CreateFile(
                socketPath,
                WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
                0,
                null,
                WinNT.OPEN_EXISTING,
                0,
                null,
            )
            if (client != WinBase.INVALID_HANDLE_VALUE) k.CloseHandle(client)
        } catch (_: Throwable) {}
    }
}

private class NamedPipeInputStream(private val handle: HANDLE) : InputStream() {
    private val k = Kernel32.INSTANCE
    private val one = ByteArray(1)

    override fun read(): Int {
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val read = IntByReference()
        val slice = if (off == 0 && len == b.size) b else ByteArray(len)
        val ok = k.ReadFile(handle, slice, len, read, null)
        if (!ok) {
            val err = k.GetLastError()
            // ERROR_BROKEN_PIPE (109) means clean EOF.
            return if (err == 109) -1 else throw IOException("ReadFile error=$err")
        }
        if (slice !== b) System.arraycopy(slice, 0, b, off, read.value)
        return if (read.value == 0) -1 else read.value
    }
}

private class NamedPipeOutputStream(private val handle: HANDLE) : OutputStream() {
    private val k = Kernel32.INSTANCE
    private val one = ByteArray(1)

    override fun write(b: Int) {
        one[0] = (b and 0xFF).toByte()
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val written = IntByReference()
        val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
        val ok = k.WriteFile(handle, slice, len, written, null)
        if (!ok) {
            val err = k.GetLastError()
            throw IOException("WriteFile error=$err")
        }
    }

    override fun flush() {
        try { k.FlushFileBuffers(handle) } catch (_: Throwable) {}
    }
}
