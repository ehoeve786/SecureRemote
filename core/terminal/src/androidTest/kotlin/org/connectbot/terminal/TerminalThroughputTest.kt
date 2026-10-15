package org.connectbot.terminal

import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        val rows = t.snapshot.value.lines.map { it.text.trimEnd() }
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
                val n = minOf(BENCH_CHUNK, data.size - off)
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
                val n = minOf(BENCH_CHUNK, data.size - off)
                t.writeInput(data, off, n)
                off = if (off + n >= data.size) 0 else off + n
            }
        }
        // Key presses run on their own thread so a stalled press can be timed out instead of hanging the test.
        val keys = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val samples = ArrayList<Long>()
        var stalled = 0
        try {
            Thread.sleep(200)
            for (i in 0 until 2000) {
                val start = System.nanoTime()
                val press = keys.submit { t.dispatchCharacter(0, 'x'.code) }
                try {
                    press.get(KEY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    samples += (System.nanoTime() - start) / 1000
                } catch (e: TimeoutException) {
                    stalled++
                    break
                }
                Thread.sleep(1)
            }
        } finally {
            running.set(false)
            feeder.join(10_000)
            keys.shutdownNow()
        }
        samples.sort()
        Log.i(
            TAG,
            "BENCH key_samples=%d key_stalled=%d key_p50_us=%d key_p99_us=%d".format(
                samples.size, stalled,
                samples.getOrNull(samples.size / 2) ?: -1,
                samples.getOrNull(samples.size * 99 / 100) ?: -1,
            ),
        )
    }

    /**
     * The SSH reader loop: one emulator write per read, or one per burst when coalescing. A 32 KiB
     * buffer with coalescing is the new reader; the plain path matches the old 8 KiB reader.
     */
    private fun pump(input: InputStream, t: TerminalEmulatorImpl, coalesce: Boolean): Long {
        val buf = ByteArray(if (coalesce) 32 * 1024 else 8 * 1024)
        var writes = 0L
        while (true) {
            var n = input.read(buf)
            if (n < 0) break
            if (coalesce) while (n < buf.size && input.available() > 0) {
                val m = input.read(buf, n, buf.size - n)
                if (m < 0) break
                n += m
            }
            t.writeInput(buf, 0, n)
            writes++
        }
        return writes
    }

    /** Coalescing reader vs the old reader, fed 4 KiB pieces through a pipe, three runs each. */
    @Test
    fun readerCoalescing() {
        // Smaller than the throughput payload: each run keeps a full scrollback, and the test heap is small.
        val data = payload(4 shl 20)
        val coalesced = ArrayList<Double>()
        val plain = ArrayList<Double>()
        var coalescedWrites = 0L
        var plainWrites = 0L
        repeat(3) {
            for (coalesce in listOf(true, false)) {
                System.gc()
                val t = newTerminal()
                val pipeIn = PipedInputStream(64 * 1024)
                val pipeOut = PipedOutputStream(pipeIn)
                val writer = thread {
                    pipeOut.use { out ->
                        var off = 0
                        while (off < data.size) {
                            val n = minOf(4096, data.size - off)
                            out.write(data, off, n)
                            off += n
                        }
                    }
                }
                val start = System.nanoTime()
                val writes = pump(pipeIn, t, coalesce)
                t.processPendingUpdates()
                val seconds = (System.nanoTime() - start) / 1e9
                writer.join()
                pipeIn.close()
                if (coalesce) { coalesced += 4.0 / seconds; coalescedWrites = writes } else { plain += 4.0 / seconds; plainWrites = writes }
            }
        }
        fun List<Double>.median() = sorted()[size / 2]
        Log.i(
            TAG,
            "BENCH reader_coalesced_mib_s=%.2f writes=%d reader_plain_mib_s=%.2f writes=%d".format(
                coalesced.median(), coalescedWrites, plain.median(), plainWrites,
            ),
        )
    }

    /** Times [presses] key presses on [t], one at a time; returns the sorted samples in µs and the stall count. */
    private fun keyLatencies(t: TerminalEmulatorImpl, presses: Int): Pair<List<Long>, Int> {
        val keys = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val samples = ArrayList<Long>()
        var stalled = 0
        try {
            for (i in 0 until presses) {
                val start = System.nanoTime()
                val press = keys.submit { t.dispatchCharacter(0, 'x'.code) }
                try {
                    press.get(KEY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    samples += (System.nanoTime() - start) / 1000
                } catch (e: TimeoutException) {
                    stalled++
                    break
                }
                Thread.sleep(1)
            }
        } finally {
            keys.shutdownNow()
        }
        samples.sort()
        return samples to stalled
    }

    /**
     * Two terminals in one process, as with two open sessions: key presses on one, timed alone and then
     * while the other floods output. The emulators share TerminalDispatcher's worker pool.
     */
    @Test
    fun keyLatencyWithSecondTerminalFlooding() {
        val data = payload(8 shl 20)
        val keyed = newTerminal()
        val flooded = newTerminal()
        val (solo, soloStalled) = keyLatencies(keyed, presses = 500)

        val running = AtomicBoolean(true)
        val feeder = thread {
            var off = 0
            while (running.get()) {
                val n = minOf(BENCH_CHUNK, data.size - off)
                flooded.writeInput(data, off, n)
                off = if (off + n >= data.size) 0 else off + n
            }
        }
        val (shared, sharedStalled) = try {
            Thread.sleep(200)
            keyLatencies(keyed, presses = 500)
        } finally {
            running.set(false)
            feeder.join(10_000)
        }
        fun List<Long>.pct(p: Int) = getOrNull(size * p / 100) ?: -1
        Log.i(
            TAG,
            "BENCH two_term solo_p50_us=%d solo_p99_us=%d solo_stalled=%d shared_p50_us=%d shared_p99_us=%d shared_stalled=%d".format(
                solo.pct(50), solo.pct(99), soloStalled, shared.pct(50), shared.pct(99), sharedStalled,
            ),
        )
    }

    private companion object {
        const val TAG = "TerminalBench"
        const val BENCH_CHUNK = 32 * 1024
        const val KEY_TIMEOUT_MS = 5_000L
    }
}
