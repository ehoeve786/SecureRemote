package com.secrm.plugin

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue

private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()
private val RED = "\u001b[31m".toByteArray()
private val RESET = "\u001b[0m".toByteArray()
private const val MAX_QUEUED_BYTES = 1 shl 20
private val TRUNCATED_NOTE = "\r\n[output truncated: the terminal was not open to read it]\r\n".toByteArray()

/**
 * A running plugin command's output, for a live terminal to read. Holds up to [MAX_QUEUED_BYTES] until it is
 * read, so a terminal opened late still shows the start; output past that is dropped. [finish] ends it.
 */
class LiveOutput : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    // Bytes waiting in [chunks]. Output past the cap is dropped, and one note says so, so a command that
    // writes a lot while nobody is watching cannot grow the app's memory without limit.
    private val queuedBytes = AtomicInteger()
    private var truncated = false
    private var current = ByteArray(0)
    private var pos = 0
    private var done = false

    /** Where the command's output goes: LF becomes CRLF for the terminal, and [red] shows it in red. */
    fun sink(red: Boolean): OutputStream = object : OutputStream() {
        /** Whether the last byte written was a CR, which may be the one before an LF in the next chunk. */
        private var lastCr = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val o = ByteArrayOutputStream(len + 16)
            if (red) o.write(RED)
            for (i in off until off + len) {
                val afterCr = if (i == off) lastCr else b[i - 1] == CR
                if (b[i] == LF && !afterCr) o.write(CR.toInt())
                o.write(b[i].toInt())
            }
            lastCr = b[off + len - 1] == CR
            if (red) o.write(RESET)
            offer(o.toByteArray())
        }
    }

    @Synchronized
    private fun offer(bytes: ByteArray) {
        if (queuedBytes.get() + bytes.size > MAX_QUEUED_BYTES) {
            if (!truncated) {
                truncated = true
                chunks.put(TRUNCATED_NOTE)
            }
            return
        }
        queuedBytes.addAndGet(bytes.size)
        chunks.put(bytes)
    }

    /** Ends the output once everything written so far has been read. */
    fun finish() { chunks.put(ByteArray(0)) }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= current.size) {
            if (done) return -1
            current = chunks.take()
            queuedBytes.addAndGet(-current.size)
            pos = 0
            if (current.isEmpty()) { done = true; return -1 }
        }
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }
}

/** Live outputs of the commands running now, by id, so the terminal screen can find its one. */
object LiveCommands {
    private val running = ConcurrentHashMap<String, LiveOutput>()

    fun add(id: String, output: LiveOutput) { running[id] = output }

    /** Hands the output to a terminal screen; it is no longer offered after this. */
    fun take(id: String): LiveOutput? = running.remove(id)

    fun remove(id: String) { running.remove(id) }
}
