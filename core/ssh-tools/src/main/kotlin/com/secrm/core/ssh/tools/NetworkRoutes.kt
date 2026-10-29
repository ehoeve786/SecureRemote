package com.secrm.core.ssh.tools

import java.net.Inet4Address
import java.net.NetworkInterface

/** Stored in [SavedConnection.routeByNetwork] for a direct connection. */
internal const val DIRECT = "direct"

/** The phone's private IPv4 /24 on a physical interface, e.g. "192.168.8", or null if it has none. */
internal fun currentNetworkKey(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && !isTunnel(it.name) }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .map { it.hostAddress.orEmpty() }
    .filter(::isPrivateIpv4)
    .map { it.substringBeforeLast('.') }
    .sorted()
    .firstOrNull()

/** Whether this phone has an IPv4 address on the same /24 as [host]. Hostnames and other networks count as not local. */
internal fun onLocalNetwork(host: String): Boolean {
    val target = host.split('.').map { it.toIntOrNull() ?: return false }
    if (target.size != 4) return false
    return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .any { a -> a.address.take(3).map { it.toInt() and 0xff } == target.take(3) }
}

/** VPN and tunnel interfaces; their addresses say nothing about the local network. */
private fun isTunnel(name: String) = name.startsWith("tun") || name.startsWith("wg") || name.startsWith("tailscale")

internal fun isPrivateIpv4(ip: String): Boolean {
    val p = ip.split('.').map { it.toIntOrNull() ?: return false }
    return p.size == 4 && (p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31))
}

/**
 * Routes to try, in order; a null entry is a direct connection. A host with a set [via] has only that route.
 * Otherwise the default order is direct then [fallback], or [fallback] first off the host's own network. The
 * route that worked on this network before ([remembered], or [DIRECT]) goes first when it is one of those.
 */
internal fun orderRoutes(via: String?, fallback: String?, onHostNetwork: Boolean, remembered: String?): List<String?> {
    if (via != null) return listOf(via)
    val defaults: List<String?> = if (fallback != null && !onHostNetwork) listOf(fallback, null) else listOfNotNull(null, fallback)
    val wanted: String? = remembered?.let { if (it == DIRECT) null else it }
    return if (remembered != null && defaults.contains(wanted)) listOf(wanted) + defaults.filterNot { it == wanted }
    else defaults
}
