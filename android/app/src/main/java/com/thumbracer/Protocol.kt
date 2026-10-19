package com.thumbracer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire format shared with the PC host (mirror of host/thumbracer/protocol.py). Little-endian. */
object Protocol {
    const val DISCOVERY_PORT = 47100
    const val DATA_PORT = 47101
    const val VERSION = 1

    const val INPUT = 0x01
    const val HELLO = 0x02
    const val WELCOME = 0x03
    const val REJECT = 0x04
    const val PING = 0x05
    const val PONG = 0x06
    const val BYE = 0x07
    const val DISCOVER = 0x10
    const val DISCOVER_REPLY = 0x11

    const val INPUT_SIZE = 26

    private fun buf(type: Int, payload: Int): ByteBuffer =
        ByteBuffer.allocate(4 + payload).order(ByteOrder.LITTLE_ENDIAN)
            .put('T'.code.toByte()).put('R'.code.toByte()).put(VERSION.toByte()).put(type.toByte())

    /** Packet type, or -1 if this is not one of our packets. */
    fun type(data: ByteArray, len: Int): Int =
        if (len >= 4 && data[0] == 'T'.code.toByte() && data[1] == 'R'.code.toByte()) data[3].toInt() and 0xFF else -1

    fun version(data: ByteArray): Int = data[2].toInt() and 0xFF

    fun input(session: Int, seq: Long, tMs: Long, o: Output): ByteArray = buf(INPUT, 22)
        .putShort(session.toShort())
        .putInt(seq.toInt())
        .putInt(tMs.toInt())
        .putShort((o.steer * 32767).toInt().coerceIn(-32767, 32767).toShort())
        .putShort(unit16(o.gas))
        .putShort(unit16(o.brake))
        .putShort(unit16(o.handbrake))
        .putShort(o.buttons.toShort())
        .put(o.flags.toByte())
        .put(0.toByte())
        .array()

    private fun unit16(v: Double): Short = (v.coerceIn(0.0, 1.0) * 65535).toInt().toShort()

    fun hello(name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8).let { if (it.size > 32) it.copyOf(32) else it }
        return buf(HELLO, 1 + raw.size).put(raw.size.toByte()).put(raw).array()
    }

    fun ping(session: Int, nonce: Int): ByteArray = buf(PING, 6).putShort(session.toShort()).putInt(nonce).array()
    fun bye(session: Int): ByteArray = buf(BYE, 2).putShort(session.toShort()).array()
    fun discover(nonce: Int): ByteArray = buf(DISCOVER, 4).putInt(nonce).array()

    private fun read(data: ByteArray, len: Int) = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN)

    /** WELCOME -> session id. */
    fun welcomeSession(data: ByteArray, len: Int): Int = read(data, len).getShort(4).toInt() and 0xFFFF
    fun rejectReason(data: ByteArray): Int = data[4].toInt()
    fun pongNonce(data: ByteArray, len: Int): Int = read(data, len).getInt(4)

    /** DISCOVER_REPLY -> (nonce, dataPort, pcName). */
    fun discoverReply(data: ByteArray, len: Int): Triple<Int, Int, String> {
        val b = read(data, len)
        val n = data[10].toInt() and 0xFF
        return Triple(b.getInt(4), b.getShort(8).toInt() and 0xFFFF, String(data, 11, n.coerceAtMost(len - 11), Charsets.UTF_8))
    }
}
