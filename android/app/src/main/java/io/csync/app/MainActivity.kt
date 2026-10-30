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
    private var hostsDraft by mutableStateOf("")
    private var portDraft by mutableStateOf("")
    private var dialog by mutableStateOf<String?>(null)
    private var message by mutableStateOf("")
    private var pairing by mutableStateOf(false)
    private var confirmation by mutableStateOf<CompletableDeferred<Boolean>?>(null)
    private var verificationCode by mutableStateOf("")
    private var bluetoothChoices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }
    private val binder = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread { if (result == PackageManager.PERMISSION_GRANTED && settings.enabled) startSync(); refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        hostsDraft = settings.hosts.joinToString(", ")
        portDraft = settings.port.toString()
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed)
        Shizuku.addBinderReceivedListenerSticky(binder)
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
        val shizuku = when {
            !Shizuku.pingBinder() -> "Shizuku 尚未启动"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku 尚未授权"
            else -> settings.shizukuStatus
        }
        val battery = if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "电池优化已豁免" else "建议检查电池与后台限制"
        Scaffold(
            topBar = { LargeTopAppBar(title = { Text(if (page == 0) "CSync" else "设置") }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Icon(painterResource(R.drawable.ic_material_sync), null) }, label = { Text("同步") })
                    NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Icon(painterResource(R.drawable.ic_material_settings), null) }, label = { Text("设置") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (page == 0) {
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
                            Text(shizuku, style = MaterialTheme.typography.bodyMedium)
                            Text(battery, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = ::authorize) { Text("授权 Shizuku") }
                        }
                    }
                } else {
                    SectionTitle("连接电脑")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("配对方式", style = MaterialTheme.typography.titleLarge)
                            Text("在电脑上开始配对，然后输入电脑显示的 IP 和端口。", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { dialog = "pair" }) { Text("输入 IP 配对") }
                            }
                            HorizontalDivider()
                            Text("局域网 / 公网地址", style = MaterialTheme.typography.titleMedium)
                            Text("多个 IP 或域名以逗号分隔，按顺序尝试。", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(value = hostsDraft, onValueChange = { hostsDraft = it }, label = { Text("电脑地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = portDraft, onValueChange = { portDraft = it }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            FilledTonalButton(onClick = ::saveEndpoint) { Text("保存地址并重连") }
                        }
                    }
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
                            Text(if (settings.bluetoothAddress.isBlank()) "尚未选择电脑" else "已选择：${settings.bluetoothAddress}", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = ::chooseBluetooth) { Text("选择已配对电脑") }
                        }
                    }
                    SectionTitle("后台与隐私")
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("后台剪贴板", style = MaterialTheme.typography.titleLarge)
                            Text("通过 Shizuku 读取剪贴板；手机重启后通常需要重新启动 Shizuku。", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = ::authorize) { Text("授权 Shizuku") }
                            TextButton(onClick = { startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("电池与后台设置") }
                            TextButton(onClick = { startActivity(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName)) }) { Text("系统通知设置") }
                            HorizontalDivider()
                            Text("仅同步新复制的文本，不保存历史。标记为敏感的内容会跳过。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                            TextButton(onClick = { dialog = "forget" }) { Text("解除电脑配对") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Dialogs()
    }

    @Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }

    @Composable private fun Dialogs() {
        when (dialog) {
            "message" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("CSync") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("确定") } })
            "pair" -> {
                var host by rememberSaveable { mutableStateOf("") }
                var port by rememberSaveable { mutableStateOf(settings.port.toString()) }
                AlertDialog(onDismissRequest = { dialog = null }, title = { Text("连接电脑") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("先在电脑上点击开始配对，再输入显示的 IP 地址和端口。")
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("电脑 IP") }, singleLine = true)
                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }, confirmButton = { TextButton(onClick = { pair(host, port) }) { Text("配对") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
            }
            "forget" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("解除配对？") }, text = { Text("本机停止同步；如需撤销电脑端的信任，请同时在电脑上撤销设备。") },
                confirmButton = { TextButton(onClick = { dialog = null; stopSync(); settings.forget(); hostsDraft = ""; refresh() }) { Text("解除") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
            "bluetooth" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("选择已配对电脑") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { bluetoothChoices.forEach { (name, address) -> TextButton(onClick = { settings.bluetoothAddress = address; dialog = null; refresh() }) { Text("$name\n$address") } } }
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
        if (settings.enabled) stopSync()
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
            result.onSuccess { (pin, name) -> settings.savePair(host, number, pin, name); hostsDraft = host; portDraft = number.toString(); refresh(); startSync() }.onFailure { alert(it.message ?: "配对失败，请检查电脑地址及配对状态") }
        }
    }
    private fun saveEndpoint() {
        val addresses = hostsDraft.split(',').map(String::trim).filter(String::isNotBlank)
        val number = portDraft.toIntOrNull()
        if (addresses.isEmpty() || addresses.size > 16 || addresses.any { it.length > 253 || it.any(Char::isWhitespace) || it.contains('/') } || number == null || number !in 1..65535) { alert("请填写有效 IP 或域名，以及 1–65535 的端口"); return }
        settings.hosts = addresses; settings.port = number
        if (settings.enabled) startSync() else alert("地址已保存")
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
    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binder); Shizuku.removeRequestPermissionResultListener(permission)
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed)
        confirmation?.complete(false); scope.cancel(); super.onDestroy()
    }
}
