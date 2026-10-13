package com.secrm.core.ssh.tools

import kotlinx.serialization.Serializable

@Serializable
data class SavedKey(
    val id: String,
    val name: String,
    val algorithm: String = "ssh-ed25519",
    val publicKey: String = "",
    val passphrase: String = "",
    // Private key is stored separately in EncryptedConfigStore under
    // "ssh.key.<id>.pem" so the list view never deserializes secrets.
)

enum class AuthMethod { PASSWORD, SAVED_KEY, AGENT }
