package com.secrm.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/** A shell that echoes what it reads: a real channel round trip without a process behind it. */
private class EchoShell : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var exit: ExitCallback? = null
    private var worker: Thread? = null

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { this.output = output }
    override fun setErrorStream(err: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        worker = thread(isDaemon = true) {
            val buf = ByteArray(256)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
            exit?.onExit(0)
        }
    }

    override fun destroy(channel: ChannelSession) { worker?.interrupt() }
}

/**
 * Runs the client against an in-process OpenSSH-compatible server (password auth, a shell and the
 * SFTP subsystem), so the same SSH session carries a terminal and SFTP at once, as on a real host.
 */
class SshIntegrationTest {
    private val user = "iq"
    private val password = "secret"
    private lateinit var server: SshServer
    private lateinit var root: Path
    private var port = 0
    private lateinit var ssh: SshClientManager

    /** Reads a stream on a background thread, so tests can wait for text without blocking. */
    private class Pump(input: InputStream) {
        private val out = StringBuilder()
        init {
            thread(isDaemon = true) {
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(out) { out.append(String(buf, 0, n)) }
                }
            }
        }
        fun text() = synchronized(out) { out.toString() }

        /** Fails with what the shell printed, so a miss is diagnosable from the test report. */
        fun expect(marker: String) {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !synchronized(out) { out.contains(marker) }) Thread.sleep(50)
            assertTrue("no \"$marker\" in shell output: ${text().replace("\n", " | ").take(1000)}", synchronized(out) { out.contains(marker) })
        }

        fun awaitText(marker: String, timeoutMs: Long = 10_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (synchronized(out) { out.contains(marker) }) return true
                Thread.sleep(50)
            }
            return false
        }
    }

    @Before fun startServer() {
        root = Files.createTempDirectory("sshd-root")
        Files.writeString(root.resolve("hello.txt"), "hi there")
        port = ServerSocket(0).use { it.localPort }
        server = SshServer.setUpDefaultServer().apply {
            setPort(this@SshIntegrationTest.port)
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser"))
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == user && p == password }
            fileSystemFactory = VirtualFileSystemFactory(root)
            subsystemFactories = listOf(SftpSubsystemFactory())
            shellFactory = ShellFactory { EchoShell() }
        }
        server.start()
        ssh = SshClientManager()
    }

    @After fun stopServer() {
        ssh.shutdown()
        server.stop(true)
    }

    /** Connects or fails with the client's own state and JSch's log, so the cause shows in the report. */
    private suspend fun connectOrFail() {
        val ok = ssh.connect(config())
        assertTrue("connect failed: ${ssh.state.value} :: ${ssh.recentLog().replace("\n", " | ").take(1500)}", ok)
    }

    private suspend fun shellOrFail() {
        val ok = ssh.ensureShell()
        assertTrue("shell did not open", ok)
    }

    /** The test server's own key, as the owner would approve it. */
    private suspend fun config(pw: String = password) = SshConfig(
        host = "127.0.0.1", port = port, username = user, auth = SshAuth.Password(pw), connectTimeoutMs = 5_000,
        hostKey = requireNotNull(ssh.scanHostKey("127.0.0.1", port)) { "test server gave no host key" },
    )

    @Test fun connectsWithPasswordAndRunsShellCommands() = runBlocking {
        connectOrFail()
        shellOrFail()
        val shell = Pump(ssh.shellInputStream()!!)
        ssh.shellOutputStream()!!.apply { write("MARK-42\n".toByteArray()); flush() }
        shell.expect("MARK-42")
    }

    @Test fun openingSftpLeavesTheTerminalSessionAlive() = runBlocking {
        connectOrFail()
        shellOrFail()
        val shell = Pump(ssh.shellInputStream()!!)
        val typed = ssh.shellOutputStream()!!
        val sftp = SftpClient(ssh)
        try {
            val home = sftp.home()
            assertTrue(sftp.list(home).any { it.name == "hello.txt" })
            val file = SftpClient.join(home, "hello.txt")
            val bytes = ByteArrayOutputStream().also { sftp.download(file, it) }
            assertEquals("hi there", bytes.toString(Charsets.UTF_8))

            typed.write("STILL-2\n".toByteArray()); typed.flush()
            shell.expect("STILL-2")
            assertTrue(ssh.activeSession()?.isConnected == true)
        } finally {
            sftp.close()
        }
    }

    @Test fun sftpWithoutAConnectionFailsCleanly() {
        assertThrows(IllegalStateException::class.java) { runBlocking { SftpClient(ssh).home() } }
    }

    @Test fun wrongPasswordReportsAnErrorState() = runBlocking {
        assertFalse(ssh.connect(config(pw = "nope")))
        assertTrue(ssh.state.value is SshState.Error)
        assertEquals(null, ssh.activeSession())
    }

    @Test fun reconnectingBumpsTheGenerationSoScreensReset() = runBlocking {
        val before = ssh.generation
        connectOrFail()
        assertEquals(before + 1, ssh.generation)
        ssh.disconnect()
        assertEquals(null, ssh.activeSession())
    }
}
