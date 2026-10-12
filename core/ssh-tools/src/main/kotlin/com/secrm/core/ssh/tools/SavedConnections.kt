package com.secrm.core.ssh.tools

interface SavedConnections {
    data class Connection(
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val allowMcpExec: Boolean = true,
        val allowMcpRead: Boolean = true
    )
    fun list(): List<Connection>
    fun find(name: String): Connection?
}
