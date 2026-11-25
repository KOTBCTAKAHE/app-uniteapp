package com.uniteapp.protocol

/**
 * UNITEAPP Protocol constants — must match the Go server exactly.
 */
object Constants {
    const val MAGIC_BYTE_0: Byte = 'U'.code.toByte()
    const val MAGIC_BYTE_1: Byte = 'A'.code.toByte()

    const val PROTOCOL_VERSION: Byte = 1

    const val HEADER_SIZE = 10
    const val MAX_PAYLOAD_SIZE = 65525

    const val DEFAULT_TCP_PORT = 7655
    const val DEFAULT_UDP_PORT = 7655

    const val KEEPALIVE_INTERVAL_MS = 10_000L
    const val KEEPALIVE_TIMEOUT_MS = 30_000L
    const val RECONNECT_DELAY_MS = 3_000L

    // Packet types
    const val TYPE_HELLO: Byte = 0x01
    const val TYPE_WELCOME: Byte = 0x02
    const val TYPE_DATA: Byte = 0x03
    const val TYPE_PING: Byte = 0x04
    const val TYPE_PONG: Byte = 0x05
    const val TYPE_DISCONNECT: Byte = 0x06
    const val TYPE_PEER_LIST: Byte = 0x07
    const val TYPE_ERROR: Byte = 0xFF.toByte()
}
