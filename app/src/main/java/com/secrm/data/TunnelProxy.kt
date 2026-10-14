package com.secrm.data

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import com.secrm.gonet.gen.srnet.Conn
import java.io.InputStream
import java.io.OutputStream

/** JSch [Proxy] over a tunnel connection opened in Go, after Tidegate's TunnelProxy. */
class TunnelProxy(private val dial: (String, Int) -> Conn) : Proxy {
    private var conn: Conn? = null

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) { conn = dial(host, port) }
    override fun getSocket(): java.net.Socket? = null
    override fun close() { runCatching { conn?.close() } }

    override fun getInputStream(): InputStream = object : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buf.size) {
                // Go returns io.EOF (as an exception) when the connection ends.
                buf = runCatching { conn!!.read(maxOf(len, 8192).toLong()) }.getOrNull() ?: return -1
                pos = 0
            }
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = conn!!.write(b.copyOfRange(off, off + len))
    }
}
