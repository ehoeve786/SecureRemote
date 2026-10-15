package com.secrm.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.server.filesystem.VirtualFileSystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

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
            setPort(port)
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser"))
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == user && p == password }
            fileSystemFactory = VirtualFileSystemFactory(root)
            subsystemFactories = listOf(SftpSubsystemFactory())
            shellFactory = ProcessShellFactory("/bin/sh", "-i")
        }
        server.start()
        ssh = SshClientManager()
    }

    @After fun stopServer() {
        ssh.shutdown()
        server.stop(true)
    }

    private fun config(pw: String = password) =
        SshConfig(host = "127.0.0.1", port = port, username = user, auth = SshAuth.Password(pw), connectTimeoutMs = 5_000)

    @Test fun connectsWithPasswordAndRunsShellCommands() = runBlocking {
        assertTrue(ssh.connect(config()))
        assertTrue(ssh.ensureShell())
        val shell = Pump(ssh.shellInputStream()!!)
        ssh.shellOutputStream()!!.apply { write("echo MARK\$((6*7))\n".toByteArray()); flush() }
        assertTrue("shell output", shell.awaitText("MARK42"))
    }

    @Test fun openingSftpLeavesTheTerminalSessionAlive() = runBlocking {
        assertTrue(ssh.connect(config()))
        assertTrue(ssh.ensureShell())
        val shell = Pump(ssh.shellInputStream()!!)
        val typed = ssh.shellOutputStream()!!
        val sftp = SftpClient(ssh)
        try {
            val home = sftp.home()
            assertTrue(sftp.list(home).any { it.name == "hello.txt" })
            val file = SftpClient.join(home, "hello.txt")
            val bytes = ByteArrayOutputStream().also { sftp.download(file, it) }
            assertEquals("hi there", bytes.toString(Charsets.UTF_8))

            typed.write("echo STILL\$((1+1))\n".toByteArray()); typed.flush()
            assertTrue("terminal must still answer after SFTP is used", shell.awaitText("STILL2"))
            assertTrue(ssh.activeSession()?.isConnected == true)
        } finally {
            sftp.close()
        }
    }

    @Test fun sftpWithoutAConnectionFailsCleanly() = runBlocking {
        assertThrows(IllegalStateException::class.java) { runBlocking { SftpClient(ssh).home() } }
    }

    @Test fun wrongPasswordReportsAnErrorState() = runBlocking {
        assertFalse(ssh.connect(config(pw = "nope")))
        assertTrue(ssh.state.value is SshState.Error)
        assertEquals(null, ssh.activeSession())
    }

    @Test fun reconnectingBumpsTheGenerationSoScreensReset() = runBlocking {
        val before = ssh.generation
        assertTrue(ssh.connect(config()))
        assertEquals(before + 1, ssh.generation)
        ssh.disconnect()
        assertEquals(null, ssh.activeSession())
    }
}
