package com.mcserver.app

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libtailscale.AppContext
import libtailscale.Application
import libtailscale.InputStream
import libtailscale.LocalAPIResponse
import libtailscale.Libtailscale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TailscaleStatus(
    val backendState: String = "Stopped",
    val connected: Boolean = false,
    val ip: String = "",
    val hostname: String = "",
    val dnsName: String = "",
    val authUrl: String = "",
    val message: String = "Tailscale is disconnected."
)

class TailscaleManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: TailscaleManager? = null
        private const val PREFS = "tailscale_secrets"
        private const val KEY_ALIAS = "mcserver.tailscale.aes"

        fun get(context: Context): TailscaleManager = instance ?: synchronized(this) {
            instance ?: TailscaleManager(context.applicationContext).also { instance = it }
        }
    }

    private val store = SecureStore(context)
    private val _status = MutableStateFlow(TailscaleStatus())
    val status: StateFlow<TailscaleStatus> = _status
    @Volatile private var backend: Application? = null
    private val deviceHostname: String by lazy {
        val model = "${Build.MANUFACTURER}-${Build.MODEL}"
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .take(38)
            .trimEnd('-')
            .ifBlank { "android" }
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
            .takeLast(5)
            .lowercase()
            .ifBlank { "device" }
        "mcserver-$model-$id".take(63).trimEnd('-')
    }

    fun hostname(): String = deviceHostname

    fun ensureBackend() {
        if (backend != null) return
        synchronized(this) {
            if (backend != null) return
            val stateDir = File(context.filesDir, "tailscale-state").apply { mkdirs() }
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ).orEmpty()
            Libtailscale.setID("mcserver-$androidId")
            backend = Libtailscale.start(
                stateDir.absolutePath,
                context.filesDir.absolutePath,
                false,
                AndroidAppContext(context, store)
            )
        }
    }

    fun connect(): Result<Unit> = runCatching {
        ensureBackend()
        _status.value = _status.value.copy(
            backendState = "Starting",
            authUrl = "",
            message = "Starting Tailscale…"
        )
        startVpnService()
        val payload = JSONObject().apply {
            put("UpdatePrefs", JSONObject().apply {
                put("WantRunning", true)
                put("HostName", hostname())
            })
        }.toString().toByteArray(Charsets.UTF_8)
        val response = callLocalAPI("POST", "/localapi/v0/start", payload)
        requireSuccess(response, "Tailscale start")
        refreshStatus()
    }


    fun startVpnService() {
        val intent = android.content.Intent(context, TailscaleVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun disconnect(): Result<Unit> = runCatching {
            ensureBackend()
            val payload = JSONObject().put(
                "UpdatePrefs",
                JSONObject().put("WantRunning", false)
            ).toString().toByteArray(Charsets.UTF_8)
            requireSuccess(callLocalAPI("POST", "/localapi/v0/start", payload), "Tailscale disconnect")
        }.onSuccess {
            context.stopService(android.content.Intent(context, TailscaleVpnService::class.java))
            _status.value = _status.value.copy(
                backendState = "Stopped",
                connected = false,
                authUrl = "",
                message = "Tailscale disconnected."
            )
        }.onFailure {
            _status.value = _status.value.copy(message = it.message ?: "Unable to stop Tailscale.")
        }

    fun refreshStatus() {
        runCatching {
            ensureBackend()
            val response = callLocalAPI("GET", "/localapi/v0/status", ByteArray(0))
            requireSuccess(response, "Tailscale status")
            parseStatus(response.bodyBytes().toString(Charsets.UTF_8))
        }.onFailure {
            _status.value = _status.value.copy(
                backendState = "Error",
                connected = false,
                authUrl = "",
                message = it.message ?: "Unable to read Tailscale status."
            )
        }
    }


    private fun parseStatus(raw: String) {
        val json = JSONObject(raw)
        val self = json.optJSONObject("Self")
        val ips = mutableListOf<String>()
        json.optJSONArray("TailscaleIPs")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { ips += it }
            }
        }
        self?.optJSONArray("TailscaleIPs")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() && !ips.contains(it) }?.let { ips += it }
            }
        }
        val state = json.optString("BackendState", "Stopped")
        val ip = ips.firstOrNull { it.contains('.') } ?: ips.firstOrNull().orEmpty()
        val host = self?.optString("HostName")?.takeIf { it.isNotBlank() } ?: hostname()
        val dns = self?.optString("DNSName", "").orEmpty().trimEnd('.')
        val authUrl = json.optString("AuthURL", "")
        val connected = state == "Running" && ip.isNotBlank()
        _status.value = TailscaleStatus(
            backendState = state,
            connected = connected,
            ip = ip,
            hostname = host,
            dnsName = dns,
            authUrl = authUrl,
            message = if (connected) {
                "Tailscale connected."
            } else if (authUrl.isNotBlank()) {
                "Sign in with your Tailscale account to add this device."
            } else if (state == "NeedsMachineAuth") {
                "This device is waiting for approval in the Tailscale admin console."
            } else if (state == "Starting") {
                "Connecting to Tailscale…"
            } else if (state == "NeedsLogin") {
                "Tailscale sign-in is required for private access."
            } else {
                "Tailscale is disconnected."
            }
        )
    }

    private fun requireSuccess(response: LocalAPIResponse, operation: String) {
        val status = response.statusCode()
        if (status in 200L..299L) return
        val body = runCatching { response.bodyBytes().toString(Charsets.UTF_8) }.getOrDefault("")
        val detail = runCatching { JSONObject(body).optString("Error").takeIf { it.isNotBlank() } }
            .getOrNull() ?: body
        val compactDetail = detail.replace(Regex("\\s+"), " ").trim().take(240)
        error(buildString {
            append("$operation failed (HTTP $status).")
            if (compactDetail.isNotBlank()) append(" $compactDetail")
        })
    }

    private fun callLocalAPI(method: String, endpoint: String, data: ByteArray): LocalAPIResponse =
        backend?.callLocalAPI(30000, method, endpoint, ByteArrayInput(data))
            ?: error("Tailscale backend is not initialized.")

    private class ByteArrayInput(private val data: ByteArray) : InputStream {
        private var consumed = false
        override fun read(): ByteArray = if (consumed) ByteArray(0) else data.also { consumed = true }
        override fun close() = Unit
    }

    private class AndroidAppContext(
        private val context: Context,
        private val store: SecureStore
    ) : AppContext {
        override fun bindSocketToNetwork(fd: Int): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.let { c ->
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } ?: return false
            return runCatching {
            android.os.ParcelFileDescriptor.fromFd(fd).use { pfd ->
                network.bindSocket(pfd.fileDescriptor)
            }
            true
        }.getOrDefault(false)
        }

        override fun decryptFromPref(key: String): String = store.get(key).orEmpty()
        override fun encryptToPref(key: String, value: String) = store.put(key, value)
        override fun getDeviceName(): String = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        override fun getInstallSource(): String = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).initiatingPackageName.orEmpty()
            } else ""
        }.getOrDefault("")

        override fun getInterfacesAsJson(): String {
            val result = JSONArray()
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                val addresses = JSONArray()
                ni.interfaceAddresses.forEach { ia ->
                    addresses.put(JSONObject().apply {
                        put("ip", ia.address.hostAddress)
                        put("prefixLen", ia.networkPrefixLength)
                    })
                }
                result.put(JSONObject().apply {
                    put("name", ni.name)
                    put("index", ni.index)
                    put("mtu", runCatching { ni.mtu }.getOrDefault(0))
                    put("up", runCatching { ni.isUp }.getOrDefault(false))
                    put("broadcast", false)
                    put("loopback", runCatching { ni.isLoopback }.getOrDefault(false))
                    put("pointToPoint", runCatching { ni.isPointToPoint }.getOrDefault(false))
                    put("multicast", runCatching { ni.supportsMulticast() }.getOrDefault(false))
                    put("addrs", addresses)
                })
            }
            return result.toString()
        }

        override fun getOSVersion(): String = Build.VERSION.RELEASE.orEmpty()
        override fun getPlatformDNSConfig(): String = ""
        override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()
        override fun getStateStoreKeysJSON(): String {
            val result = JSONArray()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys
                .filter { it.startsWith("statestore-") }
                .sorted()
                .forEach(result::put)
            return result.toString()
        }
        override fun getSyspolicyBooleanValue(key: String): Boolean = false
        override fun getSyspolicyStringArrayJSONValue(key: String): String = "[]"
        override fun getSyspolicyStringValue(key: String): String = ""
        override fun getUserCACertsPEM(): ByteArray = ByteArray(0)
        override fun hardwareAttestationKeyCreate(): String = error("Hardware attestation unsupported")
        override fun hardwareAttestationKeyLoad(key: String) = Unit
        override fun hardwareAttestationKeyPublic(key: String): ByteArray = error("Hardware attestation unsupported")
        override fun hardwareAttestationKeyRelease(key: String) = Unit
        override fun hardwareAttestationKeySign(key: String, data: ByteArray): ByteArray = error("Hardware attestation unsupported")
        override fun hardwareAttestationKeySupported(): Boolean = false
        override fun isChromeOS(): Boolean = runCatching {
            context.packageManager.hasSystemFeature("android.hardware.type.pc")
        }.getOrDefault(false)
        override fun isClientLoggingEnabled(): Boolean = false
        override fun log(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }
        override fun shouldUseGoogleDNSFallback(): Boolean = true
    }

    private class SecureStore(private val context: Context) {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun get(name: String): String? = prefs.getString(name, null)?.let(::decrypt)
        fun put(name: String, value: String) {
            prefs.edit().putString(name, encrypt(value)).apply()
        }
        fun remove(name: String) {
            prefs.edit().remove(name).apply()
        }

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(KEY_ALIAS)) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generator.generateKey()
            }
            return store.getKey(KEY_ALIAS, null) as SecretKey
        }

        private fun encrypt(value: String): String {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            val data = nonce + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        }

        private fun decrypt(value: String): String {
            val data = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
            require(data.size > 12) { "Corrupt encrypted preference." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
        }
    }
}
