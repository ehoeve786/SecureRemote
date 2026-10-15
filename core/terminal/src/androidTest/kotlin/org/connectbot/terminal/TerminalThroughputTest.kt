package org.connectbot.terminal

import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Output throughput, key latency under output load, and screen correctness. Numbers are logged as
 * "BENCH ..." lines under tag TerminalBench; the CI job prints them as notices.
 */
@RunWith(AndroidJUnit4::class)
class TerminalThroughputTest {
    private fun newTerminal() = TerminalEmulatorImpl(Looper.getMainLooper(), initialRows = 24, initialCols = 80)

    /** About [bytes] of coloured, scrolling output. */
    private fun payload(bytes: Int): ByteArray {
        val sb = StringBuilder(bytes + 128)
        var i = 0
        while (sb.length < bytes) {
            sb.append("\u001b[3%dm%06d colored output line for throughput testing lorem ipsum dolor sit amet\u001b[0m\r\n".format(i % 7, i))
            i++
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    @Test
    fun screenMatchesOutput() {
        val t = newTerminal()
        val bytes = (0 until 100).joinToString("") { "row%04d\r\n".format(it) }.toByteArray(Charsets.US_ASCII)
        t.writeInput(bytes, 0, bytes.size)
        t.processPendingUpdates()
        val rows = t.snapshot.value.lines.map { it.cells.text().trimEnd() }
        // 100 lines in 24 rows: rows 77..99 stay on screen, and the cursor row below them is empty.
        assertEquals((77..99).map { "row%04d".format(it) } + "", rows)
    }

    @Test
    fun throughput() {
        val data = payload(8 shl 20)
        val seconds = (1..5).map {
            val t = newTerminal()
            val start = System.nanoTime()
            var off = 0
            while (off < data.size) {
                val n = minOf(4096, data.size - off)
                t.writeInput(data, off, n)
                off += n
            }
            t.processPendingUpdates()
            (System.nanoTime() - start) / 1e9
        }.sorted()
        val median = seconds[seconds.size / 2]
        Log.i(TAG, "BENCH throughput_mib_s=%.2f runs_s=%s".format(8.0 / median, seconds.joinToString(",") { "%.2f".format(it) }))
    }

    @Test
    fun keyLatencyUnderLoad() {
        val data = payload(8 shl 20)
        val t = newTerminal()
        val running = AtomicBoolean(true)
        // Keeps output flowing while the key presses are timed.
        val feeder = thread {
            var off = 0
            while (running.get()) {
                val n = minOf(4096, data.size - off)
                t.writeInput(data, off, n)
                off = if (off + n >= data.size) 0 else off + n
            }
        }
        val samples = LongArray(2000)
        try {
            Thread.sleep(200)
            for (i in samples.indices) {
                val start = System.nanoTime()
                t.dispatchCharacter(0, 'x'.code)
                samples[i] = System.nanoTime() - start
                Thread.sleep(1)
            }
        } finally {
            running.set(false)
            feeder.join()
        }
        samples.sort()
        Log.i(TAG, "BENCH key_p50_us=%d key_p99_us=%d".format(samples[1000] / 1000, samples[1980] / 1000))
    }

    private companion object {
        const val TAG = "TerminalBench"
    }
}
