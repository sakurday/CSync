package io.csync.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private var revision by mutableIntStateOf(0)
    private var selectedHost by mutableStateOf<HostProfile?>(null)
    private var dialog by mutableStateOf<String?>(null)
    private var message by mutableStateOf("")
    private var pairing by mutableStateOf(false)
    private var confirmation by mutableStateOf<CompletableDeferred<Boolean>?>(null)
    private var verificationCode by mutableStateOf("")
    private var bluetoothChoices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }
    private val binder = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread { if (result == PackageManager.PERMISSION_GRANTED && settings.enabled) startSync(); refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed)
        Shizuku.addBinderReceivedListenerSticky(binder)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permission)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) darkColorScheme() else lightColorScheme()
            SideEffect {
                window.statusBarColor = colors.surface.toArgb()
                window.navigationBarColor = colors.surface.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialExpressiveTheme(colorScheme = colors) { App() }
        }
    }

    @Composable private fun App() {
        @Suppress("UNUSED_VARIABLE") val update = revision
        var page by rememberSaveable { mutableIntStateOf(0) }
        val paired = settings.paired
        val enabled = settings.enabled
        val paused = settings.paused
        val state = settings.status
        val connected = enabled && !paused && state.startsWith("已连接")
        val shizukuRunning = Shizuku.pingBinder()
        val shizukuAuthorized = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (page == 0) "CSync" else "设置") }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Icon(painterResource(R.drawable.ic_material_sync), null) }, label = { Text("同步") })
                    NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Icon(painterResource(R.drawable.ic_material_settings), null) }, label = { Text("设置") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (page == 0) {
                    if (!batteryExempt) SetupReminder(
                        title = "未开启后台保活",
                        description = "允许 CSync 忽略电池优化，减少后台同步中断。",
                        action = "去开启",
                        onClick = ::openBatterySettings
                    )
                    if (!shizukuAuthorized) SetupReminder(
                        title = if (shizukuRunning) "未授权 Shizuku" else "Shizuku 未启动",
                        description = if (shizukuRunning) "授权后才能在后台读取剪贴板。" else "请先启动 Shizuku，再返回完成授权。",
                        action = if (shizukuRunning) "去授权" else "去启动",
                        onClick = ::authorize
                    )
                    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(if (connected) R.drawable.ic_material_check_circle else R.drawable.ic_material_sync), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(when { enabled && paused -> "同步已暂停"; connected -> "已连接电脑"; enabled -> "正在连接"; else -> "未同步" }, style = MaterialTheme.typography.headlineSmall)
                            Text(when { connected -> "${settings.serverName} · 剪贴板将保持同步"; enabled -> state; paired -> "已配对 ${settings.serverName}，启动后即可同步"; else -> "连接电脑，随时同步剪贴板" }, style = MaterialTheme.typography.bodyMedium)
                            if (!paired) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { dialog = "pair" }) { Text("输入 IP 配对") }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { if (enabled) stopSync() else startSync() }) { Text(if (enabled) "停止同步" else "启动同步") }
                                    if (enabled) OutlinedButton(onClick = { startService(Intent(this@MainActivity, SyncService::class.java).setAction(SyncService.ACTION_PAUSE)) }) { Text(if (paused) "恢复" else "暂停") }
                                }
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("当前状态", style = MaterialTheme.typography.titleLarge)
                            Text(if (connected) "连接方式：${state.removePrefix("已连接 · ")}" else "连接状态：${if (enabled) state else "未运行"}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    HostList(
                        profiles = settings.profiles,
                        activeHostId = if (connected) settings.activeHostId else "",
                        onAdd = { selectedHost = null; dialog = "pair" },
                        onEdit = { selectedHost = it; dialog = "edit" },
                        onDelete = { selectedHost = it; dialog = "deleteHost" },
                        onReorder = ::saveProfiles
                    )
                    SectionTitle("备用连接")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("使用蓝牙", style = MaterialTheme.typography.titleMedium)
                                Switch(checked = settings.bluetoothEnabled, onCheckedChange = { enabledBluetooth ->
                                    settings.bluetoothEnabled = enabledBluetooth
                                    if (enabledBluetooth && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 12)
                                })
                            }
                            Text("网络不可用时尝试。请先在手机和电脑的系统设置中配对。", style = MaterialTheme.typography.bodyMedium)
                            Text("已为 ${settings.profiles.count { it.bluetoothAddress.isNotBlank() }} 台主机设置备用蓝牙", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { dialog = "bluetoothHost" }) { Text("选择已配对电脑") }
                        }
                    }
                    SectionTitle("后台与隐私")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { startActivity(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName)) }) { Text("系统通知设置") }
                            HorizontalDivider()
                            Text("仅同步新复制的文本，不保存历史。标记为敏感的内容会跳过。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { dialog = "about" }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Text("关于 CSync")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Dialogs()
    }

    @Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }

    @Composable private fun SetupReminder(title: String, description: String, action: String, onClick: () -> Unit) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_material_error), contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onClick) { Text(action) }
                }
            }
        }
    }

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .recoverCatching { startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            .onFailure { alert("无法打开系统设置，请在系统设置中允许 CSync 后台运行。") }
    }

    @Composable private fun Dialogs() {
        when (dialog) {
            "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("关于 CSync") },
                text = { AboutContent() }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
            "message" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("CSync") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("确定") } })
            "pair", "edit" -> {
                val editing = if (dialog == "edit") selectedHost else null
                var host by rememberSaveable(editing?.id) { mutableStateOf(editing?.host ?: "") }
                var port by rememberSaveable(editing?.id) { mutableStateOf((editing?.port ?: settings.port).toString()) }
                AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (editing == null) "添加主机" else "编辑主机地址") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (editing == null) "先在电脑上点击开始配对，再输入显示的 IP 地址和端口。" else "更换同一台电脑的连接地址，配对身份保持不变。")
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("电脑 IP") }, singleLine = true)
                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }, confirmButton = { TextButton(onClick = { if (editing == null) pair(host, port) else editHost(editing, host, port) }) { Text(if (editing == null) "配对" else "保存") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
            }
            "deleteHost" -> selectedHost?.let { profile ->
                AlertDialog(onDismissRequest = { dialog = null }, title = { Text("删除主机？") },
                    text = { Text("删除 ${profile.name}（${profile.host}）。如需撤销电脑端的信任，请同时在电脑上撤销设备。") },
                    confirmButton = { TextButton(onClick = { dialog = null; saveProfiles(settings.profiles.filterNot { it.id == profile.id }) }) { Text("删除") } },
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
            }
            "bluetoothHost" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("为哪台主机设置蓝牙？") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (settings.profiles.isEmpty()) Text("请先添加主机。")
                    settings.profiles.forEach { profile -> TextButton(onClick = { selectedHost = profile; chooseBluetooth() }) { Text("${profile.name} · ${profile.host}") } }
                } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
            "bluetooth" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("选择已配对电脑") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { bluetoothChoices.forEach { (name, address) -> TextButton(onClick = { selectedHost?.let { selected -> saveProfiles(settings.profiles.map { if (it.id == selected.id) it.copy(bluetoothAddress = address) else it }) }; dialog = null }) { Text("$name\n$address") } } }
            }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        }
        confirmation?.let { pending ->
            AlertDialog(onDismissRequest = { pending.complete(false); confirmation = null }, title = { Text("核对校验码") },
                text = { Text("请确认电脑显示相同的校验码：\n\n$verificationCode") },
                confirmButton = { TextButton(onClick = { pending.complete(true); confirmation = null }) { Text("一致，继续") } },
                dismissButton = { TextButton(onClick = { pending.complete(false); confirmation = null }) { Text("取消") } })
        }
        if (pairing && confirmation == null) AlertDialog(onDismissRequest = {}, title = { Text("正在配对") }, text = { Text("请在电脑端确认这台手机，最多等待 60 秒。") }, confirmButton = {})
    }

    @Composable private fun AboutContent() {
        val libraries = remember { assets.open(if (BuildConfig.DEBUG) "open_source_libraries_debug.txt" else "open_source_libraries.txt").bufferedReader().use { it.readText() } }
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.app_icon), contentDescription = "CSync", modifier = Modifier.size(80.dp).align(Alignment.CenterHorizontally))
            Text("在手机和电脑间同步剪贴板", style = MaterialTheme.typography.bodyLarge)
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            Text("开源库", style = MaterialTheme.typography.titleMedium)
            Text(libraries, style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun refresh() { revision++ }
    private fun alert(value: String) { if (!isFinishing) { message = value; dialog = "message" } }
    private fun startSync() {
        if (!settings.paired) { alert("请先与电脑配对"); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        settings.enabled = true
        runCatching { startForegroundService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_RECONNECT)) }.onFailure { settings.enabled = false; alert("系统未允许启动后台同步，请重试") }
        refresh()
    }
    private fun stopSync() { settings.enabled = false; stopService(Intent(this, SyncService::class.java)); settings.status = "同步已停止"; refresh() }
    private fun authorize() {
        if (!Shizuku.pingBinder()) { alert("请先启动 Shizuku，再返回授权。"); packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) } }
        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1)
        else { if (settings.enabled) startSync(); alert("Shizuku 已授权") }
    }
    private fun pair(hostValue: String, portValue: String) {
        if (pairing) return
        val host = hostValue.trim()
        val number = portValue.toIntOrNull()
        if (host.isEmpty() || host.length > 253 || host.any(Char::isWhitespace) || host.contains('/') || number == null || number !in 1..65535) { alert("请填写有效的电脑 IP 和 1–65535 的端口"); return }
        dialog = null
        pairing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                Transport.pair(host, number, DeviceIdentity(), settings.deviceName) { code ->
                    runBlocking { withContext(Dispatchers.Main) {
                        verificationCode = code
                        val pending = CompletableDeferred<Boolean>()
                        confirmation = pending
                        try { withTimeout(55_000) { pending.await() } } finally { confirmation = null }
                    } }
                }
            } }
            pairing = false
            result.onSuccess { (pin, name) -> settings.savePair(host, number, pin, name); refresh(); startSync() }.onFailure { alert(it.message ?: "配对失败，请检查电脑地址及配对状态") }
        }
    }
    private fun saveProfiles(profiles: List<HostProfile>) {
        runCatching { settings.updateProfiles(profiles) }.onSuccess {
            if (profiles.isEmpty()) stopSync() else if (settings.enabled) startSync()
            refresh()
        }.onFailure { alert(it.message ?: "无法保存主机列表") }
    }
    private fun editHost(profile: HostProfile, hostValue: String, portValue: String) {
        val host = hostValue.trim()
        val port = portValue.toIntOrNull()
        if (host.isEmpty() || host.length > 253 || host.any(Char::isWhitespace) || host.contains('/') || port == null || port !in 1..65535) { alert("请填写有效地址和 1–65535 的端口"); return }
        val replacement = profile.copy(host = host, port = port)
        if (settings.profiles.any { it.id != profile.id && it.id == replacement.id }) { alert("该地址和端口已在主机列表中"); return }
        dialog = null
        saveProfiles(settings.profiles.map { if (it.id == profile.id) replacement else it })
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun chooseBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 12); return }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) { alert("请先开启系统蓝牙"); return }
        bluetoothChoices = adapter.bondedDevices.map { (it.name ?: "蓝牙设备") to it.address }
        if (bluetoothChoices.isEmpty()) { alert("尚无已配对设备，请在系统设置中先与电脑配对"); return }
        dialog = "bluetooth"
    }
    override fun onResume() { super.onResume(); if (::settings.isInitialized) refresh() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::settings.isInitialized) refresh()
    }
    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeBinderReceivedListener(binder); Shizuku.removeRequestPermissionResultListener(permission)
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed)
        confirmation?.complete(false); scope.cancel(); super.onDestroy()
    }
}
