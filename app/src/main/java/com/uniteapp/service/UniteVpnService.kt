package com.uniteapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.uniteapp.R
import com.uniteapp.network.UniteClient
import com.uniteapp.protocol.PeerInfo
import com.uniteapp.protocol.WelcomeData
import com.uniteapp.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Android VPNService implementation for UNITEAPP.
 * Creates a TUN interface and routes IP packets through the UNITEAPP server.
 */
class UniteVpnService : VpnService(), UniteClient.ClientListener {

    companion object {
        private const val TAG = "UniteVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "uniteapp_vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.uniteapp.CONNECT"
        const val ACTION_DISCONNECT = "com.uniteapp.DISCONNECT"
        const val EXTRA_SERVER_HOST = "server_host"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_DEVICE_NAME = "device_name"

        // Broadcast actions for UI updates.
        const val BROADCAST_STATUS = "com.uniteapp.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_VIRTUAL_IP = "virtual_ip"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PEERS = "peers"

        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_ERROR = "error"
    }

    private var client: UniteClient? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReadThread: Thread? = null
    private var welcomeData: WelcomeData? = null

    @Volatile
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_SERVER_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, 7655)
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: Build.MODEL
                startConnection(host, port, deviceName)
            }
            ACTION_DISCONNECT -> {
                stopConnection()
            }
        }
        return START_STICKY
    }

    private fun startConnection(host: String, port: Int, deviceName: String) {
        Log.i(TAG, "Starting connection to $host:$port as '$deviceName'")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        sendStatus(STATUS_CONNECTING, message = "Connecting to $host...")

        client = UniteClient(this).also {
            it.connect(host, port, deviceName)
        }
    }

    private fun stopConnection() {
        Log.i(TAG, "Stopping connection")
        isRunning = false
        client?.disconnect()
        closeTunInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- UniteClient.ClientListener ---

    override fun onConnected(welcome: WelcomeData) {
        Log.i(TAG, "Connected! VirtualIP=${welcome.virtualIp}")
        welcomeData = welcome

        // Set up TUN interface.
        try {
            val builder = Builder()
                .setSession("UniteApp")
                .addAddress(welcome.virtualIp, subnetPrefixLength(welcome.subnetMask))
                .addRoute("10.10.0.0", 24) // Route virtual subnet through VPN.
                .setMtu(welcome.mtu)
                .setBlocking(true)

            // Protect the socket to avoid routing loops.
            // The VPN only captures traffic to the virtual subnet.

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                sendStatus(STATUS_ERROR, message = "Failed to create VPN interface")
                client?.disconnect()
                return
            }

            isRunning = true

            // Start reading from TUN.
            tunReadThread = Thread({ tunReadLoop() }, "UniteVPN-TUN").also { it.start() }

            updateNotification("Connected: ${welcome.virtualIp}")
            sendStatus(STATUS_CONNECTED, virtualIp = welcome.virtualIp)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up TUN", e)
            sendStatus(STATUS_ERROR, message = "VPN setup failed: ${e.message}")
            client?.disconnect()
        }
    }

    override fun onDisconnected(reason: String) {
        Log.i(TAG, "Disconnected: $reason")
        isRunning = false
        closeTunInterface()
        sendStatus(STATUS_DISCONNECTED, message = reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDataReceived(ipPacket: ByteArray) {
        // Write received IP packet to TUN interface.
        val fd = vpnInterface?.fileDescriptor ?: return
        try {
            val output = FileOutputStream(fd)
            output.write(ipPacket)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to TUN", e)
        }
    }

    override fun onPeerListUpdated(peers: List<PeerInfo>) {
        Log.i(TAG, "Peers: ${peers.joinToString { "${it.name}(${it.virtualIp})" }}")
        val peersStr = peers.joinToString("\n") { "${it.name}: ${it.virtualIp}" }
        sendStatus(STATUS_CONNECTED, virtualIp = welcomeData?.virtualIp, message = peersStr, peers = peersStr)
    }

    override fun onError(message: String) {
        Log.e(TAG, "Error: $message")
        sendStatus(STATUS_ERROR, message = message)
    }

    // --- TUN read loop ---

    private fun tunReadLoop() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val buffer = ByteBuffer.allocate(32767)

        while (isRunning) {
            try {
                buffer.clear()
                val length = input.read(buffer.array())
                if (length > 0) {
                    val ipPacket = ByteArray(length)
                    System.arraycopy(buffer.array(), 0, ipPacket, 0, length)
                    client?.sendData(ipPacket)
                } else if (length < 0) {
                    break
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "TUN read error", e)
                }
                break
            }
        }
    }

    private fun closeTunInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        tunReadThread?.interrupt()
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UniteApp VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("UniteApp")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // --- Status broadcasts ---

    private fun sendStatus(
        status: String,
        virtualIp: String? = null,
        message: String? = null,
        peers: String? = null
    ) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            virtualIp?.let { putExtra(EXTRA_VIRTUAL_IP, it) }
            message?.let { putExtra(EXTRA_MESSAGE, it) }
            peers?.let { putExtra(EXTRA_PEERS, it) }
        }
        sendBroadcast(intent)
    }

    // --- Helpers ---

    private fun subnetPrefixLength(mask: String): Int {
        val parts = mask.split(".")
        if (parts.size != 4) return 24

        var bits = 0
        for (part in parts) {
            var octet = part.toIntOrNull() ?: 0
            while (octet and 0x80 != 0) {
                bits++
                octet = (octet shl 1) and 0xFF
            }
        }
        return bits
    }

    override fun onDestroy() {
        super.onDestroy()
        stopConnection()
    }
}
