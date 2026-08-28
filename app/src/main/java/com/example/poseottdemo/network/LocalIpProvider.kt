package com.example.poseottdemo.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIpProvider {
    fun getLocalIpv4Address(context: Context): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 查找Wi-Fi或Ethernet
        try {
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isWifi && !isEthernet) { continue }
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                for (linkAddress in linkProperties.linkAddresses)
                {
                    val address = linkAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                    ) { return address.hostAddress }
                }
            }
        } catch (
            e: Exception
        ) {
            e.printStackTrace()
        }
        // 如果 ConnectivityManager 没拿到， 再直接扫描网卡
        return getFallbackIpv4()
    }

    private fun getFallbackIpv4(): String? {
        return try {
            val candidates = mutableListOf<Pair<String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

            while (interfaces.hasMoreElements())
            {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) { continue }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements())
                { val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                    ) {
                        val host = address.hostAddress ?: continue
                        candidates.add(networkInterface.name to host)
                    }
                }
            }

            // 优先wlan，其次eth
            candidates.sortedBy {
                    val interfaceName = it.first.lowercase()
                    when {
                        interfaceName.startsWith("wlan") -> 0
                        interfaceName.startsWith("eth") -> 1
                        else -> 2
                    }
                }.firstOrNull()?.second
        } catch (
            e: Exception
        ) {
            e.printStackTrace()
            null
        }
    }
}