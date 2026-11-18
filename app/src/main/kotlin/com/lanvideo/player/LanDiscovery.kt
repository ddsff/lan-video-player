package com.lanvideo.player

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal object LanDiscovery {
    private const val SMB_PORT = 445
    private const val CONNECT_TIMEOUT_MS = 450
    private const val WORKERS = 24

    class NoLocalNetwork : IOException()

    fun scan(connectivity: ConnectivityManager): List<String> {
        val active = connectivity.activeNetwork
        @Suppress("DEPRECATION")
        val networks = (listOfNotNull(active) + connectivity.allNetworks).distinct()
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue

            val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            for (link in addresses) {
                val address = link.address as? Inet4Address ?: continue
                if (!address.isSiteLocalAddress && !address.isLinkLocalAddress) continue
                return probe(network, address, link.prefixLength)
            }
        }
        throw NoLocalNetwork()
    }

    private fun probe(network: Network, address: Inet4Address, prefix: Int): List<String> {
        val own = address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        // On a large subnet, bound each scan to the nearby 1,024 addresses.
        val hostMask = (1L shl (32 - maxOf(prefix, 22))) - 1
        val first = own and hostMask.inv()
        val last = first + hostMask
        val subnetMask = (1L shl (32 - prefix)) - 1
        val subnetStart = own and subnetMask.inv()
        val next = AtomicLong(if (prefix <= 30) maxOf(first, subnetStart + 1) else first)
        val end = if (prefix <= 30) minOf(last, subnetStart + subnetMask - 1) else last
        val workers = Executors.newFixedThreadPool(WORKERS)
        try {
            return workers.invokeAll(List(WORKERS) {
                Callable {
                    val matches = mutableListOf<Long>()
                    while (!Thread.currentThread().isInterrupted) {
                        val candidate = next.getAndIncrement()
                        if (candidate > end) break
                        if (candidate != own && canConnect(network, candidate)) matches.add(candidate)
                    }
                    matches
                }
            }).flatMap { it.get() }.sorted().map { ipv4(it).hostAddress.orEmpty() }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun canConnect(network: Network, candidate: Long): Boolean = try {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(ipv4(candidate), SMB_PORT), CONNECT_TIMEOUT_MS)
        }
        true
    } catch (_: IOException) {
        false
    }

    private fun ipv4(value: Long): InetAddress = InetAddress.getByAddress(
        ByteArray(4) { index -> (value shr (24 - 8 * index)).toByte() }
    )
}
