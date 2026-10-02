package com.mcserver.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import libtailscale.IPNService
import libtailscale.Libtailscale
import libtailscale.ParcelFileDescriptor
import libtailscale.VPNServiceBuilder
import java.net.InetAddress

class TailscaleVpnService : VpnService(), IPNService {
    companion object {
        private const val CHANNEL_ID = "tailscale"
        private const val NOTIFICATION_ID = 4401
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Connecting Tailscale..."))
        TailscaleManager.get(this).ensureBackend()
        Libtailscale.requestVPN(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { Libtailscale.serviceDisconnect(this) }
        super.onDestroy()
    }

    override fun close() {
        runCatching { Libtailscale.serviceDisconnect(this) }
        stopSelf()
    }

    override fun disconnectVPN() = stopSelf()

    override fun id(): String = "mcserver-tailscale-vpn"

    override fun protect(fd: Int): Boolean = super.protect(fd)

    override fun updateVpnStatus(up: Boolean) {
        val status = TailscaleManager.get(this).status.value
        val text = when {
            up && status.ip.isNotBlank() -> "Tailscale - ${status.ip}"
            up -> "Tailscale connected"
            else -> "Tailscale disconnected"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun newBuilder(): VPNServiceBuilder = BuilderBridge(
        Builder()
            .setSession("MCSERVER Tailscale")
            .setMtu(1280)
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Tailscale",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("MCSERVER network")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private class BuilderBridge(private val builder: Builder) : VPNServiceBuilder {
        override fun addAddress(p0: String, p1: Int) {
            builder.addAddress(p0, p1)
        }

        override fun addDNSServer(p0: String) {
            builder.addDnsServer(p0)
        }

        override fun addRoute(p0: String, p1: Int) {
            builder.addRoute(p0, p1)
        }

        override fun addSearchDomain(p0: String) {
            builder.addSearchDomain(p0)
        }

        override fun excludeRoute(p0: String, p1: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.excludeRoute(IpPrefix(InetAddress.getByName(p0), p1))
            }
        }

        override fun establish(): ParcelFileDescriptor? =
            builder.establish()?.let { PfdBridge(it) }

        override fun setMTU(p0: Int) {
            builder.setMtu(p0)
        }
    }

    private class PfdBridge(private val fd: android.os.ParcelFileDescriptor) : ParcelFileDescriptor {
        override fun detach(): Int = fd.detachFd()
    }
}
