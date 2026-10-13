// Lives in com.jcraft.jsch on purpose: AgentConnector's query method takes a
// package-private-ish Buffer, and JSch's own implementations of this
// interface also live here.
package com.jcraft.jsch

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bridges JSch's AgentConnector interface onto a TCP socket.
 *
 * Termux runs `socat TCP-LISTEN:6000 → UNIX-CONNECT:$PREFIX/var/run/ssh-agent.socket`,
 * so we speak the SSH-agent framing (4-byte big-endian length, then payload)
 * to 127.0.0.1:6000. Bitwarden's private key never enters this process;
 * only signatures come back.
 */
class TcpAgentConnector(
    private val host: String,
    private val tcpPort: Int
) : AgentConnector {

    override fun getName(): String = "secrm-tcp"

    override fun isAvailable(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, tcpPort), 1500)
        }
        true
    } catch (_: Throwable) {
        false
    }

    override fun query(buf: Buffer) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, tcpPort), 3000)
            socket.soTimeout = 10_000

            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Pull the current request payload out of the buffer.
            val reqLen = buf.length
            val req = ByteArray(reqLen)
            buf.setOffSet(0)
            buf.getByte(req)

            // SSH-agent framing: 4-byte big-endian length prefix + payload.
            out.writeInt(reqLen)
            out.write(req)
            out.flush()

            // Read length-prefixed response.
            val respLen = input.readInt()
            if (respLen < 0 || respLen > (1 shl 20)) {
                throw AgentProxyException("Invalid agent response length: $respLen")
            }
            val resp = ByteArray(respLen)
            input.readFully(resp)

            // Replace buffer contents with the response. Both pointers back to 0,
            // then write the reply bytes so the caller reads from offset 0.
            buf.reset()
            buf.putByte(resp)
        } catch (t: Throwable) {
            throw AgentProxyException("Agent query failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { socket.close() }
        }
    }
}
