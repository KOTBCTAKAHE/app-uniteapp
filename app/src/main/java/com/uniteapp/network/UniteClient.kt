package com.uniteapp.network

import android.util.Log
import com.uniteapp.protocol.Constants
import com.uniteapp.protocol.Packet
import com.uniteapp.protocol.PeerInfo
import com.uniteapp.protocol.WelcomeData
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Network client implementing the UNITEAPP protocol.
 * Handles TCP control plane and UDP data plane.
 */
class UniteClient(
    private val listener: ClientListener
) {
    companion object {
        private const val TAG = "UniteClient"
        private const val TCP_CONNECT_TIMEOUT = 10_000
        private const val UDP_BUFFER_SIZE = 65535
    }

    interface ClientListener {
        fun onConnected(welcome: WelcomeData)
        fun onDisconnected(reason: String)
        fun onDataReceived(ipPacket: ByteArray)
        fun onPeerListUpdated(peers: List<PeerInfo>)
        fun onError(message: String)
    }

    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var sessionId: UInt = 0u
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = Constants.DEFAULT_TCP_PORT

    private val running = AtomicBoolean(false)
    private var tcpThread: Thread? = null
    private var udpThread: Thread? = null
    private var keepaliveThread: Thread? = null

    /**
     * Connect to the server.
     */
    fun connect(host: String, port: Int = Constants.DEFAULT_TCP_PORT, deviceName: String) {
        if (running.get()) {
            listener.onError("Already connected")
            return
        }

        serverPort = port

        Thread {
            try {
                doConnect(host, port, deviceName)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                listener.onError("Connection failed: ${e.message}")
                cleanup()
            }
        }.start()
    }

    private fun doConnect(host: String, port: Int, deviceName: String) {
        Log.i(TAG, "Connecting to $host:$port...")

        // Resolve server address.
        serverAddress = InetAddress.getByName(host)

        // TCP handshake.
        val tcp = Socket()
        tcp.connect(InetSocketAddress(serverAddress, port), TCP_CONNECT_TIMEOUT)
        tcp.tcpNoDelay = true
        tcp.keepAlive = true
        tcpSocket = tcp

        Log.i(TAG, "TCP connected, sending HELLO...")

        // Send HELLO.
        val hello = Packet.hello(deviceName)
        Packet.writeTo(tcp.getOutputStream(), hello)

        // Read WELCOME.
        val response = Packet.readFrom(tcp.getInputStream())
            ?: throw IOException("No response from server")

        if (response.type == Constants.TYPE_ERROR) {
            val errorMsg = if (response.payload.size > 1) {
                String(response.payload, 1, response.payload.size - 1, Charsets.UTF_8)
            } else {
                "Unknown error"
            }
            throw IOException("Server error: $errorMsg")
        }

        if (response.type != Constants.TYPE_WELCOME) {
            throw IOException("Unexpected response type: 0x${response.type.toString(16)}")
        }

        val welcome = Packet.parseWelcome(response.payload)
            ?: throw IOException("Invalid WELCOME payload")

        sessionId = response.sessionId

        Log.i(TAG, "Connected! VirtualIP=${welcome.virtualIp}, SessionID=${sessionId}")

        // Open UDP socket.
        val udp = DatagramSocket()
        udp.soTimeout = 1000
        udpSocket = udp

        // Send initial UDP packet to establish NAT mapping.
        val ping = Packet.ping(sessionId)
        val pingData = ping.encode()
        val udpPacket = DatagramPacket(pingData, pingData.size, serverAddress, port)
        udp.send(udpPacket)

        running.set(true)
        listener.onConnected(welcome)

        // Start background threads.
        tcpThread = Thread({ tcpReadLoop() }, "UniteClient-TCP").also { it.start() }
        udpThread = Thread({ udpReadLoop() }, "UniteClient-UDP").also { it.start() }
        keepaliveThread = Thread({ keepaliveLoop() }, "UniteClient-Keepalive").also { it.start() }
    }

    /**
     * Send an IP packet to the server via UDP.
     */
    fun sendData(ipPacket: ByteArray) {
        if (!running.get()) return
        val udp = udpSocket ?: return
        val addr = serverAddress ?: return

        try {
            val pkt = Packet.data(sessionId, ipPacket)
            val data = pkt.encode()
            val dgram = DatagramPacket(data, data.size, addr, serverPort)
            udp.send(dgram)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data", e)
        }
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        if (!running.compareAndSet(true, false)) return

        Log.i(TAG, "Disconnecting...")

        // Send DISCONNECT via TCP.
        try {
            val tcp = tcpSocket
            if (tcp != null && !tcp.isClosed) {
                val pkt = Packet.disconnect(sessionId)
                Packet.writeTo(tcp.getOutputStream(), pkt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending DISCONNECT", e)
        }

        cleanup()
        listener.onDisconnected("User disconnected")
    }

    private fun cleanup() {
        running.set(false)

        try { tcpSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}

        tcpSocket = null
        udpSocket = null
        sessionId = 0u

        tcpThread?.interrupt()
        udpThread?.interrupt()
        keepaliveThread?.interrupt()
    }

    /**
     * TCP read loop — handles control messages.
     */
    private fun tcpReadLoop() {
        try {
            val tcp = tcpSocket ?: return
            while (running.get()) {
                val pkt = Packet.readFrom(tcp.getInputStream()) ?: break

                when (pkt.type) {
                    Constants.TYPE_PING -> {
                        val pong = Packet.pong(sessionId)
                        Packet.writeTo(tcp.getOutputStream(), pong)
                    }
                    Constants.TYPE_PONG -> {
                        // Server responded to our ping.
                    }
                    Constants.TYPE_PEER_LIST -> {
                        val peers = Packet.parsePeerList(pkt.payload)
                        listener.onPeerListUpdated(peers)
                    }
                    Constants.TYPE_ERROR -> {
                        val errorMsg = if (pkt.payload.size > 1) {
                            String(pkt.payload, 1, pkt.payload.size - 1, Charsets.UTF_8)
                        } else {
                            "Server error"
                        }
                        listener.onError(errorMsg)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "TCP read error", e)
                running.set(false)
                cleanup()
                listener.onDisconnected("Connection lost: ${e.message}")
            }
        }
    }

    /**
     * UDP read loop — receives data packets.
     */
    private fun udpReadLoop() {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        val udp = udpSocket ?: return

        while (running.get()) {
            try {
                val dgram = DatagramPacket(buffer, buffer.size)
                udp.receive(dgram)

                val data = buffer.copyOf(dgram.length)
                val pkt = Packet.decode(data) ?: continue

                if (pkt.type == Constants.TYPE_DATA && pkt.payload.isNotEmpty()) {
                    listener.onDataReceived(pkt.payload)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout, continue.
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "UDP read error", e)
                }
            }
        }
    }

    /**
     * Keepalive loop — sends periodic pings via TCP.
     */
    private fun keepaliveLoop() {
        while (running.get()) {
            try {
                Thread.sleep(Constants.KEEPALIVE_INTERVAL_MS)
                if (!running.get()) break

                val tcp = tcpSocket
                if (tcp != null && !tcp.isClosed) {
                    val ping = Packet.ping(sessionId)
                    Packet.writeTo(tcp.getOutputStream(), ping)
                }

                // Also send UDP ping to keep NAT mapping alive.
                val udp = udpSocket
                val addr = serverAddress
                if (udp != null && addr != null) {
                    val ping = Packet.ping(sessionId)
                    val data = ping.encode()
                    udp.send(DatagramPacket(data, data.size, addr, serverPort))
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Keepalive error", e)
                    running.set(false)
                    cleanup()
                    listener.onDisconnected("Keepalive failed: ${e.message}")
                }
                break
            }
        }
    }

    fun isConnected(): Boolean = running.get()
}
