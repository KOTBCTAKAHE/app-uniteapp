package com.uniteapp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.uniteapp.R
import com.uniteapp.service.UniteVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var etServerAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvVirtualIp: TextView
    private lateinit var tvPeers: TextView

    private var pendingHost: String = ""
    private var pendingPort: Int = 7655

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService(pendingHost, pendingPort)
        } else {
            setStatus("VPN permission denied", isError = true)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val status = intent.getStringExtra(UniteVpnService.EXTRA_STATUS) ?: return
            val virtualIp = intent.getStringExtra(UniteVpnService.EXTRA_VIRTUAL_IP)
            val message = intent.getStringExtra(UniteVpnService.EXTRA_MESSAGE)
            val peers = intent.getStringExtra(UniteVpnService.EXTRA_PEERS)

            runOnUiThread {
                when (status) {
                    UniteVpnService.STATUS_CONNECTING -> {
                        setStatus("Connecting...", isConnecting = true)
                    }
                    UniteVpnService.STATUS_CONNECTED -> {
                        setStatus("Connected", isConnected = true)
                        tvVirtualIp.text = virtualIp ?: ""
                        if (peers != null) {
                            tvPeers.text = peers
                        }
                    }
                    UniteVpnService.STATUS_DISCONNECTED -> {
                        setStatus(message ?: "Disconnected")
                        tvVirtualIp.text = ""
                        tvPeers.text = ""
                    }
                    UniteVpnService.STATUS_ERROR -> {
                        setStatus(message ?: "Error", isError = true)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerAddress = findViewById(R.id.et_server_address)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        tvStatus = findViewById(R.id.tv_status)
        tvVirtualIp = findViewById(R.id.tv_virtual_ip)
        tvPeers = findViewById(R.id.tv_peers)

        btnConnect.setOnClickListener { onConnectClicked() }
        btnDisconnect.setOnClickListener { onDisconnectClicked() }

        setStatus("Not connected")
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(UniteVpnService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun onConnectClicked() {
        val input = etServerAddress.text.toString().trim()
        if (input.isEmpty()) {
            setStatus("Enter server address", isError = true)
            return
        }

        // Parse host:port.
        val parts = input.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 7655 else 7655

        pendingHost = host
        pendingPort = port

        // Request VPN permission.
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService(host, port)
        }
    }

    private fun onDisconnectClicked() {
        val intent = Intent(this, UniteVpnService::class.java).apply {
            action = UniteVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        setStatus("Disconnecting...")
    }

    private fun startVpnService(host: String, port: Int) {
        val intent = Intent(this, UniteVpnService::class.java).apply {
            action = UniteVpnService.ACTION_CONNECT
            putExtra(UniteVpnService.EXTRA_SERVER_HOST, host)
            putExtra(UniteVpnService.EXTRA_SERVER_PORT, port)
            putExtra(UniteVpnService.EXTRA_DEVICE_NAME, Build.MODEL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        setStatus("Connecting...", isConnecting = true)
    }

    private fun setStatus(
        text: String,
        isConnected: Boolean = false,
        isConnecting: Boolean = false,
        isError: Boolean = false
    ) {
        tvStatus.text = text
        tvStatus.setTextColor(
            getColor(
                when {
                    isConnected -> android.R.color.holo_green_dark
                    isError -> android.R.color.holo_red_dark
                    isConnecting -> android.R.color.holo_orange_dark
                    else -> android.R.color.darker_gray
                }
            )
        )

        btnConnect.isEnabled = !isConnected && !isConnecting
        btnDisconnect.isEnabled = isConnected || isConnecting
        etServerAddress.isEnabled = !isConnected && !isConnecting
    }
}
