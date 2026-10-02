package com.mcserver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.net.Socket as NetSocket
import java.net.InetSocketAddress as NetInetSocketAddress
import java.net.ServerSocket as NetServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

enum class ServerType(val displayName: String) {
    VANILLA("Vanilla"),
    PAPER("Paper"),
    PURPUR("Purpur"),
    SPIGOT("Spigot"),
    BUKKIT("Bukkit-compatible"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    GEYSER("Geyser · Paper")
}

data class ServerConfig(
    val name: String,
    val version: String,
    val type: ServerType,
    val ramMb: Int,
    val worldName: String,
    val maxPlayers: Int = 20,
    val javaPort: Int = 25565,
    val bedrockPort: Int = 19132
)

data class NetworkStatus(
    val connected: Boolean = false,
    val publicHost: String = "",
    val javaPort: Int = 0,
    val bedrockPort: Int? = null,
    val message: String = "Not connected"
)

data class LocaltonetConfig(
    val enabled: Boolean = false,
    val javaHost: String = "",
    val javaPort: Int = 25565,
    val bedrockHost: String = "",
    val bedrockPort: Int = 19132
)

data class WorldSettings(
    val seed: String = "",
    val levelType: String = "minecraft:normal",
    val difficulty: String = "normal",
    val gamemode: String = "survival",
    val generateStructures: Boolean = true,
    val hardcore: Boolean = false,
    val pvp: Boolean = true,
    val viewDistance: Int = 10,
    val simulationDistance: Int = 10
)

data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

fun localServerAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { network ->
            val name = network.name.lowercase()
            network.isUp && !network.isLoopback &&
                !name.startsWith("tun") && !name.startsWith("ts") && !name.contains("tailscale")
        }
        .flatMap { network -> network.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .distinct()
        .sortedWith(compareBy<String> { address ->
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val wifi = interfaces.any { network ->
                network.name.lowercase().let { it.startsWith("wlan") || it.startsWith("ap") } &&
                    network.inetAddresses.toList().any { it.hostAddress?.substringBefore('%') == address }
            }
            !wifi
        }.thenBy { it })
}.getOrDefault(emptyList())

data class DownloadResult(val file: File, val sha256: String)

class StorageManager(context: Context) {
    val root = File(context.filesDir, "app_data")
    val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
    val serverDir = File(root, "servers/main")
    val worldDir = File(serverDir, "world")
    val pluginsDir = File(serverDir, "plugins")
    val modsDir = File(serverDir, "mods")
    val backupsDir = File(serverDir, "backups")
    val logsDir = File(serverDir, "logs")
    val runtimesDir = File(root, "runtimes")
    val runtimeJava = File(runtimesDir, "current/bin/java")

    fun initialize() {
        listOf(serverDir, worldDir, pluginsDir, modsDir, backupsDir, logsDir, runtimesDir).forEach(File::mkdirs)
    }

    fun logFile(): File = File(logsDir, "server.log")
    fun eulaFile(): File = File(serverDir, "eula.txt")
}

class ConfigurationManager(private val storage: StorageManager) {
    private val file = File(storage.serverDir, "server.properties.local")
    private val localtonetFile = File(storage.serverDir, "localtonet.properties.local")

    fun save(config: ServerConfig) {
        storage.initialize()
        Properties().apply {
            setProperty("name", config.name)
            setProperty("version", config.version)
            setProperty("type", config.type.name)
            setProperty("ramMb", config.ramMb.toString())
            setProperty("worldName", config.worldName)
            setProperty("maxPlayers", config.maxPlayers.toString())
            setProperty("javaPort", config.javaPort.toString())
            setProperty("bedrockPort", config.bedrockPort.toString())
            FileOutputStream(file).use { store(it, "MCSERVER configuration") }
        }
    }

    fun load(): ServerConfig? {
        if (!file.exists()) return null
        val p = Properties()
        FileInputStream(file).use { p.load(it) }
        return ServerConfig(
            name = p.getProperty("name", "My Server"),
            version = p.getProperty("version", "1.21.10"),
            type = runCatching { ServerType.valueOf(p.getProperty("type", ServerType.PAPER.name)) }.getOrDefault(ServerType.PAPER),
            ramMb = p.getProperty("ramMb", "1024").toIntOrNull()?.coerceAtLeast(512) ?: 1024,
            worldName = p.getProperty("worldName", "world").ifBlank { "world" },
            maxPlayers = p.getProperty("maxPlayers", "20").toIntOrNull()?.coerceIn(1, 1000) ?: 20,
            javaPort = p.getProperty("javaPort", "25565").toIntOrNull()?.coerceIn(1, 65535) ?: 25565,
            bedrockPort = p.getProperty("bedrockPort", "19132").toIntOrNull()?.coerceIn(1, 65535) ?: 19132
        )
    }

    fun saveLocaltonet(config: LocaltonetConfig) {
        storage.initialize()
        Properties().apply {
            setProperty("enabled", config.enabled.toString())
            setProperty("javaHost", config.javaHost.trim())
            setProperty("javaPort", config.javaPort.coerceIn(1, 65535).toString())
            setProperty("bedrockHost", config.bedrockHost.trim())
            setProperty("bedrockPort", config.bedrockPort.coerceIn(1, 65535).toString())
            FileOutputStream(localtonetFile).use { store(it, "MCSERVER Localtonet configuration") }
        }
    }

    fun loadLocaltonet(): LocaltonetConfig {
        if (!localtonetFile.exists()) return LocaltonetConfig()
        val p = Properties()
        FileInputStream(localtonetFile).use { p.load(it) }
        return LocaltonetConfig(
            enabled = p.getProperty("enabled", "false").toBoolean(),
            javaHost = p.getProperty("javaHost", "").trim(),
            javaPort = p.getProperty("javaPort", "25565").toIntOrNull()?.coerceIn(1, 65535) ?: 25565,
            bedrockHost = p.getProperty("bedrockHost", "").trim(),
            bedrockPort = p.getProperty("bedrockPort", "19132").toIntOrNull()?.coerceIn(1, 65535) ?: 19132
        )
    }
}

class DownloadManager(private val storage: StorageManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun download(url: String, destination: File, expectedSha256: String? = null): DownloadResult {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: storage.serverDir, destination.name + ".part")
        val request = Request.Builder().url(url).header("User-Agent", BuildConfig.MCSERVER_USER_AGENT).build()
        return try {
            executeWithRetry(request).use { response ->
                check(response.isSuccessful) { httpFailure("Download", response.code, response.body?.string().orEmpty()) }
                val body = response.body ?: error("Empty download response")
                FileOutputStream(temporary).use { output -> body.byteStream().use { input -> input.copyTo(output) } }
            }
            val actual = sha256Hex(temporary)
            if (expectedSha256 != null) check(actual.equals(expectedSha256, ignoreCase = true)) { "SHA-256 mismatch for ${destination.name}" }
            if (destination.exists()) check(destination.delete()) { "Unable to replace ${destination.name}" }
            check(temporary.renameTo(destination)) { "Unable to finalize ${destination.name}" }
            DownloadResult(destination, actual)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    fun downloadRaw(url: String, destination: File): DownloadResult {
        destination.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", BuildConfig.MCSERVER_USER_AGENT)
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..399) {
                val details = runCatching {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }
                }.getOrDefault("")
                error(httpFailure("Download", code, details))
            }
            val temporary = File(destination.parentFile ?: storage.serverDir, destination.name + ".part")
            connection.inputStream.use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
            if (destination.exists()) check(destination.delete()) { "Unable to replace ${destination.name}" }
            check(temporary.renameTo(destination)) { "Unable to finalize ${destination.name}" }
            return DownloadResult(destination, sha256Hex(destination))
        } finally {
            connection.disconnect()
        }
    }

    fun downloadSha512(url: String, destination: File, expectedSha512: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: storage.serverDir, destination.name + ".part")
        val request = Request.Builder().url(url).header("User-Agent", BuildConfig.MCSERVER_USER_AGENT).build()
        try {
            executeWithRetry(request).use { response ->
                check(response.isSuccessful) { httpFailure("Download", response.code, response.body?.string().orEmpty()) }
                val body = response.body ?: error("Empty download response")
                FileOutputStream(temporary).use { output -> body.byteStream().use { input -> input.copyTo(output) } }
            }
            val digest = MessageDigest.getInstance("SHA-512")
            FileInputStream(temporary).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expectedSha512, ignoreCase = true)) { "SHA-512 mismatch for ${destination.name}" }
            if (destination.exists()) check(destination.delete()) { "Unable to replace ${destination.name}" }
            check(temporary.renameTo(destination)) { "Unable to finalize ${destination.name}" }
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    fun readText(url: String, serviceName: String): String {
        val request = Request.Builder().url(url).header("User-Agent", BuildConfig.MCSERVER_USER_AGENT).build()
        executeWithRetry(request).use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { httpFailure(serviceName, response.code, body) }
            return body
        }
    }

    private fun executeWithRetry(request: Request): okhttp3.Response {
        var lastFailure: IOException? = null
        for (attempt in 0 until 3) {
            val response = try {
                client.newCall(request).execute()
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt == 2) throw failure
                waitBeforeRetry(300L * (attempt + 1))
                continue
            }
            val retryable = response.code == 429 || response.code in 500..599
            if (!retryable || attempt == 2) return response
            val delayMillis = response.header("Retry-After")
                ?.toLongOrNull()
                ?.times(1000L)
                ?.coerceIn(250L, 5000L)
                ?: (300L * (attempt + 1))
            response.close()
            waitBeforeRetry(delayMillis)
        }
        throw lastFailure ?: IOException("Request could not be completed.")
    }

    private fun waitBeforeRetry(delayMillis: Long) {
        try {
            Thread.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Request retry was interrupted.", interrupted)
        }
    }

    private fun httpFailure(serviceName: String, statusCode: Int, responseBody: String): String {
        val details = responseBody.replace(Regex("\\s+"), " ").trim().take(320)
        return buildString {
            append("$serviceName returned HTTP $statusCode.")
            if (details.isNotBlank()) append(" $details")
        }
    }

    companion object {
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class JavaManager(private val context: Context, private val storage: StorageManager) {
    data class RuntimeSpec(val major: Int, val url: String, val sha256: String)

    companion object {
        private const val RUNTIME_BASE_URL = "https" + "://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download/"
        private val RUNTIMES = mapOf(
            8 to RuntimeSpec(8, "https" + "://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre8/jre8-android-arm64.tar.xz", "9a59124d9791957d55c68be664ab76831f336cf2e1e1cd4414220c6fdbf0e06d"),
            17 to RuntimeSpec(17, RUNTIME_BASE_URL + "jre17-android-arm64.tar.xz", "e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091"),
            21 to RuntimeSpec(21, RUNTIME_BASE_URL + "jre21-android-arm64.tar.xz", "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe"),
            25 to RuntimeSpec(25, RUNTIME_BASE_URL + "jre25-android-arm64.tar.xz", "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37")
        )

        fun requiredMajor(minecraftVersion: String): Int {
            val value = minecraftVersion.trim()
            if (value.startsWith("26.")) return 25
            if (value.startsWith("1.")) {
                val parts = value.removePrefix("1.").split('.')
                val minor = parts.firstOrNull()?.toIntOrNull() ?: 21
                if (minor > 20) return 21
                if (minor == 20) {
                    val patch = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    return if (patch >= 5) 21 else 17
                }
                if (minor >= 17) return 17
                return 8
            }
            return 21
        }
    }

    fun runtimeRoot(javaMajor: Int): File = File(storage.runtimesDir, "jre$javaMajor")

    fun executable(javaMajor: Int): File {
        storage.initialize()
        val executable = File(runtimeRoot(javaMajor), "bin/java")
        check(executable.exists() && executable.canExecute()) {
            "Java $javaMajor is not installed at ${executable.absolutePath}."
        }
        return executable
    }

    fun executable(): File = executable(21)

    fun ensureInstalled(javaMajor: Int, onLog: (String) -> Unit = {}): File {
        storage.initialize()
        val spec = RUNTIMES[javaMajor] ?: error("No Android ARM64 Java runtime package is configured for Java $javaMajor.")
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "This build currently requires an ARM64 Android device." }

        val root = runtimeRoot(javaMajor)
        val installed = File(root, "bin/java")
        if (installed.exists() && installed.canExecute()) return installed

        val archive = File(storage.runtimesDir, "jre$javaMajor.tar.xz")
        if (!archive.exists() || !DownloadManager.sha256Hex(archive).equals(spec.sha256, ignoreCase = true)) {
            onLog("Downloading Android ARM64 Java $javaMajor runtime...")
            DownloadManager(storage).download(spec.url, archive, spec.sha256)
        }

        val tempRoot = File(storage.runtimesDir, "jre$javaMajor.installing")
        if (tempRoot.exists()) tempRoot.deleteRecursively()
        check(tempRoot.mkdirs()) { "Unable to create Java $javaMajor staging directory." }
        onLog("Installing Java $javaMajor runtime...")

        FileInputStream(archive).use { fileInput ->
            XZCompressorInputStream(fileInput).use { xzInput ->
                TarArchiveInputStream(xzInput).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val normalized = entry.name.removePrefix("./")
                        if (normalized.isBlank() || normalized.startsWith("../") || normalized.contains("/../") || normalized.startsWith("/")) continue
                        val target = File(tempRoot, normalized)
                        if (entry.isDirectory) {
                            check(target.mkdirs() || target.isDirectory) { "Unable to create runtime directory ${entry.name}" }
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target, false).use { output -> tar.copyTo(output) }
                            if (normalized == "bin/java") check(target.setExecutable(true, false)) { "Unable to make Java $javaMajor executable." }
                        }
                    }
                }
            }
        }

        val stagedJava = File(tempRoot, "bin/java")
        check(stagedJava.isFile) { "Java $javaMajor package does not contain bin/java." }
        if (root.exists()) root.deleteRecursively()
        check(tempRoot.renameTo(root)) { "Unable to activate Java $javaMajor runtime." }
        check(installed.setExecutable(true, false)) { "Unable to make Java $javaMajor executable." }
        onLog("Java $javaMajor Android runtime installed successfully.")
        return installed
    }

    fun ensureInstalled(onLog: (String) -> Unit = {}): File = ensureInstalled(21, onLog)
}

interface ServerProvider {
    val type: ServerType
    fun install(config: ServerConfig): File
    fun prepare(config: ServerConfig) {}
    fun extraArgs(config: ServerConfig): List<String> = emptyList()
}
class PaperProvider(
    private val storage: StorageManager,
    private val downloads: DownloadManager
) : ServerProvider {
    override val type = ServerType.PAPER

    override fun install(config: ServerConfig): File {
        storage.initialize()
        val jar = File(storage.serverDir, "server.jar")
        if (jar.exists() && jar.length() > 0L) return jar
        val apiUrl = "https://" + "fill.papermc.io/v3/projects/paper/versions/${config.version}/builds"
        val builds = JSONArray(downloads.readText(apiUrl, "Paper downloads API"))
        var selectedUrl: String? = null
        for (i in 0 until builds.length()) {
            val build = builds.getJSONObject(i)
            if (build.optString("channel") != "STABLE") continue
            val downloadsJson = build.optJSONObject("downloads") ?: continue
            val serverKey = "server" + ":" + "default"
            val url = downloadsJson.optJSONObject(serverKey)?.optString("url")
            if (!url.isNullOrBlank()) {
                selectedUrl = url
                break
            }
        }
        check(selectedUrl != null) { "No stable Paper build exists for Minecraft ${config.version}." }
        downloads.download(selectedUrl, jar)
        return jar
    }

    override fun prepare(config: ServerConfig) {
        val properties = File(storage.serverDir, "server.properties")
        setProperty(properties, "server-port", config.javaPort.toString())
        setProperty(properties, "max-players", config.maxPlayers.toString())
        setProperty(properties, "level-name", config.worldName)
        setProperty(properties, "online-mode", "true")
        setProperty(properties, "enable-command-block", "true")
    }

    private fun setProperty(file: File, key: String, value: String) {
        val p = Properties()
        if (file.exists()) FileInputStream(file).use { p.load(it) }
        p.setProperty(key, value)
        FileOutputStream(file).use { p.store(it, null) }
    }
}

class GeyserManager(
    private val storage: StorageManager,
    private val downloads: DownloadManager
) {
    companion object {
        private const val FLOODGATE_BUILD_API = "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest"
        private const val FLOODGATE_DOWNLOAD_URL = "$FLOODGATE_BUILD_API/downloads/spigot"
        private const val FLOODGATE_PLUGIN_FILE = "Floodgate-Spigot.jar"
        private const val FLOODGATE_CONFIG_FILE = "config.yml"
        private const val GEYSER_CONFIG_FILE = "config.yml"
    }

    data class Resolved(
        val versionId: String,
        val versionName: String,
        val fileName: String,
        val sha512: String,
        val downloadUrl: String
    )

    fun resolveCompatibleVersion(minecraftVersion: String): Resolved {
        val gameVersions = java.net.URLEncoder.encode("[\"$minecraftVersion\"]", StandardCharsets.UTF_8.name())
        val loaders = java.net.URLEncoder.encode("[\"paper\"]", StandardCharsets.UTF_8.name())
        val apiUrl = "https://" + "api.modrinth.com/v2/project/geyser/version?game_versions=$gameVersions&loaders=$loaders"
        val versions = JSONArray(URL(apiUrl).readText())
        for (i in 0 until versions.length()) {
            val entry = versions.getJSONObject(i)
            if (entry.optString("status") != "listed") continue
            val files = entry.optJSONArray("files") ?: continue
            var selected: JSONObject? = null
            for (j in 0 until files.length()) {
                val candidate = files.getJSONObject(j)
                if (!candidate.optString("filename").equals("Geyser-Spigot.jar", ignoreCase = true)) continue
                selected = candidate
                if (candidate.optBoolean("primary", false)) break
            }
            val file = selected ?: continue
            val hashes = file.optJSONObject("hashes") ?: continue
            val sha512 = hashes.optString("sha512")
            val url = file.optString("url")
            if (sha512.isNotBlank() && url.isNotBlank()) {
                return Resolved(
                    versionId = entry.getString("id"),
                    versionName = entry.optString("version_number"),
                    fileName = file.optString("filename", "Geyser-Spigot.jar"),
                    sha512 = sha512,
                    downloadUrl = url
                )
            }
        }
        error("Geyser unavailable for Minecraft $minecraftVersion. No listed Paper-compatible release was returned by Modrinth.")
    }

    fun installForPaper(minecraftVersion: String): Resolved {
        val resolved = resolveCompatibleVersion(minecraftVersion)
        storage.initialize()
        val destination = File(storage.pluginsDir, resolved.fileName)
        if (!destination.exists() || destination.length() == 0L) {
            downloads.downloadSha512(resolved.downloadUrl, destination, resolved.sha512)
        }
        return resolved
    }

    fun prepareForPaper(config: ServerConfig) {
        storage.initialize()
        val floodgate = File(storage.pluginsDir, FLOODGATE_PLUGIN_FILE)
        if (!floodgate.isFile || floodgate.length() == 0L) {
            val metadata = JSONObject(downloads.readText(FLOODGATE_BUILD_API, "Floodgate downloads API"))
            val publishedDownloads = metadata.optJSONObject("downloads")
                ?: error("Floodgate downloads API did not include any files.")
            val spigot = publishedDownloads.optJSONObject("spigot")
                ?: publishedDownloads.keys().asSequence()
                    .mapNotNull { key -> publishedDownloads.optJSONObject(key)?.let { key to it } }
                    .firstOrNull { (key, file) ->
                        key.contains("spigot", ignoreCase = true) ||
                            file.optString("name").contains("spigot", ignoreCase = true)
                    }?.second
                ?: error("Floodgate downloads API did not include a Spigot/Paper build.")
            val sha256 = spigot.optString("sha256")
            check(sha256.matches(Regex("(?i)^[a-f0-9]{64}$"))) {
                "Floodgate downloads API did not provide a valid SHA-256 checksum."
            }
            downloads.download(FLOODGATE_DOWNLOAD_URL, floodgate, sha256)
        }

        val geyserJar = storage.pluginsDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("Geyser-Spigot.jar", ignoreCase = true) }
            ?: error("Geyser is not installed. Choose Geyser · Paper and install the server first.")
        val geyserConfig = File(File(storage.pluginsDir, "Geyser-Spigot"), GEYSER_CONFIG_FILE)
        val floodgateConfig = File(File(storage.pluginsDir, "floodgate"), FLOODGATE_CONFIG_FILE)
        ensurePluginConfig(geyserJar, geyserConfig)
        ensurePluginConfig(floodgate, floodgateConfig)
        removeLegacyDottedSection(geyserConfig, "advanced.bedrock")
        setYamlValue(geyserConfig, "bedrock", "address", "0.0.0.0", quote = true)
        setYamlValue(geyserConfig, "bedrock", "port", config.bedrockPort.toString())
        setYamlValue(geyserConfig, "bedrock", "transport", "raknet", quote = true)
        setYamlValue(geyserConfig, "bedrock", "clone-remote-port", "false")
        setYamlValue(geyserConfig, "remote", "port", config.javaPort.toString())
        setYamlValue(geyserConfig, "remote", "auth-type", "floodgate", quote = true)
        setYamlValue(geyserConfig, "advanced.bedrock", "use-haproxy-protocol", "false")
        setYamlValue(geyserConfig, "advanced.java", "use-haproxy-protocol", "false")
        setYamlValue(floodgateConfig, null, "username-prefix", ".", quote = true)
    }

    private fun ensurePluginConfig(pluginJar: File, configFile: File) {
        if (configFile.isFile && configFile.length() > 0L) return
        configFile.parentFile?.mkdirs()
        val bundledConfig = JarFile(pluginJar).use { jar ->
            val entries = jar.entries()
            var selected: java.util.jar.JarEntry? = null
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "config.yml") {
                    selected = entry
                    if (entry.name == "config.yml") break
                }
            }
            selected?.let { entry ->
                jar.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        }
        configFile.writeText(bundledConfig.orEmpty(), StandardCharsets.UTF_8)
    }

    private fun setYamlValue(file: File, section: String?, key: String, value: String, quote: Boolean = false) {
        val lines = if (file.isFile) file.readLines(StandardCharsets.UTF_8).toMutableList() else mutableListOf()
        val sections = section?.split('.')?.filter(String::isNotBlank).orEmpty()
        var sectionIndex = -1
        var sectionIndent = -2
        for (sectionName in sections) {
            val targetIndent = sectionIndent + 2
            val sectionPattern = Regex("^ {${targetIndent}}${Regex.escape(sectionName)}\\s*:\\s*(?:#.*)?$")
            val searchStart = sectionIndex + 1
            val searchEnd = yamlBlockEnd(lines, searchStart, sectionIndent)
            val foundIndex = (searchStart until searchEnd).firstOrNull { sectionPattern.matches(lines[it]) }
            sectionIndex = foundIndex ?: searchEnd
            if (foundIndex == null) {
                lines.add(sectionIndex, " ".repeat(targetIndent) + "$sectionName:")
            }
            sectionIndent = targetIndent
        }

        val propertyIndent = sectionIndent + 2
        val searchStart = sectionIndex + 1
        val searchEnd = yamlBlockEnd(lines, searchStart, sectionIndent)
        val keyPrefix = Regex.escape(" ".repeat(propertyIndent) + key)
        val keyPattern = Regex("^$keyPrefix\\s*:")
        val renderedValue = if (quote) "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else value
        val existingIndex = (searchStart until searchEnd).firstOrNull { keyPattern.containsMatchIn(lines[it]) }
        if (existingIndex != null) {
            val linePattern = Regex("^($keyPrefix\\s*:\\s*)([^#]*?)(\\s+#.*)?$")
            val current = lines[existingIndex]
            val match = linePattern.matchEntire(current)
            lines[existingIndex] = if (match != null) {
                match.groupValues[1] + renderedValue + match.groupValues[3]
            } else {
                " ".repeat(propertyIndent) + "$key: $renderedValue"
            }
        } else {
            lines.add(searchStart.coerceAtMost(lines.size), " ".repeat(propertyIndent) + "$key: $renderedValue")
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n", StandardCharsets.UTF_8)
    }

    private fun yamlBlockEnd(lines: List<String>, start: Int, parentIndent: Int): Int {
        for (index in start until lines.size) {
            val line = lines[index]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.takeWhile { it == ' ' }.length
            if (indent <= parentIndent) return index
        }
        return lines.size
    }

    private fun removeLegacyDottedSection(file: File, sectionName: String) {
        if (!file.isFile) return
        val lines = file.readLines(StandardCharsets.UTF_8).toMutableList()
        val headerPattern = Regex("^${Regex.escape(sectionName)}\\s*:\\s*(?:#.*)?$")
        val headerIndex = lines.indexOfFirst(headerPattern::matches)
        if (headerIndex < 0) return
        var end = headerIndex + 1
        while (end < lines.size) {
            val line = lines[end]
            if (!line.isBlank() && !line.trimStart().startsWith("#") && line.takeWhile { it == ' ' }.length == 0) break
            end++
        }
        lines.subList(headerIndex, end).clear()
        file.writeText(lines.joinToString("\n") + "\n", StandardCharsets.UTF_8)
    }
}

class GeyserProvider(
    private val paper: PaperProvider,
    private val geyser: GeyserManager
) : ServerProvider {
    override val type = ServerType.GEYSER

    override fun install(config: ServerConfig): File {
        val jar = paper.install(config)
        geyser.installForPaper(config.version)
        geyser.prepareForPaper(config)
        return jar
    }

    override fun prepare(config: ServerConfig) {
        paper.prepare(config)
        geyser.prepareForPaper(config)
    }
}

class CatalogProvider(
    override val type: ServerType,
    private val message: String
) : ServerProvider {
    override fun install(config: ServerConfig): File = error("$message (${config.version}).")
}

class VanillaProvider(
    private val storage: StorageManager,
    private val downloads: DownloadManager
) : ServerProvider {
    override val type = ServerType.VANILLA

    override fun install(config: ServerConfig): File {
        storage.initialize()
        val jar = File(storage.serverDir, "server.jar")
        if (jar.exists() && jar.length() > 0L) return jar
        val manifest = JSONObject(URL("https" + "://" + "piston-meta.mojang.com/mc/game/version_manifest_v2.json").readText())
        val versions = manifest.optJSONArray("versions") ?: error("Mojang version manifest is missing versions.")
        val entry = (0 until versions.length()).asSequence()
            .map { versions.getJSONObject(it) }
            .firstOrNull { it.optString("id") == config.version }
            ?: error("Minecraft ${config.version} is not present in the Mojang release manifest.")
        val metaUrl = entry.optString("url")
        check(metaUrl.isNotBlank()) { "Minecraft ${config.version} has no metadata URL." }
        val metadata = JSONObject(URL(metaUrl).readText())
        val server = metadata.optJSONObject("downloads")?.optJSONObject("server")
            ?: error("Minecraft ${config.version} does not publish a server jar.")
        val url = server.optString("url")
        check(url.isNotBlank()) { "Minecraft ${config.version} has no server download URL." }
        downloads.download(url, jar)
        return jar
    }

    override fun prepare(config: ServerConfig) = ServerPropertySupport.prepare(storage, config)
}

class PurpurProvider(
    private val storage: StorageManager,
    private val downloads: DownloadManager
) : ServerProvider {
    override val type = ServerType.PURPUR

    override fun install(config: ServerConfig): File {
        storage.initialize()
        val jar = File(storage.serverDir, "server.jar")
        if (jar.exists() && jar.length() > 0L) return jar
        val api = JSONObject(URL("https" + "://" + "api.purpurmc.org/v2/purpur/${config.version}").readText())
        val latest = api.optJSONObject("builds")?.optInt("latest", -1) ?: -1
        check(latest > 0) { "No Purpur build is available for Minecraft ${config.version}." }
        val url = "https" + "://" + "api.purpurmc.org/v2/purpur/${config.version}/${latest}/download"
        downloads.download(url, jar)
        return jar
    }

    override fun prepare(config: ServerConfig) = ServerPropertySupport.prepare(storage, config)
}

class FabricProvider(
    private val storage: StorageManager,
    private val downloads: DownloadManager
) : ServerProvider {
    override val type = ServerType.FABRIC

    override fun install(config: ServerConfig): File {
        storage.initialize()
        val jar = File(storage.serverDir, "server.jar")
        if (jar.exists() && jar.length() > 0L) return jar
        val loaders = JSONArray(URL("https" + "://" + "meta.fabricmc.net/v2/versions/loader/${config.version}").readText())
        check(loaders.length() > 0) { "Fabric does not currently publish a loader for Minecraft ${config.version}." }
        val loader = loaders.getJSONObject(0).optString("loader", "")
        check(loader.isNotBlank()) { "Fabric loader metadata is missing for Minecraft ${config.version}." }
        val installers = JSONArray(URL("https" + "://" + "meta.fabricmc.net/v2/versions/installer").readText())
        var installer = ""
        for (index in 0 until installers.length()) {
            val candidate = installers.getJSONObject(index)
            if (candidate.optBoolean("stable", false)) {
                installer = candidate.optString("version")
                break
            }
        }
        if (installer.isBlank() && installers.length() > 0) installer = installers.getJSONObject(0).optString("version")
        check(installer.isNotBlank()) { "Fabric installer metadata is unavailable." }
        val url = "https" + "://" + "meta.fabricmc.net/v2/versions/loader/${config.version}/${loader}/${installer}/server/jar"
        downloads.download(url, jar)
        return jar
    }

    override fun prepare(config: ServerConfig) = ServerPropertySupport.prepare(storage, config)
}

class ForgeProvider(
    private val context: Context,
    private val storage: StorageManager,
    private val downloads: DownloadManager,
    private val java: JavaManager
) : ServerProvider {
    override val type = ServerType.FORGE

    override fun install(config: ServerConfig): File {
        storage.initialize()
        val launcher = File(storage.serverDir, "run.sh")
        if (launcher.exists() && launcher.length() > 0L) return launcher
        val metadata = URL("https" + "://" + "maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml").readText()
        val versions = Regex("<version>([^<]+)</version>").findAll(metadata).map { it.groupValues[1] }.toList()
        val selected = versions.lastOrNull { it.startsWith(config.version + "-") }
            ?: error("No Forge installer is published for Minecraft ${config.version}.")
        val installer = File(storage.serverDir, "forge-installer-$selected.jar")
        downloads.download("https" + "://" + "maven.minecraftforge.net/net/minecraftforge/forge/$selected/forge-$selected-installer.jar", installer)
        runInstaller(installer, JavaManager.Companion.requiredMajor(config.version))
        check(launcher.exists() && launcher.length() > 0L) { "Forge installer completed but run.sh was not created." }
        launcher.setExecutable(true, false)
        return launcher
    }

    override fun prepare(config: ServerConfig) = ServerPropertySupport.prepare(storage, config)

    private fun runInstaller(installer: File, major: Int) {
        val javaExecutable = java.ensureInstalled(major)
        val runtimeRoot = java.runtimeRoot(major)
        val lib = runtimeRoot.resolve("lib")
        val serverLib = lib.resolve("server")
        val launcher = listOf(javaExecutable.absolutePath, "-jar", installer.name, "--installServer")
            .joinToString(" ") { shellQuote(it) }
        val command = "export JAVA_HOME=${shellQuote(runtimeRoot.absolutePath)}; export LD_LIBRARY_PATH=${shellQuote(listOf(storage.nativeLibraryDir, lib, serverLib).filter { it.isDirectory }.joinToString(":"))}; export PATH=${shellQuote(runtimeRoot.resolve("bin").absolutePath)}:\$PATH; exec $launcher"
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(storage.serverDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "Forge installer failed (exit $exit):\n$output" }
    }
}

class NeoForgeProvider(
    private val storage: StorageManager,
    private val downloads: DownloadManager,
    private val java: JavaManager
) : ServerProvider {
    override val type = ServerType.NEOFORGE

    override fun install(config: ServerConfig): File {
        storage.initialize()
        val launcher = File(storage.serverDir, "run.sh")
        if (launcher.exists() && launcher.length() > 0L) return launcher
        val metadata = URL("https" + "://" + "maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml").readText()
        val family = neoForgeFamily(config.version)
        val versions = Regex("<version>([^<]+)</version>").findAll(metadata).map { it.groupValues[1] }.toList()
        val selected = versions.lastOrNull { it.startsWith(family) }
            ?: error("No NeoForge installer is published for Minecraft ${config.version}.")
        val installer = File(storage.serverDir, "neoforge-installer-$selected.jar")
        downloads.download("https" + "://" + "maven.neoforged.net/releases/net/neoforged/neoforge/$selected/neoforge-$selected-installer.jar", installer)
        runInstaller(installer, JavaManager.Companion.requiredMajor(config.version))
        check(launcher.exists() && launcher.length() > 0L) { "NeoForge installer completed but run.sh was not created." }
        launcher.setExecutable(true, false)
        return launcher
    }

    override fun prepare(config: ServerConfig) = ServerPropertySupport.prepare(storage, config)

    private fun runInstaller(installer: File, major: Int) {
        val javaExecutable = java.ensureInstalled(major)
        val runtimeRoot = java.runtimeRoot(major)
        val lib = runtimeRoot.resolve("lib")
        val serverLib = lib.resolve("server")
        val launcher = listOf(javaExecutable.absolutePath, "-jar", installer.name, "--installServer")
            .joinToString(" ") { shellQuote(it) }
        val command = "export JAVA_HOME=${shellQuote(runtimeRoot.absolutePath)}; export LD_LIBRARY_PATH=${shellQuote(listOf(storage.nativeLibraryDir, lib, serverLib).filter { it.isDirectory }.joinToString(":"))}; export PATH=${shellQuote(runtimeRoot.resolve("bin").absolutePath)}:\$PATH; exec $launcher"
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(storage.serverDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "NeoForge installer failed (exit $exit):\n$output" }
    }

    private fun neoForgeFamily(version: String): String {
        if (version.startsWith("26.")) return version.substringBeforeLast('.') + "."
        if (!version.startsWith("1.")) return version.substringBeforeLast('.') + "."
        val parts = version.removePrefix("1.").split('.')
        val minor = parts.getOrNull(0) ?: error("Invalid Minecraft version $version")
        val patch = parts.getOrNull(1) ?: "0"
        return "$minor.$patch."
    }
}

object ServerPropertySupport {
    fun prepare(storage: StorageManager, config: ServerConfig) {
        val properties = File(storage.serverDir, "server.properties")
        setProperty(properties, "server-port", config.javaPort.toString())
        setProperty(properties, "max-players", config.maxPlayers.toString())
        setProperty(properties, "level-name", config.worldName)
        setProperty(properties, "online-mode", "true")
        setProperty(properties, "enable-command-block", "true")
    }

    private fun setProperty(file: File, key: String, value: String) {
        val p = Properties()
        if (file.exists()) FileInputStream(file).use { p.load(it) }
        p.setProperty(key, value)
        FileOutputStream(file).use { p.store(it, null) }
    }
}

class ServerProviderManager(context: Context) {
    private val storage = StorageManager(context)
    private val downloads = DownloadManager(storage)
    private val java = JavaManager(context, storage)
    private val vanilla = VanillaProvider(storage, downloads)
    private val paper = PaperProvider(storage, downloads)
    private val purpur = PurpurProvider(storage, downloads)
    private val fabric = FabricProvider(storage, downloads)
    private val forge = ForgeProvider(context, storage, downloads, java)
    private val neoforge = NeoForgeProvider(storage, downloads, java)
    private val geyser = GeyserManager(storage, downloads)

    private val providers: Map<ServerType, ServerProvider> = mapOf(
        ServerType.VANILLA to vanilla,
        ServerType.PAPER to paper,
        ServerType.PURPUR to purpur,
        ServerType.FABRIC to fabric,
        ServerType.FORGE to forge,
        ServerType.NEOFORGE to neoforge,
        ServerType.GEYSER to GeyserProvider(paper, geyser),
        ServerType.SPIGOT to CatalogProvider(ServerType.SPIGOT, "Spigot installation is not enabled yet"),
        ServerType.BUKKIT to CatalogProvider(ServerType.BUKKIT, "Bukkit installation is not enabled yet")
    )

    fun provider(type: ServerType): ServerProvider = providers[type] ?: error("Unsupported server type: $type")
}

class ProcessManager(private val storage: StorageManager, private val java: JavaManager) {
    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var stopRequested = false

    fun start(config: ServerConfig, jar: File, args: List<String>, onLog: (String) -> Unit, onExit: (Boolean) -> Unit = {}): Boolean {
        synchronized(stateLock) {
            check(!running.get()) { "A Minecraft process is already running." }
            check(jar.exists() && jar.length() > 0L) { "Server jar is missing or empty." }
            storage.initialize()
            val requiredJava = JavaManager.requiredMajor(config.version)
            val javaExecutable = java.ensureInstalled(requiredJava, onLog)
            val runtimeRoot = java.runtimeRoot(requiredJava)
            val lib = runtimeRoot.resolve("lib")
            val serverLib = lib.resolve("server")
            val forgeLike = config.type == ServerType.FORGE || config.type == ServerType.NEOFORGE
            val command = if (forgeLike) {
                val jvmArgsFile = File(storage.serverDir, "user_jvm_args.txt")
                FileOutputStream(jvmArgsFile, false).use { output ->
                    output.write(("-Xms${config.ramMb}M\n-Xmx${config.ramMb}M\n-Djna.nosys=true\n-Djna.boot.library.path=${storage.nativeLibraryDir.absolutePath}\n").toByteArray(StandardCharsets.UTF_8))
                }
                mutableListOf("/system/bin/sh", jar.name) + args + "nogui"
            } else {
                val tempDir = File(storage.serverDir, "tmp").apply { mkdirs() }
                mutableListOf(
                    javaExecutable.absolutePath,
                    "-Djava.io.tmpdir=${tempDir.absolutePath}",
                    "-Xms${config.ramMb}M",
                    "-Xmx${config.ramMb}M",
                    "-jar",
                    jar.name
                ).apply {
                    if (config.type == ServerType.PAPER || config.type == ServerType.GEYSER) {
                        add(1, "-DPaper.IgnoreJavaVersion=true")
                    }
                    addAll(args)
                    add("nogui")
                }
            }
            val shellCommand = buildString {
                append("export JAVA_HOME=").append(shellQuote(runtimeRoot.absolutePath)).append("; ")
                append("export LD_LIBRARY_PATH=").append(shellQuote(listOf(storage.nativeLibraryDir, lib, serverLib).joinToString(":"))).append("; ")
                append("export PATH=").append(shellQuote(runtimeRoot.resolve("bin").absolutePath)).append(":/system/bin; ")
                append("exec ")
                append(command.joinToString(" ") { shellQuote(it) })
            }
            return try {
                val started = ProcessBuilder("/system/bin/sh", "-c", shellCommand)
                    .directory(storage.serverDir)
                    .redirectErrorStream(true)
                    .start()
                process = started
                writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
                stopRequested = false
                running.set(true)

                Thread {
                    runCatching {
                        started.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                appendLog(line)
                                onLog(line)
                            }
                        }
                    }
                }.apply { name = "MCSERVER-log" }.start()

                Thread {
                    val exitCode = runCatching { started.waitFor() }.getOrDefault(-1)
                    val crashed = synchronized(stateLock) { !stopRequested }
                    synchronized(stateLock) {
                        runCatching { writer?.close() }
                        writer = null
                        process = null
                        running.set(false)
                    }
                    appendLog("Process exited with code $exitCode")
                    onExit(crashed)
                }.apply { name = "MCSERVER-wait" }.start()
                true
            } catch (t: Throwable) {
                writer = null
                process = null
                running.set(false)
                throw t
            }
        }
    }

    fun stop(force: Boolean = false) {
        val current = synchronized(stateLock) { process } ?: return
        synchronized(stateLock) { stopRequested = true }
        if (!force) {
            runCatching { send("stop") }
            if (!current.waitFor(15, TimeUnit.SECONDS)) current.destroy()
            if (current.isAlive) current.destroyForcibly()
        } else {
            current.destroyForcibly()
            current.waitFor(5, TimeUnit.SECONDS)
        }
        synchronized(stateLock) {
            runCatching { writer?.close() }
            writer = null
            process = null
            running.set(false)
        }
    }

    fun send(command: String) {
        val currentWriter = writer ?: error("Minecraft server is not running.")
        synchronized(stateLock) {
            currentWriter.write(command.trim())
            currentWriter.newLine()
            currentWriter.flush()
        }
    }

    fun isRunning(): Boolean = running.get()

    private fun appendLog(line: String) {
        storage.initialize()
        FileOutputStream(storage.logFile(), true).use { output ->
            output.write((line + System.lineSeparator()).toByteArray(StandardCharsets.UTF_8))
        }
    }
}

class ServerManager(context: Context) {
    val storage = StorageManager(context)
    private val configuration = ConfigurationManager(storage)
    private val providers = ServerProviderManager(context)
    private val java = JavaManager(context, storage)
    private val process = ProcessManager(storage, java)
    private val backups = BackupManager(storage)
    private val playit = PlayitManager(context.applicationContext, storage)
    private val startInProgress = AtomicBoolean(false)
    @Volatile private var startError: String? = null
    @Volatile private var network = NetworkStatus(message = "Tailscale manages public networking")

    fun create(config: ServerConfig) {
        storage.initialize()
        check(!process.isRunning()) { "Stop the current server before replacing it." }
        configuration.save(config)
    }

    fun eulaAccepted(): Boolean {
        val file = storage.eulaFile()
        if (!file.exists()) return false
        return file.readLines(StandardCharsets.UTF_8).any { it.trim().equals("eula=true", ignoreCase = true) }
    }

    fun acceptEula() {
        storage.initialize()
        FileOutputStream(storage.eulaFile(), false).use { output ->
            output.write("# Minecraft EULA accepted by the MCSERVER setup\neula=true\n".toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun config(): ServerConfig? = configuration.load()

    fun localtonetConfig(): LocaltonetConfig = configuration.loadLocaltonet()

    fun saveLocaltonet(config: LocaltonetConfig) {
        configuration.saveLocaltonet(config)
        network = if (config.enabled) {
            NetworkStatus(
                connected = false,
                publicHost = config.javaHost,
                javaPort = config.javaPort,
                bedrockPort = config.bedrockPort,
                message = if (config.javaHost.isNotBlank() || config.bedrockHost.isNotBlank()) {
                    "Localtonet configured; start the TCP/UDP tunnel from the Localtonet dashboard"
                } else {
                    "Localtonet enabled; enter the public tunnel endpoints"
                }
            )
        } else {
            NetworkStatus(message = "Public tunnel is not configured")
        }
    }

    fun availablePaperVersions(): List<String> {
        val apiUrl = "https://" + "fill.papermc.io/v3/projects/paper"
        val root = JSONObject(DownloadManager(storage).readText(apiUrl, "Paper downloads API"))
        val versions = root.optJSONObject("versions") ?: return emptyList()
        val result = mutableListOf<String>()
        val keys = versions.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = versions.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val version = array.optString(index)
                if (version.isNotBlank() && !version.contains("pre", true) && !version.contains("rc", true) && version !in result) {
                    result += version
                }
            }
        }
        return result.take(60)
    }

    fun availableMinecraftVersions(): List<String> {
        val manifestUrl = "https" + "://" + "piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        val manifest = JSONObject(URL(manifestUrl).readText())
        val versions = manifest.optJSONArray("versions") ?: return emptyList()
        val result = mutableListOf<String>()
        for (index in 0 until versions.length()) {
            val entry = versions.optJSONObject(index) ?: continue
            if (entry.optString("type") != "release") continue
            val id = entry.optString("id")
            if (id.isNotBlank()) result += id
        }
        return result
    }

    fun requiredJavaForVersion(version: String): Int = JavaManager.Companion.requiredMajor(version)

    fun isStarting(): Boolean = startInProgress.get()

    fun lastStartError(): String? = startError

    fun install(config: ServerConfig = loadConfig()): File {
        storage.initialize()
        val provider = providers.provider(config.type)
        val jar = provider.install(config)
        provider.prepare(config)
        return jar
    }

    fun start(config: ServerConfig, onLog: (String) -> Unit, onExit: (Boolean) -> Unit = {}): Boolean {
        return withStartGuard {
            check(eulaAccepted()) { "Minecraft EULA has not been accepted. Accept it in the setup screen before starting the server." }
            val jar = File(storage.serverDir, "server.jar")
            check(jar.exists()) { "Server jar is not installed yet." }
            val provider = providers.provider(config.type)
            provider.prepare(config)
            val started = process.start(config, jar, provider.extraArgs(config), onLog, onExit)
            if (started && playit.hasSecret() && playit.agentInstalled()) {
                playit.startAgent().onFailure { onLog("Playit agent could not start: ${it.message ?: "unknown error"}") }
            }
            started
        }
    }

    fun start(onLog: (String) -> Unit, onExit: (Boolean) -> Unit = {}): Boolean {
        return withStartGuard {
            val config = loadConfig()
            val jar = install(config)
            val provider = providers.provider(config.type)
            val started = process.start(config, jar, provider.extraArgs(config), onLog, onExit)
            if (started && playit.hasSecret() && playit.agentInstalled()) {
                playit.startAgent().onFailure { onLog("Playit agent could not start: ${it.message ?: "unknown error"}") }
            }
            started
        }
    }

    private fun withStartGuard(action: () -> Boolean): Boolean {
        check(startInProgress.compareAndSet(false, true)) { "Server setup is already in progress." }
        startError = null
        return try {
            action()
        } catch (failure: Throwable) {
            startError = failure.message ?: "Server setup failed."
            throw failure
        } finally {
            startInProgress.set(false)
        }
    }

    fun stop(force: Boolean = false) {
        network = NetworkStatus(message = "Server stopped; Tailscale remains managed by Network")
        process.stop(force)
        playit.stopAgent()
    }

    fun hasPlayitSecret(): Boolean = playit.hasSecret()
    fun playitPendingClaimUrl(): String? = playit.pendingClaimUrl()
    fun playitJavaAddress(): String? = playit.savedAddress("java")
    fun playitBedrockAddress(): String? = playit.savedAddress("bedrock")
    fun playitAgentVersion(): String = PlayitManager.AGENT_VERSION
    fun beginPlayitClaim(): Result<String> = playit.beginClaim()
    fun finishPlayitClaim(): Result<PlayitSetupSummary> = runCatching {
        playit.finishClaim()
        setupPlayitPublicAccess().getOrThrow()
    }
    fun setupPlayitPublicAccess(): Result<PlayitSetupSummary> = runCatching {
        val config = loadConfig()
        val result = playit.setupTunnels(config)
        if (result.bedrockAddress != null && config.type == ServerType.GEYSER) {
            val port = result.bedrockAddress.substringAfterLast(':').toIntOrNull()
            if (port != null) {
                val updated = playit.configureGeyserBedrockPort(port)
                if (!updated) result.restartRequired = true
            }
        }
        if (isRunning()) {
            playit.stopAgent()
            playit.startAgent().getOrThrow()
        }
        playit.clearPendingClaim()
        playit.refreshTunnelAddresses(result)
    }

    fun sendCommand(command: String) = process.send(command)
    fun isRunning(): Boolean = process.isRunning()

    fun loadConfig(): ServerConfig = config() ?: error("Create a server first.")
    fun createBackup(): File = backups.createBackup()

    fun restoreBackup(backupName: String): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before restoring a backup." }
        require(backupName.startsWith("backup-") && '/' !in backupName && '\\' !in backupName) {
            "Invalid backup name."
        }
        val backup = safeServerPath("backups/$backupName")
        require(backup.isDirectory && backup.canonicalFile.parentFile == storage.backupsDir.canonicalFile) {
            "Backup does not exist."
        }
        createBackup()
        storage.serverDir.listFiles().orEmpty()
            .filter { it.canonicalFile != storage.backupsDir.canonicalFile }
            .forEach(::deleteRecursively)
        copyDirectoryContents(backup, storage.serverDir)
    }

    fun deleteBackup(backupName: String): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before deleting backups." }
        require(backupName.startsWith("backup-") && '/' !in backupName && '\\' !in backupName) {
            "Invalid backup name."
        }
        val backup = safeServerPath("backups/$backupName")
        require(backup.isDirectory && backup.canonicalFile.parentFile == storage.backupsDir.canonicalFile) {
            "Backup does not exist."
        }
        deleteRecursively(backup)
    }

    fun playitPluginInstalled(): Boolean = storage.pluginsDir.listFiles()
        ?.any { it.isFile && it.name.contains("playit", ignoreCase = true) && it.extension.equals("jar", true) }
        ?: false

    fun installPlayitPlugin(): Result<File> = runCatching {
        require(!isRunning()) { "Stop the server before installing the Playit plugin." }
        val type = config()?.type ?: error("Create a server before adding a public tunnel.")
        require(type in setOf(ServerType.PAPER, ServerType.PURPUR, ServerType.GEYSER)) {
            "Playit's official plugin needs a Paper-based server. Switch to Paper or Geyser first."
        }
        storage.initialize()
        val downloads = DownloadManager(storage)
        val release = JSONObject(downloads.readText(
            "https://api.github.com/repos/playit-cloud/playit-minecraft-plugin/releases/latest",
            "Playit release service"
        ))
        val assets = release.optJSONArray("assets") ?: error("Playit release did not include downloadable files.")
        val asset = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name") == "playit-minecraft-plugin.jar" }
            ?: error("The latest Playit release did not include its server plugin.")
        val url = asset.optString("browser_download_url")
        require(url.startsWith("https://github.com/playit-cloud/playit-minecraft-plugin/")) {
            "The Playit plugin download URL was not an official release URL."
        }
        val digest = asset.optString("digest")
            .removePrefix("sha256:")
            .takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
        val staged = File(storage.pluginsDir, ".playit-plugin-download.jar")
        val destination = File(storage.pluginsDir, "playit-minecraft-plugin.jar")
        try {
            downloads.download(url, staged, digest)
            JarFile(staged).use { jar ->
                val descriptor = jar.getJarEntry("plugin.yml") ?: error("The Playit download is not a Minecraft plugin.")
                val metadata = jar.getInputStream(descriptor).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                require(metadata.contains("gg.playit.minecraft.PlayitBukkit")) {
                    "The Playit plugin entry point was missing."
                }
            }
            if (destination.exists()) check(destination.delete()) { "Could not replace the existing Playit plugin." }
            check(staged.renameTo(destination)) { "Could not finish installing the Playit plugin." }
        } finally {
            staged.delete()
        }
        destination
    }

    fun requiredJavaVersion(): Int = JavaManager.Companion.requiredMajor(config()?.version ?: "1.21.10")

    fun javaStatus(): String {
        val major = requiredJavaVersion()
        return runCatching {
            java.executable(major)
            "Java $major is ready"
        }.getOrElse { "Java $major needs setup" }
    }

    fun autoSetupJava(): Result<String> = runCatching {
        val major = requiredJavaVersion()
        val executable = java.ensureInstalled(major)
        val runtimeRoot = java.runtimeRoot(major)
        val lib = runtimeRoot.resolve("lib")
        val serverLib = lib.resolve("server")
        check(lib.isDirectory) { "Java $major lib directory is missing: ${lib.absolutePath}" }
        val shellCommand = buildString {
            append("export JAVA_HOME=").append(shellQuote(runtimeRoot.absolutePath)).append("; ")
            append("export LD_LIBRARY_PATH=").append(shellQuote(listOf(storage.nativeLibraryDir, lib, serverLib).filter { it.isDirectory }.joinToString(":"))).append("; ")
            append("exec ").append(shellQuote(executable.absolutePath)).append(" -version")
        }
        val started = ProcessBuilder("/system/bin/sh", "-c", shellCommand)
            .directory(storage.serverDir)
            .redirectErrorStream(true)
            .start()
        val output = started.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText().trim() }
        val exit = started.waitFor()
        check(exit == 0) { "Java $major validation failed (exit $exit):\n$output" }
        output.ifBlank { "Java $major Android runtime is ready." }
    }

    fun serverProperties(): Map<String, String> {
        val file = File(storage.serverDir, "server.properties")
        if (!file.exists()) return emptyMap()
        val properties = Properties()
        FileInputStream(file).use { properties.load(it) }
        return properties.stringPropertyNames().sorted().associateWith { properties.getProperty(it, "") }
    }

    fun setServerProperty(key: String, value: String) {
        require(key.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid server.properties key." }
        val file = File(storage.serverDir, "server.properties")
        storage.initialize()
        val properties = Properties()
        if (file.exists()) FileInputStream(file).use { properties.load(it) }
        properties.setProperty(key, value)
        FileOutputStream(file).use { properties.store(it, null) }
    }

    fun whitelistAdd(player: String) = sendCommand("whitelist add ${player.trim()}")
    fun whitelistRemove(player: String) = sendCommand("whitelist remove ${player.trim()}")
    fun banPlayer(player: String, reason: String = "") = sendCommand("ban ${player.trim()} ${reason.trim()}".trim())
    fun pardonPlayer(player: String) = sendCommand("pardon ${player.trim()}")
    fun kickPlayer(player: String, reason: String = "") = sendCommand("kick ${player.trim()} ${reason.trim()}".trim())

    fun recentLogs(maxLines: Int = 200): List<String> {
        val file = storage.logFile()
        if (!file.exists()) return emptyList()
        return file.readLines(StandardCharsets.UTF_8).takeLast(maxLines)
    }

    fun pluginFiles(): List<File> = storage.pluginsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
    fun modFiles(): List<File> = storage.modsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun playerSummary(): String {
        if (!isRunning()) return "Server offline."
        val regex = Regex("There are (\\d+) of a max of (\\d+) players online")
        val match = recentLogs(500).asReversed().firstNotNullOfOrNull { regex.find(it) }
        return if (match != null) {
            "${match.groupValues[1]} / ${match.groupValues[2]} players online (latest server status)"
        } else {
            "Server online. Live player data will appear after the server reports it."
        }
    }

    fun networkStatus(): NetworkStatus = network

    fun listBackups(): List<File> = storage.backupsDir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        .orEmpty()

    private fun safeServerPath(relativePath: String): File {
        storage.initialize()
        val normalized = relativePath.trim().trim('/').replace('\\', '/')
        require(normalized.split('/').none { it == ".." }) { "Invalid server path." }
        val root = storage.serverDir.canonicalFile
        val target = File(root, normalized.ifBlank { "." }).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Invalid server path."
        }
        return target
    }

    private fun relativeServerPath(file: File): String {
        val rootPath = storage.serverDir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        val relative = rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
        return if (relative == ".") "" else relative
    }

    fun listServerFiles(relativePath: String = ""): List<FileEntry> {
        val directory = safeServerPath(relativePath)
        require(directory.isDirectory) { "Not a directory." }
        return directory.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { file ->
                FileEntry(
                    name = file.name,
                    relativePath = relativeServerPath(file),
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L
                )
            }
            .orEmpty()
    }

    fun createServerFolder(relativePath: String): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before changing files." }
        val normalized = relativePath.trim().replace('\\', '/')
        val clean = normalized.substringAfterLast('/')
        require(clean.isNotBlank() && clean != "." && clean != ".." && '\u0000' !in clean) {
            "Enter one folder name."
        }
        val folder = safeServerPath(normalized)
        require(folder != storage.serverDir.canonicalFile) { "Cannot recreate the server root." }
        require(folder.parentFile?.isDirectory == true) { "The current folder does not exist." }
        require(folder.mkdirs() || folder.isDirectory) { "Could not create folder." }
    }

    fun deleteServerPath(relativePath: String): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before deleting files." }
        val target = safeServerPath(relativePath)
        require(target != storage.serverDir.canonicalFile) { "Cannot delete the server root here." }
        require(target.exists()) { "Path does not exist." }
        deleteRecursively(target)
    }

    fun renameServerPath(relativePath: String, newName: String): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before renaming files." }
        val target = safeServerPath(relativePath)
        require(target != storage.serverDir.canonicalFile && target.exists()) { "Path does not exist." }
        val cleanName = newName.trim()
        require(cleanName.isNotBlank() && cleanName != "." && cleanName != ".." &&
            '/' !in cleanName && '\\' !in cleanName && '\u0000' !in cleanName) {
            "Enter a valid name."
        }
        val destination = safeServerPath(relativeServerPath(File(target.parentFile, cleanName)))
        require(!destination.exists()) { "An item with that name already exists." }
        require(target.renameTo(destination)) { "Could not rename ${target.name}." }
    }

    fun readServerText(relativePath: String): Result<String> = runCatching {
        val file = safeServerPath(relativePath)
        require(file.isFile) { "Choose a file to preview." }
        require(file.length() <= 512L * 1024L) { "This file is larger than the 512 KB preview limit." }
        val bytes = file.readBytes()
        require(bytes.none { it == 0.toByte() }) { "This looks like a binary file and cannot be edited here." }
        bytes.toString(StandardCharsets.UTF_8)
    }

    fun saveServerText(relativePath: String, contents: String): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before editing files." }
        val file = safeServerPath(relativePath)
        require(file.isFile) { "Choose a file to edit." }
        val encoded = contents.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= 512 * 1024) { "This file is larger than the 512 KB edit limit." }
        val temporary = File(file.parentFile, ".${file.name}.editing")
        try {
            FileOutputStream(temporary).use { it.write(encoded) }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
    }

    fun copyServerFileTo(relativePath: String, resolver: ContentResolver, uri: Uri): Result<Unit> = runCatching {
        val file = safeServerPath(relativePath)
        require(file.isFile) { "Choose a file to download." }
        resolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: error("Could not open the selected destination.")
    }

    fun importServerFile(
        resolver: ContentResolver,
        uri: Uri,
        targetDirectory: String,
        fileName: String
    ): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before uploading files." }
        val cleanName = fileName.trim().replace('\\', '/')
        require(cleanName.isNotBlank() && !cleanName.contains('/') && '\u0000' !in cleanName) { "Invalid file name." }
        val directory = safeServerPath(targetDirectory)
        require(directory.isDirectory) { "Target directory does not exist." }
        val destination = File(directory, cleanName).canonicalFile
        val root = storage.serverDir.canonicalFile
        require(destination.path.startsWith(root.path + File.separator)) { "Invalid destination." }
        val temporary = File(directory, ".${cleanName}.part")
        require(!destination.exists()) { "${destination.name} already exists. Delete or rename it before uploading." }
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open selected file.")
            require(temporary.renameTo(destination)) { "Could not finish upload." }
        } finally {
            temporary.delete()
        }
    }

    fun worldSettings(): WorldSettings {
        val p = serverProperties()
        return WorldSettings(
            seed = p["level-seed"].orEmpty(),
            levelType = p["level-type"]?.ifBlank { "minecraft:normal" } ?: "minecraft:normal",
            difficulty = p["difficulty"]?.ifBlank { "normal" } ?: "normal",
            gamemode = p["gamemode"]?.ifBlank { "survival" } ?: "survival",
            generateStructures = p["generate-structures"]?.equals("true", ignoreCase = true) ?: true,
            hardcore = p["hardcore"]?.equals("true", ignoreCase = true) ?: false,
            pvp = p["pvp"]?.equals("true", ignoreCase = true) ?: true,
            viewDistance = p["view-distance"]?.toIntOrNull()?.coerceIn(2, 32) ?: 10,
            simulationDistance = p["simulation-distance"]?.toIntOrNull()?.coerceIn(2, 32) ?: 10
        )
    }

    fun applyWorldSettings(settings: WorldSettings): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before changing world settings." }
        setServerProperty("level-seed", settings.seed)
        setServerProperty("level-type", settings.levelType)
        setServerProperty("difficulty", settings.difficulty)
        setServerProperty("gamemode", settings.gamemode)
        setServerProperty("generate-structures", settings.generateStructures.toString())
        setServerProperty("hardcore", settings.hardcore.toString())
        setServerProperty("pvp", settings.pvp.toString())
        setServerProperty("view-distance", settings.viewDistance.coerceIn(2, 32).toString())
        setServerProperty("simulation-distance", settings.simulationDistance.coerceIn(2, 32).toString())
    }

    fun generateWorld(settings: WorldSettings): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before generating a world." }
        applyWorldSettings(settings).getOrThrow()
        resetConfiguredWorld()
    }

    fun resetWorld(): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before resetting the world." }
        resetConfiguredWorld()
    }

    private fun resetConfiguredWorld() {
        val worldName = config()?.worldName?.trim().orEmpty().ifBlank { "world" }
        val world = safeServerPath(worldName)
        val nether = safeServerPath("${worldName}_nether")
        val end = safeServerPath("${worldName}_the_end")
        if (world.exists()) deleteRecursively(world)
        if (nether.exists()) deleteRecursively(nether)
        if (end.exists()) deleteRecursively(end)
        world.mkdirs()
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        check(!file.exists() || file.delete()) { "Could not delete ${file.name}." }
    }

    fun exportWorld(): File = run {
        require(!isRunning()) { "Stop the server before exporting the world." }
        val worldName = config()?.worldName?.trim().orEmpty().ifBlank { "world" }
        val source = safeServerPath(worldName)
        require(source.isDirectory) { "World directory does not exist." }
        val exportDir = File(storage.root, "exports").apply { mkdirs() }
        val destination = File(exportDir, "${worldName}-${System.currentTimeMillis()}.zip")
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zipDirectory(source, worldName, zip)
        }
        destination
    }

    private fun zipDirectory(directory: File, path: String, zip: ZipOutputStream) {
        val files = directory.listFiles().orEmpty()
        if (files.isEmpty()) {
            zip.putNextEntry(ZipEntry("$path/"))
            zip.closeEntry()
            return
        }
        files.forEach { file ->
            val entryPath = "$path/${file.name}"
            if (file.isDirectory) {
                zipDirectory(file, entryPath, zip)
            } else {
                zip.putNextEntry(ZipEntry(entryPath))
                file.inputStream().buffered().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    fun importWorld(resolver: ContentResolver, uri: Uri): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before importing a world." }
        val worldName = config()?.worldName?.trim().orEmpty().ifBlank { "world" }
        val importsDir = File(storage.root, "world-imports").apply { mkdirs() }
        val archive = File(importsDir, "incoming-${System.currentTimeMillis()}.zip")

        resolver.openInputStream(uri)?.use { input ->
            archive.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Could not open world archive.")

        val staging = File(importsDir, "stage-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(
                        name.isNotBlank() &&
                            !name.startsWith('/') &&
                            !name.split('/').any { it == ".." }
                    ) { "World archive contains an unsafe path." }

                    val output = File(staging, name).canonicalFile
                    val stageRoot = staging.canonicalFile
                    require(
                        output.path == stageRoot.path ||
                            output.path.startsWith(stageRoot.path + File.separator)
                    ) { "World archive contains an unsafe path." }

                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { fileOut ->
                            zip.copyTo(fileOut)
                        }
                    }
                    zip.closeEntry()
                }
            }

            val children = staging.listFiles().orEmpty()
            val importedRoot = children.singleOrNull()?.takeIf { it.isDirectory } ?: staging
            val world = safeServerPath(worldName)
            val nether = safeServerPath("${worldName}_nether")
            val end = safeServerPath("${worldName}_the_end")

            if (world.exists()) deleteRecursively(world)
            if (nether.exists()) deleteRecursively(nether)
            if (end.exists()) deleteRecursively(end)

            world.mkdirs()
            copyDirectoryContents(importedRoot, world)
        } finally {
            deleteRecursively(staging)
            deleteRecursively(archive)
        }
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        source.listFiles().orEmpty().forEach { file ->
            val target = File(destination, file.name)
            if (file.isDirectory) {
                target.mkdirs()
                copyDirectoryContents(file, target)
            } else {
                file.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun updateServerConfiguration(newConfig: ServerConfig): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before changing server software or version." }
        val oldConfig = config()
        val providerChanged = oldConfig != null && oldConfig.type != newConfig.type
        val versionChanged = oldConfig != null && oldConfig.version != newConfig.version

        if (providerChanged || versionChanged) {
            runCatching { createBackup() }
            listOf(
                File(storage.serverDir, "server.jar"),
                File(storage.serverDir, "run.sh"),
                File(storage.serverDir, "user_jvm_args.txt")
            ).forEach { file ->
                if (file.exists()) deleteRecursively(file)
            }
            storage.serverDir.listFiles()
                ?.filter {
                    it.name.startsWith("forge-installer-") ||
                        it.name.startsWith("neoforge-installer-")
                }
                ?.forEach(::deleteRecursively)

            if (providerChanged ||
                oldConfig?.type == ServerType.FORGE ||
                oldConfig?.type == ServerType.NEOFORGE
            ) {
                val libraries = File(storage.serverDir, "libraries")
                if (libraries.exists()) deleteRecursively(libraries)
            }
        }

        configuration.save(newConfig)
        install(newConfig)
    }

    fun deleteServer(): Result<Unit> = runCatching {
        require(!isRunning()) { "Stop the server before deleting it." }
        require(storage.serverDir.exists()) { "No managed server exists." }
        deleteRecursively(storage.serverDir)
        storage.serverDir.mkdirs()
    }
}

data class PlayitSetupSummary(
    val javaAddress: String?,
    val bedrockAddress: String?,
    var restartRequired: Boolean = false,
    val note: String? = null,
    val agentTunnelCount: Int? = null
)

/** The standalone agent forwards TCP and UDP independently of the Minecraft server loader. */
class PlayitManager(
    private val context: Context,
    private val storage: StorageManager
) {
    private val directory = File(context.filesDir, "playit").apply { mkdirs() }
    private val secretFile = File(directory, "agent-secret.toml")
    private val claimFile = File(directory, "pending-claim")
    private val agentBinary = File(directory, "playit-agent")
    private val agentVersionFile = File(directory, "agent-version")
    private val agentSha256File = File(directory, "agent-sha256")
    private val agentLog = File(directory, "agent.log")
    private val agentSocket = File(directory, "agent.sock")
    private val prefs = context.getSharedPreferences("playit_state", Context.MODE_PRIVATE)
    @Volatile private var process: Process? = null
    @Volatile private var agentLogStartOffset = 0L
    private val lock = Any()
    @Volatile private var proxyServer: NetServerSocket? = null
    @Volatile private var proxyWorkers: ExecutorService? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    companion object {
        const val AGENT_VERSION = "1.0.11-preview1"
        private const val AGENT_RELEASE_TAG = "v$AGENT_VERSION"
        private const val AGENT_RELEASE_API = "https://api.github.com/repos/playit-cloud/playit-agent/releases/tags/$AGENT_RELEASE_TAG"
        private const val AGENT_RELEASE_ASSET_PREFIX = "https://github.com/playit-cloud/playit-agent/releases/download/$AGENT_RELEASE_TAG/"
    }

    fun hasSecret(): Boolean = loadSecret() != null
    fun agentInstalled(): Boolean = agentBinary.isFile && agentBinary.length() > 0L
    fun pendingClaimUrl(): String? = claimFile.takeIf(File::isFile)?.readText(StandardCharsets.UTF_8)?.trim()
        ?.takeIf { it.startsWith("https://playit.gg/claim/") }
    fun savedAddress(kind: String): String? = prefs.getString("${kind}_address", null)?.takeIf(String::isNotBlank)

    fun beginClaim(): Result<String> = runCatching {
        val bytes = ByteArray(5).also(SecureRandom()::nextBytes)
        val code = bytes.joinToString("") { "%02x".format(it) }
        apiPost("/claim/setup", null, JSONObject()
            .put("code", code)
            .put("agent_type", "self-managed")
            .put("version", "playit $AGENT_VERSION"))
        val link = "https://playit.gg/claim/$code"
        claimFile.writeText(link, StandardCharsets.UTF_8)
        link
    }

    fun finishClaim() {
        val link = pendingClaimUrl() ?: error("Start Playit setup first.")
        val code = link.substringAfterLast('/')
        val setup = apiPost("/claim/setup", null, JSONObject()
            .put("code", code)
            .put("agent_type", "self-managed")
            .put("version", "playit $AGENT_VERSION"))
        val state = setup.optString("_value").lowercase()
        if (!state.contains("useraccepted")) {
            if (state.contains("userrejected")) error("The Playit agent claim was declined.")
            error("Approve the Playit link in your browser, then tap finish setup.")
        }
        val exchanged = apiPost("/claim/exchange", null, JSONObject().put("code", code))
        val secret = exchanged.optString("secret_key").ifBlank { exchanged.optString("key") }
        check(secret.matches(Regex("[a-fA-F0-9]{32,256}"))) { "Playit returned an invalid agent key." }
        saveSecret(secret)
        claimFile.delete()
    }

    fun setupTunnels(config: ServerConfig): PlayitSetupSummary {
        val secret = loadSecret() ?: error("Claim your Playit agent first.")
        ensureAgentBinary()
        // Start and register the current agent before asking Playit to create a tunnel.
        // When the server is stopped, the old flow never launched the agent, so the
        // account still reported its stale version and rejected tunnel creation.
        startAgent().getOrThrow()
        awaitAgentConnected()
        val data = agentRunData(secret)
        val agentId = data.optString("agent_id")
        check(agentId.matches(Regex("[a-fA-F0-9-]{32,36}"))) { "Playit could not identify this agent." }
        val active = data.optJSONArray("tunnels") ?: JSONArray()
        var javaAddress = tunnelAddress(active, "minecraft-java")
        var bedrockAddress = tunnelAddress(active, "minecraft-bedrock")
        if (javaAddress == null) {
            createTunnel(secret, agentId, "minecraft-java", "tcp", config.javaPort, null)
            javaAddress = waitForTunnelAddress(secret, "minecraft-java")
        }
        var bedrockError: String? = null
        if (bedrockAddress == null) {
            try {
                createTunnel(secret, agentId, "minecraft-bedrock", "udp", config.bedrockPort, "proxy-protocol-v2")
                bedrockAddress = waitForTunnelAddress(secret, "minecraft-bedrock")
            } catch (failure: Throwable) {
                bedrockError = friendlyTunnelError(failure)
            }
        }
        javaAddress?.let { prefs.edit().putString("java_address", it).apply() }
        bedrockAddress?.let { prefs.edit().putString("bedrock_address", it).apply() }
        val note = bedrockError ?: if (config.type != ServerType.GEYSER) {
            "The Bedrock UDP tunnel is ready. It will connect when this server has a Bedrock listener on UDP ${config.bedrockPort}."
        } else null
        claimFile.delete()
        return PlayitSetupSummary(javaAddress, bedrockAddress, note = note)
    }

    fun refreshTunnelAddresses(initial: PlayitSetupSummary): PlayitSetupSummary {
        val secret = loadSecret() ?: return initial
        var javaAddress = initial.javaAddress
        var bedrockAddress = initial.bedrockAddress
        for (attempt in 0 until 8) {
            val active = runCatching { agentRunData(secret).optJSONArray("tunnels") ?: JSONArray() }
                .getOrNull()
            if (active != null) {
                javaAddress = javaAddress ?: tunnelAddress(active, "minecraft-java")
                bedrockAddress = bedrockAddress ?: tunnelAddress(active, "minecraft-bedrock")
                if (javaAddress != null && (bedrockAddress != null || initial.note != null)) break
            }
            if (attempt < 7) Thread.sleep(1_000)
        }
        prefs.edit().apply {
            if (javaAddress == null) remove("java_address") else putString("java_address", javaAddress)
            if (bedrockAddress == null) remove("bedrock_address") else putString("bedrock_address", bedrockAddress)
        }.apply()
        if (javaAddress == null && bedrockAddress == null) {
            error(initial.note ?: "Playit did not return a public address yet. Try refreshing the connections again in a few seconds.")
        }
        var loadedTunnelCount = agentTunnelCountSinceRestart()
        repeat(10) {
            if (loadedTunnelCount != null) return@repeat
            Thread.sleep(500)
            loadedTunnelCount = agentTunnelCountSinceRestart()
        }
        val statusNote = listOfNotNull(
            initial.note,
            if (loadedTunnelCount == 0) "The Playit agent loaded no tunnels, so public connections are not active yet." else null
        ).joinToString(" ").ifBlank { null }
        return initial.copy(
            javaAddress = javaAddress,
            bedrockAddress = bedrockAddress,
            note = statusNote,
            agentTunnelCount = loadedTunnelCount
        )
    }

    private fun agentTunnelCountSinceRestart(): Int? = runCatching {
        val length = agentLog.length()
        val offset = agentLogStartOffset.coerceIn(0L, length)
        val size = (length - offset).coerceAtMost(1024L * 1024L).toInt()
        if (size <= 0) return@runCatching null
        val text = java.io.RandomAccessFile(agentLog, "r").use { file ->
            file.seek(offset)
            ByteArray(size).also(file::readFully).toString(StandardCharsets.UTF_8)
        }
        val line = text.lineSequence()
            .filter { it.contains("tunnels loaded") || it.contains("tunnel state updated") }
            .lastOrNull()
            ?: return@runCatching null
        Regex("tunnel_count=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    private fun awaitAgentConnected() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (process?.isAlive != true) {
                val detail = agentLog.takeIf(File::isFile)?.readLines()?.takeLast(8)?.joinToString(" ")
                error("The updated Playit agent exited before connecting.${detail?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}")
            }
            val fromOffset = runCatching {
                val length = agentLog.length()
                val offset = agentLogStartOffset.coerceIn(0L, length)
                val size = (length - offset).coerceAtMost(1024L * 1024L).toInt()
                if (size <= 0) "" else java.io.RandomAccessFile(agentLog, "r").use { file ->
                    file.seek(offset)
                    ByteArray(size).also(file::readFully).toString(StandardCharsets.UTF_8)
                }
            }.getOrDefault("")
            if (fromOffset.contains("playit connected", ignoreCase = true) ||
                fromOffset.contains("tunnels loaded", ignoreCase = true)) return
            Thread.sleep(500)
        }
        error("The updated Playit agent did not connect in time. Check the internet connection and try again.")
    }

    fun startAgent(): Result<Unit> = runCatching {
        synchronized(lock) {
            ensureAgentBinary()
            process?.takeIf(Process::isAlive)?.let { return@runCatching }
            val secret = loadSecret() ?: error("Playit agent has not been claimed yet.")
            writeAgentSecret(secret)
            agentSocket.delete()
            val proxyUrl = startAgentProxy()
            val builder = ProcessBuilder(
                agentBinary.absolutePath,
                "--secret-path", secretFile.absolutePath,
                "--socket-path", agentSocket.absolutePath,
                "-l", agentLog.absolutePath
            )
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(agentLog))
            agentLogStartOffset = agentLog.length()
            builder.environment().apply {
                put("HTTPS_PROXY", proxyUrl)
                put("https_proxy", proxyUrl)
                put("HTTP_PROXY", proxyUrl)
                put("http_proxy", proxyUrl)
            }
            val started = try {
                builder.start()
            } catch (failure: Throwable) {
                stopAgentProxy()
                throw failure
            }
            process = started
            if (started.waitFor(350, TimeUnit.MILLISECONDS)) {
                process = null
                stopAgentProxy()
                error("The official Playit agent exited during startup. Check Android compatibility and the Playit log.")
            }
            Thread {
                runCatching { started.waitFor() }
                synchronized(lock) {
                    if (process === started) {
                        process = null
                        stopAgentProxy()
                    }
                }
            }.apply { name = "playit-agent-watch"; isDaemon = true; start() }
        }
    }

    fun stopAgent() {
        synchronized(lock) {
            val current = process
            process = null
            if (current != null) {
                runCatching {
                    current.destroy()
                    if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly()
                }
            }
            stopAgentProxy()
        }
    }

    /** Route the Linux agent's HTTPS lookups through Android's own DNS resolver. */
    private fun startAgentProxy(): String = synchronized(lock) {
        proxyServer?.takeUnless(NetServerSocket::isClosed)?.let { return@synchronized "http://127.0.0.1:${it.localPort}" }
        val server = NetServerSocket()
        server.reuseAddress = true
        server.bind(NetInetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        val workers = Executors.newCachedThreadPool()
        proxyServer = server
        proxyWorkers = workers
        workers.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { serveAgentProxyClient(client, workers) }
            }
        }
        "http://127.0.0.1:${server.localPort}"
    }

    private fun serveAgentProxyClient(client: NetSocket, workers: ExecutorService) {
        var upstream: NetSocket? = null
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII))
            val request = reader.readLine().orEmpty().trim().split(Regex("\\s+"))
            while (!reader.readLine().isNullOrEmpty()) Unit
            if (request.size < 2 || !request[0].equals("CONNECT", true)) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                return
            }
            val authority = request[1]
            val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
                else authority.substringBeforeLast(':', "")
            val portText = if (authority.startsWith("[")) authority.substringAfter(']').removePrefix(":")
                else authority.substringAfterLast(':', "")
            val port = portText.toIntOrNull()
            if (host.isBlank() || port != 443 || !(host.equals("playit.gg", true) || host.endsWith(".playit.gg", true))) {
                client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                return
            }
            val socket = NetSocket()
            upstream = socket
            socket.connect(NetInetSocketAddress(host, port), 15_000)
            socket.soTimeout = 0
            client.soTimeout = 0
            val clientOutput = client.getOutputStream()
            clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            clientOutput.flush()
            val finished = CountDownLatch(2)
            workers.execute {
                runCatching { input.copyTo(socket.getOutputStream()); socket.getOutputStream().flush() }
                runCatching { socket.shutdownOutput() }
                finished.countDown()
            }
            workers.execute {
                runCatching { socket.getInputStream().copyTo(clientOutput); clientOutput.flush() }
                runCatching { client.shutdownOutput() }
                finished.countDown()
            }
            finished.await()
        } catch (_: Throwable) {
            runCatching {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun stopAgentProxy() {
        runCatching { proxyServer?.close() }
        proxyServer = null
        proxyWorkers?.shutdownNow()
        proxyWorkers = null
    }

    fun configureGeyserBedrockPort(publicPort: Int): Boolean {
        require(publicPort in 1..65535)
        val geyserConfig = storage.pluginsDir.walkTopDown().firstOrNull {
            it.isFile && it.name.equals("config.yml", true) &&
                (it.parentFile?.name.orEmpty().contains("geyser", true) || it.parentFile?.parentFile?.name.orEmpty().contains("geyser", true))
        } ?: return false
        val original = geyserConfig.readText(StandardCharsets.UTF_8)
        val useProxy = Regex("(?m)^(\\s*use-haproxy-protocol\\s*:\\s*)(?:true|false)(\\s*(?:#.*)?)$")
        val broadcast = Regex("(?m)^(\\s*broadcast-port\\s*:\\s*)-?\\d+(\\s*(?:#.*)?)$")
        if (!useProxy.containsMatchIn(original) || !broadcast.containsMatchIn(original)) return false
        val updated = broadcast.replace(useProxy.replace(original) { match -> match.groupValues[1] + "false" + match.groupValues[2] }) { match ->
            match.groupValues[1] + publicPort + match.groupValues[2]
        }
        if (updated != original) geyserConfig.writeText(updated, StandardCharsets.UTF_8)
        return true
    }

    private fun createTunnel(secret: String, agentId: String, type: String, protocol: String, localPort: Int, proxy: String?) {
        val origin = JSONObject()
            .put("agent_id", agentId)
            .put("local_ip", "127.0.0.1")
            .put("local_port", localPort)
        val request = JSONObject()
            .put("name", "MCSERVER ${if (protocol == "udp") "Bedrock" else "Java"}")
            .put("tunnel_type", type)
            .put("port_type", protocol)
            .put("port_count", 1)
            .put("origin", JSONObject().put("type", "agent").put("data", origin))
            .put("enabled", true)
            .put("alloc", JSONObject().put("type", "region").put("details", JSONObject().put("region", "global")))
        if (proxy != null) request.put("proxy_protocol", proxy)
        apiPost("/tunnels/create", secret, request)
    }

    private fun waitForTunnelAddress(secret: String, type: String): String? {
        repeat(6) {
            val active = runCatching { agentRunData(secret).optJSONArray("tunnels") ?: JSONArray() }.getOrDefault(JSONArray())
            tunnelAddress(active, type)?.let { return it }
            Thread.sleep(750)
        }
        return null
    }

    private fun agentRunData(secret: String): JSONObject = apiPost("/v1/agents/rundata", secret, JSONObject())

    private fun tunnelAddress(tunnels: JSONArray, type: String): String? {
        for (index in 0 until tunnels.length()) {
            val tunnel = tunnels.optJSONObject(index) ?: continue
            if (!tunnel.optString("tunnel_type").equals(type, true)) continue
            val display = tunnel.optString("display_address").trim().takeIf { it.isNotBlank() }
            if (display != null) return display
            val domain = tunnel.optString("assigned_domain").trim()
            if (domain.isBlank()) continue
            if (':' in domain) return domain
            val port = tunnel.optJSONObject("port")
            val start = port?.optInt("start", -1)?.takeIf { it in 1..65535 }
                ?: port?.optInt("from", -1)?.takeIf { it in 1..65535 }
            return if (start != null) "$domain:$start" else domain
        }
        return null
    }

    private fun apiPost(path: String, secret: String?, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.playit.gg$path")
            .header("User-Agent", BuildConfig.MCSERVER_USER_AGENT)
            .apply { if (!secret.isNullOrBlank()) header("Authorization", "Agent-Key $secret") }
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = playitErrorDetail(text)
                error("Playit API returned HTTP ${response.code}${detail?.let { " ($it)" }.orEmpty()}.")
            }
            val envelope = runCatching { JSONObject(text) }.getOrElse { error("Playit returned an unreadable response.") }
            val status = envelope.optString("status")
            if (status == "error") {
                val data = envelope.optJSONObject("data") ?: JSONObject()
                val kind = data.optString("type").ifBlank { data.optString("error") }
                error(if (kind.isNotBlank()) "Playit rejected the request ($kind)." else "Playit rejected the request.")
            }
            if (status == "fail") {
                val reason = when (val value = envelope.opt("data")) {
                    is String -> value
                    else -> value?.toString().orEmpty()
                }
                error("Playit could not create this free tunnel${reason.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,80}")) }?.let { " ($it)" }.orEmpty()}.")
            }
            if (status != "success") error("Playit returned an unexpected response.")
            return when (val data = envelope.opt("data")) {
                is JSONObject -> data
                is String -> JSONObject().put("_value", data)
                is JSONArray -> JSONObject().put("_array", data)
                else -> JSONObject()
            }
        }
    }

    private fun playitErrorDetail(text: String): String? {
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val data = envelope.optJSONObject("data")
        val detail = sequenceOf(
            envelope.optString("message"),
            envelope.optString("error"),
            data?.optString("type").orEmpty(),
            data?.optString("error").orEmpty(),
            data?.optString("message").orEmpty(),
            envelope.opt("data")?.takeIf { it is String }?.toString().orEmpty()
        ).map(String::trim).firstOrNull { it.isNotEmpty() && !it.equals("null", true) }
            ?: return null
        return detail
            .replace(Regex("(?i)[a-f0-9]{32,}"), "[redacted]")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(120)
    }

    private fun friendlyTunnelError(failure: Throwable): String {
        val raw = failure.message.orEmpty()
        return when {
            raw.contains("InvalidOrigin", true) -> "Playit rejected the Bedrock UDP/RakNet tunnel for this agent. The Java connection remains available, but this Playit agent/API does not currently accept the Bedrock origin."
            raw.contains("Premium", true) -> "Playit rejected the free Bedrock UDP tunnel for this account."
            raw.contains("verified", true) -> "Verify the Playit account before creating its Bedrock UDP tunnel."
            raw.isNotBlank() -> raw
            else -> "Playit could not create the Bedrock UDP tunnel."
        }
    }

    private fun loadSecret(): String? {
        val stored = secretFile.takeIf(File::isFile)?.readText(StandardCharsets.UTF_8)?.trim()
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{32,256}")) }
        if (stored != null) return stored
        val yaml = File(storage.pluginsDir, "playit-gg/config.yml")
        if (!yaml.isFile) return null
        val match = Regex("(?m)^\\s*agent-secret\\s*:\\s*(.*?)\\s*$").find(yaml.readText(StandardCharsets.UTF_8)) ?: return null
        val value = match.groupValues[1].substringBefore(" #").trim().trim('"', '\'')
        return value.takeIf { it.matches(Regex("[a-fA-F0-9]{32,256}")) }
    }

    private fun saveSecret(secret: String) {
        directory.mkdirs()
        secretFile.writeText(secret.trim(), StandardCharsets.UTF_8)
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                secretFile.toPath(),
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
            )
        }
    }

    fun clearPendingClaim() {
        claimFile.delete()
    }

    private fun writeAgentSecret(secret: String) = saveSecret(secret)

    private fun ensureAgentBinary() {
        val installedVersion = agentVersionFile.takeIf(File::isFile)
            ?.readText(StandardCharsets.UTF_8)?.trim()
        val installedSha = agentSha256File.takeIf(File::isFile)
            ?.readText(StandardCharsets.UTF_8)?.trim()?.lowercase()
        val actualSha = agentBinary.takeIf(File::isFile)
            ?.let { runCatching { DownloadManager.sha256Hex(it) }.getOrNull() }
        if (agentInstalled() && installedVersion == AGENT_VERSION &&
            installedSha?.matches(Regex("[a-f0-9]{64}")) == true &&
            installedSha == actualSha) {
            agentBinary.setExecutable(true, true)
            return
        }
        val asset = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "playit-linux-aarch64"
            Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "playit-linux-armv7"
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "playit-linux-amd64"
            else -> error("The official Playit agent has no binary for this phone's processor.")
        }
        val remoteAsset = resolveAgentAsset(asset)
        val stagedBinary = File(directory, "playit-agent-$AGENT_VERSION.staged")
        DownloadManager(storage).download(remoteAsset.url, stagedBinary, remoteAsset.sha256)
        check(stagedBinary.setExecutable(true, true)) { "Android did not allow the Playit agent to run." }
        if (process?.isAlive == true) stopAgent()
        java.nio.file.Files.move(
            stagedBinary.toPath(),
            agentBinary.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        agentSha256File.writeText(remoteAsset.sha256.lowercase(), StandardCharsets.UTF_8)
        agentVersionFile.writeText(AGENT_VERSION, StandardCharsets.UTF_8)
        check(agentBinary.setExecutable(true, true)) { "Android did not allow the Playit agent to run." }
    }

    private fun resolveAgentAsset(assetName: String): AgentAsset {
        val request = Request.Builder()
            .url(AGENT_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", BuildConfig.MCSERVER_USER_AGENT)
            .build()
        val release = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Playit release lookup failed with HTTP ${response.code}${playitErrorDetail(body)?.let { " ($it)" }.orEmpty()}."
            }
            JSONObject(body)
        }
        check(release.optString("tag_name") == AGENT_RELEASE_TAG) {
            "GitHub returned a different Playit release than expected."
        }
        val asset = release.optJSONArray("assets")?.let { assets ->
            (0 until assets.length()).mapNotNull(assets::optJSONObject).firstOrNull {
                it.optString("name") == assetName
            }
        } ?: error("Playit release $AGENT_RELEASE_TAG does not include $assetName.")
        val url = asset.optString("browser_download_url")
        check(url.startsWith(AGENT_RELEASE_ASSET_PREFIX)) {
            "Playit returned an unexpected agent download location."
        }
        val digest = asset.optString("digest").removePrefix("sha256:")
        check(digest.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Playit did not provide a valid SHA-256 checksum for its agent."
        }
        return AgentAsset(url, digest)
    }

    private data class AgentAsset(val url: String, val sha256: String)
}

class NetworkManager {
    @Volatile var status = NetworkStatus(message = "No tunnel registration yet")
        private set

    fun setStatus(value: NetworkStatus) {
        status = value
    }
}

class AppRepository private constructor(context: Context) {
    val serverManager = ServerManager(context)
    val networkManager = NetworkManager()
    val logs = ArrayDeque<String>(500)
    private val _liveLogs = MutableStateFlow(serverManager.recentLogs(500))
    val liveLogs: StateFlow<List<String>> = _liveLogs
    private val preferences = context.getSharedPreferences("mcserver_state", Context.MODE_PRIVATE)

    @Synchronized
    fun appendLog(line: String) {
        if (logs.size >= 500) logs.removeFirst()
        logs.addLast(line)
        _liveLogs.value = logs.toList()
    }

    fun keepServerRunning(): Boolean = preferences.getBoolean("keep_server_running", false)

    fun setKeepServerRunning(value: Boolean) {
        preferences.edit().putBoolean("keep_server_running", value).apply()
    }

    companion object {
        @Volatile private var instance: AppRepository? = null

        fun get(context: Context): AppRepository = instance ?: synchronized(this) {
            instance ?: AppRepository(context.applicationContext).also { instance = it }
        }
    }
}

class RenderApiClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient()

    fun health(): Boolean {
        if (baseUrl.isBlank() || token.isBlank()) return false
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/health")
            .header("Authorization", "Bearer $token")
            .build()
        return runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}

class TunnelClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val streams = java.util.concurrent.ConcurrentHashMap<Int, java.net.Socket>()
    @Volatile private var socket: okhttp3.WebSocket? = null
    @Volatile private var connected = false

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()
    fun isConnected(): Boolean = connected

    fun connect(localPort: Int, onStatus: (NetworkStatus) -> Unit = {}): Boolean {
        check(isConfigured()) { "Render tunnel URL/token are not configured." }
        disconnect()
        val webSocketUrl = toWebSocketUrl(baseUrl.trimEnd('/')) + "/agent"
        val request = Request.Builder().url(webSocketUrl).header("Authorization", "Bearer $token").build()
        val opened = java.util.concurrent.CountDownLatch(1)
        socket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                socket = webSocket
                connected = true
                opened.countDown()
                onStatus(NetworkStatus(true, hostOf(baseUrl), localPort, null, "Tunnel connected"))
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                handleFrame(bytes.toByteArray(), localPort, onStatus)
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                connected = false
                closeStreams()
                opened.countDown()
                onStatus(NetworkStatus(false, message = "Tunnel failure: ${t.message ?: "connection error"}"))
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                connected = false
                closeStreams()
                onStatus(NetworkStatus(false, message = "Tunnel closed: $reason"))
            }
        })
        return opened.await(8, TimeUnit.SECONDS) && connected
    }

    fun disconnect() {
        connected = false
        socket?.close(1000, "client disconnect")
        socket = null
        closeStreams()
    }

    private fun handleFrame(frame: ByteArray, localPort: Int, onStatus: (NetworkStatus) -> Unit) {
        if (frame.size < 5) return
        val kind = frame[0].toInt() and 0xff
        val id = java.nio.ByteBuffer.wrap(frame, 1, 4).int
        when (kind) {
            1 -> openLocalStream(id, localPort, onStatus)
            2 -> streams[id]?.let { stream -> stream.getOutputStream().apply { write(frame, 5, frame.size - 5); flush() } }
            3 -> closeStream(id)
        }
    }

    private fun openLocalStream(id: Int, localPort: Int, onStatus: (NetworkStatus) -> Unit) {
        if (streams.containsKey(id)) return
        val local = runCatching {
            java.net.Socket().apply { connect(java.net.InetSocketAddress("127.0.0.1", localPort), 8_000) }
        }.getOrElse {
            sendFrame(3, id)
            onStatus(NetworkStatus(true, hostOf(baseUrl), localPort, null, "Tunnel connected; local Minecraft socket unavailable"))
            return
        }
        streams[id] = local
        Thread {
            val buffer = ByteArray(64 * 1024)
            try {
                while (connected) {
                    val count = local.getInputStream().read(buffer)
                    if (count < 0) break
                    if (count > 0) sendFrame(2, id, buffer, count)
                }
            } catch (_: Throwable) {
            } finally {
                sendFrame(3, id)
                closeStream(id)
            }
        }.apply { name = "MCSERVER-tunnel-$id" }.start()
    }

    private fun sendFrame(kind: Int, id: Int, payload: ByteArray = ByteArray(0), length: Int = payload.size) {
        val frame = ByteArray(5 + length)
        frame[0] = kind.toByte()
        java.nio.ByteBuffer.wrap(frame, 1, 4).putInt(id)
        if (length > 0) System.arraycopy(payload, 0, frame, 5, length)
        socket?.send(okio.ByteString.of(*frame))
    }

    private fun closeStream(id: Int) {
        streams.remove(id)?.let { runCatching { it.close() } }
    }

    private fun closeStreams() {
        streams.keys.toList().forEach(::closeStream)
    }

    private fun toWebSocketUrl(value: String): String = when {
        value.startsWith("https://") -> "wss://" + value.removePrefix("https://")
        value.startsWith("http://") -> "ws://" + value.removePrefix("http://")
        value.startsWith("wss://") || value.startsWith("ws://") -> value
        else -> "wss://$value"
    }

    private fun hostOf(value: String): String = runCatching { java.net.URI(value).host ?: "" }.getOrDefault("")
}

class BackupManager(private val storage: StorageManager) {
    fun createBackup(): File {
        storage.initialize()
        val target = File(storage.backupsDir, "backup-${System.currentTimeMillis()}")
        target.mkdirs()
        copyDirectory(storage.serverDir, target, storage.backupsDir)
        return target
    }

    private fun copyDirectory(source: File, target: File, exclude: File) {
        source.listFiles().orEmpty().forEach { child ->
            if (child.canonicalFile == exclude.canonicalFile) return@forEach
            val destination = File(target, child.name)
            if (child.isDirectory) {
                destination.mkdirs()
                copyDirectory(child, destination, exclude)
            } else {
                destination.parentFile?.mkdirs()
                child.copyTo(destination, overwrite = false)
            }
        }
    }
}

class ServerHostService : Service() {
    companion object {
        const val ACTION_START = "com.mcserver.app.START"
        const val ACTION_STOP = "com.mcserver.app.STOP"
        const val ACTION_RESTART = "com.mcserver.app.RESTART"
        const val ACTION_FORCE_STOP = "com.mcserver.app.FORCE_STOP"
        private const val CHANNEL_ID = "mcserver_host"
        private const val NOTIFICATION_ID = 4001
        private const val WAKE_LOCK_TAG = "MCSERVER:Hosting"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private lateinit var repository: AppRepository
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository.get(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification(if (repository.keepServerRunning()) "Starting server..." else "Server Offline"))
        if (repository.keepServerRunning()) acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                repository.setKeepServerRunning(true)
                scope.launch { startServer("Server Running") }
            }
            ACTION_STOP -> {
                repository.setKeepServerRunning(false)
                scope.launch {
                    repository.serverManager.stop(false)
                    releaseWakeLock()
                    updateNotification("Server Offline")
                }
            }
            ACTION_FORCE_STOP -> {
                repository.setKeepServerRunning(false)
                scope.launch {
                    repository.serverManager.stop(true)
                    releaseWakeLock()
                    updateNotification("Server Offline")
                }
            }
            ACTION_RESTART -> {
                repository.setKeepServerRunning(true)
                scope.launch {
                    repository.serverManager.stop(false)
                    startServer("Server Restarting")
                }
            }
            null -> if (repository.keepServerRunning() && !repository.serverManager.isRunning()) {
                scope.launch { startServer("Recovering server...") }
            }
        }
        return START_STICKY
    }

    private suspend fun startServer(startText: String) {
        if (!startMutex.tryLock()) return
        try {
            if (repository.serverManager.isRunning() || repository.serverManager.isStarting()) return
            runCatching {
                acquireWakeLock()
                updateNotification(startText)
                val started = repository.serverManager.start(
                    onLog = { repository.appendLog(it) },
                    onExit = { crashed ->
                        if (crashed && repository.keepServerRunning()) {
                            updateNotification("Server Crashed - restarting...")
                            scope.launch {
                                delay(3000)
                                if (repository.keepServerRunning() && !repository.serverManager.isRunning()) {
                                    startServer("Recovering server...")
                                }
                            }
                        } else {
                            releaseWakeLock()
                            updateNotification("Server Offline")
                        }
                    }
                )
                if (!started) {
                    releaseWakeLock()
                    updateNotification("Server Offline")
                } else {
                    updateNotification("Server Running")
                }
            }.onFailure {
                repository.appendLog("START ERROR: ${it.message}")
                if (!repository.keepServerRunning()) releaseWakeLock()
                updateNotification("Server Crashed")
            }
        } finally {
            startMutex.unlock()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Minecraft server", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            401,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            402,
            Intent(this, ServerHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCSERVER")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (repository.keepServerRunning()) builder.addAction(0, "STOP", stopIntent)
        return builder.build()
    }
}

class ServerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val repository = AppRepository.get(context)
        if (!repository.keepServerRunning()) return
        val serviceIntent = Intent(context, ServerHostService::class.java).setAction(ServerHostService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)
    }
}
