package io.github.besliky.airplaytv.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import io.github.besliky.airplaytv.Log
import io.github.besliky.airplaytv.Log.Category.SESSION
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Sends transport commands back to the sender over DACP, the remote-control protocol of iTunes, so
 * the TV remote can pause and skip what the phone plays.
 *
 * The sender announces its DACP service over mDNS as `iTunes_Ctrl_<DACP-ID>` and names that id in
 * its requests, together with a token every command must carry (Active-Remote). A command is a
 * plain `GET /ctrl-int/1/<command>`. The service is looked up when the identity arrives; a command
 * sent before it was found asks the network directly, since the system lookup of some TVs (TCL)
 * finds nothing after a reconnect while the sender answers at once. Main thread only.
 */
class DacpClient(context: Context) {

    enum class Command(val path: String) {
        /** A toggle: it works whatever the receiver believes the state to be. */
        PLAY_PAUSE("playpause"),
        NEXT("nextitem"),
        PREVIOUS("previtem"),
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "AirPlayRemote").apply { isDaemon = true } }

    private var serviceName: String? = null
    private var token: String? = null
    @Volatile
    private var address: InetSocketAddress? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val stopLookup = Runnable { stopDiscovery() }

    fun configure(dacpId: String, activeRemote: String) {
        val name = "iTunes_Ctrl_$dacpId"
        token = activeRemote
        if (name.equals(serviceName, ignoreCase = true) && (address != null || discovery != null)) return
        serviceName = name
        address = null
        startDiscovery()
        executor.execute { if (address == null) findDirectly(name) }
    }

    /** Forgets the sender at the end of a session. */
    fun clear() {
        stopDiscovery()
        serviceName = null
        token = null
        address = null
    }

    fun send(command: Command) {
        val activeRemote = token ?: return
        val name = serviceName ?: return
        executor.execute {
            val target = address ?: findDirectly(name)
            if (target == null) {
                Log.w(SESSION, "remote command ${command.path} dropped: the sender's remote service was not found yet")
                main.post { if (discovery == null && serviceName == name && address == null) startDiscovery() }
                return@execute
            }
            // A plain socket: HttpURLConnection refuses cleartext HTTP, and DACP is cleartext on the local
            // network by design. The app-wide cleartext policy stays strict.
            try {
                Socket().use { socket ->
                    socket.connect(target, TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS
                    val host = target.address.hostAddress
                    val request = "GET /ctrl-int/1/${command.path} HTTP/1.1\r\nHost: $host:${target.port}\r\n" +
                        "Active-Remote: $activeRemote\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                    Log.i(SESSION, "remote command ${command.path}: $status")
                }
            } catch (e: Exception) {
                Log.w(SESSION, "remote command ${command.path} failed", e)
            }
        }
    }

    fun shutdown() {
        clear()
        executor.shutdownNow()
    }

    private fun startDiscovery() {
        stopDiscovery()
        val wanted = serviceName ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.equals(wanted, ignoreCase = true)) main.post { resolve(info) }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(SESSION, "remote service lookup failed to start ($errorCode)")
                main.post { if (discovery === this) discovery = null }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
            main.postDelayed(stopLookup, LOOKUP_MS)
        } catch (e: RuntimeException) {
            Log.w(SESSION, "remote service lookup failed", e)
        }
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34
    private fun resolve(info: NsdServiceInfo) {
        if (!info.serviceName.equals(serviceName, ignoreCase = true)) return // the session ended meanwhile
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host ?: return
                    main.post {
                        if (!resolved.serviceName.equals(serviceName, ignoreCase = true)) return@post
                        address = InetSocketAddress(host, resolved.port)
                        Log.i(SESSION, "sender's remote service found")
                        stopDiscovery()
                    }
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    Log.w(SESSION, "remote service could not be resolved ($errorCode)")
                }
            })
        } catch (e: RuntimeException) {
            Log.w(SESSION, "remote service could not be resolved", e)
        }
    }

    /** [lookUpDirectly], keeping what it finds while the same sender is connected. Executor. */
    private fun findDirectly(name: String): InetSocketAddress? = lookUpDirectly(name)?.also { found ->
        main.post {
            if (serviceName == name && address == null) {
                address = found
                stopDiscovery()
            }
        }
    }

    /** Asks for the service's SRV record with a legacy unicast mDNS query, so the sender answers this socket. Executor. */
    private fun lookUpDirectly(name: String): InetSocketAddress? {
        val query = query(name)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = DIRECT_WAIT_MS
                val group = InetAddress.getByName(MDNS_GROUP)
                val buffer = ByteArray(1500)
                repeat(DIRECT_TRIES) {
                    socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                    try {
                        while (true) {
                            val reply = DatagramPacket(buffer, buffer.size)
                            socket.receive(reply)
                            val port = srvPort(buffer, reply.length, name)
                            if (port > 0) {
                                Log.i(SESSION, "sender's remote service found directly")
                                return InetSocketAddress(reply.address, port)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // ask again
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(SESSION, "direct remote service lookup failed", e)
        }
        return null
    }

    private fun stopDiscovery() {
        main.removeCallbacks(stopLookup)
        val listener = discovery ?: return
        discovery = null
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: RuntimeException) {
            // already stopped
        }
    }

    internal companion object {
        private const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val DIRECT_WAIT_MS = 1000
        private const val DIRECT_TRIES = 3
        private const val TYPE_SRV = 33

        /** An mDNS question for the SRV record of `<name>._dacp._tcp.local`. */
        fun query(name: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // one question
            for (label in "$name.$SERVICE_TYPE.local".split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(byteArrayOf(0, 0, TYPE_SRV.toByte(), 0, 1))
            return out.toByteArray()
        }

        /** The port of the SRV record for [name] in an mDNS reply, or 0. */
        fun srvPort(packet: ByteArray, length: Int, name: String): Int {
            if (length < 12) return 0
            val questions = u16(packet, 4)
            val records = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
            var at = 12
            repeat(questions) {
                val end = readName(packet, at, length, null)
                if (end < 0) return 0
                at = end + 4
            }
            repeat(records) {
                val owner = StringBuilder()
                val end = readName(packet, at, length, owner)
                if (end < 0 || end + 10 > length) return 0
                val data = end + 10
                val dataLength = u16(packet, end + 8)
                if (data + dataLength > length) return 0
                if (u16(packet, end) == TYPE_SRV && dataLength >= 6 &&
                    owner.startsWith("$name.", ignoreCase = true)) return u16(packet, data + 4)
                at = data + dataLength
            }
            return 0
        }

        /** Reads the (possibly compressed) name at [start] into [out]; returns where the record goes on, or -1. */
        private fun readName(packet: ByteArray, start: Int, length: Int, out: StringBuilder?): Int {
            var at = start
            var end = -1
            var jumps = 0
            while (at < length) {
                val size = packet[at].toInt() and 0xFF
                when {
                    size == 0 -> return if (end >= 0) end else at + 1
                    size and 0xC0 == 0xC0 -> {
                        if (at + 1 >= length || ++jumps > 16) return -1
                        if (end < 0) end = at + 2
                        at = ((size and 0x3F) shl 8) or (packet[at + 1].toInt() and 0xFF)
                    }
                    else -> {
                        if (at + 1 + size > length) return -1
                        out?.append(String(packet, at + 1, size, Charsets.UTF_8))?.append('.')
                        at += 1 + size
                    }
                }
            }
            return -1
        }

        private fun u16(packet: ByteArray, at: Int) =
            ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)

        const val SERVICE_TYPE = "_dacp._tcp"
        const val TIMEOUT_MS = 2000
        const val LOOKUP_MS = 30_000L
    }
}
