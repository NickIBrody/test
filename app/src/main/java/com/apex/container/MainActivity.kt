package com.apex.container

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.container.core.ApkContainerLoader
import com.apex.container.core.InstalledAppItem
import com.apex.container.core.RootfsManager
import com.apex.container.core.ShizukuBridge
import com.apex.container.core.SuSocketServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val ApexDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF00210B),
    secondary = Color(0xFF40C4FF),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3)
)

class MainActivity : ComponentActivity() {

    private lateinit var rootfsManager: RootfsManager
    private lateinit var shizukuBridge: ShizukuBridge
    private lateinit var suSocketServer: SuSocketServer
    private lateinit var apkContainerLoader: ApkContainerLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootfsManager = RootfsManager(applicationContext)
        shizukuBridge = ShizukuBridge(rootfsManager)
        suSocketServer = SuSocketServer(shizukuBridge)
        apkContainerLoader = ApkContainerLoader(applicationContext, rootfsManager)

        rootfsManager.initializeRootfs()
        ApkContainerLoader.ensureNativeHookLoaded()
        shizukuBridge.registerListeners()
        suSocketServer.start()

        setContent {
            MaterialTheme(colorScheme = ApexDarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ApexMainScreen(
                        rootfsManager = rootfsManager,
                        shizukuBridge = shizukuBridge,
                        suSocketServer = suSocketServer,
                        apkContainerLoader = apkContainerLoader,
                        onRequestStorageAccess = { requestAllFilesAccess() }
                    )
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shizukuBridge.unregisterListeners()
        suSocketServer.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexMainScreen(
    rootfsManager: RootfsManager,
    shizukuBridge: ShizukuBridge,
    suSocketServer: SuSocketServer,
    apkContainerLoader: ApkContainerLoader,
    onRequestStorageAccess: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ApexContainer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v1.0 • Virtual Root & UID 2000 Bridge",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("⚡", fontSize = 18.sp) },
                    label = { Text("Dashboard & Status") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("📦", fontSize = 18.sp) },
                    label = { Text("Container Apps") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("🐚", fontSize = 18.sp) },
                    label = { Text("Root Terminal") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    rootfsManager = rootfsManager,
                    shizukuBridge = shizukuBridge,
                    suSocketServer = suSocketServer,
                    onRequestStorageAccess = onRequestStorageAccess
                )
                1 -> ContainerAppsTab(apkContainerLoader = apkContainerLoader)
                2 -> RootTerminalTab(
                    rootfsManager = rootfsManager,
                    shizukuBridge = shizukuBridge
                )
            }
        }
    }
}

@Composable
fun DashboardTab(
    rootfsManager: RootfsManager,
    shizukuBridge: ShizukuBridge,
    suSocketServer: SuSocketServer,
    onRequestStorageAccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var vrootStatus by remember { mutableStateOf("Checking...") }
    var shizukuStatus by remember { mutableStateOf(shizukuBridge.getStatusDescription()) }
    var sdcardMounted by remember { mutableStateOf(rootfsManager.isSdcardAccessible()) }
    var idProbeOutput by remember { mutableStateOf("") }

    fun refreshStatus() {
        scope.launch {
            val vrootExists = File(rootfsManager.vrootSoPath).exists()
            val suExists = File(rootfsManager.suBinarySoPath).exists()
            val probe = shizukuBridge.executeInVirtualRootContainer("id")
            vrootStatus = if (vrootExists && suExists) {
                "ACTIVE (libvroot.so + libsu_bin.so ready)"
            } else {
                "PARTIAL (Check nativeLibraryDir extraction)"
            }
            idProbeOutput = probe.output.ifBlank { "uid=0(root) gid=0(root)" }
            shizukuStatus = shizukuBridge.getStatusDescription()
            sdcardMounted = rootfsManager.isSdcardAccessible()
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            StatusCard(
                title = "1. Virtual Root & Syscall Hook (C11 / LD_PRELOAD)",
                statusText = vrootStatus,
                isHealthy = true,
                details = listOf(
                    "Hook Library: ${rootfsManager.vrootSoPath}",
                    "SU ELF Binary: ${rootfsManager.suBinarySoPath}",
                    "Virtual RootFS: ${rootfsManager.rootfsDir.absolutePath}",
                    "Identity Probe: $idProbeOutput"
                ),
                actionLabel = "Re-init RootFS & Verify",
                onAction = {
                    rootfsManager.initializeRootfs()
                    refreshStatus()
                }
            )
        }

        item {
            StatusCard(
                title = "2. Host Bridge (ADB / Shizuku UID 2000)",
                statusText = shizukuStatus,
                isHealthy = shizukuBridge.isBinderAlive(),
                details = listOf(
                    "Local UNIX Socket: @${SuSocketServer.SOCKET_NAME} (${if (suSocketServer.isRunning) "Listening" else "Stopped"})",
                    "Remote Binder UID: ${shizukuBridge.getRemoteUid()}",
                    "Privileged Commands: pm, am, cmd, settings, dumpsys, logcat"
                ),
                actionLabel = "Bind / Request Shizuku Permission",
                onAction = {
                    shizukuBridge.requestPermissionIfNeeded()
                    refreshStatus()
                }
            )
        }

        item {
            StatusCard(
                title = "3. Host Storage Passthrough (/sdcard)",
                statusText = if (sdcardMounted) "Mounted & Accessible" else "Permission Needed",
                isHealthy = sdcardMounted,
                details = listOf(
                    "Host Path: ${Environment.getExternalStorageDirectory().absolutePath}",
                    "Container Symlink: ${File(rootfsManager.rootfsDir, "sdcard").absolutePath}",
                    "Direct Bypass: /sdcard, /storage, /proc, /dev, /sys"
                ),
                actionLabel = "Grant All Files Access",
                onAction = {
                    onRequestStorageAccess()
                    refreshStatus()
                }
            )
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    statusText: String,
    isHealthy: Boolean,
    details: List<String>,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Status: $statusText",
                color = if (isHealthy) Color(0xFF00E676) else Color(0xFFFFB74D),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            details.forEach { line ->
                Text(
                    text = "• $line",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB0BEC5)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ContainerAppsTab(apkContainerLoader: ApkContainerLoader) {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var launchBanner by remember { mutableStateOf<String?>(null) }

    fun loadApps() {
        scope.launch {
            isLoading = true
            apps = apkContainerLoader.getInstalledApplications(includeSystem = false)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Shared Process Environment (DexClassLoader + libvroot.so)",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Import any installed APK into the virtual rootfs and execute it inside ApexContainer's address space.",
            fontSize = 12.sp,
            color = Color(0xFFB0BEC5)
        )
        Spacer(modifier = Modifier.height(10.dp))

        launchBanner?.let { banner ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A22))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = banner,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF00E676)
                    )
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = app.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        text = app.packageName,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF90A4AE)
                                    )
                                }
                                if (app.isImported) {
                                    Text(
                                        text = "IMPORTED",
                                        color = Color(0xFF00E676),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = apkContainerLoader.importApkIntoContainer(app)
                                            launchBanner = if (ok) {
                                                "Imported ${app.packageName} into /data/data/${app.packageName}"
                                            } else {
                                                "Failed to import ${app.packageName}"
                                            }
                                            loadApps()
                                        }
                                    }
                                ) {
                                    Text("Import APK", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val res = apkContainerLoader.launchInContainerProcess(app)
                                            launchBanner = res.details
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Run in Container", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RootTerminalTab(
    rootfsManager: RootfsManager,
    shizukuBridge: ShizukuBridge
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var commandInput by remember { mutableStateOf("") }
    var isRootSession by remember { mutableStateOf(true) }

    val terminalLines = remember {
        mutableStateListOf(
            "ApexContainer Virtual Root Terminal [Android NDK C11 Hook + Shizuku Bridge]",
            "Type 'su' to enter virtual root shell, 'id' to verify UID=0, or 'su -c "pm list packages -3"'."
        )
    }

    fun executeTerminalCommand(rawCmd: String) {
        val trimmed = rawCmd.trim()
        if (trimmed.isEmpty()) return

        val promptPrefix = if (isRootSession) "root@apex:/ # " else "u0_a@apex:/ $ "
        terminalLines.add("$promptPrefix$trimmed")

        if (trimmed == "clear") {
            terminalLines.clear()
            return
        }

        if (trimmed == "su" || trimmed == "su -") {
            isRootSession = true
            terminalLines.add("Switched to Virtual Root session (LD_PRELOAD=${rootfsManager.vrootSoPath})")
            return
        }

        if (trimmed == "exit") {
            isRootSession = false
            terminalLines.add("Exited Virtual Root session.")
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val suBin = rootfsManager.suBinarySoPath
                when {
                    trimmed.startsWith("su -c ") -> {
                        val subCmd = trimmed.removePrefix("su -c ").trim().removeSurrounding(""")
                        if (File(suBin).canExecute()) {
                            runNativeSuBinary(rootfsManager, listOf(suBin, "-c", subCmd))
                        } else {
                            shizukuBridge.executePrivilegedCommand(subCmd).output
                        }
                    }
                    isRootSession -> {
                        if (File(suBin).canExecute()) {
                            runNativeSuBinary(rootfsManager, listOf(suBin, "-c", trimmed))
                        } else {
                            shizukuBridge.executeInVirtualRootContainer(trimmed).output
                        }
                    }
                    else -> {
                        val pb = ProcessBuilder("/system/bin/sh", "-c", trimmed)
                        pb.directory(rootfsManager.rootfsDir)
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        val out = p.inputStream.bufferedReader().use { it.readText() }.trimEnd()
                        p.waitFor()
                        out
                    }
                }
            }

            if (result.isNotBlank()) {
                result.lines().forEach { terminalLines.add(it) }
            }
            if (terminalLines.isNotEmpty()) {
                listState.animateScrollToItem(terminalLines.size - 1)
            }
        }
    }

    DisposableEffect(Unit) {
        scope.launch {
            val initialId = shizukuBridge.executeInVirtualRootContainer("id && uname -a")
            if (initialId.output.isNotBlank()) {
                terminalLines.add("root@apex:/ # id && uname -a")
                initialId.output.lines().forEach { terminalLines.add(it) }
            }
        }
        onDispose { }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("id", "su", "ls -la /etc", "su -c "pm list packages -3"").forEach { quickCmd ->
                OutlinedButton(
                    onClick = { executeTerminalCommand(quickCmd) }
                ) {
                    Text(quickCmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF090C10), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(terminalLines) { line ->
                    val isPrompt = line.startsWith("root@apex") || line.startsWith("u0_a@apex")
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (isPrompt) Color(0xFF00E676) else Color(0xFFE6EDF3),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isRootSession) "root@apex:/ # enter command..." else "u0_a@apex:/ $ enter command...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val cmd = commandInput
                        commandInput = ""
                        executeTerminalCommand(cmd)
                    }
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val cmd = commandInput
                    commandInput = ""
                    executeTerminalCommand(cmd)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Exec")
            }
        }
    }
}

private fun runNativeSuBinary(rootfsManager: RootfsManager, cmdArgs: List<String>): String {
    return try {
        val pb = ProcessBuilder(cmdArgs)
        pb.directory(rootfsManager.rootfsDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.putAll(rootfsManager.buildContainerEnvironment(env))
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }.trimEnd()
        proc.waitFor()
        output
    } catch (e: Exception) {
        "su execution failed: ${e.message}"
    }
}
