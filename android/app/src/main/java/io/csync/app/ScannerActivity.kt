package io.csync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only this non-exported activity owns the camera; no images leave memory. */
@OptIn(ExperimentalMaterial3Api::class)
class ScannerActivity : ComponentActivity() {
    private var permitted by mutableStateOf(false)
    private var errorKey by mutableStateOf<String?>(null)
    private val completed = AtomicBoolean(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (!granted) errorKey = "pair.camera_permission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permitted && savedInstanceState == null) permission.launch(Manifest.permission.CAMERA)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Scaffold(topBar = { TopAppBar(title = { Text(tr("pair.scan_add")) }, actions = {
                    TextButton(onClick = { finish() }) { Text(tr("common.cancel")) }
                }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(tr("pair.scan_hint"))
                        if (permitted) CameraPreview(Modifier.fillMaxWidth().weight(1f))
                        errorKey?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
                        if (!permitted) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                            Text(tr("pair.camera_allow"))
                        }
                    }
                }
            }
        }
    }

    @Composable private fun CameraPreview(modifier: Modifier) {
        val view = remember { PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
        AndroidView(factory = { view }, modifier = modifier)
        DisposableEffect(view) {
            val executor = Executors.newSingleThreadExecutor()
            val active = AtomicBoolean(true)
            val providerFuture = ProcessCameraProvider.getInstance(this@ScannerActivity)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { frame ->
                try {
                    if (!active.get() || completed.get()) return@setAnalyzer
                    // Copy the Y plane with row/pixel strides; CameraX may pad rows.
                    val plane = frame.planes[0]
                    val buffer = plane.buffer
                    val start = buffer.position()
                    val width = frame.width
                    val height = frame.height
                    val pixels = ByteArray(width * height)
                    for (y in 0 until height) for (x in 0 until width) {
                        pixels[y * width + x] = buffer.get(start + y * plane.rowStride + x * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                    val raw = runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                    if (raw != null) {
                        val valid = runCatching { PairingQr.parse(raw) }.isSuccess
                        runOnUiThread {
                            if (active.get() && !isFinishing) {
                                if (valid && completed.compareAndSet(false, true)) {
                                    analysis.clearAnalyzer()
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_QR, raw))
                                    finish()
                                } else if (!valid) errorKey = "pair.qr_invalid"
                            }
                        }
                    }
                } finally { frame.close() }
            }
            providerFuture.addListener({
                if (active.get()) runCatching {
                    providerFuture.get().bindToLifecycle(this@ScannerActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { errorKey = "pair.camera_failed" }
            }, ContextCompat.getMainExecutor(this@ScannerActivity))
            onDispose {
                active.set(false)
                analysis.clearAnalyzer()
                if (providerFuture.isDone) runCatching { providerFuture.get().unbind(preview, analysis) }
                executor.shutdown()
            }
        }
    }

    companion object { const val EXTRA_QR = "pairingQr" }
}
