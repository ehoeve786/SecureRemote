package com.secrm.core.ssh

sealed interface SshAuth {
    data class Password(val password: String) : SshAuth
    data class Key(val pem: String, val passphrase: String?) : SshAuth

    /** TCP-based agent — the reliable cross-app path on modern Android. */
    data class Agent(val host: String = "127.0.0.1", val port: Int = 6000) : SshAuth

    /** Unix-socket agent — only works if the app can reach the socket path. */
    data class UnixAgent(val socketPath: String) : SshAuth
}
