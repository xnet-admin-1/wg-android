package com.zaneschepke.wireguardautotunnel.core.tether

import android.os.ParcelFileDescriptor
import android.util.Log
import org.amnezia.awg.GoBackend
import org.amnezia.awg.crypto.KeyPair
import java.net.InetAddress

/**
 * WireGuard server on the tether interface (ncm0).
 * Laptop connects as WG client. Decrypted packets go through lwip userspace
 * stack (hev-socks5-tunnel) which makes real socket connections through the VPN.
 */
class TetherWgServer {
    companion object {
        private const val TAG = "TetherWgServer"
        private const val LISTEN_PORT = 51821
        private const val CLIENT_IP = "10.99.0.2"
        private const val SOCKS5_PORT = 10800
    }

    private var serverHandle = -1
    @Volatile private var running = false
    private var javaPfd: ParcelFileDescriptor? = null
    private var socks5Server: DirectSocks5Server? = null

    private lateinit var serverKeyPair: KeyPair
    private lateinit var clientKeyPair: KeyPair
    private lateinit var presharedKey: org.amnezia.awg.crypto.Key

    fun start(bindAddr: InetAddress, mainHandle: Int, protectSocket: (Int) -> Boolean, context: android.content.Context? = null) {
        serverKeyPair = KeyPair()
        clientKeyPair = KeyPair()
        val pskBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        presharedKey = org.amnezia.awg.crypto.Key.fromBytes(pskBytes)

        // Start direct-connect SOCKS5 server for lwip to use
        socks5Server = DirectSocks5Server(SOCKS5_PORT).also { it.start() }

        // Create socketpair: [0]=WG device TUN, [1]=lwip reads/writes raw IP packets
        val pair = ParcelFileDescriptor.createSocketPair()
        val serverTunFd = pair[0].detachFd()
        javaPfd = pair[1]

        val config = "[Interface]\n" +
                "PrivateKey = ${serverKeyPair.privateKey.toBase64()}\n" +
                "ListenPort = $LISTEN_PORT\n\n" +
                "[Peer]\n" +
                "PublicKey = ${clientKeyPair.publicKey.toBase64()}\n" +
                "PresharedKey = ${presharedKey.toBase64()}\n" +
                "AllowedIPs = 0.0.0.0/0\n"

        Log.i(TAG, "Starting on ${bindAddr.hostAddress}:$LISTEN_PORT")
        RemoteLog.log(TAG, "Starting on ${bindAddr.hostAddress}:$LISTEN_PORT")
        serverHandle = GoBackend.awgTurnOn("wg-tether", serverTunFd, config, "/dev/null")
        if (serverHandle < 0) {
            Log.e(TAG, "awgTurnOn failed: $serverHandle")
            return
        }

        val sock4 = GoBackend.awgGetSocketV4(serverHandle)
        if (sock4 >= 0) protectSocket(sock4)

        running = true

        // Write lwip config and start hev-socks5-tunnel on the socketpair fd
        val configFile = java.io.File("/sdcard/Download/tether-lwip.yml")
        configFile.writeText("""
tunnel:
  mtu: 1400
socks5:
  port: $SOCKS5_PORT
  address: '127.0.0.1'
  udp: 'udp'
misc:
  log-file: stderr
  log-level: warn
  connect-timeout: 10000
""".trimIndent())

        val fd = javaPfd!!.fileDescriptor
        val fdField = fd.javaClass.getDeclaredField("descriptor")
        fdField.isAccessible = true
        val rawFd = fdField.getInt(fd)

        ngo.xnet.pool.TProxyService.TProxyStartService(configFile.absolutePath, rawFd)
        Log.i(TAG, "lwip started on fd=$rawFd")

        Log.i(TAG, "Started, handle=$serverHandle")
        val conf = getClientConfig(bindAddr.hostAddress!!)
        RemoteLog.log(TAG, "Started, handle=$serverHandle")
        RemoteLog.log(TAG, "Client config:\n$conf")
        Log.i(TAG, "Client config:\n$conf")

        // Save config to /sdcard/Download/wg-tether-client.conf
        try {
            val f = java.io.File("/sdcard/Download/wg-tether-client.conf")
            f.writeText(conf)
            Log.i(TAG, "Config saved to ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config: ${e.message}")
        }
    }

    fun stop() {
        running = false
        ngo.xnet.pool.TProxyService.TProxyStopService()
        socks5Server?.stop()
        if (serverHandle >= 0) {
            GoBackend.awgTurnOff(serverHandle)
            serverHandle = -1
        }
        javaPfd?.close()
        Log.i(TAG, "Stopped")
    }

    val isRunning get() = running && serverHandle >= 0

    fun getClientConfig(endpoint: String) = """
        [Interface]
        PrivateKey = ${clientKeyPair.privateKey.toBase64()}
        Address = $CLIENT_IP/32
        MTU = 1420
        DNS = 1.1.1.1

        [Peer]
        PublicKey = ${serverKeyPair.publicKey.toBase64()}
        PresharedKey = ${presharedKey.toBase64()}
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 0
        Endpoint = $endpoint:$LISTEN_PORT
    """.trimIndent()
}
