package com.secrm.core.ssh.tools

import kotlinx.serialization.Serializable

@Serializable
data class SavedConnection(
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val keyId: String? = null,
    /** Private network to route through ("tailscale" or "wireguard"), or null for direct. */
    val via: String? = null,
    val allowMcpExec: Boolean = true,
    val allowMcpRead: Boolean = true
)

interface SavedConnections {
    fun list(): List<SavedConnection>
    fun find(name: String): SavedConnection?

    /**
     * Return the private key PEM for a saved key id, or null. Implemented by
     * the app module's KeyRepository; ssh-tools only needs the string.
     */
    fun privateKeyFor(keyId: String): String?

    /** Passphrase for a saved key id, or empty. */
    fun keyPassphraseFor(keyId: String): String?

    /** Private key of a Bitwarden SSH-key item, or null while the vault is locked. */
    fun bitwardenKeyFor(itemId: String): String?
}
