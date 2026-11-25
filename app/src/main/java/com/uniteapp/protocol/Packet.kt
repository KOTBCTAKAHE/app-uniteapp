package com.uniteapp.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents a UNITEAPP protocol packet.
 */
data class Packet(
    val type: Byte,
    val sessionId: UInt = 0u,
    val payload: ByteArray = ByteArray(0)
) {
    /**
     * Encode packet into binary form.
     */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(Constants.HEADER_SIZE + payload.size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(Constants.MAGIC_BYTE_0)
        buf.put(Constants.MAGIC_BYTE_1)
        buf.put(Constants.PROTOCOL_VERSION)
        buf.put(type)
        buf.putInt(sessionId.toInt())
        buf.putShort(payload.size.toShort())
        if (payload.isNotEmpty()) {
            buf.put(payload)
        }
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return type == other.type && sessionId == other.sessionId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Decode a packet from a byte array (UDP).
         */
        fun decode(data: ByteArray): Packet? {
            if (data.size < Constants.HEADER_SIZE) return null

            val buf = ByteBuffer.wrap(data)
            buf.order(ByteOrder.BIG_ENDIAN)

            val magic0 = buf.get()
            val magic1 = buf.get()
            if (magic0 != Constants.MAGIC_BYTE_0 || magic1 != Constants.MAGIC_BYTE_1) return null

            val version = buf.get()
            if (version != Constants.PROTOCOL_VERSION) return null

            val type = buf.get()
            val sessionId = buf.int.toUInt()
            val payloadLen = buf.short.toInt() and 0xFFFF

            if (payloadLen > Constants.MAX_PAYLOAD_SIZE) return null
            if (data.size < Constants.HEADER_SIZE + payloadLen) return null

            val payload = if (payloadLen > 0) {
                ByteArray(payloadLen).also { buf.get(it) }
            } else {
                ByteArray(0)
            }

            return Packet(type, sessionId, payload)
        }

        /**
         * Read a single packet from a TCP stream.
         */
        fun readFrom(input: InputStream): Packet? {
            val header = ByteArray(Constants.HEADER_SIZE)
            if (readFully(input, header) != Constants.HEADER_SIZE) return null

            val buf = ByteBuffer.wrap(header)
            buf.order(ByteOrder.BIG_ENDIAN)

            val magic0 = buf.get()
            val magic1 = buf.get()
            if (magic0 != Constants.MAGIC_BYTE_0 || magic1 != Constants.MAGIC_BYTE_1) return null

            val version = buf.get()
            if (version != Constants.PROTOCOL_VERSION) return null

            val type = buf.get()
            val sessionId = buf.int.toUInt()
            val payloadLen = buf.short.toInt() and 0xFFFF

            if (payloadLen > Constants.MAX_PAYLOAD_SIZE) return null

            val payload = if (payloadLen > 0) {
                ByteArray(payloadLen).also {
                    if (readFully(input, it) != payloadLen) return null
                }
            } else {
                ByteArray(0)
            }

            return Packet(type, sessionId, payload)
        }

        /**
         * Write a packet to a TCP stream.
         */
        fun writeTo(output: OutputStream, packet: Packet) {
            output.write(packet.encode())
            output.flush()
        }

        /**
         * Create a HELLO packet.
         */
        fun hello(deviceName: String): Packet {
            return Packet(Constants.TYPE_HELLO, payload = deviceName.toByteArray(Charsets.UTF_8))
        }

        /**
         * Create a DATA packet.
         */
        fun data(sessionId: UInt, ipPacket: ByteArray): Packet {
            return Packet(Constants.TYPE_DATA, sessionId, ipPacket)
        }

        /**
         * Create a PING packet.
         */
        fun ping(sessionId: UInt): Packet {
            return Packet(Constants.TYPE_PING, sessionId)
        }

        /**
         * Create a PONG packet.
         */
        fun pong(sessionId: UInt): Packet {
            return Packet(Constants.TYPE_PONG, sessionId)
        }

        /**
         * Create a DISCONNECT packet.
         */
        fun disconnect(sessionId: UInt): Packet {
            return Packet(Constants.TYPE_DISCONNECT, sessionId)
        }

        /**
         * Parse WELCOME payload.
         */
        fun parseWelcome(payload: ByteArray): WelcomeData? {
            if (payload.size < 14) return null
            val buf = ByteBuffer.wrap(payload)
            buf.order(ByteOrder.BIG_ENDIAN)

            val virtualIp = ByteArray(4).also { buf.get(it) }
            val subnetMask = ByteArray(4).also { buf.get(it) }
            val serverIp = ByteArray(4).also { buf.get(it) }
            val mtu = buf.short.toInt() and 0xFFFF

            return WelcomeData(
                virtualIp = ipToString(virtualIp),
                subnetMask = ipToString(subnetMask),
                serverIp = ipToString(serverIp),
                mtu = mtu
            )
        }

        /**
         * Parse PEER_LIST payload.
         */
        fun parsePeerList(payload: ByteArray): List<PeerInfo> {
            if (payload.isEmpty()) return emptyList()
            val count = payload[0].toInt() and 0xFF
            val peers = mutableListOf<PeerInfo>()
            var offset = 1

            for (i in 0 until count) {
                if (offset + 5 > payload.size) break
                val ip = ByteArray(4)
                System.arraycopy(payload, offset, ip, 0, 4)
                offset += 4

                val nameLen = payload[offset].toInt() and 0xFF
                offset++

                if (offset + nameLen > payload.size) break
                val name = String(payload, offset, nameLen, Charsets.UTF_8)
                offset += nameLen

                peers.add(PeerInfo(ipToString(ip), name))
            }

            return peers
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var totalRead = 0
            while (totalRead < buffer.size) {
                val bytesRead = input.read(buffer, totalRead, buffer.size - totalRead)
                if (bytesRead == -1) return totalRead
                totalRead += bytesRead
            }
            return totalRead
        }

        private fun ipToString(bytes: ByteArray): String {
            return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
        }
    }
}

data class WelcomeData(
    val virtualIp: String,
    val subnetMask: String,
    val serverIp: String,
    val mtu: Int
)

data class PeerInfo(
    val virtualIp: String,
    val name: String
)
