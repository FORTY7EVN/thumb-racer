package com.thumbracer

import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.random.Random

/** The one UDP socket: discovery, handshake state, pongs. Engine sends the stream through send(). */
object Link {
    enum class State { IDLE, CONNECTING, CONNECTED, REJECTED }
    data class Pc(val name: String, val ip: String, val port: Int)

    @Volatile var state = State.IDLE
    @Volatile var rejectReason = 0
    @Volatile var session = 0
    @Volatile var rttMs = -1L
    @Volatile var lastPong = 0L
    @Volatile var targetName = ""
    @Volatile private var host: String? = null
    @Volatile private var port = Protocol.DATA_PORT
    @Volatile private var addr: InetSocketAddress? = null // resolved on the engine thread

    val found = ConcurrentHashMap<String, Pc>()
    private val nonce = Random.nextInt()
    private val io = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    @Synchronized private fun socket(): DatagramSocket = socket ?: DatagramSocket().also { s ->
        s.broadcast = true
        s.trafficClass = 0xB8 // DSCP EF: ask the router to treat this as real-time
        socket = s
        thread(isDaemon = true, name = "link-rx") { receive(s) }
    }

    private fun receive(s: DatagramSocket) {
        val buf = ByteArray(128)
        val pk = DatagramPacket(buf, buf.size)
        while (!s.isClosed) {
            try { s.receive(pk) } catch (e: IOException) { break }
            handle(buf, pk.length, pk.address)
        }
    }

    private fun handle(d: ByteArray, len: Int, from: InetAddress) {
        if (len >= 3 && Protocol.type(d, len) >= 0 && Protocol.version(d) != Protocol.VERSION) return
        when (Protocol.type(d, len)) {
            Protocol.DISCOVER_REPLY -> if (len >= 11) {
                val (n, p, name) = Protocol.discoverReply(d, len)
                val ip = from.hostAddress ?: return
                if (n == nonce) found[ip] = Pc(name, ip, p)
            }
            Protocol.WELCOME -> if (len >= 6 && from == addr?.address) {
                session = Protocol.welcomeSession(d, len)
                lastPong = SystemClock.uptimeMillis()
                state = State.CONNECTED
            }
            Protocol.REJECT -> if (len >= 5 && from == addr?.address) {
                rejectReason = Protocol.rejectReason(d)
                state = State.REJECTED
            }
            Protocol.PONG -> if (len >= 8) {
                val now = SystemClock.uptimeMillis()
                rttMs = (now.toInt() - Protocol.pongNonce(d, len)).toLong()
                lastPong = now
            }
        }
    }

    /** Broadcast once; replies land in [found]. Safe to call from the UI thread. */
    fun discover() = io.execute {
        runCatching {
            val b = Protocol.discover(nonce)
            socket().send(DatagramPacket(b, b.size, InetAddress.getByName("255.255.255.255"), Protocol.DISCOVERY_PORT))
        }
    }

    fun connect(pc: Pc) {
        host = pc.ip; port = pc.port; targetName = pc.name
        addr = null; session = 0; rejectReason = 0; rttMs = -1
        state = State.CONNECTING
    }

    /** Engine thread only: may resolve a typed host name. */
    fun send(bytes: ByteArray) {
        val a = addr ?: (host ?: return).let { h -> runCatching { InetSocketAddress(h, port) }.getOrNull() }?.also { addr = it } ?: return
        runCatching { socket().send(DatagramPacket(bytes, bytes.size, a)) }
    }

    fun bye() {
        val s = session
        if (s != 0) send(Protocol.bye(s))
        session = 0
        if (state != State.IDLE) state = State.CONNECTING // resume says HELLO again
    }
}
