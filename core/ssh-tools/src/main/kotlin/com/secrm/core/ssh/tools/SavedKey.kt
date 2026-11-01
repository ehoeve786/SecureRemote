package com.secrm.core.ssh.tools

import kotlinx.serialization.Serializable

@Serializable
data class SavedKey(
    val id: String,
    val name: String,
    val algorithm: String = "ssh-ed25519",
    val publicKey: String = "",
    val passphrase: String = "",
    // The private key is stored separately under "ssh.key.<id>.pem". This whole record, passphrase
    // included, sits in the encrypted key list, which KeyRepository.list() decodes on every call.
)

enum class AuthMethod { PASSWORD, SAVED_KEY }
