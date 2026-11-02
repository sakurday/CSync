package io.csync.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val invitation = runCatching { PairingQr.parse(result.data?.getStringExtra(ScannerActivity.EXTRA_QR) ?: "") }.getOrNull()
            if (invitation == null) alert(msg("pair.qr_invalid")) else pairScanned(invitation)
        }
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> runOnUiThread {
        if (key == "language") I18n.invalidate()
        refresh()
    } }
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
            @Suppress("UNUSED_VARIABLE") val update = revision
            CompositionLocalProvider(LocalLayoutDirection provides if (I18n.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialExpressiveTheme(colorScheme = colors) { App() }
            }
        }
    }

    @Composable private fun App() {
        @Suppress("UNUSED_VARIABLE") val update = revision
        var page by rememberSaveable { mutableIntStateOf(0) }
        val scrollStates = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())
        val destinations = listOf("nav.sync", "nav.hosts", "nav.settings")
        val icons = listOf(R.drawable.ic_material_sync, R.drawable.ic_material_computer, R.drawable.ic_material_settings)
        BackHandler(enabled = page != 0) { page = 0 }
        val paired = settings.paired
        val enabled = settings.enabled
        val paused = settings.paused
        val state = settings.status
        val connected = enabled && !paused && settings.connected
        val shizukuRunning = Shizuku.pingBinder()
        val shizukuAuthorized = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (useRail) NavigationRail {
                    Spacer(Modifier.height(16.dp))
                    destinations.forEachIndexed { index, label ->
                        NavigationRailItem(selected = page == index, onClick = { page = index },
                            icon = { Icon(painterResource(icons[index]), null) }, label = { Text(tr(label)) })
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    topBar = { TopAppBar(title = { Text(if (page == 0) "CSync" else tr(destinations[page])) }) },
                    bottomBar = {
                        if (!useRail) NavigationBar {
                            destinations.forEachIndexed { index, label ->
                                NavigationBarItem(selected = page == index, onClick = { page = index },
                                    icon = { Icon(painterResource(icons[index]), null) }, label = { Text(tr(label)) })
                            }
                        }
                    },
                    floatingActionButton = {
                        if (page == 1) Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
                        ExtendedFloatingActionButton(
                            onClick = { if (!pairing) scanner.launch(Intent(this@MainActivity, ScannerActivity::class.java)) },
                            modifier = Modifier.semantics { contentDescription = tr("pair.scan_add") },
                            icon = { Icon(painterResource(R.drawable.ic_material_qr_code_scanner), null) },
                            text = { Text(tr("pair.scan_add")) }
                        )
                        ExtendedFloatingActionButton(
                            onClick = { selectedHost = null; dialog = "pair" },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.semantics { contentDescription = tr("hosts.manual") },
                            icon = { Icon(painterResource(R.drawable.ic_material_add), null) },
                            text = { Text(tr("hosts.manual")) }
                        )
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(scrollStates[page])
                            .padding(horizontal = if (useRail) 24.dp else 16.dp).padding(top = 8.dp, bottom = if (page == 1) 184.dp else 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (page == 0) {
                                if (!batteryExempt) SetupReminder(
                                    title = tr("background.battery_title"),
                                    description = tr("background.battery_hint"),
                                    action = tr("background.enable"),
                                    onClick = ::openBatterySettings
                                )
                                if (!shizukuAuthorized) SetupReminder(
                                    title = if (shizukuRunning) tr("shizuku.unauthorized") else tr("shizuku.not_started"),
                                    description = if (shizukuRunning) tr("shizuku.permission_hint") else tr("shizuku.start_hint"),
                                    action = if (shizukuRunning) tr("shizuku.authorize") else tr("shizuku.start"),
                                    onClick = ::authorize
                                )
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )) {
                                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(painterResource(if (connected) R.drawable.ic_material_check_circle else R.drawable.ic_material_sync), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(when { enabled && paused -> tr("sync.paused"); connected -> tr("sync.connected_computer"); enabled -> tr("sync.connecting"); else -> tr("sync.inactive") }, style = MaterialTheme.typography.headlineSmall)
                                        Text(when { connected -> tr("sync.connected_hint", "device" to (settings.serverName)); enabled && !paused -> state; paired -> tr("sync.paired_hint", "device" to (settings.serverName)); else -> tr("sync.pair_hint") }, style = MaterialTheme.typography.bodyMedium)
                                        if (connected) Text(tr("sync.connection_transport", "transport" to settings.transport), style = MaterialTheme.typography.bodyMedium)
                                        if (!paired) {
                                            Button(onClick = { page = 1 }) { Text(tr("hosts.add")) }
                                        } else {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { if (enabled) stopSync() else startSync() }) { Text(if (enabled) tr("sync.stop") else tr("sync.start")) }
                                                if (enabled) OutlinedButton(onClick = { startService(Intent(this@MainActivity, SyncService::class.java).setAction(SyncService.ACTION_PAUSE)) }) { Text(if (paused) tr("common.resume") else tr("common.pause")) }
                                            }
                                        }
                                    }
                                }
                            } else if (page == 1) {
                                HostList(
                                    profiles = settings.profiles,
                                    activeHostId = if (connected) settings.activeHostId else "",
                                    onEdit = { selectedHost = it; dialog = "edit" },
                                    onDelete = { selectedHost = it; dialog = "deleteHost" },
                                    onReorder = ::saveProfiles
                                )
                            } else {
                                SettingsContent(shizukuRunning, shizukuAuthorized, batteryExempt)
                            }
                        }
                    }
                }
            }
        }
        Dialogs()
    }

    @Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() }) }

    @Composable private fun SettingsContent(shizukuRunning: Boolean, shizukuAuthorized: Boolean, batteryExempt: Boolean) {
        @Suppress("UNUSED_VARIABLE") val update = revision
        SettingsGroup(tr("settings.general")) {
            val name = if (I18n.selected == "system") tr("language.system") else I18n.options.firstOrNull { it.first == I18n.selected }?.second ?: I18n.selected
            SettingsAction(tr("language.title"), name, onClick = { dialog = "language" })
        }
        SettingsGroup(tr("background.section")) {
            SettingsAction(tr("settings.shizuku"), when { shizukuAuthorized -> tr("shizuku.authorized"); shizukuRunning -> tr("shizuku.unauthorized"); else -> tr("shizuku.not_started") }, onClick = ::authorize)
            SettingsDivider()
            SettingsAction(tr("settings.battery"), if (batteryExempt) tr("background.battery_enabled") else tr("background.battery_title"), onClick = ::openBatterySettings)
            SettingsDivider()
            SettingsAction(tr("background.notification_settings"), onClick = { startActivity(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName)) })
            SettingsDivider()
            ListItem(headlineContent = { Text(tr("settings.privacy")) }, supportingContent = { Text(tr("background.privacy")) }, colors = settingsListColors())
        }
        SettingsGroup(tr("settings.about")) {
            SettingsAction(tr("about.title"), tr("about.version", "version" to BuildConfig.VERSION_NAME), onClick = { dialog = "about" })
        }
    }

    @Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }

    @Composable private fun settingsListColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

    @Composable private fun SettingsDivider() { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant) }

    @Composable private fun SettingsAction(title: String, summary: String? = null, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = { Icon(painterResource(R.drawable.ic_material_chevron_right), null) },
            modifier = Modifier.clickable(onClick = onClick).heightIn(min = 56.dp),
            colors = settingsListColors()
        )
    }

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
            .onFailure { alert(msg("background.settings_failed")) }
    }

    @Composable private fun Dialogs() {
        when (dialog) {
            "language" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(tr("language.choose")) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(tr("language.hint"))
                    (listOf("system" to tr("language.system")) + I18n.options).forEach { (locale, name) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = I18n.selected == locale, onClick = { I18n.selected = locale; refresh() })
                            TextButton(onClick = { I18n.selected = locale; refresh() }) { Text(name) }
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(tr("common.close")) } })
            "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(tr("about.title")) },
                text = { AboutContent() }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(tr("common.close")) } })
            "message" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("CSync") }, text = { Text(I18n.render(message)) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(tr("common.ok")) } })
            "pair", "edit" -> {
                val editing = if (dialog == "edit") selectedHost else null
                var host by rememberSaveable(editing?.id) { mutableStateOf(editing?.host ?: "") }
                var port by rememberSaveable(editing?.id) { mutableStateOf((editing?.port ?: settings.port).toString()) }
                AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (editing == null) tr("hosts.manual") else tr("hosts.edit")) }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (editing == null) tr("pair.instructions") else tr("hosts.edit_hint"))
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(tr("pair.computer_ip")) }, singleLine = true)
                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(tr("pair.port")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }, confirmButton = { TextButton(onClick = { if (editing == null) pair(host, port) else editHost(editing, host, port) }) { Text(if (editing == null) tr("pair.action") else tr("common.save")) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("common.cancel")) } })
            }
            "deleteHost" -> selectedHost?.let { profile ->
                AlertDialog(onDismissRequest = { dialog = null }, title = { Text(tr("hosts.delete_title")) },
                    text = { Text(tr("hosts.delete_hint", "device" to (profile.name), "host" to (profile.host))) },
                    confirmButton = { TextButton(onClick = { dialog = null; saveProfiles(settings.profiles.filterNot { it.id == profile.id }) }) { Text(tr("common.delete")) } },
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("common.cancel")) } })
            }

        }
        confirmation?.let { pending ->
            AlertDialog(onDismissRequest = { pending.complete(false); confirmation = null }, title = { Text(tr("pair.verify_title")) },
                text = { Text(tr("pair.verify_computer", "code" to (verificationCode))) },
                confirmButton = { TextButton(onClick = { pending.complete(true); confirmation = null }) { Text(tr("pair.continue")) } },
                dismissButton = { TextButton(onClick = { pending.complete(false); confirmation = null }) { Text(tr("common.cancel")) } })
        }
        if (pairing && confirmation == null) AlertDialog(onDismissRequest = {}, title = { Text(tr("pair.in_progress")) }, text = { Text(tr("pair.wait_computer")) }, confirmButton = {})
    }

    @Composable private fun AboutContent() {
        val libraries = remember { assets.open(if (BuildConfig.DEBUG) "open_source_libraries_debug.txt" else "open_source_libraries.txt").bufferedReader().use { it.readText() } }
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.app_icon), contentDescription = "CSync", modifier = Modifier.size(80.dp).align(Alignment.CenterHorizontally))
            Text(tr("app.description"), style = MaterialTheme.typography.bodyLarge)
            Text(tr("about.version", "version" to (BuildConfig.VERSION_NAME)), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            Text(tr("about.libraries"), style = MaterialTheme.typography.titleMedium)
            Text(libraries, style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun refresh() { revision++ }
    private fun alert(value: String) { if (!isFinishing) { message = value; dialog = "message" } }
    private fun startSync() {
        if (!settings.paired) { alert(msg("pair.required")); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        settings.enabled = true
        runCatching { startForegroundService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_RECONNECT)) }.onFailure { settings.enabled = false; alert(msg("background.start_failed")) }
        refresh()
    }
    private fun stopSync() { settings.enabled = false; stopService(Intent(this, SyncService::class.java)); settings.status = msg("sync.stopped"); refresh() }
    private fun authorize() {
        if (!Shizuku.pingBinder()) { alert(msg("shizuku.start_first")); packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) } }
        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1)
        else { if (settings.enabled) startSync(); alert(msg("shizuku.authorized")) }
    }
    private fun pair(hostValue: String, portValue: String) {
        if (pairing) return
        val host = hostValue.trim()
        val number = portValue.toIntOrNull()
        if (host.isEmpty() || host.length > 253 || host.any(Char::isWhitespace) || host.contains('/') || number == null || number !in 1..65535) { alert(msg("hosts.invalid_endpoint")); return }
        dialog = null
        pairing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                Transport.pair(host, number, DeviceIdentity(), settings.deviceName, ::confirmPairing)
            } }
            pairing = false
            result.onSuccess { (pin, name) -> settings.savePair(host, number, pin, name); refresh(); startSync() }.onFailure { alert(localizedFailure(it, "pair.failed")) }
        }
    }
    private fun pairScanned(invitation: PairingQr) {
        if (pairing) return
        dialog = null
        pairing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                Transport.pairQr(invitation, DeviceIdentity(), settings.deviceName, ::confirmPairing)
            } }
            pairing = false
            result.onSuccess { host ->
                settings.savePair(host.host, invitation.port, host.pin, host.name)
                refresh(); startSync()
            }.onFailure { alert(localizedFailure(it, "pair.failed")) }
        }
    }
    private fun confirmPairing(code: String): Boolean = runBlocking {
        withContext(Dispatchers.Main) {
            verificationCode = code
            val pending = CompletableDeferred<Boolean>()
            confirmation = pending
            try { withTimeout(55_000) { pending.await() } } finally { confirmation = null }
        }
    }
    private fun saveProfiles(profiles: List<HostProfile>) {
        runCatching { settings.updateProfiles(profiles) }.onSuccess {
            if (profiles.isEmpty()) stopSync() else if (settings.enabled) startSync()
            refresh()
        }.onFailure { alert(localizedFailure(it, "hosts.save_failed")) }
    }
    private fun editHost(profile: HostProfile, hostValue: String, portValue: String) {
        val host = hostValue.trim()
        val port = portValue.toIntOrNull()
        if (host.isEmpty() || host.length > 253 || host.any(Char::isWhitespace) || host.contains('/') || port == null || port !in 1..65535) { alert(msg("hosts.invalid_address")); return }
        val replacement = profile.copy(host = host, port = port)
        if (settings.profiles.any { it.id != profile.id && it.id == replacement.id }) { alert(msg("hosts.duplicate")); return }
        dialog = null
        saveProfiles(settings.profiles.map { if (it.id == profile.id) replacement else it })
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
