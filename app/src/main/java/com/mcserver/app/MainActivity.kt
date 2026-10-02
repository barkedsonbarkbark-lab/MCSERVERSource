package com.mcserver.app

import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = AppRepository.get(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF5B8CFF),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF25447A),
                    onPrimaryContainer = Color(0xFFDCE8FF),
                    secondary = Color(0xFF67D8BE),
                    onSecondary = Color(0xFF06221D),
                    background = Color(0xFF080C12),
                    onBackground = Color(0xFFE9EEF5),
                    surfaceDim = Color(0xFF080C12),
                    surface = Color(0xFF111821),
                    surfaceBright = Color(0xFF293745),
                    surfaceContainerLowest = Color(0xFF080D13),
                    surfaceContainerLow = Color(0xFF0E151E),
                    surfaceContainer = Color(0xFF121B25),
                    surfaceContainerHigh = Color(0xFF18232F),
                    surfaceContainerHighest = Color(0xFF1E2B38),
                    onSurface = Color(0xFFE9EEF5),
                    surfaceVariant = Color(0xFF1B2633),
                    onSurfaceVariant = Color(0xFFAAB6C5),
                    outline = Color(0xFF516174),
                    outlineVariant = Color(0xFF2B3948),
                    error = Color(0xFFFFB4AB),
                    onError = Color(0xFF690005)
                )
            ) {
                McServerApp(repository) { action ->
                    if ((action == ServerHostService.ACTION_START || action == ServerHostService.ACTION_RESTART) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val powerManager = getSystemService(PowerManager::class.java)
                        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            }
                        }
                    }
                    startForegroundService(Intent(this, ServerHostService::class.java).setAction(action))
                }
            }
        }
    }
}

private enum class DashboardTab(val title: String, val icon: String) {
    OVERVIEW("Overview", "⌂"),
    CONSOLE("Console", "›_"),
    FILES("Files", "▤"),
    PLAYERS("Players", "♟"),
    WORLD("World", "◉"),
    PLUGINS("Plugins", "✣"),
    MODS("Mods", "⚙"),
    SETTINGS("Settings", "⚙"),
    NETWORK("Network", "↗"),
    BACKUPS("Backups", "▣"),
    FIX("Java setup", "J")
}

@Composable
private fun McServerApp(repository: AppRepository, onServiceAction: (String) -> Unit) {
    val manager = repository.serverManager
    var config by remember { mutableStateOf(manager.config()) }
    var creating by remember { mutableStateOf(config == null) }
    var tab by remember { mutableStateOf(DashboardTab.OVERVIEW) }
    var name by remember { mutableStateOf("My Survival SMP") }
    var version by remember { mutableStateOf("1.21.10") }
    var type by remember { mutableStateOf(ServerType.PAPER) }
    var ram by remember { mutableStateOf("2048") }
    var world by remember { mutableStateOf("world") }
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(manager.isRunning()) }
    var starting by remember { mutableStateOf(manager.isStarting()) }
    var startError by remember { mutableStateOf(manager.lastStartError()) }
    var refreshTick by remember { mutableStateOf(0) }
    var minecraftVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var eulaAccepted by remember { mutableStateOf(manager.eulaAccepted()) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { manager.availableMinecraftVersions() }
                .onSuccess { loaded ->
                    minecraftVersions = loaded
                    if (loaded.isNotEmpty() && version !in loaded) version = loaded.first()
                }
        }

        while (true) {
            config = manager.config()
            running = manager.isRunning()
            starting = manager.isStarting()
            startError = manager.lastStartError()
            refreshTick++
            delay(1000)
        }
    }

    if (creating) {
        CreateScreen(
            name = name,
            onName = { name = it },
            version = version,
            onVersion = { version = it },
            minecraftVersions = minecraftVersions,
            eulaAccepted = eulaAccepted,
            onEulaAccepted = { eulaAccepted = it },
            type = type,
            onType = { type = it },
            ram = ram,
            onRam = { ram = it.filter(Char::isDigit) },
            world = world,
            onWorld = { world = it },
            errorMessage = message,
            onCreate = {
                runCatching {
                    val created = ServerConfig(
                        name = name.ifBlank { "My Survival SMP" },
                        version = version.ifBlank { "1.21.10" },
                        type = type,
                        ramMb = (ram.toIntOrNull() ?: 2048).coerceAtLeast(512),
                        worldName = world.ifBlank { "world" }
                    )
                    check(eulaAccepted) { "Accept the Minecraft EULA before creating the managed server." }
                    manager.create(created)
                    manager.acceptEula()
                    config = created
                    creating = false
                    message = "Server configuration and EULA consent saved."
                }.onFailure {
                    message = it.message ?: "Creation failed"
                }
            }
        )
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val drawerScroll = rememberScrollState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(285.dp),
                drawerContainerColor = Color(0xFF11151B)
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Text("MCSERVER", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text("CONTROL PANEL", style = MaterialTheme.typography.labelMedium, color = Color(0xFF8C96A3))
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = if (running) Color(0xFF123B2A) else Color(0xFF2A2022),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(config?.name ?: "My Server", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                when { running -> "● Server Online"; starting -> "● Starting server"; else -> "● Server Offline" },
                                style = MaterialTheme.typography.bodySmall,
                                color = when { running -> Color(0xFF7BE3AD); starting -> Color(0xFF9CC3FF); else -> Color(0xFFFFA8A8) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(drawerScroll)) {
                        DashboardTab.entries.forEach { candidate ->
                            NavigationDrawerItem(
                                icon = { Text(candidate.icon, style = MaterialTheme.typography.titleMedium) },
                                label = { Text(candidate.title) },
                                selected = tab == candidate,
                                onClick = {
                                    tab = candidate
                                    drawerScope.launch { drawerState.close() }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${config?.type?.displayName ?: "Server"} • ${config?.version ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7E8996)
                    )
                }
            }
        }
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0E141C)) {
                    listOf(DashboardTab.OVERVIEW, DashboardTab.CONSOLE, DashboardTab.FILES).forEach { candidate ->
                        NavigationBarItem(
                            selected = tab == candidate,
                            onClick = { tab = candidate },
                            icon = {
                                Text(
                                    when (candidate) {
                                        DashboardTab.OVERVIEW -> "⌂"
                                        DashboardTab.CONSOLE -> "›_"
                                        else -> "▤"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            label = { Text(candidate.title) }
                        )
                    }
                    NavigationBarItem(
                        selected = tab !in setOf(DashboardTab.OVERVIEW, DashboardTab.CONSOLE, DashboardTab.FILES),
                        onClick = { drawerScope.launch { drawerState.open() } },
                        icon = { Text("···", style = MaterialTheme.typography.titleMedium) },
                        label = { Text("More") }
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                        Text("☰", style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("MCSERVER", style = MaterialTheme.typography.titleMedium)
                        Text(config?.name ?: "My Server", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E98A5))
                    }
                    Surface(
                        color = when {
                            running -> Color(0xFF123B2A)
                            starting -> Color(0xFF243650)
                            else -> Color(0xFF2A2022)
                        },
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            when {
                                running -> "ONLINE"
                                starting -> "STARTING"
                                else -> "OFFLINE"
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            color = when {
                                running -> Color(0xFF7BE3AD)
                                starting -> Color(0xFF9CC3FF)
                                else -> Color(0xFFFFA8A8)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                if (tab == DashboardTab.OVERVIEW) {
                    ServerCard(config, running, starting, startError, onServiceAction)
                }

                if (!eulaAccepted) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = eulaAccepted, onCheckedChange = { checked ->
                                if (checked) {
                                    manager.acceptEula()
                                    eulaAccepted = true
                                }
                            })
                            Column(Modifier.weight(1f)) {
                                Text("EULA REQUIRED", style = MaterialTheme.typography.titleSmall)
                                Text("Accept Minecraft's EULA before starting the server.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    TabContent(
                        tab = tab,
                        repository = repository,
                        minecraftVersions = minecraftVersions,
                        refreshTick = refreshTick,
                        onServerDeleted = {
                            repository.setKeepServerRunning(false)
                            creating = true
                            tab = DashboardTab.OVERVIEW
                        }
                    )
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun CreateScreen(
    name: String,
    onName: (String) -> Unit,
    version: String,
    onVersion: (String) -> Unit,
    minecraftVersions: List<String>,
    eulaAccepted: Boolean,
    onEulaAccepted: (Boolean) -> Unit,
    type: ServerType,
    onType: (ServerType) -> Unit,
    ram: String,
    onRam: (String) -> Unit,
    world: String,
    onWorld: (String) -> Unit,
    errorMessage: String?,
    onCreate: () -> Unit
) {
    var versionMenuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("MCSERVER", style = MaterialTheme.typography.headlineLarge)
        Text("Create the one managed Minecraft server.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CREATE SERVER", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = name, onValueChange = onName, modifier = Modifier.fillMaxWidth(), label = { Text("Server Name") })
                Button(
                    onClick = { versionMenuOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Minecraft Version: $version")
                }
                DropdownMenu(
                    expanded = versionMenuOpen,
                    onDismissRequest = { versionMenuOpen = false }
                ) {
                    minecraftVersions.take(100).forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate) },
                            onClick = {
                                onVersion(candidate)
                                versionMenuOpen = false
                            }
                        )
                    }
                }
                Text("Server Type")
                ServerType.entries.forEach { candidate ->
                    val supported = candidate != ServerType.SPIGOT && candidate != ServerType.BUKKIT
                    FilterChip(
                        selected = type == candidate,
                        onClick = { onType(candidate) },
                        enabled = supported,
                        label = { Text(if (supported) candidate.displayName else "${candidate.displayName} (unavailable)") }
                    )
                }
                if (type == ServerType.GEYSER) {
                    Text("Geyser and Floodgate are set up together. Bedrock connections use RakNet over UDP; NetherNet is off. Players can join with a Bedrock account, and their in-game name starts with a dot, like .Gamertag.")
                }
                OutlinedTextField(value = ram, onValueChange = onRam, modifier = Modifier.fillMaxWidth(), label = { Text("RAM (MB)") })
                OutlinedTextField(value = world, onValueChange = onWorld, modifier = Modifier.fillMaxWidth(), label = { Text("World Name") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = eulaAccepted, onCheckedChange = onEulaAccepted)
                    Text("I have read and agree to Minecraft's EULA.")
                }
                Button(onClick = onCreate, enabled = eulaAccepted, modifier = Modifier.fillMaxWidth()) {
                    Text("Create server")
                }
                errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    config: ServerConfig?,
    running: Boolean,
    starting: Boolean,
    startError: String?,
    onAction: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(config?.name ?: "My Server", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${config?.type?.displayName ?: "Server"}  ·  ${config?.version.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "World: ${config?.worldName ?: "world"}  ·  ${config?.ramMb ?: 0} MB RAM  ·  ${config?.maxPlayers ?: 20} player limit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!running) {
                Button(
                    onClick = { onAction(ServerHostService.ACTION_START) },
                    enabled = !starting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (starting) "Setting up…" else "Start server")
                }
            } else {
                TextButton(
                    onClick = { onAction(ServerHostService.ACTION_STOP) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop server")
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerFact("Java port", (config?.javaPort ?: 25565).toString(), Modifier.weight(1f))
                ServerFact("Bedrock · UDP", (config?.bedrockPort ?: 19132).toString(), Modifier.weight(1f))
            }
            startError?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ServerFact(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ServerStatusStrip(config: ServerConfig?, running: Boolean, starting: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(config?.name ?: "My Server", style = MaterialTheme.typography.titleSmall)
                Text("${config?.type?.displayName ?: "Server"} · ${config?.version ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(when { running -> "ONLINE"; starting -> "STARTING"; else -> "OFFLINE" }, color = when { running -> Color(0xFF7BE3AD); starting -> Color(0xFF9CC3FF); else -> Color(0xFFFFA8A8) }, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun uriDisplayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "upload.bin" } ?: "upload.bin"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
    if (bytes < 1024L * 1024L * 1024L) return "${bytes / (1024L * 1024L)} MB"
    return "%.1f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    enabled: Boolean,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
                Text(if (entry.isDirectory) "📁 ${entry.name}" else entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(if (entry.isDirectory) "Folder" else formatFileSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.isDirectory) {
                TextButton(onClick = onOpen) { Text("OPEN") }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(text = { Text("Preview / edit") }, onClick = { menuOpen = false; onPreview() })
                        DropdownMenuItem(text = { Text("Save a copy") }, onClick = { menuOpen = false; onDownload() })
                    }
                    DropdownMenuItem(enabled = enabled, text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(enabled = enabled, text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun TabContent(
    tab: DashboardTab,
    repository: AppRepository,
    minecraftVersions: List<String>,
    refreshTick: Int,
    onServerDeleted: () -> Unit
) {
    val manager = repository.serverManager
    var playerName by remember { mutableStateOf("") }
    var propertyKey by remember { mutableStateOf("max-players") }
    var propertyValue by remember { mutableStateOf(manager.config()?.maxPlayers?.toString() ?: "20") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabScroll = rememberScrollState()
    LaunchedEffect(tab) { tabScroll.scrollTo(0) }
    var filePath by remember { mutableStateOf("") }
    var fileRefresh by remember { mutableStateOf(0) }
    var fileEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var fileQuery by remember { mutableStateOf("") }
    var fileBusy by remember { mutableStateOf(false) }
    var uploadTarget by remember { mutableStateOf("") }
    var previewEntry by remember { mutableStateOf<FileEntry?>(null) }
    var previewText by remember { mutableStateOf("") }
    var previewEditing by remember { mutableStateOf(false) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var exportEntry by remember { mutableStateOf<FileEntry?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var deleteFileDialog by remember { mutableStateOf<FileEntry?>(null) }
    var worldSettings by remember { mutableStateOf(manager.worldSettings()) }
    var worldMessage by remember { mutableStateOf<String?>(null) }
    var worldBusy by remember { mutableStateOf(false) }
    var networkMessage by remember { mutableStateOf<String?>(null) }
    var serverName by remember { mutableStateOf(manager.config()?.name.orEmpty()) }
    var serverVersion by remember { mutableStateOf(manager.config()?.version.orEmpty()) }
    var serverType by remember { mutableStateOf(manager.config()?.type ?: ServerType.PAPER) }
    var serverRam by remember { mutableStateOf(manager.config()?.ramMb?.toString() ?: "2048") }
    var serverWorld by remember { mutableStateOf(manager.config()?.worldName.orEmpty()) }
    var settingsMenuOpen by remember { mutableStateOf(false) }
    var versionMenuOpen by remember { mutableStateOf(false) }
    var savingSettings by remember { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var backupAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var playitBusy by remember { mutableStateOf(false) }
    var playitMessage by remember { mutableStateOf<String?>(null) }
    var playitClaimLink by remember { mutableStateOf(manager.playitPendingClaimUrl().orEmpty()) }
    var lanRefresh by remember { mutableStateOf(0) }
    var overviewCopyMessage by remember { mutableStateOf<String?>(null) }
    var playitBedrockAddress by remember {
        mutableStateOf(manager.playitBedrockAddress().orEmpty().ifBlank {
            context.getSharedPreferences("network_options", 0).getString("playit_bedrock_address", "").orEmpty()
        })
    }
    var playitBedrockDraft by remember { mutableStateOf(playitBedrockAddress) }

    LaunchedEffect(tab, filePath, fileRefresh) {
        if (tab == DashboardTab.FILES) {
            fileEntries = withContext(Dispatchers.IO) { runCatching { manager.listServerFiles(filePath) }.getOrDefault(emptyList()) }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uriDisplayName(context, uri)
            scope.launch {
                fileBusy = true
                val result = withContext(Dispatchers.IO) {
                    manager.importServerFile(context.contentResolver, uri, uploadTarget, name)
                }
                fileMessage = result.fold({ "Uploaded $name" }, { "Upload failed: ${it.message ?: "unknown error"}" })
                fileRefresh++
                fileBusy = false
            }
        }
    }

    val fileExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = exportEntry
        if (uri != null && entry != null) {
            scope.launch {
                fileBusy = true
                val result = withContext(Dispatchers.IO) { manager.copyServerFileTo(entry.relativePath, context.contentResolver, uri) }
                fileMessage = result.fold({ "Saved ${entry.name}" }, { "Download failed: ${it.message ?: "unknown error"}" })
                fileBusy = false
            }
        }
        exportEntry = null
    }

    val worldImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            worldBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { manager.importWorld(context.contentResolver, uri) }
                worldMessage = result.fold({ "World imported successfully." }, { "World import failed: ${it.message ?: "unknown error"}" })
                worldBusy = false
            }
        }
    }

    val worldExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            worldBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val exported = manager.exportWorld()
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            exported.inputStream().buffered().use { input -> input.copyTo(output) }
                        } ?: error("Could not open destination.")
                        exported.delete()
                    }
                }
                worldMessage = result.fold({ "World exported successfully." }, { "World export failed: ${it.message ?: "unknown error"}" })
                worldBusy = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .then(if (tab == DashboardTab.CONSOLE || tab == DashboardTab.FILES) Modifier else Modifier.verticalScroll(tabScroll)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(tab.title, style = MaterialTheme.typography.titleLarge)
            when (tab) {
                DashboardTab.OVERVIEW -> {
                    val config = manager.config()
                    val javaPort = config?.javaPort ?: 25565
                    val bedrockPort = config?.bedrockPort ?: 19132
                    val localAddresses = remember(lanRefresh, refreshTick / 30) { localServerAddresses() }
                    val recentLogText = remember(refreshTick / 2) { manager.recentLogs(500).joinToString("\n") }
                    val playitClaimUrl = playitClaimLink.takeIf { it.isNotBlank() }
                        ?: manager.playitPendingClaimUrl()
                        ?: if (!manager.hasPlayitSecret()) {
                            Regex("https://playit\\.gg/claim/[A-Za-z0-9_-]+")
                                .findAll(recentLogText).lastOrNull()?.value
                        } else null
                    val playitJavaAddress = manager.playitJavaAddress()
                        ?: Regex("(?:[A-Za-z0-9.-]+\\.playit\\.gg|[A-Za-z0-9.-]+\\.tun\\.ply\\.gg|[A-Za-z0-9.-]+\\.gl\\.at\\.ply\\.gg|[A-Za-z0-9.-]+\\.gl\\.joinmc\\.link)(?::[0-9]{1,5})?")
                            .findAll(recentLogText).lastOrNull()?.value
                    fun copyOverviewValue(label: String, value: String, message: String) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
                        overviewCopyMessage = message
                    }

                    Text("Share your server", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("On this Wi-Fi", style = MaterialTheme.typography.titleMedium)
                            Text("These addresses work for players connected to the same home network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (localAddresses.isEmpty()) {
                                Text("Connect this phone to Wi-Fi to see local addresses.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                localAddresses.forEach { address ->
                                    Text("Java Edition", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$address:$javaPort", style = MaterialTheme.typography.bodyLarge)
                                    Button(
                                        onClick = { copyOverviewValue("Local Java address", "$address:$javaPort", "Local Java address copied.") },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("COPY JAVA ADDRESS") }
                                    HorizontalDivider()
                                    Text("Bedrock Edition · UDP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$address:$bedrockPort", style = MaterialTheme.typography.bodyLarge)
                                    Button(
                                        onClick = { copyOverviewValue("Local Bedrock address", "$address:$bedrockPort", "Local Bedrock address copied.") },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("COPY BEDROCK ADDRESS") }
                                }
                            }
                            TextButton(onClick = { lanRefresh++ }, modifier = Modifier.fillMaxWidth()) { Text("REFRESH WI-FI ADDRESSES") }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Playit · Public access", style = MaterialTheme.typography.titleMedium)
                            Text("Share these with friends outside your home network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (playitJavaAddress != null) {
                                Text("Java Edition", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(playitJavaAddress, style = MaterialTheme.typography.bodyLarge)
                                Button(
                                    onClick = { copyOverviewValue("Playit Java address", playitJavaAddress, "Playit Java link copied.") },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("COPY PLAYIT JAVA LINK") }
                            } else if (playitClaimUrl != null) {
                                Text("Claim the Playit agent to get a public Java address.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(
                                    onClick = { copyOverviewValue("Playit claim link", playitClaimUrl, "Playit claim link copied.") },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("COPY PLAYIT CLAIM LINK") }
                            } else {
                                Text("Start the server to show its Playit Java link here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val currentBedrockAddress = manager.playitBedrockAddress().orEmpty().ifBlank { playitBedrockAddress }
                            if (currentBedrockAddress.isNotBlank()) {
                                HorizontalDivider()
                                Text("Bedrock Edition · UDP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(currentBedrockAddress, style = MaterialTheme.typography.bodyLarge)
                                Button(
                                    onClick = { copyOverviewValue("Playit Bedrock address", currentBedrockAddress, "Playit Bedrock address copied.") },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("COPY PLAYIT BEDROCK LINK") }
                            } else {
                                Text("Set up a Bedrock UDP tunnel in Network to show its public address here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            overviewCopyMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                DashboardTab.CONSOLE -> {
                    var command by remember { mutableStateOf("") }
                    var consoleFilter by remember { mutableStateOf("") }
                    var followConsole by remember { mutableStateOf(true) }
                    var consoleTextSize by remember { mutableStateOf(12f) }
                    var consoleMessage by remember { mutableStateOf<String?>(null) }
                    val logs by repository.liveLogs.collectAsState()
                    val consoleScroll = rememberScrollState()
                    val visibleLogs = remember(logs, consoleFilter) {
                        if (consoleFilter.isBlank()) logs else logs.filter { it.contains(consoleFilter, ignoreCase = true) }
                    }

                    LaunchedEffect(visibleLogs.size, logs.lastOrNull(), followConsole) {
                        kotlinx.coroutines.yield()
                        if (followConsole) consoleScroll.animateScrollTo(consoleScroll.maxValue)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = consoleFilter,
                            onValueChange = { consoleFilter = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Search console") }
                        )
                        TextButton(
                            onClick = {
                                if (visibleLogs.isNotEmpty()) {
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                        ClipData.newPlainText("MCSERVER console", visibleLogs.joinToString("\n"))
                                    )
                                    consoleMessage = "Copied ${visibleLogs.size} console lines."
                                }
                            },
                            enabled = visibleLogs.isNotEmpty()
                        ) { Text("COPY") }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${visibleLogs.size} of ${logs.size} lines", style = MaterialTheme.typography.labelLarge)
                        Text("Long-press a log line to select and copy it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { consoleTextSize = (consoleTextSize - 1f).coerceAtLeast(9f) }, enabled = consoleTextSize > 9f) { Text("A−") }
                                Text("${consoleTextSize.toInt()}", style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = { consoleTextSize = (consoleTextSize + 1f).coerceAtMost(18f) }, enabled = consoleTextSize < 18f) { Text("A+") }
                            }
                            FilterChip(selected = followConsole, onClick = { followConsole = !followConsole }, label = { Text("Auto-scroll") })
                        }
                    }
                    consoleMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        color = Color(0xFF090C10),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(consoleScroll).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (logs.isEmpty()) {
                                    Text("No server log output yet.", color = Color(0xFF7F8A96), style = MaterialTheme.typography.bodySmall)
                                } else if (visibleLogs.isEmpty()) {
                                    Text("No lines match this search.", color = Color(0xFF7F8A96), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    visibleLogs.forEach { line ->
                                        Text(
                                            line,
                                            color = Color(0xFFD7DEE7),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = consoleTextSize.sp, lineHeight = (consoleTextSize * 1.35f).sp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = manager.isRunning(),
                            label = { Text("Minecraft command") },
                            placeholder = { Text("say Hello") }
                        )
                        Button(
                            onClick = {
                                val value = command.trim()
                                if (value.isNotEmpty() && manager.isRunning()) {
                                    runCatching { manager.sendCommand(value.removePrefix("/")) }
                                    command = ""
                                }
                            },
                            enabled = command.isNotBlank() && manager.isRunning()
                        ) {
                            Text("SEND")
                        }
                    }
                }
                DashboardTab.PLAYERS -> {
                    Text(manager.playerSummary())
                    OutlinedTextField(value = playerName, onValueChange = { playerName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Player name") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { if (playerName.isNotBlank()) manager.whitelistAdd(playerName) }, enabled = manager.isRunning()) { Text("WHITELIST") }
                        TextButton(onClick = { if (playerName.isNotBlank()) manager.whitelistRemove(playerName) }, enabled = manager.isRunning()) { Text("UNWHITELIST") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { if (playerName.isNotBlank()) manager.kickPlayer(playerName) }, enabled = manager.isRunning()) { Text("KICK") }
                        TextButton(onClick = { if (playerName.isNotBlank()) manager.banPlayer(playerName) }, enabled = manager.isRunning()) { Text("BAN") }
                        TextButton(onClick = { if (playerName.isNotBlank()) manager.pardonPlayer(playerName) }, enabled = manager.isRunning()) { Text("PARDON") }
                    }
                }
                DashboardTab.FILES -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Server files", style = MaterialTheme.typography.titleMedium)
                            Text("Browse, edit text files, and manage uploads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { fileRefresh++ }, enabled = !fileBusy) { Text("REFRESH") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            filePath = filePath.substringBeforeLast('/', "")
                            fileRefresh++
                        }, enabled = filePath.isNotBlank()) { Text("UP ONE LEVEL") }
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                if (filePath.isBlank()) "/" else "/$filePath",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                    Button(onClick = { uploadTarget = filePath; filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !manager.isRunning() && !fileBusy) { Text("UPLOAD FILE") }
                    Button(onClick = { newFolderName = ""; newFolderDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = !manager.isRunning()) { Text("CREATE FOLDER") }
                    if (manager.isRunning()) {
                        Text("Stop the server to upload, rename, edit, or remove files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    fileMessage?.let {
                        Text(it, color = if (it.startsWith("Could not", true) || it.contains("failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(value = fileQuery, onValueChange = { fileQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Filter this folder") })
                    if (fileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val visibleFiles = fileEntries.filter { it.name.contains(fileQuery, ignoreCase = true) }
                    Text("${visibleFiles.size} ${if (visibleFiles.size == 1) "item" else "items"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (visibleFiles.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📁", style = MaterialTheme.typography.headlineMedium)
                                Text(if (fileQuery.isBlank()) "This folder is empty" else "No matching files", style = MaterialTheme.typography.titleSmall)
                                Text(if (fileQuery.isBlank()) "Upload a file or create a folder to get started." else "Try a different search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(visibleFiles, key = { it.relativePath }) { entry ->
                                FileEntryRow(
                                    entry = entry,
                                    enabled = !manager.isRunning() && !fileBusy,
                                    onOpen = { filePath = entry.relativePath; fileRefresh++ },
                                    onPreview = {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { manager.readServerText(entry.relativePath) }
                                            result.onSuccess { previewText = it; previewEditing = false; previewEntry = entry }
                                                .onFailure { fileMessage = "Could not preview ${entry.name}: ${it.message ?: "unknown error"}" }
                                        }
                                    },
                                    onDownload = { exportEntry = entry; fileExportPicker.launch(entry.name) },
                                    onRename = { renameEntry = entry; renameName = entry.name },
                                    onDelete = { deleteFileDialog = entry }
                                )
                            }
                        }
                    }
                }
                DashboardTab.WORLD -> {
                    Text("World generation & adjustments", style = MaterialTheme.typography.titleMedium)
                    Text("Changes below affect the next world generation. Stop the server before importing, exporting, generating, or resetting.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = worldSettings.seed,
                        onValueChange = { worldSettings = worldSettings.copy(seed = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Seed (blank = random)") }
                    )
                    Text("World type", style = MaterialTheme.typography.labelLarge)
                    var levelTypeMenu by remember { mutableStateOf(false) }
                    Button(onClick = { levelTypeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(worldSettings.levelType) }
                    DropdownMenu(expanded = levelTypeMenu, onDismissRequest = { levelTypeMenu = false }) {
                        listOf("minecraft:normal", "minecraft:flat", "minecraft:amplified", "minecraft:large_biomes", "minecraft:single_biome_surface").forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { worldSettings = worldSettings.copy(levelType = value); levelTypeMenu = false })
                        }
                    }
                    Text("Difficulty", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("peaceful", "easy", "normal", "hard").forEach { value ->
                            FilterChip(selected = worldSettings.difficulty == value, onClick = { worldSettings = worldSettings.copy(difficulty = value) }, label = { Text(value) })
                        }
                    }
                    Text("Game mode", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("survival", "creative", "adventure", "spectator").forEach { value ->
                            FilterChip(selected = worldSettings.gamemode == value, onClick = { worldSettings = worldSettings.copy(gamemode = value) }, label = { Text(value) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = worldSettings.generateStructures, onCheckedChange = { worldSettings = worldSettings.copy(generateStructures = it) })
                        Text("Generate structures")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = worldSettings.pvp, onCheckedChange = { worldSettings = worldSettings.copy(pvp = it) })
                        Text("Player versus player")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = worldSettings.hardcore, onCheckedChange = { worldSettings = worldSettings.copy(hardcore = it) })
                        Text("Hardcore")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = worldSettings.viewDistance.toString(), onValueChange = { worldSettings = worldSettings.copy(viewDistance = it.toIntOrNull()?.coerceIn(2, 32) ?: worldSettings.viewDistance) }, modifier = Modifier.weight(1f), label = { Text("View") })
                        OutlinedTextField(value = worldSettings.simulationDistance.toString(), onValueChange = { worldSettings = worldSettings.copy(simulationDistance = it.toIntOrNull()?.coerceIn(2, 32) ?: worldSettings.simulationDistance) }, modifier = Modifier.weight(1f), label = { Text("Simulation") })
                    }
                    Button(onClick = {
                        scope.launch {
                            worldBusy = true
                            val result = withContext(Dispatchers.IO) { manager.applyWorldSettings(worldSettings) }
                            worldMessage = result.fold({ "World options saved." }, { "Could not save world options: ${it.message ?: "unknown error"}" })
                            worldBusy = false
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !worldBusy && !manager.isRunning()) { Text("SAVE WORLD OPTIONS") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { worldImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.weight(1f), enabled = !worldBusy && !manager.isRunning()) { Text("IMPORT ZIP") }
                        Button(onClick = { worldExportPicker.launch("${manager.config()?.worldName ?: "world"}.zip") }, modifier = Modifier.weight(1f), enabled = !worldBusy && !manager.isRunning()) { Text("EXPORT ZIP") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                worldBusy = true
                                val result = withContext(Dispatchers.IO) { manager.generateWorld(worldSettings) }
                                worldMessage = result.fold({ "New world generated. Start the server to finish generation." }, { "Generation failed: ${it.message ?: "unknown error"}" })
                                worldBusy = false
                            }
                        }, modifier = Modifier.weight(1f), enabled = !worldBusy && !manager.isRunning()) { Text("GENERATE") }
                        Button(onClick = {
                            scope.launch {
                                worldBusy = true
                                val result = withContext(Dispatchers.IO) { manager.resetWorld() }
                                worldMessage = result.fold({ "World reset." }, { "Reset failed: ${it.message ?: "unknown error"}" })
                                worldBusy = false
                            }
                        }, modifier = Modifier.weight(1f), enabled = !worldBusy && !manager.isRunning()) { Text("RESET") }
                    }
                    worldMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                }
                DashboardTab.PLUGINS -> {
                    val files = manager.pluginFiles()
                    Text("Plugins", style = MaterialTheme.typography.titleMedium)
                    Text("Paper plugins are JAR files placed in the plugins folder. Stop the server before changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { uploadTarget = "plugins"; filePicker.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*")) }, enabled = !manager.isRunning() && !fileBusy, modifier = Modifier.fillMaxWidth()) { Text("ADD PLUGIN JAR") }
                    Text("${files.size} installed", style = MaterialTheme.typography.labelLarge)
                    files.forEach { file ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${file.name} · ${formatFileSize(file.length())}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { deleteFileDialog = FileEntry(file.name, "plugins/${file.name}", false, file.length()) }, enabled = !manager.isRunning()) { Text("REMOVE") }
                        }
                    }
                }
                DashboardTab.MODS -> {
                    val files = manager.modFiles()
                    Text("Mods", style = MaterialTheme.typography.titleMedium)
                    Text("Mods work only with compatible Fabric, Forge, or NeoForge software. Stop the server before changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { uploadTarget = "mods"; filePicker.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*")) }, enabled = !manager.isRunning() && !fileBusy, modifier = Modifier.fillMaxWidth()) { Text("ADD MOD JAR") }
                    Text("${files.size} installed", style = MaterialTheme.typography.labelLarge)
                    files.forEach { file ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${file.name} · ${formatFileSize(file.length())}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { deleteFileDialog = FileEntry(file.name, "mods/${file.name}", false, file.length()) }, enabled = !manager.isRunning()) { Text("REMOVE") }
                        }
                    }
                }
                DashboardTab.SETTINGS -> {
                    val powerManager = context.getSystemService(PowerManager::class.java)
                    val batteryProtected = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || powerManager.isIgnoringBatteryOptimizations(context.packageName)
                    Text("Server settings", style = MaterialTheme.typography.titleMedium)
                    Text("Change the managed server even after setup. Software/version replacement is performed while the server is stopped.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = serverName, onValueChange = { serverName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Server name") })
                    Button(onClick = { versionMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Minecraft version: $serverVersion") }
                    DropdownMenu(expanded = versionMenuOpen, onDismissRequest = { versionMenuOpen = false }) {
                        minecraftVersions.take(100).forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate) }, onClick = { serverVersion = candidate; versionMenuOpen = false })
                        }
                    }
                    Text("Software", style = MaterialTheme.typography.labelLarge)
                    ServerType.entries.forEach { candidate ->
                        val supported = candidate != ServerType.SPIGOT && candidate != ServerType.BUKKIT
                        FilterChip(selected = serverType == candidate, onClick = { serverType = candidate }, enabled = supported, label = { Text(if (supported) candidate.displayName else "${candidate.displayName} (unavailable)") })
                    }
                    OutlinedTextField(value = serverRam, onValueChange = { serverRam = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("RAM (MB)") })
                    OutlinedTextField(value = serverWorld, onValueChange = { serverWorld = it }, modifier = Modifier.fillMaxWidth(), label = { Text("World name") })
                    Button(onClick = {
                        scope.launch {
                            savingSettings = true
                            settingsMessage = null
                            val updated = ServerConfig(
                                name = serverName.ifBlank { "My Server" },
                                version = serverVersion.ifBlank { manager.config()?.version ?: "1.21.10" },
                                type = serverType,
                                ramMb = (serverRam.toIntOrNull() ?: 2048).coerceAtLeast(512),
                                worldName = serverWorld.ifBlank { "world" },
                                maxPlayers = manager.config()?.maxPlayers ?: 20,
                                javaPort = manager.config()?.javaPort ?: 25565,
                                bedrockPort = manager.config()?.bedrockPort ?: 19132
                            )
                            val result = withContext(Dispatchers.IO) { manager.updateServerConfiguration(updated) }
                            settingsMessage = result.fold({ "Server settings saved and installation checked." }, { "Could not apply settings: ${it.message ?: "unknown error"}" })
                            savingSettings = false
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !savingSettings && !manager.isRunning()) { Text(if (savingSettings) "APPLYING..." else "SAVE SERVER SETTINGS") }
                    settingsMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    Text("Server processes are kept in a foreground service and protected from normal screen-off CPU suspension while hosting.", style = MaterialTheme.typography.bodySmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (batteryProtected) "BACKGROUND PROTECTION: ENABLED" else "BACKGROUND PROTECTION: NOT ENABLED", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (batteryProtected) "Android battery optimization is ignored for MCSERVER."
                                else "Disable battery optimization for MCSERVER to reduce the chance Android pauses the host in the background.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!batteryProtected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Button(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        })
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("DISABLE BATTERY OPTIMIZATION")
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = propertyKey, onValueChange = { propertyKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Property key") })
                    OutlinedTextField(value = propertyValue, onValueChange = { propertyValue = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Property value") })
                    Button(onClick = { runCatching { manager.setServerProperty(propertyKey.trim(), propertyValue) } }, modifier = Modifier.fillMaxWidth()) { Text("SAVE PROPERTY") }
                    Text("Current world: ${manager.config()?.worldName ?: "world"}")
                    Text("Current max players: ${manager.config()?.maxPlayers ?: 20}")
                    Text("Loaded properties: ${manager.serverProperties().size}")
                    manager.serverProperties().entries.take(20).forEach { (key, value) -> Text("$key=$value", style = MaterialTheme.typography.bodySmall) }
                    var showDeleteDialog by remember { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Danger zone", style = MaterialTheme.typography.titleMedium)
                            Text("Deleting removes this managed server, its world, files, logs, and backups. The app stays installed so you can create a new server immediately.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { showDeleteDialog = true }, enabled = !manager.isRunning(), modifier = Modifier.fillMaxWidth()) { Text("DELETE SERVER") }
                        }
                    }
                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text("Delete server?") },
                            text = { Text("This permanently removes the managed server directory, including its world, files, logs, and backups. You can then create a new server from the setup screen.") },
                            confirmButton = {
                                Button(onClick = {
                                    showDeleteDialog = false
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { manager.deleteServer() }
                                        if (result.isSuccess) onServerDeleted() else settingsMessage = result.exceptionOrNull()?.message ?: "Delete failed."
                                    }
                                }) { Text("DELETE") }
                            },
                            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("CANCEL") } }
                        )
                    }
                }
                DashboardTab.NETWORK -> {
                    val context = LocalContext.current
                    val tailscale = remember { TailscaleManager.get(context) }
                    val tailscaleStatus by tailscale.status.collectAsState()
                    val scope = rememberCoroutineScope()
                    var tailscaleBusy by remember { mutableStateOf(false) }
                    var showTailscale by remember { mutableStateOf(false) }

                    LaunchedEffect(tailscaleStatus.connected) {
                        if (tailscaleStatus.connected) showTailscale = true
                    }

                    val connectTailscale = {
                        if (!tailscaleBusy) {
                            tailscaleBusy = true
                            networkMessage = null
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { tailscale.connect() }
                                    networkMessage = result.exceptionOrNull()?.message
                                } finally {
                                    tailscaleBusy = false
                                }
                            }
                        }
                    }

                    val vpnPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == android.app.Activity.RESULT_OK) {
                            connectTailscale()
                        } else {
                            networkMessage = "VPN permission was not granted."
                        }
                    }

                    LaunchedEffect(tailscale, tailscaleBusy) {
                        if (!tailscaleBusy) {
                            while (true) {
                                withContext(Dispatchers.IO) { tailscale.refreshStatus() }
                                delay(2500)
                            }
                        }
                    }

                    val javaPort = manager.config()?.javaPort ?: 25565
                    val bedrockPort = manager.config()?.bedrockPort ?: 19132
                    val lanAddresses = remember(lanRefresh, refreshTick / 30) { localServerAddresses() }
                    val recentLogText = remember(refreshTick / 2) { manager.recentLogs(500).joinToString("\n") }
                    val playitClaimUrl = playitClaimLink.takeIf { it.isNotBlank() }
                        ?: manager.playitPendingClaimUrl()
                        ?: if (!manager.hasPlayitSecret()) {
                            Regex("https://playit\\.gg/claim/[A-Za-z0-9_-]+")
                                .findAll(recentLogText).lastOrNull()?.value
                        } else null
                    val playitPublicAddress = manager.playitJavaAddress()
                        ?: Regex("(?:[A-Za-z0-9.-]+\\.playit\\.gg|[A-Za-z0-9.-]+\\.tun\\.ply\\.gg|[A-Za-z0-9.-]+\\.gl\\.at\\.ply\\.gg|[A-Za-z0-9.-]+\\.gl\\.joinmc\\.link)(?::[0-9]{1,5})?")
                            .findAll(recentLogText).lastOrNull()?.value
                    Text("Connection options", style = MaterialTheme.typography.titleLarge)
                    Text("Choose the address that matches how your friends will connect.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    networkMessage?.takeIf { it.isNotBlank() }?.let {
                        val isError = listOf("Could not", "Unable", "VPN permission", "failed").any { prefix -> it.startsWith(prefix, ignoreCase = true) }
                        Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Same Wi-Fi · free", style = MaterialTheme.typography.titleMedium)
                            Text("Players must be on the same home network. These addresses are private and work only while the server is running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (lanAddresses.isEmpty()) {
                                Text("No local Wi-Fi address found. Connect the phone to Wi-Fi and tap refresh.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                lanAddresses.forEach { address ->
                                    Text("Java: $address:$javaPort", style = MaterialTheme.typography.bodyMedium)
                                    Text("Bedrock: $address:$bedrockPort (UDP)", style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Java address", "$address:$javaPort"))
                                            networkMessage = "Java address copied."
                                        }) { Text("COPY JAVA") }
                                        TextButton(onClick = {
                                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Bedrock address", address))
                                            networkMessage = "Bedrock address copied. Port: $bedrockPort"
                                        }) { Text("COPY BEDROCK IP") }
                                    }
                                }
                            }
                            TextButton(onClick = { lanRefresh++; networkMessage = "Local addresses refreshed." }) { Text("REFRESH ADDRESSES") }
                        }
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Playit · Public connections", style = MaterialTheme.typography.titleMedium)
                            Text("Playit agent ${manager.playitAgentVersion()} (preview). Java forwarding works across Forge, Fabric, NeoForge, Paper, Vanilla, and other server software. Geyser uses RakNet over UDP for Bedrock; NetherNet is off. Public Bedrock access needs a working Playit UDP tunnel, which may be limited by your Playit account or plan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("No manual tunnel entry is needed. Playit may require a one-time agent approval in your browser; free tunnel availability is controlled by Playit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            val savedJava = manager.playitJavaAddress() ?: playitPublicAddress
                            val savedBedrock = manager.playitBedrockAddress().orEmpty().ifBlank { playitBedrockAddress }
                            if (savedJava != null) {
                                Text("Java Edition", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(savedJava, style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Playit Java address", savedJava))
                                    playitMessage = "Playit Java address copied."
                                }, modifier = Modifier.fillMaxWidth()) { Text("COPY JAVA ADDRESS") }
                            }
                            if (savedBedrock.isNotBlank()) {
                                Text("Bedrock Edition · UDP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(savedBedrock, style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Playit Bedrock address", savedBedrock))
                                    playitMessage = "Playit Bedrock address copied."
                                }, modifier = Modifier.fillMaxWidth()) { Text("COPY BEDROCK ADDRESS") }
                            }

                            if (playitBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button(
                                onClick = {
                                    playitBusy = true
                                    playitMessage = null
                                    scope.launch {
                                        if (!manager.hasPlayitSecret() && manager.playitPendingClaimUrl() == null) {
                                            val result = withContext(Dispatchers.IO) { manager.beginPlayitClaim() }
                                            result.onSuccess { link ->
                                                playitClaimLink = link
                                                playitMessage = "Open the link and approve this Playit agent, then return here to finish setup."
                                            }.onFailure { playitMessage = "Could not start Playit setup: ${it.message ?: "unknown error"}" }
                                        } else {
                                            val result = withContext(Dispatchers.IO) {
                                                if (manager.hasPlayitSecret()) manager.setupPlayitPublicAccess()
                                                else manager.finishPlayitClaim()
                                            }
                                            result.onSuccess { setup ->
                                                playitClaimLink = ""
                                                playitBedrockAddress = setup.bedrockAddress.orEmpty()
                                                playitBedrockDraft = setup.bedrockAddress.orEmpty()
                                                context.getSharedPreferences("network_options", 0).edit().apply {
                                                    if (setup.bedrockAddress == null) remove("playit_bedrock_address")
                                                    else putString("playit_bedrock_address", setup.bedrockAddress)
                                                }.apply()
                                                playitMessage = buildList {
                                                    add(when {
                                                        setup.agentTunnelCount == 0 -> "Playit returned an address, but its agent loaded no tunnels. Public connections are not active yet."
                                                        setup.javaAddress != null && setup.bedrockAddress != null -> "Playit Java and Bedrock addresses are ready."
                                                        else -> "Playit Java address was assigned."
                                                    })
                                                    setup.note?.let(::add)
                                                    if (setup.restartRequired) add("Geyser forwarding settings were updated; restart the server to apply them.")
                                                }.joinToString(" ")
                                            }.onFailure { playitMessage = "Playit setup needs attention: ${it.message ?: "unknown error"}" }
                                        }
                                        playitBusy = false
                                    }
                                },
                                enabled = !playitBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(when {
                                    playitBusy -> "SETTING UP…"
                                    !manager.hasPlayitSecret() && manager.playitPendingClaimUrl() == null -> "CONNECT PLAYIT"
                                    !manager.hasPlayitSecret() -> "FINISH PLAYIT SETUP"
                                    else -> "AUTO SET UP / REFRESH CONNECTIONS"
                                })
                            }
                            if (playitClaimUrl != null) {
                                Text("Approve your agent once", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer { Text(playitClaimUrl, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Playit claim link", playitClaimUrl))
                                        playitMessage = "Playit approval link copied."
                                    }, modifier = Modifier.weight(1f)) { Text("COPY LINK") }
                                    Button(onClick = {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(playitClaimUrl))) }
                                            .onFailure { playitMessage = it.message ?: "Could not open the approval link." }
                                    }, modifier = Modifier.weight(1f)) { Text("OPEN LINK") }
                                }
                            }
                            playitMessage?.let {
                                val isError = it.startsWith("Could not", true) || it.startsWith("Playit setup needs", true) ||
                                    it.contains("HTTP 4", true) || it.contains("rejected", true) || it.contains("did not return", true) ||
                                    it.contains("not active yet", true)
                                Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            }
                            if (playitMessage?.contains("HTTP 401") == true) {
                                Text(
                                    "Playit did not accept this setup request. Reconnect the agent once, then retry; the saved key stays until the replacement is approved.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    onClick = {
                                        playitBusy = true
                                        scope.launch {
                                            val pending = manager.playitPendingClaimUrl()
                                            if (pending != null) {
                                                playitClaimLink = pending
                                                val result = withContext(Dispatchers.IO) { manager.finishPlayitClaim() }
                                                result.onSuccess { setup ->
                                                    playitClaimLink = ""
                                                    playitBedrockAddress = setup.bedrockAddress.orEmpty()
                                                    playitBedrockDraft = setup.bedrockAddress.orEmpty()
                                                    context.getSharedPreferences("network_options", 0).edit().apply {
                                                        if (setup.bedrockAddress == null) remove("playit_bedrock_address")
                                                        else putString("playit_bedrock_address", setup.bedrockAddress)
                                                    }.apply()
                                                    playitMessage = buildList {
                                                        add(when {
                                                            setup.agentTunnelCount == 0 -> "Playit returned an address, but its agent loaded no tunnels. Public connections are not active yet."
                                                            setup.javaAddress != null && setup.bedrockAddress != null -> "Playit Java and Bedrock addresses are ready."
                                                            else -> "Playit Java address was assigned."
                                                        })
                                                        setup.note?.let(::add)
                                                        if (setup.restartRequired) add("Geyser forwarding settings were updated; restart the server to apply them.")
                                                    }.joinToString(" ")
                                                }.onFailure { playitMessage = "Playit setup needs attention: ${it.message ?: "unknown error"}" }
                                            } else {
                                                val result = withContext(Dispatchers.IO) { manager.beginPlayitClaim() }
                                                result.onSuccess { link ->
                                                    playitClaimLink = link
                                                    playitMessage = "Open the link and approve this new Playit agent, then return here to finish setup."
                                                }.onFailure { playitMessage = "Could not start Playit setup: ${it.message ?: "unknown error"}" }
                                            }
                                            playitBusy = false
                                        }
                                    },
                                    enabled = !playitBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("CONNECT A NEW AGENT") }
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Optional private access · Tailscale", style = MaterialTheme.typography.titleMedium)
                            Text("Private access for players on your tailnet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showTailscale = !showTailscale }) { Text(if (showTailscale) "HIDE" else "SET UP") }
                    }

                    if (showTailscale) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    color = if (tailscaleStatus.connected) Color(0xFF123B2A) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.extraLarge
                                ) {
                                    Text(
                                        if (tailscaleStatus.connected) "CONNECTED" else tailscaleStatus.backendState.uppercase(),
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                        color = if (tailscaleStatus.connected) Color(0xFF7BE3AD) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                if (tailscaleBusy || tailscaleStatus.backendState == "Starting") {
                                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            Text(tailscaleStatus.message, style = MaterialTheme.typography.bodyMedium)
                            if (tailscaleStatus.connected) {
                                HorizontalDivider()
                                Text("Minecraft Java", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${tailscaleStatus.ip}:$javaPort",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("Bedrock: ${tailscaleStatus.ip}:$bedrockPort")
                                if (tailscaleStatus.dnsName.isNotBlank()) Text("Device: ${tailscaleStatus.dnsName}")
                                TextButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                        ClipData.newPlainText("Minecraft server address", "${tailscaleStatus.ip}:$javaPort")
                                    )
                                    networkMessage = "Java address copied."
                                }) { Text("Copy Java address") }
                                TextButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                        ClipData.newPlainText("Bedrock server address", tailscaleStatus.ip)
                                    )
                                    networkMessage = "Bedrock IP copied. Port: $bedrockPort"
                                }) { Text("Copy Bedrock IP") }
                            } else if (tailscaleStatus.authUrl.isNotBlank()) {
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tailscaleStatus.authUrl)))
                                        }.onFailure { networkMessage = it.message ?: "Unable to open the Tailscale sign-in page." }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Sign in to Tailscale") }
                            } else if (tailscaleStatus.backendState == "NeedsLogin") {
                                Text("Tailscale needs sign-in before it can connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(
                                    onClick = {
                                        val prepareIntent = android.net.VpnService.prepare(context)
                                        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
                                        else connectTailscale()
                                    },
                                    enabled = !tailscaleBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (tailscaleBusy) "CONNECTING…" else "CONTINUE TAILSCALE SETUP") }
                            } else if (tailscaleStatus.backendState == "Starting") {
                                Text("Waiting for Tailscale to start…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (!tailscaleStatus.connected && tailscaleStatus.authUrl.isBlank() &&
                        tailscaleStatus.backendState != "NeedsLogin" && tailscaleStatus.backendState != "Starting") {
                        Button(
                            onClick = {
                                val prepareIntent = android.net.VpnService.prepare(context)
                                if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
                                else connectTailscale()
                            },
                            enabled = !tailscaleBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (tailscaleBusy) "Connecting…" else "Connect Tailscale")
                        }
                    }

                    if (!tailscaleStatus.connected) {
                        Text(
                            "Players also need Tailscale installed and signed in to the same tailnet. This address is private; it is not a public Internet endpoint.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { tailscale.refreshStatus() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check connection")
                    }

                    TextButton(
                        onClick = {
                            tailscaleBusy = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { tailscale.disconnect() }
                                    networkMessage = result.exceptionOrNull()?.message
                                } finally {
                                    tailscaleBusy = false
                                }
                            }
                        },
                        enabled = !tailscaleBusy && (tailscaleStatus.connected || tailscaleStatus.backendState == "NeedsLogin" || tailscaleStatus.backendState == "Starting"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect")
                    }
                    }
                }
                DashboardTab.BACKUPS -> {
                    val backups = manager.listBackups()
                    Text("Backups", style = MaterialTheme.typography.titleMedium)
                    Text("Create a snapshot before risky changes. Restoring replaces server files and automatically snapshots the current state first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        scope.launch {
                            backupMessage = "Creating backup…"
                            val result = withContext(Dispatchers.IO) { runCatching { manager.createBackup().name } }
                            backupMessage = result.fold({ "Created $it" }, { "Backup failed: ${it.message ?: "unknown error"}" })
                        }
                    }, enabled = !manager.isRunning(), modifier = Modifier.fillMaxWidth()) { Text("CREATE BACKUP") }
                    backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    if (backups.isEmpty()) Text("No backups yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    backups.forEach { backup ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(backup.name, style = MaterialTheme.typography.titleSmall)
                                Text("${backup.listFiles()?.size ?: 0} top-level items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { backupAction = "restore" to backup.name }, enabled = !manager.isRunning()) { Text("RESTORE") }
                                    TextButton(onClick = { backupAction = "delete" to backup.name }, enabled = !manager.isRunning()) { Text("DELETE") }
                                }
                            }
                        }
                    }
                }
                DashboardTab.FIX -> {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    val requiredJava = manager.requiredJavaVersion()
                    var fixResult by remember { mutableStateOf<Result<String>?>(null) }
                    var fixing by remember { mutableStateOf(false) }
                    Text("Android Java runtime", style = MaterialTheme.typography.titleMedium)
                    Text("Minecraft ${manager.config()?.version ?: "unknown"} requires Java $requiredJava for this server.")
                    Text("Auto Setup downloads, verifies, installs, and validates the matching Android ARM64 runtime.")
                    Text(
                        fixResult?.fold(
                            onSuccess = { "Status: $it" },
                            onFailure = { "ERROR: ${it.message ?: "Java runtime check failed."}" }
                        ) ?: "Status: Ready to check"
                    )
                    Button(
                        onClick = {
                            if (fixing) return@Button
                            fixing = true
                            fixResult = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { manager.autoSetupJava() }
                                fixResult = result
                                fixing = false
                            }
                        },
                        enabled = !fixing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (fixing) "SETTING UP JAVA $requiredJava..." else "AUTO SETUP") }
                    fixResult?.onFailure { error ->
                        val details = error.stackTraceToString()
                        Text(details, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("MCSERVER error", details))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("COPY ERROR") }
                    }
                    fixResult?.onSuccess { output ->
                        Text(output, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (newFolderDialog) {
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("Create folder") },
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = {
                Button(onClick = {
                    newFolderDialog = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { manager.createServerFolder(if (filePath.isBlank()) newFolderName else "$filePath/$newFolderName") }
                        fileMessage = result.fold({ "Created folder $newFolderName" }, { "Could not create folder: ${it.message ?: "unknown error"}" })
                        fileRefresh++
                    }
                }, enabled = newFolderName.isNotBlank() && !manager.isRunning()) { Text("CREATE") }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text("CANCEL") } }
        )
    }

    deleteFileDialog?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteFileDialog = null },
            title = { Text(if (entry.isDirectory) "Delete folder?" else "Delete file?") },
            text = { Text(if (entry.isDirectory) "Delete ${entry.name} and everything inside it? This cannot be undone." else "Delete ${entry.name}? This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    deleteFileDialog = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { manager.deleteServerPath(entry.relativePath) }
                        fileMessage = result.fold({ "Deleted ${entry.name}" }, { "Delete failed: ${it.message ?: "unknown error"}" })
                        fileRefresh++
                    }
                }, enabled = !manager.isRunning()) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { deleteFileDialog = null }) { Text("CANCEL") } }
        )
    }

    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text("Rename item") },
            text = { OutlinedTextField(value = renameName, onValueChange = { renameName = it }, singleLine = true, label = { Text("New name") }) },
            confirmButton = {
                Button(onClick = {
                    val newName = renameName
                    renameEntry = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { manager.renameServerPath(entry.relativePath, newName) }
                        fileMessage = result.fold({ "Renamed to $newName" }, { "Rename failed: ${it.message ?: "unknown error"}" })
                        fileRefresh++
                    }
                }, enabled = renameName.isNotBlank() && !manager.isRunning()) { Text("RENAME") }
            },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text("CANCEL") } }
        )
    }

    previewEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { previewEntry = null },
            title = { Text(entry.name) },
            text = {
                if (previewEditing) {
                    OutlinedTextField(value = previewText, onValueChange = { previewText = it }, modifier = Modifier.fillMaxWidth().height(360.dp), label = { Text("Text contents") })
                } else {
                    androidx.compose.foundation.text.BasicTextField(
                        value = previewText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState()),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                    )
                }
            },
            confirmButton = {
                if (previewEditing) {
                    Button(onClick = {
                        val contents = previewText
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { manager.saveServerText(entry.relativePath, contents) }
                            fileMessage = result.fold({ "Saved ${entry.name}" }, { "Save failed: ${it.message ?: "unknown error"}" })
                            if (result.isSuccess) previewEntry = null
                        }
                    }, enabled = !manager.isRunning()) { Text("SAVE") }
                } else {
                    TextButton(onClick = { previewEditing = true }, enabled = !manager.isRunning()) { Text("EDIT") }
                }
            },
            dismissButton = { TextButton(onClick = { previewEntry = null }) { Text("CLOSE") } }
        )
    }

    backupAction?.let { (action, name) ->
        AlertDialog(
            onDismissRequest = { backupAction = null },
            title = { Text(if (action == "restore") "Restore backup?" else "Delete backup?") },
            text = { Text(if (action == "restore") "This replaces the current server files with $name. The current state will first be saved as a new backup." else "Permanently delete $name?") },
            confirmButton = {
                Button(onClick = {
                    val selectedAction = backupAction
                    backupAction = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            if (selectedAction?.first == "restore") manager.restoreBackup(name) else manager.deleteBackup(name)
                        }
                        backupMessage = result.fold({ if (selectedAction?.first == "restore") "Restored $name" else "Deleted $name" }, { "Backup action failed: ${it.message ?: "unknown error"}" })
                    }
                }, enabled = !manager.isRunning()) { Text(if (action == "restore") "RESTORE" else "DELETE") }
            },
            dismissButton = { TextButton(onClick = { backupAction = null }) { Text("CANCEL") } }
        )
    }
}
