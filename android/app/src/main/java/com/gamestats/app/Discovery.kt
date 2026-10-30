package com.gamestats.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

data class Pc(val id: String, val name: String, val host: String, val port: Int)

object Discovery {
    private const val UDP_PORT = 8788

    /** Shouts "who is out there?" on the Wi-Fi and collects the answers from HubTools PC. */
    suspend fun scan(timeoutMs: Long = 1800): List<Pc> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Pc>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val msg = "HUBTOOLS?".toByteArray()
                val targets = linkedSetOf("255.255.255.255")
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    if (ni.isUp && !ni.isLoopback) {
                        ni.interfaceAddresses.forEach { ia -> ia.broadcast?.hostAddress?.let { targets.add(it) } }
                    }
                }
                val buf = ByteArray(1024)
                val end = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 600) {
                        for (t in targets) {
                            try {
                                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), UDP_PORT))
                            } catch (_: Exception) {
                            }
                        }
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val o = JSONObject(String(p.data, 0, p.length))
                        val id = o.optString("id")
                        val host = p.address?.hostAddress
                        if (id.isNotEmpty() && host != null) {
                            found[id] = Pc(id, o.optString("name", "PC"), host, o.optInt("port", 8787))
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        found.values.toList()
    }

    /** For typing the PC's address by hand. */
    suspend fun ping(host: String, port: Int = 8787): Pc? = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            try {
                if (c.responseCode != 200) {
                    null
                } else {
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    if (o.optString("app") != "hubtools") null
                    else Pc(o.optString("id"), o.optString("name", "PC"), host, port)
                }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Returns null when the code is right, otherwise a message to show. */
    suspend fun verify(host: String, port: Int, code: String): String? = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://$host:$port/auth").openConnection() as HttpURLConnection
            c.setRequestProperty("X-Code", code)
            c.connectTimeout = 3000
            c.readTimeout = 5000
            try {
                when (c.responseCode) {
                    200 -> null
                    401 -> "Wrong code. Check the code shown in the PC window."
                    429 -> "Too many wrong codes. Try again in 5 minutes."
                    else -> "The PC answered with an error."
                }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            "Can't reach the PC. Is HubTools PC still running?"
        }
    }
}
