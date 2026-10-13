package com.secrm.core.ssh

sealed interface SshAuth {
    data class Password(val password: String) : SshAuth
    data class Key(val pem: String, val passphrase: String?) : SshAuth
}
